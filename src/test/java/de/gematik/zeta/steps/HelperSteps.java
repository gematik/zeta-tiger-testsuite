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

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import tools.jackson.databind.ObjectMapper;

/**
 * Cucumber helper step definitions.
 *
 * <p>Provides generic utility steps that can be reused across different test scenarios.
 */
@Slf4j
public class HelperSteps {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Retains the previous RAT when the server did not rotate it during a metadata update.
   *
   * @param response complete JSON update response
   * @param variable variable holding the current RAT
   */
  @Und(
      "übernimm ein rotiertes Registration Access Token aus {tigerResolvedString} in Variable"
          + " {string}, falls vorhanden")
  public void captureRotatedRegistrationAccessToken(String response, String variable) {
    var metadata = JSON.readTree(response);
    if (metadata == null || !metadata.isObject()) {
      throw new AssertionError("Client metadata response must be a JSON object");
    }
    var token = metadata.get("registration_access_token");
    if (token != null) {
      if (!token.isString() || token.asString().isBlank()) {
        throw new AssertionError("Returned registration_access_token must be a nonempty string");
      }
      TigerGlobalConfiguration.putValue(
          variable, token.asString(), ConfigurationValuePrecedence.TEST_CONTEXT);
    }
  }

  /**
   * Validates that a string is strictly Base64-URL encoded (RFC 4648 Section 5). Base64-URL uses '-' and '_' instead of '+' and '/' used in
   * standard Base64.
   *
   * @param input the string to validate
   * @throws AssertionError if the string is not valid Base64-URL format
   */
  @Dann("prüfe {tigerResolvedString} ist striktes Base64-URL Format")
  @Then("check {tigerResolvedString} is strict Base64-URL format")
  public void checkStrictBase64UrlFormat(String input) {
    if (input == null || input.isBlank()) {
      throw new AssertionError("String ist leer und damit kein gültiges Base64-URL Format");
    }

    var strictBase64UrlRegex = TigerGlobalConfiguration
        .readStringOptional("regex.base64url_strict")
        .orElseThrow(() -> new AssertionError(
            "Fehlende Konfiguration: regex.base64url_strict in tiger/regex.yaml"));

    if (!input.matches(strictBase64UrlRegex)) {
      throw new AssertionError(
          "String ist kein gültiges Base64-URL Format: " + input);
    }
  }

