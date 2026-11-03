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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParRequestStepsTest {
  private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
  private static final URI ENDPOINT = URI.create("https://guard.example/authorize");
  private static final List<String> AUDIENCES = List.of("https://guard.example",
      "https://guard.example/token", "https://guard.example/par");
  private static final String APP = "http://localhost/app";
  private static final String OIDC = "http://localhost/oidc";

  @ParameterizedTest
  @ValueSource(strings = {"https://guard.example", "https://guard.example/token",
      "https://guard.example/par"})
  void acceptsEveryRfc9126AudienceWithRequiredJtiAndWithoutOptionalIat(String audience) throws Exception {
    assertion(audience, "valid");
  }

  @ParameterizedTest
  @ValueSource(strings = {"signature", "issuer", "subject", "audience", "expired", "premature",
      "no_exp", "wrong_type", "conflicting_client", "private_key", "multiple_keys",
      "no_jti"})
  void rejectsInvalidOriginalClientProof(String fault) {
    var error = assertThrows(AssertionError.class, () -> assertion(AUDIENCES.getFirst(), fault));
    if ("signature".equals(fault)) {
      assertEquals("PAR assertion has invalid registered-key signature", error.getMessage());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"numeric_jti", "array_jti"})
  void rejectsNonStringJtiDuringJwtParsing(String fault) {
    assertThrows(ParseException.class, () -> assertion(AUDIENCES.getFirst(), fault));
  }

  private static void assertion(String audience, String fault) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    final var signer = "signature".equals(fault) ? new ECKeyGenerator(Curve.P_256).generate() : key;
    var claims = new JWTClaimsSet.Builder().issuer("issuer".equals(fault) ? "other" : "client")
        .subject("subject".equals(fault) ? "other" : "client")
        .audience("audience".equals(fault) ? "https://foreign.example" : audience);
    if (!"no_jti".equals(fault)) {
      claims.claim("jti", switch (fault) {
        case "numeric_jti" -> 42;
        case "array_jti" -> List.of("fixture-jti");
        default -> "fixture-jti";
      });
    }
    if (!"no_exp".equals(fault)) {
      claims.expirationTime(Date.from(NOW.plusSeconds("expired".equals(fault) ? 0 : 60)));
    }
    if ("premature".equals(fault)) {
      claims.notBeforeTime(Date.from(NOW.plusSeconds(1)));
    }
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
        .type(com.nimbusds.jose.JOSEObjectType.JWT).jwk(key.toPublicJWK()).build(), claims.build());
    jwt.sign(new ECDSASigner(signer));
    var form = new HashMap<>(Map.of("client_assertion", jwt.serialize(), "client_assertion_type",
        "wrong_type".equals(fault) ? "other" : "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"));
    if ("conflicting_client".equals(fault)) {
      form.put("client_id", "other");
    }
    var keys = new JWKSet(key.toPublicJWK()).toString();
    if ("private_key".equals(fault)) {
      keys = new JWKSet(key).toString(false);
    } else if ("multiple_keys".equals(fault)) {
      keys = new JWKSet(List.of(key.toPublicJWK(), signer.toPublicJWK())).toString();
    }
    ParRequestSteps.verifyAssertion(form, "client", keys, NOW, AUDIENCES);
  }

  @Test
  void acceptsCompleteOuterPayload() {
    assertDoesNotThrow(() -> verifyOuter(outer()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"redirect_uri", "oidc_redirect_uri",
      "code_challenge", "code_challenge_method", "scope", "idp_iss"})
  void rejectsEveryMissingOuterParameter(String field) {
    var form = outer();
    form.remove(field);
    assertThrows(AssertionError.class, () -> verifyOuter(form));
  }

  /** Leaves missing schema-required fields to the separate non-blocking schema validation. */
  @ParameterizedTest
  @ValueSource(strings = {"client_id", "response_type"})
  void contextChecksDoNotRepeatSchemaRequiredChecks(String field) {
    var outer = outer();
    outer.remove(field);
    assertDoesNotThrow(() -> verifyOuter(outer));
  }

  @ParameterizedTest
  @ValueSource(strings = {"client_id", "redirect_uri", "oidc_redirect_uri", "scope", "idp_iss",
      "response_type", "code_challenge", "code_challenge_method", "request_uri"})
  void rejectsWrongOuterValues(String field) {
    var form = outer();
    form.put(field, "wrong");
    assertThrows(AssertionError.class, () -> verifyOuter(form));
  }

  @ParameterizedTest
  @ValueSource(strings = {"cc-state-ä", "state\tvalue", "state\nvalue", "state\u007fvalue"})
  void outerParRejectsStateOutsideTheOAuthVisibleCharacterRange(String state) {
    var outer = outer();
    outer.put("state", state);
    assertThrows(AssertionError.class, () -> verifyOuter(outer));
  }

  @Test
  void stateProofAllowsTheFullVisibleRangeIncludingSpace() {
    var outer = outer();
    outer.put("state", " !~valid-state");
    assertDoesNotThrow(() -> verifyOuter(outer));
  }

  @Test
  void rejectsUnregisteredRedirectEvenWhenItMatchesConfiguration() {
    assertThrows(AssertionError.class, () -> ParRequestSteps.verifyOuterContext(outer(), "client",
        List.of(APP), APP, OIDC, "scope", "https://idp.example"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"valid", "method", "host", "path", "fragment", "client", "uri",
      "duplicate", "early", "no_query"})
  void authorizeMustUseExactClientUriEndpointAndCausalTime(String fault) {
    String query = "?client_id=client&request_uri=urn%3Aexample%3Aissued";
    String base = ENDPOINT.toString();
    if ("client".equals(fault)) {
      query = query.replace("client_id=client", "client_id=other");
    } else if ("uri".equals(fault)) {
      query = query.replace("issued", "foreign");
    } else if ("duplicate".equals(fault)) {
      query += "&client_id=client";
    } else if ("host".equals(fault)) {
      base = "https://foreign.example/authorize";
    } else if ("path".equals(fault)) {
      base += "/other";
    } else if ("fragment".equals(fault)) {
      query += "#fragment";
    } else if ("no_query".equals(fault)) {
      query = "";
    }
    var actual = new ParRequestSteps.Observation("method".equals(fault) ? "POST" : "GET",
        URI.create(base + query), null, "", NOW.plusSeconds("early".equals(fault) ? -1 : 1));
    if ("valid".equals(fault)) {
      assertDoesNotThrow(() -> ParRequestSteps.verifyAuthorize(actual, ENDPOINT,
          "client", "urn:example:issued", NOW));
    } else {
      assertThrows(AssertionError.class, () -> ParRequestSteps.verifyAuthorize(actual, ENDPOINT,
          "client", "urn:example:issued", NOW));
    }
  }

  @Test
  void parsesRealRbelHttpWithoutSubstitutionAndRejectsDuplicateParameters() {
    var body = "state=%24%7Bliteral%7D&client_id=client";
    var raw = "POST /par HTTP/1.1\r\nHost: guard.example:443\r\n"
        + "Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n"
        + "Content-Length: " + body.length() + "\r\n\r\n" + body;
    var message = RbelLogger.build().getRbelConverter().parseMessage(
        raw.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    message.addOrReplaceFacet(new RbelMessageTimingFacet(NOW.atZone(ZoneOffset.UTC)));
    var observation = ParRequestSteps.observation(message);
    var endpoint = URI.create("https://guard.example/par");
    assertEquals("${literal}", ParRequestSteps.formAt(observation, endpoint).get("state"));
    assertEquals(NOW, observation.sent());
    assertThrows(AssertionError.class, () -> ParRequestSteps.formAt(observation, ENDPOINT));
    var duplicated = new ParRequestSteps.Observation("POST", endpoint,
        observation.contentType(), observation.body() + "&client_id=client", NOW);
    assertThrows(AssertionError.class, () -> ParRequestSteps.formAt(duplicated, endpoint));
  }

  private static HashMap<String, String> outer() {
    return new HashMap<>(Map.of("response_type", "code", "client_id", "client",
        "redirect_uri", APP, "oidc_redirect_uri", OIDC, "code_challenge", "A".repeat(43),
        "code_challenge_method", "S256", "scope", "scope", "state", "outer-state",
        "idp_iss", "https://idp.example"));
  }

  private static void verifyOuter(Map<String, String> form) {
    ParRequestSteps.verifyOuterContext(form, "client", List.of(APP, OIDC), APP, OIDC,
        "scope", "https://idp.example");
  }

}
