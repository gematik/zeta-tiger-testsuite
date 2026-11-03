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

import de.gematik.zeta.services.NotificationEnvelopeProof;
import de.gematik.zeta.services.PlaintextPayloadInspection;
import io.cucumber.java.de.Dann;

/** Assertions on recorded notification requests and encrypted message content. */
public class NotificationContentSteps {
  private static final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();

  /**
   * Authenticates a captured envelope and compares the recovered message with the known payload.
   *
   * @param ciphertext captured Base64 ciphertext
   * @param iss registration secret in hexadecimal
   * @param firstMonth registration month
   * @param encryptedMonth captured envelope month
   * @param payload original message JSON
   */
  @Dann("enthält das Push-Chiffrat {tigerResolvedString} mit ISS {tigerResolvedString} vom Monat"
      + " {tigerResolvedString} für den Monat {tigerResolvedString} den Payload {tigerResolvedString}")
  public void assertEncryptedMessage(String ciphertext, String iss, String firstMonth,
      String encryptedMonth, String payload) {
    NotificationEnvelopeProof.verify(ciphertext, iss, firstMonth, encryptedMonth, payload);
  }

  /**
   * Checks every recorded gateway request's body and metadata, including unanswered requests.
   *
   * @param pathPattern gateway notification request-path expression
   * @param expectedPayload original message JSON
   */
  @Dann("enthalten alle aufgezeichneten Gateway-Anfragen mit Pfad {tigerResolvedString}"
      + " keinen Klartext des Payloads {tigerResolvedString}")
  public void assertNoPlaintextInGatewayHistory(String pathPattern, String expectedPayload) {
    var pattern = java.util.regex.Pattern.compile(pathPattern);
    var requests = de.gematik.zeta.services.RecordedMessages.snapshot().stream()
        .filter(message -> !message.findRbelPathMembers("$.method").isEmpty())
        .filter(message -> message.findRbelPathMembers("$.path").stream()
            .anyMatch(path -> pattern.matcher(path.getRawStringContent()).matches()))
        .toList();
    if (requests.isEmpty()) {
      throw new AssertionError("No gateway requests recorded");
    }
    for (var request : requests) {
      request.findRbelPathMembers("$.body").stream().findFirst()
          .ifPresent(body -> PlaintextPayloadInspection.assertAbsentInText(
              body.getRawStringContent(), expectedPayload));
      var metadata = JSON.createObjectNode();
      request.findRbelPathMembers("$.path").stream().findFirst()
          .ifPresent(path -> metadata.put("target", path.getRawStringContent()));
      var queryValues = metadata.putArray("queryValues");
      request.findRbelPathMembers("$.path.*.value")
          .forEach(value -> queryValues.add(value.getRawStringContent()));
      request.findRbelPathMembers("$.header").stream().findFirst()
          .ifPresent(header -> metadata.put("headers", header.getRawStringContent()));
      var values = metadata.putArray("headerValues");
      request.findRbelPathMembers("$.header.*")
          .forEach(header -> values.add(header.getRawStringContent()));
      PlaintextPayloadInspection.assertAbsent(metadata.toString(), expectedPayload);
    }
  }

}
