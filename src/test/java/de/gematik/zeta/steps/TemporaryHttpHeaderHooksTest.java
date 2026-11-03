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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.test.tiger.lib.TigerHttpClient;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Regression checks for temporary headers across failed requests and scenario cleanup. */
class TemporaryHttpHeaderHooksTest {

  /** Removes unit-test header values from the global HTTP configuration. */
  @AfterEach
  void clearHeaders() {
    var http = new HttpGlueCode();
    for (var name : List.of("popp", "Idempotency-Key", "X-Unrelated")) {
      http.clearDefaultHeader(name);
    }
  }

  /**
   * A transport failure cannot leak headers into cleanup or the following scenario.
   *
   * @throws Exception if the local HTTP fixture cannot be started
   */
  @Test
  void restoresHeadersAfterTransportFailureBeforeCleanupRequests() throws Exception {
    clearHeaders();
    var hooks = new TemporaryHttpHeaderHooks();
    hooks.capture();
    var http = new HttpGlueCode();
    http.setDefaultHeader("popp", "temporary-popp");
    http.setDefaultHeader("Idempotency-Key", "temporary-key");
    http.setDefaultHeader("X-Unrelated", "keep");
    var unavailable = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    var failedPort = unavailable.getAddress().getPort();
    unavailable.start();
    unavailable.stop(0);
    assertThrows(ConnectException.class,
        () -> TigerHttpClient.givenDefaultSpec().post("http://localhost:" + failedPort + "/events"));
    hooks.restore();

    var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
    var cleanup = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    cleanup.createContext("/pushers", exchange -> {
      headers.set(exchange.getRequestHeaders());
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    cleanup.start();
    try {
      assertEquals(204, TigerHttpClient.givenDefaultSpec()
          .delete("http://localhost:" + cleanup.getAddress().getPort() + "/pushers").statusCode());
      assertNull(headers.get().getFirst("popp"));
      assertNull(headers.get().getFirst("Idempotency-Key"));
      assertEquals("keep", headers.get().getFirst("X-Unrelated"));
      var next = new TemporaryHttpHeaderHooks();
      next.capture();
      next.restore();
      assertTrue(TigerGlobalConfiguration.readStringOptional("tiger.httpClient.defaultHeader.popp").isEmpty());
      assertTrue(TigerGlobalConfiguration.readStringOptional("tiger.httpClient.defaultHeader.Idempotency-Key").isEmpty());
    } finally {
      cleanup.stop(0);
    }
  }

  /** Existing header values are restored while unrelated defaults remain intact. */
  @Test
  void restoresOriginalValuesAfterExplicitDeletion() {
    var http = new HttpGlueCode();
    http.setDefaultHeader("popp", "original-popp");
    http.setDefaultHeader("Idempotency-Key", "original-key");
    var hooks = new TemporaryHttpHeaderHooks();
    hooks.capture();
    http.setDefaultHeader("popp", "temporary-popp");
    http.clearDefaultHeader("Idempotency-Key");
    hooks.restore();
    new NotificationRetentionSteps().cleanup();
    assertEquals("original-popp", TigerGlobalConfiguration.readString("tiger.httpClient.defaultHeader.popp"));
    assertEquals("original-key", TigerGlobalConfiguration.readString("tiger.httpClient.defaultHeader.Idempotency-Key"));
  }

  /** Both restorations are attempted when one header cannot be cleared. */
  @Test
  void attemptsIndependentHeaderRestorationAfterFailure() {
    clearHeaders();
    var hooks = new TemporaryHttpHeaderHooks();
    hooks.capture();
    try (var http = mockConstruction(HttpGlueCode.class, (glue, context) ->
        doThrow(new AssertionError("header failure")).when(glue).clearDefaultHeader("popp"))) {
      assertThrows(AssertionError.class, hooks::restore);
      verify(http.constructed().getFirst()).clearDefaultHeader("Idempotency-Key");
    }
  }

  /**
   * Header restoration applies to every scenario and precedes notification cleanup.
   *
   * @throws Exception if lifecycle annotations cannot be read
   */
  @Test
  void restorationRunsWithoutTagBeforePoolCleanup() throws Exception {
    var capture = TemporaryHttpHeaderHooks.class.getMethod("capture").getAnnotation(Before.class);
    var restore = TemporaryHttpHeaderHooks.class.getMethod("restore").getAnnotation(After.class);
    assertEquals("", capture.value());
    assertEquals("", restore.value());
    assertEquals(Integer.MIN_VALUE, capture.order());
    var cleanup = Hooks.class.getMethod("resetNotificationPushers", Scenario.class).getAnnotation(After.class);
    assertTrue(restore.order() > cleanup.order());
  }
}
