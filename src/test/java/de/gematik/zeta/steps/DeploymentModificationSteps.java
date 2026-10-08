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

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.SystemCommandService;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.ZetaClientDataForwardingToggleRequest;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenValidityRequest;
import de.gematik.zeta.services.model.ZetaRequiredScopesRequest;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.And;
import io.cucumber.java.en.When;
import io.restassured.http.Method;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cucumber step definitions for modifications of the Zeta Guard deployment.
 */
@Slf4j
@SuppressWarnings("unused")
public class DeploymentModificationSteps {

  private static final int MAX_KEY_DEPTH = 20;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long DEFAULT_POLL_INTERVAL_MILLIS = 250L;
  private static final int DEFAULT_ROLLBACK_MAX_SECONDS = 660;
  private static final String DEFAULT_HELM_ROLLBACK_TIMEOUT = "10m";
  private static final String DEFAULT_ISOLATED_HELM_ROLLBACK_PROOF_INSTALL_TIMEOUT = "10m";
  private static final String DEFAULT_ISOLATED_HELM_ROLLBACK_PROOF_TIMEOUT = "30s";
  private static final String ROLLBACK_MAX_SECONDS_CONFIG_KEY = "zeta_k8s_rollback_max_seconds";
  private static final String HELM_ROLLBACK_TIMEOUT_CONFIG_KEY = "zeta_k8s_helm_rollback_timeout";
  private static final String ISOLATED_HELM_ROLLBACK_PROOF_TIMEOUT_CONFIG_KEY =
      "zeta_k8s_isolated_helm_rollback_proof_timeout";
  private static final String ISOLATED_HELM_ROLLBACK_PROOF_INSTALL_TIMEOUT_CONFIG_KEY =
      "zeta_k8s_isolated_helm_rollback_proof_install_timeout";

  private final ZetaDeploymentModificationService service;

  /**
   * Creates deployment modification steps backed by the default deployment configuration service instance.
   */
  public DeploymentModificationSteps() {
    this(ZetaDeploymentConfiguration.getServiceInstance());
  }

  /**
   * Creates deployment modification steps backed by the provided deployment configuration service.
   *
   * @param service service used to modify and inspect the deployment
   */
  DeploymentModificationSteps(final ZetaDeploymentModificationService service) {
    this.service = service;
  }

  /**
   * Mutable evidence collected while a pending request runs through a background image update.
   */
  private static final class BackgroundRolloutEvidence {

    private Instant imageVisibleAt;
    private Instant responseObservedAt;
    private Instant responseTransmittedAt;
  }

  /**
   * Mutable evidence collected while proving literal request takeover after old-pod termination.
   */
  private static final class LiteralTakeoverEvidence {

    private Instant upstreamObservedAt;
    private Instant upstreamTransmittedAt;
    private Instant podGoneObservedAt;
    private Instant rolloutFinalizedAt;
    private Instant newPepWithImageReadyObservedAt;
    private Instant responseObservedAt;
    private Instant responseTransmittedAt;
    private String newPepWithImagePodName;
  }

