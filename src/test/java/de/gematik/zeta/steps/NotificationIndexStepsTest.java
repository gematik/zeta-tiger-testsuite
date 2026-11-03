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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.NotificationReadOnlyStore;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.serenitybdd.core.Serenity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Tests index metadata completeness, identity correlation and non-vacuous storage observations. */
class NotificationIndexStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KVNR = "X110639491";
  private static final String FIRST = "a".repeat(64);
  private static final String SECOND = "b".repeat(64);

  @Test
  void acceptsNewIndexEntriesWithoutPrescribingTheirCountOrEncoding() {
    for (var ids : new String[][] {{"old", FIRST},
        {"old", FIRST, SECOND + "_owners"},
        {"old", "kvnr:pseudonym_pushers", SECOND + "_owners", "channel:pseudonym"}}) {
      var snapshot = snapshot(ids);
      var expected = new HashSet<>(List.of(ids));
      expected.remove("old");
      assertEquals(expected, NotificationIndexSteps.newIndexEntries(Set.of("old"), snapshot, KVNR));
    }
  }

  @Test
  void rejectsCleartextIdentityAlsoInAnExistingIndexEntry() {
    var snapshot = snapshot();
    ((ArrayNode) snapshot.path("ids")).add("kvnr:" + KVNR + "_pushers");
    assertThrows(AssertionError.class, () -> NotificationIndexSteps.newIndexEntries(
        Set.of("old", "kvnr:" + KVNR + "_pushers"), snapshot, KVNR));
  }

  @Test
  void rejectsMissingWritesAndDisappearedRecords() {
    for (var ids : new String[][] {{"old"}, {FIRST, SECOND}}) {
      assertThrows(AssertionError.class,
          () -> NotificationIndexSteps.newIndexEntries(Set.of("old"), snapshot(ids), KVNR));
    }
  }

  @Test
  void rejectsCleartextIdentityInANewIndexEntry() {
    var snapshot = snapshot("old", FIRST + "_owners", "kvnr:" + KVNR + "_pushers");
    assertThrows(AssertionError.class,
        () -> NotificationIndexSteps.newIndexEntries(Set.of("old"), snapshot, KVNR));
  }

  @Test
  void rejectsUnobservedIndexExpressionsIncludedColumnsAndInvalidIndexes() {
    for (var index : new String[] {
        "{\"primary\":false,\"valid\":true,\"columns\":[null]}",
        "{\"primary\":false,\"valid\":true,\"columns\":[\"id\",\"payload\"]}",
        "{\"primary\":false,\"valid\":false,\"columns\":[\"id\"]}",
        "{\"primary\":false,\"valid\":true,\"columns\":[]}"}) {
      var snapshot = snapshot();
      ((ArrayNode) snapshot.path("indexes")).add(JSON.readTree(index));
      assertThrows(AssertionError.class,
          () -> NotificationIndexSteps.newIndexEntries(Set.of("old"), snapshot, KVNR));
    }
  }

  @Test
  void rejectsMissingOrWrongPrimaryKey() {
    for (var indexes : new String[] {"null", "[]",
        "[{\"primary\":false,\"valid\":true,\"columns\":[\"id\"]}]",
        "[{\"primary\":true,\"valid\":true,\"columns\":[\"expires_at\"]}]"}) {
      var snapshot = snapshot();
      snapshot.set("indexes", JSON.readTree(indexes));
      assertThrows(AssertionError.class,
          () -> NotificationIndexSteps.newIndexEntries(Set.of("old"), snapshot, KVNR));
    }
  }

  @Test
  void rejectsIncompleteDuplicateAndMalformedIdentifierSnapshots() {
    for (var ids : new String[] {"null", "{}", "[null]", "[1]", "[\"\"]", "[\"a\",\"a\"]"}) {
      var snapshot = snapshot();
      snapshot.set("ids", JSON.readTree(ids));
      assertThrows(AssertionError.class, () -> NotificationIndexSteps.identifiers(snapshot));
    }
  }

  @Test
  void requiresBaselineAndValidIdentityBeforeComparisons() {
    var steps = new NotificationIndexSteps();
    assertThrows(AssertionError.class, () -> steps.verifyIndexes(KVNR));
    assertThrows(AssertionError.class,
        () -> NotificationIndexSteps.newIndexEntries(Set.of(), snapshot(), "unsafe"));
  }

  @Test
  void cleanupReadsStorageAfterAFailedCleartextAssertion() {
    try (var store = mockStatic(NotificationReadOnlyStore.class);
        var serenity = mockStatic(Serenity.class, RETURNS_DEEP_STUBS)) {
      store.when(NotificationReadOnlyStore::indexSnapshot).thenReturn(snapshot("old"),
          snapshot("old", "kvnr:" + KVNR + "_pushers"), snapshot("old"));
      var steps = new NotificationIndexSteps();
      steps.captureBaseline();
      assertThrows(AssertionError.class, () -> steps.verifyIndexes(KVNR));
      assertDoesNotThrow(steps::verifyCleanup);
      store.verify(NotificationReadOnlyStore::indexSnapshot, times(3));
    }
  }

  @Test
  void cleanupRejectsRemainingEntriesAfterAFailedAssertionOrBeforeTheFirstCheck() {
    for (var check : List.of(false, true)) {
      try (var store = mockStatic(NotificationReadOnlyStore.class);
          var serenity = mockStatic(Serenity.class, RETURNS_DEEP_STUBS)) {
        store.when(NotificationReadOnlyStore::indexSnapshot).thenReturn(snapshot("old"),
            snapshot("old", "kvnr:" + KVNR + "_pushers"));
        var steps = new NotificationIndexSteps();
        steps.captureBaseline();
        if (check) {
          assertThrows(AssertionError.class, () -> steps.verifyIndexes(KVNR));
        }
        assertThrows(AssertionError.class, steps::verifyCleanup);
      }
    }
  }

  @Test
  void rechecksChannelWritesAndDetectsAdditionalUnremovedEntries() {
    try (var store = mockStatic(NotificationReadOnlyStore.class);
        var serenity = mockStatic(Serenity.class, RETURNS_DEEP_STUBS)) {
      var channelKey = "kvnr:" + KVNR + "_channels";
      store.when(NotificationReadOnlyStore::indexSnapshot).thenReturn(snapshot("old"),
          snapshot("old", FIRST), snapshot("old", FIRST, channelKey), snapshot("old", channelKey));
      var steps = new NotificationIndexSteps();
      steps.captureBaseline();
      steps.verifyIndexes(KVNR);
      assertThrows(AssertionError.class, () -> steps.verifyIndexes(KVNR));
      assertThrows(AssertionError.class, steps::verifyCleanup);
    }
  }

  @Test
  void skipsDatabaseCleanupObservationOnlyWithoutACapturedBaseline() {
    try (var store = mockStatic(NotificationReadOnlyStore.class)) {
      new NotificationIndexSteps().verifyCleanup();
      store.verifyNoInteractions();
    }
  }

  @Test
  void databaseObservationFailsClosedForCommandErrorsAndAmbiguousResults() {
    for (var result : List.of(
        new CommandResult(List.of(), 1, "{}", "denied"),
        new CommandResult(List.of(), 0, "", ""),
        new CommandResult(List.of(), 0, "{}\n{}", ""))) {
      try (var deployment = mockStatic(ZetaDeploymentConfiguration.class);
          var config = mockStatic(TigerGlobalConfiguration.class)) {
        var service = mock(ZetaDeploymentModificationService.class);
        deployment.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
        deployment.when(ZetaDeploymentConfiguration::getNamespace).thenReturn("test");
        config.when(() -> TigerGlobalConfiguration.readStringOptional(any(String.class)))
            .thenReturn(Optional.of("fixture"));
        config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("fixture"))
            .thenReturn("fixture");
        when(service.executeKubectlCommand(eq(false), any(String[].class)))
            .thenReturn(new CommandResult(List.of(), 0, "pod/primary", ""), result);
        assertThrows(AssertionError.class, NotificationReadOnlyStore::indexSnapshot);
      }
    }
  }

  @Test
  void databaseObservationRejectsMissingOrMultiplePrimaryPods() {
    for (var pods : new String[] {"", "pod/one\npod/two"}) {
      try (var deployment = mockStatic(ZetaDeploymentConfiguration.class);
          var config = mockStatic(TigerGlobalConfiguration.class)) {
        var service = mock(ZetaDeploymentModificationService.class);
        deployment.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
        deployment.when(ZetaDeploymentConfiguration::getNamespace).thenReturn("test");
        config.when(() -> TigerGlobalConfiguration.readStringOptional(any(String.class)))
            .thenReturn(Optional.of("fixture"));
        config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("fixture"))
            .thenReturn("fixture");
        when(service.executeKubectlCommand(eq(false), any(String[].class)))
            .thenReturn(new CommandResult(List.of(), 0, pods, ""));
        assertThrows(AssertionError.class, NotificationReadOnlyStore::indexSnapshot);
      }
    }
  }

  /** Supplies a primary ID index, an expiry index and two newly created opaque keys. */
  private static ObjectNode snapshot() {
    return snapshot("old", FIRST, SECOND);
  }

  /**
   * Supplies storage identifiers with complete metadata for the supported indexed columns.
   *
   * @param ids storage identifiers
   * @return database snapshot
   */
  private static ObjectNode snapshot(String... ids) {
    var snapshot = (ObjectNode) JSON.readTree("""
        {"indexes":[
          {"primary":true,"valid":true,"columns":["id"]},
          {"primary":false,"valid":true,"columns":["expires_at"]}]}
        """);
    var values = snapshot.putArray("ids");
    for (var id : ids) {
      values.add(id);
    }
    return snapshot;
  }
}
