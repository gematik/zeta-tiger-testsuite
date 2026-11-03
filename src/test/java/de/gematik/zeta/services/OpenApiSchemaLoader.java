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

package de.gematik.zeta.services;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/** Loads unchanged OpenAPI schema fragments with the dialect of their owning API document. */
public final class OpenApiSchemaLoader {
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final List<Dialect> DIALECTS = List.of(
      OpenApi30Dialect.INSTANCE, Dialects.getOpenApi31(), Dialects.getDraft4(),
      Dialects.getDraft6(), Dialects.getDraft7(), Dialects.getDraft201909(), Dialects.getDraft202012());

  private OpenApiSchemaLoader() {
  }

  /**
   * Resolves an API document and JSON Pointer, retaining the document location for external refs.
   * No strict-mode additions are applied to the imported API contract.
   *
   * @param reference classpath resource followed by a JSON Pointer to a Schema Object
   * @return schema compiled with the API default and support for explicit JSON Schema dialects
   */
  public static Schema load(String reference) {
    var normalized = reference.startsWith("classpath:")
        ? reference.substring("classpath:".length()) : reference;
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    var uri = URI.create(normalized);
    var pointer = uri.getFragment();
    if (uri.isAbsolute() || uri.getRawQuery() != null || !normalized.startsWith("schemas/")
        || pointer == null || !pointer.startsWith("/")) {
      throw new AssertionError("Expected a classpath OpenAPI document with a schema JSON Pointer: " + reference);
    }
    // The container's info, examples, response objects, etc. are not payload schemas.
    if (!pointer.matches("(?:/components/schemas/[^/]+|.*/schema)")) {
      throw new AssertionError("Select a Schema Object in the OpenAPI document: " + reference);
    }
    var resourcePath = uri.getPath();
    try (var stream = OpenApiSchemaLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (stream == null) {
        throw new AssertionError("OpenAPI document not found on the classpath: " + resourcePath);
      }
      var document = YAML.readTree(stream);
      var selected = document.at(pointer);
      if (!selected.isObject() && !selected.isBoolean()) {
        throw new AssertionError("OpenAPI schema fragment not found or not a schema: " + reference);
      }
      var dialect = defaultDialect(document, reference);
      // Keep both OpenAPI and JSON Schema dialects: an external JSON Schema may declare $schema.
      var supported = new ArrayList<>(DIALECTS);
      supported.remove(dialect);
      supported.addFirst(dialect);
      var registry = SchemaRegistry.withDialects(supported);
      return registry.getSchema(SchemaLocation.of("classpath:" + normalized));
    } catch (IOException | RuntimeException e) {
      throw new AssertionError("Cannot load OpenAPI schema " + reference + ": " + e.getMessage(), e);
    }
  }

  private static Dialect defaultDialect(JsonNode document, String reference) {
    var version = document.path("openapi").asString("");
    if (version.matches("3\\.0\\.[0-9]+")) {
      return OpenApi30Dialect.INSTANCE;
    }
    if (!version.matches("3\\.1\\.[0-9]+")) {
      throw new AssertionError("Unsupported or missing OpenAPI version '" + version + "': " + reference);
    }
    if (!document.has("jsonSchemaDialect")) {
      return Dialects.getOpenApi31();
    }
    var id = document.get("jsonSchemaDialect").asString("");
    // Let the same registry normalize standard dialect URIs, while rejecting unknown dialects.
    return SchemaRegistry.withDialects(DIALECTS).getDialect(id);
  }
}
