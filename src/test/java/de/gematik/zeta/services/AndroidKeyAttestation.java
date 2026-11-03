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
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import tools.jackson.databind.ObjectMapper;

/** Parses Android key-attestation certificates and verifies their client-key binding. */
public final class AndroidKeyAttestation {
  private static final String KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17";
  private static final ObjectMapper JSON = new ObjectMapper();

  private AndroidKeyAttestation() {}

  /**
   * Validates an ordered certificate chain against PEM trust anchors and checks hardware levels.
   *
   * @param chainJson JSON array of Base64 DER certificates, leaf first
   * @param rootsFile PEM file containing independent trusted root certificates
   * @throws Exception if certificates, anchors or the hardware attestation are invalid
   */
  public static void verifyHardwareChain(String chainJson, String rootsFile) throws Exception {
    var certificates = certificates(chainJson);
    var factory = CertificateFactory.getInstance("X.509");
    Set<TrustAnchor> anchors = new HashSet<>();
    try (var input = Files.newInputStream(Path.of(rootsFile))) {
      for (var certificate : factory.generateCertificates(input)) {
        anchors.add(new TrustAnchor((X509Certificate) certificate, null));
      }
    }
    require(!anchors.isEmpty(), "No Android attestation trust anchors configured");
    var root = certificates.getLast();
    require(anchors.stream().anyMatch(anchor -> anchor.getTrustedCert().equals(root)),
        "Android attestation does not end at a configured Google root");
    var parameters = new PKIXParameters(anchors);
    parameters.setRevocationEnabled(false);
    var path = factory.generateCertPath(certificates.subList(0, certificates.size() - 1));
    CertPathValidator.getInstance("PKIX").validate(path, parameters);
    var description = keyDescription(certificates);
    require(hardwareLevel(description, 1) && hardwareLevel(description, 3),
        "Android attestation and keymaster must report TEE or StrongBox");
  }

  /**
   * Compares the attestation challenge with the client public-key hash and requires a separate AK.
   *
   * @param chainJson JSON array of Base64 DER attestation certificates, leaf first
   * @param clientJwk client instance public JWK
   * @throws Exception if the challenge or key binding is invalid
   */
  public static void verifyClientKeyBinding(String chainJson, String clientJwk) throws Exception {
    var certificates = certificates(chainJson);
    var client = ECKey.parse(clientJwk);
    var description = keyDescription(certificates);
    var challenge = ASN1OctetString.getInstance(description.getObjectAt(4)).getOctets();
    var expected = MessageDigest.getInstance("SHA-256").digest(ClientKeyProof.publicKeyPoint(client));
    require(MessageDigest.isEqual(challenge, expected),
        "Android attestation challenge differs from SHA-256 of the client public key");
    require(!Arrays.equals(certificates.getFirst().getPublicKey().getEncoded(),
            client.toECPublicKey().getEncoded()),
        "Android attestation key must differ from the client instance key");
  }

  private static List<X509Certificate> certificates(String json) throws Exception {
    var values = JSON.readTree(json);
    require(values.isArray() && values.size() >= 2, "Expected an Android certificate chain");
    var result = new ArrayList<X509Certificate>();
    var factory = CertificateFactory.getInstance("X.509");
    for (var value : values) {
      require(value.isString() && !value.asString().isBlank(), "Expected Base64 DER certificate");
      var bytes = Base64.getDecoder().decode(value.asString());
      try (var input = new ByteArrayInputStream(bytes)) {
        var certificate = (X509Certificate) factory.generateCertificate(input);
        require(input.available() == 0, "Trailing bytes in Android attestation certificate");
        require(!result.contains(certificate), "Duplicate Android attestation certificate");
        result.add(certificate);
      }
    }
    return result;
  }

  private static ASN1Sequence keyDescription(List<X509Certificate> certificates) throws Exception {
    for (int index = certificates.size() - 2; index >= 0; index--) {
      var extension = certificates.get(index).getExtensionValue(KEY_DESCRIPTION_OID);
      if (extension != null) {
        require(index == 0, "Attestation extension does not describe the presented leaf key");
        var bytes = ASN1OctetString.getInstance(extension).getOctets();
        var description = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(bytes));
        require(description.size() == 8, "Invalid Android KeyDescription");
        return description;
      }
    }
    throw new AssertionError("Missing Android key attestation extension");
  }

  private static boolean hardwareLevel(ASN1Sequence description, int index) {
    int value = ASN1Enumerated.getInstance(description.getObjectAt(index)).intValueExact();
    return value == 1 || value == 2;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
