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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.networknt.schema.InputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises dialect differences on real imports and intentionally distinct regression schemas. */
class OpenApiSchemaLoaderTest {
  private static final String FIXTURES = "schemas/mock/dialects/";
  private static final String PUSHERS = "schemas/push_notifications_openapi/fd_openapi.yaml"
      + "#/paths/~1pushers/get/responses/200/content/application~1json/schema";
  private static final String PUSHER = """
      {"pushers":[{"pushkey":"owned","app_id":"app","kind":null,
      "app_display_name":"App","device_display_name":"Device","lang":"de",
      "data":{"url":"https://example.test/push/v1/"},"append":false}]}
      """;

  @Test
  void upstreamPusherAllowsNullKindButNotNullAppendOrMissingRequiredFields() {
    var schema = OpenApiSchemaLoader.load(PUSHERS);
    assertThat(schema.validate(PUSHER, InputFormat.JSON)).isEmpty();
    assertThat(schema.validate(PUSHER.replace("false", "null"), InputFormat.JSON)).isNotEmpty();
    assertThat(schema.validate(PUSHER.replace("\"app_id\":\"app\",", ""), InputFormat.JSON)).isNotEmpty();
    assertThat(schema.validate(PUSHER.replace("\"kind\":null", "\"kind\":42"), InputFormat.JSON)).isNotEmpty();
    assertThat(schema.validate("{\"pushers\":[]}", InputFormat.JSON)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
      "openapi30.yaml, Nullable, true",
      "openapi30.yaml, NonNullable, false",
      "openapi30.yaml, EnumNullable, false",
      "openapi30.yaml, EnumWithNull, true",
      "openapi30.yaml, TypedComposition, false",
      "openapi30.yaml, WithoutType, false",
      "openapi30.yaml, RefSibling, false",
      "openapi30.yaml, External, true",
      "openapi31.yaml, Nullable, true",
      "openapi31.yaml, LegacyNullable, false",
      "openapi31.yaml, RefSibling, false"})
  void nullAcceptanceDependsOnTheDeclaredDialectAndAllConstraints(
      String document, String component, boolean acceptsNull) {
    var schema = OpenApiSchemaLoader.load(FIXTURES + document + "#/components/schemas/" + component);
    assertThat(schema.validate("\"allowed\"", InputFormat.JSON)).isEmpty();
    assertThat(schema.validate("42", InputFormat.JSON)).isNotEmpty();
    assertThat(schema.validate("null", InputFormat.JSON).isEmpty()).isEqualTo(acceptsNull);
  }

  @Test
  void unifiedV2PackageResolvesSiblingSchemaAndItsInternalReferences() {
    var schema = OpenApiSchemaLoader.load("schemas/v_2_0/openapi/zeta-guard-email-endpoint.yaml"
        + "#/components/responses/Unauthorized/content/application~1json/schema");
    assertThat(schema.validate("{\"error\":\"invalid_request\"}", InputFormat.JSON)).isEmpty();
    assertThat(schema.validate("{\"error\":42}", InputFormat.JSON)).isNotEmpty();
    assertThat(schema.validate("{}", InputFormat.JSON)).isNotEmpty();
  }

  @Test
  void explicitSchemaDialectOverridesOpenApiDocumentDefault() {
    var prefix = FIXTURES + "openapi31-default.yaml#/components/schemas/";
    assertThat(OpenApiSchemaLoader.load(prefix + "Default")
        .validate("{\"a\":1}", InputFormat.JSON)).isEmpty();
    var override = OpenApiSchemaLoader.load(prefix + "Override");
    assertThat(override.validate("{\"a\":1}", InputFormat.JSON)).isNotEmpty();
    assertThat(override.validate("{\"a\":1,\"b\":2}", InputFormat.JSON)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "openapi30.yaml", "openapi30.yaml#/info", "openapi30.yaml#/components/schemas/Missing",
      "missing.yaml#/components/schemas/Example", "unsupported.yaml#/components/schemas/Example",
      "unknown-default.yaml#/components/schemas/Example", "openapi31.yaml#/components/schemas/UnknownDialect",
      "external.yaml#/$defs/Nullable"})
  void rejectsMissingFragmentsContainersAndUnsupportedDialects(String reference) {
    assertThrows(AssertionError.class, () -> OpenApiSchemaLoader.load(FIXTURES + reference));
  }
}
