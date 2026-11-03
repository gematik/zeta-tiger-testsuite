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

package de.gematik.zeta.steps;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import io.cucumber.java.de.Dann;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Checks shipped sampling defaults separately from the configuration mounted by running collectors.
 */
public class TelemetrySamplingSteps {
  private String observedNamespace;
  private String observedReleaseName;
  private String observedPipeline;
  private boolean shippedVerified;
  private String defaultEvidence;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final String SELECTOR =
      "app.kubernetes.io/name=telemetry-gateway,component=standalone-collector";
  private static final int MAX_RELEASE_BYTES = 16 * 1024 * 1024;
  private final ZetaDeploymentModificationService service =
      ZetaDeploymentConfiguration.getServiceInstance();

  /**
   * Reads and checks the installed Helm bundle's shipped defaults independently of runtime
   * overrides.
   *
   * @param pipeline named gematik trace pipeline from the test profile
   * @param defaultsPointer JSON pointer to the shipped collector configuration within the Helm
   *     release
   */
  @Dann("prüfe deaktiviertes Sampling im Auslieferungsstandard {string} für Pipeline {tigerResolvedString}")
  public void verifyDefaultSamplingDisabled(String defaultsPointer, String pipeline) {
    var namespace = config("zetaDeploymentConfig.namespace");
    var releaseName =
        collectorReleaseName(
            readJson("-n", namespace, "get", "pods", "-l", SELECTOR, "-o", "json")
                .path("items"));
    var secrets =
        readJson(
            "-n",
            namespace,
            "get",
            "secret",
            "-l",
            "owner=helm,name=" + releaseName + ",status=deployed",
            "-o",
            "json");
    require(
        secrets.path("items").isArray() && secrets.path("items").size() == 1,
        "Expected exactly one deployed Helm release revision for release "
            + releaseName + " in namespace " + namespace
            + "; found " + secrets.path("items").size());
    var release =
        decodeRelease(secrets.path("items").get(0).path("data").path("release").asString());
    require(releaseName.equals(release.path("name").asString()), "Helm release identity mismatch");
    require(
        "deployed".equals(release.path("info").path("status").asString()),
        "Helm release is not deployed");
    var defaults = shippedDefaults(release, defaultsPointer);
    final var defaultProcessors = requireSamplingDisabled(defaults, pipeline);

    observedNamespace = namespace;
    observedReleaseName = releaseName;
    observedPipeline = pipeline;
    shippedVerified = true;
    defaultEvidence =
        "Release="
            + releaseName
            + " revision="
            + release.path("version").asInt()
            + " chart="
            + release.path("chart").path("metadata").path("name").asString()
            + " chartVersion="
            + release.path("chart").path("metadata").path("version").asString()
            + "\nDefault source="
            + defaultsPointer
            + "\nPipeline="
            + pipeline
            + "\nDefault processors="
            + defaultProcessors;
    SerenityReportUtils.addCustomData("Sampling shipped default", defaultEvidence);
  }

  /**
   * Checks the same pipeline in every ready collector belonging to the observed release.
   */
  @Dann("Sampling ist auch in allen laufenden Collectors derselben Pipeline deaktiviert")
  public void verifyRuntimeSamplingDisabled() {
    require(shippedVerified, "Missing shipped-default evidence");
    var namespace = observedNamespace;
    var releaseName = observedReleaseName;
    var pipeline = observedPipeline;
    var pods =
        readJson(
                "-n",
                namespace,
                "get",
                "pods",
                "-l",
                SELECTOR + ",app.kubernetes.io/instance=" + releaseName,
                "-o",
                "json")
            .path("items");
    require(pods.isArray() && !pods.isEmpty(), "No telemetry collector pods found");
    var evidence = new ArrayList<String>();
    for (var pod : pods) {
      require(
          releaseName.equals(
              pod.path("metadata").path("labels").path("app.kubernetes.io/instance").asString()),
          "Collector pod belongs to a different Helm release");
      inspectPod(namespace, pod, pipeline, evidence);
    }
    SerenityReportUtils.addCustomData(
        "Sampling default and mounted runtime configuration",
        defaultEvidence + "\n" + String.join("\n", evidence));
  }

