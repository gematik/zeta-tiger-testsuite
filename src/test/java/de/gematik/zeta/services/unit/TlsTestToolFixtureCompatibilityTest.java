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

package de.gematik.zeta.services.unit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.model.tls.TlsServerCertificates;
import de.gematik.zeta.steps.tls.TlsTestToolCertificateFixtures;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the TLS test tool server certificate fixtures match the mapped private key files.
 */
class TlsTestToolFixtureCompatibilityTest {

  /**
   * Verifies that every mapped certificate fixture has a matching private key fixture.
   *
   * @throws Exception if a fixture cannot be read or parsed
   */
  @Test
  void mappedServerCertificatesMatchTheirPrivateKeys() throws Exception {
    for (var certificate : TlsServerCertificates.values()) {
      if (!certificate.name().endsWith("_CERTIFICATE")) {
        continue;
      }
      var privateKey = TlsServerCertificates.getPrivateKeyForCertificate(certificate);

      var certificatePublicKey = readCertificatePublicKey(certificate);
      var privateKeyPublicKey = readPrivateKeyPublicKey(privateKey);

      assertArrayEquals(
          certificatePublicKey.getEncoded(),
          privateKeyPublicKey.getEncoded(),
          () -> "Certificate and private key fixture do not match for " + certificate.name());
    }
  }

  /**
   * Verifies that TLS test tool certificate validity windows match their fixture purpose.
   *
   * @throws Exception if a fixture cannot be read or parsed
   */
  @Test
  void mappedServerCertificatesHaveStableValidityWindows() throws Exception {
    var positiveFixtureDate = Date.from(Instant.parse("2030-01-01T00:00:00Z"));

    assertValidAt(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE, positiveFixtureDate);
    assertValidAt(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CN_CERTIFICATE, positiveFixtureDate);
    assertValidAt(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_SAN_CERTIFICATE, positiveFixtureDate);
    assertValidAt(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CN_SAN_CERTIFICATE, positiveFixtureDate);
    assertValidAt(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CA_CERTIFICATE, positiveFixtureDate);

    var currentTestDate = Date.from(Instant.parse("2026-06-07T00:00:00Z"));
    assertThrows(
        CertificateExpiredException.class,
        () -> readCertificate(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_EXPIRED_CERTIFICATE)
            .checkValidity(currentTestDate));
    assertThrows(
        CertificateNotYetValidException.class,
        () -> readCertificate(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_NOT_YET_VALID_CERTIFICATE)
            .checkValidity(currentTestDate));

    var futureFixtureDate = Date.from(Instant.parse("2036-06-01T00:00:00Z"));
    assertValidAt(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_NOT_YET_VALID_CERTIFICATE, futureFixtureDate);
  }

  /**
   * Verifies that mapped server certificates carry the revocation information required by their fixture purpose.
   *
   * @throws Exception if a fixture cannot be read or parsed
   */
  @Test
  void mappedServerCertificatesExposeConfiguredRevocationInformation() throws Exception {
    for (var certificate : TlsServerCertificates.values()) {
      if (!certificate.name().endsWith("_CERTIFICATE")
          || TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_CRL_ONLY_CERTIFICATE == certificate
          || TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_SUBCA_CHAIN_CERTIFICATE == certificate) {
        continue;
      }
      assertTrue(
          hasOcspResponderInformation(readCertificate(certificate)),
          () -> "Certificate fixture should contain OCSP AIA: " + certificate.name());
    }

    var crlOnlyCertificate =
        readCertificate(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_CRL_ONLY_CERTIFICATE);
    assertFalse(
        hasOcspResponderInformation(crlOnlyCertificate),
        "CRL-only certificate fixture should not contain OCSP AIA.");
    assertTrue(
        hasCrlDistributionPoint(crlOnlyCertificate),
        "CRL-only certificate fixture should contain a CRL Distribution Point.");

    var subCaChain =
        readCertificateChain(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_SUBCA_CHAIN_CERTIFICATE);
    assertEquals(3, subCaChain.size(), "Sub-CA fixture should contain leaf, intermediate, and root certificates.");
    assertFalse(
        hasOcspResponderInformation(subCaChain.get(0)),
        "Sub-CA leaf fixture should use its CRL Distribution Point instead of OCSP AIA.");
    assertTrue(
        hasCrlDistributionPoint(subCaChain.get(0)),
        "Sub-CA leaf fixture should contain a CRL Distribution Point.");
    assertTrue(
        hasOcspResponderInformation(subCaChain.get(1)),
        "Sub-CA intermediate fixture should contain OCSP AIA.");

    assertFalse(
        hasOcspResponderInformation(readCertificate(Path.of("ecdsa", "zeta-tls-test-tool-server_without_ocsp.pem"))),
        "Explicit no-OCSP fixture should not contain OCSP AIA.");
  }

