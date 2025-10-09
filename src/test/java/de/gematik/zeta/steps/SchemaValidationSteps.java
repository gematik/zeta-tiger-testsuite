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
package de.gematik.zeta.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Set;

/**
 * Step definitions for validating JSON instances against JSON/YAML schemas using the networknt JSON
 * Schema validator.
 */
public class SchemaValidationSteps {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  /**
   * JsonSchemaFactory for JSON Schema validation using the Draft 7 specification.
   */
  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
      SpecVersion.VersionFlag.V7);

  /**
   * Loads a YAML or JSON schema file from the classpath (resources directory).
   *
   * @param schemaPath relative path to the schema under {@code resources}
   * @return parsed {@link JsonSchema} instance
   * @throws IOException              if the schema cannot be read or parsed
   * @throws IllegalArgumentException if the schema resource cannot be found
   */
  private static JsonSchema loadSchemaFromClasspath(String schemaPath) throws IOException {
    String cp = schemaPath.trim();
    final String resource = cp.startsWith("/") ? cp.substring(1) : cp;

    try (InputStream in = SchemaValidationSteps.class.getClassLoader()
        .getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException(
            "Schema not found in the path under resources: " + resource);
      }
      String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      JsonNode schemaNode =
          resource.endsWith(".json") ? JSON.readTree(schema) : YAML.readTree(schema);
      return FACTORY.getSchema(schemaNode);
    }
  }

  /**
   * Validates a JSON string against a given {@link JsonSchema}.
   *
   * <p>If the JSON is empty, cannot be parsed, or does not match the schema,
   * an {@link AssertionError} is thrown with a detailed error message.
   *
   * @param schema       the JSON Schema to validate against
   * @param jsonInstance the JSON string to validate
   * @param schemaPath   identifier or path of the schema (used for error reporting)
   * @throws AssertionError if validation fails
   */
  private static void assertValid(JsonSchema schema, String jsonInstance, String schemaPath) {
    if (jsonInstance == null || jsonInstance.isBlank()) {
      throw new AssertionError("JSON text to be validated is empty.");
    }
    JsonNode instance;
    try {
      instance = JSON.readTree(jsonInstance);
    } catch (Exception parse) {
      // If the input is longer than 300 characters, show only the beginning
      String preview =
          jsonInstance.length() > 300 ? jsonInstance.substring(0, 300) + " …" : jsonInstance;
      throw new AssertionError("JSON could not be parsed:\n" + preview, parse);
    }

    Set<ValidationMessage> errors = schema.validate(instance);
    if (!errors.isEmpty()) {
      StringBuilder sb = new StringBuilder("Schema validation failed (" + schemaPath + "):\n");
      errors.stream()
          .sorted(Comparator.comparing(ValidationMessage::getMessage))
          .forEach(e -> sb.append(" - ").append(e.getMessage()).append("\n"));
      throw new AssertionError(sb.toString());
    }
  }

  /**
   * Cucumber step definition for validating a JSON string against a schema loaded from the
   * resources directory.
   *
   * <p>German: {@code @Dann("validiere {tigerResolvedString} gegen Schema {string}")}<br> English:
   * {@code @Then("validate {tigerResolvedString} against schema {string}")}.
   *
   * @param json       the JSON string to validate
   * @param schemaPath relative path of the schema under {@code resources}
   * @throws IOException    if the schema cannot be loaded
   * @throws AssertionError if the JSON is invalid against the schema
   */
  @Dann("validiere {tigerResolvedString} gegen Schema {string}")
  @Then("validate {tigerResolvedString} against schema {string}")
  public void validateJsonAgainstYamlSchema(String json, String schemaPath) throws IOException {
    JsonSchema schema = loadSchemaFromClasspath(schemaPath);
    assertValid(schema, json, schemaPath);
  }
}
