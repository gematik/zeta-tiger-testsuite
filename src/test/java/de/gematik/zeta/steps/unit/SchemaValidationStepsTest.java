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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerConfigurationKey;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.steps.SchemaValidationSteps;
import de.gematik.zeta.steps.SoftAssertionsContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SchemaValidationSteps} to ensure project schemas validate expected payloads
 * and reject invalid ones.
 */
class SchemaValidationStepsTest {

  private static final String CC_POLICY_INPUT = """
      {"version":"1.1", "user_info":{
        "identifier":"3-test", "commonName":"Test", "professionOID":"1.2.276.0.76.4.50"},
       "client_registration_data":{
        "client_id":"test", "product_id":"product", "product_version":"1", "platform":"linux",
        "posture_type":"software", "registration_timestamp":1, "device_info":{}, "attestation_result":{}},
       "authorization_request":{
        "resource":"https://resource.example/endpoint", "scopes":["read"], "ip_address":"127.0.0.1",
        "previous_ip_address":"127.0.0.1", "grant_type":"urn:ietf:params:oauth:grant-type:token-exchange",
        "acr":"gematik-ehealth-loa-substantial"}}
      """;

  /** The upstream policy contract resolves its dependencies and requires its original user fields. */
  @Test
  void upstreamPolicyInputResolvesReferencesAndRejectsSpecOnlyUserFields() {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(CC_POLICY_INPUT,
          "schemas/v_2_0/schemas/policy-engine-input.yaml"));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          CC_POLICY_INPUT.replace("commonName", "common_name").replace("professionOID", "profession_oid"),
          "schemas/v_2_0/schemas/policy-engine-input.yaml"));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          CC_POLICY_INPUT.replace("\"platform\":\"linux\"", "\"platform\":\"unsupported\""),
          "schemas/v_2_0/schemas/policy-engine-input.yaml"));
    });
  }

  /** Android posture requires runtime evidence even when the old certificate chain is present. */
  @ParameterizedTest
  @ValueSource(strings = {"nonce", "signed_client_data_hash"})
  void androidPostureRejectsMissingRuntimeEvidence(String field) {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      var posture = """
          {"product_id":"test", "product_version":"1", "build":{},
           "nonce":"bm9uY2U", "signed_client_data_hash":"c2lnbmF0dXJl"}
          """;
      var schema = "schemas/v_2_0/schemas/posture-android.yaml";
      assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(posture, schema));
      var incomplete = field.equals("nonce")
          ? posture.replace("\"nonce\":\"bm9uY2U\", ", "")
          : posture.replace(", \"signed_client_data_hash\":\"c2lnbmF0dXJl\"", "");
      assertThrows(AssertionError.class,
          () -> validator.validateJsonAgainstYamlSchema(incomplete, schema));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          """
          {"product_id":"test", "product_version":"1", "build":{},
           "key_attestation_certificate_chain":["Y2VydA"]}
          """, schema));
    });
  }

  /** The renamed optional chain is validated rather than silently treated as an unknown field. */
  @Test
  void androidPostureValidatesOptionalCertificateChain() {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      var posture = """
          {"product_id":"test", "product_version":"1", "build":{},
           "nonce":"bm9uY2U", "signed_client_data_hash":"c2lnbmF0dXJl",
           "android_key_attestation_certificate_chain":["Y2VydA"]}
          """;
      var schema = "schemas/v_2_0/schemas/posture-android.yaml";
      assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(posture, schema));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          posture.replace("[\"Y2VydA\"]", "\"Y2VydA\""), schema));
    });
  }

  /** Optional user fields retain their upstream constraints, also through the policy reference. */
  @ParameterizedTest
  @ValueSource(strings = {"7", "null", "\"not-a-date\"", "\"2000/12/31\"", "\"2000-02-30\""})
  void upstreamUserInfoRejectsInvalidBirthdate(String birthdate) {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      var validUser = "{\"identifier\":\"id\",\"commonName\":\"name\",\"professionOID\":\"oid\"}";
      var invalidUser = validUser.replace("}", ",\"birthdate\":" + birthdate + "}");
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          invalidUser, "schemas/v_2_0/schemas/zeta-user-info.yaml"));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          CC_POLICY_INPUT.replace("\"identifier\":\"3-test\"",
              "\"birthdate\":" + birthdate + ",\"identifier\":\"3-test\""),
          "schemas/v_2_0/schemas/policy-engine-input.yaml"));
      assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(
          validUser.replace("}", ",\"birthdate\":\"2000-12-31\"}"),
          "schemas/v_2_0/schemas/zeta-user-info.yaml"));
    });
  }

  /** Upstream error objects remain usable through token-response's relative schema reference. */
  @Test
  void ccTokenResponseResolvesErrorContract() {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(
          "{\"error\":\"access_denied\",\"reasons\":{\"denied\":true}}", "schemas/v_2_0/schemas/token-response.yaml"));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          "{\"error\":42}", "schemas/v_2_0/schemas/token-response.yaml"));
    });
  }

  private static final String ALLOW_ADDITIONAL_PROPERTIES_CONFIG =
      "schemaValidation.allowAdditionalProperties";
  private static final String SOFT_ASSERT_CONFIG = "schemaValidation.soft";

  /* Schemas */
  private static final String AS_WELL_KNOWN_SCHEMA = "schemas/v_2_0/schemas/as-well-known.yaml";
  private static final String ACCESS_TOKEN_SCHEMA = "schemas/v_2_0/schemas/access-token.yaml";
  /** Special case: The client-assertion-jwt.yaml references sub-schemas. */
  private static final String CLIENT_ASSERTION_JWT_SCHEMA = "schemas/v_2_0/schemas/client-assertion-jwt.yaml";
  private static final String SUBJECT_TOKEN_SMB_SCHEMA = "schemas/v_2_0/schemas/subject-token-smb.yaml";

  /* JSON Examples */
  private static final String EXAMPLE_ACCESS_TOKEN = loadResource("mocks/access-token_example.json");
  private static final String EXAMPLE_ACCESS_TOKEN_WITH_INVALID_HEADER = EXAMPLE_ACCESS_TOKEN.replaceAll("(?m)^[ \\t]*\"alg\".*(?:\\R|$)", "");
  private static final String EXAMPLE_ACCESS_TOKEN_WITH_INVALID_PAYLOAD = EXAMPLE_ACCESS_TOKEN.replaceAll("(?m)^[ \\t]*\"ip_address\".*(?:\\R|$)", "");
  private static final String EXAMPLE_AS_WELL_KNOWN = loadResource("mocks/as-well-known_example.json");
  private static final String VALID_CLIENT_ASSERTION_JWT = loadResource("mocks/client-assertion-jwt_example.json");

  private static final String SUBJECT_TOKEN_WITH_ADDITIONAL_PAYLOAD_TYPE = """
      {
        "header": {
          "alg": "ES256",
          "typ": "JWT",
          "x5c": [
            "MIICpDCCAYwCCQDU3pQ4pHESpDANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAls"
          ]
        },
        "payload": {
          "jti": "unique-smcb-token-id-abc123",
          "nonce": "bfb76c3e-8b4a-4f91-9d2a-3c7e5f8a1b20",
          "iss": "cid-win-tpm-abcde",
          "sub": "3-SMC-B-Testkarte-883110000129068",
          "aud": [
            "https://auth-server.zeta.gematik.de/token"
          ],
          "exp": 1678888370,
          "iat": 1678888070,
          "typ": "Bearer",
          "client_key": {
            "jkt": "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"
          },
          "dpop_key": {
            "jkt": "0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I"
          }
        }
      }
      """;

  private static final String ENCODED_SUBJECT_TOKEN =
          "eyJ0eXAiOiJKV1QiLCJraWQiOiJ3NjI5MTVJaUw1bzFVZ2o0Q3NHbzR0MUVid3EweDhiNTdwVmFldS1qemFvIiwieDV"
                  + "jIjpbIk1JSURORENDQXR1Z0F3SUJBZ0lIQWxEK0dyZ01uakFLQmdncWhrak9QUVFEQWpDQmxURUxNQWtHQTFV"
                  + "RUJoTUNSRVV4R2pBWUJnTlZCQW9NRVdkbGJXRjBhV3NnVGs5VUxWWkJURWxFTVVnd1JnWURWUVFMREQ5SmJuT"
                  + "jBhWFIxZEdsdmJpQmtaWE1nUjJWemRXNWthR1ZwZEhOM1pYTmxibk10UTBFZ1pHVnlJRlJsYkdWdFlYUnBhMm"
                  + "x1Wm5KaGMzUnlkV3QwZFhJeElEQWVCZ05WQkFNTUYwZEZUUzVUVFVOQ0xVTkJOVGNnVkVWVFZDMVBUa3haTUI"
                  + "0WERUSTFNRFl3TlRJeU1EQXdNRm9YRFRNd01EWXdOVEl4TlRrMU9Wb3dYREVMTUFrR0ExVUVCaE1DUkVVeEhE"
                  + "QWFCZ05WQkFvTUV6TXdNREEyTURZeU5TQk9UMVF0VmtGTVNVUXhMekF0QmdOVkJBTU1Ka0Z5ZW5Sd2NtRjRhW"
                  + "E1nUVc1dUxVSmxZWFJ5YVhobElGcGxkR0VnVkVWVFZDMVBUa3haTUZvd0ZBWUhLb1pJemowQ0FRWUpLeVFEQX"
                  + "dJSUFRRUhBMElBQkZRdUVrTENYNWtKY1dhR1lYZGFSVGRUQWpBaEVrRGw5Q1dXZDh2RkhZR1NobWpoY0ZobTV"
                  + "iSWV4NFIzSkVxZ2h2a1AwZkpnemdvOUF6QWF1Ukx6WkRHamdnRkxNSUlCUnpBT0JnTlZIUThCQWY4RUJBTUNC"
                  + "NEF3REFZRFZSMFRBUUgvQkFJd0FEQXNCZ05WSFI4RUpUQWpNQ0dnSDZBZGhodG9kSFJ3T2k4dlpXaGpZUzVuW"
                  + "lcxaGRHbHJMbVJsTDJOeWJDOHdSUVlGS3lRSUF3TUVQREE2TURnd05qQTBNREl3Rmd3VVFtVjBjbWxsWW5Oem"
                  + "RNT2tkSFJsSUVGeWVuUXdDUVlIS29JVUFFd0VNaE1OTVMweU1EQXhOREEyTURZeU5UQWRCZ05WSFE0RUZnUVV"
                  + "QeDhZMW82QVNBbi80aWlXVjE2OFBtQ3JLek13RXdZRFZSMGxCQXd3Q2dZSUt3WUJCUVVIQXdJd0lBWURWUjBn"
                  + "QkJrd0Z6QUtCZ2dxZ2hRQVRBU0JJekFKQmdjcWdoUUFUQVJOTUI4R0ExVWRJd1FZTUJhQUZMWHZkWDZabWhmS"
                  + "jAzY3ZXeEhGaERNdkJaeFJNRHNHQ0NzR0FRVUZCd0VCQkM4d0xUQXJCZ2dyQmdFRkJRY3dBWVlmYUhSMGNEb3"
                  + "ZMMlZvWTJFdVoyVnRZWFJwYXk1a1pTOWxZMk10YjJOemNEQUtCZ2dxaGtqT1BRUURBZ05IQURCRUFpQVdGeWt"
                  + "4RGNQSzhhdTZRVXJrZ21wZzU5bUdFb2lnbklQRS8rL2pFeURsQ2dJZ1pCV1FCL0FTR2VQanJZV2FpZUl4ekNp"
                  + "MSt3RUJxalZQUTgzeDdET1pEdUE9Il0sImFsZyI6IkVTMjU2In0"
                  + ".eyJpc3MiOiJlN2E0OTEwNy0yMDYxLTRmOTMtODhkZC1jY2M2ZWMzZGVkZjQiLCJleHAiOjE3Nzg1MTY5MTks"
                  + "ImF1ZCI6WyJodHRwczovL3pldGEta2luZC5sb2NhbC9hdXRoLyJdLCJzdWIiOiIxLTIwMDE0MDYwNjI1Iiwia"
                  + "WF0IjoxNzc4NTE2ODg5LCJub25jZSI6Im9memRfWXM5VjVLdFFFdldRdFZHbHciLCJqdGkiOiI0YjQzNGVlMC"
                  + "0wMTUxLTRjZjMtYTI0MS1hMzIwN2E5Y2ZhYzAiLCJ0eXAiOiJCZWFyZXIiLCJjbGllbnRfa2V5Ijp7ImprdCI"
                  + "6IlozZWpzNWpzcDZzSmFGREpWVzZtSUZvc2pST1dSVjd6b0ZRVEtwcmJQYkkifSwiZHBvcF9rZXkiOnsiamt0"
                  + "IjoicWVwT184MDlFN0xrUjExUFRnNmhlY3o4NW92ZC1sQ3labmZjU2NtNUM5RSJ9fQ"
                  + ".lE0buRB4kHpwlXTfPDMb1m1MVoejIiNDi6z7EjYUwgtiNk-2UCffGMu3DusW_-EBH35P29G0G8yUYtjfdV_pOA";

  private final SchemaValidationSteps validator = new SchemaValidationSteps();

  /** Requires an explicit classpath location for selecting a schema. */
  @Test
  void schemaNamesRequireAnExplicitResourcePath() {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(
          EXAMPLE_ACCESS_TOKEN, ACCESS_TOKEN_SCHEMA));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema(
          EXAMPLE_ACCESS_TOKEN, "access-token.yaml"));
    });
  }

  @Test
  void standaloneJsonSchemaDoesNotAcquireOpenApiNullability() {
    var schema = "schemas/mock/dialects/external.yaml";
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema("\"allowed\"", schema));
      assertThrows(AssertionError.class, () -> validator.validateJsonAgainstYamlSchema("null", schema));
    });
  }

  /** Ensures the lifecycle continues after API schema errors, but cannot finish successfully. */
  @Test
  void pusherApiErrorsAreCollectedUntilTheFinalSoftAssertionCheck() {
    var valid = """
        {"pushers":[{"pushkey":"owned","app_id":"app","kind":"http",
        "app_display_name":"App","device_display_name":"Device","lang":"de",
        "data":{"url":"https://example.test/push/v1/"},"append":false}]}
        """;
    var schema = "schemas/push_notifications_openapi/fd_openapi.yaml"
        + "#/paths/~1pushers/get/responses/200/content/application~1json/schema";
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "true", () -> {
      assertDoesNotThrow(() -> validator.validateJsonAgainstOpenApiSchema(valid, schema));
      assertDoesNotThrow(SoftAssertionsContext::assertAll);
      assertDoesNotThrow(() -> validator.validateJsonAgainstOpenApiSchema(
          valid.replace("\"append\":false", "\"append\":null"), schema));
      // A later operation is still validated instead of being skipped.
      assertDoesNotThrow(() -> validator.validateJsonAgainstOpenApiSchema("{\"pushers\":[]}", schema));
      assertThrows(AssertionError.class, SoftAssertionsContext::assertAll);
      assertDoesNotThrow(SoftAssertionsContext::assertAll);
    });
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () ->
        assertThrows(AssertionError.class, () -> validator.validateJsonAgainstOpenApiSchema(
            valid.replace("\"append\":false", "\"append\":null"), schema)));
  }

  /**
   * Cleanup soft assertions after each test if necessary.
   */
  @AfterEach
  void resetSoftAssertionsContext() {
    SoftAssertionsContext.reset();
  }

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
   * Executes an assertion with one Tiger configuration value overridden in test context.
   *
   * @param configKey Tiger configuration key
   * @param value     temporary value
   * @param assertion assertion to execute
   */
  private static void withTigerConfigValue(String configKey, String value, Runnable assertion) {
    var originalValue = TigerGlobalConfiguration.readStringOptional(configKey);
    TigerGlobalConfiguration.putValue(configKey, value, ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      assertion.run();
    } finally {
      originalValue.ifPresentOrElse(
          previousValue -> TigerGlobalConfiguration.putValue(configKey, previousValue,
              ConfigurationValuePrecedence.TEST_CONTEXT),
          () -> TigerGlobalConfiguration.deleteFromAllSources(
              new TigerConfigurationKey(configKey.split("\\."))));
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

    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false",
        () -> assertThrows(AssertionError.class,
            () -> validator.validateJsonAgainstYamlSchema(payload, AS_WELL_KNOWN_SCHEMA)));
  }


  /**
   * Verifies that a complete .well-known document passes schema validation.
   */
  @Test
  void wellKnown_passesWithRequiredFields() {
    assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(EXAMPLE_AS_WELL_KNOWN,
        AS_WELL_KNOWN_SCHEMA));
  }

  /**
   * Verifies that an access token structure with all required claims passes validation.
   */
  @Test
  void accessToken_payloadValidated() {
    assertDoesNotThrow(() -> validator.validateJsonAgainstYamlSchema(EXAMPLE_ACCESS_TOKEN,
        ACCESS_TOKEN_SCHEMA));
  }

  /**
   * Verifies that omitting a required claim causes schema validation to fail.
   */
  @Test
  void testMissingRequiredClaimFails() {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false", () -> {
      assertThrows(AssertionError.class,
          () -> validator.validateJsonAgainstYamlSchema(EXAMPLE_ACCESS_TOKEN_WITH_INVALID_HEADER,
              ACCESS_TOKEN_SCHEMA));
      assertThrows(AssertionError.class,
          () -> validator.validateJsonAgainstYamlSchema(EXAMPLE_ACCESS_TOKEN_WITH_INVALID_PAYLOAD,
              ACCESS_TOKEN_SCHEMA));
    });
  }

  /**
   * Verifies that a base64 encoded token is correctly decoded and validated.
   */
  @Test
  void testBase64EncodedJwtVerifiesAgainstSchema() {
    assertDoesNotThrow(
        () -> validator.validateEncodedJwtAgainstYamlSchema(ENCODED_SUBJECT_TOKEN,
            SUBJECT_TOKEN_SMB_SCHEMA));
  }

  /**
   * Verifies that a jwt verifies correct against a schema with referenced schemas.
   */
  @Test
  void testJwtVerifiesAgainstSchemaWithRef() {
    assertDoesNotThrow(
        () -> validator.validateJsonAgainstYamlSchema(VALID_CLIENT_ASSERTION_JWT,
            CLIENT_ASSERTION_JWT_SCHEMA));
  }

  /**
   * Verifies that the global "soft assert" switch is considered correctly.
   */
  @Test
  public void testTigerConfigEnablesSoftAssertion() {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "true", () -> {
      assertDoesNotThrow(
          () -> validator.validateJsonAgainstYamlSchema(EXAMPLE_ACCESS_TOKEN_WITH_INVALID_HEADER,
              ACCESS_TOKEN_SCHEMA));
      assertThrows(AssertionError.class, SoftAssertionsContext::assertAll);
    });
  }

  /**
   * Verifies that the Tiger configuration switch disables additional properties for regular schema
   * steps.
   */
  @Test
  public void testTigerConfigDisablesAdditionalProperties() {
    withTigerConfigValue(SOFT_ASSERT_CONFIG, "false",
        () -> withTigerConfigValue(ALLOW_ADDITIONAL_PROPERTIES_CONFIG, "false",
            () -> assertThrows(AssertionError.class,
                () -> validator.validateJsonAgainstYamlSchema(
                    SUBJECT_TOKEN_WITH_ADDITIONAL_PAYLOAD_TYPE, SUBJECT_TOKEN_SMB_SCHEMA))));
  }

}
