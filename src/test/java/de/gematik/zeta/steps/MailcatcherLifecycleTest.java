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

package de.gematik.zeta.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import de.gematik.test.tiger.common.config.TigerConfigurationException;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.TigerTestsuiteMain.AuthenticationProfile;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.opentest4j.TestAbortedException;

/** Checks OIDC mailbox deletion, proxy bypass and setup failure against local HTTP and HTTPS fixtures. */
class MailcatcherLifecycleTest {
  private final List<String> methods = new ArrayList<>();
  private final Hooks hooks = new Hooks(mock(ZetaDeploymentModificationService.class),
      mock(TigerProxyManipulationsSteps.class), mock(TestDriverConfigurationService.class));
  private HttpServer server;
  private MockedStatic<TigerGlobalConfiguration> configuration;
  private MockedStatic<AuthenticationProfile> profile;
  private int deletionStatus = 204;
  private String inventory = "[]";

  /** Starts a local mailbox and resolves the normal hook endpoint to it. */
  @BeforeEach
  void prepareMailbox() throws Exception {
    configuration = mockStatic(TigerGlobalConfiguration.class);
    profile = mockStatic(AuthenticationProfile.class);
    profile.when(AuthenticationProfile::current).thenReturn(AuthenticationProfile.OIDC);
    startMailbox(false);
  }

