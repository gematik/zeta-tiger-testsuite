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

/** Read-only clock observations bound to one unchanged, ready PEP container. */
public final class RawOtlpOperationClock {
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final String container;
  private final String podName;
  private final String podUid;
  private final JsonNode containerStatus;

  /**
   * Selects the sole ready PEP pod from its deployment's labels.
   *
   * @param service Kubernetes command service
   * @param namespace deployment namespace
   * @param deployment PEP deployment name
   * @param container PEP container name
   */
  public RawOtlpOperationClock(ZetaDeploymentModificationService service, String namespace,
      String deployment, String container) {
    this.service = service;
    this.namespace = namespace;
    this.container = container;
    var selector = query("get", "deployment", deployment, "-o", "json").at("/spec/selector");
    require(selector.path("matchLabels").isObject() && !selector.path("matchLabels").isEmpty()
        && selector.path("matchExpressions").isEmpty(), "Unsupported PEP selector");
    var labels = new ArrayList<String>();
    selector.path("matchLabels").properties()
        .forEach(entry -> labels.add(entry.getKey() + "=" + entry.getValue().asString()));
    var pods = query("get", "pods", "-l", String.join(",", labels), "-o", "json").path("items");
    require(pods.isArray() && pods.size() == 1, "Clock observation requires exactly one PEP pod");
    var pod = pods.get(0);
    podName = pod.path("metadata").path("name").asString();
    podUid = pod.path("metadata").path("uid").asString();
    require(!podName.isBlank() && !podUid.isBlank(), "Missing PEP pod identity");
    containerStatus = status(pod).deepCopy();
    verifyStable(pod);
  }

  /**
   * Reads Unix epoch nanoseconds directly from the bound container's clock.
   *
   * @return observed epoch nanoseconds
   */
  public long now() {
    verifyStable(query("get", "pod", podName, "-o", "json"));
    var value = execute("exec", podName, "-c", container, "--", "date", "+%s%N").strip();
    verifyStable(query("get", "pod", podName, "-o", "json"));
    require(value.matches("[1-9][0-9]*"),
        "PEP clock requires date support for Unix epoch nanoseconds");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new AssertionError("PEP clock observation exceeds the supported epoch range", error);
    }
  }

  private void verifyStable(JsonNode pod) {
    require(podUid.equals(pod.path("metadata").path("uid").asString())
        && !pod.path("metadata").hasNonNull("deletionTimestamp")
        && containerStatus.equals(status(pod)), "PEP container changed during clock observation");
  }

  private JsonNode status(JsonNode pod) {
    var matches = pod.path("status").path("containerStatuses").valueStream()
        .filter(value -> container.equals(value.path("name").asString())).toList();
    require(matches.size() == 1 && matches.getFirst().path("ready").asBoolean()
        && matches.getFirst().path("state").path("running").isObject()
        && !matches.getFirst().path("imageID").asString().isBlank(),
        "PEP clock container is not uniquely ready and running");
    return matches.getFirst();
  }

  private JsonNode query(String... arguments) {
    return new ObjectMapper().readTree(execute(arguments));
  }

  private String execute(String... arguments) {
    var command = new ArrayList<>(List.of("-n", namespace));
    command.addAll(List.of(arguments));
    var result = service.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "Cannot read the unchanged PEP runtime clock");
    return result.stdout();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
