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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService.ImmutableServiceClusterIpOverride;
import de.gematik.zeta.services.ZetaDeploymentModificationService.PepNginxRouteTemplateConfig;
import de.gematik.zeta.services.model.HelmDeploymentRequest;
import de.gematik.zeta.services.model.ZetaClientDataForwardingToggleRequest;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages access to testsuite configuration regarding the ZETA deployment modification.
 */
public class ZetaDeploymentConfiguration {
  public static final int MAX_KEY_DEPTH = 20;
  public static final int POD_READY_TIMEOUT_DEFAULT = 120;
  public static final int PROCESS_TIMEOUT_DEFAULT = 60;

  /**
   * Uses global Tiger config to create a preconfigured instance of ZetaDeploymentConfigurationService.
   *
   * @return configured ZetaDeploymentConfigurationService instance
   */
  public static ZetaDeploymentModificationService getServiceInstance() {
    int processTimeout = TigerGlobalConfiguration.readIntegerOptional("zetaDeploymentConfig.commandTimeout")
        .orElse(PROCESS_TIMEOUT_DEFAULT);
    int podReadyTimeout = TigerGlobalConfiguration.readIntegerOptional("zetaDeploymentConfig.podReadyTimeout")
        .orElse(POD_READY_TIMEOUT_DEFAULT);
    return new ZetaDeploymentModificationService(
        processTimeout,
        podReadyTimeout,
        getImmutableServiceClusterIpOverrides(),
        getPepNginxRouteTemplateConfig());
  }

  /**
   * Reads Service cluster IP overrides used to preserve immutable Kubernetes Service fields during direct Helm upgrades.
   *
   * @return configured Service cluster IP overrides, or compatibility defaults when no overrides are configured
   */
  public static List<ImmutableServiceClusterIpOverride> getImmutableServiceClusterIpOverrides() {
    var result = new ArrayList<ImmutableServiceClusterIpOverride>();
    var baseKeyPath = "zetaDeploymentConfig.immutableServiceClusterIpOverrides";
    for (int i = 0; i < MAX_KEY_DEPTH; i++) {
      var indexedKeyPath = baseKeyPath + "." + i;
      var serviceName = TigerGlobalConfiguration.readStringOptional(indexedKeyPath + ".serviceName").orElse(null);
      if (serviceName == null) {
        break;
      }
      var helmValueKey = TigerGlobalConfiguration.readStringOptional(indexedKeyPath + ".helmValueKey")
          .orElseThrow(() -> new AssertionError("Missing variable: " + indexedKeyPath + ".helmValueKey"));
      var targetStage = TigerGlobalConfiguration.readStringOptional(indexedKeyPath + ".targetStage").orElse("");
      result.add(new ImmutableServiceClusterIpOverride(serviceName, helmValueKey, targetStage));
    }
    return result.isEmpty()
        ? ZetaDeploymentModificationService.defaultImmutableServiceClusterIpOverrides()
        : result;
  }

  /**
   * Reads nginx route template metadata for deriving concrete PEP route locations.
   *
   * @return configured PEP nginx route template metadata
   */
  public static PepNginxRouteTemplateConfig getPepNginxRouteTemplateConfig() {
    var defaults = ZetaDeploymentModificationService.defaultPepNginxRouteTemplateConfig();
    var genericRoutePrefix = TigerGlobalConfiguration.readStringOptional("paths.guard.pepRoutePrefix")
        .orElse(defaults.genericRoutePrefix());
    var upstreamBasePath = TigerGlobalConfiguration.readStringOptional("paths.fachdienst.upstreamBasePath")
        .orElse(defaults.upstreamBasePath());
    return new PepNginxRouteTemplateConfig(genericRoutePrefix, upstreamBasePath);
  }

