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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.TestDriverConfigurationServiceFactory;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.en.Given;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for creating the isolated test identity used by OIDC scenarios.
 */
@Slf4j
public class OidcTestDataSteps {

  private static final String EMAIL_DOMAIN_CONFIG_KEY = "oidcTestData.emailDomain";
  private final TestDriverConfigurationService testDriverConfigurationService;

  /**
   * Creates OIDC test-data steps backed by the configured client testdriver.
   */
  public OidcTestDataSteps() {
    this(TestDriverConfigurationServiceFactory.getInstance());
  }

  /**
   * Creates OIDC test-data steps with the supplied testdriver client.
   *
   * @param testDriverConfigurationService service used to configure the testdriver identity
   */
  public OidcTestDataSteps(final TestDriverConfigurationService testDriverConfigurationService) {
    this.testDriverConfigurationService = Objects.requireNonNull(
        testDriverConfigurationService, "testDriverConfigurationService must not be null");
  }

  /**
   * Generates and configures one valid KVNR and correlated email for the current OIDC scenario.
   *
   * <p>The testdriver is reset before the identity is configured. The identity endpoint
   * re-authenticates the next request, so a reset after this call would make the following OIDC
   * request use the wrong identity.</p>
   *
   * <p>The KVNR is stored in the requested Tiger variable and can be used directly as the email
   * local-part by the feature. This keeps the email binding and the testdriver identity visibly
   * tied to the same generated test identity without introducing an additional prefix.</p>
   *
   * @param variableName Tiger variable receiving the generated KVNR
   */
  @Gegebensei("eine eindeutige, randomisierte KVNR in der Variablen {tigerResolvedString}")
  @Given("a unique randomized KVNR in variable {tigerResolvedString}")
  public void generateUniqueRandomizedKvnr(String variableName) {
    var emailDomain = TigerGlobalConfiguration.readStringOptional(EMAIL_DOMAIN_CONFIG_KEY)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError(
            "The OIDC test email domain is not configured at " + EMAIL_DOMAIN_CONFIG_KEY + "."));
    testDriverConfigurationService.reset();
    var kvnr = testDriverConfigurationService.setRandomKvnrEmail(emailDomain);
    TigerGlobalConfiguration.putValue(variableName, kvnr, ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Configured randomized KVNR '{}' and correlated binding email in variable '{}'.", kvnr, variableName);
  }
}
