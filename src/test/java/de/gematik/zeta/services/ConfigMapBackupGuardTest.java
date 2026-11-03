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

package de.gematik.zeta.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ConfigMapBackupGuardTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OWNER = "this-test-run";

  @Test
  void restoresAfterEitherSideOfAnInterruptedDataPatch() {
    var f = new Fixture();
    var changed = JSON.createObjectNode().put("nginx.conf", "changed");
    f.arm(changed);
    assertTrue(ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER).isEmpty());
    f.current.set("data", changed);
    var patch = ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER);
    assertEquals(f.backup.path("data"), patch.get(3).path("value"));
    assertEquals(changed, patch.get(2).path("value"));
  }

  @Test
  void retainsThePreviousOwnedStateWhenASecondPatchFails() {
    var f = new Fixture();
    var first = JSON.createObjectNode().put("nginx.conf", "first change");
    f.arm(first);
    f.current.set("data", first);
    f.arm(JSON.createObjectNode().put("nginx.conf", "second change"));
    assertEquals(f.backup.path("data"),
        ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER).get(3).path("value"));
  }

  @Test
  void refusesForeignDataInsteadOfOverwritingOrAcceptingItAsOwnChange() {
    var f = new Fixture();
    f.arm(JSON.createObjectNode().put("nginx.conf", "own change"));
    f.current.set("data", JSON.createObjectNode().put("nginx.conf", "concurrent change"));
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER));
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.pendingPatch(f.backup, f.current,
            JSON.createObjectNode().put("nginx.conf", "another own change"), OWNER));
    assertEquals("concurrent change", f.current.at("/data/nginx.conf").asString());
  }

  @Test
  void refusesForeignOwnerReplacedTargetAndTamperedBackup() {
    var foreign = new Fixture();
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.restorePatch(foreign.backup, foreign.current, "other-run"));
    var replaced = new Fixture();
    ((ObjectNode) replaced.current.path("metadata")).put("uid", "replacement-uid");
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.restorePatch(replaced.backup, replaced.current, OWNER));
    var tampered = new Fixture();
    ((ObjectNode) tampered.backup.path("data")).put("nginx.conf", "tampered backup");
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.restorePatch(tampered.backup, tampered.current, OWNER));
  }

  @Test
  void metadataChangesArePreservedButCurrentResourceVersionIsCheckedAtomically() {
    var f = new Fixture();
    var changed = JSON.createObjectNode().put("nginx.conf", "own change");
    f.arm(changed);
    f.current.set("data", changed);
    var metadata = (ObjectNode) f.current.path("metadata");
    metadata.put("resourceVersion", "newer-version");
    metadata.putObject("labels").put("foreign-label", "preserve-me");
    var patch = ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER);
    assertEquals("source-uid", patch.get(0).path("value").asString());
    assertEquals("newer-version", patch.get(1).path("value").asString());
    assertEquals("test", patch.get(1).path("op").asString());
    assertEquals(List.of("/metadata/uid", "/metadata/resourceVersion", "/data", "/data"),
        patch.valueStream().map(op -> op.path("path").asString()).toList());
    assertEquals("preserve-me", f.current.at("/metadata/labels/foreign-label").asString());
  }

  @Test
  void deleteRequiresRestoredDataAndCarriesBothBackupIdentityPreconditions() {
    var f = new Fixture();
    var options = ConfigMapBackupGuard.deleteOptions(f.backup, f.current, OWNER);
    assertEquals("backup-uid", options.at("/preconditions/uid").asString());
    assertEquals("backup-version", options.at("/preconditions/resourceVersion").asString());
    f.current.set("data", JSON.createObjectNode().put("nginx.conf", "not yet restored"));
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.deleteOptions(f.backup, f.current, OWNER));
  }

  @Test
  void dataPropertyOrderDoesNotCreateAFalseConflictAndSnapshotIsIndependent() {
    var f = new Fixture();
    f.current.set("data", JSON.createObjectNode().put("b", "second").put("a", "first"));
    f.backup = ConfigMapBackupGuard.backup(f.current, OWNER);
    f.identifyBackup();
    f.current.set("data", JSON.createObjectNode().put("a", "first").put("b", "second"));
    assertTrue(ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER).isEmpty());
    ((ObjectNode) f.current.path("data")).put("a", "foreign");
    assertEquals("first", f.backup.at("/data/a").asString());
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER));
    assertFalse(f.backup.path("metadata").has("managedFields"));
  }

  @Test
  void missingBackupVersionOrTargetUidCannotProduceAnUnguardedOperation() {
    var f = new Fixture();
    ((ObjectNode) f.backup.path("metadata")).remove("resourceVersion");
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.restorePatch(f.backup, f.current, OWNER));
    ((ObjectNode) f.current.path("metadata")).remove("uid");
    assertThrows(IllegalStateException.class,
        () -> ConfigMapBackupGuard.dataPatch(f.current, JSON.createObjectNode()));
  }

  private static class Fixture {
    final ObjectNode current = JSON.createObjectNode();
    ObjectNode backup;

    Fixture() {
      current.put("apiVersion", "v1");
      current.put("kind", "ConfigMap");
      current.putObject("metadata").put("name", "nginx").put("namespace", "test-ns")
          .put("uid", "source-uid").put("resourceVersion", "source-version");
      current.putObject("data").put("nginx.conf", "original");
      backup = ConfigMapBackupGuard.backup(current, OWNER);
      identifyBackup();
    }

    void identifyBackup() {
      ((ObjectNode) backup.path("metadata")).put("uid", "backup-uid")
          .put("resourceVersion", "backup-version");
    }

    void arm(ObjectNode desired) {
      var patch = ConfigMapBackupGuard.pendingPatch(backup, current, desired, OWNER);
      for (int index : List.of(2, 3)) {
        var operation = patch.get(index);
        var key = operation.path("path").asString().substring("/metadata/annotations/".length())
            .replace("~1", "/").replace("~0", "~");
        ((ObjectNode) backup.path("metadata").path("annotations"))
            .put(key, operation.path("value").asString());
      }
    }
  }
}
