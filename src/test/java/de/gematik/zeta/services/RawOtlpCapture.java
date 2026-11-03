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

import de.gematik.zeta.services.TelemetryCollectorDiscovery.CaptureTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Temporary, processor-free OTLP JSON capture at the local test monitoring receiver.
 */
public final class RawOtlpCapture implements AutoCloseable {
  /**
   * Observes the full original 30-second window, including late conflicting exports.
   */
  public boolean observeServiceNames(List<RawOtlpTraceAssertions.Operation> operations)
      throws InterruptedException {
    return observeServiceNames(operations, System::nanoTime, Thread::sleep);
  }

  boolean observeServiceNames(
      List<RawOtlpTraceAssertions.Operation> operations,
      LongSupplier clock,
      BoundedObservation.Sleeper sleep)
      throws InterruptedException {
    var deadline = clock.getAsLong() + java.time.Duration.ofSeconds(30).toNanos();
    do {
      try {
        RawOtlpTraceAssertions.verifyServiceNames(batches(), operations);
      } catch (IncompleteLastRecord pending) {
        sleep.sleep(2000);
        continue;
      }
      sleep.sleep(2000);
    } while (clock.getAsLong() < deadline);
    return RawOtlpTraceAssertions.verifyServiceNames(batches(), operations);
  }

  private static final ObjectMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final String ANNOTATION = "tests.zeta.gematik.de/raw-otlp-capture";
  private static final String EXPORTER = "file/cc_raw_capture";
  private static final String PIPELINE = "traces/cc_raw_capture";
  private static final int MAX_LOG_BYTES = 16 * 1024 * 1024;
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final CaptureTarget target;
  private final String run = UUID.randomUUID().toString();
  private String originalRelay;
  private String captureRelay;
  private String podName;
  private String previousLogs = "";
  private JsonNode initialContainerStatus;

  /**
   * Creates an inactive capture for a resolved Deployment, mounted ConfigMap and OTLP receiver.
   *
   * @param service Kubernetes command adapter
   * @param namespace test namespace
   * @param target resolved collector resources and receiver
   */
  public RawOtlpCapture(ZetaDeploymentModificationService service, String namespace, CaptureTarget target) {
    this.service = service;
    this.namespace = namespace;
    this.target = target;
  }

  /**
   * Installs a separate unmodified-input capture pipeline; existing pipelines remain present.
   */
  public void start() {
    require(originalRelay == null, "Raw capture has already been started");
    var deployment = read("deployment", target.deployment());
    require(
        deployment.path("spec").path("replicas").asInt() == 1,
        "Raw capture requires exactly one test collector replica");
    require(
        !deployment.at("/spec/template/metadata/annotations").has(ANNOTATION),
        "Another raw capture owns this collector");
    var containers = deployment.at("/spec/template/spec/containers");
    require(containers.size() == 1, "Raw capture requires one collector container");
    require(
        containers.get(0).path("args").toString().contains("--config=/conf/relay.yaml"),
        "Unsupported collector config path");
    var volumes = deployment.at("/spec/template/spec/volumes");
    require(
        volumes
            .valueStream()
            .anyMatch(v -> target.configMap().equals(v.path("configMap").path("name").asString())),
        "Test collector does not mount its configured ConfigMap");
    originalRelay = read("configmap", target.configMap()).path("data").path("relay").asString();
    var config = YAML.readTree(originalRelay);
    captureRelay = YAML.writeValueAsString(withCapturePipeline(config, target.receiver()));
    replaceRelay(originalRelay, captureRelay);
    setCaptureAnnotation(true);
    rollout();
    var pods = JSON.readTree(execute("get", "pods", "-o", "json")).path("items");
    var matches =
        pods.valueStream()
            .filter(
                p ->
                    run.equals(p.at("/metadata/annotations").path(ANNOTATION).asString())
                        && !p.path("metadata").has("deletionTimestamp"))
            .toList();
    require(matches.size() == 1, "Raw capture pod is not unique");
    var pod = matches.getFirst();
    podName = pod.path("metadata").path("name").asString();
    initialContainerStatus = pod.path("status").path("containerStatuses").deepCopy();
    require(
        initialContainerStatus.size() == 1
            && initialContainerStatus.get(0).path("ready").asBoolean(),
        "Raw capture collector is not ready");
    batches();
    require(!previousLogs.isBlank(), "Raw capture requires retained collector startup logs");
  }

  /**
   * Reads every available raw JSON batch since this capture pod started; restarts/truncation fail.
   *
   * @return unmodified OTLP trace export objects
   */
  public List<JsonNode> batches() {
    require(podName != null, "Raw capture has not started");
    require(
        captureRelay.equals(read("configmap", target.configMap()).path("data").path("relay").asString()),
        "Collector config changed during capture");
    verifyStablePod();
    var logs = execute("logs", podName, "--tail=-1", "--limit-bytes=" + MAX_LOG_BYTES);
    require(
        logs.getBytes(StandardCharsets.UTF_8).length < MAX_LOG_BYTES,
        "Raw capture exceeds the complete-log collection bound");
    require(
        logs.startsWith(previousLogs),
        "Raw capture logs were rotated, truncated or changed during observation");
    var result = new ArrayList<JsonNode>();
    var lines = logs.lines().toList();
    for (int index = 0; index < lines.size(); index++) {
      var line = lines.get(index);
      if (!line.stripLeading().startsWith("{") && !line.contains("\"resourceSpans\"")) {
        continue;
      }
      try {
        var record = JSON.readTree(line);
        if (record.isObject() && !record.has("resourceSpans")) {
          continue;
        }
        require(
            record.isObject() && record.path("resourceSpans").isArray(),
            "Malformed raw trace export object");
        result.add(record);
      } catch (RuntimeException e) {
        if (e instanceof UnexpectedEndOfInputException && index == lines.size() - 1) {
          verifyStablePod();
          previousLogs = logs;
          throw new IncompleteLastRecord();
        }
        throw new AssertionError(
            "Incomplete or malformed raw OTLP JSON capture line ("
                + e.getClass().getSimpleName()
                + ", length="
                + line.length()
                + ", objectStart="
                + line.startsWith("{")
                + ", objectEnd="
                + line.endsWith("}")
                + ")");
      }
    }
    verifyStablePod();
    previousLogs = logs;
    return result;
  }

