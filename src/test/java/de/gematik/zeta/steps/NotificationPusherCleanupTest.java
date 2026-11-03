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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.NotificationKvnrPool;
import de.gematik.zeta.services.NotificationKvnrPool.Reservation;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Exercises cleanup against an HTTP fixture including identity mismatch and partial failures. */
class NotificationPusherCleanupTest {
  private final Reservation identity = new Reservation("owned", "D198916288", "D198916288@example.test");
  private final TestDriverConfigurationService driver = mock(TestDriverConfigurationService.class);
  private final TigerProxyAccessTokenExtractor tokens = mock(TigerProxyAccessTokenExtractor.class);
  private final TigerProxyManipulationsSteps proxy = mock(TigerProxyManipulationsSteps.class);
  private final List<String> requests = new ArrayList<>();
  private HttpServer server;
  private String body = "[]";
  private boolean deleteFails;
  private boolean retainPushers;
  private int reads;
  private String previousEndpoint;
  private String previousPath;

  @BeforeEach
  void setUp() throws IOException {
    previousEndpoint = TigerGlobalConfiguration.readStringOptional("paths.client.notifications.pushers").orElse(null);
    previousPath = TigerGlobalConfiguration.readStringOptional("paths.guard.notificationPushersPath").orElse(null);
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/pushers", exchange -> {
      requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
      boolean get = exchange.getRequestMethod().equals("GET");
      if (get) {
        reads++;
      }
      var bytes = (get ? (reads == 1 || deleteFails || retainPushers ? body : "[]") : "{}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(!get && deleteFails ? 500 : 200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    TigerGlobalConfiguration.putValue("paths.client.notifications.pushers", "http://localhost:" + server.getAddress().getPort() + "/pushers");
    TigerGlobalConfiguration.putValue("paths.guard.notificationPushersPath", "/push/v1/pushers");
    tokenSubject(identity.kvnr());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    OidcScenarioContext.clear();
    if (previousEndpoint != null) {
      TigerGlobalConfiguration.putValue("paths.client.notifications.pushers", previousEndpoint);
    }
    if (previousPath != null) {
      TigerGlobalConfiguration.putValue("paths.guard.notificationPushersPath", previousPath);
    }
  }

  /** Exercises the real hook and cleanup against separate, mutable stores for two reserved KVNRs. */
  @Test
  void hookDeletesPushersForBothReservedIdentitiesAndPreservesUnownedIdentity() {
    var pool = new NotificationKvnrPool(List.of("D198916288", "R379696303"));
    var first = pool.reserve("example.test");
    var second = pool.reserve("example.test");
    OidcScenarioContext.register(pool, first);
    OidcScenarioContext.register(pool, second);
    var stores = new ConcurrentHashMap<String, List<Map<String, String>>>();
    stores.put(first.kvnr(), new CopyOnWriteArrayList<>(List.of(Map.of("pushkey", "first", "app_id", "app1"))));
    stores.put(second.kvnr(), new CopyOnWriteArrayList<>(List.of(Map.of("pushkey", "second", "app_id", "app2"))));
    stores.put("unowned", new CopyOnWriteArrayList<>(List.of(Map.of("pushkey", "first", "app_id", "app1"))));
    var currentIdentity = new AtomicReference<>(second.kvnr());
    doAnswer(invocation -> {
      currentIdentity.set(null);
      return null;
    }).when(driver).reset();
    doAnswer(invocation -> {
      currentIdentity.set(invocation.getArgument(0));
      return null;
    }).when(driver).setKvnrEmail(any(), any());
    when(tokens.extractLatestAccessToken(any(), eq("/push/v1/pushers")))
        .thenAnswer(invocation -> token(currentIdentity.get()));
    var observations = new CopyOnWriteArrayList<String>();
    server.removeContext("/pushers");
    server.createContext("/pushers", exchange -> {
      var kvnr = currentIdentity.get();
      var pushers = kvnr == null ? null : stores.get(kvnr);
      int status = pushers == null ? 401 : 200;
      if (pushers != null && exchange.getRequestMethod().equals("DELETE")) {
        var query = exchange.getRequestURI().getRawQuery();
        boolean removed = pushers.removeIf(pusher -> query.equals(
            "pushkey=" + pusher.get("pushkey") + "&appId=" + pusher.get("app_id")));
        status = removed ? 200 : 404;
      }
      observations.add(kvnr + " " + exchange.getRequestMethod() + " " + (pushers == null ? -1 : pushers.size()));
      var bytes = new ObjectMapper().writeValueAsBytes(pushers);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });

    var cleanup = new NotificationPusherCleanup(driver, tokens, proxy);
    new Hooks(mock(ZetaDeploymentModificationService.class), proxy, driver, cleanup)
        .resetNotificationPushers(mock(Scenario.class));

    assertThat(observations).containsExactly(
        first.kvnr() + " GET 1", first.kvnr() + " DELETE 0", first.kvnr() + " GET 0",
        second.kvnr() + " GET 1", second.kvnr() + " DELETE 0", second.kvnr() + " GET 0");
    assertThat(stores.get(first.kvnr())).isEmpty();
    assertThat(stores.get(second.kvnr())).isEmpty();
    assertThat(stores.get("unowned")).containsExactly(Map.of("pushkey", "first", "app_id", "app1"));
    assertThat(List.of(pool.reserve("example.test").kvnr(), pool.reserve("example.test").kvnr()))
        .containsExactlyInAnyOrder(first.kvnr(), second.kvnr());
  }

  /** Repeated cleanup logins keep the KVNR but never reuse a previous Keycloak F1 binding. */
  @Test
  void usesFreshBindingEmailForEveryResetWithoutChangingReservedKvnr() {
    var cleanup = new NotificationPusherCleanup(driver, tokens, proxy);
    cleanup.clean(identity);
    cleanup.clean(identity);
    var emails = ArgumentCaptor.forClass(String.class);
    verify(driver, times(2)).setKvnrEmail(eq(identity.kvnr()), emails.capture());
    assertThat(emails.getAllValues()).doesNotHaveDuplicates().allSatisfy(email ->
        assertThat(email).startsWith(identity.kvnr() + "-").endsWith("@example.test"));
    assertThat(requests).containsExactly("GET /pushers", "GET /pushers", "GET /pushers", "GET /pushers");
  }

  @Test
  void restoresIdentityAndDeletesEveryDynamicallyListedPusher() {
    body = """
        [{"pushkey":"first/+&", "app_id":"app=1"}, {"pushkey":"second", "app_id":"app2"}]
        """;
    new NotificationPusherCleanup(driver, tokens, proxy).clean(identity);
    var order = inOrder(proxy, driver, tokens);
    order.verify(proxy).stopAllTigerProxyManipulationsStrictly();
    order.verify(driver).reset();
    var email = ArgumentCaptor.forClass(String.class);
    order.verify(driver).setKvnrEmail(eq(identity.kvnr()), email.capture());
    assertThat(email.getValue()).startsWith(identity.kvnr() + "-").endsWith("@example.test");
    order.verify(tokens).captureMessageIds();
    assertThat(requests).containsExactly("GET /pushers", "DELETE /pushers?pushkey=first%2F%2B%26&appId=app%3D1",
        "DELETE /pushers?pushkey=second&appId=app2", "GET /pushers");
  }

  @Test
  void failedManipulationResetPreventsResourceOperations() {
    doThrow(new AssertionError("proxy reset failed")).when(proxy).stopAllTigerProxyManipulationsStrictly();
    assertThatThrownBy(() -> new NotificationPusherCleanup(driver, tokens, proxy).clean(identity))
        .isInstanceOf(AssertionError.class).hasMessage("proxy reset failed");
    verifyNoInteractions(driver);
    assertThat(requests).isEmpty();
  }

  @Test
  void refusesToDeleteForWrongAuthenticatedIdentity() {
    body = "[{\"pushkey\":\"other-user\",\"app_id\":\"app\"}]";
    tokenSubject("R379696303");
    assertThatThrownBy(() -> new NotificationPusherCleanup(driver, tokens, proxy).clean(identity))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Refusing cleanup");
    assertThat(requests).containsExactly("GET /pushers");
  }

  @Test
  void malformedPusherListCannotCountAsEmpty() {
    body = "{\"unexpected\":[]}";
    assertThatThrownBy(() -> new NotificationPusherCleanup(driver, tokens, proxy).clean(identity))
        .isInstanceOf(AssertionError.class).hasMessageContaining("JSON array");
    assertThat(requests).containsExactly("GET /pushers");
  }

  @Test
  void successfulDeleteResponsesStillRequireEmptyFinalList() {
    body = "[{\"pushkey\":\"retained\",\"app_id\":\"app\"}]";
    retainPushers = true;
    assertThatThrownBy(() -> new NotificationPusherCleanup(driver, tokens, proxy).clean(identity))
        .isInstanceOf(AssertionError.class)
        .satisfies(failure -> assertThat(failure.getSuppressed()[0]).hasMessageContaining("Pushers remain"));
    assertThat(requests).hasSize(3);
  }

  @Test
  void attemptsRemainingDeletesAndVerificationAfterFailure() {
    body = "[{\"pushkey\":\"first\",\"app_id\":\"app\"},{\"pushkey\":\"second\",\"app_id\":\"app\"}]";
    deleteFails = true;
    assertThatThrownBy(() -> new NotificationPusherCleanup(driver, tokens, proxy).clean(identity))
        .isInstanceOf(AssertionError.class).satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(3));
    assertThat(requests).hasSize(4);
    assertThat(requests.get(2)).startsWith("DELETE /pushers?pushkey=second");
    assertThat(requests.get(3)).isEqualTo("GET /pushers");
  }

  private void tokenSubject(String subject) {
    when(tokens.extractLatestAccessToken(any(), eq("/push/v1/pushers"))).thenReturn(token(subject));
  }

  private static String token(String subject) {
    var payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
    return "header." + payload + ".signature";
  }
}
