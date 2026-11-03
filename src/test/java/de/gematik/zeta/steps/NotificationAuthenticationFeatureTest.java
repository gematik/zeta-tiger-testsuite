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

import de.gematik.test.tiger.glue.TigerParameterTypeDefinitions;
import de.gematik.test.tiger.lib.json.JsonSchemaChecker;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Pickle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests the header manipulation and state comparisons used by the notification feature.
 */
class NotificationAuthenticationFeatureTest {

  /**
   * Checks that the feature's manipulation removes Authorization while preserving other headers.
   *
   * @throws IOException if the maintained feature cannot be read
   */
  @Test
  void removesOnlyAuthorizationHeader() throws IOException {
    var manipulations = parseFeature().stream()
        .filter(pickle -> pickle.getTags().stream()
            .anyMatch(tag -> List.of("@TA_A_29975_02", "@TA_A_29975_03", "@TA_A_29975_04",
                "@TA_A_29975_05").contains(tag.getName())))
        .flatMap(pickle -> pickle.getSteps().stream())
        .map(step -> step.getText())
        .filter(text -> text.contains("die Regex-Manipulation"))
        .distinct()
        .toList();
    assertThat(manipulations).isNotEmpty();
    for (var manipulation : manipulations) {
      var regexStart = manipulation.indexOf("mit Regex \"") + "mit Regex \"".length();
      var regexEnd = manipulation.indexOf("\" und Wert", regexStart);
      var resolvedRegex = TigerParameterTypeDefinitions.tigerResolvedString(
          manipulation.substring(regexStart, regexEnd));
      var headerPattern = Pattern.compile(resolvedRegex);
      for (var token : List.of("dpop original.token.signature", "DPoP renewed.token.signature")) {
        var headers = "Host: example.test\r\nAuthorization: " + token
            + "\r\nDPoP: proof.token.signature\r\nContent-Type: application/json\r\n";
        assertThat(headerPattern.matcher(headers).replaceAll(""))
            .isEqualTo("Host: example.test\r\nDPoP: proof.token.signature\r\n"
                + "Content-Type: application/json\r\n");
      }
    }
  }

  /**
   * Exercises the actual feature schemas with malformed, absent, duplicate and changed state.
   * Object field order and unrelated list entries must not affect the owned-state proof.
   * Additional nullable metadata is accepted but remains part of the unchanged-state comparison.
   * Resolves the feature's app ID placeholder to the same local value used by the pusher fixtures.
   *
   * @throws IOException if the maintained feature cannot be read
   */
  @Test
  void rejectsInvalidStateWithoutDependingOnJsonFieldOrder() throws IOException {
    var checker = new JsonSchemaChecker();
    var appId = "de.gematik.zeta.testsuite.notification";
    var pusher = """
        {"pushkey":"zeta-testsuite-notification-auth",
         "app_id":"%s",
         "app_display_name":"Original","kind":"http","device_display_name":"Testgerät",
         "lang":"de","data":{"url":"https://example.test/push/v1/"},
         "profile_tag":null,"append":null}
        """.formatted(appId);
    var reorderedPusher = """
        {"append":null,"profile_tag":null,"data":{"url":"https://example.test/push/v1/"},"lang":"de",
         "device_display_name":"Testgerät","kind":"http","app_display_name":"Original",
         "app_id":"%s",
         "pushkey":"zeta-testsuite-notification-auth"}
        """.formatted(appId);
    var channel = "{\"id\":\"zeta\",\"status\":\"disabled\"}";
    int checked = 0;
    for (var pickle : parseFeature()) {
      if (pickle.getTags().stream().noneMatch(tag -> List.of("@TA_A_29975_02", "@TA_A_29975_03", "@TA_A_29975_04",
                "@TA_A_29975_05").contains(tag.getName()))) {
        continue;
      }
      boolean channels = pickle.getName().contains("Ändern der Geräte-Channels");
      boolean registration = pickle.getName().contains("Pusher-Registrierung");
      var owned = channels ? channel : pusher;
      var field = channels ? "channels" : "pushers";
      for (var step : pickle.getSteps()) {
        if (!step.getText().contains("JSON_SCHEMA")) {
          continue;
        }
        var schema = step.getArgument().orElseThrow().getDocString().orElseThrow().getContent()
            .replace("${testdata.notificationService.pusher.appId}", appId);
        if (schema.contains("notificationStateBefore")) {
          var resolved = schema.replace("${notificationStateBefore}", owned);
          var reordered = channels ? "{\"status\":\"disabled\",\"id\":\"zeta\"}" : reorderedPusher;
          assertDoesNotThrow(() -> checker.compareToSchema(reordered, resolved));
          var changed = owned.replace("Original", "Changed").replace("disabled", "enabled");
          assertThrows(AssertionError.class, () -> checker.compareToSchema(changed, resolved));
          if (!channels) {
            var changedMetadata = owned.replace("\"append\":null", "\"append\":false");
            assertThrows(AssertionError.class, () -> checker.compareToSchema(changedMetadata, resolved));
          }
          assertThrows(AssertionError.class, () -> checker.compareToSchema("{}", resolved));
          checked++;
        } else if (schema.contains("\"contains\"")) {
          assertThat(schema).doesNotContain("\"$ref\"");
          for (var malformed : List.of("{}", "[]", "null", "{\"" + field + "\":null}",
              "{\"" + field + "\":[{}]}", "{\"" + field + "\":{}}",
              "{\"" + field + "\":[null]}", "{\"" + field + "\":[42]}",
              "{\"" + field + "\":[{\"pushkey\":42,\"app_id\":\"x\",\"id\":42}]}")) {
            assertThrows(AssertionError.class, () -> checker.compareToSchema(malformed, schema));
          }
          var empty = "{\"" + field + "\":[]}";
          var populated = "{\"" + field + "\":[" + owned + "]}";
          if (registration) {
            assertDoesNotThrow(() -> checker.compareToSchema(empty, schema));
            assertThrows(AssertionError.class, () -> checker.compareToSchema(populated, schema));
          } else {
            assertDoesNotThrow(() -> checker.compareToSchema(populated, schema));
            assertThrows(AssertionError.class, () -> checker.compareToSchema(empty, schema));
            var duplicate = "{\"" + field + "\":[" + owned + "," + owned + "]}";
            assertThrows(AssertionError.class, () -> checker.compareToSchema(duplicate, schema));
            var other = channels ? "{\"id\":\"other\",\"status\":\"enabled\"}"
                : pusher.replace("zeta-testsuite-notification-auth", "other")
                    .replace(appId, "other");
            for (var entries : List.of(owned + "," + other, other + "," + owned)) {
              var mixed = "{\"" + field + "\":[" + entries + "]}";
              assertDoesNotThrow(() -> checker.compareToSchema(mixed, schema));
            }
          }
          checked++;
        }
      }
    }
    assertThat(checked).as("State checks from the feature must be exercised").isPositive();
  }

