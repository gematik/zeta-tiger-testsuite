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
import java.security.Signature;
import java.util.Base64;

/** Encodes EC client keys and verifies detached ECDSA possession signatures. */
public final class ClientKeyProof {
  private ClientKeyProof() {}

  /**
   * Verifies a DER ECDSA signature over the SHA-256 hash of the uncompressed client key.
   *
   * @param clientJwk public client instance JWK
   * @param encodedSignature Base64url DER ECDSA signature
   * @throws Exception if the key, encoding or signature is invalid
   */
  public static void verifyPossession(String clientJwk, String encodedSignature) throws Exception {
    var client = ECKey.parse(clientJwk);
    var verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(client.toECPublicKey());
    verifier.update(publicKeyPoint(client));
    if (!verifier.verify(Base64.getUrlDecoder().decode(encodedSignature))) {
      throw new AssertionError("Client possession signature does not verify with the registered public key");
    }
  }

  /**
   * Encodes an EC public key as its fixed-width X9.62 uncompressed point.
   *
   * @param key public EC key
   * @return prefix 0x04 followed by the unsigned X and Y coordinates
   * @throws Exception if the curve or coordinate lengths are invalid
   */
  public static byte[] publicKeyPoint(ECKey key) throws Exception {
    int width = (key.toECPublicKey().getParams().getCurve().getField().getFieldSize() + 7) / 8;
    var x = key.getX().decode();
    var y = key.getY().decode();
    if (x.length != width || y.length != width) {
      throw new AssertionError("Invalid EC public-key coordinate length");
    }
    var point = new byte[1 + 2 * width];
    point[0] = 4;
    System.arraycopy(x, 0, point, 1, width);
    System.arraycopy(y, 0, point, 1 + width, width);
    return point;
  }
}
