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
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentConfigurationServiceFactory;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import java.util.ArrayList;

/**
 * Cucumber step definitions for Kubernetes probe verification.
 */
public class KubernetesProbeSteps {

  private final ZetaDeploymentConfigurationService service = ZetaDeploymentConfigurationServiceFactory.getInstance();

  /**
   * Verifies that exactly one pod contains the given container and that the requested Kubernetes probe is configured.
   *
   * @param namespace namespace for the kubectl query
   * @param containerName target container name
   * @param probeName probe to check (`livenessProbe`, `readinessProbe`, `startupProbe`)
   */
  @Und("prüfe im Namespace {tigerResolvedString} dass der Container {tigerResolvedString} eine {kubeProbe} konfiguriert hat")
  @And("verify in namespace {tigerResolvedString} that container {tigerResolvedString} has {kubeProbe} configured")
  public void verifyContainerHasProbeConfigured(String namespace, String containerName, String probeName) {
    var items = service.readPodsForNamespace(namespace);
    var matchingPodNames = new ArrayList<String>();
    JsonNode matchingContainer = null;
    String reportLine;

    for (var pod : items) {
      var containers = pod.path("spec").path("containers");
      if (!containers.isArray()) {
        continue;
      }
      var podName = pod.path("metadata").path("name").asText("<unknown>");
      for (var container : containers) {
        if (!containerName.equals(container.path("name").asText(""))) {
          continue;
        }
        matchingPodNames.add(podName);
        matchingContainer = container;
      }
    }

    if (matchingPodNames.isEmpty()) {
      var msg = "No container with name '" + containerName + "' found in namespace '" + namespace + "'.";
      reportLine = "CONTAINER=" + containerName + " | RESULT=NOT_FOUND";
      SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
    } else if (matchingPodNames.size() > 1) {
      var msg = "Container '" + containerName + "' found in multiple pods in namespace '" + namespace
          + "': " + String.join(", ", matchingPodNames);
      reportLine = "CONTAINER=" + containerName + " | RESULT=AMBIGUOUS | PODS=" + String.join(",", matchingPodNames);
      SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
    } else {
      var podName = matchingPodNames.getFirst();
      var probe = matchingContainer.path(probeName);
      if (probe.isMissingNode() || probe.isNull()) {
        var msg = "Container '" + containerName + "' in pod '" + podName + "' has no " + probeName + " configured.";
        reportLine = "POD=" + podName + " | CONTAINER=" + containerName + " | " + probeName + "=MISSING";
        SoftAssertionsContext.recordSoftFailure(msg, new AssertionError(msg));
      } else {
        reportLine =
            "POD=" + podName + " | CONTAINER=" + containerName + " | " + probeName + ":\n" + probe.toPrettyString();
      }
    }

    SerenityReportUtils.addCustomData(
        "Kubernetes probe check: " + containerName + " / " + probeName,
        reportLine);
  }
}
