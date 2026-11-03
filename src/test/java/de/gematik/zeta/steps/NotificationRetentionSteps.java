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

import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.NotificationDispatchProfile;
import de.gematik.zeta.services.NotificationReadOnlyStore;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Actual processing records and physical purge for a declared local operator retention profile.
 */
public class NotificationRetentionSteps {
  private NotificationDispatchProfile profile;
  private final Map<String, Event> events = new LinkedHashMap<>();
  private final Map<String, JsonNode> snapshots = new LinkedHashMap<>();

  private record Event(String marker, long started, String id) {}

  /**
   * Selects a two-minute logical TTL and an explicitly bounded five-second physical purge budget.
   */
  @Gegebensei("ein lokales NS-Aufbewahrungsprofil mit 120 Sekunden TTL und höchstens 5 Sekunden Löschbudget")
  public void prepare() {
    require(profile == null, "Retention profile already exists");
    require(
        TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false),
        "The local retention test requires enabled deployment modification");
    profile =
        new NotificationDispatchProfile(
            ZetaDeploymentConfiguration.getServiceInstance(),
            ZetaDeploymentConfiguration.getNamespace(),
            config("testdata.ccNotification.rsDeployment"));
    profile.startRetention();
  }

  /**
   * Captures the selected Fachdienst request timestamp and distinct notification identity.
   *
   * @param name scenario-local event name
   * @param id notification ID returned through the SDK and NS responses
   * @param marker unique event correlation
   */
  @Dann("hat die Aufbewahrungsnachricht {word} die eindeutige ID {tigerResolvedString} und Korrelation {tigerResolvedString}")
  public void rememberReceipt(String name, String id, String marker) {
    require(
        profile != null && !events.containsKey(name),
        "Missing isolated retention fixture or repeated event name");
    require(
        id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
            && marker.matches("[A-Za-z0-9-]+")
            && events.values().stream().noneMatch(e -> id.equals(e.id()) || marker.equals(e.marker())),
        "Missing or repeated notification ID");
    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    var markers = request.findRbelPathMembers("$.body.correlation_id");
    require(markers.size() == 1 && marker.equals(markers.getFirst().getRawStringContent()),
        "Selected Fachdienst request belongs to a different retention event");
    long started = request.getFacet(RbelMessageTimingFacet.class)
        .orElseThrow(() -> new AssertionError("Missing original Fachdienst request timestamp"))
        .getTransmissionTime().toInstant().toEpochMilli();
    events.put(name, new Event(marker, started, id));
  }

  /**
   * Checks the exact committed metadata and its TTL before expiry.
   */
  @Dann("hat die Aufbewahrungsnachricht {word} einen frischen Verarbeitungsdatensatz mit 120 Sekunden TTL")
  public void verifyRecord(String name) {
    var event = event(name);
    var snapshot = NotificationReadOnlyStore.processingRecord(id(name));
    verifyAccepted(snapshot, id(name), event.marker(), event.started(), System.currentTimeMillis());
    snapshots.put(name, snapshot);
  }

  /**
   * Checks distinct, increasing expiry times for the two independent events.
   */
  @Dann("besitzen die Aufbewahrungsnachrichten {word} und {word} individuelle Ablaufzeiten")
  public void verifyIndependentExpiry(String first, String second) {
    require(
        !first.equals(second) && snapshots.containsKey(first) && snapshots.containsKey(second),
        "Two verified processing records are required");
    require(
        expiry(snapshots.get(second)) > expiry(snapshots.get(first)),
        "Independent events share a global expiry");
    SerenityReportUtils.addCustomData(
        "NS operator retention profile",
        "Local test policy: first persistence starts the lifetime; logical TTL 120 s, physical"
            + " deletion budget at most 5 s, maximum storage 125 s. Purger interval 1 s."
            + " Two accepted events carry distinct IDs/status and individual expiry times."
            + " This is an operator test policy, not a specification-wide numeric duration."
            + " Existing payload protection/history settings remain unchanged.");
  }

  /**
   * Checks the NS processing entry for the exact accepted notification ID.
   *
   * @param name scenario-local event name
   */
  @Dann("ist die Aufbewahrungsnachricht {word} im NS-Verarbeitungslog eindeutig korreliert")
  public void verifyProcessingLog(String name) {
    require(snapshots.containsKey(name), "No verified processing record for " + name);
    verifyProcessingLog(profile.logs(), id(name));
  }

  static void verifyProcessingLog(String logs, String id) {
    require(logs.lines().anyMatch(line -> line.contains("Notification " + id + " via ")),
        "Missing NS processing evidence for the accepted notification ID");
  }

  /**
   * Observes deletion within both the individual purge budget and the absolute storage limit.
   */
  @Dann("ist die Aufbewahrungsnachricht {word} spätestens 5 Sekunden nach ihrer TTL physisch gelöscht")
  public void verifyPurge(String name) throws InterruptedException {
    require(snapshots.containsKey(name), "No verified processing record for " + name);
    var snapshot = snapshots.get(name);
    long expires = expiry(snapshot);
    long deadline = physicalDeadline(snapshot);
    var last = NotificationReadOnlyStore.awaitPurge(id(name), expires, deadline);
    verifyPurged(last, deadline);
    profile.logs();
    SerenityReportUtils.addCustomData(
        "NS physical purge: " + name,
        "The exact scenario-owned processing row is absent after its individual TTL and within"
            + " the declared maximum storage period. Direct storage_record query; no API filtering"
            + " or test-issued DELETE can satisfy this observation.");
  }

  private Event event(String name) {
    require(events.containsKey(name), "Unknown retention event: " + name);
    return events.get(name);
  }

  private String id(String name) {
    return event(name).id();
  }

  static long physicalDeadline(JsonNode snapshot) {
    long accepted = Instant.parse(snapshot.path("records").get(0).path("accepted_at").asString())
        .toEpochMilli();
    return Math.min(accepted + 125000, expiry(snapshot) + 5000);
  }

  static void verifyPurged(JsonNode observation, long deadline) {
    require(observation.path("records").isArray() && observation.path("records").isEmpty()
            && observation.path("observed_at_ms").isIntegralNumber()
            && observation.path("observed_at_ms").asLong() <= deadline,
        "Processing record remains beyond the declared maximum physical retention");
  }

  static void verifyAccepted(
      JsonNode snapshot, String id, String marker, long started, long ended) {
    require(
        snapshot.path("observed_at_ms").isIntegralNumber()
            && Math.abs(snapshot.path("observed_at_ms").asLong() - ended) <= 5000,
        "Database clock is not correlated to the test");
    var rows = snapshot.path("records");
    require(rows.isArray() && rows.size() == 1, "Missing exact committed processing record");
    var row = rows.get(0);
    require(
        id.equals(row.path("notification_id").asString())
            && marker.equals(row.path("correlation").asString())
            && (row.path("status").isNull()
                || java.util.Set.of("accepted", "queued").contains(row.path("status").asString())),
        "Processing record differs from the accepted event");
    long accepted = Instant.parse(row.path("accepted_at").asString()).toEpochMilli();
    require(
        accepted >= started - 5000 && accepted <= ended + 5000, "Stale notification acceptance");
    long lifetime = expiry(snapshot) - accepted;
    require(
        lifetime >= 120000 && lifetime <= 122000,
        "Actual processing TTL does not match the declared two-minute profile");
    require(
        expiry(snapshot) > snapshot.path("observed_at_ms").asLong(),
        "Processing record is already expired");
  }

  private static long expiry(JsonNode snapshot) {
    var value = snapshot.path("records").get(0).path("expires_at");
    require(
        value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0,
        "No finite expiry on the actual processing record");
    return value.asLong();
  }

  /**
   * Closes the owned NS profile and retains it for retry if restoration fails.
   */
  @After(value = "@ns_retention_profile", order = 100)
  public void cleanup() {
    if (profile != null) {
      profile.close();
      profile = null;
    }
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
