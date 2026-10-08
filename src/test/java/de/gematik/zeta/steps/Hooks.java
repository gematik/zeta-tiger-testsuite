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

import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.reporting.DeploymentVersionCapture;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.TestDriverConfigurationServiceFactory;
import de.gematik.zeta.services.TlsTestToolServiceFactory;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.traceability.TraceabilityLookup;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.Scenario;
import io.restassured.http.Method;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;
import org.opentest4j.TestAbortedException;

/**
 * Injects traceability information into every Serenity scenario report.
 */
@Slf4j
public class Hooks {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TraceabilityLookup TRACEABILITY = TraceabilityLookup.load();
  private static final String NO_PROXY_TAG = "@no_proxy";
  private static final String REQUIRE_KUBECTL_TAG = "@require_kubectl";
  private static final String REQUIRE_SIGNING_KEY_TAG = "@require_signing_key";
  private static final String PERFORMANCE_TAG = "@performance";
  private static final String LONGRUNNING_TAG = "@longrunning";
  private static final String TPM_ENVIRONMENT_TAG = "@tpm_environment";
  private static final String REQUIRE_OIDC_TESTDRIVER_TAG = "@require_oidc_testdriver";
  private static final String REQUIRE_OIDC_DECRYPTION_KEY_TAG = "@require_oidc_decryption_key";
  private static final String DEPLOYMENT_MODIFICATION_TAG = "@deployment_modification";
  private static final String TLS_CLIENT_FACHDIENST_HOOK_TAG = "@tls_client_fachdienst_hook";
  private static final String TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG = "@tls_native_client_fachdienst_hook";
  private static final String RESET_TLS_REVOCATION_CACHE_TAG = "@reset_tls_revocation_cache";
  private static final String OCSP_MOCK_MODE_MODIFIED_TAG = "@ocsp_mock_mode_modified";
  private static final String RESET_NOTIFICATION_PUSHERS_TAG = "@reset_notification_pushers";
  private static final String TLS_SUBCA_ROOT_TAG = "@tls_subca_root";
  private static final String TLS_TEST_TOOL_URL_CONFIG_KEY = "tlsTestTool.url";
  private static final String TLS_TEST_TOOL_PORT_CONFIG_KEY = "tlsTestTool.port";
  private static final String TLS_TEST_TOOL_CLIENT_DISABLE_TLS_VERIFICATION_CONFIG_KEY = "tlsTestTool.clientDisableTlsVerification";
  private static final String TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY = "tlsTestTool.caCertificatePath";
  private static final String OCSP_MOCK_URL_CONFIG_KEY = "zeta_cert_validation_mock_url";
  private static final Duration TLS_DRIVER_READINESS_RETRY_INTERVAL = Duration.ofSeconds(1);
  private static final int DEFAULT_TLS_DRIVER_READINESS_TIMEOUT_SECONDS = 60;
  private static final Path TLS_SUBCA_ROOT_CERTIFICATE = Path.of(
      "src", "test", "resources", "tls-test-tool", "certificates", "ecdsa",
      "zeta-tls-test-tool-server_subca_root.cer");
  private static final String ALLOW_PERFORMANCE_TESTS_CONFIG_KEY = "allow_performance_tests";
  private static final String ALLOW_LONGRUNNING_TESTS_CONFIG_KEY = "allow_longrunning_tests";
  private static final String TPM_ENVIRONMENT_CONFIG_KEY = "tpm_environment";
  private static final String TESTDRIVER_DEPLOYMENT_NAME = "testdriver";
  private static final String TESTDRIVER_AUTH_MODE_ENVIRONMENT_VARIABLE = "AUTH_MODE";
  private static final String REQUIRED_OIDC_AUTH_MODE = "OIDC";
  private static final ThreadLocal<String> CAPTURED_PEP_ORIGINAL_IMAGE = new ThreadLocal<>();
  private static final ThreadLocal<Map<String, Integer>> CAPTURED_DEPLOYMENT_REPLICAS =
      ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<Map<String, String>> CAPTURED_DEPLOYMENT_STRATEGIES =
      ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<Set<String>> CAPTURED_CONFIG_MAP_BACKUPS =
      ThreadLocal.withInitial(HashSet::new);
  private static final ThreadLocal<Boolean> SCENARIO_ABORTED = ThreadLocal.withInitial(() -> false);
  private static final int ORDER_CLEAR_SCENARIO_LIFECYCLE_STATE = Integer.MIN_VALUE;
  private static final int ORDER_RESTORE_DEPLOYMENT_STATE = ORDER_CLEAR_SCENARIO_LIFECYCLE_STATE + 1;
  private static final int ORDER_PREPARE_SOFT_ASSERTIONS = ORDER_RESTORE_DEPLOYMENT_STATE + 1;
  private static final int ORDER_RESET_TIGER_PROXY_STATE = ORDER_PREPARE_SOFT_ASSERTIONS + 1;
  private static final int ORDER_CLEAR_RECORDED_MESSAGES = ORDER_RESET_TIGER_PROXY_STATE + 1;
  private static final int ORDER_PROXY_REQUIREMENT_GUARD = ORDER_CLEAR_RECORDED_MESSAGES + 1;
  private static final int ORDER_KUBECTL_REQUIREMENT_GUARD = ORDER_PROXY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_SIGNING_KEY_REQUIREMENT_GUARD = ORDER_KUBECTL_REQUIREMENT_GUARD + 1;
  private static final int ORDER_PERFORMANCE_REQUIREMENT_GUARD = ORDER_SIGNING_KEY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_TPM_ENVIRONMENT_REQUIREMENT_GUARD = ORDER_PERFORMANCE_REQUIREMENT_GUARD + 1;
  private static final int ORDER_OIDC_TESTDRIVER_REQUIREMENT_GUARD = ORDER_TPM_ENVIRONMENT_REQUIREMENT_GUARD + 1;
  private static final int ORDER_OIDC_DECRYPTION_KEY_REQUIREMENT_GUARD =
      ORDER_OIDC_TESTDRIVER_REQUIREMENT_GUARD + 1;
  private static final int ORDER_VERIFY_DEPLOYMENT_MODIFICATION =
      ORDER_OIDC_DECRYPTION_KEY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_RESET_TLS_REVOCATION_CACHE = ORDER_VERIFY_DEPLOYMENT_MODIFICATION + 1;
  private static final int ORDER_TLS_CLIENT_PRE_HOOK = ORDER_RESET_TLS_REVOCATION_CACHE + 1;
  private static final int ORDER_APPEND_TRACEABILITY = Integer.MAX_VALUE;
  private static final int ORDER_VERIFY_SOFT_ASSERTIONS = ORDER_APPEND_TRACEABILITY - 1;