  /**
   * Verifies that every normal-CA leaf certificate chains to the configured TLS test tool CA.
   *
   * @throws Exception if a fixture cannot be read, parsed, or verified
   */
  @Test
  void normalCaServerCertificatesChainToConfiguredCa() throws Exception {
    var caCertificate = readCertificate(TlsTestToolCertificateFixtures.resolveCaCertificatePath());

    for (var certificate : TlsServerCertificates.values()) {
      if (!certificate.name().endsWith("_CERTIFICATE")
          || TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CA_CERTIFICATE == certificate
          || TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_SUBCA_CHAIN_CERTIFICATE == certificate) {
        continue;
      }
      assertIssuedBy(certificate.name(), readCertificate(certificate), caCertificate);
    }

    var subCaChain =
        readCertificateChain(TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_SUBCA_CHAIN_CERTIFICATE);
    assertEquals(3, subCaChain.size(), "Sub-CA fixture should contain leaf, intermediate, and root certificates.");
    assertIssuedBy("Sub-CA leaf certificate", subCaChain.get(0), subCaChain.get(1));
    assertIssuedBy("Sub-CA intermediate certificate", subCaChain.get(1), subCaChain.get(2));
    assertIssuedBy("Sub-CA root certificate", subCaChain.get(2), subCaChain.get(2));

    assertIssuedBy(
        "zeta-tls-test-tool-server_without_ocsp.pem",
        readCertificate(Path.of("ecdsa", "zeta-tls-test-tool-server_without_ocsp.pem")),
        caCertificate);
  }

  /**
   * Verifies that a certificate fixture is valid at the given point in time.
   *
   * @param certificate certificate fixture descriptor
   * @param date        point in time to check
   * @throws IOException              if the certificate file cannot be read
   * @throws GeneralSecurityException if the certificate cannot be parsed
   */
  private void assertValidAt(TlsServerCertificates certificate, Date date)
      throws IOException, GeneralSecurityException {
    assertDoesNotThrow(() -> readCertificate(certificate).checkValidity(date),
        () -> "Certificate fixture should be valid at " + date + ": " + certificate.name());
  }

  /**
   * Verifies that a leaf certificate is issued by the expected CA.
   *
   * @param certificateName name used in assertion messages
   * @param certificate     leaf certificate to check
   * @param caCertificate   expected issuing CA certificate
   * @throws GeneralSecurityException if signature verification fails
   */
  private void assertIssuedBy(String certificateName, X509Certificate certificate, X509Certificate caCertificate)
      throws GeneralSecurityException {
    assertArrayEquals(
        caCertificate.getSubjectX500Principal().getEncoded(),
        certificate.getIssuerX500Principal().getEncoded(),
        () -> "Certificate issuer must match the configured CA subject byte-for-byte: " + certificateName);
    assertDoesNotThrow(
        () -> certificate.verify(caCertificate.getPublicKey()),
        () -> "Certificate signature must verify with configured CA: " + certificateName);
  }

  /**
   * Reads the public key from the configured certificate fixture.
   *
   * @param certificate certificate fixture descriptor
   * @return public key from the X.509 certificate
   * @throws IOException              if the certificate file cannot be read
   * @throws GeneralSecurityException if the certificate cannot be parsed
   */
  private PublicKey readCertificatePublicKey(TlsServerCertificates certificate)
      throws IOException, GeneralSecurityException {
    return readCertificate(certificate).getPublicKey();
  }

