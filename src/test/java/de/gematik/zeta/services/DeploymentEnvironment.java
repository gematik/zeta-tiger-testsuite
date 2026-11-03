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

import de.gematik.zeta.services.model.CommandResult;
import java.util.HashSet;
import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reversible deployment configuration preserving unrelated environment entries and image identity. */
public final class DeploymentEnvironment {
  private static final ObjectMapper JSON = new ObjectMapper();

  private DeploymentEnvironment() {}

  /**
   * Sets literal environment values and waits for the configured container to become ready.
   *
   * @param service Kubernetes executor
   * @param namespace target namespace
   * @param deployment deployment name
   * @param container container name
   * @param values object mapping environment names to strings
   */
  public static void configure(ZetaDeploymentModificationService service, String namespace,
      String deployment, String container, JsonNode values) {
    if (!values.isObject() || values.isEmpty()) {
      throw new AssertionError("Environment updates must be a nonempty JSON object");
    }
    change(service, namespace, deployment, container, template -> {
      var configured = container(template, container);
      values.properties().forEach(entry -> {
        if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || !entry.getValue().isString()
            || entry.getValue().asString().contains("$(")) {
          throw new AssertionError("Environment updates require valid names and literal strings");
        }
        put(configured, entry.getKey(), entry.getValue().asString());
      });
      return template;
    });
  }

  /**
   * Changes one pod template under the current recovery session without changing its running image.
   *
   * @param service Kubernetes executor
   * @param namespace target namespace
   * @param deployment deployment name
   * @param container container whose image must remain unchanged
   * @param transform transformation of an independent template copy
   */
  static void change(ZetaDeploymentModificationService service, String namespace,
      String deployment, String container, UnaryOperator<JsonNode> transform) {
    var recovery = DeploymentStateRecovery.currentFor(namespace);
    if (recovery == null) {
      throw new AssertionError("Deployment configuration requires an active recovery session");
    }
    var digest = image(service, namespace, deployment, container);
    requireSuccess(recovery.changeTemplate(deployment, container, digest, transform));
    requireSuccess(service.waitForDeploymentRollout(namespace, deployment, service.getPodReadyTimeoutSeconds()));
    if (!digest.equals(image(service, namespace, deployment, container))) {
      throw new AssertionError("Product image changed during deployment configuration: " + deployment);
    }
  }

  /**
   * Finds exactly one container in a pod template.
   *
   * @param template pod template
   * @param name container name
   * @return mutable container
   */
  static ObjectNode container(JsonNode template, String name) {
    var matches = template.at("/spec/containers").valueStream()
        .filter(candidate -> name.equals(candidate.path("name").asString())).toList();
    if (matches.size() != 1) {
      throw new AssertionError("Expected exactly one container named " + name);
    }
    return (ObjectNode) matches.getFirst();
  }

  /**
   * Replaces one environment entry while preserving all other literal and indirect entries.
   *
   * @param container mutable container
   * @param name environment name
   * @param value literal value
   */
  static void put(ObjectNode container, String name, String value) {
    var entries = environment(container);
    for (int index = 0; index < entries.size(); index++) {
      if (name.equals(entries.get(index).path("name").asString())) {
        entries.set(index, JSON.createObjectNode().put("name", name).put("value", value));
        return;
      }
    }
    entries.addObject().put("name", name).put("value", value);
  }

  /**
   * Adds a CSV value without removing or resolving an existing value.
   *
   * @param container mutable container
   * @param name environment name
   * @param value additional CSV item
   */
  static void append(ObjectNode container, String name, String value) {
    var entries = environment(container);
    var existing = entries.valueStream().filter(entry -> name.equals(entry.path("name").asString())).findFirst();
    String previous = "";
    if (existing.isPresent()) {
      var entry = existing.orElseThrow();
      if (entry.has("valueFrom") || !entry.path("value").isString()) {
        throw new AssertionError("Cannot append to indirectly configured environment entry: " + name);
      }
      previous = entry.path("value").asString();
    }
    if (!List.of(previous.split(",")).contains(value)) {
      put(container, name, previous.isBlank() ? value : previous + "," + value);
    }
  }

  /**
   * Validates and returns an explicit environment array.
   *
   * @param container mutable container
   * @return environment entries
   */
  private static ArrayNode environment(ObjectNode container) {
    if (!container.has("env")) {
      return container.putArray("env");
    }
    var entries = container.path("env");
    var names = new HashSet<String>();
    if (!entries.isArray()) {
      throw new AssertionError("Container environment must be an array");
    }
    for (var entry : entries) {
      if (entry.path("name").asString().isBlank() || !names.add(entry.path("name").asString())) {
        throw new AssertionError("Duplicate or missing environment name");
      }
    }
    return (ArrayNode) entries;
  }

  /**
   * Reads the immutable image identity from the single ready pod for the deployment.
   *
   * @param service Kubernetes executor
   * @param namespace target namespace
   * @param deployment deployment name
   * @param container container name
   * @return immutable image digest
   */
  static String image(ZetaDeploymentModificationService service, String namespace,
      String deployment, String container) {
    var pod = service.getSingleReadyPodNameForDeployment(namespace, deployment);
    var result = service.executeKubectlCommand(false, List.of("-n", namespace, "get", "pod", pod, "-o", "json"));
    requireSuccess(result);
    var matches = JSON.readTree(result.stdout()).at("/status/containerStatuses").valueStream()
        .filter(status -> container.equals(status.path("name").asString()) && status.path("ready").asBoolean()).toList();
    if (matches.size() != 1) {
      throw new AssertionError("Expected one ready container: " + container);
    }
    var image = matches.getFirst().path("imageID").asString();
    if (!image.matches(".*sha256:[a-f0-9]{64}")) {
      throw new AssertionError("Container does not expose an immutable image digest: " + container);
    }
    return image.substring(image.lastIndexOf("sha256:"));
  }

  /**
   * Requires a successful Kubernetes operation.
   *
   * @param result command result
   */
  private static void requireSuccess(CommandResult result) {
    if (result.exitCode() != 0) {
      throw new AssertionError("Deployment configuration failed: " + result.stderr());
    }
  }
}