  /**
   * Starts the mailbox fixture and configures its endpoint for the hook.
   *
   * @param tls whether to serve HTTPS with the test certificate
   * @throws Exception if the server or TLS context cannot be initialized
   */
  private void startMailbox(boolean tls) throws Exception {
    var address = new InetSocketAddress("127.0.0.1", 0);
    if (tls) {
      var httpsServer = HttpsServer.create(address, 0);
      server = httpsServer;
      httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
    } else {
      server = HttpServer.create(address, 0);
    }
    server.createContext("/mailcatcher/messages", exchange -> {
      methods.add(exchange.getRequestMethod());
      if ("DELETE".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(deletionStatus, -1);
      } else {
        var body = inventory.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
    server.start();
    var scheme = tls ? "https" : "http";
    configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders(
        "https://${zeta_base_url}${paths.mailcatcher.messagesPath}"))
        .thenReturn(scheme + "://127.0.0.1:" + server.getAddress().getPort() + "/mailcatcher/messages");
  }

  /** Releases the fixture and restores the thread-local hook state. */
  @AfterEach
  void closeMailbox() {
    profile.close();
    configuration.close();
    server.stop(0);
    Hooks.clearScenarioAborted();
  }

  /** Deletes and verifies the mailbox without consulting Tiger's JVM-wide proxy. */
  @Test
  void clearsAndVerifiesTheMailboxWithoutTheJvmProxy() {
    var previous = ProxySelector.getDefault();
    var selected = new AtomicInteger();
    ProxySelector.setDefault(new ProxySelector() {
      @Override
      public List<Proxy> select(URI uri) {
        selected.incrementAndGet();
        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
      }

      @Override
      public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        throw new AssertionError("Mailcatcher traffic reached the JVM proxy", failure);
      }
    });
    try {
      hooks.resetMailcatcher();
      assertThat(selected).hasValue(0);
      assertThat(methods).containsExactly("DELETE", "GET");
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  /**
   * Accepts the mailbox test certificate without replacing JVM-wide TLS defaults.
   *
   * @throws Exception if the isolated TLS control cannot be initialized
   */
  @Test
  void acceptsTestCertificateWithoutChangingGlobalTlsDefaults() throws Exception {
    server.stop(0);
    startMailbox(true);
    final var defaultContext = SSLContext.getDefault();
    final var defaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
    final var defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    var strictContext = SSLContext.getInstance("TLS", "SunJSSE");
    strictContext.init(null, null, null);
    var endpoint = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/mailcatcher/messages");
    var connection = (HttpsURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
    connection.setSSLSocketFactory(strictContext.getSocketFactory());
    connection.setConnectTimeout(2000);
    connection.setReadTimeout(2000);
    try {
      assertThatThrownBy(connection::getResponseCode).isInstanceOf(SSLHandshakeException.class);
    } finally {
      connection.disconnect();
    }
    var previousProxy = ProxySelector.getDefault();
    try {
      ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("127.0.0.1", 1)));
      hooks.resetMailcatcher();
    } finally {
      ProxySelector.setDefault(previousProxy);
    }
    assertThat(methods).containsExactly("DELETE", "GET");
    assertThat(SSLContext.getDefault()).isSameAs(defaultContext);
    assertThat(HttpsURLConnection.getDefaultSSLSocketFactory()).isSameAs(defaultSocketFactory);
    assertThat(HttpsURLConnection.getDefaultHostnameVerifier()).isSameAs(defaultHostnameVerifier);
  }

  /**
   * Loads the existing test server certificate and private key into an isolated TLS context.
   *
   * @return TLS context for the local mailbox fixture
   * @throws Exception if fixture material cannot be loaded
   */
  private static SSLContext serverSslContext() throws Exception {
    var directory = Path.of("src/test/resources/tls-test-tool/certificates/ecdsa");
    var keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    try (var certificateInput = Files.newInputStream(directory.resolve("zeta-tls-test-tool-server_good.pem"));
        var keyReader = new PEMParser(Files.newBufferedReader(directory.resolve("zeta-tls-test-tool-server.privkey.pem")))) {
      var certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateInput);
      var keyPair = (PEMKeyPair) keyReader.readObject();
      var privateKey = new JcaPEMKeyConverter().getPrivateKey(keyPair.getPrivateKeyInfo());
      keyStore.setKeyEntry("server", privateKey, "fixture".toCharArray(),
          new java.security.cert.Certificate[]{certificate});
    }
    var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, "fixture".toCharArray());
    var context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), null, null);
    return context;
  }

  /** Does not depend on a mailbox when executing the SMB profile. */
  @Test
  void smbDoesNotContactTheMailbox() {
    profile.when(AuthenticationProfile::current).thenReturn(AuthenticationProfile.SMB);
    hooks.resetMailcatcher();
    assertThat(methods).isEmpty();
  }

  /** Does not delete messages after an earlier prerequisite aborted the scenario. */
  @Test
  void abortedScenarioDoesNotContactTheMailbox() {
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@oidc"));
    configuration.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
        .thenReturn(Optional.empty());
    assertThrows(TestAbortedException.class, () -> hooks.skipIfProxyMissing(scenario));
    hooks.resetMailcatcher();
    assertThat(methods).isEmpty();
  }

  /**
   * Preserves prerequisite skips without requiring an available mailbox.
   *
   * @param tag prerequisite that is unavailable in the fixture
   */
  @ParameterizedTest
  @ValueSource(strings = {"@require_kubectl", "@performance", "@longrunning", "@tpm_environment",
      "@deployment_modification"})
  void unavailablePrerequisitesDoNotContactTheMailbox(String tag) {
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of(tag));
    assertThrows(TestAbortedException.class, () -> {
      switch (tag) {
        case "@require_kubectl" -> hooks.skipIfKubectlMissing(scenario);
        case "@performance", "@longrunning" -> hooks.skipPerformanceAndLongRunningScenarios(scenario);
        case "@tpm_environment" -> hooks.skipIfTpmEnvironmentMissing(scenario);
        case "@deployment_modification" -> hooks.verifyDeploymentModification(scenario);
        default -> throw new IllegalArgumentException(tag);
      }
    });
    hooks.resetMailcatcher();
    assertThat(methods).isEmpty();
  }

  /** Gives unresolved configuration the same setup diagnostic as an HTTP reset failure. */
  @Test
  void unresolvedEndpointFailsWithResetDiagnostic() {
    var failure = new TigerConfigurationException("missing mail endpoint");
    configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders(
        "https://${zeta_base_url}${paths.mailcatcher.messagesPath}")).thenThrow(failure);
    assertThatThrownBy(hooks::resetMailcatcher).isInstanceOf(AssertionError.class)
        .hasMessageContaining("Mailcatcher reset failed").hasCause(failure);
    assertThat(methods).isEmpty();
  }

  /** Wraps malformed endpoint URIs before any network request is issued. */
  @Test
  void malformedEndpointFailsWithResetDiagnostic() {
    configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders(
        "https://${zeta_base_url}${paths.mailcatcher.messagesPath}")).thenReturn("invalid URI");
    assertThatThrownBy(hooks::resetMailcatcher).isInstanceOf(AssertionError.class)
        .hasMessageContaining("Mailcatcher reset failed").hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(methods).isEmpty();
  }

  /**
   * Requires an explicit deletion acknowledgement before reading the inventory.
   *
   * @param status unexpected HTTP deletion status
   */
  @ParameterizedTest
  @ValueSource(ints = {200, 500})
  void rejectedDeletionFailsSetup(int status) {
    deletionStatus = status;
    assertThatThrownBy(hooks::resetMailcatcher).isInstanceOf(AssertionError.class);
    assertThat(methods).containsExactly("DELETE");
  }

  /**
   * Rejects deletion acknowledgements without a valid empty mailbox observation.
   *
   * @param body nonempty or malformed inventory
   */
  @ParameterizedTest
  @ValueSource(strings = {"[{\"id\":2346}]", "not JSON"})
  void invalidInventoryFailsSetup(String body) {
    inventory = body;
    assertThatThrownBy(hooks::resetMailcatcher).isInstanceOf(AssertionError.class);
    assertThat(methods).containsExactly("DELETE", "GET");
  }

  /** Places deletion after prerequisite guards and before OTP preflight. */
  @Test
  void resetPrecedesOtpPreflight() throws Exception {
    int guard = Hooks.class.getMethod("verifyOidcProfile").getAnnotation(Before.class).order();
    int reset = Hooks.class.getMethod("resetMailcatcher").getAnnotation(Before.class).order();
    int preflight = Hooks.class.getMethod("skipIfSigningKeyUnavailable", Scenario.class)
        .getAnnotation(Before.class).order();
    assertTrue(guard < reset);
    for (var method : List.of("skipIfKubectlMissing", "skipPerformanceAndLongRunningScenarios",
        "skipIfTpmEnvironmentMissing", "skipIfOidcDecryptionKeyUnavailable", "selectAslProfile",
        "verifyDeploymentModification")) {
      int prerequisite = Hooks.class.getMethod(method, Scenario.class).getAnnotation(Before.class).order();
      assertTrue(prerequisite < reset, method + " must run before mailbox deletion");
    }
    assertTrue(reset < preflight);
    assertTrue(Hooks.class.getMethod("resetMailcatcher").getAnnotation(Before.class).value().isEmpty());
  }
}
