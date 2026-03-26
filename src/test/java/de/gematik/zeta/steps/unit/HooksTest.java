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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.model.CommandResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class HooksTest {

  @Test
  void restorePepDeploymentImageEnsuresConfiguredUpdateTag() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.podName", "pep-deployment",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.containerName", "nginx",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.image.versionUpdate", "main",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    FakeDeploymentConfigurationService service = new FakeDeploymentConfigurationService();
    service.currentImageReferenceResult = new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:0.3.0", "");
    Hooks hooks = new Hooks(service);

    boolean restored = hooks.restorePepDeploymentImage("zeta-local");

    assertTrue(restored);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.cleanupExpectedImage);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.verifyExpectedImage);
  }

  @Test
  void restorePepDeploymentImageReturnsFalseWhenCleanupFails() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.podName", "pep-deployment",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.containerName", "nginx",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.image.versionUpdate", "main",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    FakeDeploymentConfigurationService service = new FakeDeploymentConfigurationService();
    service.currentImageReferenceResult = new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:0.3.0", "");
    service.cleanupResult = new CommandResult(List.of("kubectl"), 1, "", "cleanup failed");
    Hooks hooks = new Hooks(service);

    boolean restored = hooks.restorePepDeploymentImage("zeta-local");

    assertFalse(restored);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.cleanupExpectedImage);
    assertNull(service.verifyExpectedImage);
  }

  @Test
  void restorePepDeploymentImageReturnsFalseWhenExpectedImageAlreadyActive() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.podName", "pep-deployment",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.containerName", "nginx",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.image.versionUpdate", "main",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    FakeDeploymentConfigurationService service = new FakeDeploymentConfigurationService();
    service.currentImageReferenceResult = new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:main", "");
    Hooks hooks = new Hooks(service);

    boolean restored = hooks.restorePepDeploymentImage("zeta-local");

    assertFalse(restored);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.cleanupExpectedImage);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.verifyExpectedImage);
  }

  private static final class FakeDeploymentConfigurationService extends ZetaDeploymentConfigurationService {

    private CommandResult imagePathResult =
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep", "");
    private CommandResult currentImageReferenceResult =
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:0.3.0", "");
    private CommandResult cleanupResult =
        new CommandResult(List.of("kubectl"), 0, "cleanup ok", "");
    private CommandResult verifyResult =
        new CommandResult(List.of("kubectl"), 0, "verify ok", "");
    private String cleanupExpectedImage;
    private String verifyExpectedImage;

    private FakeDeploymentConfigurationService() {
      super(1, 1);
    }

    @Override
    public CommandResult getContainerImagePathForDeployment(String namespace, String deploymentName, String containerName) {
      return imagePathResult;
    }

    @Override
    public CommandResult getContainerImageReferenceForDeployment(String namespace, String deploymentName, String containerName) {
      return currentImageReferenceResult;
    }

    @Override
    public CommandResult cleanupFailedRolloutPods(String namespace, String deploymentName, String containerName,
        String expectedStableImage) {
      cleanupExpectedImage = expectedStableImage;
      return cleanupResult;
    }

    @Override
    public CommandResult verifyDeploymentUpdate(String namespace, String deploymentName, String containerName,
        String newImage) {
      verifyExpectedImage = newImage;
      return verifyResult;
    }
  }
}
