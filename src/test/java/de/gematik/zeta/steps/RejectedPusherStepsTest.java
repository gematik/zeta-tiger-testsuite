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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.rbellogger.util.RbelJexlExecutor;
import de.gematik.test.tiger.common.config.RbelModificationDescription;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.RecordedMessages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RejectedPusherStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void interveningFeatureChecksDoNotRestartTheAcceptanceBudget() {
    var accepted = Instant.parse("2026-10-13T14:00:00Z");
    assertEquals(Duration.ofSeconds(13),
        RejectedPusherSteps.remainingBudget(accepted, accepted.plusSeconds(7)));
    assertThrows(AssertionError.class,
        () -> RejectedPusherSteps.remainingBudget(accepted, accepted.plusSeconds(20)));
    assertThrows(AssertionError.class,
        () -> RejectedPusherSteps.remainingBudget(accepted, accepted.plusSeconds(21)));
  }

  @Test
  void slightlyFutureProxyTimestampDoesNotExtendTheObservationBudget() {
    var now = Instant.parse("2026-10-13T14:00:00Z");
    assertEquals(Duration.ofSeconds(20), RejectedPusherSteps.remainingBudget(now, now));
    assertEquals(Duration.ofSeconds(20),
        RejectedPusherSteps.remainingBudget(now.plusMillis(300), now));
    assertEquals(Duration.ofSeconds(20),
        RejectedPusherSteps.remainingBudget(now.plusSeconds(1), now));
    assertThrows(AssertionError.class,
        () -> RejectedPusherSteps.remainingBudget(now.plusSeconds(1).plusNanos(1), now));
  }

  @Test
  void anotherUserCategoryOrEventCannotSatisfyTheSdkRoute() {
    var request = JSON.readTree("""
        {"user_id":"A123456789","event_type":"registered_client_activity",
        "correlation_id":"event-marker"}
        """);
    assertDoesNotThrow(() -> RejectedPusherSteps.verifyEventRequest(
        request, "A123456789", "event-marker"));
    for (String field : new String[] {"user_id", "event_type", "correlation_id"}) {
      var changed = request.deepCopy();
      ((ObjectNode) changed).put(field, "another-value");
      assertThrows(AssertionError.class, () -> RejectedPusherSteps.verifyEventRequest(
          changed, "A123456789", "event-marker"), field);
    }
  }

  @Test
  void encryptedRegistrationCannotSatisfyTheUnencryptedProfile() {
    var plain = JSON.readTree("""
        [{"pushKey":"good","appId":"de.gematik.zeta.ta29971.rejected"}]
        """);
    assertDoesNotThrow(() -> RejectedPusherSteps.unencryptedKeys(plain, "pushKey", "appId"));
    ((ObjectNode) plain.get(0)).putNull("encryption");
    assertDoesNotThrow(() -> RejectedPusherSteps.unencryptedKeys(plain, "pushKey", "appId"));
    ((ObjectNode) plain.get(0)).putObject("encryption").put("method", "aes-hmac-sha256");
    assertThrows(AssertionError.class,
        () -> RejectedPusherSteps.unencryptedKeys(plain, "pushKey", "appId"));
  }

  @Test
  void originalNotificationRetryFailsEvenAfterSuccessfulFollowUpAndWithoutAResponse() {
    var original = gatewayRequest("/push/v1/notify/batch", batch("original", "bad"));
    var follow = gatewayRequest("/push/v1/notify/batch", batch("follow-bad-marker", "good"));
    assertDoesNotThrow(() -> RejectedPusherSteps.verifyStoppedDelivery(
        List.of(original, follow), original.getUuid(), "bad"));
    var retry = gatewayRequest("/push/v1/notify/batch", batch("original", "bad"));
    assertThrows(AssertionError.class, () -> RejectedPusherSteps.verifyStoppedDelivery(
        List.of(original, follow, retry), original.getUuid(), "bad"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/push/v1/notify", "/push/v1/notify/batch",
      "/push/v1/notifyEncrypted/batch"})
  void rejectedKeyCannotEscapeThroughAnotherEndpointOrEventMarker(String path) {
    var original = gatewayRequest("/push/v1/notify/batch", batch("original", "bad"));
    var notification = JSON.readTree(batch("unrelated", "bad"))
        .path("notifications").get(0).path("notification").deepCopy();
    if (path.contains("Encrypted")) {
      ((ObjectNode) notification).set("device", notification.path("devices").get(0));
      ((ObjectNode) notification).remove("devices");
    }
    var body = JSON.createObjectNode();
    if (path.endsWith("/batch")) {
      body.putArray("notifications").addObject().set("notification", notification);
    } else {
      body.set("notification", notification);
    }
    var later = gatewayRequest(path, body.toString());
    assertThrows(AssertionError.class, () -> RejectedPusherSteps.verifyStoppedDelivery(
        List.of(original, later), original.getUuid(), "bad"));
  }

  @Test
  void rejectedKeyInAnotherBatchItemIsStillObserved() {
    var original = gatewayRequest("/push/v1/notify/batch", batch("original", "bad"));
    var body = JSON.readTree(batch("follow", "good"));
    ((ObjectNode) body).withArray("notifications")
        .add(JSON.readTree(batch("another-event", "bad")).path("notifications").get(0));
    var later = gatewayRequest("/push/v1/notify/batch", body.toString());
    assertThrows(AssertionError.class, () -> RejectedPusherSteps.verifyStoppedDelivery(
        List.of(original, later), original.getUuid(), "bad"));
  }

  @Test
  void registrationTrafficAndPayloadTextDoNotCountAsDeliveryToTheRejectedKey() {
    var original = gatewayRequest("/push/v1/notify/batch", batch("original", "bad"));
    var registration = gatewayRequest("/push/v1/pushers/set", "{\"pushkey\":\"bad\"}");
    var followBody = JSON.readTree(batch("follow", "good"));
    ((ObjectNode) followBody.at("/notifications/0/notification/content")).put("pushkey", "bad");
    var follow = gatewayRequest("/push/v1/notify/batch", followBody.toString());
    assertDoesNotThrow(() -> RejectedPusherSteps.verifyStoppedDelivery(
        List.of(original, registration, follow), original.getUuid(), "bad"));
  }

  @Test
  void erasedHistoryOrMalformedGatewayBodiesCannotEstablishAbsence() {
    var original = gatewayRequest("/push/v1/notify/batch", batch("original", "bad"));
    assertThrows(AssertionError.class, () -> RejectedPusherSteps.verifyStoppedDelivery(
        List.of(), original.getUuid(), "bad"));
    var malformed = gatewayRequest("/push/v1/notify/batch", "{not-json}");
    assertThrows(RuntimeException.class, () -> RejectedPusherSteps.verifyStoppedDelivery(
        List.of(original, malformed), original.getUuid(), "bad"));
  }

  private static String batch(String marker, String pushkey) {
    return """
        {"notifications":[{"id":"event-id","notification":{"content":{"correlation_id":"%s"},
        "devices":[{"pushkey":"%s","app_id":"de.gematik.zeta.ta29971.rejected"}]}}]}
        """.formatted(marker, pushkey);
  }

  private static RbelElement gatewayRequest(String path, String body) {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    var raw = "POST " + path + " HTTP/1.1\r\nHost: gateway.test\r\n"
        + "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n" + body;
    return RbelLogger.build().getRbelConverter().parseMessage(raw.getBytes(StandardCharsets.UTF_8),
        new RbelMessageMetadata());
  }

  private static RbelElement gatewayResponse(RbelLogger logger, RbelElement request, int status, String body) {
    var raw = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: "
        + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    var response = logger.getRbelConverter().parseMessage(raw.getBytes(StandardCharsets.UTF_8),
        new RbelMessageMetadata());
    var pair = TracingMessagePairFacet.builder().request(request).responses(List.of(response)).build();
    request.addFacet(pair);
    response.addFacet(pair);
    return response;
  }

  private static ObjectNode twoDeviceBatch(boolean reversed) {
    var request = (ObjectNode) JSON.readTree(batch("marker", reversed ? "good" : "bad"));
    ((ObjectNode) request.path("notifications").get(0).path("notification"))
        .withArray("devices").addObject()
        .put("pushkey", reversed ? "bad" : "good")
        .put("app_id", "de.gematik.zeta.ta29971.rejected");
    return request;
  }

  private static void installFeatureManipulation(RbelLogger logger, String variable, int position)
      throws IOException {
    var feature = Files.readString(Path.of(
        "src/test/resources/features/UserStory_02/UseCase_07/abgelehnte_pusher_entfernen.feature"));
    var rule = Pattern.compile("Nachricht \"([^\"]*" + Pattern.quote(variable) + "[^\"]*)\" die Manipulation auf Feld"
        + " \"([^\"]*)\" und folgenden JSON-Wert:\n\\s*\"\"\"\n([\\s\\S]*?)\n\\s*\"\"\"")
        .matcher(feature);
    assertTrue(rule.find(), "Missing request-aware response template");
    var condition = rule.group(1).replace("${" + variable + "}", "marker");
    var target = rule.group(2);
    var template = rule.group(3).replace("<abgelehntePosition>", Integer.toString(position));
    assertEquals(false, rule.find(), "Repeated response template");
    RbelJexlExecutor.initialize();
    var replacement = new AtomicReference<String>();
    var steps = new TigerProxyManipulationsSteps() {
      @Override
      public void setTigerProxyManipulation(String selector, String field, String value) {
        assertEquals(condition, selector);
        assertEquals(target, field);
        replacement.set(value);
      }
    };
    steps.setTigerProxyJsonManipulation(condition, target, template);
    logger.getRbelModifier().addModification(RbelModificationDescription.builder()
        .name("selected-position").condition(condition).targetElement(target)
        .replaceWith(replacement.get()).deleteAfterNExecutions(1).build());
  }

  @ParameterizedTest
  @CsvSource({"0,false", "1,false", "0,true", "1,true"})
  void featureTemplateRejectsTheSelectedOutgoingPositionInEitherDeviceOrder(int position, boolean reversed)
      throws IOException {
    var logger = RbelLogger.build();
    var body = twoDeviceBatch(reversed);
    var request = gatewayRequest("/push/v1/notify/batch", body.toString());
    var response = gatewayResponse(logger, request, 200, """
        {"results":[{"id":"upstream-id","status":"success","rejected":[]}],
        "summary":{"total":1,"successful":1,"failed":0,"partial":0}}
        """);
    installFeatureManipulation(logger, "CC_REJECT_MARKER", position);
    var unrelatedRequest = gatewayRequest("/push/v1/notify/batch",
        body.toString().replace("marker", "another-event"));
    var unrelatedResponse = gatewayResponse(logger, unrelatedRequest, 200,
        response.findRbelPathMembers("$.body").getFirst().getRawStringContent());
    assertEquals(unrelatedResponse, logger.getRbelModifier().applyModifications(unrelatedResponse));
    var changed = logger.getRbelModifier().applyModifications(response);
    var reply = JSON.readTree(changed.findRbelPathMembers("$.body").getFirst().getRawStringContent());
    var devices = body.path("notifications").get(0).path("notification").path("devices");
    var rejected = devices.get(position).path("pushkey").asString();
    var valid = devices.get(1 - position).path("pushkey").asString();
    assertDoesNotThrow(() -> RejectedPusherSteps.verifyGateway(body, reply, "marker", rejected, valid));
    assertThrows(AssertionError.class,
        () -> RejectedPusherSteps.verifyGateway(body, reply, "marker", valid, rejected));
    assertEquals(response, logger.getRbelModifier().applyModifications(response));
  }

  @Test
  void followUpTemplateUsesTheActualItemIdAndOnlyRunsOnce() throws IOException {
    var logger = RbelLogger.build();
    var body = JSON.readTree(batch("marker", "good"));
    var request = gatewayRequest("/push/v1/notify/batch", body.toString());
    var response = gatewayResponse(logger, request, 200, """
        {"results":[{"id":"upstream-id","status":"failed","rejected":["good"]}],
        "summary":{"total":1,"successful":0,"failed":1,"partial":0}}
        """);
    installFeatureManipulation(logger, "CC_REJECT_FOLLOW", 0);
    var changed = logger.getRbelModifier().applyModifications(response);
    var reply = JSON.readTree(changed.findRbelPathMembers("$.body").getFirst().getRawStringContent());
    assertDoesNotThrow(() -> RejectedPusherSteps.verifySuccessfulGateway(body, reply, "marker", "good"));
    assertEquals(response, logger.getRbelModifier().applyModifications(response));
  }

  @Test
  void acceptanceAndGatewayItemIdsRemainIndependent() {
    var logger = RbelLogger.build();
    var event = gatewayRequest("/sdk/events", """
        {"user_id":"A123456789","event_type":"registered_client_activity","correlation_id":"marker"}
        """);
    var accepted = gatewayResponse(logger, event, 202, """
        {"status":"accepted","channel":"zeta","notification_id":"01234567-89ab-cdef-0123-456789abcdef"}
        """);
    accepted.addOrReplaceFacet(new RbelMessageTimingFacet(Instant.now().atZone(ZoneOffset.UTC)));
    var request = gatewayRequest("/push/v1/notify/batch", twoDeviceBatch(false).toString());
    var response = gatewayResponse(logger, request, 200, """
        {"results":[{"id":"event-id","status":"partial","rejected":["bad"]}],
        "summary":{"total":1,"successful":0,"failed":0,"partial":1}}
        """);
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var messages = mockStatic(RecordedMessages.class)) {
      config.when(() -> TigerGlobalConfiguration.readStringOptional("paths.client.notificationTestEventsPath"))
          .thenReturn(Optional.of("/sdk/events"));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(call -> call.getArgument(0));
      messages.when(RecordedMessages::snapshot).thenReturn(List.of(event, accepted, request, response));
      var steps = new RejectedPusherSteps();
      steps.prepare("A123456789", "bad", "good", "marker");
      assertDoesNotThrow(steps::verifyReceipt);
      assertDoesNotThrow(() -> steps.verifyGatewayDelivery("bad", "good"));
    }
  }

  @Test
  void successfulFollowUpMustReachExactlyTheSurvivingPusher() {
    var request = JSON.readTree("""
        {"notifications":[{"id":"follow-id","notification":{"content":{"correlation_id":"follow"},
        "devices":[{"pushkey":"good","app_id":"de.gematik.zeta.ta29971.rejected"}]}}]}
        """);
    var reply = JSON.readTree("""
        {"results":[{"id":"follow-id","status":"success","rejected":[]}],
        "summary":{"total":1,"successful":1,"failed":0,"partial":0}}
        """);
    assertDoesNotThrow(() -> RejectedPusherSteps.verifySuccessfulGateway(
        request, reply, "follow", "good"));
    var withoutOptionalRejected = reply.deepCopy();
    ((ObjectNode) withoutOptionalRejected.path("results").get(0)).remove("rejected");
    assertDoesNotThrow(() -> RejectedPusherSteps.verifySuccessfulGateway(
        request, withoutOptionalRejected, "follow", "good"));
    for (String mutation : new String[] {
        "rejected-key", "no-neighbour", "foreign-app", "foreign-id", "old-marker", "duplicate-item"
    }) {
      var changed = request.deepCopy();
      var item = (ObjectNode) changed.path("notifications").get(0);
      var notification = (ObjectNode) item.path("notification");
      switch (mutation) {
        case "rejected-key" -> notification.withArray("devices").addObject()
            .put("pushkey", "bad").put("app_id", "de.gematik.zeta.ta29971.rejected");
        case "no-neighbour" -> notification.putArray("devices");
        case "foreign-app" -> ((ObjectNode) notification.path("devices").get(0))
            .put("app_id", "another.app");
        case "foreign-id" -> item.put("id", "original-id");
        case "old-marker" -> ((ObjectNode) notification.path("content"))
            .put("correlation_id", "original-marker");
        case "duplicate-item" -> ((ObjectNode) changed).withArray("notifications").add(item.deepCopy());
        default -> throw new AssertionError();
      }
      assertThrows(AssertionError.class, () -> RejectedPusherSteps.verifySuccessfulGateway(
          changed, reply, "follow", "good"), mutation);
    }
    for (String mutation : new String[] {
        "wrong-id", "rejected-neighbour", "failed", "wrong-summary", "non-integer-summary",
        "overflow-summary"
    }) {
      var changed = reply.deepCopy();
      var result = (ObjectNode) changed.path("results").get(0);
      switch (mutation) {
        case "wrong-id" -> result.put("id", "original-id");
        case "rejected-neighbour" -> result.putArray("rejected").add("good");
        case "failed" -> result.put("status", "failed");
        case "wrong-summary" -> ((ObjectNode) changed.path("summary")).put("successful", 0);
        case "non-integer-summary" -> ((ObjectNode) changed.path("summary")).put("successful", "1");
        case "overflow-summary" -> ((ObjectNode) changed.path("summary"))
            .put("successful", 4294967297L);
        default -> throw new AssertionError();
      }
      assertThrows(AssertionError.class, () -> RejectedPusherSteps.verifySuccessfulGateway(
          request, changed, "follow", "good"), mutation);
    }
  }

  @Test
  void followUpCannotBeAcceptedWithoutVerifiedRemoval() {
    var steps = new RejectedPusherSteps();
    steps.prepare("A123456789", "bad", "good", "original");
    assertThrows(AssertionError.class, () -> steps.verifyFollowUpReceipt("follow"));
    assertThrows(AssertionError.class, steps::verifyFollowUpDelivery);
    assertThrows(AssertionError.class, steps::verifyNoFurtherRejectedDelivery);
    assertThrows(AssertionError.class, steps::verifySurvivingStorageInventory);
  }

  @Test
  void bindsRejectionToActualItemAndPreservesTheValidNeighbour() {
    var request =
        JSON.readTree(
            """
            {"notifications":[{"id":"event-id","notification":{"content":{"correlation_id":"marker"},
            "devices":[{"pushkey":"bad","app_id":"de.gematik.zeta.ta29971.rejected"},
            {"pushkey":"good","app_id":"de.gematik.zeta.ta29971.rejected"}]}}]}
            """);
    var reply =
        JSON.readTree(
            """
            {"results":[{"id":"event-id","status":"partial","rejected":["bad"]}],
            "summary":{"total":1,"successful":0,"failed":0,"partial":1}}
            """);
    assertDoesNotThrow(
        () ->
            RejectedPusherSteps.verifyGateway(request, reply, "marker", "bad", "good"));
    for (String mutation :
        new String[] {
          "foreign-id", "missing-rejection", "valid-rejected", "wrong-status", "wrong-summary"
        }) {
      var changed = reply.deepCopy();
      var item = (ObjectNode) changed.path("results").get(0);
      switch (mutation) {
        case "foreign-id" -> item.put("id", "another-event");
        case "missing-rejection" -> item.putArray("rejected");
        case "valid-rejected" -> item.putArray("rejected").add("good");
        case "wrong-status" -> item.put("status", "failed");
        case "wrong-summary" -> ((ObjectNode) changed.path("summary")).put("partial", 0);
        default -> throw new AssertionError();
      }
      assertThrows(
          AssertionError.class,
          () ->
              RejectedPusherSteps.verifyGateway(
                  request, changed, "marker", "bad", "good"),
          mutation);
    }
    assertThrows(
        AssertionError.class,
        () ->
            RejectedPusherSteps.verifyGateway(
                request, reply, "foreign-marker", "bad", "good"));
    ((ObjectNode) request.at("/notifications/0/notification/devices/1")).put("pushkey", "bad");
    assertThrows(
        AssertionError.class,
        () ->
            RejectedPusherSteps.verifyGateway(request, reply, "marker", "bad", "good"));
  }
}
