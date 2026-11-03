/*
 * #%L
 * ZETA Testsuite
 * %%
 * (C) achelos GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.services;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.DirectWebSocketClient.Connection;
import java.io.EOFException;
import java.net.SocketException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

/**
 * Maintains a bounded pool of WebSocket connections with established STOMP sessions.
 */
@Slf4j
public class WebSocketLoadService implements AutoCloseable {

  private static final int MAX_OPEN_CONCURRENCY = 25;

  private final WebSocketClientFactory clientFactory;
  private final List<LoadConnection> sessions = Collections.synchronizedList(new ArrayList<>());
  private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
  private long setupDeadline;

  /**
   * Creates a load service with the default WebSocket client factory.
   */
  public WebSocketLoadService() {
    this(new WebSocketClientFactory());
  }

  /**
   * Creates a load service with a caller-provided client factory.
   *
   * @param clientFactory the factory used to open raw WebSocket connections
   */
  public WebSocketLoadService(WebSocketClientFactory clientFactory) {
    this.clientFactory = clientFactory;
  }

  /**
   * Opens WebSocket and STOMP consecutively per connection with at most 25 concurrent workers.
   *
   * <p>Existing connections are closed first so each call starts from a clean state.</p>
   *
   * @param url the target WebSocket URL, including Tiger placeholders if needed
   * @param connectionCount the number of connections to open
   * @throws AssertionError if the requested capacity is not reached or setup cannot complete
   */
  public void openStompConnections(String url, int connectionCount) {
    if (connectionCount <= 0) {
      throw new AssertionError("WebSocket connection count must be > 0");
    }

    close();
    int setupTimeout = TigerGlobalConfiguration.readIntegerOptional("websocket.loadSetupTimeoutSeconds").orElse(180);
    if (setupTimeout <= 0) {
      throw new IllegalArgumentException("websocket.loadSetupTimeoutSeconds must be > 0");
    }
    setupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(setupTimeout);

    var resolvedUrl = TigerGlobalConfiguration.resolvePlaceholders(url).trim();

    log.info("Opening {} WebSocket connections to {}", connectionCount, resolvedUrl);

    var executor = Executors.newFixedThreadPool(Math.min(MAX_OPEN_CONCURRENCY, connectionCount));
    boolean completed = false;
    try {
      int nextConnectionIndex = 1;
      while (getOpenConnectionCount() < connectionCount) {
        int missing = connectionCount - getOpenConnectionCount();
        log.info("Opening {} missing WebSocket connections for a pool of {}", missing, connectionCount);
        List<Future<Boolean>> attempts = new ArrayList<>(missing);
        for (int index = 0; index < missing; index++) {
          final int connectionIndex = nextConnectionIndex++;
          attempts.add(executor.submit(() -> openWithRetries(resolvedUrl, connectionIndex)));
        }
        if (!collectAttempts(attempts)) {
          throw new AssertionError("Expected " + connectionCount + " open WebSocket connections after setup but found "
              + getOpenConnectionCount() + ". Recent failures: " + getRecentFailures(10));
        }
      }
      completed = true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("STOMP pool setup interrupted", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new AssertionError("STOMP pool setup failed or exceeded its total budget", e);
    } finally {
      shutdownExecutor(executor);
      if (!completed) {
        closeConnections();
      }
    }

    log.info(
        "WebSocket load pool established: openConnections={}, failures={}",
        getOpenConnectionCount(),
        getFailureCount());
  }

  /**
   * Returns the number of transports whose STOMP sessions are still connected.
   *
   * @return the number of sessions that are still open
   */
  public int getOpenConnectionCount() {
    synchronized (sessions) {
      return (int) sessions.stream().filter(LoadConnection::isOpen).count();
    }
  }

  /**
   * Returns the number of connection attempts that failed during the last pool setup.
   *
   * @return the number of failed attempts
   */
  public int getFailureCount() {
    synchronized (failures) {
      return failures.size();
    }
  }

  /**
   * Returns up to {@code maxEntries} recent connection failure descriptions.
   *
   * @param maxEntries the maximum number of failure strings to include
   * @return a list of failure descriptions
   */
  public List<String> getRecentFailures(int maxEntries) {
    synchronized (failures) {
      return failures.stream().limit(Math.max(0, maxEntries)).toList();
    }
  }

  /**
   * Closes all open WebSocket sessions and clears collected failure state.
   */
  @Override
  public void close() {
    closeConnections();
    synchronized (failures) {
      failures.clear();
    }
  }

  /** Closes successful connections and any transport still owned by the factory. */
  private void closeConnections() {
    synchronized (sessions) {
      for (var session : sessions) {
        closeQuietly(session.transport());
      }
      sessions.clear();
    }
    clientFactory.close();
  }

  /**
   * Opens one connection, retrying transient failures within the original setup budget.
   *
   * @param resolvedUrl the already resolved WebSocket URL
   * @param connectionIndex the 1-based connection index for diagnostics
   * @return whether the connection was established; false indicates a non-retryable failure
   * @throws InterruptedException if a worker is interrupted while opening or waiting to retry
   */
  private boolean openWithRetries(String resolvedUrl, int connectionIndex) throws InterruptedException {
    for (int attempt = 1; System.nanoTime() < setupDeadline; attempt++) {
      Connection session = null;
      boolean retained = false;
      long started = System.nanoTime();
      String stage = "WebSocket upgrade";
      var protocolError = new AtomicReference<String>();
      try {
        session = awaitHandshake(clientFactory.createRawClient()
            .open(URI.create(resolvedUrl), WebSocketClientFactory.STOMP_PROTOCOLS));
        stage = "STOMP handshake";
        final var transport = session;
        var stomp = awaitHandshake(clientFactory.connectStomp(session, new StompSessionHandlerAdapter() {
          @Override
          public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
            return byte[].class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            protocolError.set(headers.toString());
            transport.close();
          }

          @Override
          public void handleTransportError(StompSession stompSession, Throwable error) {
            log.warn("STOMP load connection #{} transport failed: {}", connectionIndex, error.toString());
          }
        }));
        if (!session.isOpen() || !stomp.isConnected()) {
          throw new ConnectionLostException("Connection closed before pool registration");
        }
        sessions.add(new LoadConnection(session, stomp));
        retained = true;
        return true;
      } catch (ExecutionException | TimeoutException | RuntimeException e) {
        failures.add("connection #" + connectionIndex + " attempt " + attempt + " " + stage
            + " failed after " + Duration.ofNanos(System.nanoTime() - started).toMillis()
            + "ms: " + rootCauseMessage(e)
            + (protocolError.get() == null ? "" : "; STOMP ERROR=" + protocolError.get()));
        if (protocolError.get() != null || !isRetryable(e)) {
          return false;
        }
      } finally {
        if (!retained) {
          closeQuietly(session);
        }
      }
      long remaining = setupDeadline - System.nanoTime();
      if (remaining > 0) {
        TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)));
      }
    }
    return false;
  }

  /**
   * Limits a handshake by its own timeout and the remaining overall setup budget.
   *
   * @param handshake asynchronous handshake to await and cancel on failure
   * @param <T> handshake result type
   * @return completed handshake result
   * @throws InterruptedException if the worker is interrupted
   * @throws ExecutionException if the handshake failed
   * @throws TimeoutException if either time limit expires
   */
  private <T> T awaitHandshake(CompletableFuture<T> handshake)
      throws InterruptedException, ExecutionException, TimeoutException {
    try {
      return handshake.get(Math.max(0, Math.min(setupDeadline - System.nanoTime(),
          TimeUnit.SECONDS.toNanos(WebSocketConfiguration.loadConnectionTimeoutSeconds()))), TimeUnit.NANOSECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
      handshake.cancel(true);
      throw e;
    }
  }

  /**
   * Accepts only timeouts and identifiable socket failures for setup retries.
   *
   * @param error failed handshake cause
   * @return whether another bounded attempt may be made
   */
  private boolean isRetryable(Throwable error) {
    var cause = error;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException
        || cause instanceof SocketException || cause instanceof EOFException
        || cause instanceof ConnectionLostException;
  }

  /**
   * Waits for all workers within the original setup budget.
   *
   * @param attempts the submitted connection attempts
   * @return whether all workers established a connection
   * @throws InterruptedException if the pool owner is interrupted
   * @throws ExecutionException if a worker fails unexpectedly
   * @throws TimeoutException if the setup budget expires
   */
  private boolean collectAttempts(List<Future<Boolean>> attempts)
      throws InterruptedException, ExecutionException, TimeoutException {
    boolean connected = true;
    for (var attemptFuture : attempts) {
      connected &= attemptFuture.get(Math.max(0, setupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
    if (System.nanoTime() >= setupDeadline) {
      throw new TimeoutException("STOMP pool setup exceeded its total budget");
    }
    return connected;
  }

  /**
   * Shuts down the executor used for connection setup.
   *
   * @param executor the executor to stop
   */
  private void shutdownExecutor(ExecutorService executor) {
    executor.shutdownNow();
    boolean interrupted = Thread.interrupted();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("WebSocket connection executor did not terminate cleanly.");
      }
    } catch (InterruptedException e) {
      interrupted = true;
      log.warn("Interrupted while waiting for WebSocket executor shutdown.");
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Closes a WebSocket session while suppressing cleanup exceptions.
   *
   * @param session the session to close
   */
  private void closeQuietly(Connection session) {
    if (session == null || !session.isOpen()) {
      return;
    }

    try {
      session.close();
    } catch (Exception e) {
      log.warn("Failed to close WebSocket session cleanly: {}", e.getMessage());
    }
  }

  /**
   * Builds a concise root-cause string for diagnostics.
   *
   * @param throwable the exception to inspect
   * @return a compact message with the root-cause type and text
   */
  private String rootCauseMessage(Throwable throwable) {
    var root = throwable;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    var message = root.getMessage();
    if (message == null || message.isBlank()) {
      return root.getClass().getSimpleName();
    }
    return root.getClass().getSimpleName() + ": " + message;
  }

  /**
   * Holds a transport and its successfully negotiated STOMP session.
   *
   * @param transport underlying WebSocket connection
   * @param stomp negotiated STOMP session
   */
  private record LoadConnection(Connection transport, StompSession stomp) {
    /** Returns true only while both protocol layers remain connected. */
    private boolean isOpen() {
      return transport.isOpen() && stomp.isConnected();
    }
  }
}
