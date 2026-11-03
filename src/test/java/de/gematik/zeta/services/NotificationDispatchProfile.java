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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Isolated, reversible NS queue profile with request-correlated server access logs.
 */
public final class NotificationDispatchProfile implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OWNER = "ZETA_TEST_BACKLOG_PROBE";
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final String deployment;
  private final Function<List<String>, String> runtime;
  private NotificationRetentionProfile isolated;
  private JsonNode originalService;
  private JsonNode ownSelector;
  private boolean routeClaimed;
  private String gatewayPod;
  private String gatewayNode;
  private String gatewayTask;
  private JsonNode gatewayStatus;

  /**
   * Creates an inactive fixture for the explicitly configured test NS deployment.
   */
  public NotificationDispatchProfile(
      ZetaDeploymentModificationService service, String namespace, String deployment) {
    this(service, namespace, deployment, NotificationDispatchProfile::runtimeCommand);
  }

  NotificationDispatchProfile(
      ZetaDeploymentModificationService service,
      String namespace,
      String deployment,
      Function<List<String>, String> runtime) {
    this.service = service;
    this.namespace = namespace;
    this.deployment = deployment;
    this.runtime = runtime;
  }

  /**
   * Starts a pinned disposable NS and routes the existing RS path only to that process.
   */
  public void start() {
    startWithEnvironment(NotificationDispatchProfile::probeEnvironment);
  }

  /**
   * Routes RS traffic to a pinned NS with a two-minute retention policy.
   */
  public void startRetention() {
    startWithEnvironment(NotificationDispatchProfile::retentionEnvironment);
  }

  private void startWithEnvironment(BiFunction<JsonNode, String, JsonNode> environment) {
    require(isolated == null, "NS dispatch profile already started");
    var source = readDeployment();
    require(source.at("/spec/replicas").asInt() == 1, "NS dispatch profile requires one replica");
    originalService = JSON.readTree(execute("get", "service", deployment, "-o", "json"));
    var selector = originalService.at("/spec/selector");
    require(
        selector.isObject()
            && !selector.isEmpty()
            && selector.properties().stream()
                .allMatch(
                    e ->
                        e.getValue()
                            .equals(source.at("/spec/selector/matchLabels").path(e.getKey()))),
        "RS service does not exclusively select the original NS deployment");
    isolated =
        new NotificationRetentionProfile(
            service, namespace, deployment, environment);
    isolated.start();
    ownSelector = isolated.selector();
    routeClaimed = true; // Restore even if the patch succeeds but its acknowledgement is lost.
    replaceSelector(selector, ownSelector);
    awaitEndpoint();
    logs();
  }

  /**
   * Checks the exact routed NS process, original deployment and retained access-log history.
   */
  public String logs() {
    require(isolated != null && routeClaimed, "NS dispatch profile is not ready");
    var current = JSON.readTree(execute("get", "service", deployment, "-o", "json"));
    require(
        originalService.at("/metadata/uid").equals(current.at("/metadata/uid"))
            && ownSelector.equals(current.at("/spec/selector")),
        "NS dispatch route changed");
    return isolated.logs();
  }

  private void awaitEndpoint() {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    do {
      var endpoints = JSON.readTree(execute("get", "endpoints", deployment, "-o", "json"));
      var addresses =
          endpoints
              .path("subsets")
              .valueStream()
              .flatMap(subset -> subset.path("addresses").valueStream())
              .toList();
      if (!addresses.isEmpty()
          && addresses.stream()
              .allMatch(
                  address -> isolated.podUid().equals(address.at("/targetRef/uid").asString()))
          && endpoints
              .path("subsets")
              .valueStream()
              .allMatch(s -> s.path("notReadyAddresses").isEmpty())) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("NS route convergence interrupted", e);
      }
    } while (System.nanoTime() < deadline);
    throw new AssertionError("RS service did not converge exclusively to the owned NS pod");
  }

  /**
   * Pauses only the identified kind test gateway container; an external watchdog resumes it after
   * 12 seconds.
   */
  public void pauseGateway(String gatewayDeployment, String kindCluster) {
    require(
        gatewayPod == null && isolated != null && routeClaimed,
        "Gateway pause requires an active isolated NS profile");
    var source = JSON.readTree(execute("get", "deployment", gatewayDeployment, "-o", "json"));
    var containers = source.at("/spec/template/spec/containers");
    require(
        source.at("/spec/replicas").asInt() == 1 && containers.size() == 1,
        "Gateway pause requires one test replica/container");
    var liveness = containers.get(0).path("livenessProbe");
    require(
        liveness.path("periodSeconds").asInt(10) * liveness.path("failureThreshold").asInt(3) > 15,
        "Gateway liveness window is too short for the automatic 12-second resume");
    var labels = source.at("/spec/selector/matchLabels");
    require(labels.isObject() && !labels.isEmpty(), "Gateway selector is missing");
    var gatewaySelector =
        String.join(
            ",",
            labels.properties().stream()
                .map(e -> e.getKey() + "=" + e.getValue().asString())
                .toList());
    var pods =
        JSON.readTree(execute("get", "pods", "-l", gatewaySelector, "-o", "json"))
            .path("items")
            .valueStream()
            .filter(p -> !p.path("metadata").has("deletionTimestamp"))
            .toList();
    require(pods.size() == 1, "Test gateway pod is not unique");
    var statuses = pods.getFirst().at("/status/containerStatuses");
    require(
        statuses.size() == 1 && statuses.get(0).path("ready").asBoolean(),
        "Test gateway is not ready");
    final var name = pods.getFirst().path("metadata").path("name").asString();
    gatewayNode = pods.getFirst().at("/spec/nodeName").asString();
    require(
        gatewayNode.matches("[a-z0-9][a-z0-9-]{0,62}")
            && kindCluster.matches("[a-z0-9][a-z0-9-]{0,62}"),
        "Invalid kind runtime identity");
    var nodeLabels =
        JSON.readTree(
            runtime.apply(
                List.of("docker", "inspect", "--format", "{{json .Config.Labels}}", gatewayNode)));
    require(
        kindCluster.equals(nodeLabels.path("io.x-k8s.kind.cluster").asString()),
        "Gateway node is not in the configured local kind cluster");
    var id = statuses.get(0).path("containerID").asString();
    require(
        id.matches("containerd://[a-f0-9]{64}"), "Unsupported gateway container runtime identity");
    gatewayTask = id.substring("containerd://".length());
    require("RUNNING".equals(taskState()), "Gateway task was not running before the test");
    gatewayPod = name;
    gatewayStatus = statuses.get(0).deepCopy();
    // The watchdog lives in the kind node, outside the paused container and its cgroup.
    runtime.apply(
        List.of(
            "docker",
            "exec",
            gatewayNode,
            "sh",
            "-c",
            "(sleep 12; ctr -n k8s.io tasks resume \"$1\") >/dev/null 2>&1 </dev/null & ctr -n"
                + " k8s.io tasks pause \"$1\"",
            "sh",
            gatewayTask));
    require("PAUSED".equals(taskState()), "Gateway pause was not established");
  }

  /**
   * Resumes the same test container explicitly; the watchdog is a fallback, not the cleanup proof.
   */
  public void resumeGateway() {
    if (gatewayPod == null) {
      return;
    }
    var pod = JSON.readTree(execute("get", "pod", gatewayPod, "-o", "json"));
    var current = pod.at("/status/containerStatuses/0");
    for (var field : List.of("containerID", "imageID", "restartCount", "state")) {
      require(
          gatewayStatus.path(field).equals(current.path(field)),
          "Gateway restarted during the bounded pause");
    }
    if ("PAUSED".equals(taskState())) {
      runtime.apply(
          List.of(
              "docker",
              "exec",
              gatewayNode,
              "ctr",
              "-n",
              "k8s.io",
              "tasks",
              "resume",
              gatewayTask));
    }
    require("RUNNING".equals(taskState()), "Test gateway did not resume");
    gatewayPod = null;
  }

  /**
   * Resumes the gateway, restores only the owned route and always removes the disposable NS.
   */
  @Override
  public void close() {
    var failures = new ArrayList<Throwable>();
    try {
      resumeGateway();
    } catch (AssertionError | RuntimeException failure) {
      failures.add(failure);
    }
    try {
      if (routeClaimed) {
        var current = JSON.readTree(execute("get", "service", deployment, "-o", "json"));
        var original = originalService.at("/spec/selector");
        if (!original.equals(current.at("/spec/selector"))) {
          replaceSelector(ownSelector, original);
        }
        require(
            originalService.at("/metadata/uid").equals(current.at("/metadata/uid")),
            "Original RS service was replaced");
        require(
            original.equals(
                JSON.readTree(execute("get", "service", deployment, "-o", "json"))
                    .at("/spec/selector")),
            "Original RS service selector was not restored");
        routeClaimed = false;
      }
    } catch (AssertionError | RuntimeException failure) {
      failures.add(failure);
    }
    try {
      if (isolated != null) {
        isolated.close();
      }
    } catch (AssertionError | RuntimeException failure) {
      failures.add(failure);
    }
    if (!failures.isEmpty()) {
      var failure = new AssertionError("NS dispatch cleanup failed");
      failures.forEach(failure::addSuppressed);
      throw failure;
    }
  }

  private String taskState() {
    var tasks =
        runtime.apply(
            List.of("docker", "exec", gatewayNode, "ctr", "-n", "k8s.io", "tasks", "list"));
    var matching =
        tasks
            .lines()
            .map(line -> line.trim().split("\\s+"))
            .filter(fields -> fields.length == 3 && gatewayTask.equals(fields[0]))
            .toList();
    require(matching.size() == 1, "Gateway runtime task was not uniquely identified");
    return matching.getFirst()[2];
  }

  private static String runtimeCommand(List<String> command) {
    try (var commands = new SystemCommandService(10)) {
      var result = commands.executeCommand(command, false);
      require(result.exitCode() == 0, "Local kind runtime operation failed");
      return result.stdout();
    }
  }

  static ArrayNode probeEnvironment(JsonNode environment, String owner) {
    require(environment.isArray(), "NS environment must be an explicit array");
    var configured = new LinkedHashMap<String, JsonNode>();
    var names = new HashSet<String>();
    for (var entry : environment) {
      var name = entry.path("name").asString();
      require(!name.isBlank() && names.add(name), "Duplicate or missing NS environment name");
      require(!OWNER.equals(name), "Another test owns the NS dispatch profile");
      configured.put(name, entry.deepCopy());
    }
    var overrides =
        Map.of(
            "NOTIFICATION_DISPATCH_CONCURRENCY",
            "1",
            "NOTIFICATION_DISPATCH_QUEUE_CAPACITY",
            "1",
            "QUARKUS_HTTP_ACCESS_LOG_ENABLED",
            "true",
            "QUARKUS_HTTP_ACCESS_LOG_LOG_TO_FILE",
            "false",
            "QUARKUS_HTTP_ACCESS_LOG_PATTERN",
            "CC_NS_BACKLOG %m %U %s %{i,X-CC-NS-Test}",
            "QUARKUS_HTTP_ACCESS_LOG_EXCLUDE_PATTERN",
            "^/q/.*$",
            OWNER,
            owner);
    overrides.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            e ->
                configured.put(
                    e.getKey(),
                    JSON.createObjectNode().put("name", e.getKey()).put("value", e.getValue())));
    var result = JSON.createArrayNode();
    configured.values().forEach(result::add);
    return result;
  }

  static ArrayNode retentionEnvironment(JsonNode original, String owner) {
    for (var required :
        List.of("NOTIFICATION_PERSISTENCE_SEALING_ENABLED", "NOTIFICATION_HISTORY_ENABLED")) {
      var matches =
          original.valueStream().filter(e -> required.equals(e.path("name").asString())).toList();
      require(
          matches.size() == 1 && "false".equals(matches.getFirst().path("value").asString()),
          "Retention observer requires the existing unsealed, history-disabled local test profile");
    }
    var result = probeEnvironment(original, owner);
    for (var entry :
        Map.of(
                "NOTIFICATION_PERSISTENCE_RETENTION",
                "PT2M",
                "NOTIFICATION_PERSISTENCE_PURGE_INTERVAL",
                "1s",
                "PUSH_GATEWAY_RETRY_GIVE_UP_AFTER",
                "PT1M")
            .entrySet()) {
      for (int i = result.size() - 1; i >= 0; i--) {
        if (entry.getKey().equals(result.get(i).path("name").asString())) {
          result.remove(i);
        }
      }
      result.addObject().put("name", entry.getKey()).put("value", entry.getValue());
    }
    return result;
  }

  /**
   * Requires exactly one NS-side log entry for this request, excluding proxy-generated statuses.
   */
  public static void verifyAccessLog(String logs, String correlation, int status) {
    require(correlation.matches("[a-zA-Z0-9-]{1,100}"), "Invalid NS correlation marker");
    var expected = "CC_NS_BACKLOG POST /notifications " + status + " " + correlation;
    require(
        logs.lines().filter(line -> line.endsWith(expected)).count() == 1,
        "Missing or ambiguous NS response evidence");
  }

  private void replaceSelector(JsonNode expected, JsonNode desired) {
    var current = JSON.readTree(execute("get", "service", deployment, "-o", "json"));
    require(
        originalService.at("/metadata/uid").equals(current.at("/metadata/uid")),
        "RS service was replaced");
    require(
        expected.equals(current.at("/spec/selector")),
        "RS service route changed; refusing to overwrite");
    var patch = JSON.createArrayNode();
    patch
        .addObject()
        .put("op", "test")
        .put("path", "/metadata/resourceVersion")
        .put("value", current.at("/metadata/resourceVersion").asString());
    patch.addObject().put("op", "replace").put("path", "/spec/selector").set("value", desired);
    Path file = null;
    try {
      file = Files.createTempFile(Path.of("target"), "cc-ns-route-", ".json");
      Files.writeString(file, JSON.writeValueAsString(patch));
      execute(
          "patch",
          "service",
          deployment,
          "--type=json",
          "--patch-file",
          file.toAbsolutePath().toString());
    } catch (IOException e) {
      throw new AssertionError("Could not prepare the NS route patch");
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          throw new AssertionError("Could not remove the NS route patch");
        }
      }
    }
  }

  private JsonNode readDeployment() {
    return JSON.readTree(execute("get", "deployment", deployment, "-o", "json"));
  }

  private String execute(String... arguments) {
    var command = new ArrayList<>(List.of("-n", namespace));
    command.addAll(List.of(arguments));
    var response = service.executeKubectlCommand(false, command);
    require(response.exitCode() == 0, "NS profile Kubernetes operation failed: " + arguments[0]);
    return response.stdout();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
