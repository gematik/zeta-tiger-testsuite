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

import static io.restassured.RestAssured.given;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import io.restassured.http.ContentType;
import io.restassured.http.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.springframework.http.MediaType;

/**
 * Cucumber step definitions for TigerProxy manipulation operations.
 *
 * <p>This class provides step definitions for manipulating the TigerProxy.
 * The TigerProxy URL is automatically resolved from the configuration variable {@code paths.tigerProxy.baseUrl}.
 */
@Slf4j
public class TigerProxyManipulationsSteps {

  private final AtomicLong manipulationCounter = new AtomicLong();

  /**
   * Resolves the TigerProxy base URL from configuration.
   *
   * @return The TigerProxy base URL
   */
  private String getTigerProxyBaseUrl() {
    return TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}");
  }

  /**
   * Creates a unique TigerProxy manipulation name for this step definition instance.
   *
   * @param prefix manipulation name prefix describing the manipulation type
   * @return a unique manipulation name
   */
  private String createManipulationName(String prefix) {
    return prefix + manipulationCounter.incrementAndGet();
  }

  /**
   * Validates proxy configuration presence and resolves the TigerProxy base URL.
   *
   * @param action short label used for log messages
   * @return the resolved base URL, or {@code null} when proxy usage is disabled or unresolved
   */
  private String resolveTigerProxyBaseUrl(String action) {
    var proxyId = TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId")
        .orElse(null);
    if (proxyId == null || proxyId.isBlank()) {
      log.info("Skipping TigerProxy {} (tigerProxyId='{}').", action,
          proxyId == null ? "<missing>" : proxyId);
      return null;
    }

    var baseUrl = getTigerProxyBaseUrl();
    if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("${")) {
      log.info("Skipping TigerProxy {}; base URL not resolved: '{}'.", action, baseUrl);
      return null;
    }

    return baseUrl;
  }

  /**
   * Resolves a required RBEL path from the Tiger configuration.
   *
   * @param configKey configuration key pointing to an RBEL path
   * @return the resolved RBEL path
   */
  private String getRequiredRbelPath(String configKey) {
    var configuredPath = TigerGlobalConfiguration.readStringOptional(configKey)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("Missing configuration: " + configKey));

    if (configuredPath.isBlank() || configuredPath.contains("${")) {
      throw new AssertionError("RBEL path configuration could not be resolved for " + configKey
          + ": '" + configuredPath + "'");
    }

    return configuredPath;
  }

  /**
   * Configures a JWT manipulation on the TigerProxy without re-signing.
   *
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField    what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value       new value for the field
   */
  @Dann("Setze im TigerProxy für JWT in {string} das Feld {string} auf Wert {tigerResolvedString}")
  @Then("Set in TigerProxy for JWT in {string} the field {string} to value {tigerResolvedString}")
  public void setTigerProxyJwtManipulation(String jwtLocation, String jwtField, String value) {
    sendJwtManipulation(Map.of(
        "name", createManipulationName("jwt-modification-"),
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value));
  }

  /**
   * Sends a manipulation request to the TigerProxy to modify intercepted messages based on specified criteria. This method directs the
   * TigerProxy to apply a modification targeting a particular field within the message, identified by its RBel path. It updates the field's
   * value with the provided new value during message interception.
   *
   * @param message Logic to identify the messages that needs to be manipulated
   * @param field   RBel path identifier of the field you want to manipulate
   * @param value   The new value to assign to the specified field
   */
  @Dann("Setze im TigerProxy für die Nachricht {tigerResolvedString} die Manipulation auf "
      + "Feld {tigerResolvedString} und Wert {tigerResolvedString}")
  @Then("Set the manipulation in the TigerProxy for message {tigerResolvedString} to "
      + "field {tigerResolvedString} and value {tigerResolvedString}")
  public void setTigerProxyManipulation(String message, String field, String value) {
    sendRbelManipulation(Map.of(
        "name", createManipulationName("modification"),
        "condition", message,
        "targetElement", field,
        "replaceWith", value,
        "deleteAfterNExecutions", 1)); // only use the manipulation ONCE and delete it afterwards
  }

  /**
   * Sends a manipulation request to the TigerProxy to modify intercepted messages with execution count. This method directs the TigerProxy
   * to apply a modification targeting a particular field within the message, identified by its RBel path, for a specified number of
   * executions.
   *
   * @param message    Logic to identify the messages that needs to be manipulated
   * @param field      RBel path identifier of the field you want to manipulate
   * @param value      The new value to assign to the specified field
   * @param executions Number of times to execute before auto-clearing
   */
  @Dann("Setze im TigerProxy für die Nachricht {tigerResolvedString} die Manipulation auf "
      + "Feld {tigerResolvedString} und Wert {tigerResolvedString} und {int} Ausführungen")
  @Then("Set the manipulation in the TigerProxy for message {tigerResolvedString} to "
      + "field {tigerResolvedString} and value {tigerResolvedString} with {int} executions")
  public void setTigerProxyManipulationWithExecutions(String message, String field, String value,
      Integer executions) {
    sendRbelManipulation(Map.of(
        "name", createManipulationName("modification"),
        "condition", message,
        "targetElement", field,
        "replaceWith", value,
        "deleteAfterNExecutions", executions));
  }

  /**
   * Sends a manipulation request to the TigerProxy to modify intercepted messages using regex replacement. This is useful for modifying
   * form-data fields which cannot be directly addressed by RBel path. The regex filter is applied to the target element and matching parts
   * are replaced with the new value.
   *
   * @param message     Logic to identify the messages that needs to be manipulated
   * @param field       RBel path identifier of the field you want to manipulate (e.g., $.body)
   * @param regexFilter Regex pattern to find the part to replace within the target element
   * @param value       The new value to replace the matched regex with
   */
  @Dann("Setze im TigerProxy für die Nachricht {tigerResolvedString} die Regex-Manipulation auf "
      + "Feld {string} mit Regex {tigerResolvedString} und Wert {tigerResolvedString}")
  @Then("Set the regex manipulation in the TigerProxy for message {tigerResolvedString} to "
      + "field {string} with regex {tigerResolvedString} and value {tigerResolvedString}")
  public void setTigerProxyRegexManipulation(String message, String field, String regexFilter,
      String value) {
    sendRbelManipulation(Map.of(
        "name", createManipulationName("regex-modification"),
        "condition", message,
        "targetElement", field,
        "regexFilter", regexFilter,
        "replaceWith", value,
        "deleteAfterNExecutions", 1));
  }

  /**
   * Duplicates the existing raw HTTP header line in intercepted messages without changing its
   * value.
   *
   * @param message logic to identify the messages that need to be manipulated
   * @param headerName header name to duplicate, for example {@code DPoP}
   */
  @Dann("Dupliziere im TigerProxy für die Nachricht {tigerResolvedString} den Header {string}")
  @Then("Duplicate in TigerProxy for message {tigerResolvedString} the header {string}")
  public void duplicateTigerProxyExistingHeaderLine(String message, String headerName) {
    var normalizedHeaderName = normalizeHeaderNameForManipulation(headerName);

    var lineRegex = "(?im)^(" + Pattern.quote(normalizedHeaderName)
        + "[\\t ]*:[^\\r\\n]*)(?:\\r?\\n)?";
    sendRbelManipulation(Map.of(
        "name", createManipulationName("header-line-duplication"),
        "condition", message,
        "targetElement", "$.header",
        "regexFilter", lineRegex,
        "replaceWith", "$1\r\n$1\r\n",
        "deleteAfterNExecutions", 1));
  }

  /**
   * Verifies how often a raw HTTP header line with a specific value occurs in the currently selected request.
   *
   * @param headerName          HTTP header name, matched case-insensitively
   * @param headerValue         HTTP header value to count exactly after trimming line whitespace
   * @param expectedOccurrences expected number of matching raw header lines
   */
  @Dann("prüfe aktuelle Anfrage enthält den Header {string} mit Wert {tigerResolvedString} {int} mal")
  @Then("verify current request contains header {string} with value {tigerResolvedString} {int} times")
  public void verifyCurrentRequestHeaderValueOccurrenceCount(String headerName, String headerValue,
      int expectedOccurrences) {
    var normalizedHeaderName = validateHeaderCountInput(headerName, expectedOccurrences);
    var rawRequest = getCurrentRequestRawContent();

    var normalizedHeaderValue = headerValue == null ? "" : headerValue.trim();
    var headerLinePattern = Pattern.compile("(?im)^" + Pattern.quote(normalizedHeaderName)
        + "[\\t ]*:[\\t ]*" + Pattern.quote(normalizedHeaderValue) + "[\\t ]*\\r?$");
    var occurrences = headerLinePattern.matcher(rawRequest).results().count();

    Assertions.assertThat(occurrences)
        .as("Raw request header '%s' with expected value should occur %s times",
            normalizedHeaderName, expectedOccurrences)
        .isEqualTo(expectedOccurrences);
  }

  /**
   * Verifies how often a raw HTTP header line occurs in the currently selected request.
   *
   * @param headerName          HTTP header name, matched case-insensitively
   * @param expectedOccurrences expected number of matching raw header lines
   */
  @Dann("prüfe aktuelle Anfrage enthält den Header {string} {int} mal")
  @Then("verify current request contains header {string} {int} times")
  public void verifyCurrentRequestHeaderOccurrenceCount(String headerName,
      int expectedOccurrences) {
    var normalizedHeaderName = validateHeaderCountInput(headerName, expectedOccurrences);
    var rawRequest = getCurrentRequestRawContent();
    var headerLinePattern = Pattern.compile("(?im)^" + Pattern.quote(normalizedHeaderName)
        + "[\\t ]*:[^\\r\\n]*\\r?$");
    var occurrences = headerLinePattern.matcher(rawRequest).results().count();

    Assertions.assertThat(occurrences)
        .as("Raw request header '%s' should occur %s times",
            normalizedHeaderName, expectedOccurrences)
        .isEqualTo(expectedOccurrences);
  }

  /**
   * Validates the expected header occurrence count input.
   *
   * @param headerName          HTTP header name to normalize
   * @param expectedOccurrences expected number of matching raw header lines
   * @return normalized header name
   */
  private String validateHeaderCountInput(String headerName, int expectedOccurrences) {
    var normalizedHeaderName = normalizeRequiredHeaderName(
        headerName, "Header name for current request header count must not be empty.");
    if (expectedOccurrences < 0) {
      throw new AssertionError("Expected header occurrence count must not be negative.");
    }
    return normalizedHeaderName;
  }

  /**
   * Reads the raw HTTP content of the currently selected request.
   *
   * @return raw request content
   */
  private String getCurrentRequestRawContent() {
    var currentRequest = RbelMessageRetriever.getInstance().getCurrentRequest();
    if (currentRequest == null) {
      throw new AssertionError("No current request message found.");
    }

    var rawRequest = currentRequest.getRawStringContent();
    if (rawRequest == null) {
      throw new AssertionError("Current request has no raw content.");
    }
    return rawRequest;
  }

  /**
   * Configures a JWT manipulation on the TigerProxy.
   *
   * @param jwtLocation   where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField      what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value         new value for the field
   * @param privateKeyPem private key used to re-sign the token
   */
  @Dann("Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString}")
  @Then("Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString}")
  public void setTigerProxyJwtManipulationWithKey(String jwtLocation, String jwtField, String value,
      String privateKeyPem) {
    sendJwtManipulation(Map.of(
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value,
        "privateKeyPem", privateKeyPem));
  }

  /**
   * Normalizes and validates a header name used in TigerProxy header manipulation.
   *
   * @param headerName header name to normalize
   * @return trimmed header name
   */
  private String normalizeHeaderNameForManipulation(String headerName) {
    return normalizeRequiredHeaderName(
        headerName, "Header name for TigerProxy header manipulation must not be empty.");
  }

  /**
   * Normalizes and validates a required HTTP header name.
   *
   * @param headerName header name to normalize
   * @param emptyMessage assertion message used when the header name is empty
   * @return trimmed header name
   */
  private String normalizeRequiredHeaderName(String headerName, String emptyMessage) {
    var normalizedHeaderName = headerName == null ? "" : headerName.trim();
    if (normalizedHeaderName.isEmpty()) {
      throw new AssertionError(emptyMessage);
    }
    return normalizedHeaderName;
  }

  /**
   * Configures a JWT manipulation on the TigerProxy with condition and execution limit, without re-signing.
   *
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField    what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value       new value for the field
   * @param condition   regex pattern to match request paths
   * @param executions  number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann("Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert {tigerResolvedString} "
      + "für Pfad {tigerResolvedString} und {int} Ausführungen")
  @Then("Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value {tigerResolvedString} "
      + "for path {tigerResolvedString} with {int} executions")
  public void setTigerProxyJwtManipulationWithConditionNoResign(String jwtLocation, String jwtField,
      String value, String condition, Integer executions) {
    sendJwtManipulation(Map.of(
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value,
        "condition", condition,
        "deleteAfterNExecutions", executions));
  }

  /**
   * Configures a JWT manipulation on the TigerProxy with condition and execution limit.
   *
   * @param jwtLocation   where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField      what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value         new value for the field
   * @param privateKeyPem private key used to re-sign the token
   * @param condition     regex pattern to match request paths
   * @param executions    number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann("Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} und {int} Ausführungen")
  @Then("Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString} for path {tigerResolvedString} with {int} executions")
  public void setTigerProxyJwtManipulationWithCondition(String jwtLocation, String jwtField,
      String value, String privateKeyPem, String condition, Integer executions) {
    sendJwtManipulation(Map.ofEntries(
        Map.entry("jwtLocation", jwtLocation),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value),
        Map.entry("privateKeyPem", privateKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions)));
  }

  /**
   * Configures a JWT manipulation on the TigerProxy with condition, execution limit, and JWK replacement. The JWK in the JWT header will be
   * replaced with the public key derived from the provided private key.
   *
   * @param jwtLocation   where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField      what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value         new value for the field
   * @param privateKeyPem private key used to re-sign the token and derive public key for JWK
   * @param condition     regex pattern to match request paths
   * @param executions    number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann("Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} und {int} Ausführungen und ersetze JWK")
  @Then("Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString} for path {tigerResolvedString} with {int} executions and replace JWK")
  public void setTigerProxyJwtManipulationWithConditionAndReplaceJwk(String jwtLocation,
      String jwtField,
      String value, String privateKeyPem, String condition, Integer executions) {
    sendJwtManipulation(Map.ofEntries(
        Map.entry("jwtLocation", jwtLocation),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value),
        Map.entry("privateKeyPem", privateKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions),
        Map.entry("replaceJwk", true)));
  }

  /**
   * Configures a JWT manipulation on the TigerProxy that removes the selected JWT field and
   * re-signs the token with a replacement JWK derived from the provided private key.
   *
   * @param jwtLocation   where the JWT is located (e.g., "$.header.dpop")
   * @param jwtField      what to remove in the JWT (e.g., "body.jti")
   * @param privateKeyPem private key used to re-sign the token and derive public key for JWK
   * @param condition     regex pattern to match request paths
   * @param executions    number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann("Entferne im TigerProxy für JWT in {tigerResolvedString} das Feld {string} "
      + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} "
      + "und {int} Ausführungen und ersetze JWK")
  @Then("Remove in TigerProxy for JWT in {tigerResolvedString} the field {string} "
      + "using private key {tigerResolvedString} for path {tigerResolvedString} "
      + "with {int} executions and replace JWK")
  public void removeTigerProxyJwtFieldWithConditionAndReplaceJwk(String jwtLocation,
      String jwtField,
      String privateKeyPem, String condition, Integer executions) {
    sendJwtManipulation(Map.ofEntries(
        Map.entry("jwtLocation", jwtLocation),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", ""),
        Map.entry("privateKeyPem", privateKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions),
        Map.entry("replaceJwk", true),
        Map.entry("deleteField", true)));
  }

  /**
   * Configures a JWT manipulation on the TigerProxy for Authorization header (access token) with automatic DPoP ath update. When the access
   * token is manipulated, the ath claim in the DPoP JWT will be recalculated and the DPoP JWT will be re-signed.
   *
   * @param jwtField          what to change in the access token JWT (e.g., "body.iss", "body.sub")
   * @param value             new value for the field
   * @param accessTokenKeyPem private key used to re-sign the access token
   * @param dpopKeyPem        private key used to re-sign the DPoP JWT after ath update
   * @param condition         regex pattern to match request paths
   * @param executions        number of times to execute before auto-clearing
   */
  @Dann("Setze im TigerProxy für Access Token das Feld {string} auf Wert {tigerResolvedString} "
      + "mit Access Token Key {tigerResolvedString} und DPoP Key {tigerResolvedString} "
      + "für Pfad {tigerResolvedString} und {int} Ausführungen")
  @Then("Set in TigerProxy for access token the field {string} to value {tigerResolvedString} "
      + "using access token key {tigerResolvedString} and DPoP key {tigerResolvedString} "
      + "for path {tigerResolvedString} with {int} executions")
  public void setTigerProxyAccessTokenManipulationWithAthUpdate(String jwtField, String value,
      String accessTokenKeyPem, String dpopKeyPem, String condition, Integer executions) {
    sendJwtManipulation(Map.ofEntries(
        Map.entry("jwtLocation", getRequiredRbelPath("headers.authorization.root")),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value),
        Map.entry("privateKeyPem", accessTokenKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions),
        Map.entry("dpopLocation", getRequiredRbelPath("headers.dpop.root")),
        Map.entry("dpopPrivateKeyPem", dpopKeyPem),
        Map.entry("updateAth", true)));
  }

  /**
   * Configures a single-execution JWT manipulation on the TigerProxy (executes once then auto-clears).
   *
   * @param jwtLocation   where the JWT is located
   * @param jwtField      what to change in the JWT
   * @param value         new value for the field
   * @param privateKeyPem private key used to re-sign the token
   */
  @Dann("Setze im TigerProxy für JWT in {string} das Feld {string} auf Wert {tigerResolvedString} "
      + "mit privatem Schlüssel {tigerResolvedString} einmalig")
  @Then("Set in TigerProxy for JWT in {string} the field {string} to value {tigerResolvedString} "
      + "using private key {tigerResolvedString} once")
  public void setTigerProxyJwtManipulationOnce(String jwtLocation, String jwtField,
      String value, String privateKeyPem) {
    sendJwtManipulation(Map.of(
        "jwtLocation", jwtLocation,
        "jwtField", jwtField,
        "replaceWith", value,
        "privateKeyPem", privateKeyPem,
        "deleteAfterNExecutions", 1));
  }

  /**
   * Stops all configured TigerProxy manipulations without touching the recorded message history.
   */
  @Dann("Alle Manipulationen im TigerProxy werden gestoppt")
  @Then("All manipulations in the TigerProxy are stopped")
  public void stopAllTigerProxyManipulations() {
    resetTigerProxyManipulationsIfAvailable();
  }

  /**
   * Resets all TigerProxy state that may leak between scenarios.
   *
   * <p>If the standalone TigerProxy is not configured or not reachable, the reset is skipped so
   * {@code @no_proxy} scenarios and local runs without a proxy still execute.</p>
   */
  void resetTigerProxyStateIfAvailable() {
    var baseUrl = resolveTigerProxyBaseUrl("state reset");
    if (baseUrl == null) {
      return;
    }

    try {
      resetTigerProxyManipulationsIfAvailable();
      var httpGlueCode = new HttpGlueCode();
      httpGlueCode.sendEmptyRequest(
          Method.GET,
          new URI(getUrl("${paths.tigerProxy.resetMessagesPath}")));
    } catch (Exception e) {
      log.info("Skipping TigerProxy state reset because proxy is not reachable at '{}'.", baseUrl);
    }
  }

  /**
   * Clears RBEL and JWT manipulations from TigerProxy without resetting recorded proxy messages.
   */
  private void resetTigerProxyManipulationsIfAvailable() {
    var baseUrl = resolveTigerProxyBaseUrl("manipulation reset");
    if (baseUrl == null) {
      return;
    }

    try {
      var httpGlueCode = new HttpGlueCode();
      httpGlueCode.sendEmptyRequest(
          Method.DELETE,
          new URI(getUrl("${paths.tigerProxy.manipulationPath}")));
      httpGlueCode.sendRequestWithMultiLineBody(
          Method.POST,
          new URI(getUrl("${paths.tigerProxy.resetJwtManipulationPath}")),
          "",
          MediaType.APPLICATION_JSON_VALUE);
    } catch (Exception e) {
      log.info("Skipping TigerProxy manipulation reset because proxy is not reachable at '{}'.", baseUrl);
    }
  }

  /**
   * Sends a JWT manipulation request to TigerProxy. Central method handling all HTTP communication for JWT manipulations.
   *
   * @param body Request body containing manipulation parameters
   */
  private void sendJwtManipulation(Map<String, Object> body) {
    var baseUrl = resolveTigerProxyBaseUrl("JWT manipulation");
    if (baseUrl == null) {
      return;
    }

    var url = getUrl("${paths.tigerProxy.modifyJwtPath}");

    try {
      given()
          .contentType(ContentType.JSON)
          .body(body)
          .when()
          .post(url)
          .then()
          .statusCode(200);
    } catch (Exception e) {
      throwTigerProxyManipulationFailure("JWT manipulation", baseUrl, e);
    }
  }

  /**
   * Sends an RBel manipulation request to TigerProxy. Central method handling all HTTP communication for RBel path manipulations.
   *
   * @param body Request body containing manipulation parameters
   */
  private void sendRbelManipulation(Map<String, Object> body) {
    sendRbelManipulations(body);
  }

  /**
   * Sends one or more RBel manipulations to TigerProxy in a single update.
   *
   * @param body request body containing one manipulation object or a list of manipulation objects
   */
  private void sendRbelManipulations(Object body) {
    var baseUrl = resolveTigerProxyBaseUrl("RBel manipulation");
    if (baseUrl == null) {
      return;
    }

    var url = getUrl("${paths.tigerProxy.modificationPath}");

    try {
      given()
          .contentType(ContentType.JSON)
          .body(body)
          .when()
          .put(url)
          .then()
          .statusCode(200);
      given()
          .when()
          .get(url)
          .then()
          .statusCode(200);
    } catch (Exception e) {
      throwTigerProxyManipulationFailure("RBel manipulation", baseUrl, e);
    }
  }

  /**
   * Converts low-level HTTP client failures into stable assertion failures for step execution.
   *
   * @param action    short action label used in the failure message
   * @param baseUrl   resolved TigerProxy base URL
   * @param exception original HTTP client exception
   */
  private void throwTigerProxyManipulationFailure(final String action, final String baseUrl,
      final Exception exception) {
    if (isTigerProxyReachabilityFailure(exception)) {
      throw new AssertionError("TigerProxy not reachable at '" + baseUrl + "'.", exception);
    }
    throw new AssertionError(action + " failed: " + exception.getMessage(), exception);
  }

  /**
   * Checks whether the exception chain indicates that the proxy could not be reached at all.
   *
   * @param throwable exception to inspect
   * @return {@code true} when the failure is caused by connection or DNS issues
   */
  private boolean isTigerProxyReachabilityFailure(final Throwable throwable) {
    for (var current = throwable; current != null; current = current.getCause()) {
      if (current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof UnknownHostException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Builds the request URL from the TigerProxy base URL and uri part.
   *
   * @param uri Specific endpoint that is called in the TigerProxy
   * @return The complete URL to access the TigerProxy API
   */
  private String getUrl(String uri) {
    var tigerProxy = getTigerProxyBaseUrl();
    var resolvedUri = TigerGlobalConfiguration.resolvePlaceholders(uri);
    return
        (tigerProxy.endsWith("/") ? tigerProxy.substring(0, tigerProxy.length() - 1) : tigerProxy)
            + resolvedUri;
  }

}
