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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A disposable NS process from the running image; no mutation of the shared service deployment.
 */
public final class NotificationRetentionProfile implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final String deployment;
  private final String name = "cc-ns-retention-" + UUID.randomUUID();
  private JsonNode originalTemplate;
  private JsonNode originalPod;
  private JsonNode template;
  private JsonNode status;
  private String image;
  private String podUid;
  private boolean created;
  private String previousLogs = "";
  private final java.util.function.BiFunction<JsonNode, String, JsonNode> environment;

  NotificationRetentionProfile(
      ZetaDeploymentModificationService service,
      String namespace,
      String deployment,
      java.util.function.BiFunction<JsonNode, String, JsonNode> environment) {
    this.service = service;
    this.namespace = namespace;
    this.deployment = deployment;
    this.environment = environment;
  }

  /**
   * Starts one isolated replica on the same node, pinned to the independently observed image.
   */
  public void start() {
    var source = JSON.readTree(execute("get", "deployment", deployment, "-o", "json"));
    final var observedTemplate = source.at("/spec/template").deepCopy();
    var labels = source.at("/spec/selector/matchLabels");
    require(labels.isObject() && !labels.isEmpty(), "Missing NS selector");
    var selector =
        String.join(
            ",",
            labels.properties().stream()
                .map(e -> e.getKey() + "=" + e.getValue().asString())
                .toList());
    var pods =
        JSON.readTree(execute("get", "pods", "-l", selector, "-o", "json"))
            .path("items")
            .valueStream()
            .filter(p -> !p.path("metadata").has("deletionTimestamp"))
            .toList();
    require(pods.size() == 1, "Expected one original NS pod");
    originalPod = pods.getFirst();
    // Publish cleanup snapshots together, before the first mutation. Earlier failures are
    // read-only.
    originalTemplate = observedTemplate;
    var running = originalPod.at("/status/containerStatuses");
    require(
        running.size() == 1 && running.get(0).path("ready").asBoolean(),
        "Original NS must be ready");
    image = running.get(0).path("imageID").asString();
    require(
        image.matches("[^\\s]+@sha256:[a-f0-9]{64}"),
        "NS runtime did not expose a pullable immutable image");
    template = manifest(source, originalPod, name, image, environment);
    Path file = null;
    try {
      file = Files.createTempFile(Path.of("target"), "cc-ns-retention-", ".json");
      Files.writeString(file, template.toString());
      // UUID name and ownership metadata remain known even if create succeeds but the response is
      // lost.
      created = true;
      execute("create", "-f", file.toAbsolutePath().toString());
    } catch (IOException e) {
      throw new AssertionError("Could not prepare the isolated NS profile");
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          throw new AssertionError("Could not remove NS profile file");
        }
      }
    }
    execute("wait", "--for=condition=Ready", "pod/" + name, "--timeout=60s");
    var pod = readPod();
    podUid = pod.at("/metadata/uid").asString();
    status = pod.at("/status/containerStatuses/0").deepCopy();
    require(
        image.equals(status.path("imageID").asString()) && status.path("ready").asBoolean(),
        "NS clone image or readiness differs");
    logs();
  }

  static JsonNode manifest(
      JsonNode source,
      JsonNode running,
      String name,
      String image,
      java.util.function.BiFunction<JsonNode, String, JsonNode> environment) {
    var pod = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Pod");
    pod.putObject("metadata").put("name", name).putObject("labels").put("cc-ns-retention", name);
    var spec = (ObjectNode) source.at("/spec/template/spec").deepCopy();
    require(
        spec.path("containers").isArray() && spec.path("containers").size() == 1,
        "Expected one NS container");
    require(
        !spec.path("hostNetwork").asBoolean() && !spec.path("hostPID").asBoolean(),
        "NS clone may not use host namespaces");
    spec.put("restartPolicy", "Never").put("nodeName", running.at("/spec/nodeName").asString());
    var container = (ObjectNode) spec.path("containers").get(0);
    container.put("image", image).put("imagePullPolicy", "IfNotPresent");
    container.set("env", environment.apply(container.path("env"), name));
    pod.set("spec", spec);
    return pod;
  }

  /**
   * Checks the exact pod/configuration and complete retained access log.
   */
  public String logs() {
    var pod = readPod();
    require(
        status != null
            && podUid.equals(pod.at("/metadata/uid").asString())
            && status.equals(pod.at("/status/containerStatuses/0")),
        "Isolated NS restarted or changed");
    require(
        template.at("/spec/containers/0/env").equals(pod.at("/spec/containers/0/env")),
        "Retention environment changed");
    verifyOriginal();
    var log = execute("logs", name, "--tail=-1", "--limit-bytes=1048576");
    require(
        log.length() < 1048576 && log.startsWith(previousLogs), "NS observation log is incomplete");
    previousLogs = log;
    return log;
  }

  /**
   * Selector for routing only explicitly scoped traffic to this owned ready pod.
   */
  public JsonNode selector() {
    require(status != null, "Isolated NS is not ready");
    return template.at("/metadata/labels").deepCopy();
  }

  /**
   * Immutable identity used to verify service endpoint convergence.
   */
  public String podUid() {
    require(podUid != null, "Isolated NS is not ready");
    return podUid;
  }

  private void verifyOriginal() {
    if (originalTemplate != null) {
      var current = JSON.readTree(execute("get", "deployment", deployment, "-o", "json"));
      require(
          originalTemplate.equals(current.at("/spec/template")),
          "Shared NS deployment changed during observation");
      var running =
          JSON.readTree(
              execute("get", "pod", originalPod.at("/metadata/name").asString(), "-o", "json"));
      require(
          originalPod.at("/metadata/uid").equals(running.at("/metadata/uid"))
              && originalPod
                  .at("/status/containerStatuses")
                  .equals(running.at("/status/containerStatuses")),
          "Shared NS pod changed during observation");
    }
  }

  /**
   * Deletes only this owned disposable pod and verifies the shared deployment was never changed.
   */
  @Override
  public void close() {
    try {
      if (created) {
        var response = execute("get", "pod", name, "--ignore-not-found", "-o", "json");
        if (!response.isBlank()) {
          var pod = JSON.readTree(response);
          require(
              name.equals(pod.at("/metadata/labels/cc-ns-retention").asString())
                  && (podUid == null || podUid.equals(pod.at("/metadata/uid").asString())),
              "NS clone ownership changed");
          execute("delete", "pod", name, "--wait=true", "--timeout=30s");
        }
        created = false;
      }
    } finally {
      verifyOriginal();
    }
  }

  private JsonNode readPod() {
    return JSON.readTree(execute("get", "pod", name, "-o", "json"));
  }

  private String execute(String... arguments) {
    var command = new ArrayList<>(List.of("-n", namespace));
    command.addAll(List.of(arguments));
    var result = service.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "Isolated NS operation failed: " + arguments[0]);
    return result.stdout();
  }

  private static void require(boolean value, String message) {
    if (!value) {
      throw new AssertionError(message);
    }
  }
}
