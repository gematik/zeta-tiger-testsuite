/*-
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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.And;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;

/**
 * Extracts Telematik-ID from gematik SMC-B certificates.
 *
 */
public class SmcbSteps {

  /**
   * Gematik Admission Extension OID (gemSpec_PKI 6.3.3).
   */
  private static final ASN1ObjectIdentifier ADMISSION_OID =
      new ASN1ObjectIdentifier("1.3.36.8.3.3");


  /**
   * Extracts Telematik-ID from SMC-B certificate and stores it in Tiger test variable.
   *
   * @param certificate Base64-encoded SMC-B certificate (PEM or raw Base64)
   * @param varName     Tiger variable name to store Telematik-ID (e.g. "telematikId")
   * @throws AssertionError if certificate parsing fails or Telematik-ID not found
   * @throws Exception      on ASN.1 parsing or certificate validation errors
   */
  @Dann("schreibe die Telematik ID aus dem SMC-B Zertifikat {tigerResolvedString} in die Variable {string}")
  @And("write the Telematik ID from the SMC-B certificate {tigerResolvedString} into the variable {string}")
  public void extractTelematikID(String certificate, String varName) throws AssertionError {
    String telematikId = extractTelematikId(certificate);

    TigerGlobalConfiguration.putValue(varName, telematikId, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Extracts Telematik-ID from Base64-encoded SMC-B certificate.
   *
   * @param certificate Base64-encoded X.509 certificate (PEM or DER)
   * @return Telematik-ID (format: X-XXXXXXXXXX) or {@code null} if not found
   * @throws Exception on parsing errors
   */
  private static String extractTelematikId(String certificate) throws AssertionError {
    try {
      byte[] certBytes = Base64.getDecoder().decode(certificate);

      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      try (var stream = new ByteArrayInputStream(certBytes)) {
        X509Certificate cert = (X509Certificate) cf.generateCertificate(stream);
        byte[] extValue = cert.getExtensionValue(ADMISSION_OID.getId());
        if (extValue == null) {
          return null;
        }

        try (ASN1InputStream asn1In = new ASN1InputStream(new ByteArrayInputStream(extValue))) {
          ASN1Object obj1 = asn1In.readObject();

          if (obj1 instanceof ASN1OctetString) {
            byte[] realExtValue = ((ASN1OctetString) obj1).getOctets();
            try (ASN1InputStream asn1In2 = new ASN1InputStream(new ByteArrayInputStream(realExtValue))) {
              Object seqObj = asn1In2.readObject();

              if (seqObj instanceof ASN1Sequence admissionData) {

                while (admissionData.size() == 1) {
                  admissionData = (ASN1Sequence) admissionData.getObjectAt(0);
                }

                return parseAdmissionData(admissionData);
              }
            }
          }
        }

        return cert.getSubjectX500Principal().getName().split("O=")[1].split(",")[0].trim();
      }
    } catch (Exception e) {
      throw new AssertionError("Could not extract TelematikID from the given SMC-B certificate.");
    }
  }

  /**
   * Parses registrationNumber from position 2 of Admission structure.
   *
   * @param admissionData ASN1Sequence
   * @return TelematikID if it is found
   */

  private static String parseAdmissionData(ASN1Sequence admissionData) {
    if (admissionData.size() == 3) {
      ASN1Encodable regNumEnc = admissionData.getObjectAt(2);

      if (regNumEnc != null) {
        return regNumEnc.toASN1Primitive().toString();
      }
    }

    return "";
  }
}
