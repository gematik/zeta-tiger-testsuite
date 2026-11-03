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

import de.gematik.zeta.services.DeploymentStateRecovery;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Regression tests for durable deployment cleanup with stateful Kubernetes command execution. */
class DeploymentStateRecoveryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Deleted fixtures remain absent when clearing the journal must be retried. */
  @Test
  void repeatsCleanupAfterFixturesWereDeleted() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    var manifest = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Service");
    manifest.putObject("metadata").put("name", "fixture");
    manifest.putObject("spec").putObject("selector").put("idle", "true");
    recovery.create(manifest);
    recovery.change("service/fixture", "/spec/selector", JSON.readTree("{\"tls\":\"true\"}"), "");
    cluster.failJournal = true;
    assertThrows(AssertionError.class, recovery::finish);
    assertFalse(cluster.resources.containsKey("service/fixture"));
    cluster.failJournal = false;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Owned fixtures are removed without retaining their Secret contents in the journal. */
  @Test
  void createsAndDeletesOwnedFixturesWithoutPersistingSecrets() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.create(secret("fixture"));
    var snapshot = cluster.journal().at("/data/snapshot").asString();
    assertFalse(snapshot.contains("private-key"));
    assertTrue(snapshot.contains("secret/fixture"));
    recovery.finish();
    assertFalse(cluster.resources.containsKey("secret/fixture"));
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  @Test
  void storesCommandDocumentsUnderTargetAndRemovesThemAfterUse() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.create(secret("fixture"));
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(3), "");
    recovery.finish();

    for (var prefix : List.of("zeta-owned-resource-", "zeta-routing-journal-", "zeta-deployment-recovery-")) {
      assertTrue(cluster.requestFiles.stream().anyMatch(file -> file.startsWith("target/" + prefix)), prefix);
    }
    for (var file : cluster.requestFiles) {
      assertTrue(file.startsWith("target/"), file);
      assertFalse(file.contains("\\"), file);
      assertFalse(Files.exists(Path.of(file)), file);
    }
  }

  /** A lost creation response still leaves enough durable ownership for cleanup. */
  @Test
  void recoversCreatedFixtureAfterLostResponse() {
    var cluster = new Cluster();
    cluster.lostCreateTarget = "secret/fixture";
    var recovery = new DeploymentStateRecovery(cluster, "test");
    assertThrows(AssertionError.class, () -> recovery.create(secret("fixture")));
    assertTrue(cluster.requestFiles.stream().noneMatch(file -> Files.exists(Path.of(file))));
    assertTrue(cluster.resources.containsKey("secret/fixture"));
    ((ObjectNode) cluster.journal().path("data")).put("pid", "2147483647");
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertFalse(cluster.resources.containsKey("secret/fixture"));
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Replaced fixtures are preserved while independently owned fixtures are still removed. */
  @Test
  void refusesDeletionOfReplacedFixtureAndRetainsRecovery() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.create(secret("first"));
    recovery.create(secret("second"));
    ((ObjectNode) cluster.resources.get("secret/second").path("metadata")).put("uid", "foreign");
    assertThrows(AssertionError.class, recovery::finish);
    assertTrue(cluster.resources.containsKey("secret/second"));
    assertFalse(cluster.resources.containsKey("secret/first"));
    assertTrue(cluster.journal().path("data").has("snapshot"));
  }

  /** Existing fixtures and resources outside the supported core namespace scope are rejected. */
  @Test
  void refusesExistingOrInvalidFixture() {
    var cluster = new Cluster();
    cluster.resources.put("secret/fixture", secret("fixture"));
    var recovery = new DeploymentStateRecovery(cluster, "test");
    assertThrows(AssertionError.class, () -> recovery.create(secret("fixture")));
    var invalid = secret("other").put("kind", "Namespace");
    assertThrows(AssertionError.class, () -> recovery.create(invalid));
    assertFalse(cluster.resources.containsKey("configmap/testsuite-deployment-state"));
  }

  /** A failed field restoration retains fixtures still referenced by the deployment. */
  @Test
  void keepsFixturesUntilRestorationSucceeds() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.create(secret("fixture"));
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(3), "");
    cluster.failedPatchTarget = "deployment/pep";
    assertThrows(AssertionError.class, recovery::finish);
    assertTrue(cluster.resources.containsKey("secret/fixture"));
    cluster.failedPatchTarget = null;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertFalse(cluster.resources.containsKey("secret/fixture"));
    assertEquals(2, cluster.resources.get("deployment/pep").at("/spec/replicas").asInt());
  }

  /**
   * Constructs a Secret containing a recognizable value for journal disclosure checks.
   *
   * @param name resource name
   * @return Secret manifest
   */
  private ObjectNode secret(String name) {
    var manifest = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Secret");
    manifest.putObject("metadata").put("name", name);
    manifest.putObject("stringData").put("tls.key", "private-key");
    return manifest;
  }

  /** Object transformations preserve and restore the original configuration. */
  @Test
  void restoresObjectMutationAndRejectsInvalidTransforms() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    final var original = cluster.resources.get("deployment/pep").path("spec").deepCopy();
    assertThrows(AssertionError.class, () -> recovery.changeObject("deployment/pep", "/missing", n -> n));
    assertThrows(AssertionError.class, () -> recovery.changeObject("deployment/pep", "/spec", n -> JSON.nullNode()));
    var changed = recovery.changeObject("deployment/pep", "/spec", n -> {
      ((ObjectNode) n).put("replicas", 3);
      return n;
    });
    assertEquals(0, changed.exitCode());
    assertEquals(3, cluster.resources.get("deployment/pep").at("/spec/replicas").asInt());
    assertEquals(2, original.path("replicas").asInt());
    recovery.finish();
    assertEquals(original, cluster.resources.get("deployment/pep").path("spec"));
  }

  /** API-server file mode defaults must allow repeated template changes and final restoration. */
  @Test
  void restoresDefaultedVolumesAfterRepeatedTemplateChanges() {
    var cluster = new Cluster();
    cluster.defaultVolumeModes = true;
    final var original = cluster.resources.get("deployment/pep").at("/spec/template").deepCopy();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeObject("deployment/pep", "/spec/template", this::addVolumeSources);
    assertEquals(420, cluster.resources.get("deployment/pep").at("/spec/template/spec/volumes/0/secret/defaultMode").asInt());
    assertEquals(420, cluster.resources.get("deployment/pep").at("/spec/template/spec/volumes/1/configMap/defaultMode").asInt());
    var saved = JSON.readTree(cluster.journal().at("/data/snapshot").asString());
    assertFalse(saved.at("/fields/0/expected/spec/volumes/0/secret").has("defaultMode"));
    recovery.changeObject("deployment/pep", "/spec/template", template -> {
      ((ObjectNode) template.at("/spec/volumes/0/secret")).put("secretName", "second-client");
      return template;
    });
    recovery.finish();
    assertEquals(original, cluster.resources.get("deployment/pep").at("/spec/template"));
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Recovery accepts the already persisted pre-defaulting backup after owner termination. */
  @Test
  void recoversAbandonedTemplateWithDefaultedVolumeModes() {
    var cluster = new Cluster();
    cluster.defaultVolumeModes = true;
    final var original = cluster.resources.get("deployment/pep").at("/spec/template").deepCopy();
    new DeploymentStateRecovery(cluster, "test")
        .changeObject("deployment/pep", "/spec/template", this::addVolumeSources);
    ((ObjectNode) cluster.journal().path("data")).put("pid", "2147483647");
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals(original, cluster.resources.get("deployment/pep").at("/spec/template"));
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** A restored original can itself receive API-server defaults during its restoration patch. */
  @Test
  void verifiesRestoredOriginalWithDefaultedVolumeModes() {
    var cluster = new Cluster();
    cluster.defaultVolumeModes = true;
    addVolumeSources(cluster.resources.get("deployment/pep").at("/spec/template"));
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeObject("deployment/pep", "/spec/template", template -> {
      ((ObjectNode) template.at("/spec/containers/0")).put("image", "image:temporary");
      return template;
    });
    recovery.finish();
    assertEquals("image:original", cluster.resources.get("deployment/pep").at("/spec/template/spec/containers/0/image").asString());
    assertEquals(420, cluster.resources.get("deployment/pep").at("/spec/template/spec/volumes/0/secret/defaultMode").asInt());
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Default handling must still reject real permission, certificate and image changes. */
  @Test
  void preservesExternalTemplateChangesAndExplicitModes() {
    for (var change : List.of("secretMode", "configMapMode", "secretName", "image")) {
      var cluster = new Cluster();
      cluster.defaultVolumeModes = true;
      var recovery = new DeploymentStateRecovery(cluster, "test");
      recovery.changeObject("deployment/pep", "/spec/template", this::addVolumeSources);
      var template = cluster.resources.get("deployment/pep").at("/spec/template");
      switch (change) {
        case "secretMode" -> ((ObjectNode) template.at("/spec/volumes/0/secret")).put("defaultMode", 256);
        case "configMapMode" -> ((ObjectNode) template.at("/spec/volumes/1/configMap")).put("defaultMode", 256);
        case "secretName" -> ((ObjectNode) template.at("/spec/volumes/0/secret")).put("secretName", "external-client");
        case "image" -> ((ObjectNode) template.at("/spec/containers/0")).put("image", "image:external");
        default -> throw new AssertionError(change);
      }
      var external = template.deepCopy();
      assertThrows(AssertionError.class, recovery::finish, change);
      assertEquals(external, cluster.resources.get("deployment/pep").at("/spec/template"), change);
      assertTrue(cluster.journal().path("data").has("snapshot"), change);
    }
  }

  /**
   * Adds the two volume source types whose file modes are defaulted by Kubernetes.
   *
   * @param template pod template
   * @return modified template
   */
  private JsonNode addVolumeSources(final JsonNode template) {
    var volumes = ((ObjectNode) template.path("spec")).putArray("volumes");
    volumes.addObject().put("name", "client").putObject("secret").put("secretName", "test-client");
    volumes.addObject().put("name", "auth").putObject("configMap").put("name", "test-auth");
    return template;
  }

  /** Repeated scaling restores the first observed replica count, never a hard-coded default. */
  @Test
  void restoresOriginalReplicaCountAfterMultipleChanges() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    cluster.scaleDeployment("test", "pep", 3);
    cluster.scaleDeployment("test", "pep", 4);
    recovery.finish();
    assertEquals(2, cluster.resources.get("deployment/pep").at("/spec/replicas").asInt());
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** A new JVM can recover the snapshot after the original owner disappears. */
  @Test
  void restoresAfterOwnerTermination() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(4), "");
    ((ObjectNode) cluster.journal().path("data")).put("pid", "2147483647");
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals(2, cluster.resources.get("deployment/pep").at("/spec/replicas").asInt());
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** A failed rollout keeps the backup and surfaces a failure until recovery succeeds. */
  @Test
  void retainsBackupUntilRolloutSucceeds() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("configmap/nginx", "/data/nginx.conf", JSON.valueToTree("temporary"), "pep");
    cluster.failRollout = true;
    assertThrows(AssertionError.class, recovery::finish);
    assertTrue(cluster.journal().path("data").has("snapshot"));
    cluster.failRollout = false;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals("original", cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asString());
    assertEquals(2, cluster.restarts);
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Rejects unrelated scaling while still restoring independently owned configuration. */
  @Test
  void preservesExternalChangesButAttemptsIndependentCleanup() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(4), "");
    recovery.change("configmap/nginx", "/data/nginx.conf", JSON.valueToTree("temporary"), "pep");
    ((ObjectNode) cluster.resources.get("deployment/pep").path("spec")).put("replicas", 7);
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(7, cluster.resources.get("deployment/pep").at("/spec/replicas").asInt());
    assertEquals("original", cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asString());
    assertTrue(cluster.journal().path("data").has("snapshot"));
  }

  /** A failed ConfigMap restore preserves evidence while other captured resources are restored. */
  @Test
  void failedRestoreKeepsBackupAndStillAttemptsOtherCapturedResources() {
    var cluster = new Cluster();
    cluster.resources.put("configmap/second", cluster.resources.get("configmap/nginx").deepCopy());
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("configmap/nginx", "/data/nginx.conf", JSON.valueToTree("first-change"), "pep");
    recovery.change("configmap/second", "/data/nginx.conf", JSON.valueToTree("second-change"), "pep");
    cluster.failedPatchTarget = "configmap/nginx";
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals("first-change", cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asString());
    assertEquals("original", cluster.resources.get("configmap/second").at("/data/nginx.conf").asString());
    assertEquals(1, cluster.restarts);
    assertTrue(cluster.journal().path("data").has("snapshot"));
    cluster.failedPatchTarget = null;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertEquals("original", cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asString());
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Failure to clear the journal remains visible after the restored configuration is reloaded. */
  @Test
  void failedBackupClearingFailsCleanupButStillReloadsRestoredConfig() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("configmap/nginx", "/data/nginx.conf", JSON.valueToTree("temporary"), "pep");
    cluster.failJournal = true;
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals("original", cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asString());
    assertEquals(1, cluster.restarts);
    assertTrue(cluster.journal().path("data").has("snapshot"));
    cluster.failJournal = false;
    new DeploymentStateRecovery(cluster, "test").recoverAbandoned();
    assertTrue(cluster.journal().path("data").isEmpty());
  }

  /** Backup write failure prevents the first mutation. */
  @Test
  void refusesMutationWithoutDurableBackup() {
    var cluster = new Cluster();
    cluster.failJournal = true;
    var recovery = new DeploymentStateRecovery(cluster, "test");
    assertThrows(AssertionError.class,
        () -> recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(4), ""));
    assertEquals(0, cluster.patches);
  }

  /** An unapplied second change still allows restoration from the previous temporary value. */
  @Test
  void handlesCrashBetweenUpdatedBackupAndPatch() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(3), "");
    cluster.failPatch = true;
    assertEquals(1, recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(4), "").exitCode());
    cluster.failPatch = false;
    recovery.finish();
    assertEquals(2, cluster.resources.get("deployment/pep").at("/spec/replicas").asInt());
  }

  /** Async image changes carry the original scenario context and restore the exact image. */
  @Test
  void restoresAsynchronousImageAndStrategyChanges() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    cluster.setDeploymentStrategy("test", "pep", "{\"type\":\"Recreate\"}");
    var result = CompletableFuture.supplyAsync(() -> DeploymentStateRecovery.withContext(recovery, () -> {
      try {
        return cluster.setDeploymentContainerImage("test", "pep", "nginx", "image:broken");
      } catch (IOException exception) {
        throw new AssertionError(exception);
      }
    })).join();
    assertEquals(0, result.exitCode());
    recovery.finish();
    assertEquals("image:original", cluster.resources.get("deployment/pep").at("/spec/template/spec/containers/0/image").asString());
    assertEquals("RollingUpdate", cluster.resources.get("deployment/pep").at("/spec/strategy/type").asString());
  }

  /** Refuses to reuse a snapshot for a deleted and recreated deployment. */
  @Test
  void rejectsRecreatedDeployment() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(4), "");
    ((ObjectNode) cluster.resources.get("deployment/pep").path("metadata")).put("uid", "replacement");
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals(4, cluster.resources.get("deployment/pep").at("/spec/replicas").asInt());
  }

  /** Another scenario cannot take over an active journal. */
  @Test
  void rejectsActiveOwner() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(4), "");
    assertThrows(AssertionError.class, () -> new DeploymentStateRecovery(cluster, "test").recoverAbandoned());
    recovery.finish();
  }

  /** A field absent before the test is removed instead of being restored as JSON null. */
  @Test
  void restoresAbsentFields() {
    var cluster = new Cluster();
    ((ObjectNode) cluster.resources.get("deployment/pep").path("spec")).remove("replicas");
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.change("deployment/pep", "/spec/replicas", JSON.valueToTree(4), "");
    recovery.finish();
    assertFalse(cluster.resources.get("deployment/pep").path("spec").has("replicas"));
  }

  /** Namespace mismatches fail before a deployment can be changed in either namespace. */
  @Test
  void rejectsWrongNamespace() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      assertThrows(AssertionError.class, () -> cluster.scaleDeployment("other", "pep", 4));
      assertEquals(0, cluster.patches);
    } finally {
      recovery.finish();
    }
  }

  /** Image restoration never follows a stale array index to another container. */
  @Test
  void rejectsChangedContainerIdentity() {
    var cluster = new Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.changeImage("deployment/pep", "nginx", "image:temporary");
    ((ObjectNode) cluster.resources.get("deployment/pep").at("/spec/template/spec/containers/0")).put("name", "other");
    assertThrows(AssertionError.class, recovery::finish);
    assertEquals("image:temporary", cluster.resources.get("deployment/pep").at("/spec/template/spec/containers/0/image").asString());
  }

  /** A text mutation and its recovery use the same observed original configuration. */
  @Test
  void transformsConfigurationWithoutReplacingUnrelatedKeys() {
    var cluster = new Cluster();
    ((ObjectNode) cluster.resources.get("configmap/nginx").path("data")).put("other.conf", "unchanged");
    var recovery = new DeploymentStateRecovery(cluster, "test");
    var result = recovery.changeText("configmap/nginx", "/data/nginx.conf", value -> value + "-changed", "pep");
    assertEquals("original", result.originalValue());
    recovery.finish();
    assertEquals("original", cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asString());
    assertEquals("unchanged", cluster.resources.get("configmap/nginx").at("/data/other.conf").asString());
  }

  /** A deployed-config verification failure still leaves the mutation journal available for cleanup. */
  @Test
  void restoresNginxAfterPreparationVerificationFails() throws Exception {
    var cluster = new Cluster();
    var original = "server { location /pep/ { pep_require_popp off; } }";
    ((ObjectNode) cluster.resources.get("configmap/nginx").path("data")).put("nginx.conf", original);
    cluster.nginxDumpOverride = original;
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      var details = new ZetaDeploymentDetails("test", "pep", "nginx", new String[]{"data", "nginx.conf"}, "unused", new String[]{"data", "unused"});
      var request = new ZetaPoppTokenToggleRequest("pep_require_popp\\s+(on|off)\\s*;", "pep_require_popp on;", "pep_require_popp off;");
      var error = assertThrows(AssertionError.class, () -> cluster.setPoppVerification(details, request, "/pep/", true));
      assertTrue(error.getMessage().contains("not effective"));
      assertTrue(cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asText().contains("pep_require_popp on;"));
      assertTrue(cluster.journal().path("data").has("snapshot"));
    } finally {
      recovery.finish();
    }
    assertEquals(original, cluster.resources.get("configmap/nginx").at("/data/nginx.conf").asText());
    assertTrue(DeploymentStateRecovery.current() == null);
  }

  /** Stateful fake supporting journal CAS, real field patches and rollout failures. */
  static class Cluster extends ZetaDeploymentModificationService {
    final Map<String, ObjectNode> resources = new HashMap<>();
    final List<String> requestFiles = new ArrayList<>();
    boolean defaultVolumeModes;
    boolean failRollout;
    boolean failJournal;
    boolean failPatch;
    String failedPatchTarget;
    int patches;
    int restarts;
    String nginxDumpOverride;
    String lostCreateTarget;

    /** Creates a two-replica deployment and a mounted nginx ConfigMap. */
    Cluster() {
      super(1, 1);
      resources.put("deployment/pep", (ObjectNode) JSON.readTree("""
          {"metadata":{"uid":"pep-uid","resourceVersion":"1"},"spec":{"replicas":2,
          "strategy":{"type":"RollingUpdate"},"template":{"spec":{"containers":[
          {"name":"nginx","image":"image:original"}]}}}}
          """));
      resources.put("configmap/nginx", (ObjectNode) JSON.readTree("""
          {"metadata":{"uid":"cm-uid","resourceVersion":"1"},"data":{"nginx.conf":"original"}}
          """));
    }

    /**
     * Returns the deployment journal.
     *
     * @return journal resource
     */
    ObjectNode journal() {
      return resources.get("configmap/testsuite-deployment-state");
    }

    /**
     * Executes Kubernetes requests against in-memory versioned objects.
     *
     * @param verbose ignored log flag
     * @param arguments command arguments
     * @return command result
     */
    @Override
    public CommandResult executeKubectlCommand(final boolean verbose, final List<String> arguments) {
      for (var option : List.of("-f", "--patch-file")) {
        if (arguments.contains(option)) {
          requestFiles.add(arguments.get(arguments.indexOf(option) + 1));
        }
      }
      try {
        if (arguments.contains("exec")) {
          var config = nginxDumpOverride == null ? resources.get("configmap/nginx").at("/data/nginx.conf").asText() : nginxDumpOverride;
          return result(arguments, 0, "# configuration file /etc/nginx/nginx.conf:\n" + config);
        }
        if (arguments.contains("get")) {
          var target = arguments.get(arguments.indexOf("get") + 1);
          if (target.equals("pods")) {
            return result(arguments, 0, """
                {"items":[{"metadata":{"name":"pep-ready"},"status":{"phase":"Running",
                  "containerStatuses":[{"name":"nginx","ready":true}]}}]}
                """);
          }
          if ("configmap".equals(target)) {
            target += "/" + arguments.get(arguments.indexOf("get") + 2);
          }
          var resource = resources.get(target);
          return result(arguments, 0, resource == null ? "" : resource.toString());
        }
        if (arguments.contains("create") || arguments.contains("replace")) {
          var next = (ObjectNode) JSON.readTree(Files.readString(Path.of(arguments.get(arguments.indexOf("-f") + 1))));
          var target = next.path("kind").asString().toLowerCase(java.util.Locale.ROOT)
              + "/" + next.path("metadata").path("name").asString();
          var previous = resources.get(target);
          if (failJournal || (arguments.contains("create") && previous != null)
              || (arguments.contains("replace") && (previous == null || !previous.path("metadata").path("resourceVersion")
                  .equals(next.path("metadata").path("resourceVersion"))))) {
            return result(arguments, 1, "journal conflict");
          }
          ((ObjectNode) next.path("metadata")).put("resourceVersion", Integer.toString(previous == null
              ? 1 : previous.path("metadata").path("resourceVersion").asInt() + 1));
          if (!next.path("metadata").has("uid")) {
            ((ObjectNode) next.path("metadata")).put("uid", target + "-uid");
          }
          resources.put(target, next);
          if (target.equals(lostCreateTarget)) {
            return result(arguments, 1, "lost creation response");
          }
          return result(arguments, 0, next.toString());
        }
        if (arguments.contains("delete") && arguments.contains("--raw")) {
          var path = arguments.get(arguments.indexOf("--raw") + 1).split("/");
          var plural = path[path.length - 2];
          var target = plural.substring(0, plural.length() - 1) + "/" + path[path.length - 1];
          var current = resources.get(target);
          var options = JSON.readTree(Files.readString(Path.of(arguments.get(arguments.indexOf("-f") + 1))));
          if (current == null || !options.at("/preconditions/uid").equals(current.at("/metadata/uid"))
              || !options.at("/preconditions/resourceVersion").equals(current.at("/metadata/resourceVersion"))) {
            return result(arguments, 1, "delete precondition failed");
          }
          resources.remove(target);
          return result(arguments, 0, "deleted");
        }
        if (arguments.contains("--for=delete")) {
          var target = arguments.get(arguments.indexOf("--for=delete") + 1);
          return result(arguments, resources.containsKey(target) ? 1 : 0, "deletion wait");
        }
        if (arguments.contains("patch")) {
          if (failPatch) {
            return result(arguments, 1, "patch failure");
          }
          var target = arguments.get(arguments.indexOf("patch") + 1);
          if (target.equals(failedPatchTarget)) {
            return result(arguments, 1, "target patch failure");
          }
          var next = resources.get(target).deepCopy();
          var patch = JSON.readTree(Files.readString(Path.of(arguments.get(arguments.indexOf("--patch-file") + 1))));
          for (var operation : patch) {
            var pointer = operation.path("path").asString();
            if ("test".equals(operation.path("op").asString())) {
              if (!next.at(pointer).equals(operation.path("value"))) {
                return result(arguments, 1, "resourceVersion conflict");
              }
            } else {
              var offset = pointer.lastIndexOf('/');
              var parent = (ObjectNode) next.at(pointer.substring(0, offset));
              var key = pointer.substring(offset + 1).replace("~1", "/").replace("~0", "~");
              if ("remove".equals(operation.path("op").asString())) {
                parent.remove(key);
              } else {
                parent.set(key, operation.path("value"));
              }
            }
          }
          ((ObjectNode) next.path("metadata")).put("resourceVersion",
              Integer.toString(next.path("metadata").path("resourceVersion").asInt() + 1));
          if (defaultVolumeModes && target.startsWith("deployment/")) {
            for (var volume : next.at("/spec/template/spec/volumes")) {
              for (var kind : List.of("secret", "configMap")) {
                var volumeSource = volume.path(kind);
                if (volumeSource.isObject() && !volumeSource.has("defaultMode")) {
                  ((ObjectNode) volumeSource).put("defaultMode", 420);
                }
              }
            }
          }
          resources.put(target, next);
          patches++;
          return result(arguments, 0, "patched");
        }
        if (arguments.contains("restart")) {
          restarts++;
          return result(arguments, 0, "restarted");
        }
        throw new AssertionError("Unexpected command: " + arguments);
      } catch (IOException exception) {
        throw new AssertionError(exception);
      }
    }

    /**
     * Simulates rollout completion or failure.
     *
     * @param namespace namespace
     * @param deploymentName deployment
     * @param timeoutSeconds timeout
     * @return simulated result
     */
    @Override
    public CommandResult waitForDeploymentRollout(final String namespace, final String deploymentName, final int timeoutSeconds) {
      return result(List.of("rollout"), failRollout ? 1 : 0, "rollout");
    }

    /**
     * Constructs a command result without exposing resource contents in errors.
     *
     * @param arguments command
     * @param code exit code
     * @param output output
     * @return result
     */
    private CommandResult result(final List<String> arguments, final int code, final String output) {
      return new CommandResult(arguments, code, code == 0 ? output : "", code == 0 ? "" : output);
    }
  }
}
