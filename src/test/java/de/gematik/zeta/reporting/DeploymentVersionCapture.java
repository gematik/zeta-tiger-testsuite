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

package de.gematik.zeta.reporting;

import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/** Captures the tested Kubernetes container versions before the test run starts. */
@Slf4j
public final class DeploymentVersionCapture {

  private static final String DEFAULT_SERENITY_OUTPUT_DIRECTORY = "target/site/serenity";
  private static final String DEFAULT_ALLURE_RESULTS_DIRECTORY = "target/allure-results";

  private DeploymentVersionCapture() {
    // utility class
  }

  /**
   * Best-effort capture of deployment versions for the current run.
   *
   * <p>Missing kubectl, missing permissions, or an unavailable cluster do not fail the test run.</p>
   *
   * @param kubernetesService service used to execute kubectl
   */
  public static void capture(ZetaDeploymentModificationService kubernetesService) {
    var serenityOutputDirectory = Path.of(System.getProperty(
        "serenity.outputDirectory", DEFAULT_SERENITY_OUTPUT_DIRECTORY));
    var allureResultsDirectory = Path.of(System.getProperty(
        "allure.results.directory", DEFAULT_ALLURE_RESULTS_DIRECTORY));
    captureAutomatically(
        kubernetesService,
        ZetaDeploymentConfiguration.getNamespace(),
        serenityOutputDirectory,
        allureResultsDirectory);
  }

  /**
   * Best-effort Kubernetes capture isolated for focused verification.
   *
   * @param kubernetesService service used to execute kubectl
   * @param namespace Kubernetes namespace
   * @param serenityOutputDirectory Serenity output directory
   * @param allureResultsDirectory Allure results directory
   * @return {@code true} when the artifacts were captured
   */
  static boolean captureAutomatically(
      ZetaDeploymentModificationService kubernetesService,
      String namespace,
      Path serenityOutputDirectory,
      Path allureResultsDirectory) {
    removeGeneratedArtifacts(serenityOutputDirectory, allureResultsDirectory);
    try {
      captureFromKubernetes(
          kubernetesService, namespace, serenityOutputDirectory, allureResultsDirectory);
      return true;
    } catch (RuntimeException | AssertionError exception) {
      removeGeneratedArtifacts(serenityOutputDirectory, allureResultsDirectory);
      log.warn("Deployment versions could not be collected; continuing without them: {}",
          exception.getMessage());
      return false;
    }
  }

  /**
   * Remove artifacts from an earlier attempt so stale versions cannot be reported as current.
   *
   * @param serenityOutputDirectory Serenity output directory
   * @param allureResultsDirectory Allure results directory
   */
  private static void removeGeneratedArtifacts(
      Path serenityOutputDirectory, Path allureResultsDirectory) {
    try {
      Files.deleteIfExists(
          serenityOutputDirectory.resolve(DeploymentVersions.SERENITY_BUILD_INFO_FILE));
    } catch (IOException exception) {
      log.warn("Could not remove stale deployment-version artifacts: {}", exception.getMessage());
    }
    try {
      DeploymentVersions.clearAllureEnvironment(allureResultsDirectory);
    } catch (RuntimeException exception) {
      log.warn("Could not remove stale Allure deployment versions: {}", exception.getMessage());
    }
  }

  /**
   * Query the pod snapshot and publish its versions to Serenity and Allure.
   *
   * @param kubernetesService service used to execute kubectl
   * @param namespace Kubernetes namespace
   * @param serenityOutputDirectory Serenity output directory
   * @param allureResultsDirectory Allure results directory
   */
  static void captureFromKubernetes(
      ZetaDeploymentModificationService kubernetesService,
      String namespace,
      Path serenityOutputDirectory,
      Path allureResultsDirectory) {
    var result = kubernetesService.executeKubectlCommand(
        false, "get", "pods", "--namespace", namespace, "--output", "json");
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "kubectl get pods failed for namespace '" + namespace + "': " + result.stderr());
    }

    // Parse before publishing either report format so malformed command output remains non-fatal.
    var deploymentVersions = DeploymentVersions.fromPodsJson(result.stdout());
    deploymentVersions.writeToSerenity(serenityOutputDirectory);
    deploymentVersions.writeToAllure(allureResultsDirectory);
  }
}
