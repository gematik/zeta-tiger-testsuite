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
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;

/**
 * Cucumber helper step definitions.
 *
 * <p>Provides generic utility steps that can be reused across different test scenarios.
 */
@Slf4j
public class HelperSteps {

  /**
   * Decodes a Base64-URL encoded string and stores the UTF-8 result in the Tiger test context under
   * the given variable name. Uses Base64-URL decoding as specified in RFC 4648 Section 5.
   *
   * @param input   Base64-URL encoded content (supports tigerResolvedString placeholders)
   * @param varName target variable name for the decoded text
   * @throws RuntimeException if the input cannot be decoded from Base64-URL
   */
  @Dann("decodiere {tigerResolvedString} von Base64-URL und speichere in der Variable {tigerResolvedString}")
  @Then("decode {tigerResolvedString} from Base64-URL and save in variable {tigerResolvedString}")
  public void decodeFromBase64Url(String input, String varName) {
    String decodedText = decodeBase64UrlToString(input);
    TigerGlobalConfiguration.putValue(varName, decodedText, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  private String decodeBase64UrlToString(String base64UrlString) {
    try {
      byte[] decodedBytes = Base64.getUrlDecoder().decode(base64UrlString);
      return new String(decodedBytes, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new RuntimeException("Invalid Base64-URL string: " + e.getMessage(), e);
    }
  }

  /**
   * Validates that a string is strictly Base64-URL encoded (RFC 4648 Section 5).
   * Base64-URL uses '-' and '_' instead of '+' and '/' used in standard Base64.
   *
   * @param input the string to validate
   * @throws AssertionError if the string is not valid Base64-URL format
   */
  @Dann("prüfe {tigerResolvedString} ist striktes Base64-URL Format")
  @Then("check {tigerResolvedString} is strict Base64-URL format")
  public void checkStrictBase64UrlFormat(String input) {
    // Prüfe ob gültiges Base64-URL Format (erlaubt: A-Z, a-z, 0-9, -, _, =)
    if (!input.matches("^[A-Za-z0-9_=-]*$")) {
      throw new AssertionError(
          "String ist kein gültiges Base64-URL Format: " + input);
    }

    // Prüfe ob es NICHT Standard-Base64 ist (keine + oder /)
    if (input.contains("+") || input.contains("/")) {
      throw new AssertionError(
          "String enthält Standard-Base64 Zeichen (+ oder /) statt Base64-URL: " + input);
    }
  }

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
  @Wenn("warte {int} Sekunden")
  public void waitSeconds(int seconds) throws InterruptedException {
    Thread.sleep(seconds * 1000L);
  }

  /**
   * Cucumber step for waiting/sleeping for a specified number of seconds.
   * This variant accepts a Tiger variable (string) that will be resolved and parsed as integer.
   *
   * @param seconds the number of seconds to wait (as string, supports Tiger variables like ${varName})
   */
  @Wenn("warte {tigerResolvedString} Sekunden")
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

  /**
   * Verifies that no element matching the given RBEL path exists in the current request.
   */
  @Dann("prüfe aktueller Request enthält keinen Knoten {tigerResolvedString}")
  @Then("current request does not contain node {tigerResolvedString}")
  public void currentRequestMessageNotContainsNode(String rbelPath) {
    var rbelMessageRetriever = RbelMessageRetriever.getInstance();
    if (rbelMessageRetriever.getCurrentRequest() == null) {
      throw new AssertionError("No current request message found!");
    }
    List<RbelElement> elems = rbelMessageRetriever.getCurrentRequest()
        .findRbelPathMembers(rbelPath);
    Assertions
        .assertThat(elems)
        .as("Expected current request to not contain node '%s'", rbelPath)
        .isEmpty();
  }

  /**
   * Validates that a timestamp is in the past.
   *
   * @param timestamp the Unix timestamp in seconds
   */
  @Und("prüfe dass Timestamp {tigerResolvedString} in der Vergangenheit liegt")
  @And("check that timestamp {tigerResolvedString} is in the past")
  public void validateTimestampInPast(String timestamp) {
    long timestampValue = parseTimestamp(timestamp);
    long now = Instant.now().getEpochSecond();

    Assertions
        .assertThat(timestampValue)
        .as("Timestamp %d should be in the past (earlier than or equal to now: %d)", timestampValue, now)
        .isLessThanOrEqualTo(now);

    log.info("Timestamp validation successful: {} is in the past (now: {})", timestampValue, now);
  }

  /**
   * Validates that a timestamp is in the future.
   *
   * @param timestamp the Unix timestamp in seconds
   */
  @Und("prüfe dass Timestamp {tigerResolvedString} in der Zukunft liegt")
  @And("check that timestamp {tigerResolvedString} is in the future")
  public void validateTimestampInFuture(String timestamp) {
    long timestampValue = parseTimestamp(timestamp);
    long now = Instant.now().getEpochSecond();

    Assertions
        .assertThat(timestampValue)
        .as("Timestamp %d should be in the future (later than now: %d)", timestampValue, now)
        .isGreaterThan(now);

    log.info("Timestamp validation successful: {} is in the future (now: {})", timestampValue, now);
  }

  /**
   * Validates that the TTL of a token (calculated as exp - iat) matches the expected value.
   *
   * <p>The actual TTL is calculated from the JWT claims (exp - iat) and compared to the
   * expected value.
   *
   * @param exp               the expiration timestamp (exp claim) from the token
   * @param iat               the issued-at timestamp (iat claim) from the token
   * @param expectedTtlString the expected TTL value in seconds
   */
  @Und("prüfe dass Token TTL zwischen exp={tigerResolvedString} und iat={tigerResolvedString} gleich {tigerResolvedString} Sekunden ist")
  @And("check that token TTL between exp={tigerResolvedString} and iat={tigerResolvedString} equals {tigerResolvedString} seconds")
  public void validateTokenTtl(String exp, String iat, String expectedTtlString) {
    int expectedTtl;
    try {
      expectedTtl = Integer.parseInt(expectedTtlString.trim());
    } catch (NumberFormatException e) {
      throw new AssertionError("Invalid TTL format: " + expectedTtlString);
    }
    long expValue;
    long iatValue;
    try {
      expValue = Long.parseLong(exp);
      iatValue = Long.parseLong(iat);
    } catch (NumberFormatException e) {
      throw new AssertionError("Invalid timestamp format: exp=" + exp + ", iat=" + iat);
    }

    long actualTtl = expValue - iatValue;

    Assertions
        .assertThat(actualTtl)
        .as("Token TTL (exp - iat) must equal expected TTL")
        .isEqualTo(expectedTtl);

    log.info("Token TTL validation successful: actual TTL = {} seconds, expected = {} seconds",
        actualTtl, expectedTtl);
  }

  /**
   * Parses a timestamp string to a long value.
   *
   * @param timestamp the timestamp string
   * @return the parsed timestamp as long
   * @throws AssertionError if the timestamp format is invalid
   */
  private long parseTimestamp(String timestamp) {
    try {
      return Long.parseLong(timestamp.trim());
    } catch (NumberFormatException e) {
      throw new AssertionError("Invalid timestamp format: " + timestamp);
    }
  }
}
