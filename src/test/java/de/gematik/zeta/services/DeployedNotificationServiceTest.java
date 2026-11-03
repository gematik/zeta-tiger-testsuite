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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.zeta.services.model.CommandResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DeployedNotificationServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void observesTheRealControllerOwnedPodWithoutMutations() {
    var api = new ReadOnlyApi();
    var target = new DeployedNotificationService(api, "test", "ns");
    assertEquals("10.0.0.9", target.address());
    assertEquals("sha256:" + "a".repeat(64), target.imageDigest());
    assertDoesNotThrow(target::verify);
  }

  @Test
  void matchingLabelsCannotSubstituteAStandalonePodOrForeignReplicaSet() {
    var api = new ReadOnlyApi();
    ((ObjectNode) api.pod.path("metadata")).remove("ownerReferences");
    assertThrows(AssertionError.class, () -> new DeployedNotificationService(api, "test", "ns"));
    var foreign = new ReadOnlyApi();
    ((ObjectNode) foreign.replicaSet.at("/metadata/ownerReferences/0")).put("uid", "foreign");
    assertThrows(AssertionError.class,
        () -> new DeployedNotificationService(foreign, "test", "ns"));
  }

  @Test
  void rejectsReplacementRestartAndConfigurationChangesDuringObservation() {
    for (var field : List.of("uid", "restart", "spec", "deployment")) {
      var api = new ReadOnlyApi();
      var target = new DeployedNotificationService(api, "test", "ns");
      switch (field) {
        case "uid" -> ((ObjectNode) api.pod.path("metadata")).put("uid", "replacement");
        case "restart" -> ((ObjectNode) api.pod.at("/status/containerStatuses/0"))
            .put("restartCount", 1);
        case "spec" -> ((ObjectNode) api.pod.path("spec")).put("nodeName", "different");
        case "deployment" -> ((ObjectNode) api.deployment.path("spec")).put("replicas", 2);
        default -> throw new AssertionError(field);
      }
      assertThrows(AssertionError.class, target::verify, field);
    }
  }

  @Test
  void doesNotClaimHelmOrReplicaCoverageForUnmanagedOrMultiplePods() {
    var api = new ReadOnlyApi();
    ((ObjectNode) api.deployment.at("/metadata/labels")).put("app.kubernetes.io/managed-by", "test");
    assertThrows(AssertionError.class, () -> new DeployedNotificationService(api, "test", "ns"));
    var multiple = new ReadOnlyApi();
    multiple.multiple = true;
    assertThrows(AssertionError.class,
        () -> new DeployedNotificationService(multiple, "test", "ns"));
  }

  private static class ReadOnlyApi extends ZetaDeploymentModificationService {
    final ObjectNode deployment = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"ns","uid":"deployment-uid",
        "labels":{"app.kubernetes.io/managed-by":"Helm"},
        "annotations":{"meta.helm.sh/release-name":"product","meta.helm.sh/release-namespace":"test"}},
        "spec":{"selector":{"matchLabels":{"app":"ns"}},"replicas":1}}
        """);
    final ObjectNode replicaSet = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"ns-rs","uid":"rs-uid","ownerReferences":[
        {"kind":"Deployment","name":"ns","uid":"deployment-uid","controller":true}]},"spec":{}}
        """);
    final ObjectNode pod = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"ns-pod","uid":"pod-uid","ownerReferences":[
        {"kind":"ReplicaSet","name":"ns-rs","uid":"rs-uid","controller":true}]},
        "spec":{"containers":[{"name":"notification-service"}]},
        "status":{"phase":"Running","podIP":"10.0.0.9","conditions":[{"type":"Ready","status":"True"}],
        "containerStatuses":[{"name":"notification-service","ready":true,"restartCount":0,
        "imageID":"containerd://docker.io/library/import-2026-10-04@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}}
        """);
    boolean multiple;

    ReadOnlyApi() {
      super(5, 5);
    }

    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> args) {
      assertEquals(List.of("-n", "test", "get"), args.subList(0, 3),
          "NS access must remain read-only");
      String output = switch (args.get(3)) {
        case "deployment" -> deployment.toString();
        case "replicaset" -> replicaSet.toString();
        case "pod" -> pod.toString();
        case "pods" -> "{\"items\":[" + pod + (multiple ? "," + pod : "") + "]}";
        default -> throw new AssertionError("Unexpected NS command");
      };
      return new CommandResult(args, 0, output, "");
    }
  }
}
