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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SdkLogoutStepsTest {
  @Test
  void refreshPreservesOptionalOriginalGrantParametersAndTheirAbsence() {
    assertEquals(Map.of(), SdkLogoutSteps.originalRefreshParameters(Map.of("grant_type", "token-exchange", "client_assertion", "old")));
    var parameters = Map.of("scope", "scope1 scope2", "resource", "https://guard.example/pep/",
        "audience", "https://guard.example/pep/", "requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
    var source = new HashMap<>(parameters);
    source.put("client_assertion", "must-be-replaced");
    source.put("subject_token", "must-not-be-replayed");
    assertEquals(parameters, SdkLogoutSteps.originalRefreshParameters(source));
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid_grant", "refresh_token_revoked", "session_terminated"})
  void requiresActualSdkRevocationRejectedRefreshAndInactiveAdminSession(String error) {
    var json = new ObjectMapper();
    var body = json.readTree("{\"error\":\"" + error + "\"}");
    // Physical retained rows alone are not a violation of this logout aspect.
    var inventory = json.readTree("{\"admin_sessions\":0,\"persisted\":{\"database_sessions\":1}}");
    assertDoesNotThrow(() -> SdkLogoutSteps.verifyServerOutcome(true, 400, body, inventory));
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyServerOutcome(false, 400, body, inventory));
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyServerOutcome(true, 200, body, inventory));
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyServerOutcome(true, 503, body, inventory));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"admin_sessions\":1}", "{}", "{\"admin_sessions\":\"0\"}", "{\"admin_sessions\":null}"})
  void missingTypedZeroOrStillActiveSessionCannotPass(String inventory) {
    var json = new ObjectMapper();
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyServerOutcome(true, 400,
        json.readTree("{\"error\":\"invalid_grant\"}"), json.readTree(inventory)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"error\":\"invalid_client\"}", "{\"error\":\"invalid_dpop_proof\"}",
      "{\"error\":\"invalid_grant\",\"access_token\":null}", "{\"error\":\"invalid_grant\",\"refresh_token\":\"issued\"}",
      "{\"error\":\"invalid_grant\",\"id_token\":\"issued\"}"})
  void unrelatedAuthenticationFailureOrAnyIssuedTokenCannotPass(String body) {
    var json = new ObjectMapper();
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyServerOutcome(true, 400, json.readTree(body), json.readTree("{\"admin_sessions\":0}")));
  }

  @Test
  void reportsBothTheMissingSdkRequestAndTheUsableServerGrant() {
    var json = new ObjectMapper();
    var error = assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyServerOutcome(false, 200,
        json.readTree("{\"access_token\":\"issued\"}"), json.readTree("{\"admin_sessions\":1}")));
    assertTrue(error.getMessage().contains("SDK did not successfully POST"));
    assertTrue(error.getMessage().contains("HTTP 200"));
    assertTrue(error.getMessage().contains("session remains active"));
  }

  @Test
  void bindsTheOriginalPairToIdentityClientAndSuccessfulLogout() throws Exception {
    var token = token("identity", "client", 100000);
    assertDoesNotThrow(() -> SdkLogoutSteps.verifyGrant(proof(token), token, "refresh", "client"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"access_sha256", "refresh_sha256", "identity", "status_before", "logout_success", "protocol"})
  void rejectsWrongContextOrFailedLogout(String field) throws Exception {
    var token = token("identity", "client", 100000);
    var proof = proof(token);
    proof.put(field, "wrong");
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyGrant(proof, token, "refresh", "client"));
  }

  @Test
  void rejectsAnotherClientAndExpiringToken() throws Exception {
    var token = token("identity", "another-client", 100000);
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyGrant(proof(token), token, "refresh", "client"));
    var expiring = token("identity", "client", 33000);
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyGrant(proof(expiring), expiring, "refresh", "client"));
  }

  @Test
  void rejectsReversedOperationOrder() throws Exception {
    var token = token("identity", "client", 100000);
    var proof = proof(token);
    proof.put("logout_completed_ms", "1");
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyGrant(proof, token, "refresh", "client"));
  }

  @Test
  void rejectsAnObserverWithoutPositiveControlTraffic() throws Exception {
    var token = token("identity", "client", 100000);
    var proof = proof(token);
    proof.put("control_socket_writes", "0");
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.verifyGrant(proof, token, "refresh", "client"));
  }

  @Test
  void acceptsThePublishedHttpsPath() {
    assertDoesNotThrow(() -> SdkLogoutSteps.validateEndpoint("https://guard.example/auth/revoke", "guard.example"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://guard.example/revoke", "https://other.example/revoke",
      "https://user@guard.example/revoke", "https://guard.example/revoke?x=y",
      "https://guard.example/revoke#fragment", "https://guard.example/"})
  void rejectsUntrustedOrAmbiguousDiscoveryEndpoints(String endpoint) {
    assertThrows(AssertionError.class, () -> SdkLogoutSteps.validateEndpoint(endpoint, "guard.example"));
  }

  private static Map<String, String> proof(String token) throws Exception {
    return new HashMap<>(Map.of("protocol", "sdk-logout-v1", "status_before", "HAS_ACCESS_AND_REFRESH_TOKEN",
        "access_sha256", hash(token), "refresh_sha256", hash("refresh"), "identity", "identity",
        "authentication_started_ms", "1000", "logout_started_ms", "2000", "logout_completed_ms", "3000", "logout_success", "true",
        "control_socket_writes", "5"));
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static String token(String subject, String client, long expires) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    var token = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), new JWTClaimsSet.Builder()
        .subject(subject).claim("client_id", client).expirationTime(new Date(expires)).build());
    token.sign(new ECDSASigner(key));
    return token.serialize();
  }
}
