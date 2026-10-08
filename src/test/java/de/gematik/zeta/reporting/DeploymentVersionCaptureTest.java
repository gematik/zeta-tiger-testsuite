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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link DeploymentVersionCapture}. */
class DeploymentVersionCaptureTest {

  private static final String PODS_JSON = """
      {
        "items": [
          {
            "metadata": {
              "namespace": "zeta-staging",
              "name": "guard-123"
            },
            "status": {
              "initContainerStatuses": [
                {
                  "name": "init",
                  "image": "registry.example/init:1.0",
                  "imageID": "docker-pullable://registry.example/init@sha256:init"
                }
              ],
              "containerStatuses": [
                {
                  "name": "guard",
                  "image": "registry.example/guard:1.2.3",
                  "imageID": "docker-pullable://registry.example/guard@sha256:guard"
                }
              ],
              "ephemeralContainerStatuses": [
                {
                  "name": "debug",
                  "image": "registry.example/debug:2.0",
                  "imageID": ""
                }
              ]
            }
          }
        ]
      }
      """;

  @TempDir
  Path tempDir;

  /** Verifies that one pod snapshot produces native Serenity and Allure metadata. */
  @Test
  void capturesAllContainerTypesInNativeReportMetadata() throws Exception {
    var serenityDirectory = tempDir.resolve("serenity");
    var allureDirectory = tempDir.resolve("allure");
    var service = new FakeKubernetesService(
        new CommandResult(List.of("kubectl"), 0, PODS_JSON, ""));

    var captured = DeploymentVersionCapture.captureAutomatically(
        service, "zeta-staging", serenityDirectory, allureDirectory);

    assertThat(captured).isTrue();
    assertThat(service.arguments)
        .containsExactly(
            "get", "pods", "--namespace", "zeta-staging", "--output", "json");
    var serenityBuildInfo = new ObjectMapper().readTree(
        serenityDirectory.resolve(DeploymentVersions.SERENITY_BUILD_INFO_FILE).toFile())
        .path("zeta-staging/guard-123")
        .path("values");
    assertThat(serenityBuildInfo.path("init [init_container]").asText())
        .isEqualTo("init:1.0 | init@sha256:init");
    assertThat(serenityBuildInfo.path("guard [container]").asText())
        .isEqualTo("guard:1.2.3 | guard@sha256:guard");
    assertThat(serenityBuildInfo.path("debug [ephemeral_container]").asText())
        .isEqualTo("debug:2.0");
    var allureEnvironment = loadProperties(
        allureDirectory.resolve(DeploymentVersions.ALLURE_ENVIRONMENT_FILE));
    assertThat(allureEnvironment)
        .containsEntry(
            "deployment.version.0001",
            "zeta-staging/guard-123/init [init_container] = registry.example/init:1.0 "
                + "(docker-pullable://registry.example/init@sha256:init)")
        .containsEntry(
            "deployment.version.0002",
            "zeta-staging/guard-123/guard [container] = registry.example/guard:1.2.3 "
                + "(docker-pullable://registry.example/guard@sha256:guard)")
        .containsEntry(
            "deployment.version.0003",
            "zeta-staging/guard-123/debug [ephemeral_container] = registry.example/debug:2.0");
  }

  /** Verifies that unavailable Kubernetes access does not fail startup or create final artifacts. */
  @Test
  void ignoresKubectlFailure() throws Exception {
    var serenityDirectory = Files.createDirectory(tempDir.resolve("serenity"));
    var allureDirectory = Files.createDirectory(tempDir.resolve("allure"));
    Files.writeString(
        serenityDirectory.resolve(DeploymentVersions.SERENITY_BUILD_INFO_FILE), "{}");
    Files.writeString(
        allureDirectory.resolve(DeploymentVersions.ALLURE_ENVIRONMENT_FILE),
        "deployment.version.0001=stale\nexisting=value\n");
    var service = new FakeKubernetesService(
        new CommandResult(List.of("kubectl"), 1, "", "forbidden"));

    var captured = DeploymentVersionCapture.captureAutomatically(
        service, "zeta-staging", serenityDirectory, allureDirectory);

    assertThat(captured).isFalse();
    assertThat(serenityDirectory.resolve(DeploymentVersions.SERENITY_BUILD_INFO_FILE))
        .doesNotExist();
    assertThat(loadProperties(
        allureDirectory.resolve(DeploymentVersions.ALLURE_ENVIRONMENT_FILE)))
        .containsOnlyKeys("existing")
        .containsEntry("existing", "value");
  }

