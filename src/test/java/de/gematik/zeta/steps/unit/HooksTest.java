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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.steps.Hooks;
import de.gematik.zeta.steps.SoftAssertionsContext;
import de.gematik.zeta.steps.TigerProxyManipulationsSteps;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Unit tests for {@link Hooks}.
 */
public class HooksTest {

  private static final String OIDC_TESTDRIVER_DEPLOYMENT_JSON = """
      {"spec":{"template":{"spec":{"containers":[{"name":"testdriver","env":[
        {"name":"AUTH_MODE","value":"OIDC"}
      ]}]}}}}
      """;
  private static final String SMB_TESTDRIVER_DEPLOYMENT_JSON = """
      {"spec":{"template":{"spec":{"containers":[{"name":"testdriver","env":[
        {"name":"AUTH_MODE","value":"SMB"}
      ]}]}}}}
      """;

  private HttpServer tlsTestToolServiceServer;
  private HttpServer pushNotificationServiceServer;

  /**
   * Stops the embedded TLS test tool service after each test.
   */
  @AfterEach
  void tearDown() {
    if (tlsTestToolServiceServer != null) {
      tlsTestToolServiceServer.stop(0);
      tlsTestToolServiceServer = null;
    }
    if (pushNotificationServiceServer != null) {
      pushNotificationServiceServer.stop(0);
      pushNotificationServiceServer = null;
    }
    SoftAssertionsContext.reset();
    Hooks.clearScenarioAborted();
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
   * Verifies that the OIDC testdriver guard accepts the deployed OIDC mode.
   */
  @Test
  void oidcTestdriverGuardAcceptsOidcAuthMode() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var deploymentService = new FakeDeploymentModificationService();
    deploymentService.testdriverAuthModeResult =
        new CommandResult(List.of("kubectl"), 0, OIDC_TESTDRIVER_DEPLOYMENT_JSON, "");
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@require_oidc_testdriver"));

    hooks.prepareSoftAssertions();

    assertDoesNotThrow(() -> hooks.skipIfTestdriverIsNotConfiguredForOidc(scenario));
    assertEquals(List.of("-n", "zeta-local", "get", "deployment", "testdriver", "-o", "json"),
        deploymentService.testdriverAuthModeArguments);
  }

  /**
   * Verifies that the OIDC testdriver guard skips a deployment left in SMB mode.
   */
  @Test
  void oidcTestdriverGuardAbortsForSmbAuthMode() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var deploymentService = new FakeDeploymentModificationService();
    deploymentService.testdriverAuthModeResult =
        new CommandResult(List.of("kubectl"), 0, SMB_TESTDRIVER_DEPLOYMENT_JSON, "");
    var hooks = new Hooks(deploymentService, new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@require_oidc_testdriver"));

    hooks.prepareSoftAssertions();

    assertThrows(TestAbortedException.class, () -> hooks.skipIfTestdriverIsNotConfiguredForOidc(scenario));
    verify(scenario).log("Skipping: deployed testdriver AUTH_MODE is 'SMB' but OIDC scenarios require 'OIDC'");
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
   * Verifies that the reset hook lists and deletes every registered pusher dynamically.
   */
  @Test
  void resetNotificationPushersDeletesAllReturnedPushers() {
    var receivedRequests = new CopyOnWriteArrayList<String>();
    TigerGlobalConfiguration.putValue("paths.client.notifications.pushers",
        startPushNotificationService(receivedRequests) + "/pushers",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    var hooks = new Hooks(new FakeDeploymentModificationService(), new TigerProxyManipulationsSteps(),
        new FakeTestDriverConfigurationService());
    var scenario = mock(Scenario.class);

    assertDoesNotThrow(() -> hooks.resetNotificationPushers(scenario));

    assertEquals(Set.of(
            "GET null",
            "DELETE pushkey=dynamic-pusher-one&appId=de.gematik.dynamic.one",
            "DELETE pushkey=dynamic-pusher-two&appId=de.gematik.dynamic.two"),
        Set.copyOf(receivedRequests));
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
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@require_kubectl"));
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

  /**
   * Starts a minimal service stub that records pusher cleanup requests.
   *
   * @param receivedRequests thread-safe destination for received method and query values
   * @return base URL of the embedded service
   */
  private String startPushNotificationService(final List<String> receivedRequests) {
    try {
      pushNotificationServiceServer = HttpServer.create(new InetSocketAddress(0), 0);
      pushNotificationServiceServer.createContext("/pushers", exchange -> {
        receivedRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawQuery());
        if ("GET".equals(exchange.getRequestMethod())) {
          var body = """
              [
                {"pushkey":"dynamic-pusher-one","app_id":"de.gematik.dynamic.one"},
                {"pushkey":"dynamic-pusher-two","app_id":"de.gematik.dynamic.two"}
              ]
              """.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        } else {
          exchange.sendResponseHeaders(200, -1);
        }
        exchange.close();
      });
      pushNotificationServiceServer.start();
      return "http://localhost:" + pushNotificationServiceServer.getAddress().getPort();
    } catch (IOException e) {
      throw new AssertionError("Could not start push notification cleanup service stub", e);
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
    private CommandResult testdriverAuthModeResult =
        new CommandResult(List.of("kubectl"), 0, OIDC_TESTDRIVER_DEPLOYMENT_JSON, "");
    private List<String> testdriverAuthModeArguments = List.of();
    private String cleanupExpectedImage;
    private String verifyExpectedImage;
    private AssertionError requirementsFailure;
    private int restartCount;

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
     * Returns the configured testdriver AUTH_MODE result for the OIDC guard.
     *
     * @param verbose whether command output should be logged
     * @param arguments kubectl arguments
     * @return configured testdriver AUTH_MODE command result
     */
    @Override
    public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
      testdriverAuthModeArguments = List.of(arguments);
      return testdriverAuthModeResult;
    }

    /**
     * Records a requested pod restart without modifying a deployment.
     */
    @Override
    public void restartPod(String namespace, String podName, boolean waitForPodReadiness, int readinessTimeout) {
      restartCount++;
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
