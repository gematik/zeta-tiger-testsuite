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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import de.gematik.zeta.services.model.ZetaAslToggleResult;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaDisableAslRequest;
import de.gematik.zeta.services.model.ZetaEnableAslRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Unit tests for ZetaDeploymentConfigurationService.
 */
@SuppressWarnings("unused")
public class ZetaDeploymentConfigurationServiceTest {

  final String evilDeploymentVersion = "6.6.6";

  private static final ZetaDeploymentDetails details = new ZetaDeploymentDetails(
      "zeta-local",
      "pep-deployment",
      "pep-test-nginx-conf",
      new String[]{"data", "nginx.conf"},
      "pep-well-known",
      new String[]{"data", "oauth-protected-resource"}
  );

  private final ZetaDeploymentConfigurationService service = new ZetaDeploymentConfigurationService(60, 120);

  @Test
  @Ignore
  public void verifyRequirements() {
    final String namespace = "zeta-local";
    service.verifyRequirements(namespace);
    Assert.assertThrows(AssertionError.class, () -> service.verifyRequirements("this-namespace-very-probably-does-not-exist"));
  }

  @Test
  @Ignore
  public void testConfigQuery() {
    CommandResult result =
        service.executeKubectlCommand("-n", "zeta-local", "get", "cm", "pep-test-nginx-conf", "-o", "yaml");

    assertTrue(result.stdout().contains("app.kubernetes.io/component: pep"));
    assertTrue(result.stderr().isBlank());
  }

  @Test
  @Ignore
  public void testConfigNonExistent() {
    CommandResult result =
        service.executeKubectlCommand("-n", "zeta-local", "get", "cm", "this-resource-does-not-exist", "-o", "yaml");

    assertFalse(result.stderr().isBlank());
  }

  @Test
  @Ignore
  public void restartPod() throws TimeoutException, InterruptedException {
    // wait for pod readiness
    service.restartPod("zeta-local", "pep-deployment", true, 120);
  }

  @Test
  @Ignore
  public void toggleAsl() throws TimeoutException, InterruptedException, IOException {
    final String nginxAslConfig =
        """
                    location /ASL {
                      pep                   on;
                      asl                   on;
                      pep_require_aud       https://zeta-kind.local;
                      # pep_require_scope     "";
                      # pep_leeway           60; # s
                    }\
        """;

    final String nginxAslRegex = "location\\s+/ASL\\s+\\{[^}]*}";
    final String wellKnownAslRegex = "\"zeta_asl_use\"\\s*:\\s*\"[^\"]+\"\\s*";

    final ZetaEnableAslRequest enableAslRequest = new ZetaEnableAslRequest(nginxAslRegex,
        "location\\s+/.well-known/\\s+\\{[^}]*}",
        nginxAslConfig,
        wellKnownAslRegex,
        "\"zeta_asl_use\":\"required\"");

    final ZetaDisableAslRequest disableAslRequest = new ZetaDisableAslRequest(nginxAslRegex,
        wellKnownAslRegex,
        "\"zeta_asl_use\":\"not_supported\"");

    ZetaAslToggleResult enableResult = service.enableAsl(details, enableAslRequest);
    assertEquals(0, enableResult.pepNginxResult().commandResult().exitCode());
    assertEquals(0, enableResult.pepWellKnownResult().commandResult().exitCode());

    ZetaAslToggleResult disableResult = service.disableAsl(details, disableAslRequest);
    assertEquals(0, disableResult.pepNginxResult().commandResult().exitCode());
    assertEquals(0, disableResult.pepWellKnownResult().commandResult().exitCode());
  }

  @Test
  @Ignore
  public void togglePoppVerification() throws IOException, InterruptedException, TimeoutException {

    ZetaPoppTokenToggleRequest request = new ZetaPoppTokenToggleRequest(
        "pep_require_popp\\s+(on|off)\\s*;",
        "pep_require_popp      on;",
        "pep_require_popp      off;"
    );

    // modify existing key
    KubectlPatchCommandResult disableResult = service.disablePoppVerification(details, request, "/pep/");
    assertEquals(0, disableResult.commandResult().exitCode());

    KubectlPatchCommandResult enableResult = service.enablePoppVerification(details, request, "/pep/");
    assertEquals(0, enableResult.commandResult().exitCode());

    // modify non-existing key
    disableResult = service.disablePoppVerification(details, request, "/");
    assertEquals(0, disableResult.commandResult().exitCode());

    enableResult = service.enablePoppVerification(details, request, "/");
    assertEquals(0, enableResult.commandResult().exitCode());
  }