  /** Verifies that a missing kubectl executable is handled like other unavailable cluster access. */
  @Test
  void ignoresMissingKubectlExecutable() {
    var serenityDirectory = tempDir.resolve("serenity");
    var allureDirectory = tempDir.resolve("allure");
    var captured = DeploymentVersionCapture.captureAutomatically(
        new MissingKubectlService(), "zeta-staging", serenityDirectory, allureDirectory);

    assertThat(captured).isFalse();
    assertThat(serenityDirectory.resolve(DeploymentVersions.SERENITY_BUILD_INFO_FILE))
        .doesNotExist();
    assertThat(allureDirectory.resolve(DeploymentVersions.ALLURE_ENVIRONMENT_FILE))
        .doesNotExist();
  }

  /** Verifies that malformed successful output is rejected before either artifact is published. */
  @Test
  void ignoresMalformedKubectlOutput() {
    var serenityDirectory = tempDir.resolve("serenity");
    var allureDirectory = tempDir.resolve("allure");
    var service = new FakeKubernetesService(
        new CommandResult(List.of("kubectl"), 0, "not-json", ""));

    var captured = DeploymentVersionCapture.captureAutomatically(
        service, "zeta-staging", serenityDirectory, allureDirectory);

    assertThat(captured).isFalse();
    assertThat(serenityDirectory.resolve(DeploymentVersions.SERENITY_BUILD_INFO_FILE))
        .doesNotExist();
    assertThat(allureDirectory.resolve(DeploymentVersions.ALLURE_ENVIRONMENT_FILE))
        .doesNotExist();
  }

  /**
   * Load a Java properties file for assertions.
   *
   * @param input properties file
   * @return loaded properties
   */
  private static Properties loadProperties(Path input) throws Exception {
    var properties = new Properties();
    try (var reader = Files.newBufferedReader(input)) {
      properties.load(reader);
    }
    return properties;
  }

  /** Fake Kubernetes service returning one canned command result. */
  private static final class FakeKubernetesService
      extends ZetaDeploymentModificationService {

    private final CommandResult result;
    private List<String> arguments = List.of();

    /**
     * Create the fake service.
     *
     * @param result canned kubectl result
     */
    private FakeKubernetesService(CommandResult result) {
      super(1, 1);
      this.result = result;
    }

    /**
     * Return the canned kubectl response.
     *
     * @param verbose ignored logging flag
     * @param commandArguments kubectl arguments
     * @return canned result
     */
    @Override
    public CommandResult executeKubectlCommand(
        boolean verbose, List<String> commandArguments) {
      arguments = List.copyOf(commandArguments);
      return result;
    }
  }

  /** Fake Kubernetes service representing a runtime without kubectl. */
  private static final class MissingKubectlService
      extends ZetaDeploymentModificationService {

    /** Create the fake service. */
    private MissingKubectlService() {
      super(1, 1);
    }

    /**
     * Simulate the command-execution error raised when kubectl is unavailable.
     *
     * @param verbose ignored logging flag
     * @param commandArguments ignored kubectl arguments
     * @return never returns
     */
    @Override
    public CommandResult executeKubectlCommand(
        boolean verbose, List<String> commandArguments) {
      throw new AssertionError("cannot execute kubectl");
    }
  }
}
