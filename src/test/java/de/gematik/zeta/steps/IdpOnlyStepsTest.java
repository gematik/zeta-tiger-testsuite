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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class IdpOnlyStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void replacementChallengeCannotSelectOldForeignOrSharedOtp() {
    var messages =
        JSON.readTree(
            """
            [{"id":1,"subject":"verification code","recipients":["<old@example.org>"]},
             {"id":2,"subject":"verification code","recipients":["<foreign@example.org>"]},
             {"id":3,"subject":"verification code","recipients":["new@example.org","foreign@example.org"]},
             {"id":4,"subject":"verification code","recipients":["<NEW@example.org>"]}]
            """);
    var matches =
        IdpOnlySteps.newChallengeMessages(
            messages, java.util.Set.of(1), java.util.Set.of("old@example.org", "new@example.org"));
    assertEquals(1, matches.size());
    assertEquals(4, matches.getFirst().path("id").asInt());
    assertEquals("123456", IdpOnlySteps.uniqueOtp("Code: 123456. Repeated 123456"));
    assertThrows(AssertionError.class, () -> IdpOnlySteps.uniqueOtp("123456 or 654321"));
    assertThrows(AssertionError.class, () -> IdpOnlySteps.uniqueOtp("missing"));
  }

  @Test
  void doesNotMistakeBadAuthenticationOrSuccessfulGrantForDeniedRecovery() {
    var rejected = JSON.readTree("{\"error\":\"invalid_grant\"}");
    IdpOnlySteps.verifyRejection(400, rejected);
    for (var status : List.of(200, 401, 403, 500)) {
      assertThrows(AssertionError.class, () -> IdpOnlySteps.verifyRejection(status, rejected));
    }
    for (var error : List.of("invalid_client", "invalid_request", "invalid_dpop_proof")) {
      assertThrows(
          AssertionError.class,
          () -> IdpOnlySteps.verifyRejection(400, JSON.createObjectNode().put("error", error)));
    }
    for (var field : List.of("access_token", "refresh_token", "id_token")) {
      assertThrows(
          AssertionError.class,
          () ->
              IdpOnlySteps.verifyRejection(
                  400, JSON.createObjectNode().put("error", "invalid_grant").putNull(field)));
    }
  }

  @Test
  void preservesTheSuccessfulControlProfileButReplacesContextAndProof() {
    var original =
        new HashMap<>(
            Map.of(
                "grant_type",
                "urn:ietf:params:oauth:grant-type:token-exchange",
                "subject_token_type",
                "urn:ietf:params:oauth:token-type:access_token",
                "client_assertion_type",
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "subject_token",
                "control-binding",
                "client_assertion",
                "old-assertion",
                "client_id",
                "control"));
    var fresh = IdpOnlySteps.exchangeParameters(original, "pending-binding", "new-client");
    assertEquals("pending-binding", fresh.get("subject_token"));
    assertEquals("new-client", fresh.get("client_id"));
    assertFalse(fresh.containsKey("client_assertion"));
    assertFalse(fresh.containsKey("scope"));
    original.put("resource", "r1");
    original.put("scope", "s1 s2");
    fresh = IdpOnlySteps.exchangeParameters(original, "pending-binding", "new-client");
    assertEquals("s1 s2", fresh.get("scope"));
    assertEquals("r1", fresh.get("resource"));
    original.put("unknown", "value");
    assertThrows(
        AssertionError.class,
        () -> IdpOnlySteps.exchangeParameters(original, "pending-binding", "new-client"));
  }

  @Test
  void formDecodingRejectsDuplicateParametersIncludingEncodedNames() {
    assertEquals("a+b&c", IdpOnlySteps.form("value=a%2Bb%26c").get("value"));
    for (var body :
        List.of("client_id=a&client_id=b", "client_id=a&client%5Fid=b", "bad", "bad=%XX")) {
      assertThrows(AssertionError.class, () -> IdpOnlySteps.form(body));
    }
  }

  private static Map<String, String> proof() {
    var result = new HashMap<String, String>();
    result.put("protocol", "sdk-idp-only-v1");
    int n = 0;
    for (var role : List.of("owner", "attacker", "control")) {
      n++;
      result.put(role + "_client", "00000000-0000-0000-0000-00000000000" + n);
      result.put(role + "_public_sha", Integer.toString(n).repeat(64));
      var count = role.equals("attacker") ? "0" : "1";
      for (var field : List.of("at_count", "rt_count", "otp_delivered")) {
        result.put(role + "_" + field, count);
      }
      result.put(role + "_status", "HAS_ACCESS_AND_REFRESH_TOKEN");
      result.put(
          role.equals("attacker") ? "attacker_withheld_message_id" : role + "_otp_message_id",
          Integer.toString(n));
    }
    result.put("owner_completed_ms", "1");
    result.put("attacker_started_ms", "2");
    result.put("attacker_pending_ms", "3");
    result.put("control_started_ms", "4");
    result.put("control_completed_ms", "5");
    result.put("attacker_pending_client", result.get("attacker_client"));
    result.put("attacker_pending_public_sha", result.get("attacker_public_sha"));
    result.put("attacker_pending_at_count", "0");
    result.put("attacker_pending_rt_count", "0");
    result.put("attacker_awaiting", "otp");
    result.put("attacker_auth_completed", "false");
    result.put("attacker_pending_after_control", "true");
    return result;
  }

  @Test
  void acceptsSeparatedPendingAndSuccessfulControlContexts() {
    IdpOnlySteps.verifyFixture(proof());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "attacker_otp_delivered",
        "attacker_pending_at_count",
        "attacker_pending_rt_count",
        "attacker_auth_completed",
        "attacker_pending_after_control",
        "attacker_awaiting",
        "owner_status",
        "control_status",
        "attacker_pending_public_sha"
      })
  void contaminationOrUnsuccessfulControlCannotPass(String field) {
    var proof = proof();
    proof.put(field, "invalid");
    assertThrows(AssertionError.class, () -> IdpOnlySteps.verifyFixture(proof));
  }

  @Test
  void sharedClientsKeysOrMailboxMessagesCannotPass() {
    for (var field : List.of("client", "public_sha", "otp_message_id")) {
      var proof = proof();
      proof.put("control_" + field, proof.get("owner_" + field));
      assertThrows(AssertionError.class, () -> IdpOnlySteps.verifyFixture(proof));
    }
    var proof = proof();
    proof.put("attacker_started_ms", "0");
    assertThrows(AssertionError.class, () -> IdpOnlySteps.verifyFixture(proof));
  }
}
