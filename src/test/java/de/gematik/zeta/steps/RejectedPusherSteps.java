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

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.common.jexl.TigerJexlExecutor;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.NotificationReadOnlyStore;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Controlled actual gateway replies with independent API and committed pusher-store observations.
 */
public class RejectedPusherSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String APP = "de.gematik.zeta.ta29971.rejected";
  private static final Duration MAX_PROXY_CLOCK_SKEW = Duration.ofSeconds(1);
  private String identity;
  private Set<String> initialKeys;
  private String rejected;
  private String valid;
  private String marker;
  private String id;
  private String initialGatewayRequestId;
  private long deadline;
  private boolean removed;
  private String followMarker;
  private String followId;
  private long followDeadline;
  private boolean followDeliveryVerified;
  private boolean followObservationComplete;

  /**
   * Restores the controlled gateway response even after a failed receipt or storage assertion.
   */
  @After(value = "@rejected_pusher_response", order = 100)
  public void cleanupManipulations() {
    if (identity != null) {
      new TigerProxyManipulationsSteps().stopAllTigerProxyManipulations();
    }
  }

  /**
   * Selects the independently created recipient, unordered pusher keys and event correlation.
   *
   * @param identity reserved recipient identity
   * @param first first registered pusher key
   * @param second second registered pusher key
   * @param marker unique event correlation
   */
  @Gegebensei("eine NS-Pusherprüfung für Nutzer {tigerResolvedString}, Pusher {tigerResolvedString} und {tigerResolvedString} und Korrelation {tigerResolvedString}")
  public void prepare(String identity, String first, String second, String marker) {
    require(
        identity.matches("[A-Z][0-9]{9}")
            && first.matches("[A-Za-z0-9-]+")
            && second.matches("[A-Za-z0-9-]+")
            && !first.equals(second),
        "Invalid isolated pusher fixture");
    require(
        this.identity == null && marker.matches("[A-Za-z0-9-]+"),
        "Repeated or invalid pusher fixture");
    this.identity = identity;
    initialKeys = Set.of(first, second);
    this.marker = marker;
  }

  /**
   * Verifies that the stored pusher keys equal the two configured initial keys.
   */
  @Dann("enthält der NS-Speicher genau beide ursprünglichen Pusher")
  public void verifyInitialStorageInventory() {
    require(identity != null, "Pusher fixture was not prepared");
    require(
        storageKeys(identity).equals(initialKeys),
        "Committed pusher inventory differs before rejection");
  }

  /**
   * Binds the explicitly submitted event to its recorded acceptance and original time budget.
   */
  @Dann("hat der NS die Benachrichtigung für die Pusherprüfung angenommen")
  public void verifyReceipt() {
    require(identity != null && id == null, "Missing pusher fixture or repeated receipt");
    var received = eventResponse(marker);
    id = receiptId(received);
    deadline = observationDeadline(received);
  }

  private RbelElement eventResponse(String correlation) {
    var messages = RecordedMessages.snapshot();
    var requests = messages.stream()
        .filter(message -> config("paths.client.notificationTestEventsPath")
            .equals(value(message, "$.path")) && "POST".equals(value(message, "$.method"))
            && value(message, "$.body") != null
            && value(message, "$.body").contains(correlation)).toList();
    require(requests.size() == 1, "Missing, repeated or ambiguous SDK event request");
    var request = requests.getFirst();
    verifyEventRequest(JSON.readTree(value(request, "$.body")), identity, correlation);
    var received = new TimingGlue().findResponseForRequest(messages, request);
    require(received != null, "SDK event acceptance is missing");
    return received;
  }

  private static String receiptId(RbelElement received) {
    require("202".equals(value(received, "$.responseCode")), "NS did not accept the SDK event");
    var receipt = JSON.readTree(value(received, "$.body"));
    require("accepted".equals(receipt.path("status").asString())
            && "zeta".equals(receipt.path("channel").asString()),
        "Unexpected NS acceptance result or channel");
    var notificationId = receipt.path("notification_id").asString();
    require(notificationId != null && notificationId.matches("[a-f0-9-]{36}"),
        "Missing notification ID");
    return notificationId;
  }

  private static long observationDeadline(RbelElement received) {
    var accepted = received.getFacet(RbelMessageTimingFacet.class)
        .orElseThrow(() -> new AssertionError("SDK acceptance timestamp is missing"))
        .getTransmissionTime().toInstant();
    var now = Instant.now();
    var nanoNow = System.nanoTime();
    return nanoNow + remainingBudget(accepted, now).toNanos();
  }

  static Duration remainingBudget(Instant accepted, Instant now) {
    if (accepted.isAfter(now)) {
      require(Duration.between(now, accepted).compareTo(MAX_PROXY_CLOCK_SKEW) <= 0,
          "SDK acceptance timestamp exceeds tolerated proxy clock skew");
      // Proxy and runner clocks can differ; tolerate bounded skew without granting over 20 seconds.
      accepted = now;
    }
    var remaining = Duration.between(now, accepted.plusSeconds(20));
    require(!remaining.isNegative() && !remaining.isZero(), "Pusher observation budget expired");
    return remaining;
  }

  static void verifyEventRequest(JsonNode request, String identity, String correlation) {
    require(identity.equals(request.path("user_id").asString())
            && "registered_client_activity".equals(request.path("event_type").asString())
            && correlation.equals(request.path("correlation_id").asString()),
        "SDK-to-Testfachdienst event has a different user, category or correlation");
  }

  /**
   * Checks the actual two-device batch and its single-key partial rejection.
   *
   * @param rejected key selected from the outgoing batch for rejection
   * @param valid other outgoing key that must survive
   * @throws InterruptedException if interrupted while awaiting the gateway response
   */
  @Dann("weist die korrelierte Gateway-Antwort ausschließlich den Pushkey {tigerResolvedString} zurück und erhält den Pushkey {tigerResolvedString}")
  public void verifyGatewayDelivery(String rejected, String valid) throws InterruptedException {
    require(id != null, "Original acceptance has not been verified");
    require(!rejected.equals(valid) && initialKeys.equals(Set.of(rejected, valid)),
        "Selected gateway keys differ from the registered pusher fixture");
    this.rejected = rejected;
    this.valid = valid;
    var delivery = awaitGateway(marker, deadline);
    var request = delivery[0];
    var reply = delivery[1];
    var condition =
        "isResponse && request.path =~ '.*/push/v1/notify/batch' && request.bodyAsString =~ '(?s).*"
            + marker
            + ".*'";
    require(
        TigerJexlExecutor.matchesAsJexlExpression(reply, condition),
        "Gateway response does not match the configured correlation condition");
    verifyGateway(
        JSON.readTree(value(request, "$.body")),
        JSON.readTree(value(reply, "$.body")),
        marker,
        rejected,
        valid);
    require(
        "200".equals(value(reply, "$.responseCode")),
        "Gateway response did not reach NS as a valid batch reply");
    initialGatewayRequestId = request.getUuid();
    verifyStoppedDelivery(RecordedMessages.snapshot(), initialGatewayRequestId, rejected);
  }

  private RbelElement[] awaitGateway(String correlation, long bound) throws InterruptedException {
    return BoundedObservation.awaitUntil(
        () -> gatewayDelivery(correlation),
        pair -> pair[1] != null,
        bound,
        Duration.ofMillis(100),
        "Actual correlated NS-to-gateway delivery is missing or late");
  }

  private RbelElement[] gatewayDelivery(String correlation) {
    RbelElement request = null;
    RbelElement reply = null;
    var messages = RecordedMessages.snapshot();
    if (initialGatewayRequestId != null) {
      verifyStoppedDelivery(messages, initialGatewayRequestId, rejected);
    }
    var matches =
        messages.stream()
            .filter(
                r -> value(r, "$.method") != null
                    && value(r, "$.path") != null
                    && value(r, "$.path").matches(".*/notify(?:Encrypted)?(?:/batch)?")
                    && value(r, "$.body") != null
                    && value(r, "$.body").contains(correlation))
            .toList();
    require(matches.size() <= 1, "Repeated or ambiguous gateway delivery");
    if (matches.size() == 1) {
      request = matches.getFirst();
      require("POST".equals(value(request, "$.method"))
              && "/push/v1/notify/batch".equals(value(request, "$.path")),
          "Delivery does not use the selected unencrypted batch profile");
      reply = new TimingGlue().findResponseForRequest(messages, request);
    }
    return new RbelElement[] {request, reply};
  }

  /**
   * Observes only the rejected key disappearing within the same 20-second delivery/removal budget.
   */
  @Dann("enthalten SDK-Pusher-API und NS-Speicher innerhalb des Pusher-Prüfbudgets nur den erhaltenen Pusher")
  public void verifyRemoval() throws InterruptedException {
    require(id != null, "Original acceptance has not been verified");
    BoundedObservation.awaitUntil(
        () -> {
          verifyStoppedDelivery(RecordedMessages.snapshot(), initialGatewayRequestId, rejected);
          return java.util.List.of(apiKeys(), storageKeys(identity));
        },
        inventories -> inventories.stream().allMatch(keys -> keys.equals(Set.of(valid))),
        deadline,
        Duration.ofMillis(250),
        "NS did not remove exactly the rejected pusher within the shared observation budget");
    verifyStoppedDelivery(RecordedMessages.snapshot(), initialGatewayRequestId, rejected);
    removed = true;
    SerenityReportUtils.addCustomData(
        "Rejected pusher removal",
        "Two SDK-registered active pushers initially existed in API and committed store. One actual"
            + " NS batch contained both devices and the event correlation. Its correlated"
            + " response retained that item ID and rejected only one key. Both independent"
            + " inventories subsequently contain exactly the valid neighbour. The test performs no"
            + " pusher DELETE before this observation.");
  }

  /**
   * Keeps the separately submitted follow-up acceptance and its original deadline independent.
   */
  @Dann("hat der NS das Folgeereignis mit Korrelation {tigerResolvedString} und einer neuen Notification-ID angenommen")
  public void verifyFollowUpReceipt(String correlation) {
    require(removed && followId == null, "Removal not confirmed or repeated follow-up");
    require(correlation.matches("[A-Za-z0-9-]+") && !correlation.contains(marker),
        "Follow-up must have an independent event marker");
    followMarker = correlation;
    var received = eventResponse(correlation);
    followId = receiptId(received);
    require(!followId.equals(id), "Follow-up reused the original notification ID");
    followDeadline = observationDeadline(received);
  }

  /**
   * Verifies follow-up delivery to the surviving pusher and records its completion.
   *
   * @throws InterruptedException if interrupted while awaiting delivery
   */
  @Dann("wird das Folgeereignis ausschließlich an den erhaltenen Pusher versandt")
  public void verifyFollowUpDelivery() throws InterruptedException {
    require(followId != null, "Follow-up receipt not verified");
    var delivery = awaitGateway(followMarker, followDeadline);
    verifyFollowUp(delivery);
    followDeliveryVerified = true;
  }

  /**
   * Verifies absence of further rejected-pusher deliveries until the stored deadline.
   *
   * @throws InterruptedException if interrupted during observation
   */
  @Dann("wird im vollständigen Pusher-Prüffenster kein weiterer Versand an den abgelehnten Pushkey beobachtet")
  public void verifyNoFurtherRejectedDelivery() throws InterruptedException {
    require(followDeliveryVerified, "Follow-up positive control has not been verified");
    while (System.nanoTime() < followDeadline) {
      verifyFollowUp(gatewayDelivery(followMarker));
      Thread.sleep(100);
    }
    verifyFollowUp(gatewayDelivery(followMarker));
    verifyStoppedDelivery(RecordedMessages.snapshot(), initialGatewayRequestId, rejected);
    followObservationComplete = true;
  }

  /**
   * Verifies that only the surviving pusher key remains stored after follow-up observation.
   */
  @Dann("enthält der NS-Speicher weiterhin genau den erhaltenen Pusher")
  public void verifySurvivingStorageInventory() {
    require(followObservationComplete, "Follow-up observation has not been completed");
    require(storageKeys(identity).equals(Set.of(valid)),
        "Follow-up changed the surviving stored pusher inventory");
    verifyStoppedDelivery(RecordedMessages.snapshot(), initialGatewayRequestId, rejected);
  }

  /**
   * Excludes only the verified initial send; unique pushkeys also identify uncorrelated retries.
   */
  static void verifyStoppedDelivery(
      Collection<RbelElement> messages, String initialRequestId, String rejected) {
    require(initialRequestId != null
            && messages.stream().anyMatch(message -> initialRequestId.equals(message.getUuid())),
        "Verified initial gateway request is missing from the observation history");
    for (var message : messages) {
      var path = value(message, "$.path");
      if (initialRequestId.equals(message.getUuid()) || !"POST".equals(value(message, "$.method"))
          || path == null || !path.matches(".*/notify(?:Encrypted)?(?:/batch)?")) {
        continue;
      }
      var body = value(message, "$.body");
      require(body != null, "Gateway request body is missing from the observation");
      var request = JSON.readTree(body);
      if (request.path("notifications").isArray()) {
        for (var item : request.path("notifications")) {
          verifyNoRejectedDevice(item.path("notification"), rejected);
        }
      } else {
        verifyNoRejectedDevice(request.path("notification"), rejected);
      }
    }
  }

  private static void verifyNoRejectedDevice(JsonNode notification, String rejected) {
    for (var device : notification.path("devices")) {
      require(!rejected.equals(device.path("pushkey").asString()),
          "Further gateway request addresses the rejected pushkey");
    }
    require(!rejected.equals(notification.path("device").path("pushkey").asString()),
        "Further encrypted gateway request addresses the rejected pushkey");
  }

  private void verifyFollowUp(RbelElement[] delivery) {
    require(delivery[0] != null && delivery[1] != null, "Follow-up delivery missing");
    verifySuccessfulGateway(
        JSON.readTree(value(delivery[0], "$.body")),
        JSON.readTree(value(delivery[1], "$.body")), followMarker, valid);
  }

  static void verifySuccessfulGateway(
      JsonNode request, JsonNode reply, String marker, String valid) {
    var itemId = verifyDevices(request, marker, Set.of(valid));
    var results = reply.path("results");
    require(results.isArray() && results.size() == 1
            && results.get(0).path("id").isTextual()
            && itemId.equals(results.get(0).path("id").asString())
            && "success".equals(results.get(0).path("status").asString())
            && (results.get(0).path("rejected").isMissingNode()
                || (results.get(0).path("rejected").isArray()
                    && results.get(0).path("rejected").isEmpty())),
        "Follow-up was not successfully delivered to the valid neighbour");
    verifySummary(reply.path("summary"), 1, 0);
  }

  static void verifyGateway(
      JsonNode request, JsonNode reply, String marker, String rejected, String valid) {
    var itemId = verifyDevices(request, marker, Set.of(rejected, valid));
    verifyRejection(reply, itemId, rejected);
  }

  private static String verifyDevices(
      JsonNode request, String marker, Set<String> expected) {
    var items = request.path("notifications");
    require(
        items.isArray() && items.size() == 1 && items.get(0).path("id").isTextual(),
        "Gateway batch must contain exactly one item with a string ID");
    var notification = items.get(0).path("notification");
    require(
        marker.equals(notification.path("content").path("correlation_id").asString()),
        "Gateway payload lost its event correlation");
    var devices = notification.path("devices");
    require(
        keys(devices, "pushkey", "app_id").equals(expected),
        "Gateway did not receive exactly the expected SDK pushers");
    return items.get(0).path("id").asString();
  }

  private static void verifyRejection(JsonNode reply, String id, String rejected) {
    var results = reply.path("results");
    require(
        results.isArray()
            && results.size() == 1
            && results.get(0).path("id").isTextual()
            && id.equals(results.get(0).path("id").asString())
            && "partial".equals(results.get(0).path("status").asString()),
        "Gateway result is not the corresponding partial result; item count="
            + results.size()
            + ", status="
            + results.path(0).path("status").asString());
    require(
        results.get(0).path("rejected").isArray()
            && results.get(0).path("rejected").size() == 1
            && rejected.equals(results.get(0).path("rejected").get(0).asString()),
        "Wrong rejected-key set");
    verifySummary(reply.path("summary"), 0, 1);
  }

  private static void verifySummary(JsonNode summary, int successful, int partial) {
    for (String field : new String[] {"total", "successful", "failed", "partial"}) {
      require(summary.path(field).isIntegralNumber() && summary.path(field).canConvertToInt(),
          "Batch summary count is not a bounded integer");
    }
    require(
        summary.path("total").asInt(-1) == 1
            && summary.path("partial").asInt(-1) == partial
            && summary.path("successful").asInt(-1) == successful
            && summary.path("failed").asInt(-1) == 0,
        "Invalid batch summary");
  }

  private static Set<String> apiKeys() {
    var response = http().get(config("paths.client.notifications.pushers"));
    require(response.statusCode() == 200, "SDK pusher inventory failed");
    return unencryptedKeys(JSON.readTree(response.asString()), "pushkey", "app_id");
  }

  private static Set<String> storageKeys(String identity) {
    return unencryptedKeys(NotificationReadOnlyStore.pusherCollection(identity), "pushKey", "appId");
  }

  static Set<String> unencryptedKeys(JsonNode nodes, String key, String app) {
    var result = keys(nodes, key, app);
    for (var node : nodes) {
      require(node.path("encryption").isMissingNode() || node.path("encryption").isNull(),
          "The selected unencrypted profile contains encryption metadata");
    }
    return result;
  }

  private static RequestSpecification http() {
    var timeout =
        HttpClientConfig.httpClientConfig()
            .setParam("http.connection.timeout", 5000)
            .setParam("http.socket.timeout", 5000);
    return TigerHttpClient.givenDefaultSpec().config(RestAssured.config().httpClient(timeout));
  }

  static Set<String> keys(JsonNode nodes, String key, String app) {
    require(nodes.isArray(), "Pusher inventory is not an array");
    var result = new HashSet<String>();
    for (var node : nodes) {
      require(
          APP.equals(node.path(app).asString())
              && node.path(key).isTextual()
              && !node.path(key).asString().isBlank()
              && result.add(node.path(key).asString()),
          "Foreign or duplicate pusher inventory entry");
    }
    return Set.copyOf(result);
  }

  private static String value(RbelElement node, String path) {
    var values = node.findRbelPathMembers(path);
    return values.size() == 1 ? values.getFirst().getRawStringContent() : null;
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean value, String message) {
    if (!value) {
      throw new AssertionError(message);
    }
  }
}
