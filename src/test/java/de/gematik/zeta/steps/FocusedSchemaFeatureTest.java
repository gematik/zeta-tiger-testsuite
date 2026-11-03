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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.test.tiger.lib.json.JsonSchemaChecker;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.ObjectMapper;

/** Exercises the maintained scenario DocStrings through Tiger's actual JSON_SCHEMA validator. */
class FocusedSchemaFeatureTest {
  private static final Map<String, String> TAGS = Map.of(
      "acr", "@TA_A_29993_02", "issued_token_type", "@TA_A_29951_01",
      "ver", "@TA_A_29676_01", "revocation_endpoint", "@TA_A_29996_02");
  private static final List<String> FEATURES = List.of(
      "UserStory_01/UseCase_06/client_authentisierung_und_autorisierung_software_attest_sc_200.feature",
      "UserStory_01/UseCase_17/client_ressource_anfrage_fachdienst_popp_header_sc_403.feature",
      "UserStory_04/UseCase_07/token_revocation.feature");

  /** Checks every example and both version observations, preserving typed JSON and required fields. */
  @TestFactory
  Stream<DynamicTest> upstreamFieldContractsInScenarios() throws IOException {
    var tests = new ArrayList<DynamicTest>();
    var counts = new HashMap<String, Integer>();
    var json = new ObjectMapper();
    for (var feature : FEATURES) {
      List<Envelope> messages;
      try (var stream = GherkinParser.builder().build()
          .parse(Path.of("src/test/resources/features/" + feature))) {
        messages = stream.toList();
      }
      assertThat(messages.stream().flatMap(message -> message.getParseError().stream()).toList())
          .as("Feature parses: %s", feature).isEmpty();
      for (var pickle : messages.stream().flatMap(message -> message.getPickle().stream()).toList()) {
        var tags = pickle.getTags().stream().map(tag -> tag.getName()).toList();
        if (tags.stream().noneMatch(TAGS::containsValue)) {
          continue;
        }
        for (var step : pickle.getSteps()) {
          if (!step.getText().contains("JSON_SCHEMA")) {
            continue;
          }
          var schema = step.getArgument().orElseThrow().getDocString().orElseThrow().getContent();
          var node = json.readTree(schema);
          for (var field : TAGS.keySet()) {
            if (!node.path("properties").has(field)) {
              continue;
            }
            assertThat(tags).contains(TAGS.get(field));
            // Verify the observation site too, not only a detached schema's behavior.
            var observation = field.equals("ver") ? "aktueller Request" : "aktuelle Antwort";
            var path = switch (field) {
              case "acr" -> "$.body.access_token.body";
              case "ver" -> "${headers.authorization.dpopToken.root}.body";
              default -> "$.body";
            };
            assertThat(step.getText()).isEqualTo("TGR prüfe " + observation + " im Knoten \""
                + path + "\" stimmt als \"JSON_SCHEMA\" überein mit:");
            counts.merge(field, 1, Integer::sum);
            var label = pickle.getName() + " / " + field + " / observation " + counts.get(field);
            for (var value : validValues(field)) {
              var instance = instance(field, value);
              tests.add(DynamicTest.dynamicTest(label + " accepts " + value,
                  () -> assertDoesNotThrow(() -> new JsonSchemaChecker().compareToSchema(instance, schema))));
            }
            for (var instance : List.of("{}", "[]", "null", "42", "\"text\"")) {
              tests.add(rejects(label, instance, schema));
            }
            for (var value : invalidValues(field)) {
              tests.add(rejects(label, instance(field, value), schema));
            }
          }
        }
      }
    }
    assertThat(counts).containsExactlyInAnyOrderEntriesOf(
        Map.of("acr", 1, "issued_token_type", 2, "ver", 4, "revocation_endpoint", 1));
    return tests.stream();
  }

  private static DynamicTest rejects(String label, String instance, String schema) {
    return DynamicTest.dynamicTest(label + " rejects " + instance,
        () -> assertThrows(AssertionError.class,
            () -> new JsonSchemaChecker().compareToSchema(instance, schema)));
  }

  private static List<String> validValues(String field) {
    return switch (field) {
      case "acr" -> List.of("\"gematik-ehealth-loa-substantial\"", "\"gematik-ehealth-loa-high\"");
      case "ver" -> List.of("1", "2");
      case "issued_token_type" -> List.of("\"urn:ietf:params:oauth:token-type:access_token\"",
          "\"urn:ietf:params:oauth:token-type:jwt\"");
      case "revocation_endpoint" -> List.of("\"https://as.example/revoke\"", "\"https://as.example\"",
          "\"https://as.example:8443/revoke?tenant=test\"", "\"HTTPS://as.example/revoke\"",
          "\"https://as.example/revoke?value=%23encoded\"");
      default -> throw new IllegalArgumentException(field);
    };
  }

  private static String instance(String field, String value) {
    var requiredPeers = field.equals("issued_token_type")
        ? "\"access_token\":\"signed-token\",\"token_type\":\"DPoP\",\"expires_in\":300," : "";
    return "{" + requiredPeers + "\"" + field + "\":" + value
        + ",\"unrelated\":{\"nested\":true}}";
  }

  private static List<String> invalidValues(String field) {
    var values = new ArrayList<>(List.of("null", "true", "{}", "[]"));
    values.addAll(switch (field) {
      case "acr" -> List.of("1", "\"1\"", "\"gematik-ehealth-loa-low\"",
          "[\"gematik-ehealth-loa-high\"]", "\"gematik-ehealth-loa-high-extra\"");
      case "ver" -> List.of("\"1\"", "\"2\"", "0", "3", "1.5");
      case "issued_token_type" -> List.of("42", "\"\"", "[\"urn:ietf:params:oauth:token-type:jwt\"]",
          "\"urn:ietf:params:oauth:token-type:refresh_token\"", "\"URN:ietf:params:oauth:token-type:access_token\"",
          "\"urn:ietf:params:oauth:token-type:jwt-suffix\"", "\" urn:ietf:params:oauth:token-type:jwt\"");
      case "revocation_endpoint" -> List.of("42", "\"\"", "\"/revoke\"", "\"https://as.example/bad path\"",
          "\"http://as.example/revoke\"", "\"urn:example:revoke\"",
          "\"https://as.example/revoke#fragment\"", "\"https://as.example/revoke#\"",
          "\"https://\"", "\"https:///revoke\"");
      default -> throw new IllegalArgumentException(field);
    });
    return values;
  }
}
