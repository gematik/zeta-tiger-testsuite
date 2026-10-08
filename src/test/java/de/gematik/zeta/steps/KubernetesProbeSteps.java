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
import de.gematik.zeta.services.KubernetesRateLimitConfigurationEvidence;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Cucumber step definitions for Kubernetes probe verification.
 */
public class KubernetesProbeSteps {

  private static final String ZETA_NAMESPACE_CONFIG_KEY = "zetaDeploymentConfig.namespace";
  private static final String OPENTELEMETRY_RELAY_JSONPATH = "jsonpath={.data.relay}";
  private static final String ACTIVE_OPA_CONFIGMAP = "opa-config";
  private static final String SIMULATION_OPA_CONFIGMAP = "opa-simulation-config";
  private static final String RATE_LIMIT_CONFIG_RESOURCE_TYPES = "configmap,ingress";
  private static final String TELEMETRY_GATEWAY_CONFIGMAP_SELECTOR =
      "app.kubernetes.io/name=telemetry-gateway,app.kubernetes.io/component=standalone-collector";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final Pattern TRACES_PIPELINE_USES_BATCH = Pattern.compile(
      "(?ms)^\\s*traces:\\s*$.*?^\\s*processors:\\s*$.*?^\\s*-\\s*batch\\s*(?:#.*)?$");
  private static final Pattern TRACES_PIPELINE_OTLP_EXPORTER = Pattern.compile(
      "(?ms)^\\s*traces:\\s*$.*?^\\s*exporters:\\s*$.*?^\\s*-\\s*(otlp[\\w-]*(?:/[^\\s#]+)?)\\s*(?:#.*)?$");
  private static final Pattern TRACES_PIPELINE_PATTERN = Pattern.compile(
      "(?ms)^\\s*traces:\\s*$.*?(?=^\\s{0,4}\\S|\\z)");
  private static final List<String> TELEMETRY_SIGNALS = List.of("logs", "metrics", "traces");
  private final ZetaDeploymentModificationService service = ZetaDeploymentConfiguration.getServiceInstance();

  /**
   * Checks whether the traces pipeline uses a batch processor.
   *
   * @param relayConfig OpenTelemetry Collector relay configuration
   * @return true if the traces pipeline references the batch processor
   */
  static boolean tracesPipelineUsesBatch(String relayConfig) {
    return TRACES_PIPELINE_USES_BATCH.matcher(relayConfig).find();
  }

  /**
   * Reads the first OTLP exporter from the traces pipeline.
   *
   * @param relayConfig OpenTelemetry Collector relay configuration
   * @return OTLP exporter name when present
   */
  static Optional<String> firstTraceOtlpExporter(String relayConfig) {
    var exporterMatcher = TRACES_PIPELINE_OTLP_EXPORTER.matcher(relayConfig);
    if (exporterMatcher.find()) {
      return Optional.of(exporterMatcher.group(1));
    }
    return Optional.empty();
  }

  /**
   * Extracts the traces pipeline for the Serenity report.
   *
   * @param relayConfig OpenTelemetry Collector relay configuration
   * @return traces pipeline block or a placeholder
   */
  static String tracePipelineForReport(String relayConfig) {
    var tracesMatcher = TRACES_PIPELINE_PATTERN.matcher(relayConfig);
    var tracesPipeline = "";
    while (tracesMatcher.find()) {
      tracesPipeline = tracesMatcher.group();
    }
    if (tracesPipeline.isBlank()) {
      return "<pipeline not found>";
    }
    return tracesPipeline.stripTrailing();
  }

  /**
   * Reads configured OTLP gRPC or HTTP/JSON exporter names from the collector configuration.
   *
   * @param relayConfig OpenTelemetry Collector relay configuration
   * @return exporter names
   */
  static List<String> configuredOtlpExporters(String relayConfig) {
    return exporterNames(firstBlock(relayConfig, "exporters", 0)).stream()
        .filter(KubernetesProbeSteps::isOtlpExporterName)
        .toList();
  }

  /**
   * Reads active OTLP forwarding protocols from the collector service pipelines.
   *
   * @param relayConfig OpenTelemetry Collector relay configuration
   * @return active OTLP forwarding protocols
   */
  static List<String> activeOtlpForwardingProtocols(String relayConfig) {
    var exportersBlock = firstBlock(relayConfig, "exporters", 0);
    return configuredOtlpExporters(relayConfig).stream()
        .filter(exporterName -> !pipelinesUsingAnyExporter(relayConfig, List.of(exporterName)).isEmpty())
        .map(exporterName -> otlpProtocolForExporter(exportersBlock, exporterName))
        .flatMap(Optional::stream)
        .distinct()
        .toList();
  }

