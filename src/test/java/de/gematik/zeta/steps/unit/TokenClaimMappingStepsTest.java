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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerConfigurationKey;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.steps.TokenClaimMappingSteps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenClaimMappingSteps}.
 */
class TokenClaimMappingStepsTest {

  private static final String POLICY_INPUT = """
      {
        "version": "1.0",
        "authorization_request": {
          "audience": [ "https://zeta-kind.local/pep/" ],
          "scopes": [ "zero:audience" ],
          "grant_type": "urn:ietf:params:oauth:grant-type:token-exchange",
          "ip_address": "172.18.0.4",
          "previous_ip_address": "Unknown"
        },
        "client_registration_data": {
          "platform": "linux",
          "client_id": "38eae48a-ec90-434b-a9b0-17f1c1e8e09f",
          "product_id": "test-proxy",
          "product_version": "0.5.0",
          "registration_timestamp": 1781609046
        },
        "user_info": {
          "identifier": "1-20000300139",
          "professionOID": "1.2.276.0.76.4.50"
        }
      }
      """;

  private static final String MATCHING_ACCESS_TOKEN_CLAIMS = """
      {
        "aud": "https://zeta-kind.local/pep/",
        "sub": "1-20000300139",
        "azp": "38eae48a-ec90-434b-a9b0-17f1c1e8e09f",
        "scope": "zero:audience",
        "product_version": "0.5.0",
        "ip_address": "172.18.0.4",
        "client_id": "38eae48a-ec90-434b-a9b0-17f1c1e8e09f",
        "platform": "linux",
        "product_id": "test-proxy",
        "profession_oid": "1.2.276.0.76.4.50"
      }
      """;

  private static final String MATCHING_REFRESH_TOKEN_CLAIMS = """
      {
        "aud": "https://zeta-kind.local/auth/realms/zeta-guard",
        "sub": "1-20000300139",
        "azp": "38eae48a-ec90-434b-a9b0-17f1c1e8e09f",
        "scope": "zero:audience",
        "product_version": "0.5.0",
        "ip_address": "172.18.0.4",
        "client_id": "38eae48a-ec90-434b-a9b0-17f1c1e8e09f",
        "platform": "linux",
        "product_id": "test-proxy",
        "profession_oid": "1.2.276.0.76.4.50"
      }
      """;
  private static final String ZETA_BASE_URL_KEY = "zeta_base_url";
  private static final String TOKEN_ENDPOINT_PATH_KEY = "paths.guard.tokenEndpointPath";
  private static final String DEFAULT_ZETA_BASE_URL = "zeta-kind.local";
  private static final String DEFAULT_TOKEN_ENDPOINT_PATH =
      "/auth/realms/zeta-guard/protocol/openid-connect/token";

  private final TokenClaimMappingSteps steps = new TokenClaimMappingSteps();

  /**
   * Configures the path variables used to derive the refresh token audience.
   */
  @BeforeEach
  void configurePaths() {
    setDefaultPaths();
  }

  /**
   * Restores the path variables after tests that intentionally modify global Tiger configuration.
   */
  @AfterEach
  void restorePaths() {
    setDefaultPaths();
  }

  /**
   * Verifies that a fully matching access token report has no trouble section.
   */
  @Test
  void accessTokenClaimsPassWhenTheyMatchPolicyInput() {
    var report = steps.evaluateAccessTokenClaims(MATCHING_ACCESS_TOKEN_CLAIMS, POLICY_INPUT);

    assertThat(report.troubleFound()).isFalse();
    assertThat(report.report()).contains("sub: matches");
    assertThat(report.report()).doesNotContain("TROUBLE:");
  }

