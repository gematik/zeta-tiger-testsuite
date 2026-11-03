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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.IdpOnlyInventory;
import io.cucumber.java.de.Dann;
import java.util.HashSet;
import java.util.Set;

/** Reads committed public factors and verifies recorded client signatures. */
public class PublicFactorSteps {
  /**
   * Reads the selected client's persisted JWKS.
   *
   * @param client registered client ID
   * @param variable variable receiving the persisted JWKS
   */
  @Dann("lies den gespeicherten F2 für Client {tigerResolvedString} aus dem AS-Bestand in Variable {string}")
  public void readFactor(String client, String variable) {
    var row = new IdpOnlyInventory().readClient(client);
    var jwks = row.path("public_keys").path("jwks.string");
    require(jwks.isString() && !jwks.asString().isBlank(), "Missing committed public F2 JWKS");
    put(variable, jwks.asString());
  }

  /**
   * Compares the public material of persisted and registered key sets.
   *
   * @param persisted persisted JWKS
   * @param registered recorded registration JWKS
   * @throws Exception if key parsing or thumbprint calculation fails
   */
  @Dann("der gespeicherte F2 {tigerResolvedString} ist öffentlich und entspricht dem Registrierungs-JWKS {tigerResolvedString}")
  public void verifyFactor(String persisted, String registered) throws Exception {
    require(fingerprints(persisted).equals(fingerprints(registered)),
        "Committed F2 differs from the actual SDK registration key");
  }

  private static Set<String> fingerprints(String jwks) throws Exception {
    var keys = JWKSet.parse(jwks).getKeys();
    require(!keys.isEmpty(), "Missing public instance key");
    var fingerprints = new HashSet<String>();
    for (JWK key : keys) {
      require(!key.isPrivate(), "F2 contains private key material");
      require(fingerprints.add(key.computeThumbprint("SHA-256").toString()),
          "Ambiguous duplicate public instance key");
    }
    return fingerprints;
  }

  /**
   * Verifies an ES256 assertion against the persisted public key without requiring a kid.
   *
   * @param assertion recorded compact JWS
   * @param persisted persisted public JWKS
   * @throws Exception if JWS or key parsing fails
   */
  @Dann("die Besitzsignatur {tigerResolvedString} ist mit dem gespeicherten F2 {tigerResolvedString} kryptografisch gültig")
  public void verifyPossession(String assertion, String persisted) throws Exception {
    fingerprints(persisted);
    var jwt = SignedJWT.parse(assertion);
    require(JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm()), "Expected ES256 client assertion");
    int verified = 0;
    for (var key : JWKSet.parse(persisted).getKeys()) {
      if (key instanceof ECKey ec && jwt.verify(new ECDSAVerifier(ec.toECPublicKey()))) {
        verified++;
      }
    }
    require(verified == 1, "Possession signature does not verify with exactly one committed F2");
  }

  private static void put(String variable, String value) {
    TigerGlobalConfiguration.putValue(variable, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