  private void inspectPod(String namespace, JsonNode pod, String pipeline, List<String> evidence) {
    require(
        pod.path("metadata").path("deletionTimestamp").isMissingNode(),
        "Collector is terminating; wait for a stable rollout");
    var podName = pod.path("metadata").path("name").asString();
    var containers = pod.path("spec").path("containers");
    require(containers.size() == 1, "Sampling profile requires one collector container per pod");
    var container = containers.get(0);
    var status = pod.path("status").path("containerStatuses").get(0);
    require(status != null && status.path("ready").asBoolean(), "Collector container is not ready");
    final var started =
        Instant.parse(status.path("state").path("running").path("startedAt").asString());
    var arguments = new ArrayList<String>();
    arguments.addAll(
        List.of("-n", namespace, "exec", podName, "-c", container.path("name").asString(), "--"));
    var command = container.path("command");
    require(
        command.isArray() && command.size() == 1,
        "Collector binary must be explicit in the pod command");
    arguments.add(command.get(0).asString());
    arguments.add("print-config");
    var configCount = 0;
    for (var argument : container.path("args")) {
      var value = argument.asString();
      require(
          value.startsWith("--config="),
          "Sampling profile requires explicit file config arguments without CLI overrides");
      verifyMountedConfigPredatesProcess(
          namespace, pod, container, value.substring("--config=".length()), started);
      arguments.add(value);
      configCount++;
    }
    require(configCount > 0, "Collector has no explicit mounted configuration");
    arguments.add("--mode=redacted");
    var resolved = YAML.readTree(execute(arguments.toArray(String[]::new)));
    // print-config starts a separate process. Reject configuration updates or a restart during
    // observation.
    for (var argument : container.path("args")) {
      verifyMountedConfigPredatesProcess(
          namespace, pod, container, argument.asString().substring("--config=".length()), started);
    }
    var observedPod = readJson("-n", namespace, "get", "pod", podName, "-o", "json");
    require(
        status.equals(observedPod.path("status").path("containerStatuses").get(0)),
        "Collector container state changed during observation");
    var processors = requireSamplingDisabled(resolved, pipeline);
    var exporters = resolved.path("service").path("pipelines").path(pipeline).path("exporters");
    require(
        exporters.isArray() && !exporters.isEmpty(), "Running gematik pipeline has no exporter");
    for (var exporter : exporters) {
      var name = exporter.asString();
      require(
          List.of("otlp", "otlphttp", "otlp_grpc", "otlp_http").contains(name.split("/", 2)[0])
              && resolved.path("exporters").has(name),
          "Running gematik pipeline must export directly through a declared OTLP exporter");
    }
    evidence.add(
        "Pod="
            + podName
            + " started="
            + started
            + " image="
            + status.path("imageID").asString()
            + " processors="
            + processors);
  }

  private void verifyMountedConfigPredatesProcess(
      String namespace, JsonNode pod, JsonNode container, String path, Instant started) {
    require(path.startsWith("/"), "Collector config must be an absolute mounted file");
    var matchingMounts = new ArrayList<JsonNode>();
    for (var mount : container.path("volumeMounts")) {
      if (path.equals(mount.path("mountPath").asString())
          || path.startsWith(mount.path("mountPath").asString() + "/")) {
        matchingMounts.add(mount);
      }
    }
    require(
        matchingMounts.size() == 1,
        "Sampling profile requires an unambiguous ConfigMap directory mount");
    for (var mount : matchingMounts) {
      require(
          !mount.has("subPath") && !mount.has("subPathExpr"),
          "Sampling profile does not support subPath mounts");
      if (!path.startsWith(mount.path("mountPath").asString() + "/")) {
        continue;
      }
      for (var volume : pod.path("spec").path("volumes")) {
        if (volume.path("name").asString().equals(mount.path("name").asString())) {
          var name = volume.path("configMap").path("name").asString("");
          require(!name.isBlank(), "Collector config must come from a ConfigMap");
          var map =
              readJson(
                  "-n",
                  namespace,
                  "get",
                  "configmap",
                  name,
                  "-o",
                  "json",
                  "--show-managed-fields=true");
          requireConfigPredatesProcess(map.path("metadata"), started);
          return;
        }
      }
    }
    throw new AssertionError(
        "Collector configuration is not backed by an observed ConfigMap mount");
  }