  /**
   * Checks which service pipelines reference any of the provided exporters.
   *
   * @param relayConfig   OpenTelemetry Collector relay configuration
   * @param exporterNames exporter names to search
   * @return pipeline signal names
   */
  static List<String> pipelinesUsingAnyExporter(String relayConfig, List<String> exporterNames) {
    if (exporterNames.isEmpty()) {
      return List.of();
    }

    var serviceBlock = firstBlock(relayConfig, "service", 0);
    var pipelinesBlock = firstBlock(serviceBlock, "pipelines", 2);
    return TELEMETRY_SIGNALS.stream()
        .filter(signal -> pipelineUsesAnyExporter(pipelinesBlock, signal, exporterNames))
        .toList();
  }

  /**
   * Checks whether a service pipeline references any of the given exporters.
   *
   * @param pipelinesBlock collector service pipelines block
   * @param signal         pipeline signal name
   * @param exporterNames  exporter names to search
   * @return true if the pipeline references one of the exporters
   */
  private static boolean pipelineUsesAnyExporter(String pipelinesBlock, String signal, List<String> exporterNames) {
    return pipelineNamesForSignal(pipelinesBlock, signal).stream()
        .map(pipelineName -> firstBlock(pipelinesBlock, pipelineName, 4))
        .map(pipelineBlock -> firstBlock(pipelineBlock, "exporters", 6))
        .map(KubernetesProbeSteps::listItems)
        .flatMap(List::stream)
        .anyMatch(exporterNames::contains);
  }

  /**
   * Extracts plain and named service pipeline keys for a telemetry signal.
   *
   * @param pipelinesBlock collector service pipelines block
   * @param signal         pipeline signal name
   * @return matching pipeline keys
   */
  private static List<String> pipelineNamesForSignal(String pipelinesBlock, String signal) {
    var names = new ArrayList<String>();
    for (var line : pipelinesBlock.split("\\R")) {
      if (countLeadingSpaces(line) != 4) {
        continue;
      }
      var trimmed = line.trim();
      if (!trimmed.endsWith(":")) {
        continue;
      }
      var pipelineName = trimmed.substring(0, trimmed.length() - 1);
      if (pipelineName.equals(signal) || pipelineName.startsWith(signal + "/")) {
        names.add(pipelineName);
      }
    }
    return names;
  }

  /**
   * Extracts the service pipelines block for the Serenity report.
   *
   * @param relayConfig OpenTelemetry Collector relay configuration
   * @return service pipelines block or a placeholder
   */
  static String servicePipelinesForReport(String relayConfig) {
    var serviceBlock = firstBlock(relayConfig, "service", 0);
    var pipelinesBlock = firstBlock(serviceBlock, "pipelines", 2);
    if (pipelinesBlock.isBlank()) {
      return "<pipelines not found>";
    }
    return pipelinesBlock.stripTrailing();
  }

  /**
   * Extracts direct child mapping keys as exporter names.
   *
   * @param block YAML-like block
   * @return direct child mapping keys
   */
  private static List<String> exporterNames(String block) {
    var names = new ArrayList<String>();
    for (var line : block.split("\\R")) {
      if (countLeadingSpaces(line) != 2) {
        continue;
      }
      var trimmed = line.trim();
      if (trimmed.endsWith(":")) {
        names.add(trimmed.substring(0, trimmed.length() - 1));
      }
    }
    return names;
  }

  /**
   * Extracts YAML list item values from a block.
   *
   * @param block YAML-like block
   * @return list item values
   */
  private static List<String> listItems(String block) {
    var items = new ArrayList<String>();
    for (var line : block.split("\\R")) {
      var trimmed = line.trim();
      if (trimmed.startsWith("- ")) {
        items.add(trimmed.substring(2).split("\\s+#", 2)[0].trim());
      }
    }
    return items;
  }

  /**
   * Checks whether a collector exporter name denotes OTLP over gRPC or HTTP/JSON.
   *
   * @param exporterName exporter name
   * @return true for OTLP exporter names
   */
  private static boolean isOtlpExporterName(String exporterName) {
    return "otlp".equals(exporterName)
        || exporterName.startsWith("otlp/")
        || exporterName.startsWith("otlp_grpc")
        || exporterName.startsWith("otlp_http")
        || exporterName.startsWith("otlphttp");
  }

