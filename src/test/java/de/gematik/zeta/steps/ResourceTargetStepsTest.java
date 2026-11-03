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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceTargetStepsTest {
  private static final String TARGET = "https://pep.example/resource";
  private static final String WRONG = TARGET + "/foreign";
  private static final Instant NOW = Instant.now();

  @Test
  void permitsRotatedTokensButReportsEveryWrongResponseIncludingEarlierRetries() throws Exception {
    var f = new Fixture();
    var rotated = f.token("jti", "rotated");
    f.verify(List.of(f.observation(f.control, "one", 403), f.observation(rotated, "two", 403)));
    var failure = assertThrows(AssertionError.class, () -> f.verify(List.of(
        f.observation(f.control, "one", 200), f.observation(rotated, "two", 401))));
    assertTrue(failure.getMessage().contains("request 1 HTTP status"));
    assertTrue(failure.getMessage().contains("request 2 HTTP status"));
  }

  @Test
  void independentClaimChangesAndProofReplaysCannotProveAnIsolatedTargetFailure() throws Exception {
    var f = new Fixture();
    for (var claim : List.of("iss", "sub", "client_id", "sid", "aud", "scope", "acr")) {
      var changed = f.token(claim, claim.equals("aud") ? List.of("foreign") : "foreign");
      assertThrows(AssertionError.class, () -> f.verify(List.of(f.observation(changed, "one", 403))), claim);
    }
    var first = f.observation(f.control, "replayed", 403);
    assertThrows(AssertionError.class, () -> f.verify(List.of(first, first)));
  }

  @Test
  void missingEvidenceUnchangedTargetAndInvalidAccessSignatureCannotPass() throws Exception {
    var f = new Fixture();
    assertThrows(AssertionError.class, () -> f.verify(List.of()));
    var request = f.observation(f.control, "one", 403);
    assertThrows(AssertionError.class,
        () -> ResourceTargetSteps.verify(List.of(request), TARGET, TARGET, f.control, f.jwks));
    var parts = f.control.split("\\.");
    var signature = Base64URL.from(parts[2]).decode();
    signature[0] ^= 1;
    var corrupted = parts[0] + "." + parts[1] + "." + Base64URL.encode(signature);
    assertThrows(AssertionError.class,
        () -> f.verify(List.of(f.observation(corrupted, "one", 403))));
  }

  private static final class Fixture {
    private final ECKey key = new ECKeyGenerator(Curve.P_256).keyID("as-key").generate();
    private final String jwks = new JWKSet(key.toPublicJWK()).toString();
    private final String control = token("jti", "original");

    Fixture() throws Exception {}

    String token(String claim, Object value) throws Exception {
      var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("as-key").build(),
          new JWTClaimsSet.Builder().issuer("https://as.example").subject("test-user")
              .audience("fachdienst").claim("client_id", "client").claim("sid", "session")
              .claim("scope", "read").claim("acr", "high")
              .claim("cnf", Map.of("jkt", key.computeThumbprint().toString()))
              .expirationTime(Date.from(NOW.plusSeconds(300))).claim(claim, value).build());
      token.sign(new ECDSASigner(key));
      return token.serialize();
    }

    ResourceTargetSteps.Observation observation(String access, String id, int status) throws Exception {
      var hash = Base64URL.encode(MessageDigest.getInstance("SHA-256")
          .digest(access.getBytes(StandardCharsets.US_ASCII))).toString();
      var proof = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
          .type(new JOSEObjectType("dpop+jwt")).jwk(key.toPublicJWK()).build(),
          new JWTClaimsSet.Builder().jwtID(id).issueTime(Date.from(NOW))
              .claim("htm", "GET").claim("htu", WRONG).claim("ath", hash).build());
      proof.sign(new ECDSASigner(key));
      return new ResourceTargetSteps.Observation(access, proof.serialize(), status, NOW);
    }

    void verify(List<ResourceTargetSteps.Observation> requests) throws Exception {
      ResourceTargetSteps.verify(requests, TARGET, WRONG, control, jwks);
    }
  }
}
