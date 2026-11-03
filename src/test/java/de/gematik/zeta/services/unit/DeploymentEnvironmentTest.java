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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.services.DeploymentEnvironment;
import de.gematik.zeta.services.DeploymentStateRecovery;
import de.gematik.zeta.services.NotificationTlsPeer;
import de.gematik.zeta.services.model.CommandResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Regression checks for reversible environment updates and immutable running images. */
class DeploymentEnvironmentTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Additional peer trust retains prior targets, credentials and mounts across repeated changes. */
  @Test
  void appendsPeerTrustWithoutReplacingExistingConfiguration() {
    var cluster = new EnvironmentCluster();
    cluster.containerName = "notification-service";
    var template = cluster.resources.get("deployment/pep").at("/spec/template");
    var container = (ObjectNode) template.at("/spec/containers/0");
    container.put("name", "notification-service");
    container.set("env", JSON.readTree("""
        [{"name":"PUSH_GATEWAY_ALLOWED_BASE_URLS","value":"https://original/push/v1/"},
         {"name":"PUSH_GATEWAY_TRUSTED_CA_PATHS","value":"/certs/original/ca.pem"},
         {"name":"PASSWORD","valueFrom":{"secretKeyRef":{"name":"db","key":"password"}}}]
        """));
    var original = template.deepCopy();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      NotificationTlsPeer.trust(cluster, "test", "pep", "ns-peer");
      DeploymentEnvironment.configure(cluster, "test", "pep", "notification-service",
          JSON.readTree("{\"PUSH_GATEWAY_RETRY_GIVE_UP_AFTER\":\"PT20S\"}"));
      var configured = cluster.resources.get("deployment/pep").at("/spec/template");
      var env = configured.at("/spec/containers/0/env");
      assertTrue(env.valueStream().anyMatch(entry -> entry.path("value").asString()
          .equals("https://original/push/v1/,https://ns-peer/push/v1/")));
      assertTrue(env.valueStream().anyMatch(entry -> entry.path("value").asString()
          .equals("/certs/original/ca.pem,/certs/cc-ns-peer/ca.crt")));
      assertTrue(env.valueStream().anyMatch(entry -> entry.has("valueFrom")));
      assertEquals("ns-peer-ca", configured.at("/spec/volumes/0/secret/secretName").asString());
      assertTrue(configured.at("/spec/containers/0/volumeMounts/0/readOnly").asBoolean());
    } finally {
      recovery.finish();
    }
    assertEquals(pinnedOriginal(original), cluster.resources.get("deployment/pep").at("/spec/template"));
  }

  /**
   * Literal updates retain indirect credentials and restore the original configuration and digest.
   *
   * @param originallyPinned whether the original image already contains a digest
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesUnrelatedEnvironmentAndRestoresOriginal(boolean originallyPinned) {
    var cluster = new EnvironmentCluster();
    var template = cluster.resources.get("deployment/pep").at("/spec/template");
    var container = (ObjectNode) template.at("/spec/containers/0");
    if (originallyPinned) {
      container.put("image", "image:original@sha256:" + cluster.runningDigest);
    }
    container.set("env", JSON.readTree("""
        [{"name":"PASSWORD","valueFrom":{"secretKeyRef":{"name":"db","key":"password"}}},
         {"name":"RETENTION","value":"PT1H"}]
        """));
    var original = template.deepCopy();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      DeploymentEnvironment.configure(cluster, "test", "pep", "nginx",
          JSON.readTree("{\"RETENTION\":\"PT24H\",\"HISTORY\":\"true\"}"));
      var updated = cluster.resources.get("deployment/pep").at("/spec/template/spec/containers/0/env");
      assertEquals(container.path("env").get(0), updated.get(0));
      assertEquals("PT24H", updated.get(1).path("value").asString());
      assertEquals("true", updated.get(2).path("value").asString());
    } finally {
      recovery.finish();
    }
    assertEquals(pinnedOriginal(original), cluster.resources.get("deployment/pep").at("/spec/template"));
  }

  /** A wrong runtime image fails both configuration and cleanup until digest recovery succeeds. */
  @Test
  void rejectsChangedRuntimeImage() {
    var cluster = new EnvironmentCluster();
    cluster.ignorePin = true;
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      var failure = assertThrows(AssertionError.class, () -> DeploymentEnvironment.configure(
          cluster, "test", "pep", "nginx", JSON.readTree("{\"RETENTION\":\"PT24H\"}")));
      assertTrue(failure.getMessage().contains("Product image changed"));
    } finally {
      assertThrows(AssertionError.class, recovery::finish);
    }
    assertTrue(cluster.journal().path("data").has("snapshot"));
    cluster.ignorePin = false;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals("a".repeat(64), cluster.runningDigest);
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Abandoned recovery restores the original configuration, pull policy and pinned image. */
  @Test
  void restoresOriginalImageAfterAbandonedRecovery() {
    var cluster = new EnvironmentCluster();
    ((ObjectNode) cluster.resources.get("deployment/pep").at("/spec/template/spec/containers/0"))
        .put("imagePullPolicy", "Always");
    final var original = cluster.resources.get("deployment/pep").at("/spec/template").deepCopy();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeTemplate("pep", "nginx", "sha256:" + "a".repeat(64), template -> {
      ((ObjectNode) template.at("/spec/containers/0")).putArray("env")
          .addObject().put("name", "RETENTION").put("value", "PT24H");
      return template;
    });
    cluster.waitForDeploymentRollout("test", "pep", 1);
    assertEquals("a".repeat(64), cluster.runningDigest);
    var saved = JSON.readTree(cluster.journal().at("/data/snapshot").asString());
    assertEquals("sha256:" + "a".repeat(64), saved.at("/fields/0/images/0/digest").asString());
    assertEquals("image:original", saved.at("/fields/0/original/spec/containers/0/image").asString());
    assertFalse(saved.at("/fields/0").has("restored"));
    ((ObjectNode) cluster.journal().path("data")).put("pid", "2147483647");
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals(pinnedOriginal(original), cluster.resources.get("deployment/pep").at("/spec/template"));
    assertEquals("a".repeat(64), cluster.runningDigest);
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /**
   * A moved tag cannot change the restored image during normal or abandoned recovery.
   *
   * @param abandoned whether recovery starts from an interrupted session
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void restoresCapturedDigestWhenOriginalTagHasMoved(boolean abandoned) {
    var cluster = new EnvironmentCluster();
    var template = cluster.resources.get("deployment/pep").at("/spec/template");
    ((ObjectNode) template.at("/spec/containers/0")).put("imagePullPolicy", "Always");
    final var original = template.deepCopy();
    cluster.tagDigest = "b".repeat(64);
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeTemplate("pep", "nginx", "sha256:" + "a".repeat(64), configured -> {
      ((ObjectNode) configured.at("/spec/containers/0")).putArray("env")
          .addObject().put("name", "RETENTION").put("value", "PT24H");
      return configured;
    });
    cluster.waitForDeploymentRollout("test", "pep", 1);
    assertEquals("a".repeat(64), cluster.runningDigest);
    if (abandoned) {
      ((ObjectNode) cluster.resources.get("deployment/pep").path("spec")).set("template", original.deepCopy());
      cluster.waitForDeploymentRollout("test", "pep", 1);
      assertEquals("b".repeat(64), cluster.runningDigest);
      ((ObjectNode) cluster.journal().path("data")).put("pid", "2147483647");
      new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    } else {
      recovery.finish();
    }
    assertEquals(pinnedOriginal(original), cluster.resources.get("deployment/pep").at("/spec/template"));
    assertEquals("a".repeat(64), cluster.runningDigest);
    assertEquals("b".repeat(64), cluster.tagDigest);
    assertTrue(cluster.journal().path("data").isEmpty());
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals(pinnedOriginal(original), cluster.resources.get("deployment/pep").at("/spec/template"));
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Legacy journals with a pinned recovery target retain the captured original image. */
  @Test
  void restoresOriginalImageFromLegacyPinnedRecoveryTarget() {
    var cluster = new EnvironmentCluster();
    var original = cluster.resources.get("deployment/pep").at("/spec/template").deepCopy();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeTemplate("pep", "nginx", "sha256:" + "a".repeat(64), template -> {
      ((ObjectNode) template.at("/spec/containers/0")).putArray("env")
          .addObject().put("name", "RETENTION").put("value", "PT24H");
      return template;
    });
    var saved = JSON.readTree(cluster.journal().at("/data/snapshot").asString());
    var pinned = original.deepCopy();
    ((ObjectNode) pinned.at("/spec/containers/0")).put("image", "image:original@sha256:" + "a".repeat(64));
    ((ObjectNode) saved.at("/fields/0")).set("restored", pinned);
    ((ObjectNode) cluster.resources.get("deployment/pep").path("spec")).set("template", pinned);
    ((ObjectNode) cluster.journal().path("data")).put("snapshot", saved.toString()).put("pid", "2147483647");
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals(pinnedOriginal(original), cluster.resources.get("deployment/pep").at("/spec/template"));
    assertEquals("a".repeat(64), cluster.runningDigest);
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** A cleanup-only image change is detected even after successful configuration verification. */
  @Test
  void verifiesActualImageAfterCleanup() {
    var cluster = new EnvironmentCluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    DeploymentEnvironment.configure(cluster, "test", "pep", "nginx", JSON.readTree("{\"RETENTION\":\"PT24H\"}"));
    cluster.ignorePin = true;
    assertThrows(AssertionError.class, recovery::finish);
    assertTrue(cluster.journal().path("data").has("snapshot"));
    cluster.ignorePin = false;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Recovery can resume after its pinned template was applied but journal completion failed. */
  @Test
  void resumesPinnedRecoveryAfterJournalFailure() {
    var cluster = new EnvironmentCluster();
    final var original = cluster.resources.get("deployment/pep").at("/spec/template").deepCopy();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeTemplate("pep", "nginx", "sha256:" + "a".repeat(64), template -> {
      ((ObjectNode) template.at("/spec/containers/0")).putArray("env")
          .addObject().put("name", "RETENTION").put("value", "PT24H");
      return template;
    });
    cluster.tagDigest = "b".repeat(64);
    cluster.failJournal = true;
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(pinnedOriginal(original), cluster.resources.get("deployment/pep").at("/spec/template"));
    assertTrue(cluster.journal().path("data").has("snapshot"));
    cluster.failJournal = false;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals("a".repeat(64), cluster.runningDigest);
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** External configuration changes remain protected during digest-pinned recovery. */
  @Test
  void preservesExternalConfigurationDuringPinnedRecovery() {
    var cluster = new EnvironmentCluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeTemplate("pep", "nginx", "sha256:" + "a".repeat(64), template -> template);
    var external = cluster.resources.get("deployment/pep").at("/spec/template");
    ((ObjectNode) external.at("/spec/containers/0")).putArray("env")
        .addObject().put("name", "FOREIGN").put("value", "change");
    external = external.deepCopy();
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(external, cluster.resources.get("deployment/pep").at("/spec/template"));
    assertTrue(cluster.journal().path("data").has("snapshot"));
  }

  /**
   * Copies the original configuration with the captured immutable image reference.
   *
   * @param original original pod template
   * @return expected restored template
   */
  private JsonNode pinnedOriginal(final JsonNode original) {
    var pinned = original.deepCopy();
    ((ObjectNode) pinned.at("/spec/containers/0")).put("image", "image:original@sha256:" + "a".repeat(64));
    return pinned;
  }

  /** Ambiguous environment entries and non-string updates fail before changing Kubernetes. */
  @Test
  void rejectsAmbiguousOrNonliteralInput() {
    for (var invalid : List.of("{\"RETENTION\":24}", "{\"INVALID-NAME\":\"x\"}", "{\"X\":\"$(OTHER)\"}")) {
      var cluster = new EnvironmentCluster();
      var recovery = new DeploymentStateRecovery(cluster, "test");
      recovery.attach();
      try {
        assertThrows(AssertionError.class, () -> DeploymentEnvironment.configure(
            cluster, "test", "pep", "nginx", JSON.readTree(invalid)));
        assertEquals(0, cluster.patches);
      } finally {
        recovery.finish();
      }
    }
    var cluster = new EnvironmentCluster();
    ((ObjectNode) cluster.resources.get("deployment/pep").at("/spec/template/spec/containers/0"))
        .set("env", JSON.readTree("[{\"name\":\"X\",\"value\":\"1\"},{\"name\":\"X\",\"value\":\"2\"}]"));
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      assertThrows(AssertionError.class, () -> DeploymentEnvironment.configure(cluster, "test", "pep", "nginx",
          JSON.readTree("{\"X\":\"3\"}")));
      assertEquals(0, cluster.patches);
    } finally {
      recovery.finish();
    }
  }

  /** Kubernetes emulator exposing a stable or deliberately changed runtime image digest. */
  private static class EnvironmentCluster extends DeploymentStateRecoveryTest.Cluster {
    private boolean ignorePin;
    private String runningDigest = "a".repeat(64);
    private String tagDigest = "a".repeat(64);
    private String containerName = "nginx";

    /**
     * Resolves the emulator's ready pod.
     *
     * @param namespace namespace
     * @param deploymentName deployment
     * @return ready pod name
     */
    @Override
    public String getSingleReadyPodNameForDeployment(String namespace, String deploymentName) {
      return "pep-ready";
    }

    /**
     * Resolves mutable tags to their configured digest and honors immutable references unless overridden.
     *
     * @param namespace namespace
     * @param deploymentName deployment
     * @param timeoutSeconds timeout
     * @return rollout result
     */
    @Override
    public CommandResult waitForDeploymentRollout(String namespace, String deploymentName, int timeoutSeconds) {
      var reference = resources.get("deployment/" + deploymentName).at("/spec/template/spec/containers/0/image").asString();
      if (ignorePin) {
        runningDigest = "b".repeat(64);
      } else if (reference.contains("@sha256:")) {
        runningDigest = reference.substring(reference.lastIndexOf("sha256:") + 7);
      } else {
        runningDigest = tagDigest;
      }
      return super.waitForDeploymentRollout(namespace, deploymentName, timeoutSeconds);
    }

    /**
     * Supplies runtime image identity while retaining the versioned resource emulator.
     *
     * @param verbose output flag
     * @param arguments command arguments
     * @return command result
     */
    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) {
      if (arguments.contains("get") && arguments.contains("pod")) {
        return new CommandResult(arguments, 0,
            "{\"status\":{\"containerStatuses\":[{\"name\":\"" + containerName + "\",\"ready\":true,\"imageID\":\"repo@sha256:"
                + runningDigest + "\"}]}}", "");
      }
      return super.executeKubectlCommand(verbose, arguments);
    }
  }
}