  @Test
  void openApiMigrationPreservesHardEmptyListPreconditionsAndCorrelatedBodies() throws IOException {
    var checker = new JsonSchemaChecker();
    int contracts = 0;
    int emptyLists = 0;
    for (var feature : List.of("UseCase_01/push_benachrichtigungen_verwalten.feature",
        "UseCase_02/zustellvoraussetzungen_synchron_vorpruefen.feature")) {
      for (var pickle : parseFeature(feature)) {
        var steps = pickle.getSteps();
        for (int i = 0; i < steps.size(); i++) {
          var step = steps.get(i);
          if (!step.getText().contains("gegen OpenAPI-Schema")) {
            continue;
          }
          contracts++;
          var previous = steps.get(i - 1).getText();
          assertThat(previous).startsWith("TGR speichere Wert des Knotens \"$.body\" der aktuellen Antwort");
          var variable = previous.substring(previous.lastIndexOf('"', previous.length() - 2) + 1,
              previous.length() - 1);
          assertThat(step.getText()).startsWith("validiere \"${" + variable + "}\"");
          assertThat(step.getText()).contains("fd_openapi.yaml#/paths/~1pushers/get/responses/200/");
          if (!variable.equals("emptyPushersResponse")) {
            continue;
          }
          emptyLists++;
          var state = steps.get(i - 2).getArgument().orElseThrow().getDocString().orElseThrow().getContent();
          assertDoesNotThrow(() -> checker.compareToSchema("{\"pushers\":[]}", state));
          for (var invalid : List.of("{}", "null", "[]", "{\"pushers\":null}",
              "{\"pushers\":{}}", "{\"pushers\":\"\"}", "{\"pushers\":[{}]}")) {
            assertThrows(AssertionError.class, () -> checker.compareToSchema(invalid, state));
          }
        }
      }
    }
    assertThat(contracts).isEqualTo(10);
    assertThat(emptyLists).isEqualTo(3);
  }

  /**
   * Parses and expands the maintained German feature without executing its steps.
   *
   * @return all expanded scenarios
   * @throws IOException if the maintained feature cannot be read
   */
  private List<Pickle> parseFeature() throws IOException {
    return parseFeature("UseCase_01/push_benachrichtigungen_verwalten.feature");
  }

  private List<Pickle> parseFeature(String feature) throws IOException {
    var path = Path.of("src/test/resources/features/UserStory_02/" + feature);
    List<Envelope> messages;
    try (var stream = GherkinParser.builder().build().parse(path)) {
      messages = stream.toList();
    }
    assertThat(messages.stream().flatMap(message -> message.getParseError().stream()).toList())
        .as("German feature must parse without errors")
        .isEmpty();
    return messages.stream().flatMap(message -> message.getPickle().stream()).toList();
  }
}
