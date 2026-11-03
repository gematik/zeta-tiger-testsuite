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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerConfigurationKey;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.steps.HelperSteps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks RAT rotation handling without hiding invalid server metadata. */
class RegistrationAccessTokenCaptureTest {
  private static final String VARIABLE = "unitClientManagementRat";
  private final HelperSteps steps = new HelperSteps();

  @AfterEach
  void clearRat() {
    TigerGlobalConfiguration.deleteFromAllSources(new TigerConfigurationKey(VARIABLE));
  }

  @Test
  void omissionPreservesTheWorkingCredential() {
    TigerGlobalConfiguration.putValue(
        VARIABLE, "old-test-rat", ConfigurationValuePrecedence.TEST_CONTEXT);
    steps.captureRotatedRegistrationAccessToken("{\"client_name\":\"renamed\"}", VARIABLE);
    assertEquals(
        "old-test-rat", TigerGlobalConfiguration.readStringOptional(VARIABLE).orElseThrow());
  }

  @Test
  void rotationReplacesTheCredentialForFollowingRequests() {
    TigerGlobalConfiguration.putValue(
        VARIABLE, "old-test-rat", ConfigurationValuePrecedence.TEST_CONTEXT);
    steps.captureRotatedRegistrationAccessToken(
        "{\"registration_access_token\":\"new-test-rat\"}", VARIABLE);
    assertEquals(
        "new-test-rat", TigerGlobalConfiguration.readStringOptional(VARIABLE).orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "null",
        "{\"registration_access_token\":null}",
        "{\"registration_access_token\":42}",
        "{\"registration_access_token\":\"\"}",
        "{\"registration_access_token\":[\"new-test-rat\"]}"
      })
  void malformedReplacementCannotOverwriteTheCredential(String response) {
    TigerGlobalConfiguration.putValue(
        VARIABLE, "old-test-rat", ConfigurationValuePrecedence.TEST_CONTEXT);
    assertThrows(
        AssertionError.class,
        () -> steps.captureRotatedRegistrationAccessToken(response, VARIABLE));
    assertEquals(
        "old-test-rat", TigerGlobalConfiguration.readStringOptional(VARIABLE).orElseThrow());
  }
}
