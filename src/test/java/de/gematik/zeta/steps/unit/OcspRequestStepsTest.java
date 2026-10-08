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

package de.gematik.zeta.steps.unit;

import static de.gematik.zeta.model.tls.TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CA_CERTIFICATE;
import static de.gematik.zeta.model.tls.TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE;
import static de.gematik.zeta.model.tls.TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_NOT_YET_VALID_CERTIFICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.steps.OcspRequestSteps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OcspRequestSteps}.
 */
class OcspRequestStepsTest {

  private static final String CAPTURED_GOOD_CERTIFICATE_OCSP_REQUEST_HEX =
      "305530533051304f304d300906052b0e03021a0500041466a8f76355758287c48d63cbb93935f982a23d14"
          + "0414272817f83eb2ed45f2d4b59f04dd610fffafbb6b021465be14a49d30b2abcadc9b0c106f96a464b60eca";
  private static final String ECDSA_SERVER_CA_CERTIFICATE =
      "ecdsa/zeta-tls-test-tool-server_CA.cer";
  private static final Path CERTIFICATE_DIRECTORY =
      Path.of("src", "test", "resources", "tls-test-tool", "certificates");

  /**
   * Returns the complete remaining duration when the target is in the future.
   */
  @Test
  void remainingWaitDurationReturnsFutureDifference() {
    var now = Instant.parse("2026-07-20T10:00:00Z");

    assertThat(OcspRequestSteps.remainingWaitDuration(now.plusSeconds(17), now))
        .isEqualTo(Duration.ofSeconds(17));
  }

  /**
   * Returns zero when the target has already passed.
   */
  @Test
  void remainingWaitDurationReturnsZeroForPastTarget() {
    var now = Instant.parse("2026-07-20T10:00:00Z");

    assertThat(OcspRequestSteps.remainingWaitDuration(now.minusSeconds(1), now))
        .isZero();
  }

  /**
   * Returns zero when the target is exactly the current instant.
   */
  @Test
  void remainingWaitDurationReturnsZeroForCurrentTarget() {
    var now = Instant.parse("2026-07-20T10:00:00Z");

    assertThat(OcspRequestSteps.remainingWaitDuration(now, now))
        .isZero();
  }

  /**
   * Accepts a captured OCSP request when its CertID identifies the configured server certificate.
   */
  @Test
  void assertOcspRequestContainsCertificateIdAcceptsCapturedGoodCertificateRequest() {
    var ocspSteps = new OcspRequestSteps();

    assertDoesNotThrow(() -> ocspSteps.assertOcspRequestContainsCertificateId(
        capturedGoodCertificateOcspRequest(),
        ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE));
  }

  /**
   * Rejects a captured OCSP request when its CertID does not identify the configured server certificate.
   */
  @Test
  void assertOcspRequestContainsCertificateIdRejectsDifferentCertificateRequest() {
    var ocspSteps = new OcspRequestSteps();

    var assertionError = assertThrows(AssertionError.class, () -> ocspSteps.assertOcspRequestContainsCertificateId(
        capturedGoodCertificateOcspRequest(),
        ZETA_TLS_TEST_TOOL_SERVER_ECDSA_NOT_YET_VALID_CERTIFICATE));

    assertTrue(assertionError.getMessage().contains("OCSP request did not contain CertID"));
  }

  /**
   * Accepts a captured OCSP request when the expected CertID is built from explicit leaf and issuer certificates.
   *
   * @throws IOException              if the certificate fixture cannot be read
   * @throws GeneralSecurityException if the certificate fixture cannot be parsed
   */
  @Test
  void assertOcspRequestContainsCertificateIdAcceptsExplicitCertificateAndIssuer()
      throws IOException, GeneralSecurityException {
    var ocspSteps = new OcspRequestSteps();
    var certificateChain = readCertificateChain(ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE.getRelativePath());
    var caChain = readCertificateChain(ECDSA_SERVER_CA_CERTIFICATE);

    assertDoesNotThrow(() -> ocspSteps.assertOcspRequestContainsCertificateId(
        capturedGoodCertificateOcspRequest(),
        certificateChain.getFirst(),
        caChain.getFirst(),
        "explicit certificate"));
  }

  /**
   * Rejects a leaf-only fixture whose issuer differs from the configured TLS test tool CA.
   */
  @Test
  void assertOcspRequestContainsCertificateIdRejectsLeafOnlyFixtureWithDifferentIssuer() {
    var ocspSteps = new OcspRequestSteps();

    var assertionError = assertThrows(AssertionError.class, () -> ocspSteps.assertOcspRequestContainsCertificateId(
        capturedGoodCertificateOcspRequest(),
        ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CA_CERTIFICATE));

    assertTrue(assertionError.getMessage().contains("issuer does not match the configured TLS test tool CA"));
  }

  /**
   * Returns the OCSP request captured in the target report for the good ECDSA server certificate.
   *
   * @return DER-encoded OCSP request bytes
   */
  private static byte[] capturedGoodCertificateOcspRequest() {
    return HexFormat.of().parseHex(CAPTURED_GOOD_CERTIFICATE_OCSP_REQUEST_HEX);
  }

  /**
   * Reads all certificates from a TLS test tool certificate fixture.
   *
   * @param relativePath certificate path relative to the fixture directory
   * @return parsed certificate chain in file order
   * @throws IOException              if the certificate fixture cannot be read
   * @throws GeneralSecurityException if the certificate fixture cannot be parsed
   */
  private static List<X509Certificate> readCertificateChain(String relativePath)
      throws IOException, GeneralSecurityException {
    try (var inputStream = Files.newInputStream(CERTIFICATE_DIRECTORY.resolve(relativePath))) {
      return CertificateFactory.getInstance("X.509")
          .generateCertificates(inputStream)
          .stream()
          .map(X509Certificate.class::cast)
          .collect(ArrayList::new, List::add, List::addAll);
    }
  }
}