  /**
   * Determines the OTLP transport protocol represented by an exporter.
   *
   * @param exportersBlock top-level exporters block
   * @param exporterName   exporter name
   * @return protocol name when the exporter represents an allowed protocol
   */
  private static Optional<String> otlpProtocolForExporter(String exportersBlock, String exporterName) {
    if (isOtlpGrpcExporterName(exporterName)) {
      return Optional.of("OTLP/gRPC");
    }
    if (isOtlpHttpExporterName(exporterName) && exporterUsesJsonEncoding(exportersBlock, exporterName)) {
      return Optional.of("OTLP/HTTP JSON");
    }
    return Optional.empty();
  }

  /**
   * Checks whether a collector exporter name denotes OTLP over gRPC.
   *
   * @param exporterName exporter name
   * @return true for OTLP/gRPC exporter names
   */
  private static boolean isOtlpGrpcExporterName(String exporterName) {
    return "otlp".equals(exporterName)
        || exporterName.startsWith("otlp/")
        || exporterName.startsWith("otlp_grpc");
  }

  /**
   * Checks whether a collector exporter name denotes OTLP over HTTP.
   *
   * @param exporterName exporter name
   * @return true for OTLP/HTTP exporter names
   */
  private static boolean isOtlpHttpExporterName(String exporterName) {
    return exporterName.startsWith("otlp_http")
        || exporterName.startsWith("otlphttp");
  }