  private static final class IncompleteLastRecord extends AssertionError {
    private IncompleteLastRecord() {
      super(
          "Incomplete final raw OTLP record; complete capture required within the observation"
              + " window");
    }
  }

  /**
   * Restores the original config and removes only this capture's rollout annotation.
   */
  @Override
  public void close() {
    if (originalRelay == null) {
      return;
    }
    var annotation = read("deployment", target.deployment()).at("/spec/template/metadata/annotations").path(ANNOTATION);
    require(
        annotation.isMissingNode() || run.equals(annotation.asString()),
        "Raw capture ownership changed; refusing to overwrite");
    var current = read("configmap", target.configMap()).path("data").path("relay").asString();
    if (!current.equals(originalRelay)) {
      replaceRelay(captureRelay, originalRelay);
    }
    if (!annotation.isMissingNode()) {
      setCaptureAnnotation(false);
      rollout();
    }
    require(
        originalRelay.equals(read("configmap", target.configMap()).path("data").path("relay").asString()),
        "Collector config restoration failed");
    originalRelay = null;
    previousLogs = "";
  }

  static ObjectNode withCapturePipeline(JsonNode source, String receiver) {
    require(
        source.isObject() && source.path("receivers").has(receiver),
        "Expected raw gematik receiver is missing");
    var config = (ObjectNode) source.deepCopy();
    require(
        !config.path("exporters").has(EXPORTER) && !config.at("/service/pipelines").has(PIPELINE),
        "Raw capture component names are already in use");
    var exporters = (ObjectNode) config.path("exporters");
    exporters
        .putObject(EXPORTER)
        .put("path", "/dev/stdout")
        .put("format", "json")
        .put("append", true)
        .put("flush_interval", "100ms");
    var pipeline = ((ObjectNode) config.at("/service/pipelines")).putObject(PIPELINE);
    pipeline.putArray("receivers").add(receiver);
    pipeline.putArray("processors");
    pipeline.putArray("exporters").add(EXPORTER);
    return config;
  }

  private void verifyStablePod() {
    var pod = JSON.readTree(execute("get", "pod", podName, "-o", "json"));
    require(
        !pod.path("metadata").has("deletionTimestamp")
            && initialContainerStatus.equals(pod.path("status").path("containerStatuses")),
        "Raw capture collector restarted or changed during observation");
  }

  private void replaceRelay(String expected, String desired) {
    var map = (ObjectNode) read("configmap", target.configMap());
    require(
        expected != null && expected.equals(map.path("data").path("relay").asString()),
        "Concurrent collector config change; refusing to overwrite");
    ((ObjectNode) map.path("data")).put("relay", desired);
    executeJsonFile(map, "replace", "-f");
  }

  private void executeJsonFile(JsonNode data, String... args) {
    Path temporary = null;
    try {
      temporary = Files.createTempFile(Path.of("target"), "cc-otlp-config-", ".json");
      Files.writeString(temporary, JSON.writeValueAsString(data));
      var command = new ArrayList<>(List.of(args));
      command.add(temporary.toAbsolutePath().toString());
      execute(command.toArray(String[]::new));
    } catch (IOException e) {
      throw new AssertionError("Could not prepare the local raw capture configuration file");
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException e) {
          throw new AssertionError("Could not remove the temporary raw capture configuration file");
        }
      }
    }
  }

  private void setCaptureAnnotation(boolean enable) {
    var deployment = read("deployment", target.deployment());
    var existing = deployment.at("/spec/template/metadata/annotations");
    var annotations =
        existing.isObject() ? (ObjectNode) existing.deepCopy() : JSON.createObjectNode();
    if (enable) {
      require(!annotations.has(ANNOTATION), "Another capture owns the collector rollout");
      annotations.put(ANNOTATION, run);
    } else {
      require(
          run.equals(annotations.path(ANNOTATION).asString()), "Capture rollout ownership changed");
      annotations.remove(ANNOTATION);
    }
    var patch = JSON.createArrayNode();
    patch
        .addObject()
        .put("op", "test")
        .put("path", "/metadata/resourceVersion")
        .put("value", deployment.path("metadata").path("resourceVersion").asString());
    patch
        .addObject()
        .put("op", "add")
        .put("path", "/spec/template/metadata/annotations")
        .set("value", annotations);
    executeJsonFile(patch, "patch", "deployment", target.deployment(), "--type=json", "--patch-file");
  }

  private void rollout() {
    execute("rollout", "status", "deployment/" + target.deployment(), "--timeout=60s");
  }

  private JsonNode read(String kind, String name) {
    return JSON.readTree(execute("get", kind, name, "-o", "json"));
  }

  private String execute(String... args) {
    var command = new ArrayList<>(List.of("-n", namespace));
    command.addAll(List.of(args));
    var result = service.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "Raw OTLP capture Kubernetes operation failed: " + args[0]);
    return result.stdout();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
