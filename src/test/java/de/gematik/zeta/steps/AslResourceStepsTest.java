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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AslResourceStepsTest {
  @Test
  void acceptsPairedNonPuEnvelopeWithIdenticalCounterAndConnectionKey() {
    assertDoesNotThrow(() -> AslResourceSteps.verifyEnvelope(envelope(1), envelope(2)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"requestVersion", "responseVersion", "requestPu", "responsePu",
      "requestDirection", "responseDirection", "counter", "key", "requestTruncated",
      "responseTruncated", "requestMissing", "responseMissing"})
  void rejectsUnrelatedOrMalformedEnvelope(String mutation) {
    byte[] request = envelope(1);
    byte[] response = envelope(2);
    switch (mutation) {
      case "requestVersion" -> request[0] = 1;
      case "responseVersion" -> response[0] = 1;
      case "requestPu" -> request[1] = 1;
      case "responsePu" -> response[1] = 1;
      case "requestDirection" -> request[2] = 2;
      case "responseDirection" -> response[2] = 1;
      case "counter" -> response[10]++;
      case "key" -> response[42]++;
      case "requestTruncated" -> request = Arrays.copyOf(request, 71);
      case "responseTruncated" -> response = Arrays.copyOf(response, 71);
      case "requestMissing" -> request = null;
      case "responseMissing" -> response = null;
      default -> throw new AssertionError("Unknown mutation");
    }
    var actualRequest = request;
    var actualResponse = response;
    assertThrows(AssertionError.class,
        () -> AslResourceSteps.verifyEnvelope(actualRequest, actualResponse));
  }

  @Test
  void preservesExactUtf8ResponseAtEveryReturnHop() {
    var body = "{\"text\":\"Grüße\"}\n".getBytes(StandardCharsets.UTF_8);
    assertDoesNotThrow(() -> AslResourceSteps.verifyResponseBytes(body,
        List.of(body.clone(), body.clone(), body.clone())));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void detectsChangedResponseAtEachHopIncludingFinalCaller(int hop) {
    var body = "RS content".getBytes(StandardCharsets.UTF_8);
    var received = new ArrayList<>(List.of(body.clone(), body.clone(), body.clone()));
    received.get(hop)[0]++;
    assertThrows(AssertionError.class, () -> AslResourceSteps.verifyResponseBytes(body, received));
  }

  @ParameterizedTest
  @ValueSource(strings = {"originalMissing", "originalEmpty", "noHops", "emptyHop", "missingHop",
      "changedWhitespace", "changedEncoding"})
  void rejectsVacuousOrNonIdenticalResourceResponses(String mutation) {
    var body = "Grüße\n".getBytes(StandardCharsets.UTF_8);
    byte[] original = body.clone();
    List<byte[]> received = new ArrayList<>(List.of(body.clone()));
    switch (mutation) {
      case "originalMissing" -> original = null;
      case "originalEmpty" -> original = new byte[0];
      case "noHops" -> received.clear();
      case "emptyHop" -> received.set(0, new byte[0]);
      case "missingHop" -> received.set(0, null);
      case "changedWhitespace" -> received.set(0, "Grüße".getBytes(StandardCharsets.UTF_8));
      case "changedEncoding" -> received.set(0, "Grüße\n".getBytes(StandardCharsets.ISO_8859_1));
      default -> throw new AssertionError("Unknown mutation");
    }
    var expected = original;
    assertThrows(AssertionError.class, () -> AslResourceSteps.verifyResponseBytes(expected, received));
  }

  @Test
  void acceptsProtocolOrderIncludingEqualTimestampResolution() {
    var now = Instant.now();
    assertDoesNotThrow(() -> AslResourceSteps.requireOrdered(
        List.of(now, now, now.plusNanos(1), now.plusSeconds(1))));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void rejectsReversedHandshakeOrForwardingBoundary(int boundary) {
    var now = Instant.now();
    var times = new ArrayList<Instant>();
    for (int i = 0; i < 6; i++) {
      times.add(now.plusSeconds(i));
    }
    times.set(boundary, times.get(boundary - 1).minusNanos(1));
    assertThrows(AssertionError.class, () -> AslResourceSteps.requireOrdered(times));
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "single", "missing"})
  void rejectsMissingProtocolTiming(String mutation) {
    var times = new ArrayList<Instant>();
    if (!mutation.equals("empty")) {
      times.add(Instant.now());
    }
    if (mutation.equals("missing")) {
      times.add(null);
    }
    assertThrows(AssertionError.class, () -> AslResourceSteps.requireOrdered(times));
  }

  private static byte[] envelope(int direction) {
    var bytes = new byte[100];
    bytes[0] = 2;
    bytes[2] = (byte) direction;
    bytes[10] = 1;
    Arrays.fill(bytes, 11, 43, (byte) 7);
    return bytes;
  }

  @Test
  void acceptsCaseInsensitiveHttpAuthenticationSchemeButRequiresToken() {
    assertTrue(AslResourceSteps.isDpopAuthorization("DPoP token"));
    assertTrue(AslResourceSteps.isDpopAuthorization("dpop token"));
    assertFalse(AslResourceSteps.isDpopAuthorization("Bearer token"));
    assertFalse(AslResourceSteps.isDpopAuthorization("DPoP "));
    assertFalse(AslResourceSteps.isDpopAuthorization(null));
  }

  /** Rejects foreign credentials for both the resource GET and the outer ASL POST. */
  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST"})
  void rejectsValidlySignedProofsForForeignTokensTargetsKeysOrUsers(String method) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    var now = Instant.now();
    var target = "https://resource.example"
        + (method.equals("GET") ? "/pep/resource" : "/ASL/connection");
    var token =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.ES256),
            new JWTClaimsSet.Builder()
                .subject("X123456789")
                .claim("client_id", "client")
                .claim("cnf", Map.of("jkt", key.computeThumbprint().toString()))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build());
    token.sign(new ECDSASigner(key));
    var access = token.serialize();
    var ath =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(access.getBytes(StandardCharsets.US_ASCII)));
    for (String mutation :
        new String[] {"valid", "target", "method", "ath", "key", "stale", "identity"}) {
      var signingKey = mutation.equals("key") ? new ECKeyGenerator(Curve.P_256).generate() : key;
      var proof =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256)
                  .type(new JOSEObjectType("dpop+jwt"))
                  .jwk(signingKey.toPublicJWK())
                  .build(),
              new JWTClaimsSet.Builder()
                  .jwtID("proof-id")
                  .issueTime(Date.from(now.minusSeconds(mutation.equals("stale") ? 120 : 0)))
                  .claim("htm", mutation.equals("method") ? (method.equals("GET") ? "POST" : "GET") : method)
                  .claim("htu", mutation.equals("target") ? target + "/foreign" : target)
                  .claim("ath", mutation.equals("ath") ? "foreign" : ath)
                  .build());
      proof.sign(new ECDSASigner(signingKey));
      org.junit.jupiter.api.function.Executable check =
          () ->
              AslResourceSteps.verifyProof(
                  proof.serialize(),
                  access,
                  mutation.equals("identity") ? "Y123456789" : "X123456789",
                  target,
                  method,
                  now);
      if (mutation.equals("valid")) {
        assertDoesNotThrow(check);
      } else {
        assertThrows(AssertionError.class, check, mutation);
      }
    }
  }
}
