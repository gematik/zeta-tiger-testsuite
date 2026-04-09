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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.TestDriverConfigurationServiceFactory;
import de.gematik.zeta.services.TlsTestToolServiceFactory;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentConfigurationServiceFactory;
import de.gematik.zeta.traceability.TraceabilityLookup;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.restassured.http.Method;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;
import org.opentest4j.TestAbortedException;

/**
 * Injects traceability information into every Serenity scenario report.
 */
@Slf4j
public class Hooks {

  private static final TraceabilityLookup TRACEABILITY = TraceabilityLookup.load();
  private static final String NO_PROXY_TAG = "@no_proxy";
  private static final String REQUIRE_KUBECTL_TAG = "@require_kubectl";
  private static final String JUSTIFIED_NOT_TESTED_TAG = "@nicht_getestet_begruendet";
  private static final String DEPLOYMENT_MODIFICATION_TAG = "@deployment_modification";
  private static final String TLS_CLIENT_FACHDIENST_HOOK_TAG = "@tls_client_fachdienst_hook";
  private static final String TLS_TEST_TOOL_URL_CONFIG_KEY = "tlsTestTool.url";
  private static final String TLS_TEST_TOOL_PORT_CONFIG_KEY = "tlsTestTool.port";
  private static final String TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY = "tlsTestTool.caCertificatePath";
  private static final ThreadLocal<String> CAPTURED_PEP_ORIGINAL_IMAGE = new ThreadLocal<>();
  private static final int ORDER_RESTORE_DEPLOYMENT_STATE = Integer.MIN_VALUE;
  private static final int ORDER_PREPARE_SOFT_ASSERTIONS = ORDER_RESTORE_DEPLOYMENT_STATE + 1;
  private static final int ORDER_RESET_TIGER_PROXY_STATE = ORDER_PREPARE_SOFT_ASSERTIONS + 1;
  private static final int ORDER_CLEAR_RECORDED_MESSAGES = ORDER_RESET_TIGER_PROXY_STATE + 1;
  private static final int ORDER_PROXY_REQUIREMENT_GUARD = ORDER_CLEAR_RECORDED_MESSAGES + 1;
  private static final int ORDER_JUSTIFIED_NOT_TESTED_GUARD = ORDER_PROXY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_KUBECTL_REQUIREMENT_GUARD = ORDER_JUSTIFIED_NOT_TESTED_GUARD + 1;
  private static final int ORDER_VERIFY_DEPLOYMENT_MODIFICATION = ORDER_KUBECTL_REQUIREMENT_GUARD + 1;
  private static final int ORDER_TLS_CLIENT_PRE_HOOK = ORDER_VERIFY_DEPLOYMENT_MODIFICATION + 1;
  private static final int ORDER_APPEND_TRACEABILITY = Integer.MAX_VALUE;
  private static final int ORDER_VERIFY_SOFT_ASSERTIONS = ORDER_APPEND_TRACEABILITY - 1;
  private static final int ORDER_TLS_CLIENT_POST_HOOK = ORDER_VERIFY_SOFT_ASSERTIONS - 1;
  private final ZetaDeploymentConfigurationService deploymentConfigurationService;
  private final TigerProxyManipulationsSteps tigerProxyManipulationsSteps;
  private final TestDriverConfigurationService testDriverConfigurationService;

  /**
   * Creates hooks backed by the default deployment configuration service instance.
   */
  @SuppressWarnings("unused")
  public Hooks() {
    this(ZetaDeploymentConfigurationServiceFactory.getInstance(), new TigerProxyManipulationsSteps(),
        TestDriverConfigurationServiceFactory.getInstance());
  }

  /**
   * Creates hooks backed by the provided deployment configuration service.
   *
   * @param deploymentConfigurationService service used for deployment-related checks and restoration
   */
  Hooks(final ZetaDeploymentConfigurationService deploymentConfigurationService) {
    this(deploymentConfigurationService, new TigerProxyManipulationsSteps(),
        TestDriverConfigurationServiceFactory.getInstance());
  }

