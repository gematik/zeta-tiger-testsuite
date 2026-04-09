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

import de.gematik.zeta.steps.TlsTestToolSteps.TlsServerCertificates;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the TLS test tool server certificate fixtures match the mapped private key files.
 */
class TlsTestToolFixtureCompatibilityTest {

  private static final Path CERTIFICATE_DIRECTORY =
      Path.of("tools", "tls-test-tool-1.0.1", "certificates");

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

  private PublicKey readCertificatePublicKey(TlsServerCertificates certificate)
      throws IOException, GeneralSecurityException {
    try (var inputStream = Files.newInputStream(CERTIFICATE_DIRECTORY.resolve(certificate.getRelativePath()))) {
      return ((X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(inputStream))
          .getPublicKey();
    }
  }

  private PublicKey readPrivateKeyPublicKey(TlsServerCertificates privateKey)
      throws IOException {
    try (var pemParser =
        new PEMParser(
            new StringReader(
                Files.readString(CERTIFICATE_DIRECTORY.resolve(privateKey.getRelativePath()))))) {
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
          "Unsupported TLS test tool private key fixture: " + Arrays.toString(new Object[] {pemObject}));
    }
  }
}
