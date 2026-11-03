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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SmcbPolicyStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant START = Instant.ofEpochSecond(1800000000);
  private static final String REASONS = "{\"scope denied\":true,\"context denied\":true}";
  private static final String INPUT =
      """
      {"input":{"client_registration_data":{"client_id":"own"},
      "authorization_request":{"grant_type":"urn:ietf:params:oauth:grant-type:token-exchange"}}}
      """;
  private static final String DECISION =
      "{\"result\":{\"allow\":false,\"reasons\":" + REASONS + "}}";
  private static final String RESPONSE =
      "{\"error\":\"access_denied\",\"reasons\":" + REASONS + "}";

  private static SmcbPolicySteps.TokenExchange token(int start, int end) {
    return new SmcbPolicySteps.TokenExchange(
        START.plusSeconds(start), START.plusSeconds(end), 403, RESPONSE);
  }

  private static SmcbPolicySteps.PolicyExchange policy(int start, int end) {
    return new SmcbPolicySteps.PolicyExchange(
        START.plusSeconds(start), START.plusSeconds(end), 200, INPUT, DECISION);
  }

  @Test
  void matchesEachRetryToItsOwnDecisionInAnyRecordedOrder() {
    var first = policy(1, 2);
    var second = policy(5, 6);
    var paired =
        SmcbPolicySteps.correlate(List.of(token(0, 3), token(4, 7)), List.of(second, first), "own");
    assertEquals(List.of(first, second), paired);
    var reasons = SmcbPolicySteps.reasonFixture(REASONS);
    paired.forEach(p -> SmcbPolicySteps.verifyDenyDecision(p, reasons));
    assertTrue(SmcbPolicySteps.responseFailures(token(0, 3), reasons).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "foreign_client",
        "foreign_grant",
        "duplicate",
        "before",
        "after",
        "inverted",
        "missing_time",
        "inverted_token",
        "missing_token_time",
        "empty_tokens",
        "late_extra",
        "shared_between_retries",
        "malformed_input"
      })
  void rejectsUnrelatedAmbiguousOrLatePolicyEvidence(String fault) {
    var p = policy(1, 2);
    var tokens = List.of(token(0, 3));
    var policies = List.of(p);
    switch (fault) {
      case "missing" -> policies = List.of();
      case "foreign_client" ->
          policies =
              List.of(
                  new SmcbPolicySteps.PolicyExchange(
                      p.sent(), p.completed(), 200, INPUT.replace("own", "foreign"), DECISION));
      case "foreign_grant" ->
          policies =
              List.of(
                  new SmcbPolicySteps.PolicyExchange(
                      p.sent(),
                      p.completed(),
                      200,
                      INPUT.replace("token-exchange", "refresh_token"),
                      DECISION));
      case "duplicate" -> policies = List.of(p, p);
      case "before" -> policies = List.of(policy(-1, 2));
      case "after" -> policies = List.of(policy(1, 4));
      case "inverted" -> policies = List.of(policy(2, 1));
      case "missing_time" ->
          policies =
              List.of(
                  new SmcbPolicySteps.PolicyExchange(null, p.completed(), 200, INPUT, DECISION));
      case "inverted_token" -> tokens = List.of(token(3, 0));
      case "missing_token_time" ->
          tokens = List.of(new SmcbPolicySteps.TokenExchange(START, null, 403, RESPONSE));
      case "empty_tokens" -> tokens = List.of();
      case "late_extra" -> policies = List.of(p, policy(4, 5));
      case "shared_between_retries" -> {
        tokens = List.of(token(0, 3), token(0, 3));
        policies = List.of(p, policy(5, 6));
      }
      case "malformed_input" ->
          policies =
              List.of(
                  new SmcbPolicySteps.PolicyExchange(p.sent(), p.completed(), 200, "[]", DECISION));
      default -> throw new AssertionError("Unknown counterexample");
    }
    var observedTokens = tokens;
    var observedPolicies = policies;
    assertThrows(
        AssertionError.class,
        () -> SmcbPolicySteps.correlate(observedTokens, observedPolicies, "own"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[]",
        "null",
        "broken",
        "{\"x\":false}",
        "{\"x\":\"true\"}",
        "{\"x\":1}",
        "{\" \":true}",
        "{\"${unresolved}\":true}"
      })
  void rejectsInvalidIndependentReasons(String reasons) {
    assertThrows(AssertionError.class, () -> SmcbPolicySteps.reasonFixture(reasons));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "allow",
        "status",
        "wrong_reason",
        "false_reason",
        "ttl",
        "aud",
        "unknown_field",
        "missing_allow",
        "wrong_allow_type",
        "missing_reasons"
      })
  void rejectsIncorrectPolicyManipulationBeforeProductClassification(String fault) {
    var body = (ObjectNode) JSON.readTree(DECISION);
    var result = (ObjectNode) body.path("result");
    switch (fault) {
      case "allow" -> result.put("allow", true);
      case "status" -> {
        /* Only the observed HTTP status changes. */
      }
      case "wrong_reason" -> result.putObject("reasons").put("different", true);
      case "false_reason" -> ((ObjectNode) result.path("reasons")).put("scope denied", false);
      case "ttl" -> result.putObject("ttl").put("access_token", 60).put("refresh_token", 120);
      case "aud" -> result.put("aud", "resource");
      case "unknown_field" -> result.put("extra", true);
      case "missing_allow" -> result.remove("allow");
      case "wrong_allow_type" -> result.put("allow", "false");
      case "missing_reasons" -> result.remove("reasons");
      default -> throw new AssertionError("Unknown counterexample");
    }
    var p =
        new SmcbPolicySteps.PolicyExchange(
            START.plusSeconds(1),
            START.plusSeconds(2),
            fault.equals("status") ? 500 : 200,
            INPUT,
            body.toString());
    assertThrows(
        AssertionError.class,
        () -> SmcbPolicySteps.verifyDenyDecision(p, SmcbPolicySteps.reasonFixture(REASONS)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "access_token",
        "refresh_token",
        "zeta_attestation_token",
        "asl_access_token",
        "id_token"
      })
  void detectsEveryForbiddenTokenEvenWhenNullAndSchemasPermitIt(String field) {
    var body = (ObjectNode) JSON.readTree(RESPONSE);
    body.putNull(field);
    var observed =
        new SmcbPolicySteps.TokenExchange(START, START.plusSeconds(3), 403, body.toString());
    assertTrue(
        SmcbPolicySteps.responseFailures(observed, SmcbPolicySteps.reasonFixture(REASONS))
            .contains("Forbidden token field present: " + field));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong_error",
        "missing_error",
        "wrong_reasons",
        "missing_reason",
        "extra_reason",
        "array_reasons",
        "null_reasons",
        "false_reason",
        "schema_error_description"
      })
  void detectsWrongOrIncompleteErrorContractDespite403(String fault) {
    var body = (ObjectNode) JSON.readTree(RESPONSE);
    switch (fault) {
      case "wrong_error" -> body.put("error", "invalid_client");
      case "missing_error" -> body.remove("error");
      case "wrong_reasons" -> body.putObject("reasons").put("other", true);
      case "missing_reason" -> ((ObjectNode) body.path("reasons")).remove("scope denied");
      case "extra_reason" -> ((ObjectNode) body.path("reasons")).put("other", true);
      case "array_reasons" -> body.putArray("reasons").add("scope denied");
      case "null_reasons" -> body.putNull("reasons");
      case "false_reason" -> ((ObjectNode) body.path("reasons")).put("scope denied", false);
      case "schema_error_description" -> body.put("error_description", 42);
      default -> throw new AssertionError("Unknown counterexample");
    }
    var observed =
        new SmcbPolicySteps.TokenExchange(START, START.plusSeconds(3), 403, body.toString());
    assertTrue(
        !SmcbPolicySteps.responseFailures(observed, SmcbPolicySteps.reasonFixture(REASONS))
            .isEmpty());
  }

  @Test
  void wrongStatusDoesNotHideErrorReasonsTokenOrSchemaChecks() {
    var observed =
        new SmcbPolicySteps.TokenExchange(
            START,
            START.plusSeconds(3),
            200,
            "{\"access_token\":\"never print credentials\",\"error\":42}");
    var failures =
        SmcbPolicySteps.responseFailures(observed, SmcbPolicySteps.reasonFixture(REASONS));
    assertEquals(6, failures.size());
    assertTrue(failures.stream().noneMatch(s -> s.contains("never print credentials")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", "broken"})
  void malformedBodyStillReportsWrongStatusAndBlockedBodyAssertions(String body) {
    var failures =
        SmcbPolicySteps.responseFailures(
            new SmcbPolicySteps.TokenExchange(START, START.plusSeconds(3), 500, body),
            SmcbPolicySteps.reasonFixture(REASONS));
    assertEquals(2, failures.size());
    assertTrue(failures.getLast().contains("cannot be evaluated"));
  }

  @Test
  void certificateReferenceMustBeIndependentAndMatchTheActualLeaf() throws Exception {
    var subject =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .x509CertChain(List.of(new Base64("b3du")))
                .build(),
            new JWTClaimsSet.Builder().subject("identity").build());
    SmcbPolicySteps.verifyCertificate(subject, "b3du");
    for (var reference : List.of("Zm9yZWlnbg==", "", "${missing}")) {
      assertThrows(
          AssertionError.class, () -> SmcbPolicySteps.verifyCertificate(subject, reference));
    }
    var absent = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), subject.getJWTClaimsSet());
    assertThrows(AssertionError.class, () -> SmcbPolicySteps.verifyCertificate(absent, "b3du"));
  }
}
