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

package de.gematik.zeta.steps.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.AslProfile;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.steps.Hooks;
import de.gematik.zeta.steps.SoftAssertionsContext;
import de.gematik.zeta.steps.TigerProxyManipulationsSteps;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;
import org.yaml.snakeyaml.Yaml;

/**
 * Unit tests for {@link Hooks}.
 */
public class HooksTest {

  private HttpServer tlsTestToolServiceServer;

  /**
   * Stops embedded services and clears scenario state and configuration after each test.
   */
  @AfterEach
  void tearDown() {
    if (tlsTestToolServiceServer != null) {
      tlsTestToolServiceServer.stop(0);
      tlsTestToolServiceServer = null;
    }
    SoftAssertionsContext.reset();
    Hooks.clearScenarioAborted();
    TigerGlobalConfiguration.clearTestVariables();
  }

  /**
   * Verifies the authentication mode and Kubernetes driver identity declared by each client profile.
   *
   * @param profile client configuration suffix
   * @param authMode expected authentication mode
   * @param driverName expected deployment and container name
   * @throws IOException if the client configuration cannot be read
   */
  @ParameterizedTest
  @CsvSource({"smcb, SMB, testdriver-smcb", "oidc, OIDC, testdriver-oidc"})
  void clientProfilesDeclareDriverIdentity(final String profile, final String authMode,
      final String driverName) throws IOException {
    Map<?, ?> yaml = new Yaml().load(
        Files.readString(Path.of("tiger", "client-paths-" + profile + ".yaml")));
    Map<?, ?> client = Assertions.assertInstanceOf(Map.class, yaml.get("client"));

    assertEquals(authMode, client.get("authMode"));
    assertEquals(driverName, client.get("deploymentName"));
    assertEquals(driverName, client.get("containerName"));
  }

  /**
   * Validates authentication tags without requiring Kubernetes access.
   *
   * @param expectedMode authentication mode required by the tag
   * @param configuredMode authentication mode declared by the client configuration
   */
  @ParameterizedTest
  @CsvSource({"SMB, SMB", "SMB, OIDC", "OIDC, OIDC", "OIDC, SMB"})
  void profileHooksValidateConfigurationWithoutKubectl(final String expectedMode, final String configuredMode) {
    var service = mock(ZetaDeploymentModificationService.class);
    TigerGlobalConfiguration.putValue("paths.client.authMode", configuredMode, ConfigurationValuePrecedence.TEST_CONTEXT);
    var hooks = new Hooks(service, new TigerProxyManipulationsSteps(), mock(TestDriverConfigurationService.class));
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@" + expectedMode.toLowerCase(java.util.Locale.ROOT)));
    Executable checkProfile = expectedMode.equals("SMB") ? hooks::verifySmbProfile : hooks::verifyOidcProfile;

    if (expectedMode.equals(configuredMode)) {
      assertDoesNotThrow(checkProfile);
    } else {
      assertThrows(AssertionError.class, checkProfile);
    }
    hooks.skipIfKubectlMissing(scenario);
    verifyNoInteractions(service);
  }

