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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ClientManagementStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String EMAIL = "M404230279@example.invalid";

  @Test
  void exactSameIdentityPairAndRemovalOfTargetPass() {
    assertDoesNotThrow(() -> ClientManagementSteps.verifyBefore(before(), "caller", "target", EMAIL));
    assertDoesNotThrow(() -> ClientManagementSteps.verifyAfter(before(), after(), "caller"));
  }

  @Test
  void retainedMetadataDoesNotRepresentAnExistingClientRegistration() {
    var first = before().put("raw_registration_count", 2);
    var last = after().put("raw_registration_count", 2);
    assertDoesNotThrow(() -> ClientManagementSteps.verifyAfter(first, last, "caller"));
    // Retained metadata must never hide a still-existing target or another live registration.
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(first, last.deepCopy().put("target_count", 1), "caller"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(first, last.deepCopy().put("registration_count", 2), "caller"));
  }

  @Test
  void selfDeletionForeignTargetOrAdditionalClientCannotSatisfyFixture() {
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyBefore(before(), "caller", "caller", EMAIL));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyBefore(before(), "caller", "foreign", EMAIL));
    var extra = before();
    extra.withArray("client_ids").add("third");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyBefore(extra, "caller", "target", EMAIL));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyBefore(before().put("email_verified", false), "caller", "target", EMAIL));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyBefore(before(), "caller", "target", "M404230279@foreign.invalid"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity_id", "user_id", "email", "email_verified"})
  void changedOrMissingIdentityCannotPass(String field) {
    var last = after().put(field, "changed");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), last, "caller"));
    last.remove(field);
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), last, "caller"));
  }

  @Test
  void deletingWrongClientNoClientOrBothClientsFails() {
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), before(), "caller"));
    var last = after();
    last.putArray("client_ids").add("target");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), last, "caller"));
    last.putArray("client_ids");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), last, "caller"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), after().put("target_count", 1), "caller"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), after().put("registration_count", 2), "caller"));
  }

  @Test
  void duplicateAndIncompleteInventoriesFail() {
    var last = after();
    last.withArray("client_ids").add("caller");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), last, "caller"));
    last.remove("client_ids");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), last, "caller"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyAfter(before(), after().put("target_count", "0"), "caller"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"${unresolved}", "x'; DELETE FROM client;--", "a b", "x\n"})
  void unresolvedOrUnsafeSelectorsFail(String selector) {
    assertThrows(AssertionError.class, () -> ClientManagementSteps.query(selector, "caller", "target"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.query("realm", selector, "target"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.query("realm", "caller", selector));
  }

  @Test
  void absentAmbiguousOrMalformedDatabaseRowsFail() {
    assertThrows(AssertionError.class, () -> ClientManagementSteps.parse(""));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.parse("{}\n{}"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.parse("[]"));
    var error = assertThrows(AssertionError.class, () -> ClientManagementSteps.parse("{\"sensitive-marker\":"));
    assertFalse(error.toString().contains("sensitive-marker"));
  }

  @Test
  void requiresAnExistingClientOfADifferentVerifiedIdentity() {
    assertDoesNotThrow(() -> ClientManagementSteps.verifyForeignFixture(before(), foreign(), "foreign-client", "foreign-sibling", "X110639491@example.invalid"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyForeignFixture(before(), before(), "caller", "target", EMAIL));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyForeignFixture(before(), foreign(), "nonexistent", "foreign-sibling", "X110639491@example.invalid"));
    assertThrows(AssertionError.class, 
        () -> ClientManagementSteps.verifyForeignFixture(before(), foreign().put("identity_id", "identity"), "foreign-client", "foreign-sibling", "X110639491@example.invalid"));
    assertThrows(AssertionError.class, 
        () -> ClientManagementSteps.verifyForeignFixture(before(), foreign().put("email_verified", false), "foreign-client", "foreign-sibling", "X110639491@example.invalid"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyForeignFixture(before(), foreign().put("target_count", 0), "foreign-client", "foreign-sibling", "X110639491@example.invalid"));
  }

  @Test
  void lastForeignClientCannotConfoundTheAuthorizationTest() {
    var lastClient = foreign().put("registration_count", 1);
    lastClient.putArray("client_ids").add("foreign-client");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyForeignFixture(before(), lastClient,
        "foreign-client", "foreign-sibling", "X110639491@example.invalid"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyForeignFixture(before(), foreign(),
        "foreign-client", "foreign-client", "X110639491@example.invalid"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity_id", "user_id", "email", "email_verified", "client_ids", "registration_count", "target_count"})
  void forbiddenAttemptsMustNotChangeAnyProjectedIdentityOrRegistrationField(String field) {
    var last = foreign();
    last.remove(field);
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyUnchanged(foreign(), last));
    last.put(field, "changed");
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyUnchanged(foreign(), last));
    assertDoesNotThrow(() -> ClientManagementSteps.verifyUnchanged(foreign(), foreign()));
  }

  @Test
  void denyAllAndAllowAllCannotPassTheResponsePair() {
    assertDoesNotThrow(() -> ClientManagementSteps.verifyResponsePair("403", "204"));
    assertDoesNotThrow(() -> ClientManagementSteps.verifyResponsePair("403", "200"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyResponsePair("403", "403"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyResponsePair("204", "204"));
    assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyResponsePair("404", "204"));
    var failure = assertThrows(AssertionError.class, () -> ClientManagementSteps.verifyResponsePair("405", "405"));
    org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("Foreign client DELETE expected 403, got 405"));
    org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("Own-client DELETE control expected 2xx, got 405"));
  }

  private static ObjectNode foreign() {
    var row = after().put("identity_id", "foreign-identity").put("user_id", "foreign-user")
        .put("email", "X110639491@example.invalid").put("registration_count", 2).put("target_count", 1);
    row.putArray("client_ids").add("foreign-client").add("foreign-sibling");
    return row;
  }

  private static ObjectNode before() {
    var row = JSON.createObjectNode().put("identity_id", "identity").put("user_id", "user")
        .put("email", EMAIL).put("email_verified", true).put("registration_count", 2).put("target_count", 1);
    row.putArray("client_ids").add("caller").add("target");
    return row;
  }

  private static ObjectNode after() {
    var row = before().put("registration_count", 1).put("target_count", 0);
    row.putArray("client_ids").add("caller");
    return row;
  }
}
