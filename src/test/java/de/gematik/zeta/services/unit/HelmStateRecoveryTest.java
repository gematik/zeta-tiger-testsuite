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

package de.gematik.zeta.services.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.services.HelmStateRecovery;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.HelmDeploymentRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Tests revision ownership and crash recovery for global ASL deployment changes. */
class HelmStateRecoveryTest {

  private static final HelmDeploymentRequest REQUEST = new HelmDeploymentRequest(".", "test", List.of(), Map.of());

  /** Restores the captured installed revision rather than deploying local defaults. */
  @Test
  void rollsBackOwnedUpgradeToCapturedRevision() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    cluster.upgrade(recovery.begin());
    recovery.finish();
    assertEquals(1, cluster.rollbacks);
    assertEquals(7, cluster.revision);
    assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").isEmpty());
  }

  /** Does not roll back if preparation stopped before the Helm upgrade. */
  @Test
  void preservesUnmodifiedRelease() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    recovery.begin();
    recovery.finish();
    assertEquals(0, cluster.rollbacks);
  }

  /** Preserves an administrator's subsequent Helm upgrade. */
  @Test
  void refusesUnrelatedUpgrade() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    recovery.begin();
    cluster.upgrade("administrator-upgrade");
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(0, cluster.rollbacks);
    assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").has("snapshot"));
  }

  /** A new process restores the owned global modification after JVM termination. */
  @Test
  void recoversAbandonedGlobalModification() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    cluster.upgrade(recovery.begin());
    ((ObjectNode) cluster.resources.get("configmap/testsuite-global-helm-state").path("data")).put("pid", "2147483647");
    new HelmStateRecovery(cluster, REQUEST, "test").recoverAbandoned();
    assertEquals(1, cluster.rollbacks);
  }

  /** A failed Helm rollback retains the durable backup for a later retry. */
  @Test
  void retainsBackupOnFailedRollback() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    cluster.upgrade(recovery.begin());
    cluster.failRollback = true;
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(7, cluster.revision);
    cluster.failRollback = false;
    new HelmStateRecovery(cluster, REQUEST, "test").recoverAbandoned();
    assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").isEmpty());
  }

  /** Pending and failed upgrades retain their label even when Helm replaces the description. */
  @Test
  void recoversIncompleteUpgrades() {
    for (var state : List.of("pending-upgrade", "failed")) {
      var cluster = new HelmCluster();
      var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
      cluster.upgrade(recovery.begin());
      cluster.status = state;
      cluster.description = "pending-upgrade".equals(state) ? "Preparing upgrade" : "Upgrade failed: timeout";
      recovery.finish();
      assertEquals(1, cluster.rollbacks);
      assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").isEmpty());
    }
  }

  /** Each interrupted retry can create another revision without making recovery impossible. */
  @Test
  void retriesRepeatedInterruptedRollbacks() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    cluster.upgrade(recovery.begin());
    cluster.failRollback = true;
    cluster.failureStatus = "pending-rollback";
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(7, cluster.revision);
    var retry = new HelmStateRecovery(cluster, REQUEST, "test");
    assertThrows(AssertionError.class, retry::recoverAbandoned);
    assertEquals(8, cluster.revision);
    cluster.failRollback = false;
    new HelmStateRecovery(cluster, REQUEST, "test").recoverAbandoned();
    assertEquals(9, cluster.revision);
    assertEquals(3, cluster.rollbacks);
    assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").isEmpty());
  }

  /** A later upgrade may inherit the marker but must never be mistaken for a rollback. */
  @Test
  void refusesLaterUpgradeWithInheritedLabel() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    var marker = recovery.begin();
    cluster.upgrade(marker);
    cluster.upgrade(marker);
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(0, cluster.rollbacks);
    assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").has("snapshot"));
  }

  /** Helm's automatic rollback needs no second rollback if it already restored the release. */
  @Test
  void acceptsAutomaticRollback() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    cluster.upgrade(recovery.begin());
    cluster.revision++;
    cluster.description = "Rollback to 5";
    recovery.finish();
    assertEquals(0, cluster.rollbacks);
    assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").isEmpty());
  }

  /** Failed automatic rollbacks and lost successful responses are recoverable too. */
  @Test
  void recoversAutomaticRollbackStates() {
    for (var state : List.of("pending-rollback", "failed", "deployed")) {
      var cluster = new HelmCluster();
      var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
      cluster.upgrade(recovery.begin());
      cluster.failRollback = true;
      cluster.failureStatus = state;
      assertThrows(AssertionError.class, recovery::finish);
      // Simulate the chart's automatic rollback, before the recovery recorded an attempt.
      var data = (ObjectNode) cluster.resources.get("configmap/testsuite-global-helm-state").path("data");
      var snapshot = (ObjectNode) new ObjectMapper().readTree(data.path("snapshot").asText());
      snapshot.remove("rollbackRevision");
      data.put("snapshot", snapshot.toString());
      cluster.failRollback = false;
      new HelmStateRecovery(cluster, REQUEST, "test").recoverAbandoned();
      assertEquals("deployed".equals(state) ? 1 : 2, cluster.rollbacks);
      assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").isEmpty());
    }
  }

  /** A command failure before storing a revision leaves the upgrade eligible for retry. */
  @Test
  void retriesRollbackWithoutNewRevision() {
    var cluster = new HelmCluster();
    var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
    cluster.upgrade(recovery.begin());
    cluster.failBeforeCreate = true;
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(6, cluster.revision);
    cluster.failBeforeCreate = false;
    new HelmStateRecovery(cluster, REQUEST, "test").recoverAbandoned();
    assertEquals(7, cluster.revision);
  }

  /** A nominally successful rollback with different content must retain the backup. */
  @Test
  void refusesMismatchedRollbackContents() {
    for (var kind : List.of("manifest", "values")) {
      var cluster = new HelmCluster();
      var recovery = new HelmStateRecovery(cluster, REQUEST, "test");
      cluster.upgrade(recovery.begin());
      cluster.mismatchedKind = kind;
      assertThrows(AssertionError.class, recovery::finish);
      assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").has("snapshot"));
      assertThrows(AssertionError.class, () -> new HelmStateRecovery(cluster, REQUEST, "test").recoverAbandoned());
      assertEquals(1, cluster.rollbacks);
    }
  }

  /** The owned upgrade targets the captured release and passes arguments without literal shell quotes. */
  @Test
  void upgradeUsesCapturedReleaseNamespaceAndMarker() {
    var cluster = new HelmCluster();
    var request = new HelmDeploymentRequest(".", "test", List.of("asl=true"), Map.of(), "zeta-testenv-test", "custom.yaml");
    cluster.deployZetaGuardWithHelmOverrides(request, "zeta-testsuite-test", "actual-namespace");
    assertTrue(cluster.makeArgs.contains("stage=test"));
    assertFalse(cluster.makeArgs.stream().anyMatch(argument -> argument.startsWith("RELEASE=")));
    assertTrue(cluster.makeArgs.contains("NAMESPACE=actual-namespace"));
    assertTrue(cluster.makeArgs.contains("VALUES=custom.yaml"));
    assertTrue(cluster.makeArgs.contains("HELM_EXTRA_VALUES_PARAMS=--set asl=true --description zeta-testsuite-test"
        + " --labels zeta-testsuite-owner=zeta-testsuite-test"));
  }

  /** A mismatched release is rejected before even reading Helm history or creating a journal. */
  @Test
  void rejectsMismatchedReleaseBeforeCapturingRecoveryState() {
    for (var mismatch : Map.of("test", "other-release", "k3s", "zeta-testenv-k3s", "", "zeta-testenv-").entrySet()) {
      var cluster = new HelmCluster();
      var request = new HelmDeploymentRequest(".", mismatch.getKey(), List.of(), Map.of(), mismatch.getValue(), "custom.yaml");
      var recovery = new HelmStateRecovery(cluster, request, "test");

      var failure = assertThrows(IllegalArgumentException.class, recovery::begin);

      assertTrue(failure.getMessage().contains("does not match Make's release"));
      assertEquals(0, cluster.helmCalls);
      assertFalse(cluster.resources.containsKey("configmap/testsuite-global-helm-state"));
    }
  }

  /** Calling the owned deployment directly must not bypass the release check. */
  @Test
  void rejectsMismatchedReleaseBeforeExecutingMake() {
    var cluster = new HelmCluster();
    var request = new HelmDeploymentRequest(".", "test", List.of(), Map.of(), "other-release", "custom.yaml");

    assertThrows(IllegalArgumentException.class,
        () -> cluster.deployZetaGuardWithHelmOverrides(request, "zeta-testsuite-test", "test"));

    assertNull(cluster.makeArgs);
  }

  /** Default stages and k3s capture and restore the local release selected by Make. */
  @Test
  void acceptsMakeStageAliasesForRecoveryAndDeployment() {
    for (var stage : List.of("", "  ", "local", "k3s", " k3s ")) {
      var cluster = new HelmCluster();
      var request = new HelmDeploymentRequest(".", stage, List.of(), Map.of(), "zeta-testenv-local", "custom.yaml");
      var recovery = new HelmStateRecovery(cluster, request, "test");
      var marker = recovery.begin();
      var saved = new ObjectMapper().readTree(cluster.resources.get("configmap/testsuite-global-helm-state")
          .path("data").path("snapshot").asText());
      assertEquals("zeta-testenv-local", saved.path("release").asText());

      cluster.deployZetaGuardWithHelmOverrides(request, marker, "test");
      assertTrue(cluster.makeArgs.contains("stage=" + stage));
      cluster.upgrade(marker);
      recovery.finish();

      assertEquals(1, cluster.rollbacks);
      assertTrue(cluster.resources.get("configmap/testsuite-global-helm-state").path("data").isEmpty());
    }
  }

  /** Models an installed Helm revision and uniquely described test upgrades. */
  private static final class HelmCluster extends DeploymentStateRecoveryTest.Cluster {
    private int revision = 5;
    private int rollbacks;
    private int helmCalls;
    private String description = "initial";
    private String status = "deployed";
    private String failureStatus = "failed";
    private final Map<Integer, String> labels = new HashMap<>();
    private boolean failRollback;
    private boolean failBeforeCreate;
    private String mismatchedKind = "";
    private List<String> makeArgs;

    /**
     * Captures the exact argument vector given to make.
     *
     * @param workDir working directory
     * @param arguments make arguments
     * @param environment process environment
     * @return successful result
     */
    @Override
    public CommandResult executeMakeCommand(final String workDir, final List<String> arguments,
        final Map<String, String> environment) {
      makeArgs = List.copyOf(arguments);
      return new CommandResult(arguments, 0, "", "");
    }

    /**
     * Simulates a Helm upgrade.
     *
     * @param marker revision description
     */
    private void upgrade(final String marker) {
      revision++;
      description = marker;
      labels.put(revision, marker);
    }

    /**
     * Executes supported Helm commands without a real cluster.
     *
     * @param workDir unused directory
     * @param arguments Helm arguments
     * @param environment unused environment
     * @param timeoutSeconds unused timeout
     * @return simulated result
     */
    @Override
    public CommandResult executeHelmCommand(final String workDir, final List<String> arguments,
        final Map<String, String> environment, final int timeoutSeconds) {
      helmCalls++;
      if (arguments.contains("history")) {
        var history = new ObjectMapper().createArrayNode();
        history.addObject().put("revision", revision).put("status", status).put("description", description);
        return new CommandResult(arguments, 0, history.toString(), "");
      }
      if (arguments.contains("metadata")) {
        var storedRevision = Integer.parseInt(arguments.get(arguments.indexOf("--revision") + 1));
        var metadata = new ObjectMapper().createObjectNode();
        var storedLabels = metadata.putObject("labels");
        if (labels.containsKey(storedRevision)) {
          storedLabels.put(HelmStateRecovery.OWNERSHIP_LABEL, labels.get(storedRevision));
        }
        return new CommandResult(arguments, 0, metadata.toString(), "");
      }
      if (arguments.contains("rollback")) {
        assertEquals("5", arguments.get(2));
        rollbacks++;
        var saved = new ObjectMapper().readTree(resources.get("configmap/testsuite-global-helm-state")
            .path("data").path("snapshot").asText());
        assertEquals(revision + 1, saved.path("rollbackRevision").asInt());
        if (failBeforeCreate) {
          return new CommandResult(arguments, 1, "", "connection failed");
        }
        revision++;
        description = "Rollback to 5";
        status = "deployed";
        if (failRollback) {
          status = failureStatus;
          if ("failed".equals(status)) {
            description = "Release \"" + arguments.get(1) + "\" failed: timeout";
          }
          return new CommandResult(arguments, 1, "", "rollback failed");
        }
      }
      if (arguments.contains(mismatchedKind)
          && !"5".equals(arguments.get(arguments.indexOf("--revision") + 1))) {
        return new CommandResult(arguments, 0, "different state", "");
      }
      return new CommandResult(arguments, 0, "original state", "");
    }
  }
}
