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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.steps.SoftAssertionsContext;
import de.gematik.zeta.steps.TokenClaimMappingSteps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Counterexamples for scope grants and the two audience contracts. */
class TokenClaimMappingStepsTest {
  private final TokenClaimMappingSteps steps = new TokenClaimMappingSteps();
  private static final String INPUT =
      """
      {"version":"1.1","user_info":{"identifier":"id","professionOID":"oid"},
       "authorization_request":{"resource":"https://service.example/path","scopes":["S1","S2"],
       "acr":"gematik-ehealth-loa-substantial"}}
      """;
  private static final String TOKEN =
      """
      {"ver":2,"sub":"id","profession_oid":"oid","scope":"S1","aud":"service",
       "acr":"gematik-ehealth-loa-substantial"}
      """;
  private static final String DECISION =
      """
      {"result":{"allow":true,"aud":"service"}}
      """;

  @ParameterizedTest
  @ValueSource(strings = {"\"logical\"", "[\"logical\"]", "[\"one\",\"two\"]"})
  void resourceIssuanceUsesTheLogicalAudienceFixture(String audience) {
    var decision = "{\"result\":{\"allow\":true,\"aud\":" + audience + "}}";
    assertThat(
            steps
                .evaluateResourceIssuanceClaims(
                    "{\"ver\":2,\"aud\":" + audience + "}",
                    decision,
                    "https://resource.example/path")
                .troubleFound())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "1",
        "0",
        "-2",
        "\"2\"",
        "2.0",
        "true",
        "4294967298",
        "18446744073709551618"
      })
  void resourceIssuanceRejectsEveryNonExactVersionWithoutLosingAudienceErrors(String version) {
    assertThat(
            steps
                .evaluateResourceIssuanceClaims(
                    "{\"ver\":" + version + ",\"aud\":\"wrong\"}",
                    "{\"result\":{\"allow\":true,\"aud\":\"logical\"}}",
                    "https://resource.example/path")
                .report())
        .contains("ver:", "aud:");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "\"\"",
        "true",
        "2",
        "{}",
        "[]",
        "[\"one\"]",
        "[\"one\",\"two\",\"extra\"]",
        "[\"one\",2]",
        "\"https://resource.example/path\""
      })
  void resourceIssuanceCannotLoseAddOrReplaceLogicalAudiences(String audience) {
    assertThat(
            steps
                .evaluateResourceIssuanceClaims(
                    "{\"ver\":2,\"aud\":" + audience + "}",
                    "{\"result\":{\"allow\":true,\"aud\":[\"one\",\"two\"]}}",
                    "https://resource.example/path")
                .report())
        .contains("aud");
  }

  @Test
  void resourceIssuanceCannotOmitAudienceAndVersion() {
    assertThat(
            steps
                .evaluateResourceIssuanceClaims(
                    "{}",
                    "{\"result\":{\"allow\":true,\"aud\":\"logical\"}}",
                    "https://resource.example/path")
                .report())
        .contains("aud", "ver:");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"result\":{\"allow\":false,\"aud\":\"logical\"}}",
        "{\"result\":{\"allow\":\"true\",\"aud\":\"logical\"}}",
        "{\"result\":{\"allow\":true,\"aud\":[]}}",
        "{\"result\":{\"allow\":true,\"aud\":2}}",
        "{\"result\":{\"allow\":true,\"aud\":\"https://resource.example/path\"}}"
      })
  void resourceIssuanceNeedsAnIndependentPositiveLogicalAudience(String decision) {
    assertThrows(
        AssertionError.class,
        () ->
            steps.evaluateResourceIssuanceClaims("{}", decision, "https://resource.example/path"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {" ", "${missing}", "/relative", "not a URI", "https://fd.example/#fragment"})
  void resourceIssuanceCannotInferResourceFromTheIssuedToken(String resource) {
    assertThrows(
        AssertionError.class,
        () ->
            steps.evaluateResourceIssuanceClaims(
                "{\"ver\":2}", "{\"result\":{\"allow\":true,\"aud\":\"logical\"}}", resource));
  }

  @ParameterizedTest
  @ValueSource(strings = {"4294967298", "18446744073709551618"})
  void oversizedResourceVersionIsReportedWithoutAbortingClaimComparison(String version) {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"ver\":2", "\"ver\":" + version),
                    INPUT,
                    DECISION,
                    "another-scope")
                .report())
        .contains("ver:", "scope:");
  }

  @ParameterizedTest
  @ValueSource(strings = {"4294967297", "18446744073709551617"})
  void oversizedLegacyVersionIsReportedWithoutAbortingClaimComparison(String version) {
    var input =
        INPUT
            .replace("\"version\":\"1.1\"", "\"version\":\"1.0\"")
            .replace("\"resource\":\"https://service.example/path\"", "\"audience\":\"service\"");
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"ver\":2", "\"ver\":" + version),
                    input,
                    DECISION,
                    "another-scope")
                .report())
        .contains("ver:", "scope:");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"\"https://service.example/A%2fb\"", "[\"https://service.example/A%2fb\"]"})
  void issuedLegacyAudienceRetainsExactValueInEitherJwtRepresentation(String aud) {
    assertThat(
            steps
                .evaluateLegacyIssuanceClaims(
                    "{\"aud\":" + aud + ",\"ver\":1}", "https://service.example/A%2fb")
                .troubleFound())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"null", "\"1\"", "1.0", "true", "2", "4294967297", "18446744073709551617"})
  void issuedLegacyVersionMustBeExactlyIntegerOne(String version) {
    assertThat(
            steps
                .evaluateLegacyIssuanceClaims(
                    "{\"aud\":\"service\",\"ver\":" + version + "}", "service")
                .report())
        .contains("ver:")
        .doesNotContain("aud:");
  }

  @Test
  void legacyCompatibilityDoesNotExcuseMissingVersionOnNewIssuance() {
    assertThat(steps.evaluateLegacyIssuanceClaims("{\"aud\":\"service\"}", "service").report())
        .contains("ver:");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "[\"service\",\"other\"]",
        "[\"service\",\"service\"]",
        "[1]",
        "1",
        "{}",
        "\"SERVICE\"",
        "\" service\"",
        "\"service/\""
      })
  void legacyAudienceCannotBeNormalizedExpandedOrMalformed(String audience) {
    assertThat(
            steps
                .evaluateLegacyIssuanceClaims("{\"ver\":1,\"aud\":" + audience + "}", "service")
                .report())
        .contains("aud:")
        .doesNotContain("ver:");
  }

  @Test
  void allLegacyIssuanceFailuresAreReportedTogether() {
    assertThat(steps.evaluateLegacyIssuanceClaims("{}", "secret-sentinel").report())
        .contains("aud:", "ver:")
        .doesNotContain("secret-sentinel");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "${unresolved}"})
  void missingIndependentRequestReferenceCannotProduceSuccess(String audience) {
    assertThrows(AssertionError.class, () -> steps.evaluateLegacyIssuanceClaims("{}", audience));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", "{", "\"token\""})
  void malformedIssuedTokenPayloadFailsClosed(String claims) {
    assertThrows(AssertionError.class, () -> steps.evaluateLegacyIssuanceClaims(claims, "service"));
  }

  /** Preserves decoded audience values regardless of their scalar or singleton-array shape. */
  @ParameterizedTest
  @ValueSource(strings = {"service", "https://service.example/path", "urn:service:ä", "service\"\\\n", " service "})
  void singleAudiencePreservesValuesAcrossRepresentations(String audience) {
    var scalar = new ObjectMapper().writeValueAsString(audience);
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      steps.storeSingleAudience("{\"aud\":" + scalar + "}", "single_audience");
      steps.storeSingleAudience("{\"aud\":[" + scalar + "]}", "single_audience");
      config.verify(() -> TigerGlobalConfiguration.putValue(
          "single_audience", audience, ConfigurationValuePrecedence.TEST_CONTEXT), times(2));
      config.verifyNoMoreInteractions();
    }
  }

  /** Rejects ambiguous or malformed audiences before writing scenario state. */
  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "[\"service\",\"other\"]", "[\"service\",\"service\"]",
      "1", "true", "{}", "[1]", "[null]", "[{}]", "[[\"service\"]]", "\"\"", "\" \"", "[\" \" ]"})
  void singleAudienceRejectsInvalidOrMultipleValues(String audience) {
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      assertThrows(AssertionError.class, () -> steps.storeSingleAudience(
          "{\"aud\":" + audience + "}", "single_audience"));
      config.verifyNoInteractions();
    }
  }

  /** Requires a JSON object containing an audience instead of retaining an earlier reference. */
  @ParameterizedTest
  @ValueSource(strings = {"{}", "[]", "null", "{", "\"token\""})
  void singleAudienceRejectsMissingAudienceAndInvalidContainers(String json) {
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      assertThrows(AssertionError.class, () -> steps.storeSingleAudience(json, "single_audience"));
      config.verifyNoInteractions();
    }
  }

  @AfterEach
  void resetSoftAssertions() {
    SoftAssertionsContext.reset();
  }

  @ParameterizedTest
  @CsvSource({
      "zero%3Aread, zero:read",
      "zero%3Aread+zero%3Awrite, zero:read zero:write",
      "zero%3Aread%20zero%3Awrite, zero:read zero:write",
      "zero%3Aread%2Bextra, zero:read+extra",
      "zero%253Aread, zero%3Aread"
  })
  void tokenRequestScopesAreDecodedOnceBeforeComparison(String encodedScopes, String tokenScopes) {
    steps.checkAccessTokenClaimsWithRequestScopes(
        TOKEN.replace("\"scope\":\"S1\"", "\"scope\":\"" + tokenScopes + "\""),
        INPUT, DECISION, encodedScopes);

    assertThat(SoftAssertionsContext.softly().errorsCollected()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"zero:read", "zero:write", "zero:read zero:write:other"})
  void tokenScopesMustContainEveryRequestedScopeEvenWhenPolicyInputHasOtherScopes(String tokenScopes) {
    steps.checkAccessTokenClaimsWithRequestScopes(
        TOKEN.replace("\"scope\":\"S1\"", "\"scope\":\"" + tokenScopes + "\""),
        INPUT, DECISION, "zero%3Aread+zero%3Awrite");

    assertThat(SoftAssertionsContext.softly().errorsCollected())
        .singleElement().satisfies(error -> assertThat(error.getMessage()).contains("scope:"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "${missing}"})
  void missingTokenRequestScopeReferenceFails(String scopes) {
    assertThrows(AssertionError.class,
        () -> steps.checkAccessTokenClaimsWithRequestScopes(TOKEN, INPUT, DECISION, scopes));
  }

  @ParameterizedTest
  @ValueSource(strings = {"%", "%XY"})
  void malformedTokenRequestScopeEncodingFails(String scopes) {
    assertThrows(IllegalArgumentException.class,
        () -> steps.checkAccessTokenClaimsWithRequestScopes(TOKEN, INPUT, DECISION, scopes));
  }

  @Test
  void reducedGrantPassesWithoutRequiringAllRequestedScopes() {
    assertThat(steps.evaluateAccessTokenClaims(TOKEN, INPUT, DECISION, "S1").troubleFound())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"S1 S2", "email profile S1", "  profile  S1 email  "})
  void additionalTokenScopesAreAllowed(String tokenScopes) {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"S1\"", "\"" + tokenScopes + "\""), INPUT, DECISION, "S1")
                .troubleFound())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "zero:read zero:write zero:admin",
      "zero:write profile zero:read email",
      "  zero:write  zero:read profile email  "
  })
  void tokenRequestScopesMayBeIncludedAlongsideAdditionalTokenScopes(String tokenScopes) {
    steps.checkAccessTokenClaimsWithRequestScopes(
        TOKEN.replace("\"scope\":\"S1\"", "\"scope\":\"" + tokenScopes + "\""),
        INPUT, DECISION, "zero%3Aread+zero%3Awrite");

    assertThat(SoftAssertionsContext.softly().errorsCollected()).isEmpty();
  }

  @Test
  void missingGrantedScopeFails() {
    assertThat(steps.evaluateAccessTokenClaims(TOKEN, INPUT, DECISION, "S1 S2").troubleFound())
        .isTrue();
  }

  @Test
  void whitespaceAndOrderDoNotChangeScopeSets() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"S1\"", "\"S2 S1\""), INPUT, DECISION, " S1  S2 ")
                .troubleFound())
        .isFalse();
  }

  @Test
  void resourceUrlIsNotUsedAsAudience() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"service\"", "\"https://service.example/path\""),
                    INPUT,
                    DECISION,
                    "S1")
                .troubleFound())
        .isTrue();
  }

  @Test
  void missingDecisionAudienceFailsClosedForResourceContract() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(TOKEN, INPUT, "{\"result\":{\"allow\":true}}", "S1")
                .report())
        .contains("expected aud: missing");
  }

  @Test
  void malformedAudienceAndScopesFail() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"scope\":\"S1\"", "\"scope\":[\"S1\"]"), INPUT, DECISION, "S1")
                .troubleFound())
        .isTrue();
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN, INPUT, "{\"result\":{\"allow\":true,\"aud\":[\"service\",{}]}}", "S1")
                .troubleFound())
        .isTrue();
  }

  @Test
  void wrongContractVersionFails() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"ver\":2", "\"ver\":1"), INPUT, DECISION, "S1")
                .report())
        .contains("ver:");
  }

  @Test
  void negativeDecisionCannotJustifyTokenIssuance() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(TOKEN, INPUT, DECISION.replace("true", "false"), "S1")
                .report())
        .contains("allow:");
  }

  @Test
  void camelCaseTokenClaimDoesNotSatisfyCcMapping() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("profession_oid", "professionOID"), INPUT, DECISION, "S1")
                .report())
        .contains("profession_oid:");
  }

  @Test
  void missingMappedClaimFails() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"sub\":\"id\",", ""), INPUT, DECISION, "S1")
                .report())
        .contains("sub:");
  }

  @Test
  void legacyContractStillUsesRequestedAudience() {
    String input =
        INPUT
            .replace("\"resource\":\"https://service.example/path\"", "\"audience\":[\"service\"]")
            .replace("\"1.1\"", "\"1.0\"");
    String token = TOKEN.replace("\"ver\":2,", "");
    assertThat(
            steps
                .evaluateAccessTokenClaims(token, input, "{\"result\":{\"allow\":true}}", "S1")
                .troubleFound())
        .isFalse();
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN.replace("\"ver\":2", "\"ver\":1"),
                    input,
                    "{\"result\":{\"allow\":true}}",
                    "S1")
                .troubleFound())
        .isFalse();
    assertThat(steps.evaluateAccessTokenClaims(TOKEN, input, DECISION, "S1").report())
        .contains("ver:");
  }

  @Test
  void mixedPolicyContractsAreRejected() {
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN, INPUT.replace("\"1.1\"", "\"1.0\""), DECISION, "S1")
                .report())
        .contains("version:");
    assertThat(
            steps
                .evaluateAccessTokenClaims(
                    TOKEN,
                    INPUT.replace("\"scopes\":", "\"audience\":[\"service\"],\"scopes\":"),
                    DECISION,
                    "S1")
                .report())
        .contains("audience:");
  }

  @Test
  void unresolvedGrantFixtureFails() {
    assertThrows(
        AssertionError.class,
        () -> steps.evaluateAccessTokenClaims(TOKEN, INPUT, DECISION, "${missing}"));
  }

  @Test
  void optionalUserClaimsMayBeAbsentButMustMatchWhenIncluded() {
    var input =
        INPUT.replace("\"identifier\":\"id\"", "\"identifier\":\"id\",\"commonName\":\"name\"");
    assertThat(steps.evaluateAccessTokenClaims(TOKEN, input, DECISION, "S1").troubleFound())
        .isFalse();
    var token = TOKEN.replace("\"sub\":\"id\"", "\"sub\":\"id\",\"common_name\":\"wrong\"");
    assertThat(steps.evaluateAccessTokenClaims(token, input, DECISION, "S1").report())
        .contains("common_name:");
  }

  /** Policy camelCase fields map to the distinct snake_case JWT contract without aliases. */
  @Test
  void userInfoUsesUpstreamFieldNamesWhileTokenClaimsKeepSnakeCase() {
    var input = INPUT.replace("\"identifier\":\"id\"",
        "\"identifier\":\"id\",\"commonName\":\"name\",\"organizationName\":\"org\","
            + "\"birthdate\":\"2000-12-31\"");
    var token = TOKEN.replace("\"sub\":\"id\"",
        "\"sub\":\"id\",\"common_name\":\"name\",\"organization_name\":\"org\","
            + "\"birthdate\":\"2000-12-31\"");
    assertThat(steps.evaluateAccessTokenClaims(token, input, DECISION, "S1").troubleFound())
        .isFalse();
    for (var pair : new String[][] {{"professionOID", "profession_oid"},
        {"commonName", "common_name"}, {"organizationName", "organization_name"}}) {
      assertThat(steps.evaluateAccessTokenClaims(
          token, input.replace(pair[0], pair[1]), DECISION, "S1").troubleFound()).isTrue();
    }
  }

  @Test
  void malformedLegacyAudienceCannotBeMixedWithResource() {
    var input = INPUT.replace("\"scopes\":", "\"audience\":\"service\",\"scopes\":");
    assertThat(steps.evaluateAccessTokenClaims(TOKEN, input, DECISION, "S1").report())
        .contains("audience:");
  }

  @Test
  void invalidObjectsFailInsteadOfSilentlySkipping() {
    assertThrows(
        AssertionError.class, () -> steps.evaluateAccessTokenClaims("[]", INPUT, DECISION, "S1"));
    assertThrows(
        AssertionError.class, () -> steps.evaluateAccessTokenClaims(TOKEN, "{", DECISION, "S1"));
  }
}
