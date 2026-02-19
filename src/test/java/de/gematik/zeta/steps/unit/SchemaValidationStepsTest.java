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

package de.gematik.zeta.steps.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.zeta.steps.SchemaValidationSteps;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SchemaValidationSteps} to ensure project schemas validate expected payloads
 * and reject invalid ones.
 */
class SchemaValidationStepsTest {

  private static final String WELL_KNOWN_SCHEMA = "schemas/v_1_0/as-well-known.yaml";
  private static final String ACCESS_TOKEN_SCHEMA = "schemas/v_1_0/access-token.yaml";
  private static final String CLIENT_ASSERTION_JWT_SCHEMA = "schemas/v_1_0/client-assertion-jwt.yaml";
  private static final String POPP_TOKEN_SCHEMA = "schemas/v_1_0/popp-token.yaml";
  private static final String POPP_TOKEN_GEMSPEC_POPP_SCHEMA = "schemas/mock/popp-token-gemspec_popp.yaml";

  private final SchemaValidationSteps steps = new SchemaValidationSteps();

  private static final String ENCODED_POPP_TOKEN = loadResource("mocks/encoded_popp_token.jwt");
  private static final String EXAMPLE_POPP_TOKEN = loadResource("mocks/example_popp_token.json");
  private static final String VALID_CLIENT_ASSERTION_JWT = loadResource("mocks/example_client-assertion-jwt.json");
  /**
   * The signature of this JWT is not valid, but that doesn't matter for the schema validation.
   */
  private static final String ENCODED_CLIENT_ASSERTION_JWT = loadResource("mocks/encoded_client-assertion-token.jwt");

  private static String loadResource(String resource) {
    final String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
    try (InputStream in =
        SchemaValidationStepsTest.class.getClassLoader().getResourceAsStream(normalized)) {
      if (in == null) {
        throw new IllegalArgumentException("Resource not found: " + normalized);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read resource: " + normalized, e);
    }
  }

  /**
   * Verifies that missing required .well-known fields trigger validation errors.
   */
  @Test
  void wellKnown_failsWhenMissingRequired() {
    var payload = """
        {
          "issuer": "https://issuer.example"
        }
        """;

    assertThrows(AssertionError.class,
        () -> steps.validateJsonAgainstYamlSchema(payload, WELL_KNOWN_SCHEMA));
  }


  /**
   * Verifies that a complete .well-known document passes schema validation.
   */
  @Test
  void wellKnown_passesWithRequiredFields() {
    var payload = """
        {
          "issuer": "https://issuer.example",
          "authorization_endpoint": "https://issuer.example/auth",
          "token_endpoint": "https://issuer.example/token",
          "nonce_endpoint": "https://issuer.example/nonce",
          "openid_providers_endpoint": "https://issuer.example/openid",
          "jwks_uri": "https://issuer.example/jwks.json",
          "scopes_supported": ["openid"],
          "response_types_supported": ["code"],
          "grant_types_supported": ["authorization_code"],
          "token_endpoint_auth_methods_supported": ["private_key_jwt"],
          "token_endpoint_auth_signing_alg_values_supported": ["ES256"],
          "ui_locales_supported": ["de-DE"],
          "code_challenge_methods_supported": ["S256"]
        }
        """;

    assertDoesNotThrow(() -> steps.validateJsonAgainstYamlSchema(payload, WELL_KNOWN_SCHEMA));
  }

  /**
   * Verifies that an access token structure with all required claims passes validation.
   */
  @Test
  void accessToken_payloadValidated() {
    var accessTokenJson = """
        {
          "header": {
            "typ": "at+jwt",
            "alg": "ES256",
            "x5c": ["BASE64CERT"]
          },
          "payload": {
            "iss": "https://issuer.example",
            "exp": 1914067200,
            "aud": ["api"],
            "sub": "subject",
            "client_id": "client-123",
            "iat": 1914067100,
            "jti": "id-123",
            "scope": "openid",
            "cnf": { "jkt": "thumbprint" }
          }
        }
        """;

    assertDoesNotThrow(() -> steps.validateJsonAgainstYamlSchema(accessTokenJson,
        ACCESS_TOKEN_SCHEMA));
  }

  /**
   * Verifies that omitting a required claim causes schema validation to fail.
   */
  @Test
  void accessToken_missingRequiredClaimFails() {
    var accessTokenJson = """
        {
          "header": {
            "typ": "at+jwt",
            "alg": "ES256",
            "x5c": ["BASE64CERT"]
          },
          "payload": {
            "iss": "https://issuer.example",
            "exp": 1914067200,
            "aud": ["api"],
            "client_id": "client-123",
            "iat": 1914067100,
            "jti": "id-123",
            "cnf": { "jkt": "thumbprint" }
          }
        }
        """;

    assertThrows(AssertionError.class,
        () -> steps.validateJsonAgainstYamlSchema(accessTokenJson, ACCESS_TOKEN_SCHEMA));
  }

  /**
   * Verifies that a base64 encoded token is correctly decoded and validated.
   */
  @Test
  public void testBase64EncodedJwtVerifiesAgainstSchema() {

    assertDoesNotThrow(
        () -> steps.validateEncodedJwtAgainstYamlSchema(ENCODED_CLIENT_ASSERTION_JWT,
            CLIENT_ASSERTION_JWT_SCHEMA));

  }

  /**
   * Verifies that a jwt verifies correct against a schema with referenced schema.
   */
  @Test
  public void testJwtVerifiesAgainstSchemaWithRef() {

    assertDoesNotThrow(
        () -> steps.validateJsonAgainstYamlSchema(VALID_CLIENT_ASSERTION_JWT,
            CLIENT_ASSERTION_JWT_SCHEMA));
  }

  /**
   * Verifies that a jwt verifies correct against a schema with referenced schema.
   */
  @Test
  public void testJwtVerifiesSoftlyAgainstSchemaWithRef() {

    assertDoesNotThrow(
        () -> steps.softlyValidateJsonAgainstYamlSchema(VALID_CLIENT_ASSERTION_JWT,
            CLIENT_ASSERTION_JWT_SCHEMA));
  }

  /**
   * Verifies that a jwt verifies correct against a schema with referenced schema.
   */
  @Test
  public void testPoppTokenVerifiesAgainstSchemaWithRef() {

    assertDoesNotThrow(
        () -> steps.validateJsonAgainstYamlSchema(EXAMPLE_POPP_TOKEN,
                POPP_TOKEN_GEMSPEC_POPP_SCHEMA));
  }

  /**
   * Verifies that a base64 encoded token is correctly decoded and validated.
   */
  @Test
  public void testBase64EncodedPoppTokenVerifiesAgainstSchema() {

    assertDoesNotThrow(
        () -> steps.validateEncodedJwtAgainstYamlSchema(ENCODED_POPP_TOKEN,
                POPP_TOKEN_GEMSPEC_POPP_SCHEMA));

  }
}
