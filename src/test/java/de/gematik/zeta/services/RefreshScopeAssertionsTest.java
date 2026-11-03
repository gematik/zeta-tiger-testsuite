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

package de.gematik.zeta.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Counterexamples for changed-scope response, continuity and independently expected grants. */
class RefreshScopeAssertionsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BEFORE = "zero:audience profile email";
  private static final String AFTER = "zero:audience notification.pusher.read profile email";

  @Test
  void exactNewGrantRetainsContextAndAllowsDifferentScopeOrdering() throws Exception {
    var original = token(BEFORE, Map.of());
    var body = response(token("email notification.pusher.read profile zero:audience", Map.of()));
    assertDoesNotThrow(() -> verify(original, body));
    body.remove("scope");
    assertDoesNotThrow(() -> verify(original, body));
  }

  @Test
  void scopeSetsIgnoreOrderingAndRepeatedNames() {
    assertDoesNotThrow(() -> RefreshScopeAssertions.scopes("scope scope"));
    assertDoesNotThrow(() -> RefreshScopeAssertions.verifyRequest(
        "grant_type=refresh_token&resource=https%3A%2F%2Fguard.example%2F&scope=read+write+read",
        "https://guard.example/", "write read"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"grant_type=other&resource=https%3A%2F%2Fguard.example%2F&scope=read",
      "grant_type=refresh_token&resource=https%3A%2F%2Fother.example%2F&scope=read",
      "grant_type=refresh_token&resource=https%3A%2F%2Fguard.example%2F&scope=wrong",
      "grant_type=refresh_token&audience=https%3A%2F%2Fguard.example%2F&scope=read",
      "grant_type=refresh_token&resource=https%3A%2F%2Fguard.example%2F&audience=legacy&scope=read",
      "grant_type=refresh_token&resource=https%3A%2F%2Fguard.example%2F&%61udience=legacy&scope=read",
      "grant_type=refresh_token&resource=https%3A%2F%2Fguard.example%2F&scope=read&%73cope=read",
      "grant_type=refresh_token&resource=%ZZ&scope=read"})
  void wrongOrAmbiguousRecordedRequestFails(String body) {
    assertThrows(AssertionError.class, () -> RefreshScopeAssertions.verifyRequest(body, "https://guard.example/", "read"));
  }

  @Test
  void resourcePolicyTupleUsesExactlyTheChangedScopeSet() {
    assertDoesNotThrow(() -> RefreshScopeAssertions.verifyPolicy(policy().toString(), "test-client", "https://guard.example/", "write read"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"client", "grant_type", "resource", "missing_resource", "resource_type",
      "audience", "legacy_only", "null_audience", "version", "missing_version", "version_type",
      "scopes", "extra_scope", "scope_type"})
  void foreignOrIncompletePolicyTupleFails(String field) {
    var policy = policy();
    var request = (ObjectNode) policy.path("authorization_request");
    switch (field) {
      case "client" -> ((ObjectNode) policy.path("client_registration_data")).put("client_id", "different");
      case "grant_type" -> request.put("grant_type", "different");
      case "resource" -> request.put("resource", "https://other.example/");
      case "missing_resource" -> request.remove("resource");
      case "resource_type" -> request.putArray("resource").add("https://guard.example/");
      case "audience" -> request.putArray("audience").add("https://guard.example/");
      case "legacy_only" -> {
        request.remove("resource");
        request.putArray("audience").add("https://guard.example/");
        policy.put("version", "1.0");
      }
      case "null_audience" -> request.putNull("audience");
      case "version" -> policy.put("version", "1.0");
      case "missing_version" -> policy.remove("version");
      case "version_type" -> policy.put("version", 1.1);
      case "scopes" -> request.putArray("scopes").add("read");
      case "extra_scope" -> request.putArray("scopes").add("read").add("write").add("unexpected");
      case "scope_type" -> request.put("scopes", "read write");
      default -> throw new AssertionError("Unknown test fixture");
    }
    assertThrows(AssertionError.class,
        () -> RefreshScopeAssertions.verifyPolicy(policy.toString(), "test-client", "https://guard.example/", "read write"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"zero:audience profile email", "zero:audience notification.pusher.read",
      "zero:audience notification.pusher.read profile email unexpected"})
  void oldMissingOrExtraScopesCannotSatisfyTheChangedGrant(String scope) throws Exception {
    var original = token(BEFORE, Map.of());
    var body = response(token(scope, Map.of()));
    assertThrows(AssertionError.class, () -> verify(original, body));
  }

  @ParameterizedTest
  @ValueSource(strings = {"iss", "sub", "client_id", "sid", "aud"})
  void differentContextCannotSatisfyTheRefresh(String field) throws Exception {
    var original = token(BEFORE, Map.of());
    var body = response(token(AFTER, Map.of(field, "different")));
    assertThrows(AssertionError.class, () -> verify(original, body));
  }

  @Test
  void wrongConfirmationKeyOrExpiredTokenFails() throws Exception {
    var original = token(BEFORE, Map.of());
    var wrongKey = response(token(AFTER, Map.of("cnf", Map.of("jkt", "different"))));
    assertThrows(AssertionError.class, () -> verify(original, wrongKey));
    var expired = response(token(AFTER, Map.of("exp", Date.from(Instant.now().minusSeconds(1)))));
    assertThrows(AssertionError.class, () -> verify(original, expired));
  }

  @ParameterizedTest
  @ValueSource(strings = {"access_token", "refresh_token", "token_type", "expires_in"})
  void incompleteTokenSetFails(String field) throws Exception {
    var original = token(BEFORE, Map.of());
    var body = response(token(AFTER, Map.of()));
    body.remove(field);
    assertThrows(AssertionError.class, () -> verify(original, body));
  }

  @Test
  void unchangedRefreshTokenAndContradictoryResponseScopeFail() throws Exception {
    var original = token(BEFORE, Map.of());
    var body = response(token(AFTER, Map.of()));
    body.put("refresh_token", "previous-refresh");
    assertThrows(AssertionError.class, () -> verify(original, body));
    body.put("refresh_token", "new-refresh").put("scope", BEFORE);
    assertThrows(AssertionError.class, () -> verify(original, body));
  }

  @Test
  void expectedScopeCannotBeTheOriginalGrant() throws Exception {
    var original = token(BEFORE, Map.of());
    var body = response(token(BEFORE, Map.of()));
    assertThrows(AssertionError.class, () -> RefreshScopeAssertions.verifyChangedGrant(original, "previous-refresh", body.toString(), BEFORE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "${UNRESOLVED}", "scope\nother", "scope\tother", "scope\\other", "scope\"other"})
  void invalidScopeFixturesFail(String invalid) {
    assertThrows(AssertionError.class, () -> RefreshScopeAssertions.scopes(invalid));
  }

  @Test
  void malformedResponseDoesNotExposeParserContext() throws Exception {
    var original = token(BEFORE, Map.of());
    var error = assertThrows(AssertionError.class,
        () -> RefreshScopeAssertions.verifyChangedGrant(original, "previous-refresh", "{sensitive sentinel", AFTER));
    assertFalse(error.toString().contains("sensitive"));
  }

  private static void verify(String original, ObjectNode body) throws Exception {
    RefreshScopeAssertions.verifyChangedGrant(original, "previous-refresh", body.toString(), AFTER);
  }

  private static ObjectNode response(String access) {
    return JSON.createObjectNode().put("access_token", access).put("refresh_token", "new-refresh")
        .put("token_type", "DPoP").put("expires_in", 300).put("scope", AFTER);
  }

  private static ObjectNode policy() {
    var policy = JSON.createObjectNode().put("version", "1.1");
    policy.putObject("client_registration_data").put("client_id", "test-client");
    var request = policy.putObject("authorization_request").put("grant_type", "refresh_token");
    request.put("resource", "https://guard.example/");
    request.putArray("scopes").add("read").add("write");
    return policy;
  }

  private static String token(String scopes, Map<String, Object> overrides) throws Exception {
    var builder = new JWTClaimsSet.Builder().issuer("https://guard.example/auth").subject("X123456789")
        .audience("https://guard.example/resource").claim("client_id", "test-client").claim("sid", "test-session")
        .claim("cnf", Map.of("jkt", "bound-key")).claim("scope", scopes).expirationTime(Date.from(Instant.now().plusSeconds(360)));
    overrides.forEach(builder::claim);
    var token = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), builder.build());
    token.sign(new ECDSASigner(new ECKeyGenerator(Curve.P_256).generate()));
    return token.serialize();
  }
}
