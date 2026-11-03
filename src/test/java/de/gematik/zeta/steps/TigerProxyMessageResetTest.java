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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/** Verifies the standalone message-reset wire contract and setup failure handling. */
class TigerProxyMessageResetTest {
  private final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{\"numMsgs\":0}");
  private HttpServer server;
  private MockedStatic<TigerGlobalConfiguration> config;

  /**
   * Starts a loopback-only standalone management endpoint and isolates proxy configuration.
   *
   * @throws IOException if the embedded endpoint cannot be started
   */
  @BeforeEach
  void startEndpoint() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
      var bytes = body.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    });
    server.start();
    config = mockStatic(TigerGlobalConfiguration.class);
    config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
        .thenAnswer(call -> call.getArgument(0));
    config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
        .thenReturn(Optional.of("unit-proxy"));
    config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
    config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.resetMessagesPath}"))
        .thenReturn("/resetMessages");
  }

  /** Stops the endpoint and releases thread-local mocks and interruption state. */
  @AfterEach
  void stopEndpoint() {
    if (config != null) {
      config.close();
    }
    if (server != null) {
      server.stop(0);
    }
    Thread.interrupted();
  }

  /**
   * Accepts an empty history and a history containing messages with exactly one management call.
   *
   * @param removed number of removed standalone messages
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 12})
  void acknowledgedResetUsesOneDirectGet(int removed) {
    body.set("{\"numMsgs\":" + removed + "}");
    new TigerProxyManipulationsSteps().resetTigerProxyMessagesStrictly();
    assertThat(requests).containsExactly("GET /resetMessages");
  }

  /**
   * Rejects HTTP success without a valid reset acknowledgement.
   *
   * @param response malformed or invalid acknowledgement
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "{}", "not-json", "[]", "{\"numMsgs\":-1}",
      "{\"numMsgs\":\"0\"}", "{\"numMsgs\":0.5}", "{\"numMsgs\":2147483648}"})
  void invalidAcknowledgementFailsSetup(String response) {
    body.set(response);
    assertThatThrownBy(() -> new TigerProxyManipulationsSteps().resetTigerProxyMessagesStrictly())
        .isInstanceOf(AssertionError.class).hasMessageContaining("message reset");
    assertThat(requests).containsExactly("GET /resetMessages");
  }

  /**
   * Requires the management endpoint's successful synchronous HTTP status.
   *
   * @param responseStatus rejected HTTP status
   */
  @ParameterizedTest
  @ValueSource(ints = {202, 404, 500, 503})
  void rejectedResetFailsSetupWithoutRetry(int responseStatus) {
    status.set(responseStatus);
    assertThatThrownBy(() -> new TigerProxyManipulationsSteps().resetTigerProxyMessagesStrictly())
        .isInstanceOf(AssertionError.class).hasCauseInstanceOf(IOException.class);
    assertThat(requests).containsExactly("GET /resetMessages");
  }

  /** Requires the remote endpoint to be reachable before setup can continue. */
  @Test
  void unreachableStandaloneFailsSetup() {
    server.stop(0);
    assertThatThrownBy(() -> new TigerProxyManipulationsSteps().resetTigerProxyMessagesStrictly())
        .isInstanceOf(AssertionError.class).hasCauseInstanceOf(IOException.class);
    assertThat(requests).isEmpty();
  }

  /**
   * Refuses to silently skip a required reset when proxy configuration is absent or unresolved.
   *
   * @param configured whether the standalone proxy is selected
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingConfigurationFailsSetup(boolean configured) {
    config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
        .thenReturn(configured ? Optional.of("unit-proxy") : Optional.empty());
    config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}"))
        .thenReturn("${paths.tigerProxy.baseUrl}");
    assertThatThrownBy(() -> new TigerProxyManipulationsSteps().resetTigerProxyMessagesStrictly())
        .isInstanceOf(AssertionError.class).hasMessageContaining("required");
    assertThat(requests).isEmpty();
  }

  /** Preserves cancellation instead of treating an interrupted reset as successful setup. */
  @Test
  void interruptedResetFailsSetupAndPreservesInterrupt() {
    Thread.currentThread().interrupt();
    assertThatThrownBy(() -> new TigerProxyManipulationsSteps().resetTigerProxyMessagesStrictly())
        .isInstanceOf(AssertionError.class).hasCauseInstanceOf(InterruptedException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    assertThat(requests).isEmpty();
  }
}
