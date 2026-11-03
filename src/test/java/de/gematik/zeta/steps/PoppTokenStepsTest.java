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

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link PoppTokenSteps}.
 */
class PoppTokenStepsTest {

  private HttpServer server;

  /**
   * Stops the embedded HTTP server and clears test configuration values.
   */
  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    clear("paths.popp.tokenGenerator");
    clear("testdata.popp.proofMethod");
    clear("testdata.popp.patientId");
    clear("testdata.popp.insurerId");
    clear("testdata.popp.actorProfessionOid");
    clear("POPP_TOKEN");
  }

  /**
   * Verifies that the step sends the supplied actor data to the token generator.
   *
   * @throws Exception on embedded server setup failure
   */
  @Test
  void fetchFreshPoppTokenUsesProvidedActorData() throws Exception {
    var tokenRequestBody = new AtomicReference<String>();

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/token-generator", exchange -> {
      tokenRequestBody.set(readBody(exchange.getRequestBody()));
      writeResponse(exchange, "{\"tokenResults\":[\"GENERATED_POPP_TOKEN\"]}");
    });
    server.start();

    configure();
    var steps = new PoppTokenSteps();

    steps.fetchFreshPoppToken("1-20014060625", "1.2.276.0.76.4.50", "POPP_TOKEN");

    assertThat(read("POPP_TOKEN")).isEqualTo("GENERATED_POPP_TOKEN");
    assertThat(tokenRequestBody.get())
        .contains("\"actorId\":\"1-20014060625\"")
        .contains("\"actorProfessionOid\":\"1.2.276.0.76.4.50\"")
        .contains("\"patientId\":\"X110639491\"")
        .contains("\"insurerId\":\"109500969\"");
  }

  /**
   * Proves direct HTTPS setup works with a test certificate without changing global TLS defaults.
   *
   * @throws Exception if fixture startup or token generation fails
   */
  @Test
  void fetchesTokenOverDirectHttpsWithTestCertificate() throws Exception {
    final var previousProxy = ProxySelector.getDefault();
    final var defaultContext = SSLContext.getDefault();
    final var defaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
    final var defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    var httpsServer = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
    server = httpsServer;
    httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
    var method = new AtomicReference<String>();
    var contentType = new AtomicReference<String>();
    var requestBody = new AtomicReference<String>();
    server.createContext("/token-generator", exchange -> {
      method.set(exchange.getRequestMethod());
      contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      requestBody.set(readBody(exchange.getRequestBody()));
      writeResponse(exchange, "{\"tokenResults\":[\"HTTPS_POPP_TOKEN\"]}");
    });
    server.start();
    configure();
    put("paths.popp.tokenGenerator", "https://localhost:" + server.getAddress().getPort() + "/token-generator");
    try {
      ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("localhost", 1)));
      // Keep this SSLHandshakeException control independent of globally installed BCJSSE providers.
      var strictContext = SSLContext.getInstance("TLS", "SunJSSE");
      strictContext.init(null, null, null);
      var strictConnection = (HttpsURLConnection) URI.create(read("paths.popp.tokenGenerator"))
          .toURL().openConnection(Proxy.NO_PROXY);
      strictConnection.setSSLSocketFactory(strictContext.getSocketFactory());
      strictConnection.setConnectTimeout(2000);
      strictConnection.setReadTimeout(2000);
      try {
        assertThatThrownBy(strictConnection::getResponseCode).isInstanceOf(SSLHandshakeException.class);
      } finally {
        strictConnection.disconnect();
      }
      new PoppTokenSteps().fetchFreshPoppToken("1-20014060625", "1.2.276.0.76.4.50", "POPP_TOKEN");
      assertThat(read("POPP_TOKEN")).isEqualTo("HTTPS_POPP_TOKEN");
      assertThat(method.get()).isEqualTo("POST");
      assertThat(contentType.get()).startsWith("application/json");
      assertThat(requestBody.get()).contains("\"actorId\":\"1-20014060625\"");
      assertThat(SSLContext.getDefault()).isSameAs(defaultContext);
      assertThat(HttpsURLConnection.getDefaultSSLSocketFactory()).isSameAs(defaultSocketFactory);
      assertThat(HttpsURLConnection.getDefaultHostnameVerifier()).isSameAs(defaultHostnameVerifier);
    } finally {
      ProxySelector.setDefault(previousProxy);
    }
  }

  /**
   * Requires DNS SNI with Bouncy Castle and omits SNI for IPv4 and bracketed IPv6 URLs.
   *
   * @param host URL host used for the direct backend call
   * @param expectedName expected SNI name, or empty for IP literals
   * @throws Exception if fixture startup or token generation fails
   */
  @ParameterizedTest
  @CsvSource({"localhost, localhost", "127.0.0.1, ''", "[::1], ''"})
  void sendsDestinationSniWithBouncyCastle(String host, String expectedName) throws Exception {
    final var previousProxy = ProxySelector.getDefault();
    var httpsServer = HttpsServer.create(new InetSocketAddress(host, 0), 0);
    server = httpsServer;
    httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
    var observedName = new AtomicReference<String>();
    server.createContext("/token-generator", exchange -> {
      var session = (ExtendedSSLSession) ((HttpsExchange) exchange).getSSLSession();
      var names = session.getRequestedServerNames();
      var name = names.isEmpty() ? "" : ((SNIHostName) names.getFirst()).getAsciiName();
      observedName.set(name);
      if (!expectedName.equals(name)) {
        exchange.sendResponseHeaders(421, -1);
        exchange.close();
        return;
      }
      exchange.getRequestBody().readAllBytes();
      writeResponse(exchange, "{\"tokenResults\":[\"SNI_POPP_TOKEN\"]}");
    });
    server.start();
    configure();
    put("paths.popp.tokenGenerator", "https://" + host + ":" + server.getAddress().getPort() + "/token-generator");
    try {
      ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("localhost", 1)));
      new PoppTokenSteps(bouncyCastleClientContext())
          .fetchFreshPoppToken("1-20014060625", "1.2.276.0.76.4.50", "POPP_TOKEN");
      assertThat(observedName.get()).isEqualTo(expectedName);
      assertThat(read("POPP_TOKEN")).isEqualTo("SNI_POPP_TOKEN");
    } finally {
      ProxySelector.setDefault(previousProxy);
    }
  }

  /**
   * Creates a Bouncy Castle client trusting only the server fixture, without installing providers globally.
   *
   * @return isolated client context
   * @throws Exception if the context or trust store cannot be initialized
   */
  private SSLContext bouncyCastleClientContext() throws Exception {
    var trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    try (var input = Files.newInputStream(
        Path.of("src/test/resources/tls-test-tool/certificates/ecdsa/zeta-tls-test-tool-server_good.pem"))) {
      trustStore.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    var context = SSLContext.getInstance("TLS", new BouncyCastleJsseProvider(new BouncyCastleProvider()));
    context.init(null, trustManagers.getTrustManagers(), null);
    return context;
  }

  /**
   * Loads an existing TLS test certificate whose issuer and hostname are not trusted for localhost.
   *
   * @return server context containing only test-owned key material
   * @throws Exception if fixture material cannot be loaded
   */
  private SSLContext serverSslContext() throws Exception {
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

  /**
   * Configures Tiger values used by the PoPP token step.
   */
  private void configure() {
    put("paths.popp.tokenGenerator", baseUrl() + "/token-generator");
    put("testdata.popp.proofMethod", "ehc-practitioner-user-x509");
    put("testdata.popp.patientId", "X110639491");
    put("testdata.popp.insurerId", "109500969");
    put("testdata.popp.actorProfessionOid", "1.2.276.0.76.4.32");
  }

  /**
   * Stores one Tiger test-context value.
   *
   * @param key configuration key
   * @param value configuration value
   */
  private void put(String key, String value) {
    TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Clears one Tiger test-context value.
   *
   * @param key configuration key
   */
  private void clear(String key) {
    TigerGlobalConfiguration.putValue(key, "", ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Reads one Tiger configuration value.
   *
   * @param key configuration key
   * @return configuration value
   */
  private String read(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .orElseThrow(() -> new AssertionError("Missing Tiger variable: " + key));
  }

  /**
   * Returns the embedded server base URL.
   *
   * @return base URL
   */
  private String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  /**
   * Writes one JSON response.
   *
   * @param exchange HTTP exchange
   * @param body response body
   * @throws IOException if writing fails
   */
  private void writeResponse(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /**
   * Reads a request body as UTF-8.
   *
   * @param inputStream request body stream
   * @return decoded request body
   * @throws IOException if reading fails
   */
  private String readBody(InputStream inputStream) throws IOException {
    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
  }
}
