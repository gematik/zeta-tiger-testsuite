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

import de.gematik.zeta.services.DeploymentEnvironment;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.de.Gegebensei;
import tools.jackson.databind.ObjectMapper;

/** Steps for reversible, container-specific Kubernetes environment updates. */
public class KubernetesConfigurationSteps {

  /**
   * Updates named environment entries and waits for the deployment rollout.
   *
   * @param container target container
   * @param deployment target deployment
   * @param values JSON object mapping names to literal values
   */
  @Gegebensei("im Container {tigerResolvedString} des Deployments {tigerResolvedString} gelten folgende Umgebungsvariablen:")
  public void configureEnvironment(String container, String deployment, String values) {
    if (!ZetaDeploymentConfiguration.isDeploymentModificationAllowed()) {
      throw new AssertionError("Deployment modification is disabled");
    }
    DeploymentEnvironment.configure(ZetaDeploymentConfiguration.getServiceInstance(),
        ZetaDeploymentConfiguration.getNamespace(), deployment, container,
        new ObjectMapper().readTree(de.gematik.test.tiger.common.config.TigerGlobalConfiguration.resolvePlaceholders(values)));
  }
}
