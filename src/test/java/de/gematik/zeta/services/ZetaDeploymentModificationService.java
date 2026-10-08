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

import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.HelmDeploymentRequest;
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import de.gematik.zeta.services.model.ZetaClientDataForwardingToggleRequest;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenValidityRequest;
import de.gematik.zeta.services.model.ZetaRequiredScopesRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Service for running kubectl commands as part of deployment configuration steps.
 */
@Slf4j
public class ZetaDeploymentModificationService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KUBECTL_COMMAND = "kubectl";
  private static final String HELM_COMMAND = "helm";
  private static final String HELM_FIELD_MANAGER_ARGUMENT = "--field-manager=helm";
  private static final String MAKE_COMMAND = "make";
  private static final String MAKE_PARAM_HELM_ARGS = "HELM_EXTRA_VALUES_PARAMS";
  private static final String HELM_ARGS_ENVIRONMENT_KEY = "HELM_ARGS";
  private static final String SMB_KEYSTORE_PASSWORD_FILE_ENVIRONMENT_KEY = "SMB_KEYSTORE_PW_FILE";
  private static final String SMB_KEYSTORE_FILE_ENVIRONMENT_KEY = "SMB_KEYSTORE_FILE_B64";
  private static final List<ImmutableServiceClusterIpOverride> DEFAULT_IMMUTABLE_SERVICE_CLUSTER_IP_OVERRIDES = List.of(
      new ImmutableServiceClusterIpOverride("tiger-proxy", "global.dns.tigerStaticClusterIP", ""),
      new ImmutableServiceClusterIpOverride(
          "zeta-cert-validation-mock", "zeta-cert-validation-mock.service.clusterIP", "achelos")
  );
  private static final PepNginxRouteTemplateConfig DEFAULT_PEP_NGINX_ROUTE_TEMPLATE =
      new PepNginxRouteTemplateConfig("/pep/", "/testfachdienst");
  private static final String K8S_SUFFIX_ORIGINAL_RESOURCE = "tiger-original-backup";
  private static final int K8S_POD_STATUS_CHECK_INTERVAL = 2;
  private static final int MAX_OBSERVATION_ENTRIES = 8;
  private final int processTimeoutSeconds;
  @Getter
  private final int podReadyTimeoutSeconds;
  private final List<ImmutableServiceClusterIpOverride> immutableServiceClusterIpOverrides;
  private final PepNginxRouteTemplateConfig pepNginxRouteTemplate;

  /**
   * Constructor for ZetaDeploymentConfigurationService.
   *
   * @param processTimeoutSeconds max timeout for system commands
   */
  public ZetaDeploymentModificationService(int processTimeoutSeconds, int podReadyTimeoutSeconds) {
    this(
        processTimeoutSeconds,
        podReadyTimeoutSeconds,
        defaultImmutableServiceClusterIpOverrides(),
        defaultPepNginxRouteTemplateConfig());
  }

  /**
   * Constructor for ZetaDeploymentConfigurationService.
   *
   * @param processTimeoutSeconds max timeout for system commands
   * @param podReadyTimeoutSeconds max timeout for pod readiness checks
   * @param immutableServiceClusterIpOverrides Service cluster IP override definitions for direct Helm upgrades
   * @param pepNginxRouteTemplate nginx route template metadata used to derive concrete PEP route locations
   */
  public ZetaDeploymentModificationService(int processTimeoutSeconds, int podReadyTimeoutSeconds,
      List<ImmutableServiceClusterIpOverride> immutableServiceClusterIpOverrides,
      PepNginxRouteTemplateConfig pepNginxRouteTemplate) {
    this.processTimeoutSeconds = processTimeoutSeconds;
    this.podReadyTimeoutSeconds = podReadyTimeoutSeconds;
    this.immutableServiceClusterIpOverrides = List.copyOf(
        Objects.requireNonNull(immutableServiceClusterIpOverrides, "immutableServiceClusterIpOverrides must not be null"));
    this.pepNginxRouteTemplate = Objects.requireNonNull(pepNginxRouteTemplate, "pepNginxRouteTemplate must not be null");

    if (K8S_POD_STATUS_CHECK_INTERVAL < 1) {
      throw new IllegalArgumentException("value of K8S_POD_STATUS_CHECK_INTERVAL can not be < 1");
    }
  }

  /**
   * Returns the default Service cluster IP overrides used for direct Helm upgrades.
   *
   * @return immutable default Service cluster IP override definitions
   */
  public static List<ImmutableServiceClusterIpOverride> defaultImmutableServiceClusterIpOverrides() {
    return DEFAULT_IMMUTABLE_SERVICE_CLUSTER_IP_OVERRIDES;
  }

  /**
   * Returns the default nginx route template metadata used for PEP route derivation.
   *
   * @return default PEP nginx route template configuration
   */
  public static PepNginxRouteTemplateConfig defaultPepNginxRouteTemplateConfig() {
    return DEFAULT_PEP_NGINX_ROUTE_TEMPLATE;
  }

  /**
   * Configures one Helm value that should be set to the live Kubernetes Service cluster IP.
   *
   * @param serviceName Kubernetes Service name whose cluster IP is read
   * @param helmValueKey Helm value key that receives the live cluster IP
   * @param targetStage optional target stage filter; blank means all stages
   */
  public record ImmutableServiceClusterIpOverride(String serviceName, String helmValueKey, String targetStage) {

    /**
     * Validates and normalizes the immutable Service override definition.
     */
    public ImmutableServiceClusterIpOverride {
      serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null").trim();
      helmValueKey = Objects.requireNonNull(helmValueKey, "helmValueKey must not be null").trim();
      targetStage = targetStage == null ? "" : targetStage.trim();
      if (serviceName.isBlank()) {
        throw new IllegalArgumentException("serviceName must not be blank");
      }
      if (helmValueKey.isBlank()) {
        throw new IllegalArgumentException("helmValueKey must not be blank");
      }
    }

    /**
     * Checks whether this override applies to the requested Helm target stage.
     *
     * @param currentTargetStage target stage from the Helm deployment request
     * @return {@code true} when no stage filter is configured or the filter matches the current stage
     * @throws NullPointerException when this override has a stage filter but the current target stage is {@code null}
     */
    public boolean appliesTo(String currentTargetStage) {
      return targetStage.isBlank()
          || targetStage.equals(Objects.requireNonNull(currentTargetStage, "currentTargetStage must not be null"));
    }
  }

  /**
   * Configures how concrete PEP nginx route locations are derived from the generic PEP location.
   *
   * @param genericRoutePrefix generic PEP route prefix, for example {@code /pep/}
   * @param upstreamBasePath upstream base path in the generic {@code proxy_pass} value
   */
  public record PepNginxRouteTemplateConfig(String genericRoutePrefix, String upstreamBasePath) {

    /**
     * Validates and normalizes the nginx route template metadata.
     */
    public PepNginxRouteTemplateConfig {
      genericRoutePrefix = Objects.requireNonNull(genericRoutePrefix, "genericRoutePrefix must not be null").trim();
      upstreamBasePath = Objects.requireNonNull(upstreamBasePath, "upstreamBasePath must not be null").trim();
      if (!genericRoutePrefix.startsWith("/") || !genericRoutePrefix.endsWith("/")) {
        throw new IllegalArgumentException("genericRoutePrefix must start and end with '/'");
      }
      if (!upstreamBasePath.startsWith("/")) {
        throw new IllegalArgumentException("upstreamBasePath must start with '/'");
      }
      while (upstreamBasePath.length() > 1 && upstreamBasePath.endsWith("/")) {
        upstreamBasePath = upstreamBasePath.substring(0, upstreamBasePath.length() - 1);
      }
    }
  }

  /**
   * Enables the Additional Security Layer (ASL) by modifying a ZETA Guard deployment.
   *
   * @param request Request parameter required for enabling ASL
   * @return Object containing information about the executed command
   * @throws AssertionError if any other exception occurs, acts as a wrapper
   */
  public CommandResult enableAsl(HelmDeploymentRequest request) throws AssertionError {
    return deployZetaGuardWithHelmOverrides(request);
  }

  /**
   * Disables the Additional Security Layer (ASL) by modifying a ZETA Guard deployment.
   *
   * @param request Request parameter required for disable ASL
   * @return Object containing information about the executed command
   * @throws AssertionError if any other exception occurs, acts as a wrapper
   */
  public CommandResult disableAsl(HelmDeploymentRequest request) throws AssertionError {
    return deployZetaGuardWithHelmOverrides(request);
  }

  /**
   * Performs a ZETA Guard deployment via make + helm with the provided Helm value overrides.
   *
   * @param request request containing stage, Helm value overrides and environment variables
   * @return captured command result of the make deployment
   * @throws AssertionError if command execution fails before the process can return an exit code
   */
  public CommandResult deployZetaGuardWithHelmOverrides(HelmDeploymentRequest request) throws AssertionError {
    String helmArgs = request.valuesOverride().stream()
        .map(value -> "--set " + value)
        .reduce("", (acc, value) -> acc.isBlank() ? value : acc + " " + value);

    List<String> args = List.of("deploy", "stage=" + request.targetStage(),
        MAKE_PARAM_HELM_ARGS + "='" + helmArgs + "'");

    return executeMakeCommand(request.helmDeploymentDirectory(), args, request.environmentVariables());
  }

  /**
   * Performs a ZETA Guard Helm deployment directly and lets Helm roll back a failed upgrade.
   *
   * @param request request containing stage, Helm value overrides and environment variables
   * @param namespace Kubernetes namespace for the Helm release
   * @param timeout Helm timeout value, for example {@code 2m}
   * @return captured command result of the Helm deployment
   * @throws AssertionError if command execution fails before Helm can return an exit code
   */
  public CommandResult deployZetaGuardWithHelmRollbackOnFailure(
      HelmDeploymentRequest request, String namespace, String timeout) throws AssertionError {
    return deployZetaGuardWithHelmRollbackOnFailure(request, namespace, timeout, processTimeoutSeconds);
  }

  /**
   * Performs a ZETA Guard deployment directly through Helm with rollback-on-failure enabled.
   *
   * @param request required Helm deployment inputs
   * @param namespace namespace of the Helm release
   * @param timeout Helm timeout value, for example {@code 2m}
   * @param commandTimeoutSeconds max timeout for the surrounding system command
   * @return captured command result of the Helm deployment
   * @throws AssertionError if command execution fails before Helm can return an exit code
   */
  public CommandResult deployZetaGuardWithHelmRollbackOnFailure(
      HelmDeploymentRequest request, String namespace, String timeout, int commandTimeoutSeconds) throws AssertionError {
    Objects.requireNonNull(request, "Helm deployment request must not be null");
    Objects.requireNonNull(request.helmReleaseName(), "helmReleaseName must not be null");
    Objects.requireNonNull(request.helmValuesFile(), "helmValuesFile must not be null");
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (commandTimeoutSeconds <= 0) {
      throw new AssertionError("Helm command timeout must be positive: " + commandTimeoutSeconds);
    }

    List<String> args = new ArrayList<>(List.of(
        "upgrade",
        "--install",
        request.helmReleaseName(),
        ".",
        "-f",
        request.helmValuesFile()
    ));

    request.valuesOverride().forEach(value -> {
      args.add("--set");
      args.add(value);
    });
    appendRequiredSmcbKeystoreArgs(args, request.environmentVariables());
    appendConfiguredHelmArgs(args, request.environmentVariables().get(HELM_ARGS_ENVIRONMENT_KEY));
    appendImmutableServiceIpOverrides(args, request, namespace);
    args.add("-n");
    args.add(namespace);
    args.add("--rollback-on-failure");
    args.add("--timeout");
    args.add(timeout);

    return executeHelmCommand(
        request.helmDeploymentDirectory(), args, request.environmentVariables(), commandTimeoutSeconds);
  }

  /**
   * Proves Helm rollback-on-failure with an isolated PEP image deployment instead of upgrading the shared ZETA release.
   *
   * @param namespace Kubernetes namespace for the temporary Helm release
   * @param containerName container name used in the temporary deployment
   * @param stableImage stable PEP image expected after rollback
   * @param failedImageTag invalid image tag used to force the failed upgrade
   * @param installTimeout Helm timeout value for installing the stable temporary release, for example {@code 10m}
   * @param rollbackTimeout Helm timeout value for the failed upgrade, for example {@code 30s}
   * @param commandTimeoutSeconds max timeout for the surrounding system command
   * @return captured command result of the failing Helm upgrade
   * @throws AssertionError if setup, rollback verification, or command execution fails
   */
  public CommandResult verifyIsolatedPepHelmRollbackOnFailure(
      String namespace,
      String containerName,
      String stableImage,
      String failedImageTag,
      String installTimeout,
      String rollbackTimeout,
      int commandTimeoutSeconds) throws AssertionError {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(containerName, "containerName must not be null");
    Objects.requireNonNull(stableImage, "stableImage must not be null");
    Objects.requireNonNull(failedImageTag, "failedImageTag must not be null");
    Objects.requireNonNull(installTimeout, "installTimeout must not be null");
    Objects.requireNonNull(rollbackTimeout, "rollbackTimeout must not be null");
    if (failedImageTag.isBlank()) {
      throw new AssertionError("Missing faulty image tag for isolated Helm rollback proof");
    }
    if (failedImageTag.contains(":") || failedImageTag.contains("/")) {
      throw new AssertionError("Faulty image tag must not contain ':' or '/': " + failedImageTag);
    }
    if (stableImage.isBlank()) {
      throw new AssertionError("Missing stable image for isolated Helm rollback proof");
    }
    if (installTimeout.isBlank()) {
      throw new AssertionError("Missing install timeout for isolated Helm rollback proof");
    }
    if (rollbackTimeout.isBlank()) {
      throw new AssertionError("Missing rollback timeout for isolated Helm rollback proof");
    }
    if (commandTimeoutSeconds <= 0) {
      throw new AssertionError("Helm command timeout must be positive: " + commandTimeoutSeconds);
    }

    String suffix = Long.toUnsignedString(System.nanoTime(), 36);
    String releaseName = "zeta-rollback-proof-" + suffix;
    Path chartDirectory = null;
    CommandResult rollbackResult;
    try {
      chartDirectory = createRollbackProofChartDirectory();
      CommandResult installResult = executeHelmCommand(chartDirectory.toString(),
          List.of(
              "upgrade",
              "--install",
              releaseName,
              ".",
              "--set",
              "deploymentName=" + releaseName,
              "--set",
              "containerName=" + containerName,
              "--set",
              "image=" + stableImage,
              "-n",
              namespace,
              "--wait",
              "--timeout",
              installTimeout),
          Map.of(), commandTimeoutSeconds);
      if (installResult.exitCode() != 0) {
        throw new AssertionError("Isolated Helm rollback proof setup failed.\nstdout:\n"
            + installResult.stdout() + "\nstderr:\n" + installResult.stderr());
      }

      String failedImage = replaceImageTag(stableImage, failedImageTag);
      rollbackResult = executeHelmCommand(chartDirectory.toString(),
          List.of(
              "upgrade",
              releaseName,
              ".",
              "--reuse-values",
              "--set",
              "image=" + failedImage,
              "-n",
              namespace,
              "--rollback-on-failure",
              "--wait",
              "--timeout",
              rollbackTimeout),
          Map.of(), commandTimeoutSeconds);
      if (rollbackResult.exitCode() == 0) {
        throw new AssertionError("Isolated Helm rollback proof upgrade unexpectedly succeeded for image '"
            + failedImage + "'. The faulty image tag may actually exist.\nstdout:\n"
            + rollbackResult.stdout() + "\nstderr:\n" + rollbackResult.stderr());
      }
      CommandResult verifyResult = verifyDeploymentUpdate(namespace, releaseName, containerName, stableImage);
      if (verifyResult.exitCode() != 0) {
        throw new AssertionError("Isolated Helm rollback proof did not return to the stable image.\n"
            + "Helm stdout:\n" + rollbackResult.stdout() + "\nHelm stderr:\n" + rollbackResult.stderr()
            + "\nVerification stderr:\n" + verifyResult.stderr());
      }
      return rollbackResult;
    } finally {
      try {
        if (chartDirectory != null) {
          executeHelmCommand(chartDirectory.toString(),
              List.of("uninstall", releaseName, "-n", namespace, "--wait", "--timeout", "60s"),
              Map.of(), commandTimeoutSeconds);
        }
      } catch (AssertionError e) {
        log.warn("Could not uninstall isolated Helm rollback proof release '{}'", releaseName, e);
      }
      deleteDirectoryQuietly(chartDirectory);
    }
  }

  /**
   * Sets the PoPP token verification directive for a given target route in the PEP proxy configuration.
   *
   * @param details Required information that define ZETA Guard deployment
   * @param request Request parameter required for setting PoPP token verification
   * @param targetRoute Route that PoPP token verification should be set for
   * @param enabled {@code true} to enable PoPP token verification, {@code false} to disable it
   * @return Object containing information about the executed steps
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  public KubectlPatchCommandResult setPoppVerification(ZetaDeploymentDetails details, ZetaPoppTokenToggleRequest request,
      String targetRoute, boolean enabled)
      throws IOException, InterruptedException, TimeoutException {
    var targetPoppValue = enabled ? request.nginxPoppEnabledValue() : request.nginxPoppDisabledValue();
    var patchFunc = getPoppToggleFunction(request.nginxPoppValueRegex(), targetRoute, targetPoppValue);

    return modifyPepNginxConfigAndRestart(
        details.namespace(), details.pepPodName(), details.nginxConfigMapName(), details.nginxConfigMapKeySegments(),
        patchFunc
    );
  }

  /**
   * Sets client-data forwarding for a given target route in the PEP proxy configuration.
   *
   * @param details Required information that define ZETA Guard deployment
   * @param request Request parameter required for setting client-data forwarding
   * @param targetRoute Route that client-data forwarding should be set for
   * @param enabled {@code true} to enable client-data forwarding, {@code false} to disable it
   * @return Object containing information about the executed steps
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  public KubectlPatchCommandResult setClientDataForwarding(ZetaDeploymentDetails details,
      ZetaClientDataForwardingToggleRequest request, String targetRoute, boolean enabled)
      throws IOException, InterruptedException, TimeoutException {
    var targetDirective = enabled
        ? request.nginxClientDataForwardingEnabledValue()
        : request.nginxClientDataForwardingDisabledValue();
    var patchFunc = getNginxDirectiveToggleFunction(
        request.nginxClientDataForwardingRegex(), targetRoute, targetDirective);

    return modifyPepNginxConfigAndRestart(
        details.namespace(), details.pepPodName(), details.nginxConfigMapName(), details.nginxConfigMapKeySegments(),
        patchFunc
    );
  }

  /**
   * Sets the required scopes for a given target route in the PEP proxy configuration.
   *
   * @param details information that defines the ZETA Guard deployment
   * @param request request parameter required for setting required scopes
   * @param targetRoute route for which the required scopes should be set
   * @param requiredScopes space-separated scopes required by the route
   * @return object containing information about the executed steps
   * @throws TimeoutException if waiting for the expected system state exceeds the timeout
   * @throws InterruptedException if restart waiting is interrupted
   * @throws IOException if a required temporary file could not be created
   */
  public KubectlPatchCommandResult setRequiredScopes(ZetaDeploymentDetails details,
      ZetaRequiredScopesRequest request, String targetRoute, String requiredScopes)
      throws IOException, InterruptedException, TimeoutException {
    var targetDirective = renderRequiredScopesDirective(request.nginxRequiredScopesTemplate(), requiredScopes);
    var patchFunc = getNginxDirectiveToggleFunction(
        request.nginxRequiredScopesRegex(), targetRoute, targetDirective);

    return modifyPepNginxConfigAndRestart(
        details.namespace(), details.pepPodName(), details.nginxConfigMapName(), details.nginxConfigMapKeySegments(),
        patchFunc
    );
  }

  /**
   * Sets the PoPP token validity mode in the PEP proxy of a ZETA Guard deployment.
   *
   * @param details Required information that define ZETA Guard deployment
   * @param request Request parameter required for modifying PoPP token validity
   * @param validity PoPP validity value, either {@code quarter} or a duration like {@code 300s}
   * @return Object containing information about the executed ConfigMap patch
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   * @throws IOException if required temporary file could not be created
   */
  public KubectlPatchCommandResult setPoppTokenValidity(ZetaDeploymentDetails details,
      ZetaPoppTokenValidityRequest request, String validity)
      throws IOException, InterruptedException, TimeoutException {

    var patchFunc = getPoppValidityFunction(request, validity);

    return modifyPepNginxConfigAndRestart(
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
   * Performs a ZETA Guard deployment via make + helm without any overrides and thus restores the initial state of
   * the deployment.
   *
   * @param request Request parameter required for restoring deployment state
   * @return Object containing information about the executed command
   */
  public CommandResult restoreOriginalZetaDeployment(HelmDeploymentRequest request) {
    List<String> args = List.of("deploy", "stage=" + request.targetStage());
    return executeMakeCommand(request.helmDeploymentDirectory(), args, request.environmentVariables());
  }

  /**
   * Reads the desired replica count from a deployment spec.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name
   * @return successful result with replica count in stdout, otherwise a failing command result
   */
  public CommandResult getDeploymentReplicaCount(String namespace, String deploymentName) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");

    CommandResult result = executeKubectlCommand(
        "get", "deployment", deploymentName, "-n", namespace, "-o", "jsonpath='{.spec.replicas}'");
    if (result.exitCode() != 0) {
      return result;
    }

    String stdout = result.stdout() == null ? "" : result.stdout().trim();
    if (stdout.isBlank()) {
      stdout = "1";
    }

    try {
      Integer.parseInt(stdout);
      return new CommandResult(result.command(), 0, stdout, result.stderr());
    } catch (NumberFormatException e) {
      return new CommandResult(
          result.command(),
          1,
          result.stdout(),
          "Could not parse deployment replicas for " + deploymentName + ": " + stdout);
    }
  }

  /**
   * Scales a deployment to the requested replica count and waits until rollout and replica readiness
   * are complete.
   *
   * @param namespace Kubernetes namespace
   * @param deploymentName deployment name
   * @param replicas desired replica count
   * @return successful command result when scaling and rollout succeeded, otherwise a failing result
   */
  public CommandResult scaleDeployment(String namespace, String deploymentName, int replicas) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");
    if (replicas < 0) {
      throw new IllegalArgumentException("replicas must not be negative");
    }

    String patch = String.format("{\"spec\":{\"replicas\":%d}}", replicas);
    String patchFile = null;
    CommandResult scaleResult;
    try {
      patchFile = createTempFile(patch);
      scaleResult = executeKubectlCommand(
          "patch", "deployment", deploymentName,
          "-n", namespace,
          HELM_FIELD_MANAGER_ARGUMENT,
          "--type=merge",
          "--patch-file", patchFile);
    } catch (Exception e) {
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "patch", "deployment", deploymentName, "-n", namespace),
          1, "", "Failed to scale deployment '" + deploymentName + "': " + e.getMessage());
    } finally {
      deleteTempFileQuietly(patchFile);
    }
    if (scaleResult.exitCode() != 0) {
      return scaleResult;
    }

    return waitForDeploymentRollout(namespace, deploymentName, this.podReadyTimeoutSeconds);
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
          HELM_FIELD_MANAGER_ARGUMENT,
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
   * Reads the current deployment strategy as JSON.
   *
   * @param namespace Target namespace
   * @param deploymentName Target deployment name
   * @return Command result containing the deployment strategy JSON on stdout
   */
  public CommandResult getDeploymentStrategy(String namespace, String deploymentName) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");

    return executeKubectlCommand("get", "deployment", deploymentName,
        "-n", namespace, "-o", "jsonpath={.spec.strategy}");
  }

  /**
   * Replaces the deployment strategy with the given JSON object.
   *
   * @param namespace Target namespace
   * @param deploymentName Target deployment name
   * @param strategyJson Deployment strategy JSON
   * @return Command result of the patch operation
   */
  public CommandResult setDeploymentStrategy(String namespace, String deploymentName, String strategyJson) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(deploymentName, "deploymentName must not be null");
    Objects.requireNonNull(strategyJson, "strategyJson must not be null");

    String patch = "{\"spec\":{\"strategy\":" + strategyJson + "}}";
    String patchFile = null;
    try {
      patchFile = createTempFile(patch);
      return executeKubectlCommand("patch", "deployment", deploymentName,
          "-n", namespace,
          HELM_FIELD_MANAGER_ARGUMENT,
          "--patch-file", patchFile);
    } catch (Exception e) {
      String patchFileArg = patchFile != null ? patchFile : "<not-created>";
      return new CommandResult(
          List.of(KUBECTL_COMMAND, "patch", "deployment", deploymentName, "-n", namespace, "--patch-file", patchFileArg),
          1,
          "",
          "Error while updating deployment strategy for deployment : " + deploymentName + "\n " + e.getMessage()
      );
    } finally {
      deleteTempFileQuietly(patchFile);
    }
  }

  /**
   * Sets the RollingUpdate maxSurge and maxUnavailable values of a deployment.
   *
   * @param namespace Target namespace
   * @param deploymentName Target deployment name
   * @param maxSurge maxSurge value, for example {@code 0} or {@code 25%}
   * @param maxUnavailable maxUnavailable value, for example {@code 1} or {@code 25%}
   * @return Command result of the patch operation
   */
  public CommandResult setDeploymentRollingUpdateStrategy(
      String namespace,
      String deploymentName,
      String maxSurge,
      String maxUnavailable) {
    Objects.requireNonNull(maxSurge, "maxSurge must not be null");
    Objects.requireNonNull(maxUnavailable, "maxUnavailable must not be null");
    String strategyJson = String.format(
        "{\"type\":\"RollingUpdate\",\"rollingUpdate\":{\"maxSurge\":%s,\"maxUnavailable\":%s}}",
        formatIntOrString(maxSurge),
        formatIntOrString(maxUnavailable));
    return setDeploymentStrategy(namespace, deploymentName, strategyJson);
  }

  /**
   * Formats a Kubernetes IntOrString value as JSON.
   *
   * @param value raw value
   * @return JSON number for integer values, otherwise JSON string
   */
  private String formatIntOrString(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.matches("\\d+")) {
      return trimmed;
    }
    return "\"" + escapeJson(trimmed) + "\"";
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
      String podName = pod.path("metadata").path("name").asString("");
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
    } catch (JacksonException e) {
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
      String reason = event.path("reason").asString("");
      String message = event.path("message").asString("");
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
        String podName = pod.path("metadata").path("name").asString("");
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
      String podName = pod.path("metadata").path("name").asString("");
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
      if ("ReplicaSet".equals(ownerReference.path("kind").asString(""))) {
        return ownerReference.path("name").asString("");
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
      String podName = pod.path("metadata").path("name").asString("");
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
        if (containerName.equals(container.path("name").asString(""))) {
          image = container.path("image").asString("");
          break;
        }
      }
    }

    JsonNode containerStatuses = pod.path("status").path("containerStatuses");
    if (!containerStatuses.isArray()) {
      return new ContainerStateSnapshot(image, false, pod.path("status").path("phase").asString(""), "", "");
    }

    for (JsonNode status : containerStatuses) {
      if (!containerName.equals(status.path("name").asString(""))) {
        continue;
      }
      return new ContainerStateSnapshot(
          image,
          status.path("ready").asBoolean(false),
          pod.path("status").path("phase").asString(""),
          status.path("state").path("waiting").path("reason").asString(""),
          status.path("state").path("terminated").path("reason").asString("")
      );
    }

    return new ContainerStateSnapshot(image, false, pod.path("status").path("phase").asString(""), "", "");
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
  public CommandResult waitForDeploymentRollout(String namespace, String deploymentName, int timeoutSeconds) {
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
   * Applies an nginx patch and restarts the affected PEP pod to activate the new config.
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
  private KubectlPatchCommandResult modifyPepNginxConfigAndRestart(String namespace, String pepPodName,
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

    var restoreManifest = buildConfigMapRestoreManifest(existingBackup.stdout(), namespace, configMapName);
    var configMapTmpFile = createTempFile(restoreManifest);
    try {
      return executeKubectlCommand("-n", namespace, "apply", "--server-side", "--force-conflicts",
          HELM_FIELD_MANAGER_ARGUMENT, "-f", configMapTmpFile);
    } finally {
      deleteTempFileQuietly(configMapTmpFile);
    }
  }

  /**
   * Builds a sanitized server-side apply manifest from a ConfigMap backup.
   *
   * <p>Only declarative ConfigMap fields are copied. Cluster-generated identity and version fields
   * from the backup resource must not be applied to the Helm-managed target.</p>
   *
   * @param backupConfigMapJson serialized backup ConfigMap
   * @param namespace target namespace
   * @param configMapName target ConfigMap name
   * @return JSON manifest suitable for server-side apply
   * @throws IOException when the backup cannot be parsed or serialized
   */
  private String buildConfigMapRestoreManifest(String backupConfigMapJson, String namespace, String configMapName)
      throws IOException {
    JsonNode backup = JSON.readTree(backupConfigMapJson);
    if (!(backup instanceof ObjectNode restore)) {
      throw new IOException("ConfigMap backup is empty or invalid");
    }

    if (!(restore.path("metadata") instanceof ObjectNode metadata)) {
      throw new IOException("ConfigMap backup contains no metadata object");
    }
    metadata.put("name", configMapName);
    metadata.put("namespace", namespace);
    metadata.remove(List.of(
        "creationTimestamp", "generation", "managedFields", "resourceVersion", "selfLink", "uid"));
    if (metadata.path("annotations") instanceof ObjectNode annotations) {
      annotations.remove("kubectl.kubernetes.io/last-applied-configuration");
    }
    restore.remove("status");
    return JSON.writeValueAsString(restore);
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
    return getNginxDirectiveToggleFunction(poppValueRegex, targetRoute, targetPoppValue);
  }

  /**
   * Creates a function that sets a directive in a nginx config section for a specific route.
   *
   * @param directiveRegex Captures the present directive regardless of its state
   * @param targetRoute Target route for setting the directive
   * @param targetDirective Target directive to be written into the route section
   * @return Function that sets the defined target directive for targetRoute
   */
  private UnaryOperator<String> getNginxDirectiveToggleFunction(String directiveRegex, String targetRoute, String targetDirective) {
    return (str) -> {
      Matcher routeSection = getNginxLocationMatcher(str, targetRoute);
      if (!routeSection.find()) {
        var patchedConfig = addPepRouteLocationWithDirective(str, targetRoute, targetDirective);
        if (!patchedConfig.equals(str)) {
          return patchedConfig;
        }
        log.warn("Setting nginx directive: no matching route config found for {}", targetRoute);
        return str;
      }
      String originalSection = routeSection.group(0);
      Matcher directiveConfigValue = Pattern.compile(directiveRegex).matcher(originalSection);
      String patchedSection;
      if (directiveConfigValue.find()) {
        patchedSection = directiveConfigValue.replaceAll(Matcher.quoteReplacement(targetDirective));
      } else {
        // if directive is not present, append it after last config statement of location section
        Matcher sectionEnd = Pattern.compile(";\\s+}\\s*$").matcher(originalSection);
        patchedSection = sectionEnd.replaceAll(Matcher.quoteReplacement(";\n      " + targetDirective + "\n  }"));
      }
      return str.replace(originalSection, patchedSection);
    };
  }

  /**
   * Renders the nginx directive for the configured required scopes after validating the value.
   *
   * @param template directive template containing a single {@code %s} placeholder
   * @param requiredScopes space-separated scopes to require
   * @return rendered nginx directive
   */
  private String renderRequiredScopesDirective(String template, String requiredScopes) {
    var trimmedScopes = Objects.requireNonNull(requiredScopes, "requiredScopes must not be null").trim();
    if (!trimmedScopes.matches("[A-Za-z0-9._:-]+(?: +[A-Za-z0-9._:-]+)*")) {
      throw new IllegalArgumentException(
          "Required scopes must contain only letters, digits, '.', '_', ':', '-' and spaces between scopes");
    }
    return Objects.requireNonNull(template, "required scopes template must not be null").formatted(trimmedScopes);
  }

  /**
   * Creates a matcher for an nginx location section that addresses the requested route.
   *
   * @param nginxConfig nginx configuration content
   * @param targetRoute route configured in a Cucumber deployment modification step
   * @return matcher positioned before the first matching location section
   */
  private Matcher getNginxLocationMatcher(String nginxConfig, String targetRoute) {
    return Pattern.compile("location\\s+(?:=\\s+)?" + Pattern.quote(targetRoute) + "\\s+\\{[^}]*}")
        .matcher(nginxConfig);
  }

  /**
   * Adds a route-specific PEP location with the requested directive when the generic PEP route exists.
   *
   * @param nginxConfig nginx configuration content
   * @param targetRoute missing route for which a specific location should be created
   * @param targetDirective directive that should be configured in the created location
   * @return updated nginx configuration, or the original content when no route can be derived
   */
  private String addPepRouteLocationWithDirective(String nginxConfig, String targetRoute, String targetDirective) {
    var routePrefix = pepNginxRouteTemplate.genericRoutePrefix();
    if (!targetRoute.startsWith(routePrefix) || routePrefix.equals(targetRoute)) {
      return nginxConfig;
    }

    var genericPepRoute = getNginxLocationMatcher(nginxConfig, routePrefix);
    if (!genericPepRoute.find()) {
      return nginxConfig;
    }

    var newLocation = renderPepRouteLocation(targetRoute, targetDirective, genericPepRoute.group(0));
    if (newLocation.isBlank()) {
      return nginxConfig;
    }

    return nginxConfig.substring(0, genericPepRoute.start())
        + newLocation
        + "\n"
        + nginxConfig.substring(genericPepRoute.start());
  }

  /**
   * Renders an nginx location for a concrete PEP route by reusing the generic PEP upstream.
   *
   * @param targetRoute concrete PEP route to create
   * @param targetDirective directive to insert into the location
   * @param genericPepRoute generic PEP location section used as routing template
   * @return rendered nginx location, or an empty string when no compatible upstream is present
   */
  private String renderPepRouteLocation(String targetRoute, String targetDirective, String genericPepRoute) {
    var proxyPassMatcher = Pattern.compile(
        "proxy_pass\\s+([^;]*?" + Pattern.quote(pepNginxRouteTemplate.upstreamBasePath()) + ")/?\\s*;")
        .matcher(genericPepRoute);
    if (!proxyPassMatcher.find()) {
      return "";
    }

    var routeSuffix = targetRoute.substring(pepNginxRouteTemplate.genericRoutePrefix().length());
    var proxyPass = proxyPassMatcher.group(1) + "/" + routeSuffix;
    var includeProxyHeaders = genericPepRoute.contains("include proxy_headers.conf;")
        ? "            include proxy_headers.conf;\n"
        : "";
    var proxySslVerifyOff = genericPepRoute.contains("proxy_ssl_verify off;")
        ? "            proxy_ssl_verify off;\n"
        : "";

    return """
            location %s {
%s                proxy_pass %s;
%s                %s
            }
            """.formatted(targetRoute, includeProxyHeaders, proxyPass, proxySslVerifyOff, targetDirective);
  }

  /**
   * Creates a function that sets the global PoPP token validity directive in nginx config.
   *
   * @param request request containing regexes and the target directive template
   * @param validity PoPP validity value, either {@code quarter} or a duration like {@code 300s}
   * @return Function that sets the PoPP validity directive
   */
  private UnaryOperator<String> getPoppValidityFunction(ZetaPoppTokenValidityRequest request, String validity) {
    var targetDirective = renderPoppValidityDirective(request.nginxPoppValidityTemplate(), validity);

    return str -> {
      var validityMatcher = Pattern.compile(request.nginxPoppValidityRegex()).matcher(str);
      if (validityMatcher.find()) {
        return validityMatcher.replaceAll(Matcher.quoteReplacement(targetDirective));
      }

      var anchorMatcher = Pattern.compile(request.nginxPoppValidityAnchorRegex()).matcher(str);
      if (!anchorMatcher.find()) {
        throw new IllegalStateException("Cannot set PoPP token validity: pep_popp_issuer anchor was not found");
      }

      return anchorMatcher.replaceFirst(Matcher.quoteReplacement(anchorMatcher.group(0) + "\n    " + targetDirective));
    };
  }

  /**
   * Renders the PoPP validity directive after validating the configured test value.
   *
   * @param template directive template containing a single {@code %s} placeholder
   * @param validity PoPP validity value, either {@code quarter} or a duration like {@code 300s}
   * @return rendered nginx directive
   */
  private String renderPoppValidityDirective(String template, String validity) {
    var trimmedValidity = Objects.requireNonNull(validity, "validity must not be null").trim();
    if (!trimmedValidity.matches("quarter|\\d+[dhms]?")) {
      throw new IllegalArgumentException(
          "PoPP token validity must be 'quarter' or a duration like '300s', but was: " + validity);
    }
    return template.formatted(trimmedValidity);
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
    builder.append("\"").append(patchValue).append("\"").repeat("}", keyCount);
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
  public CommandResult executeKubectlCommand(String... arguments) throws AssertionError {
    return executeKubectlCommand(true, arguments);
  }

  /**
   * Executes a kubectl command with the provided arguments.
   *
   * @param arguments kubectl arguments (excluding the kubectl binary name)
   * @param verbose logging of response/error messages
   * @return captured stdout/stderr and exit code
   */
  public CommandResult executeKubectlCommand(boolean verbose, String... arguments) throws AssertionError {
    Objects.requireNonNull(arguments, "kubectl arguments must not be null");
    return executeKubectlCommand(verbose, Arrays.asList(arguments));
  }

  /**
   * Executes a Helm command with the provided arguments and command timeout.
   *
   * @param workDir target workdir where the Helm command should be invoked
   * @param args Helm arguments excluding the Helm binary name
   * @param environmentVariables set of environment variables for the Helm command process
   * @param commandTimeoutSeconds max timeout for the surrounding system command
   * @return captured stdout/stderr and exit code
   * @throws AssertionError if any error occurs during command execution
   */
  public CommandResult executeHelmCommand(String workDir, List<String> args, Map<String, String> environmentVariables,
      int commandTimeoutSeconds) throws AssertionError {
    Objects.requireNonNull(args, "Helm arguments must not be null");
    List<String> command = new ArrayList<>();
    command.add(HELM_COMMAND);
    command.addAll(args);

    try (var commandService = new SystemCommandService(commandTimeoutSeconds)) {
      return commandService.executeCommand(command, workDir, environmentVariables);
    }
  }

  /**
   * Executes a make command with the provided arguments.
   *
   * @param workDir target workdir where the make command should be invoked
   * @param args additional arguments that are passed to helm
   * @param environmentVariables set of environment variables for the make command process
   * @return captured stdout/stderr and exit code
   * @throws AssertionError if any error occurs during command execution
   */
  public CommandResult executeMakeCommand(String workDir, List<String> args, Map<String, String> environmentVariables)
      throws AssertionError {
    Objects.requireNonNull(args, "make arguments must not be null");
    List<String> command = new ArrayList<>();
    command.add(MAKE_COMMAND);
    command.addAll(args);

    try (var commandService = new SystemCommandService(processTimeoutSeconds)) {
      return commandService.executeCommand(command, workDir, environmentVariables);
    }
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
        backupConfigMapName, "-o", "json"), false);
  }

  /**
   * Adds the required SMC-B keystore set-file arguments to a Helm command.
   *
   * @param args mutable Helm argument list
   * @param environmentVariables deployment environment variables
   */
  private void appendRequiredSmcbKeystoreArgs(List<String> args, Map<String, String> environmentVariables) {
    String passwordFile = readRequiredEnvironmentVariable(environmentVariables, SMB_KEYSTORE_PASSWORD_FILE_ENVIRONMENT_KEY);
    String keystoreFile = readRequiredEnvironmentVariable(environmentVariables, SMB_KEYSTORE_FILE_ENVIRONMENT_KEY);
    args.add("--set-file");
    args.add("smcb_keystore.password=" + passwordFile);
    args.add("--set-file");
    args.add("smcb_keystore.keystore=" + keystoreFile);
  }

  /**
   * Appends additional Helm arguments from configuration.
   *
   * @param args mutable Helm argument list
   * @param configuredHelmArgs configured Helm argument string
   */
  private void appendConfiguredHelmArgs(List<String> args, String configuredHelmArgs) {
    if (configuredHelmArgs == null || configuredHelmArgs.isBlank()) {
      return;
    }
    args.addAll(parseConfiguredHelmArgs(configuredHelmArgs));
  }

  /**
   * Preserves chart-managed Service cluster IPs that Kubernetes treats as immutable during direct Helm upgrades.
   *
   * @param args mutable Helm argument list
   * @param request deployment request containing the target stage
   * @param namespace Kubernetes namespace for the release
   */
  private void appendImmutableServiceIpOverrides(List<String> args, HelmDeploymentRequest request, String namespace) {
    for (var override : immutableServiceClusterIpOverrides) {
      if (override.appliesTo(request.targetStage())) {
        appendLiveServiceClusterIpOverride(args, namespace, override.serviceName(), override.helmValueKey());
      }
    }
  }

  /**
   * Appends a {@code --set-string} override with the live Service cluster IP when the Service already exists.
   *
   * @param args mutable Helm argument list
   * @param namespace Kubernetes namespace for the Service lookup
   * @param serviceName Kubernetes Service name
   * @param valueKey Helm value key to override
   */
  private void appendLiveServiceClusterIpOverride(
      List<String> args, String namespace, String serviceName, String valueKey) {
    CommandResult result;
    try {
      result = executeKubectlCommand(false, "--request-timeout=3s", "-n", namespace, "get", "service", serviceName,
          "-o", "jsonpath={.spec.clusterIP}");
    } catch (AssertionError e) {
      log.debug("Could not read live clusterIP for Service '{}' in namespace '{}'. Skipping Helm override.",
          serviceName, namespace, e);
      return;
    }
    String clusterIp = result.stdout() == null ? "" : result.stdout().trim();
    if (result.exitCode() != 0 || clusterIp.isBlank() || "None".equalsIgnoreCase(clusterIp)) {
      return;
    }

    args.add("--set-string");
    args.add(valueKey + "=" + clusterIp);
  }

  /**
   * Parses additional Helm arguments with shell-like quote handling.
   *
   * @param configuredHelmArgs configured Helm argument string
   * @return parsed Helm arguments
   * @throws IllegalArgumentException if the argument string contains an unterminated quote, unfinished escape,
   *     or empty quoted token
   */
  private static List<String> parseConfiguredHelmArgs(String configuredHelmArgs) {
    List<String> parsedArgs = new ArrayList<>();
    StringBuilder currentArg = new StringBuilder();
    char activeQuote = 0;
    boolean escaping = false;
    boolean tokenStarted = false;

    for (int index = 0; index < configuredHelmArgs.length(); index++) {
      char current = configuredHelmArgs.charAt(index);
      if (escaping) {
        currentArg.append(current);
        escaping = false;
        continue;
      }
      if (current == '\\' && activeQuote != '\'') {
        escaping = true;
        tokenStarted = true;
        continue;
      }
      if (activeQuote != 0) {
        if (current == activeQuote) {
          activeQuote = 0;
        } else {
          currentArg.append(current);
        }
        continue;
      }
      if (current == '\'' || current == '"') {
        activeQuote = current;
        tokenStarted = true;
        continue;
      }
      if (Character.isWhitespace(current)) {
        if (tokenStarted) {
          appendParsedHelmArg(parsedArgs, currentArg);
          tokenStarted = false;
        }
        continue;
      }
      currentArg.append(current);
      tokenStarted = true;
    }

    if (escaping) {
      throw new IllegalArgumentException("Configured Helm arguments end with an unfinished escape sequence.");
    }
    if (activeQuote != 0) {
      throw new IllegalArgumentException("Configured Helm arguments contain an unterminated quoted value.");
    }
    if (tokenStarted) {
      appendParsedHelmArg(parsedArgs, currentArg);
    }
    return parsedArgs;
  }

  /**
   * Appends the current token to the parsed Helm argument list and rejects explicitly empty tokens.
   *
   * @param parsedArgs parsed argument list
   * @param currentArg current token buffer
   * @throws IllegalArgumentException if the token was explicitly started but has no content
   */
  private static void appendParsedHelmArg(List<String> parsedArgs, StringBuilder currentArg) {
    if (currentArg.isEmpty()) {
      throw new IllegalArgumentException("Configured Helm arguments contain an empty quoted value.");
    }
    parsedArgs.add(currentArg.toString());
    currentArg.setLength(0);
  }

  /**
   * Reads a required environment variable from a deployment request.
   *
   * @param environmentVariables deployment environment variables
   * @param key environment variable key
   * @return configured environment variable value
   * @throws AssertionError if the value is missing or blank
   */
  private String readRequiredEnvironmentVariable(Map<String, String> environmentVariables, String key) {
    String value = environmentVariables == null ? null : environmentVariables.get(key);
    if (value == null || value.isBlank()) {
      throw new AssertionError("Missing required Helm deployment environment variable: " + key);
    }
    return value.trim();
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
    } catch (JacksonException ex) {
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

  /**
   * Creates the minimal Helm chart used by the isolated rollback proof.
   *
   * @return path to the temporary chart directory
   * @throws AssertionError if the chart files cannot be written
   */
  private static Path createRollbackProofChartDirectory() {
    Path chartDirectory = null;
    try {
      chartDirectory = Files.createTempDirectory("zeta-rollback-proof-");
      Path templatesDirectory = Files.createDirectories(chartDirectory.resolve("templates"));
      Files.writeString(chartDirectory.resolve("Chart.yaml"), """
          apiVersion: v2
          name: zeta-rollback-proof
          type: application
          version: 0.1.0
          """, StandardCharsets.UTF_8);
      Files.writeString(chartDirectory.resolve("values.yaml"), """
          deploymentName: zeta-rollback-proof
          containerName: nginx
          image: nginx:stable
          """, StandardCharsets.UTF_8);
      Files.writeString(templatesDirectory.resolve("deployment.yaml"), """
          apiVersion: apps/v1
          kind: Deployment
          metadata:
            name: {{ .Values.deploymentName | quote }}
            labels:
              app.kubernetes.io/name: {{ .Values.deploymentName | quote }}
              app.kubernetes.io/instance: {{ .Release.Name | quote }}
          spec:
            replicas: 1
            selector:
              matchLabels:
                app.kubernetes.io/name: {{ .Values.deploymentName | quote }}
                app.kubernetes.io/instance: {{ .Release.Name | quote }}
            template:
              metadata:
                labels:
                  app.kubernetes.io/name: {{ .Values.deploymentName | quote }}
                  app.kubernetes.io/instance: {{ .Release.Name | quote }}
              spec:
                containers:
                  - name: {{ .Values.containerName | quote }}
                    image: {{ .Values.image | quote }}
                    command:
                      - sh
                      - -c
                      - sleep 3600
                    resources:
                      requests:
                        cpu: 10m
                        memory: 16Mi
                      limits:
                        cpu: 100m
                        memory: 64Mi
          """, StandardCharsets.UTF_8);
      return chartDirectory;
    } catch (IOException e) {
      deleteDirectoryQuietly(chartDirectory);
      throw new AssertionError("Could not create isolated Helm rollback proof chart", e);
    }
  }

  /**
   * Replaces the tag part of a container image while preserving registry ports and repository paths.
   *
   * @param stableImage image reference containing the stable repository
   * @param imageTag replacement tag
   * @return image reference with the replacement tag
   */
  private static String replaceImageTag(String stableImage, String imageTag) {
    String imageWithoutDigest = stableImage.trim().split("@", 2)[0];
    int lastSlash = imageWithoutDigest.lastIndexOf('/');
    int lastColon = imageWithoutDigest.lastIndexOf(':');
    if (lastColon > lastSlash) {
      return imageWithoutDigest.substring(0, lastColon + 1) + imageTag.trim();
    }
    return imageWithoutDigest + ":" + imageTag.trim();
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

  /**
   * Deletes a temporary directory tree and logs cleanup errors without masking the test result.
   *
   * @param directory directory tree to delete
   */
  private static void deleteDirectoryQuietly(Path directory) {
    if (directory == null) {
      return;
    }

    try (var files = Files.walk(directory)) {
      files.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          log.warn("Could not delete temp path: {}", path, e);
        }
      });
    } catch (IOException e) {
      log.warn("Could not read temp directory for deletion: {}", directory, e);
    }
  }
}
