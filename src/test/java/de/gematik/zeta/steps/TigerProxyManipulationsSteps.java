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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.JwtVariant;
import de.gematik.zeta.services.DirectHttpClients;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

/**
 * Cucumber step definitions for TigerProxy manipulation operations.
 *
 * <p>This class provides step definitions for manipulating the TigerProxy.
 * The TigerProxy URL is automatically resolved from the configuration variable {@code paths.tigerProxy.baseUrl}.
 */
@Slf4j
public class TigerProxyManipulationsSteps {

  private static final HttpClient PROXY_CONTROL_CLIENT = DirectHttpClients.httpClientBuilder().build();

  private final AtomicLong manipulationCounter = new AtomicLong();
  private final String manipulationNamespace = UUID.randomUUID().toString();
  private String historyCheckpoint;

  /**
   * Resolves the TigerProxy base URL from configuration.
   *
   * @return The TigerProxy base URL
   */
  private String getTigerProxyBaseUrl() {
    return TigerGlobalConfiguration.resolvePlaceholders("${paths.tigerProxy.baseUrl}");
  }

  /**
   * Keeps rules from separate glue instances distinct in the shared TigerProxy registry.
   *
   * @param prefix manipulation name prefix describing the manipulation type
   * @return a unique manipulation name
   */
  private String createManipulationName(String prefix) {
    return prefix + manipulationNamespace + "-" + manipulationCounter.incrementAndGet();
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
   * Replaces an unsigned JSON element using the existing Tiger modification API.
   *
   * @param message selector for the intercepted message
   * @param field target RBEL element
   * @param json JSON value, including resolved scenario variables
   */
  @Dann("Setze im TigerProxy für die Nachricht {tigerResolvedString} die Manipulation auf Feld"
      + " {tigerResolvedString} und folgenden JSON-Wert:")
  public void setTigerProxyJsonManipulation(String message, String field, String json) {
    var resolved = TigerGlobalConfiguration.resolvePlaceholders(json);
    var mapper = new ObjectMapper();
    var value = mapper.readTree(resolved);
    if (value == null || !value.isObject()) {
      throw new AssertionError("Proxy JSON replacement requires an object");
    }
    setTigerProxyManipulation(message, field, mapper.writeValueAsString(value));
  }

  /**
   * Configures one bounded JSON-object replacement selected by its correlated request.
   *
   * @param json JSON description with when, replace and executions properties
   * @throws IllegalArgumentException if the description contains unsupported or invalid fields
   */
  @Dann("Setze im TigerProxy folgende Manipulation:")
  @Then("Set the following manipulation in the TigerProxy:")
  public void setTigerProxyStructuredManipulation(String json) {
    var manipulation = JsonProxyManipulation.parse(json, TigerGlobalConfiguration::resolvePlaceholders);
    setTigerProxyManipulationWithExecutions(manipulation.condition(), manipulation.field(),
        manipulation.replacement(), manipulation.executions());
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
   * Applies a regex replacement to one matching message.
   *
   * @param message condition identifying the messages to manipulate
   * @param field RBel path of the target element
   * @param regexFilter pattern identifying the text to replace
   * @param value replacement text
   */
  @Dann("Setze im TigerProxy für die Nachricht {tigerResolvedString} die Regex-Manipulation auf "
      + "Feld {string} mit Regex {tigerResolvedString} und Wert {tigerResolvedString}")
  @Then("Set the regex manipulation in the TigerProxy for message {tigerResolvedString} to "
      + "field {string} with regex {tigerResolvedString} and value {tigerResolvedString}")
  public void setTigerProxyRegexManipulation(String message, String field, String regexFilter,
      String value) {
    setTigerProxyRegexManipulationWithExecutions(message, field, regexFilter, value, 1);
  }

  /**
   * Applies one raw-field regex mutation to the original request and its bounded SDK retries.
   *
   * @param message message selector
   * @param field raw field to rewrite
   * @param regexFilter part of the field to replace
   * @param value replacement text
   * @param executions positive maximum number of rewrites
   */
  @Dann(
      "Setze im TigerProxy für die Nachricht {tigerResolvedString} die Regex-Manipulation auf Feld"
          + " {string} mit Regex {tigerResolvedString} und Wert {tigerResolvedString} und {int}"
          + " Ausführungen")
  @Then(
      "Set the regex manipulation in the TigerProxy for message {tigerResolvedString} to field"
          + " {string} with regex {tigerResolvedString} and value {tigerResolvedString} with {int}"
          + " executions")
  public void setTigerProxyRegexManipulationWithExecutions(
      String message, String field, String regexFilter, String value, Integer executions) {
    if (executions == null || executions < 1) {
      throw new AssertionError("Executions must be positive");
    }
    sendRbelManipulation(Map.of(
        "name", createManipulationName("regex-modification"),
        "condition", message,
        "targetElement", field,
        "regexFilter", regexFilter,
        "replaceWith", value,
        "deleteAfterNExecutions", executions));
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
   * Verifies how often a raw HTTP header line with a specific value occurs in the currently
   * selected request.
   *
   * @param headerName HTTP header name, matched case-insensitively
   * @param headerValue HTTP header value to count exactly after trimming line whitespace
   * @param expectedOccurrences expected number of matching raw header lines
   */
  @Dann(
      "prüfe aktuelle Anfrage enthält den Header {string} mit Wert {tigerResolvedString} {int} mal")
  @Then(
      "verify current request contains header {string} with value {tigerResolvedString} {int}"
          + " times")
  public void verifyCurrentRequestHeaderValueOccurrenceCount(
      String headerName, String headerValue, int expectedOccurrences) {
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
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param privateKeyPem private key used to re-sign the token
   */
  @Dann(
      "Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert"
          + " {tigerResolvedString} mit privatem Schlüssel {tigerResolvedString}")
  @Then(
      "Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value"
          + " {tigerResolvedString} using private key {tigerResolvedString}")
  public void setTigerProxyJwtManipulationWithKey(
      String jwtLocation, String jwtField, String value, String privateKeyPem) {
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
   * Configures a JWT manipulation on the TigerProxy with condition and execution limit, without
   * re-signing.
   *
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param condition regex pattern to match request paths
   * @param executions number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann(
      "Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert"
          + " {tigerResolvedString} für Pfad {tigerResolvedString} und {int} Ausführungen")
  @Then(
      "Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value"
          + " {tigerResolvedString} for path {tigerResolvedString} with {int} executions")
  public void setTigerProxyJwtManipulationWithConditionNoResign(
      String jwtLocation, String jwtField, String value, String condition, Integer executions) {
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
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param privateKeyPem private key used to re-sign the token
   * @param condition regex pattern to match request paths
   * @param executions number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann(
      "Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert"
          + " {tigerResolvedString} mit privatem Schlüssel {tigerResolvedString} für Pfad"
          + " {tigerResolvedString} und {int} Ausführungen")
  @Then(
      "Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value"
          + " {tigerResolvedString} using private key {tigerResolvedString} for path"
          + " {tigerResolvedString} with {int} executions")
  public void setTigerProxyJwtManipulationWithCondition(
      String jwtLocation,
      String jwtField,
      String value,
      String privateKeyPem,
      String condition,
      Integer executions) {
    sendJwtManipulation(Map.ofEntries(
        Map.entry("jwtLocation", jwtLocation),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value),
        Map.entry("privateKeyPem", privateKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions)));
  }

  /**
   * Configures a signed JWT variant with an optional parameter.
   *
   * @param location intercepted JWT location
   * @param variant JWT variant
   * @param parameter optional variant parameter
   * @param key signing key
   * @param condition request condition
   * @param executions execution limit
   */
  @Dann("Setze im TigerProxy für JWT in {tigerResolvedString} die Variante {jwtVariant} "
      + "mit Parameter {tigerResolvedString} mit privatem Schlüssel {tigerResolvedString} "
      + "für Pfad {tigerResolvedString} und {int} Ausführungen")
  public void setSignedJwtVariant(String location, JwtVariant variant, String parameter,
      String key, String condition, int executions) {
    setTigerProxyJwtManipulationWithCondition(location, variant.manipulationField(),
        variant.manipulationValue(parameter), key, condition, executions);
  }

  /**
   * Configures a signed JWT variant with a replacement public JWK.
   *
   * @param location intercepted JWT location
   * @param variant JWT variant
   * @param parameter optional variant parameter
   * @param key signing key
   * @param condition request condition
   * @param executions execution limit
   */
  @Dann("Setze im TigerProxy für JWT in {tigerResolvedString} die Variante {jwtVariant} "
      + "mit Parameter {tigerResolvedString} mit privatem Schlüssel {tigerResolvedString} "
      + "für Pfad {tigerResolvedString} und {int} Ausführungen und ersetze JWK")
  public void setSignedJwtVariantReplacingJwk(String location, JwtVariant variant, String parameter,
      String key, String condition, int executions) {
    setTigerProxyJwtManipulationWithConditionAndReplaceJwk(location, variant.manipulationField(),
        variant.manipulationValue(parameter), key, condition, executions);
  }

  /**
   * Configures an Access Token variant and updates its bound DPoP proof.
   *
   * @param variant JWT variant
   * @param parameter optional variant parameter
   * @param accessKey Access Token signing key
   * @param dpopKey DPoP signing key
   * @param condition request condition
   * @param executions execution limit
   */
  @Dann("Setze im TigerProxy für Access Token die Variante {jwtVariant} "
      + "mit Parameter {tigerResolvedString} mit Access Token Key {tigerResolvedString} "
      + "und DPoP Key {tigerResolvedString} für Pfad {tigerResolvedString} und {int} Ausführungen")
  public void setAccessTokenVariant(JwtVariant variant, String parameter,
      String accessKey, String dpopKey, String condition, int executions) {
    setTigerProxyAccessTokenManipulationWithAthUpdate(variant.manipulationField(),
        variant.manipulationValue(parameter), accessKey, dpopKey, condition, executions);
  }

  /**
   * Removes one claim and re-signs the SDK token without changing its header.
   *
   * @param jwtLocation token location
   * @param jwtField claim path
   * @param privateKeyPem original signing key
   * @param condition request path selector
   * @param executions maximum manipulated requests
   */
  @Dann("Entferne im TigerProxy für JWT in {tigerResolvedString} das Feld {string} "
      + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} "
      + "und {int} Ausführungen und behalte den JWT-Header")
  public void removeTigerProxyJwtFieldPreservingHeader(String jwtLocation, String jwtField,
      String privateKeyPem, String condition, int executions) {
    manipulateSignedJwtClaim(jwtLocation, jwtField, "", "string", true, privateKeyPem,
        condition, executions);
  }

  /** Removes a claim on every matching request until the manipulation is stopped. */
  @Dann("Entferne im TigerProxy für JWT in {tigerResolvedString} das Feld {string} "
      + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} "
      + "und behalte den JWT-Header")
  public void removeTigerProxyJwtFieldPreservingHeader(String jwtLocation, String jwtField,
      String privateKeyPem, String condition) {
    manipulateSignedJwtClaim(jwtLocation, jwtField, "", "string", true, privateKeyPem,
        condition, null);
  }

  /** Changes one typed claim or removes it, retaining the original JWT header and signing key. */
  public void manipulateSignedJwtClaim(String jwtLocation, String jwtField, String value,
      String valueType, boolean delete, String privateKeyPem, String condition, Integer executions) {
    Assertions.assertThat(valueType).isIn("string", "number");
    var body = new LinkedHashMap<String, Object>(Map.ofEntries(
        Map.entry("jwtLocation", jwtLocation), Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value), Map.entry("valueType", valueType),
        Map.entry("deleteField", delete), Map.entry("privateKeyPem", privateKeyPem),
        Map.entry("condition", condition)));
    if (executions != null) {
      Assertions.assertThat(executions).isPositive();
      body.put("deleteAfterNExecutions", executions);
    }
    sendJwtManipulation(body);
  }

  /**
   * Configures a JWT manipulation on the TigerProxy with condition, execution limit, and JWK
   * replacement. The JWK in the JWT header will be replaced with the public key derived from the
   * provided private key.
   *
   * @param jwtLocation where the JWT is located (e.g., "$.header.dpop", "$.body.client_assertion")
   * @param jwtField what to change in the JWT (e.g., "header.typ", "body.iss")
   * @param value new value for the field
   * @param privateKeyPem private key used to re-sign the token and derive public key for JWK
   * @param condition regex pattern to match request paths
   * @param executions number of times to execute before auto-clearing (null = unlimited)
   */
  @Dann(
      "Setze im TigerProxy für JWT in {tigerResolvedString} das Feld {string} auf Wert"
          + " {tigerResolvedString} mit privatem Schlüssel {tigerResolvedString} für Pfad"
          + " {tigerResolvedString} und {int} Ausführungen und ersetze JWK")
  @Then(
      "Set in TigerProxy for JWT in {tigerResolvedString} the field {string} to value"
          + " {tigerResolvedString} using private key {tigerResolvedString} for path"
          + " {tigerResolvedString} with {int} executions and replace JWK")
  public void setTigerProxyJwtManipulationWithConditionAndReplaceJwk(
      String jwtLocation,
      String jwtField,
      String value,
      String privateKeyPem,
      String condition,
      Integer executions) {
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
    configureAccessTokenManipulation(jwtField, value, null, accessTokenKeyPem, dpopKeyPem, condition, executions);
  }

  /**
   * Configures an Access Token replacement with an explicit JSON type and a matching DPoP proof.
   *
   * @param jwtField claim path in the JWT
   * @param value replacement value
   * @param valueType explicit JSON type: string or number
   * @param accessTokenKeyPem key used to re-sign the Access Token
   * @param dpopKeyPem key used to re-sign the DPoP proof
   * @param condition request-path selection
   * @param executions number of requests to manipulate
   */
  @Dann(
      "Setze im TigerProxy für Access Token das Feld {string} auf Wert {tigerResolvedString} mit"
          + " Typ {tigerResolvedString} mit Access Token Key {tigerResolvedString} und DPoP Key"
          + " {tigerResolvedString} für Pfad {tigerResolvedString} und {int} Ausführungen")
  @Then(
      "Set in TigerProxy for access token the field {string} to value {tigerResolvedString} with"
          + " type {tigerResolvedString} using access token key {tigerResolvedString} and DPoP key"
          + " {tigerResolvedString} for path {tigerResolvedString} with {int} executions")
  public void setTigerProxyAccessTokenManipulationWithValueType(
      String jwtField,
      String value,
      String valueType,
      String accessTokenKeyPem,
      String dpopKeyPem,
      String condition,
      Integer executions) {
    configureAccessTokenManipulation(jwtField, value, valueType, accessTokenKeyPem, dpopKeyPem, condition, executions);
  }

  /**
   * Builds the common request, omitting valueType for callers using the original step.
   *
   * @param jwtField claim path in the JWT
   * @param value replacement value
   * @param valueType optional explicit JSON type
   * @param accessTokenKeyPem key used to re-sign the Access Token
   * @param dpopKeyPem key used to re-sign the DPoP proof
   * @param condition request-path selection
   * @param executions number of requests to manipulate
   */
  private void configureAccessTokenManipulation(String jwtField, String value, String valueType,
      String accessTokenKeyPem, String dpopKeyPem, String condition, Integer executions) {
    var body = new LinkedHashMap<String, Object>(Map.ofEntries(
        Map.entry("jwtLocation", getRequiredRbelPath("headers.authorization.root")),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", value),
        Map.entry("privateKeyPem", accessTokenKeyPem),
        Map.entry("condition", condition),
        Map.entry("deleteAfterNExecutions", executions),
        Map.entry("dpopLocation", getRequiredRbelPath("headers.dpop.root")),
        Map.entry("dpopPrivateKeyPem", dpopKeyPem),
        Map.entry("updateAth", true)));
    if (valueType != null) {
      body.put("valueType", valueType);
    }
    sendJwtManipulation(body);
  }

  /**
   * Keeps newly inserted numeric JWT claims numeric while re-signing token and bound DPoP proof.
   */
  @Dann("Setze im TigerProxy für Access Token das numerische Feld {string} auf Wert {int} "
      + "mit Access Token Key {tigerResolvedString} und DPoP Key {tigerResolvedString} "
      + "für Pfad {tigerResolvedString} und {int} Ausführungen")
  public void setNumericAccessTokenClaim(String jwtField, int value,
      String accessTokenKeyPem, String dpopKeyPem, String condition, int executions) {
    sendJwtManipulation(Map.ofEntries(
        Map.entry("jwtLocation", getRequiredRbelPath("headers.authorization.root")),
        Map.entry("jwtField", jwtField),
        Map.entry("replaceWith", Integer.toString(value)),
        Map.entry("valueType", "number"),
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
   * Clears both manipulation types and requires success before identity-scoped resource cleanup.
   *
   * @throws AssertionError if the proxy is unavailable or rejects either reset
   */
  public void stopAllTigerProxyManipulationsStrictly() {
    if (resolveTigerProxyBaseUrl("notification cleanup") == null) {
      throw new AssertionError("TigerProxy is required for verified notification cleanup.");
    }
    resetBothManipulationTypes();
  }

  /**
   * Resets proxy manipulations while preserving the history of the active remote subscription.
   */
  void resetTigerProxyStateIfAvailable() {
    resetTigerProxyManipulationsIfAvailable();
  }

  /**
   * Clears standalone messages and requires an acknowledgement containing the removed count.
   *
   * @throws AssertionError if configuration, transport or the reset acknowledgement is invalid
   */
  void resetTigerProxyMessagesStrictly() {
    if (resolveTigerProxyBaseUrl("message reset") == null) {
      throw new AssertionError("A configured TigerProxy is required for the standalone message reset.");
    }
    try {
      var response = sendProxyControlRequest(
          HttpMethod.GET, URI.create(getUrl("${paths.tigerProxy.resetMessagesPath}")), null);
      var acknowledgement = new ObjectMapper().readTree(response);
      var count = acknowledgement == null ? null : acknowledgement.path("numMsgs");
      if (count == null || !count.isIntegralNumber() || !count.canConvertToInt()
          || count.asInt() < 0) {
        throw new AssertionError("TigerProxy message reset returned an invalid acknowledgement.");
      }
      log.info("Standalone TigerProxy message reset acknowledged: {} messages removed.", count.asInt());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("TigerProxy message reset was interrupted.", e);
    } catch (IOException | RuntimeException e) {
      throw new AssertionError("TigerProxy message reset failed; standalone history was not cleared reliably.", e);
    }
  }

  /**
   * Clears RBEL and JWT manipulations without resetting messages when a proxy is configured.
   */
  private void resetTigerProxyManipulationsIfAvailable() {
    var baseUrl = resolveTigerProxyBaseUrl("manipulation reset");
    if (baseUrl == null) {
      if (TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId")
          .filter(id -> !id.isBlank()).isPresent()) {
        throw new AssertionError(
            "Configured TigerProxy base URL is unresolved; manipulations cannot be reset.");
      }
      return;
    }
    resetBothManipulationTypes();
  }

  /** Attempts independent resets and retains every failure so subsequent scenarios cannot hide stale state. */
  private void resetBothManipulationTypes() {
    AssertionError failure = null;
    boolean interrupted = false;
    var methods = List.of(HttpMethod.DELETE, HttpMethod.POST);
    var paths =
        List.of(
            "${paths.tigerProxy.manipulationPath}", "${paths.tigerProxy.resetJwtManipulationPath}");
    try {
      for (int index = 0; index < paths.size(); index++) {
        try {
          sendProxyControlRequest(methods.get(index), URI.create(getUrl(paths.get(index))), null);
        } catch (IOException | InterruptedException | RuntimeException e) {
          if (e instanceof InterruptedException) {
            interrupted = true;
            // Permit the independent cleanup request, then preserve the caller's cancellation.
            Thread.interrupted();
          }
          if (failure == null) {
            failure =
                new AssertionError(
                    "TigerProxy manipulation reset failed; manipulations may remain active.", e);
          } else {
            failure.addSuppressed(e);
          }
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (failure != null) {
      throw failure;
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
      sendProxyControlRequest(HttpMethod.POST, URI.create(url), body);
    } catch (Exception e) {
      throwTigerProxyManipulationFailure("JWT manipulation", baseUrl, e);
    }
  }

  /** Configures typed claims as one atomic mutation followed by one signature operation. */
  @Dann(
      "Setze im TigerProxy für JWT in {tigerResolvedString} folgende Felder "
          + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} "
          + "und {int} Ausführungen")
  public void setAtomicJwtFields(
      String location, String key, String condition, int executions, DataTable fields) {
    var values = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
    var json = new ObjectMapper();
    var rows = fields.cells();
    Assertions.assertThat(rows.getFirst()).containsExactly("Feld", "JSON-Wert");
    for (var row : rows.subList(1, rows.size())) {
      Assertions.assertThat(row).hasSize(2);
      var field = row.getFirst();
      var value = json.readTree(TigerGlobalConfiguration.resolvePlaceholders(row.get(1)));
      if (value == null || values.putIfAbsent(field, value) != null) {
        throw new AssertionError("JWT fields require unique paths and JSON values");
      }
    }
    configureAtomicJwtFields(location, key, condition, executions, values);
  }

  /** Shares the typed wire contract with proof-specific manipulation steps. */
  void configureAtomicJwtFields(
      String location,
      String key,
      String condition,
      int executions,
      Map<String, tools.jackson.databind.JsonNode> fields) {
    Assertions.assertThat(executions).as("Executions must be positive").isPositive();
    Assertions.assertThat(fields).as("Atomic JWT fields must not be empty").isNotEmpty();
    sendJwtManipulation(
        Map.of(
            "jwtLocation",
            location,
            "privateKeyPem",
            key,
            "condition",
            condition,
            "deleteAfterNExecutions",
            executions,
            "jwtFields",
            fields));
  }

  /** Expires one live SDK proof while retaining its identity, nonce and JOSE header. */
  @Dann(
      "Setze im TigerProxy für JWT in {tigerResolvedString} iat und exp in die Vergangenheit "
          + "mit privatem Schlüssel {tigerResolvedString} für Pfad {tigerResolvedString} "
          + "und {int} Ausführungen")
  public void expireJwt(String location, String key, String condition, int executions) {
    var now = java.time.Instant.now().getEpochSecond();
    var factory = tools.jackson.databind.node.JsonNodeFactory.instance;
    configureAtomicJwtFields(
        location,
        key,
        condition,
        executions,
        Map.of(
            "body.iat", factory.numberNode(now - 330), "body.exp", factory.numberNode(now - 300)));
  }

  /**
   * Sends an RBel manipulation request to TigerProxy. Central method handling all HTTP
   * communication for RBel path manipulations.
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
      sendProxyControlRequest(HttpMethod.PUT, URI.create(url), body);
      sendProxyControlRequest(HttpMethod.GET, URI.create(url), null);
    } catch (Exception e) {
      throwTigerProxyManipulationFailure("RBel manipulation", baseUrl, e);
    }
  }

  /**
   * Configures a standalone proxy route without recording the management request.
   *
   * @param route JSON route definition
   * @throws IOException if the management request fails
   * @throws InterruptedException if the request is interrupted
   */
  @Dann("im TigerProxy wird folgende Route eingerichtet:")
  public void configureTigerProxyRoute(String route) throws IOException, InterruptedException {
    var body = new ObjectMapper().readTree(TigerGlobalConfiguration.resolvePlaceholders(route));
    sendProxyControlRequest(HttpMethod.PUT, URI.create(getUrl("/route")), body);
  }

  /**
   * Sends proxy control traffic through a shared direct client, bypassing Tiger's proxy and history.
   *
   * @param method HTTP method
   * @param uri resolved management endpoint
   * @param body JSON payload, or null for an empty request
   * @return response body
   * @throws IOException if transport fails or the endpoint rejects the request
   * @throws InterruptedException if the request is interrupted
   */
  private String sendProxyControlRequest(HttpMethod method, URI uri, Object body)
      throws IOException, InterruptedException {
    var payload = body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body));
    var request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .method(method.name(), payload)
        .build();
    try {
      var response = PROXY_CONTROL_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException(
            "TigerProxy " + method + " " + uri + " returned HTTP " + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
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
   * Checks for connection, connection-timeout or DNS failures, including direct JDK client failures.
   *
   * @param throwable exception to inspect
   * @return {@code true} when the failure is caused by connection or DNS issues
   */
  private boolean isTigerProxyReachabilityFailure(final Throwable throwable) {
    for (var current = throwable; current != null; current = current.getCause()) {
      if (current instanceof ConnectException
          || current instanceof HttpConnectTimeoutException
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

  /** Remembers the selected request UUID as the start of a remote-history interval. */
  @Dann("merke die aktuelle Anfrage als Proxy-Historienbeginn")
  public void rememberProxyHistoryStart() {
    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    if (request == null) {
      throw new AssertionError("No request selected for the proxy history checkpoint");
    }
    historyCheckpoint = request.getUuid();
  }

  /**
   * Waits for all messages indexed since the checkpoint to reach scenario-local RBEL.
   *
   * @throws IOException if the proxy index cannot be read
   * @throws InterruptedException if synchronization is interrupted
   */
  @Dann("synchronisiere die Proxy-Historie seit dem gemerkten Beginn")
  public void synchronizeProxyHistory() throws IOException, InterruptedException {
    if (historyCheckpoint == null || resolveTigerProxyBaseUrl("history synchronization") == null) {
      throw new AssertionError("Proxy history checkpoint and configured proxy are required");
    }
    var response = sendProxyControlRequest(HttpMethod.GET,
        URI.create(getUrl("${paths.tigerProxy.messagesWithMetaPath}")), null);
    var messages = new ObjectMapper().readTree(response).path("messages");
    if (!messages.isArray()) {
      throw new AssertionError("Proxy message index is not an array");
    }
    Long sequence = null;
    for (var message : messages) {
      if (historyCheckpoint.equals(message.path("uuid").asString())) {
        sequence = message.path("sequenceNumber").asLong();
      }
    }
    if (sequence == null) {
      throw new AssertionError("Checkpoint request is missing from the proxy message index");
    }
    var expected = new java.util.HashSet<String>();
    for (var message : messages) {
      if (message.path("sequenceNumber").asLong() >= sequence) {
        expected.add(message.path("uuid").asString());
      }
    }
    de.gematik.zeta.services.BoundedObservation.await(
        de.gematik.zeta.services.RecordedMessages::snapshot,
        local -> local.stream().map(de.gematik.rbellogger.data.RbelElement::getUuid)
            .collect(java.util.stream.Collectors.toSet()).containsAll(expected),
        Duration.ofSeconds(20), Duration.ofMillis(100),
        "Scenario history is missing messages from the completed proxy interval");
  }

}
