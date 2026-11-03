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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionRefreshContractStepsTest {
  private static final Instant NOW = Instant.ofEpochSecond(1800000000);
  private static final String ENDPOINT = "https://as.example/token";
  private static final String ERROR = "{\"error\":\"session_terminated\"}";

  @AfterEach
  void clearSchemaFailures() {
    SoftAssertionsContext.reset();
  }

  @Test
  void acceptsIndependentFreshProofsAndPreservesControlParameters() throws Exception {
    var f = new Fixture();
    SessionRefreshContractSteps.verifyRequests(List.of(f.negative()), f.reference(), ENDPOINT);
    assertDoesNotThrow(() -> SessionRefreshContractSteps.verifyResponses(List.of(f.negative())));
  }

  @Test
  void foreignClientAndWrongRegistrationKeyCannotExplainRejection() throws Exception {
    var f = new Fixture();
    f.client = "foreign";
    assertThrows(AssertionError.class, () -> f.verify());
    var other = new Fixture();
    other.key = new ECKeyGenerator(Curve.P_256).generate();
    assertThrows(AssertionError.class, () -> other.verify());
  }

  @Test
  void wrongDpopKeyTargetMethodOrNonceCannotPass() throws Exception {
    for (var fault : List.of("key", "target", "method", "nonce")) {
      var f = new Fixture();
      switch (fault) {
        case "key" -> f.dpopKey = new ECKeyGenerator(Curve.P_256).generate();
        case "target" -> f.target = ENDPOINT + "/foreign";
        case "method" -> f.method = "GET";
        case "nonce" -> f.proofNonce = "another-nonce";
        default -> throw new AssertionError();
      }
      assertThrows(AssertionError.class, () -> f.verify(), fault);
    }
  }

  @Test
  void signedIndependentScopeOrAudienceChangesCannotPass() throws Exception {
    for (var field : List.of("scope", "audience", "resource", "unexpected")) {
      var f = new Fixture();
      f.parameters.put(field, "different");
      assertThrows(AssertionError.class, () -> f.verify(), field);
    }
  }

  @Test
  void alteredOrConsumedRefreshCannotPass() throws Exception {
    var f = new Fixture();
    f.refresh = f.initialRefresh;
    assertThrows(AssertionError.class, () -> f.verify());
    var foreign = new Fixture();
    foreign.refresh = foreign.token("other-session", NOW.plusSeconds(300), "other-rt");
    assertThrows(AssertionError.class, () -> foreign.verify());
  }

  @Test
  void expiredRefreshAssertionAndStaleProofAreRejected() throws Exception {
    var f = new Fixture();
    var expired = f.negative();
    var later = new SessionRefreshContractSteps.Observation(expired.request(), expired.proof(),
        ERROR, 403, NOW.plusSeconds(400), expired.nonce());
    assertThrows(AssertionError.class, () -> SessionRefreshContractSteps.verifyRequests(
        List.of(later), f.reference(), ENDPOINT));
    f.assertionExpires = NOW.minusSeconds(1);
    assertThrows(AssertionError.class, () -> f.verify());
    var stale = new Fixture();
    stale.proofTime = NOW.minusSeconds(90);
    assertThrows(AssertionError.class, () -> stale.verify());
  }

  @Test
  void assertionAndDpopSignaturesMustBeCryptographicallyValid() throws Exception {
    for (var field : List.of("assertion", "dpop")) {
      var f = new Fixture();
      var original = f.negative();
      var form = new HashMap<>(SmbExchangeSteps.form(original.request()));
      var proof = original.proof();
      if (field.equals("assertion")) {
        form.put("client_assertion", corrupt(form.get("client_assertion")));
      } else {
        proof = corrupt(proof);
      }
      var altered = new SessionRefreshContractSteps.Observation(encode(form), proof, ERROR,
          403, original.sent(), original.nonce());
      assertThrows(AssertionError.class, () -> SessionRefreshContractSteps.verifyRequests(
          List.of(altered), f.reference(), ENDPOINT), field);
    }
  }

  @Test
  void missingHistoryAndProofReplayCannotPass() throws Exception {
    var f = new Fixture();
    assertThrows(AssertionError.class, () -> SessionRefreshContractSteps.verifyRequests(
        List.of(), f.reference(), ENDPOINT));
    var same = f.negative();
    assertThrows(AssertionError.class, () -> SessionRefreshContractSteps.verifyRequests(
        List.of(same, same), f.reference(), ENDPOINT));
    assertThrows(AssertionError.class, () -> SessionRefreshContractSteps.verifyResponses(null));
  }

  @Test
  void anEarlyWrongResponseCannotBeHiddenByALaterCorrectRetry() throws Exception {
    var f = new Fixture();
    var n = f.negative();
    var wrong = new SessionRefreshContractSteps.Observation(n.request(), n.proof(),
        "{\"error\":\"invalid_grant\"}", 400, n.sent(), n.nonce());
    var failure = assertThrows(AssertionError.class, () -> SessionRefreshContractSteps.verifyResponses(
        List.of(wrong, n)));
    assertTrue(failure.getMessage().contains("HTTP status"));
    assertTrue(failure.getMessage().contains("OAuth error"));
  }

  @Test
  void everyTokenFieldAndErrorSchemaAreChecked() throws Exception {
    var f = new Fixture();
    var n = f.negative();
    for (var field : List.of("access_token", "refresh_token", "id_token", "zeta_attestation_token")) {
      var body = "{\"error\":\"session_terminated\",\"" + field + "\":null}";
      assertThrows(AssertionError.class, () -> SessionRefreshContractSteps.verifyResponses(List.of(
          new SessionRefreshContractSteps.Observation(n.request(), n.proof(), body, 403,
              n.sent(), n.nonce()))));
      SoftAssertionsContext.reset();
    }
    assertThrows(AssertionError.class, () -> {
      SessionRefreshContractSteps.verifyResponses(List.of(new SessionRefreshContractSteps.Observation(
          n.request(), n.proof(), "{\"error\":\"session_terminated\",\"error_description\":42}",
          403, n.sent(), n.nonce())));
      SoftAssertionsContext.assertAll();
    });
  }

  @Test
  void staleOrChangedSoftwareAttestationCannotPass() throws Exception {
    var f = new Fixture();
    f.posture.put("product_version", "changed");
    assertThrows(AssertionError.class, () -> f.verify());
    var challenge = new Fixture();
    challenge.posture.put("attestation_challenge", "wrong");
    assertThrows(AssertionError.class, () -> challenge.verify());
    var time = new Fixture();
    time.attestationTime = NOW.minusSeconds(90);
    assertThrows(AssertionError.class, () -> time.verify());
  }

  private static String corrupt(String jwt) {
    var parts = jwt.split("\\.");
    var bytes = Base64URL.from(parts[2]).decode();
    bytes[0] ^= 1;
    return parts[0] + "." + parts[1] + "." + Base64URL.encode(bytes);
  }

  private static String encode(Map<String, String> form) {
    return form.entrySet().stream().map(e -> e.getKey() + "="
        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
  }

  private static class Fixture {
    ECKey key = new ECKeyGenerator(Curve.P_256).generate();
    ECKey dpopKey = new ECKeyGenerator(Curve.P_256).generate();
    String client = "client";
    String target = ENDPOINT;
    String method = "POST";
    String proofNonce = "dGVzdC1ub25jZQ";
    Instant proofTime = NOW;
    Instant assertionExpires = NOW.plusSeconds(60);
    Instant attestationTime = NOW;
    Map<String, Object> posture = new HashMap<>();
    Map<String, String> parameters = new HashMap<>(Map.of("grant_type", "refresh_token",
        "client_id", "client", "scope", "read", "audience", "https://resource.example",
        "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"));
    String initialRefresh = token("session", NOW.plusSeconds(300), "initial-rt");
    String refresh = token("session", NOW.plusSeconds(300), "next-rt");
    SessionRefreshContractSteps.Reference saved;

    Fixture() throws Exception {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(key.computeThumbprint().decode());
      posture.put("attestation_challenge",
          Base64URL.encode(digest.digest(Base64URL.from(proofNonce).decode())).toString());
      posture.put("product_version", "1.0");
      var control = observation(initialRefresh, NOW.minusSeconds(1));
      saved = new SessionRefreshContractSteps.Reference(control, new JWKSet(key.toPublicJWK()).toString(),
          token("session", NOW.plusSeconds(300), "access"), refresh);
    }

    String token(String session, Instant expiry, String id) throws Exception {
      return sign(new JWTClaimsSet.Builder().issuer("https://as.example").subject("A123456789")
          .claim("client_id", client).claim("sid", session).jwtID(id)
          .claim("cnf", Map.of("jkt", dpopKey.computeThumbprint().toString()))
          .expirationTime(Date.from(expiry)).build(), key, "JWT");
    }

    SessionRefreshContractSteps.Reference reference() {
      return saved;
    }

    SessionRefreshContractSteps.Observation negative() throws Exception {
      return observation(refresh, NOW);
    }

    SessionRefreshContractSteps.Observation observation(String rt, Instant sent) throws Exception {
      var assertion = sign(new JWTClaimsSet.Builder().issuer(client).subject(client).audience(ENDPOINT)
          .expirationTime(Date.from(assertionExpires)).jwtID(UUID.randomUUID().toString())
          .claim("client_statement", Map.of("sub", client, "platform", "linux", "posture_type", "software",
              "posture", posture, "attestation_timestamp", attestationTime.getEpochSecond()))
          .build(), key, "JWT");
      var proof = sign(new JWTClaimsSet.Builder().issueTime(Date.from(proofTime))
          .jwtID(UUID.randomUUID().toString()).claim("htm", method).claim("htu", target)
          .claim("nonce", proofNonce).build(), dpopKey, "dpop+jwt");
      var form = new HashMap<>(parameters);
      form.put("client_assertion", assertion);
      form.put("refresh_token", rt);
      return new SessionRefreshContractSteps.Observation(encode(form), proof, ERROR, 403, sent,
          "dGVzdC1ub25jZQ");
    }

    void verify() throws Exception {
      SessionRefreshContractSteps.verifyRequests(List.of(negative()), reference(), ENDPOINT);
    }

    private static String sign(JWTClaimsSet claims, ECKey key, String type) throws Exception {
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
          .jwk(key.toPublicJWK()).type(new JOSEObjectType(type)).build(), claims);
      jwt.sign(new ECDSASigner(key));
      return jwt.serialize();
    }
  }
}
