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
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.steps.OidcTestDataSteps;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OidcTestDataSteps}.
 */
class OidcTestDataStepsTest {

  /**
   * Verifies that the generated KVNR is exposed in the requested variable.
   */
  @Test
  void generateUniqueRandomizedKvnrStoresKvnr() {
    var service = new FakeTestDriverConfigurationService();
    var steps = new OidcTestDataSteps(service);
    TigerGlobalConfiguration.putValue("oidcTestData.emailDomain", "mailgun.com");

    steps.generateUniqueRandomizedKvnr("OIDC_KVNR");

    assertEquals("X110411675", TigerGlobalConfiguration.readStringOptional("OIDC_KVNR").orElseThrow());
    assertTrue(service.resetCalled);
    assertEquals("mailgun.com", service.emailDomain);
  }

  /**
   * Minimal testdriver client returning one deterministic valid KVNR.
   */
  private static final class FakeTestDriverConfigurationService extends TestDriverConfigurationService {

    private boolean resetCalled;
    private String emailDomain;

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
    }

    /**
     * Returns a deterministic valid KVNR without making an HTTP request.
     *
     * @param configuredEmailDomain binding email domain
     * @return deterministic valid KVNR
     */
    @Override
    public String setRandomKvnrEmail(String configuredEmailDomain) {
      emailDomain = configuredEmailDomain;
      return "X110411675";
    }
  }
}
