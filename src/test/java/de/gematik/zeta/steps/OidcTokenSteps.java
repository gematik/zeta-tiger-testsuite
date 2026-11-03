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

import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers;
import org.jose4j.jwe.JsonWebEncryption;
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers;
import tools.jackson.databind.ObjectMapper;

/**
 * Extracts claims from encrypted OIDC tokens that were parsed before their dynamic key was known.
 */
public class OidcTokenSteps {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Retains the original nested JWS for independent signature and type-preserving claim checks.
   *
   * @param jwePath path in the currently selected IDP response
   * @param variableName variable receiving the unmodified compact JWS
   */
  @Dann("entschlüssle das ID Token {tigerResolvedString} der aktuellen Antwort als signiertes JWT in Variable {string}")
  public void storeSignedIdToken(String jwePath, String variableName) {
    var compact = RbelMessageRetriever.getInstance().findElementInCurrentResponse(jwePath).getRawStringContent();
    TigerGlobalConfiguration.putValue(variableName,
        decryptSignedToken(compact, OidcDecryptionKeyPreflight.currentKey()), ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  static String decryptSignedToken(String compact, Key key) {
    try {
      var jwe = new JsonWebEncryption();
      jwe.setAlgorithmConstraints(new AlgorithmConstraints(ConstraintType.PERMIT, KeyManagementAlgorithmIdentifiers.ECDH_ES));
      jwe.setContentEncryptionAlgorithmConstraints(new AlgorithmConstraints(ConstraintType.PERMIT, ContentEncryptionAlgorithmIdentifiers.AES_256_GCM));
      jwe.setCompactSerialization(compact);
      jwe.setKey(key);
      var signed = jwe.getPayload();
      SignedJWT.parse(signed);
      return signed;
    } catch (Exception e) {
      throw new AssertionError("Cannot decrypt the original signed ID token using the configured ECDH-ES/A256GCM profile");
    }
  }

  /**
   * Decrypts the JWE selected from the current response and stores one nested JWT claim.
   *
   * <p>The compact token is read from the already selected response so the claim remains bound to
   * the same protocol message used by the surrounding assertions.</p>
   *
   * @param jwePath RBEL path of the compact JWE in the current response
   * @param claimName exact claim name in the nested JWT payload
   * @param variableName Tiger variable receiving the scalar claim value
   */
  @Und("entschlüssle JWE {tigerResolvedString} der aktuellen Antwort und speichere Claim {tigerResolvedString} in Variable {string}")
  public void storeDecryptedClaim(
      final String jwePath, final String claimName, final String variableName) {
    var compactJwe = RbelMessageRetriever.getInstance()
        .findElementInCurrentResponse(jwePath)
        .getRawStringContent();
    try {
      var jwe = new JsonWebEncryption();
      jwe.setCompactSerialization(compactJwe);
      jwe.setKey(OidcDecryptionKeyPreflight.currentKey());
      var jwtParts = jwe.getPayload().split("\\.", -1);
      if (jwtParts.length != 3) {
        throw new AssertionError("Decrypted OIDC id_token is not a compact signed JWT.");
      }
      var claims = JSON.readTree(new String(
          Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8));
      var claim = claims.get(claimName);
      if (claim == null || !claim.isValueNode()) {
        throw new AssertionError("OIDC id_token has no scalar claim '" + claimName + "'.");
      }
      TigerGlobalConfiguration.putValue(
          variableName, claim.asString(), ConfigurationValuePrecedence.TEST_CONTEXT);
    } catch (Exception e) {
      throw new AssertionError("Could not decrypt the OIDC id_token claim '" + claimName + "'.", e);
    }
  }
}
