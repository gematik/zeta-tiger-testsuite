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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.BufferingStompDecoder;
import org.springframework.messaging.simp.stomp.ConnectionHandlingStompSession;
import org.springframework.messaging.simp.stomp.StompClientSupport;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompEncoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.tcp.TcpConnection;
import org.springframework.util.MimeTypeUtils;

/** Uses JDK WebSockets directly, with Spring's byte transport contract for optional STOMP. */
@Slf4j
public final class DirectWebSocketClient extends StompClientSupport implements AutoCloseable {
  private static final int MESSAGE_LIMIT = 1024 * 1024;
  private final HttpClient httpClient;
  private final int closeTimeoutSeconds = WebSocketConfiguration.closeTimeoutSeconds();
  private boolean shuttingDown;
  private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

  /** Creates a transport with connection-local TLS and no inherited JVM proxy. */
  public DirectWebSocketClient(SSLContext sslContext) {
    httpClient = HttpClient.newBuilder().proxy(ProxySelector.of(null))
        .sslContext(sslContext).connectTimeout(Duration.ofSeconds(WebSocketConfiguration.connectionTimeoutSeconds())).build();
  }

  /** Completes only the HTTP-to-WebSocket upgrade, without sending STOMP. */
  public synchronized CompletableFuture<Connection> open(URI uri, List<String> protocols) {
    if (shuttingDown) {
      return CompletableFuture.failedFuture(new IOException("WebSocket client is closed"));
    }
    var connection = new Connection(uri);
    connections.add(connection);
    var builder = httpClient.newWebSocketBuilder();
    if (!protocols.isEmpty()) {
      builder.subprotocols(protocols.getFirst(), protocols.stream().skip(1).toArray(String[]::new));
    }
    var result = new CompletableFuture<Connection>();
    var handshake = builder.buildAsync(uri, connection);
    handshake.whenComplete((socket, error) -> {
      if (error != null) {
        connections.remove(connection);
        result.completeExceptionally(error);
      } else if (!result.complete(connection)) {
        connection.abort();
      }
    });
    result.whenComplete((socket, error) -> {
      if (result.isCancelled()) {
        handshake.cancel(true);
        connection.abort();
      }
    });
    return result;
  }

  /** Starts STOMP asynchronously so the caller's timeout also bounds the initial CONNECT send. */
  public CompletableFuture<StompSession> connectStomp(Connection connection, StompHeaders headers,
      StompSessionHandler handler) {
    synchronized (connection) {
      if (!connection.isOpen() || connection.stomp != null) {
        return CompletableFuture.failedFuture(new IOException("Verified WebSocket is closed or already uses STOMP"));
      }
      var stomp = createSession(headers, handler);
      connection.stomp = stomp;
      var result = stomp.getSession();
      connection.handshakeThread = Thread.ofVirtual().name("stomp-connect").unstarted(() -> {
        try {
          stomp.afterConnected(connection);
        } catch (Throwable error) {
          connection.onError(connection.socket, error);
        } finally {
          connection.handshakeThread = null;
        }
      });
      result.whenComplete((session, error) -> {
        if (result.isCancelled()) {
          connection.onError(connection.socket, new CancellationException("STOMP handshake cancelled"));
        }
      });
      connection.handshakeThread.start();
      return result;
    }
  }

  /** Waits for bounded peer close handshakes before releasing the JDK client resources. */
  @Override
  public synchronized void close() {
    shuttingDown = true;
    var closing = List.copyOf(connections);
    closing.forEach(Connection::close);
    CompletableFuture.allOf(closing.stream()
        .map(connection -> connection.closeCompletion.handle((ignored, error) -> null))
        .toArray(CompletableFuture[]::new)).join();
    httpClient.shutdownNow();
  }

  /** Holds one native WebSocket; raw tests use it without initializing any STOMP session. */
  public final class Connection implements WebSocket.Listener, TcpConnection<byte[]> {
    @Getter private final URI uri;
    private final BufferingStompDecoder decoder = new BufferingStompDecoder(new StompDecoder(), MESSAGE_LIMIT);
    private final StringBuilder text = new StringBuilder();
    private final List<ScheduledFuture<?>> heartbeatTasks = new CopyOnWriteArrayList<>();
    private volatile ConnectionHandlingStompSession stomp;
    private volatile Thread handshakeThread;
    private volatile WebSocket socket;
    private volatile boolean closed;
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private volatile long lastRead = System.nanoTime();
    private volatile long lastWrite = System.nanoTime();
    private CompletableFuture<Void> pendingSend = CompletableFuture.completedFuture(null);

    /** Records the target for diagnostics and the subsequent STOMP handshake. */
    private Connection(URI uri) {
      this.uri = uri;
    }

    /** Starts receiving only after the native HTTP upgrade succeeds. */
    @Override
    public void onOpen(WebSocket webSocket) {
      socket = webSocket;
      if (closed) {
        webSocket.abort();
      } else {
        webSocket.request(1);
      }
    }