  /**
   * Verifies that additional scopes and audience drift are listed as trouble.
   */
  @Test
  void accessTokenClaimsListAdditionalScopesAndAudienceDrift() {
    var tokenClaims = MATCHING_ACCESS_TOKEN_CLAIMS
        .replace("\"scope\": \"zero:audience\"", "\"scope\": \"email profile zero:audience\"")
        .replace("\"aud\": \"https://zeta-kind.local/pep/\"", "\"aud\": \"https://zeta-kind.local\"");

    var report = steps.evaluateAccessTokenClaims(tokenClaims, POLICY_INPUT);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("scope.extra");
    assertThat(report.report()).contains("additional scopes [email, profile]");
    assertThat(report.report()).contains("aud.requested");
    assertThat(report.report()).contains("missing requested audience values [https://zeta-kind.local/pep/]");
    assertThat(report.report()).contains("aud.extra");
    assertThat(report.report()).contains("additional requested audience values [https://zeta-kind.local]");
  }

  /**
   * Verifies that an audience array containing the requested and unrelated values is reported.
   */
  @Test
  void accessTokenClaimsListAdditionalAudienceValues() {
    var tokenClaims = MATCHING_ACCESS_TOKEN_CLAIMS
        .replace("\"aud\": \"https://zeta-kind.local/pep/\"",
            "\"aud\": [ \"https://zeta-kind.local/pep/\", \"https://zeta-kind.local/other/\" ]");

    var report = steps.evaluateAccessTokenClaims(tokenClaims, POLICY_INPUT);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("aud.requested");
    assertThat(report.report()).contains("aud.extra");
    assertThat(report.report()).contains("additional requested audience values [https://zeta-kind.local/other/]");
  }

  /**
   * Verifies that a missing mapped token claim is reported.
   */
  @Test
  void accessTokenClaimsListMissingClaim() {
    var tokenClaims = MATCHING_ACCESS_TOKEN_CLAIMS
        .replaceAll("(?m)^\\s*\"platform\".*(?:\\R|$)", "");

    var report = steps.evaluateAccessTokenClaims(tokenClaims, POLICY_INPUT);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("platform");
    assertThat(report.report()).contains("missing token claim");
  }

  /**
   * Verifies that a present but non-scalar token claim is reported as malformed.
   */
  @Test
  void accessTokenClaimsListNonScalarTokenClaim() {
    var tokenClaims = MATCHING_ACCESS_TOKEN_CLAIMS
        .replace("\"platform\": \"linux\"", "\"platform\": { \"value\": \"linux\" }");

    var report = steps.evaluateAccessTokenClaims(tokenClaims, POLICY_INPUT);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("platform");
    assertThat(report.report()).contains("token claim is not a scalar value");
    assertThat(report.report()).doesNotContain("SKIPPED:");
  }

  /**
   * Verifies that a present but non-scalar Policy Engine input value is reported as malformed.
   */
  @Test
  void accessTokenClaimsListNonScalarPolicyInputValue() {
    var policyInput = POLICY_INPUT
        .replace("\"platform\": \"linux\"", "\"platform\": { \"value\": \"linux\" }");

    var report = steps.evaluateAccessTokenClaims(MATCHING_ACCESS_TOKEN_CLAIMS, policyInput);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("platform");
    assertThat(report.report()).contains("policy input path /client_registration_data/platform "
        + "is not a scalar value");
    assertThat(report.report()).doesNotContain("SKIPPED:");
  }

  /**
   * Verifies that a malformed requested scope value is reported instead of skipped.
   */
  @Test
  void accessTokenClaimsListNonScalarPolicyInputScopes() {
    var policyInput = POLICY_INPUT
        .replace("\"scopes\": [ \"zero:audience\" ]", "\"scopes\": { \"value\": \"zero:audience\" }");

    var report = steps.evaluateAccessTokenClaims(MATCHING_ACCESS_TOKEN_CLAIMS, policyInput);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("scope");
    assertThat(report.report()).contains("policy input path /authorization_request/scopes "
        + "contains non-scalar scope values");
    assertThat(report.report()).doesNotContain("scope: no requested scopes in policy input");
  }

