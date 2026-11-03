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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.NotificationKvnrPool;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Verifies scenario ownership survives setup failure and cleanup visits all identities. */
class NotificationPoolLifecycleTest {
  private final NotificationKvnrPool pool = new NotificationKvnrPool(List.of("D198916288", "R379696303"));
  private final TestDriverConfigurationService driver = mock(TestDriverConfigurationService.class);
  private final NotificationPusherCleanup cleanup = mock(NotificationPusherCleanup.class);
  private final TigerProxyManipulationsSteps manipulations = mock(TigerProxyManipulationsSteps.class);

  @AfterEach
  void clear() {
    OidcScenarioContext.clear();
  }

  @Test
  void hookContinuesAfterFailureAndOnlyReleasesCleanIdentity() {
    var first = pool.reserve("example.test");
    var second = pool.reserve("example.test");
    OidcScenarioContext.register(pool, first);
    OidcScenarioContext.register(pool, second);
    doThrow(new AssertionError("failed first cleanup")).when(cleanup).clean(first);
    var hooks = new Hooks(mock(ZetaDeploymentModificationService.class), manipulations, driver, cleanup);
    assertThatThrownBy(() -> hooks.resetNotificationPushers(mock(Scenario.class)))
        .isInstanceOf(AssertionError.class).hasMessageContaining("incomplete");
    verify(cleanup).clean(first);
    verify(cleanup).clean(second);
    verify(driver).reset();
    assertThat(pool.reserve("example.test").kvnr()).isEqualTo(second.kvnr());
    assertThatThrownBy(() -> pool.reserve("example.test")).hasMessageContaining("1 quarantined");
  }

  /** Targeted cleanup must finish with its SDK identity before pool cleanup resets the driver. */
  @Test
  void ownedPusherCleanupRunsBeforePoolIdentityChanges(@TempDir Path records) throws Throwable {
    var identity = pool.reserve("example.test");
    OidcScenarioContext.register(pool, identity);
    var activeIdentity = new AtomicReference<>(identity.kvnr());
    var ownedRequests = new AtomicInteger();
    var response = mock(Response.class);
    when(response.statusCode()).thenReturn(200);
    when(response.asString()).thenReturn("[]");
    var owned = new NotificationLifecycleSteps((method, uri) -> {
      assertThat(activeIdentity.get()).isEqualTo(identity.kvnr());
      ownedRequests.incrementAndGet();
      return response;
    }, records);
    owned.prepareCleanup("zeta-lifecycle-order", "test-app", identity.kvnr(),
        "http://driver/pushers", "example.test", "test");
    doAnswer(invocation -> {
      activeIdentity.set("pool-cleanup");
      return null;
    }).when(cleanup).clean(identity);
    doAnswer(invocation -> {
      activeIdentity.set("reset");
      return null;
    }).when(driver).reset();
    var hooks = new Hooks(mock(ZetaDeploymentModificationService.class), manipulations, driver, cleanup);
    var afterHooks = new TreeMap<Integer, Executable>(Comparator.reverseOrder());
    afterHooks.put(NotificationLifecycleSteps.class.getMethod("cleanup").getAnnotation(After.class).order(),
        owned::cleanup);
    afterHooks.put(Hooks.class.getMethod("resetNotificationPushers", Scenario.class)
        .getAnnotation(After.class).order(), () -> hooks.resetNotificationPushers(mock(Scenario.class)));
    assertThat(afterHooks).hasSize(2);
    for (var hook : afterHooks.values()) {
      hook.execute();
    }
    assertThat(ownedRequests.get()).isEqualTo(2);
    assertThat(activeIdentity.get()).isEqualTo("reset");
    verify(cleanup).clean(identity);
    verify(driver).reset();
  }

