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

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicFactorStepsTest {
  private final PublicFactorSteps steps = new PublicFactorSteps();

  @Test
  void factorComparisonRejectsWrongPrivateEmptyAndDuplicateKeys() throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    var publicJwks = new JWKSet(key.toPublicJWK()).toString();
    var labelled = new ECKey.Builder(key.toPublicJWK()).keyID("different-metadata").build();
    steps.verifyFactor(new JWKSet(labelled).toString(), publicJwks);
    var foreign = new ECKeyGenerator(Curve.P_256).generate();
    assertThrows(AssertionError.class,
        () -> steps.verifyFactor(new JWKSet(foreign.toPublicJWK()).toString(), publicJwks));
    assertThrows(AssertionError.class,
        () -> steps.verifyFactor(new JWKSet(key).toString(false), publicJwks));
    assertThrows(AssertionError.class, () -> steps.verifyFactor("{\"keys\":[]}", publicJwks));
    assertThrows(AssertionError.class, () -> steps.verifyFactor(
        new JWKSet(List.of(key.toPublicJWK(), labelled)).toString(), publicJwks));
  }

  @Test
  void possessionUsesPersistedKeyRatherThanEmbeddedKeyAndNeedsNoKid() throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    var foreign = new ECKeyGenerator(Curve.P_256).generate();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
        .jwk(key.toPublicJWK()).build(), new JWTClaimsSet.Builder().issuer("client").build());
    jwt.sign(new ECDSASigner(key));
    var publicJwks = new JWKSet(key.toPublicJWK()).toString();
    steps.verifyPossession(jwt.serialize(), publicJwks);
    assertThrows(AssertionError.class, () -> steps.verifyPossession(
        jwt.serialize(), new JWKSet(foreign.toPublicJWK()).toString()));
    var parts = jwt.serialize().split("\\.");
    var modified = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"iss\":\"other\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + "." + parts[2];
    assertThrows(AssertionError.class, () -> steps.verifyPossession(modified, publicJwks));
  }
}
