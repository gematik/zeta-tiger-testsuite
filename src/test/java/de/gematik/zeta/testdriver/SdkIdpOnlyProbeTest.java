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

package de.gematik.zeta.testdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SdkIdpOnlyProbeTest {
  @Test
  void contextRejectsSkippedActionsAndCannotContinueAfterAnOperationFailure() throws Exception {
    var context = new SdkIdpOnlyProbe.Context("control", "owner@example.org", new Object[3]);
    assertThrows(AssertionError.class, () -> context.action("register"));
    context.requirePhase("discover");
    // The SDK is deliberately unavailable in this unit test. A failed action must not advance.
    assertThrows(Exception.class, () -> context.action("discover"));
    assertThrows(AssertionError.class, () -> context.action("register"));
    assertThrows(AssertionError.class, () -> context.action("discover"));
  }

  @Test
  void publicFingerprintUsesCanonicalCoordinatesAndRejectsPrivateOrMalformedInput()
      throws Exception {
    var generator = java.security.KeyPairGenerator.getInstance("EC");
    generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
    var pair = generator.generateKeyPair();
    var der = pair.getPublic().getEncoded();
    var pem =
        "-----BEGIN PUBLIC KEY-----\n"
            + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
            + "\n-----END PUBLIC KEY-----\n";
    var key =
        new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256,
                (java.security.interfaces.ECPublicKey) pair.getPublic())
            .build();
    assertEquals(key.computeThumbprint().toString(), SdkIdpOnlyProbe.publicKeyThumbprint(pem));
    assertEquals(
        SdkIdpOnlyProbe.publicKeyThumbprint(pem),
        SdkIdpOnlyProbe.publicKeyThumbprint(pem.replace("\n", "\r\n")));
    assertThrows(
        AssertionError.class,
        () -> SdkIdpOnlyProbe.publicKeyThumbprint(pem.replace("PUBLIC", "PRIVATE")));
    assertThrows(
        Exception.class,
        () ->
            SdkIdpOnlyProbe.publicKeyThumbprint(
                "-----BEGIN PUBLIC KEY-----invalid-----END PUBLIC KEY-----"));
  }

  private static Map<String, Object> mail(int id, String recipient, String subject) {
    return Map.of("id", id, "recipients", List.of(recipient), "subject", subject);
  }

  @Test
  void excludesTheWithheldBaselineAndOtherRecipientsSubjects() throws Exception {
    var messages =
        List.of(
            mail(10, "<owner@example.org>", "verification code"),
            mail(11, "foreign@example.org", "verification code"),
            mail(12, "owner@example.org", "other mail"),
            mail(13, "<OWNER@example.org>", "Verification Code"));
    assertEquals(
        List.of(13), SdkIdpOnlyProbe.matchingMail(messages, Set.of(10), "owner@example.org"));
  }

  @Test
  void duplicateAndAmbiguousMessagesFailInsteadOfPickingLatest() {
    var first = mail(10, "owner@example.org", "verification code");
    assertThrows(
        AssertionError.class,
        () -> SdkIdpOnlyProbe.matchingMail(List.of(first, first), Set.of(), "owner@example.org"));
    assertThrows(
        AssertionError.class,
        () ->
            SdkIdpOnlyProbe.matchingMail(
                List.of(first, mail(11, "owner@example.org", "verification code")),
                Set.of(),
                "owner@example.org"));
  }

  @Test
  void sharedCodeIsNotExclusiveOriginalFactorEvidence() throws Exception {
    var message =
        Map.of(
            "id",
            10,
            "recipients",
            List.of("owner@example.org", "foreign@example.org"),
            "subject",
            "verification code");
    assertEquals(
        List.of(), SdkIdpOnlyProbe.matchingMail(List.of(message), Set.of(), "owner@example.org"));
  }

  @Test
  void selectsStructuredRegistrationAndRejectsConflictingOrMissingClients() throws Exception {
    assertEquals(
        "client-a",
        SdkIdpOnlyProbe.client(Map.of("registration", Map.of("client_id", "client-a"))));
    assertThrows(
        AssertionError.class,
        () ->
            SdkIdpOnlyProbe.client(
                Map.of(
                    "a", Map.of("client_id", "client-a"), "b", Map.of("client_id", "client-b"))));
    assertThrows(
        AssertionError.class, () -> SdkIdpOnlyProbe.client(Map.of("unrelated", "client_id: fake")));
  }
}