  /**
   * Cucumber step for waiting/sleeping for a specified number of seconds. Temporary implementation - will be replaced by step from other
   * branch.
   *
   * @param seconds the number of seconds to wait
   */
  @Wenn("warte {int} Sekunden")
  public void waitSeconds(int seconds) {
    try {
      Thread.sleep(seconds * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Wait was interrupted", e);
    }
  }

  /**
   * Cucumber step for waiting/sleeping for a specified number of seconds. This variant accepts a Tiger variable (string) that will be
   * resolved and parsed as integer.
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
   * Verifies that no recorded request has a path equal to or matching the given pattern.
   *
   * @param pathPattern the request path or regular expression that must not occur in the recorded request history
   */
  @Dann("prüfe, dass keine aufgezeichnete Anfrage den Pfad {tigerResolvedString} hat")
  @Then("verify no recorded request has path {tigerResolvedString}")
  public void verifyNoRecordedRequestHasPath(String pathPattern) {
    var messages = RecordedMessages.snapshot();
    if (messages == null || messages.isEmpty()) {
      return;
    }

    var matchingPaths = messages
        .stream()
        .filter(this::isRequestMessage)
        .flatMap(message -> extractPathValues(message).stream())
        .filter(path -> path.equals(pathPattern) || path.matches(pathPattern))
        .toList();

    Assertions
        .assertThat(matchingPaths)
        .as("No recorded request path should match '%s'", pathPattern)
        .isEmpty();
  }

  /**
   * Verifies that exactly the expected number of recorded requests has a path equal to or matching the given pattern.
   *
   * @param expectedCount the expected number of matching requests
   * @param pathPattern   the request path or regular expression to count in the recorded request history
   */
  @Dann("prüfe, dass genau {int} aufgezeichnete Anfragen den Pfad {tigerResolvedString} haben")
  @Then("verify exactly {int} recorded requests have path {tigerResolvedString}")
  public void verifyRecordedRequestCountForPath(int expectedCount, String pathPattern) {
    var messages = RecordedMessages.snapshot();
    if (messages == null || messages.isEmpty()) {
      Assertions
          .assertThat(0)
          .as("Recorded request path count for '%s'", pathPattern)
          .isEqualTo(expectedCount);
      return;
    }

    var matchingPaths = messages
        .stream()
        .filter(this::isRequestMessage)
        .flatMap(message -> extractPathValues(message).stream())
        .filter(path -> path.equals(pathPattern) || path.matches(pathPattern))
        .toList();

    Assertions
        .assertThat(matchingPaths)
        .as("Recorded request paths matching '%s'", pathPattern)
        .hasSize(expectedCount);
  }

  /**
   * Checks the correlated HTTP status of every matching request in the recorded history.
   *
   * @param method HTTP request method
   * @param pathPattern exact path or full path regular expression
   * @param statusPattern regular expression for each permitted HTTP response status
   * @throws AssertionError if no request matches, an answer is missing or its status differs
   */
  @Dann("prüfe, dass alle aufgezeichneten {tigerResolvedString} Anfragen mit Pfad"
      + " {tigerResolvedString} mit HTTP {tigerResolvedString} beantwortet wurden")
  public void verifyRecordedResponseStatuses(String method, String pathPattern, String statusPattern) {
    var messages = RecordedMessages.snapshot();
    var requests = messages.stream()
        .filter(message -> message.findElement("$.method")
            .map(RbelElement::getRawStringContent).filter(method::equals).isPresent())
        .filter(message -> extractPathValues(message).stream()
            .anyMatch(path -> path.equals(pathPattern) || path.matches(pathPattern)))
        .toList();
    Assertions.assertThat(requests).as("Recorded %s requests to %s", method, pathPattern)
        .isNotEmpty();
    var timing = new TimingGlue();
    Assertions.assertThat(requests).allSatisfy(request -> {
      var response = timing.findResponseForRequest(messages, request);
      Assertions.assertThat(response).as("Response to request %s", request.getUuid()).isNotNull();
      Assertions.assertThat(response.findRbelPathMembers("$.responseCode"))
          .as("HTTP status of request %s", request.getUuid()).singleElement()
          .satisfies(code -> Assertions.assertThat(code.getRawStringContent()).matches(statusPattern));
    });
  }

  /**
   * Checks an optional expected value against a request node.
   *
   * <p>If the expected value is blank (null/empty/"null"), the node must be absent.
   * Otherwise the node must exist and match the expected value.</p>
   *
   * @param rbelPath      the RBEL path to the attribute in the request
   * @param expectedValue the optional value to compare (may be empty)
   */
  @Dann("prüfe optional: Knoten {tigerResolvedString} fehlt wenn {tigerResolvedString} leer ist, sonst gleich")
  @Then("check optional: node {tigerResolvedString} is absent when {tigerResolvedString} is empty, otherwise equal")
  public void checkOptionalValueAgainstNode(String rbelPath, String expectedValue) {
    checkOptionalValueAgainstNodeInternal(rbelPath, expectedValue, false);
  }

  /**
   * Soft-asserting variant of {@link #checkOptionalValueAgainstNode(String, String)}.
   *
   * @param rbelPath      the RBEL path to the attribute in the request
   * @param expectedValue the optional value to compare (may be empty)
   */
  @Dann("prüfe optional: Knoten {tigerResolvedString} fehlt wenn {tigerResolvedString} leer ist, sonst gleich und nutze soft assert")
  @Then("check optional: node {tigerResolvedString} is absent when {tigerResolvedString} is empty, otherwise equal with soft assert")
  public void checkOptionalValueAgainstNodeSoft(String rbelPath, String expectedValue) {
    checkOptionalValueAgainstNodeInternal(rbelPath, expectedValue, true);
  }

  /**
   * Checks an optional expected value against a request node, optionally recording failures as soft assertions.
   *
   * @param rbelPath      the RBEL path to the attribute in the request
   * @param expectedValue the optional value to compare
   * @param soft          whether assertion failures should be recorded as soft failures
   */
  private void checkOptionalValueAgainstNodeInternal(String rbelPath, String expectedValue, boolean soft) {
    var rbelMessageRetriever = RbelMessageRetriever.getInstance();
    if (rbelMessageRetriever.getCurrentRequest() == null) {
      var ex = new AssertionError("No current request message found!");
      if (soft) {
        SoftAssertionsContext.recordSoftFailure("No current request message found", ex);
        return;
      }
      throw ex;
    }

    if (rbelPath == null || rbelPath.isBlank() || !rbelPath.trim().startsWith("$.")) {
      var ex = new AssertionError("RbelPath expressions always start with $. (got '" + rbelPath + "')");
      if (soft) {
        SoftAssertionsContext.recordSoftFailure("Invalid RBEL path: " + rbelPath, ex);
        return;
      }
      throw ex;
    }

    String normalized = expectedValue == null ? "" : expectedValue.trim();
    boolean hasExpectedValue = !normalized.isBlank() && !"null".equalsIgnoreCase(normalized);

    List<RbelElement> elems = rbelMessageRetriever.getCurrentRequest()
        .findRbelPathMembers(rbelPath);

    try {
      if (!hasExpectedValue) {
        Assertions
            .assertThat(elems)
            .as("Node '%s' must be absent when expected value is empty", rbelPath)
            .isEmpty();
        return;
      }

      Assertions
          .assertThat(elems)
          .as("Expected node '%s' to be present", rbelPath)
          .isNotEmpty();

      String actualValue = rbelMessageRetriever
          .findElementsInCurrentRequest(rbelPath)
          .stream()
          .map(RbelElement::getRawStringContent)
          .filter(Objects::nonNull)
          .map(String::trim)
          .collect(Collectors.joining());

      Assertions
          .assertThat(actualValue)
          .as("Node value %s should be equal to '%s'", actualValue, normalized)
          .isEqualTo(normalized);
    } catch (AssertionError ex) {
      if (soft) {
        SoftAssertionsContext.recordSoftFailure("Optional node check failed for " + rbelPath, ex);
      } else {
        throw ex;
      }
    }
  }

  /**
   * Checks whether an RBEL message represents a request with a path.
   *
   * @param message the RBEL message to inspect
   * @return {@code true} if the message has a path and no response code
   */
  private boolean isRequestMessage(RbelElement message) {
    return !message.findRbelPathMembers("$.path").isEmpty()
        && message.findRbelPathMembers("$.responseCode").isEmpty();
  }

  /**
   * Extracts all path values from an RBEL message.
   *
   * @param message the RBEL message to inspect
   * @return trimmed path values from the message
   */
  private List<String> extractPathValues(RbelElement message) {
    return message
        .findRbelPathMembers("$.path")
        .stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .toList();
  }

  /**
   * Checks if the current request's attribute at the given RBEL path does either not exist or has a timestamp earlier than the current
   * time.
   *
   * @param rbelPath the RBEL path to the attribute (typically a timestamp) in the request
   */
  @Dann("prüfe aktuelle Anfrage: der Knoten {tigerResolvedString} ist nicht vorhanden oder früher als jetzt")
  @Then("current request: the attribute {tigerResolvedString} does not exist or is earlier then now")
  public void variableDoesNotExistOrIsEarlier(String rbelPath) {
    var rbelMessageRetriever = RbelMessageRetriever.getInstance();
    var currentRequest = rbelMessageRetriever.getCurrentRequest();
    if (currentRequest == null) {
      throw new AssertionError("No current request message found!");
    }
    if (currentRequest.findRbelPathMembers(rbelPath).isEmpty()) {
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
        .as("Node value %s should not exist or be earlier then '%s'", optionalValue, value)
        .isLessThan(value);
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
}
