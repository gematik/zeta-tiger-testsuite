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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentConfigurationServiceFactory;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaDisableAslRequest;
import de.gematik.zeta.services.model.ZetaEnableAslRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.restassured.http.Method;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber step definitions for modifications of the Zeta Guard deployment.
 */
@Slf4j
@SuppressWarnings("unused")
public class DeploymentModificationSteps {

  private static final int MAX_KEY_DEPTH = 20;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long DEFAULT_POLL_INTERVAL_MILLIS = 250L;

  private final ZetaDeploymentConfigurationService service;

  /**
   * Creates deployment modification steps backed by the default deployment configuration service instance.
   */
  public DeploymentModificationSteps() {
    this(ZetaDeploymentConfigurationServiceFactory.getInstance());
  }

  /**
   * Creates deployment modification steps backed by the provided deployment configuration service.
   *
   * @param service service used to modify and inspect the deployment
   */
  DeploymentModificationSteps(final ZetaDeploymentConfigurationService service) {
    this.service = service;
  }

  /**
   * Cucumber step to disable the Additional Security Layer in a Zeta Guard deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("deaktiviere den Additional Security Layer im Zeta Deployment")
  @And("deactivate the Additional Security Layer in Zeta deployment")
  public void disableAsl() throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaDisableAslRequest request = getDisableAslRequest();
    try {
      service.disableAsl(details, request);
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
   * Cucumber step to enable the Additional Security Layer in a Zeta Guard
   * deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Gegebensei("aktiviere den Additional Security Layer im Zeta Deployment")
  @Given("activate the Additional Security Layer in Zeta deployment")
  public void enableAsl() throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaEnableAslRequest request = getEnableAslRequest();
    try {
      service.enableAsl(details, request);
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
   * Cucumber step to disable the PoPP token verification for a route in a ZETA Guard deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("deaktiviere die PoPP Token Verifikation für die Route {tigerResolvedString} im ZETA Deployment")
  @And("deactivate PoPP token verification for route {tigerResolvedString} in ZETA deployment")
  public void disablePoppTokenVerification(String targetRoute) throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaPoppTokenToggleRequest request = getPoppTokenRequest();
    try {
      service.disablePoppVerification(details, request, targetRoute);
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
   * Cucumber step to enable the PoPP token verification for a route in a ZETA Guard deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("aktiviere die PoPP Token Verifikation für die Route {tigerResolvedString} im ZETA Deployment")
  @And("activate PoPP token verification for route {tigerResolvedString} in ZETA deployment")
  public void enablePoppTokenVerification(String targetRoute) throws AssertionError {
    assertModificationIsAllowed();

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaPoppTokenToggleRequest request = getPoppTokenRequest();
    try {
      service.enablePoppVerification(details, request, targetRoute);
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
    service.verifyRequirements(namespace);

    CommandResult result = service.executeKubectlCommand("-n", namespace, "get", "pods", "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("kubectl get pods failed: " + result.stderr());
    }

    JsonNode items;
    try {
      items = JSON.readTree(result.stdout()).path("items");
    } catch (IOException e) {
      throw new AssertionError("Failed to parse kubectl pods JSON output.", e);
    }
    if (!items.isArray() || items.isEmpty()) {
      throw new AssertionError("kubectl get pods -o json returned no pods in namespace '" + namespace + "'.");
    }

    String requestedHeader = headerName == null ? "" : headerName.trim();
    String filter = rowFilter == null ? "" : rowFilter.trim();
    JsonNode matchingPod = null;
    String matchedRowProjection = null;
    for (JsonNode pod : items) {
      String rowProjection = buildPodWideProjection(pod);
      if (!filter.isBlank() && rowProjection.contains(filter)) {
        matchingPod = pod;
        matchedRowProjection = rowProjection;
        break;
      }
    }
    if (matchingPod == null) {
      throw new AssertionError(
          "No pod row containing '" + rowFilter + "' found in namespace '" + namespace + "'.");
    }

    String value = extractWideColumnValue(matchingPod, requestedHeader);
    if (value == null) {
      throw new AssertionError(
          "Header '" + headerName + "' not supported. Available headers: NAME, READY, STATUS, RESTARTS, AGE, IP, NODE, NOMINATED NODE, READINESS GATES");
    }
    if (value.isBlank()) {
      throw new AssertionError(
          "Header '" + headerName + "' exists, but value is missing in matched pod projection: " + matchedRowProjection);
    }

    TigerGlobalConfiguration.putValue(varName, value, ConfigurationValuePrecedence.TEST_CONTEXT);
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
  private ZetaDeploymentDetails getDeploymentDetails() throws AssertionError {
    String namespace = getNamespace();
    String pepPodName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElseThrow(() -> new AssertionError("Missing variable: pep.podName"));

    String nginxConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.configMapName")
        .orElseThrow(() -> new AssertionError("Missing variable: pep.nginx.configMapName"));
    String[] nginxConfigMapSegments = parseKeySegments("zetaDeploymentConfig.pep.nginx.keySegments");

    String wellKnownConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.configMapName")
        .orElseThrow(() -> new AssertionError("Missing variable: pep.wellKnown.configMapName"));
    String[] wellKnownConfigMapSegments = parseKeySegments("zetaDeploymentConfig.pep.wellKnown.keySegments");

    return new ZetaDeploymentDetails(namespace, pepPodName,
        nginxConfigMapName, nginxConfigMapSegments,
        wellKnownConfigMapName, wellKnownConfigMapSegments);
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
   * Verifies takeover evidence for a long-running request: the old pod disappears while the tracked
   * request is still pending, rollout finalization happens only afterwards, and the request later
   * completes successfully.
   */
  @Und(
      "prüfe, dass der Pod {tigerResolvedString} verschwindet, während die erste Anfrage mit Pfad"
          + " {tigerResolvedString} und Knoten {tigerResolvedString} der mit {tigerResolvedString} übereinstimmt,"
          + " noch keine Antwort hat, und dass das Deployment {tigerResolvedString} erst danach finalisiert wird"
          + " oder {int} Sekunden vergangen sind")
  @And(
      "verify pod {tigerResolvedString} disappears while first request to path {tigerResolvedString}"
          + " with {tigerResolvedString} matching {tigerResolvedString} is still pending and deployment"
          + " {tigerResolvedString} is finalized only afterwards or {int} seconds have passed")
  public void verifyPodDisappearsWhileRequestPendingAndDeploymentFinalizesAfterwards(String podName,
      String pathPattern, String rbelPath, String expectedValueRegex, String deploymentName, int timeoutSeconds) {
    verifyTakeoverEvidenceBeforeDeploymentFinalized(
        podName, pathPattern, rbelPath, expectedValueRegex, deploymentName, timeoutSeconds);
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
    return TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError("Missing variable: namespace"));
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

