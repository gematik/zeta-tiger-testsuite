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

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.text.ParseException;
import java.util.Comparator;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Step definitions for validating JSON instances against JSON/YAML schemas using the networknt JSON Schema validator.
 */
@Slf4j
public class SchemaValidationSteps {

  private static final String ALLOW_ADDITIONAL_PROPERTIES_CONFIG =
      "schemaValidation.allowAdditionalProperties";
  private static final String SOFT_ASSERT = "schemaValidation.soft";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  /**
   * Shared registry for loading schemas using the networknt 3.x API with a Draft-7 default (used when a schema does not provide
   * $schema).
   */
  private static final SchemaRegistry SCHEMA_REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
  private static final SchemaRegistry STRICT_SCHEMA_REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7,
          builder -> builder.schemaCacheEnabled(false));

  /**
   * Reads whether regular schema validation should allow additional object properties.
   * Returns {@code true} by default if the global property {@value ALLOW_ADDITIONAL_PROPERTIES_CONFIG} is not set.
   *
   * @return true if additional object properties are allowed
   */
  private boolean isAdditionalPropertiesAllowed() {
    return TigerGlobalConfiguration.readBooleanOptional(ALLOW_ADDITIONAL_PROPERTIES_CONFIG)
        .orElse(true);
  }

  /**
   * Reads whether validation errors should break the scenario and skip the following steps.
   * Returns {@code false} by default if the global property {@value SOFT_ASSERT} is not set.
   *
   * @return true if soft assert is active
   */
  private boolean isSoftAssertActive() {
    return TigerGlobalConfiguration.readBooleanOptional(SOFT_ASSERT)
        .orElse(false);
  }

  /**
   * Loads a YAML schema file from the classpath (resources directory).
   *
   * @param schemaName name or relative path of the schema on the classpath
   * @return {@link Schema} configured with the schema's base location
   */
  private Schema loadYamlSchema(String schemaName) {
    return loadYamlSchema(schemaName, !isAdditionalPropertiesAllowed());
  }

  /**
   * Loads a YAML schema file from the classpath and optionally makes every object schema strict.
   *
   * @param schemaName name or relative path of the schema on the classpath
   * @param strict     if true, object schemas without an explicit {@code additionalProperties} declaration are copied with
   *                   {@code additionalProperties: false}
   * @return {@link Schema} configured with the schema's base location
   */
  private Schema loadYamlSchema(String schemaName, boolean strict) {
    var normalizedPath = schemaName.startsWith("/") ? schemaName.substring(1) : schemaName;
    if (!normalizedPath.startsWith("schemas/")) {
      normalizedPath = "schemas/v_1_0/" + normalizedPath;
    }

    var resource = SchemaValidationSteps.class.getClassLoader().getResource(normalizedPath);
    if (resource == null) {
      throw new AssertionError("Schema not found on the classpath: " + normalizedPath);
    }

    var location = SchemaLocation.of("classpath:" + normalizedPath);
    if (!strict) {
      return SCHEMA_REGISTRY.getSchema(location);
    }

    try (var schemaStream = resource.openStream()) {
      var strictSchemaNode = YAML.readTree(schemaStream).deepCopy();
      disallowAdditionalProperties(strictSchemaNode);
      return STRICT_SCHEMA_REGISTRY.getSchema(location, strictSchemaNode);
    } catch (IOException e) {
      throw new AssertionError("Schema could not be read from the classpath: " + normalizedPath,
          e);
    }
  }

  /**
   * Recursively injects {@code additionalProperties: false} into object schemas that do not already define their own additional-properties
   * behavior.
   *
   * @param node the schema node to adjust in-place
   */
  private void disallowAdditionalProperties(JsonNode node) {
    if (node instanceof ObjectNode objectNode) {
      objectNode.properties().forEach(entry -> disallowAdditionalProperties(entry.getValue()));

      if (isObjectSchema(objectNode) && !objectNode.has("additionalProperties")) {
        objectNode.put("additionalProperties", false);
      }
    } else if (node.isArray()) {
      node.forEach(this::disallowAdditionalProperties);
    }
  }

  /**
   * Determines whether a JSON Schema node describes an object.
   *
   * @param objectNode the schema node to inspect
   * @return true if the node is an object schema
   */
  private boolean isObjectSchema(ObjectNode objectNode) {
    var type = objectNode.get("type");
    if (type != null && type.isString() && "object".equals(type.asString())) {
      return true;
    }
    if (type != null && type.isArray()) {
      for (JsonNode typeEntry : type) {
        if (typeEntry.isString() && "object".equals(typeEntry.asString())) {
          return true;
        }
      }
    }
    return objectNode.has("properties");
  }

  /**
   * Validates a JSON string against a given {@link Schema}.
   *
   * <p>With the soft option enabled, errors will be logged only and no exception is thrown.</p>
   *
   * <p>If the JSON is empty, cannot be parsed, or does not match the schema,
   * an {@link AssertionError} is thrown with a detailed error message.
   *
   * @param schema     the JSON Schema to validate against
   * @param jsonNode   the JSON string to validate
   * @param schemaPath identifier or path of the schema (used for error reporting)
   * @param soft       if true, errors will only be logged.
   * @throws AssertionError if validation fails
   */
  private void assertValid(Schema schema, JsonNode jsonNode, String schemaPath, boolean soft) {

    try {
      var errors = schema.validate(jsonNode);
      if (!errors.isEmpty()) {
        var sb = new StringBuilder(
            "Validation against " + schemaPath + " failed with " + errors.size()
                + " errors:\n");
        errors.stream()
            .sorted(Comparator.comparing(Error::getMessage))
            .forEach(e -> {
              sb.append(" - ").append(e.getMessage());
              var path = e.getEvaluationPath();
              if (path != null) {
                sb.append(" [path: ").append(path).append("]");
              }
              sb.append("\n");
            });
        throw new AssertionError(sb.toString());
      } else {
        // Im Gutfall ist es egal, ob soft oder interrupting geprüft wird
        log.info("Validation passed for schema {}", schemaPath);
      }
    } catch (AssertionError | RuntimeException ex) {
      if (soft) {
        log.warn("Soft validation failed for schema {}: {}", schemaPath, ex.getMessage());
        SoftAssertionsContext.recordSoftFailure("Schema validation (soft) for " + schemaPath, ex);
      } else {
        throw ex;
      }
    }
  }

  /**
   * Cucumber step definition for validating a JSON string against a schema loaded from the resources directory.
   *
   * @param jsonString the JSON string to validate
   * @param schemaPath relative path of the schema under {@code resources}
   */
  @Dann("validiere {tigerResolvedString} gegen Schema {string}")
  @Then("validate {tigerResolvedString} against schema {string}")
  public void validateJsonAgainstYamlSchema(String jsonString, String schemaPath) {
    var schema = loadYamlSchema(schemaPath);
    JsonNode jsonNode = CheckMessageSteps.parseJsonString(jsonString, false);
    assertValid(schema, jsonNode, schemaPath, isSoftAssertActive());
  }

  /**
   * Cucumber step definition for validating a Base64 coded JSON string against a schema loaded from the resources directory.
   *
   * <p>The encoded token is expected to consist of at least two parts separated by dots:
   *   <ol>
   *     <li>header</li>
   *     <li>payload</li>
   *     <li>optional: signature</li>
   *    </ol>
   * </p>
   *
   * @param encodedJwt the Base64 coded JWT to be validated
   * @param schemaName relative path of the schema under {@code resources}
   */
  @Dann("decodiere und validiere {tigerResolvedString} gegen Schema {string}")
  @Then("decode and validate {tigerResolvedString} against schema {string}")
  public void validateEncodedJwtAgainstYamlSchema(String encodedJwt, String schemaName) {
    var schema = loadYamlSchema(schemaName);
    var jsonNode = decodeJwt(encodedJwt);
    assertValid(schema, jsonNode, schemaName, isSoftAssertActive());
  }

  /**
   * Decodes a Base64URL encoded JWT.
   *
   * @param encodedToken the Base64URL coded JWT to be validated
   * @return the decoded json string
   */
  private ObjectNode decodeJwt(String encodedToken) {

    try {
      SignedJWT signedJwt = SignedJWT.parse(encodedToken);

      ObjectNode jsNode = JSON.createObjectNode();
      jsNode.set("header", JSON.valueToTree(signedJwt.getHeader().toJSONObject()));
      jsNode.set("payload", JSON.readTree(signedJwt.getPayload().toString()));

      return jsNode;

    } catch (ParseException | JacksonException e) {
      throw new AssertionError("signed JWT could not be parsed.");
    }
  }

}
