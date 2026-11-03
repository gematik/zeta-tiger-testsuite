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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests formal input-error isolation using the pinned Notification Service schema. */
class OpenApiFaultIsolationTest {
  private static final String SCHEMA = "schemas/v_2_0/openapi/notification-service-rs-endpoint.yaml"
      + "#/components/schemas/NotificationRequest";
  private static final String VALID =
      "{\"user\":{\"id_type\":\"kvnr\",\"value\":\"X110411675\"},"
          + "\"channel\":\"zeta\",\"payload\":{}}";

  @Test
  void acceptsCoupledConstraintsOnOneInvalidValue() {
    var invalid = VALID.replace("X110411675", "");
    assertDoesNotThrow(() -> new SchemaValidationSteps().validateIsolatedOpenApiFault(
        invalid, SCHEMA, "minLength,pattern", "/user/value"));
  }

  @Test
  void rejectsAValidInputAsANegativeFixture() {
    assertThrows(AssertionError.class, () -> new SchemaValidationSteps()
        .validateIsolatedOpenApiFault(VALID, SCHEMA, "pattern", "/user/value"));
  }

  @Test
  void rejectsAnAdditionalIndependentFault() {
    var invalid = VALID.replace("X110411675", "!").replace("\"zeta\"", "3");
    assertThrows(AssertionError.class, () -> new SchemaValidationSteps()
        .validateIsolatedOpenApiFault(invalid, SCHEMA, "pattern,type", "/user/value"));
  }
}
