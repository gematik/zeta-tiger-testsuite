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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Counterexamples for event correlation; mail bodies here are synthetic unit fixtures.
 */
class ClientDeletionMailAssertionsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String EMAIL = "M404230279@example.invalid";

  @Test
  void requiresNewDeliveryToExactBoundF1AndExcludesOldAndOtpMessages() {
    var old = message(1, EMAIL, "Client deleted");
    var otp = message(2, EMAIL, "Your email verification code");
    var foreign = message(3, "M404230279@other.invalid", "Client deleted");
    var fresh = message(4, EMAIL.toLowerCase(java.util.Locale.ROOT), "Client deleted");
    var before = ClientDeletionMailAssertions.index(array(old));
    var after = ClientDeletionMailAssertions.index(array(old, otp, foreign, fresh));
    assertEquals(
        java.util.List.of(fresh), ClientDeletionMailAssertions.newDeliveries(before, after, EMAIL));
    assertTrue(ClientDeletionMailAssertions.newDeliveries(before, before, EMAIL).isEmpty());
  }

  @Test
  void mailResetReusedIdsOrMutatedBaselineCannotProduceAPass() {
    var old = message(1, EMAIL, "Your email verification code");
    var before = ClientDeletionMailAssertions.index(array(old));
    assertThrows(
        AssertionError.class,
        () ->
            ClientDeletionMailAssertions.newDeliveries(
                before,
                ClientDeletionMailAssertions.index(array(message(2, EMAIL, "Client deleted"))),
                EMAIL));
    assertThrows(
        AssertionError.class,
        () ->
            ClientDeletionMailAssertions.newDeliveries(
                before,
                ClientDeletionMailAssertions.index(array(message(1, EMAIL, "Client deleted"))),
                EMAIL));
    assertThrows(AssertionError.class, () -> ClientDeletionMailAssertions.index(array(old, old)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "[{}]",
        "[{\"id\":1,\"subject\":\"notice\",\"recipients\":[]}]",
        "[{\"id\":2147483648,\"subject\":\"notice\",\"recipients\":[\"<a@b>\"]}]",
        "[{\"id\":1,\"subject\":\"notice\",\"recipients\":[\"evil <a@b> text\"]}]"
      })
  void malformedMetadataCannotBeSilentlyTreatedAsNoMail(String body) {
    assertThrows(
        AssertionError.class, () -> ClientDeletionMailAssertions.index(JSON.readTree(body)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Your client was deleted.",
        "Your client has been removed.\nContact support if this was not you.",
        "Your client \"target-client\" was deregistered.",
        "Ihr Client wurde gelöscht.",
        "Ihr Client „target-client“ wurde entfernt."
      })
  void configuredAdapterRecognizesCompletedDeletionStatements(String body) throws Exception {
    assertTrue(profile().matcher(body).matches());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Your client will be deleted.",
        "Your client was not deleted.",
        "Your client was registered.",
        "Your email address was changed.",
        "Your email verification code is 123456.",
        "Client deletion requested.",
        "If your client was deleted, contact support.",
        "Your client \"other-client\" was deleted.",
        "Ihr Client wird gelöscht.",
        "Ihr Client wurde nicht gelöscht.",
        "Your client was deleted?",
        ""
      })
  void configuredAdapterRejectsDelayedEnrollmentPlannedNegatedForeignAndHypotheticalNotices(
      String body) throws Exception {
    assertFalse(profile().matcher(body).matches());
  }

  @Test
  void permissiveOrUnresolvedProfilesFailBeforeDeletion() {
    assertThrows(AssertionError.class, () -> ClientDeletionMailAssertions.noticePattern("(?s).*"));
    assertThrows(
        AssertionError.class, () -> ClientDeletionMailAssertions.noticePattern("${unresolved}"));
    assertThrows(AssertionError.class, () -> ClientDeletionMailAssertions.noticePattern(""));
  }

  private static Pattern profile() throws Exception {
    String line =
        Files.readAllLines(Path.of("tiger/testdata.yaml")).stream()
            .map(String::strip)
            .filter(s -> s.startsWith("completedNoticePattern: '"))
            .findFirst()
            .orElseThrow();
    assertTrue(line.endsWith("'"));
    String expression = line.substring("completedNoticePattern: '".length(), line.length() - 1);
    return ClientDeletionMailAssertions.noticePattern(
        expression.replace("{clientId}", Pattern.quote("target-client")));
  }

  private static ObjectNode message(int id, String recipient, String subject) {
    var node = JSON.createObjectNode().put("id", id).put("subject", subject);
    node.putArray("recipients").add("<" + recipient + ">");
    return node;
  }

  private static ArrayNode array(ObjectNode... messages) {
    var result = JSON.createArrayNode();
    for (var message : messages) {
      result.add(message);
    }
    return result;
  }
}