  private static ZetaDeploymentModificationService deploymentModificationService = ZetaDeploymentConfiguration.getServiceInstance();
  private static final int ORDER_TLS_CLIENT_POST_HOOK = ORDER_VERIFY_SOFT_ASSERTIONS - 1;
  private static final int ORDER_RESTORE_OCSP_MOCK_MODE = ORDER_TLS_CLIENT_POST_HOOK - 1;
  private static final int ORDER_RESET_NOTIFICATION_PUSHERS = ORDER_RESTORE_OCSP_MOCK_MODE - 1;
  private static final String PUSHER_ENDPOINT_CONFIG_KEY = "paths.client.notifications.pushers";
  private final TigerProxyManipulationsSteps tigerProxyManipulationsSteps;
  private final TestDriverConfigurationService testDriverConfigurationService;
  private final SigningKeyPreflight signingKeyPreflight;
  private final OidcDecryptionKeyPreflight oidcDecryptionKeyPreflight;

  /**
   * Creates hooks backed by the default deployment configuration service instance.
   */
  @SuppressWarnings("unused")
  public Hooks() {
    this(ZetaDeploymentConfiguration.getServiceInstance(), new TigerProxyManipulationsSteps(),
        TestDriverConfigurationServiceFactory.getInstance());
  }

  /**
   * Creates hooks backed by the provided services.
   *
   * @param injectedDeploymentModificationService service used for deployment-related checks and restoration
   * @param tigerProxyManipulationsSteps helper used for TigerProxy cleanup before scenarios
   * @param testDriverConfigurationService service used for testdriver reset/configure operations
   */
  public Hooks(final ZetaDeploymentModificationService injectedDeploymentModificationService,
               final TigerProxyManipulationsSteps tigerProxyManipulationsSteps,
               final TestDriverConfigurationService testDriverConfigurationService) {
    deploymentModificationService = injectedDeploymentModificationService;
    this.tigerProxyManipulationsSteps = tigerProxyManipulationsSteps;
    this.testDriverConfigurationService = testDriverConfigurationService;
    var jwtSteps = new JwtSteps();
    this.signingKeyPreflight = new SigningKeyPreflight(
        tigerProxyManipulationsSteps,
        new TigerProxyAccessTokenExtractor(),
        jwtSteps,
        new KeycloakSigningKeyResolver(injectedDeploymentModificationService, jwtSteps));
    this.oidcDecryptionKeyPreflight = new OidcDecryptionKeyPreflight(
        new KeycloakOidcDecryptionKeyResolver(injectedDeploymentModificationService));
  }

  /**
   * Global hook to capture tested versions and apply active ZETA Guard modifications before any scenario is run.
   *
   * @throws AssertionError if the modification was not successful
   */
  @BeforeAll
  @SuppressWarnings("unused")
  public static void applyGlobalModifications() throws AssertionError {
    DeploymentVersionCapture.capture(deploymentModificationService);
    verifyGlobalConfiguration();

    CommandResult result = null;
    if (ZetaDeploymentConfiguration.isAslEnabled()) {
      var request = ZetaDeploymentConfiguration.getEnableAslRequest();
      result = deploymentModificationService.enableAsl(request);
    }

    if (ZetaDeploymentConfiguration.isAslDisabled()) {
      var request = ZetaDeploymentConfiguration.getDisableAslRequest();
      result = deploymentModificationService.disableAsl(request);
    }

    if (result != null && result.exitCode() != 0) {
      throw new AssertionError("Applying global modifications failed: " + formatCommandResult(result));
    }
  }

  /**
   * Global hook to restore ZETA Guard deployment to its original state after all scenarios were run.
   *
   * @throws AssertionError if the restore was not successful
   */
  @AfterAll
  @SuppressWarnings("unused")
  public static void restoreGlobalModifications() throws AssertionError {
    verifyGlobalConfiguration();

    // only roll back if global modifications were requested
    if (ZetaDeploymentConfiguration.isAslEnabled() || ZetaDeploymentConfiguration.isAslDisabled()) {
      var request = ZetaDeploymentConfiguration.getRestoreRequest();
      var result = deploymentModificationService.restoreOriginalZetaDeployment(request);

      if (result != null && result.exitCode() != 0) {
        throw new AssertionError("Restoring global modifications failed: " + formatCommandResult(result));
      }
    }
  }

  /**
   * Stores the initially observed PEP image reference once per scenario so rollout cleanup can restore the deployment to its original
   * image.
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
   * Stores the initially observed replica count of a deployment once per scenario so cleanup can
   * restore it after scaling tests.
   *
   * @param deploymentName deployment name
   * @param replicas initially observed replica count
   */
  static void rememberDeploymentReplicaCountIfAbsent(final String deploymentName, final int replicas) {
    if (deploymentName == null || deploymentName.isBlank()) {
      return;
    }
    CAPTURED_DEPLOYMENT_REPLICAS.get().putIfAbsent(deploymentName.trim(), replicas);
  }

