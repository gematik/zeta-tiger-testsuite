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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Counterexamples for exact session selection and preservation of the independent control. */
class SessionTerminationStepsTest {
  @Test
  void logoutInventoryIsReadOnlyAndValidatesAllSelectors() {
    var sql = SessionTerminationSteps.singleSessionQuery("realm", "client", "sid");
    assertTrue(sql.contains("BEGIN TRANSACTION READ ONLY"));
    assertTrue(sql.contains("r.name='realm' AND c.client_id='client'"));
    assertTrue(sql.contains("u.username=d.user_id AND u.realm_id=r.id"));
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.singleSessionQuery("realm';--", "client", "sid"));
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.singleSessionQuery("realm", "client';--", "sid"));
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.singleSessionQuery("realm", "client", "sid';--"));
  }

  @Test
  void bothActiveThenOnlyTheExactTargetAbsentPasses() {
    assertDoesNotThrow(() -> SessionTerminationSteps.verifyState(state(1, 1, 1, 1), "X123456789", 1));
    assertDoesNotThrow(() -> SessionTerminationSteps.verifyState(state(0, 1, 1, 1), "X123456789", 0));
    assertDoesNotThrow(() -> admin("[" + session("target", "tc") + "," + session("control", "cc") + "]", true));
    assertDoesNotThrow(() -> admin("[" + session("control", "cc") + "]", false));
  }

  @Test
  void targetStillActiveOrMissingControlCannotPass() {
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.verifyState(state(1, 1, 1, 1), "X123456789", 0));
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.verifyState(state(0, 0, 1, 1), "X123456789", 0));
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.verifyState(state(0, 1, 0, 1), "X123456789", 0));
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.verifyState(state(0, 1, 1, 0), "X123456789", 0));
  }

  @Test
  void wrongIdentityAndNonIntegralCountsCannotPass() {
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.verifyState(state(0, 1, 1, 1), "Y123456789", 0));
    var wrong = SessionTerminationSteps.parse("{\"identity\":\"X123456789\",\"target_sessions\":\"0\"}");
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.verifyState(wrong, "X123456789", 0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "{}", "null",
      "[{\"id\":\"control\",\"userId\":\"foreign\",\"clients\":{\"internal\":\"cc\"}}]",
      "[{\"id\":\"control\",\"userId\":\"user\",\"clients\":{\"internal\":\"wrong\"}}]"})
  void missingOrForeignAdministrativeControlCannotPass(String response) {
    assertThrows(AssertionError.class, () -> admin(response, false));
  }

  @Test
  void deletingAnotherSessionOrDuplicateSessionIdsCannotPass() {
    assertThrows(AssertionError.class, () -> admin("[" + session("target", "tc") + "," + session("control", "cc") + "]", false));
    assertThrows(AssertionError.class, () -> admin("[" + session("control", "cc") + "," + session("control", "cc") + "]", false));
  }

  @Test
  void selectorsRejectSqlAndShellInjection() {
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.stateQuery("realm", "X123456789", "client'; DELETE FROM client;--", "sid", "cc", "cs"));
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.stateQuery("realm", "X123456789", "client", "$(secret)", "cc", "cs"));
    var sql = SessionTerminationSteps.stateQuery("realm", "X123456789", "client", "sid", "cc", "cs");
    assertTrue(sql.contains("BEGIN TRANSACTION READ ONLY"));
    assertTrue(sql.contains("u.username=d.user_id"));
    assertTrue(sql.contains("zetaguard.mobileuser.kvnr"));
    assertFalse(sql.contains("u.username='X123456789'"));
  }

  @Test
  void malformedProjectionDoesNotLeakRawOutput() {
    var failure = assertThrows(AssertionError.class, () -> SessionTerminationSteps.parse("sensitive-sentinel"));
    assertFalse(failure.toString().contains("sensitive-sentinel"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid_grant", "refresh_token_revoked", "session_terminated"})
  void terminatedGrantErrorAlternativesAreAccepted(String error) {
    assertDoesNotThrow(() -> SessionTerminationSteps.verifyError(SessionTerminationSteps.parse("{\"error\":\"" + error + "\"}")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"error\":\"invalid_client\"}", "{\"error\":\"invalid_dpop_proof\"}",
      "{\"error\":\"invalid_grant\",\"access_token\":null}", "{\"error\":\"invalid_grant\",\"refresh_token\":\"\"}",
      "{\"error\":\"invalid_grant\",\"id_token\":\"issued\"}"})
  void credentialErrorsOrIssuedTokensCannotPass(String body) {
    assertThrows(AssertionError.class, () -> SessionTerminationSteps.verifyError(SessionTerminationSteps.parse(body)));
  }

  private static JsonNode state(int target, int control, int targetClient, int controlClient) {
    return SessionTerminationSteps.parse("{\"identity\":\"X123456789\",\"user_id\":\"user\",\"target_sessions\":" + target
        + ",\"control_sessions\":" + control + ",\"target_clients\":" + targetClient + ",\"control_clients\":" + controlClient + "}");
  }

  private static void admin(String json, boolean active) {
    SessionTerminationSteps.verifyAdminSessions(SessionTerminationSteps.parse(json), "user", "target", "tc", "control", "cc", active);
  }

  private static String session(String id, String client) {
    return "{\"id\":\"" + id + "\",\"userId\":\"user\",\"clients\":{\"internal\":\"" + client + "\"}}";
  }
}
