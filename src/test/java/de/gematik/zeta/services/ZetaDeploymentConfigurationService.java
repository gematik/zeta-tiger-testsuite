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
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import de.gematik.zeta.services.model.StringTransform;
import de.gematik.zeta.services.model.ZetaAslToggleResult;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaDisableAslRequest;
import de.gematik.zeta.services.model.ZetaEnableAslRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Service for running kubectl commands as part of deployment configuration steps.
 */
@Slf4j
public class ZetaDeploymentConfigurationService {

  private static final String KUBECTL_COMMAND = "kubectl";
  private static final String K8S_SUFFIX_ORIGINAL_RESOURCE = "tiger-original-backup";
  private static final int K8S_POD_STATUS_CHECK_INTERVAL = 2;
  private final int processTimeoutSeconds;
  private final int podReadyTimeoutSeconds;

  /**
   * Constructor for ZetaDeploymentConfigurationService.
   *
   * @param processTimeoutSeconds max timeout for system commands
   */
  public ZetaDeploymentConfigurationService(int processTimeoutSeconds, int podReadyTimeoutSeconds) {
    this.processTimeoutSeconds = processTimeoutSeconds;
    this.podReadyTimeoutSeconds = podReadyTimeoutSeconds;
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
    StringTransform nginxEnableAslFunc = (str) -> {
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

    StringTransform wellKnownEnableAslFunc = (str) -> Pattern.compile(request.wellKnownAslRegex())
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

    StringTransform nginxDisableAslFunc = (str) -> {
      Matcher matcher = Pattern.compile(request.nginxAslRegex()).matcher(str);
      if (!matcher.find()) {
        log.warn("Disable ASL: Could not find ASL config in ConfigMap {}", details.nginxConfigMapName());
        return str;
      }
      return matcher.replaceAll("");
    };

    StringTransform wellKnownDisableAslFunc = (str) -> Pattern.compile(request.wellKnownAslRegex())
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

    StringTransform patchFunc = getPoppToggleFunction(request.nginxPoppValueRegex(), targetRoute,
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

    StringTransform patchFunc = getPoppToggleFunction(request.nginxPoppValueRegex(), targetRoute,
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
                                            StringTransform nginxPatchFunc, StringTransform wellKnownPatchFunc)
      throws TimeoutException, InterruptedException, IOException {

    // patch nginx config map
    KubectlPatchCommandResult pepNginxPatchResult = changeConfigMap(namespace, nginxConfigMapName, nginxConfigMapKeySegments, nginxPatchFunc);

    // patch well known config map
    KubectlPatchCommandResult pepWellKnownPatchResult = changeConfigMap(namespace, wellKnownConfigMapName, wellKnownConfigMapKeySegments, wellKnownPatchFunc);

    // restart pods to load modified config
    restartPod(namespace, pepPodName, true, podReadyTimeoutSeconds);

    return new ZetaAslToggleResult(pepNginxPatchResult, pepWellKnownPatchResult);
  }

  private KubectlPatchCommandResult modifyZetaPoppVerification(String namespace, String pepPodName,
                                            String nginxConfigMapName, String[] nginxConfigMapKeySegments,
                                           StringTransform nginxPatchFunc)
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
   * @return Command result
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException sleeping thread is interrupted by system
   */
  public CommandResult restartPod(String namespace, String podName, boolean waitForPodReadiness, int readinessTimeout)
      throws TimeoutException, InterruptedException {
    String podNameInCluster = getPodNameByPrefix(namespace, podName);
    CommandResult deleteResult = executeKubectlCommand("-n", namespace, "delete", "pod", podNameInCluster);
    if (waitForPodReadiness) {
      log.info("Waiting for pod {} to be ready before proceeding", podName);
      waitForPodState(namespace, podName, readinessTimeout);
    }
    return deleteResult;
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
                                                   StringTransform updateFunc)
      throws IOException, IllegalStateException {

    // get config map value
    CommandResult cmOriginalValue = executeKubectlCommand("-n", namespace, "get", "configmap",
        cmName, "-o=jsonpath=" + buildJsonPath(cmKeySegments));

    String modifiedCm = updateFunc.transform(cmOriginalValue.stdout());

    // sanitize before patching
    modifiedCm = escapeJson(modifiedCm);
    modifiedCm = stripChar("'", modifiedCm);

    // prepare patch string
    String cmPatchStr = assemblePatchString(cmKeySegments, modifiedCm);
    String cmPatchFile = createTempFile(cmPatchStr);

    List<String> params = Arrays.asList("-n", namespace, "patch", "configmap",
        cmName, "--type", "merge", "--patch-file", cmPatchFile);

    // backup existing config map before modification
    CommandResult backupResult = createConfigMapBackup(namespace, cmName);
    if (backupResult.exitCode() != 0) {
      throw new IllegalStateException("Could not backup ConfigMap " + cmName + " before modification");
    }

    // patch config map
    CommandResult patchResult = executeKubectlCommand(params);

    try {
      Files.delete(Path.of(cmPatchFile));
    } catch (IOException e) {
      log.warn("Could not delete temp file: {}", cmPatchFile, e);
    }

    return new KubectlPatchCommandResult(patchResult, cmOriginalValue.stdout());
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

    String backupConfigMapName = assemblyBackupResourceName(configMapName);

    CommandResult existingBackup = executeKubectlCommand("-n", namespace, "get", "configmap",
        backupConfigMapName, "-o", "yaml");

    if (existingBackup.exitCode() != 0) {
      log.warn("ConfigMap backup of {} was not found", configMapName);
      return new CommandResult(new ArrayList<>(), 0, "Not found", "");
    }

    // ConfigMap name must be restored at manifest level
    String originalConfigMap = Pattern.compile("\n {2}name: " + Pattern.quote(backupConfigMapName) + "\n")
                                      .matcher(existingBackup.stdout())
                                      .replaceAll("\n  name: " + configMapName + "\n");

    String configMapTmpFile = createTempFile(originalConfigMap);
    return executeKubectlCommand("-n", namespace, "apply", "--force", "-f", configMapTmpFile);
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

    String backupConfigMapName = assemblyBackupResourceName(configMapName);

    CommandResult existingBackup = executeKubectlCommand("-n", namespace, "get", "configmap",
        backupConfigMapName, "-o", "yaml");

    if (existingBackup.exitCode() == 0) {
      log.debug("ConfigMap {} was already backed up", configMapName);
      return new CommandResult(new ArrayList<>(), 0, "Unchanged", "");
    }

    CommandResult configMapOriginal = executeKubectlCommand("-n", namespace, "get", "configmap",
        configMapName, "-o", "yaml");

    String configMapBackup = Pattern.compile("\n {2}name: " + Pattern.compile(configMapName) + "\n")
                                              .matcher(configMapOriginal.stdout())
                                              .replaceAll("\n  name: " + backupConfigMapName + "\n");

    String configMapTmpFile = createTempFile(configMapBackup);
    return executeKubectlCommand("-n", namespace, "apply", "-f", configMapTmpFile);
  }

  /**
   * Wait for Kubernetes Pod to be in a ready state. Throws when set timeout is exceeded.
   *
   * @param namespace Namespace the Pod is running in
   * @param podName Name of target pod
   * @param timeoutSeconds Maximum number of seconds to wait
   * @return Command result of the last status check
   * @throws TimeoutException if waiting time for expected system state is exceeded
   * @throws InterruptedException if sleeping thread is interrupted by system
   */
  private CommandResult waitForPodState(String namespace, String podName, int timeoutSeconds)
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
        return result;
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
        .map(str -> stripChar("'", str.strip()))
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
  private StringTransform getPoppToggleFunction(String poppValueRegex, String targetRoute, String targetPoppValue) {
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
    Objects.requireNonNull(arguments, "kubectl arguments must not be null");
    List<String> command = new ArrayList<>();
    command.add(KUBECTL_COMMAND);
    command.addAll(arguments);

    try (var commandService = new SystemCommandService(processTimeoutSeconds)) {
      return commandService.executeCommand(command);
    }
  }

  /**
   * Executes a kubectl command with the provided arguments.
   *
   * @param arguments kubectl arguments (excluding the kubectl binary name)
   * @return captured stdout/stderr and exit code
   */
  public CommandResult executeKubectlCommand(String... arguments) {
    Objects.requireNonNull(arguments, "kubectl arguments must not be null");
    return executeKubectlCommand(Arrays.asList(arguments));
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
   * Strips exactly one leading and trailing character.
   *
   * @param character Character (sequence) to be removed
   * @param target String that is to be stripped
   * @return Stripped string
   */
  private static String stripChar(String character, String target) {
    target = target.replaceAll("^" + character, "");
    target = target.replaceAll(character + "$", "");
    return target;
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
}
