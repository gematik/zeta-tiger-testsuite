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

import de.gematik.zeta.services.NotificationReadOnlyStore;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import java.util.HashSet;
import java.util.Set;
import net.serenitybdd.core.Serenity;
import tools.jackson.databind.JsonNode;

/** Read-only comparison of notification storage index snapshots. */
public class NotificationIndexSteps {
  private Set<String> baseline;

  /** Captures storage IDs before the SDK creates its owned records. */
  @Gegebensei("der aktuelle NS-Indexbestand als Ausgangszustand")
  public void captureBaseline() {
    baseline = identifiers(observe());
  }

  /**
   * Requires new storage index entries without the supplied cleartext identity.
   *
   * @param identity independently configured KVNR
   */
  @Dann("besitzt der NS neue Indexeinträge ohne Klartext-KVNR {tigerResolvedString}")
  public void verifyIndexes(String identity) {
    require(baseline != null, "Missing baseline index snapshot");
    newIndexEntries(baseline, observe(), identity);
  }

  /** Verifies physical removal after reserved notification identities have been cleaned. */
  @After(value = "@notification_index_observation", order = 97)
  public void verifyCleanup() {
    if (baseline != null) {
      var remaining = new HashSet<>(identifiers(observe()));
      remaining.removeAll(baseline);
      require(remaining.isEmpty(),
          "New notification index entries remain after SDK cleanup; "
              + "incomplete cleanup or concurrent database writes: " + remaining);
    }
  }

  /**
   * Validates complete index metadata and isolates newly created storage identifiers.
   *
   * @param before baseline IDs
   * @param snapshot current storage and index metadata
   * @param identity independently configured KVNR
   * @return new storage IDs
   */
  static Set<String> newIndexEntries(Set<String> before, JsonNode snapshot, String identity) {
    require(identity.matches("[A-Z][0-9]{9}"), "Invalid index observation input");
    var indexes = snapshot.path("indexes");
    require(indexes.isArray() && !indexes.isEmpty(), "Missing storage index metadata");
    boolean primary = false;
    for (var index : indexes) {
      require(index.path("valid").isBoolean() && index.path("valid").asBoolean(),
          "Invalid or unready storage index");
      var columns = index.path("columns");
      require(columns.isArray() && !columns.isEmpty(), "Missing index columns");
      for (var column : columns) {
        require(column.isString() && Set.of("id", "expires_at").contains(column.asString()),
            "Unobserved index column or expression: " + column);
      }
      if (index.path("primary").asBoolean()) {
        require(columns.size() == 1 && "id".equals(columns.get(0).asString()),
            "Unexpected storage primary key");
        primary = true;
      }
    }
    require(primary, "Missing primary storage ID index");
    var ids = identifiers(snapshot);
    require(ids.stream().noneMatch(id -> id.contains(identity)), "Cleartext KVNR in storage index");
    require(ids.containsAll(before), "Storage rows disappeared during observation");
    var added = new HashSet<>(ids);
    added.removeAll(before);
    require(!added.isEmpty(), "No new storage index entries observed after SDK write");
    return Set.copyOf(added);
  }

  /**
   * Parses a complete, duplicate-free storage ID snapshot.
   *
   * @param snapshot database observation
   * @return immutable storage IDs
   */
  static Set<String> identifiers(JsonNode snapshot) {
    require(snapshot.path("ids").isArray(), "Missing storage identifiers");
    var ids = new HashSet<String>();
    for (var id : snapshot.path("ids")) {
      require(id.isString() && !id.asString().isBlank() && ids.add(id.asString()),
          "Invalid or duplicate storage ID");
    }
    return Set.copyOf(ids);
  }

  /**
   * Reads and attaches the raw database metadata to the current test evidence.
   *
   * @return current index snapshot
   */
  private static JsonNode observe() {
    var snapshot = NotificationReadOnlyStore.indexSnapshot();
    Serenity.recordReportData().withTitle("Notification storage index snapshot")
        .andContents(snapshot.toPrettyString());
    return snapshot;
  }

  /**
   * Requires a complete observation contract.
   *
   * @param condition required condition
   * @param message diagnostic text
   */
  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
