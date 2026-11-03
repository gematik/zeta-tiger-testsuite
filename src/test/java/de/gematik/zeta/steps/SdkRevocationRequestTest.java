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
import de.gematik.rbellogger.RbelConverter;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SdkRevocationRequestTest {
  private static final Instant NOW = Instant.ofEpochSecond(1800000000);
  private static final URI REVOKE = URI.create("https://guard.example/revoke");
  private static final URI TOKEN = URI.create("https://guard.example/token");

  @ParameterizedTest
  @ValueSource(strings = {"missing", "expired", "wrong_client", "wrong_signature"})
  void acceptsOnlyTheDeclaredProxyFaultWithValidIndependentDpop(String fault) throws Exception {
    var f = new Fixture();
    switch (fault) {
      case "expired" ->
          f.assertionClaims
              .issueTime(Date.from(NOW.minusSeconds(330)))
              .expirationTime(Date.from(NOW.minusSeconds(300)));
      case "wrong_client" -> f.assertionClaims.issuer("foreign").subject("foreign");
      case "wrong_signature" -> f.corruptAssertion = true;
      default -> {}
    }
    var observed = f.observation();
    if (fault.equals("missing")) {
      var body = observed.body().replaceAll("(^|&)client_assertion=[^&]*(&|$)", "$1");
      observed =
          new SdkRevocationRequest.Observation(
              observed.method(),
              observed.target(),
              observed.host(),
              observed.contentType(),
              body,
              observed.proof(),
              observed.sent());
    }
    var reference =
        new SdkRevocationRequest.Reference(
            f.access,
            f.refresh,
            "client",
            f.jwks,
            "initial-assertion",
            "initial-dpop",
            TOKEN,
            REVOKE,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1));
    var request = observed;
    assertDoesNotThrow(
        () ->
            SdkRevocationRequest.verify(
                request, reference, f.refresh, "refresh_token", fault, false));
    assertThrows(
        AssertionError.class,
        () ->
            SdkRevocationRequest.verify(
                request, reference, f.refresh, "refresh_token", "valid", false));
    var badProof =
        new SdkRevocationRequest.Observation(
            observed.method(),
            observed.target(),
            observed.host(),
            observed.contentType(),
            observed.body(),
            corrupt(observed.proof()),
            observed.sent());
    assertThrows(
        AssertionError.class,
        () ->
            SdkRevocationRequest.verify(
                badProof, reference, f.refresh, "refresh_token", fault, false));
  }

  @Test
  void acceptsFreshRegisteredProofsForTheExactOriginalGrant() throws Exception {
    var f = new Fixture();
    assertDoesNotThrow(f::verify);
    f.host = "GUARD.EXAMPLE:443";
    f.target = REVOKE.toString();
    assertDoesNotThrow(f::verify);
  }

  @Test
  void parsesAndCorrelatesActualRbelHttpBodiesHeadersAndRegistration() throws Exception {
    var f = new Fixture();
    var converter = RbelLogger.build().getRbelConverter();
    var messages = new ArrayList<RbelElement>();
    final var json = new tools.jackson.databind.ObjectMapper();
    var registration = parse(converter, http("POST /register HTTP/1.1",
        "application/json", "{\"jwks\":" + f.jwks + "}", ""));
    var registered = parse(converter, http("HTTP/1.1 201 Created", "application/json",
        "{\"client_id\":\"client\"}", ""));
    var registrationPair = TracingMessagePairFacet.builder().request(registration).responses(List.of(registered)).build();
    registration.addFacet(registrationPair);
    registered.addFacet(registrationPair);
    messages.add(registration);
    messages.add(registered);
    var originalAssertion = sign(new JWTClaimsSet.Builder(f.assertionClaims.build())
        .jwtID("initial-assertion").build(), f.clientKey, "JWT", false);
    var originalProof =
        sign(
            new JWTClaimsSet.Builder(f.proofClaims.build())
                .jwtID("initial-dpop")
                .claim("htu", TOKEN.toString())
                .build(),
            f.dpopKey,
            "dpop+jwt",
            false);
    var grant = parse(converter, http("POST /token HTTP/1.1",
        "application/x-www-form-urlencoded", "client_assertion=" + encode(originalAssertion),
        "DPoP: " + originalProof + "\r\n"));
    var granted = parse(converter, http("HTTP/1.1 200 OK", "application/json",
        json.writeValueAsString(Map.of("access_token", f.access, "refresh_token", f.refresh)), ""));
    var grantPair = TracingMessagePairFacet.builder().request(grant).responses(List.of(granted)).build();
    grant.addFacet(grantPair);
    granted.addFacet(grantPair);
    messages.add(grant);
    messages.add(granted);
    var observed = f.observation();
    var request = parse(converter, http("POST /revoke HTTP/1.1", observed.contentType(),
        observed.body(), "DPoP: " + observed.proof() + "\r\n"));
    request.addOrReplaceFacet(new RbelMessageTimingFacet(NOW.atZone(ZoneOffset.UTC)));
    messages.add(request);
    var evidence = Map.of("logout_started_ms", Long.toString(NOW.minusSeconds(1).toEpochMilli()),
        "logout_completed_ms", Long.toString(NOW.plusSeconds(1).toEpochMilli()));
    try {
      TigerGlobalConfiguration.putValue(
          "zeta_base_url", "guard.example", ConfigurationValuePrecedence.TEST_CONTEXT);
      TigerGlobalConfiguration.putValue(
          "paths.guard.registerEndpointPath",
          "/register",
          ConfigurationValuePrecedence.TEST_CONTEXT);
      TigerGlobalConfiguration.putValue(
          "paths.guard.tokenEndpointPath", "/token", ConfigurationValuePrecedence.TEST_CONTEXT);
      assertDoesNotThrow(() -> SdkRevocationRequest.verifyRecorded(messages, request, evidence,
          f.access, f.refresh, "client", REVOKE.toString()));
      messages.add(registration);
      assertThrows(AssertionError.class, () -> SdkRevocationRequest.verifyRecorded(messages, request,
          evidence, f.access, f.refresh, "client", REVOKE.toString()));
      messages.remove(registration);
      messages.remove(grant);
      assertThrows(AssertionError.class, () -> SdkRevocationRequest.verifyRecorded(messages, request,
          evidence, f.access, f.refresh, "client", REVOKE.toString()));
    } finally {
      TigerGlobalConfiguration.reset();
    }
  }

  private static RbelElement parse(RbelConverter converter, String message) {
    return converter.parseMessage(message.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }

  private static String http(String firstLine, String contentType, String body, String headers) {
    return firstLine + "\r\nHost: guard.example\r\nContent-Type: " + contentType + "\r\n"
        + headers + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
        + "\r\n\r\n" + body;
  }

  @ParameterizedTest
  @ValueSource(strings = {"method", "path", "query", "host", "port", "type", "hint",
      "token", "client", "assertion-type", "before-logout", "after-logout", "duplicate-token"})
  void rejectsIncompleteOrUnrelatedRequests(String fault) throws Exception {
    var f = new Fixture();
    switch (fault) {
      case "method" -> f.method = "GET";
      case "path" -> f.target = "/other/revoke";
      case "query" -> f.target += "?foreign=1";
      case "host" -> f.host = "other.example";
      case "port" -> f.host += ":444";
      case "type" -> f.type = "application/json";
      case "hint" -> f.form.remove("token_type_hint");
      case "token" -> f.form.put("token", "another-refresh");
      case "client" -> f.form.put("client_id", "another-client");
      case "assertion-type" -> f.form.remove("client_assertion_type");
      case "before-logout" -> f.sent = NOW.minusSeconds(2);
      case "after-logout" -> f.sent = NOW.plusSeconds(2);
      case "duplicate-token" -> f.suffix = "&token=" + encode(f.refresh);
      default -> throw new AssertionError();
    }
    assertThrows(AssertionError.class, f::verify, fault);
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuer", "subject", "audience", "expired", "premature", "stale",
      "replay", "missing-jti", "attestation", "wrong-key", "invalid-signature"})
  void checksTheClientAssertionAgainstTheRegisteredPublicKey(String fault) throws Exception {
    var f = new Fixture();
    switch (fault) {
      case "issuer" -> f.assertionClaims.issuer("foreign");
      case "subject" -> f.assertionClaims.subject("foreign");
      case "audience" -> f.assertionClaims.audience("https://foreign.example/token");
      case "expired" -> f.assertionClaims.expirationTime(Date.from(NOW));
      case "premature" -> f.assertionClaims.notBeforeTime(Date.from(NOW.plusSeconds(5)));
      case "stale" -> f.assertionClaims.issueTime(Date.from(NOW.minusSeconds(61)));
      case "replay" -> f.assertionClaims.jwtID("initial-assertion");
      case "missing-jti" -> f.assertionClaims.jwtID("");
      case "attestation" ->
          f.assertionClaims.claim("client_statement", Map.of("posture", "hardware"));
      case "wrong-key" -> f.clientKey = new ECKeyGenerator(Curve.P_256).generate();
      case "invalid-signature" -> f.corruptAssertion = true;
      default -> throw new AssertionError();
    }
    assertThrows(AssertionError.class, f::verify, fault);
  }

  @ParameterizedTest
  @ValueSource(strings = {"key", "private-key", "method", "target", "type", "nonce", "stale",
      "future", "replay", "missing-jti", "invalid-signature"})
  void checksDpopSignatureOriginalGrantBindingTargetAndFreshness(String fault) throws Exception {
    var f = new Fixture();
    switch (fault) {
      case "key" -> f.dpopKey = new ECKeyGenerator(Curve.P_256).generate();
      case "private-key" -> f.privateProofKey = true;
      case "method" -> f.proofClaims.claim("htm", "GET");
      case "target" -> f.proofClaims.claim("htu", TOKEN.toString());
      case "type" -> f.proofType = "JWT";
      case "nonce" -> f.proofClaims.claim("nonce", "initial-nonce");
      case "stale" -> f.proofClaims.issueTime(Date.from(NOW.minusSeconds(61)));
      case "future" -> f.proofClaims.issueTime(Date.from(NOW.plusSeconds(61)));
      case "replay" -> f.proofClaims.jwtID("initial-dpop");
      case "missing-jti" -> f.proofClaims.jwtID("");
      case "invalid-signature" -> f.corruptProof = true;
      default -> throw new AssertionError();
    }
    assertThrows(AssertionError.class, f::verify, fault);
  }

  @ParameterizedTest
  @ValueSource(strings = {"client_id", "sid", "sub", "iss", "expired"})
  void matchingBodyAloneDoesNotEstablishRefreshOwnership(String fault) throws Exception {
    var f = new Fixture();
    var claims = new JWTClaimsSet.Builder(SignedJWT.parse(f.refresh).getJWTClaimsSet());
    if (fault.equals("expired")) {
      claims.expirationTime(Date.from(NOW));
    } else {
      claims.claim(fault, "foreign");
    }
    f.refresh = sign(claims.build(), f.clientKey, "JWT", false);
    f.form.put("token", f.refresh);
    assertThrows(AssertionError.class, f::verify, fault);
  }

  @Test
  void registrationMustBeUnambiguousAndMustNotExposePrivateKeys() throws Exception {
    for (var fault : List.of("missing", "two-keys", "private")) {
      var f = new Fixture();
      f.jwks = switch (fault) {
        case "missing" -> new JWKSet().toString();
        case "two-keys" -> new JWKSet(List.of(f.clientKey.toPublicJWK(),
            new ECKeyGenerator(Curve.P_256).generate().toPublicJWK())).toString();
        case "private" -> "{\"keys\":[" + f.clientKey.toJSONString() + "]}";
        default -> throw new AssertionError();
      };
      assertThrows(AssertionError.class, f::verify, fault);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String sign(JWTClaimsSet claims, ECKey key, String type, boolean privateKey)
      throws Exception {
    var header = new JWSHeader.Builder(JWSAlgorithm.ES256)
        .type(new JOSEObjectType(type)).jwk(key.toPublicJWK()).build();
    var jwt = new SignedJWT(header, claims);
    var signer = new ECDSASigner(key);
    jwt.sign(signer);
    if (privateKey) {
      // Nimbus refuses private keys in its builder. Build the invalid wire header explicitly
      // and sign those exact bytes, so the validator (not fixture construction) rejects it.
      var fields = new HashMap<>(header.toJSONObject());
      fields.put("jwk", key.toJSONObject());
      var wire = Base64URL.encode(new tools.jackson.databind.ObjectMapper()
          .writeValueAsString(fields)) + "." + jwt.getPayload().toBase64URL();
      return wire + "." + signer.sign(header, wire.getBytes(StandardCharsets.US_ASCII));
    }
    return jwt.serialize();
  }

  private static String corrupt(String jwt) {
    var parts = jwt.split("\\.");
    var bytes = Base64URL.from(parts[2]).decode();
    bytes[0] ^= 1;
    return parts[0] + "." + parts[1] + "." + Base64URL.encode(bytes);
  }

  private static final class Fixture {
    ECKey clientKey = new ECKeyGenerator(Curve.P_256).generate();
    ECKey dpopKey = new ECKeyGenerator(Curve.P_256).generate();
    String jwks = new JWKSet(clientKey.toPublicJWK()).toString();
    String access;
    String refresh;
    String method = "POST";
    String target = "/revoke";
    String host = "guard.example";
    String type = "application/x-www-form-urlencoded; charset=UTF-8";
    String proofType = "dpop+jwt";
    String suffix = "";
    Instant sent = NOW;
    boolean privateProofKey;
    boolean corruptAssertion;
    boolean corruptProof;
    Map<String, String> form = new HashMap<>();
    JWTClaimsSet.Builder assertionClaims = new JWTClaimsSet.Builder().issuer("client")
        .subject("client").audience(TOKEN.toString()).issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plusSeconds(60))).jwtID("logout-assertion");
    JWTClaimsSet.Builder proofClaims = new JWTClaimsSet.Builder().claim("htm", "POST")
        .claim("htu", REVOKE.toString()).issueTime(Date.from(NOW)).jwtID("logout-dpop");

    Fixture() throws Exception {
      var claims = new JWTClaimsSet.Builder().issuer("https://guard.example").subject("identity")
          .claim("client_id", "client").claim("sid", "session")
          .expirationTime(Date.from(NOW.plusSeconds(300)));
      refresh = sign(claims.build(), clientKey, "JWT", false);
      access = sign(claims.claim("cnf", Map.of("jkt", dpopKey.computeThumbprint().toString()))
          .build(), clientKey, "JWT", false);
      form.put("token", refresh);
      form.put("token_type_hint", "refresh_token");
      form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
    }

    void verify() throws Exception {
      SdkRevocationRequest.verify(observation(),
          new SdkRevocationRequest.Reference(access, refresh, "client", jwks,
              "initial-assertion", "initial-dpop", TOKEN, REVOKE,
              NOW.minusSeconds(1), NOW.plusSeconds(1)));
    }

    SdkRevocationRequest.Observation observation() throws Exception {
      var assertion = sign(assertionClaims.build(), clientKey, "JWT", false);
      var proof = sign(proofClaims.build(), dpopKey, proofType, privateProofKey);
      form.put("client_assertion", corruptAssertion ? corrupt(assertion) : assertion);
      var body = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
          .collect(Collectors.joining("&")) + suffix;
      return new SdkRevocationRequest.Observation(method, target, host, type,
          body, corruptProof ? corrupt(proof) : proof, sent);
    }
  }
}