  /**
   * Creates hooks backed by the provided services.
   *
   * @param deploymentConfigurationService service used for deployment-related checks and restoration
   * @param tigerProxyManipulationsSteps   helper used for TigerProxy cleanup before scenarios
   */
  Hooks(final ZetaDeploymentConfigurationService deploymentConfigurationService,
      final TigerProxyManipulationsSteps tigerProxyManipulationsSteps) {
    this(deploymentConfigurationService, tigerProxyManipulationsSteps, TestDriverConfigurationServiceFactory.getInstance());
  }

  /**
   * Creates hooks backed by the provided services.
   *
   * @param deploymentConfigurationService service used for deployment-related checks and restoration
   * @param tigerProxyManipulationsSteps helper used for TigerProxy cleanup before scenarios
   * @param testDriverConfigurationService service used for testdriver reset/configure operations
   */
  Hooks(final ZetaDeploymentConfigurationService deploymentConfigurationService,
      final TigerProxyManipulationsSteps tigerProxyManipulationsSteps,
      final TestDriverConfigurationService testDriverConfigurationService) {
    this.deploymentConfigurationService = deploymentConfigurationService;
    this.tigerProxyManipulationsSteps = tigerProxyManipulationsSteps;
    this.testDriverConfigurationService = testDriverConfigurationService;
  }

  /**
   * Stores the initially observed PEP image reference once per scenario so rollout cleanup can
   * restore the deployment to its original image.
   *
   * @param imageReference current PEP image reference
   */
  static void rememberPepOriginalImageIfAbsent(final String imageReference) {
    if (imageReference == null || imageReference.isBlank() || CAPTURED_PEP_ORIGINAL_IMAGE.get() != null) {
      return;
    }
    CAPTURED_PEP_ORIGINAL_IMAGE.set(imageReference.trim());
  }

  /**
   * Returns the remembered original PEP image reference for the current scenario, if present.
   *
   * @return captured original PEP image
   */
  static Optional<String> getCapturedPepOriginalImage() {
    return Optional.ofNullable(CAPTURED_PEP_ORIGINAL_IMAGE.get())
        .map(String::trim)
        .filter(image -> !image.isBlank());
  }

  /**
   * Aborts the current scenario as skipped.
   *
   * @param reason skip reason visible in the report
   */
  private static void abortScenario(final String reason) {
    throw new TestAbortedException(reason);
  }

  /**
   * Clears the remembered original PEP image for the current scenario thread.
   */
  private static void clearCapturedPepOriginalImage() {
    CAPTURED_PEP_ORIGINAL_IMAGE.remove();
  }

  /**
   * Clears any soft assertions before each scenario to avoid leaking state across scenarios.
   */
  @Before(order = ORDER_PREPARE_SOFT_ASSERTIONS)
  public void prepareSoftAssertions() {
    SoftAssertionsContext.reset();
    clearCapturedPepOriginalImage();
  }

  /**
   * Resets TigerProxy state before each scenario to keep scenarios independent from one another.
   *
   * <p>If the standalone TigerProxy is not configured or not reachable, the cleanup is skipped so
   * scenarios tagged {@code @no_proxy} still run unchanged.</p>
   */
  @Before(order = ORDER_RESET_TIGER_PROXY_STATE)
  public void resetTigerProxyState() {
    tigerProxyManipulationsSteps.resetTigerProxyStateIfAvailable();
  }

  /**
   * Clears RBEL messages before each scenario so scenario assertions only see data created within the scenario itself.
   */
  @Before(order = ORDER_CLEAR_RECORDED_MESSAGES)
  public void clearRecordedMessages() {
    RbelMessageRetriever.getInstance().clearRbelMessages();
  }

