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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.test.tiger.common.config.RbelModificationDescription;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exercises structured selectors and bounded replacements with the installed Tiger modifier. */
class JsonProxyManipulationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLIENT = "own-client";
  private static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
  private static final String DECISION_PATH = "/opa/v1/data/decision";
  private static final String ORIGINAL = "{\"result\":{\"allow\":false,\"aud\":\"original\"},\"control\":\"preserved\"}";

  @ParameterizedTest
  @ValueSource(strings = {"own", "foreign_client", "refresh", "registration", "wrong_path",
      "request", "missing_client", "missing_grant", "missing_input", "empty_body"})
  void changesOnlyTheResponseToTheSelectedClientAndGrant(String variant) throws IOException {
    var rule = JsonProxyManipulation.parse(featureDescription());
    var logger = logger(rule);
    var body = requestBody(variant.equals("foreign_client") ? CLIENT + "-other" : CLIENT,
        switch (variant) {
          case "refresh" -> "refresh_token";
          case "registration" -> "registration";
          default -> GRANT;
        });
    if (variant.equals("missing_client")) {
      ((ObjectNode) body.path("input").path("client_registration_data")).remove("client_id");
    } else if (variant.equals("missing_grant")) {
      ((ObjectNode) body.path("input").path("authorization_request")).remove("grant_type");
    } else if (variant.equals("missing_input")) {
      body.remove("input");
    }
    var request = message(logger, "POST " + DECISION_PATH + (variant.equals("wrong_path") ? "-other" : "")
        + " HTTP/1.1", variant.equals("empty_body") ? "" : JSON.writeValueAsString(body));
    var response = message(logger, "HTTP/1.1 200 OK", ORIGINAL);
    var selected = variant.equals("request") ? request : response;
    var changed = logger.getRbelModifier().applyModifications(selected);
    var result = JSON.readTree(changed.findElement("$.body").orElseThrow().getRawStringContent());
    if (variant.equals("own")) {
      assertThat(result.path("result")).isEqualTo(JSON.readTree(rule.replacement()));
    } else {
      assertThat(result).isEqualTo(JSON.readTree(variant.equals("request") ? JSON.writeValueAsString(body) : ORIGINAL));
    }
    assertThat(changed.findElement("$.header.X-Control").orElseThrow().getRawStringContent()).isEqualTo("preserved");
    if (!variant.equals("request")) {
      assertThat(result.path("control").asString()).isEqualTo("preserved");
    }
  }

  @Test
  void treatsQuotesBackslashesAndRbelMarkersAsLiteralSelectorValues() throws IOException {
    var client = "client'\\$.body@.field\nend";
    var description = (ObjectNode) JSON.readTree(featureDescription());
    ((ObjectNode) description.path("when").path("requestBody"))
        .put("$.input.client_registration_data.client_id", client);
    var rule = JsonProxyManipulation.parse(JSON.writeValueAsString(description));
    var logger = logger(rule);
    message(logger, "POST " + DECISION_PATH + " HTTP/1.1", JSON.writeValueAsString(requestBody(client, GRANT)));
    var response = message(logger, "HTTP/1.1 200 OK", ORIGINAL);
    var changed = logger.getRbelModifier().applyModifications(response);
    assertThat(JSON.readTree(changed.findElement("$.body").orElseThrow().getRawStringContent()).path("result"))
        .isEqualTo(JSON.readTree(rule.replacement()));
  }

  @Test
  void expiresAfterTwentyMatchesWithoutCountingForeignTransactions() throws IOException {
    var rule = JsonProxyManipulation.parse(featureDescription());
    var logger = logger(rule);
    for (int attempt = 0; attempt < 21; attempt++) {
      message(logger, "POST " + DECISION_PATH + " HTTP/1.1", JSON.writeValueAsString(requestBody("foreign", GRANT)));
      var foreign = message(logger, "HTTP/1.1 200 OK", ORIGINAL);
      assertThat(logger.getRbelModifier().applyModifications(foreign).findElement("$.body").orElseThrow().getRawStringContent())
          .isEqualTo(ORIGINAL);
      message(logger, "POST " + DECISION_PATH + " HTTP/1.1", JSON.writeValueAsString(requestBody(CLIENT, GRANT)));
      var response = message(logger, "HTTP/1.1 200 OK", ORIGINAL);
      var changed = logger.getRbelModifier().applyModifications(response);
      var result = JSON.readTree(changed.findElement("$.body").orElseThrow().getRawStringContent()).path("result");
      assertThat(result).isEqualTo(attempt < 20 ? JSON.readTree(rule.replacement()) : JSON.readTree(ORIGINAL).path("result"));
    }
  }

  @Test
  void canSelectRequestsWhileLeavingTheirResponsesUnchanged() throws IOException {
    var description = (ObjectNode) JSON.readTree(featureDescription());
    ((ObjectNode) description.path("when")).put("isResponse", false);
    var replacement = (ObjectNode) description.path("replace");
    replacement.put("path", "$.body");
    replacement.putObject("value").put("changed", true);
    var logger = logger(JsonProxyManipulation.parse(JSON.writeValueAsString(description)));
    var request = message(logger, "POST " + DECISION_PATH + " HTTP/1.1", JSON.writeValueAsString(requestBody(CLIENT, GRANT)));
    var changed = logger.getRbelModifier().applyModifications(request);
    assertThat(JSON.readTree(changed.findElement("$.body").orElseThrow().getRawStringContent()).path("changed").asBoolean()).isTrue();
    var response = message(logger, "HTTP/1.1 200 OK", ORIGINAL);
    assertThat(logger.getRbelModifier().applyModifications(response).findElement("$.body").orElseThrow().getRawStringContent())
        .isEqualTo(ORIGINAL);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown_root", "unknown_when", "unknown_replace", "missing_when", "missing_value",
      "bad_boolean", "bad_regex", "bad_body", "bad_path", "bad_value", "bad_replacement", "zero", "negative", "fraction", "overflow"})
  void rejectsInvalidDescriptionsBeforeTheyCanBroadenTheSelector(String fault) throws IOException {
    var description = (ObjectNode) JSON.readTree(featureDescription());
    switch (fault) {
      case "unknown_root" -> description.put("execution", 20);
      case "unknown_when" -> ((ObjectNode) description.path("when")).put("requestPaths", ".*");
      case "unknown_replace" -> ((ObjectNode) description.path("replace")).put("fields", "$.body");
      case "missing_when" -> description.remove("when");
      case "missing_value" -> ((ObjectNode) description.path("replace")).remove("value");
      case "bad_boolean" -> ((ObjectNode) description.path("when")).put("isResponse", "true");
      case "bad_regex" -> ((ObjectNode) description.path("when")).put("requestPathMatches", "[");
      case "bad_body" -> ((ObjectNode) description.path("when")).put("requestBody", "body");
      case "bad_path" -> ((ObjectNode) description.path("when").path("requestBody")).put("$..client_id", CLIENT);
      case "bad_value" -> ((ObjectNode) description.path("when").path("requestBody")).put("$.client_id", 7);
      case "bad_replacement" -> ((ObjectNode) description.path("replace")).put("value", "changed");
      case "zero" -> description.put("executions", 0);
      case "negative" -> description.put("executions", -1);
      case "fraction" -> description.put("executions", 1.5);
      case "overflow" -> description.put("executions", Long.MAX_VALUE);
      default -> throw new IllegalArgumentException(fault);
    }
    assertThatThrownBy(() -> JsonProxyManipulation.parse(JSON.writeValueAsString(description)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"bad_regex", "bad_path", "duplicate_path", "empty_target"})
  void validatesResolvedPathsBeforeCompilingTheManipulation(String fault) throws IOException {
    var description = (ObjectNode) JSON.readTree(featureDescription());
    var when = (ObjectNode) description.path("when");
    var resolved = switch (fault) {
      case "bad_regex" -> {
        when.put("requestPathMatches", "${resolved}");
        yield "[";
      }
      case "bad_path", "duplicate_path" -> {
        ((ObjectNode) when.path("requestBody")).put("${resolved}", CLIENT);
        yield fault.equals("bad_path") ? "$..client_id" : "$.input.client_registration_data.client_id";
      }
      case "empty_target" -> {
        ((ObjectNode) description.path("replace")).put("path", "${resolved}");
        yield "";
      }
      default -> throw new IllegalArgumentException(fault);
    };
    assertThatThrownBy(() -> JsonProxyManipulation.parse(JSON.writeValueAsString(description),
        value -> value.replace("${resolved}", resolved)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String featureDescription() throws IOException {
    var feature = Files.readString(Path.of("src/test/resources/features/UserStory_01/UseCase_26/statische_weiterleitung.feature"));
    var matcher = Pattern.compile("Setze im TigerProxy folgende Manipulation:\\s*\"\"\"(.*?)\"\"\"", Pattern.DOTALL).matcher(feature);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1).replace("${paths.opa.decisionPath}", DECISION_PATH)
        .replace("${ROUTE_CLIENT}", CLIENT)
        .replace("${oauth_parameters.grant_type.tokenExchange}", GRANT)
        .replace("${ROUTE_AUDIENCE}", "https://route.example");
  }

  private static ObjectNode requestBody(String client, String grant) {
    var body = JSON.createObjectNode();
    var input = body.putObject("input");
    input.putObject("client_registration_data").put("client_id", client);
    input.putObject("authorization_request").put("grant_type", grant);
    return body;
  }

  private static RbelLogger logger(JsonProxyManipulation rule) {
    var logger = RbelLogger.build();
    logger.getRbelModifier().addModification(RbelModificationDescription.builder()
        .name("structured-selector").condition(rule.condition()).targetElement(rule.field())
        .replaceWith(rule.replacement()).deleteAfterNExecutions(rule.executions()).build());
    return logger;
  }

  private static RbelElement message(RbelLogger logger, String startLine, String body) {
    return logger.getRbelConverter().parseMessage((startLine + "\r\nHost: route.example\r\nX-Control: preserved\r\n"
        + "Content-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body)
        .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }
}
