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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SmcbResourceStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.ofEpochSecond(1800000000);
  private static final String RESOURCE = "https://resource.example/path";
  private static final String INPUT =
      """
      {"input":{"client_registration_data":{"client_id":"own"},
      "authorization_request":{"grant_type":"urn:ietf:params:oauth:grant-type:token-exchange"}}}
      """;
  private static final String DECISION =
      """
      {"allow":true,"ttl":{"access_token":120,"refresh_token":240},"aud":"logical"}
      """;

  private static SmcbPolicySteps.TokenExchange token(int start, int end) {
    return new SmcbPolicySteps.TokenExchange(
        NOW.plusSeconds(start), NOW.plusSeconds(end), 200, "{}");
  }

  private static SmcbPolicySteps.PolicyExchange policy(int start, int end) {
    return new SmcbPolicySteps.PolicyExchange(
        NOW.plusSeconds(start), NOW.plusSeconds(end), 200, INPUT, "{\"result\":" + DECISION + "}");
  }

  @Test
  void eachRetryRequiresItsOwnExactDecisionStrictlyInsideItsResponseWindow() {
    var fixture = SmcbResourceSteps.decisionFixture(DECISION, RESOURCE);
    assertDoesNotThrow(
        () ->
            SmcbResourceSteps.verifyPolicyStimuli(
                List.of(token(0, 3), token(4, 7)),
                List.of(policy(5, 6), policy(1, 2)),
                "own",
                fixture));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "allow",
        "ttl_missing",
        "zero_ttl",
        "negative_ttl",
        "ttl_type",
        "aud_missing",
        "url_aud",
        "empty_aud",
        "reasons",
        "unknown",
        "malformed"
      })
  void invalidPolicyFixturesAreNotProductEvidence(String fault) {
    var d = (ObjectNode) JSON.readTree(DECISION);
    switch (fault) {
      case "allow" -> d.put("allow", false);
      case "ttl_missing" -> d.remove("ttl");
      case "zero_ttl" -> ((ObjectNode) d.path("ttl")).put("access_token", 0);
      case "negative_ttl" -> ((ObjectNode) d.path("ttl")).put("refresh_token", -1);
      case "ttl_type" -> ((ObjectNode) d.path("ttl")).put("access_token", "120");
      case "aud_missing" -> d.remove("aud");
      case "url_aud" -> d.put("aud", RESOURCE);
      case "empty_aud" -> d.putArray("aud");
      case "reasons" -> d.putObject("reasons");
      case "unknown" -> d.put("extra", true);
      case "malformed" -> {}
      default -> throw new AssertionError();
    }
    assertThrows(
        AssertionError.class,
        () ->
            SmcbResourceSteps.decisionFixture(
                "malformed".equals(fault) ? "[]" : d.toString(), RESOURCE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "extra",
        "foreign",
        "foreign_extra",
        "equal_start",
        "equal_end",
        "late",
        "shared",
        "changed_aud",
        "changed_ttl",
        "wrong_status"
      })
  void changedAmbiguousForeignOrLatePolicyCannotSatisfyTheStimulus(String fault) {
    var fixture = SmcbResourceSteps.decisionFixture(DECISION, RESOURCE);
    var p = policy(1, 2);
    var tokens = List.of(token(0, 3));
    var policies = List.of(p);
    switch (fault) {
      case "missing" -> policies = List.of();
      case "extra" -> policies = List.of(p, p);
      case "foreign" ->
          policies =
              List.of(
                  new SmcbPolicySteps.PolicyExchange(
                      p.sent(), p.completed(), 200, INPUT.replace("own", "foreign"), p.response()));
      case "foreign_extra" ->
          policies =
              List.of(
                  p,
                  new SmcbPolicySteps.PolicyExchange(
                      p.sent(), p.completed(), 200, INPUT.replace("own", "foreign"), p.response()));
      case "equal_start" -> policies = List.of(policy(0, 2));
      case "equal_end" -> policies = List.of(policy(1, 3));
      case "late" -> policies = List.of(policy(1, 4));
      case "shared" -> {
        tokens = List.of(token(0, 3), token(0, 3));
        policies = List.of(p, policy(5, 6));
      }
      case "changed_aud", "changed_ttl", "wrong_status" ->
          policies =
              List.of(
                  new SmcbPolicySteps.PolicyExchange(
                      p.sent(),
                      p.completed(),
                      "wrong_status".equals(fault) ? 500 : 200,
                      p.input(),
                      "changed_aud".equals(fault)
                          ? p.response().replace("logical", "foreign")
                          : "changed_ttl".equals(fault)
                              ? p.response().replace("120", "60")
                              : p.response()));
      default -> throw new AssertionError();
    }
    var observedTokens = tokens;
    var observedPolicies = policies;
    assertThrows(
        AssertionError.class,
        () ->
            SmcbResourceSteps.verifyPolicyStimuli(
                observedTokens, observedPolicies, "own", fixture));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "valid",
        "wrong_aud",
        "wrong_version",
        "wrong_client",
        "wrong_subject",
        "wrong_dpop",
        "wrong_key",
        "wrong_status",
        "no_access_token",
        "malformed_access_token",
        "malformed_response",
        "nonobject_claims",
        "multiple"
      })
  void actualAsSignedOutputMustBelongToTheOwnGrantAndPolicy(String fault) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("as").generate();
    var claims =
        new JWTClaimsSet.Builder()
            .audience("wrong_aud".equals(fault) ? "wrong" : "logical")
            .claim("ver", "wrong_version".equals(fault) || "multiple".equals(fault) ? 1 : 2)
            .claim(
                "client_id",
                "wrong_client".equals(fault) || "multiple".equals(fault) ? "foreign" : "own")
            .subject("wrong_subject".equals(fault) ? "foreign" : "smcb")
            .claim("cnf", java.util.Map.of("jkt", "wrong_dpop".equals(fault) ? "foreign" : "proof"))
            .build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("as").build(), claims);
    jwt.sign(
        new ECDSASigner(
            "wrong_key".equals(fault) ? new ECKeyGenerator(Curve.P_256).generate() : key));
    var body = JSON.createObjectNode().put("access_token", jwt.serialize());
    if ("nonobject_claims".equals(fault)) {
      body.put(
          "access_token",
          JwtTestHelper.createSignedJwt(jwt.getHeader().toString(), "[]", key.toECPrivateKey()));
    }
    if ("no_access_token".equals(fault)) {
      body.remove("access_token");
    }
    if ("malformed_access_token".equals(fault)) {
      body.put("access_token", "not-jwt");
    }
    var response = "malformed_response".equals(fault) ? "not-json" : body.toString();
    var grant =
        new SmcbResourceSteps.Grant(
            new SmcbPolicySteps.TokenExchange(
                NOW,
                NOW.plusSeconds(3),
                "wrong_status".equals(fault) || "multiple".equals(fault) ? 403 : 200,
                response),
            "own",
            "smcb",
            "proof");
    var failures =
        SmcbResourceSteps.outputFailures(
            grant,
            SmcbResourceSteps.decisionFixture(DECISION, RESOURCE),
            RESOURCE,
            new JWKSet(key.toPublicJWK()).toString());
    if ("valid".equals(fault)) {
      assertTrue(failures.isEmpty());
    } else {
      assertFalse(failures.isEmpty());
    }
    if ("multiple".equals(fault)) {
      assertEquals(3, failures.size());
      assertTrue(String.join(" ", failures).contains("ver:"));
    }
    if (List.of(
            "no_access_token", "malformed_access_token", "malformed_response", "nonobject_claims")
        .contains(fault)) {
      assertTrue(String.join(" ", failures).contains("not evaluated"));
    }
  }
}