  /**
   * Skip proxy-dependent scenarios unless the TigerProxy configuration is present. Scenarios tagged with {@link #NO_PROXY_TAG} are always
   * executed.
   */
  @Before(order = ORDER_PROXY_REQUIREMENT_GUARD)
  public void skipIfProxyMissing(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    if (scenario.getSourceTagNames().contains(NO_PROXY_TAG)) {
      return;
    }

    var proxyId = TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId")
        .orElse(null);
    boolean proxyConfigured = proxyId != null && !proxyId.isBlank();

    if (!proxyConfigured) {
      String reason = "Skipping: standalone Tiger proxy is not configured "
          + "and scenario is not tagged " + NO_PROXY_TAG;
      scenario.log(reason);
      log.warn("{} (scenario: '{}', tigerProxyId='{}', envProfile='{}', sysProfile='{}')",
          reason, scenario.getName(),
          proxyId == null ? "<missing>" : proxyId,
          System.getenv("PROFILE"),
          System.getProperty("PROFILE"));
      abortScenario(reason);
    } else {
      log.info("Scenario not skipped, proxy explicitly configured.");
    }
  }

  /**
   * Skip coverage-only scenarios that document justified test gaps so Allure and Serenity classify them consistently as skipped.
   */
  @Before(order = ORDER_JUSTIFIED_NOT_TESTED_GUARD)
  public void skipJustifiedNotTestedScenarios(final Scenario scenario) {
    if (scenario == null || !scenario.getSourceTagNames().contains(JUSTIFIED_NOT_TESTED_TAG)) {
      return;
    }

    String reason = "Skipping: scenario documents a justified, intentionally not executed test aspect "
        + "and is tagged " + JUSTIFIED_NOT_TESTED_TAG;
    scenario.log(reason);
    abortScenario(reason);
  }

  /**
   * Skip kubectl-dependent scenarios unless kubectl and cluster access are available.
   */
  @Before(order = ORDER_KUBECTL_REQUIREMENT_GUARD)
  public void skipIfKubectlMissing(final Scenario scenario) {
    if (scenario == null || !scenario.getSourceTagNames().contains(REQUIRE_KUBECTL_TAG)) {
      return;
    }

    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElse("");
    if (namespace.isBlank()) {
      String reason = "Skipping: kubectl requirement check is not possible because namespace is not defined "
          + "and scenario is tagged " + REQUIRE_KUBECTL_TAG;
      scenario.log(reason);
      abortScenario(reason);
    }

    try {
      deploymentConfigurationService.verifyRequirements(namespace);
      log.info("Scenario not skipped, kubectl requirement check passed.");
    } catch (Exception e) {
      String reason = "Skipping: kubectl requirement check failed and scenario is tagged " + REQUIRE_KUBECTL_TAG;
      scenario.log(reason);
      log.warn("{} (scenario: '{}', namespace='{}')", reason, scenario.getName(), namespace, e);
      abortScenario(reason);
    }
  }

  /**
   * Performs checks to verify that deployment modification is correctly set up if enabled at all.
   *
   * <p>Failure in verfication checks lead to skipping the scenario</p>
   *
   * @param scenario Scenario to be executed
   */
  @Before(order = ORDER_VERIFY_DEPLOYMENT_MODIFICATION)
  public void verifyDeploymentModification(final Scenario scenario) {
    // only run checks if scenario is properly tagged
    if (scenario == null || !scenario.getSourceTagNames().contains(DEPLOYMENT_MODIFICATION_TAG)) {
      log.debug("Deployment modification verify: tag '{}' was not found, ignore further checks",
          DEPLOYMENT_MODIFICATION_TAG);
      return;
    }

    // verify that deployment modifications are generally allowed in this run
    if (!TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification")
        .orElse(false)) {
      String reason = String.format("Skipping: deployment modification is not allowed; scenario is tagged with %s",
          DEPLOYMENT_MODIFICATION_TAG);
      scenario.log(reason);
      abortScenario(reason);
    }

    // check if requirements for deployment modifications are given
    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElse("");

    if (namespace.isBlank()) {
      String reason = "Skipping: namespace for deployment modification is not defined";
      scenario.log(reason);
      abortScenario(reason);
    }

    try {
      deploymentConfigurationService.verifyRequirements(namespace);
    } catch (Exception e) {
      String reason = "Skipping: verification check for deployment modification failed";
      scenario.log(reason);
      log.error("Unexpected error while verifying deployment modification requirements", e);
      abortScenario(reason);
    }
  }

