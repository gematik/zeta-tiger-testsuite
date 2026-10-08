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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import io.cucumber.java.de.Und;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jose4j.jwe.JsonWebEncryption;
import tools.jackson.databind.ObjectMapper;

/**
 * Extracts claims from encrypted OIDC tokens that were parsed before their dynamic key was known.
 */
public class OidcTokenSteps {

  private static final ObjectMapper JSON = new ObjectMapper();

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