  /**
   * Checks the selected driver's runtime mode after Kubernetes prerequisites have passed.
   *
   * @param requiredMode authentication mode required by the scenario
   * @param actualMode selected container's runtime authentication mode
   */
  @ParameterizedTest
  @CsvSource({"SMB, SMB", "SMB, OIDC", "OIDC, OIDC", "OIDC, SMB"})
  void kubectlHookVerifiesSelectedDriverRuntime(final String requiredMode, final String actualMode) {
    final var service = mock(ZetaDeploymentModificationService.class);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "test-namespace", ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("paths.client.deploymentName", "selected-driver", ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("paths.client.containerName", "selected-container", ConfigurationValuePrecedence.TEST_CONTEXT);
    when(service.executeKubectlCommand(anyBoolean(), any(String[].class))).thenReturn(
        new CommandResult(List.of(), 0,
            "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"selected-container\","
                + "\"env\":[{\"name\":\"AUTH_MODE\",\"value\":\"" + actualMode + "\"}]}]}}}}", ""));
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@require_kubectl", "@" + requiredMode.toLowerCase(java.util.Locale.ROOT)));
    try (var configuration = mockStatic(ZetaDeploymentConfiguration.class)) {
      configuration.when(ZetaDeploymentConfiguration::getNamespace).thenReturn("test-namespace");
      var hooks = new Hooks(service, new TigerProxyManipulationsSteps(), mock(TestDriverConfigurationService.class));
      if (requiredMode.equals(actualMode)) {
        assertDoesNotThrow(() -> hooks.skipIfKubectlMissing(scenario));
      } else {
        assertThrows(AssertionError.class, () -> hooks.skipIfKubectlMissing(scenario));
      }
      var calls = inOrder(service);
      calls.verify(service).verifyRequirements("test-namespace");
      calls.verify(service).executeKubectlCommand(false, "-n", "test-namespace", "get", "deployment", "selected-driver", "-o", "json");
    }
  }

  /** Checks shared Kubernetes prerequisites without inspecting an unrelated client deployment. */
  @Test
  void kubectlHookDoesNotInspectDriverForSharedScenarios() {
    var service = mock(ZetaDeploymentModificationService.class);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "test-namespace", ConfigurationValuePrecedence.TEST_CONTEXT);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@require_kubectl"));
    var hooks = new Hooks(service, new TigerProxyManipulationsSteps(), mock(TestDriverConfigurationService.class));

    hooks.skipIfKubectlMissing(scenario);

    verify(service).verifyRequirements("test-namespace");
    verifyNoMoreInteractions(service);
  }

  /**
   * Verifies that rollout cleanup restores the configured update tag when the original image differs from the expected target.
   */
  @Test
  void restorePepDeploymentImageEnsuresConfiguredUpdateTag() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.podName", "pep-deployment",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.containerName", "nginx",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.image.versionUpdate", "main",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    FakeDeploymentModificationService service = new FakeDeploymentModificationService();
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

    FakeDeploymentModificationService service = new FakeDeploymentModificationService();
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

    FakeDeploymentModificationService service = new FakeDeploymentModificationService();
    service.currentImageReferenceResult = new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:main", "");
    Hooks hooks = new Hooks(service, new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());

    boolean restored = hooks.restorePepDeploymentImage("zeta-local");

