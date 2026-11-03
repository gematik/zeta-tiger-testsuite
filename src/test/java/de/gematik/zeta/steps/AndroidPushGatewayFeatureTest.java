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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.test.tiger.common.config.RbelModificationDescription;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Verifies the gateway batch selectors used by the Android push smoke scenario. */
class AndroidPushGatewayFeatureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Preserves device and encryption fields through registration, update and deletion. */
  @Test
  void pusherRewritePreservesTheRemainingBodyAcrossItsLifecycle() throws IOException {
    var logger = pusherRewriteLogger();
    for (var kind : List.of("http", "http", "delete")) {
      var body = JSON.createObjectNode().put("app_id", "zeta-android-testdriver.zeta.firebase")
          .put("pushkey", "own-device-token");
      if (kind.equals("delete")) {
        body.putNull("kind");
      } else {
        body.put("kind", kind);
        body.putObject("encryption").put("iss", "own-secret")
            .put("key_identifier", "own-key").put("time_iss_created", "2026-10");
      }
      var message = logger.getRbelConverter().parseMessage(wire(
          "POST /push/v1/pushers/set HTTP/1.1", body.toString()), new RbelMessageMetadata());
      var changed = logger.getRbelModifier().applyModifications(message);
      body.put("app_id", "zeta-4899c.zeta.firebase");
      assertEquals(body, JSON.readTree(changed.findElement("$.body").orElseThrow()
          .getRawStringContent()));
    }
  }

  /** Keeps unrelated messages outside the Android pusher identity mapping. */
  @ParameterizedTest
  @ValueSource(strings = {"wrong_path", "wrong_method", "foreign_app", "missing_app",
      "missing_body", "response"})
  void pusherRewriteDoesNotChangeUnrelatedTraffic(String variant) throws IOException {
    var logger = pusherRewriteLogger();
    var body = JSON.createObjectNode().put("app_id", variant.equals("foreign_app")
        ? "another-project.zeta.firebase" : "zeta-android-testdriver.zeta.firebase");
    if (variant.equals("missing_app")) {
      body.remove("app_id");
    }
    var path = variant.equals("wrong_path") ? "/push/v1/pushers/other" : "/push/v1/pushers/set";
    var method = variant.equals("wrong_method") ? "GET" : "POST";
    var original = variant.equals("missing_body") ? "" : body.toString();
    var request = logger.getRbelConverter().parseMessage(wire(
        method + " " + path + " HTTP/1.1", original), new RbelMessageMetadata());
    var selected = variant.equals("response")
        ? logger.getRbelConverter().parseMessage(wire("HTTP/1.1 200 OK", original),
            new RbelMessageMetadata()) : request;
    var changed = logger.getRbelModifier().applyModifications(selected);
    assertEquals(original, changed.findElement("$.body").orElseThrow().getRawStringContent());
  }

  /** Rejects batch entries that match only the reference or only the target device. */
  @Test
  void gatewaySelectorsKeepReferenceAndDeviceInOneEntry() throws IOException {
    var feature = feature();
    var itemPath = feature.stream()
        .filter(line -> line.contains("setze lokale Variable \"androidGatewayItemPath\""))
        .findFirst().orElseThrow().split("\"")[3]
        .replace("${androidPushCorrelationId}", "own-marker")
        .replace("${androidPushPushkey}", "own-token");
    var body = JSON.createObjectNode();
    var notifications = body.putArray("notifications");
    notifications.addObject().put("id", "wrong-event").putObject("notification")
        .put("identifier", "foreign-marker").putObject("device").put("pushkey", "own-token");
    notifications.addObject().put("id", "wrong-device").putObject("notification")
        .put("identifier", "own-marker").putObject("device").put("pushkey", "foreign-token");
    assertEquals(List.of(), rbelValues(body.toString(), itemPath + ".id"));
    notifications.addObject().put("id", "own-entry").putObject("notification")
        .put("identifier", "own-marker").putObject("device").put("pushkey", "own-token");
    assertEquals(List.of("own-entry"), rbelValues(body.toString(), itemPath + ".id"));
  }

  /** An unrelated success cannot hide a failed or missing result for the selected batch item. */
  @Test
  void gatewayResponseSelectorKeepsTheSubmittedItemId() throws IOException {
    var resultPath = feature().stream()
        .filter(line -> line.contains("\"$.body.results.[?(@.id == '${androidGatewayItemId}')].status\""))
        .findFirst().orElseThrow().split("\"")[1]
        .replace("${androidGatewayItemId}", "own-entry");
    assertEquals(List.of("failed"), rbelValues("""
        {"results":[{"id":"foreign-entry","status":"success"},
                    {"id":"own-entry","status":"failed"}]}
        """, resultPath));
    assertEquals(List.of(), rbelValues("""
        {"results":[{"id":"foreign-entry","status":"success"}]}
        """, resultPath));
    assertEquals(List.of("success"), rbelValues("""
        {"results":[{"id":"own-entry","status":"success"}]}
        """, resultPath));
  }

  /** The catalog schema rejects absent containers and incomplete or invalid channel entries. */
  @Test
  void catalogSchemaRejectsMissingFieldsAndInvalidStatuses() throws IOException {
    var schema = featureSchema("aktuelle Antwort im Knoten \"$.body\" stimmt als \"JSON_SCHEMA\"");
    assertTrue(schema.validate("{\"channels\":[{\"id\":\"zeta\",\"status\":\"not_set\"}]}", InputFormat.JSON).isEmpty());
    for (var invalid : List.of("{}", "{\"channels\":{}}", "{\"channels\":[{\"id\":\"zeta\"}]}",
        "{\"channels\":[{\"id\":\"zeta\",\"status\":\"unknown\"}]}")) {
      assertFalse(schema.validate(invalid, InputFormat.JSON).isEmpty(), invalid);
    }
  }

  /** Exactly one item must contain both the event correlation and the registered device. */
  @Test
  void batchSchemaRejectsDuplicateMissingAndSplitMatches() throws IOException {
    var schema = featureSchema("aktueller Request im Knoten \"$.body.notifications\"");
    var own = """
        {"id":"item","notification":{"identifier":"own-marker","device":{"pushkey":"own-token"}}}
        """.trim();
    assertTrue(schema.validate("[" + own + "]", InputFormat.JSON).isEmpty());
    assertFalse(schema.validate("[" + own + "," + own + "]", InputFormat.JSON).isEmpty());
    assertFalse(schema.validate("[]", InputFormat.JSON).isEmpty());
    var split = "[" + own.replace("own-marker", "foreign-marker") + ","
        + own.replace("own-token", "foreign-token") + "]";
    assertFalse(schema.validate(split, InputFormat.JSON).isEmpty());
  }

  /** Loads one maintained inline schema with independent event and device fixture values. */
  private static Schema featureSchema(String step) throws IOException {
    var matcher = Pattern.compile("(?s)" + Pattern.quote(step) + ".*?\"\"\"\\s*(.*?)\\s*\"\"\"")
        .matcher(String.join("\n", feature()));
    if (!matcher.find()) {
      throw new IllegalArgumentException("Missing inline schema for " + step);
    }
    var text = matcher.group(1).replace("${androidPushCorrelationId}", "own-marker")
        .replace("${androidPushPushkey}", "own-token");
    return SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())
        .getSchema(JSON.readTree(text));
  }

  /** Reads the maintained Android delivery feature without runtime substitutions. */
  private static List<String> feature() throws IOException {
    return Files.readAllLines(Path.of(
        "src/test/resources/features/UserStory_02/UseCase_07/android_push_zustellung.feature"));
  }

  /** Builds the maintained pusher rewrite with resolved fixture values. */
  private static RbelLogger pusherRewriteLogger() throws IOException {
    var line = feature().stream().map(String::strip)
        .filter(value -> value.startsWith("Gegeben sei Setze im TigerProxy für die Nachricht "))
        .filter(value -> value.contains("die Manipulation auf Feld"))
        .findFirst().orElseThrow();
    var arguments = line.split("\"");
    var count = Pattern.compile(" und (\\d+) Ausführungen$").matcher(line);
    if (!count.find()) {
      throw new IllegalArgumentException("Missing pusher rewrite execution bound");
    }
    var condition = arguments[1]
        .replace("${paths.guard.notificationPushersSetPath}", "/push/v1/pushers/set")
        .replace("${testdata.androidPush.driverAppId}", "zeta-android-testdriver.zeta.firebase");
    var logger = RbelLogger.build();
    logger.getRbelModifier().addModification(RbelModificationDescription.builder()
        .name("android-pusher-project").condition(condition).targetElement(arguments[3])
        .replaceWith(arguments[5].replace("${android_push_firebase_project_id}", "zeta-4899c"))
        .deleteAfterNExecutions(Integer.parseInt(count.group(1))).build());
    return logger;
  }

  /** Encodes a JSON HTTP message for the installed RBEL parser. */
  private static byte[] wire(String startLine, String body) {
    return (startLine + "\r\nHost: push-gateway:8080\r\nContent-Type: application/json\r\n"
        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  /** Parses an HTTP JSON message and returns the values selected by an RBEL expression. */
  private static List<String> rbelValues(String body, String path) {
    var wire = "POST /push/v1/notifyEncrypted/batch HTTP/1.1\r\n"
        + "Host: push-gateway:8080\r\nContent-Type: application/json\r\nContent-Length: "
        + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    var message = RbelLogger.build().getRbelConverter().parseMessage(
        wire.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    return message.findRbelPathMembers(path).stream()
        .map(element -> element.getRawStringContent()).toList();
  }
}