  /**
   * Verifies that mixed scalar and non-scalar requested scope values are reported as malformed.
   */
  @Test
  void accessTokenClaimsListMixedNonScalarPolicyInputScopes() {
    var policyInput = POLICY_INPUT
        .replace("\"scopes\": [ \"zero:audience\" ]",
            "\"scopes\": [ \"zero:audience\", { \"value\": \"invalid\" } ]");

    var report = steps.evaluateAccessTokenClaims(MATCHING_ACCESS_TOKEN_CLAIMS, policyInput);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("scope");
    assertThat(report.report()).contains("policy input path /authorization_request/scopes "
        + "contains non-scalar scope values");
    assertThat(report.report()).doesNotContain("scope.requested: all requested scopes are present");
    assertThat(report.report()).doesNotContain("scope.extra: no additional scopes found");
  }

  /**
   * Verifies that a malformed requested audience value is reported instead of skipped.
   */
  @Test
  void accessTokenClaimsListNonScalarPolicyInputAudience() {
    var policyInput = POLICY_INPUT
        .replace("\"audience\": [ \"https://zeta-kind.local/pep/\" ]",
            "\"audience\": { \"value\": \"https://zeta-kind.local/pep/\" }");

    var report = steps.evaluateAccessTokenClaims(MATCHING_ACCESS_TOKEN_CLAIMS, policyInput);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("aud");
    assertThat(report.report()).contains("policy input path /authorization_request/audience "
        + "contains non-scalar audience values");
    assertThat(report.report()).doesNotContain("aud: no requested audience available");
  }

  /**
   * Verifies that mixed scalar and non-scalar requested audience values are reported as malformed.
   */
  @Test
  void accessTokenClaimsListMixedNonScalarPolicyInputAudience() {
    var policyInput = POLICY_INPUT
        .replace("\"audience\": [ \"https://zeta-kind.local/pep/\" ]",
            "\"audience\": [ \"https://zeta-kind.local/pep/\", { \"value\": \"invalid\" } ]");

    var report = steps.evaluateAccessTokenClaims(MATCHING_ACCESS_TOKEN_CLAIMS, policyInput);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("aud");
    assertThat(report.report()).contains("policy input path /authorization_request/audience "
        + "contains non-scalar audience values");
    assertThat(report.report()).doesNotContain("aud.requested: all requested audience values are present");
    assertThat(report.report()).doesNotContain("aud.extra: no additional requested audience values found");
  }

  /**
   * Verifies that a malformed token audience value is reported instead of flattened.
   */
  @Test
  void accessTokenClaimsListNonScalarTokenAudience() {
    var tokenClaims = MATCHING_ACCESS_TOKEN_CLAIMS
        .replace("\"aud\": \"https://zeta-kind.local/pep/\"",
            "\"aud\": { \"value\": \"https://zeta-kind.local/pep/\" }");

    var report = steps.evaluateAccessTokenClaims(tokenClaims, POLICY_INPUT);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("aud");
    assertThat(report.report()).contains("token claim contains no scalar requested audience values");
    assertThat(report.report()).doesNotContain("{\"value\":\"https://zeta-kind.local/pep/\"}");
  }

  /**
   * Verifies that refresh token audience is checked against the Authorization Server issuer.
   */
  @Test
  void refreshTokenClaimsUseAuthorizationServerAudience() {
    var report = steps.evaluateRefreshTokenClaims(MATCHING_REFRESH_TOKEN_CLAIMS, POLICY_INPUT);

    assertThat(report.troubleFound()).isFalse();
    assertThat(report.report()).contains("aud.requested: all authorization server audience values are present");
    assertThat(report.report()).doesNotContain("TROUBLE:");
  }

  /**
   * Verifies that refresh token audience drift is listed as trouble.
   */
  @Test
  void refreshTokenClaimsListAuthorizationServerAudienceDrift() {
    var tokenClaims = MATCHING_REFRESH_TOKEN_CLAIMS
        .replace("\"aud\": \"https://zeta-kind.local/auth/realms/zeta-guard\"",
            "\"aud\": \"https://zeta-kind.local\"");

    var report = steps.evaluateRefreshTokenClaims(tokenClaims, POLICY_INPUT);

    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("aud.requested");
    assertThat(report.report()).contains("missing authorization server audience values "
        + "[https://zeta-kind.local/auth/realms/zeta-guard]");
    assertThat(report.report()).contains("aud.extra");
    assertThat(report.report()).contains("additional authorization server audience values [https://zeta-kind.local]");
  }

