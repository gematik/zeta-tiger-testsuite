/*-
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
  private static final String CLIENT_ASSERTION_JWT_SCHEMA = "client-assertion-jwt.yaml";
  // private static final String POPP_TOKEN_SCHEMA = "popp-token.yaml";
  private static final String POPP_TOKEN_SCHEMA = "schemas/mock/popp-token-gemspec_popp.yaml";

  private final SchemaValidationSteps steps = new SchemaValidationSteps();

  private static final String ENCODED_POPP_TOKEN = loadResource("mocks/encoded_popp_token.jwt");

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

  private static final String EXAMPLE_POPP_TOKEN = loadResource("mocks/example_popp_token.json");

  /**
   * The sub-element "client_statement" comes from referenced schema file.
   */
  private static final String VALID_CLIENT_ASSERTION_JWT = """
      {
          "header": {
              "typ": "jwt",
              "alg": "ES256",
              "jwk": {
                  "kid": "tXz0SP3vfu_KzENmVDbZNtcg5uP-ogpk_9QZT8dDe3o",
                  "kty": "EC",
                  "alg": "ES256",
                  "use": "sig",
                  "crv": "P-256",
                  "x": "bN5XcNDtNxE_y_OYKuxg4VsncdZIGRsXxvKVoBTseuw",
                  "y": "KnHyxGQqrng_2ocR13Ss-X7KoxyXS5xE-qXGdJ2zQp4"
              }
          },
          "payload": {
              "iss": "b8e8899c-14e1-415f-b534-1448e8aa3b57",
              "sub": "b8e8899c-14e1-415f-b534-1448e8aa3b57",
              "aud": [
                  "https://zeta-kind.local/auth/realms/zeta-guard/protocol/openid-connect/token"
              ],
              "exp": 1764599351,
              "jti": "[B@1f54480b",
              "client_statement": {
                  "sub": "b8e8899c-14e1-415f-b534-1448e8aa3b57",
                  "platform": "linux",
                  "posture": {
                      "platform_product_id": {
                          "platform": "linux",
                          "packaging_type": "deb",
                          "application_id": "org.mozilla.firefox"
                      },
                      "product_id": "test_proxy",
                      "product_version": "0.1.0",
                      "os": "Linux",
                      "os_version": "5.15.167.4-microsoft-standard-WSL2",
                      "arch": "amd64",
                      "public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbN5XcNDtNxE_y_OYKuxg4VsncdZIGRsXxvKVoBTseuwqcfLEZCqueD_ahxHXdKz5fsqjHJdLnET6pcZ0nbNCng",
                      "attestation_challenge": "nBfYfqy91Zr6gjjMn9J/CM7CH46VoD1DmUv+RXKvXYE="
                  },
                  "product_id": "test_proxy",
                  "product_version": "0.1.0",
                  "os": "Linux",
                  "os_version": "5.15.167.4-microsoft-standard-WSL2",
                  "arch": "amd64",
                  "public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbN5XcNDtNxE_y_OYKuxg4VsncdZIGRsXxvKVoBTseuwqcfLEZCqueD_ahxHXdKz5fsqjHJdLnET6pcZ0nbNCng",
                  "attestation_challenge": "nBfYfqy91Zr6gjjMn9J/CM7CH46VoD1DmUv+RXKvXYE=",
                  "attestation_timestamp": 1764599321
              }
          }
      }
      """;

  /**
   * The signature of this JWT is not valid, but that doesn't matter for the schema validation.
   */
  private static final String ENCODED_CLIENT_ASSERTION_JWT =
      "eyJ0eXAiOiJqd3QiLCJhbGciOiJFUzI1NiIsImp3ayI6eyJraWQiOiJ0WHowU1AzdmZ1X0t6RU5tVkRiWk50Y2c1dVA"
          + "tb2dwa185UVpUOGREZTNvIiwia3R5IjoiRUMiLCJhbGciOiJFUzI1NiIsInVzZSI6InNpZyIsImNydiI6IlAt"
          + "MjU2IiwieCI6ImJONVhjTkR0TnhFX3lfT1lLdXhnNFZzbmNkWklHUnNYeHZLVm9CVHNldXciLCJ5IjoiS25Ie"
          + "XhHUXFybmdfMm9jUjEzU3MtWDdLb3h5WFM1eEUtcVhHZEoyelFwNCJ9fQ"
          + "."
          + "eyJpc3MiOiJiOGU4ODk5Yy0xNGUxLTQxNWYtYjUzNC0xNDQ4ZThhYTNiNTciLCJzdWIiOiJiOGU4ODk5Yy0xN"
          + "GUxLTQxNWYtYjUzNC0xNDQ4ZThhYTNiNTciLCJhdWQiOlsiaHR0cHM6Ly96ZXRhLWxvY2FsLndlc3RldXJvcG"
          + "UuY2xvdWRhcHAuYXp1cmUuY29tL2F1dGgvcmVhbG1zL3pldGEtZ3VhcmQvcHJvdG9jb2wvb3BlbmlkLWNvbm5"
          + "lY3QvdG9rZW4iXSwiZXhwIjoxNzY0NTk5MzUxLCJqdGkiOiJbQkAxZjU0NDgwYiIsImNsaWVudF9zdGF0ZW1l"
          + "bnQiOnsic3ViIjoiYjhlODg5OWMtMTRlMS00MTVmLWI1MzQtMTQ0OGU4YWEzYjU3IiwicGxhdGZvcm0iOiJsa"
          + "W51eCIsInBvc3R1cmUiOnsicHJvZHVjdF9pZCI6InRlc3RfcHJveHkiLCJwcm9kdWN0X3ZlcnNpb24iOiIwLj"
          + "EuMCIsIm9zIjoiTGludXgiLCJvc192ZXJzaW9uIjoiNS4xNS4xNjcuNC1taWNyb3NvZnQtc3RhbmRhcmQtV1N"
          + "MMiIsImFyY2giOiJhbWQ2NCIsInB1YmxpY19rZXkiOiJNRmt3RXdZSEtvWkl6ajBDQVFZSUtvWkl6ajBEQVFj"
          + "RFFnQUViTjVYY05EdE54RV95X09ZS3V4ZzRWc25jZFpJR1JzWHh2S1ZvQlRzZXV3cWNmTEVaQ3F1ZURfYWh4S"
          + "FhkS3o1ZnNxakhKZExuRVQ2cGNaMG5iTkNuZyIsImF0dGVzdGF0aW9uX2NoYWxsZW5nZSI6Im5CZllmcXk5MV"
          + "pyNmdqak1uOUovQ003Q0g0NlZvRDFEbVV2K1JYS3ZYWUU9In0sInByb2R1Y3RfaWQiOiJ0ZXN0X3Byb3h5Iiw"
          + "icHJvZHVjdF92ZXJzaW9uIjoiMC4xLjAiLCJvcyI6IkxpbnV4Iiwib3NfdmVyc2lvbiI6IjUuMTUuMTY3LjQt"
          + "bWljcm9zb2Z0LXN0YW5kYXJkLVdTTDIiLCJhcmNoIjoiYW1kNjQiLCJwdWJsaWNfa2V5IjoiTUZrd0V3WUhLb"
          + "1pJemowQ0FRWUlLb1pJemowREFRY0RRZ0FFYk41WGNORHROeEVfeV9PWUt1eGc0VnNuY2RaSUdSc1h4dktWb0"
          + "JUc2V1d3FjZkxFWkNxdWVEX2FoeEhYZEt6NWZzcWpISmRMbkVUNnBjWjBuYk5DbmciLCJhdHRlc3RhdGlvbl9"
          + "jaGFsbGVuZ2UiOiJuQmZZZnF5OTFacjZnampNbjlKL0NNN0NINDZWb0QxRG1VditSWEt2WFlFPSIsImF0dGVz"
          + "dGF0aW9uX3RpbWVzdGFtcCI6MTc2NDU5OTMyMX19"
          + "."
          + "W3F0yI-EoA6AdPlsOVLPBOnqa-ZbgD2hD8efWxC47TKIBryP0kMuvWK17VU5cewDy9B0LJa2hDl-fHDNw495Dw";

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
            POPP_TOKEN_SCHEMA));
  }

  /**
   * Verifies that a base64 encoded token is correctly decoded and validated.
   */
  @Test
  public void testBase64EncodedPoppTokenVerifiesAgainstSchema() {

    assertDoesNotThrow(
        () -> steps.validateEncodedJwtAgainstYamlSchema(ENCODED_POPP_TOKEN,
            POPP_TOKEN_SCHEMA));

  }
}
