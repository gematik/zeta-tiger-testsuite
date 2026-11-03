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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AndroidKeyAttestationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ASN1ObjectIdentifier ATTESTATION =
      new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");

  @TempDir
  Path directory;

  @Test
  void acceptsIndependentHardwareEvidenceAndDetachedPossession() throws Exception {
    var fixture = fixture(1, false, true);
    AndroidKeyAttestation.verifyHardwareChain(fixture.chain(), fixture.roots());
    AndroidKeyAttestation.verifyClientKeyBinding(fixture.chain(), fixture.jwk());
    ClientKeyProof.verifyPossession(fixture.jwk(), fixture.proof());
  }

  @Test
  void acceptsStrongBox() throws Exception {
    var fixture = fixture(2, false, true);
    AndroidKeyAttestation.verifyHardwareChain(fixture.chain(), fixture.roots());
  }

  @Test
  void rejectsUntrustedRootEvenWhenChainIsSelfConsistent() throws Exception {
    var fixture = fixture(1, false, true);
    var foreign = fixture(1, false, true);
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyHardwareChain(fixture.chain(), foreign.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("configured Google root");
  }

  @Test
  void rejectsBrokenCertificateSignatureUnderTheTrustedRoot() throws Exception {
    var fixture = fixture(1, false, true);
    var values = JSON.readTree(fixture.chain());
    var leaf = Base64.getDecoder().decode(values.get(0).asString());
    leaf[leaf.length - 1] ^= 1;
    var changed = JSON.writeValueAsString(List.of(
        Base64.getEncoder().encodeToString(leaf), values.get(1).asString()));
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyHardwareChain(changed, fixture.roots()))
        .isInstanceOf(CertPathValidatorException.class);
  }

  @Test
  void rejectsAValidChainBoundToAnotherClient() throws Exception {
    var fixture = fixture(1, false, true);
    var foreign = new ECKeyGenerator(Curve.P_256).generate().toPublicJWK().toJSONString();
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyClientKeyBinding(fixture.chain(), foreign))
        .isInstanceOf(AssertionError.class).hasMessageContaining("challenge differs");
  }

  @Test
  void rejectsPossessionWithAnotherClientKey() throws Exception {
    var fixture = fixture(1, false, true);
    var foreign = new ECKeyGenerator(Curve.P_256).generate().toPublicJWK().toJSONString();
    assertThatThrownBy(() -> ClientKeyProof.verifyPossession(foreign, fixture.proof()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("possession signature");
  }

  @Test
  void rejectsDoubleHashedSignature() throws Exception {
    var fixture = fixture(1, false, true);
    var hash = MessageDigest.getInstance("SHA-256").digest(point(fixture.client()));
    var wrong = sign(fixture.client(), hash);
    assertThatThrownBy(() -> ClientKeyProof.verifyPossession(fixture.jwk(), wrong))
        .isInstanceOf(AssertionError.class).hasMessageContaining("possession signature");
  }

  @Test
  void rejectsClientKeyUsedAsAttestationKey() throws Exception {
    var fixture = fixture(1, true, true);
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyClientKeyBinding(fixture.chain(), fixture.jwk()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("must differ");
  }

  @Test
  void rejectsSoftwareAttestationLevels() throws Exception {
    var fixture = fixture(0, false, true);
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyHardwareChain(fixture.chain(), fixture.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("TEE or StrongBox");
  }

  @Test
  void rejectsMissingAttestationExtension() throws Exception {
    var fixture = fixture(1, false, false);
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyHardwareChain(fixture.chain(), fixture.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Missing Android");
  }

  @Test
  void rejectsDuplicateCertificates() throws Exception {
    var fixture = fixture(1, false, true);
    var certificates = JSON.readTree(fixture.chain());
    var chain = JSON.writeValueAsString(List.of(
        certificates.get(0).asString(), certificates.get(0).asString(), certificates.get(1).asString()));
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyHardwareChain(chain, fixture.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Duplicate");
  }

  @Test
  void rejectsMalformedCertificateAndSignature() throws Exception {
    var fixture = fixture(1, false, true);
    assertThatThrownBy(() -> AndroidKeyAttestation.verifyHardwareChain("[\"AA==\",\"AA==\"]", fixture.roots()))
        .isInstanceOf(CertificateException.class);
    assertThatThrownBy(() -> ClientKeyProof.verifyPossession(fixture.jwk(), "!"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Fixture fixture(int level, boolean sameKey, boolean extension) throws Exception {
    var rootKey = new ECKeyGenerator(Curve.P_256).generate();
    var client = new ECKeyGenerator(Curve.P_256).generate();
    var attestationKey = sameKey ? client : new ECKeyGenerator(Curve.P_256).generate();
    var rootName = new X500Name("CN=Unit Root " + UUID.randomUUID());
    var root = certificate(rootName, rootName, rootKey, rootKey, null, true);
    ASN1Encodable description = null;
    if (extension) {
      description = new DERSequence(new ASN1Encodable[] {
          new ASN1Integer(400), new ASN1Enumerated(level),
          new ASN1Integer(400), new ASN1Enumerated(level),
          new DEROctetString(MessageDigest.getInstance("SHA-256").digest(point(client))),
          new DEROctetString(new byte[0]), new DERSequence(), new DERSequence()
      });
    }
    var leaf = certificate(new X500Name("CN=AK"), rootName, attestationKey, rootKey, description, false);
    var roots = directory.resolve(UUID.randomUUID() + ".pem");
    Files.writeString(roots, "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(root.getEncoded())
        + "\n-----END CERTIFICATE-----\n", StandardCharsets.US_ASCII);
    var chain = JSON.writeValueAsString(List.of(
        Base64.getEncoder().encodeToString(leaf.getEncoded()),
        Base64.getEncoder().encodeToString(root.getEncoded())));
    return new Fixture(chain, roots.toString(), client, sign(client, point(client)));
  }

  private static X509Certificate certificate(X500Name name, X500Name issuer, ECKey key,
      ECKey signer, ASN1Encodable description, boolean ca) throws Exception {
    var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.ONE,
        Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)),
        name, key.toECPublicKey());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (description != null) {
      builder.addExtension(ATTESTATION, false, description);
    }
    return new JcaX509CertificateConverter().getCertificate(
        builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.toECPrivateKey())));
  }

  private static byte[] point(ECKey key) {
    var point = new byte[65];
    point[0] = 4;
    System.arraycopy(key.getX().decode(), 0, point, 1, 32);
    System.arraycopy(key.getY().decode(), 0, point, 33, 32);
    return point;
  }

  private static String sign(ECKey key, byte[] input) throws Exception {
    var signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key.toECPrivateKey());
    signer.update(input);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
  }

  private record Fixture(String chain, String roots, ECKey client, String proof) {
    String jwk() {
      return client.toPublicJWK().toJSONString();
    }
  }
}