  /**
   * Verifies that missing zeta base URL configuration is reported as trouble.
   */
  @Test
  void refreshTokenClaimsFailWhenZetaBaseUrlIsMissing() {
    deleteConfigValue(ZETA_BASE_URL_KEY);

    var report = steps.evaluateRefreshTokenClaims(MATCHING_REFRESH_TOKEN_CLAIMS, POLICY_INPUT);

    assertMissingRefreshAudienceConfiguration(report);
  }

  /**
   * Verifies that blank zeta base URL configuration is reported as trouble.
   */
  @Test
  void refreshTokenClaimsFailWhenZetaBaseUrlIsBlank() {
    setConfigValue(ZETA_BASE_URL_KEY, " ");

    var report = steps.evaluateRefreshTokenClaims(MATCHING_REFRESH_TOKEN_CLAIMS, POLICY_INPUT);

    assertMissingRefreshAudienceConfiguration(report);
  }

  /**
   * Verifies that missing token endpoint path configuration is reported as trouble.
   */
  @Test
  void refreshTokenClaimsFailWhenTokenEndpointPathIsMissing() {
    deleteConfigValue(TOKEN_ENDPOINT_PATH_KEY);

    var report = steps.evaluateRefreshTokenClaims(MATCHING_REFRESH_TOKEN_CLAIMS, POLICY_INPUT);

    assertMissingRefreshAudienceConfiguration(report);
  }

  /**
   * Verifies that blank token endpoint path configuration is reported as trouble.
   */
  @Test
  void refreshTokenClaimsFailWhenTokenEndpointPathIsBlank() {
    setConfigValue(TOKEN_ENDPOINT_PATH_KEY, " ");

    var report = steps.evaluateRefreshTokenClaims(MATCHING_REFRESH_TOKEN_CLAIMS, POLICY_INPUT);

    assertMissingRefreshAudienceConfiguration(report);
  }

  /**
   * Verifies that an unsupported token endpoint path shape is reported as trouble.
   */
  @Test
  void refreshTokenClaimsFailWhenTokenEndpointPathHasUnexpectedSuffix() {
    setConfigValue(TOKEN_ENDPOINT_PATH_KEY, "/auth/realms/zeta-guard/token");

    var report = steps.evaluateRefreshTokenClaims(MATCHING_REFRESH_TOKEN_CLAIMS, POLICY_INPUT);

    assertMissingRefreshAudienceConfiguration(report);
  }

  /**
   * Stores a Tiger configuration value in the test context.
   *
   * @param key   configuration key
   * @param value configuration value
   */
  private static void setConfigValue(String key, String value) {
    TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Stores the default path variables used by the token claim mapping tests.
   */
  private static void setDefaultPaths() {
    setConfigValue(ZETA_BASE_URL_KEY, DEFAULT_ZETA_BASE_URL);
    setConfigValue(TOKEN_ENDPOINT_PATH_KEY, DEFAULT_TOKEN_ENDPOINT_PATH);
  }

  /**
   * Removes a Tiger configuration value from all sources visible to the test.
   *
   * @param key configuration key
   */
  private static void deleteConfigValue(String key) {
    TigerGlobalConfiguration.deleteFromAllSources(new TigerConfigurationKey(key.split("\\.")));
  }

  /**
   * Asserts that refresh token audience derivation failed as a real claim mapping problem.
   *
   * @param report claim comparison report
   */
  private static void assertMissingRefreshAudienceConfiguration(TokenClaimMappingSteps.ClaimReport report) {
    assertThat(report.troubleFound()).isTrue();
    assertThat(report.report()).contains("TROUBLE:");
    assertThat(report.report()).contains("aud: authorization server audience could not be derived "
        + "from zeta_base_url and paths.guard.tokenEndpointPath");
    assertThat(report.report()).doesNotContain("SKIPPED:");
  }
}
