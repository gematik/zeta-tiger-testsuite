/*-
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

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.Then;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;

/**
 * Cucumber helper step definitions.
 *
 * <p>Provides generic utility steps that can be reused across different test scenarios.
 */
public class HelperSteps {

  /**
   * Cucumber step for checking if a tiger variable is set.
   *
   * @param key the variable name to check (can also be a regex according to tiger)
   */
  @Gegebensei("Variable {tigerResolvedString} existiert")
  public void variableExists(String key) {
    Optional<String> optionalValue = TigerGlobalConfiguration.readStringOptional(key);
    Assertions
        .assertThat(optionalValue)
        .withFailMessage("Variable " + key + " is not set.")
        .isPresent();
  }

  /**
   * Cucumber step for waiting/sleeping for a specified number of seconds.
   * Temporary implementation - will be replaced by step from other branch.
   *
   * @param seconds the number of seconds to wait
   */
  @Wenn("TGR warte {int} Sekunden")
  public void waitSeconds(int seconds) throws InterruptedException {
    Thread.sleep(seconds * 1000L);
  }

  /**
   * Cucumber step for waiting/sleeping for a specified number of seconds.
   * This variant accepts a Tiger variable (string) that will be resolved and parsed as integer.
   *
   * @param seconds the number of seconds to wait (as string, supports Tiger variables like ${varName})
   */
  @Wenn("TGR warte {tigerResolvedString} Sekunden")
  public void waitSecondsFromVariable(String seconds) {
    try {
      int secondsInt = Integer.parseInt(seconds.trim());
      Thread.sleep(secondsInt * 1000L);
    } catch (NumberFormatException e) {
      throw new AssertionError("Invalid seconds format: " + seconds, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Wait was interrupted", e);
    }
  }

  /**
   * Checks if the current request's attribute at the given RBEL path is either empty or not equal
   * to the specified value.
   *
   * @param rbelPath the RBEL path to the attribute in the request
   * @param value    the value to compare against; the attribute is considered valid if it is empty or
   *                 not equal to this value
   */
  @Dann("prüfe aktuelle Anfrage der Knoten {tigerResolvedString} ist leer oder ungleich {tigerResolvedString}")
  @Then("current request the attribute {tigerResolvedString} is empty or not equal {tigerResolvedString}")
  public void variableIsEmptyOrNotEqual(String rbelPath, String value) {
    var rbelMessageRetriever = RbelMessageRetriever.getInstance();
    if (rbelMessageRetriever.getCurrentRequest().findRbelPathMembers(rbelPath).isEmpty()) {
      return;
    }

    String optionalValue = rbelMessageRetriever
        .findElementsInCurrentRequest(rbelPath)
        .stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .collect(Collectors.joining());

    Assertions
        .assertThat(optionalValue.trim())
        .as("Node value %s should be empty or not equal to '%s'", optionalValue, value)
        .isNotEqualTo(value);
  }

  /**
   * Checks if the current request's attribute at the given RBEL path is either empty or has
   * a timestamp earlier than the current time.
   *
   * @param rbelPath the RBEL path to the attribute (typically a timestamp) in the request
   */
  @Dann("prüfe aktuelle Anfrage: der Knoten {tigerResolvedString} ist leer oder früher als jetzt")
  @Then("current request: the attribute {tigerResolvedString} is empty or earlier then now")
  public void variableIsEmptyOrEarlier(String rbelPath) {
    var rbelMessageRetriever = RbelMessageRetriever.getInstance();
    if (rbelMessageRetriever.getCurrentRequest().findRbelPathMembers(rbelPath).isEmpty()) {
      return;
    }

    String optionalValue = rbelMessageRetriever
        .findElementsInCurrentRequest(rbelPath)
        .stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .collect(Collectors.joining());

    long value = Instant.now().getEpochSecond();
    Assertions
        .assertThat(Long.parseLong(optionalValue.trim()))
        .as("Node value %s should be empty or smaller then '%s'", optionalValue, value)
        .isLessThan(value);
  }

  /**
   * Checks if the current request's attribute at the given RBEL path is either empty or has
   * a timestamp later than the current time.
   *
   * @param rbelPath the RBEL path to the attribute (typically a timestamp) in the request
   */
  @Dann("prüfe aktuelle Anfrage der Knoten {tigerResolvedString} ist leer oder später als jetzt")
  @Then("current request the attribute {tigerResolvedString} is empty or later then now")
  public void variableIsEmptyOrLater(String rbelPath) {
    var rbelMessageRetriever = RbelMessageRetriever.getInstance();
    if (rbelMessageRetriever.getCurrentRequest().findRbelPathMembers(rbelPath).isEmpty()) {
      return;
    }

    String optionalValue = rbelMessageRetriever
        .findElementsInCurrentRequest(rbelPath)
        .stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .collect(Collectors.joining());

    long value = Instant.now().getEpochSecond();
    Assertions
        .assertThat(Long.parseLong(optionalValue.trim()))
        .as("Node value %s should be empty or smaller then '%s'", optionalValue, value)
        .isGreaterThan(value);
  }
}
