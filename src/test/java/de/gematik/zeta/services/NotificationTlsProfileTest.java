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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationTlsProfileTest {
  private static final URI GATEWAY = URI.create("https://gateway.test/push/v1/");
  private static final String ENTRY =
      "Push Gateway dispatch failed for notification id to " + GATEWAY + " on attempt 1: ";

  @Test
  void distinguishesTheNsCertificateCauseAndRejectsUnrelatedDiagnostics() {
    for (var log :
        List.of(
            "Handshake aborted. TCP EOF",
            "SSLHandshakeException: timed out",
            "SSLHandshakeException: Received fatal alert: internal_error",
            "SSLHandshakeException: timed out\nPKIX path building failed",
            "SSLHandshakeException: No subject alternative DNS name matching other.test found")) {
      assertNull(
          NotificationTlsProfile.certificateValidationRejection(ENTRY + log, GATEWAY, "id"), log);
    }
    String chain =
        "SSLHandshakeException: PKIX path building failed: unable to find valid certification path";
    var untrusted =
        NotificationTlsProfile.certificateValidationRejection(ENTRY + chain, GATEWAY, "id");
    assertNotNull(untrusted);
    assertEquals("SERVER_CERTIFICATE_UNTRUSTED", untrusted.reason());
    org.junit.jupiter.api.Assertions.assertTrue(untrusted.cause().contains(chain));
    for (var cause :
        List.of(
            "No subject alternative DNS name matching gateway.test found",
            "No name matching gateway.test found",
            "Hostname gateway.test not verified",
            "Certificate for <gateway.test> doesn't match any of the subject alternative names")) {
      var rejection =
          NotificationTlsProfile.certificateValidationRejection(
              ENTRY + "Caused by: javax.net.ssl.SSLHandshakeException: " + cause, GATEWAY, "id");
      assertNotNull(rejection);
      assertEquals("SERVER_HOSTNAME_MISMATCH", rejection.reason());
    }
  }

  @Test
  void ignoresOlderNotificationsOtherDestinationsAndSplitEntries() {
    String failure = ENTRY + "PKIX path building failed";
    assertNull(NotificationTlsProfile.certificateValidationRejection(failure, GATEWAY, "other"));
    assertNull(
        NotificationTlsProfile.certificateValidationRejection(
            failure, URI.create("https://other.test/push/v1/"), "id"));
    assertNull(
        NotificationTlsProfile.certificateValidationRejection(
            failure.replace("id to", "id-old to"), GATEWAY, "id"));
    assertNull(
        NotificationTlsProfile.certificateValidationRejection(
            ENTRY + "timed out\nSSLHandshakeException: PKIX path building failed", GATEWAY, "id"));
    assertNull(
        NotificationTlsProfile.certificateValidationRejection(
            failure.replace("/push/v1/ on", "/push/v1/other on"), GATEWAY, "id"));
    var rejection = NotificationTlsProfile.certificateValidationRejection(failure, GATEWAY, "id");
    assertNotNull(rejection);
    assertEquals("SERVER_CERTIFICATE_UNTRUSTED", rejection.reason());
  }

  @Test
  void failedReconfigurationCannotFallBackToClonedNs() {
    try (var targets = mockConstruction(DeployedNotificationService.class);
        var files = mockStatic(NotificationTlsFiles.class);
        var peers = mockConstruction(TlsTestToolEndpoint.class);
        var configuration = mockStatic(NotificationTlsDeployment.class)) {
      files
          .when(() -> NotificationTlsFiles.configuration(anyString()))
          .thenAnswer(
              call -> {
                String key = call.getArgument(0);
                if (key.contains(".targets.")) {
                  String name = key.split("\\.")[4];
                  return key.endsWith(".url") ? "https://" + name + ".test/push/v1/" : name;
                }
                throw new AssertionError("missing installed Helm variant");
              });
      configuration.when(() -> NotificationTlsDeployment.gateway(null, "test", "installed", "missing"))
          .thenThrow(new AssertionError("TLS rollout failed"));
      var profile =
          new NotificationTlsProfile(
              null,
              "test",
              "installed",
              "https://baseline.test/push/v1/",
              "tool",
              "baseline");
      assertThrows(AssertionError.class, () -> profile.variant("missing", "good"));
      profile.close();
      org.junit.jupiter.api.Assertions.assertTrue(targets.constructed().isEmpty());
      org.junit.jupiter.api.Assertions.assertTrue(peers.constructed().isEmpty());
    }
  }
}