  /**
   * Configures the testdriver for TLS validation scenarios after verifying that the TLS test tool
   * service is reachable.
   *
   * @param scenario active Cucumber scenario
   */
  @Before(order = ORDER_TLS_CLIENT_PRE_HOOK)
  public void patchTestdriverForTlsClientScenario(final Scenario scenario) {
    if (scenario == null || !scenario.getSourceTagNames().contains(TLS_CLIENT_FACHDIENST_HOOK_TAG)) {
      return;
    }

    try {
      TlsTestToolServiceFactory.getInstance().getState();
      log.info("TLS client pre hook: TLS test tool service availability check passed.");
    } catch (AssertionError e) {
      String reason = "Skipping: TLS test tool service is not reachable and scenario is tagged " + TLS_CLIENT_FACHDIENST_HOOK_TAG;
      scenario.log(reason);
      log.warn("{}", reason, e);
      abortScenario(reason);
    }

    String tlsTestToolUrl = TigerGlobalConfiguration.readStringOptional(TLS_TEST_TOOL_URL_CONFIG_KEY)
        .orElseThrow(() -> new AssertionError(
            "Failed pre hook: config key '" + TLS_TEST_TOOL_URL_CONFIG_KEY + "' could not be resolved."));

    String tlsTestToolPort = TigerGlobalConfiguration.readStringOptional(TLS_TEST_TOOL_PORT_CONFIG_KEY)
        .orElseThrow(() -> new AssertionError(
            "Failed pre hook: config key '" + TLS_TEST_TOOL_PORT_CONFIG_KEY + "' could not be resolved."));

    String tlsTestToolServerUrl = tlsTestToolUrl + ":" + tlsTestToolPort;

    try {
      testDriverConfigurationService.configure(tlsTestToolServerUrl, readTlsTestToolCaCertificatePem());
    } catch (AssertionError e) {
      throw new AssertionError("Failed pre hook: could not configure the testdriver for TLS client scenario.", e);
    }
    log.info("TLS client pre hook: configured testdriver resource '{}' for scenario '{}'.",
        tlsTestToolServerUrl, scenario.getName());
  }


  /**
   * Append the traceability table after each scenario has finished.
   *
   * @param scenario active Cucumber scenario
   */
  @After(order = ORDER_APPEND_TRACEABILITY)
  public void attachTraceability(final Scenario scenario) {
    if (scenario == null) {
      return;
    }
    TRACEABILITY.buildReport(scenario.getName(), scenario.getSourceTagNames())
        .ifPresent(markdown -> {
          log.debug("Adding traceability block to scenario '{}'", scenario.getName());
          Serenity.recordReportData()
              .withTitle("Traceability")
              .andContents(markdown);
        });
  }

  /**
   * Verifies all collected soft assertions at the very end of the scenario lifecycle. Runs after the traceability appendix so the report is
   * always populated even when soft assertions fail.
   */
  @After(order = ORDER_VERIFY_SOFT_ASSERTIONS)
  public void verifySoftAssertions() {
    SoftAssertionsContext.assertAll();
  }

