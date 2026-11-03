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

import static java.nio.charset.StandardCharsets.UTF_8;

import de.gematik.zeta.services.model.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Temporarily routes performance traffic around the standalone TigerProxy. */
@Slf4j
public final class PerformanceIngressRoutingService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String WEBSOCKET_SERVICES_ANNOTATION = "nginx.org/websocket-services";
  private static final String SSL_SERVICES_ANNOTATION = "nginx.org/ssl-services";
  private static final String HTTPS_CONTAINER_PORT = "https-port";
  private static final String SMCB_PROXY_PATH = "/(td-smcb/proxy)(/|$)(.*)";
  private static final String SMCB_DIRECT_PATH = "/td-smcb/(proxy)(/|$)(.*)";
  private static final String PEP_CONFIG_MAP = "pep-test-nginx-conf";
  private static final String PEP_CONFIG_KEY = "nginx.conf";
  private static final String PEP_DEPLOYMENT = "pep-deployment";
  private static final String PEP_NGINX_CONTAINER = "nginx";
  private static final int PEP_ROLLOUT_TIMEOUT_SECONDS = 300;
  private static final Pattern TIGER_TESTFACHDIENST_URL = Pattern.compile(
      "https?://tiger-proxy(?::\\d+)?/testfachdienst");
  private static final Pattern TIGER_POPP_URL = Pattern.compile(
      "https?://tiger-proxy(?::\\d+)?/popp");
  private static final List<RouteDefinition> ROUTES = List.of(
      new RouteDefinition(
          "zeta-guard-pep", "/", "pep-proxy-svc", "http",
          Map.of(WEBSOCKET_SERVICES_ANNOTATION, "pep-proxy-svc")),
      new RouteDefinition(
          "zeta-guard-auth", "/auth", "authserver", null, Map.of()),
      new RouteDefinition(
          "testdriver-smcb", SMCB_DIRECT_PATH, "testdriver-smcb", "http",
          Map.of(WEBSOCKET_SERVICES_ANNOTATION, "testdriver-smcb")));

  private final ZetaDeploymentModificationService deploymentService;
  private final PerformanceRoutingJournal journal;

  /**
   * Creates a routing service backed by the shared kubectl command service.
   *
   * @param deploymentService kubectl command service
   */
  public PerformanceIngressRoutingService(
      final ZetaDeploymentModificationService deploymentService) {
    this.deploymentService = Objects.requireNonNull(
        deploymentService, "deploymentService must not be null");
    journal = new PerformanceRoutingJournal(deploymentService);
  }

  /**
   * Captures all affected routes and changes them to their direct backends.
   *
   * @param namespace Kubernetes namespace
   * @return snapshot required to restore the exact original state
   */
  public RoutingSnapshot enableDirectRouting(final String namespace) {
    requireNamespace(namespace);
    recoverAbandonedRouting(namespace);
    var directRoutes = resolveDirectRoutes(namespace);
    var capturedRoutes = directRoutes.stream()
        .map(route -> captureRoute(namespace, route))
        .toList();
    var capturedPepConfiguration = capturePepConfiguration(namespace);
    var snapshot = new RoutingSnapshot(namespace, capturedRoutes, capturedPepConfiguration);
    journal.begin(namespace, encodeSnapshot(snapshot));

    try {
      for (int index = 0; index < capturedRoutes.size(); index++) {
        applyDirectRoute(namespace, capturedRoutes.get(index), directRoutes.get(index));
      }
      applyDirectPepConfiguration(namespace, capturedPepConfiguration);
      verifyDirectRouting(namespace, capturedRoutes);
      verifyDirectPepConfiguration(namespace, capturedPepConfiguration);
      log.info("Performance routing in namespace '{}' now bypasses TigerProxy.", namespace);
      return snapshot;
    } catch (AssertionError | RuntimeException failure) {
      try {
        restore(snapshot);
      } catch (AssertionError | RuntimeException restoreFailure) {
        failure.addSuppressed(restoreFailure);
      }
      throw failure;
    }
  }

  /**
   * Repairs an interrupted performance run before any scenario sends traffic.
   *
   * @param namespace Kubernetes namespace
   */
  public void recoverAbandonedRouting(final String namespace) {
    requireNamespace(namespace);
    journal.recover(namespace, saved -> restoreState(decodeSnapshot(namespace, saved)));
  }

  /** Leaves the routing backup recoverable when dependent deployment cleanup must be retried first. */
  public void deferRestoration() {
    journal.release();
  }

  /**
   * Restores all captured ingress and PEP configuration state.
   *
   * @param snapshot original routing state
   */
  public void restore(final RoutingSnapshot snapshot) {
    try {
      journal.assertOwned(snapshot.namespace());
      restoreState(snapshot);
      journal.complete(snapshot.namespace());
    } finally {
      journal.release();
    }
  }

  /**
   * Restores only state still attributable to this snapshot, retaining conflicting changes.
   *
   * @param snapshot original routing state
   */
  private void restoreState(final RoutingSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    validateRestorableState(snapshot);
    AssertionError combinedFailure = null;

    try {
      restorePepConfiguration(snapshot.namespace(), snapshot.pepConfiguration());
    } catch (AssertionError | RuntimeException failure) {
      combinedFailure = new AssertionError(
          "Could not fully restore performance routing in namespace '"
              + snapshot.namespace() + "'.");
      combinedFailure.addSuppressed(failure);
    }

    for (var route : snapshot.routes()) {
      try {
        restoreRoute(snapshot.namespace(), route);
      } catch (AssertionError | RuntimeException failure) {
        if (combinedFailure == null) {
          combinedFailure = new AssertionError(
              "Could not fully restore performance routing in namespace '"
                  + snapshot.namespace() + "'.");
        }
        combinedFailure.addSuppressed(failure);
      }
    }

    if (combinedFailure != null) {
      throw combinedFailure;
    }
    verifyRestoredRouting(snapshot);
    verifyRestoredPepConfiguration(snapshot.namespace(), snapshot.pepConfiguration());
    log.info("Restored original performance routing in namespace '{}'.", snapshot.namespace());
  }

  /**
   * Resolves environment-dependent direct route details.
   *
   * @param namespace Kubernetes namespace
   * @return direct route definitions
   */
  private List<RouteDefinition> resolveDirectRoutes(final String namespace) {
    var authPort = authserverUsesHttps(namespace) ? "https" : "http";
    var authAnnotations = "https".equals(authPort)
        ? Map.of(SSL_SERVICES_ANNOTATION, "authserver")
        : Map.<String, String>of();

    return ROUTES.stream()
        .map(route -> "zeta-guard-auth".equals(route.ingressName())
            ? new RouteDefinition(
                route.ingressName(), route.path(), route.serviceName(), authPort,
                authAnnotations)
            : route)
        .toList();
  }

  /**
   * Detects whether the active authserver exposes its HTTPS container port.
   *
   * @param namespace Kubernetes namespace
   * @return {@code true} when the authserver HTTPS port is active
   */
  private boolean authserverUsesHttps(final String namespace) {
    var deployment = readResource(namespace, "deployment", "authserver");
    var containers = deployment.path("spec").path("template").path("spec").path("containers");
    for (var container : containers) {
      for (var port : container.path("ports")) {
        if (HTTPS_CONTAINER_PORT.equals(port.path("name").asText())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Captures the backend and relevant annotations of one ingress path.
   *
   * @param namespace Kubernetes namespace
   * @param route route definition
   * @return captured route
   */
  private CapturedRoute captureRoute(final String namespace, final RouteDefinition route) {
    var ingress = readResource(namespace, "ingress", route.ingressName());
    var pathLocation = "testdriver-smcb".equals(route.ingressName())
        ? findPath(ingress, route.ingressName(), route.path(), SMCB_PROXY_PATH)
        : findPath(ingress, route.ingressName(), route.path());
    var service = pathLocation.pathNode().path("backend").path("service");
    if (!service.isObject() || service.path("name").asText().isBlank()) {
      throw new AssertionError("Ingress '" + route.ingressName() + "' path '"
          + route.path() + "' has no service backend.");
    }

    var annotationKeys = relevantAnnotationKeys(route.ingressName());
    var annotations = ingress.path("metadata").path("annotations");
    var capturedAnnotations = annotationKeys.stream()
        .map(key -> new CapturedAnnotation(
            key,
            annotations.has(key) ? Optional.of(annotations.path(key).asText()) : Optional.empty()))
        .toList();
    return new CapturedRoute(
        route.ingressName(), pathLocation.pathNode().path("path").asText(), pathLocation.jsonPointer(), service.deepCopy(),
        capturedAnnotations, ingress.path("metadata").path("uid").asText(),
        serviceBackend(route.serviceName(), route.servicePortName()), directAnnotations(route, annotations), route.path());
  }

  /**
   * Preserves WebSocket services used by sibling SMC-B paths, including the legacy Tiger-backed proxy.
   *
   * @param route direct route definition
   * @param annotations current ingress annotations
   * @return annotations for the temporary direct route
   */
  private Map<String, String> directAnnotations(final RouteDefinition route, final JsonNode annotations) {
    if (!"testdriver-smcb".equals(route.ingressName())) {
      return route.annotations();
    }
    var originalServices = annotations.path(WEBSOCKET_SERVICES_ANNOTATION).asText();
    if (originalServices.isBlank()) {
      return route.annotations();
    }
    var directService = route.annotations().get(WEBSOCKET_SERVICES_ANNOTATION);
    var alreadyEnabled = Arrays.stream(originalServices.split(","))
        .map(String::trim).anyMatch(directService::equals);
    return Map.of(WEBSOCKET_SERVICES_ANNOTATION,
        alreadyEnabled ? originalServices : originalServices + "," + directService);
  }

  /**
   * Captures the complete PEP nginx configuration and prepares its direct variant.
   *
   * @param namespace Kubernetes namespace
   * @return original and direct PEP nginx configuration
   */
  private CapturedPepConfiguration capturePepConfiguration(final String namespace) {
    var configMap = readResource(namespace, "configmap", PEP_CONFIG_MAP);
    var originalValue = configMap.path("data").path(PEP_CONFIG_KEY).asText();
    if (originalValue.isBlank()) {
      throw new AssertionError(
          "ConfigMap '" + PEP_CONFIG_MAP + "' has no non-empty '" + PEP_CONFIG_KEY + "' value.");
    }

    var directValue = TIGER_TESTFACHDIENST_URL.matcher(originalValue)
        .replaceAll("https://testfachdienst:443");
    directValue = TIGER_POPP_URL.matcher(directValue).replaceAll("http://popp-statics");
    return new CapturedPepConfiguration(originalValue, directValue,
        configMap.path("metadata").path("uid").asText());
  }

  /**
   * Returns annotation keys whose values are changed by one ingress route.
   *
   * @param ingressName ingress resource name
   * @return relevant annotation keys
   */
  private List<String> relevantAnnotationKeys(final String ingressName) {
    return switch (ingressName) {
      case "zeta-guard-pep", "testdriver-smcb" -> List.of(WEBSOCKET_SERVICES_ANNOTATION);
      case "zeta-guard-auth" -> List.of(SSL_SERVICES_ANNOTATION);
      default -> throw new IllegalArgumentException("Unsupported performance ingress: " + ingressName);
    };
  }

  /**
   * Applies one direct backend and its required annotations.
   *
   * @param namespace Kubernetes namespace
   * @param capturedRoute captured route with its JSON pointer
   * @param directRoute direct route definition
   */
  private void applyDirectRoute(
      final String namespace,
      final CapturedRoute capturedRoute,
      final RouteDefinition directRoute) {
    var current = readResource(namespace, "ingress", capturedRoute.ingressName());
    validateRoute(current, capturedRoute, false);
    var patch = guardedPatch(current);
    patch.add(replaceOperation(capturedRoute.jsonPointer() + "/path", JSON.valueToTree(capturedRoute.directPath())));
    patch.add(replaceOperation(
        capturedRoute.jsonPointer() + "/backend/service",
        serviceBackend(directRoute.serviceName(), directRoute.servicePortName())));

    for (var annotation : capturedRoute.annotations()) {
      var directValue = capturedRoute.directAnnotations().get(annotation.key());
      if (directValue != null) {
        patch.add(annotationOperation("add", annotation.key(), directValue));
      } else if (annotation.value().isPresent()) {
        patch.add(annotationOperation("remove", annotation.key(), null));
      }
    }
    patchIngress(namespace, capturedRoute.ingressName(), patch);
  }

  /**
   * Applies and activates the direct PEP upstream configuration when a TigerProxy target exists.
   *
   * @param namespace Kubernetes namespace
   * @param configuration captured original and prepared direct configuration
   */
  private void applyDirectPepConfiguration(
      final String namespace,
      final CapturedPepConfiguration configuration) {
    if (!configuration.requiresChange()) {
      return;
    }
    patchPepConfiguration(namespace, configuration, configuration.directValue());
    restartPepDeployment(namespace);
  }

  /**
   * Restores one captured ingress route.
   *
   * @param namespace Kubernetes namespace
   * @param route captured route
   */
  private void restoreRoute(final String namespace, final CapturedRoute route) {
    var currentIngress = readResource(namespace, "ingress", route.ingressName());
    validateRoute(currentIngress, route, true);
    var currentAnnotations = currentIngress.path("metadata").path("annotations");
    var patch = guardedPatch(currentIngress);
    patch.add(replaceOperation(route.jsonPointer() + "/path", JSON.valueToTree(route.path())));
    patch.add(replaceOperation(route.jsonPointer() + "/backend/service", route.backendService()));

    for (var annotation : route.annotations()) {
      if (annotation.value().isPresent()) {
        patch.add(annotationOperation("add", annotation.key(), annotation.value().orElseThrow()));
      } else if (currentAnnotations.has(annotation.key())) {
        patch.add(annotationOperation("remove", annotation.key(), null));
      }
    }
    patchIngress(namespace, route.ingressName(), patch);
  }

  /**
   * Restores and activates the exact original PEP nginx configuration.
   *
   * @param namespace Kubernetes namespace
   * @param configuration captured PEP configuration
   */
  private void restorePepConfiguration(
      final String namespace,
      final CapturedPepConfiguration configuration) {
    if (!configuration.requiresChange()) {
      return;
    }
    patchPepConfiguration(namespace, configuration, configuration.originalValue());
    restartPepDeployment(namespace);
  }

  /**
   * Verifies all direct routes from fresh Kubernetes state.
   *
   * @param namespace Kubernetes namespace
   * @param routes expected direct routes
   */
  private void verifyDirectRouting(final String namespace, final List<CapturedRoute> routes) {
    for (var route : routes) {
      var ingress = readResource(namespace, "ingress", route.ingressName());
      var path = findPath(ingress, route.ingressName(), route.directPath()).pathNode();
      var actualService = path.path("backend").path("service");
      var expectedService = route.directBackend();
      if (!expectedService.equals(actualService)) {
        throw new AssertionError("Ingress '" + route.ingressName() + "' path '" + route.directPath()
            + "' does not use expected direct backend " + expectedService + ".");
      }
      verifyAnnotations(ingress, route.ingressName(), route.directAnnotations());
    }
  }

  /**
   * Verifies both stored and active PEP configuration use direct internal services.
   *
   * @param namespace Kubernetes namespace
   * @param configuration expected direct configuration
   */
  private void verifyDirectPepConfiguration(
      final String namespace,
      final CapturedPepConfiguration configuration) {
    verifyPepConfiguration(namespace, configuration.directValue(), "direct");
    if (TIGER_TESTFACHDIENST_URL.matcher(configuration.directValue()).find()
        || TIGER_POPP_URL.matcher(configuration.directValue()).find()) {
      throw new AssertionError("Prepared direct PEP configuration still references TigerProxy.");
    }
  }

  /**
   * Verifies the restored routes against their snapshot.
   *
   * @param snapshot original routing snapshot
   */
  private void verifyRestoredRouting(final RoutingSnapshot snapshot) {
    for (var route : snapshot.routes()) {
      var ingress = readResource(snapshot.namespace(), "ingress", route.ingressName());
      var path = findPath(ingress, route.ingressName(), route.path()).pathNode();
      if (!route.backendService().equals(path.path("backend").path("service"))) {
        throw new AssertionError("Ingress '" + route.ingressName() + "' path '" + route.path()
            + "' was not restored to its original backend.");
      }
      var expectedAnnotations = route.annotations().stream()
          .filter(annotation -> annotation.value().isPresent())
          .collect(java.util.stream.Collectors.toMap(
              CapturedAnnotation::key,
              annotation -> annotation.value().orElseThrow()));
      verifyAnnotations(ingress, route.ingressName(), expectedAnnotations);
      var annotations = ingress.path("metadata").path("annotations");
      route.annotations().stream()
          .filter(annotation -> annotation.value().isEmpty())
          .filter(annotation -> annotations.has(annotation.key()))
          .findFirst()
          .ifPresent(annotation -> {
            throw new AssertionError("Ingress '" + route.ingressName()
                + "' still has temporary annotation '" + annotation.key() + "'.");
          });
    }
  }

  /**
   * Verifies both stored and active PEP configuration equal the captured original.
   *
   * @param namespace Kubernetes namespace
   * @param configuration captured PEP configuration
   */
  private void verifyRestoredPepConfiguration(
      final String namespace,
      final CapturedPepConfiguration configuration) {
    verifyPepConfiguration(namespace, configuration.originalValue(), "restored");
  }

  /**
   * Verifies the ConfigMap and the nginx container expose the expected configuration.
   *
   * @param namespace Kubernetes namespace
   * @param expected expected complete nginx configuration
   * @param state diagnostic state name
   */
  private void verifyPepConfiguration(
      final String namespace,
      final String expected,
      final String state) {
    var configMap = readResource(namespace, "configmap", PEP_CONFIG_MAP);
    var stored = configMap.path("data").path(PEP_CONFIG_KEY).asText();
    if (!expected.equals(stored)) {
      throw new AssertionError("PEP ConfigMap does not contain the expected " + state + " configuration.");
    }
    var active = readActivePepConfiguration(namespace);
    if (!normalizeActiveConfiguration(expected).equals(normalizeActiveConfiguration(active))) {
      throw new AssertionError("PEP nginx container does not use the expected " + state + " configuration.");
    }
  }

  /**
   * Normalizes line endings and the trailing newline stripped by command output collection.
   *
   * @param configuration nginx configuration text
   * @return normalized configuration
   */
  private String normalizeActiveConfiguration(final String configuration) {
    return configuration.replace("\r\n", "\n").replace('\r', '\n').replaceFirst("\n\\z", "");
  }

  /**
   * Verifies expected ingress annotations.
   *
   * @param ingress current ingress JSON
   * @param ingressName ingress name for diagnostics
   * @param expected expected annotation values
   */
  private void verifyAnnotations(
      final JsonNode ingress,
      final String ingressName,
      final Map<String, String> expected) {
    var annotations = ingress.path("metadata").path("annotations");
    expected.forEach((key, value) -> {
      if (!value.equals(annotations.path(key).asText())) {
        throw new AssertionError("Ingress '" + ingressName + "' annotation '" + key
            + "' is not set to expected value '" + value + "'.");
      }
    });
  }

  /**
   * Locates one ingress path and returns its JSON pointer.
   *
   * @param ingress ingress JSON
   * @param ingressName ingress name for diagnostics
   * @param expectedPaths accepted ingress paths
   * @return path location
   */
  private PathLocation findPath(
      final JsonNode ingress,
      final String ingressName,
      final String... expectedPaths) {
    var rules = ingress.path("spec").path("rules");
    for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
      var paths = rules.get(ruleIndex).path("http").path("paths");
      for (int pathIndex = 0; pathIndex < paths.size(); pathIndex++) {
        var path = paths.get(pathIndex);
        if (List.of(expectedPaths).contains(path.path("path").asText())) {
          return new PathLocation(
              "/spec/rules/" + ruleIndex + "/http/paths/" + pathIndex,
              path);
        }
      }
    }
    throw new AssertionError("Ingress '" + ingressName + "' has none of the paths '"
        + String.join("', '", expectedPaths) + "'.");
  }

  /**
   * Reads one Kubernetes resource as JSON.
   *
   * @param namespace Kubernetes namespace
   * @param kind resource kind
   * @param name resource name
   * @return parsed resource JSON
   */
  private JsonNode readResource(
      final String namespace,
      final String kind,
      final String name) {
    var result = deploymentService.executeKubectlCommand(
        "-n", namespace, "get", kind, name, "-o", "json");
    requireSuccess(result, "read " + kind + "/" + name);
    try {
      return JSON.readTree(result.stdout());
    } catch (JacksonException exception) {
      throw new AssertionError("Could not parse kubectl JSON for " + kind + "/" + name + ".", exception);
    }
  }

  /**
   * Applies an RFC 6902 JSON patch to one ingress.
   *
   * @param namespace Kubernetes namespace
   * @param ingressName ingress name
   * @param patch JSON patch operations
   */
  private void patchIngress(
      final String namespace,
      final String ingressName,
      final ArrayNode patch) {
    patchResource(namespace, "ingress/" + ingressName, "json", patch);
  }

  /**
   * Replaces the complete nginx configuration value in the PEP ConfigMap.
   *
   * @param namespace Kubernetes namespace
   * @param captured original and direct configuration
   * @param configuration complete nginx configuration
   */
  private void patchPepConfiguration(
      final String namespace, final CapturedPepConfiguration captured, final String configuration) {
    var current = readResource(namespace, "configmap", PEP_CONFIG_MAP);
    validatePepConfiguration(current, captured);
    var patch = guardedPatch(current);
    patch.add(replaceOperation("/data/" + PEP_CONFIG_KEY, JSON.getNodeFactory().textNode(configuration)));
    patchResource(namespace, "configmap/" + PEP_CONFIG_MAP, "json", patch);
  }

  /**
   * Applies a JSON patch document from a temporary file without shell quoting.
   *
   * @param namespace Kubernetes namespace
   * @param target resource/name target
   * @param patchType kubectl patch type
   * @param patch patch document
   */
  private void patchResource(
      final String namespace,
      final String target,
      final String patchType,
      final JsonNode patch) {
    Path patchFile = null;
    try {
      patchFile = Files.createTempFile("zeta-performance-routing-", ".json");
      Files.writeString(patchFile, patch.toString(), UTF_8);
      var result = deploymentService.executeKubectlCommand(
          "-n", namespace, "patch", target,
          "--type=" + patchType, "--field-manager=helm", "--patch-file", patchFile.toString());
      requireSuccess(result, "patch " + target);
    } catch (IOException exception) {
      throw new AssertionError("Could not create the temporary routing patch file.", exception);
    } finally {
      deletePatchFile(patchFile);
    }
  }

  /**
   * Restarts all PEP replicas and waits for the rollout to complete.
   *
   * @param namespace Kubernetes namespace
   */
  private void restartPepDeployment(final String namespace) {
    var restart = deploymentService.executeKubectlCommand(
        "rollout", "restart", "deployment/" + PEP_DEPLOYMENT,
        "-n", namespace, "--field-manager=helm");
    requireSuccess(restart, "restart deployment/" + PEP_DEPLOYMENT);
    var rollout = deploymentService.waitForDeploymentRollout(
        namespace, PEP_DEPLOYMENT, PEP_ROLLOUT_TIMEOUT_SECONDS);
    requireSuccess(rollout, "wait for deployment/" + PEP_DEPLOYMENT + " rollout");
  }

  /**
   * Reads the nginx configuration mounted in a live PEP container.
   *
   * @param namespace Kubernetes namespace
   * @return active nginx configuration
   */
  private String readActivePepConfiguration(final String namespace) {
    var result = deploymentService.executeKubectlCommand(
        "-n", namespace, "exec", "deployment/" + PEP_DEPLOYMENT,
        "-c", PEP_NGINX_CONTAINER, "--", "cat", "/etc/nginx/nginx.conf");
    requireSuccess(result, "read active PEP nginx configuration");
    return result.stdout();
  }

  /**
   * Deletes a temporary routing patch file after kubectl has completed.
   *
   * @param patchFile temporary file, or {@code null} if creation failed
   */
  private void deletePatchFile(final Path patchFile) {
    if (patchFile == null) {
      return;
    }
    try {
      Files.deleteIfExists(patchFile);
    } catch (IOException exception) {
      log.warn("Could not delete temporary routing patch file '{}'.", patchFile, exception);
    }
  }

  /**
   * Creates a backend service JSON object.
   *
   * @param serviceName service name
   * @param servicePortName named service port
   * @return backend service object
   */
  private ObjectNode serviceBackend(final String serviceName, final String servicePortName) {
    var service = JSON.createObjectNode();
    service.put("name", serviceName);
    service.putObject("port").put("name", servicePortName);
    return service;
  }

  /**
   * Creates a JSON replace operation.
   *
   * @param path JSON pointer
   * @param value replacement value
   * @return JSON patch operation
   */
  private ObjectNode replaceOperation(final String path, final JsonNode value) {
    var operation = JSON.createObjectNode();
    operation.put("op", "replace");
    operation.put("path", path);
    operation.set("value", value);
    return operation;
  }

  /**
   * Creates an annotation JSON patch operation.
   *
   * @param operationName add or remove
   * @param annotationKey annotation name
   * @param value annotation value, ignored for remove
   * @return JSON patch operation
   */
  private ObjectNode annotationOperation(
      final String operationName,
      final String annotationKey,
      final String value) {
    var operation = JSON.createObjectNode();
    operation.put("op", operationName);
    operation.put("path", "/metadata/annotations/" + escapeJsonPointer(annotationKey));
    if (value != null) {
      operation.put("value", value);
    }
    return operation;
  }

  /**
   * Escapes a string for use as one JSON pointer segment.
   *
   * @param value unescaped value
   * @return escaped JSON pointer segment
   */
  private String escapeJsonPointer(final String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  /**
   * Throws an assertion with command evidence when kubectl failed.
   *
   * @param result command result
   * @param action attempted action
   */
  private void requireSuccess(final CommandResult result, final String action) {
    if (result.exitCode() != 0) {
      throw new AssertionError(
          "Failed to " + action + " with command '"
              + String.join(" ", result.command()) + "': " + result.stderr());
    }
  }

  /**
   * Validates the Kubernetes namespace.
   *
   * @param namespace Kubernetes namespace
   */
  private void requireNamespace(final String namespace) {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("Kubernetes namespace must not be blank.");
    }
  }

  /**
   * Checks every owned field before starting restoration, avoiding partial repairs on drift.
   *
   * @param snapshot saved original and temporary state
   */
  private void validateRestorableState(final RoutingSnapshot snapshot) {
    for (var route : snapshot.routes()) {
      validateRoute(readResource(snapshot.namespace(), "ingress", route.ingressName()), route, true);
    }
    validatePepConfiguration(readResource(snapshot.namespace(), "configmap", PEP_CONFIG_MAP),
        snapshot.pepConfiguration());
  }

  /**
   * Rejects changed identities, moved paths and unrelated routing edits.
   *
   * @param current current ingress
   * @param route captured route
   * @param allowDirect whether the temporary state is acceptable
   */
  private void validateRoute(
      final JsonNode current, final CapturedRoute route, final boolean allowDirect) {
    var path = current.at(route.jsonPointer());
    var backend = path.path("backend").path("service");
    var original = route.path().equals(path.path("path").asText()) && route.backendService().equals(backend);
    var direct = allowDirect && route.directPath().equals(path.path("path").asText())
        && route.directBackend().equals(backend);
    if (!route.uid().equals(current.path("metadata").path("uid").asText()) || (!original && !direct)) {
      throw new AssertionError("Routing conflict on ingress/" + route.ingressName()
          + ": resource or backend changed outside this performance run. Backup retained.");
    }
    var annotations = current.path("metadata").path("annotations");
    var originalAnnotations = true;
    var directAnnotations = allowDirect;
    for (var annotation : route.annotations()) {
      var actual = annotations.has(annotation.key()) ? annotations.path(annotation.key()).asText() : null;
      originalAnnotations &= Objects.equals(actual, annotation.value().orElse(null));
      directAnnotations &= Objects.equals(actual, route.directAnnotations().get(annotation.key()));
    }
    if (!(original && originalAnnotations) && !(direct && directAnnotations)) {
      throw new AssertionError("Routing annotation conflict on ingress/" + route.ingressName()
          + ". Backup retained.");
    }
  }

  /**
   * Refuses to replace a recreated ConfigMap or configuration modified by another actor.
   *
   * @param current current ConfigMap
   * @param captured saved configuration
   */
  private void validatePepConfiguration(
      final JsonNode current, final CapturedPepConfiguration captured) {
    var value = current.path("data").path(PEP_CONFIG_KEY).asText();
    if (!captured.uid().equals(current.path("metadata").path("uid").asText())
        || (!captured.originalValue().equals(value) && !captured.directValue().equals(value))) {
      throw new AssertionError("PEP configuration changed outside this routing operation. Backup retained.");
    }
  }

  /**
   * Makes every patch conditional on the exact resource read immediately beforehand.
   *
   * @param current current Kubernetes resource
   * @return JSON patch with a resourceVersion test
   */
  private ArrayNode guardedPatch(final JsonNode current) {
    var version = current.path("metadata").path("resourceVersion");
    if (!version.isTextual() || version.asText().isBlank()) {
      throw new AssertionError("Missing resourceVersion; refusing an unconditional routing patch.");
    }
    var patch = JSON.createArrayNode();
    var test = replaceOperation("/metadata/resourceVersion", version);
    test.put("op", "test");
    patch.add(test);
    return patch;
  }

  /**
   * Encodes a versioned snapshot without relying on Optional serialization modules.
   *
   * @param snapshot captured state
   * @return durable JSON payload
   */
  private ObjectNode encodeSnapshot(final RoutingSnapshot snapshot) {
    var saved = JSON.createObjectNode().put("version", 1).put("namespace", snapshot.namespace());
    saved.set("pep", JSON.valueToTree(snapshot.pepConfiguration()));
    var routes = saved.putArray("routes");
    for (var route : snapshot.routes()) {
      var item = routes.addObject().put("name", route.ingressName()).put("path", route.path())
          .put("directPath", route.directPath()).put("pointer", route.jsonPointer()).put("uid", route.uid());
      item.set("original", route.backendService());
      item.set("direct", route.directBackend());
      item.set("directAnnotations", JSON.valueToTree(route.directAnnotations()));
      var annotations = item.putObject("annotations");
      for (var annotation : route.annotations()) {
        annotations.put(annotation.key(), annotation.value().orElse(null));
      }
    }
    return saved;
  }

  /**
   * Decodes and validates the backup schema before attempting recovery.
   *
   * @param namespace requested namespace
   * @param saved durable payload
   * @return original routing snapshot
   */
  private RoutingSnapshot decodeSnapshot(final String namespace, final JsonNode saved) {
    if (saved.path("version").asInt() != 1 || !namespace.equals(saved.path("namespace").asText())
        || saved.path("routes").size() != ROUTES.size()) {
      throw new AssertionError("Unsupported or incomplete performance routing backup.");
    }
    var routes = new ArrayList<CapturedRoute>();
    for (var item : saved.path("routes")) {
      var annotations = new ArrayList<CapturedAnnotation>();
      for (var entry : item.path("annotations").properties()) {
        annotations.add(new CapturedAnnotation(entry.getKey(), entry.getValue().isNull()
            ? Optional.empty() : Optional.of(entry.getValue().asText())));
      }
      var directAnnotations = new java.util.HashMap<String, String>();
      for (var entry : item.path("directAnnotations").properties()) {
        directAnnotations.put(entry.getKey(), entry.getValue().asText());
      }
      routes.add(new CapturedRoute(item.path("name").asText(), item.path("path").asText(),
          item.path("pointer").asText(), item.path("original"), annotations,
          item.path("uid").asText(), item.path("direct"), directAnnotations,
          item.has("directPath") ? item.path("directPath").asText() : item.path("path").asText()));
    }
    var pep = saved.path("pep");
    return new RoutingSnapshot(namespace, routes, new CapturedPepConfiguration(
        pep.path("originalValue").asText(), pep.path("directValue").asText(), pep.path("uid").asText()));
  }

  /** Snapshot of all routing state changed for a performance scenario. */
  public record RoutingSnapshot(
      String namespace,
      List<CapturedRoute> routes,
      CapturedPepConfiguration pepConfiguration) {

    /** Validates and defensively copies the routing snapshot. */
    public RoutingSnapshot {
      namespace = Objects.requireNonNull(namespace, "namespace must not be null");
      routes = List.copyOf(Objects.requireNonNull(routes, "routes must not be null"));
      pepConfiguration = Objects.requireNonNull(
          pepConfiguration, "pepConfiguration must not be null");
    }
  }

  /** Captured original and prepared direct PEP nginx configuration. */
  public record CapturedPepConfiguration(String originalValue, String directValue, String uid) {

    /** Validates the captured PEP configuration. */
    public CapturedPepConfiguration {
      originalValue = Objects.requireNonNull(originalValue, "originalValue must not be null");
      directValue = Objects.requireNonNull(directValue, "directValue must not be null");
      if (uid == null || uid.isBlank()) {
        throw new IllegalArgumentException("PEP ConfigMap UID must not be blank");
      }
    }

    /**
     * Reports whether activating the direct configuration requires a PEP restart.
     *
     * @return {@code true} when original and direct configuration differ
     */
    public boolean requiresChange() {
      return !originalValue.equals(directValue);
    }
  }

  /** Captured state for one ingress path. */
  public record CapturedRoute(
      String ingressName,
      String path,
      String jsonPointer,
      JsonNode backendService,
      List<CapturedAnnotation> annotations,
      String uid,
      JsonNode directBackend,
      Map<String, String> directAnnotations,
      String directPath) {

    /** Validates and defensively copies the captured route. */
    public CapturedRoute {
      ingressName = Objects.requireNonNull(ingressName, "ingressName must not be null");
      path = Objects.requireNonNull(path, "path must not be null");
      jsonPointer = Objects.requireNonNull(jsonPointer, "jsonPointer must not be null");
      backendService = Objects.requireNonNull(backendService, "backendService must not be null").deepCopy();
      annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations must not be null"));
      if (uid == null || uid.isBlank()) {
        throw new IllegalArgumentException("Ingress UID must not be blank");
      }
      directBackend = Objects.requireNonNull(directBackend, "directBackend must not be null").deepCopy();
      directAnnotations = Map.copyOf(directAnnotations);
      directPath = Objects.requireNonNull(directPath, "directPath must not be null");
    }
  }

  /** Captured value or absence of one ingress annotation. */
  public record CapturedAnnotation(String key, Optional<String> value) {

    /** Validates the captured annotation. */
    public CapturedAnnotation {
      key = Objects.requireNonNull(key, "key must not be null");
      value = Objects.requireNonNull(value, "value must not be null");
    }
  }

  /** Direct backend target for one ingress path. */
  private record RouteDefinition(
      String ingressName,
      String path,
      String serviceName,
      String servicePortName,
      Map<String, String> annotations) {
  }

  /** Located ingress path and its JSON pointer. */
  private record PathLocation(String jsonPointer, JsonNode pathNode) {
  }
}
