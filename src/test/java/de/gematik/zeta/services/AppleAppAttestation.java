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

import com.nimbusds.jose.jwk.ECKey;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.cbor.CBORMapper;

/** Decodes Apple App Attest evidence and verifies certificate trust and client-key binding. */
public final class AppleAppAttestation {
  private static final String NONCE_OID = "1.2.840.113635.100.8.2";
  private static final CBORMapper CBOR = CBORMapper.builder()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private AppleAppAttestation() {}

  /**
   * Validates the credential certificate chain against independent PEM trust anchors.
   *
   * @param encodedObject Base64 CBOR App Attest object
   * @param rootsFile PEM file containing trusted Apple App Attest roots
   * @throws Exception if the object, certificates or certificate path are invalid
   */
  public static void verifyChain(String encodedObject, String rootsFile) throws Exception {
    var certificates = certificates(attestation(encodedObject));
    var factory = CertificateFactory.getInstance("X.509");
    Set<TrustAnchor> anchors = new HashSet<>();
    try (var input = Files.newInputStream(Path.of(rootsFile))) {
      for (var certificate : factory.generateCertificates(input)) {
        anchors.add(new TrustAnchor((X509Certificate) certificate, null));
      }
    }
    require(!anchors.isEmpty(), "No Apple App Attest trust anchors configured");
    var last = certificates.getLast();
    if (anchors.stream().anyMatch(anchor -> anchor.getTrustedCert().equals(last))) {
      certificates.removeLast();
    }
    require(!certificates.isEmpty(), "Missing Apple credential certificate");
    var parameters = new PKIXParameters(anchors);
    parameters.setRevocationEnabled(false);
    CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(certificates), parameters);
  }

  /**
   * Checks the credential certificate nonce against authData and the client public-key hash.
   *
   * @param encodedObject Base64 CBOR App Attest object
   * @param clientJwk public client instance JWK
   * @throws Exception if the nonce extension or client-key binding is invalid
   */
  public static void verifyClientKeyBinding(String encodedObject, String clientJwk) throws Exception {
    var object = attestation(encodedObject);
    var leaf = certificates(object).getFirst();
    var client = ECKey.parse(clientJwk);
    require(!Arrays.equals(leaf.getPublicKey().getEncoded(), client.toECPublicKey().getEncoded()),
        "Apple attestation key must differ from the client instance key");
    var extension = leaf.getExtensionValue(NONCE_OID);
    require(extension != null, "Missing Apple App Attest nonce extension");
    var sequence = ASN1Sequence.getInstance(ASN1OctetString.getInstance(extension).getOctets());
    require(sequence.size() == 1, "Invalid Apple App Attest nonce extension");
    var tagged = ASN1TaggedObject.getInstance(sequence.getObjectAt(0));
    require(tagged.hasContextTag(1) && tagged.isExplicit(), "Invalid Apple App Attest nonce tag");
    var nonce = ASN1OctetString.getInstance(tagged.getExplicitBaseObject()).getOctets();
    var digest = MessageDigest.getInstance("SHA-256");
    var clientDataHash = digest.digest(ClientKeyProof.publicKeyPoint(client));
    digest.update(object.get("authData").binaryValue());
    var expected = digest.digest(clientDataHash);
    require(MessageDigest.isEqual(nonce, expected),
        "Apple App Attest nonce differs from SHA-256(authData || SHA-256(client public key))");
  }

  private static JsonNode attestation(String encodedObject) throws Exception {
    var object = CBOR.readTree(Base64.getDecoder().decode(encodedObject));
    require(object != null && object.isObject(), "Expected an Apple App Attest CBOR object");
    require("apple-appattest".equals(object.path("fmt").asString()), "Invalid Apple attestation format");
    require(object.path("authData").isBinary() && object.get("authData").binaryValue().length >= 37,
        "Missing or truncated Apple authenticator data");
    require(object.path("attStmt").path("receipt").isBinary()
        && object.get("attStmt").get("receipt").binaryValue().length > 0,
        "Missing Apple App Attest receipt");
    return object;
  }

  private static List<X509Certificate> certificates(JsonNode object) throws Exception {
    var values = object.path("attStmt").path("x5c");
    require(values.isArray() && !values.isEmpty(), "Missing Apple App Attest certificate chain");
    var result = new ArrayList<X509Certificate>();
    var factory = CertificateFactory.getInstance("X.509");
    for (var value : values) {
      require(value.isBinary(), "Expected DER certificate bytes in Apple x5c");
      try (var input = new ByteArrayInputStream(value.binaryValue())) {
        var certificate = (X509Certificate) factory.generateCertificate(input);
        require(input.available() == 0, "Trailing bytes in Apple attestation certificate");
        require(!result.contains(certificate), "Duplicate Apple attestation certificate");
        result.add(certificate);
      }
    }
    return result;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
