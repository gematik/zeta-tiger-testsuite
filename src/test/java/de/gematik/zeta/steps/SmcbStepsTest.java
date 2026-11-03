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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax;
import org.bouncycastle.asn1.isismtt.x509.Admissions;
import org.bouncycastle.asn1.isismtt.x509.NamingAuthority;
import org.bouncycastle.asn1.isismtt.x509.ProfessionInfo;
import org.bouncycastle.asn1.x500.DirectoryString;
import org.bouncycastle.asn1.x509.GeneralName;
import org.junit.jupiter.api.Test;

class SmcbStepsTest {
  @Test
  void optionalNamingAuthoritiesAndAdditionalProfessionDataDoNotShiftIdentityFields() throws Exception {
    var authority = new GeneralName(GeneralName.uniformResourceIdentifier, "https://fixture.invalid/authority");
    var naming = new NamingAuthority(new ASN1ObjectIdentifier("1.2.3.4"), "https://fixture.invalid/naming",
        new DirectoryString("Fixture naming authority"));
    var profession = new ProfessionInfo(naming, new DirectoryString[] {new DirectoryString("Fixture profession")},
        new ASN1ObjectIdentifier[] {new ASN1ObjectIdentifier("1.2.276.0.76.4.53")}, "fixture-registration",
        new DEROctetString(new byte[] {1, 2}));
    var admission = new AdmissionSyntax(authority,
        new DERSequence(new Admissions(authority, naming, new ProfessionInfo[] {profession})));
    var info = new SmcbSteps.CertificateInfo();
    SmcbSteps.extractAdmissionValues(new DEROctetString(admission.getEncoded()).getEncoded(), info);
    assertEquals("1.2.276.0.76.4.53", info.getProfessionId());
    assertEquals("fixture-registration", info.getTelematikId());
  }

  @Test
  void absentProfessionOidIsNotReplacedByRegistrationNumber() throws Exception {
    var profession = new ProfessionInfo(null, new DirectoryString[] {new DirectoryString("Fixture profession")},
        null, "fixture-registration", null);
    var admission = new AdmissionSyntax(null,
        new DERSequence(new Admissions(null, null, new ProfessionInfo[] {profession})));
    var info = new SmcbSteps.CertificateInfo();
    SmcbSteps.extractAdmissionValues(new DEROctetString(admission.getEncoded()).getEncoded(), info);
    assertEquals("", info.getProfessionId());
    assertEquals("fixture-registration", info.getTelematikId());
  }

  @Test
  void absentRegistrationNumberProducesEmptyTelematikId() throws Exception {
    var profession = new ProfessionInfo(null, new DirectoryString[] {new DirectoryString("Fixture profession")},
        new ASN1ObjectIdentifier[] {new ASN1ObjectIdentifier("1.2.276.0.76.4.53")}, null, null);
    var admission = new AdmissionSyntax(null,
        new DERSequence(new Admissions(null, null, new ProfessionInfo[] {profession})));
    var info = new SmcbSteps.CertificateInfo();
    SmcbSteps.extractAdmissionValues(new DEROctetString(admission.getEncoded()).getEncoded(), info);
    assertEquals("1.2.276.0.76.4.53", info.getProfessionId());
    assertEquals("", info.getTelematikId());
  }

  @Test
  void emptyAdmissionsProduceEmptyIdentifiers() throws Exception {
    var admission = new AdmissionSyntax(null, new DERSequence());
    var info = new SmcbSteps.CertificateInfo();
    SmcbSteps.extractAdmissionValues(new DEROctetString(admission.getEncoded()).getEncoded(), info);
    assertEquals("", info.getProfessionId());
    assertEquals("", info.getTelematikId());
  }

  @Test
  void emptyProfessionInfosProduceEmptyIdentifiers() throws Exception {
    var admission = new AdmissionSyntax(null,
        new DERSequence(new Admissions(null, null, new ProfessionInfo[0])));
    var info = new SmcbSteps.CertificateInfo();
    SmcbSteps.extractAdmissionValues(new DEROctetString(admission.getEncoded()).getEncoded(), info);
    assertEquals("", info.getProfessionId());
    assertEquals("", info.getTelematikId());
  }

  @Test
  void invalidCertificateDoesNotProduceFallbackIdentity() {
    assertThrows(AssertionError.class, () -> SmcbSteps.extractSmcbInfo("not-a-certificate"));
  }
}
