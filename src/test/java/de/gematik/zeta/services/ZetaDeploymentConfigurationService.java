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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import de.gematik.zeta.services.model.ZetaAslToggleResult;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaDisableAslRequest;
import de.gematik.zeta.services.model.ZetaEnableAslRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Service for running kubectl commands as part of deployment configuration steps.
 */
@Slf4j
public class ZetaDeploymentConfigurationService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KUBECTL_COMMAND = "kubectl";
  private static final String K8S_SUFFIX_ORIGINAL_RESOURCE = "tiger-original-backup";
  private static final int K8S_POD_STATUS_CHECK_INTERVAL = 2;
  private static final int MAX_OBSERVATION_ENTRIES = 8;
  private final int processTimeoutSeconds;
  @Getter
  private final int podReadyTimeoutSeconds;

  /**
   * Constructor for ZetaDeploymentConfigurationService.
   *
   * @param processTimeoutSeconds max timeout for system commands
   */
  public ZetaDeploymentConfigurationService(int processTimeoutSeconds, int podReadyTimeoutSeconds) {
    this.processTimeoutSeconds = processTimeoutSeconds;
    this.podReadyTimeoutSeconds = podReadyTimeoutSeconds;

    if (K8S_POD_STATUS_CHECK_INTERVAL < 1) {
      throw new IllegalArgumentException("value of K8S_POD_STATUS_CHECK_INTERVAL can not be < 1");
    }
  }

  /**
   * Enables the Additional Security Layer (ASL) by modifying a ZETA Guard deployment.
   *
   * @param details Required information that define ZETA Guard deployment
   * @param request Request parameter required for enabling ASL
   * @return Object containing information about the executed steps
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  public ZetaAslToggleResult enableAsl(ZetaDeploymentDetails details, ZetaEnableAslRequest request)
      throws TimeoutException, InterruptedException, IOException {

    // use anchor in nginx config to append actual ASL config
    UnaryOperator<String> nginxEnableAslFunc = (str) -> {
      if (Pattern.compile(request.nginxAslRegex()).matcher(str).find()) {
        log.warn("Enable ASL: ASL config section already present in ConfigMap {}. Won't apply any changes",
            details.nginxConfigMapName());
        return str;
      }
      Matcher anchorMatcher = Pattern.compile(request.nginxAslAnchorRegex()).matcher(str);
      if (!anchorMatcher.find()) {
        log.warn("Enable ASL: Could not find ASL config in ConfigMap {}. Won't apply any changes.",
            details.nginxConfigMapName());
        return str;
      }
      return anchorMatcher.replaceAll(anchorMatcher.group(0) + "\n" + request.nginxAslConfig());
    };

    UnaryOperator<String> wellKnownEnableAslFunc = (str) -> Pattern.compile(request.wellKnownAslRegex())
        .matcher(str)
        .replaceAll(request.wellKnownAslEnabledValue());


    return modifyZetaAsl(details.namespace(), details.pepPodName(), details.nginxConfigMapName(),
        details.nginxConfigMapKeySegments(),
        details.wellKnownConfigMapName(), details.wellKnownConfigMapKeySegments(),
        nginxEnableAslFunc, wellKnownEnableAslFunc);
  }

  /**
   * Disables the Additional Security Layer (ASL) by modifying a ZETA Guard deployment.
   *
   * @param details Required information that define ZETA Guard deployment
   * @param request Request parameter required for enabling ASL
   * @return Object containing information about the executed steps
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  public ZetaAslToggleResult disableAsl(ZetaDeploymentDetails details, ZetaDisableAslRequest request)
      throws TimeoutException, InterruptedException, IOException {

    UnaryOperator<String> nginxDisableAslFunc = (str) -> {
      Matcher matcher = Pattern.compile(request.nginxAslRegex()).matcher(str);
      if (!matcher.find()) {
        log.warn("Disable ASL: Could not find ASL config in ConfigMap {}", details.nginxConfigMapName());
        return str;
      }
      return matcher.replaceAll("");
    };

    UnaryOperator<String> wellKnownDisableAslFunc = (str) -> Pattern.compile(request.wellKnownAslRegex())
        .matcher(str)
        .replaceAll(request.wellKnownAslDisabledValue());

    return modifyZetaAsl(details.namespace(), details.pepPodName(), details.nginxConfigMapName(), details.nginxConfigMapKeySegments(),
        details.wellKnownConfigMapName(), details.wellKnownConfigMapKeySegments(),
        nginxDisableAslFunc, wellKnownDisableAslFunc);
  }

  /**
   * Disables PoPP token verification for a given targetRoute in the PEP proxy of a ZETA Guard deployment.
   *
   * @param details Required information that define ZETA Guard deployment
   * @param request Request parameter required for enabling PoPP token verification
   * @param targetRoute Route that PoPP token verification should be enabled for
   * @return Object containing information about the executed steps
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  public KubectlPatchCommandResult disablePoppVerification(ZetaDeploymentDetails details, ZetaPoppTokenToggleRequest request,
      String targetRoute)
      throws IOException, InterruptedException, TimeoutException {

    var patchFunc = getPoppToggleFunction(request.nginxPoppValueRegex(), targetRoute,
        request.nginxPoppDisabledValue());

    return modifyZetaPoppVerification(
        details.namespace(), details.pepPodName(), details.nginxConfigMapName(), details.nginxConfigMapKeySegments(),
        patchFunc
    );
  }

  /**
   * Enables PoPP token verification for a given targetRoute in the PEP proxy in a ZETA Guard deployment.
   *
   * @param details Required information that define ZETA Guard deployment
   * @param request Request parameter required for enabling PoPP token verification
   * @param targetRoute Route that PoPP token verification should be enabled for
   * @return Object containing information about the executed steps
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  public KubectlPatchCommandResult enablePoppVerification(ZetaDeploymentDetails details, ZetaPoppTokenToggleRequest request,
      String targetRoute)
      throws IOException, InterruptedException, TimeoutException {

    var patchFunc = getPoppToggleFunction(request.nginxPoppValueRegex(), targetRoute,
        request.nginxPoppEnabledValue());

    return modifyZetaPoppVerification(
        details.namespace(), details.pepPodName(), details.nginxConfigMapName(), details.nginxConfigMapKeySegments(),
        patchFunc
    );
  }

  /**
   * Performs a simple health check against the provided namespace.
   *
   * <p>This check should make sure that the kubectl binary is available and that the
   * used kubeconfig provides authentication and authorization in the given namespace.</p>
   *
   * @param namespace Target namespace
   * @throws AssertionError if the kubectl could not be executed successfully
   */
  public void verifyRequirements(String namespace) throws AssertionError {
    CommandResult r = executeKubectlCommand(Arrays.asList("-n", namespace, "get", "all"));
    if (r.exitCode() != 0 || !r.stderr().isBlank()) {
      throw new AssertionError(String.format("Requirement check failed: cannot execute kubectl command. "
          + "stderr output:\n%s", r.stderr()));
    }
  }

  /**
   * Updates the container image for a specific container in a deployment and validates rollout.
   *
   * @param namespace Target namespace
   * @param deploymentName Target deployment name
   * @param containerName Target container name within the deployment
   * @param newImage New image reference to set
   * @return Command result of the final validation step
   */
  public CommandResult setDeploymentContainerImage(String namespace, String deploymentName, String containerName, String newImage)
      throws IOException {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");
    Objects.requireNonNull(containerName, "containerName must not be null");
    Objects.requireNonNull(newImage, "newImage must not be null");

    String patch = String.format(
        "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"%s\",\"image\":\"%s\"}]}}}}",
        escapeJson(containerName), escapeJson(newImage));


    String cmPatchFile = null;
    try {
      cmPatchFile = createTempFile(patch);
      return executeKubectlCommand("patch", "deployment", deploymentName,
          "-n", namespace,
          "--patch-file", cmPatchFile);
    } catch (Exception e) {
      String patchFileArg = cmPatchFile != null ? cmPatchFile : "<not-created>";
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "patch", "deployment", deploymentName, "-n", namespace, "--patch-file", patchFileArg),
          1,
          "",
          "Error while executing command for pod : " + deploymentName + "\n " + e.getMessage()
      );
    } finally {
      deleteTempFileQuietly(cmPatchFile);
    }
  }

  /**
   * Verifies that a deployment rollout has completed and the target container runs with the expected image.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName Deployment name
   * @param containerName Container name
   * @param newImage Expected container image
   * @return Validation result including error details when checks fail
   */
  public CommandResult verifyDeploymentUpdate(String namespace, String deploymentName, String containerName, String newImage) {
    CommandResult rolloutResult = waitForDeploymentRollout(namespace, deploymentName, this.podReadyTimeoutSeconds);
    if (rolloutResult.exitCode() != 0) {
      return rolloutResult;
    }

    String podName;
    try {
      podName = getPodNameByPrefix(namespace, deploymentName);
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace),
          1,
          "",
          "Pod not found for deployment prefix: " + deploymentName + "\n" + e.getMessage()
      );
    }

    String imageJsonPath = String.format("{.spec.containers[?(@.name==\"%s\")].image}", containerName);
    CommandResult imageResult;
    try {
      imageResult = executeKubectlCommand("get", "pod", podName, "-n", namespace,
          "-o", "jsonpath='" + imageJsonPath + "'");
      if (imageResult.exitCode() != 0) {
        return imageResult;
      }
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pod", podName, "-n", namespace, "-o", "jsonpath='" + imageJsonPath + "'"),
          1,
          "",
          "Could not query container image for deployment: " + deploymentName + ", container: " + containerName + "\n"
              + e.getMessage()
      );
    }

    String foundImage = imageResult.stdout() == null ? "" : imageResult.stdout().trim();
    if (foundImage.isBlank()) {
      return new CommandResult(
          imageResult.command(),
          1,
          imageResult.stdout(),
          "Container not found in pod spec: " + containerName
      );
    }

    if (!newImage.equals(foundImage)) {
      return new CommandResult(
          imageResult.command(),
          1,
          imageResult.stdout(),
          "Expected image not found for container " + containerName + ". expected=" + newImage + ", actual=" + foundImage
      );
    }

    String stateJsonPath =
        String.format("{.status.containerStatuses[?(@.name==\"%s\")].state.running.startedAt}", containerName);
    CommandResult stateResult;
    try {
      stateResult = executeKubectlCommand("get", "pod", podName, "-n", namespace,
          "-o", "jsonpath='" + stateJsonPath + "'");
      if (stateResult.exitCode() != 0) {
        return stateResult;
      }
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pod", podName, "-n", namespace, "-o", "jsonpath='" + stateJsonPath + "'"),
          1,
          "",
          "Could not query container running state for deployment: " + deploymentName + ", container: " + containerName + "\n"
              + e.getMessage()
      );
    }

    String runningStartedAt = stateResult.stdout() == null ? "" : stateResult.stdout().trim();
    if (runningStartedAt.isBlank()) {
      return new CommandResult(
          stateResult.command(),
          1,
          stateResult.stdout(),
          "Container state is not Running: " + containerName
      );
    }

    String readyJsonPath = String.format("{.status.containerStatuses[?(@.name==\"%s\")].ready}", containerName);
    CommandResult readyResult;
    try {
      readyResult = executeKubectlCommand("get", "pod", podName, "-n", namespace,
          "-o", "jsonpath='" + readyJsonPath + "'");
      if (readyResult.exitCode() != 0) {
        return readyResult;
      }
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pod", podName, "-n", namespace, "-o", "jsonpath='" + readyJsonPath + "'"),
          1,
          "",
          "Could not query container readiness for deployment: " + deploymentName + ", container: " + containerName + "\n"
              + e.getMessage()
      );
    }

    String readyState = readyResult.stdout() == null ? "" : readyResult.stdout().trim();
    if (!"true".equalsIgnoreCase(readyState)) {
      return new CommandResult(
          readyResult.command(),
          1,
          readyResult.stdout(),
          "Container is not Ready=true: " + containerName + " (actual=" + readyState + ")"
      );
    }

    return readyResult;
  }

  /**
   * Rolls back a deployment to its previous revision.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName Deployment name
   * @return Rollback command result
   */
  public CommandResult rollbackDeployment(String namespace, String deploymentName) {
    CommandResult result = executeKubectlCommand("rollout", "undo", "deployment/" + deploymentName, "-n", namespace);
    String expectedMessage = "deployment.apps/" + deploymentName + " rolled back";
    String stdout = result.stdout() == null ? "" : result.stdout();
    if (result.exitCode() != 0 || !stdout.contains(expectedMessage)) {
      return new CommandResult(
          result.command(),
          1,
          stdout,
          "Rollback failed. Expected output: " + expectedMessage + "\nstdout:\n" + stdout + "\nstderr:\n" + result.stderr()
      );
    }
    return result;
  }

  /**
   * Reads the full image reference of a given container in a pod.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName Deployment name (used for error context)
   * @param podName Pod name
   * @param containerName Container name
   * @return Command result containing the full image reference in stdout when successful
   */
  private CommandResult getContainerImageReference(String namespace, String deploymentName, String podName, String containerName) {
    String imageJsonPath = String.format("{.spec.containers[?(@.name==\"%s\")].image}", containerName);
    CommandResult imageResult;
    try {
      imageResult = executeKubectlCommand("get", "pod", podName, "-n", namespace,
          "-o", "jsonpath='" + imageJsonPath + "'");
      if (imageResult.exitCode() != 0) {
        return imageResult;
      }
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pod", podName, "-n", namespace, "-o", "jsonpath='" + imageJsonPath + "'"),
          1,
          "",
          "Could not query container image for deployment: " + deploymentName + ", container: " + containerName + "\n"
              + e.getMessage()
      );
    }

    String foundImage = imageResult.stdout() == null ? "" : imageResult.stdout().trim();
    if (foundImage.isBlank()) {
      return new CommandResult(
          imageResult.command(),
          1,
          imageResult.stdout(),
          "Container not found in pod spec: " + containerName
      );
    }

    return new CommandResult(imageResult.command(), 0, foundImage, "");
  }

  /**
   * Reads the image of a given container in a pod and returns its image path without tag.
   *
   * <p>Examples: {@code ghcr.io/path/image:tag -> ghcr.io/path/image},
   * {@code registry.local:5000/path/image@sha256:abcd -> registry.local:5000/path/image},
   * {@code nginx:latest -> nginx}.</p>
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName Deployment name (used for error context)
   * @param podName Pod name
   * @param containerName Container name
   * @return Command result containing the image path without tag in stdout when successful
   */
  private CommandResult getContainerImagePath(String namespace, String deploymentName, String podName, String containerName) {
    CommandResult imageResult = getContainerImageReference(namespace, deploymentName, podName, containerName);
    if (imageResult.exitCode() != 0) {
      return imageResult;
    }

    String imagePath = extractPathFromImage(imageResult.stdout());
    return new CommandResult(imageResult.command(), 0, imagePath, "");
  }

  /**
   * Resolves the active pod of a deployment and returns the image path of the target container.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName Deployment name
   * @param containerName Container name
   * @return Command result containing the image path without tag in stdout when successful
   */
  public CommandResult getContainerImagePathForDeployment(String namespace, String deploymentName, String containerName) {
    final String podName;
    try {
      podName = getPodNameByPrefix(namespace, deploymentName);
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace),
          1,
          "",
          "Pod not found for deployment prefix: " + deploymentName + "\n" + e.getMessage()
      );
    }
    return getContainerImagePath(namespace, deploymentName, podName, containerName);
  }

  /**
   * Resolves the active pod of a deployment and returns the full image reference of the target container.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName Deployment name
   * @param containerName Container name
   * @return Command result containing the full image reference in stdout when successful
   */
  public CommandResult getContainerImageReferenceForDeployment(String namespace, String deploymentName, String containerName) {
    final String podName;
    try {
      podName = getPodNameByPrefix(namespace, deploymentName);
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace),
          1,
          "",
          "Pod not found for deployment prefix: " + deploymentName + "\n" + e.getMessage()
      );
    }
    return getContainerImageReference(namespace, deploymentName, podName, containerName);
  }

  private CommandResult getConfiguredContainerImageReferenceForDeployment(String namespace, String deploymentName,
      String containerName) {
    String imageJsonPath = String.format("{.spec.template.spec.containers[?(@.name==\"%s\")].image}", containerName);
    CommandResult imageResult;
    try {
      imageResult = executeKubectlCommand("get", "deployment", deploymentName, "-n", namespace,
          "-o", "jsonpath='" + imageJsonPath + "'");
      if (imageResult.exitCode() != 0) {
        return imageResult;
      }
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "deployment", deploymentName, "-n", namespace,
              "-o", "jsonpath='" + imageJsonPath + "'"),
          1,
          "",
          "Could not query configured container image for deployment: " + deploymentName + ", container: "
              + containerName + "\n" + e.getMessage()
      );
    }

    String foundImage = imageResult.stdout() == null ? "" : imageResult.stdout().trim();
    if (foundImage.isBlank()) {
      return new CommandResult(
          imageResult.command(),
          1,
          imageResult.stdout(),
          "Container not found in deployment spec: " + containerName
      );
    }

    return new CommandResult(imageResult.command(), 0, foundImage, "");
  }

  /**
   * Resolves exactly one ready pod whose name starts with the deployment prefix.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name used as pod prefix
   * @return ready pod name
   * @throws AssertionError if no single ready pod can be determined
   */
  public String getSingleReadyPodNameForDeployment(String namespace, String deploymentName) throws AssertionError {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");

    JsonNode pods = readPodsForNamespace(namespace);
    List<String> matchingReadyPodNames = new ArrayList<>();

    for (JsonNode pod : pods) {
      String podName = pod.path("metadata").path("name").asText("");
      if (!podName.startsWith(deploymentName + "-")) {
        continue;
      }
      if (isPodReady(pod)) {
        matchingReadyPodNames.add(podName);
      }
    }

    if (matchingReadyPodNames.size() != 1) {
      throw new AssertionError("Expected exactly one ready pod for deployment '" + deploymentName + "' in namespace '"
          + namespace + "', but found " + matchingReadyPodNames.size() + ": " + matchingReadyPodNames);
    }

    return matchingReadyPodNames.getFirst();
  }

  /**
   * Verifies that Kubernetes recorded an image pull event for the container image used by the given pod.
   *
   * @param namespace Kubernetes namespace
   * @param podName pod name
   * @param containerName container name inside the pod
   * @return successful result when a matching pull event exists; failing result otherwise
   */
  public CommandResult verifyPodImagePullOccurred(String namespace, String podName, String containerName) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(podName, "podName must not be null");
    Objects.requireNonNull(containerName, "containerName must not be null");

    verifyRequirements(namespace);

    String imageJsonPath = String.format("{.spec.containers[?(@.name==\"%s\")].image}", containerName);
    CommandResult imageResult = executeKubectlCommand("get", "pod", podName, "-n", namespace,
        "-o", "jsonpath='" + imageJsonPath + "'");
    if (imageResult.exitCode() != 0) {
      return imageResult;
    }

    String image = imageResult.stdout() == null ? "" : imageResult.stdout().trim();
    if (image.isBlank()) {
      return new CommandResult(
          imageResult.command(),
          1,
          imageResult.stdout(),
          "Container not found in pod spec: " + containerName
      );
    }

    CommandResult eventsResult = executeKubectlCommand("get", "events", "-n", namespace,
        "--field-selector", "involvedObject.kind=Pod,involvedObject.name=" + podName,
        "-o", "json");
    if (eventsResult.exitCode() != 0) {
      return eventsResult;
    }

    JsonNode items;
    try {
      items = JSON.readTree(eventsResult.stdout()).path("items");
    } catch (IOException e) {
      return new CommandResult(
          eventsResult.command(),
          1,
          eventsResult.stdout(),
          "Failed to parse kubectl events JSON output."
      );
    }

    if (!items.isArray()) {
      return new CommandResult(
          eventsResult.command(),
          1,
          eventsResult.stdout(),
          "kubectl get events -o json returned no event list for pod '" + podName + "'."
      );
    }

    String normalizedImage = image.toLowerCase(java.util.Locale.ROOT);
    List<String> relevantEvents = new ArrayList<>();

    for (JsonNode event : items) {
      String reason = event.path("reason").asText("");
      String message = event.path("message").asText("");
      String normalizedReason = reason.toLowerCase(java.util.Locale.ROOT);
      String normalizedMessage = message.toLowerCase(java.util.Locale.ROOT);
      boolean pullReason = "pulling".equals(normalizedReason) || "pulled".equals(normalizedReason);
      boolean imageMentioned = normalizedMessage.contains(normalizedImage);

      if (pullReason && imageMentioned) {
        relevantEvents.add(reason + ": " + message);
      }
    }

    if (relevantEvents.isEmpty()) {
      return new CommandResult(
          eventsResult.command(),
          1,
          eventsResult.stdout(),
          "No image pull event found for pod '" + podName + "', container '" + containerName + "', image '" + image + "'."
      );
    }

    return new CommandResult(
        eventsResult.command(),
        0,
        String.join(System.lineSeparator(), relevantEvents),
        ""
    );
  }

  /**
   * Verifies that rollout activity for the expected image becomes observable on deployment pods
   * within a given time budget.
   *
   * <p>This is used to demonstrate that Kubernetes has started creating or updating a pod for the
   * target image while traffic may still be served by the previous revision.</p>
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name used as pod prefix
   * @param containerName target container name
   * @param expectedImage expected image reference that should appear on a deployment pod
   * @param maxDurationSeconds maximum observation time in seconds
   * @return successful result when a matching pod image is observed
   */
  public CommandResult verifyDeploymentShowsPodWithImageWithinSeconds(String namespace, String deploymentName,
      String containerName, String expectedImage, int maxDurationSeconds) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");
    Objects.requireNonNull(containerName, "containerName must not be null");
    Objects.requireNonNull(expectedImage, "expectedImage must not be null");

    verifyRequirements(namespace);

    Duration maxDuration = Duration.ofSeconds(Math.abs(maxDurationSeconds));
    long startNanos = System.nanoTime();
    long deadlineNanos = startNanos + maxDuration.toNanos();
    List<String> stateSnapshots = new ArrayList<>();

    while (System.nanoTime() <= deadlineNanos) {
      JsonNode pods;
      try {
        pods = readPodsForNamespaceInternal(namespace, false);
      } catch (AssertionError e) {
        return new CommandResult(
            List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
            1,
            "",
            "Could not observe deployment pods for image visibility verification.\n" + e.getMessage()
        );
      }

      boolean observedExpectedImage = false;
      List<String> currentSnapshot = new ArrayList<>();

      for (JsonNode pod : pods) {
        String podName = pod.path("metadata").path("name").asText("");
        if (!podName.startsWith(deploymentName + "-")) {
          continue;
        }

        ContainerStateSnapshot snapshot = inspectContainerState(pod, containerName);
        currentSnapshot.add(snapshot.describe(podName));
        if (expectedImage.equals(snapshot.image())) {
          observedExpectedImage = true;
        }
      }

      if (!currentSnapshot.isEmpty() && stateSnapshots.size() < MAX_OBSERVATION_ENTRIES) {
        stateSnapshots.add(String.join("; ", currentSnapshot));
      }

      if (observedExpectedImage) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        return new CommandResult(
            List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
            0,
            "Observed deployment '" + deploymentName + "' with image '" + expectedImage + "' after "
                + elapsed.toMillis() + " ms.\nObserved pods: " + String.join(" | ", stateSnapshots),
            ""
        );
      }

      if (System.nanoTime() > deadlineNanos) {
        break;
      }

      try {
        sleepForPodStatusCheckInterval();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return new CommandResult(
            List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
            1,
            "",
            "Image visibility verification interrupted.\nObserved pods: " + String.join(" | ", stateSnapshots)
        );
      }
    }

    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    return new CommandResult(
        List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
        1,
        "",
        "Deployment '" + deploymentName + "' did not show image '" + expectedImage + "' on any pod within "
            + maxDuration.getSeconds() + " seconds.\nElapsed: " + elapsed.toMillis() + " ms.\nObserved pods: "
            + String.join(" | ", stateSnapshots)
    );
  }

  /**
   * Verifies in one observation window that a failed update never becomes active and the deployment
   * automatically returns to the expected stable image.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name
   * @param containerName target container name inside the deployment
   * @param failedImage image that was configured but must never become active
   * @param expectedStableImage expected stable image after automatic recovery
   * @param maxDurationSeconds maximum total duration to observe
   * @return successful result when the failed image stays inactive and automatic recovery is observed
   */
  public CommandResult verifyFailedUpdateDoesNotBecomeActiveAndAutomaticallyReturnsToImageWithinSeconds(
      String namespace, String deploymentName, String containerName, String failedImage, String expectedStableImage,
      int maxDurationSeconds) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");
    Objects.requireNonNull(containerName, "containerName must not be null");
    Objects.requireNonNull(failedImage, "failedImage must not be null");
    Objects.requireNonNull(expectedStableImage, "expectedStableImage must not be null");

    verifyRequirements(namespace);

    Duration maxDuration = Duration.ofSeconds(Math.abs(maxDurationSeconds));
    long startNanos = System.nanoTime();
    long deadlineNanos = startNanos + maxDuration.toNanos();
    List<String> rolloutStatusCommand = List.of(
        KUBECTL_COMMAND, "rollout", "status", "deployment/" + deploymentName, "-n", namespace);

    boolean observedFailedRolloutEvidence = false;
    List<String> evidenceNotes = new ArrayList<>();
    List<String> stateSnapshots = new ArrayList<>();
    CommandResult lastRolloutResult = new CommandResult(rolloutStatusCommand, 1, "", "");

    while (System.nanoTime() <= deadlineNanos) {
      DeploymentObservation observation;
      try {
        JsonNode pods = readPodsForNamespaceInternal(namespace, false);
        observation = observeDeploymentState(pods, deploymentName, containerName, expectedStableImage);
      } catch (AssertionError e) {
        return new CommandResult(
            List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
            1,
            "",
            "Could not observe deployment state for failed update auto-recovery verification.\n" + e.getMessage()
        );
      }

      if (!observation.summary().isBlank() && stateSnapshots.size() < MAX_OBSERVATION_ENTRIES) {
        stateSnapshots.add(observation.summary());
      }
      if (observation.hasFailedRolloutEvidence()) {
        observedFailedRolloutEvidence = true;
        if (!observation.failureEvidence().isBlank() && evidenceNotes.size() < MAX_OBSERVATION_ENTRIES) {
          evidenceNotes.add(observation.failureEvidence());
        }
      }

      if (observation.hasReadyPodWithImage(failedImage)) {
        return new CommandResult(
            List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
            1,
            "",
            "Failed image '" + failedImage + "' unexpectedly became active for deployment '" + deploymentName + "'.\n"
                + formatObservationDetails(evidenceNotes, stateSnapshots, lastRolloutResult)
        );
      }

      CommandResult deploymentImageResult = getConfiguredContainerImageReferenceForDeployment(
          namespace, deploymentName, containerName);
      if (deploymentImageResult.exitCode() != 0) {
        return new CommandResult(
            deploymentImageResult.command(),
            1,
            deploymentImageResult.stdout(),
            "Could not verify deployment image for failed update auto-recovery verification.\n"
                + deploymentImageResult.stderr() + "\n"
                + formatObservationDetails(evidenceNotes, stateSnapshots, lastRolloutResult)
        );
      }

      String configuredImage = deploymentImageResult.stdout() == null ? "" : deploymentImageResult.stdout().trim();
      boolean deploymentSpecRestored = expectedStableImage.equals(configuredImage);
      if (!deploymentSpecRestored) {
        addObservationDetail(evidenceNotes, "Deployment spec still references image '" + configuredImage + "'");
      }

      lastRolloutResult = executeKubectlCommand(false, "rollout", "status", "deployment/" + deploymentName, "-n",
          namespace, "--timeout=2s");
      boolean rolloutSuccessful = lastRolloutResult.exitCode() == 0;

      if (observation.isRecoveredToStableImage() && deploymentSpecRestored && rolloutSuccessful
          && observedFailedRolloutEvidence) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        return new CommandResult(
            lastRolloutResult.command(),
            0,
            "Failed update remained inactive and automatic rollback verified for deployment '" + deploymentName
                + "' after " + elapsed.toMillis() + " ms.\n"
                + formatObservationDetails(evidenceNotes, stateSnapshots, lastRolloutResult),
            ""
        );
      }

      if (System.nanoTime() > deadlineNanos) {
        break;
      }

      try {
        sleepForPodStatusCheckInterval();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return new CommandResult(
            lastRolloutResult.command(),
            1,
            lastRolloutResult.stdout(),
            "Failed update auto-recovery verification interrupted.\n"
                + formatObservationDetails(evidenceNotes, stateSnapshots, lastRolloutResult)
        );
      }
    }

    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    String baseMessage = observedFailedRolloutEvidence
        ? "Deployment '" + deploymentName + "' did not return automatically to stable image '" + expectedStableImage
            + "' within " + maxDuration.getSeconds() + " seconds while keeping failed image '" + failedImage
            + "' inactive."
        : "No failed rollout evidence observed for deployment '" + deploymentName + "' within "
            + maxDuration.getSeconds() + " seconds.";
    return new CommandResult(
        lastRolloutResult.command(),
        1,
        lastRolloutResult.stdout(),
        baseMessage + "\nElapsed: " + elapsed.toMillis() + " ms.\n"
            + formatObservationDetails(evidenceNotes, stateSnapshots, lastRolloutResult)
    );
  }

  /**
   * Removes lingering failed rollout pods for a deployment after the stable image is active again.
   *
   * <p>The cleanup first restores the deployment spec to the expected stable image. Afterwards it
   * scales the owner ReplicaSet of each unexpected-image pod down to {@code 0} and deletes the
   * pod itself to prevent Kubernetes from recreating it immediately.</p>
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name used as pod prefix
   * @param containerName target container name inside the deployment pods
   * @param expectedStableImage image that is considered the stable deployment state
   * @return successful result when cleanup completed or no cleanup was needed
   */
  public CommandResult cleanupFailedRolloutPods(String namespace, String deploymentName, String containerName,
      String expectedStableImage) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");
    Objects.requireNonNull(containerName, "containerName must not be null");
    Objects.requireNonNull(expectedStableImage, "expectedStableImage must not be null");

    verifyRequirements(namespace);

    JsonNode pods;
    try {
      pods = readPodsForNamespaceInternal(namespace, false);
    } catch (AssertionError e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
          1,
          "",
          "Could not inspect deployment pods for failed rollout cleanup.\n" + e.getMessage()
      );
    }

    List<String> podsToDelete = new ArrayList<>();
    List<String> replicaSetsToScale = new ArrayList<>();
    List<String> cleanupTargets = new ArrayList<>();

    for (JsonNode pod : pods) {
      String podName = pod.path("metadata").path("name").asText("");
      if (!podName.startsWith(deploymentName + "-")) {
        continue;
      }

      ContainerStateSnapshot snapshot = inspectContainerState(pod, containerName);
      if (snapshot.image().isBlank() || expectedStableImage.equals(snapshot.image())) {
        continue;
      }

      podsToDelete.add(podName);
      cleanupTargets.add(snapshot.describe(podName));

      String replicaSetName = extractOwnerReplicaSetName(pod);
      if (!replicaSetName.isBlank() && !replicaSetsToScale.contains(replicaSetName)) {
        replicaSetsToScale.add(replicaSetName);
      }
    }

    if (podsToDelete.isEmpty()) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
          0,
          "No failed rollout pods found for deployment '" + deploymentName + "'.",
          ""
      );
    }

    List<String> cleanupActions = new ArrayList<>();
    CommandResult restoreDeploymentResult;
    try {
      restoreDeploymentResult = setDeploymentContainerImage(
          namespace, deploymentName, containerName, expectedStableImage);
    } catch (IOException e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "patch", "deployment", deploymentName, "-n", namespace),
          1,
          "",
          "Failed to restore deployment '" + deploymentName + "' to stable image '" + expectedStableImage
              + "' during failed rollout cleanup.\n" + e.getMessage()
      );
    }
    if (restoreDeploymentResult.exitCode() != 0) {
      return new CommandResult(
          restoreDeploymentResult.command(),
          1,
          restoreDeploymentResult.stdout(),
          "Failed to restore deployment '" + deploymentName + "' to stable image '" + expectedStableImage
              + "' during failed rollout cleanup.\n" + restoreDeploymentResult.stderr()
      );
    }
    cleanupActions.add("Restored deployment '" + deploymentName + "' to image '" + expectedStableImage + "'");

    for (String replicaSetName : replicaSetsToScale) {
      CommandResult scaleResult = executeKubectlCommand(
          "scale", "rs", replicaSetName, "-n", namespace, "--replicas=0");
      if (scaleResult.exitCode() != 0) {
        return new CommandResult(
            scaleResult.command(),
            1,
            scaleResult.stdout(),
            "Failed to scale ReplicaSet '" + replicaSetName + "' down during failed rollout cleanup.\n"
                + scaleResult.stderr()
        );
      }
      cleanupActions.add("Scaled ReplicaSet '" + replicaSetName + "' to 0");
    }

    for (String podName : podsToDelete) {
      CommandResult deleteResult = executeKubectlCommand(
          "-n", namespace, "delete", "pod", podName, "--ignore-not-found=true");
      if (deleteResult.exitCode() != 0) {
        return new CommandResult(
            deleteResult.command(),
            1,
            deleteResult.stdout(),
            "Failed to delete lingering failed rollout pod '" + podName + "'.\n" + deleteResult.stderr()
        );
      }
      cleanupActions.add("Deleted pod '" + podName + "'");
    }

    return new CommandResult(
        List.of(KUBECTL_COMMAND, "get", "pods", "-n", namespace, "-o", "json"),
        0,
        "Cleaned failed rollout targets for deployment '" + deploymentName + "': "
            + String.join(" | ", cleanupTargets) + "\n"
            + String.join(" | ", cleanupActions),
        ""
    );
  }

  /**
   * Extracts the image path from a full container image reference.
   *
   * <p>The returned value excludes optional digest ({@code @sha256:...}) and
   * optional tag ({@code :<tag>}) parts.</p>
   *
   * <p>Examples:
   * {@code registry.tas-devtools-gitlab.spree.de:443/zeta/zeta-guard/ngx_pep:0.3.0 -> registry.tas-devtools-gitlab.spree.de:443/zeta/zeta-guard/ngx_pep},
   * {@code registry.tas-devtools-gitlab.spree.de:443/zeta/zeta-guard/ngx_pep@sha256:abcd -> registry.tas-devtools-gitlab.spree.de:443/zeta/zeta-guard/ngx_pep},
   * {@code nginx:latest -> nginx}.</p>
   *
   * @param image Full container image reference
   * @return Image path without tag and digest
   */
  private String extractPathFromImage(String image) {
    String normalizedImage = image.trim();

    // image with digest reference
    int digestIndex = normalizedImage.indexOf('@');
    if (digestIndex >= 0) {
      normalizedImage = normalizedImage.substring(0, digestIndex);
    }

    // image with tag reference
    int lastSlashIndex = normalizedImage.lastIndexOf('/');
    int tagSeparatorIndex = normalizedImage.lastIndexOf(':');
    if (tagSeparatorIndex > lastSlashIndex) {
      return normalizedImage.substring(0, tagSeparatorIndex);
    }

    return normalizedImage;
  }

  private String extractOwnerReplicaSetName(JsonNode pod) {
    JsonNode ownerReferences = pod.path("metadata").path("ownerReferences");
    if (!ownerReferences.isArray()) {
      return "";
    }

    for (JsonNode ownerReference : ownerReferences) {
      if ("ReplicaSet".equals(ownerReference.path("kind").asText(""))) {
        return ownerReference.path("name").asText("");
      }
    }

    return "";
  }

  private DeploymentObservation observeDeploymentState(JsonNode pods, String deploymentName, String containerName,
      String expectedStableImage) {
    List<String> podSummaries = new ArrayList<>();
    List<String> evidence = new ArrayList<>();
    boolean hasReadyStablePod = false;
    boolean hasUnexpectedImagePod = false;
    List<String> readyImages = new ArrayList<>();

    for (JsonNode pod : pods) {
      String podName = pod.path("metadata").path("name").asText("");
      if (!podName.startsWith(deploymentName + "-")) {
        continue;
      }

      ContainerStateSnapshot snapshot = inspectContainerState(pod, containerName);
      podSummaries.add(snapshot.describe(podName));

      if (snapshot.ready() && expectedStableImage.equals(snapshot.image())) {
        hasReadyStablePod = true;
      }

      if (snapshot.ready() && !snapshot.image().isBlank()) {
        readyImages.add(snapshot.image());
      }

      if (!snapshot.image().isBlank() && !expectedStableImage.equals(snapshot.image())) {
        hasUnexpectedImagePod = true;
        evidence.add("Observed attempted rollout pod with unexpected image: " + snapshot.describe(podName));
      }

      if (!snapshot.waitingReason().isBlank()) {
        evidence.add("Observed waiting container state on pod '" + podName + "': " + snapshot.waitingReason());
      }

      if (!snapshot.terminatedReason().isBlank()) {
        evidence.add("Observed terminated container state on pod '" + podName + "': " + snapshot.terminatedReason());
      }
    }

    boolean recovered = hasReadyStablePod && !hasUnexpectedImagePod;
    return new DeploymentObservation(
        recovered,
        !evidence.isEmpty(),
        hasReadyStablePod,
        hasUnexpectedImagePod,
        List.copyOf(readyImages),
        String.join("; ", podSummaries),
        String.join("; ", evidence)
    );
  }

  private ContainerStateSnapshot inspectContainerState(JsonNode pod, String containerName) {
    String image = "";
    JsonNode specContainers = pod.path("spec").path("containers");
    if (specContainers.isArray()) {
      for (JsonNode container : specContainers) {
        if (containerName.equals(container.path("name").asText(""))) {
          image = container.path("image").asText("");
          break;
        }
      }
    }

    JsonNode containerStatuses = pod.path("status").path("containerStatuses");
    if (!containerStatuses.isArray()) {
      return new ContainerStateSnapshot(image, false, pod.path("status").path("phase").asText(""), "", "");
    }

    for (JsonNode status : containerStatuses) {
      if (!containerName.equals(status.path("name").asText(""))) {
        continue;
      }
      return new ContainerStateSnapshot(
          image,
          status.path("ready").asBoolean(false),
          pod.path("status").path("phase").asText(""),
          status.path("state").path("waiting").path("reason").asText(""),
          status.path("state").path("terminated").path("reason").asText("")
      );
    }

    return new ContainerStateSnapshot(image, false, pod.path("status").path("phase").asText(""), "", "");
  }

  private String formatObservationDetails(List<String> evidenceNotes, List<String> stateSnapshots, CommandResult lastRolloutResult) {
    List<String> details = new ArrayList<>();
    if (!evidenceNotes.isEmpty()) {
      details.add("Evidence: " + String.join(" | ", evidenceNotes));
    }
    if (!stateSnapshots.isEmpty()) {
      details.add("Observed pods: " + String.join(" | ", stateSnapshots));
    }
    if (lastRolloutResult != null) {
      String stderr = lastRolloutResult.stderr() == null ? "" : lastRolloutResult.stderr().trim();
      String stdout = lastRolloutResult.stdout() == null ? "" : lastRolloutResult.stdout().trim();
      if (!stdout.isBlank()) {
        details.add("Last rollout stdout: " + stdout);
      }
      if (!stderr.isBlank()) {
        details.add("Last rollout stderr: " + stderr);
      }
    }
    return details.isEmpty() ? "No deployment observations collected." : String.join("\n", details);
  }

  private void addObservationDetail(List<String> details, String detail) {
    if (detail == null || detail.isBlank() || details.size() >= MAX_OBSERVATION_ENTRIES || details.contains(detail)) {
      return;
    }
    details.add(detail);
  }

  /**
   * Waits until a deployment rollout succeeds and the number of active pods matches the deployment replicas.
   *
   * <p>The method polls {@code kubectl rollout status} and, after a successful rollout check, validates
   * the number of pods with names starting with {@code deploymentName-}. The expected pod count is read
   * from {@code kubectl get deploy ... -o jsonpath='{.spec.replicas}'} in the target namespace. If the
   * replicas field is empty, Kubernetes default {@code 1} is assumed.</p>
   *
   * @param namespace Kubernetes namespace containing the deployment
   * @param deploymentName Name of the deployment to monitor
   * @param timeoutSeconds Maximum total wait time in seconds (absolute value is used)
   * @return A successful {@link CommandResult} if rollout and replica checks pass; otherwise a failing
   *     result with details about the last observed error or timeout
   */
  private CommandResult waitForDeploymentRollout(String namespace, String deploymentName, int timeoutSeconds) {
    int expectedReplicas = 1;

    CommandResult replicasResult = executeKubectlCommand("get", "deploy", deploymentName, "-n", namespace,
        "-o", "jsonpath='{.spec.replicas}'");
    if (replicasResult.exitCode() != 0) {
      return replicasResult;
    }

    String replicasOutput = replicasResult.stdout() == null ? "" : replicasResult.stdout().trim();
    if (!replicasOutput.isBlank()) {
      try {
        expectedReplicas = Integer.parseInt(replicasOutput);
      } catch (NumberFormatException nfe) {
        return new CommandResult(
            replicasResult.command(),
            1,
            replicasResult.stdout(),
            "Could not parse deployment replicas for " + deploymentName + ": " + replicasOutput
        );
      }
    }

    final int maxTimeout = Math.abs(timeoutSeconds);
    int elapsedSeconds = 0;
    CommandResult lastResult = null;

    while (elapsedSeconds < maxTimeout) {
      lastResult = executeKubectlCommand(false, "rollout", "status", "deployment/" + deploymentName, "-n", namespace,
          "--timeout=5s");

      if (lastResult.exitCode() == 0) {
        CommandResult podListResult = executeKubectlCommand("-n", namespace, "get", "pods",
            "--no-headers", "-o", "custom-columns=NAME:.metadata.name");
        if (podListResult.exitCode() != 0) {
          return podListResult;
        }

        List<String> matchingPods = Arrays.stream(podListResult.stdout().split("\\R"))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .filter(name -> name.startsWith(deploymentName + "-"))
            .toList();

        if (matchingPods.size() == expectedReplicas) {
          return lastResult;
        }

        lastResult = new CommandResult(
            podListResult.command(),
            1,
            podListResult.stdout(),
            String.format("Expected %d active pods with prefix %s-, but found %d",
                expectedReplicas, deploymentName, matchingPods.size())
        );
      }

      try {
        sleepForPodStatusCheckInterval();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return new CommandResult(
            lastResult.command(),
            1,
            lastResult.stdout(),
            "Rollout wait interrupted"
        );
      }
      elapsedSeconds += K8S_POD_STATUS_CHECK_INTERVAL;
    }

    String timeoutMessage = "Rollout and replica check did not complete within " + maxTimeout + " seconds";
    return new CommandResult(
        lastResult == null ? List.of(KUBECTL_COMMAND, "rollout", "status", "deployment/" + deploymentName, "-n", namespace)
            : lastResult.command(),
        1,
        lastResult == null ? "" : lastResult.stdout(),
        timeoutMessage + (lastResult != null && !lastResult.stderr().isBlank() ? "\n" + lastResult.stderr() : "")
    );
  }




  /**
   * Uses input to modify the resources in a Kubernetes deploy in order to en-/disable the ASL.
   *
   * @param namespace Namespace target deployment is running in
   * @param pepPodName Name of the PEP pod
   * @param nginxConfigMapName Name of the ConfigMap for the nginx settings of the PEP service
   * @param nginxConfigMapKeySegments Segmented key of nginx config in ConfigMap
   * @param wellKnownConfigMapName Name of the ConfigMap for the well-known settings of the PEP service
   * @param wellKnownConfigMapKeySegments Segmented key of well-known config in ConfigMap
   * @param nginxPatchFunc Function to update the nginx config value
   * @param wellKnownPatchFunc Function to update the well-known config value
   * @return Object containing information about the executed steps
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  private ZetaAslToggleResult modifyZetaAsl(String namespace, String pepPodName,
      String nginxConfigMapName, String[] nginxConfigMapKeySegments,
      String wellKnownConfigMapName, String[] wellKnownConfigMapKeySegments,
      UnaryOperator<String> nginxPatchFunc, UnaryOperator<String> wellKnownPatchFunc)
      throws TimeoutException, InterruptedException, IOException {

    // patch nginx config map
    KubectlPatchCommandResult pepNginxPatchResult = changeConfigMap(namespace, nginxConfigMapName, nginxConfigMapKeySegments, nginxPatchFunc);

    // patch well known config map
    KubectlPatchCommandResult pepWellKnownPatchResult = changeConfigMap(namespace, wellKnownConfigMapName, wellKnownConfigMapKeySegments, wellKnownPatchFunc);

    // restart pods to load modified config
    restartPod(namespace, pepPodName, true, podReadyTimeoutSeconds);

    return new ZetaAslToggleResult(pepNginxPatchResult, pepWellKnownPatchResult);
  }

  /**
   * Applies a PoPP-related nginx patch and restarts the affected PEP pod to activate the new config.
   *
   * @param namespace target namespace
   * @param pepPodName PEP pod prefix
   * @param nginxConfigMapName nginx ConfigMap name
   * @param nginxConfigMapKeySegments key path of the nginx config in the ConfigMap
   * @param nginxPatchFunc patch function applied to the nginx config value
   * @return result of the ConfigMap patch operation
   * @throws TimeoutException if waiting for pod readiness exceeds the timeout
   * @throws InterruptedException if restart waiting is interrupted
   * @throws IOException if temporary patch files cannot be created
   */
  private KubectlPatchCommandResult modifyZetaPoppVerification(String namespace, String pepPodName,
      String nginxConfigMapName, String[] nginxConfigMapKeySegments,
      UnaryOperator<String> nginxPatchFunc)
      throws TimeoutException, InterruptedException, IOException {

    // patch nginx config map
    KubectlPatchCommandResult pepNginxPatchResult = changeConfigMap(namespace, nginxConfigMapName, nginxConfigMapKeySegments, nginxPatchFunc);

    // restart pods to load modified config
    restartPod(namespace, pepPodName, true, podReadyTimeoutSeconds);

    return pepNginxPatchResult;
  }

  /**
   * Triggers a restart of podName in namespace.
   *
   * @param namespace Target namespace
   * @param podName Target pod name
   * @param waitForPodReadiness Flag to initiate a wait for a pod ready state
   * @param readinessTimeout Timeout of waiting for pod ready state
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   */
  public void restartPod(String namespace, String podName, boolean waitForPodReadiness, int readinessTimeout)
      throws TimeoutException, InterruptedException {
    String podNameInCluster = getPodNameByPrefix(namespace, podName);
    executeKubectlCommand("-n", namespace, "delete", "pod", podNameInCluster);
    if (waitForPodReadiness) {
      log.info("Waiting for pod {} to be ready before proceeding", podName);
      waitForPodState(namespace, podName, readinessTimeout);
    }
  }


  /**
   * Uses a Pod name prefix to get the full name of the Pod including the suffixed random string.
   *
   * @param namespace Namespace the Pod is running in
   * @param podNamePrefix Prefix of target Pod
   * @return Full Pod name
   * @throws IllegalArgumentException Thrown if Pod could not be found
   */
  private String getPodNameByPrefix(String namespace, String podNamePrefix) throws IllegalArgumentException {
    CommandResult getPodsResult = executeKubectlCommand("-n", namespace, "get", "pods");

    Matcher matcher = Pattern.compile("(" + Pattern.quote(podNamePrefix) + "-[^ ]+)").matcher(getPodsResult.stdout());

    String capture;
    if (matcher.find()) {
      capture = matcher.group(0);
    } else {
      log.error("Pod {} was not found in namespace {}", podNamePrefix, namespace);
      throw new IllegalArgumentException("Could not find pod with name (prefix) " + podNamePrefix);
    }
    return capture;
  }

  /**
   * Modifies a value in a Kubernetes ConfigMap.
   *
   * @param namespace Target namespace
   * @param cmName Target ConfigMap resource
   * @param cmKeySegments Segments of the target key in the ConfigMap
   * @param updateFunc Function that modifies the value of the target key
   * @return Command result
   * @throws IOException if required temporary file could not be created
   */
  public KubectlPatchCommandResult changeConfigMap(String namespace, String cmName, String[] cmKeySegments,
      UnaryOperator<String> updateFunc)
      throws IOException, IllegalStateException {

    // get config map value
    CommandResult cmOriginalValue = executeKubectlCommand("-n", namespace, "get", "configmap",
        cmName, "-o=jsonpath='" + buildJsonPath(cmKeySegments) + "'");

    if (cmOriginalValue.stdout() == null || cmOriginalValue.stdout().isBlank()) {
      throw new IllegalStateException(String.format("Cannot change ConfigMap %s: "
          + "received empty value from cluster", cmName));
    }

    var modifiedCm = updateFunc.apply(cmOriginalValue.stdout());

    // sanitize before patching
    modifiedCm = escapeJson(modifiedCm);
    modifiedCm = stripSingleQuotes(modifiedCm);

    // prepare patch string
    String cmPatchStr = assemblePatchString(cmKeySegments, modifiedCm);
    var cmPatchFile = createTempFile(cmPatchStr);

    try {
      List<String> params = Arrays.asList("-n", namespace, "patch", "configmap",
          cmName, "--type", "merge", "--patch-file", cmPatchFile);

      // backup existing config map before modification
      CommandResult backupResult = createConfigMapBackup(namespace, cmName);
      if (backupResult.exitCode() != 0) {
        throw new IllegalStateException("Could not backup ConfigMap " + cmName + " before modification");
      }

      // patch config map
      CommandResult patchResult = executeKubectlCommand(params);
      return new KubectlPatchCommandResult(patchResult, cmOriginalValue.stdout());
    } finally {
      deleteTempFileQuietly(cmPatchFile);
    }
  }

  /**
   * Deletes the backup up a ConfigMap resource.
   *
   * @param namespace Target kubernetes namespace
   * @param configMapName ConfigMap whose backup is to be deleted
   * @return Command result
   */
  public CommandResult deleteConfigMapBackup(String namespace, String configMapName) {
    CommandResult result = executeKubectlCommand("-n", namespace, "delete", "configmap", assemblyBackupResourceName(configMapName));
    if (result.exitCode() != 0) {
      log.error("Could not delete ConfigMap backup of {}", configMapName);
    }
    return result;
  }

  /**
   * Restores a previously backed up ConfigMap resource by overwriting all changes and restoring the original state.
   *
   * @param namespace Target kubernetes namespace
   * @param configMapName ConfigMap to be restored
   * @return Command result
   * @throws IOException if required temporary file could not be created
   */
  public CommandResult restoreConfigMapBackup(String namespace, String configMapName) throws IOException {

    CommandResult existingBackup = getConfigMapBackup(namespace, configMapName);

    if (existingBackup.exitCode() != 0) {
      log.debug("ConfigMap backup of {} was not found", configMapName);
      return new CommandResult(List.of(), 0, "Not found", "");
    }

    String backupConfigMapName = assemblyBackupResourceName(configMapName);

    // ConfigMap name must be restored at manifest level
    String originalConfigMap = Pattern.compile("\n {2}name: " + Pattern.quote(backupConfigMapName) + "\n")
        .matcher(existingBackup.stdout())
        .replaceAll("\n  name: " + configMapName + "\n");

    var configMapTmpFile = createTempFile(originalConfigMap);
    try {
      return executeKubectlCommand("-n", namespace, "apply", "--force", "-f", configMapTmpFile);
    } finally {
      deleteTempFileQuietly(configMapTmpFile);
    }
  }

  /**
   * Creates a copy of a ConfigMap resources specified by configMapName if there isn't a backup already present
   * in the given namespace.
   *
   * @param namespace Target kubernetes namespace
   * @param configMapName ConfigMap to be backed up
   * @return Command result
   * @throws IOException if required temporary file could not be created
   */
  public CommandResult createConfigMapBackup(String namespace, String configMapName) throws IOException {

    CommandResult existingBackup = getConfigMapBackup(namespace, configMapName);

    if (existingBackup.exitCode() == 0) {
      log.debug("ConfigMap {} was already backed up", configMapName);
      return new CommandResult(List.of(), 0, "Unchanged", "");
    }

    String backupConfigMapName = assemblyBackupResourceName(configMapName);

    CommandResult configMapOriginal = executeKubectlCommand("-n", namespace, "get", "configmap",
        configMapName, "-o", "yaml");

    String configMapBackup = Pattern.compile("\n {2}name: " + Pattern.quote(configMapName) + "\n")
        .matcher(configMapOriginal.stdout())
        .replaceAll("\n  name: " + backupConfigMapName + "\n");

    var configMapTmpFile = createTempFile(configMapBackup);
    try {
      return executeKubectlCommand("-n", namespace, "apply", "-f", configMapTmpFile);
    } finally {
      deleteTempFileQuietly(configMapTmpFile);
    }
  }

  /**
   * Checks whether a backup for the given ConfigMap exists.
   *
   * @param namespace Target kubernetes namespace
   * @param configMapName ConfigMap whose backup is to be checked
   * @return {@code true} if a backup exists
   */
  public boolean hasConfigMapBackup(String namespace, String configMapName) {
    return getConfigMapBackup(namespace, configMapName).exitCode() == 0;
  }

  /**
   * Wait for Kubernetes Pod to be in a ready state. Throws when set timeout is exceeded.
   *
   * @param namespace Namespace the Pod is running in
   * @param podName Name of target pod
   * @param timeoutSeconds Maximum number of seconds to wait
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException if sleeping thread is interrupted by system
   */
  private void waitForPodState(String namespace, String podName, int timeoutSeconds)
      throws TimeoutException, InterruptedException {

    int maxTimeout = Math.abs(timeoutSeconds);
    int i = 0;
    String currentPodName;
    CommandResult result;

    while (i * K8S_POD_STATUS_CHECK_INTERVAL < maxTimeout) {
      try {
        currentPodName = getPodNameByPrefix(namespace, podName);
      } catch (IllegalArgumentException e) {
        log.warn("Waiting for pod state: Pod {} not found in namespace {} (yet). Continue waiting.",
            podName, namespace);
        TimeUnit.SECONDS.sleep(K8S_POD_STATUS_CHECK_INTERVAL);
        i++;
        continue;
      }

      result = executeKubectlCommand("-n", namespace, "get", "pods", currentPodName, "--no-headers",
          "-o", "jsonpath={.status.containerStatuses[*].ready}");

      // by individual container state
      if (result.exitCode() == 0 && result.stdout() != null && areContainersReady(result.stdout())) {
        return;
      }
      TimeUnit.SECONDS.sleep(K8S_POD_STATUS_CHECK_INTERVAL);
      i++;
    }

    throw new TimeoutException(String.format("Waiting for pod state: ready state of pod %s timed out after %d seconds",
        podName, i * K8S_POD_STATUS_CHECK_INTERVAL));
  }

  /**
   * Determines if state of containers in a Pod are to be considered state ready to handle connections.
   *
   * @param containerList Space separated list of container status
   * @return True if all containers in list are considered ready
   */
  private boolean areContainersReady(String containerList) {
    return Arrays.stream(containerList.split(" "))
        .map(str -> stripSingleQuotes(str.strip()))
        .allMatch(str -> str.equalsIgnoreCase("true"));
  }

  /**
   * Creates a function that sets the PoPP related config value in a nginx config section for a specific route.
   *
   * @param poppValueRegex Captures the present PoPP config value regardless of its state
   * @param targetRoute Target route for dis-/enabling PoPP verification
   * @param targetPoppValue Target value to be set for PoPP verification
   * @return Function that sets the defined targetPoppValue for targetRoute
   */
  private UnaryOperator<String> getPoppToggleFunction(String poppValueRegex, String targetRoute, String targetPoppValue) {
    return (str) -> {
      Matcher routeSection = Pattern.compile("location\\s+" + Pattern.quote(targetRoute) + "\\s+\\{[^}]*}").matcher(str);
      if (!routeSection.find()) {
        log.warn("Disabling PoPP token verification: no matching route config found for {}", targetRoute);
        return str;
      }
      String originalSection = routeSection.group(0);
      Matcher poppConfigValue = Pattern.compile(poppValueRegex).matcher(originalSection);
      String patchedSection;
      if (poppConfigValue.find()) {
        patchedSection = poppConfigValue.replaceAll(targetPoppValue);
      } else {
        // if popp config key not present, append it after last config statement of location section
        Matcher sectionEnd = Pattern.compile(";\\s+}\\s*$").matcher(originalSection);
        patchedSection = sectionEnd.replaceAll(";\n      " + targetPoppValue + "\n  }");
      }
      return str.replace(originalSection, patchedSection);
    };
  }

  /**
   * Assembles a JSON string from segments as required for kubectl patch command.
   *
   * <p>Does not perform JSON escape on patchValue, needs to be done before calling this method.</p>
   *
   * @param segments Key segments for manifest value
   * @param patchValue Value to be applied by patch
   * @return JSON formed patch string
   */
  private String assemblePatchString(String[] segments, String patchValue) {
    StringBuilder builder = new StringBuilder();
    int keyCount = 0;
    for (String segment : segments) {
      builder.append("{\"").append(segment).append("\":");
      keyCount++;
    }
    builder.append("\"").append(patchValue).append("\"").append("}".repeat(keyCount));
    return builder.toString();
  }

  /**
   * Constructs a string derived from resourceName.
   *
   * @param resourceName Target resource name serving as base
   * @return Derived string
   */
  private String assemblyBackupResourceName(String resourceName) {
    return resourceName + "-" + K8S_SUFFIX_ORIGINAL_RESOURCE;
  }

  /**
   * Executes a kubectl command with the provided arguments.
   *
   * @param arguments the kubectl arguments
   * @return captured stdout/stderr and exit code
   */
  public CommandResult executeKubectlCommand(List<String> arguments) throws AssertionError {
    return executeKubectlCommand(true, arguments);
  }

  /**
   * Executes a kubectl command with the provided arguments.
   *
   * @param arguments the kubectl arguments
   * @param verbose logging of response/error messages
   * @return captured stdout/stderr and exit code
   */
  public CommandResult executeKubectlCommand(List<String> arguments, boolean verbose) throws AssertionError {
    return executeKubectlCommand(verbose, arguments);
  }

  /**
   * Executes a kubectl command with the provided arguments.
   *
   * @param arguments the kubectl arguments
   * @param verbose logging of response/error messages
   * @return captured stdout/stderr and exit code
   */
  public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) throws AssertionError {
    Objects.requireNonNull(arguments, "kubectl arguments must not be null");
    List<String> command = new ArrayList<>();
    command.add(KUBECTL_COMMAND);
    command.addAll(arguments);

    boolean sanitizeJsonResponse =  String.join(" ", command).toLowerCase().contains("-o jsonpath=");

    try (var commandService = new SystemCommandService(processTimeoutSeconds)) {
      CommandResult result = commandService.executeCommand(command, verbose);
      if (sanitizeJsonResponse) {
        result = new CommandResult(result.command(), result.exitCode(),
            stripSingleQuotes(result.stdout()), result.stderr());
      }
      return result;
    }
  }

  /**
   * Executes a kubectl command with the provided arguments.
   *
   * @param arguments kubectl arguments (excluding the kubectl binary name)
   * @return captured stdout/stderr and exit code
   */
  public CommandResult executeKubectlCommand(String... arguments) {
    return executeKubectlCommand(true, arguments);
  }

  /**
   * Executes a kubectl command with the provided arguments.
   *
   * @param arguments kubectl arguments (excluding the kubectl binary name)
   * @param verbose logging of response/error messages
   * @return captured stdout/stderr and exit code
   */
  public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
    Objects.requireNonNull(arguments, "kubectl arguments must not be null");
    return executeKubectlCommand(verbose, Arrays.asList(arguments));
  }

  /**
   * Reads all pods from a namespace and returns the `items` array from kubectl JSON output.
   *
   * @param namespace namespace for the kubectl query
   * @return pod items array from `kubectl get pods -o json`
   * @throws AssertionError when requirements fail, kubectl exits non-zero, or JSON parsing fails
   */
  public JsonNode readPodsForNamespace(String namespace) throws AssertionError {
    return readPodsForNamespaceInternal(namespace, true);
  }

  /**
   * Reads the backup ConfigMap for the given original ConfigMap name without noisy stderr logging.
   *
   * @param namespace Target kubernetes namespace
   * @param configMapName Original ConfigMap name whose backup should be queried
   * @return Command result of the backup lookup
   */
  private CommandResult getConfigMapBackup(String namespace, String configMapName) {
    String backupConfigMapName = assemblyBackupResourceName(configMapName);
    return executeKubectlCommand(Arrays.asList("-n", namespace, "get", "configmap",
        backupConfigMapName, "-o", "yaml"), false);
  }

  /**
   * Adds escape sequence for characters that require escaping in JSON spec.
   *
   * @param input String to be escaped
   * @return JSON spec compliant value
   */
  private static String escapeJson(String input) {
    return StringEscapeUtils.escapeJson(input);
  }

  /**
   * Strips one leading and one trailing single quote when present.
   *
   * @param target String that is to be stripped
   * @return Stripped string
   */
  private static String stripSingleQuotes(String target) {
    target = target.replaceAll("^'", "");
    target = target.replaceAll("'$", "");
    return target;
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
   * Waits for the configured interval before checking pod status again.
   *
   * @throws InterruptedException if the waiting thread is interrupted
   */
  protected void sleepForPodStatusCheckInterval() throws InterruptedException {
    TimeUnit.SECONDS.sleep(K8S_POD_STATUS_CHECK_INTERVAL);
  }

  private JsonNode readPodsForNamespaceInternal(String namespace, boolean verifyRequirements) throws AssertionError {
    if (verifyRequirements) {
      verifyRequirements(namespace);
    }

    var result = executeKubectlCommand("-n", namespace, "get", "pods", "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("kubectl get pods failed in namespace '" + namespace + "': " + result.stderr());
    }

    try {
      var items = JSON.readTree(result.stdout()).path("items");
      if (!items.isArray() || items.isEmpty()) {
        throw new AssertionError("kubectl get pods -o json returned no pods in namespace '" + namespace + "'.");
      }
      return items;
    } catch (IOException ex) {
      throw new AssertionError("Failed to parse kubectl pods JSON output.", ex);
    }
  }

  private record DeploymentObservation(boolean isRecoveredToStableImage, boolean hasFailedRolloutEvidence,
                                       boolean hasReadyStablePod, boolean hasUnexpectedImagePod,
                                       List<String> readyImages, String summary, String failureEvidence) {
    private boolean hasReadyPodWithImage(String image) {
      return readyImages.stream().anyMatch(image::equals);
    }
  }

  private record ContainerStateSnapshot(String image, boolean ready, String phase, String waitingReason,
                                        String terminatedReason) {
    private String describe(String podName) {
      return "pod=" + podName + ", image=" + image + ", ready=" + ready + ", phase=" + phase
          + (waitingReason.isBlank() ? "" : ", waiting=" + waitingReason)
          + (terminatedReason.isBlank() ? "" : ", terminated=" + terminatedReason);
    }
  }

  /**
   * Constructs JSONPath string from given segments; escapes potential dot chars.
   *
   * @param segments Segments for construction
   * @return JSONPath string
   */
  private static String buildJsonPath(String[] segments) {
    StringBuilder jsonPath = new StringBuilder("{");
    for (String segment : segments) {
      jsonPath.append(".").append(segment.replace(".", "\\."));
    }
    return jsonPath + "}";
  }

  /**
   * Creates a temporary file and writes content to it.
   *
   * <p>Temp file is marked for deletion on JVM shutdown.</p>
   *
   * @param content To be written to file
   * @return Absolute path to temp file
   */
  private static String createTempFile(String content) throws IOException {
    Path tmp = Files.createTempFile("testsuite-", ".tmp");
    Files.writeString(tmp, content, StandardCharsets.UTF_8);
    tmp.toFile().deleteOnExit();
    return tmp.toAbsolutePath().toString();
  }

  private static void deleteTempFileQuietly(String tempFile) {
    if (tempFile == null) {
      return;
    }

    try {
      Files.deleteIfExists(Path.of(tempFile));
    } catch (IOException e) {
      log.warn("Could not delete temp file: {}", tempFile, e);
    }
  }
}
