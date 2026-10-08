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

import static org.assertj.core.api.Assertions.assertThat;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.steps.TestDataSteps;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestDataSteps}.
 */
class TestDataStepsTest {

  private static final Pattern TEST_ID_PATTERN = Pattern.compile(
      "RX-TEST-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final TestDataSteps steps = new TestDataSteps();

  /**
   * Verifies that unique test IDs use the requested prefix and are stored in the target variable.
   */
  @Test
  void generateUniqueTestIdStoresPrefixedUuid() {
    var variableName = "testDataStepsTest.id";

    steps.generateUniqueTestId("RX-TEST", variableName);

    var value = TigerGlobalConfiguration.readStringOptional(variableName);
    assertThat(value).hasValueSatisfying(
        generatedId -> assertThat(generatedId).matches(TEST_ID_PATTERN));
  }

  /**
   * Verifies that consecutive generated test IDs do not reuse the same value.
   */
  @Test
  void generateUniqueTestIdGeneratesDifferentValues() {
    var firstVariableName = "testDataStepsTest.firstId";
    var secondVariableName = "testDataStepsTest.secondId";

    steps.generateUniqueTestId("RX-TEST", firstVariableName);
    steps.generateUniqueTestId("RX-TEST", secondVariableName);

    var firstValue = TigerGlobalConfiguration.readStringOptional(firstVariableName);
    var secondValue = TigerGlobalConfiguration.readStringOptional(secondVariableName);
    assertThat(firstValue).isPresent();
    assertThat(secondValue).isPresent();
    assertThat(firstValue).isNotEqualTo(secondValue);
  }
}