  static void requireConfigPredatesProcess(JsonNode metadata, Instant started) {
    var created = Instant.parse(metadata.path("creationTimestamp").asString());
    require(!created.isAfter(started), "Collector ConfigMap was created after process startup");
    var fields = metadata.path("managedFields");
    require(fields.isArray() && !fields.isEmpty(), "ConfigMap modification history is missing");
    for (var field : fields) {
      var changed = Instant.parse(field.path("time").asString());
      require(
          !changed.isAfter(started),
          "Collector ConfigMap changed after process startup; a fresh rollout is required");
    }
  }

  static List<String> requireSamplingDisabled(JsonNode collector, String pipelineName) {
    require(
        collector != null && collector.isObject(),
        "Shipped or mounted collector configuration is missing");
    var pipeline = collector.path("service").path("pipelines").path(pipelineName);
    require(
        pipelineName.startsWith("traces/") && pipeline.isObject(),
        "Named gematik trace pipeline is missing");
    var receivers = pipeline.path("receivers");
    require(receivers.isArray() && !receivers.isEmpty(), "Gematik pipeline has no receivers");
    for (var receiver : receivers) {
      var name = receiver.asString();
      require(
          name.split("/", 2)[0].equals("otlp") && collector.path("receivers").has(name),
          "Sampling profile requires direct OTLP input, without an unexamined connector pipeline");
    }
    var names = new ArrayList<String>();
    var processors = pipeline.path("processors");
    require(processors.isArray(), "Pipeline processors must be explicitly listed");
    for (var processor : processors) {
      var name = processor.asString();
      require(
          !name.contains("sampl"),
          "Sampling must be disabled in the default profile; active processor: " + name);
      require(collector.path("processors").has(name), "Referenced collector processor is missing");
      names.add(name);
    }
    return List.copyOf(names);
  }

  static JsonNode decodeRelease(String encoded) {
    try {
      require(
          encoded != null && encoded.length() < MAX_RELEASE_BYTES,
          "Helm release data is absent or exceeds the test limit");
      var compressed = Base64.getDecoder().decode(Base64.getDecoder().decode(encoded));
      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
        var bytes = gzip.readNBytes(MAX_RELEASE_BYTES + 1);
        require(bytes.length <= MAX_RELEASE_BYTES, "Decoded Helm release exceeds the test limit");
        return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
      }
    } catch (IOException | RuntimeException e) {
      // Helm release values can include credentials. Do not attach their parser context to reports.
      throw new AssertionError("Could not decode the installed Helm release");
    }
  }

  static JsonNode shippedDefaults(JsonNode release, String pointer) {
    require(
        pointer.startsWith("/chart/values/"),
        "Sampling defaults must come from chart values, not installation overrides");
    return release.at(pointer);
  }

  static JsonNode parseEvidenceJson(String content) {
    try {
      return JSON.readTree(content);
    } catch (RuntimeException e) {
      // Secret or ConfigMap parser excerpts must never enter a report.
      throw new AssertionError("Could not parse Kubernetes evidence");
    }
  }

  /**
   * Resolves the common Helm instance label of the selected collector pods.
   *
   * @param pods collector pod list from the configured namespace
   * @return the release name shared by all selected collectors
   * @throws AssertionError if collectors or instance labels are missing or releases are ambiguous
   */
  static String collectorReleaseName(JsonNode pods) {
    require(pods.isArray() && !pods.isEmpty(), "No telemetry collector pods found");
    var releases = new TreeSet<String>();
    for (var pod : pods) {
      var release =
          pod.path("metadata").path("labels").path("app.kubernetes.io/instance").asString("");
      require(!release.isBlank(), "Collector pod is missing its Helm instance label");
      releases.add(release);
    }
    require(releases.size() == 1, "Telemetry collector pods belong to multiple Helm releases: " + releases);
    return releases.first();
  }

  private JsonNode readJson(String... arguments) {
    return parseEvidenceJson(execute(arguments));
  }

  private String execute(String... arguments) {
    var result = service.executeKubectlCommand(false, arguments);
    require(result.exitCode() == 0, "Read-only collector evidence command failed");
    return result.stdout();
  }

  private static String config(String name) {
    return TigerGlobalConfiguration.readStringOptional(name)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
