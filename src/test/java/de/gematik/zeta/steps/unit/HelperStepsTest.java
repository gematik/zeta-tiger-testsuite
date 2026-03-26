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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.steps.HelperSteps;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HelperSteps}.
 */
class HelperStepsTest {

  private final HelperSteps helperSteps = new HelperSteps();

  /**
   * Ensures strict Base64-URL format validation accepts valid input.
   */
  @Test
  void checkStrictBase64UrlFormatAcceptsValidInput() {
    assertDoesNotThrow(
        () -> helperSteps.checkStrictBase64UrlFormat("eyJmb28iOiJiYXIifQ"));
  }

  /**
   * Ensures strict Base64-URL format validation rejects invalid input for the expected reason.
   */
  @Test
  void checkStrictBase64UrlFormatRejectsInvalidInput() {
    AssertionError error = assertThrows(AssertionError.class,
        () -> helperSteps.checkStrictBase64UrlFormat("### not base64-url ###"),
        "Invalid Base64-URL input should raise an AssertionError");

    assertTrue(error.getMessage().contains("kein gültiges Base64-URL Format"),
        "Invalid Base64-URL input should fail validation instead of missing regex configuration");
  }
}
