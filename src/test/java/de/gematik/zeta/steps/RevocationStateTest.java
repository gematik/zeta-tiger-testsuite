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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.util.RbelJexlExecutor;
import de.gematik.test.tiger.common.jexl.TigerJexlExecutor;
import io.restassured.builder.ResponseBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class RevocationStateTest {
  private static final Instant NOW = Instant.ofEpochSecond(1800000000);
  private static final ObjectMapper JSON = new ObjectMapper();

  @AfterEach
  void clearCollectedFailures() {
    SoftAssertionsContext.reset();
  }

  @Test
  void collectedWrongStatusStillFailsTheScenarioAndIsNeverAcceptedAsTheExpectedStatus() {
    var wrong = new ResponseBuilder().setStatusCode(400).build();
    assertDoesNotThrow(() -> RevocationSteps.collectStatus(wrong, 401));
    var failure = assertThrows(AssertionError.class, SoftAssertionsContext::assertAll);
    assertTrue(failure.getMessage().contains("Expected HTTP 401 but received 400"));
    assertThrows(AssertionError.class, () -> RevocationSteps.collectStatus(null, 401));
    assertDoesNotThrow(() -> RevocationSteps.collectStatus(new ResponseBuilder().setStatusCode(401).build(), 401));
    assertDoesNotThrow(SoftAssertionsContext::assertAll);
  }

  @ParameterizedTest
  @CsvSource({"revocation,target", "revocation,foreign-client", "revocation,refresh-grant",
      "revocation,resource-policy", "revocation,foreign-path", "fragment,target",
      "fragment,foreign-client", "fragment,refresh-grant", "fragment,resource-policy", "fragment,foreign-path",
      "fragment-negative,target", "fragment-negative,foreign-client", "fragment-negative,refresh-grant",
      "fragment-negative,resource-policy", "fragment-negative,foreign-path"})
  void actualFeatureConditionSelectsOnlyTheFreshClientsTokenExchangePolicy(String fixture, String variant) throws Exception {
    boolean fragment = fixture.startsWith("fragment");
    var feature = Files.readString(Path.of(fragment
        ? "src/test/resources/features/UserStory_04/UseCase_09/token_exchange_isolierte_nachweise.feature"
        : "src/test/resources/features/UserStory_04/UseCase_07/revocation_authentisierung.feature"));
    var variable = fixture.equals("fragment-negative") ? "ccFragmentNegativePolicy"
        : fragment ? "ccFragmentControlPolicy" : "ccExpiryPolicy";
    var line = feature.lines().filter(value -> value.contains("Variable \"" + variable + "\" auf \"")).findFirst().orElseThrow();
    final var condition = line.substring(line.indexOf(" auf \"") + 6, line.lastIndexOf('"'))
        .replace("${paths.opa.decisionPath}", "/decision")
        .replace("${CC_EXPIRY_CLIENT}", "target-client")
        .replace("${CC_FRAGMENT_CONTROL_CLIENT}", "target-client")
        .replace("${CC_NEGATIVE_CLIENT}", "target-client")
        .replace("${oauth_parameters.grant_type.tokenExchange}", "urn:ietf:params:oauth:grant-type:token-exchange");
    var registration = Map.of("client_id", variant.equals("foreign-client") ? "other-client" : "target-client");
    var authorization = variant.equals("resource-policy") ? Map.of()
        : Map.of("grant_type", variant.equals("refresh-grant") ? "refresh_token" : "urn:ietf:params:oauth:grant-type:token-exchange");
    var body = JSON.writeValueAsString(Map.of("input", Map.of(
        "client_registration_data", registration, "authorization_request", authorization)));
    var request = parse("POST " + (variant.equals("foreign-path") ? "/other" : "/decision") + " HTTP/1.1", body, "application/json");
    var response = parse("HTTP/1.1 200 OK", "{\"result\":{\"ttl\":{\"refresh_token\":86400}}}", "application/json");
    var pair = TracingMessagePairFacet.builder().request(request).responses(List.of(response)).build();
    request.addFacet(pair);
    response.addFacet(pair);
    RbelJexlExecutor.initialize();
    if (variant.equals("target")) {
      for (var clause : condition.split(" && ")) {
        assertTrue(TigerJexlExecutor.matchesAsJexlExpression(response, clause), clause);
      }
    }
    assertEquals(variant.equals("target"), TigerJexlExecutor.matchesAsJexlExpression(response, condition));
    assertFalse(TigerJexlExecutor.matchesAsJexlExpression(request, condition));
  }

  @Test
  void acceptsFreshBoundOriginalTokensAndActualIssuedLifetime() throws Exception {
    var f = new Fixture();
    var state = f.state();
    assertEquals("client", state.client());
    assertEquals("session", state.session());
    assertDoesNotThrow(() -> RevocationSteps.verifyIssuedLifetime(f.response(45), state, 45));
  }

  @ParameterizedTest
  @ValueSource(strings = {"client_id", "sid", "sub", "iss", "cnf", "expired", "premature", "future-iat"})
  void independentTokenConditionsMustMatchBeforeAnyStateClaim(String fault) throws Exception {
    var f = new Fixture();
    var claims = new JWTClaimsSet.Builder(SignedJWT.parse(f.refresh).getJWTClaimsSet());
    switch (fault) {
      case "cnf" -> claims.claim("cnf", Map.of("jkt", "foreign"));
      case "expired" -> claims.expirationTime(Date.from(NOW));
      case "premature" -> claims.notBeforeTime(Date.from(NOW.plusSeconds(1)));
      case "future-iat" -> claims.issueTime(Date.from(NOW.plusSeconds(6)));
      default -> claims.claim(fault, "foreign");
    }
    f.refresh = sign(claims.build());
    assertThrows(AssertionError.class, f::state, fault);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"45\"", "44", "45.0", "4294967341", "null", "-1"})
  void reportedTtlMustBeAnExactIntegralBoundedValue(String ttl) throws Exception {
    var f = new Fixture();
    var response = "{\"refresh_token\":\"" + f.refresh + "\",\"refresh_expires_in\":" + ttl + "}";
    assertThrows(AssertionError.class, () -> RevocationSteps.verifyIssuedLifetime(response, f.state(), 45));
  }

  @Test
  void responseSubstitutionAndAlteredSignedLifetimeCannotPass() throws Exception {
    var f = new Fixture();
    assertThrows(AssertionError.class, () -> RevocationSteps.verifyIssuedLifetime(
        "{\"refresh_token\":\"foreign\",\"refresh_expires_in\":45}", f.state(), 45));
    var state = f.state();
    var differentLifetime = new RevocationSteps.TokenState(state.refresh(), state.client(),
        state.session(), state.issued(), state.expires().plusSeconds(1));
    assertThrows(AssertionError.class,
        () -> RevocationSteps.verifyIssuedLifetime(f.response(45), differentLifetime, 45));
    for (int seconds : new int[] {0, 10, 61, Integer.MAX_VALUE}) {
      assertThrows(AssertionError.class,
          () -> RevocationSteps.verifyIssuedLifetime(f.response(seconds), state, seconds));
    }
  }

  @Test
  void absenceRequiresTheAuthoritativeIntegerCountAndCannotDefaultMissingDataToZero() {
    for (var value : List.of("{}", "{\"admin_sessions\":null}", "{\"admin_sessions\":\"0\"}",
        "{\"admin_sessions\":4294967296}", "{\"admin_sessions\":1}", "{\"admin_sessions\":0.0}")) {
      assertThrows(AssertionError.class, () -> RevocationSteps.verifySessionCount(JSON.readTree(value), 0));
    }
    assertDoesNotThrow(() -> RevocationSteps.verifySessionCount(JSON.readTree("{\"admin_sessions\":0}"), 0));
    assertDoesNotThrow(() -> RevocationSteps.verifySessionCount(JSON.readTree("{\"admin_sessions\":1}"), 1));
  }

  @Test
  void expirationRequiresTheFullTwoSecondsOfIndependentlyObservedAsTime() throws Exception {
    var state = new Fixture().state();
    for (var time : List.of(state.expires().minusSeconds(1), state.expires(), state.expires().plusSeconds(1))) {
      assertThrows(AssertionError.class, () -> RevocationSteps.verifyExpired(state, time));
    }
    var actual = state.expires().plusSeconds(2);
    assertDoesNotThrow(() -> RevocationSteps.verifyExpired(state, actual));
  }

  @Test
  void backgroundRefreshOfTheExactTokenInvalidatesExpirationIsolation() {
    var target = parse("POST /token HTTP/1.1", "refresh_token=target", "application/x-www-form-urlencoded");
    assertThrows(AssertionError.class,
        () -> RevocationSteps.verifyNoRecordedRefresh(List.of(target), "/token", "target"));
    var unrelated = parse("POST /token HTTP/1.1", "refresh_token=another", "application/x-www-form-urlencoded");
    var response = parse("HTTP/1.1 200 OK", "{\"refresh_token\":\"target\"}", "application/json");
    var anotherPath = parse("POST /other HTTP/1.1", "refresh_token=target", "application/x-www-form-urlencoded");
    assertDoesNotThrow(() -> RevocationSteps.verifyNoRecordedRefresh(List.of(unrelated, response, anotherPath), "/token", "target"));
  }

  @Test
  void unchangedRefreshRequestMustContainTheExactCapturedCurrentToken() {
    assertDoesNotThrow(() -> RevocationSteps.verifyRefreshTokenSelection(
        "grant_type=refresh_token&refresh_token=current%2Btoken&client_assertion=proof",
        "current+token"));
    for (var body : List.of(
        "grant_type=refresh_token&refresh_token=previous-token&client_assertion=proof",
        "grant_type=refresh_token&refresh_token=access-token&client_assertion=proof",
        "grant_type=refresh_token&client_assertion=proof",
        "grant_type=refresh_token&refresh_token=current%2Btoken&refresh_token=other&client_assertion=proof",
        "grant_type=token_exchange&refresh_token=current%2Btoken&client_assertion=proof")) {
      assertThrows(AssertionError.class,
          () -> RevocationSteps.verifyRefreshTokenSelection(body, "current+token"));
    }
  }

  private static RbelElement parse(String first, String body, String type) {
    var message = first + "\r\nHost: guard.example\r\nContent-Type: " + type + "\r\nContent-Length: "
        + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    return RbelLogger.build().getRbelConverter()
        .parseMessage(message.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }

  private static String sign(JWTClaimsSet claims) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    var token = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims);
    token.sign(new ECDSASigner(key));
    return token.serialize();
  }

  private static final class Fixture {
    String access;
    String refresh;

    Fixture() throws Exception {
      var claims = new JWTClaimsSet.Builder().issuer("https://guard.example").subject("owner")
          .claim("client_id", "client").claim("sid", "session").claim("cnf", Map.of("jkt", "key"))
          .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(45)));
      refresh = sign(claims.jwtID("rt").build());
      access = sign(claims.jwtID("at").build());
    }

    RevocationSteps.TokenState state() throws Exception {
      return RevocationSteps.tokenState(access, refresh, "client", NOW);
    }

    String response(int seconds) {
      return JSON.writeValueAsString(Map.of("refresh_token", refresh, "refresh_expires_in", seconds));
    }
  }
}
