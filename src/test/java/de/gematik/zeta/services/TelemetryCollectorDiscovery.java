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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/** Resolves a gateway and its in-namespace HTTP OTLP receiver from Kubernetes resources. */
public final class TelemetryCollectorDiscovery {
  private static final String GATEWAY_SELECTOR =
      "app.kubernetes.io/name=telemetry-gateway,app.kubernetes.io/component=standalone-collector";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** Prevents construction of the discovery utility. */
  private TelemetryCollectorDiscovery() {
  }

  /**
   * Identifies the resources and receiver belonging to one capture target.
   *
   * @param deployment collector Deployment name
   * @param configMap mounted collector ConfigMap name
   * @param receiver existing OTLP receiver identifier
   */
  public record CaptureTarget(String deployment, String configMap, String receiver) {
  }

  /**
   * Finds exactly one telemetry gateway ConfigMap using the chart's labels.
   *
   * @param service Kubernetes command adapter
   * @param namespace selected namespace
   * @return gateway ConfigMap name
   */
  public static String gatewayConfigMap(ZetaDeploymentModificationService service, String namespace) {
    var maps = read(service, namespace, "configmap", "-l", GATEWAY_SELECTOR).path("items");
    require(maps.isArray() && maps.size() == 1,
        "Expected exactly one telemetry gateway ConfigMap in namespace " + namespace);
    var name = maps.get(0).path("metadata").path("name").asString();
    require(!name.isBlank(), "Telemetry gateway ConfigMap has no name");
    return name;
  }

  /**
   * Reads the literal relay configuration from one selected ConfigMap.
   *
   * @param service Kubernetes command adapter
   * @param namespace selected namespace
   * @param configMap selected ConfigMap name
   * @return nonblank relay configuration without normalization
   */
  public static String relayConfig(ZetaDeploymentModificationService service, String namespace, String configMap) {
    var relay = read(service, namespace, "configmap", configMap).path("data").path("relay");
    require(relay.isString() && !relay.asString().isBlank(), "ConfigMap " + configMap + " has no relay configuration");
    return relay.asString();
  }

  /**
   * Follows the selected pipeline through its Service, Deployment and mounted receiver config.
   *
   * @param service Kubernetes command adapter
   * @param namespace selected namespace
   * @param gateway gateway collector configuration
   * @param pipeline selected trace pipeline
   * @return unambiguous existing capture target
   */
  public static CaptureTarget captureTarget(ZetaDeploymentModificationService service,
      String namespace, JsonNode gateway, String pipeline) {
    var exporters = OtlpTracePipeline.exporters(gateway, pipeline);
    require(gateway.path("service").path("pipelines").path(pipeline).path("exporters").size() == 1,
        "Raw capture requires exactly one pipeline exporter");
    var exporter = exporters.getFirst();
    require(exporter.matches("otlp(?:http|_http)(?:/[^/]+)?"),
        "Raw capture requires an HTTP OTLP exporter");
    var config = gateway.path("exporters").path(exporter);
    require(config.path("traces_endpoint").asString().isBlank(),
        "Raw capture does not support a separate traces_endpoint override");
    var endpoint = endpoint(config.path("endpoint").asString());
    var serviceName = serviceName(endpoint.getHost(), namespace);
    var description = read(service, namespace, "service", serviceName);
    require(serviceName.equals(description.path("metadata").path("name").asString())
        && namespace.equals(description.path("metadata").path("namespace").asString()),
        "OTLP Service does not belong to the selected namespace");
    var selector = description.path("spec").path("selector");
    require(selector.isObject() && !selector.isEmpty()
        && !"ExternalName".equals(description.path("spec").path("type").asString()),
        "Raw capture requires a Service with a pod selector");
    var matches = new ArrayList<JsonNode>();
    for (var deployment : read(service, namespace, "deployment").path("items")) {
      var labels = deployment.at("/spec/template/metadata/labels");
      if (selector.propertyNames().stream().allMatch(key -> selector.path(key).equals(labels.path(key)))) {
        matches.add(deployment);
      }
    }
    require(matches.size() == 1, "OTLP Service must select exactly one collector Deployment");
    var deployment = matches.getFirst();
    var containers = deployment.at("/spec/template/spec/containers");
    require(containers.isArray() && containers.size() == 1,
        "Raw capture requires one collector container");
    var container = containers.get(0);
    int port = targetPort(description, endpoint.getPort() == -1 ? 80 : endpoint.getPort(), container);
    var mapName = mountedConfigMap(deployment, container);
    var relay = YAML.readTree(relayConfig(service, namespace, mapName));
    require(relay != null && relay.isObject(), "Mounted collector relay configuration is missing");
    var receivers = new ArrayList<String>();
    for (var name : relay.path("receivers").propertyNames()) {
      var http = relay.path("receivers").path(name).path("protocols").path("http");
      if (name.matches("otlp(?:/[^/]+)?") && !http.hasNonNull("tls")
          && http.path("endpoint").asString().endsWith(":" + port)) {
        receivers.add(name);
      }
    }
    require(receivers.size() == 1, "Service target port must identify exactly one HTTP OTLP receiver");
    var receiver = receivers.getFirst();
    require(relay.path("service").path("pipelines").propertyNames().stream()
        .filter(name -> name.equals("traces") || name.startsWith("traces/"))
        .anyMatch(name -> relay.path("service").path("pipelines").path(name).path("receivers")
            .valueStream().anyMatch(value -> receiver.equals(value.asString()))),
        "Capture receiver is not used by an existing trace pipeline");
    return new CaptureTarget(deployment.path("metadata").path("name").asString(), mapName, receiver);
  }

