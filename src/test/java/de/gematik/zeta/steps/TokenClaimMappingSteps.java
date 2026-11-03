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
import io.cucumber.java.de.Dann;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Compares access-token claims with the correlated CC policy transaction and a scope reference
 * independent of the observed token.
 */
public class TokenClaimMappingSteps {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Result of the claim comparison.
   *
   * @param report readable comparison result
   * @param troubleFound whether any claim differs from its reference
   */
  public record ClaimReport(String report, boolean troubleFound) {}

  /**
   * Stores the exact audience value from a scalar or singleton-array aud member.
   *
   * @param json JSON object containing aud
   * @param variableName scenario-local variable receiving the audience string
   * @throws AssertionError if aud is missing, blank, nontextual or contains multiple entries
   */
  @Dann("speichere die einzelne Audience aus JSON {tigerResolvedString} in Variable {string}")
  public void storeSingleAudience(String json, String variableName) {
    var aud = object(json).path("aud");
    if (aud.isArray() && aud.size() == 1) {
      aud = aud.get(0);
    }
    if (!aud.isTextual() || aud.asString().isBlank()) {
      throw new AssertionError("Expected exactly one nonempty audience string");
    }
    TigerGlobalConfiguration.putValue(variableName, aud.asString(), ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Checks newly issued legacy tokens; acceptance of older tokens without ver is a PEP duty.
   *
   * @param claimsJson independently signature-verified access-token payload
   * @param requestedAudience decoded audience from the correlated SDK request
   */
  @Dann(
      "prüfe neu ausgestellte Legacy-Token-Claims {tigerResolvedString} auf unveränderte Audience"
          + " {tigerResolvedString} und ver 1 mit soft assert")
  public void checkLegacyIssuanceClaims(String claimsJson, String requestedAudience) {
    var report = evaluateLegacyIssuanceClaims(claimsJson, requestedAudience);
    ReportAttachments.addText("Legacy Token Issuance", report.report());
    if (report.troubleFound()) {
      SoftAssertionsContext.recordSoftFailure(
          "Legacy Token Issuance", new AssertionError(report.report()));
    }
  }

  /**
   * Distinguishes the AS issuance requirement from backward-compatible PEP interpretation.
   *
   * @param claimsJson access-token payload
   * @param requestedAudience one independently observed, decoded legacy audience
   * @return all audience and explicit-version deviations without disclosing token contents
   */
  public ClaimReport evaluateLegacyIssuanceClaims(String claimsJson, String requestedAudience) {
    if (requestedAudience == null
        || requestedAudience.isBlank()
        || requestedAudience.contains("${")) {
      throw new AssertionError("Correlated legacy request audience is missing or unresolved");
    }
    var token = object(claimsJson);
    var aud = token.path("aud");
    List<String> errors = new ArrayList<>();
    boolean exactAudience = aud.isTextual() && requestedAudience.equals(aud.textValue());
    if (aud.isArray() && aud.size() == 1) {
      exactAudience = aud.get(0).isTextual() && requestedAudience.equals(aud.get(0).textValue());
    }
    if (!exactAudience) {
      errors.add(
          "aud: newly issued legacy token must preserve the single requested audience exactly");
    }
    var version = token.path("ver");
    if (!version.isIntegralNumber() || !BigInteger.ONE.equals(version.bigIntegerValue())) {
      errors.add("ver: newly issued legacy token must explicitly contain integer 1");
    }
    return new ClaimReport(
        errors.isEmpty()
            ? "Legacy audience preserved and explicit ver=1 present"
            : String.join("\n", errors),
        !errors.isEmpty());
  }

  /**
   * Checks the token using the observed decision and the supplied scope reference.
   *
   * @param claimsJson access-token payload
   * @param inputJson correlated policy input
   * @param decisionJson correlated policy response
   * @param grantedScopes space-separated reference scopes
   */
  @Dann(
      "prüfe Access-Token-Claims {tigerResolvedString} gegen Policy-Input {tigerResolvedString},"
          + " Entscheidung {tigerResolvedString} und gewährte Scopes {tigerResolvedString} mit soft"
          + " assert")
  public void checkAccessTokenClaims(
      String claimsJson, String inputJson, String decisionJson, String grantedScopes) {
    var report = evaluateAccessTokenClaims(claimsJson, inputJson, decisionJson, grantedScopes);
    ReportAttachments.addText("Access Token Claim Mapping", report.report());
    if (report.troubleFound()) {
      SoftAssertionsContext.recordSoftFailure(
          "Access Token Claim Mapping", new AssertionError(report.report()));
    }
  }

  /**
   * Checks claims against the scopes observed in the correlated form-encoded token request.
   *
   * @param claimsJson access-token payload
   * @param inputJson correlated policy input
   * @param decisionJson correlated policy response
   * @param requestScopes URL-encoded scope value from the token request
   */
  @Dann(
      "prüfe Access-Token-Claims {tigerResolvedString} gegen Policy-Input {tigerResolvedString},"
          + " Entscheidung {tigerResolvedString} und Scopes aus Token-Request {tigerResolvedString}"
          + " mit soft assert")
  public void checkAccessTokenClaimsWithRequestScopes(
      String claimsJson, String inputJson, String decisionJson, String requestScopes) {
    if (requestScopes == null || requestScopes.isBlank() || requestScopes.contains("${")) {
      throw new AssertionError("Correlated token-request scopes are missing or unresolved");
    }
    var decodedScopes = URLDecoder.decode(requestScopes, StandardCharsets.UTF_8);
    if (decodedScopes.isBlank()) {
      throw new AssertionError("Correlated token-request scopes are missing or unresolved");
    }
    checkAccessTokenClaims(claimsJson, inputJson, decisionJson, decodedScopes);
  }

  /**
   * Evaluates CC claims without scenario/reporting side effects.
   *
   * @param claimsJson access-token payload
   * @param inputJson policy input, optionally wrapped in input
   * @param decisionJson policy response containing result
   * @param grantedScopes scope reference, never copied from the observed token
   * @return comparison report
   */
  public ClaimReport evaluateAccessTokenClaims(
      String claimsJson, String inputJson, String decisionJson, String grantedScopes) {
    final var token = object(claimsJson);
    var input = object(inputJson);
    if (input.has("input")) {
      input = input.get("input");
    }
    var decision = object(decisionJson).path("result");
    List<String> errors = new ArrayList<>();
    if (!input.isObject()) {
      throw new AssertionError("Policy input must be an object");
    }
    if (!decision.path("allow").isBoolean() || !decision.path("allow").booleanValue()) {
      errors.add("allow: token issuance requires a positive policy decision");
    }
    compareClaim(errors, token, "sub", input.at("/user_info/identifier"));
    compareClaim(errors, token, "profession_oid", input.at("/user_info/professionOID"));
    // Policy user_info and JWT claims have different names in their upstream schemas.
    for (var mapping : new String[][] {{"common_name", "commonName"},
        {"organization_name", "organizationName"}, {"birthdate", "birthdate"}}) {
      if (token.has(mapping[0])) {
        compareClaim(errors, token, mapping[0], input.at("/user_info/" + mapping[1]));
      }
    }
    for (var claim : List.of("client_id", "product_id", "product_version")) {
      compareClaim(errors, token, claim, input.at("/client_registration_data/" + claim));
    }
    compareClaim(errors, token, "ip_address", input.at("/authorization_request/ip_address"));
    compareClaim(errors, token, "acr", input.at("/authorization_request/acr"));
    if (token.has("platform")) {
      compareClaim(errors, token, "platform", input.at("/client_registration_data/platform"));
    }

    var resource = input.at("/authorization_request/resource");
    boolean contractV2 = !resource.isMissingNode() && !resource.isNull();
    if (contractV2 && input.path("authorization_request").has("audience")) {
      errors.add(
          "audience: a resource-based request must not also use the legacy audience parameter");
    }
    if (input.has("version")
        && !(contractV2 ? "1.1" : "1.0").equals(input.path("version").asText())) {
      errors.add("version: policy input version does not match its audience/resource contract");
    }
    var expectedAudience =
        contractV2 ? decision.path("aud") : input.at("/authorization_request/audience");
    var expected = audience(expectedAudience, "expected aud", errors);
    var actual = audience(token.path("aud"), "token aud", errors);
    if (!expected.equals(actual)) {
      errors.add("aud: expected " + expected + " but was " + actual);
    }
    if (contractV2 && (!resource.isTextual() || resource.textValue().isBlank())) {
      errors.add("resource: expected a nonempty resource indicator");
    }
    if (contractV2
        && (!token.path("ver").isIntegralNumber()
            || !BigInteger.TWO.equals(token.path("ver").bigIntegerValue()))) {
      errors.add("ver: a resource-based request requires token contract version 2");
    }
    if (!contractV2
        && token.has("ver")
        && (!token.path("ver").isIntegralNumber()
            || !BigInteger.ONE.equals(token.path("ver").bigIntegerValue()))) {
      errors.add("ver: a legacy audience request requires version 1 or an absent version");
    }
    if (contractV2 && expected.contains(resource.asText())) {
      errors.add("aud: logical audience must not be the resource URL");
    }
    if (grantedScopes == null || grantedScopes.contains("${")) {
      throw new AssertionError("Scope reference is missing or unresolved");
    }
    var expectedScopes = scopes(grantedScopes);
    if (!token.path("scope").isTextual()) {
      errors.add("scope: token claim must be a string");
    } else if (!scopes(token.get("scope").textValue()).containsAll(expectedScopes)) {
      errors.add(
          "scope: must contain expected granted scopes " + expectedScopes + " but was " + token.get("scope"));
    }
    return new ClaimReport(
        errors.isEmpty()
            ? "Access Token claims match the policy transaction and scope reference"
            : String.join("\n", errors),
        !errors.isEmpty());
  }

  /** Checks only the AS-owned audience/version obligation against an independent resource grant. */
  public ClaimReport evaluateResourceIssuanceClaims(
      String claimsJson, String decisionJson, String requestedResource) {
    if (requestedResource == null
        || requestedResource.isBlank()
        || requestedResource.contains("${")) {
      throw new AssertionError("Missing independent resource indicator");
    }
    final java.net.URI resource;
    try {
      resource = java.net.URI.create(requestedResource);
    } catch (IllegalArgumentException error) {
      throw new AssertionError("Invalid independent resource indicator");
    }
    if (!resource.isAbsolute() || resource.getRawFragment() != null) {
      throw new AssertionError("Invalid independent resource indicator");
    }
    var decision = object(decisionJson).path("result");
    var fixtureErrors = new ArrayList<String>();
    var expected = audience(decision.path("aud"), "policy aud", fixtureErrors);
    if (!decision.path("allow").isBoolean()
        || !decision.path("allow").booleanValue()
        || !fixtureErrors.isEmpty()
        || expected.contains(requestedResource)) {
      throw new AssertionError("Expected a valid allow decision with independent logical audience");
    }
    var token = object(claimsJson);
    var errors = new ArrayList<String>();
    if (!expected.equals(audience(token.path("aud"), "token aud", errors))) {
      errors.add("aud: token differs from its own policy decision");
    }
    var version = token.path("ver");
    if (!version.isIntegralNumber() || !BigInteger.TWO.equals(version.bigIntegerValue())) {
      errors.add("ver: resource issuance must contain exact integer 2");
    }
    return new ClaimReport(
        errors.isEmpty()
            ? "Resource audience and ver=2 match the own policy"
            : String.join("\n", errors),
        !errors.isEmpty());
  }

  private static Set<String> scopes(String text) {
    var result = new LinkedHashSet<String>();
    Arrays.stream(text.trim().split("\\s+")).filter(s -> !s.isBlank()).forEach(result::add);
    return result;
  }

  private static Set<String> audience(JsonNode node, String label, List<String> errors) {
    Set<String> result = new LinkedHashSet<>();
    if (node.isTextual() && !node.textValue().isBlank()) {
      result.add(node.textValue());
    } else if (node.isArray() && !node.isEmpty()) {
      for (var item : node) {
        if (!item.isTextual() || item.textValue().isBlank()) {
          errors.add(label + ": expected nonempty string values");
        } else {
          result.add(item.textValue());
        }
      }
    } else {
      errors.add(label + ": missing or malformed audience");
    }
    return result;
  }

  private static void compareClaim(
      List<String> errors, JsonNode token, String claim, JsonNode expected) {
    if (expected.isMissingNode() || expected.isNull()) {
      if (token.has(claim)) {
        errors.add(claim + ": token claim has no value in its upstream policy source field");
      }
      return;
    }
    if (!expected.isTextual()
        || !token.path(claim).isTextual()
        || !expected.equals(token.get(claim))) {
      errors.add(claim + ": expected " + expected + " but was " + token.path(claim));
    }
  }

  private static JsonNode object(String value) {
    try {
      var node = JSON.readTree(value);
      if (node == null || !node.isObject()) {
        throw new AssertionError("Expected JSON object");
      }
      return node;
    } catch (JacksonException ex) {
      throw new AssertionError("Invalid JSON object", ex);
    }
  }
}
