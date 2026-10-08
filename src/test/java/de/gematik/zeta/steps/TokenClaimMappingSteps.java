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
import io.cucumber.java.ParameterType;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cucumber steps for comparing token claims with the Policy Engine input that led to token issuance.
 */
@Slf4j
public class TokenClaimMappingSteps {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Result of one token claim mapping evaluation.
   *
   * @param report       human-readable comparison report
   * @param troubleFound whether one or more mapped claims differ from the Policy Engine input
   */
  public record ClaimReport(String report, boolean troubleFound) {
  }

  private enum Status {
    OK,
    TROUBLE,
    SKIPPED
  }

  private record ClaimCheck(Status status, String claim, String message) {
  }

  /**
   * Token variants supported by the token claim mapping step.
   */
  public enum TokenType {
    Access, Refresh
  }

  /**
   * Converts the Cucumber token type parameter to the matching enum value.
   *
   * @param tokenType token type from the Gherkin step
   * @return matching token type enum value
   */
  @ParameterType("Access|Refresh|access|refresh")
  public TokenType tokenType(String tokenType) {
    for (TokenType type : TokenType.values()) {
      if (type.name().equalsIgnoreCase(tokenType)) {
        return type;
      }
    }

    throw new AssertionError("Unknown token type: " + tokenType);
  }

  /**
   * Verifies that a token contains claims matching the corresponding Policy Engine input.
   *
   * @param tokenClaimsJson token payload as JSON
   * @param policyInputJson Policy Engine input as JSON
   */
  @Dann("prüfe {tokenType} Token Claims {tigerResolvedString} passen zum Policy-Engine-Input {tigerResolvedString} und nutze soft assert")
  @Then("check {tokenType} token claims {tigerResolvedString} match policy engine input {tigerResolvedString} with soft assert")
  public void checkTokenClaimsAgainstPolicyInput(TokenType tokenType, String tokenClaimsJson, String policyInputJson) {
    switch (tokenType) {
      case Access -> {
        var report = evaluateAccessTokenClaims(tokenClaimsJson, policyInputJson);
        attachAndRecord("Access Token Claim Mapping", report);
      }
      case Refresh -> {
        var report = evaluateRefreshTokenClaims(tokenClaimsJson, policyInputJson);
        attachAndRecord("Refresh Token Claim Mapping", report);
      }
      case null, default -> throw new AssertionError("Unknown TokenType: " + tokenType + " to validate!");
    }
  }

  /**
   * Builds a comparison report for an access token without recording a Cucumber assertion.
   *
   * @param tokenClaimsJson token payload as JSON
   * @param policyInputJson Policy Engine input as JSON
   * @return comparison report
   */
  public ClaimReport evaluateAccessTokenClaims(String tokenClaimsJson, String policyInputJson) {
    var tokenClaims = parseObject(tokenClaimsJson, "access token claims");
    var policyInput = normalizePolicyInput(parseObject(policyInputJson, "policy engine input"));
    var checks = new ArrayList<ClaimCheck>();

    addCommonClaimChecks(checks, tokenClaims, policyInput);
    addScopeChecks(checks, tokenClaims, policyInput);
    addAccessAudienceChecks(checks, tokenClaims, policyInput);

    return toReport("Access Token", checks);
  }

  /**
   * Builds a comparison report for a refresh token without recording a Cucumber assertion.
   *
   * @param tokenClaimsJson token payload as JSON
   * @param policyInputJson Policy Engine input as JSON
   * @return comparison report
   */
  public ClaimReport evaluateRefreshTokenClaims(String tokenClaimsJson, String policyInputJson) {
    var tokenClaims = parseObject(tokenClaimsJson, "refresh token claims");
    var policyInput = normalizePolicyInput(parseObject(policyInputJson, "policy engine input"));
    var checks = new ArrayList<ClaimCheck>();

    addCommonClaimChecks(checks, tokenClaims, policyInput);
    addScopeChecks(checks, tokenClaims, policyInput);
    addRefreshAudienceChecks(checks, tokenClaims);

    return toReport("Refresh Token", checks);
  }

  /**
   * Adds all direct user and client data claim mappings shared by access and refresh tokens.
   *
   * @param checks      mutable check list
   * @param tokenClaims token claim JSON object
   * @param policyInput normalized Policy Engine input object
   */
  private void addCommonClaimChecks(List<ClaimCheck> checks, JsonNode tokenClaims, JsonNode policyInput) {
    addEqualityCheck(checks, "sub", tokenClaims, policyInput, "/user_info/identifier");
    addEqualityCheck(checks, "profession_oid", tokenClaims, policyInput, "/user_info/professionOID");
    addEqualityCheck(checks, "client_id", tokenClaims, policyInput, "/client_registration_data/client_id");
    addEqualityCheck(checks, "azp", tokenClaims, policyInput, "/client_registration_data/client_id");
    addEqualityCheck(checks, "product_id", tokenClaims, policyInput, "/client_registration_data/product_id");
    addEqualityCheck(checks, "product_version", tokenClaims, policyInput, "/client_registration_data/product_version");
    addEqualityCheck(checks, "platform", tokenClaims, policyInput, "/client_registration_data/platform");
    addEqualityCheck(checks, "ip_address", tokenClaims, policyInput, "/authorization_request/ip_address");
  }

