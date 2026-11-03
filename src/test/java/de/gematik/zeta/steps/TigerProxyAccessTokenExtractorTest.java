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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests fresh-token selection against a persistent remote message history. */
class TigerProxyAccessTokenExtractorTest {

  private final Map<String, String> messages = new LinkedHashMap<>();
  private final Map<String, String> previousConfiguration = new LinkedHashMap<>();
  private final AtomicInteger contentReads = new AtomicInteger();
  private HttpServer server;
  private ProxySelector previousProxy;

  /**
   * Starts a proxy API fixture while the JVM proxy is deliberately unavailable.
   *
   * @throws IOException if the fixture cannot start
   */
  @BeforeEach
  void setUp() throws IOException {
    previousProxy = ProxySelector.getDefault();
    ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("localhost", 1)));
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/getMessagesWithMeta", exchange -> {
      var metadata = messages.keySet().stream().map(id -> Map.of("uuid", id)).toList();
      var bytes = new ObjectMapper().writeValueAsBytes(Map.of("messages", metadata));
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.createContext("/messageContent/", exchange -> {
      contentReads.incrementAndGet();
      var id = exchange.getRequestURI().getPath().substring("/messageContent/".length());
      var bytes = messages.get(id).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    configure("paths.tigerProxy.baseUrl", "http://localhost:" + server.getAddress().getPort());
    configure("paths.tigerProxy.messagesWithMetaPath", "/getMessagesWithMeta");
    configure("paths.tigerProxy.messageContentPath", "/messageContent");
    configure("tiger.tigerProxy.proxyId", "fixture");
    configure("paths.guard.helloZetaPath", "/pep/hello");
    configure("zeta_base_url", "guard.example");
  }

  /** Restores shared state even when an assertion fails. */
  @AfterEach
  void tearDown() {
    Thread.interrupted();
    ProxySelector.setDefault(previousProxy);
    if (server != null) {
      server.stop(0);
    }
    previousConfiguration.forEach((key, value) -> TigerGlobalConfiguration.putValue(
        key, value, ConfigurationValuePrecedence.TEST_CONTEXT));
  }

  /** Proves that old tokens and newer unrelated messages cannot replace the fresh Guard token. */
  @Test
  void selectsNewGuardAuthorizationBeyondTheLastThirtyTwoMessages() {
    messages.put("old", request("eyJold.payload.signature"));
    var extractor = new TigerProxyAccessTokenExtractor(Map::of);
    final var baseline = extractor.captureMessageIds();
    messages.put("fresh", request("eyJfresh.payload.signature"));
    for (var index = 0; index < 40; index++) {
      messages.put("unrelated-" + index, "HTTP/1.1 200 OK\r\n\r\nBearer eyJwrong.payload.signature");
    }
    messages.put("wrong-host", request("eyJwrong.payload.signature").replace("guard.example", "other.example"));
    messages.put("wrong-path", request("eyJwrong.payload.signature").replace("/pep/hello", "/other"));
    assertThat(extractor.extractLatestAccessToken(baseline)).isEqualTo("eyJfresh.payload.signature");
    assertThat(contentReads.get()).isEqualTo(43);
    assertThat(messages).containsKey("old");
  }

  /** Proves that a failed fresh request cannot fall back to a historical token. */
  @Test
  void rejectsHistoryWhenNoFreshRequestAppears() {
    messages.put("old", request("eyJold.payload.signature"));
    var extractor = new TigerProxyAccessTokenExtractor(
        () -> Map.of("local-old", request("eyJlocalold.payload.signature")));
    final var baseline = extractor.captureMessageIds();
    assertThatThrownBy(() -> extractor.extractLatestAccessToken(baseline))
        .isInstanceOf(AssertionError.class).hasMessageContaining("No fresh Access Token");
    assertThat(contentReads.get()).isZero();
  }

  /** Distinguishes request timeouts from lookup expiry without relying on CI scheduling. */
  @ParameterizedTest
  @CsvSource({"10, Could not read the fresh Access Token", "20, No fresh Access Token",
      "21, No fresh Access Token"})
  void classifiesHttpTimeoutAgainstTheLookupDeadline(long elapsedSeconds, String expectedMessage)
      throws IOException, InterruptedException {
    var clock = new AtomicLong();
    var client = mock(HttpClient.class);
    var timeout = new HttpTimeoutException("request timed out");
    when(client.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      clock.set(Duration.ofSeconds(elapsedSeconds).toNanos());
      throw timeout;
    });
    var extractor = new TigerProxyAccessTokenExtractor(Map::of, client, clock::get);

    assertThatThrownBy(() -> extractor.extractLatestAccessToken(Set.of()))
        .isInstanceOf(AssertionError.class).hasMessageContaining(expectedMessage).hasCause(timeout);
  }

  /** Expiry immediately before an HTTP request has the same outcome as expiry during it. */
  @Test
  void reportsLookupExpiryBeforeStartingAnotherRequest() {
    var clock = new AtomicLong();
    var client = mock(HttpClient.class);
    var extractor = new TigerProxyAccessTokenExtractor(() -> {
      clock.set(Duration.ofSeconds(20).toNanos());
      return Map.of("old", request("eyJold.payload.signature"));
    }, client, clock::get);

    assertThatThrownBy(() -> extractor.extractLatestAccessToken(Set.of("old")))
        .isInstanceOf(AssertionError.class).hasMessageContaining("No fresh Access Token")
        .hasCauseInstanceOf(HttpTimeoutException.class);
    verifyNoInteractions(client);
  }

  /** Selects the exact pusher operation even when newer hello traffic exists. */
  @Test
  void selectsTokenForExplicitNotificationPath() {
    var extractor = new TigerProxyAccessTokenExtractor(Map::of);
    var baseline = extractor.captureMessageIds();
    messages.put("notification", request("eyJnotification.payload.signature").replace("/pep/hello", "/push/v1/pushers"));
    messages.put("hello", request("eyJhello.payload.signature"));
    assertThat(extractor.extractLatestAccessToken(baseline, "/push/v1/pushers"))
        .isEqualTo("eyJnotification.payload.signature");
  }

  /** Applies the explicit path to local traffic when the remote parsed history does not advance. */
  @Test
  void selectsLocalTokenForExplicitNotificationPath() {
    var local = new LinkedHashMap<String, String>();
    var extractor = new TigerProxyAccessTokenExtractor(() -> local);
    var baseline = extractor.captureMessageIds();
    local.put("notification", request("eyJnotification.payload.signature").replace("/pep/hello", "/push/v1/pushers"));
    local.put("hello", request("eyJhello.payload.signature"));
    assertThat(extractor.extractLatestAccessToken(baseline, "/push/v1/pushers"))
        .isEqualTo("eyJnotification.payload.signature");
    assertThat(contentReads.get()).isZero();
  }

  /** Verifies that interruption aborts lookup without losing the interrupt flag. */
  @Test
  void preservesInterrupt() {
    var extractor = new TigerProxyAccessTokenExtractor(Map::of);
    final var baseline = extractor.captureMessageIds();
    Thread.currentThread().interrupt();
    assertThatThrownBy(() -> extractor.extractLatestAccessToken(baseline))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Interrupted");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  /** A stale remote parsed prefix cannot hide fresh traffic already downloaded by the scenario. */
  @Test
  void usesFreshLocalGuardRequestWhenRemoteHistoryDoesNotAdvance() {
    messages.put("remote-old", request("eyJremote.payload.signature"));
    var local = new LinkedHashMap<String, String>();
    local.put("local-old", request("eyJold.payload.signature"));
    var extractor = new TigerProxyAccessTokenExtractor(() -> local);
    final var baseline = extractor.captureMessageIds();
    local.put("fresh", request("eyJfresh.payload.signature"));
    local.put("wrong-host", request("eyJwrong.payload.signature").replace("guard.example", "other.example"));
    local.put("wrong-path", request("eyJwrong.payload.signature").replace("/pep/hello", "/other"));
    local.put("response", "HTTP/1.1 200 OK\r\n\r\nDPoP eyJwrong.payload.signature");
    assertThat(extractor.extractLatestAccessToken(baseline)).isEqualTo("eyJfresh.payload.signature");
    assertThat(contentReads.get()).isZero();
  }

  /**
   * Builds a Guard request containing one token.
   *
   * @param token compact token
   * @return raw HTTP message
   */
  private String request(String token) {
    return "GET /pep/hello HTTP/1.1\r\nHost: guard.example\r\nAuthorization: DPoP " + token + "\r\n\r\n";
  }

  /**
   * Overrides a fixture setting and remembers its previous effective value.
   *
   * @param key configuration key
   * @param value fixture value
   */
  private void configure(String key, String value) {
    previousConfiguration.put(key, TigerGlobalConfiguration.readStringOptional(key).orElse(""));
    TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }
}
