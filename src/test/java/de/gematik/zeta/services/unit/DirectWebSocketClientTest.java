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

import de.gematik.zeta.services.StompSessionManager;
import de.gematik.zeta.services.WebSocketClientFactory;
import de.gematik.zeta.services.WebSocketLoadService;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Exercises real Spring STOMP traffic while the JVM proxy points at an unusable address. */
@ResourceLock("JVM_PROXY_SELECTOR")
class DirectWebSocketClientTest {
  @TempDir Path directory;
  private Tomcat server;
  private ProxySelector previousProxy;
  private StompSessionManager manager;

  /** Starts a local WebSocket server and routes inherited proxy users to a closed port. */
  @BeforeEach
  void startServer() throws Exception {
    EchoEndpoint.opens.set(0);
    EchoEndpoint.frames.set(0);
    EchoEndpoint.reply = true;
    EchoEndpoint.binaryReplies = false;
    EchoEndpoint.heartbeatHeader = "0,0";
    EchoEndpoint.heartbeatReceived = new CountDownLatch(1);
    EchoEndpoint.closed = new CountDownLatch(1);
    EchoEndpoint.closeReason = null;
    EchoEndpoint.rejectFirstConnect.set(false);
    EchoEndpoint.ignoreFirstConnect.set(false);
    EchoEndpoint.rejectStompFrame = false;
    EchoEndpoint.rejectConnectsRemaining.set(0);
    EchoEndpoint.connectStarted = new CountDownLatch(0);
    EchoEndpoint.releaseConnects = new CountDownLatch(0);
    EchoEndpoint.pendingConnects.set(0);
    EchoEndpoint.maxPendingConnects.set(0);
    EchoEndpoint.connectDelayMillis = 0;
    EchoEndpoint.openedSessions.clear();
    server = new Tomcat();
    server.setBaseDir(directory.toString());
    server.setPort(0);
    server.getConnector().setProperty("address", "127.0.0.1");
    var context = server.addContext("", directory.toString());
    Tomcat.addServlet(context, "default", new org.apache.catalina.servlets.DefaultServlet());
    context.addServletMappingDecoded("/", "default");
    context.addServletContainerInitializer(new WsSci(), Set.of(EchoEndpoint.class));
    server.start();
    previousProxy = ProxySelector.getDefault();
    ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("127.0.0.1", 1)));
    manager = new StompSessionManager(new WebSocketClientFactory());
  }

  /** Releases native connections and restores process state even after assertion failures. */
  @AfterEach
  void stopServer() throws Exception {
    if (manager != null) {
      manager.close();
    }
    ProxySelector.setDefault(previousProxy);
    server.stop();
    server.destroy();
  }

  /** Builds the test server endpoint URL. */
  private String url(String path) {
    return "ws://127.0.0.1:" + server.getConnector().getLocalPort() + path;
  }

  /** Proves the upgrade precedes STOMP and both phases use one direct connection. */
  @Test
  void checksWebSocketBeforeStompOnSameDirectConnection() {
    manager.verifyWebSocketUpgrade(url("/ws"));
    assertThat(EchoEndpoint.opens.get()).isEqualTo(1);
    assertThat(EchoEndpoint.frames.get()).isZero();
    assertThat(manager.getSession()).isNull();

    manager.connectStompUsingExistingWebSocket();
    assertThat(manager.getSession().isConnected()).isTrue();
    assertThat(EchoEndpoint.opens.get()).isEqualTo(1);
    manager.subscribe("/queue/replies", "test-sub");
    manager.awaitMessage("/queue/replies");
    assertThat(manager.getLastReceivedMessage().payload()).isNotNull();
  }

  /** Verifies a terminal transport error clears an established Spring session and preserves its cause. */
  @Test
  void transportErrorDisconnectsEstablishedStompSession() throws Exception {
    try (var factory = new WebSocketClientFactory()) {
      var client = factory.createRawClient();
      var connection = client.open(java.net.URI.create(url("/ws")), java.util.List.of("v12.stomp"))
          .get(5, TimeUnit.SECONDS);
      var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
      var handler = new org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter() {
        /** Records transport failures so the original cause remains observable. */
        @Override
        public void handleTransportError(org.springframework.messaging.simp.stomp.StompSession session,
            Throwable exception) {
          errors.add(exception);
        }
      };
      var session = client.connectStomp(connection,
          new org.springframework.messaging.simp.stomp.StompHeaders(), handler).get(5, TimeUnit.SECONDS);
      assertThat(connection.isOpen()).isTrue();
      assertThat(session.isConnected()).isTrue();

      var failure = new java.io.IOException("Transport failed after CONNECTED");
      connection.onError(null, failure);

      assertThat(connection.isOpen()).isFalse();
      assertThat(session.isConnected()).isFalse();
      assertThat(errors).contains(failure);
      assertThat(EchoEndpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();
      connection.close();
      factory.close();
      assertThat(session.isConnected()).isFalse();
    }
  }

  /** Verifies Spring decodes binary STOMP frames split across WebSocket fragments. */
  @Test
  void receivesFragmentedBinaryStomp() {
    EchoEndpoint.binaryReplies = true;
    manager.verifyWebSocketUpgrade(url("/ws"));
    manager.connectStompUsingExistingWebSocket();
    manager.subscribe("/queue/replies", "test-sub");
    manager.awaitMessage("/queue/replies");
    assertThat(manager.getLastReceivedMessage().payload()).isNotNull();
  }

  /** Verifies negotiated outgoing STOMP heartbeats pass through the smaller byte bridge. */
  @Test
  void sendsNegotiatedHeartbeat() throws Exception {
    EchoEndpoint.heartbeatHeader = "0,20";
    var factory = new WebSocketClientFactory();
    factory.createRawClient().setDefaultHeartbeat(new long[]{20, 0});
    manager = new StompSessionManager(factory);
    manager.verifyWebSocketUpgrade(url("/ws"));
    manager.connectStompUsingExistingWebSocket();
    assertThat(EchoEndpoint.heartbeatReceived.await(5, TimeUnit.SECONDS)).isTrue();
    manager.close();
    assertThat(EchoEndpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();
  }

  /** Verifies pool STOMP handshakes reuse the upgraded sockets and negotiate heartbeats. */
  @Test
  void loadPoolConnectsStompAndNegotiatesHeartbeats() throws Exception {
    EchoEndpoint.heartbeatHeader = "0,20";
    try (var factory = new WebSocketClientFactory() {
      @Override
      public de.gematik.zeta.services.DirectWebSocketClient createRawClient() {
        var client = super.createRawClient();
        client.setDefaultHeartbeat(new long[]{20, 0});
        return client;
      }
    }; var pool = new WebSocketLoadService(factory)) {
      pool.openStompConnections(url("/ws"), 3);
      assertThat(pool.getOpenConnectionCount()).isEqualTo(3);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(3);
      assertThat(pool.getOpenConnectionCount()).isEqualTo(3);
      assertThat(pool.getFailureCount()).isZero();
      assertThat(EchoEndpoint.heartbeatReceived.await(5, TimeUnit.SECONDS)).isTrue();
      pool.close();
      assertThat(pool.getOpenConnectionCount()).isZero();
    }
  }

  /** Verifies timed-out pool STOMP handshakes release their transports and retain diagnostics. */
  @Test
  void loadPoolClosesFailedStompHandshakes() throws Exception {
    EchoEndpoint.reply = false;
    final var previousTimeout = de.gematik.test.tiger.common.config.TigerGlobalConfiguration
        .readStringOptional("websocket.loadConnectionTimeoutSeconds").orElse("10");
    final var previousSetupTimeout = de.gematik.test.tiger.common.config.TigerGlobalConfiguration
        .readStringOptional("websocket.loadSetupTimeoutSeconds").orElse("180");
    de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
        "websocket.loadConnectionTimeoutSeconds", 1);
    de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
        "websocket.loadSetupTimeoutSeconds", 3);
    try (var pool = new WebSocketLoadService()) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> pool.openStompConnections(url("/ws"), 3))
          .isInstanceOf(AssertionError.class).hasMessageContaining("total budget");
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
          .isLessThan(java.time.Duration.ofSeconds(7));
      assertThat(pool.getOpenConnectionCount()).isZero();
      assertThat(pool.getFailureCount()).isGreaterThanOrEqualTo(3);
      assertThat(EchoEndpoint.opens.get()).isGreaterThan(3);
      assertThat(pool.getRecentFailures(pool.getFailureCount()))
          .allMatch(message -> message.contains("TimeoutException"));
      de.gematik.zeta.services.BoundedObservation.await(
          () -> EchoEndpoint.openedSessions.stream().filter(Session::isOpen).count(), count -> count == 0,
          java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(10), "Timed-out transports remain open");
    } finally {
      de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
          "websocket.loadConnectionTimeoutSeconds", previousTimeout);
      de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
          "websocket.loadSetupTimeoutSeconds", previousSetupTimeout);
    }
  }

  /** Replaces a lost handshake without closing the already established pool connections. */
  @Test
  void loadPoolReplacesLostHandshakeAndRetainsSuccessfulConnections() {
    EchoEndpoint.rejectFirstConnect.set(true);
    try (var pool = new WebSocketLoadService()) {
      pool.openStompConnections(url("/ws"), 3);
      assertThat(pool.getOpenConnectionCount()).isEqualTo(3);
      assertThat(pool.getFailureCount()).isEqualTo(1);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(4);
    }
  }

  /** Replaces a connection closed after its handshake but before pool registration. */
  @Test
  void loadPoolRetriesConnectionLostBeforeRegistration() {
    var firstHandshake = new java.util.concurrent.atomic.AtomicBoolean(true);
    var closedTransport = new java.util.concurrent.atomic.AtomicReference<
        de.gematik.zeta.services.DirectWebSocketClient.Connection>();
    try (var factory = new WebSocketClientFactory() {
      /** Closes the first transport before exposing its successful STOMP handshake. */
      @Override
      public java.util.concurrent.CompletableFuture<org.springframework.messaging.simp.stomp.StompSession> connectStomp(
          de.gematik.zeta.services.DirectWebSocketClient.Connection connection,
          org.springframework.messaging.simp.stomp.StompSessionHandler handler) {
        return super.connectStomp(connection, handler).thenApply(stomp -> {
          if (firstHandshake.getAndSet(false)) {
            closedTransport.set(connection);
            connection.close();
          }
          return stomp;
        });
      }
    }; var pool = new WebSocketLoadService(factory)) {
      pool.openStompConnections(url("/ws"), 1);
      assertThat(pool.getOpenConnectionCount()).isEqualTo(1);
      assertThat(pool.getFailureCount()).isEqualTo(1);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(2);
      assertThat(closedTransport.get().isOpen()).isFalse();
      assertThat(pool.getRecentFailures(1).getFirst())
          .contains("ConnectionLostException: Connection closed before pool registration");
    }
  }

  /** Continues replacing failed handshakes beyond two attempts within the original budget. */
  @Test
  void loadPoolKeepsRebuildingUntilTheRequestedCountIsReached() {
    EchoEndpoint.rejectConnectsRemaining.set(3);
    try (var pool = new WebSocketLoadService()) {
      pool.openStompConnections(url("/ws"), 1);
      assertThat(pool.getOpenConnectionCount()).isEqualTo(1);
      assertThat(pool.getFailureCount()).isEqualTo(3);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(4);
    }
  }

  /** Replaces one timed-out handshake while retaining its failure diagnostic. */
  @Test
  void loadPoolRetriesOneTimedOutHandshake() {
    EchoEndpoint.ignoreFirstConnect.set(true);
    final var configuration = de.gematik.test.tiger.common.config.TigerGlobalConfiguration
        .readStringOptional("websocket.loadConnectionTimeoutSeconds").orElse("10");
    de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
        "websocket.loadConnectionTimeoutSeconds", 1);
    try (var pool = new WebSocketLoadService()) {
      pool.openStompConnections(url("/ws"), 3);
      assertThat(pool.getOpenConnectionCount()).isEqualTo(3);
      assertThat(pool.getFailureCount()).isEqualTo(1);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(4);
      assertThat(pool.getRecentFailures(1).getFirst()).contains("TimeoutException");
    } finally {
      de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
          "websocket.loadConnectionTimeoutSeconds", configuration);
    }
  }

  /** Retains explicit STOMP rejection without retrying it as a transient transport failure. */
  @Test
  void loadPoolDoesNotRetryStompErrorFrames() {
    EchoEndpoint.rejectStompFrame = true;
    try (var pool = new WebSocketLoadService()) {
      assertThatThrownBy(() -> pool.openStompConnections(url("/ws"), 3))
          .isInstanceOf(AssertionError.class).hasMessageContaining("found 0");
      assertThat(pool.getOpenConnectionCount()).isZero();
      assertThat(pool.getFailureCount()).isEqualTo(3);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(3);
      assertThat(pool.getRecentFailures(3)).allMatch(message -> message.contains("STOMP ERROR="));
    }
  }

  /** Bounds unfinished upgrades and STOMP handshakes by the same worker limit. */
  @Test
  void loadPoolLimitsCompleteConnectionSetupToTwentyFiveWorkers() throws Exception {
    EchoEndpoint.connectStarted = new CountDownLatch(25);
    EchoEndpoint.releaseConnects = new CountDownLatch(1);
    try (var pool = new WebSocketLoadService(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var setup = executor.submit(() -> pool.openStompConnections(url("/ws"), 50));
      try {
        assertThat(EchoEndpoint.connectStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(EchoEndpoint.opens.get()).isEqualTo(25);
      } finally {
        EchoEndpoint.releaseConnects.countDown();
      }
      setup.get(10, TimeUnit.SECONDS);
      assertThat(EchoEndpoint.maxPendingConnects.get()).isLessThanOrEqualTo(25);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(50);
      assertThat(pool.getOpenConnectionCount()).isEqualTo(50);
      assertThat(pool.getFailureCount()).isZero();
    }
  }

  /** Gives connections in later worker waves their own handshake timeout. */
  @Test
  void loadPoolDoesNotShareHandshakeDeadlineAcrossWorkerWaves() {
    final var configuration = de.gematik.test.tiger.common.config.TigerGlobalConfiguration
        .readStringOptional("websocket.loadConnectionTimeoutSeconds").orElse("10");
    de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
        "websocket.loadConnectionTimeoutSeconds", 2);
    EchoEndpoint.connectDelayMillis = 1200;
    try (var pool = new WebSocketLoadService()) {
      long started = System.nanoTime();
      pool.openStompConnections(url("/ws"), 26);
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
          .isGreaterThan(java.time.Duration.ofSeconds(2));
      assertThat(pool.getOpenConnectionCount()).isEqualTo(26);
      assertThat(pool.getFailureCount()).isZero();
    } finally {
      de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
          "websocket.loadConnectionTimeoutSeconds", configuration);
    }
  }

  /** Closes pending and retained transports when the original setup budget expires. */
  @Test
  void loadPoolClosesTransportsWhenOverallBudgetExpires() throws Exception {
    final var configuration = de.gematik.test.tiger.common.config.TigerGlobalConfiguration
        .readStringOptional("websocket.loadSetupTimeoutSeconds").orElse("180");
    de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue("websocket.loadSetupTimeoutSeconds", 1);
    EchoEndpoint.ignoreFirstConnect.set(true);
    EchoEndpoint.closed = new CountDownLatch(3);
    try (var pool = new WebSocketLoadService()) {
      assertThatThrownBy(() -> pool.openStompConnections(url("/ws"), 3))
          .isInstanceOf(AssertionError.class).hasMessageContaining("total budget");
      assertThat(pool.getOpenConnectionCount()).isZero();
      assertThat(EchoEndpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      de.gematik.test.tiger.common.config.TigerGlobalConfiguration.putValue(
          "websocket.loadSetupTimeoutSeconds", configuration);
    }
  }

  /** Counts a closed connection as lost without replacing it after setup. */
  @Test
  void loadPoolDoesNotReplaceConnectionsAfterSetup() throws Exception {
    try (var pool = new WebSocketLoadService()) {
      pool.openStompConnections(url("/ws"), 3);
      EchoEndpoint.openedSessions.getFirst().close();
      de.gematik.zeta.services.BoundedObservation.await(pool::getOpenConnectionCount, count -> count == 2,
          java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(10), "Closed connection remains counted");
      assertThat(pool.getOpenConnectionCount()).isEqualTo(2);
      assertThat(EchoEndpoint.opens.get()).isEqualTo(3);
    }
  }

  /** Releases in-progress transports and preserves interruption of the pool owner. */
  @Test
  void loadPoolInterruptionClosesPendingTransports() throws Exception {
    EchoEndpoint.reply = false;
    EchoEndpoint.connectStarted = new CountDownLatch(3);
    EchoEndpoint.closed = new CountDownLatch(3);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    try (var pool = new WebSocketLoadService()) {
      var owner = new Thread(() -> {
        try {
          pool.openStompConnections(url("/ws"), 3);
        } catch (Throwable error) {
          failure.set(error);
          interrupted.set(Thread.currentThread().isInterrupted());
        }
      });
      owner.start();
      try {
        assertThat(EchoEndpoint.connectStarted.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        owner.interrupt();
        owner.join(5000);
      }
      assertThat(owner.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(AssertionError.class).hasMessageContaining("interrupted");
      assertThat(interrupted.get()).isTrue();
      assertThat(pool.getOpenConnectionCount()).isZero();
      assertThat(EchoEndpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Verifies Spring closes a peer that promises heartbeats but sends none. */
  @Test
  void closesPeerAfterNegotiatedReadInactivity() throws Exception {
    EchoEndpoint.heartbeatHeader = "20,0";
    var factory = new WebSocketClientFactory();
    factory.createRawClient().setDefaultHeartbeat(new long[]{0, 100});
    manager = new StompSessionManager(factory);
    manager.verifyWebSocketUpgrade(url("/ws"));
    manager.connectStompUsingExistingWebSocket();
    assertThat(EchoEndpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(manager.getSession().isConnected()).isFalse();
  }

  /** Verifies normal native closing is idempotent and actually releases the peer connection. */
  @Test
  void normalCloseWithoutReasonIsIdempotent() throws Exception {
    try (var factory = new WebSocketClientFactory()) {
      var session = factory.createRawClient().open(java.net.URI.create(url("/ws")), java.util.List.of())
          .get(5, TimeUnit.SECONDS);
      session.close();
      session.close();
      assertThat(session.isOpen()).isFalse();
      assertThat(EchoEndpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(EchoEndpoint.closeReason.getCloseCode().getCode()).isEqualTo(1000);
    }
  }

  /** Proves an HTTP rejection fails the WebSocket step before any STOMP attempt. */
  @Test
  void rejectsFailedWebSocketUpgrade() {
    assertThatThrownBy(() -> manager.verifyWebSocketUpgrade(url("/missing")))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Raw WebSocket upgrade probe failed");
    assertThat(manager.getSession()).isNull();
    assertThat(EchoEndpoint.frames.get()).isZero();
  }

  /** Proves a WebSocket-only peer fails the separate STOMP step and its socket is released. */
  @Test
  void stompTimeoutClosesTheVerifiedSocket() throws Exception {
    EchoEndpoint.reply = false;
    manager.verifyWebSocketUpgrade(url("/ws"));
    manager.setConnectionTimeout(1);
    assertThatThrownBy(() -> manager.connectStompUsingExistingWebSocket())
        .isInstanceOf(AssertionError.class).hasMessageContaining("STOMP CONNECTED");
    assertThat(EchoEndpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(EchoEndpoint.opens.get()).isEqualTo(1);
  }

  /** Confirms removing Tyrus leaves a Jakarta WebSocket provider available for Tiger. */
  @Test
  void tomcatRemainsAvailableAsStandardWebSocketProvider() throws Exception {
    var container = ContainerProvider.getWebSocketContainer();
    assertThat(container.getClass().getName()).startsWith("org.apache.tomcat.websocket.");
    ProxySelector.setDefault(ProxySelector.of(null));
    var client = new org.springframework.web.socket.messaging.WebSocketStompClient(
        new org.springframework.web.socket.client.standard.StandardWebSocketClient(container));
    try {
      var session = client.connectAsync(url("/ws"),
          new org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter() {})
          .get(5, TimeUnit.SECONDS);
      assertThat(session.isConnected()).isTrue();
      session.disconnect();
    } finally {
      ((org.apache.tomcat.websocket.WsWebSocketContainer) container).destroy();
    }
  }

  /** Confirms the shared factory also bypasses the proxy for raw WebSocket load connections. */
  @Test
  void stompLoadConnectionsRemainDirectAndCloseTogether() {
    try (var load = new de.gematik.zeta.services.WebSocketLoadService()) {
      load.openStompConnections(url("/ws"), 2);
      assertThat(load.getOpenConnectionCount()).isEqualTo(2);
      assertThat(load.getFailureCount()).isZero();
      assertThat(EchoEndpoint.frames.get()).isGreaterThanOrEqualTo(2);
      load.close();
      assertThat(load.getOpenConnectionCount()).isZero();
    }
  }

  /** Verifies functional-test TLS, including an untrusted name, without modifying global TLS. */
  @Test
  void secureWebSocketBypassesProxyWithConnectionLocalTestTls() throws Exception {
    var keyStore = directory.resolve("websocket-test.p12");
    var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
    var generation = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=fixture.invalid",
        "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", "changeit", "-noprompt")
        .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
    assertThat(generation.waitFor(20, TimeUnit.SECONDS)).isTrue();
    assertThat(generation.exitValue()).isZero();
    var connector = new org.apache.catalina.connector.Connector();
    connector.setPort(0);
    connector.setScheme("https");
    connector.setSecure(true);
    connector.setProperty("address", "127.0.0.1");
    connector.setProperty("SSLEnabled", "true");
    var ssl = new org.apache.tomcat.util.net.SSLHostConfig();
    var certificate = new org.apache.tomcat.util.net.SSLHostConfigCertificate(ssl,
        org.apache.tomcat.util.net.SSLHostConfigCertificate.Type.RSA);
    certificate.setCertificateKeystoreFile(keyStore.toString());
    certificate.setCertificateKeystorePassword("changeit");
    certificate.setCertificateKeystoreType("PKCS12");
    ssl.addCertificate(certificate);
    connector.addSslHostConfig(ssl);
    server.getService().addConnector(connector);
    final var previousTls = javax.net.ssl.SSLContext.getDefault();
    final var previousVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
    manager.verifyWebSocketUpgrade("wss://127.0.0.1:" + connector.getLocalPort() + "/ws");
    assertThat(EchoEndpoint.frames.get()).isZero();
    manager.connectStompUsingExistingWebSocket();
    assertThat(manager.getSession().isConnected()).isTrue();
    assertThat(javax.net.ssl.SSLContext.getDefault()).isSameAs(previousTls);
    assertThat(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()).isSameAs(previousVerifier);
  }

  /** Minimal local STOMP peer; Spring remains responsible for the production client protocol. */
  @ServerEndpoint(value = "/ws", subprotocols = {"v12.stomp"})
  public static class EchoEndpoint {
    static final AtomicInteger opens = new AtomicInteger();
    static final AtomicInteger frames = new AtomicInteger();
    static final AtomicInteger pendingConnects = new AtomicInteger();
    static final AtomicInteger maxPendingConnects = new AtomicInteger();
    static final AtomicInteger rejectConnectsRemaining = new AtomicInteger();
    static final java.util.List<Session> openedSessions = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.concurrent.atomic.AtomicBoolean rejectFirstConnect =
        new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicBoolean ignoreFirstConnect =
        new java.util.concurrent.atomic.AtomicBoolean();
    static volatile boolean reply;
    static volatile boolean rejectStompFrame;
    static volatile CountDownLatch connectStarted;
    static volatile CountDownLatch releaseConnects;
    static volatile long connectDelayMillis;
    static volatile boolean binaryReplies;
    static volatile String heartbeatHeader;
    static volatile CountDownLatch heartbeatReceived;
    static volatile CountDownLatch closed;
    static volatile CloseReason closeReason;

    /** Counts HTTP upgrades independently of STOMP frames. */
    @OnOpen
    public void opened(Session session) {
      opens.incrementAndGet();
      openedSessions.add(session);
    }

    /** Replies to CONNECT and subscription frames using deterministic fragmented messages. */
    @OnMessage
    public void message(Session session, String frame) throws Exception {
      frames.incrementAndGet();
      if (frame.equals("\n")) {
        heartbeatReceived.countDown();
      }
      if (frame.startsWith("CONNECT\n")) {
        connectStarted.countDown();
      }
      if (!reply) {
        return;
      }
      if (frame.startsWith("CONNECT\n")) {
        if (ignoreFirstConnect.getAndSet(false)) {
          return;
        }
        maxPendingConnects.accumulateAndGet(pendingConnects.incrementAndGet(), Math::max);
        try {
          if (!releaseConnects.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("CONNECT fixture gate did not open");
          }
          if (rejectFirstConnect.getAndSet(false)) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "CONNECT rejected"));
            return;
          }
          if (rejectConnectsRemaining.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
            session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Temporary connection loss"));
            return;
          }
          if (rejectStompFrame) {
            sendFragmented(session, "ERROR\nmessage:CONNECT rejected\n\nrejected\0");
            return;
          }
          if (connectDelayMillis > 0) {
            Thread.sleep(connectDelayMillis);
          }
          sendFragmented(session, "CONNECTED\nversion:1.2\nheart-beat:" + heartbeatHeader + "\n\n\0");
        } finally {
          pendingConnects.decrementAndGet();
        }
      } else if (frame.startsWith("SUBSCRIBE\n")) {
        sendFragmented(session,
            "MESSAGE\nsubscription:test-sub\ndestination:/queue/replies\nmessage-id:1\n\n{\"status\":\"ok\"}\0");
      }
    }

    /** Exercises text or binary fragmentation without changing the STOMP content. */
    private void sendFragmented(Session session, String frame) throws Exception {
      int middle = frame.length() / 2;
      if (binaryReplies) {
        session.getBasicRemote().sendBinary(java.nio.ByteBuffer.wrap(
            frame.substring(0, middle).getBytes(java.nio.charset.StandardCharsets.UTF_8)), false);
        session.getBasicRemote().sendBinary(java.nio.ByteBuffer.wrap(
            frame.substring(middle).getBytes(java.nio.charset.StandardCharsets.UTF_8)), true);
      } else {
        session.getBasicRemote().sendText(frame.substring(0, middle), false);
        session.getBasicRemote().sendText(frame.substring(middle), true);
      }
    }

    /** Signals that a timeout or cleanup actually closed the native socket. */
    @OnClose
    public void closed(Session session, CloseReason reason) {
      closeReason = reason;
      closed.countDown();
    }
  }
}