    assertFalse(restored);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.cleanupExpectedImage);
    assertEquals("registry.example.org/zeta/ngx_pep:main", service.verifyExpectedImage);
  }

  /**
   * Verifies that the TLS client hooks configure the testdriver and reset it again after the scenario.
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
        "src/test/resources/tls-test-tool/certificates/ecdsa/zeta-tls-test-tool-server_CA.cer",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("tlsTestTool.clientDisableTlsVerification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var deploymentService = new FakeDeploymentModificationService();
    var testDriverService = new FakeTestDriverConfigurationService();
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(), testDriverService);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@tls_client_fachdienst_hook"));

    hooks.prepareSoftAssertions();
    hooks.patchTestdriverForTlsClientScenario(scenario);
    hooks.rollbackTestdriverAfterTlsClientScenario(scenario);

    assertEquals(1, testDriverService.resetCount);
    assertEquals(1, testDriverService.configureWhenReadyCount);
    assertEquals("https://zeta-tls-test-tool-server:4433", testDriverService.configuredResource);
    assertTrue(testDriverService.configuredCaCertificatePem.startsWith("-----BEGIN CERTIFICATE-----"));
    assertTrue(testDriverService.configuredDisableTlsVerification);
  }

  /**
   * Verifies that a failed TLS client post-hook reset is reported without failing the scenario.
   */
  @Test
  void rollbackTestdriverAfterTlsClientScenarioLogsFailedResetWithoutThrowing() {
    var testDriverService = new FakeTestDriverConfigurationService();
    testDriverService.resetFailure = new AssertionError("reset unreachable");
    var hooks = new Hooks(new FakeDeploymentModificationService(), new TigerProxyManipulationsSteps(),
        testDriverService);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@tls_client_fachdienst_hook"));
    when(scenario.getName()).thenReturn("TLS Client Reset");

    hooks.prepareSoftAssertions();

    assertDoesNotThrow(() -> hooks.rollbackTestdriverAfterTlsClientScenario(scenario));
    assertEquals(1, testDriverService.resetCount);
    verify(scenario).log("TLS client post hook: could not reset the testdriver after scenario 'TLS Client Reset'.");
  }

  /**
   * Verifies that the cache-reset hook cannot restart a TLS client deployment without explicit permission.
   */
  @Test
  void resetTlsRevocationCacheAbortsWithoutDeploymentModificationPermission() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "false",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var deploymentService = new FakeDeploymentModificationService();
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@tls_client_fachdienst_hook", "@reset_tls_revocation_cache"));

    hooks.prepareSoftAssertions();

    assertThrows(TestAbortedException.class, () -> hooks.resetTlsRevocationCache(scenario));
    assertEquals(0, deploymentService.restartCount);
    verify(scenario).log("Skipping: TLS revocation cache reset requires deployment modification permission");
  }

  /**
   * Verifies that revocation resets target the selected driver while preserving native-client selection.
   *
   * @param regularDriver deployment selected by the active regular-client profile
   * @param hookTag scenario tag selecting the regular or native client
   * @param expectedDriver only pod prefix that may be restarted
   */
  @ParameterizedTest
  @CsvSource({
      "testdriver-smcb, @tls_client_fachdienst_hook, testdriver-smcb",
      "testdriver-oidc, @tls_client_fachdienst_hook, testdriver-oidc",
      "testdriver-smcb, @tls_native_client_fachdienst_hook, nativedriver"
  })
  void resetTlsRevocationCacheRestartsSelectedDriver(
      final String regularDriver, final String hookTag, final String expectedDriver) {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.podReadyTimeout", "1",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("paths.client.deploymentName", regularDriver,
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("paths.nativeClient.deploymentName", "nativedriver",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var deploymentService = new FakeDeploymentModificationService();
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of(hookTag, "@reset_tls_revocation_cache"));

    hooks.resetTlsRevocationCache(scenario);

    assertEquals(1, deploymentService.restartCount);
    assertEquals(expectedDriver, deploymentService.restartedPodPrefix);
  }

  /**
   * Verifies that a missing driver identity cannot fall back to restarting an ambiguous testdriver pod.
   */
  @Test
  void resetTlsRevocationCacheRejectsBlankDriverIdentity() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("paths.client.deploymentName", " ",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var deploymentService = new FakeDeploymentModificationService();
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@tls_client_fachdienst_hook", "@reset_tls_revocation_cache"));

    var error = assertThrows(AssertionError.class, () -> hooks.resetTlsRevocationCache(scenario));

    assertEquals("Missing driver configuration paths.client.deploymentName.", error.getMessage());
    assertEquals(0, deploymentService.restartCount);
  }

  /** Reserves the active OIDC driver for shared scenarios as well as tagged scenarios. */
  @Test
  void oidcProfileReservesDriverForEveryScenario() {
    TigerGlobalConfiguration.putValue("profile", "oidc-proxy", ConfigurationValuePrecedence.TEST_CONTEXT);
    try (var driver = mockStatic(de.gematik.zeta.steps.OidcScenarioContext.class)) {
      var hooks = new Hooks(new FakeDeploymentModificationService(), new TigerProxyManipulationsSteps(),
          new FakeTestDriverConfigurationService());
      hooks.reserveOidcDriver();
      driver.verify(de.gematik.zeta.steps.OidcScenarioContext::acquireDriver);
    }
  }

  /** Leaves the OIDC driver untouched in SMB runs. */
  @Test
  void smbProfileDoesNotReserveOidcDriver() {
    TigerGlobalConfiguration.putValue("profile", "proxy", ConfigurationValuePrecedence.TEST_CONTEXT);
    try (var driver = mockStatic(de.gematik.zeta.steps.OidcScenarioContext.class)) {
      var hooks = new Hooks(new FakeDeploymentModificationService(), new TigerProxyManipulationsSteps(),
          new FakeTestDriverConfigurationService());
      hooks.reserveOidcDriver();
      driver.verifyNoInteractions();
    }
  }

  /**
   * Verifies that the OCSP mock cleanup endpoint resolves placeholders before constructing its URI.
   */
  @Test
  void resolveOcspMockAdminUriResolvesConfiguredPlaceholders() {
    TigerGlobalConfiguration.putValue("zeta_base_url", "zeta-kind.local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zeta_cert_validation_mock_url", "${zeta_base_url}:9013",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var hooks = new Hooks(new FakeDeploymentModificationService(), new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());

    var resolvedUri = assertDoesNotThrow(() -> {
      var method = Hooks.class.getDeclaredMethod("resolveOcspMockAdminUri");
      method.setAccessible(true);
      return method.invoke(hooks);
    });

    assertEquals("http://zeta-kind.local:9013/admin/mode", resolvedUri.toString());
  }

  /**
   * Verifies that after hooks do not execute cleanup work after a scenario was aborted in a before hook.
   */
  @Test
  void restoreDeploymentModificationsDoesNothingAfterScenarioAbort() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "false",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var deploymentService = new FakeDeploymentModificationService();
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@deployment_modification"));

    hooks.prepareSoftAssertions();

    Assertions.assertThrows(TestAbortedException.class,
        () -> hooks.verifyDeploymentModification(scenario));

    assertDoesNotThrow(() -> hooks.restoreDeploymentModifications(scenario));
    assertNull(deploymentService.cleanupExpectedImage);
    assertNull(deploymentService.verifyExpectedImage);
  }

  /**
   * Verifies that replica-only deployment scenarios do not patch the PEP image during cleanup.
   */
  @Test
  void restoreDeploymentModificationsDoesNotTouchUnchangedPepImage() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.configMapName", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.podName", "pep-deployment",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.containerName", "nginx",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.image.versionUpdate", "main",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("paths.client.reset", startTlsTestToolService() + "/reset",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var deploymentService = new FakeDeploymentModificationService();
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@deployment_modification"));

    hooks.prepareSoftAssertions();
    hooks.restoreDeploymentModifications(scenario);

    assertNull(deploymentService.cleanupExpectedImage);
    assertNull(deploymentService.verifyExpectedImage);
  }

  /**
   * Verifies that deferred soft assertion verification does not fail an already skipped scenario.
   */
  @Test
  void verifySoftAssertionsDoesNothingAfterScenarioAbort() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "false",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@deployment_modification"));

    hooks.prepareSoftAssertions();
    SoftAssertionsContext.recordSoftFailure("pre-abort soft failure", new IllegalStateException("boom"));

    Assertions.assertThrows(TestAbortedException.class,
        () -> hooks.verifyDeploymentModification(scenario));

    assertDoesNotThrow(hooks::verifySoftAssertions);
  }

  /**
   * Verifies that scenarios explicitly tagged as no-proxy scenarios do not require a configured TigerProxy.
   */
  @Test
  void skipIfProxyMissingAllowsNoProxyScenario() {
    TigerGlobalConfiguration.putValue("tiger.tigerProxy.proxyId", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@no_proxy"));

    hooks.prepareSoftAssertions();

    assertDoesNotThrow(() -> hooks.skipIfProxyMissing(scenario));
  }

  /**
   * Verifies that proxy-dependent scenarios continue when a TigerProxy is configured.
   */
  @Test
  void skipIfProxyMissingAllowsProxyDependentScenarioWhenProxyConfigured() {
    TigerGlobalConfiguration.putValue("tiger.tigerProxy.proxyId", "Cluster",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of());

    hooks.prepareSoftAssertions();

    assertDoesNotThrow(() -> hooks.skipIfProxyMissing(scenario));
  }

  /**
   * Verifies that performance scenarios are skipped unless they are enabled explicitly.
   */
  @Test
  void skipPerformanceScenarioWhenNotEnabled() {
    TigerGlobalConfiguration.putValue("allow_performance_tests", "false",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@performance"));

    hooks.prepareSoftAssertions();

    assertThrows(TestAbortedException.class, () -> hooks.skipPerformanceAndLongRunningScenarios(scenario));
    verify(scenario).log("Skipping: performance scenarios require allow_performance_tests=true and scenario is tagged @performance");
  }

  /**
   * Verifies that long-running scenarios are skipped unless they are enabled explicitly.
   */
  @Test
  void skipLongRunningScenarioWhenNotEnabled() {
    TigerGlobalConfiguration.putValue("allow_longrunning_tests", "false",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@longrunning"));

    hooks.prepareSoftAssertions();

    assertThrows(TestAbortedException.class, () -> hooks.skipPerformanceAndLongRunningScenarios(scenario));
    verify(scenario).log("Skipping: long-running scenarios require allow_longrunning_tests=true and scenario is tagged @longrunning");
  }

  /**
   * Verifies that explicitly enabled performance and long-running scenarios are not skipped by the guard.
   */
  @Test
  void doNotSkipPerformanceOrLongRunningScenariosWhenEnabled() {
    TigerGlobalConfiguration.putValue("allow_performance_tests", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("allow_longrunning_tests", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@performance", "@longrunning"));

    hooks.prepareSoftAssertions();

    assertDoesNotThrow(() -> hooks.skipPerformanceAndLongRunningScenarios(scenario));
  }

  /**
   * Verifies that TPM scenarios are skipped unless the test object explicitly supports a TPM environment.
   */
  @Test
  void skipTpmEnvironmentScenarioWhenTestObjectHasNoTpm() {
    TigerGlobalConfiguration.putValue("tpm_environment", "false",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@tpm_environment"));

    hooks.prepareSoftAssertions();

    assertThrows(TestAbortedException.class, () -> hooks.skipIfTpmEnvironmentMissing(scenario));
    verify(scenario).log("Skipping: TPM scenarios require tpm_environment=true and scenario is tagged @tpm_environment");
  }

  /**
   * Verifies that TPM scenarios continue when the test object explicitly supports a TPM environment.
   */
  @Test
  void doNotSkipTpmEnvironmentScenarioWhenTestObjectHasTpm() {
    TigerGlobalConfiguration.putValue("tpm_environment", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var hooks = new Hooks(new FakeDeploymentModificationService(),
        new TigerProxyManipulationsSteps(), new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@tpm_environment"));

    hooks.prepareSoftAssertions();

    assertDoesNotThrow(() -> hooks.skipIfTpmEnvironmentMissing(scenario));
  }

  /**
   * Verifies that kubectl requirement failures abort the scenario as skipped instead of failing the before hook.
   */
  @Test
  void skipIfKubectlMissingAbortsScenarioWhenRequirementCheckFails() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-staging",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var deploymentService = new FakeDeploymentModificationService();
    deploymentService.requirementsFailure =
        new AssertionError("Requirement check failed: cannot execute kubectl command.");
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@smb", "@require_kubectl"));
    when(scenario.getName()).thenReturn("Client Authentisierung");

    hooks.prepareSoftAssertions();

    assertThrows(TestAbortedException.class, () -> hooks.skipIfKubectlMissing(scenario));
    verify(scenario).log("Skipping: kubectl requirement check failed and scenario is tagged @require_kubectl");
  }

  /**
   * Verifies that deployment modification requirement failures abort the scenario as skipped instead of failing the before hook.
   */
  @Test
  void verifyDeploymentModificationAbortsScenarioWhenRequirementCheckFails() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-staging",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var deploymentService = new FakeDeploymentModificationService();
    deploymentService.requirementsFailure =
        new AssertionError("Requirement check failed: cannot execute kubectl command.");
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@deployment_modification"));

    hooks.prepareSoftAssertions();

    assertThrows(TestAbortedException.class, () -> hooks.verifyDeploymentModification(scenario));
    verify(scenario).log("Skipping: verification check for deployment modification failed");
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

  private static class FakeDeploymentModificationService extends ZetaDeploymentModificationService {

    private final CommandResult imagePathResult =
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep", "");
    private final CommandResult verifyResult =
        new CommandResult(List.of("kubectl"), 0, "verify ok", "");
    private CommandResult currentImageReferenceResult =
        new CommandResult(List.of("kubectl"), 0, "registry.example.org/zeta/ngx_pep:0.3.0", "");
    private CommandResult cleanupResult =
        new CommandResult(List.of("kubectl"), 0, "cleanup ok", "");
    private String cleanupExpectedImage;
    private String verifyExpectedImage;
    private AssertionError requirementsFailure;
    private int restartCount;
    private String restartedPodPrefix;

    /**
     * Creates a fake deployment configuration service with short timeout values for tests.
     */
    private FakeDeploymentModificationService() {
      super(1, 1);
    }

    /**
     * Throws the configured fake requirement failure when present.
     *
     * @param namespace namespace to validate
     */
    @Override
    public void verifyRequirements(final String namespace) {
      if (requirementsFailure != null) {
        throw requirementsFailure;
      }
    }

    /**
     * Records a requested pod restart and its target without modifying a deployment.
     *
     * @param namespace target namespace
     * @param podName selected driver's pod prefix
     * @param waitForPodReadiness whether readiness must be awaited
     * @param readinessTimeout maximum readiness wait in seconds
     */
    @Override
    public void restartPod(String namespace, String podName, boolean waitForPodReadiness, int readinessTimeout) {
      restartCount++;
      restartedPodPrefix = podName;
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

  /**
   * Mock implementation of the {@link TestDriverConfigurationService}TestDriverConfigurationService for unit testing.
   */
  public static final class FakeTestDriverConfigurationService extends TestDriverConfigurationService {

    private int resetCount;
    private String configuredResource;
    private String configuredCaCertificatePem;
    private boolean configuredDisableTlsVerification;
    private int configureWhenReadyCount;
    private AssertionError resetFailure;

    /**
     * Creates a fake testdriver configuration service backed by placeholder URLs.
     */
    public FakeTestDriverConfigurationService() {
      super("http://localhost/reset", "http://localhost/configure");
    }

    /**
     * Records that a reset call was issued.
     */
    @Override
    public void reset() {
      resetCount++;
      if (resetFailure != null) {
        throw resetFailure;
      }
    }

    /**
     * Records the configuration payload supplied by the TLS client hook.
     */
    @Override
    public void configure(String resource, String caCertificatePem,
        boolean clientDisableTlsVerification) {
      configuredResource = resource;
      configuredCaCertificatePem = caCertificatePem;
      configuredDisableTlsVerification = clientDisableTlsVerification;
    }

    /**
     * Records readiness-aware configuration and delegates to the payload capture method.
     */
    @Override
    public void configureWhenReady(
        String resource,
        String caCertificatePem,
        boolean clientDisableTlsVerification,
        Duration timeout,
        Duration retryInterval) {
      configureWhenReadyCount++;
      configure(resource, caCertificatePem, clientDisableTlsVerification);
    }
  }
}
