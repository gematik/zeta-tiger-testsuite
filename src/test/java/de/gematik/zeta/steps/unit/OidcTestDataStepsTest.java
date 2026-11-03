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

package de.gematik.zeta.steps.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.steps.Hooks;
import de.gematik.zeta.steps.OidcScenarioContext;
import de.gematik.zeta.steps.OidcTestDataSteps;
import io.cucumber.java.After;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link OidcTestDataSteps}.
 */
class OidcTestDataStepsTest {

  @AfterEach
  void releaseDriver() {
    OidcScenarioContext.clear();
  }

  /**
   * Verifies that the generated KVNR is exposed in the default OIDC test-data variable.
   */
  @Test
  void generateUniqueRandomizedKvnrStoresKvnrInDefaultVariable() {
    var service = new FakeTestDriverConfigurationService();
    var steps = new OidcTestDataSteps(service);
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "mailgun.com");

    steps.generateUniqueRandomizedKvnr();

    assertEquals(
        "X110411675", TigerGlobalConfiguration.readStringOptional("oidcTestData.kvnr").orElseThrow());
    assertTrue(service.resetCalled);
    assertEquals("mailgun.com", service.emailDomain);
  }

  /**
   * Verifies that the generated KVNR is exposed in the requested variable.
   */
  @Test
  void generateUniqueRandomizedKvnrStoresKvnrInRequestedVariable() {
    var service = new FakeTestDriverConfigurationService();
    var steps = new OidcTestDataSteps(service);
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "mailgun.com");

    steps.generateUniqueRandomizedKvnr("CUSTOM_KVNR");

    assertEquals(
        "X110411675", TigerGlobalConfiguration.readStringOptional("CUSTOM_KVNR").orElseThrow());
    assertTrue(service.resetCalled);
    assertEquals("mailgun.com", service.emailDomain);
  }

  @Test
  void ownedDriverResetRunsBeforeScenarioLockRelease() throws NoSuchMethodException {
    var resetOrder = OidcTestDataSteps.class.getMethod("resetOwnedDriver")
        .getAnnotation(After.class).order();
    var clearOrder = Hooks.class.getMethod("clearScenarioLifecycleState")
        .getAnnotation(After.class).order();

    assertTrue(resetOrder > clearOrder,
        "The shared OIDC driver must be reset before its scenario lock is released");
  }

  @Test
  void resetDoesNotTouchAnUnownedDriver() {
    var service = new FakeTestDriverConfigurationService();
    new OidcTestDataSteps(service).resetOwnedDriver();
    assertEquals(0, service.resetCalls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void resetCoversPartialIdentityConfigurationAndIsIdempotent(boolean initialResetFails) {
    var service = new FakeTestDriverConfigurationService();
    service.failConfiguration = !initialResetFails;
    service.resetFailures = initialResetFails ? 1 : 0;
    var steps = new OidcTestDataSteps(service);
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "mailgun.com");
    assertThrows(IllegalStateException.class, steps::generateUniqueRandomizedKvnr);
    steps.resetOwnedDriver();
    steps.resetOwnedDriver();
    assertEquals(2, service.resetCalls);
  }

  @Test
  void failedResetIsReportedAndCanBeRetried() {
    var service = new FakeTestDriverConfigurationService();
    var steps = new OidcTestDataSteps(service);
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "mailgun.com");
    steps.generateUniqueRandomizedKvnr();
    service.resetFailures = 1;
    assertThrows(IllegalStateException.class, steps::resetOwnedDriver);
    steps.resetOwnedDriver();
    assertEquals(3, service.resetCalls);
  }

  /**
   * Minimal testdriver client returning one deterministic valid KVNR.
   */
  private static final class FakeTestDriverConfigurationService extends TestDriverConfigurationService {

    private boolean resetCalled;
    private String emailDomain;
    private int resetCalls;
    private int resetFailures;
    private boolean failConfiguration;

    /**
     * Creates a fake client backed by placeholder endpoint URLs.
     */
    private FakeTestDriverConfigurationService() {
      super("http://localhost/reset", "http://localhost/configure");
    }

    /**
     * Records that the testdriver was reset before the identity was configured.
     */
    @Override
    public void reset() {
      resetCalled = true;
      resetCalls++;
      if (resetFailures > 0) {
        resetFailures--;
        throw new IllegalStateException("Reset unavailable");
      }
    }

    /**
     * Returns a deterministic valid KVNR without making an HTTP request.
     *
     * @param configuredEmailDomain binding email domain
     * @return deterministic valid KVNR
     */
    @Override
    public String setRandomKvnrEmail(String configuredEmailDomain) {
      if (failConfiguration) {
        throw new IllegalStateException("Identity configuration failed");
      }
      emailDomain = configuredEmailDomain;
      return "X110411675";
    }
  }
}