  /** Parses an HTTP base endpoint without credentials or per-request URL components. */
  private static URI endpoint(String value) {
    final URI endpoint;
    try {
      endpoint = URI.create(value);
    } catch (IllegalArgumentException error) {
      throw new AssertionError("Invalid HTTP OTLP capture endpoint");
    }
    require("http".equalsIgnoreCase(endpoint.getScheme()) && endpoint.getHost() != null
        && endpoint.getUserInfo() == null && endpoint.getQuery() == null && endpoint.getFragment() == null
        && (endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
        && (endpoint.getPort() == -1 || endpoint.getPort() > 0 && endpoint.getPort() <= 65535),
        "Raw capture requires an HTTP Service base endpoint");
    return endpoint;
  }

  /** Resolves short and standard namespace-qualified Kubernetes Service DNS names. */
  private static String serviceName(String host, String namespace) {
    var name = host.toLowerCase(Locale.ROOT);
    if (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    var pattern = Pattern.compile("([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\."
        + Pattern.quote(namespace) + "(?:\\.svc(?:\\.cluster\\.local)?)?)?");
    var match = pattern.matcher(name);
    require(match.matches(), "OTLP endpoint is not a Service in namespace " + namespace);
    return match.group(1);
  }

  /** Resolves the Service's unique TCP port, including named container target ports. */
  private static int targetPort(JsonNode service, int port, JsonNode container) {
    var ports = service.path("spec").path("ports").valueStream()
        .filter(value -> value.path("port").asInt() == port
            && "TCP".equals(value.path("protocol").asString("TCP"))).toList();
    require(ports.size() == 1, "OTLP endpoint port is not a unique TCP Service port");
    var target = ports.getFirst().path("targetPort");
    int result;
    if (target.isMissingNode()) {
      result = port;
    } else if (target.isIntegralNumber()) {
      result = target.asInt();
    } else {
      var matches = container.path("ports").valueStream()
          .filter(value -> target.asString().equals(value.path("name").asString())
              && "TCP".equals(value.path("protocol").asString("TCP"))).toList();
      require(matches.size() == 1, "Named OTLP Service target port is ambiguous or missing");
      result = matches.getFirst().path("containerPort").asInt();
    }
    require(result > 0 && result <= 65535, "Invalid OTLP Service target port");
    return result;
  }

  /** Resolves the mounted relay.yaml file to its source ConfigMap. */
  private static String mountedConfigMap(JsonNode deployment, JsonNode container) {
    require(container.path("args").valueStream()
        .anyMatch(value -> "--config=/conf/relay.yaml".equals(value.asString())),
        "Unsupported collector config path");
    var mounts = container.path("volumeMounts").valueStream()
        .filter(value -> "/conf".equals(value.path("mountPath").asString())).toList();
    require(mounts.size() == 1 && !mounts.getFirst().has("subPath") && !mounts.getFirst().has("subPathExpr"),
        "Collector requires an unambiguous ConfigMap directory mount");
    var volumes = deployment.at("/spec/template/spec/volumes").valueStream()
        .filter(value -> mounts.getFirst().path("name").equals(value.path("name"))).toList();
    require(volumes.size() == 1, "Collector config volume is ambiguous or missing");
    var map = volumes.getFirst().path("configMap");
    var name = map.path("name").asString();
    require(!name.isBlank() && map.path("items").valueStream().anyMatch(value ->
        "relay".equals(value.path("key").asString()) && "relay.yaml".equals(value.path("path").asString())),
        "Collector relay.yaml must be projected from a ConfigMap relay key");
    return name;
  }

  /** Reads Kubernetes JSON without modifying the selected resources. */
  private static JsonNode read(ZetaDeploymentModificationService service, String namespace, String... resource) {
    var command = new ArrayList<>(List.of("-n", namespace, "get"));
    command.addAll(List.of(resource));
    command.addAll(List.of("-o", "json"));
    var result = service.executeKubectlCommand(false, command);
    require(result.exitCode() == 0, "Cannot inspect telemetry " + resource[0] + " in namespace " + namespace);
    return JSON.readTree(result.stdout());
  }

  /** Rejects missing or ambiguous resource relationships. */
  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
