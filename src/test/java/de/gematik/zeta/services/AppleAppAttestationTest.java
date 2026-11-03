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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertPathValidatorException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.cbor.CBORMapper;

/** Exercises App Attest certificate trust, nonce correlation and malformed evidence rejection. */
class AppleAppAttestationTest {
  private static final CBORMapper CBOR = new CBORMapper();
  private static final ASN1ObjectIdentifier NONCE = new ASN1ObjectIdentifier("1.2.840.113635.100.8.2");

  @TempDir
  Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptsTrustedEvidenceWithOptionalRootInX5c(boolean includeRoot) throws Exception {
    var fixture = fixture(false, true);
    var chain = includeRoot ? List.of(fixture.leaf(), fixture.root()) : List.of(fixture.leaf());
    var object = object(chain, fixture.authData());
    AppleAppAttestation.verifyChain(object, fixture.roots());
    AppleAppAttestation.verifyClientKeyBinding(object, fixture.jwk());
  }

  @Test
  void rejectsUntrustedRoot() throws Exception {
    var fixture = fixture(false, true);
    var foreign = fixture(false, true);
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain(fixture.object(), foreign.roots()))
        .isInstanceOf(CertPathValidatorException.class);
  }

  @Test
  void rejectsBrokenCertificateSignature() throws Exception {
    var fixture = fixture(false, true);
    var leaf = fixture.leaf().clone();
    leaf[leaf.length - 1] ^= 1;
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain(
        object(List.of(leaf), fixture.authData()), fixture.roots()))
        .isInstanceOf(CertPathValidatorException.class);
  }

  @Test
  void rejectsAnotherClientWithOtherwiseValidEvidence() throws Exception {
    var fixture = fixture(false, true);
    var foreign = new ECKeyGenerator(Curve.P_256).generate().toPublicJWK().toJSONString();
    assertThatThrownBy(() -> AppleAppAttestation.verifyClientKeyBinding(fixture.object(), foreign))
        .isInstanceOf(AssertionError.class).hasMessageContaining("nonce differs");
  }

  @Test
  void rejectsChangedAuthenticatorData() throws Exception {
    var fixture = fixture(false, true);
    var changed = fixture.authData().clone();
    changed[0] ^= 1;
    assertThatThrownBy(() -> AppleAppAttestation.verifyClientKeyBinding(
        object(List.of(fixture.leaf()), changed), fixture.jwk()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("nonce differs");
  }

  @Test
  void rejectsMissingNonceExtension() throws Exception {
    var fixture = fixture(false, false);
    assertThatThrownBy(() -> AppleAppAttestation.verifyClientKeyBinding(fixture.object(), fixture.jwk()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Missing Apple App Attest nonce");
  }

  @Test
  void rejectsClientKeyUsedAsAttestationKey() throws Exception {
    var fixture = fixture(true, true);
    assertThatThrownBy(() -> AppleAppAttestation.verifyClientKeyBinding(fixture.object(), fixture.jwk()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("must differ");
  }

  @Test
  void rejectsDuplicateCertificatesAndTrailingDerBytes() throws Exception {
    var fixture = fixture(false, true);
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain(
        object(List.of(fixture.leaf(), fixture.leaf()), fixture.authData()), fixture.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Duplicate");
    var trailing = Arrays.copyOf(fixture.leaf(), fixture.leaf().length + 1);
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain(
        object(List.of(trailing), fixture.authData()), fixture.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Trailing bytes");
  }

  @Test
  void rejectsMissingAndMalformedCborFields() throws Exception {
    var fixture = fixture(false, true);
    for (var field : List.of("fmt", "authData", "attStmt")) {
      var changed = CBOR.createObjectNode();
      changed.setAll((tools.jackson.databind.node.ObjectNode) CBOR.readTree(
          Base64.getDecoder().decode(fixture.object())));
      changed.remove(field);
      var encoded = Base64.getEncoder().encodeToString(CBOR.writeValueAsBytes(changed));
      assertThatThrownBy(() -> AppleAppAttestation.verifyChain(encoded, fixture.roots()))
          .isInstanceOf(AssertionError.class);
    }
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain(
        object(List.of(fixture.leaf()), new byte[0]), fixture.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("authenticator data");
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain(
        object(List.of(), fixture.authData()), fixture.roots()))
        .isInstanceOf(AssertionError.class).hasMessageContaining("certificate chain");
  }

  @Test
  void rejectsTrailingCborAndInvalidBase64() throws Exception {
    var fixture = fixture(false, true);
    var bytes = Base64.getDecoder().decode(fixture.object());
    var trailing = Arrays.copyOf(bytes, bytes.length + 1);
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain(
        Base64.getEncoder().encodeToString(trailing), fixture.roots()))
        .isInstanceOf(tools.jackson.core.JacksonException.class);
    assertThatThrownBy(() -> AppleAppAttestation.verifyChain("!", fixture.roots()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Fixture fixture(boolean sameKey, boolean withNonce) throws Exception {
    var client = new ECKeyGenerator(Curve.P_256).generate();
    var authData = new byte[164];
    authData[32] = 0x40;
    var point = new byte[65];
    point[0] = 4;
    System.arraycopy(client.getX().decode(), 0, point, 1, 32);
    System.arraycopy(client.getY().decode(), 0, point, 33, 32);
    var digest = MessageDigest.getInstance("SHA-256");
    var clientHash = digest.digest(point);
    digest.update(authData);
    var nonce = digest.digest(clientHash);
    var rootKey = new ECKeyGenerator(Curve.P_256).generate();
    var rootName = new X500Name("CN=Unit Apple Root " + UUID.randomUUID());
    var attestationKey = sameKey ? client : new ECKeyGenerator(Curve.P_256).generate();
    var leaf = certificate(new X500Name("CN=App Attest"), rootName,
        attestationKey, rootKey, withNonce ? nonce : new byte[0]);
    var root = certificate(rootName, rootName, rootKey, rootKey, null);
    var roots = directory.resolve(UUID.randomUUID() + ".pem");
    Files.writeString(roots, "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(root.getEncoded())
        + "\n-----END CERTIFICATE-----\n");
    return new Fixture(leaf.getEncoded(), root.getEncoded(), roots.toString(), authData,
        client.toPublicJWK().toJSONString());
  }

  private static X509Certificate certificate(X500Name name, X500Name issuer, ECKey key,
      ECKey signer, byte[] nonce) throws Exception {
    var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.ONE,
        Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)),
        name, key.toECPublicKey());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(nonce == null));
    if (nonce != null && nonce.length > 0) {
      builder.addExtension(NONCE, false,
          new DERSequence(new DERTaggedObject(true, 1, new DEROctetString(nonce))));
    }
    return new JcaX509CertificateConverter().getCertificate(
        builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.toECPrivateKey())));
  }

  private static String object(List<byte[]> chain, byte[] authData) throws Exception {
    return Base64.getEncoder().encodeToString(CBOR.writeValueAsBytes(Map.of(
        "fmt", "apple-appattest", "attStmt", Map.of("x5c", chain, "receipt", new byte[] {1}),
        "authData", authData)));
  }

  private record Fixture(byte[] leaf, byte[] root, String roots, byte[] authData, String jwk) {
    String object() throws Exception {
      return AppleAppAttestationTest.object(List.of(leaf), authData);
    }
  }
}