  /**
   * Checks whether an OTLP/HTTP exporter explicitly uses JSON encoding.
   *
   * @param exportersBlock top-level exporters block
   * @param exporterName   exporter name
   * @return true if the exporter uses JSON encoding
   */
  private static boolean exporterUsesJsonEncoding(String exportersBlock, String exporterName) {
    var exporterBlock = firstBlock(exportersBlock, exporterName, 2);
    for (var line : exporterBlock.split("\\R")) {
      var trimmed = line.trim();
      if ("encoding: json".equals(trimmed)
          || "encoding: \"json\"".equals(trimmed)
          || "encoding: 'json'".equals(trimmed)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Extracts the first YAML-like block with the requested key and indentation.
   *
   * @param content YAML-like content
   * @param key     mapping key
   * @param indent  expected leading spaces
   * @return matching block or empty string
   */
  private static String firstBlock(String content, String key, int indent) {
    if (content == null || content.isBlank()) {
      return "";
    }

    var lines = content.split("\\R", -1);
    var block = new StringBuilder();
    var inBlock = false;
    var header = key + ":";

    for (var line : lines) {
      var leadingSpaces = countLeadingSpaces(line);
      var trimmed = line.trim();
      if (!inBlock) {
        if (leadingSpaces == indent && trimmed.equals(header)) {
          inBlock = true;
          block.append(line).append(System.lineSeparator());
        }
        continue;
      }

      if (!trimmed.isBlank() && leadingSpaces <= indent && !(leadingSpaces == indent && trimmed.startsWith("- "))) {
        break;
      }
      block.append(line).append(System.lineSeparator());
    }
    return block.toString();
  }

  /**
   * Counts leading space characters in a line.
   *
   * @param line text line
   * @return number of leading spaces
   */
  private static int countLeadingSpaces(String line) {
    var count = 0;
    while (count < line.length() && line.charAt(count) == ' ') {
      count++;
    }
    return count;
  }

  /**
   * Verifies that exactly one pod contains the given container and that the requested Kubernetes probe is configured.
   *
   * @param namespace     namespace for the kubectl query
   * @param containerName target container name
   * @param probeName     probe to check (`livenessProbe`, `readinessProbe`, `startupProbe`)
   */
  @Und("prüfe im Namespace {tigerResolvedString} dass der Container {tigerResolvedString} eine {kubeProbe} konfiguriert hat")
  @And("verify in namespace {tigerResolvedString} that container {tigerResolvedString} has {kubeProbe} configured")
  public void verifyContainerHasProbeConfigured(String namespace, String containerName, String probeName) {
    var items = service.readPodsForNamespace(namespace);
    var matchingPodNames = new ArrayList<String>();
    JsonNode matchingContainer = null;
    String reportLine;

    for (var pod : items) {
      var containers = pod.path("spec").path("containers");
      if (!containers.isArray()) {
        continue;
      }
      var podName = pod.path("metadata").path("name").asText("<unknown>");
      for (var container : containers) {
        if (!containerName.equals(container.path("name").asText(""))) {
          continue;
        }
        matchingPodNames.add(podName);
        matchingContainer = container;
      }
    }

    if (matchingPodNames.isEmpty()) {
      var msg = "No container with name '" + containerName + "' found in namespace '" + namespace + "'.";
      reportLine = "CONTAINER=" + containerName + " | RESULT=NOT_FOUND";
      SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
    } else if (matchingPodNames.size() > 1) {
      var msg = "Container '" + containerName + "' found in multiple pods in namespace '" + namespace
          + "': " + String.join(", ", matchingPodNames);
      reportLine = "CONTAINER=" + containerName + " | RESULT=AMBIGUOUS | PODS=" + String.join(",", matchingPodNames);
      SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
    } else {
      var podName = matchingPodNames.getFirst();
      var probe = matchingContainer.path(probeName);
      if (probe.isMissingNode() || probe.isNull()) {
        var msg = "Container '" + containerName + "' in pod '" + podName + "' has no " + probeName + " configured.";
        reportLine = "POD=" + podName + " | CONTAINER=" + containerName + " | " + probeName + "=MISSING";
        SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
      } else {
        reportLine =
            "POD=" + podName + " | CONTAINER=" + containerName + " | " + probeName + ":\n" + probe.toPrettyString();
      }
    }

    SerenityReportUtils.addCustomData(
        "Kubernetes probe check: " + containerName + " / " + probeName,
        reportLine);
  }

  /**
   * Verifies that the current Kubernetes deployment contains the expected rate-limit configuration for one endpoint.
   *
   * @param endpoint      endpoint path
   * @param configuration expected rate-limit configuration marker
   */
  @Gegebensei("im Kubernetes-Deployment eine Rate-Limit-Konfiguration {tigerResolvedString} für den Endpunkt {tigerResolvedString}")
  @Given("the Kubernetes deployment has rate-limit configuration {tigerResolvedString} for endpoint {tigerResolvedString}")
  public void verifyRateLimitConfiguredForEndpoint(String configuration, String endpoint) {
    var namespace = getNamespace();
    service.verifyRequirements(namespace);
    var result = service.executeKubectlCommand(
        "-n", namespace, "get", RATE_LIMIT_CONFIG_RESOURCE_TYPES, "-o", "json");

    if (result.exitCode() != 0) {
      throw new AssertionError("Could not read Kubernetes resources for rate-limit configuration in namespace '"
          + namespace + "': " + result.stderr());
    }

    var normalizedEndpoint = KubernetesRateLimitConfigurationEvidence.normalizeEndpointPath(endpoint);
    var normalizedConfiguration = KubernetesRateLimitConfigurationEvidence.normalizeConfigMarker(configuration);
    var evidence = KubernetesRateLimitConfigurationEvidence.findNormalized(
        result.stdout(), normalizedEndpoint, normalizedConfiguration);

    var reportLine = "NAMESPACE=" + namespace
        + " | RESOURCE_TYPES=" + RATE_LIMIT_CONFIG_RESOURCE_TYPES
        + "\nENDPOINT=" + normalizedEndpoint
        + "\nCONFIG=" + normalizedConfiguration
        + "\nEVIDENCE:\n" + evidence.orElse("<none>");

    SerenityReportUtils.addCustomData(
        "Kubernetes rate-limit configuration",
        reportLine);

    if (evidence.isEmpty()) {
      throw new AssertionError("Rate-limit configuration '" + normalizedConfiguration
          + "' is missing for endpoint '" + normalizedEndpoint + "'.");
    }
  }

  /**
   * Verifies that both deployed OPA configurations use the configured bundle polling interval.
   *
   * @param expectedSeconds expected polling interval in seconds
   */
  @Und("prüfe, dass die aktiven und simulierten OPA Bundle Polling Intervalle {tigerResolvedString} Sekunden "
      + "betragen")
  @And("verify that active and simulation OPA bundle polling intervals are {tigerResolvedString} seconds")
  public void verifyActiveAndSimulationOpaBundlePollingIntervals(String expectedSeconds) {
    var namespace = getNamespace();
    var expectedPollingSeconds = parseExpectedPollingIntervalSeconds(expectedSeconds);
    service.verifyRequirements(namespace);
    var evidence = new ArrayList<String>();
    var failures = new ArrayList<String>();

    verifyOpaBundlePollingConfigMap(namespace, ACTIVE_OPA_CONFIGMAP, expectedPollingSeconds, evidence, failures);
    verifyOpaBundlePollingConfigMap(namespace, SIMULATION_OPA_CONFIGMAP, expectedPollingSeconds, evidence, failures);

    var reportLine = "NAMESPACE=" + namespace
        + "\nEXPECTED_SECONDS=" + expectedPollingSeconds
        + "\n" + String.join("\n", evidence);
    SerenityReportUtils.addCustomData("OPA bundle polling configuration", reportLine);

    if (!failures.isEmpty()) {
      throw new AssertionError("OPA bundle polling configuration does not match the expected interval: "
          + String.join(" | ", failures));
    }
  }

  /**
   * Reads and verifies one OPA ConfigMap while collecting evidence and failures.
   *
   * @param namespace              Kubernetes namespace
   * @param configMapName          OPA ConfigMap name
   * @param expectedPollingSeconds expected polling interval in seconds
   * @param evidence               evidence lines for Serenity reporting
   * @param failures               failure messages to raise after all ConfigMaps were inspected
   */
  private void verifyOpaBundlePollingConfigMap(String namespace, String configMapName, int expectedPollingSeconds,
      List<String> evidence, List<String> failures) {
    try {
      var opaConfig = readOpaConfig(namespace, configMapName);
      evidence.add(opaBundlePollingEvidence(configMapName, opaConfig, expectedPollingSeconds));
    } catch (AssertionError error) {
      var failure = "CONFIGMAP=" + configMapName + " | FAILURE=" + error.getMessage();
      evidence.add(failure);
      failures.add(failure);
    }
  }

  /**
   * Parses the expected OPA polling interval.
   *
   * @param expectedSeconds expected polling interval in seconds as resolved by Tiger
   * @return expected polling interval in seconds
   */
  private static int parseExpectedPollingIntervalSeconds(String expectedSeconds) {
    try {
      return Integer.parseInt(expectedSeconds);
    } catch (NumberFormatException e) {
      throw new AssertionError("Configured OPA bundle polling interval must be an integer number of seconds but was '"
          + expectedSeconds + "'.", e);
    }
  }

  /**
   * Builds an evidence line for the bundle polling configuration and asserts the required interval.
   *
   * @param configMapName  ConfigMap name used as evidence source
   * @param opaConfigYaml  OPA YAML configuration
   * @param expectedSecond required polling interval in seconds
   * @return report line containing the observed polling values
   */
  static String opaBundlePollingEvidence(String configMapName, String opaConfigYaml, int expectedSecond) {
    var root = parseOpaYaml(configMapName, opaConfigYaml);
    var minDelay = requiredInteger(root, configMapName, "bundles.authz.polling.min_delay_seconds");
    var maxDelay = requiredInteger(root, configMapName, "bundles.authz.polling.max_delay_seconds");

    if (minDelay != expectedSecond || maxDelay != expectedSecond) {
      throw new AssertionError("OPA ConfigMap '" + configMapName + "' has bundle polling min_delay_seconds="
          + minDelay + " and max_delay_seconds=" + maxDelay + ", expected both to be " + expectedSecond + ".");
    }

    return "CONFIGMAP=" + configMapName
        + " | bundles.authz.polling.min_delay_seconds=" + minDelay
        + " | bundles.authz.polling.max_delay_seconds=" + maxDelay;
  }

  /**
   * Parses OPA YAML configuration into a JSON tree.
   *
   * @param configMapName ConfigMap name used for error messages
   * @param opaConfigYaml OPA YAML configuration
   * @return parsed YAML root node
   */
  private static JsonNode parseOpaYaml(String configMapName, String opaConfigYaml) {
    try {
      var root = YAML.readTree(opaConfigYaml);
      if (root == null || root.isMissingNode() || root.isNull()) {
        throw new AssertionError("OPA ConfigMap '" + configMapName + "' contains an empty opa.yaml.");
      }
      return root;
    } catch (JacksonException e) {
      throw new AssertionError("Could not parse opa.yaml from OPA ConfigMap '" + configMapName + "'.", e);
    }
  }

  /**
   * Reads a required integer field from the parsed OPA configuration.
   *
   * @param root          parsed OPA configuration
   * @param configMapName ConfigMap name used for error messages
   * @param dottedPath    dot-separated YAML field path
   * @return integer value at the requested path
   */
  private static int requiredInteger(JsonNode root, String configMapName, String dottedPath) {
    var current = root;
    for (var segment : dottedPath.split("\\.")) {
      current = current.path(segment);
      if (current.isMissingNode() || current.isNull()) {
        throw new AssertionError("Missing required OPA config field '" + dottedPath
            + "' in ConfigMap '" + configMapName + "'.");
      }
    }
    if (!current.isIntegralNumber()) {
      throw new AssertionError("OPA config field '" + dottedPath + "' in ConfigMap '" + configMapName
          + "' must be an integer but was '" + current.asText() + "'.");
    }
    return current.intValue();
  }

  /**
   * Verifies that the telemetry gateway exports traces asynchronously through a batch processor and an OTLP exporter.
   */
  @Und("prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Traces per Batch exportiert")
  @And("verify that the telemetry gateway collector configuration exports traces by batch")
  public void verifyTelemetryGatewayExportsTracesByBatch() {
    var namespace = getNamespace();
    var configMapName = findTelemetryGatewayConfigMapName(namespace);
    var relayConfig = readOpenTelemetryCollectorRelayConfig(namespace, configMapName);
    var batchConfigured = tracesPipelineUsesBatch(relayConfig);
    var otlpExporter = firstTraceOtlpExporter(relayConfig).orElse("");

    var reportLine = "CONFIGMAP=" + configMapName
        + " | TRACES_BATCH=" + batchConfigured
        + " | TRACES_OTLP_EXPORTER=" + (otlpExporter.isBlank() ? "<missing>" : otlpExporter)
        + "\nTRACES_PIPELINE:\n" + tracePipelineForReport(relayConfig);

    if (!batchConfigured || otlpExporter.isBlank()) {
      var msg = "Telemetry gateway ConfigMap '" + configMapName
          + "' must export traces with the batch processor and an OTLP exporter.";
      SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
    }

    SerenityReportUtils.addCustomData(
        "Telemetry gateway trace export configuration",
        reportLine);
  }

  /**
   * Verifies that the telemetry gateway forwards telemetry through at least one allowed OTLP protocol.
   */
  @Und("prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Telemetriedaten per erlaubtem OTLP Protokoll weitergibt")
  @And("verify that the telemetry gateway collector configuration forwards telemetry by an allowed OTLP protocol")
  public void verifyTelemetryGatewayForwardsTelemetryByAllowedOtlpProtocol() {
    var namespace = getNamespace();
    var configMapName = findTelemetryGatewayConfigMapName(namespace);
    var relayConfig = readOpenTelemetryCollectorRelayConfig(namespace, configMapName);
    var activeProtocols = activeOtlpForwardingProtocols(relayConfig);
    var otlpExporters = configuredOtlpExporters(relayConfig);
    var forwardingPipelines = pipelinesUsingAnyExporter(relayConfig, otlpExporters);
    var activeProtocolsReport = activeProtocols.isEmpty() ? "<missing>" : String.join(",", activeProtocols);
    var otlpExportersReport = otlpExporters.isEmpty() ? "<missing>" : String.join(",", otlpExporters);
    var forwardingPipelinesReport = forwardingPipelines.isEmpty() ? "<missing>" : String.join(",", forwardingPipelines);

    var reportLine = "CONFIGMAP=" + configMapName
        + " | ACTIVE_OTLP_PROTOCOLS=" + activeProtocolsReport
        + " | OTLP_EXPORTERS=" + otlpExportersReport
        + " | PIPELINES_USING_OTLP=" + forwardingPipelinesReport
        + "\nSERVICE_PIPELINES:\n" + servicePipelinesForReport(relayConfig);

    if (activeProtocols.isEmpty()) {
      var msg = "Telemetry gateway ConfigMap '" + configMapName
          + "' must reference an OTLP/gRPC exporter or an OTLP/HTTP exporter with JSON encoding in a service"
          + " pipeline.";
      SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
    }

    SerenityReportUtils.addCustomData(
        "Telemetry gateway active OTLP forwarding protocol",
        reportLine);
  }

  /**
   * Finds the telemetry gateway ConfigMap rendered by the zeta-guard Helm chart.
   *
   * @param namespace Kubernetes namespace
   * @return ConfigMap name
   */
  private String findTelemetryGatewayConfigMapName(String namespace) {
    service.verifyRequirements(namespace);
    var result = service.executeKubectlCommand(
        "-n", namespace, "get", "configmap", "-l", TELEMETRY_GATEWAY_CONFIGMAP_SELECTOR,
        "-o", "jsonpath={.items[*].metadata.name}");

    if (result.exitCode() != 0) {
      throw new AssertionError("Could not find telemetry gateway ConfigMap in namespace '" + namespace
          + "': " + result.stderr());
    }

    var configMapNamesOutput = result.stdout() == null ? "" : result.stdout();
    var names = Pattern.compile("\\s+").splitAsStream(configMapNamesOutput)
        .map(String::trim)
        .filter(name -> !name.isBlank())
        .toList();
    if (names.isEmpty()) {
      throw new AssertionError("No telemetry gateway ConfigMap found in namespace '" + namespace
          + "' with selector '" + TELEMETRY_GATEWAY_CONFIGMAP_SELECTOR + "'.");
    }
    if (names.size() > 1) {
      throw new AssertionError("Multiple telemetry gateway ConfigMaps found in namespace '" + namespace
          + "': " + String.join(", ", names));
    }
    return names.getFirst();
  }

  /**
   * Reads the OPA YAML configuration from a ConfigMap.
   *
   * @param namespace     Kubernetes namespace
   * @param configMapName name of the OPA ConfigMap
   * @return OPA configuration from `data.opa.yaml`
   */
  private String readOpaConfig(String namespace, String configMapName) {
    var result = service.executeKubectlCommand(
        "-n", namespace, "get", "configmap", configMapName, "-o", "json");

    if (result.exitCode() != 0) {
      throw new AssertionError("Could not read OPA ConfigMap '" + configMapName
          + "' in namespace '" + namespace + "': " + result.stderr());
    }

    return extractOpaConfigYaml(configMapName, result.stdout());
  }

  /**
   * Extracts the literal {@code opa.yaml} data entry from a ConfigMap JSON document.
   *
   * @param configMapName ConfigMap name used for error messages
   * @param configMapJson JSON returned by {@code kubectl get configmap ... -o json}
   * @return OPA configuration YAML
   */
  static String extractOpaConfigYaml(String configMapName, String configMapJson) {
    try {
      var root = JSON.readTree(configMapJson);
      var opaConfig = root.path("data").path("opa.yaml");
      if (opaConfig.isMissingNode() || opaConfig.isNull() || opaConfig.asText().isBlank()) {
        throw new AssertionError("OPA ConfigMap '" + configMapName + "' contains no data key 'opa.yaml'.");
      }
      return opaConfig.asText();
    } catch (JacksonException e) {
      throw new AssertionError("OPA ConfigMap '" + configMapName
          + "' could not be parsed as ConfigMap JSON.", e);
    }
  }

  /**
   * Reads the OpenTelemetry Collector relay configuration from a ConfigMap.
   *
   * @param namespace     Kubernetes namespace
   * @param configMapName name of the OpenTelemetry Collector ConfigMap
   * @return relay configuration from `data.relay`
   */
  private String readOpenTelemetryCollectorRelayConfig(String namespace, String configMapName) {
    var result = service.executeKubectlCommand(
        "-n", namespace, "get", "configmap", configMapName, "-o", OPENTELEMETRY_RELAY_JSONPATH);

    if (result.exitCode() != 0) {
      throw new AssertionError("Could not read OpenTelemetry Collector ConfigMap '" + configMapName
          + "' in namespace '" + namespace + "': " + result.stderr());
    }

    var relayConfig = result.stdout();
    if (relayConfig == null || relayConfig.isBlank()) {
      throw new AssertionError("OpenTelemetry Collector ConfigMap '" + configMapName
          + "' in namespace '" + namespace + "' has no data.relay content.");
    }
    return relayConfig;
  }

  /**
   * Reads the configured ZETA namespace.
   *
   * @return configured Kubernetes namespace
   */
  private String getNamespace() {
    var namespace = TigerGlobalConfiguration.readStringOptional(ZETA_NAMESPACE_CONFIG_KEY)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElse("");
    if (namespace.isBlank()) {
      throw new AssertionError("Missing configuration: " + ZETA_NAMESPACE_CONFIG_KEY);
    }
    return namespace;
  }
}
