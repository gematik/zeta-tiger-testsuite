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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.model.CommandResult;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HooksTest {

  private HttpServer tlsTestToolServiceServer;

  /**
   * Stops the embedded TLS test tool service after each test.
   */
  @AfterEach
  void tearDown() {
    if (tlsTestToolServiceServer != null) {
      tlsTestToolServiceServer.stop(0);
      tlsTestToolServiceServer = null;
    }
  }

  /**
   * Verifies that rollout cleanup restores the configured update tag when the original image
   * differs from the expected target.
   */
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
    Hooks hooks = new Hooks(service, new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());

    boolean restored = hooks.restorePepDeploymentImage("zeta-local");

    assertTrue(restored);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.cleanupExpectedImage);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.verifyExpectedImage);
  }

  /**
   * Verifies that rollout cleanup reports failure when pod cleanup cannot complete successfully.
   */
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
    Hooks hooks = new Hooks(service, new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());

    boolean restored = hooks.restorePepDeploymentImage("zeta-local");

    assertFalse(restored);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.cleanupExpectedImage);
    assertNull(service.verifyExpectedImage);
  }

  /**
   * Verifies that no restore rollout is triggered when the expected image is already active.
   */
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
    Hooks hooks = new Hooks(service, new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());

    boolean restored = hooks.restorePepDeploymentImage("zeta-local");

    assertFalse(restored);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.cleanupExpectedImage);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.verifyExpectedImage);
  }

  /**
   * Verifies that the TLS client hooks configure the testdriver and reset it again after the
   * scenario.
   */
  @Test
  void rollbackTestdriverAfterTlsClientScenarioResetsAfterSuccessfulConfigure() {
    TigerGlobalConfiguration.putValue("tlsTestTool.url", "https://zeta-tls-test-tool-server",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("tlsTestTool.port", "4433",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("tlsTestTool.serviceUrl", startTlsTestToolService(),
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("tlsTestTool.caCertificatePath",
        "tools/tls-test-tool-1.0.1/certificates/ecdsa/zeta-tls-test-tool-server_CA.cer",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var deploymentService = new FakeDeploymentConfigurationService();
    var testDriverService = new FakeTestDriverConfigurationService();
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(), testDriverService);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@tls_client_fachdienst_hook"));

    hooks.prepareSoftAssertions();
    hooks.patchTestdriverForTlsClientScenario(scenario);
    hooks.rollbackTestdriverAfterTlsClientScenario(scenario);

    assertEquals(1, testDriverService.resetCount);
    assertEquals("https://zeta-tls-test-tool-server:4433", testDriverService.configuredResource);
    assertTrue(testDriverService.configuredCaCertificatePem.startsWith("-----BEGIN CERTIFICATE-----"));
  }

  /**
   * Starts a minimal embedded TLS test tool service stub that serves the {@code /state} endpoint.
   *
   * @return base URL of the embedded service
   */
  private String startTlsTestToolService() {
    try {
      tlsTestToolServiceServer = HttpServer.create(new InetSocketAddress(0), 0);
      tlsTestToolServiceServer.createContext("/state", exchange -> {
        var body = "{\"running\":false,\"lastExitCode\":0,\"startedAt\":null,\"stoppedAt\":null}";
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
      });
      tlsTestToolServiceServer.start();
      return "http://localhost:" + tlsTestToolServiceServer.getAddress().getPort();
    } catch (IOException e) {
      throw new RuntimeException("Failed to start embedded TLS test tool service", e);
    }
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

    /**
     * Creates a fake deployment configuration service with short timeout values for tests.
     */
    private FakeDeploymentConfigurationService() {
      super(1, 1);
    }

    /**
     * Returns the configured fake image path lookup result.
     */
    @Override
    public CommandResult getContainerImagePathForDeployment(String namespace, String deploymentName, String containerName) {
      return imagePathResult;
    }

    /**
     * Returns the configured fake image reference lookup result.
     */
    @Override
    public CommandResult getContainerImageReferenceForDeployment(String namespace, String deploymentName, String containerName) {
      return currentImageReferenceResult;
    }

    /**
     * Captures the expected stable image used during rollout cleanup.
     */
    @Override
    public CommandResult cleanupFailedRolloutPods(String namespace, String deploymentName, String containerName,
        String expectedStableImage) {
      cleanupExpectedImage = expectedStableImage;
      return cleanupResult;
    }

    /**
     * Captures the expected image used during rollout verification.
     */
    @Override
    public CommandResult verifyDeploymentUpdate(String namespace, String deploymentName, String containerName,
        String newImage) {
      verifyExpectedImage = newImage;
      return verifyResult;
    }
  }

  private static final class FakeTestDriverConfigurationService extends TestDriverConfigurationService {

    private boolean resetCalled;
    private int resetCount;
    private String configuredResource;
    private String configuredCaCertificatePem;

    /**
     * Creates a fake testdriver configuration service backed by placeholder URLs.
     */
    private FakeTestDriverConfigurationService() {
      super("http://localhost/reset", "http://localhost/configure");
    }

    /**
     * Records that a reset call was issued.
     */
    @Override
    public void reset() {
      resetCalled = true;
      resetCount++;
    }

    /**
     * Records the configuration payload supplied by the TLS client hook.
     */
    @Override
    public void configure(String resource, String caCertificatePem) {
      configuredResource = resource;
      configuredCaCertificatePem = caCertificatePem;
    }
  }
}