  /**
   * Reads the configured certificate fixture.
   *
   * @param certificate certificate fixture descriptor
   * @return parsed X.509 certificate
   * @throws IOException              if the certificate file cannot be read
   * @throws GeneralSecurityException if the certificate cannot be parsed
   */
  private X509Certificate readCertificate(TlsServerCertificates certificate)
      throws IOException, GeneralSecurityException {
    return readCertificate(Path.of(certificate.getRelativePath()));
  }

  /**
   * Reads a certificate fixture by its path.
   *
   * @param relativePath certificate fixture path, either absolute or relative to the configured fixture directory
   * @return parsed X.509 certificate
   * @throws IOException              if the certificate file cannot be read
   * @throws GeneralSecurityException if the certificate cannot be parsed
   */
  private X509Certificate readCertificate(Path relativePath)
      throws IOException, GeneralSecurityException {
    var certificatePath = relativePath.isAbsolute()
        ? relativePath
        : TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(relativePath.toString());
    try (var inputStream = Files.newInputStream(certificatePath)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(inputStream);
    }
  }

  /**
   * Reads every certificate from the configured certificate-chain fixture.
   *
   * @param certificate certificate-chain fixture descriptor
   * @return certificates in the order stored in the fixture
   * @throws IOException              if the certificate file cannot be read
   * @throws GeneralSecurityException if a certificate cannot be parsed
   */
  private List<X509Certificate> readCertificateChain(TlsServerCertificates certificate)
      throws IOException, GeneralSecurityException {
    var certificatePath =
        TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(certificate.getRelativePath());
    try (var inputStream = Files.newInputStream(certificatePath)) {
      return CertificateFactory.getInstance("X.509").generateCertificates(inputStream).stream()
          .map(X509Certificate.class::cast)
          .toList();
    }
  }

  /**
   * Checks whether the certificate contains an OCSP authority-information-access description.
   *
   * @param certificate certificate to inspect
   * @return {@code true} when an OCSP access description is present
   * @throws IOException if the extension value cannot be decoded
   */
  private boolean hasOcspResponderInformation(X509Certificate certificate) throws IOException {
    var extensionValue = certificate.getExtensionValue(Extension.authorityInfoAccess.getId());
    if (extensionValue == null) {
      return false;
    }
    var octets = ASN1OctetString.getInstance(extensionValue).getOctets();
    var authorityInformationAccess = AuthorityInformationAccess.getInstance(ASN1Primitive.fromByteArray(octets));
    return Arrays.stream(authorityInformationAccess.getAccessDescriptions())
        .anyMatch(accessDescription -> AccessDescription.id_ad_ocsp.equals(accessDescription.getAccessMethod()));
  }

  /**
   * Checks whether the certificate contains a CRL Distribution Points extension.
   *
   * @param certificate certificate to inspect
   * @return {@code true} when a CRL Distribution Points extension is present
   */
  private boolean hasCrlDistributionPoint(X509Certificate certificate) {
    return certificate.getExtensionValue(Extension.cRLDistributionPoints.getId()) != null;
  }

  /**
   * Reads the public key from the configured private key fixture.
   *
   * @param privateKey private key fixture descriptor
   * @return public key derived from the private key fixture
   * @throws IOException if the private key file cannot be read
   */
  private PublicKey readPrivateKeyPublicKey(TlsServerCertificates privateKey)
      throws IOException {
    try (var pemParser =
        new PEMParser(
            new StringReader(
                Files.readString(TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(privateKey.getRelativePath()))))) {
      var pemObject = pemParser.readObject();
      var converter = new JcaPEMKeyConverter();
      if (pemObject instanceof PEMKeyPair keyPair) {
        return converter.getKeyPair(keyPair).getPublic();
      }
      if (pemObject instanceof PrivateKeyInfo privateKeyInfo) {
        throw new AssertionError(
            "The TLS test tool private key fixture does not expose a public key: "
                + privateKeyInfo.getPrivateKeyAlgorithm().getAlgorithm());
      }
      throw new AssertionError(
          "Unsupported TLS test tool private key fixture: " + Arrays.toString(new Object[]{pemObject}));
    }
  }
}