  static boolean hasTakeoverBeforeRolloutFinalizationEvidence(boolean podGoneObserved,
      boolean rolloutObservedAfterPodDisappearance, boolean responseObserved) {
    return podGoneObserved && rolloutObservedAfterPodDisappearance && responseObserved;
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

  private void verifyTakeoverEvidenceBeforeDeploymentFinalized(String podName, String pathPattern, String rbelPath,
      String expectedValueRegex, String deploymentName, int timeoutSeconds) {
    String namespace = getNamespace();
    Instant deadline = Instant.now().plusSeconds(Math.abs(timeoutSeconds));
    Instant responseObservedAt = null;
    Instant podGoneObservedAt = null;
    Instant rolloutObservedAt = null;
    boolean observedRolloutAfterPodDisappearance = false;
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

      if (podGoneObservedAt == null && podMissing(namespace, podName)) {
        if (rolloutObservedAt != null) {
          throw new AssertionError("Deployment '" + deploymentName + "' finalized before pod '" + podName
              + "' disappeared while proving request takeover. observations=" + observations);
        }
        podGoneObservedAt = Instant.now();
        boolean responsePendingWhenPodDisappeared = responseObservedAt == null;
        observations.add("podGone=" + podGoneObservedAt + ", responsePending=" + responsePendingWhenPodDisappeared);
        if (!responsePendingWhenPodDisappeared) {
          throw new AssertionError("Tracked request was already answered before pod '" + podName
              + "' disappeared. observations=" + observations);
        }
      }

      CommandResult rolloutResult = service.executeKubectlCommand(false, "rollout", "status",
          "deployment/" + deploymentName, "-n", namespace, "--timeout=1s");
      if (rolloutResult.exitCode() == 0 && rolloutObservedAt == null) {
        rolloutObservedAt = Instant.now();
        observations.add("rolloutFinalized=" + rolloutObservedAt);
        if (podGoneObservedAt != null) {
          observedRolloutAfterPodDisappearance = true;
          observations.add("rolloutObservedAfterPodGone");
        }
      }

      if (hasTakeoverBeforeRolloutFinalizationEvidence(
          podGoneObservedAt != null, observedRolloutAfterPodDisappearance, responseObservedAt != null)) {
        SerenityReportUtils.addCustomData("Takeover before rollout finalization",
            "deployment=" + deploymentName + ", pod=" + podName + ", requestPath=" + pathPattern
                + ", podGoneAt=" + podGoneObservedAt + ", rolloutFinalizedAt=" + rolloutObservedAt
                + ", responseObservedAt=" + responseObservedAt + ", observations=" + observations);
        return;
      }

      sleepBeforeNextPoll();
    }

    throw new AssertionError("Timeout while proving takeover before rollout finalization for deployment '"
        + deploymentName + "' and pod '" + podName + "'. observations=" + observations);
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
    } catch (IOException e) {
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
    } catch (IOException e) {
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
    } catch (IOException e) {
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
    CommandResult result = service.executeKubectlCommand("-n", namespace, "get", "pods", "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("kubectl get pods failed: " + result.stderr());
    }

    JsonNode items;
    try {
      items = JSON.readTree(result.stdout()).path("items");
    } catch (IOException e) {
      throw new AssertionError("Failed to parse kubectl pods JSON output.", e);
    }

    for (JsonNode pod : items) {
      String podName = pod.path("metadata").path("name").asText("");
      if (!podName.startsWith(deploymentName + "-")) {
        continue;
      }

      String foundImage = extractContainerImage(pod, containerName);
      if (expectedImage.equals(foundImage)) {
        return podName;
      }
    }

    return null;
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
   * Builds the request object for enabling ASL based on Tiger configuration values.
   *
   * @return request payload for enabling ASL
   * @throws AssertionError when required configuration values are missing
   */
  private ZetaEnableAslRequest getEnableAslRequest() throws AssertionError {
    String nginxAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex"));

    String nginxAslAnchorRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfigAnchorRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfigAnchorRegex"));

    String nginxAslConfig = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfig")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfig"));

    String wellKnownAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex"));

    String wellKnownAslEnableValue = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.aslEnabledValue")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.aslEnabledValue"));

    return new ZetaEnableAslRequest(nginxAslRegex, nginxAslAnchorRegex, nginxAslConfig, wellKnownAslRegex, wellKnownAslEnableValue);
  }

  /**
   * Builds the request object for disabling ASL based on Tiger configuration values.
   *
   * @return request payload for disabling ASL
   * @throws AssertionError when required configuration values are missing
   */
  private ZetaDisableAslRequest getDisableAslRequest() throws AssertionError {
    String nginxAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex"));

    String wellKnownAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex"));

    String wellKnownAslDisableValue = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.aslDisabledValue")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.aslDisabledValue"));

    return new ZetaDisableAslRequest(nginxAslRegex, wellKnownAslRegex, wellKnownAslDisableValue);
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
