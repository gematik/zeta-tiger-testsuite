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

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.jose4j.jws.EcdsaUsingShaAlgorithm;

/**
 * Test helper for creating compact ES256 JWTs.
 */
public final class JwtTestHelper {

  private static final String DEFAULT_HEADER_JSON = "{\"alg\":\"ES256\",\"typ\":\"JWT\"}";
  private static final String DEFAULT_PAYLOAD_JSON = "{\"sub\":\"test-subject\"}";

  /**
   * Prevents instantiation of this utility class.
   */
  private JwtTestHelper() {
  }

  /**
   * Creates one compact ES256 JWT with default JOSE header and payload.
   *
   * @param privateKeyPem PEM encoded EC private key
   * @return compact JWT
   * @throws Exception if parsing or signing fails
   */
  public static String createSignedJwt(String privateKeyPem) throws Exception {
    return createSignedJwt(DEFAULT_HEADER_JSON, DEFAULT_PAYLOAD_JSON, loadPrivateKey(privateKeyPem));
  }

  /**
   * Creates one compact ES256 JWT for raw JOSE header and payload text.
   *
   * @param headerJson raw JOSE header text
   * @param payloadJson raw JWT payload text
   * @param privateKey EC private key
   * @return compact signed JWT
   * @throws Exception if signing fails
   */
  public static String createSignedJwt(
      String headerJson, String payloadJson, PrivateKey privateKey) throws Exception {
    var signingInput = base64Url(headerJson) + "." + base64Url(payloadJson);
    var signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign(privateKey);
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    var joseSignature = EcdsaUsingShaAlgorithm.convertDerToConcatenated(signature.sign(), 64);
    return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(joseSignature);
  }

  /**
   * Loads a fixture private key through Bouncy Castle for test JWT creation.
   *
   * @param privateKeyPem PEM encoded private key
   * @return parsed private key
   * @throws IOException if Bouncy Castle cannot parse the key
   */
  private static PrivateKey loadPrivateKey(String privateKeyPem) throws IOException {
    try (var pemParser = new PEMParser(new StringReader(privateKeyPem))) {
      var parsedObject = pemParser.readObject();
      var converter = new JcaPEMKeyConverter();
      if (parsedObject instanceof PEMKeyPair pemKeyPair) {
        return converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
      }
      if (parsedObject instanceof PrivateKeyInfo privateKeyInfo) {
        return converter.getPrivateKey(privateKeyInfo);
      }
      throw new AssertionError("Unsupported private key fixture format.");
    }
  }

  /**
   * Encodes text as unpadded Base64URL.
   *
   * @param value text to encode
   * @return Base64URL encoded text
   */
  private static String base64Url(String value) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
