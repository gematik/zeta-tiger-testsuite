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

import io.restassured.builder.ResponseBuilder;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the normative response alternatives against overly strict or permissive assertions. */
class RevocationResponseTest {
  @AfterEach
  void clearSoftAssertions() {
    SoftAssertionsContext.reset();
  }

  @Test
  void acceptsEmptySuccessfulResponse() {
    assertDoesNotThrow(() -> verify(200, ""));
  }

  @Test
  void checksStatusCodeAndEveryTokenFieldIndependently() {
    var response = new ResponseBuilder().setStatusCode(400)
        .setBody("{\"error\":\"invalid_grant\",\"access_token\":null,"
            + "\"refresh_token\":null,\"zeta_attestation_token\":null}").build();
    var failure = assertThrows(AssertionError.class,
        () -> RevocationSteps.verifyTokenError(response, 403, "refresh_token_revoked"));
    for (var label : new String[] {"HTTP status", "response error", "omit access_token",
        "omit refresh_token", "omit zeta_attestation_token"}) {
      assertTrue(failure.getMessage().contains(label));
    }
  }

  @Test
  void rejectsMalformedErrorSchemaDespiteCorrectTokenStatusAndCode() {
    var response = new ResponseBuilder().setStatusCode(403)
        .setBody("{\"error\":\"refresh_token_revoked\",\"error_description\":42}").build();
    assertThrows(AssertionError.class, () -> {
      RevocationSteps.verifyTokenError(response, 403, "refresh_token_revoked");
      SoftAssertionsContext.assertAll();
    });
  }

  @Test
  void acceptsTheCompleteTokenErrorContractAndRejectsMissingResponse() {
    var response = new ResponseBuilder().setStatusCode(403)
        .setBody("{\"error\":\"refresh_token_revoked\"}").build();
    assertDoesNotThrow(() -> RevocationSteps.verifyTokenError(response, 403, "refresh_token_revoked"));
    assertThrows(AssertionError.class,
        () -> RevocationSteps.verifyTokenError(null, 403, "refresh_token_revoked"));
  }

  @Test
  void expiredOrNotYetValidRefreshTokenCannotProveRevocation() throws Exception {
    var key = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(
        com.nimbusds.jose.jwk.Curve.P_256).generate();
    var now = Instant.now();
    for (var offset : new int[] {-1, 0, 10, 60}) {
      var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
          .expirationTime(Date.from(now.plusSeconds(offset))).build();
      var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(
          com.nimbusds.jose.JWSAlgorithm.ES256), claims);
      jwt.sign(new com.nimbusds.jose.crypto.ECDSASigner(key));
      if (offset <= 10) {
        assertThrows(AssertionError.class,
            () -> RevocationSteps.verifyRefreshLifetime(jwt.serialize(), now));
      } else {
        assertDoesNotThrow(() -> RevocationSteps.verifyRefreshLifetime(jwt.serialize(), now));
      }
    }
    var future = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(
        com.nimbusds.jose.JWSAlgorithm.ES256), new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .expirationTime(Date.from(now.plusSeconds(60)))
        .notBeforeTime(Date.from(now.plusSeconds(1))).build());
    future.sign(new com.nimbusds.jose.crypto.ECDSASigner(key));
    assertThrows(AssertionError.class,
        () -> RevocationSteps.verifyRefreshLifetime(future.serialize(), now));
  }

  @Test
  void acceptsExplicitUnsupportedTokenTypeAlternative() {
    assertDoesNotThrow(() -> verify(400, "{\"error\":\"unsupported_token_type\"}"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"error\":\"invalid_client\"}", "{\"error\":\"invalid_request\"}",
      "{\"error\":\"unsupported_token_type\",\"access_token\":\"unexpected\"}",
      "{\"error\":\"unsupported_token_type\",\"refresh_token\":\"unexpected\"}",
      "{\"error\":\"unsupported_token_type\",\"error_description\":42}"})
  void rejectsOtherErrorsAndUnexpectedTokenIssuance(String body) {
    assertThrows(AssertionError.class, () -> verify(400, body));
  }

  @ParameterizedTest
  @ValueSource(ints = {201, 204, 401, 403, 404, 500})
  void rejectsUnexpectedStatusEvenWithMatchingErrorCode(int status) {
    assertThrows(AssertionError.class, () -> verify(status, "{\"error\":\"unsupported_token_type\"}"));
  }

  private void verify(int status, String body) {
    RevocationSteps.verifyAccessTokenAlternative(new ResponseBuilder().setStatusCode(status).setBody(body).build());
    // Reproduce the scenario's mandatory after-hook when schema validation is configured as soft.
    SoftAssertionsContext.assertAll();
  }
}
