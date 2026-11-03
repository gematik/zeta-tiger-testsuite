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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.rbellogger.util.RbelJexlExecutor;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.common.jexl.TigerJexlExecutor;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.proxy.handler.SingleConnectionParser.SingleConnectionParserMarkerFacet;
import de.gematik.zeta.services.RecordedMessages;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PoppJwksStepsTest {
  private static final Instant NOW = Instant.parse("2026-10-04T02:00:00.500Z");

  @AfterEach
  void resetPublishedVariables() {
    TigerGlobalConfiguration.reset();
  }

  @Test
  void publishesExactSignedFixtureAndExpiryForGenericSteps() throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("server").generate();
    var original = original(key, validClaims(key));
    var glue = new PoppJwksSteps();
    glue.prepareFixture(original.serialize(), pem(key), 180, NOW, "/.well-known/signed-jwks", "POPP_JWKS_TEST");
    var body = TigerGlobalConfiguration.readString("POPP_JWKS_TEST.body");
    assertTrue(SignedJWT.parse(body).verify(new ECDSAVerifier(key.toPublicJWK())));
    assertEquals(body, TigerGlobalConfiguration.resolvePlaceholders("${POPP_JWKS_TEST.body}"));
    assertEquals(PoppJwksSteps.responseCondition("/.well-known/signed-jwks"),
        TigerGlobalConfiguration.readString("POPP_JWKS_TEST.condition"));
    assertEquals(String.valueOf(NOW.getEpochSecond() + 180),
        TigerGlobalConfiguration.readString("POPP_JWKS_TEST.expires"));
    assertEquals(SignedJWT.parse(body).getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond(),
        Long.parseLong(TigerGlobalConfiguration.readString("POPP_JWKS_TEST.expires")));
  }

  @ParameterizedTest
  @CsvSource({"true,-1,true", "true,0,false", "true,1,false", "false,-1,false"})
  void newDownloadMustBeDistinctAndCompleteStrictlyBeforeSignedExpiry(
      boolean distinct, int nanosFromExpiry, boolean valid) throws Exception {
    var glue = preparedFixture();
    var initial = response(NOW.plusSeconds(1));
    var expires = Instant.ofEpochSecond(NOW.getEpochSecond() + 180);
    var current = distinct ? response(expires.plusNanos(nanosFromExpiry)) : initial;
    try (var recorded = mockStatic(RecordedMessages.class);
        var singleton = mockStatic(RbelMessageRetriever.class)) {
      var retriever = mock(RbelMessageRetriever.class);
      singleton.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      recorded.when(RecordedMessages::snapshot).thenReturn(List.of(initial, current));
      when(retriever.getCurrentResponse()).thenReturn(initial);
      glue.rememberInitialResponseBeforeExpiry();
      when(retriever.getCurrentResponse()).thenReturn(current);
      if (valid) {
        assertDoesNotThrow(glue::verifyDistinctResponseBeforeExpiry);
      } else {
        assertThrows(AssertionError.class, glue::verifyDistinctResponseBeforeExpiry);
      }
    }
  }

  @Test
  void firstDownloadUsesLastChunkAndRejectsIncompleteOrExpiredReception() throws Exception {
    var glue = preparedFixture();
    var expires = Instant.ofEpochSecond(NOW.getEpochSecond() + 180);
    var first = response(expires.minusSeconds(1));
    var last = response(expires.plusNanos(1));
    var assembled = response(expires.minusSeconds(1));
    assembled.addFacet(new SingleConnectionParserMarkerFacet(List.of(first.getUuid(), last.getUuid())));
    try (var recorded = mockStatic(RecordedMessages.class);
        var singleton = mockStatic(RbelMessageRetriever.class)) {
      var retriever = mock(RbelMessageRetriever.class);
      singleton.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      when(retriever.getCurrentResponse()).thenReturn(assembled);
      recorded.when(RecordedMessages::snapshot).thenReturn(List.of(first, last));
      assertThrows(AssertionError.class, glue::rememberInitialResponseBeforeExpiry);
      recorded.when(RecordedMessages::snapshot).thenReturn(List.of(first));
      assertThrows(AssertionError.class, glue::rememberInitialResponseBeforeExpiry);
    }
  }

  @Test
  void refreshRequiresAnObservedInitialDownload() throws Exception {
    assertThrows(AssertionError.class, preparedFixture()::verifyDistinctResponseBeforeExpiry);
  }

  private static PoppJwksSteps preparedFixture() throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("server").generate();
    var glue = new PoppJwksSteps();
    glue.prepareFixture(original(key, validClaims(key)).serialize(), pem(key), 180, NOW,
        "/.well-known/signed-jwks", "POPP_JWKS_TEST");
    return glue;
  }

  private static RbelElement response(Instant received) {
    var result = RbelLogger.build().getRbelConverter().parseMessage(
        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    result.addOrReplaceFacet(new RbelMessageTimingFacet(received.atZone(ZoneOffset.UTC)));
    return result;
  }

  @ParameterizedTest
  @CsvSource({"GET,/.well-known/signed-jwks,200,true", "GET,/popp/.well-known/signed-jwks,200,true",
      "GET,/other,200,false", "GET,/.wellXknown/signed-jwks,200,false",
      "POST,/.well-known/signed-jwks,200,false", "GET,/.well-known/signed-jwks,400,false"})
  void fixtureConditionMatchesOnlySuccessfulJwksGetResponses(String method, String path, int status, boolean matches) {
    var converter = RbelLogger.build().getRbelConverter();
    var request = converter.parseMessage((method + " " + path + " HTTP/1.1\r\nHost: mock\r\n\r\n")
        .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    var response = converter.parseMessage(("HTTP/1.1 " + status + " OK\r\nContent-Length: 2\r\n\r\n{}")
        .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    var pair = TracingMessagePairFacet.builder().request(request).responses(List.of(response)).build();
    request.addFacet(pair);
    response.addFacet(pair);
    RbelJexlExecutor.initialize();
    var condition = PoppJwksSteps.responseCondition("/.well-known/signed-jwks");
    assertEquals(matches, TigerJexlExecutor.matchesAsJexlExpression(response, condition));
    assertEquals(false, TigerJexlExecutor.matchesAsJexlExpression(request, condition));
  }

  @Test
  void shortLifetimePreservesServerIdentityPublicKeysAndEveryOtherClaim() throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("server").generate();
    var original = original(key, validClaims(key));
    var fixture = PoppJwksSteps.shortLivedFixture(original.serialize(), pem(key), 180, NOW);
    var replacement = SignedJWT.parse(fixture.compact());
    assertTrue(replacement.verify(new ECDSAVerifier(key.toPublicJWK())));
    assertEquals(original.getHeader().toJSONObject(), replacement.getHeader().toJSONObject());
    var expected = new java.util.HashMap<>(original.getJWTClaimsSet().toJSONObject());
    expected.put("iat", NOW.getEpochSecond());
    expected.put("exp", NOW.getEpochSecond() + 180);
    assertEquals(expected, replacement.getJWTClaimsSet().toJSONObject());
    assertEquals(Instant.ofEpochSecond(NOW.getEpochSecond() + 180), fixture.expires());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 601})
  void rejectsUnsupportedLifetime(int seconds) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("server").generate();
    assertThrows(AssertionError.class, () -> PoppJwksSteps.shortLivedFixture(
        original(key, validClaims(key)).serialize(), pem(key), seconds, NOW));
  }

  @Test
  void rejectsForeignSignerInsteadOfReplacingTheServersSigningIdentity() throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("server").generate();
    var foreign = new ECKeyGenerator(Curve.P_256).generate();
    assertThrows(AssertionError.class, () -> PoppJwksSteps.shortLivedFixture(
        original(key, validClaims(key)).serialize(), pem(foreign), 180, NOW));
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty_keys", "private_keys", "missing_issuer", "missing_exp", "expired"})
  void rejectsInvalidOriginalJwks(String variant) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("server").generate();
    var claims = new JWTClaimsSet.Builder(validClaims(key));
    switch (variant) {
      case "empty_keys" -> claims.claim("keys", List.of());
      case "private_keys" -> claims.claim("keys", List.of(key.toJSONObject()));
      case "missing_issuer" -> claims.issuer(null);
      case "missing_exp" -> claims.expirationTime(null);
      case "expired" -> claims.expirationTime(Date.from(NOW.minusSeconds(1)));
      default -> throw new AssertionError("Unexpected fixture variant");
    }
    assertThrows(AssertionError.class, () -> PoppJwksSteps.shortLivedFixture(
        original(key, claims.build()).serialize(), pem(key), 180, NOW));
  }

  private static JWTClaimsSet validClaims(ECKey key) {
    return new JWTClaimsSet.Builder().issuer("http://popp.fixture")
        .issueTime(Date.from(NOW.minusSeconds(100)))
        .expirationTime(Date.from(NOW.plusSeconds(3600)))
        .claim("keys", new JWKSet(key.toPublicJWK()).toJSONObject().get("keys"))
        .claim("fixture_marker", "preserve").build();
  }

  private static SignedJWT original(ECKey key, JWTClaimsSet claims) throws Exception {
    var result = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("server")
        .type(new JOSEObjectType("jwk-set+json")).build(), claims);
    result.sign(new ECDSASigner(key));
    return result;
  }

  private static String pem(ECKey key) throws Exception {
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getEncoder().encodeToString(key.toECPrivateKey().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
  }
}