  /**
   * Adds scope checks: requested scopes must be present and no additional scopes may appear.
   *
   * @param checks      mutable check list
   * @param tokenClaims token claim JSON object
   * @param policyInput normalized Policy Engine input object
   */
  private void addScopeChecks(List<ClaimCheck> checks, JsonNode tokenClaims, JsonNode policyInput) {
    var expectedScopesNode = policyInput.at("/authorization_request/scopes");
    if (containsNonScalarValue(expectedScopesNode)) {
      checks.add(new ClaimCheck(Status.TROUBLE, "scope",
          "policy input path /authorization_request/scopes contains non-scalar scope values"));
      return;
    }
    var expectedScopes = stringSet(expectedScopesNode);
    if (expectedScopes.isEmpty()) {
      checks.add(new ClaimCheck(Status.SKIPPED, "scope", "no requested scopes in policy input"));
      return;
    }

    var actualScopeNode = tokenClaims.get("scope");
    if (isAbsent(actualScopeNode)) {
      checks.add(new ClaimCheck(Status.TROUBLE, "scope", "missing token claim; expected " + expectedScopes));
      return;
    }

    var actualScopeValue = textValue(actualScopeNode);
    if (actualScopeValue == null) {
      checks.add(new ClaimCheck(Status.TROUBLE, "scope",
          "token claim is not a scalar value; expected " + expectedScopes));
      return;
    }

    var actualScopes = Arrays.stream(actualScopeValue.trim().split("\\s+"))
        .filter(scope -> !scope.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    var missing = expectedScopes.stream()
        .filter(expected -> !actualScopes.contains(expected))
        .toList();
    var extras = actualScopes.stream()
        .filter(actual -> !expectedScopes.contains(actual))
        .toList();

    checks.add(missing.isEmpty()
        ? new ClaimCheck(Status.OK, "scope.requested", "all requested scopes are present: " + expectedScopes)
        : new ClaimCheck(Status.TROUBLE, "scope.requested",
            "missing requested scopes " + missing + "; actual " + actualScopes));
    checks.add(extras.isEmpty()
        ? new ClaimCheck(Status.OK, "scope.extra", "no additional scopes found")
        : new ClaimCheck(Status.TROUBLE, "scope.extra",
            "additional scopes " + extras + "; requested only " + expectedScopes));
  }

  /**
   * Adds access token audience checks against the resource audience requested from the Policy Engine.
   *
   * @param checks      mutable check list
   * @param tokenClaims token claim JSON object
   * @param policyInput normalized Policy Engine input object
   */
  private void addAccessAudienceChecks(List<ClaimCheck> checks, JsonNode tokenClaims, JsonNode policyInput) {
    var expectedAudienceNode = policyInput.at("/authorization_request/audience");
    if (containsNonScalarValue(expectedAudienceNode)) {
      checks.add(new ClaimCheck(Status.TROUBLE, "aud",
          "policy input path /authorization_request/audience contains non-scalar audience values"));
      return;
    }
    var expectedAudiences = stringSet(expectedAudienceNode);
    addAudienceCheck(checks, "aud", tokenClaims.get("aud"), expectedAudiences, "requested audience");
  }

  /**
   * Adds refresh token audience checks against the Authorization Server issuer.
   *
   * @param checks      mutable check list
   * @param tokenClaims token claim JSON object
   */
  private void addRefreshAudienceChecks(List<ClaimCheck> checks, JsonNode tokenClaims) {
    var expectedAudience = expectedAuthorizationServerAudience();
    if (expectedAudience == null) {
      checks.add(new ClaimCheck(Status.TROUBLE, "aud",
          "authorization server audience could not be derived from zeta_base_url and paths.guard.tokenEndpointPath"));
      return;
    }
    addAudienceCheck(checks, "aud", tokenClaims.get("aud"), Set.of(expectedAudience),
        "authorization server audience");
  }

  /**
   * Adds an exact audience check.
   *
   * @param checks             mutable check list
   * @param claim              audience claim name
   * @param actualAudienceNode actual token audience value
   * @param expectedAudiences  expected audience values
   * @param expectationLabel   diagnostic label for the expected values
   */
  private void addAudienceCheck(List<ClaimCheck> checks, String claim, JsonNode actualAudienceNode,
      Set<String> expectedAudiences, String expectationLabel) {
    if (expectedAudiences.isEmpty()) {
      checks.add(new ClaimCheck(Status.SKIPPED, claim, "no " + expectationLabel + " available"));
      return;
    }
    if (isAbsent(actualAudienceNode)) {
      checks.add(new ClaimCheck(Status.TROUBLE, claim, "missing token claim; expected " + expectedAudiences));
      return;
    }
    var actualAudiences = stringSet(actualAudienceNode);
    if (actualAudiences.isEmpty()) {
      if (containsNonScalarValue(actualAudienceNode)) {
        checks.add(new ClaimCheck(Status.TROUBLE, claim,
            "token claim contains no scalar " + expectationLabel + " values; expected " + expectedAudiences));
        return;
      }
      checks.add(new ClaimCheck(Status.TROUBLE, claim, "missing token claim; expected " + expectedAudiences));
      return;
    }

    var missing = expectedAudiences.stream()
        .filter(expected -> !actualAudiences.contains(expected))
        .toList();
    var extras = actualAudiences.stream()
        .filter(actual -> !expectedAudiences.contains(actual))
        .toList();

    checks.add(missing.isEmpty()
        ? new ClaimCheck(Status.OK, claim + ".requested", "all " + expectationLabel
                                                          + " values are present: " + expectedAudiences)
        : new ClaimCheck(Status.TROUBLE, claim + ".requested",
            "missing " + expectationLabel + " values " + missing + "; actual " + actualAudiences));
    checks.add(extras.isEmpty()
        ? new ClaimCheck(Status.OK, claim + ".extra", "no additional " + expectationLabel + " values found")
        : new ClaimCheck(Status.TROUBLE, claim + ".extra",
            "additional " + expectationLabel + " values " + extras + "; expected only " + expectedAudiences));
  }

  /**
   * Derives the Authorization Server audience from {@code zeta_base_url} and the configured token endpoint path.
   *
   * @return expected Authorization Server audience or {@code null} when required config is missing
   */
  private String expectedAuthorizationServerAudience() {
    var zetaBaseUrl = TigerGlobalConfiguration.readStringOptional("zeta_base_url")
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .orElse(null);
    var tokenEndpointPath = TigerGlobalConfiguration.readStringOptional("paths.guard.tokenEndpointPath")
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .orElse(null);
    if (zetaBaseUrl == null || tokenEndpointPath == null) {
      return null;
    }
    var tokenSuffix = "/protocol/openid-connect/token";
    if (!tokenEndpointPath.endsWith(tokenSuffix)) {
      return null;
    }
    var baseUrl = zetaBaseUrl.startsWith("http://") || zetaBaseUrl.startsWith("https://")
        ? zetaBaseUrl
        : "https://" + zetaBaseUrl;
    return baseUrl + tokenEndpointPath.substring(0, tokenEndpointPath.length() - tokenSuffix.length());
  }

  /**
   * Adds one scalar equality check when the expected Policy Engine input path is present.
   *
   * @param checks         mutable check list
   * @param tokenClaimName token claim name
   * @param tokenClaims    token claim JSON object
   * @param policyInput    normalized Policy Engine input object
   * @param policyJsonPath JSON pointer to the expected value in the Policy Engine input
   */
  private void addEqualityCheck(List<ClaimCheck> checks, String tokenClaimName, JsonNode tokenClaims,
      JsonNode policyInput, String policyJsonPath) {
    var expectedNode = policyInput.at(policyJsonPath);
    if (isAbsent(expectedNode)) {
      checks.add(new ClaimCheck(Status.SKIPPED, tokenClaimName,
          "policy input path " + policyJsonPath + " is absent"));
      return;
    }

    var expected = textValue(expectedNode);
    if (expected == null) {
      checks.add(new ClaimCheck(Status.TROUBLE, tokenClaimName,
          "policy input path " + policyJsonPath + " is not a scalar value"));
      return;
    }

    var actualNode = tokenClaims.get(tokenClaimName);
    if (isAbsent(actualNode)) {
      checks.add(new ClaimCheck(Status.TROUBLE, tokenClaimName,
          "missing token claim; expected '" + expected + "' from " + policyJsonPath));
      return;
    }

    var actual = textValue(actualNode);
    if (actual == null) {
      checks.add(new ClaimCheck(Status.TROUBLE, tokenClaimName,
          "token claim is not a scalar value; expected '" + expected + "' from " + policyJsonPath));
      return;
    }

    checks.add(expected.equals(actual)
        ? new ClaimCheck(Status.OK, tokenClaimName, "matches '" + expected + "'")
        : new ClaimCheck(Status.TROUBLE, tokenClaimName,
            "expected '" + expected + "' from " + policyJsonPath + " but was '" + actual + "'"));
  }

  /**
   * Parses a JSON object from a resolved Cucumber argument.
   *
   * @param json  JSON text
   * @param label diagnostic label
   * @return parsed JSON object
   */
  private JsonNode parseObject(String json, String label) {
    try {
      var node = JSON.readTree(json);
      if (node == null || !node.isObject()) {
        throw new AssertionError(label + " must be a JSON object but was " + node);
      }
      return node;
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse " + label + " as JSON: " + e.getMessage(), e);
    }
  }

  /**
   * Accepts either the raw Policy Engine input object or a wrapper containing an {@code input} node.
   *
   * @param policyInput parsed policy input or wrapper object
   * @return normalized policy input object
   */
  private JsonNode normalizePolicyInput(JsonNode policyInput) {
    if (policyInput.has("input") && policyInput.get("input").isObject()) {
      return policyInput.get("input");
    }
    return policyInput;
  }

  /**
   * Formats all checks into one report and marks whether trouble was found.
   *
   * @param tokenName token label
   * @param checks    collected claim checks
   * @return formatted report
   */
  private ClaimReport toReport(String tokenName, List<ClaimCheck> checks) {
    var report = new StringBuilder(tokenName)
        .append(" claim mapping against Policy Engine input\n");
    appendSection(report, "OK", checks, Status.OK);
    appendSection(report, "TROUBLE", checks, Status.TROUBLE);
    appendSection(report, "SKIPPED", checks, Status.SKIPPED);
    var troubleFound = checks.stream().anyMatch(check -> check.status() == Status.TROUBLE);
    return new ClaimReport(report.toString().trim(), troubleFound);
  }

  /**
   * Appends one status section to the report when it contains entries.
   *
   * @param report mutable report builder
   * @param title  section title
   * @param checks all collected checks
   * @param status section status
   */
  private void appendSection(StringBuilder report, String title, List<ClaimCheck> checks, Status status) {
    var entries = checks.stream()
        .filter(check -> check.status() == status)
        .toList();
    if (entries.isEmpty()) {
      return;
    }

    report.append("\n\n").append(title).append(":\n");
    entries.forEach(check -> report
        .append("- ")
        .append(check.claim())
        .append(": ")
        .append(check.message())
        .append('\n'));
  }

  /**
   * Attaches the report and records a soft failure if any claim mapping differs.
   *
   * @param title  attachment title
   * @param report claim comparison report
   */
  private void attachAndRecord(String title, ClaimReport report) {
    ReportAttachments.addText(title, report.report());
    log.info("{}\n{}", title, report.report());
    if (report.troubleFound()) {
      SoftAssertionsContext.recordSoftFailure(title + " contains differing requested claims",
          new AssertionError(report.report()));
    }
  }

  /**
   * Converts a JSON node into a string set.
   *
   * @param node scalar or array node
   * @return set of textual values
   */
  private static Set<String> stringSet(JsonNode node) {
    var values = new LinkedHashSet<String>();
    if (node == null || node.isMissingNode() || node.isNull()) {
      return values;
    }
    if (node.isArray()) {
      node.forEach(item -> addTextValue(values, item));
      return values;
    }
    addTextValue(values, node);
    return values;
  }

  /**
   * Adds a scalar node value to a set when it is not blank.
   *
   * @param values target set
   * @param node   source node
   */
  private static void addTextValue(Set<String> values, JsonNode node) {
    var value = textValue(node);
    if (value != null && !value.isBlank()) {
      values.add(value);
    }
  }

  /**
   * Checks whether a JSON node is absent or explicitly null.
   *
   * @param node source node
   * @return {@code true} when the node cannot provide a value
   */
  private static boolean isAbsent(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull();
  }

  /**
   * Checks whether a node or any direct array item is an object or array instead of a scalar value.
   *
   * @param node source node
   * @return {@code true} when the node contains a non-scalar value
   */
  private static boolean containsNonScalarValue(JsonNode node) {
    if (isAbsent(node)) {
      return false;
    }
    if (node.isArray()) {
      for (var item : node) {
        if (!isAbsent(item) && !(item.isString() || item.isNumber() || item.isBoolean())) {
          return true;
        }
      }
      return false;
    }
    return !(node.isString() || node.isNumber() || node.isBoolean());
  }

  /**
   * Returns a compact textual value for scalar JSON nodes.
   *
   * @param node source node
   * @return textual value or {@code null}
   */
  private static String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isNumber() || node.isBoolean()) {
      return node.asString();
    }
    return null;
  }

}