  /**
   * Stores the initially observed deployment strategy once per scenario so cleanup can restore it.
   *
   * @param deploymentName deployment name
   * @param strategyJson initially observed strategy JSON
   */
  static void rememberDeploymentStrategyIfAbsent(final String deploymentName, final String strategyJson) {
    if (deploymentName == null || deploymentName.isBlank() || strategyJson == null || strategyJson.isBlank()) {
      return;
    }
    CAPTURED_DEPLOYMENT_STRATEGIES.get().putIfAbsent(deploymentName.trim(), strategyJson.trim());
  }

  /**
   * Stores a ConfigMap backup name once per scenario so cleanup only restores state captured for that scenario.
   *
   * @param configMapName ConfigMap name
   * @return {@code true} when the ConfigMap was newly registered for the scenario
   */
  static boolean rememberConfigMapBackupIfAbsent(final String configMapName) {
    if (configMapName == null || configMapName.isBlank()) {
      return false;
    }
    return CAPTURED_CONFIG_MAP_BACKUPS.get().add(configMapName.trim());
  }

  /**
   * Returns ConfigMap backups captured for the current scenario.
   *
   * @return captured ConfigMap backup names
   */
  static Set<String> getCapturedConfigMapBackups() {
    return Set.copyOf(CAPTURED_CONFIG_MAP_BACKUPS.get());
  }

  /**
   * Aborts the current scenario as skipped.
   *
   * @param reason skip reason visible in the report
   */
  private static void abortScenario(final String reason) {
    SCENARIO_ABORTED.set(true);
    throw new TestAbortedException(reason);
  }

  /**
   * Clears the remembered original PEP image for the current scenario thread.
   */
  private static void clearCapturedPepOriginalImage() {
    CAPTURED_PEP_ORIGINAL_IMAGE.remove();
  }

  /**
   * Clears remembered deployment replica counts for the current scenario thread.
   */
  private static void clearCapturedDeploymentReplicaCounts() {
    CAPTURED_DEPLOYMENT_REPLICAS.remove();
  }

  /**
   * Clears remembered deployment strategies for the current scenario thread.
   */
  private static void clearCapturedDeploymentStrategies() {
    CAPTURED_DEPLOYMENT_STRATEGIES.remove();
  }

  /**
   * Clears remembered ConfigMap backups for the current scenario thread.
   */
  private static void clearCapturedConfigMapBackups() {
    CAPTURED_CONFIG_MAP_BACKUPS.remove();
  }

  /**
   * Resets the aborted marker for the current scenario thread.
   */
  public static void clearScenarioAborted() {
    SCENARIO_ABORTED.set(false);
  }

  /**
   * Indicates whether the current scenario was aborted from a before hook.
   *
   * @return {@code true} if the scenario was already aborted
   */
  private static boolean isScenarioAborted() {
    return SCENARIO_ABORTED.get();
  }

  /**
   * Registers the current scenario before any step or hook can emit report attachments.
   *
   * @param scenario active Cucumber scenario
   */
  @Before(order = ORDER_CLEAR_SCENARIO_LIFECYCLE_STATE)
  public void registerScenarioForReportAttachments(final Scenario scenario) {
    ReportAttachments.setCurrentScenario(scenario);
  }

