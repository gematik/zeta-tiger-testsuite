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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.KubernetesRateLimitConfigurationEvidence;
import de.gematik.zeta.services.OtlpTracePipeline;
import de.gematik.zeta.services.TelemetryCollectorDiscovery;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
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
  private static final String ACTIVE_OPA_CONFIGMAP = "opa-config";
  private static final String SIMULATION_OPA_CONFIGMAP = "opa-simulation-config";
  private static final String RATE_LIMIT_CONFIG_RESOURCE_TYPES = "configmap,ingress";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final List<String> TELEMETRY_SIGNALS = List.of("logs", "metrics", "traces");
  private final ZetaDeploymentModificationService service = ZetaDeploymentConfiguration.getServiceInstance();

  /**
   * Resolves the named Service's current ClusterIP for an exact RBEL sender assertion.
   *
   * @param serviceName Kubernetes Service name
   * @param port required TCP Service port
   * @param variableName Tiger variable receiving the sender pattern
   */
  @Dann("ermittle das Sender-Muster für den Kubernetes Service {tigerResolvedString} auf Port {int} "
      + "und speichere in der Variable {tigerResolvedString}")
  @Then("resolve the sender pattern for Kubernetes Service {tigerResolvedString} on port {int} "
      + "and store it in variable {tigerResolvedString}")
  public void storeServiceSenderPattern(String serviceName, int port, String variableName) {
    var result = service.executeKubectlCommand(false, "-n", getNamespace(), "get", "service", serviceName,
        "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("Could not read Kubernetes Service '" + serviceName + "': " + result.stderr());
    }
    try {
      var pattern = serviceSenderPattern(JSON.readTree(result.stdout()), serviceName, port);
      TigerGlobalConfiguration.putValue(variableName, pattern, ConfigurationValuePrecedence.TEST_CONTEXT);
    } catch (JacksonException e) {
      throw new AssertionError("Could not parse Kubernetes Service '" + serviceName + "'", e);
    }
  }

  /**
   * Allows only the expected Service name or its live ClusterIP on the advertised TCP port.
   *
   * @param description live Kubernetes Service JSON
   * @param serviceName expected Kubernetes Service name
   * @param port required TCP Service port
   * @return anchored RBEL sender pattern
   */
  static String serviceSenderPattern(JsonNode description, String serviceName, int port) {
    if (description == null || !serviceName.equals(description.path("metadata").path("name").asText())) {
      throw new AssertionError("Kubernetes response does not identify Service '" + serviceName + "'");
    }
    var spec = description.path("spec");
    var clusterIp = spec.path("clusterIP").asText();
    if (clusterIp.isBlank() || "None".equals(clusterIp)) {
      throw new AssertionError("Kubernetes Service '" + serviceName + "' has no ClusterIP");
    }
    var tcpPortFound = false;
    for (var servicePort : spec.path("ports")) {
      if (servicePort.path("port").asInt() == port
          && "TCP".equals(servicePort.path("protocol").asText("TCP"))) {
        tcpPortFound = true;
      }
    }
    if (port < 1 || port > 65535 || !tcpPortFound) {
      throw new AssertionError("Kubernetes Service '" + serviceName + "' does not expose TCP port " + port);
    }
    return "\\A(?:" + Pattern.quote(serviceName) + "|" + Pattern.quote(clusterIp) + "):" + port + "\\z";
  }

  /**
   * Reads an explicit environment value from the selected running pod without changing Kubernetes resources.
   *
   * <p>Only literal pod environment entries are supported. Missing values, valueFrom references and
   * envFrom-only configuration fail rather than being interpreted as an empty allowlist.</p>
   *
   * @param environmentName name of the non-secret environment entry to record
   * @param containerName container in the selected pod
   * @param podName pod previously selected for the tested deployment
   * @param variableName Tiger variable receiving the literal string value
   */
  @Und("ermittle die konfigurierte Umgebungsvariable {tigerResolvedString} des Containers {tigerResolvedString} "
      + "im Pod {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  public void readConfiguredContainerEnvironment(String environmentName, String containerName,
      String podName, String variableName) {
    var namespace = getNamespace();
    var result = service.executeKubectlCommand(false, "-n", namespace, "get", "pod", podName, "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("Could not read pod configuration: " + result.stderr());
    }
    JsonNode pod;
    try {
      pod = JSON.readTree(result.stdout());
    } catch (JacksonException e) {
      throw new AssertionError("Could not parse pod configuration", e);
    }
    var value = literalContainerEnvironment(pod, containerName, environmentName);
    var context = service.executeKubectlCommand(false, "config", "current-context");
    if (context.exitCode() != 0 || context.stdout().isBlank()) {
      throw new AssertionError("Could not identify Kubernetes context: " + context.stderr());
    }
    SerenityReportUtils.addCustomData("Kubernetes container configuration: " + environmentName,
        "CONTEXT=" + context.stdout().strip() + " | NAMESPACE=" + namespace + " | POD=" + podName
            + " | UID=" + pod.path("metadata").path("uid").asText()
            + " | RESOURCE_VERSION=" + pod.path("metadata").path("resourceVersion").asText()
            + " | CONTAINER=" + containerName + "\n" + environmentName + "=" + value);
    TigerGlobalConfiguration.putValue(variableName, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Extracts a non-empty literal environment entry from a named, ready and running container.
   *
   * @param pod Kubernetes pod JSON
   * @param containerName exact container name
   * @param environmentName exact environment entry name
   * @return configured string, retaining CSV separators and whitespace
   * @throws AssertionError if the container is unavailable or the entry cannot be resolved literally
   */
  static String literalContainerEnvironment(JsonNode pod, String containerName, String environmentName) {
    if (pod == null || !"Running".equals(pod.path("status").path("phase").asText())
        || pod.path("metadata").hasNonNull("deletionTimestamp")) {
      throw new AssertionError("Selected pod is not running or is terminating");
    }
    var ready = false;
    for (var status : pod.path("status").path("containerStatuses")) {
      if (containerName.equals(status.path("name").asText())
          && status.path("ready").asBoolean(false) && status.path("state").has("running")) {
        ready = true;
      }
    }
    if (!ready) {
      throw new AssertionError("Selected container is not ready and running: " + containerName);
    }
    var values = new ArrayList<String>();
    for (var container : pod.path("spec").path("containers")) {
      if (!containerName.equals(container.path("name").asText())) {
        continue;
      }
      for (var entry : container.path("env")) {
        if (!environmentName.equals(entry.path("name").asText())) {
          continue;
        }
        if (entry.has("valueFrom") || !entry.path("value").isString()
            || entry.path("value").asText().isBlank() || entry.path("value").asText().contains("$(")) {
          throw new AssertionError("Environment entry must have a non-empty literal value: " + environmentName);
        }
        values.add(entry.path("value").asText());
      }
    }
    if (values.size() != 1) {
      throw new AssertionError("Expected exactly one explicit environment entry for " + containerName
          + "/" + environmentName + ", found " + values.size());
    }
    return values.getFirst();
  }

  /**
   * Resolves batch processors in every trace pipeline using the selected OTLP exporter.
   *
   * @param relayConfig OpenTelemetry Collector YAML configuration
   * @param exporterName configured exporter for the gematik telemetry destination
   * @return nonempty list of correlated pipeline, processor and exporter names
   * @throws AssertionError if the selected exporter or its batched trace pipelines are missing
   */
  static List<String> traceBatchExportEvidence(String relayConfig, String exporterName) {
    final JsonNode collector;
    try {
      collector = YAML.readTree(relayConfig);
    } catch (JacksonException failure) {
      throw new AssertionError("Invalid OpenTelemetry Collector YAML", failure);
    }
    if (collector == null || !collector.isObject()) {
      throw new AssertionError("Missing OpenTelemetry Collector configuration");
    }
    if (exporterName == null || !exporterName.matches("otlp(?:http|_grpc|_http)?(?:/[^/]+)?")) {
      throw new AssertionError("Expected one configured OTLP exporter for the gematik telemetry destination");
    }
    var declaration = collector.path("exporters").path(exporterName);
    if (!declaration.isObject() || !declaration.path("endpoint").isTextual()
        || declaration.path("endpoint").asText().isBlank()) {
      throw new AssertionError("Missing OTLP exporter endpoint: " + exporterName);
    }
    var evidence = new ArrayList<String>();
    var pipelines = collector.path("service").path("pipelines");
    for (var name : pipelines.propertyNames()) {
      if (!name.equals("traces") && !name.startsWith("traces/")) {
        continue;
      }
      var pipeline = pipelines.path(name);
      var usesExporter = false;
      for (var exporter : pipeline.path("exporters")) {
        usesExporter |= exporterName.equals(exporter.asText());
      }
      if (!usesExporter) {
        continue;
      }
      var batches = new ArrayList<String>();
      for (var processor : pipeline.path("processors")) {
        var id = processor.asText();
        var processorConfig = collector.path("processors").path(id);
        if (id.matches("batch(?:/[^/]+)?") && collector.path("processors").has(id)
            && (processorConfig.isObject() || processorConfig.isNull())) {
          batches.add(id);
        }
      }
      if (batches.isEmpty()) {
        throw new AssertionError("OTLP trace pipeline '" + name + "' has no declared batch processor");
      }
      evidence.add(name + " | BATCH=" + batches + " | OTLP=" + List.of(exporterName));
    }
    if (evidence.isEmpty()) {
      throw new AssertionError("No trace pipeline references the selected OTLP exporter: " + exporterName);
    }
    return List.copyOf(evidence);
  }

  /**
   * Verifies batching for every trace pipeline sharing the selected pipeline's OTLP destinations.
   *
   * @param relayConfig collector configuration
   * @param pipelineName trace pipeline assigned to the gematik telemetry destination
   * @return correlated trace pipeline, batch processor and exporter evidence
   * @throws AssertionError if a destination or its batching configuration is missing
   */
  static List<String> tracePipelineBatchExportEvidence(String relayConfig, String pipelineName) {
    final JsonNode collector;
    try {
      collector = YAML.readTree(relayConfig);
    } catch (JacksonException failure) {
      throw new AssertionError("Invalid OpenTelemetry Collector YAML", failure);
    }
    return OtlpTracePipeline.exporters(collector, pipelineName).stream()
        .flatMap(exporter -> traceBatchExportEvidence(relayConfig, exporter).stream())
        .distinct()
        .toList();
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
   * Verifies batching on trace pipelines using the configured gematik pipeline's OTLP destinations.
   *
   * @param pipelineName trace pipeline assigned to the gematik telemetry destination
   */
  @Dann("prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Traces aus Pipeline {tigerResolvedString} per Batch über OTLP exportiert")
  @Then("verify that the telemetry gateway collector configuration exports traces from pipeline {tigerResolvedString} by batch through OTLP")
  public void verifyTelemetryGatewayExportsTracesByBatch(String pipelineName) {
    var namespace = getNamespace();
    var configMapName = TelemetryCollectorDiscovery.gatewayConfigMap(service, namespace);
    var relayConfig = TelemetryCollectorDiscovery.relayConfig(service, namespace, configMapName);
    var pipelines = tracePipelineBatchExportEvidence(relayConfig, pipelineName);
    SerenityReportUtils.addCustomData(
        "Telemetry gateway trace export configuration",
        "CONFIGMAP=" + configMapName + " | SELECTED_PIPELINE=" + pipelineName
            + "\n" + String.join("\n", pipelines));
  }

  /**
   * Verifies that the telemetry gateway forwards telemetry through at least one allowed OTLP protocol.
   */
  @Und("prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Telemetriedaten per erlaubtem OTLP Protokoll weitergibt")
  @And("verify that the telemetry gateway collector configuration forwards telemetry by an allowed OTLP protocol")
  public void verifyTelemetryGatewayForwardsTelemetryByAllowedOtlpProtocol() {
    var namespace = getNamespace();
    var configMapName = TelemetryCollectorDiscovery.gatewayConfigMap(service, namespace);
    var relayConfig = TelemetryCollectorDiscovery.relayConfig(service, namespace, configMapName);
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
