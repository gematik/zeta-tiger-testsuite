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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.lib.json.JsonSchemaChecker;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Pickle;
import io.restassured.http.Method;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationLifecycleStepsTest {
  private static final String KEY = "zeta-lifecycle-owned/+";
  private static final String APP = "app/id+";
  private static final String IDENTITY = "X123456789";
  private static final String PUSHER = """
      {"pushkey":"owned","app_id":"app","kind":"http","app_display_name":"App",
       "device_display_name":"Device","profile_tag":"profile","lang":"de",
       "data":{"url":"https://example.test/push/v1/","format":"event_id_only"},"append":false}
      """;
  @TempDir
  Path records;

  @Test
  void cleanupRunsAfterPartialRegistrationAndPreservesForeignPushers() throws IOException {
    var calls = new ArrayList<String>();
    var steps = new NotificationLifecycleSteps((method, uri) -> {
      calls.add(method + " " + uri);
      return response(200, method == Method.GET
          ? "[{\"pushkey\":\"foreign\",\"app_id\":\"app/id+\"}]" : "");
    }, records);
    prepare(steps);
    assertThat(calls).isEmpty();
    assertThat(recordText()).contains("pending", IDENTITY, KEY);
    // Simulates a failing registration/assertion: only the After hook can now run.
    steps.cleanup();
    assertThat(calls).containsExactly(
        "DELETE http://example.test/pushers?pushkey=zeta-lifecycle-owned%2F%2B&appId=app%2Fid%2B",
        "GET http://example.test/pushers");
    assertThat(recordText()).contains("cleaned");
    steps.cleanup();
    assertThat(calls).hasSize(2);
  }

  @Test
  void failedCleanupRetainsRecoveryRecordAndCanBeRetried() throws IOException {
    var deletionResponse = response(500, "");
    var steps = new NotificationLifecycleSteps((method, uri) ->
        method == Method.DELETE ? deletionResponse : response(200, "[]"), records);
    prepare(steps);
    assertThrows(AssertionError.class, steps::cleanup);
    assertThat(recordText()).contains("pending");
    when(deletionResponse.statusCode()).thenReturn(200);
    steps.cleanup();
    assertThat(recordText()).contains("cleaned");
  }

  @Test
  void identityCleanupAttemptsBothOwnersAndRetriesOnlyTheFailedOne() throws IOException {
    var identity = new AtomicReference<String>();
    var failFirst = new AtomicBoolean(true);
    var calls = new ArrayList<String>();
    var steps = new NotificationLifecycleSteps((method, uri) -> {
      calls.add(identity.get() + " " + method);
      return response(IDENTITY.equals(identity.get()) && failFirst.get() ? 500 : 200, "[]");
    }, records, (selected, email) -> {
      assertThat(email).isEqualTo(selected + "@example.test");
      identity.set(selected);
    });
    for (var owner : List.of(IDENTITY, "Y987654321")) {
      steps.prepareCleanup(KEY, APP, owner, "http://example.test/pushers", "example.test", "test", true);
    }
    assertThat(identity.get()).isNull();
    assertThat(calls).isEmpty();
    var failure = assertThrows(AssertionError.class, steps::cleanup);
    assertThat(failure.getSuppressed()).hasSize(1);
    assertThat(calls).containsExactly(IDENTITY + " DELETE", "Y987654321 DELETE", "Y987654321 GET");
    assertThat(recordFor(IDENTITY)).contains("pending");
    assertThat(recordFor("Y987654321")).contains("cleaned");
    failFirst.set(false);
    calls.clear();
    steps.cleanup();
    assertThat(calls).containsExactly(IDENTITY + " DELETE", IDENTITY + " GET");
    assertThat(recordFor(IDENTITY)).contains("cleaned");
    calls.clear();
    steps.cleanup();
    assertThat(calls).isEmpty();
  }

  @Test
  void failedIdentitySelectionNeverDeletesUnderThePreviousIdentity() throws IOException {
    var calls = new ArrayList<Method>();
    var steps = new NotificationLifecycleSteps((method, uri) -> {
      calls.add(method);
      return response(200, "[]");
    }, records, (identity, email) -> {
      if (IDENTITY.equals(identity)) {
        throw new AssertionError("Cannot select first owner");
      }
    });
    for (var owner : List.of(IDENTITY, "Y987654321")) {
      steps.prepareCleanup(KEY, APP, owner, "http://example.test/pushers", "example.test", "test", true);
    }
    assertThrows(AssertionError.class, steps::cleanup);
    assertThat(calls).containsExactly(Method.DELETE, Method.GET);
    assertThat(recordFor(IDENTITY)).contains("pending");
    assertThat(recordFor("Y987654321")).contains("cleaned");
  }

  @Test
  void identityCleanupDetectsRegistrationRemainingForEitherOwner() throws IOException {
    var identity = new AtomicReference<String>();
    var steps = new NotificationLifecycleSteps((method, uri) -> response(200,
        method == Method.GET && IDENTITY.equals(identity.get())
            ? "[{\"pushkey\":\"" + KEY + "\",\"app_id\":\"" + APP + "\"}]" : "[]"),
        records, (selected, email) -> identity.set(selected));
    for (var owner : List.of(IDENTITY, "Y987654321")) {
      steps.prepareCleanup(KEY, APP, owner, "http://example.test/pushers", "example.test", "test", true);
    }
    assertThrows(AssertionError.class, steps::cleanup);
    assertThat(recordFor(IDENTITY)).contains("pending");
    assertThat(recordFor("Y987654321")).contains("cleaned");
  }

  @Test
  void cleanupCannotReportSuccessForFailedObservationOrRemainingOwnedPusher() throws IOException {
    var observed = response(500, "[]");
    var steps = new NotificationLifecycleSteps((method, uri) ->
        method == Method.DELETE ? response(200, "") : observed, records);
    prepare(steps);
    assertThrows(AssertionError.class, steps::cleanup);
    when(observed.statusCode()).thenReturn(200);
    for (var body : List.of("{}", "null", "[{}]", "[42]",
        "[{\"pushkey\":\"" + KEY + "\",\"app_id\":\"" + APP + "\"}]")) {
      when(observed.asString()).thenReturn(body);
      assertThrows(AssertionError.class, steps::cleanup);
      assertThat(recordText()).contains("pending");
    }
  }

  @Test
  void lifecycleSchemaRejectsLostConfigurationAndDuplicates() throws IOException {
    var schema = resolveSchema(Files.readString(Path.of(
        "src/test/resources/testdata/push/pusher_lifecycle_response.schema.json")));
    var checker = new JsonSchemaChecker();
    assertDoesNotThrow(() -> checker.compareToSchema("{\"pushers\":[" + PUSHER + "]}", schema));
    for (var invalid : List.of(
        PUSHER.replace("event_id_only", "wrong"),
        PUSHER.replace(",\"format\":\"event_id_only\"", ""),
        PUSHER.replace("\"profile_tag\":\"profile\"", "\"profile_tag\":null"),
        PUSHER.replace("Device", "Old device"), PUSHER + "," + PUSHER, "")) {
      assertThrows(AssertionError.class,
          () -> checker.compareToSchema("{\"pushers\":[" + invalid + "]}", schema));
    }
    var updated = schema.replace("Device", "Updated device");
    assertThrows(AssertionError.class,
        () -> checker.compareToSchema("{\"pushers\":[" + PUSHER + "]}", updated));
    assertDoesNotThrow(() -> checker.compareToSchema(
        "{\"pushers\":[" + PUSHER.replace("Device", "Updated device") + "]}", updated));
    // Unrelated API metadata errors are collected by the following custom validation step.
    assertDoesNotThrow(() -> checker.compareToSchema(
        "{\"pushers\":[" + PUSHER.replace("\"append\":false", "\"append\":null") + "]}", schema));
  }

  @Test
  void discoveryUsesTheVersionTwoRequiredScopesContract() throws IOException {
    var schema = lifecycle().getSteps().stream()
        .flatMap(step -> step.getArgument().stream())
        .flatMap(argument -> argument.getDocString().stream())
        .map(doc -> doc.getContent())
        .filter(content -> content.contains("opr-well-known.yaml"))
        .findFirst().orElseThrow();
    var checker = new JsonSchemaChecker();
    var discovery = """
        {"resource":"https://example.test/notification",
         "authorization_servers":["https://example.test/"],
         "scopes_supported":["notification.pusher.read"],"zeta_asl_use":"not_supported"}
        """;
    assertDoesNotThrow(() -> checker.compareToSchema(discovery, schema));
    assertThrows(AssertionError.class, () -> checker.compareToSchema(
        discovery.replace("\"scopes_supported\":[\"notification.pusher.read\"],", ""), schema));
  }

  @Test
  void discoveryHistorySchemaChecksBothIndependentlyObservedProfiles() throws IOException {
    var raw = lifecycle().getSteps().stream()
        .flatMap(step -> step.getArgument().stream())
        .flatMap(argument -> argument.getDocString().stream())
        .map(doc -> doc.getContent())
        .filter(content -> content.contains("notificationLifecycleHistoryScopeCount"))
        .findFirst().orElseThrow();
    var checker = new JsonSchemaChecker();
    for (var enabled : List.of("true", "false")) {
      var count = NotificationLifecycleSteps.historyScopeCount(enabled + "\n");
      var schema = raw.replace("${notificationLifecycleHistoryScopeCount}", Integer.toString(count));
      var present = "[\"notification.history.read\"]";
      assertDoesNotThrow(() -> checker.compareToSchema(count == 1 ? present : "[]", schema));
      assertThrows(AssertionError.class, () -> checker.compareToSchema(count == 1 ? "[]" : present, schema));
      assertThrows(AssertionError.class, () -> checker.compareToSchema(
          "[\"notification.history.read\",\"notification.history.read\"]", schema));
    }
    for (var unknown : List.of("", "TRUE", "true\nfalse", "missing")) {
      assertThrows(AssertionError.class, () -> NotificationLifecycleSteps.historyScopeCount(unknown));
    }
  }

  @Test
  void everyLifecycleRequestRejectsForeignOrAdditionalAudiences() throws IOException {
    var audienceChecks = lifecycle().getSteps().stream()
        .filter(step -> step.getText().contains("${headers.authorization.dpopToken.root}.body\""))
        .toList();
    assertThat(audienceChecks).hasSize(7);
    var resource = "https://example.test/notification";
    var checker = new JsonSchemaChecker();
    for (var step : audienceChecks) {
      var schema = step.getArgument().orElseThrow().getDocString().orElseThrow().getContent()
          .replace("${notificationLifecycleResource}", resource);
      for (var valid : List.of("\"" + resource + "\"", "[\"" + resource + "\"]",
          "[\"" + resource.replace("/", "\\/") + "\"]")) {
        assertDoesNotThrow(() -> checker.compareToSchema("{\"aud\":" + valid + "}", schema));
      }
      for (var invalid : List.of("\"https://example.test/other\"", "[]",
          "[\"" + resource + "\",\"https://example.test/other\"]")) {
        assertThrows(AssertionError.class,
            () -> checker.compareToSchema("{\"aud\":" + invalid + "}", schema));
      }
    }
  }

  private static Pickle lifecycle() throws IOException {
    return scenario("Notification Service registriert und deregistriert einen Pusher mit dienstspezifischem Token");
  }

  @Test
  void ownershipListsRejectForeignEntriesAndLostOrChangedControls() throws IOException {
    var original = PUSHER.replace("owned", "original");
    var ownerControl = PUSHER.replace("owned", "owner-control");
    var otherControl = PUSHER.replace("owned", "other-control");
    var foreign = PUSHER.replace("owned", "foreign");
    var checker = new JsonSchemaChecker();
    int checked = 0;
    for (var step : scenario("Notification Service ordnet Pusher beim Nutzerwechsel gemäß append-Default neu zu")
        .getSteps()) {
      if (step.getArgument().isEmpty() || step.getArgument().get().getDocString().isEmpty()) {
        continue;
      }
      var raw = step.getArgument().get().getDocString().get().getContent();
      if (!raw.contains("\"const\":${notificationOwnerControlPusher}")
          && !raw.contains("\"const\":${notificationOtherControlPusher}")) {
        continue;
      }
      var schema = raw.replace("${notificationOriginalPusher}", original)
          .replace("${notificationOwnerControlPusher}", ownerControl)
          .replace("${notificationOtherControlPusher}", otherControl);
      var control = raw.contains("notificationOwnerControlPusher") ? ownerControl : otherControl;
      var entries = raw.contains("\"minItems\":2") ? original + "," + control : control;
      assertDoesNotThrow(() -> checker.compareToSchema("{\"pushers\":[" + entries + "]}", schema));
      if (raw.contains("\"minItems\":2")) {
        assertDoesNotThrow(() -> checker.compareToSchema(
            "{\"pushers\":[" + control + "," + original + "]}", schema));
      }
      for (var invalid : List.of(
          "{\"pushers\":[" + entries + "," + foreign + "]}",
          "{\"pushers\":[" + entries.replace(control, foreign) + "]}",
          "{\"pushers\":[" + entries.replace(control, control.replace("Device", "Changed")) + "]}",
          "{\"pushers\":[]}", "{}", "{\"pushers\":null}")) {
        assertThrows(AssertionError.class, () -> checker.compareToSchema(invalid, schema));
      }
      checked++;
    }
    assertThat(checked).isEqualTo(5);
  }

  private static Pickle scenario(String name) throws IOException {
    try (var stream = GherkinParser.builder().build().parse(Path.of(
        "src/test/resources/features/UserStory_02/UseCase_01/push_benachrichtigungen_verwalten.feature"))) {
      var messages = stream.toList();
      assertThat(messages.stream().flatMap(message -> message.getParseError().stream()).toList()).isEmpty();
      return messages.stream().flatMap(message -> message.getPickle().stream())
          .filter(pickle -> pickle.getName().equals(name))
          .findFirst().orElseThrow();
    }
  }

  private static String resolveSchema(String schema) {
    var values = Map.of("pushkey", "owned", "appId", "app", "appDisplayName", "App",
        "deviceDisplayName", "Device", "profileTag", "profile", "lang", "de",
        "url", "https://example.test/push/v1/", "format", "event_id_only");
    for (var value : values.entrySet()) {
      schema = schema.replace("${testdata.notificationService.pusher." + value.getKey() + "}", value.getValue());
    }
    return schema;
  }

  private void prepare(NotificationLifecycleSteps steps) throws IOException {
    steps.prepareCleanup(KEY, APP, IDENTITY, "http://example.test/pushers", "example.test", "test");
  }

  private String recordText() throws IOException {
    try (var files = Files.list(records)) {
      return Files.readString(files.findFirst().orElseThrow());
    }
  }

  private String recordFor(String identity) throws IOException {
    try (var files = Files.list(records)) {
      for (var file : files.toList()) {
        var record = Files.readString(file);
        if (record.contains(identity)) {
          return record;
        }
      }
    }
    throw new AssertionError("Missing cleanup record for " + identity);
  }

  private static Response response(int status, String body) {
    var response = mock(Response.class);
    when(response.statusCode()).thenReturn(status);
    when(response.asString()).thenReturn(body);
    return response;
  }
}