  /**
   * Ensures modifications to the Zeta Guard deployment are restored to their original state after scenarios finish.
   *
   * @param scenario active Cucumber scenario
   */
  @After(order = ORDER_RESTORE_DEPLOYMENT_STATE)
  public void restoreDeploymentModifications(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    if (!scenario.getSourceTagNames().contains(DEPLOYMENT_MODIFICATION_TAG)) {
      log.debug("Restore deployment modification: skipping because {} tag was not found", DEPLOYMENT_MODIFICATION_TAG);
      return;
    }

    if (!TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification")
        .orElse(false)) {
      log.warn("Restore deployment modification: skipping because deployment modification is not allowed");
      return;
    }

    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElse("");

    if (namespace.isBlank()) {
      log.warn("Restore deployment modification: could not restore original Zeta deployment because"
          + " namespace is not configured");
      return;
    }

    String pepNginxConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.configMapName")
        .orElse("");
    boolean restoredAnyConfigMap = false;
    if (pepNginxConfigMapName.isBlank()) {
      log.warn("Restore deployment modification: could not restore original Zeta deployment because"
          + " zetaDeploymentConfig.pep.nginx.configMapName is not configured");
    } else if (!deploymentConfigurationService.hasConfigMapBackup(namespace, pepNginxConfigMapName)) {
      log.debug("Restore deployment modification: skipping nginx restore because no backup exists for {}",
          pepNginxConfigMapName);
    } else {
      try {
        deploymentConfigurationService.restoreConfigMapBackup(namespace, pepNginxConfigMapName);
        restoredAnyConfigMap = true;
      } catch (Exception e) {
        log.error("Restore deployment modification: could not restore original nginx config in Zeta deployment because "
            + "an unexpected error occurred", e);
      }
    }

    String pepWellKnownConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.configMapName")
        .orElse("");
    if (pepWellKnownConfigMapName.isBlank()) {
      log.warn("Restore deployment modification: could not restore Zeta deployment because"
          + " zetaDeploymentConfig.pep.wellKnown.configMapName is not configured");
    } else if (!deploymentConfigurationService.hasConfigMapBackup(namespace, pepWellKnownConfigMapName)) {
      log.debug("Restore deployment modification: skipping well-known restore because no backup exists for {}",
          pepWellKnownConfigMapName);
    } else {
      try {
        deploymentConfigurationService.restoreConfigMapBackup(namespace, pepWellKnownConfigMapName);
        restoredAnyConfigMap = true;
      } catch (Exception e) {
        log.error("Restore deployment modification: could not restore original well-known config because "
            + "an unexpected error occurred", e);
      }
    }

    boolean restoredPepDeploymentImageWithRollout = restorePepDeploymentImage(namespace);

    // since current modifications are only related to PEP HTTP proxy, it's ok to restart only once for both restores
    // TODO: requires refactoring once different / more modifications are implemented
    var podName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName").orElse("");

    if (restoredPepDeploymentImageWithRollout) {
      log.debug("Restore deployment modification: skipping explicit PEP pod restart because image restore already triggered a rollout");
    } else if (!restoredAnyConfigMap) {
      log.debug("Restore deployment modification: skipping PEP pod restart because no ConfigMap backup was restored");
    } else if (podName.isBlank()) {
      log.warn("Restore deployment modification: could not restart PEP pod because pod name is not set "
          + "(expected at: zetaDeploymentConfig.pep.podName)");
    } else {
      try {
        deploymentConfigurationService.restartPod(namespace, podName, true,
            deploymentConfigurationService.getPodReadyTimeoutSeconds());
      } catch (InterruptedException e) {
        log.warn("Restore deployment modification: could not restart PEP pod after restoring due to an Interrupted Exception.", e);
      } catch (TimeoutException e) {
        log.warn("Restore deployment modification: could not restart PEP pod after restoring due to a Timeout Exception.", e);
      }
    }

    String url = TigerGlobalConfiguration.readStringOptional("paths.client.reset").orElse(null);
    if (url == null) {
      log.warn("Restore deployment modification: could not issue client reset because "
          + "client URL not set (expected at: paths.client.reset)");
      return;
    }

    try {
      new HttpGlueCode().sendEmptyRequest(Method.GET, new URI(url));
    } catch (Exception e) {
      log.error("Restore deployment modification: could not issue client reset because "
          + "an unexpected error occurred while sending request", e);
    }
  }