  @Test
  @Ignore
  public void configMapBackup() throws IOException {
    final String namespace = "zeta-local";
    final String cmName = "pep-test-nginx-conf";

    CommandResult backupResult = service.createConfigMapBackup(namespace, cmName);
    assertEquals(0, backupResult.exitCode());

    CommandResult restoreResult = service.restoreConfigMapBackup(namespace, cmName);
    assertEquals(0, restoreResult.exitCode());

    CommandResult deleteResult = service.deleteConfigMapBackup(namespace, cmName);
    assertEquals(0, deleteResult.exitCode());
  }

  /**
   * Verifies that restoring a missing backup returns a no-op result without surfacing stderr noise.
   *
   * @throws IOException if temporary file handling inside the restore flow fails unexpectedly
   */
  @Test
  public void restoreConfigMapBackupReturnsNoOpWhenBackupIsMissing() throws IOException {
    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)")
    );

    CommandResult restoreResult = fakeService.restoreConfigMapBackup("zeta-local", "pep-well-known");

    assertEquals(0, restoreResult.exitCode());
    assertEquals("Not found", restoreResult.stdout());
    assertTrue(restoreResult.stderr().isBlank());
    assertEquals(1, fakeService.commands.size());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "configmap",
        "pep-well-known-tiger-original-backup", "-o", "yaml"), fakeService.commands.getFirst());
    assertFalse(fakeService.logStderrFlags.getFirst());
  }

  /**
   * Verifies that backup creation is skipped when a backup ConfigMap already exists.
   *
   * @throws IOException if temporary file handling inside the backup flow fails unexpectedly
   */
  @Test
  public void createConfigMapBackupSkipsCreationWhenBackupAlreadyExists() throws IOException {
    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, "apiVersion: v1", "")
    );

    CommandResult backupResult = fakeService.createConfigMapBackup("zeta-local", "pep-test-nginx-conf");

    assertEquals(0, backupResult.exitCode());
    assertEquals("Unchanged", backupResult.stdout());
    assertTrue(backupResult.stderr().isBlank());
    assertEquals(1, fakeService.commands.size());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "configmap",
        "pep-test-nginx-conf-tiger-original-backup", "-o", "yaml"), fakeService.commands.getFirst());
    assertFalse(fakeService.logStderrFlags.getFirst());
  }

  @Test
  public void getContainerImageReferenceForDeploymentReturnsFullImage() {
    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, "NAME READY STATUS\npep-deployment-abc 1/1 Running", ""),
        new CommandResult(List.of("kubectl"), 0,
            "registry.tas-devtools-gitlab.spree.de:443/zeta/zeta-guard/ngx_pep:0.3.0", "")
    );

    CommandResult imageResult = fakeService.getContainerImageReferenceForDeployment("zeta-local", "pep-deployment", "nginx");

    assertEquals(0, imageResult.exitCode());
    assertEquals("registry.tas-devtools-gitlab.spree.de:443/zeta/zeta-guard/ngx_pep:0.3.0", imageResult.stdout());
    assertTrue(imageResult.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods"), fakeService.commands.getFirst());
    assertEquals(Arrays.asList("get", "pod", "pep-deployment-abc", "-n", "zeta-local",
        "-o", "jsonpath='{.spec.containers[?(@.name==\"nginx\")].image}'"), fakeService.commands.get(1));
  }

  @Test
  public void getSingleReadyPodNameForDeploymentReturnsMatchingReadyPod() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "status": { "containerStatuses": [ { "ready": false } ] }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "status": { "containerStatuses": [ { "ready": true } ] }
            }
          ]
        }
        """;
    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, "")
    );

    String podName = fakeService.getSingleReadyPodNameForDeployment("zeta-local", "pep-deployment");

    assertEquals("pep-deployment-new", podName);
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(1, fakeService.commands.size());
  }

  @Test
  public void verifyPodImagePullOccurredReturnsSuccessWhenMatchingEventExists() {
    String image = "registry.example.org/zeta/ngx_pep:1.2.3";
    String eventsJson = """
        {
          "items": [
            {
              "reason": "Scheduled",
              "message": "Successfully assigned namespace/pep-deployment-new to node-a"
            },
            {
              "reason": "Pulling",
              "message": "Pulling image \\"%s\\""
            },
            {
              "reason": "Pulled",
              "message": "Successfully pulled image \\"%s\\" in 5.432s"
            }
          ]
        }
        """.formatted(image, image);
    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, image, ""),
        new CommandResult(List.of("kubectl"), 0, eventsJson, "")
    );

    CommandResult result = fakeService.verifyPodImagePullOccurred("zeta-local", "pep-deployment-new", "nginx");

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Pulling image"));
    assertTrue(result.stdout().contains("Successfully pulled image"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("get", "pod", "pep-deployment-new", "-n", "zeta-local",
        "-o", "jsonpath='{.spec.containers[?(@.name==\"nginx\")].image}'"), fakeService.commands.getFirst());
    assertEquals(Arrays.asList("get", "events", "-n", "zeta-local",
        "--field-selector", "involvedObject.kind=Pod,involvedObject.name=pep-deployment-new",
        "-o", "json"), fakeService.commands.get(1));
  }

  @Test
  public void verifyPodImagePullOccurredFailsWhenNoMatchingEventExists() {
    String image = "registry.example.org/zeta/ngx_pep:1.2.3";
    String eventsJson = """
        {
          "items": [
            {
              "reason": "Pulling",
              "message": "Pulling image \\"registry.example.org/zeta/other:9.9.9\\""
            },
            {
              "reason": "Started",
              "message": "Started container nginx"
            }
          ]
        }
        """;
    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, image, ""),
        new CommandResult(List.of("kubectl"), 0, eventsJson, "")
    );

    CommandResult result = fakeService.verifyPodImagePullOccurred("zeta-local", "pep-deployment-new", "nginx");

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("No image pull event found"));
  }

  @Test
  public void verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSecondsSucceeds() {
    String failedImage = "registry.example.org/zeta/ngx_pep:6.6.6";
    String failedPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:00Z" } } } ]
              }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "%s" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ImagePullBackOff" } } } ]
              }
            }
          ]
        }
        """.formatted(failedImage);
    String recoveredPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:05Z" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, failedPodsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:1.2.3", ""),
        new CommandResult(List.of("kubectl"), 1, "", "deployment \"pep-deployment\" exceeded its progress deadline"),
        new CommandResult(List.of("kubectl"), 0, recoveredPodsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:1.2.3", ""),
        new CommandResult(List.of("kubectl"), 0, "deployment \"pep-deployment\" successfully rolled out", "")
    );

    CommandResult result = fakeService.verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        failedImage,
        "registry.example.org/zeta/ngx_pep:1.2.3",
        1
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Failed update remained inactive and automatic rollback verified"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(Arrays.asList("get", "deployment", "pep-deployment", "-n", "zeta-local",
        "-o", "jsonpath='{.spec.template.spec.containers[?(@.name==\"nginx\")].image}'"), fakeService.commands.get(1));
    assertEquals(Arrays.asList("rollout", "status", "deployment/pep-deployment", "-n", "zeta-local", "--timeout=2s"),
        fakeService.commands.get(2));
  }

  @Test
  public void verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSecondsFailsWhileFailedPodRemainsPending() {
    String failedImage = "registry.example.org/zeta/ngx_pep:6.6.6";
    String failedPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:00Z" } } } ]
              }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "%s" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ImagePullBackOff" } } } ]
              }
            }
          ]
        }
        """.formatted(failedImage);

    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, failedPodsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:1.2.3", ""),
        new CommandResult(List.of("kubectl"), 0, "deployment \"pep-deployment\" successfully rolled out", "")
    );

    CommandResult result = fakeService.verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        failedImage,
        "registry.example.org/zeta/ngx_pep:1.2.3",
        0
    );

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("pep-deployment"));
    assertTrue(result.stderr().contains("did not return automatically to stable image")
        || result.stderr().contains("No failed rollout evidence observed"));
  }

  @Test
  public void verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSecondsFailsWhenBadImageBecomesReady() {
    String failedImage = "registry.example.org/zeta/ngx_pep:6.6.6";
    String activeFailedPodsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "%s" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:03Z" } } } ]
              }
            }
          ]
        }
        """.formatted(failedImage);

    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, activeFailedPodsJson, "")
    );

    CommandResult result = fakeService.verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        failedImage,
        "registry.example.org/zeta/ngx_pep:1.2.3",
        1
    );

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("unexpectedly became active"));
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(1, fakeService.commands.size());
  }

  @Test
  public void cleanupFailedRolloutPodsScalesReplicaSetAndDeletesUnexpectedImagePod() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": {
                "name": "pep-deployment-799bb999bf-mnmmt",
                "ownerReferences": [ { "kind": "ReplicaSet", "name": "pep-deployment-799bb999bf" } ]
              },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-12T07:00:00Z" } } } ]
              }
            },
            {
              "metadata": {
                "name": "pep-deployment-5d9dc7b898-dr6d5",
                "ownerReferences": [ { "kind": "ReplicaSet", "name": "pep-deployment-5d9dc7b898" } ]
              },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:nonexistent-rollout-test-20260311" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ImagePullBackOff" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, ""),
        new CommandResult(List.of("kubectl"), 0, "deployment.apps/pep-deployment patched", ""),
        new CommandResult(List.of("kubectl"), 0, "replicaset.apps/pep-deployment-5d9dc7b898 scaled", ""),
        new CommandResult(List.of("kubectl"), 0, "pod \"pep-deployment-5d9dc7b898-dr6d5\" deleted", "")
    );

    CommandResult result = fakeService.cleanupFailedRolloutPods(
        "zeta-local",
        "pep-deployment",
        "nginx",
        "registry.example.org/zeta/ngx_pep:1.2.3"
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("pep-deployment-5d9dc7b898-dr6d5"));
    assertTrue(result.stdout().contains("Restored deployment 'pep-deployment' to image 'registry.example.org/zeta/ngx_pep:1.2.3'"));
    assertTrue(result.stdout().contains("Scaled ReplicaSet 'pep-deployment-5d9dc7b898' to 0"));
    assertTrue(result.stdout().contains("Deleted pod 'pep-deployment-5d9dc7b898-dr6d5'"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals("patch", fakeService.commands.get(1).getFirst());
    assertEquals("deployment", fakeService.commands.get(1).get(1));
    assertEquals("pep-deployment", fakeService.commands.get(1).get(2));
    assertEquals(Arrays.asList("scale", "rs", "pep-deployment-5d9dc7b898", "-n", "zeta-local", "--replicas=0"),
        fakeService.commands.get(2));
    assertEquals(Arrays.asList("-n", "zeta-local", "delete", "pod", "pep-deployment-5d9dc7b898-dr6d5",
        "--ignore-not-found=true"), fakeService.commands.get(3));
  }

  @Test
  public void cleanupFailedRolloutPodsReturnsNoOpWhenOnlyStableImagePodsRemain() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": {
                "name": "pep-deployment-799bb999bf-mnmmt",
                "ownerReferences": [ { "kind": "ReplicaSet", "name": "pep-deployment-799bb999bf" } ]
              },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-12T07:00:00Z" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, "")
    );

    CommandResult result = fakeService.cleanupFailedRolloutPods(
        "zeta-local",
        "pep-deployment",
        "nginx",
        "registry.example.org/zeta/ngx_pep:1.2.3"
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("No failed rollout pods found"));
    assertTrue(result.stderr().isBlank());
    assertEquals(1, fakeService.commands.size());
  }

  @Test
  public void verifyDeploymentShowsPodWithImageWithinSecondsSucceedsWhenExpectedImageAppears() {
    String podsJson = """
        {
          "items": [
            {
              "metadata": { "name": "pep-deployment-old" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.3" } ] },
              "status": {
                "phase": "Running",
                "containerStatuses": [ { "name": "nginx", "ready": true, "state": { "running": { "startedAt": "2026-03-11T08:00:00Z" } } } ]
              }
            },
            {
              "metadata": { "name": "pep-deployment-new" },
              "spec": { "containers": [ { "name": "nginx", "image": "registry.example.org/zeta/ngx_pep:1.2.4" } ] },
              "status": {
                "phase": "Pending",
                "containerStatuses": [ { "name": "nginx", "ready": false, "state": { "waiting": { "reason": "ContainerCreating" } } } ]
              }
            }
          ]
        }
        """;

    FakeZetaDeploymentConfigurationService fakeService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, podsJson, "")
    );

    CommandResult result = fakeService.verifyDeploymentShowsPodWithImageWithinSeconds(
        "zeta-local",
        "pep-deployment",
        "nginx",
        "registry.example.org/zeta/ngx_pep:1.2.4",
        1
    );

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Observed deployment 'pep-deployment' with image"));
    assertTrue(result.stderr().isBlank());
    assertEquals(Arrays.asList("-n", "zeta-local", "get", "pods", "-o", "json"), fakeService.commands.getFirst());
    assertEquals(1, fakeService.commands.size());
  }

  /**
   * Verifies that backup existence checks use the quiet lookup variant for both present and missing backups.
   */
  @Test
  public void hasConfigMapBackupUsesQuietLookupResult() {
    FakeZetaDeploymentConfigurationService presentBackupService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 0, "apiVersion: v1", "")
    );
    FakeZetaDeploymentConfigurationService missingBackupService = new FakeZetaDeploymentConfigurationService(
        new CommandResult(List.of("kubectl"), 1, "", "Error from server (NotFound)")
    );

    assertTrue(presentBackupService.hasConfigMapBackup("zeta-local", "pep-test-nginx-conf"));
    assertFalse(presentBackupService.logStderrFlags.getFirst());

    assertFalse(missingBackupService.hasConfigMapBackup("zeta-local", "pep-well-known"));
    assertFalse(missingBackupService.logStderrFlags.getFirst());
  }

  /**
   * Verifies deployment image update and rollback against a live cluster setup.
   *
   * @throws IOException if kubectl command execution requires temporary file handling that fails
   * @throws InterruptedException if waiting for deployment rollout is interrupted
   */
  @Ignore
  public void updateAndRollbackDeploymentImage() throws IOException, InterruptedException {
    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.namespace"));
    String deploymentName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.podName"));
    String containerName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.containerName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.containerName"));
    String versionDowngrade = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.image.versionDowngrade")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.image.versionDowngrade"));

    // get Image path of running Deployment
    CommandResult imagePathResult = service.getContainerImagePathForDeployment(namespace, deploymentName, containerName);
    assertEquals(0, imagePathResult.exitCode());

    String imagePath = imagePathResult.stdout() == null ? "" : imagePathResult.stdout().trim();
    assertFalse(imagePath.isBlank());

    // successful patch downgrade version
    String downgradeImage = imagePath + ":" + versionDowngrade;
    CommandResult setResult = service.setDeploymentContainerImage(namespace, deploymentName, containerName, downgradeImage);
    assertEquals(0, setResult.exitCode());

    // checking for positive pod deployment
    CommandResult verifyDowngradeResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, downgradeImage);
    assertEquals(0, verifyDowngradeResult.exitCode());

    Thread.sleep(30_000L);

    // successful rollback to last working deployment version
    CommandResult rollbackResult = service.rollbackDeployment(namespace, deploymentName);
    assertEquals(0, rollbackResult.exitCode());

    // positive check of deployed Image is equals standard image
    String versionUpdate = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.image.versionUpdate")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.image.versionUpdate"));
    String updatedImage = imagePath + ":" + versionUpdate;

    CommandResult verifyUpdatedResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, updatedImage);
    assertEquals(0, verifyUpdatedResult.exitCode());
  }

  /**
   * Verifies that a failing deployment image update reports the rollout error and can still be rolled back.
   *
   * @throws IOException if kubectl command execution requires temporary file handling that fails
   */
  @Test
  @Ignore
  public void failingUpdateAndVerifyDeploymentImage() throws IOException {
    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.namespace"));
    String deploymentName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.podName"));
    String containerName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.containerName")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.containerName"));

    // get Image path of running Deployment
    CommandResult imagePathResult = service.getContainerImagePathForDeployment(namespace, deploymentName, containerName);
    assertEquals(0, imagePathResult.exitCode());

    String imagePath = imagePathResult.stdout() == null ? "" : imagePathResult.stdout().trim();
    assertFalse(imagePath.isBlank());

    // successful patch evil version
    String evilImage = imagePath + ":" + evilDeploymentVersion;
    CommandResult setResult = service.setDeploymentContainerImage(namespace, deploymentName,
        containerName, evilImage);
    assertEquals(0, setResult.exitCode());

    // timeout while checking for positive pod deployment
    CommandResult verifyDowngradeResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, evilImage);
    assertNotEquals(0, verifyDowngradeResult.exitCode());
    assertNotNull(verifyDowngradeResult.stderr());
    assertFalse(verifyDowngradeResult.stderr().isBlank());

    // successful rollback to last working deployment version
    CommandResult rollbackResult = service.rollbackDeployment(namespace, deploymentName);
    assertEquals(0, rollbackResult.exitCode());

    // positive check of deployed Image is equals standard image
    String versionUpdate = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.image.versionUpdate")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.image.versionUpdate"));
    String updatedImage = imagePath + ":" + versionUpdate;

    CommandResult verifyUpdatedResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, updatedImage);
    assertEquals(0, verifyUpdatedResult.exitCode());
  }

  private static class FakeZetaDeploymentConfigurationService extends ZetaDeploymentConfigurationService {

    private final Queue<CommandResult> responses = new ArrayDeque<>();
    private final List<List<String>> commands = new ArrayList<>();
    private final List<Boolean> logStderrFlags = new ArrayList<>();

    /**
     * Creates a fake deployment configuration service with pre-seeded command results.
     *
     * @param responses command results to be returned in invocation order
     */
    FakeZetaDeploymentConfigurationService(CommandResult... responses) {
      super(60, 120);
      this.responses.addAll(List.of(responses));
    }

    /**
     * Records the requested kubectl arguments and returns the next configured fake response.
     *
     * @param arguments kubectl arguments
     * @param logStderr whether stderr logging would be enabled for the lookup
     * @return configured fake command result
     */
    @Override
    public CommandResult executeKubectlCommand(List<String> arguments, boolean logStderr) {
      return recordCommand(arguments, logStderr);
    }

    /**
     * Records kubectl commands issued through the boolean-first overload as well so tests never
     * fall through to a real cluster command.
     *
     * @param verbose whether stderr logging would be enabled for the lookup
     * @param arguments kubectl arguments
     * @return configured fake command result
     */
    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) {
      return recordCommand(arguments, verbose);
    }

    /**
     * Stores one intercepted command invocation and returns the next canned result.
     *
     * @param arguments kubectl arguments
     * @param logStderr whether stderr logging would be enabled for the lookup
     * @return configured fake command result
     */
    private CommandResult recordCommand(List<String> arguments, boolean logStderr) {
      commands.add(List.copyOf(arguments));
      logStderrFlags.add(logStderr);
      CommandResult next = responses.poll();
      if (next == null) {
        throw new AssertionError("No fake command result configured for arguments: " + arguments);
      }
      return next;
    }

    /**
     * Skips the live kubectl availability check for unit tests that operate on canned command results.
     *
     * @param namespace ignored test namespace
     */
    @Override
    public void verifyRequirements(String namespace) {
      // unit tests provide canned kubectl responses and do not require a real cluster toolchain
    }

    /**
     * Avoids real waiting during polling-based unit tests.
     */
    @Override
    protected void sleepForPodStatusCheckInterval() {
      // avoid waiting in polling-based unit tests
    }
  }
}