    /** Reassembles text fragments before UTF-8 encoding, including split surrogate pairs. */
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      lastRead = System.nanoTime();
      if (stomp != null) {
        if ((long) text.length() + data.length() > MESSAGE_LIMIT) {
          onError(webSocket, new IOException("WebSocket text message exceeds configured limit"));
          return null;
        }
        text.append(data);
        if (last) {
          receive(ByteBuffer.wrap(text.toString().getBytes(UTF_8)));
          text.setLength(0);
        }
      }
      webSocket.request(1);
      return null;
    }

    /** Lets Spring buffer and decode binary STOMP fragments. */
    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      lastRead = System.nanoTime();
      // The JDK may reuse callback buffers; Spring can retain incomplete frame bytes.
      receive(ByteBuffer.allocate(data.remaining()).put(data).flip());
      webSocket.request(1);
      return null;
    }

    /** Passes decoded STOMP frames to Spring; raw-only connections do not inspect payloads. */
    private void receive(ByteBuffer data) {
      if (stomp != null) {
        try {
          decoder.decode(data).forEach(stomp::handleMessage);
        } catch (Exception e) {
          onError(socket, e);
        }
      }
    }

    /** Preserves the transport failure and clears Spring's connection state after terminal errors. */
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      abort();
      if (stomp != null) {
        try {
          // Preserve the original handshake failure before Spring reports generic connection loss.
          stomp.handleFailure(error);
        } finally {
          // JDK onError is terminal: no later onClose callback will reset Spring's session.
          stomp.afterConnectionClosed();
        }
      } else {
        log.warn("WebSocket transport error at {}", uri, error);
      }
    }

    /** Reports an unexpected peer closure to the STOMP session. */
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closed = true;
      heartbeatTasks.forEach(task -> task.cancel(false));
      connections.remove(this);
      try {
        if (stomp != null) {
          stomp.afterConnectionClosed();
        }
      } finally {
        closeCompletion.complete(null);
      }
      return null;
    }

    /** Indicates whether both native directions remain available. */
    public boolean isOpen() {
      return !closed && socket != null && !socket.isInputClosed() && !socket.isOutputClosed();
    }

    /** Serializes encoded STOMP frames and heartbeats on the native connection. */
    @Override
    public synchronized CompletableFuture<Void> sendAsync(Message<byte[]> message) {
      if (!isOpen()) {
        return CompletableFuture.failedFuture(new IOException("WebSocket connection is closed"));
      }
      byte[] bytes = new StompEncoder().encode(message);
      var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
      boolean binary = headers != null && message.getPayload().length > 0
          && MimeTypeUtils.APPLICATION_OCTET_STREAM.isCompatibleWith(headers.getContentType() == null
          ? MimeTypeUtils.TEXT_PLAIN : headers.getContentType());
      pendingSend = pendingSend.thenCompose(ignored -> binary
          ? socket.sendBinary(ByteBuffer.wrap(bytes), true) : socket.sendText(new String(bytes, UTF_8), true))
          .thenAccept(ignored -> lastWrite = System.nanoTime());
      return pendingSend;
    }

    /** Schedules the read-idle action negotiated by Spring's STOMP heartbeat handling. */
    @Override
    public void onReadInactivity(Runnable task, long duration) {
      scheduleIdle(task, duration, () -> lastRead);
    }

    /** Schedules outgoing heartbeats only while application traffic is idle. */
    @Override
    public void onWriteInactivity(Runnable task, long duration) {
      scheduleIdle(task, duration, () -> lastWrite);
    }

    /** Uses one scenario-owned scheduler and cancels tasks when the connection closes. */
    private void scheduleIdle(Runnable task, long duration, LongSupplier lastActivity) {
      heartbeatTasks.add(getTaskScheduler().scheduleWithFixedDelay(() -> {
        if (isOpen() && System.nanoTime() - lastActivity.getAsLong() >= TimeUnit.MILLISECONDS.toNanos(duration)) {
          task.run();
        }
      }, Duration.ofMillis(Math.max(1, duration / 2))));
    }

    /** Sends Close after queued frames and allows the peer a bounded time to reply. */
    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        heartbeatTasks.forEach(task -> task.cancel(false));
        if (socket == null) {
          abort();
          return;
        }
        CompletableFuture.delayedExecutor(closeTimeoutSeconds, TimeUnit.SECONDS)
            .execute(() -> {
              if (!closeCompletion.isDone()) {
                log.warn("WebSocket close handshake timed out at {}; aborting transport", uri);
                abort();
              }
            });
        pendingSend.thenCompose(ignored -> socket.sendClose(WebSocket.NORMAL_CLOSURE, ""))
            .whenComplete((ignored, error) -> {
              if (error != null) {
                onError(socket, error);
              }
            });
      }
    }

    /** Aborts the native connection and releases any worker blocked sending STOMP CONNECT. */
    private void abort() {
      closed = true;
      var connecting = handshakeThread;
      if (connecting != null && connecting != Thread.currentThread()) {
        connecting.interrupt();
      }
      heartbeatTasks.forEach(task -> task.cancel(false));
      connections.remove(this);
      if (socket != null) {
        socket.abort();
      }
      closeCompletion.complete(null);
    }
  }
}