  /**
   * Indicates whether deployment modifications are allowed for the current test run.
   *
   * @return {@code true} when deployment modification is enabled in configuration
   */
  public static boolean isDeploymentModificationAllowed() {
    return TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false);
  }

  /**
   * Indicates whether ASL is configured to be enabled globally.
   *
   * @return {@code true} if the global ASL enable flag is set
   */
  public static boolean isAslEnabled() {
    return TigerGlobalConfiguration.readBooleanOptional("zeta_k8s_enable_asl_globally").orElse(false);
  }

  /**
   * Indicates whether ASL is configured to be disabled globally.
   *
   * @return {@code true} if the global ASL disable flag is set
   */
  public static boolean isAslDisabled() {
    return TigerGlobalConfiguration.readBooleanOptional("zeta_k8s_disable_asl_globally").orElse(false);
  }

  /**
   * Reads the deployment namespace from Tiger configuration.
   *
   * @return configured deployment namespace
   * @throws AssertionError when the namespace is missing
   */
  public static String getNamespace() {
    return TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError("Missing variable: namespace"));
  }

  /**
   * Reads deployment-specific information required for configuration changes.
   *
   * @return deployment details containing namespace, pod and ConfigMap metadata
   * @throws AssertionError if required configuration keys are missing
   */
  public static ZetaDeploymentDetails getDeploymentDetails() throws AssertionError {
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
   * Reads request data required to enable ASL-related configuration in the deployment.
   *
   * @return request payload for enabling ASL
   * @throws AssertionError if required configuration keys are missing
   */
  public static HelmDeploymentRequest getEnableAslRequest() throws AssertionError {
    var helmArgs = List.of(
        TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.helm.aslFlag")
            .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.helm.aslFlag")) + "true"
    );
    return getBaseHelmRequestWithArgs(helmArgs);
  }

  /**
   * Reads request data required to disable ASL-related configuration in the deployment.
   *
   * @return request payload for disabling ASL
   * @throws AssertionError if required configuration keys are missing
   */
  public static HelmDeploymentRequest getDisableAslRequest() throws AssertionError {

    List<String> helmArgs = List.of(
        TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.helm.aslFlag")
            .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.helm.aslFlag")) + "false"
    );

    return getBaseHelmRequestWithArgs(helmArgs);
  }

  /**
   * Reads request data required to update the PEP image tag through the documented Helm deployment path.
   *
   * @param imageTag target PEP image tag
   * @return request payload for a Helm deployment with the PEP image tag override
   * @throws AssertionError if required configuration keys are missing
   */
  public static HelmDeploymentRequest getPepImageTagUpdateRequest(String imageTag) throws AssertionError {
    if (imageTag == null || imageTag.isBlank()) {
      throw new AssertionError("Missing PEP image tag for Helm deployment request");
    }
    var helmArgs = List.of(
        TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.helm.imageTagFlag")
            .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.helm.imageTagFlag"))
            + imageTag.trim()
    );
    var request = getBaseHelmRequestWithArgs(helmArgs);
    var envVars = new HashMap<>(request.environmentVariables());
    TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.helmDeploymentArgs")
        .filter(args -> !args.isBlank())
        .ifPresent(args -> envVars.put("HELM_ARGS", args.trim()));
    return new HelmDeploymentRequest(request.helmDeploymentDirectory(), request.targetStage(),
        request.valuesOverride(), envVars, request.helmReleaseName(), request.helmValuesFile());
  }

  /**
   * Reads request data required to restore original ZETA Guard deployment state.
   *
   * @return request payload for disabling ASL
   * @throws AssertionError if required configuration keys are missing
   */
  public static HelmDeploymentRequest getRestoreRequest() throws AssertionError {
    return getBaseHelmRequestWithArgs(List.of());
  }

  private static HelmDeploymentRequest getBaseHelmRequestWithArgs(List<String> helmArgs) {

    var helmDeploymentDirectory = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.helmDeploymentDirectory")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.helmDeploymentDirectory"));

    var targetStage = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.helmDeploymentTargetStage")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.helmDeploymentTargetStage"));

    var envVars = getEnvironmentVariablesFromConfig("zetaDeploymentConfig.environmentVariables");

    var helmReleaseName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.helmReleaseName")
        .filter(value -> !value.isBlank())
        .orElse("zeta-testenv-" + targetStage);

    var helmValuesFile = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.helmValuesFile")
        .filter(value -> !value.isBlank())
        .orElse("local-test/values." + targetStage + ".yaml");

    return new HelmDeploymentRequest(helmDeploymentDirectory,
        targetStage, helmArgs, envVars, helmReleaseName, helmValuesFile);
  }

  private static Map<String, String> getEnvironmentVariablesFromConfig(String pathToEnvVars) {
    var initialMap = TigerGlobalConfiguration.readMap(pathToEnvVars);
    var map = new HashMap<String, String>();
    for (var k : initialMap.keySet()) {
      map.put(k.toUpperCase().replace(".", "_"), initialMap.get(k));
    }
    return map;
  }

  /**
   * Reads request data required to toggle PoPP token verification configuration.
   *
   * @return request payload for PoPP verification toggling
   * @throws AssertionError if required configuration keys are missing
   */
  public static ZetaPoppTokenToggleRequest getPoppTokenRequest() {
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
   * Reads request data required to toggle client-data forwarding configuration.
   *
   * @return request payload for client-data forwarding toggling
   * @throws AssertionError if required configuration keys are missing
   */
  public static ZetaClientDataForwardingToggleRequest getClientDataForwardingRequest() {
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
   * Parses ordered key segment values from indexed configuration entries.
   *
   * @param baseKeyPath configuration key prefix containing indexed path elements
   * @return ordered key segments read from {@code baseKeyPath.0}, {@code baseKeyPath.1}, ...
   * @throws AssertionError if the configured key depth exceeds {@link #MAX_KEY_DEPTH}
   */
  private static String[] parseKeySegments(String baseKeyPath) throws AssertionError {
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