  boolean restorePepDeploymentImage(final String namespace) {
    String deploymentName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElse("");
    String containerName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.containerName")
        .orElse("");
    String expectedTag = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.image.versionUpdate")
        .orElse("");

    if (deploymentName.isBlank() || containerName.isBlank() || expectedTag.isBlank()) {
      log.warn(
          "Restore deployment modification: could not restore PEP image because deployment, container, or target tag is not configured");
      return false;
    }

    String expectedImage = getCapturedPepOriginalImage().orElse(null);
    if (expectedImage == null) {
      var imagePathResult =
          deploymentConfigurationService.getContainerImagePathForDeployment(namespace, deploymentName, containerName);
      if (imagePathResult.exitCode() != 0 || imagePathResult.stdout() == null || imagePathResult.stdout().isBlank()) {
        log.warn("Restore deployment modification: could not resolve PEP image path for deployment '{}' and container '{}': {}",
            deploymentName, containerName, imagePathResult.stderr());
        return false;
      }
      expectedImage = imagePathResult.stdout().trim() + ":" + expectedTag;
      log.debug("Restore deployment modification: falling back to configured image '{}' for deployment '{}'",
          expectedImage, deploymentName);
    } else {
      log.debug("Restore deployment modification: using captured original image '{}' for deployment '{}'",
          expectedImage, deploymentName);
    }

    boolean imageRestoreTriggeredRollout = false;
    var currentImageResult =
        deploymentConfigurationService.getContainerImageReferenceForDeployment(namespace, deploymentName, containerName);
    if (currentImageResult.exitCode() == 0 && currentImageResult.stdout() != null && !currentImageResult.stdout().isBlank()) {
      imageRestoreTriggeredRollout = !expectedImage.equals(currentImageResult.stdout().trim());
    } else {
      log.debug("Restore deployment modification: could not determine current PEP image before restore for deployment '{}': {}",
          deploymentName, currentImageResult.stderr());
    }

    var cleanupResult =
        deploymentConfigurationService.cleanupFailedRolloutPods(namespace, deploymentName, containerName, expectedImage);
    if (cleanupResult.exitCode() != 0) {
      log.warn("Restore deployment modification: cleanup for deployment '{}' failed: {}",
          deploymentName, cleanupResult.stderr());
      return false;
    }

    var verifyResult =
        deploymentConfigurationService.verifyDeploymentUpdate(namespace, deploymentName, containerName, expectedImage);
    if (verifyResult.exitCode() != 0) {
      log.warn("Restore deployment modification: deployment '{}' did not settle on expected image '{}': {}",
          deploymentName, expectedImage, verifyResult.stderr());
      return false;
    }

    log.info("Restore deployment modification: ensured deployment '{}' runs with image '{}'",
        deploymentName, expectedImage);
    return imageRestoreTriggeredRollout;
  }

  /**
   * Restores the testdriver state for TLS client validation scenarios.
   *
   * @param scenario active Cucumber scenario
   */
  @After(order = ORDER_TLS_CLIENT_POST_HOOK)
  public void rollbackTestdriverAfterTlsClientScenario(final Scenario scenario) {
    if (scenario == null || !scenario.getSourceTagNames().contains(TLS_CLIENT_FACHDIENST_HOOK_TAG)) {
      return;
    }

    try {
      testDriverConfigurationService.reset();
    } catch (AssertionError e) {
      throw new AssertionError("Failed post hook: could not reset the testdriver after TLS client scenario.", e);
    }
    log.info("TLS client post hook: reset testdriver after scenario '{}'.", scenario.getName());
  }

  /**
   * Reads the PEM-encoded TLS test tool CA certificate from the checked-out fixture.
   *
   * @return PEM certificate content
   */
  private String readTlsTestToolCaCertificatePem() {
    var configuredPath = TigerGlobalConfiguration.readStringOptional(TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY)
        .orElseThrow(() -> new AssertionError(
            "Failed pre hook: config key '" + TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY + "' could not be resolved."));

    var caCertificatePath = Path.of(System.getProperty("user.dir"), configuredPath);
    try {
      var pem = Files.readString(caCertificatePath, StandardCharsets.UTF_8);
      if (pem.isBlank()) {
        throw new AssertionError("The TLS test tool CA certificate is empty.");
      }
      return pem;
    } catch (IOException e) {
      throw new AssertionError("Failed to read the TLS test tool CA certificate from '" + caCertificatePath + "'.", e);
    }
  }
}
