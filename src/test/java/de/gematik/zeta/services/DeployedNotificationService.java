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

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read-only selection of a Helm deployment and its actual controller-owned NS pod. */
final class DeployedNotificationService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final String deploymentName;
  private final String observationStart = java.time.Instant.now().minusSeconds(2).toString();
  private final JsonNode deployment;
  private final JsonNode replicaSet;
  private final JsonNode pod;

  DeployedNotificationService(
      ZetaDeploymentModificationService service, String namespace, String deploymentName) {
    this.service = service;
    this.namespace = namespace;
    this.deploymentName = deploymentName;
    deployment = read("deployment", deploymentName);
    require(
        "Helm".equals(deployment.at("/metadata/labels/app.kubernetes.io~1managed-by").asString())
            && !deployment.at("/metadata/annotations/meta.helm.sh~1release-name").asString().isBlank()
            && namespace.equals(
                deployment.at("/metadata/annotations/meta.helm.sh~1release-namespace").asString()),
        "Notification service must be an installed Helm deployment: " + deploymentName);
    var labels = deployment.at("/spec/selector/matchLabels");
    require(labels.isObject() && !labels.isEmpty(), "Missing deployed NS selector");
    var selector = String.join(
        ",", labels.properties().stream()
            .map(e -> e.getKey() + "=" + e.getValue().asString()).toList());
    var pods = JSON.readTree(execute("get", "pods", "-l", selector, "-o", "json"))
        .path("items").valueStream()
        .filter(p -> !p.path("metadata").has("deletionTimestamp")).toList();
    require(pods.size() == 1,
        "TLS profile requires exactly one deployed NS pod; replica coverage is not implicit");
    pod = pods.getFirst();
    var controller = controller(pod, "ReplicaSet");
    replicaSet = read("replicaset", controller.path("name").asString());
    require(controller.path("uid").equals(replicaSet.at("/metadata/uid")),
        "NS pod ReplicaSet identity differs");
    var owner = controller(replicaSet, "Deployment");
    require(deploymentName.equals(owner.path("name").asString())
            && owner.path("uid").equals(deployment.at("/metadata/uid")),
        "NS pod is not owned by the installed deployment");
    require("Running".equals(pod.at("/status/phase").asString())
            && pod.at("/status/conditions").valueStream().anyMatch(
                c -> "Ready".equals(c.path("type").asString())
                    && "True".equals(c.path("status").asString()))
            && pod.at("/status/containerStatuses").valueStream().anyMatch(
                c -> "notification-service".equals(c.path("name").asString())
                    && c.path("ready").asBoolean()
                    && c.path("imageID").asString().matches("[^\\s]+@sha256:[a-f0-9]{64}")),
        "Deployed notification service is not Ready with an immutable image identity");
    require(address().matches("[0-9.]+"), "Unsupported deployed NS pod address");
  }

  private static JsonNode controller(JsonNode resource, String kind) {
    var refs = resource.at("/metadata/ownerReferences").valueStream()
        .filter(r -> r.path("controller").asBoolean() && kind.equals(r.path("kind").asString()))
        .toList();
    require(refs.size() == 1 && !refs.getFirst().path("uid").asString().isBlank(),
        "Missing actual NS controller: " + kind);
    return refs.getFirst();
  }

  JsonNode template() {
    return deployment.at("/spec/template").deepCopy();
  }

  String imageDigest() {
    String identity = pod.at("/status/containerStatuses").valueStream()
        .filter(c -> "notification-service".equals(c.path("name").asString()))
        .findFirst().orElseThrow().path("imageID").asString();
    return identity.substring(identity.lastIndexOf('@') + 1);
  }

  String address() {
    return pod.at("/status/podIP").asString();
  }

  String name() {
    return pod.at("/metadata/name").asString();
  }

  String environment(String key) {
    var values = pod.at("/spec/containers").valueStream()
        .filter(c -> "notification-service".equals(c.path("name").asString()))
        .flatMap(c -> c.path("env").valueStream())
        .filter(e -> key.equals(e.path("name").asString())).toList();
    require(values.size() == 1 && values.getFirst().path("value").isString(),
        "Expected explicit Helm NS environment: " + key);
    return values.getFirst().path("value").asString();
  }

  String logs() {
    verify();
    var log = execute("logs", name(), "-c", "notification-service",
        "--since-time=" + observationStart, "--limit-bytes=262144");
    require(log.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 262144,
        "Deployed NS evidence was truncated");
    return log;
  }

  void verify() {
    var now = read("deployment", deploymentName);
    require(deployment.at("/metadata/uid").equals(now.at("/metadata/uid"))
            && deployment.path("spec").equals(now.path("spec")),
        "Installed NS deployment changed during TLS observation");
    var rs = read("replicaset", replicaSet.at("/metadata/name").asString());
    require(replicaSet.at("/metadata/uid").equals(rs.at("/metadata/uid"))
            && replicaSet.path("spec").equals(rs.path("spec")),
        "Installed NS ReplicaSet changed during TLS observation");
    var live = read("pod", name());
    require(!live.path("metadata").has("deletionTimestamp")
            && pod.at("/metadata/uid").equals(live.at("/metadata/uid"))
            && pod.at("/metadata/ownerReferences").equals(live.at("/metadata/ownerReferences"))
            && pod.path("spec").equals(live.path("spec"))
            && pod.at("/status/containerStatuses").equals(live.at("/status/containerStatuses")),
        "Deployed NS process or Helm configuration changed during TLS observation");
  }

  private JsonNode read(String kind, String name) {
    return JSON.readTree(execute("get", kind, name, "-o", "json"));
  }

  private String execute(String... args) {
    var command = new ArrayList<>(List.of("-n", namespace));
    command.addAll(List.of(args));
    var result = service.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "Deployed NS observation failed: " + args[0]);
    return result.stdout();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
