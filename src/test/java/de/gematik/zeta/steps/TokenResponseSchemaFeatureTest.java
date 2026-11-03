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

import de.gematik.test.tiger.lib.json.JsonSchemaChecker;
import io.cucumber.gherkin.GherkinParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exercises each positive example's complete response schema through Tiger's validator. */
class TokenResponseSchemaFeatureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> positiveExamplesEnforceTheWholeSuccessBranch() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    var schemas = new ArrayList<String>();
    try (var envelopes = GherkinParser.builder().build().parse(Path.of(
        "src/test/resources/features/UserStory_01/UseCase_06/"
            + "client_authentisierung_und_autorisierung_software_attest_sc_200.feature"))) {
      envelopes.flatMap(e -> e.getPickle().stream())
          .filter(p -> p.getTags().stream().anyMatch(t -> t.getName().equals("@TA_A_29951_01")))
          .forEach(p -> {
            var schemaSteps = p.getSteps().stream()
                .filter(s -> s.getText().contains("JSON_SCHEMA")).toList();
            assertEquals(1, schemaSteps.size());
            schemas.add(schemaSteps.getFirst().getArgument().orElseThrow()
                .getDocString().orElseThrow().getContent());
          });
    }
    assertEquals(2, schemas.size());
    for (int example = 0; example < schemas.size(); example++) {
      var schema = schemas.get(example);
      var prefix = "Positive example " + example + ": ";
      tests.add(DynamicTest.dynamicTest(prefix + "accepts required fields", () ->
          assertDoesNotThrow(() -> new JsonSchemaChecker().compareToSchema(
              validResponse().toString(), schema))));
      var withoutIssuedType = validResponse();
      withoutIssuedType.remove("issued_token_type");
      tests.add(DynamicTest.dynamicTest(prefix + "accepts absent optional issued_token_type", () ->
          assertDoesNotThrow(() -> new JsonSchemaChecker().compareToSchema(
              withoutIssuedType.toString(), schema))));
      var optional = validResponse();
      for (var field : List.of("refresh_token", "zeta_attestation_token", "asl_access_token",
          "scope", "email_hint")) {
        optional.put(field, "string-value");
      }
      optional.put("refresh_expires_in", 600);
      optional.putObject("unrelated").put("nested", true);
      tests.add(DynamicTest.dynamicTest(prefix + "accepts typed optional fields", () ->
          assertDoesNotThrow(() -> new JsonSchemaChecker().compareToSchema(
              optional.toString(), schema))));
      for (var field : List.of("access_token", "token_type", "expires_in")) {
        var missing = validResponse();
        missing.remove(field);
        tests.add(rejects(prefix + "requires " + field, missing.toString(), schema));
      }
      for (var field : List.of("access_token", "token_type", "expires_in", "issued_token_type",
          "refresh_token", "refresh_expires_in", "zeta_attestation_token", "asl_access_token",
          "scope", "email_hint")) {
        var wrong = validResponse();
        wrong.put(field, true);
        tests.add(rejects(prefix + "rejects wrong type for " + field, wrong.toString(), schema));
      }
      var bearer = validResponse();
      bearer.put("token_type", "Bearer");
      tests.add(rejects(prefix + "requires DPoP", bearer.toString(), schema));
      var fractional = validResponse();
      fractional.put("expires_in", 1.5);
      tests.add(rejects(prefix + "requires integer lifetime", fractional.toString(), schema));
      tests.add(rejects(prefix + "rejects denial response",
          "{\"error\":\"access_denied\",\"reasons\":{\"Denied\":true}}", schema));
      var mixed = validResponse();
      mixed.put("error", "access_denied");
      mixed.putObject("reasons").put("Denied", true);
      tests.add(rejects(prefix + "rejects a response matching both branches",
          mixed.toString(), schema));
    }
    return tests.stream();
  }

  private static ObjectNode validResponse() {
    var body = JSON.createObjectNode();
    body.put("access_token", "signed.token.jwt");
    body.put("token_type", "DPoP");
    body.put("expires_in", 300);
    body.put("issued_token_type", "urn:ietf:params:oauth:token-type:access_token");
    return body;
  }

  private static DynamicTest rejects(String name, String response, String schema) {
    return DynamicTest.dynamicTest(name, () -> assertThrows(AssertionError.class,
        () -> new JsonSchemaChecker().compareToSchema(response, schema)));
  }
}
