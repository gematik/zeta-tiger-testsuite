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

import com.fasterxml.jackson.annotation.JsonProperty;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.And;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.security.auth.x500.X500Principal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;

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
   */
  @Dann("schreibe Daten aus dem SMC-B Zertifikat {tigerResolvedString} in die Variable {string}")
  @And("write data from the SMC-B certificate {tigerResolvedString} into the variable {string}")
  public void extractSmcbData(String certificate, String varName)  {
    CertificateInfo info = extractSmcbInfo(certificate);

    TigerGlobalConfiguration.putValue(varName, info, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Exposes the certificate's admission identifier as a scalar for Tiger assertions.
   *
   * @param certificate Base64-encoded SMC-B certificate
   * @param variable scalar variable receiving the nonempty Telematik-ID
   */
  @Dann("speichere die Telematik-ID aus SMC-B Zertifikat {tigerResolvedString} in Variable {string}")
  public void storeSmcbTelematikId(String certificate, String variable) {
    var id = extractSmcbInfo(certificate).telematikId;
    if (id == null || id.isBlank()) {
      throw new AssertionError("SMC-B certificate must contain a nonempty Telematik-ID in its admission extension");
    }
    TigerGlobalConfiguration.putValue(variable, id, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Data container for extracted certificate information.
   *
   * <p>This class holds values parsed from an SMC-B (Smartcard for Medical Practices)
   * X.509 certificate, such as Telematik ID, Common Name, Organization Name,
   * and Profession ID.</p>
   */
  @Data
  @NoArgsConstructor  // Default Constructor
  @AllArgsConstructor  // Optional: Full Constructor
  static class CertificateInfo {
    /** The Telematik ID associated with the certificate. */
    @JsonProperty("telematikId")
    String telematikId;

    /** The common name (CN) of the certificate subject. */
    @JsonProperty("commonName")
    String commonName;

    /** The organization name (O) of the certificate subject. */
    @JsonProperty("organizationName")
    String organizationName;

    /** The profession identifier (ProfessionOID) extracted from the admission data. */
    @JsonProperty("professionId")
    String professionId;
  }

  /**
   * Extracts information from an SMC-B certificate string.
   *
   * <p>This method decodes the Base64-encoded certificate, converts it to an {@link X509Certificate},
   * parses the subject DN for common fields (CN, O), and extracts profession- and telematik-related
   * OID values from the certificate extensions.</p>
   *
   * @param certificate The Base64-encoded certificate string.
   * @return A {@link CertificateInfo} object containing parsed certificate details.
   */
  static CertificateInfo extractSmcbInfo(String certificate) {
    try {
      byte[] certBytes = Base64.getDecoder().decode(certificate);
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      try (var stream = new ByteArrayInputStream(certBytes)) {
        X509Certificate cert = (X509Certificate) cf.generateCertificate(stream);

        // Subject DN parsen (commonName, organizationName)
        CertificateInfo info = parseSubjectDN(cert);

        extractOidValues(cert, info);

        return info;
      }
    } catch (Exception e) {
      throw new AssertionError("Could not extract info from SMC-B certificate: " + e.getMessage());
    }
  }

  /**
   * Parses the subject distinguished name (DN) of an X.509 certificate.
   *
   * <p>Extracts commonly used attributes such as Common Name (CN) and Organization Name (O)
   * from the subject field of the provided certificate.</p>
   *
   * @param cert The X.509 certificate to parse.
   * @return A partially filled {@link CertificateInfo} containing CN and O values.
   */
  private static CertificateInfo parseSubjectDN(X509Certificate cert) {
    CertificateInfo info = new CertificateInfo();

    X500Principal subject = cert.getSubjectX500Principal();
    X500Name x500name = new X500Name(subject.getName());

    // commonName (CN)
    RDN[] cnRdns = x500name.getRDNs(BCStyle.CN);
    if (cnRdns.length > 0) {
      info.commonName = IETFUtils.valueToString(cnRdns[0].getFirst().getValue());
    }

    // organizationName (O)
    RDN[] onRdns = x500name.getRDNs(BCStyle.O);
    if (onRdns.length > 0) {
      info.organizationName = IETFUtils.valueToString(onRdns[0].getFirst().getValue());
    }

    return info;
  }

  /**
   * Extracts profession and telematik identifiers from certificate extension OIDs.
   *
   * <p>This method specifically processes the 'Admission' extension, which contains
   * structured profession and registration information encoded in ASN.1 format.
   * The extracted data is added to the given {@link CertificateInfo} instance.</p>
   *
   * @param cert The certificate from which OID values are extracted.
   * @param info The {@link CertificateInfo} instance to update.
   */
  private static void extractOidValues(X509Certificate cert, CertificateInfo info) {
    try {
      byte[] extValue = cert.getExtensionValue(ADMISSION_OID.getId());
      if (extValue == null) {
        return;
      }

      extractAdmissionValues(extValue, info);
    } catch (Exception e) {
      throw new AssertionError("Could not extract TelematikID from the given SMC-B certificate.");
    }
  }

  /** Decodes optional Admission/ProfessionInfo fields by their ASN.1 types, not field count. */
  static void extractAdmissionValues(byte[] extension, CertificateInfo info) throws IOException {
    var value = ASN1OctetString.getInstance(extension).getOctets();
    var admission = AdmissionSyntax.getInstance(ASN1Primitive.fromByteArray(value));
    info.professionId = "";
    info.telematikId = "";
    for (var entry : admission.getContentsOfAdmissions()) {
      for (var profession : entry.getProfessionInfos()) {
        var identifiers = profession.getProfessionOIDs();
        info.professionId = identifiers.length == 0 ? "" : identifiers[0].getId();
        var registrationNumber = profession.getRegistrationNumber();
        info.telematikId = registrationNumber == null ? "" : registrationNumber;
        return;
      }
    }
  }
}
