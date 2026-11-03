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

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Owns direct WebSocket transports and Spring STOMP heartbeat resources for a scenario. */
public class WebSocketClientFactory implements AutoCloseable {

  static final List<String> STOMP_PROTOCOLS = List.of("v12.stomp", "v11.stomp", "v10.stomp");

  private DirectWebSocketClient transport;
  private ThreadPoolTaskScheduler scheduler;

  /**
   * Starts STOMP 1.2 on an existing transport with the shared heartbeat configuration.
   *
   * @param connection upgraded WebSocket owned by this factory
   * @param handler session callback handler
   * @return asynchronous STOMP handshake
   */
  public CompletableFuture<StompSession> connectStomp(
      DirectWebSocketClient.Connection connection, StompSessionHandler handler) {
    var headers = new StompHeaders();
    headers.setAcceptVersion("1.2");
    headers.setHost(connection.getUri().getHost());
    return createRawClient().connectStomp(connection, headers, handler);
  }

  /** Returns the scenario's transport, releasing failed initialization resources before retrying. */
  public synchronized DirectWebSocketClient createRawClient() {
    if (transport == null) {
      try {
        transport = new DirectWebSocketClient(testTlsContext());
        configureStomp(transport);
      } catch (Exception e) {
        try {
          close();
        } catch (Exception cleanupError) {
          e.addSuppressed(cleanupError);
        }
        throw new AssertionError("Could not configure direct WebSocket client", e);
      }
    }
    return transport;
  }

  /** Preserves permissive functional-test TLS locally, without changing JVM TLS defaults. */
  private static SSLContext testTlsContext() throws GeneralSecurityException {
    var context = SSLContext.getInstance("TLS");
    var trust = new X509ExtendedTrustManager() {
      /** Accepts test client certificates. */
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) {
      }

      /** Accepts test client certificates on sockets. */
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType,
          Socket socket) {
      }

      /** Accepts test client certificates on TLS engines. */
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType,
          SSLEngine engine) {
      }

      /** Accepts test server certificates. */
      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {
      }

      /** Accepts test server certificates and names on this functional-test socket only. */
      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType,
          Socket socket) {
      }

      /** Accepts test server certificates and names on this functional-test engine only. */
      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType,
          SSLEngine engine) {
      }

      /** No issuer restriction applies to functional-test TLS. */
      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
    context.init(null, new TrustManager[]{trust}, null);
    return context;
  }

  /** Configures Spring message conversion and a shared scenario heartbeat scheduler. */
  private void configureStomp(DirectWebSocketClient client) {
    client.setMessageConverter(new CompositeMessageConverter(List.of(
        new StringMessageConverter(), new JacksonJsonMessageConverter())));
    if (scheduler == null) {
      scheduler = new ThreadPoolTaskScheduler();
      scheduler.setPoolSize(1);
      scheduler.setThreadNamePrefix("stomp-");
      scheduler.setDaemon(true);
      scheduler.initialize();
    }
    client.setTaskScheduler(scheduler);
    client.setDefaultHeartbeat(new long[]{30000, 30000});
  }

  /** Clears owned resources and releases heartbeat tasks even if transport cleanup fails. */
  @Override
  public synchronized void close() {
    var closingTransport = transport;
    var closingScheduler = scheduler;
    transport = null;
    scheduler = null;
    try (closingTransport) {
      // The transport must finish its bounded Close handshake before the scheduler shuts down.
    } finally {
      if (closingScheduler != null) {
        closingScheduler.shutdown();
      }
    }
  }
}