  /**
   * Clears any soft assertions before each scenario to avoid leaking state across scenarios.
   */
  @Before(order = ORDER_PREPARE_SOFT_ASSERTIONS)
  public void prepareSoftAssertions() {
    SoftAssertionsContext.reset();
    OidcDecryptionKeyPreflight.clear();
    clearCapturedPepOriginalImage();
    clearCapturedDeploymentReplicaCounts();
    clearCapturedDeploymentStrategies();
    clearCapturedConfigMapBackups();
    clearScenarioAborted();
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
      deploymentModificationService.verifyRequirements(namespace);
      log.info("Scenario not skipped, kubectl requirement check passed.");
    } catch (AssertionError | RuntimeException e) {
      String reason = "Skipping: kubectl requirement check failed and scenario is tagged " + REQUIRE_KUBECTL_TAG;
      scenario.log(reason);
      log.warn("{} (scenario: '{}', namespace='{}')", reason, scenario.getName(), namespace, e);
      abortScenario(reason);
    }
  }

  /**
   * Verifies the signing key required by a scenario and skips only when local and Keycloak fallback
   * resolution both fail.
   *
   * <p>The local check deliberately uses the existing testclient request and JWT verification glue.
   * The fallback uses the existing Java kubectl service to resolve the active Keycloak key and
   * stores it at the configured path for the scenario's TigerProxy manipulation.</p>
   *
   * @param scenario active Cucumber scenario
   */
  @Before(value = REQUIRE_SIGNING_KEY_TAG, order = ORDER_SIGNING_KEY_REQUIREMENT_GUARD)
  public void skipIfSigningKeyUnavailable(final Scenario scenario) {
    String reason = null;
    try {
      signingKeyPreflight.ensureAvailable();
    } catch (AssertionError | RuntimeException e) {
      reason = "Skipping: configured signing key is invalid or the Keycloak signing-key fallback is unavailable";
      log.warn("{} (scenario: '{}')", reason, scenario.getName(), e);
    } finally {
      tigerProxyManipulationsSteps.resetTigerProxyStateIfAvailable();
      RbelMessageRetriever.getInstance().clearRbelMessages();
    }

    if (reason != null) {
      scenario.log(reason + " and scenario is tagged " + REQUIRE_SIGNING_KEY_TAG);
      abortScenario(reason + " and scenario is tagged " + REQUIRE_SIGNING_KEY_TAG);
    }
    log.info("Scenario not skipped, signing key requirement check passed.");
  }

  /**
   * Skip performance and long-running scenarios unless the corresponding execution flags are enabled explicitly.
   */
  @Before(order = ORDER_PERFORMANCE_REQUIREMENT_GUARD)
  public void skipPerformanceAndLongRunningScenarios(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    var tags = scenario.getSourceTagNames();

    if (tags.contains(PERFORMANCE_TAG)
        && !TigerGlobalConfiguration.readBooleanOptional(ALLOW_PERFORMANCE_TESTS_CONFIG_KEY).orElse(false)) {
      String reason = "Skipping: performance scenarios require " + ALLOW_PERFORMANCE_TESTS_CONFIG_KEY
          + "=true and scenario is tagged " + PERFORMANCE_TAG;
      scenario.log(reason);
      abortScenario(reason);
    }

    if (tags.contains(LONGRUNNING_TAG)
        && !TigerGlobalConfiguration.readBooleanOptional(ALLOW_LONGRUNNING_TESTS_CONFIG_KEY).orElse(false)) {
      String reason = "Skipping: long-running scenarios require " + ALLOW_LONGRUNNING_TESTS_CONFIG_KEY
          + "=true and scenario is tagged " + LONGRUNNING_TAG;
      scenario.log(reason);
      abortScenario(reason);
    }
  }

  /**
   * Skip TPM-dependent scenarios unless the current test object explicitly provides a TPM environment.
   *
   * @param scenario active Cucumber scenario
   */
  @Before(order = ORDER_TPM_ENVIRONMENT_REQUIREMENT_GUARD)
  public void skipIfTpmEnvironmentMissing(final Scenario scenario) {
    if (scenario == null || !scenario.getSourceTagNames().contains(TPM_ENVIRONMENT_TAG)) {
      return;
    }

    if (!TigerGlobalConfiguration.readBooleanOptional(TPM_ENVIRONMENT_CONFIG_KEY).orElse(false)) {
      String reason = "Skipping: TPM scenarios require " + TPM_ENVIRONMENT_CONFIG_KEY
          + "=true and scenario is tagged " + TPM_ENVIRONMENT_TAG;
      scenario.log(reason);
      abortScenario(reason);
    }
  }

  /**
   * Skips OIDC scenarios when the deployed testdriver is not configured for OIDC.
   *
   * <p>The Helm value documented by the OIDC MR is {@code testdriver.authMode=OIDC}; the
   * deployed testdriver exposes that value as the {@code AUTH_MODE} environment variable.
   * Reading the deployment template makes this guard validate the actual deployment rather
   * than a local test-suite assumption.</p>
   *
   * @param scenario active Cucumber scenario
   */
  @Before(value = REQUIRE_OIDC_TESTDRIVER_TAG, order = ORDER_OIDC_TESTDRIVER_REQUIREMENT_GUARD)
  public void skipIfTestdriverIsNotConfiguredForOidc(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElse("");
    if (namespace.isBlank()) {
      String reason = "Skipping: OIDC testdriver guard requires a configured Kubernetes namespace";
      scenario.log(reason);
      abortScenario(reason);
    }

    CommandResult result;
    try {
      result = deploymentModificationService.executeKubectlCommand(
          false,
          "-n", namespace,
          "get", "deployment", TESTDRIVER_DEPLOYMENT_NAME,
          "-o", "json");
    } catch (AssertionError | RuntimeException e) {
      String reason = "Skipping: OIDC testdriver guard could not read the deployed testdriver configuration";
      scenario.log(reason);
      log.warn("{} (namespace='{}', deployment='{}')", reason, namespace, TESTDRIVER_DEPLOYMENT_NAME, e);
      abortScenario(reason);
      return;
    }

    String deploymentJson = result.stdout() == null ? "" : result.stdout().trim();
    if (result.exitCode() != 0 || deploymentJson.isBlank()) {
      String reason = "Skipping: OIDC testdriver guard could not read AUTH_MODE from deployment '"
          + TESTDRIVER_DEPLOYMENT_NAME + "'";
      scenario.log(reason);
      log.warn("{} (namespace='{}', exitCode={}, stderr='{}')", reason, namespace, result.exitCode(), result.stderr());
      abortScenario(reason);
      return;
    }

    String actualAuthMode;
    try {
      actualAuthMode = testdriverEnvironmentValue(
          deploymentJson, TESTDRIVER_AUTH_MODE_ENVIRONMENT_VARIABLE);
    } catch (AssertionError | RuntimeException e) {
      String reason = "Skipping: OIDC testdriver guard could not parse deployment '"
          + TESTDRIVER_DEPLOYMENT_NAME + "'";
      scenario.log(reason);
      log.warn("{} (namespace='{}')", reason, namespace, e);
      abortScenario(reason);
      return;
    }

    if (actualAuthMode.isBlank()) {
      String reason = "Skipping: OIDC testdriver guard could not read AUTH_MODE from deployment '"
          + TESTDRIVER_DEPLOYMENT_NAME + "'";
      scenario.log(reason);
      log.warn("{} (namespace='{}')", reason, namespace);
      abortScenario(reason);
      return;
    }

    if (!REQUIRED_OIDC_AUTH_MODE.equalsIgnoreCase(actualAuthMode)) {
      String reason = "Skipping: deployed testdriver AUTH_MODE is '" + actualAuthMode
          + "' but OIDC scenarios require '" + REQUIRED_OIDC_AUTH_MODE + "'";
      scenario.log(reason);
      log.warn("{} (namespace='{}', deployment='{}')", reason, namespace, TESTDRIVER_DEPLOYMENT_NAME);
      abortScenario(reason);
    }

    log.info("OIDC testdriver guard passed: deployment '{}' uses AUTH_MODE={}.",
        TESTDRIVER_DEPLOYMENT_NAME, actualAuthMode);
  }

  /**
   * Loads the PDP Authorization Server's OIDC decryption key into RBEL before protocol traffic starts.
   *
   * @param scenario active Cucumber scenario
   */
  @Before(value = REQUIRE_OIDC_DECRYPTION_KEY_TAG,
      order = ORDER_OIDC_DECRYPTION_KEY_REQUIREMENT_GUARD)
  public void skipIfOidcDecryptionKeyUnavailable(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    try {
      oidcDecryptionKeyPreflight.ensureAvailable();
    } catch (AssertionError | RuntimeException e) {
      String reason = "Skipping: active Keycloak OIDC decryption key is unavailable";
      scenario.log(reason + " and scenario is tagged " + REQUIRE_OIDC_DECRYPTION_KEY_TAG);
      log.warn("{} (scenario: '{}')", reason, scenario.getName(), e);
      abortScenario(reason + " and scenario is tagged " + REQUIRE_OIDC_DECRYPTION_KEY_TAG);
    }
  }

  /**
   * Reads one environment variable from the testdriver container in a Kubernetes deployment.
   *
   * @param deploymentJson Kubernetes deployment JSON
   * @param environmentVariableName environment variable to read from the testdriver container
   * @return environment variable value, or an empty string when the container or variable is absent
   */
  private String testdriverEnvironmentValue(
      final String deploymentJson, final String environmentVariableName) {
    try {
      var containers = JSON.readTree(deploymentJson)
          .path("spec")
          .path("template")
          .path("spec")
          .path("containers");
      for (var container : containers) {
        if (!TESTDRIVER_DEPLOYMENT_NAME.equals(container.path("name").asText())) {
          continue;
        }
        for (var environmentVariable : container.path("env")) {
          if (environmentVariableName.equals(environmentVariable.path("name").asText())) {
            return environmentVariable.path("value").asText("").trim();
          }
        }
      }
      return "";
    } catch (IOException e) {
      throw new AssertionError("Could not parse the deployed testdriver configuration.", e);
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
      deploymentModificationService.verifyRequirements(namespace);
    } catch (AssertionError | RuntimeException e) {
      String reason = "Skipping: verification check for deployment modification failed";
      scenario.log(reason);
      log.error("Unexpected error while verifying deployment modification requirements", e);
      abortScenario(reason);
    }
  }

  /**
   * Configures the testdriver for TLS validation scenarios after verifying that the TLS test tool service is reachable.
   *
   * @param scenario active Cucumber scenario
   */
  @Before(order = ORDER_TLS_CLIENT_PRE_HOOK)
  public void patchTestdriverForTlsClientScenario(final Scenario scenario) {
    if (scenario == null || isNotTlsClientScenario(scenario)) {
      return;
    }

    try {
      TlsTestToolServiceFactory.getInstance().getState();
      log.info("TLS client pre hook: TLS test tool service availability check passed.");
    } catch (AssertionError e) {
      String reason = "Skipping: TLS test tool service is not reachable and scenario is tagged " + getTlsClientHookTag(scenario);
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

    var clientDisableTlsVerification = TigerGlobalConfiguration.readBooleanOptional(
            TLS_TEST_TOOL_CLIENT_DISABLE_TLS_VERIFICATION_CONFIG_KEY)
        .orElse(false);

    try {
      getTlsTestDriverConfigurationService(scenario)
          .configureWhenReady(
              tlsTestToolServerUrl,
              readTlsTestToolCaCertificatePem(scenario),
              clientDisableTlsVerification,
              tlsDriverReadinessTimeout(),
              TLS_DRIVER_READINESS_RETRY_INTERVAL);
    } catch (AssertionError e) {
      throw new AssertionError("Failed pre hook: could not configure the testdriver for TLS client scenario.", e);
    }
    log.info("TLS client pre hook: configured testdriver resource '{}' for scenario '{}'.",
        tlsTestToolServerUrl, scenario.getName());
  }

  /**
   * Restarts the selected TLS client driver before cache-sensitive revocation scenarios so each scenario starts without revocation state
   * retained by an earlier scenario or test run.
   *
   * @param scenario active Cucumber scenario
   */
  @Before(value = RESET_TLS_REVOCATION_CACHE_TAG, order = ORDER_RESET_TLS_REVOCATION_CACHE)
  public void resetTlsRevocationCache(final Scenario scenario) {
    if (scenario == null || isNotTlsClientScenario(scenario)) {
      return;
    }

    if (!TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification")
        .orElse(false)) {
      var reason = "Skipping: TLS revocation cache reset requires deployment modification permission";
      scenario.log(reason);
      abortScenario(reason);
    }

    var namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("Missing configuration zetaDeploymentConfig.namespace."));
    var readinessTimeout = TigerGlobalConfiguration.readIntegerOptional("zetaDeploymentConfig.podReadyTimeout")
        .orElseThrow(() -> new AssertionError("Missing configuration zetaDeploymentConfig.podReadyTimeout."));
    var deploymentName = scenario.getSourceTagNames().contains(TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG)
        ? "nativedriver"
        : "testdriver";

    try {
      deploymentModificationService.restartPod(namespace, deploymentName, true, readinessTimeout);
    } catch (TimeoutException e) {
      throw new AssertionError("TLS revocation cache reset timed out for deployment '" + deploymentName + "'.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("TLS revocation cache reset was interrupted for deployment '" + deploymentName + "'.", e);
    }
    log.info("TLS revocation cache reset: restarted deployment '{}' before scenario '{}'.",
        deploymentName, scenario.getName());
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
    if (isScenarioAborted()) {
      return;
    }
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
    Set<String> capturedConfigMapBackups = getCapturedConfigMapBackups();
    if (pepNginxConfigMapName.isBlank()) {
      log.warn("Restore deployment modification: could not restore original Zeta deployment because"
          + " zetaDeploymentConfig.pep.nginx.configMapName is not configured");
    } else if (!capturedConfigMapBackups.contains(pepNginxConfigMapName)) {
      log.debug("Restore deployment modification: skipping nginx restore because no backup was captured for {} in this scenario",
          pepNginxConfigMapName);
    } else if (!deploymentModificationService.hasConfigMapBackup(namespace, pepNginxConfigMapName)) {
      log.debug("Restore deployment modification: skipping nginx restore because no backup exists for {}",
          pepNginxConfigMapName);
    } else {
      try {
        var restoreResult = deploymentModificationService.restoreConfigMapBackup(namespace, pepNginxConfigMapName);
        if (restoreResult.exitCode() == 0) {
          deploymentModificationService.deleteConfigMapBackup(namespace, pepNginxConfigMapName);
          restoredAnyConfigMap = true;
        } else {
          log.warn("Restore deployment modification: could not restore nginx ConfigMap backup for '{}': {}",
              pepNginxConfigMapName, restoreResult.stderr());
        }
      } catch (Exception e) {
        log.error("Restore deployment modification: could not restore original nginx config in Zeta deployment because "
            + "an unexpected error occurred", e);
      }
    }

    for (Map.Entry<String, Integer> entry : CAPTURED_DEPLOYMENT_REPLICAS.get().entrySet()) {
      try {
        var scaleResult =
            deploymentModificationService.scaleDeployment(namespace, entry.getKey(), entry.getValue());
        if (scaleResult.exitCode() != 0) {
          log.warn("Restore deployment modification: could not restore replica count for deployment '{}' to {}: {}",
              entry.getKey(), entry.getValue(), scaleResult.stderr());
        } else {
          log.info("Restore deployment modification: restored deployment '{}' to {} replicas",
              entry.getKey(), entry.getValue());
        }
      } catch (Exception e) {
        log.warn("Restore deployment modification: could not restore replica count for deployment '{}' to {}",
            entry.getKey(), entry.getValue(), e);
      }
    }

    for (Map.Entry<String, String> entry : CAPTURED_DEPLOYMENT_STRATEGIES.get().entrySet()) {
      try {
        var strategyResult =
            deploymentModificationService.setDeploymentStrategy(namespace, entry.getKey(), entry.getValue());
        if (strategyResult.exitCode() != 0) {
          log.warn("Restore deployment modification: could not restore strategy for deployment '{}': {}",
              entry.getKey(), strategyResult.stderr());
        } else {
          log.info("Restore deployment modification: restored strategy for deployment '{}'",
              entry.getKey());
        }
      } catch (Exception e) {
        log.warn("Restore deployment modification: could not restore strategy for deployment '{}'",
            entry.getKey(), e);
      }
    }

    boolean restoredPepDeploymentImageWithRollout = false;
    if (getCapturedPepOriginalImage().isPresent()) {
      restoredPepDeploymentImageWithRollout = restorePepDeploymentImage(namespace);
    } else {
      log.debug("Restore deployment modification: skipping PEP image restore because this scenario did not change the image");
    }

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
        deploymentModificationService.restartPod(namespace, podName, true,
            deploymentModificationService.getPodReadyTimeoutSeconds());
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

  /**
   * Restores the PEP deployment image to the configured update tag and verifies the rollout result.
   *
   * @param namespace Kubernetes namespace containing the PEP deployment
   * @return {@code true} when cleanup triggered a rollout that settled on the expected image, otherwise {@code false}
   */
  public boolean restorePepDeploymentImage(final String namespace) {
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
          deploymentModificationService.getContainerImagePathForDeployment(namespace, deploymentName, containerName);
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
        deploymentModificationService.getContainerImageReferenceForDeployment(namespace, deploymentName, containerName);
    if (currentImageResult.exitCode() == 0 && currentImageResult.stdout() != null && !currentImageResult.stdout().isBlank()) {
      imageRestoreTriggeredRollout = !expectedImage.equals(currentImageResult.stdout().trim());
    } else {
      log.debug("Restore deployment modification: could not determine current PEP image before restore for deployment '{}': {}",
          deploymentName, currentImageResult.stderr());
    }

    var cleanupResult =
        deploymentModificationService.cleanupFailedRolloutPods(namespace, deploymentName, containerName, expectedImage);
    if (cleanupResult.exitCode() != 0) {
      log.warn("Restore deployment modification: cleanup for deployment '{}' failed: {}",
          deploymentName, cleanupResult.stderr());
      return false;
    }

    var verifyResult =
        deploymentModificationService.verifyDeploymentUpdate(namespace, deploymentName, containerName, expectedImage);
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
    if (scenario == null || isScenarioAborted()
        || isNotTlsClientScenario(scenario)) {
      return;
    }

    if (scenario.getSourceTagNames().contains(TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG)) {
      log.info("TLS client post hook: native driver has no reset endpoint; configuration is replaced by the next pre hook.");
      return;
    }

    try {
      getTlsTestDriverConfigurationService(scenario).reset();
    } catch (AssertionError e) {
      var message = "TLS client post hook: could not reset the testdriver after scenario '"
          + scenario.getName() + "'.";
      scenario.log(message);
      log.warn(message, e);
      return;
    }
    log.info("TLS client post hook: reset testdriver after scenario '{}'.", scenario.getName());
  }

  /**
   * Restores the certificate-validation mock to its deterministic successful mode after a scenario changed it.
   *
   * @param scenario completed Cucumber scenario
   */
  @After(value = OCSP_MOCK_MODE_MODIFIED_TAG, order = ORDER_RESTORE_OCSP_MOCK_MODE)
  public void restoreOcspMockMode(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    try {
      new HttpGlueCode().sendRequestWithMultiLineBody(
          Method.POST,
          resolveOcspMockAdminUri(),
          "application/json",
          "{\"mode\":\"always_good\",\"thisUpdateOffsetSeconds\":0,"
              + "\"nextUpdateOffsetSeconds\":43200,\"includeNextUpdate\":true,"
              + "\"responseVariant\":\"normal\",\"ocspAvailable\":true,"
              + "\"crlAvailable\":true}");
      log.info("OCSP mock post hook: restored mode 'always_good' after scenario '{}'.", scenario.getName());
    } catch (Exception e) {
      var message = "OCSP mock post hook: could not restore mode 'always_good' after scenario '"
          + scenario.getName() + "'.";
      scenario.log(message);
      log.warn(message, e);
    }
  }

  /**
   * Removes every pusher returned for the current client after a tagged scenario.
   * Cleanup is best effort so an unavailable cleanup endpoint does not mask the original scenario result.
   *
   * @param scenario completed Cucumber scenario
   */
  @After(value = RESET_NOTIFICATION_PUSHERS_TAG, order = ORDER_RESET_NOTIFICATION_PUSHERS)
  public void resetNotificationPushers(final Scenario scenario) {
    var configuredEndpoint = TigerGlobalConfiguration.readStringOptional(PUSHER_ENDPOINT_CONFIG_KEY)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElse(null);
    if (configuredEndpoint == null || configuredEndpoint.isBlank()) {
      var message = "Push notification cleanup: endpoint is not configured at '"
          + PUSHER_ENDPOINT_CONFIG_KEY + "'.";
      scenario.log(message);
      log.warn(message);
      return;
    }

    List<Map<String, Object>> registeredPushers;
    try {
      var response = TigerHttpClient.givenDefaultSpec().request(Method.GET, URI.create(configuredEndpoint));
      if (!isSuccessfulResponse(response.statusCode())) {
        var message = "Push notification cleanup: listing registered pushers returned HTTP "
            + response.statusCode() + ".";
        scenario.log(message);
        log.warn(message);
        return;
      }
      registeredPushers = response.jsonPath().getList("$");
    } catch (Exception e) {
      var message = "Push notification cleanup: could not list registered pushers.";
      scenario.log(message);
      log.warn(message, e);
      return;
    }

    if (registeredPushers == null) {
      var message = "Push notification cleanup: registered pusher response is not a JSON array.";
      scenario.log(message);
      log.warn(message);
      return;
    }

    for (var registeredPusher : registeredPushers) {
      var pushkeyValue = registeredPusher.get("pushkey");
      var appIdValue = registeredPusher.get("app_id");
      if (!(pushkeyValue instanceof String pushkey) || pushkey.isBlank()
          || !(appIdValue instanceof String appId) || appId.isBlank()) {
        var message = "Push notification cleanup: skipping a registered pusher without pushkey or app_id.";
        scenario.log(message);
        log.warn(message);
        continue;
      }

      try {
        var deleteUri = notificationPusherDeleteUri(configuredEndpoint, pushkey, appId);
        var response = TigerHttpClient.givenDefaultSpec().request(Method.DELETE, deleteUri);
        if (!isSuccessfulResponse(response.statusCode())) {
          var message = "Push notification cleanup: deleting pusher '" + pushkey + "' for appId '" + appId
              + "' returned HTTP " + response.statusCode() + ".";
          scenario.log(message);
          log.warn(message);
        } else {
          log.info("Push notification cleanup: deleted pusher '{}' for appId '{}'.", pushkey, appId);
        }
      } catch (Exception e) {
        var message = "Push notification cleanup: could not delete pusher '" + pushkey
            + "' for appId '" + appId + "'.";
        scenario.log(message);
        log.warn(message, e);
      }
    }
  }

  /**
   * Builds the client pusher deletion URI with encoded identifiers.
   *
   * @param endpoint configured pusher collection endpoint
   * @param pushkey registered pusher key
   * @param appId registered application identifier
   * @return deletion URI for the registered pusher
   */
  private URI notificationPusherDeleteUri(final String endpoint, final String pushkey, final String appId) {
    var querySeparator = endpoint.contains("?") ? "&" : "?";
    return URI.create(endpoint + querySeparator
        + "pushkey=" + URLEncoder.encode(pushkey, StandardCharsets.UTF_8)
        + "&appId=" + URLEncoder.encode(appId, StandardCharsets.UTF_8));
  }

  /**
   * Checks whether an HTTP response indicates successful cleanup processing.
   *
   * @param responseCode HTTP status code
   * @return {@code true} for a 2xx response
   */
  private boolean isSuccessfulResponse(final int responseCode) {
    return responseCode >= 200 && responseCode < 300;
  }

  /**
   * Resolves the certificate-validation mock administration endpoint.
   *
   * @return URI of the mock mode endpoint
   */
  private URI resolveOcspMockAdminUri() {
    var configuredUrl = TigerGlobalConfiguration.readStringOptional(OCSP_MOCK_URL_CONFIG_KEY)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError(
            "The config key '" + OCSP_MOCK_URL_CONFIG_KEY + "' could not be resolved."));
    var baseUrl = configuredUrl.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")
        ? configuredUrl
        : "http://" + configuredUrl;
    return URI.create(baseUrl.replaceAll("/+$", "") + "/admin/mode");
  }

  /**
   * Checks whether the scenario does not need TLS client testdriver configuration.
   *
   * @param scenario active Cucumber scenario
   * @return {@code true} if no TLS client hook tag is present
   */
  private boolean isNotTlsClientScenario(final Scenario scenario) {
    var tags = scenario.getSourceTagNames();
    return !tags.contains(TLS_CLIENT_FACHDIENST_HOOK_TAG)
        && !tags.contains(TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG);
  }

  /**
   * Returns the TLS client hook tag used by the scenario.
   *
   * @param scenario active Cucumber scenario
   * @return configured TLS client hook tag
   */
  private String getTlsClientHookTag(final Scenario scenario) {
    return scenario.getSourceTagNames().contains(TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG)
        ? TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG
        : TLS_CLIENT_FACHDIENST_HOOK_TAG;
  }

  /**
   * Selects the testdriver configuration service for the regular or native client driver.
   *
   * @param scenario active Cucumber scenario
   * @return testdriver configuration service matching the scenario tag
   */
  private TestDriverConfigurationService getTlsTestDriverConfigurationService(final Scenario scenario) {
    if (scenario.getSourceTagNames().contains(TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG)) {
      return TestDriverConfigurationServiceFactory.getInstanceForPathPrefix("paths.nativeClient");
    }
    return testDriverConfigurationService;
  }

  /**
   * Resolves the maximum wait for a restarted TLS driver application endpoint.
   *
   * @return positive readiness timeout
   */
  private Duration tlsDriverReadinessTimeout() {
    int timeoutSeconds = TigerGlobalConfiguration.readIntegerOptional("zetaDeploymentConfig.podReadyTimeout")
        .orElse(DEFAULT_TLS_DRIVER_READINESS_TIMEOUT_SECONDS);
    if (timeoutSeconds <= 0) {
      throw new AssertionError("TLS driver readiness timeout must be positive.");
    }
    return Duration.ofSeconds(timeoutSeconds);
  }

  /**
   * Clears thread-local scenario lifecycle state after all other after hooks have completed.
   */
  @After(order = ORDER_CLEAR_SCENARIO_LIFECYCLE_STATE)
  public void clearScenarioLifecycleState() {
    clearCapturedPepOriginalImage();
    clearCapturedDeploymentReplicaCounts();
    clearCapturedDeploymentStrategies();
    clearCapturedConfigMapBackups();
    clearScenarioAborted();
    SoftAssertionsContext.reset();
    ReportAttachments.clearCurrentScenario();
  }

  /**
   * Reads the PEM-encoded TLS test tool CA certificate from the checked-out fixture.
   *
   * @param scenario active Cucumber scenario
   * @return PEM certificate content
   */
  private String readTlsTestToolCaCertificatePem(Scenario scenario) {
    if (scenario.getSourceTagNames().contains(TLS_SUBCA_ROOT_TAG)) {
      return readPemCertificate(Path.of(System.getProperty("user.dir")).resolve(TLS_SUBCA_ROOT_CERTIFICATE));
    }
    var configuredPath = TigerGlobalConfiguration.readStringOptional(TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY)
        .orElseThrow(() -> new AssertionError(
            "Failed pre hook: config key '" + TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY + "' could not be resolved."));

    return readPemCertificate(Path.of(System.getProperty("user.dir"), configuredPath));
  }

  /**
   * Reads one non-empty PEM certificate fixture.
   *
   * @param caCertificatePath certificate path
   * @return PEM certificate content
   */
  private String readPemCertificate(Path caCertificatePath) {
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

  /**
   * Checks if the current testsuite configuration is valid.
   *
   * @throws AssertionError if the current configuration is invalid
   */
  private static void verifyGlobalConfiguration() throws AssertionError {
    boolean isModificationAllowed = ZetaDeploymentConfiguration.isDeploymentModificationAllowed();
    boolean isAslEnabled = ZetaDeploymentConfiguration.isAslEnabled();
    boolean isAslDisabled = ZetaDeploymentConfiguration.isAslDisabled();
    verifyAslConfiguration(isModificationAllowed, isAslEnabled, isAslDisabled);
  }

  /**
   * Validates that ASL-related configuration flags are logically consistent.
   *
   * @param isModificationAllowed indicates whether deployment modifications are permitted
   * @param isAslEnabled indicates whether ASL is configured to be enabled
   * @param isAslDisabled indicates whether ASL is configured to be disabled
   * @throws AssertionError if the provided flag combination is invalid
   */
  private static void verifyAslConfiguration(boolean isModificationAllowed, boolean isAslEnabled, boolean isAslDisabled)
      throws AssertionError {
    if (isAslEnabled && isAslDisabled) {
      throw new AssertionError("Testsuite misconfiguration: ASL cannot be enabled and disabled at the"
          + "same time (zeta_k8s_enable_asl_globally and zeta_k8s_disable_asl_globally)");
    }
    if (!isModificationAllowed) {
      if (isAslEnabled) {
        throw new AssertionError("Testsuite misconfiguration: deployment modification is not allowed "
            + "but ASL is expected to be enabled (zeta_k8s_enable_asl_globally");
      }
      if (isAslDisabled) {
        throw new AssertionError("Testsuite misconfiguration: deployment modification is not allowed "
            + "but ASL is expected to be disabled (zeta_k8s_disable_asl_globally");
      }
    }
  }

  private static String formatCommandResult(CommandResult result) {
    return String.format("""
              Command execution failed (exit code %d)
              
              Command: %s
              
              stdout: %s
              
              stderr: %s""",
        result.exitCode(),
        result.command(),
        result.stdout(),
        result.stderr());
  }
}
