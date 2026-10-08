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

package de.gematik.zeta.steps.tls;

import de.gematik.zeta.model.tls.TlsSupportedGroup;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.regex.Pattern;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.assertj.core.api.Assertions;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1ParsingException;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;

/**
 * Inspector for certificate material logged by the TLS test tool.
 */
public final class TlsCertificateLogInspector {

  private static final String OID_BRAINPOOL256R1 = "1.3.36.3.3.2.8.1.1.7";
  private static final String OID_BRAINPOOL384R1 = "1.3.36.3.3.2.8.1.1.11";
  private static final String OID_BRAINPOOL512R1 = "1.3.36.3.3.2.8.1.1.13";
  private static final String OID_P256 = "1.2.840.10045.3.1.7";
  private static final String OID_P384 = "1.3.132.0.34";
  private static final Pattern CERTIFICATE_LIST_PATTERN =
      Pattern.compile("Certificate\\.certificate_list\\[0\\]=([0-9a-fA-F \\t]+)");
  private static final Pattern HEX_BYTE_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{2}\\b");

  /** Utility class. */
  private TlsCertificateLogInspector() {
  }

  /**
   * Checks whether the logged server certificate uses TR-02102-2-recommended key lengths and domain parameters.
   *
   * @param tlsLogs full TLS test tool log content
   */
  public static void assertRecommendedKeyLengthsAndDomainParameters(String tlsLogs) {
    var der = extractCertificateFromLog(tlsLogs);
    if (der == null || der.length == 0) {
      throw new AssertionError("No certificate hex dump found in the log.");
    }

    X509Certificate cert;
    try {
      cert = parseAsX509(der);
    } catch (CertificateException e) {
      throw new AssertionError("Error parsing the certificate", e);
    }

    var pk = cert.getPublicKey();
    if (!(pk instanceof ECPublicKey ecPk)) {
      throw new AssertionError("The X.509 certificate public key is not EC.");
    }

    var curveOid = getNamedCurveOid(cert);
    if (curveOid == null) {
      throw new AssertionError("The X.509 certificate EC curve is missing or can not be determined.");
    }

    var selectedCurve = getSupportedGroupFromCurveOid(curveOid);
    var fieldSize = ecPk.getParams().getCurve().getField().getFieldSize();
    Assertions
        .assertThat(selectedCurve.getTls12Policy())
        .withFailMessage(
            "Certificate EC curve OID %s resolves to %s with non-recommended policy %s. Field size=%d",
            curveOid,
            selectedCurve.getDisplayName(),
            selectedCurve.getTls12Policy(),
            fieldSize)
        .isIn(TlsSupportedGroup.Tls12Policy.MANDATORY, TlsSupportedGroup.Tls12Policy.OPTIONAL);

    Assertions
        .assertThat(fieldSize)
        .withFailMessage(
            "Certificate EC key length/domain parameters are not recommended by TR-02102-2. Curve=%s, OID=%s, field size=%d",
            selectedCurve.getDisplayName(),
            curveOid,
            fieldSize)
        .isGreaterThanOrEqualTo(250);
  }

  /**
   * Parses DER-encoded certificate bytes as an {@link X509Certificate}.
   *
   * @param der certificate bytes in DER format
   * @return parsed {@link X509Certificate}
   * @throws CertificateException if parsing fails
   */
  private static X509Certificate parseAsX509(byte[] der) throws CertificateException {
    var cf = CertificateFactory.getInstance("X.509");
    try (InputStream in = new ByteArrayInputStream(der)) {
      var c = cf.generateCertificate(in);
      if (!(c instanceof X509Certificate x509)) {
        throw new AssertionError(
            "Parsed certificate is not an X509Certificate (type=" + c.getType() + ")");
      }
      return x509;
    } catch (IOException ioe) {
      throw new CertificateException("I/O error while parsing certificate", ioe);
    }
  }

  /**
   * Extracts the certificate bytes from the log line with {@code Certificate.certificate_list[0]=...}.
   *
   * @param fullLog full TLS log content
   * @return DER certificate bytes or {@code null} if not present
   */
  private static byte[] extractCertificateFromLog(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      return null;
    }

    var m = CERTIFICATE_LIST_PATTERN.matcher(fullLog);
    if (!m.find()) {
      return null;
    }

    var raw = m.group(1);
    var b = HEX_BYTE_PATTERN.matcher(raw);
    var byteCount = 0;
    while (b.find()) {
      byteCount++;
    }
    if (byteCount == 0) {
      return null;
    }
    var normalized = new StringBuilder(byteCount * 2);
    b.reset();
    while (b.find()) {
      normalized.append(b.group());
    }
    try {
      return Hex.decodeHex(normalized.toString());
    } catch (DecoderException e) {
      return null;
    }
  }

  /**
   * Extracts the named curve OID from the certificate.
   *
   * @param cert X509 certificate
   * @return named curve OID or {@code null} if not an EC named curve certificate
   */
  private static String getNamedCurveOid(X509Certificate cert) {
    if (cert == null) {
      return null;
    }

    var pk = cert.getPublicKey();
    if (!(pk instanceof ECPublicKey)) {
      return null;
    }
    try {
      var spki = SubjectPublicKeyInfo.getInstance(pk.getEncoded());
      if (!spki.getAlgorithm().getAlgorithm().equals(X9ObjectIdentifiers.id_ecPublicKey)) {
        return null;
      }

      var params = spki.getAlgorithm().getParameters();
      if (params == null) {
        return null;
      }

      var curveOid = ASN1ObjectIdentifier.getInstance(params);
      return curveOid.getId();
    } catch (NullPointerException | IllegalArgumentException | ASN1ParsingException e) {
      return null;
    }
  }

  /**
   * Resolves a TLS supported group from a certificate curve OID.
   *
   * @param curveOid EC named-curve OID from the certificate
   * @return matching supported group or {@link TlsSupportedGroup#UNKNOWN}
   */
  private static TlsSupportedGroup getSupportedGroupFromCurveOid(String curveOid) {
    if (curveOid == null || curveOid.isBlank()) {
      return TlsSupportedGroup.UNKNOWN;
    }
    return switch (curveOid) {
      case OID_P256 -> TlsSupportedGroup.SECP256R1;
      case OID_P384 -> TlsSupportedGroup.SECP384R1;
      case OID_BRAINPOOL256R1 -> TlsSupportedGroup.BRAINPOOLP256R1;
      case OID_BRAINPOOL384R1 -> TlsSupportedGroup.BRAINPOOLP384R1;
      case OID_BRAINPOOL512R1 -> TlsSupportedGroup.BRAINPOOLP512R1;
      default -> TlsSupportedGroup.UNKNOWN;
    };
  }
}
