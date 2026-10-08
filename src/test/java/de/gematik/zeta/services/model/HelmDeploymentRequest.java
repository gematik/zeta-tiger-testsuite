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

package de.gematik.zeta.services.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contains information required to modify configuration values to disable the
 * Additional Security Layer in a ZETA Guard deployment.
 *
 * @param helmDeploymentDirectory path to the directory where the ZETA Guard deployment is defined
 * @param targetStage stage parameter to be passed to make / helm, affects the target namespace
 * @param valuesOverride additional params to be passed to the helm command
 * @param environmentVariables set of environment variables for the process environment
 * @param helmReleaseName release name used for direct Helm upgrade calls
 * @param helmValuesFile values file path relative to the Helm deployment directory
 */
public record HelmDeploymentRequest(String helmDeploymentDirectory,
                                    String targetStage,
                                    List<String> valuesOverride,
                                    Map<String, String> environmentVariables,
                                    String helmReleaseName,
                                    String helmValuesFile) {

  /**
   * Validates deployment request metadata.
   */
  public HelmDeploymentRequest {
    targetStage = Objects.requireNonNull(targetStage, "targetStage must not be null");
  }

  /**
   * Creates a request with the historical local release and values-file defaults.
   *
   * @param helmDeploymentDirectory path to the directory where the ZETA Guard deployment is defined
   * @param targetStage stage parameter to be passed to make / helm
   * @param valuesOverride additional params to be passed to the helm command
   * @param environmentVariables set of environment variables for the process environment
   */
  public HelmDeploymentRequest(String helmDeploymentDirectory,
                               String targetStage,
                               List<String> valuesOverride,
                               Map<String, String> environmentVariables) {
    this(
        helmDeploymentDirectory,
        targetStage,
        valuesOverride,
        environmentVariables,
        "zeta-testenv-" + targetStage,
        "local-test/values." + targetStage + ".yaml");
  }
}
