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
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for generating isolated test data values.
 */
@Slf4j
public class TestDataSteps {

  /**
   * Generates a unique test id with the given prefix and stores it as a Tiger variable.
   *
   * @param prefix  the id prefix
   * @param varName the target Tiger variable name
   */
  @Und("erzeuge eindeutige Test-ID mit Präfix {tigerResolvedString} "
      + "und speichere in Variable {tigerResolvedString}")
  @Gegebensei("eine eindeutige Test-ID mit Präfix {tigerResolvedString} "
      + "in Variable {tigerResolvedString}")
  @And("generate unique test ID with prefix {tigerResolvedString} "
      + "and store in variable {tigerResolvedString}")
  @Given("a unique test ID with prefix {tigerResolvedString} "
      + "in variable {tigerResolvedString}")
  public void generateUniqueTestId(String prefix, String varName) {
    var value = "%s-%s".formatted(prefix, UUID.randomUUID());
    TigerGlobalConfiguration.putValue(varName, value, ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Generated unique test id '{}' for variable '{}'", value, varName);
  }
}
