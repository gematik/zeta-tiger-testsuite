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

package de.gematik.zeta.services.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.gematik.zeta.services.DirectWebSocketClient;
import de.gematik.zeta.services.StompSessionManager;
import de.gematik.zeta.services.WebSocketClientFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.util.ReflectionTestUtils;

/** Controls peer Close delivery independently of successful local frame sending. */
class WebSocketCloseHandshakeTest {
  private DirectWebSocketClient client;
  private DirectWebSocketClient.Connection connection;
  private HttpClient httpClient;
  private WebSocket socket;
  private CountDownLatch closeSent;

  /** Opens a transport whose peer reply is controlled explicitly by each test. */
  @BeforeEach
  void openConnection() throws Exception {
    client = new DirectWebSocketClient(SSLContext.getDefault());
    // Release the constructor's real HTTP client before injecting the controlled transport.
    ((HttpClient) ReflectionTestUtils.getField(client, "httpClient")).shutdownNow();
    httpClient = mock(HttpClient.class);
    socket = mock(WebSocket.class);
    var builder = mock(WebSocket.Builder.class);
    when(httpClient.newWebSocketBuilder()).thenReturn(builder);
    when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(invocation -> {
      invocation.<WebSocket.Listener>getArgument(1).onOpen(socket);
      return CompletableFuture.completedFuture(socket);
    });
    closeSent = new CountDownLatch(1);
    when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "")).thenAnswer(invocation -> {
      closeSent.countDown();
      return CompletableFuture.completedFuture(socket);
    });
    ReflectionTestUtils.setField(client, "httpClient", httpClient);
    connection = client.open(URI.create("ws://localhost/ws"), List.of()).get(5, TimeUnit.SECONDS);
  }

  /** Releases the owner even after a failed assertion. */
  @AfterEach
  void closeClient() {
    client.close();
  }

  /** Proves factory shutdown cannot abort a connection while its peer Close is still pending. */
  @Test
  void waitsForPeerCloseBeforeShuttingDownOwner() throws Exception {
    var cleanup = CompletableFuture.runAsync(client::close);
    assertThat(closeSent.await(5, TimeUnit.SECONDS)).isTrue();
    verify(socket, after(100).never()).abort();
    assertThat(cleanup).isNotDone();
    verify(httpClient, never()).shutdownNow();

    connection.onClose(socket, WebSocket.NORMAL_CLOSURE, "");

    cleanup.get(5, TimeUnit.SECONDS);
    verify(socket, never()).abort();
    verify(httpClient).shutdownNow();
    assertThat(connection.isOpen()).isFalse();
  }

  /** Proves an unresponsive peer cannot leave scenario cleanup waiting indefinitely. */
  @Test
  void abortsWhenPeerDoesNotReplyBeforeDeadline() throws Exception {
    var cleanup = CompletableFuture.runAsync(client::close);
    assertThat(closeSent.await(5, TimeUnit.SECONDS)).isTrue();
    cleanup.get(5, TimeUnit.SECONDS);
    verify(socket).abort();
    verify(httpClient).shutdownNow();
    assertThat(connection.isOpen()).isFalse();
  }

  /** Proves a failed Close send immediately releases the transport without waiting for a reply. */
  @Test
  void abortsWhenCloseCannotBeSent() {
    when(socket.sendClose(WebSocket.NORMAL_CLOSURE, ""))
        .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("Close send failed")));
    client.close();
    verify(socket).abort();
    verify(httpClient).shutdownNow();
    assertThat(connection.isOpen()).isFalse();
  }

  /** Proves the manager times out a stalled CONNECT send and releases its transport and worker. */
  @Test
  void connectionTimeoutIncludesStalledStompConnectSend() throws Exception {
    var send = new CompletableFuture<WebSocket>();
    var sendStarted = new CountDownLatch(1);
    var sender = new AtomicReference<Thread>();
    when(socket.sendText(any(CharSequence.class), eq(true))).thenAnswer(invocation -> {
      sender.set(Thread.currentThread());
      sendStarted.countDown();
      return send;
    });
    try (var factory = new WebSocketClientFactory()) {
      ReflectionTestUtils.setField(factory, "transport", client);
      var manager = new StompSessionManager(factory);
      manager.setConnectionTimeout(1);
      ReflectionTestUtils.setField(manager, "rawWebSocket", connection);
      ReflectionTestUtils.setField(manager, "validatedWebSocketUri", connection.getUri());

      var attempt = CompletableFuture.runAsync(manager::connectStompUsingExistingWebSocket);
      try {
        assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(AssertionError.class)
            .hasStackTraceContaining("STOMP CONNECTED was not received within 1 seconds");
        assertThat(sender.get().join(Duration.ofSeconds(2))).isTrue();
        assertThat(connection.isOpen()).isFalse();
        assertThat(send).isNotDone();
        verify(httpClient).shutdownNow();
      } finally {
        // Also releases the synchronous implementation when this regression test fails.
        send.completeExceptionally(new IOException("Test cleanup"));
      }
    }
  }

  /** Preserves a failed CONNECT send as an exceptional handshake and clears Spring's connection. */
  @Test
  void failedStompConnectSendCompletesHandshakeExceptionally() throws Exception {
    var failure = new IOException("CONNECT send failed");
    when(socket.sendText(any(CharSequence.class), eq(true)))
        .thenReturn(CompletableFuture.failedFuture(failure));

    var handshake = client.connectStomp(connection, new StompHeaders(), new StompSessionHandlerAdapter() {});

    assertThatThrownBy(() -> handshake.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasRootCause(failure);
    assertThat(connection.isOpen()).isFalse();
  }
}