  /**
   * Sets PoPP token verification for a route in a ZETA Guard deployment.
   *
   * @param enabled {@code true} to enable PoPP token verification, {@code false} to disable it
   * @param targetRoute Route that PoPP token verification should be set for
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("{toggleAction} die PoPP Token Verifikation für die Route {tigerResolvedString} im ZETA Deployment")
  @And("{toggleAction} PoPP token verification for route {tigerResolvedString} in ZETA deployment")
  public void setPoppTokenVerification(boolean enabled, String targetRoute) throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaPoppTokenToggleRequest request = getPoppTokenRequest();
    try {
      prepareConfigMapBackupForCurrentScenario(details.namespace(), details.nginxConfigMapName());
      service.setPoppVerification(details, request, targetRoute, enabled);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Sets client-data forwarding for a route in a ZETA Guard deployment.
   *
   * @param enabled {@code true} to enable client-data forwarding, {@code false} to disable it
   * @param targetRoute route that client-data forwarding should be set for
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("{toggleAction} die Client-Daten-Weiterleitung für die Route {tigerResolvedString} im ZETA Deployment")
  @And("{toggleAction} client data forwarding for route {tigerResolvedString} in ZETA deployment")
  public void setClientDataForwarding(boolean enabled, String targetRoute) throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaClientDataForwardingToggleRequest request = getClientDataForwardingRequest();
    try {
      prepareConfigMapBackupForCurrentScenario(details.namespace(), details.nginxConfigMapName());
      service.setClientDataForwarding(details, request, targetRoute, enabled);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Sets the required scopes for a route in a ZETA Guard deployment.
   *
   * @param requiredScopes space-separated scopes required by the route
   * @param targetRoute route for which the required scopes should be set
   * @throws AssertionError if any exception occurred during setup or execution
   */
  @Und("setze die erforderlichen Scopes {tigerResolvedString} für die Route {tigerResolvedString} im ZETA Deployment")
  @And("set required scopes {tigerResolvedString} for route {tigerResolvedString} in ZETA deployment")
  public void setRequiredScopes(String requiredScopes, String targetRoute) throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaRequiredScopesRequest request = getRequiredScopesRequest();
    try {
      prepareConfigMapBackupForCurrentScenario(details.namespace(), details.nginxConfigMapName());
      service.setRequiredScopes(details, request, targetRoute, requiredScopes);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to set the PoPP token validity mode in a ZETA Guard deployment.
   *
   * @param validity PoPP validity value, either {@code quarter} or a duration like {@code 300s}
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("setze die PoPP Token Gültigkeit im ZETA Deployment auf {tigerResolvedString}")
  @And("set PoPP token validity in ZETA deployment to {tigerResolvedString}")
  public void setPoppTokenValidity(String validity) throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaPoppTokenValidityRequest request = getPoppTokenValidityRequest();
    try {
      prepareConfigMapBackupForCurrentScenario(details.namespace(), details.nginxConfigMapName());
      service.setPoppTokenValidity(details, request, validity);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Asserts that steps that modify the ZETA guard deployment are allowed by configuration.
   *
   * @throws AssertionError if modification step is not allowed
   */
  private void assertModificationIsAllowed() throws AssertionError {
    var deploymentModificationAllowed = TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false);

    if (deploymentModificationAllowed) {
      return;
    }
    throw new AssertionError("Deployment modification is not allowed, won't execute step");
  }

  /**
   * Scales a deployment to the requested replica count and waits until rollout is finalized.
   *
   * @param deploymentName deployment name
   * @param replicas desired replica count
   */
  @Und("skaliere das Deployment {tigerResolvedString} auf {int} Replikas")
  @And("scale deployment {tigerResolvedString} to {int} replicas")
  public void scaleDeployment(String deploymentName, int replicas) {
    assertModificationIsAllowed();

    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElse("");
    if (namespace.isBlank()) {
      throw new AssertionError("Namespace for deployment scaling is not configured");
    }

    CommandResult currentReplicasResult = service.getDeploymentReplicaCount(namespace, deploymentName);
    if (currentReplicasResult.exitCode() != 0 || currentReplicasResult.stdout() == null
        || currentReplicasResult.stdout().isBlank()) {
      throw new AssertionError("Could not determine current replica count for deployment '" + deploymentName
          + "': " + currentReplicasResult.stderr());
    }

    int currentReplicas;
    try {
      currentReplicas = Integer.parseInt(currentReplicasResult.stdout().trim());
    } catch (NumberFormatException e) {
      throw new AssertionError("Could not parse current replica count '" + currentReplicasResult.stdout()
          + "' for deployment '" + deploymentName + "'", e);
    }

    Hooks.rememberDeploymentReplicaCountIfAbsent(deploymentName, currentReplicas);

    CommandResult scaleResult = service.scaleDeployment(namespace, deploymentName, replicas);
    if (scaleResult.exitCode() != 0) {
      throw new AssertionError("Failed to scale deployment '" + deploymentName + "' to " + replicas
          + " replicas.\n" + scaleResult.stderr());
    }
  }

  /**
   * Calculates and stores the minimum scalability target for a replica count based on a single-pod reference value.
   *
   * <p>The formula is {@code base + (replicas - 1) * ceil(base * 0.75)} so the baseline remains unchanged for one
   * pod and each additional pod contributes at least 75 percent of the single-pod reference.</p>
   *
   * @param varName target variable name to store in test context
   * @param baseValue reference value for one pod (may contain Tiger placeholders)
   * @param replicas active replica count for the scenario
   */
  @Und("berechne und setze lokale Variable {string} aus Basis {tigerResolvedString} und {int} Replikas mit 75 Prozent Zusatzleistung pro Pod")
  @And("calculate and set local variable {string} from base {tigerResolvedString} and {int} replicas with 75 percent additional capacity per pod")
  public void calculateReplicaScaledThreshold(String varName, String baseValue, int replicas) {
    if (varName == null || varName.isBlank()) {
      throw new AssertionError("Target variable name must not be blank.");
    }
    if (replicas < 1) {
      throw new AssertionError("Replica count must be at least 1 but was " + replicas + ".");
    }

    String resolvedBase = TigerGlobalConfiguration.resolvePlaceholders(baseValue).trim();
    BigDecimal base;
    try {
      base = new BigDecimal(resolvedBase);
    } catch (NumberFormatException e) {
      throw new AssertionError("Base value must be numeric but was '" + resolvedBase + "'.", e);
    }

    BigDecimal perAdditionalPod = base.multiply(BigDecimal.valueOf(0.75d))
        .setScale(0, RoundingMode.CEILING);
    BigDecimal scaled = base.add(perAdditionalPod.multiply(BigDecimal.valueOf(replicas - 1L)));
    String scaledText = scaled.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
    TigerGlobalConfiguration.putValue(varName, scaledText, ConfigurationValuePrecedence.TEST_CONTEXT);
  }


  /**
   * Executes "kubectl get pods -o json" and stores a selected logical "wide" column from all
   * matching pods in a Tiger test variable and returns a comma separated list of these values.
   *
   * @param namespace namespace for kubectl query
   * @param headerName header to extract ("NAME", "READY", "STATUS", "RESTARTS", "AGE", "IP", "NODE", "NOMINATED NODE", "READINESS GATES")
   * @param rowFilter row filter applied as contains-match against extracted pod values
   * @param varName target Tiger variable name
   */
  @Und("ermittle aus Pods im Namespace {tigerResolvedString} den Wert der Spalte {tigerResolvedString} für alle Zeilen mit {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @And("extract from pods in namespace {tigerResolvedString} the value from header {tigerResolvedString} for all rows containing {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void extractAllPodWideColumnValuesToVariable(String namespace, String headerName, String rowFilter, String varName) {
    var value = getValueFromPodInfo(namespace, headerName, rowFilter, false);
    TigerGlobalConfiguration.putValue(varName, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Executes "kubectl get pods -o json" and stores a selected logical "wide" column from the first
   * matching pod in a Tiger test variable.
   *
   * @param namespace namespace for kubectl query
   * @param headerName header to extract ("NAME", "READY", "STATUS", "RESTARTS", "AGE", "IP", "NODE", "NOMINATED NODE", "READINESS GATES")
   * @param rowFilter row filter applied as contains-match against extracted pod values
   * @param varName target Tiger variable name
   */
  @Und("ermittle aus den Pods im Namespace {tigerResolvedString} den Wert aus der Spalte {tigerResolvedString} der Zeile mit {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @And("extract from pods in namespace {tigerResolvedString} value from header {tigerResolvedString} in row containing {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void extractPodWideColumnToVariable(String namespace, String headerName, String rowFilter, String varName) {
    var value = getValueFromPodInfo(namespace, headerName, rowFilter, true);
    TigerGlobalConfiguration.putValue(varName, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Queries pod information from a Kubernetes cluster and extracts a column value from a single or all filtered rows.
   *
   * @param namespace namespace for kubectl query
   * @param headerName header to extract ("NAME", "READY", "STATUS", "RESTARTS", "AGE", "IP", "NODE", "NOMINATED NODE", "READINESS GATES")
   * @param rowFilter row filter applied as contains-match against extracted pod values
   * @param matchSingle flag to either break after first found row or append to comma separated list of values
   * @return a single value or a comma separated list of values extracted from pod information in the cluster
   */
  private String getValueFromPodInfo(String namespace, String headerName, String rowFilter, boolean matchSingle) {
    service.verifyRequirements(namespace);

    CommandResult cmdResult = service.executeKubectlCommand("-n", namespace, "get", "pods", "-o", "json");
    if (cmdResult.exitCode() != 0) {
      throw new AssertionError("kubectl get pods failed: " + cmdResult.stderr());
    }

    // explicitly append command and result to Serenity report for traceability
    String cmdStr = String.join(" ", cmdResult.command());
    log.debug(cmdStr);
    Serenity.recordReportData()
        .withTitle("Command:")
        .andContents(cmdStr);
    log.debug(cmdResult.toString());
    Serenity.recordReportData()
        .withTitle("Result :")
        .andContents(cmdResult.toString());

    JsonNode items;
    try {
      items = JSON.readTree(cmdResult.stdout()).path("items");
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse kubectl pods JSON output.", e);
    }
    if (!items.isArray() || items.isEmpty()) {
      throw new AssertionError("kubectl get pods -o json returned no pods in namespace '" + namespace + "'.");
    }

    String result = "";
    String requestedHeader = headerName == null ? "" : headerName.trim();
    String filter = rowFilter == null ? "" : rowFilter.trim();
    JsonNode matchingPod = null;
    String matchedRowProjection = null;
    for (JsonNode pod : items) {
      String rowProjection = buildPodWideProjection(pod);
      if (!filter.isBlank() && rowProjection.contains(filter)) {
        matchingPod = pod;
        matchedRowProjection = rowProjection;

        String value = extractWideColumnValue(matchingPod, requestedHeader);
        if (value == null) {
          throw new AssertionError(
              "Header '" + headerName + "' not supported. Available headers: NAME, READY, STATUS, RESTARTS, AGE, IP, NODE, NOMINATED NODE, READINESS GATES");
        }
        if (value.isBlank()) {
          throw new AssertionError(
              "Header '" + headerName + "' exists, but value is missing in matched pod projection: " + matchedRowProjection);
        }

        result = result.isBlank() ? value : result + ", " + value;
        if (matchSingle) {
          break;
        }
      }
    }

    if (result.isBlank()) {
      throw new AssertionError(
          "No pod row containing '" + rowFilter + "' found in namespace '" + namespace + "'.");
    }

    return result;
  }

  /**
   * Builds a single string projection of pod values equivalent to the columns of "kubectl get pods -o wide".
   *
   * @param pod pod JSON node
   * @return projected pod line containing all supported logical wide columns
   */
  private String buildPodWideProjection(JsonNode pod) {
    return String.join(" ",
        extractWideColumnValue(pod, "NAME"),
        extractWideColumnValue(pod, "READY"),
        extractWideColumnValue(pod, "STATUS"),
        extractWideColumnValue(pod, "RESTARTS"),
        extractWideColumnValue(pod, "AGE"),
        extractWideColumnValue(pod, "IP"),
        extractWideColumnValue(pod, "NODE"),
        extractWideColumnValue(pod, "NOMINATED NODE"),
        extractWideColumnValue(pod, "READINESS GATES"));
  }

  /**
   * Extracts the value for one logical "kubectl wide" header from a pod JSON node.
   *
   * @param pod pod JSON node
   * @param headerName requested logical header
   * @return extracted value, or {@code null} if the header is unsupported
   */
  private String extractWideColumnValue(JsonNode pod, String headerName) {
    return switch (headerName.toUpperCase()) {
      case "NAME" -> pod.path("metadata").path("name").asText("");
      case "READY" -> {
        JsonNode statuses = pod.path("status").path("containerStatuses");
        int total = statuses.isArray() ? statuses.size() : 0;
        int ready = 0;
        if (statuses.isArray()) {
          for (JsonNode status : statuses) {
            if (status.path("ready").asBoolean(false)) {
              ready++;
            }
          }
        }
        yield ready + "/" + total;
      }
      case "STATUS" -> pod.path("status").path("phase").asText("");
      case "RESTARTS" -> {
        JsonNode statuses = pod.path("status").path("containerStatuses");
        int restartCount = 0;
        if (statuses.isArray()) {
          for (JsonNode status : statuses) {
            restartCount += status.path("restartCount").asInt(0);
          }
        }
        yield String.valueOf(restartCount);
      }
      case "AGE" -> pod.path("metadata").path("creationTimestamp").asText("");
      case "IP" -> pod.path("status").path("podIP").asText("");
      case "NODE" -> pod.path("spec").path("nodeName").asText("");
      case "NOMINATED NODE" -> pod.path("status").path("nominatedNodeName").asText("<none>");
      case "READINESS GATES" -> {
        JsonNode readinessGates = pod.path("spec").path("readinessGates");
        yield readinessGates.isArray() && !readinessGates.isEmpty()
            ? String.valueOf(readinessGates.size())
            : "<none>";
      }
      default -> null;
    };
  }

  /**
   * Reads deployment-related configuration from Tiger variables and maps it to deployment details.
   *
   * @return deployment details used by deployment manipulation service methods
   * @throws AssertionError when required configuration values are missing
   */
  private ZetaDeploymentDetails getDeploymentDetails() {
    return ZetaDeploymentConfiguration.getDeploymentDetails();
  }

  /**
   * Cucumber step to update the container image in a deployment and validate rollout.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("setze das Image {tigerResolvedString} für den Container {tigerResolvedString} im Deployment {tigerResolvedString}")
  @And("set image {tigerResolvedString} for container {tigerResolvedString} in deployment {tigerResolvedString}")
  public void setDeploymentContainerImage(String newImage, String containerName, String deploymentName)
      throws AssertionError {
    String namespace = getNamespace();
    try {
      rememberPepOriginalImageIfNeeded(namespace, deploymentName, containerName);
      CommandResult result = service.setDeploymentContainerImage(namespace, deploymentName, containerName, newImage);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to verify the deployment rollout and container readiness after an image update.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("prüfe, dass das Image {tigerResolvedString} für den Container {tigerResolvedString} im Deployment {tigerResolvedString} aktiv ist")
  @And("verify image {tigerResolvedString} for container {tigerResolvedString} in deployment {tigerResolvedString} is active")
  public void verifyDeploymentContainerImage(String newImage, String containerName, String deploymentName)
      throws AssertionError {
    String namespace = getNamespace();
    try {
      CommandResult result = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, newImage);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Resolves the current ready pod for a deployment and stores it in a Tiger variable.
   *
   * @throws AssertionError if no single ready pod can be determined
   */
  @Und("ermittle den aktuellen Ready-Pod für das Deployment {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @And("resolve current ready pod for deployment {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void resolveCurrentReadyPodForDeploymentToVariable(String deploymentName, String variableName)
      throws AssertionError {
    String namespace = getNamespace();
    try {
      String podName = service.getSingleReadyPodNameForDeployment(namespace, deploymentName);
      TigerGlobalConfiguration.putValue(variableName, podName, ConfigurationValuePrecedence.TEST_CONTEXT);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Sets the RollingUpdate strategy for a deployment and remembers the original strategy for scenario cleanup.
   *
   * @param deploymentName deployment name
   * @param maxSurge maxSurge value, for example {@code 0} or {@code 25%}
   * @param maxUnavailable maxUnavailable value, for example {@code 1} or {@code 25%}
   */
  @Und("setze die RollingUpdate-Strategie des Deployments {tigerResolvedString} auf maxSurge {tigerResolvedString} und maxUnavailable {tigerResolvedString}")
  @And("set RollingUpdate strategy of deployment {tigerResolvedString} to maxSurge {tigerResolvedString} and maxUnavailable {tigerResolvedString}")
  public void setDeploymentRollingUpdateStrategy(String deploymentName, String maxSurge, String maxUnavailable) {
    assertModificationIsAllowed();

    String namespace = getNamespace();
    CommandResult strategyResult = service.getDeploymentStrategy(namespace, deploymentName);
    if (strategyResult.exitCode() != 0 || strategyResult.stdout() == null || strategyResult.stdout().isBlank()) {
      throw new AssertionError("Could not determine current strategy for deployment '" + deploymentName
          + "': " + strategyResult.stderr());
    }
    Hooks.rememberDeploymentStrategyIfAbsent(deploymentName, strategyResult.stdout());

    CommandResult patchResult =
        service.setDeploymentRollingUpdateStrategy(namespace, deploymentName, maxSurge, maxUnavailable);
    if (patchResult.exitCode() != 0) {
      throw new AssertionError("Could not set RollingUpdate strategy for deployment '" + deploymentName
          + "': " + patchResult.stderr());
    }
  }

  /**
   * Verifies that Kubernetes recorded an image pull for the given pod and container.
   *
   * @throws AssertionError if no matching pull event can be found
   */
  @Und("prüfe, dass für den Pod {tigerResolvedString} ein Image-Pull für den Container {tigerResolvedString} stattgefunden hat")
  @And("verify pod {tigerResolvedString} had an image pull for container {tigerResolvedString}")
  public void verifyPodImagePullOccurred(String podName, String containerName) throws AssertionError {
    String namespace = getNamespace();
    try {
      CommandResult result = service.verifyPodImagePullOccurred(namespace, podName, containerName);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
      SerenityReportUtils.addCustomData("Pod image pull verification",
          "pod=" + podName + ", container=" + containerName + "\n" + result.stdout());
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Verifies that rollout activity for the target image becomes visible on deployment pods within
   * the given time budget.
   */
  @Und("prüfe, dass für das Deployment {tigerResolvedString} innerhalb von {int} Sekunden ein Pod mit dem Image {tigerResolvedString} für den Container {tigerResolvedString} sichtbar wird")
  @And("verify deployment {tigerResolvedString} shows a pod with image {tigerResolvedString} for container {tigerResolvedString} within {int} seconds")
  public void verifyDeploymentShowsPodWithImageWithinSeconds(String deploymentName, int maxDurationSeconds,
      String expectedImage, String containerName) throws AssertionError {
    String namespace = getNamespace();
    try {
      CommandResult result = service.verifyDeploymentShowsPodWithImageWithinSeconds(
          namespace, deploymentName, containerName, expectedImage, maxDurationSeconds);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
      SerenityReportUtils.addCustomData("Deployment image visibility", result.stdout());
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Verifies that rollout activity for the target image becomes visible before a previously started
   * request receives its response.
   */
  @Und(
      "prüfe, dass für das Deployment {tigerResolvedString} innerhalb von {int} Sekunden ein Pod mit dem Image"
          + " {tigerResolvedString} für den Container {tigerResolvedString} sichtbar wird, bevor die erste Anfrage"
          + " mit Pfad {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString}"
          + " übereinstimmt, beantwortet wird")
  @And(
      "verify deployment {tigerResolvedString} shows a pod with image {tigerResolvedString} for container"
          + " {tigerResolvedString} within {int} seconds before first request to path {tigerResolvedString}"
          + " with {tigerResolvedString} matching {tigerResolvedString} is answered")
  public void verifyDeploymentShowsPodWithImageBeforeFirstMatchingRequestAnswered(String deploymentName,
      int maxDurationSeconds, String expectedImage, String containerName, String pathPattern, String rbelPath,
      String expectedValueRegex) {
    verifyDeploymentImageVisibilityBeforeRequestAnswered(
        deploymentName, maxDurationSeconds, expectedImage, containerName, pathPattern, rbelPath, expectedValueRegex);
  }

  /**
   * Starts a long-running client request, triggers the deployment image update while that request is pending,
   * and verifies that rollout activity becomes visible before the tracked request is answered successfully.
   *
   * @param requestUrl client URL that is called via the configured Tiger HTTP client
   * @param headerName header name used to correlate the request
   * @param headerValue header value used to correlate the request
   * @param pathPattern RBEL path pattern for the original client request
   * @param newImage image that triggers the rollout
   * @param containerName container whose image is changed
   * @param deploymentName deployment whose image is changed
   * @param timeoutSeconds maximum verification time
   */
  @Wenn(
      "prüfe, dass eine leere GET Anfrage an {tigerResolvedString} mit Header {tigerResolvedString} gleich"
          + " {tigerResolvedString} und Pfad {tigerResolvedString} während das Image {tigerResolvedString}"
          + " für den Container {tigerResolvedString} im Deployment {tigerResolvedString} gesetzt wird,"
          + " bis zur Sichtbarkeit eines Pods mit diesem Image weiterläuft und anschließend erfolgreich"
          + " beantwortet wird oder {int} Sekunden vergangen sind")
  @When(
      "verify empty GET request to {tigerResolvedString} with header {tigerResolvedString} equal"
          + " {tigerResolvedString} and path {tigerResolvedString} keeps running while setting image"
          + " {tigerResolvedString} for container {tigerResolvedString} in deployment {tigerResolvedString}"
          + " until a pod with this image is visible and then responds successfully or {int} seconds have passed")
  public void sendRequestAndVerifyBackgroundImageVisibilityBeforeResponse(String requestUrl,
      String headerName, String headerValue, String pathPattern, String newImage, String containerName,
      String deploymentName, int timeoutSeconds) {
    HttpGlueCode httpGlueCode = startEmptyGetRequestWithHeader(requestUrl, headerName, headerValue);
    try {
      verifyBackgroundImageVisibilityBeforeRequestAnsweredAfterImageUpdate(
          pathPattern, "$.header.[~'" + headerName.toLowerCase() + "']", Pattern.quote(headerValue),
          newImage, containerName, deploymentName, timeoutSeconds);
    } finally {
      httpGlueCode.clearDefaultHeader(headerName);
    }
  }

  /**
   * Sends repeated GET requests until a deployment has fully switched to a new ready pod.
   */
  @Wenn(
      "sende wiederholt eine leere GET Anfrage an {tigerResolvedString} und erwarte HTTP Status {tigerResolvedString} bis"
          + " das Deployment {tigerResolvedString} ausgehend von Pod {tigerResolvedString} auf einen neuen Pod gewechselt"
          + " ist oder {int} Sekunden vergangen sind")
  @When(
      "send repeated empty GET requests to {tigerResolvedString} and expect HTTP status {tigerResolvedString} until"
          + " deployment {tigerResolvedString} has switched from pod {tigerResolvedString} to a new pod or {int}"
          + " seconds have passed")
  public void pollGetUntilDeploymentSwitchedFromKnownPod(String url, String expectedStatusCode, String deploymentName,
      String initialPodName, int timeoutSeconds) {
    pollGetUntilDeploymentSwitchedToNewPod(url, expectedStatusCode, deploymentName, initialPodName, timeoutSeconds);
  }

  /**
   * Sends repeated GET requests until the expected HTTP status is observed.
   *
   * @param url request URL
   * @param expectedStatusCode expected HTTP status code
   * @param timeoutSeconds maximum wait time
   */
  @Wenn(
      "sende wiederholt eine leere GET Anfrage an {tigerResolvedString} und erwarte HTTP Status {tigerResolvedString}"
          + " innerhalb von {int} Sekunden")
  @When(
      "send repeated empty GET requests to {tigerResolvedString} and expect HTTP status {tigerResolvedString}"
          + " within {int} seconds")
  public void pollGetUntilExpectedStatus(String url, String expectedStatusCode, int timeoutSeconds) {
    Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
    int attempts = 0;
    AssertionError lastError = null;

    while (!Instant.now().isAfter(deadline)) {
      attempts++;
      try {
        sendEmptyGet(url);
        String actualStatusCode = extractCurrentResponseCode();
        if (expectedStatusCode.equals(actualStatusCode)) {
          SerenityReportUtils.addCustomData("HTTP status polling",
              "url=" + url + ", expectedStatusCode=" + expectedStatusCode + ", attempts=" + attempts);
          return;
        }
        lastError = new AssertionError("Polling-Request lieferte unerwarteten Statuscode. expected="
            + expectedStatusCode + ", actual=" + actualStatusCode + ", attempt=" + attempts);
      } catch (AssertionError e) {
        lastError = e;
      }
      sleepBeforeNextPoll();
    }

    throw new AssertionError("Timeout beim Polling auf HTTP Status " + expectedStatusCode
        + ". url=" + url + ", attempts=" + attempts, lastError);
  }

  /**
   * Sends repeated GET requests until a deployment has fully switched to a new ready pod.
   */
  @Wenn(
      "sende wiederholt eine leere GET Anfrage an {tigerResolvedString} und erwarte HTTP Status {tigerResolvedString} bis"
          + " das Deployment {tigerResolvedString} auf einen neuen Pod gewechselt ist oder {int} Sekunden vergangen sind")
  @When(
      "send repeated empty GET requests to {tigerResolvedString} and expect HTTP status {tigerResolvedString} until"
      + " deployment {tigerResolvedString} has switched to a new pod or {int} seconds have passed")
  public void pollGetUntilDeploymentSwitchedToNewPod(String url, String expectedStatusCode, String deploymentName,
      int timeoutSeconds) {
    String namespace = getNamespace();
    service.verifyRequirements(namespace);

    String initialPodName = requireSingleReadyPodNameByPrefix(namespace, deploymentName);
    pollGetUntilDeploymentSwitchedToNewPod(url, expectedStatusCode, deploymentName, initialPodName, timeoutSeconds);
  }

  private void pollGetUntilDeploymentSwitchedToNewPod(String url, String expectedStatusCode, String deploymentName,
      String initialPodName, int timeoutSeconds) {
    String namespace = getNamespace();
    Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
    int attempts = 0;
    List<String> observedPods = new ArrayList<>();

    while (!Instant.now().isAfter(deadline)) {
      attempts++;
      sendEmptyGet(url);
      String actualStatusCode = extractCurrentResponseCode();
      if (!expectedStatusCode.equals(actualStatusCode)) {
        throw new AssertionError("Polling-Request lieferte unerwarteten Statuscode. expected="
            + expectedStatusCode + ", actual=" + actualStatusCode + ", attempt=" + attempts);
      }

      String currentReadyPodName = findSingleReadyPodNameByPrefix(namespace, deploymentName);
      if (currentReadyPodName != null) {
        observedPods.add(currentReadyPodName);
        if (hasDeploymentSwitchedToDifferentReadyPod(initialPodName, currentReadyPodName)) {
          SerenityReportUtils.addCustomData("Deployment polling",
              "deployment=" + deploymentName + ", attempts=" + attempts + ", oldPod=" + initialPodName
                  + ", newPod=" + currentReadyPodName);
          return;
        }
      }

      sleepBeforeNextPoll();
    }

    throw new AssertionError("Timeout beim Polling auf Deployment-Wechsel. deployment=" + deploymentName
        + ", initialPod=" + initialPodName + ", observedReadyPods=" + observedPods);
  }

  /**
   * Sends repeated GET requests until the deployment rollout is fully finalized while requiring
   * evidence that the rollout was actually in progress during the polling window.
   */
  @Wenn(
      "sende wiederholt eine leere GET Anfrage an {tigerResolvedString} und erwarte HTTP Status {tigerResolvedString} bis"
          + " das Deployment {tigerResolvedString} finalisiert ist oder {int} Sekunden vergangen sind")
  @When(
      "send repeated empty GET requests to {tigerResolvedString} and expect HTTP status {tigerResolvedString} until"
          + " deployment {tigerResolvedString} is finalized or {int} seconds have passed")
  public void pollGetUntilDeploymentFinalized(String url, String expectedStatusCode, String deploymentName,
      int timeoutSeconds) {
    String namespace = getNamespace();
    service.verifyRequirements(namespace);

    Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
    int attempts = 0;
    boolean observedRolloutInProgress = false;
    List<String> observations = new ArrayList<>();

    while (!Instant.now().isAfter(deadline)) {
      attempts++;
      boolean rolloutPendingBeforeFinalizationProbe = !isDeploymentRolloutFinalized(namespace, deploymentName);
      observations.add((rolloutPendingBeforeFinalizationProbe
          ? "rolloutPendingBeforeAttempt="
          : "rolloutAlreadyFinalizedBeforeAttempt=") + attempts);

      sendEmptyGet(url);
      String actualStatusCode = extractCurrentResponseCode();
      if (!expectedStatusCode.equals(actualStatusCode)) {
        throw new AssertionError("Polling-Request lieferte unerwarteten Statuscode. expected="
            + expectedStatusCode + ", actual=" + actualStatusCode + ", attempt=" + attempts);
      }

      CommandResult rolloutResult = service.executeKubectlCommand(false, "rollout", "status",
          "deployment/" + deploymentName, "-n", namespace, "--timeout=1s");
      if (rolloutResult.exitCode() == 0) {
        Instant rolloutFinalizedAt = Instant.now();
        observations.add("rolloutFinalized=" + rolloutFinalizedAt
            + ", rolloutPendingBeforeProbe=" + rolloutPendingBeforeFinalizationProbe);
        if (hasRolloutFinalizationEvidenceAfterObservedProgress(
            observedRolloutInProgress, rolloutPendingBeforeFinalizationProbe, rolloutFinalizedAt)) {
          SerenityReportUtils.addCustomData("Deployment finalization polling",
              "deployment=" + deploymentName + ", attempts=" + attempts + ", expectedStatus=" + expectedStatusCode
                  + ", observedRolloutInProgress=" + observedRolloutInProgress + ", observations=" + observations);
          return;
        }

        throw new AssertionError("Deployment '" + deploymentName + "' was already finalized before rollout progress"
            + " was observed during availability polling. observations=" + observations);
      }

      if (rolloutPendingBeforeFinalizationProbe) {
        observedRolloutInProgress = true;
        observations.add("rolloutInProgressAfterAttempt=" + attempts);
      }

      sleepBeforeNextPoll();
    }

    throw new AssertionError("Timeout beim Polling auf Deployment-Finalisierung. deployment=" + deploymentName
        + ", observedRolloutInProgress=" + observedRolloutInProgress + ", observations=" + observations);
  }

  static boolean hasDeploymentSwitchedToDifferentReadyPod(String initialPodName, String currentReadyPodName) {
    return initialPodName != null
        && currentReadyPodName != null
        && !initialPodName.equals(currentReadyPodName);
  }

  static boolean hasRolloutFinalizationEvidenceAfterObservedProgress(boolean observedRolloutInProgress,
      boolean rolloutPendingBeforeFinalizationProbe, Instant rolloutFinalizedAt) {
    return rolloutFinalizedAt != null
        && (observedRolloutInProgress || rolloutPendingBeforeFinalizationProbe);
  }

  /**
   * Verifies that a previously started request completes before Kubernetes reports rollout completion
   * for the deployment.
   */
  @Und(
      "prüfe, dass die erste Anfrage mit Pfad {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString}"
          + " übereinstimmt, beantwortet wird bevor das Deployment {tigerResolvedString} finalisiert ist oder {int} Sekunden"
          + " vergangen sind")
  @And(
      "verify first request to path {tigerResolvedString} with {tigerResolvedString} matching {tigerResolvedString}"
          + " is answered before deployment {tigerResolvedString} is finalized or {int} seconds have passed")
  public void verifyFirstMatchingRequestAnsweredBeforeDeploymentFinalized(String pathPattern, String rbelPath,
      String expectedValueRegex, String deploymentName, int timeoutSeconds) {
    verifyRequestResponseVsDeploymentFinalization(pathPattern, rbelPath, expectedValueRegex, deploymentName,
        timeoutSeconds, true, "200", false);
  }

  /**
   * Verifies that Kubernetes finalizes the rollout before a previously started request completes.
   */
  @Und(
      "prüfe, dass das Deployment {tigerResolvedString} finalisiert ist, bevor die erste Anfrage mit Pfad"
          + " {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString} übereinstimmt,"
          + " beantwortet wird oder {int} Sekunden vergangen sind")
  @And(
      "verify deployment {tigerResolvedString} is finalized before first request to path {tigerResolvedString}"
          + " with {tigerResolvedString} matching {tigerResolvedString} is answered or {int} seconds have passed")
  public void verifyDeploymentFinalizedBeforeFirstMatchingRequestAnswered(String deploymentName, String pathPattern,
      String rbelPath, String expectedValueRegex, int timeoutSeconds) {
    verifyRequestResponseVsDeploymentFinalization(pathPattern, rbelPath, expectedValueRegex, deploymentName,
        timeoutSeconds, false, "200", false);
  }

  /**
   * Verifies that a previously started request with a specific expected response status completes
   * before Kubernetes reports rollout completion for the deployment.
   */
  @Und(
      "prüfe, dass die erste Anfrage mit Pfad {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString}"
          + " übereinstimmt, eine Antwort mit HTTP Status {tigerResolvedString} hat bevor das Deployment"
          + " {tigerResolvedString} finalisiert ist oder {int} Sekunden vergangen sind")
  @And(
      "verify first request to path {tigerResolvedString} with {tigerResolvedString} matching {tigerResolvedString}"
          + " has HTTP status {tigerResolvedString} before deployment {tigerResolvedString} is finalized or {int}"
          + " seconds have passed")
  public void verifyFirstMatchingRequestWithExpectedStatusAnsweredBeforeDeploymentFinalized(String pathPattern,
      String rbelPath, String expectedValueRegex, String expectedStatusCode, String deploymentName,
      int timeoutSeconds) {
    verifyRequestResponseVsDeploymentFinalization(pathPattern, rbelPath, expectedValueRegex, deploymentName,
        timeoutSeconds, true, expectedStatusCode, false);
  }

  /**
   * Verifies that a previously started request with a response status matching a regex completes
   * before Kubernetes reports rollout completion for the deployment.
   */
  @Und(
      "prüfe, dass die erste Anfrage mit Pfad {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString}"
          + " übereinstimmt, eine Antwort mit HTTP Status der auf Regex {tigerResolvedString} passt bevor das Deployment"
          + " {tigerResolvedString} finalisiert ist oder {int} Sekunden vergangen sind")
  @And(
      "verify first request to path {tigerResolvedString} with {tigerResolvedString} matching {tigerResolvedString}"
          + " has HTTP status matching regex {tigerResolvedString} before deployment {tigerResolvedString} is"
          + " finalized or {int} seconds have passed")
  public void verifyFirstMatchingRequestWithStatusMatchingRegexAnsweredBeforeDeploymentFinalized(
      String pathPattern, String rbelPath, String expectedValueRegex, String expectedStatusCodeRegex,
      String deploymentName, int timeoutSeconds) {
    verifyRequestResponseVsDeploymentFinalization(pathPattern, rbelPath, expectedValueRegex, deploymentName,
        timeoutSeconds, true, expectedStatusCodeRegex, true);
  }

  /**
   * Verifies that a previously started request is answered only after a deleted pod no longer
   * exists in Kubernetes, providing evidence that the request was taken over during rollout.
   */
  @Und(
      "prüfe, dass der Pod {tigerResolvedString} nicht mehr existiert, bevor die erste Anfrage mit Pfad"
          + " {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString} übereinstimmt,"
          + " beantwortet wird oder {int} Sekunden vergangen sind")
  @And(
      "verify pod {tigerResolvedString} no longer exists before first request to path {tigerResolvedString}"
          + " with {tigerResolvedString} matching {tigerResolvedString} is answered or {int} seconds have passed")
  public void verifyPodGoneBeforeFirstMatchingRequestAnswered(String podName, String pathPattern, String rbelPath,
      String expectedValueRegex, int timeoutSeconds) {
    verifyRequestResponseAfterPodDisappeared(pathPattern, rbelPath, expectedValueRegex, podName, timeoutSeconds);
  }

  /**
   * Sends a long-running client request and verifies literal request takeover by forcefully terminating the old pod
   * while that same request is still pending.
   *
   * @param requestUrl client URL that is called via the configured Tiger HTTP client
   * @param headerName header name used to correlate the request
   * @param headerValue header value used to correlate the request
   * @param clientPathPattern RBEL path pattern for the original client request
   * @param upstreamPathPattern RBEL path pattern for the forwarded Fachdienst request
   * @param newImage image that triggers the rollout
   * @param containerName container whose image is changed
   * @param deploymentName deployment whose image is changed
   * @param podName old pod that must be forcefully terminated
   * @param timeoutSeconds maximum verification time
   */
  @And(
      "verify empty GET request to {tigerResolvedString} with header {tigerResolvedString} equal"
          + " {tigerResolvedString} and client path {tigerResolvedString} and upstream path {tigerResolvedString}"
          + " is successfully taken over while setting image {tigerResolvedString} for container"
          + " {tigerResolvedString} in deployment {tigerResolvedString} after forced termination of pod"
          + " {tigerResolvedString} with exactly one upstream request or {int} seconds have passed")
  @Dann(
      "prüfe, dass eine leere GET Anfrage an {tigerResolvedString} mit Header {tigerResolvedString} gleich"
          + " {tigerResolvedString} und Client-Pfad {tigerResolvedString} und Fachdienst-Pfad {tigerResolvedString}"
          + " während das Image {tigerResolvedString} für den Container {tigerResolvedString} im Deployment"
          + " {tigerResolvedString} gesetzt wird, nach erzwungener Beendigung des Pods {tigerResolvedString}"
          + " mit genau einer Fachdienst-Anfrage erfolgreich übernommen wird oder {int} Sekunden vergangen sind")
  public void sendRequestAndVerifyLiteralRequestTakeoverAfterForcedPodTermination(String requestUrl,
      String headerName, String headerValue, String clientPathPattern, String upstreamPathPattern, String newImage,
      String containerName, String deploymentName, String podName, int timeoutSeconds) {
    HttpGlueCode httpGlueCode = startEmptyGetRequestWithHeader(requestUrl, headerName, headerValue);
    try {
      verifyLiteralRequestTakeoverEvidenceAfterForcedPodTermination(
          podName, clientPathPattern, upstreamPathPattern,
          "$.header.[~'" + headerName.toLowerCase() + "']", Pattern.quote(headerValue), newImage,
          containerName, deploymentName, timeoutSeconds);
    } finally {
      httpGlueCode.clearDefaultHeader(headerName);
    }
  }

  /**
   * Cucumber step to verify that a deployment update does not become active, for example because rollout fails.
   *
   * @throws AssertionError if the image unexpectedly becomes active or an unexpected exception occurs
   */
  @Und("prüfe, dass das Image {tigerResolvedString} für den Container {tigerResolvedString} im Deployment {tigerResolvedString} nicht aktiv wird")
  @And("verify image {tigerResolvedString} for container {tigerResolvedString} in deployment {tigerResolvedString} does not become active")
  public void verifyDeploymentContainerImageDoesNotBecomeActive(String newImage, String containerName, String deploymentName)
      throws AssertionError {
    String namespace = getNamespace();
    try {
      CommandResult result = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, newImage);
      if (result.exitCode() == 0) {
        throw new AssertionError("Image unexpectedly became active: " + newImage);
      }
      SerenityReportUtils.addCustomData("Failed rollout verification",
          "Deployment " + deploymentName + " did not activate image " + newImage + ". stderr: " + result.stderr());
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to resolve the current image path (without tag) of a deployment container and store it in a variable.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("ermittle den Image-Pfad für den Container {tigerResolvedString} im Deployment {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @And("resolve image path for container {tigerResolvedString} in deployment {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void resolveDeploymentContainerImagePathToVariable(String containerName, String deploymentName, String variableName)
      throws AssertionError {
    String namespace = getNamespace();
    try {
      CommandResult result = service.getContainerImagePathForDeployment(namespace, deploymentName, containerName);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
      TigerGlobalConfiguration.putValue(variableName, result.stdout(), ConfigurationValuePrecedence.TEST_CONTEXT);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to resolve the current full image reference of a deployment container and store it in a variable.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("ermittle das vollständige Image für den Container {tigerResolvedString} im Deployment {tigerResolvedString} und speichere in der Variable {tigerResolvedString}")
  @And("resolve full image for container {tigerResolvedString} in deployment {tigerResolvedString} and store in variable {tigerResolvedString}")
  public void resolveDeploymentContainerFullImageToVariable(String containerName, String deploymentName, String variableName)
      throws AssertionError {
    String namespace = getNamespace();
    try {
      CommandResult result = service.getContainerImageReferenceForDeployment(namespace, deploymentName, containerName);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
      rememberPepOriginalImageIfNamedVariableMatches(variableName, deploymentName, containerName, result.stdout());
      TigerGlobalConfiguration.putValue(variableName, result.stdout(), ConfigurationValuePrecedence.TEST_CONTEXT);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to rollback a deployment in the zeta-local namespace.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("rolle das Deployment {tigerResolvedString} zurück")
  @And("rollback deployment {tigerResolvedString}")
  public void rollbackDeployment(String deploymentName) throws AssertionError {
    String namespace = getNamespace();
    try {
      CommandResult result = service.rollbackDeployment(namespace, deploymentName);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to rollback a deployment and assert that the stable image is active within a fixed time budget.
   *
   * @throws AssertionError if rollback execution, rollout verification, or time limit validation fails
   */
  @Und("rolle das Deployment {tigerResolvedString} zurück und prüfe, dass das Image {tigerResolvedString} für den Container {tigerResolvedString} innerhalb von {int} Sekunden aktiv ist")
  @And("rollback deployment {tigerResolvedString} and verify image {tigerResolvedString} for container {tigerResolvedString} is active within {int} seconds")
  public void rollbackDeploymentAndVerifyImageWithinSeconds(String deploymentName, String expectedImage,
      String containerName, int maxDurationSeconds) throws AssertionError {
    assertModificationIsAllowed();

    String namespace = getNamespace();
    long startNanos = System.nanoTime();
    try {
      CommandResult rollbackResult = service.rollbackDeployment(namespace, deploymentName);
      if (rollbackResult.exitCode() != 0) {
        throw new AssertionError(rollbackResult.stderr());
      }

      CommandResult verifyResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, expectedImage);
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      SerenityReportUtils.addCustomData("Rollback duration",
          "Rollback for deployment " + deploymentName + " took " + elapsed.toMillis() + " ms.");
      if (verifyResult.exitCode() != 0) {
        throw new AssertionError("Rollback verification failed within " + elapsed.toMillis() + " ms.\nstderr:\n"
            + verifyResult.stderr());
      }
      if (elapsed.compareTo(Duration.ofSeconds(maxDurationSeconds)) > 0) {
        throw new AssertionError("Rollback duration exceeded limit. Expected <= " + maxDurationSeconds
            + " s but was " + elapsed.toMillis() + " ms.");
      }
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Verifies with an isolated PEP image deployment that a faulty Helm update with rollback-on-failure returns
   * automatically to the expected stable image within the configured rollback time budget.
   *
   * @param failedImageTag invalid PEP image tag used to trigger the faulty Helm update
   * @param containerName target PEP container name
   * @param deploymentName target PEP deployment name
   * @param expectedImage expected stable image after Helm rollback
   * @throws AssertionError if Helm does not fail as expected, rollback is not observed, or the time budget is exceeded
   */
  @Und(
      "prüfe mit einem isolierten Helm-Rollback-Nachweis, dass eine fehlerhafte Aktualisierung des PEP"
          + " Image-Tags {tigerResolvedString} für den Container {tigerResolvedString} das Deployment"
          + " {tigerResolvedString} nicht verändert und innerhalb des konfigurierten Rollback-Zeitbudgets"
          + " automatisch auf das Image {tigerResolvedString} zurückkehrt")
  @And(
      "verify faulty Helm update of PEP image tag {tigerResolvedString} for container {tigerResolvedString}"
          + " in deployment {tigerResolvedString} automatically returns to image {tigerResolvedString}"
          + " within the configured rollback time budget")
  public void verifyFaultyHelmPepImageUpdateAutomaticallyReturnsToImageWithinConfiguredSeconds(
      String failedImageTag, String containerName, String deploymentName, String expectedImage) {
    assertModificationIsAllowed();

    final int maxDurationSeconds = getConfiguredRollbackMaxSeconds();
    String namespace = getNamespace();
    rememberPepOriginalImageIfNeeded(namespace, deploymentName, containerName);
    long startNanos = System.nanoTime();
    CommandResult helmResult;
    try {
      var isolatedProofInstallTimeout = getIsolatedRollbackProofInstallTimeout();
      var isolatedProofTimeout = getIsolatedRollbackProofTimeout();
      SerenityReportUtils.addCustomData("Helm rollback system command timeout",
          "timeout=" + maxDurationSeconds + " s");
      helmResult = service.verifyIsolatedPepHelmRollbackOnFailure(
          namespace, containerName, expectedImage, failedImageTag, isolatedProofInstallTimeout, isolatedProofTimeout,
          maxDurationSeconds);
    } catch (AssertionError e) {
      if (isProcessTimeoutFailure(e)) {
        throw new AssertionError("Faulty Helm deployment did not finish within the configured rollback time budget.", e);
      }
      throw e;
    }

    Duration helmElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    if (helmResult.exitCode() == 0) {
      throw new AssertionError("Faulty Helm deployment unexpectedly succeeded for PEP image tag '"
          + failedImageTag + "'.\nstdout:\n" + helmResult.stdout() + "\nstderr:\n" + helmResult.stderr());
    }
    assertHelmFailureIsCompatibleWithRollbackProof(helmResult);

    CommandResult verifyResult = service.verifyDeploymentUpdate(namespace, deploymentName, containerName, expectedImage);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    SerenityReportUtils.addCustomData("Faulty Helm rollback-on-failure verification",
        "Isolated Helm command failed as expected with rollback-on-failure after " + helmElapsed.toMillis() + " ms. "
            + "Stable deployment remained unchanged and was verified after " + elapsed.toMillis() + " ms. "
            + "command=" + helmResult.command() + ", stdout=" + helmResult.stdout()
            + ", stderr=" + helmResult.stderr());
    if (verifyResult.exitCode() != 0) {
      throw new AssertionError("Stable image was not active after faulty Helm deployment failed with rollback-on-failure.\n"
          + "Helm stdout:\n" + helmResult.stdout() + "\nHelm stderr:\n" + helmResult.stderr()
          + "\nVerification stderr:\n" + verifyResult.stderr());
    }
    if (elapsed.compareTo(Duration.ofSeconds(maxDurationSeconds)) > 0) {
      throw new AssertionError("Helm rollback duration exceeded configured limit. Expected <= "
          + maxDurationSeconds + " s but was " + elapsed.toMillis() + " ms.");
    }
  }

  /**
   * Verifies in one timed window that a failed target image never becomes active and the deployment
   * automatically returns to the expected stable image.
   */
  @Und(
      "prüfe, dass das Image {tigerResolvedString} für den Container {tigerResolvedString} im Deployment"
          + " {tigerResolvedString} nicht aktiv wird und das Deployment innerhalb von {int} Sekunden nach"
          + " fehlgeschlagenem Update automatisch auf das Image {tigerResolvedString} zurückkehrt")
  @And(
      "verify image {tigerResolvedString} for container {tigerResolvedString} in deployment {tigerResolvedString}"
          + " does not become active and deployment automatically returns to image {tigerResolvedString} within"
          + " {int} seconds after failed update")
  public void verifyFailedImageDoesNotBecomeActiveAndDeploymentAutomaticallyReturnsToImageWithinSeconds(
      String failedImage, String containerName, String deploymentName, int maxDurationSeconds, String expectedImage)
      throws AssertionError {
    assertModificationIsAllowed();

    String namespace = getNamespace();
    try {
      CommandResult result = service.verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
          namespace, deploymentName, containerName, failedImage, expectedImage, maxDurationSeconds);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
      SerenityReportUtils.addCustomData("Failed rollout auto-recovery verification", result.stdout());
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Removes lingering failed rollout pods that still use an unexpected image after rollback handling.
   *
   * @throws AssertionError if cleanup fails
   */
  @Und("bereinige verbleibende fehlgeschlagene Rollout-Pods des Deployments {tigerResolvedString} für den Container {tigerResolvedString} mit anderem Image als {tigerResolvedString}")
  @And("cleanup remaining failed rollout pods of deployment {tigerResolvedString} for container {tigerResolvedString} with image different from {tigerResolvedString}")
  public void cleanupFailedRolloutPods(String deploymentName, String containerName, String expectedStableImage)
      throws AssertionError {
    assertModificationIsAllowed();

    String namespace = getNamespace();
    try {
      CommandResult result = service.cleanupFailedRolloutPods(namespace, deploymentName, containerName, expectedStableImage);
      if (result.exitCode() != 0) {
        throw new AssertionError(result.stderr());
      }
      SerenityReportUtils.addCustomData("Failed rollout pod cleanup", result.stdout());
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Reads the deployment namespace from Tiger configuration.
   *
   * @return configured deployment namespace
   * @throws AssertionError when the namespace is missing
   */
  private String getNamespace() {
    return ZetaDeploymentConfiguration.getNamespace();
  }

  /**
   * Reads the configured maximum rollback duration from Tiger configuration.
   *
   * @return configured maximum rollback duration in seconds
   * @throws AssertionError if the configured duration is not positive
   */
  private int getConfiguredRollbackMaxSeconds() {
    int maxDurationSeconds = TigerGlobalConfiguration.readIntegerOptional(ROLLBACK_MAX_SECONDS_CONFIG_KEY)
        .orElse(DEFAULT_ROLLBACK_MAX_SECONDS);
    if (maxDurationSeconds <= 0) {
      throw new AssertionError("Configured rollback time budget must be positive. Key "
          + ROLLBACK_MAX_SECONDS_CONFIG_KEY + "=" + maxDurationSeconds);
    }
    SerenityReportUtils.addCustomData("Configured rollback time budget",
        ROLLBACK_MAX_SECONDS_CONFIG_KEY + "=" + maxDurationSeconds + " s");
    return maxDurationSeconds;
  }

  /**
   * Reads the configured Helm timeout used by the rollback-on-failure command.
   *
   * @return Helm timeout value, for example {@code 5m}
   */
  private String getConfiguredHelmRollbackTimeout() {
    String timeout = TigerGlobalConfiguration.readStringOptional(HELM_ROLLBACK_TIMEOUT_CONFIG_KEY)
        .map(DeploymentModificationSteps::resolveHelmRollbackTimeout)
        .orElse(DEFAULT_HELM_ROLLBACK_TIMEOUT);
    SerenityReportUtils.addCustomData("Configured Helm rollback timeout",
        HELM_ROLLBACK_TIMEOUT_CONFIG_KEY + "=" + timeout);
    return timeout;
  }

  /**
   * Resolves a Helm timeout configuration value, falling back to the default for missing or blank values.
   *
   * @param configuredTimeout raw configured timeout
   * @return trimmed configured timeout or default timeout
   */
  static String resolveHelmRollbackTimeout(String configuredTimeout) {
    if (configuredTimeout == null || configuredTimeout.isBlank()) {
      return DEFAULT_HELM_ROLLBACK_TIMEOUT;
    }
    return configuredTimeout.trim();
  }

  /**
   * Reads the Helm timeout for installing the temporary stable release used by the isolated rollback proof.
   *
   * @return Helm timeout for the temporary stable release setup
   */
  private static String getIsolatedRollbackProofInstallTimeout() {
    String timeout = TigerGlobalConfiguration.readStringOptional(ISOLATED_HELM_ROLLBACK_PROOF_INSTALL_TIMEOUT_CONFIG_KEY)
        .map(DeploymentModificationSteps::resolveHelmRollbackTimeout)
        .orElse(DEFAULT_ISOLATED_HELM_ROLLBACK_PROOF_INSTALL_TIMEOUT);
    SerenityReportUtils.addCustomData("Isolated Helm rollback proof install timeout",
        ISOLATED_HELM_ROLLBACK_PROOF_INSTALL_TIMEOUT_CONFIG_KEY + "=" + timeout);
    return timeout;
  }

  /**
   * Reads the Helm timeout for the failed upgrade used by the isolated rollback proof.
   *
   * @return Helm timeout for the temporary failed upgrade
   */
  private static String getIsolatedRollbackProofTimeout() {
    String timeout = TigerGlobalConfiguration.readStringOptional(ISOLATED_HELM_ROLLBACK_PROOF_TIMEOUT_CONFIG_KEY)
        .map(DeploymentModificationSteps::resolveHelmRollbackTimeout)
        .orElse(DEFAULT_ISOLATED_HELM_ROLLBACK_PROOF_TIMEOUT);
    SerenityReportUtils.addCustomData("Isolated Helm rollback proof failure timeout",
        ISOLATED_HELM_ROLLBACK_PROOF_TIMEOUT_CONFIG_KEY + "=" + timeout);
    return timeout;
  }

  /**
   * Checks whether an assertion was caused by the system command timeout guard.
   *
   * @param throwable assertion or nested cause to inspect
   * @return {@code true} when a command timeout message is present in the cause chain
   */
  private static boolean isProcessTimeoutFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      String message = current.getMessage();
      if (message != null && message.contains(SystemCommandService.PROCESS_TIMEOUT_MESSAGE_PREFIX)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * Rejects command failures that prove the Helm invocation was invalid instead of proving a faulty update rollback.
   *
   * @param helmResult failed Helm deployment command result
   * @throws AssertionError if the command failed before a Helm deployment failure could be observed
   */
  private void assertHelmFailureIsCompatibleWithRollbackProof(CommandResult helmResult) {
    String combinedOutput = ((helmResult.stdout() == null ? "" : helmResult.stdout()) + "\n"
        + (helmResult.stderr() == null ? "" : helmResult.stderr())).toLowerCase();
    if (combinedOutput.contains("unknown flag")
        || combinedOutput.contains("no rule to make target")
        || combinedOutput.contains("no such file or directory")
        || combinedOutput.contains("command not found")
        || combinedOutput.contains("conflict occurred while applying object")
        || combinedOutput.contains("apply failed")
        || combinedOutput.contains("another operation")
        || combinedOutput.contains("unknown option")) {
      throw new AssertionError("Faulty Helm deployment command failed before a rollback could be proven.\nstdout:\n"
          + helmResult.stdout() + "\nstderr:\n" + helmResult.stderr());
    }
  }

  private void rememberPepOriginalImageIfNeeded(String namespace, String deploymentName, String containerName) {
    if (Hooks.getCapturedPepOriginalImage().isPresent()) {
      return;
    }
    if (!isPepDeploymentContainer(deploymentName, containerName)) {
      return;
    }

    CommandResult currentImageResult =
        service.getContainerImageReferenceForDeployment(namespace, deploymentName, containerName);
    if (currentImageResult.exitCode() != 0 || currentImageResult.stdout() == null || currentImageResult.stdout().isBlank()) {
      log.debug("Could not capture original PEP image before rollout change for deployment '{}' and container '{}': {}",
          deploymentName, containerName, currentImageResult.stderr());
      return;
    }

    Hooks.rememberPepOriginalImageIfAbsent(currentImageResult.stdout());
  }

  /**
   * Registers that this scenario owns the ConfigMap backup and removes stale backup data before the first modification.
   *
   * @param namespace Kubernetes namespace containing the ConfigMap
   * @param configMapName ConfigMap name
   */
  private void prepareConfigMapBackupForCurrentScenario(String namespace, String configMapName) {
    if (namespace == null || namespace.isBlank() || configMapName == null || configMapName.isBlank()) {
      return;
    }
    var normalizedConfigMapName = configMapName.trim();
    if (Hooks.getCapturedConfigMapBackups().contains(normalizedConfigMapName)) {
      return;
    }

    if (service.hasConfigMapBackup(namespace, normalizedConfigMapName)) {
      var deleteResult = service.deleteConfigMapBackup(namespace, normalizedConfigMapName);
      if (deleteResult.exitCode() != 0) {
        throw new AssertionError("Could not delete pre-existing ConfigMap backup '" + normalizedConfigMapName
            + "' before scenario-local backup capture.\n" + deleteResult.stderr());
      }
    }

    // Ownership must only be recorded after stale backup state can no longer be restored by cleanup.
    Hooks.rememberConfigMapBackupIfAbsent(normalizedConfigMapName);
  }

  private void rememberPepOriginalImageIfNamedVariableMatches(String variableName, String deploymentName,
      String containerName, String imageReference) {
    if (!"pep_original_image".equals(variableName)) {
      return;
    }
    if (isPepDeploymentContainer(deploymentName, containerName)) {
      Hooks.rememberPepOriginalImageIfAbsent(imageReference);
    }
  }

  private boolean isPepDeploymentContainer(String deploymentName, String containerName) {
    String configuredDeploymentName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElse("");
    String configuredContainerName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.containerName")
        .orElse("");
    return deploymentName != null
        && containerName != null
        && deploymentName.equals(configuredDeploymentName)
        && containerName.equals(configuredContainerName);
  }

  /**
   * Sends an empty HTTP GET request to the given URL.
   *
   * @param url target URL
   * @throws AssertionError if request execution fails
   */
  private void sendEmptyGet(String url) {
    try {
      new HttpGlueCode().sendEmptyRequest(Method.GET, new URI(url));
    } catch (Exception e) {
      throw new AssertionError("GET Request konnte nicht gesendet werden: " + url, e);
    }
  }

  /**
   * Resolves the HTTP status code from the current RBEL request/response pair.
   *
   * @return current response code as string
   * @throws AssertionError if no current or recent response code node is available
   */
  private String extractCurrentResponseCode() {
    var messageRetriever = RbelMessageRetriever.getInstance();
    List<RbelElement> messages = new ArrayList<>(messageRetriever.getMessageHistory().getMessages());
    if (messages.isEmpty()) {
      throw new AssertionError("No RBEL messages recorded - cannot determine response code.");
    }

    var request = messageRetriever.getCurrentRequest();
    if (request != null) {
      var response = findResponseForRequest(messages, request);
      if (response != null) {
        String responseCode = extractResponseCode(response);
        if (responseCode != null) {
          return responseCode;
        }
      }
    }

    for (int i = messages.size() - 1; i >= 0; i--) {
      String responseCode = extractResponseCode(messages.get(i));
      if (responseCode != null) {
        return responseCode;
      }
    }

    throw new AssertionError("No recent response code available - cannot determine response code.");
  }

  private void verifyRequestResponseVsDeploymentFinalization(String pathPattern, String rbelPath,
      String expectedValueRegex, String deploymentName, int timeoutSeconds, boolean expectResponseFirst,
      String expectedResponseCode, boolean expectedResponseCodeIsRegex) {
    String namespace = getNamespace();
    Instant deadline = Instant.now().plusSeconds(Math.abs(timeoutSeconds));
    Instant responseObservedAt = null;
    Instant rolloutObservedAt = null;
    boolean observedRolloutInProgressAfterResponse = false;
    boolean observedResponsePendingAfterRollout = false;
    String observedResponseCode = null;
    List<String> observations = new ArrayList<>();

    while (!Instant.now().isAfter(deadline)) {
      var messageHistory = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
      if (messageHistory == null || messageHistory.isEmpty()) {
        sleepBeforeNextPoll();
        continue;
      }

      List<RbelElement> messages = new ArrayList<>(messageHistory);
      var request = findFirstRequestMatchingPathAndNode(messages, pathPattern, rbelPath, expectedValueRegex);
      if (request == null) {
        sleepBeforeNextPoll();
        continue;
      }

      if (responseObservedAt == null) {
        var response = findResponseForRequest(messages, request);
        if (response != null) {
          responseObservedAt = Instant.now();
          observedResponseCode = extractResponseCode(response);
          observations.add("responseObserved=" + responseObservedAt + ", code=" + observedResponseCode);
        }
      }

      CommandResult rolloutResult = service.executeKubectlCommand(false, "rollout", "status",
          "deployment/" + deploymentName, "-n", namespace, "--timeout=1s");
      boolean rolloutFinalizedNow = rolloutResult.exitCode() == 0;
      if (!rolloutFinalizedNow && responseObservedAt != null && expectResponseFirst) {
        observedRolloutInProgressAfterResponse = true;
        observations.add("rolloutStillInProgressAfterResponse");
      }
      if (rolloutFinalizedNow && rolloutObservedAt == null) {
        rolloutObservedAt = Instant.now();
        observations.add("rolloutFinalized=" + rolloutObservedAt);
      } else if (rolloutObservedAt != null && responseObservedAt == null && !expectResponseFirst) {
        observedResponsePendingAfterRollout = true;
        observations.add("responseStillPendingAfterRollout");
      }

      if (responseObservedAt != null && observedResponseCode != null
          && !matchesExpectedResponseCode(observedResponseCode, expectedResponseCode, expectedResponseCodeIsRegex)) {
        throw new AssertionError("Observed response for matching request has unexpected status code " + observedResponseCode
            + " instead of " + (expectedResponseCodeIsRegex ? "regex " : "") + expectedResponseCode + ".");
      }

      if (hasExpectedRolloutOrderingEvidence(expectResponseFirst, responseObservedAt, rolloutObservedAt,
          observedRolloutInProgressAfterResponse, observedResponsePendingAfterRollout)) {
        SerenityReportUtils.addCustomData("Rollout completion order",
            "deployment=" + deploymentName + ", requestPath=" + pathPattern + ", responseAt=" + responseObservedAt
                + ", rolloutFinalizedAt=" + rolloutObservedAt + ", observations=" + observations);
        return;
      }

      sleepBeforeNextPoll();
    }

    throw new AssertionError("Timeout while comparing request completion and rollout finalization for deployment '"
        + deploymentName + "'. observations=" + observations);
  }

  static boolean hasExpectedRolloutOrderingEvidence(boolean expectResponseFirst, Instant responseObservedAt,
      Instant rolloutObservedAt, boolean observedRolloutInProgressAfterResponse,
      boolean observedResponsePendingAfterRollout) {
    if (responseObservedAt == null || rolloutObservedAt == null) {
      return false;
    }

    if (expectResponseFirst) {
      return observedRolloutInProgressAfterResponse || responseObservedAt.isBefore(rolloutObservedAt);
    }

    return observedResponsePendingAfterRollout || rolloutObservedAt.isBefore(responseObservedAt);
  }

  static boolean hasBackgroundUpdateEvidence(boolean imageVisibleObserved, boolean responseObserved) {
    return imageVisibleObserved && responseObserved;
  }

  private void verifyRequestResponseAfterPodDisappeared(String pathPattern, String rbelPath,
      String expectedValueRegex, String podName, int timeoutSeconds) {
    String namespace = getNamespace();
    Instant deadline = Instant.now().plusSeconds(Math.abs(timeoutSeconds));
    Instant responseObservedAt = null;
    Instant podGoneObservedAt = null;
    List<String> observations = new ArrayList<>();

    while (!Instant.now().isAfter(deadline)) {
      var messageHistory = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
      if (messageHistory == null || messageHistory.isEmpty()) {
        sleepBeforeNextPoll();
        continue;
      }

      List<RbelElement> messages = new ArrayList<>(messageHistory);
      var request = findFirstRequestMatchingPathAndNode(messages, pathPattern, rbelPath, expectedValueRegex);
      if (request == null) {
        sleepBeforeNextPoll();
        continue;
      }

      if (responseObservedAt == null) {
        var response = findResponseForRequest(messages, request);
        if (response != null) {
          responseObservedAt = Instant.now();
          String observedResponseCode = extractResponseCode(response);
          observations.add("responseObserved=" + responseObservedAt + ", code=" + observedResponseCode);
        }
      }

      if (podGoneObservedAt == null && podMissing(namespace, podName)) {
        podGoneObservedAt = Instant.now();
        boolean responsePendingWhenPodDisappeared = responseObservedAt == null;
        observations.add("podGone=" + podGoneObservedAt + ", pod=" + podName
            + ", responsePending=" + responsePendingWhenPodDisappeared);
        if (!responsePendingWhenPodDisappeared) {
          throw new AssertionError("Matching request was already answered before pod '" + podName + "' disappeared."
              + " observations=" + observations);
        }
      }

      if (responseObservedAt != null && podGoneObservedAt != null) {
        SerenityReportUtils.addCustomData("Pod takeover evidence",
            "pod=" + podName + ", requestPath=" + pathPattern + ", podGoneAt=" + podGoneObservedAt
                + ", responseObservedAt=" + responseObservedAt + ", observations=" + observations);
        return;
      }

      sleepBeforeNextPoll();
    }

    throw new AssertionError("Timeout while comparing request completion and disappearance of pod '" + podName
        + "'. observations=" + observations);
  }

  private void verifyDeploymentImageVisibilityBeforeRequestAnswered(String deploymentName, int timeoutSeconds,
      String expectedImage, String containerName, String pathPattern, String rbelPath, String expectedValueRegex) {
    String namespace = getNamespace();
    service.verifyRequirements(namespace);

    Instant deadline = Instant.now().plusSeconds(Math.abs(timeoutSeconds));
    Instant imageVisibleAt = null;
    Instant responseObservedAt = null;
    List<String> observations = new ArrayList<>();

    while (!Instant.now().isAfter(deadline)) {
      var messageHistory = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
      if (messageHistory == null || messageHistory.isEmpty()) {
        sleepBeforeNextPoll();
        continue;
      }

      List<RbelElement> messages = new ArrayList<>(messageHistory);
      var request = findFirstRequestMatchingPathAndNode(messages, pathPattern, rbelPath, expectedValueRegex);
      if (request == null) {
        sleepBeforeNextPoll();
        continue;
      }

      if (responseObservedAt == null) {
        var response = findResponseForRequest(messages, request);
        if (response != null) {
          responseObservedAt = Instant.now();
          String observedResponseCode = extractResponseCode(response);
          observations.add("responseObserved=" + responseObservedAt + ", code=" + observedResponseCode);
          assertObservedSuccessfulResponseCode(observedResponseCode, observations);
        }
      }

      if (imageVisibleAt == null && deploymentShowsPodWithImage(namespace, deploymentName, expectedImage, containerName)) {
        imageVisibleAt = Instant.now();
        boolean responsePendingWhenImageVisible = responseObservedAt == null;
        observations.add("imageVisible=" + imageVisibleAt + ", responsePending=" + responsePendingWhenImageVisible);
        if (!responsePendingWhenImageVisible) {
          throw new AssertionError("Matching request was already answered before rollout activity with image '"
              + expectedImage + "' became visible for deployment '" + deploymentName + "'. observations=" + observations);
        }
      }

      if (hasBackgroundUpdateEvidence(imageVisibleAt != null, responseObservedAt != null)) {
        SerenityReportUtils.addCustomData("Background rollout evidence",
            "deployment=" + deploymentName + ", expectedImage=" + expectedImage + ", requestPath=" + pathPattern
                + ", imageVisibleAt=" + imageVisibleAt + ", responseObservedAt=" + responseObservedAt
                + ", observations=" + observations);
        return;
      }

      sleepBeforeNextPoll();
    }

    throw new AssertionError("Timeout while comparing rollout image visibility and request completion for deployment '"
        + deploymentName + "'. observations=" + observations);
  }

  /**
   * Verifies that a request is pending before an image update is triggered, then proves that a pod with the new image
   * becomes visible before the request is answered.
   *
   * @param pathPattern RBEL path pattern for the original client request
   * @param rbelPath RBEL node path used to correlate the tracked request
   * @param expectedValueRegex expected value regex for the correlation node
   * @param newImage image that triggers the rollout
   * @param containerName container whose image is changed
   * @param deploymentName deployment whose image is changed
   * @param timeoutSeconds maximum verification time
   */
  private void verifyBackgroundImageVisibilityBeforeRequestAnsweredAfterImageUpdate(String pathPattern,
      String rbelPath, String expectedValueRegex, String newImage, String containerName, String deploymentName,
      int timeoutSeconds) {
    String namespace = getNamespace();
    service.verifyRequirements(namespace);

    Instant deadline = Instant.now().plusSeconds(Math.abs(timeoutSeconds));
    Instant clientRequestTransmittedAt = null;
    List<String> observations = new ArrayList<>();

    RbelElement initialClientRequest = null;
    while (!Instant.now().isAfter(deadline)) {
      var messageHistory = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
      if (messageHistory == null || messageHistory.isEmpty()) {
        sleepBeforeNextPoll();
        continue;
      }

      List<RbelElement> messages = new ArrayList<>(messageHistory);
      initialClientRequest = findFirstRequestMatchingPathAndNode(
          messages, pathPattern, rbelPath, expectedValueRegex);
      if (initialClientRequest == null) {
        sleepBeforeNextPoll();
        continue;
      }

      RbelElement prematureResponse = findResponseForRequest(messages, initialClientRequest);
      if (prematureResponse != null) {
        throw new AssertionError("Tracked request was already answered before rollout image update could be triggered."
            + " responseTransmittedAt=" + transmissionTimeOf(prematureResponse, "premature response")
            + ", observations=" + observations);
      }

      clientRequestTransmittedAt = transmissionTimeOf(initialClientRequest, "client request");
      observations.add("clientRequestPending=" + Instant.now() + ", clientRequestTransmittedAt="
          + clientRequestTransmittedAt);
      break;
    }

    if (initialClientRequest == null) {
      throw new AssertionError("Timeout while waiting for pending client request to path '" + pathPattern
          + "' before triggering rollout image update. observations=" + observations);
    }

    Instant imageUpdateTriggeredAt = Instant.now();
    observations.add("imageUpdateTriggered=" + imageUpdateTriggeredAt + ", image=" + newImage);
    final CompletableFuture<CommandResult> imageUpdate =
        startDeploymentImageUpdate(namespace, deploymentName, containerName, newImage);
    final BackgroundRolloutEvidence evidence = new BackgroundRolloutEvidence();

    try {
      while (!Instant.now().isAfter(deadline)) {
        var messageHistory = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
        if (messageHistory == null || messageHistory.isEmpty()) {
          sleepBeforeNextPoll();
          continue;
        }

        List<RbelElement> messages = new ArrayList<>(messageHistory);
        var clientRequest = findFirstRequestMatchingPathAndNode(messages, pathPattern, rbelPath, expectedValueRegex);
        if (clientRequest == null) {
          sleepBeforeNextPoll();
          continue;
        }

        var response = findResponseForRequest(messages, clientRequest);
        if (evidence.imageVisibleAt == null
            && deploymentShowsPodWithImage(namespace, deploymentName, newImage, containerName)) {
          evidence.imageVisibleAt = Instant.now();
          boolean responsePendingWhenImageVisible = response == null;
          observations.add("imageVisible=" + evidence.imageVisibleAt
              + ", responsePending=" + responsePendingWhenImageVisible);
          if (!responsePendingWhenImageVisible) {
            throw new AssertionError("Matching request was already answered before rollout activity with image '"
                + newImage + "' became visible for deployment '" + deploymentName + "'. observations=" + observations);
          }
        }

        if (evidence.responseObservedAt == null && response != null) {
          evidence.responseObservedAt = Instant.now();
          evidence.responseTransmittedAt = transmissionTimeOf(response, "client response");
          String observedResponseCode = extractResponseCode(response);
          observations.add("responseObserved=" + evidence.responseObservedAt + ", responseTransmittedAt="
              + evidence.responseTransmittedAt + ", code=" + observedResponseCode);
          assertObservedSuccessfulResponseCode(observedResponseCode, observations);
          if (evidence.imageVisibleAt == null) {
            throw new AssertionError("Tracked request was answered before rollout activity with image '"
                + newImage + "' became visible for deployment '" + deploymentName + "'. observations=" + observations);
          }
        }

        if (hasBackgroundUpdateEvidence(evidence.imageVisibleAt != null, evidence.responseObservedAt != null)) {
          CommandResult imageUpdateResult = imageUpdate.join();
          observations.add("imageUpdateExitCode=" + imageUpdateResult.exitCode());
          if (imageUpdateResult.exitCode() != 0) {
            throw new AssertionError("Failed to trigger rollout image update while proving background rollout."
                + " stderr=" + imageUpdateResult.stderr() + ", observations=" + observations);
          }
          SerenityReportUtils.addCustomData("Background rollout evidence",
              "deployment=" + deploymentName + ", expectedImage=" + newImage + ", requestPath=" + pathPattern
                  + ", clientRequestTransmittedAt=" + clientRequestTransmittedAt
                  + ", imageUpdateTriggeredAt=" + imageUpdateTriggeredAt
                  + ", imageVisibleAt=" + evidence.imageVisibleAt
                  + ", responseObservedAt=" + evidence.responseObservedAt
                  + ", responseTransmittedAt=" + evidence.responseTransmittedAt + ", observations=" + observations);
          return;
        }

        sleepBeforeNextPoll();
      }

      throw new AssertionError("Timeout while proving background rollout during pending request for deployment '"
          + deploymentName + "'. observations=" + observations);
    } finally {
      awaitDeploymentImageUpdateBeforeScenarioExit(imageUpdate, deploymentName, observations);
    }
  }

  /**
   * Verifies literal takeover evidence for a client request after the old pod is forcefully terminated.
   *
   * @param podName old pod that must be forcefully terminated
   * @param clientPathPattern RBEL path pattern for the original client request
   * @param upstreamPathPattern RBEL path pattern for the forwarded Fachdienst request
   * @param rbelPath RBEL node path used to identify the tracked request
   * @param expectedValueRegex expected node value regex
   * @param newImage image that triggers the rollout
   * @param containerName container whose image is changed
   * @param deploymentName deployment whose image is changed
   * @param timeoutSeconds maximum verification time
   */
  private void verifyLiteralRequestTakeoverEvidenceAfterForcedPodTermination(
      String podName, String clientPathPattern, String upstreamPathPattern, String rbelPath, String expectedValueRegex,
      String newImage, String containerName, String deploymentName, int timeoutSeconds) {
    Instant deadline = Instant.now().plusSeconds(Math.abs(timeoutSeconds));
    Instant clientRequestTransmittedAt = null;
    List<String> observations = new ArrayList<>();

    RbelElement initialClientRequest = null;
    while (!Instant.now().isAfter(deadline)) {
      var messageHistory = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
      if (messageHistory == null || messageHistory.isEmpty()) {
        sleepBeforeNextPoll();
        continue;
      }
      List<RbelElement> messages = new ArrayList<>(messageHistory);
      initialClientRequest = findFirstRequestMatchingPathAndNode(
          messages, clientPathPattern, rbelPath, expectedValueRegex);
      if (initialClientRequest == null) {
        sleepBeforeNextPoll();
        continue;
      }
      RbelElement prematureResponse = findResponseForRequest(messages, initialClientRequest);
      if (prematureResponse != null) {
        throw new AssertionError("Tracked request was already answered before rollout and forced pod termination"
            + " could be triggered. responseTransmittedAt=" + transmissionTimeOf(prematureResponse, "premature response")
            + ", observations=" + observations);
      }
      clientRequestTransmittedAt = transmissionTimeOf(initialClientRequest, "client request");
      observations.add("clientRequestPending=" + Instant.now() + ", clientRequestTransmittedAt="
          + clientRequestTransmittedAt);
      break;
    }
    if (initialClientRequest == null) {
      throw new AssertionError("Timeout while waiting for pending client request to path '" + clientPathPattern
          + "' before proving literal takeover. observations=" + observations);
    }

    String namespace = getNamespace();
    Instant imageUpdateTriggeredAt = Instant.now();
    observations.add("imageUpdateTriggered=" + imageUpdateTriggeredAt + ", image=" + newImage);
    final CompletableFuture<CommandResult> imageUpdate =
        startDeploymentImageUpdate(namespace, deploymentName, containerName, newImage);
    try {
      forceDeletePod(namespace, podName, observations);
      Instant forcedDeletionAt = Instant.now();
      observations.add("oldPodForceDeleted=" + forcedDeletionAt);
      final LiteralTakeoverEvidence evidence = new LiteralTakeoverEvidence();

      while (!Instant.now().isAfter(deadline)) {
        var messageHistory = RbelMessageRetriever.getInstance().getMessageHistory().getMessages();
        if (messageHistory == null || messageHistory.isEmpty()) {
          sleepBeforeNextPoll();
          continue;
        }

        List<RbelElement> messages = new ArrayList<>(messageHistory);
        var clientRequest = findFirstRequestMatchingPathAndNode(
            messages, clientPathPattern, rbelPath, expectedValueRegex);
        if (clientRequest == null) {
          sleepBeforeNextPoll();
          continue;
        }

        long upstreamRequestCount = countRequestMessagesMatchingPathAndNode(
            messages, upstreamPathPattern, rbelPath, expectedValueRegex);
        if (upstreamRequestCount > 1) {
          throw new AssertionError("Observed " + upstreamRequestCount + " matching Fachdienst requests while proving"
              + " literal request takeover. A retry does not prove takeover. observations=" + observations);
        }

        if (evidence.upstreamObservedAt == null && upstreamRequestCount == 1) {
          evidence.upstreamObservedAt = Instant.now();
          var upstreamRequest = findFirstRequestMatchingPathAndNode(
              messages, upstreamPathPattern, rbelPath, expectedValueRegex);
          evidence.upstreamTransmittedAt = transmissionTimeOf(upstreamRequest, "upstream request");
          observations.add("upstreamObserved=" + evidence.upstreamObservedAt + ", upstreamTransmittedAt="
              + evidence.upstreamTransmittedAt);
        }

        if (evidence.podGoneObservedAt == null && podMissing(namespace, podName)) {
          evidence.podGoneObservedAt = Instant.now();
          observations.add("oldPodGone=" + evidence.podGoneObservedAt);
        }

        if (evidence.rolloutFinalizedAt == null) {
          CommandResult rolloutResult = service.executeKubectlCommand(false, "rollout", "status",
              "deployment/" + deploymentName, "-n", namespace, "--timeout=1s");
          if (rolloutResult.exitCode() == 0) {
            evidence.rolloutFinalizedAt = Instant.now();
            observations.add("rolloutFinalized=" + evidence.rolloutFinalizedAt);
            if (!hasLiteralTakeoverFinalizationTiming(evidence.podGoneObservedAt, evidence.rolloutFinalizedAt)) {
              throw new AssertionError("Deployment '" + deploymentName + "' finalized before old pod '" + podName
                  + "' disappeared while proving literal request takeover. observations=" + observations);
            }
          }
        }

        if (evidence.newPepWithImageReadyObservedAt == null) {
          evidence.newPepWithImagePodName =
              findReadyDeploymentPodWithImage(namespace, deploymentName, newImage, containerName);
          if (evidence.newPepWithImagePodName != null) {
            evidence.newPepWithImageReadyObservedAt = Instant.now();
            observations.add("newPepWithImageReady=" + evidence.newPepWithImageReadyObservedAt + ", pod="
                + evidence.newPepWithImagePodName);
          }
        }

        if (evidence.responseObservedAt == null) {
          var response = findResponseForRequest(messages, clientRequest);
          if (response != null) {
            evidence.responseObservedAt = Instant.now();
            evidence.responseTransmittedAt = transmissionTimeOf(response, "client response");
            String observedResponseCode = extractResponseCode(response);
            observations.add("responseObserved=" + evidence.responseObservedAt + ", responseTransmittedAt="
                + evidence.responseTransmittedAt + ", code=" + observedResponseCode);
            assertObservedSuccessfulResponseCode(observedResponseCode, observations);
            if (!hasLiteralTakeoverResponseTiming(evidence.responseTransmittedAt, forcedDeletionAt)) {
              throw new AssertionError("Tracked request response was transmitted before the old pod was force-deleted."
                  + " responseTransmittedAt=" + evidence.responseTransmittedAt
                  + ", oldPodForceDeletedAt=" + forcedDeletionAt + ", observations=" + observations);
            }
            if (!hasLiteralTakeoverNewPepTiming(
                clientRequestTransmittedAt, evidence.newPepWithImageReadyObservedAt, evidence.responseTransmittedAt)) {
              throw new AssertionError("Tracked request response was transmitted before a new ready PEP pod with image '"
                  + newImage + "' was observed after the client request. clientRequestTransmittedAt="
                  + clientRequestTransmittedAt + ", newPepWithImageReadyObservedAt="
                  + evidence.newPepWithImageReadyObservedAt
                  + ", responseTransmittedAt=" + evidence.responseTransmittedAt + ", observations=" + observations);
            }
          }
        }

        if (evidence.podGoneObservedAt != null && evidence.rolloutFinalizedAt != null
            && evidence.newPepWithImageReadyObservedAt != null && evidence.responseObservedAt != null) {
          long finalUpstreamRequestCount = countRequestMessagesMatchingPathAndNode(
              messages, upstreamPathPattern, rbelPath, expectedValueRegex);
          if (finalUpstreamRequestCount != 1) {
            throw new AssertionError("Expected exactly one matching Fachdienst request while proving literal request"
                + " takeover, but observed " + finalUpstreamRequestCount + ". observations=" + observations);
          }
          CommandResult imageUpdateResult = imageUpdate.join();
          observations.add("imageUpdateExitCode=" + imageUpdateResult.exitCode());
          if (imageUpdateResult.exitCode() != 0) {
            throw new AssertionError("Failed to trigger rollout image update while proving literal request takeover."
                + " stderr=" + imageUpdateResult.stderr() + ", observations=" + observations);
          }
          SerenityReportUtils.addCustomData("Literal request takeover after forced pod termination",
              "pod=" + podName + ", clientPath=" + clientPathPattern + ", upstreamPath=" + upstreamPathPattern
                  + ", clientRequestTransmittedAt=" + clientRequestTransmittedAt + ", upstreamObservedAt="
                  + evidence.upstreamObservedAt + ", upstreamTransmittedAt=" + evidence.upstreamTransmittedAt
                  + ", imageUpdateTriggeredAt=" + imageUpdateTriggeredAt + ", oldPodForceDeletedAt="
                  + forcedDeletionAt + ", oldPodGoneAt=" + evidence.podGoneObservedAt + ", newPepWithImagePod="
                  + evidence.newPepWithImagePodName + ", rolloutFinalizedAt=" + evidence.rolloutFinalizedAt
                  + ", newPepWithImageReadyObservedAt=" + evidence.newPepWithImageReadyObservedAt
                  + ", responseObservedAt=" + evidence.responseObservedAt + ", responseTransmittedAt="
                  + evidence.responseTransmittedAt + ", upstreamRequestCount=" + finalUpstreamRequestCount
                  + ", observations=" + observations);
          return;
        }

        sleepBeforeNextPoll();
      }

      throw new AssertionError("Timeout while proving literal request takeover after forced termination of pod '"
          + podName + "'. observations=" + observations);
    } finally {
      awaitDeploymentImageUpdateBeforeScenarioExit(imageUpdate, deploymentName, observations);
    }
  }

  /**
   * Checks whether the response was transmitted after the old pod force-delete command completed.
   *
   * @param responseTransmittedAt RBEL transmission time of the tracked client response
   * @param oldPodForceDeletedAt local time after the old pod force-delete command completed
   * @return {@code true} when the response is after the forced pod termination
   */
  static boolean hasLiteralTakeoverResponseTiming(Instant responseTransmittedAt, Instant oldPodForceDeletedAt) {
    return responseTransmittedAt != null
        && oldPodForceDeletedAt != null
        && responseTransmittedAt.isAfter(oldPodForceDeletedAt);
  }

  /**
   * Checks whether the new ready PEP observation is strictly between the client request and its response.
   *
   * @param clientRequestTransmittedAt RBEL transmission time of the tracked client request
   * @param newPepWithImageReadyObservedAt local time when a ready PEP pod with the target image was observed
   * @param responseTransmittedAt RBEL transmission time of the tracked client response
   * @return {@code true} when the new PEP was observed after the request and before the response
   */
  static boolean hasLiteralTakeoverNewPepTiming(
      Instant clientRequestTransmittedAt,
      Instant newPepWithImageReadyObservedAt,
      Instant responseTransmittedAt) {
    return clientRequestTransmittedAt != null
        && newPepWithImageReadyObservedAt != null
        && responseTransmittedAt != null
        && newPepWithImageReadyObservedAt.isAfter(clientRequestTransmittedAt)
        && responseTransmittedAt.isAfter(newPepWithImageReadyObservedAt);
  }

  /**
   * Checks whether rollout finalization was observed after the old pod disappeared.
   *
   * @param oldPodGoneAt local time when the old pod was no longer visible
   * @param rolloutFinalizedAt local time when Kubernetes reported rollout finalization
   * @return {@code true} when finalization follows the takeover evidence
   */
  static boolean hasLiteralTakeoverFinalizationTiming(Instant oldPodGoneAt, Instant rolloutFinalizedAt) {
    return oldPodGoneAt != null
        && rolloutFinalizedAt != null
        && rolloutFinalizedAt.isAfter(oldPodGoneAt);
  }

  /**
   * Starts an empty GET request through the Tiger HTTP client without waiting for the response.
   *
   * @param requestUrl URL to call
   * @param headerName correlation header name
   * @param headerValue correlation header value
   * @return HTTP glue instance holding the temporary default header until the caller clears it
   */
  private HttpGlueCode startEmptyGetRequestWithHeader(String requestUrl, String headerName,
      String headerValue) {
    var httpGlueCode = new HttpGlueCode();
    try {
      httpGlueCode.setDefaultHeader(headerName, headerValue);
      httpGlueCode.sendEmptyRequestNonBlocking(Method.GET, new URI(requestUrl));
      return httpGlueCode;
    } catch (Exception e) {
      httpGlueCode.clearDefaultHeader(headerName);
      throw new AssertionError("Failed to start asynchronous client request to '" + requestUrl + "'.", e);
    }
  }

  /**
   * Extracts the RBEL transmission time of a message.
   *
   * @param message RBEL message
   * @param context human-readable failure context
   * @return transmission time
   */
  private Instant transmissionTimeOf(RbelElement message, String context) {
    if (message == null) {
      throw new AssertionError("Missing RBEL message for " + context + ".");
    }
    return message.getFacet(RbelMessageTimingFacet.class)
        .orElseThrow(() -> new AssertionError("Missing RbelMessageTimingFacet for " + context + "."))
        .getTransmissionTime()
        .toInstant();
  }

  /**
   * Forcefully deletes a pod and fails the current scenario when Kubernetes cannot delete it.
   *
   * @param namespace Kubernetes namespace
   * @param podName pod name
   * @param observations observation log enriched with the command result
   */
  private void forceDeletePod(String namespace, String podName, List<String> observations) {
    CommandResult deleteResult = service.executeKubectlCommand(false,
        "-n", namespace, "delete", "pod", podName, "--grace-period=0", "--force", "--wait=true");
    observations.add("forceDeleteExitCode=" + deleteResult.exitCode());
    if (deleteResult.exitCode() != 0) {
      throw new AssertionError("Failed to force-delete pod '" + podName + "'. stderr=" + deleteResult.stderr()
          + ", observations=" + observations);
    }
  }

  /**
   * Starts a deployment image update without blocking the takeover proof before the old pod is force-deleted.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name
   * @param containerName container whose image is changed
   * @param newImage target image
   * @return future containing the image update command result
   */
  private CompletableFuture<CommandResult> startDeploymentImageUpdate(String namespace, String deploymentName,
      String containerName, String newImage) {
    rememberPepOriginalImageIfNeeded(namespace, deploymentName, containerName);
    return CompletableFuture.supplyAsync(() -> {
      try {
        return service.setDeploymentContainerImage(namespace, deploymentName, containerName, newImage);
      } catch (Exception e) {
        return new CommandResult(
            List.of("kubectl", "set", "image", "deployment/" + deploymentName, containerName + "=" + newImage),
            1,
            "",
            "Could not update deployment image: " + e.getMessage()
        );
      }
    });
  }

  /**
   * Waits for an already started deployment image update before the scenario may enter after-hook cleanup.
   *
   * <p>This method intentionally does not throw, so an evidence failure from the scenario is not hidden by
   * a cleanup-synchronization failure. The after hook still restores deployment state afterwards.</p>
   *
   * @param imageUpdate asynchronous deployment image update command
   * @param deploymentName deployment being modified
   * @param observations mutable observation log for diagnostics
   */
  static void awaitDeploymentImageUpdateBeforeScenarioExit(
      CompletableFuture<CommandResult> imageUpdate, String deploymentName, List<String> observations) {
    if (imageUpdate == null || imageUpdate.isDone()) {
      return;
    }

    observations.add("waitingForImageUpdateCompletionBeforeScenarioExit=" + Instant.now());
    try {
      CommandResult imageUpdateResult = imageUpdate.join();
      observations.add("imageUpdateCompletedBeforeScenarioExitExitCode=" + imageUpdateResult.exitCode());
    } catch (RuntimeException e) {
      observations.add("imageUpdateCompletionBeforeScenarioExitFailed=" + e.getClass().getSimpleName()
          + ": " + e.getMessage());
      log.warn("Image update for deployment '{}' did not complete cleanly before scenario exit.",
          deploymentName, e);
    }
  }

  private void sleepBeforeNextPoll() {
    try {
      TimeUnit.MILLISECONDS.sleep(DEFAULT_POLL_INTERVAL_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Polling wurde unterbrochen", e);
    }
  }

  private void assertObservedSuccessfulResponseCode(String observedResponseCode, List<String> observations) {
    if (observedResponseCode != null && !"200".equals(observedResponseCode)) {
      throw new AssertionError("Observed response for matching request has unexpected status code "
          + observedResponseCode + " instead of 200. observations=" + observations);
    }
  }

  /**
   * Finds the response associated with the given request from a list of RBEL messages.
   *
   * @param messages recorded RBEL messages
   * @param request current request message
   * @return matching response or {@code null} if none is found
   */
  private RbelElement findResponseForRequest(Collection<RbelElement> messages, RbelElement request) {
    return messages.isEmpty() ? null : messages.stream()
        .map(message -> message.getFacet(TracingMessagePairFacet.class).orElse(null))
        .filter(pairFacet -> pairFacet != null && pairFacet.getRequest().getUuid().equals(request.getUuid()))
        .findFirst()
        .map(TracingMessagePairFacet::getResponse)
        .orElse(null);
  }

  private RbelElement findFirstRequestMatchingPathAndNode(Collection<RbelElement> messages, String pathPattern,
      String rbelPath, String expectedValueRegex) {
    return messages.stream()
        .filter(Objects::nonNull)
        .filter(message -> matchesPath(message, pathPattern))
        .filter(message -> matchesNodeValue(message, rbelPath, expectedValueRegex))
        .findFirst()
        .orElse(null);
  }

  /**
   * Counts HTTP request messages matching the given path and node value.
   *
   * @param messages recorded RBEL messages
   * @param pathPattern path pattern to match
   * @param rbelPath RBEL node path used for correlation
   * @param expectedValueRegex expected node value regex
   * @return number of matching request messages
   */
  private long countRequestMessagesMatchingPathAndNode(Collection<RbelElement> messages, String pathPattern,
      String rbelPath, String expectedValueRegex) {
    return messages.stream()
        .filter(Objects::nonNull)
        .filter(this::isHttpRequestMessage)
        .filter(message -> matchesPath(message, pathPattern))
        .filter(message -> matchesNodeValue(message, rbelPath, expectedValueRegex))
        .count();
  }

  /**
   * Checks whether an RBEL element represents an HTTP request.
   *
   * @param message RBEL message
   * @return {@code true} if the message contains an HTTP method node
   */
  private boolean isHttpRequestMessage(RbelElement message) {
    return !message.findRbelPathMembers("$.method").isEmpty();
  }

  private boolean matchesPath(RbelElement message, String pathPattern) {
    return message.findRbelPathMembers("$.path").stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .anyMatch(actualPath -> actualPath.equals(pathPattern) || Pattern.compile(pathPattern).matcher(actualPath).find());
  }

  private boolean matchesNodeValue(RbelElement message, String rbelPath, String expectedValueRegex) {
    Pattern pattern = Pattern.compile(expectedValueRegex);
    return message.findRbelPathMembers(rbelPath).stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .anyMatch(actualValue -> pattern.matcher(actualValue).find());
  }

  /**
   * Extracts the response code from an RBEL message if present.
   *
   * @param message RBEL message that may represent a response
   * @return trimmed response code, or {@code null} if no response code node exists
   */
  private String extractResponseCode(RbelElement message) {
    var responseCodeNodes = message.findRbelPathMembers("$.responseCode");
    if (responseCodeNodes.isEmpty()) {
      return null;
    }

    String responseCode = responseCodeNodes.getFirst().getRawStringContent();
    return responseCode == null ? null : responseCode.trim();
  }

  static boolean matchesExpectedResponseCode(String observedResponseCode, String expectedResponseCode,
      boolean expectedResponseCodeIsRegex) {
    if (observedResponseCode == null || expectedResponseCode == null) {
      return false;
    }

    return expectedResponseCodeIsRegex
        ? Pattern.compile(expectedResponseCode).matcher(observedResponseCode).matches()
        : expectedResponseCode.equals(observedResponseCode);
  }

  private boolean isDeploymentRolloutFinalized(String namespace, String deploymentName) {
    CommandResult result = service.executeKubectlCommand("-n", namespace, "get", "deployment", deploymentName, "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("kubectl get deployment failed: " + result.stderr());
    }

    try {
      return isDeploymentRolloutFinalized(JSON.readTree(result.stdout()));
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse kubectl deployment JSON output.", e);
    }
  }

  static boolean isDeploymentRolloutFinalized(JsonNode deployment) {
    int desiredReplicas = deployment.path("spec").path("replicas").asInt(1);
    long generation = deployment.path("metadata").path("generation").asLong(0L);
    long observedGeneration = deployment.path("status").path("observedGeneration").asLong(0L);
    int updatedReplicas = deployment.path("status").path("updatedReplicas").asInt(0);
    int currentReplicas = deployment.path("status").path("replicas").asInt(0);
    int availableReplicas = deployment.path("status").path("availableReplicas").asInt(0);

    return observedGeneration >= generation
        && updatedReplicas == desiredReplicas
        && currentReplicas == desiredReplicas
        && availableReplicas == desiredReplicas
        && hasNewReplicaSetAvailableCondition(deployment.path("status").path("conditions"));
  }

  private static boolean hasNewReplicaSetAvailableCondition(JsonNode conditions) {
    if (!conditions.isArray()) {
      return false;
    }

    for (JsonNode condition : conditions) {
      if ("Progressing".equals(condition.path("type").asText(""))
          && "True".equalsIgnoreCase(condition.path("status").asText(""))
          && "NewReplicaSetAvailable".equals(condition.path("reason").asText(""))) {
        return true;
      }
    }

    return false;
  }

  /**
   * Resolves exactly one ready pod name for the given deployment prefix.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name used as pod name prefix
   * @return ready pod name
   * @throws AssertionError if no single ready pod can be determined
   */
  private String requireSingleReadyPodNameByPrefix(String namespace, String deploymentName) {
    String podName = findSingleReadyPodNameByPrefix(namespace, deploymentName);
    if (podName == null) {
      throw new AssertionError("Kein einzelner Ready-Pod für Deployment '" + deploymentName + "' gefunden.");
    }
    return podName;
  }

  /**
   * Searches the namespace for exactly one ready pod whose name starts with the deployment prefix.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name used as pod name prefix
   * @return ready pod name, or {@code null} if none or multiple ready pods match
   * @throws AssertionError if the pod list cannot be queried or parsed
   */
  private String findSingleReadyPodNameByPrefix(String namespace, String deploymentName) {
    List<String> matchingReadyPodNames = findReadyPodNamesByPrefix(namespace, deploymentName);
    return matchingReadyPodNames.size() == 1 ? matchingReadyPodNames.getFirst() : null;
  }

  private List<String> findReadyPodNamesByPrefix(String namespace, String deploymentName) {
    CommandResult result = service.executeKubectlCommand("-n", namespace, "get", "pods", "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("kubectl get pods failed: " + result.stderr());
    }

    JsonNode items;
    try {
      items = JSON.readTree(result.stdout()).path("items");
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse kubectl pods JSON output.", e);
    }

    List<String> matchingReadyPodNames = new ArrayList<>();
    for (JsonNode pod : items) {
      String podName = pod.path("metadata").path("name").asText("");
      if (!podName.startsWith(deploymentName + "-")) {
        continue;
      }
      if (isPodReady(pod)) {
        matchingReadyPodNames.add(podName);
      }
    }

    return matchingReadyPodNames;
  }

  private boolean podMissing(String namespace, String podName) {
    CommandResult result = service.executeKubectlCommand("-n", namespace, "get", "pods", "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("kubectl get pods failed: " + result.stderr());
    }

    JsonNode items;
    try {
      items = JSON.readTree(result.stdout()).path("items");
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse kubectl pods JSON output.", e);
    }

    for (JsonNode pod : items) {
      if (podName.equals(pod.path("metadata").path("name").asText(""))) {
        return false;
      }
    }
    return true;
  }

  private boolean deploymentShowsPodWithImage(String namespace, String deploymentName, String expectedImage,
      String containerName) {
    return findDeploymentPodWithImage(namespace, deploymentName, expectedImage, containerName) != null;
  }

  private String findDeploymentPodWithImage(String namespace, String deploymentName, String expectedImage,
      String containerName) {
    return findDeploymentPodWithImage(namespace, deploymentName, expectedImage, containerName, false);
  }

  /**
   * Finds a deployment pod that uses the expected image for the selected container.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name used as pod name prefix
   * @param expectedImage expected container image
   * @param containerName container name
   * @param requireReady whether the pod must be ready
   * @return matching pod name, or {@code null} if none matches
   */
  private String findDeploymentPodWithImage(String namespace, String deploymentName, String expectedImage,
      String containerName, boolean requireReady) {
    CommandResult result = service.executeKubectlCommand("-n", namespace, "get", "pods", "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("kubectl get pods failed: " + result.stderr());
    }

    JsonNode items;
    try {
      items = JSON.readTree(result.stdout()).path("items");
    } catch (JacksonException e) {
      throw new AssertionError("Failed to parse kubectl pods JSON output.", e);
    }

    for (JsonNode pod : items) {
      String podName = pod.path("metadata").path("name").asText("");
      if (!podName.startsWith(deploymentName + "-")) {
        continue;
      }
      if (requireReady && !isPodReady(pod)) {
        continue;
      }

      String foundImage = extractContainerImage(pod, containerName);
      if (expectedImage.equals(foundImage)) {
        return podName;
      }
    }

    return null;
  }

  /**
   * Finds a ready deployment pod that uses the expected image for the selected container.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name used as pod name prefix
   * @param expectedImage expected container image
   * @param containerName container name
   * @return matching ready pod name, or {@code null} if none matches
   */
  private String findReadyDeploymentPodWithImage(String namespace, String deploymentName, String expectedImage,
      String containerName) {
    return findDeploymentPodWithImage(namespace, deploymentName, expectedImage, containerName, true);
  }

  private String extractContainerImage(JsonNode pod, String containerName) {
    JsonNode containers = pod.path("spec").path("containers");
    if (!containers.isArray()) {
      return "";
    }

    for (JsonNode container : containers) {
      if (containerName.equals(container.path("name").asText(""))) {
        return container.path("image").asText("");
      }
    }

    return "";
  }

  /**
   * Checks whether all reported container statuses of a pod are ready.
   *
   * @param pod pod JSON node returned by Kubernetes
   * @return {@code true} if all container statuses are ready, otherwise {@code false}
   */
  private boolean isPodReady(JsonNode pod) {
    JsonNode containerStatuses = pod.path("status").path("containerStatuses");
    if (!containerStatuses.isArray() || containerStatuses.isEmpty()) {
      return false;
    }

    for (JsonNode status : containerStatuses) {
      if (!status.path("ready").asBoolean(false)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Builds the request object used to enable or disable PoPP verification in deployment configuration.
   *
   * @return request payload for PoPP verification toggling
   * @throws AssertionError when required configuration values are missing
   */
  private ZetaPoppTokenToggleRequest getPoppTokenRequest() {
    String nginxPoppRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.popp.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.popp.nginxConfigRegex"));

    String nginxPoppEnabled = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.popp.nginxConfigEnabled")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.popp.nginxConfigEnabled"));

    String nginxPoppDisabled = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.popp.nginxConfigDisabled")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.popp.nginxConfigDisabled"));

    return new ZetaPoppTokenToggleRequest(
        nginxPoppRegex,
        nginxPoppEnabled,
        nginxPoppDisabled
    );
  }

  /**
   * Builds the request object used to enable or disable client-data forwarding in deployment configuration.
   *
   * @return request payload for client-data forwarding toggling
   * @throws AssertionError when required configuration values are missing
   */
  private ZetaClientDataForwardingToggleRequest getClientDataForwardingRequest() {
    String nginxClientDataForwardingRegex = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.clientDataForwarding.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.clientDataForwarding.nginxConfigRegex"));

    String nginxClientDataForwardingEnabled = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.clientDataForwarding.nginxConfigEnabled")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.clientDataForwarding.nginxConfigEnabled"));

    String nginxClientDataForwardingDisabled = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.clientDataForwarding.nginxConfigDisabled")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.clientDataForwarding.nginxConfigDisabled"));

    return new ZetaClientDataForwardingToggleRequest(
        nginxClientDataForwardingRegex,
        nginxClientDataForwardingEnabled,
        nginxClientDataForwardingDisabled
    );
  }

  /**
   * Builds the request object used to configure required scopes in the deployment.
   *
   * @return request payload for required-scope modification
   * @throws AssertionError when required configuration values are missing
   */
  private ZetaRequiredScopesRequest getRequiredScopesRequest() {
    String nginxRequiredScopesRegex = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.requiredScopes.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.requiredScopes.nginxConfigRegex"));

    String nginxRequiredScopesTemplate = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.requiredScopes.nginxConfigTemplate")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.requiredScopes.nginxConfigTemplate"));

    return new ZetaRequiredScopesRequest(nginxRequiredScopesRegex, nginxRequiredScopesTemplate);
  }

  /**
   * Builds the request object used to modify PoPP token validity in deployment configuration.
   *
   * @return request payload for PoPP validity modification
   * @throws AssertionError when required configuration values are missing
   */
  private ZetaPoppTokenValidityRequest getPoppTokenValidityRequest() {
    String nginxPoppValidityRegex = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.popp.validity.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.popp.validity.nginxConfigRegex"));

    String nginxPoppValidityAnchorRegex = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.popp.validity.nginxConfigAnchorRegex")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.popp.validity.nginxConfigAnchorRegex"));

    String nginxPoppValidityTemplate = TigerGlobalConfiguration
        .readStringOptional("zetaDeploymentConfig.pep.nginx.popp.validity.nginxConfigTemplate")
        .orElseThrow(() -> new AssertionError(
            "Missing variable: zetaDeploymentConfig.pep.nginx.popp.validity.nginxConfigTemplate"));

    return new ZetaPoppTokenValidityRequest(
        nginxPoppValidityRegex,
        nginxPoppValidityAnchorRegex,
        nginxPoppValidityTemplate
    );
  }

  /**
   * Reads ordered key segments from Tiger configuration using numeric suffixes (.0, .1, ...).
   *
   * @param baseKeyPath base configuration key containing indexed segments
   * @return ordered key segments until the first missing index
   * @throws AssertionError when configured depth exceeds {@link #MAX_KEY_DEPTH}
   */
  private String[] parseKeySegments(String baseKeyPath) throws AssertionError {
    // since the order of lists defined in tiger.yaml files is not retained this key-based custom order is required
    int i = 0;
    var result = new ArrayList<String>();
    while (i < MAX_KEY_DEPTH) {
      String segment = TigerGlobalConfiguration.readStringOptional(baseKeyPath + "." + i).orElse(null);
      if (segment == null) {
        return result.toArray(new String[0]);
      } else {
        result.add(segment);
      }
      i++;
    }
    throw new AssertionError("Max depth for configuration keys exceeded for key: " + baseKeyPath);
  }
}
