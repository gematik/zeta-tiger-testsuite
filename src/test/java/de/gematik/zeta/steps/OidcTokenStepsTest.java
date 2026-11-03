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

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDHEncrypter;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

/** Authenticated decryption retains the exact original JWS without disclosing parser context. */
class OidcTokenStepsTest {
  @Test
  void retainsOriginalSignedBytes() throws Exception {
    var recipient = new ECKeyGenerator(Curve.P_256).generate();
    var signed = signed();
    var encrypted = encrypt(signed, recipient, EncryptionMethod.A256GCM);
    assertEquals(signed, OidcTokenSteps.decryptSignedToken(encrypted, recipient.toECPrivateKey()));
  }

  @Test
  void wrongRecipientAndTamperedCiphertextFail() throws Exception {
    var recipient = new ECKeyGenerator(Curve.P_256).generate();
    var other = new ECKeyGenerator(Curve.P_256).generate().toECPrivateKey();
    var encrypted = encrypt(signed(), recipient, EncryptionMethod.A256GCM);
    assertThrows(AssertionError.class, () -> OidcTokenSteps.decryptSignedToken(encrypted, other));
    var parts = encrypted.split("\\.", -1);
    parts[3] = (parts[3].startsWith("A") ? "B" : "A") + parts[3].substring(1);
    var tampered = String.join(".", parts);
    var key = recipient.toECPrivateKey();
    assertThrows(AssertionError.class, () -> OidcTokenSteps.decryptSignedToken(tampered, key));
  }

  @Test
  void profileAndNestedSignedTokenAreRequired() throws Exception {
    var recipient = new ECKeyGenerator(Curve.P_256).generate();
    var key = recipient.toECPrivateKey();
    var wrongProfile = encrypt(signed(), recipient, EncryptionMethod.A128GCM);
    var unsigned = encrypt("plain unsigned claims", recipient, EncryptionMethod.A256GCM);
    assertThrows(AssertionError.class, () -> OidcTokenSteps.decryptSignedToken(wrongProfile, key));
    assertThrows(AssertionError.class, () -> OidcTokenSteps.decryptSignedToken(unsigned, key));
    var error = assertThrows(AssertionError.class, () -> OidcTokenSteps.decryptSignedToken("secret-invalid-input", key));
    assertEquals("Cannot decrypt the original signed ID token using the configured ECDH-ES/A256GCM profile", error.getMessage());
  }

  private static String signed() throws Exception {
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), new JWTClaimsSet.Builder().claim("acr", "example").build());
    jwt.sign(new ECDSASigner(new ECKeyGenerator(Curve.P_256).generate()));
    return jwt.serialize();
  }

  private static String encrypt(String value, ECKey recipient, EncryptionMethod method) throws Exception {
    var jwe = new JWEObject(new JWEHeader.Builder(JWEAlgorithm.ECDH_ES, method).contentType("JWT").build(), new Payload(value));
    jwe.encrypt(new ECDHEncrypter(recipient.toECPublicKey()));
    return jwe.serialize();
  }
}