  @Test
  void sharedDriverCannotBeAcquiredByAnotherScenario() throws Exception {
    OidcScenarioContext.acquireDriver();
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor.submit(() -> {
        try {
          assertThatThrownBy(OidcScenarioContext::acquireDriver).isInstanceOf(AssertionError.class);
        } finally {
          OidcScenarioContext.clear();
        }
      }).get();
      OidcScenarioContext.clear();
      executor.submit(() -> {
        try {
          OidcScenarioContext.acquireDriver();
        } finally {
          OidcScenarioContext.clear();
        }
      }).get();
    }
  }

  @Test
  void unfinishedReservationsAreQuarantinedOnFinalClear() {
    OidcScenarioContext.register(pool, pool.reserve("example.test"));
    OidcScenarioContext.register(pool, pool.reserve("example.test"));
    OidcScenarioContext.clear();
    assertThatThrownBy(() -> pool.reserve("example.test")).hasMessageContaining("2 quarantined");
    assertThat(OidcScenarioContext.identities()).isEmpty();
  }

  @Test
  void poolOwnershipIsRecordedBeforePreparationCanFail() {
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "example.test");
    doAnswer(invocation -> {
      assertThat(OidcScenarioContext.identities()).hasSize(1);
      assertThat(OidcScenarioContext.identities().getFirst().reservation()).isEqualTo(invocation.getArgument(0));
      throw new AssertionError("setup interrupted");
    }).when(cleanup).clean(any());
    assertThatThrownBy(() -> new OidcTestDataSteps(driver, cleanup).reserveNotificationKvnr("FAILED_POOL_KVNR"))
        .isInstanceOf(AssertionError.class).hasMessage("setup interrupted");
    assertThat(OidcScenarioContext.identities()).hasSize(1);
  }

  @Test
  void defaultPoolIdentityIsUsedByJsonTemplateAndSurvivesSecondIdentity() throws Exception {
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "example.test");
    var steps = new OidcTestDataSteps(driver, cleanup);

    steps.reserveNotificationKvnr();
    var first = OidcScenarioContext.identities().getFirst().reservation();
    assertThat(TigerGlobalConfiguration.resolvePlaceholders("${oidcTestData.kvnr}")).isEqualTo(first.kvnr());
    verify(cleanup).clean(first);

    steps.reserveNotificationKvnr("SECOND_POOL_KVNR");
    var second = OidcScenarioContext.identities().getLast().reservation();
    assertThat(second.kvnr()).isNotEqualTo(first.kvnr());
    assertThat(TigerGlobalConfiguration.resolvePlaceholders("${SECOND_POOL_KVNR}")).isEqualTo(second.kvnr());
    verify(cleanup).clean(second);

    var template = Files.readString(Path.of("src/test/resources/testdata/oidc/user_kvnr_email.json"));
    var identity = new ObjectMapper()
        .readTree(TigerGlobalConfiguration.resolvePlaceholders(template));
    assertThat(identity.path("kvnr").asText()).isEqualTo(first.kvnr());
    assertThat(identity.path("email").asText()).isEqualTo(first.email());

    new Hooks(mock(ZetaDeploymentModificationService.class), manipulations, driver, cleanup)
        .resetNotificationPushers(mock(Scenario.class));
    verify(cleanup, times(2)).clean(first);
    verify(cleanup, times(2)).clean(second);
  }

  @Test
  void randomOidcIdentityDoesNotUseOrCleanPool() {
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "example.test");
    org.mockito.Mockito.when(driver.setRandomKvnrEmail("example.test")).thenReturn("X110411675");
    new OidcTestDataSteps(driver, cleanup).generateUniqueRandomizedKvnr("FRESH_OIDC_KVNR");
    verify(driver).reset();
    verify(driver).setRandomKvnrEmail("example.test");
    assertThat(OidcScenarioContext.identities()).isEmpty();
    new Hooks(mock(ZetaDeploymentModificationService.class), manipulations, driver, cleanup)
        .resetNotificationPushers(mock(Scenario.class));
    verifyNoInteractions(cleanup, manipulations);
  }
}
