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

  /* Schemas */
  private static final String AS_WELL_KNOWN_SCHEMA = "schemas/v_1_0/as-well-known.yaml";
  private static final String ACCESS_TOKEN_SCHEMA = "schemas/v_1_0/access-token.yaml";
  private static final String CLIENT_ASSERTION_JWT_SCHEMA = "schemas/v_1_0/client-assertion-jwt.yaml";
  /** Schema according to gemspec_PoPP. */
  private static final String POPP_TOKEN_GEMSPEC_POPP_SCHEMA = "schemas/mock/popp-token-gemspec_popp.yaml";

  /* JSON Examples */
  private static final String ENCODED_POPP_TOKEN = loadResource("mocks/popp-token_gemspec-popp_encoded.jwt");
  private static final String EXAMPLE_POPP_TOKEN = loadResource("mocks/popp-token_gemspec-popp_example.json");
  private static final String EXAMPLE_ACCESS_TOKEN = loadResource("mocks/access-token_example.json");
  private static final String EXAMPLE_ACCESS_TOKEN_INVALID_HEADER = loadResource("mocks/access-token_example_invalid-header.json");
  private static final String EXAMPLE_ACCESS_TOKEN_INVALID_PAYLOAD = loadResource("mocks/access-token_example_invalid-payload.json");
  private static final String EXAMPLE_AS_WELL_KNOWN = loadResource("mocks/as-well-known_example.json");
  // private static final String VALID_CLIENT_ASSERTION_JWT = loadResource("mocks/client-assertion-jwt-win-software_example-payload.json");
  private static final String VALID_CLIENT_ASSERTION_JWT = loadResource("mocks/client-assertion-jwt_example.json");

  /**
   * The signature of this JWT is not valid, but that doesn't matter for the schema validation.
   */
  private static final String ENCODED_CLIENT_ASSERTION_JWT = loadResource("mocks/client-assertion-jwt_encoded.jwt");

  private final SchemaValidationSteps steps = new SchemaValidationSteps();

  /**
   * Reads the content of the resource.
   *
   * @param resource a path to a text file
   * @return  a string containing the content of the resource
   */
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
        () -> steps.validateJsonAgainstYamlSchema(payload, AS_WELL_KNOWN_SCHEMA));
  }


  /**
   * Verifies that a complete .well-known document passes schema validation.
   */
  @Test
  void wellKnown_passesWithRequiredFields() {

    assertDoesNotThrow(() -> steps.validateJsonAgainstYamlSchema(EXAMPLE_AS_WELL_KNOWN,
        AS_WELL_KNOWN_SCHEMA));
  }

  /**
   * Verifies that an access token structure with all required claims passes validation.
   */
  @Test
  void accessToken_payloadValidated() {

    assertDoesNotThrow(() -> steps.validateJsonAgainstYamlSchema(EXAMPLE_ACCESS_TOKEN,
        ACCESS_TOKEN_SCHEMA));
  }

  /**
   * Verifies that omitting a required claim causes schema validation to fail.
   */
  @Test
  void accessToken_missingRequiredClaimFails() {

    assertThrows(AssertionError.class,
        () -> steps.validateJsonAgainstYamlSchema(EXAMPLE_ACCESS_TOKEN_INVALID_HEADER,
            ACCESS_TOKEN_SCHEMA));
    assertThrows(AssertionError.class,
        () -> steps.validateJsonAgainstYamlSchema(EXAMPLE_ACCESS_TOKEN_INVALID_PAYLOAD,
            ACCESS_TOKEN_SCHEMA));
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
   * Verifies that a popp token verifies correct against the schema.
   */
  @Test
  public void testPoppTokenVerifiesAgainstSchema() {

    assertDoesNotThrow(
        () -> steps.validateJsonAgainstYamlSchema(EXAMPLE_POPP_TOKEN,
            POPP_TOKEN_GEMSPEC_POPP_SCHEMA));
  }

  /**
   * Verifies that a base64 encoded popp token is correctly decoded and validated.
   */
  @Test
  public void testBase64EncodedPoppTokenVerifiesAgainstSchema() {

    assertDoesNotThrow(
        () -> steps.validateEncodedJwtAgainstYamlSchema(ENCODED_POPP_TOKEN,
            POPP_TOKEN_GEMSPEC_POPP_SCHEMA));

  }
}
