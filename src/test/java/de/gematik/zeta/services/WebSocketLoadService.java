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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Maintains a pool of raw WebSocket connections for load and capacity scenarios.
 */
@Slf4j
public class WebSocketLoadService implements AutoCloseable {

  private static final int CONNECT_TIMEOUT_SECONDS = 10;
  private static final int MAX_OPEN_CONCURRENCY = 25;

  private final WebSocketClientFactory clientFactory;
  private final List<WebSocketSession> sessions = Collections.synchronizedList(new ArrayList<>());
  private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

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
   * Opens the requested number of raw WebSocket connections against the given endpoint.
   *
   * <p>Existing connections are closed first so each call starts from a clean state.</p>
   *
   * @param url the target WebSocket URL, including Tiger placeholders if needed
   * @param connectionCount the number of connections to open
   */
  public void openConnections(String url, int connectionCount) {
    if (connectionCount <= 0) {
      throw new AssertionError("WebSocket connection count must be > 0");
    }

    close();

    var resolvedUrl = TigerGlobalConfiguration.resolvePlaceholders(url).trim();
    SslConfigurationService.configureForTesting();

    log.info("Opening {} WebSocket connections to {}", connectionCount, resolvedUrl);

    var executor = Executors.newFixedThreadPool(Math.min(MAX_OPEN_CONCURRENCY, connectionCount));
    try {
      List<Future<ConnectionAttempt>> attempts = new ArrayList<>(connectionCount);
      for (int index = 0; index < connectionCount; index++) {
        final int connectionIndex = index + 1;
        attempts.add(executor.submit(() -> openSingleConnection(resolvedUrl, connectionIndex)));
      }
      collectAttempts(attempts);
    } finally {
      shutdownExecutor(executor);
    }

    log.info(
        "WebSocket load pool established: openConnections={}, failures={}",
        getOpenConnectionCount(),
        getFailureCount());
  }

  /**
   * Returns the number of currently open WebSocket sessions.
   *
   * @return the number of sessions that are still open
   */
  public int getOpenConnectionCount() {
    synchronized (sessions) {
      sessions.removeIf(session -> session == null || !session.isOpen());
      return sessions.size();
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
      return failures.stream()
          .limit(Math.max(0, maxEntries))
          .toList();
    }
  }

  /**
   * Closes all open WebSocket sessions and clears collected failure state.
   */
  @Override
  public void close() {
    synchronized (sessions) {
      for (var session : sessions) {
        closeQuietly(session);
      }
      sessions.clear();
    }

    synchronized (failures) {
      failures.clear();
    }
  }

  /**
   * Opens a single raw WebSocket connection.
   *
   * @param resolvedUrl the already resolved WebSocket URL
   * @param connectionIndex the 1-based connection index for diagnostics
   * @return the connection attempt result
   */
  private ConnectionAttempt openSingleConnection(String resolvedUrl, int connectionIndex) {
    try {
      var session = clientFactory.createRawClient()
          .execute(new NoOpWebSocketHandler(), new WebSocketHttpHeaders(), URI.create(resolvedUrl))
          .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (session == null || !session.isOpen()) {
        return ConnectionAttempt.failure(
            String.format("connection #%d did not stay open", connectionIndex));
      }

      return ConnectionAttempt.success(session);
    } catch (ExecutionException e) {
      return ConnectionAttempt.failure(
          String.format("connection #%d failed: %s", connectionIndex, rootCauseMessage(e)));
    } catch (TimeoutException e) {
      return ConnectionAttempt.failure(
          String.format(
              "connection #%d timed out after %d seconds",
              connectionIndex,
              CONNECT_TIMEOUT_SECONDS));
    } catch (Exception e) {
      return ConnectionAttempt.failure(
          String.format("connection #%d failed: %s", connectionIndex, rootCauseMessage(e)));
    }
  }

  /**
   * Collects completed connection attempts and updates the internal success/failure state.
   *
   * @param attempts the submitted connection attempts
   */
  private void collectAttempts(List<Future<ConnectionAttempt>> attempts) {
    for (var attemptFuture : attempts) {
      try {
        var attempt = attemptFuture.get();
        if (attempt.session() != null) {
          sessions.add(attempt.session());
        } else if (attempt.failureMessage() != null) {
          failures.add(attempt.failureMessage());
        }
      } catch (Exception e) {
        failures.add("connection attempt collection failed: " + rootCauseMessage(e));
      }
    }
  }

  /**
   * Shuts down the executor used for connection setup.
   *
   * @param executor the executor to stop
   */
  private void shutdownExecutor(ExecutorService executor) {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("WebSocket connection executor did not terminate cleanly.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for WebSocket executor shutdown.");
    }
  }

  /**
   * Closes a WebSocket session while suppressing cleanup exceptions.
   *
   * @param session the session to close
   */
  private void closeQuietly(WebSocketSession session) {
    if (session == null || !session.isOpen()) {
      return;
    }

    try {
      session.close(CloseStatus.NORMAL);
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
   * No-op handler for raw WebSocket connections that only keeps the session alive.
   */
  private static final class NoOpWebSocketHandler extends AbstractWebSocketHandler {

    /**
     * Logs transport errors on the background connections.
     *
     * @param session the affected session
     * @param exception the reported transport error
     */
    @Override
    public void handleTransportError(
        @NonNull WebSocketSession session,
        @NonNull Throwable exception) {
      log.warn("WebSocket transport error on session {}: {}", session.getId(), exception.getMessage());
    }
  }

  /**
   * Captures the outcome of a single connection attempt.
   *
   * @param session the opened session, if successful
   * @param failureMessage the failure description, if unsuccessful
   */
  private record ConnectionAttempt(WebSocketSession session, String failureMessage) {

    /**
     * Creates a successful connection attempt result.
     *
     * @param session the opened session
     * @return the success result
     */
    private static ConnectionAttempt success(WebSocketSession session) {
      return new ConnectionAttempt(session, null);
    }

    /**
     * Creates a failed connection attempt result.
     *
     * @param failureMessage the diagnostic failure text
     * @return the failure result
     */
    private static ConnectionAttempt failure(String failureMessage) {
      return new ConnectionAttempt(null, failureMessage);
    }
  }
}
