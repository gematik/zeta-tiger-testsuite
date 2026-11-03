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
import de.gematik.zeta.TigerTestsuiteMain.AuthenticationProfile;
import de.gematik.zeta.reporting.DeploymentVersionCapture;
import de.gematik.zeta.services.AslProfile;
import de.gematik.zeta.services.DeploymentStateRecovery;
import de.gematik.zeta.services.DirectHttpClients;
import de.gematik.zeta.services.HelmStateRecovery;
import de.gematik.zeta.services.PerformanceIngressRoutingService;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.TestDriverConfigurationServiceFactory;
import de.gematik.zeta.services.TlsTestToolServiceFactory;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.Scenario;
import io.restassured.http.Method;
import java.io.IOException;
import java.net.URI;
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
import org.opentest4j.TestAbortedException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Manages scenario preparation, assertions and cleanup hooks.
 */
@Slf4j
public class Hooks {

  private static final ObjectMapper DRIVER_JSON = new ObjectMapper();

  private static final String NO_PROXY_TAG = "@no_proxy";
  private static final String REQUIRE_KUBECTL_TAG = "@require_kubectl";
  private static final String REQUIRE_SIGNING_KEY_TAG = "@require_signing_key";
  private static final String PERFORMANCE_TAG = "@performance";
  private static final String PERFORMANCE_DIRECT_ROUTING_TAG = "@performance_direct_routing";
  private static final String LONGRUNNING_TAG = "@longrunning";
  private static final String TPM_ENVIRONMENT_TAG = "@tpm_environment";
  private static final String REQUIRE_OIDC_DECRYPTION_KEY_TAG = "@require_oidc_decryption_key";
  private static final String DEPLOYMENT_MODIFICATION_TAG = "@deployment_modification";
  private static final String TLS_CLIENT_FACHDIENST_HOOK_TAG = "@tls_client_fachdienst_hook";
  private static final String TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG = "@tls_native_client_fachdienst_hook";
  private static final String RESET_TLS_REVOCATION_CACHE_TAG = "@reset_tls_revocation_cache";
  private static final String OCSP_MOCK_MODE_MODIFIED_TAG = "@ocsp_mock_mode_modified";
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
  private static final ThreadLocal<String> CAPTURED_PEP_ORIGINAL_IMAGE = new ThreadLocal<>();
  private static final ThreadLocal<Map<String, Integer>> CAPTURED_DEPLOYMENT_REPLICAS =
      ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<Map<String, String>> CAPTURED_DEPLOYMENT_STRATEGIES =
      ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<Set<String>> CAPTURED_CONFIG_MAP_BACKUPS =
      ThreadLocal.withInitial(HashSet::new);
  private static final ThreadLocal<Boolean> SCENARIO_ABORTED = ThreadLocal.withInitial(() -> false);
  private static final int ORDER_CLEAR_SCENARIO_LIFECYCLE_STATE = Integer.MIN_VALUE;
  private static final int ORDER_RESTORE_PERFORMANCE_ROUTING = ORDER_CLEAR_SCENARIO_LIFECYCLE_STATE + 1;
  private static final int ORDER_RESTORE_DEPLOYMENT_STATE = ORDER_RESTORE_PERFORMANCE_ROUTING + 1;
  private static final int ORDER_PREPARE_SOFT_ASSERTIONS = ORDER_RESTORE_DEPLOYMENT_STATE + 1;
  private static final int ORDER_RECOVER_PERFORMANCE_ROUTING = ORDER_PREPARE_SOFT_ASSERTIONS + 1;
  private static final int ORDER_RESET_TIGER_PROXY_STATE = ORDER_RECOVER_PERFORMANCE_ROUTING + 1;
  private static final int ORDER_PROXY_REQUIREMENT_GUARD = ORDER_RESET_TIGER_PROXY_STATE + 1;
  private static final int ORDER_CLEAR_RECORDED_MESSAGES = ORDER_PROXY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_DRIVER_PROFILE_GUARD = ORDER_CLEAR_RECORDED_MESSAGES + 1;
  private static final int ORDER_KUBECTL_REQUIREMENT_GUARD = ORDER_DRIVER_PROFILE_GUARD + 1;
  private static final int ORDER_PERFORMANCE_REQUIREMENT_GUARD = ORDER_KUBECTL_REQUIREMENT_GUARD + 1;
  private static final int ORDER_TPM_ENVIRONMENT_REQUIREMENT_GUARD = ORDER_PERFORMANCE_REQUIREMENT_GUARD + 1;
  private static final int ORDER_OIDC_DECRYPTION_KEY_REQUIREMENT_GUARD =
      ORDER_TPM_ENVIRONMENT_REQUIREMENT_GUARD + 1;
  private static final int ORDER_VERIFY_DEPLOYMENT_MODIFICATION =
      ORDER_OIDC_DECRYPTION_KEY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_RESET_MAILCATCHER = ORDER_VERIFY_DEPLOYMENT_MODIFICATION + 1;
  private static final int ORDER_SIGNING_KEY_REQUIREMENT_GUARD = ORDER_RESET_MAILCATCHER + 1;
  private static final int ORDER_ENABLE_PERFORMANCE_ROUTING = ORDER_SIGNING_KEY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_RESET_TLS_REVOCATION_CACHE = ORDER_ENABLE_PERFORMANCE_ROUTING + 1;
  private static final int ORDER_TLS_CLIENT_PRE_HOOK = ORDER_RESET_TLS_REVOCATION_CACHE + 1;
  private static final int ORDER_VERIFY_SOFT_ASSERTIONS = Integer.MAX_VALUE - 1;

  private static HelmStateRecovery globalHelmRecovery;
  private static ZetaDeploymentModificationService deploymentModificationService = ZetaDeploymentConfiguration.getServiceInstance();
  private static final int ORDER_TLS_CLIENT_POST_HOOK = ORDER_VERIFY_SOFT_ASSERTIONS - 1;
  private static final int ORDER_RESTORE_OCSP_MOCK_MODE = ORDER_TLS_CLIENT_POST_HOOK - 1;
  // Cucumber runs @After hooks in descending order. Keep the active SDK context for
  // NotificationLifecycleSteps.cleanup (order 100) before pool cleanup changes identities.
  private static final int ORDER_RESET_NOTIFICATION_PUSHERS = 99;

  /** Runs the owned OIDC driver reset before scenario lifecycle cleanup releases its lock. */
  public static final int ORDER_RESET_OWNED_OIDC_DRIVER = ORDER_RESET_NOTIFICATION_PUSHERS - 1;

  private final TigerProxyManipulationsSteps tigerProxyManipulationsSteps;
  private final TestDriverConfigurationService testDriverConfigurationService;
  private final SigningKeyPreflight signingKeyPreflight;
  private final OidcDecryptionKeyPreflight oidcDecryptionKeyPreflight;
  private final NotificationPusherCleanup notificationPusherCleanup;
  private final PerformanceIngressRoutingService performanceIngressRoutingService;
  private final ThreadLocal<PerformanceIngressRoutingService.RoutingSnapshot> performanceRoutingSnapshot =
      new ThreadLocal<>();
  private final ThreadLocal<Boolean> deploymentCleanupFailed = ThreadLocal.withInitial(() -> false);

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
    this(injectedDeploymentModificationService, tigerProxyManipulationsSteps, testDriverConfigurationService,
        new NotificationPusherCleanup(testDriverConfigurationService));
  }

  Hooks(final ZetaDeploymentModificationService injectedDeploymentModificationService,
        final TigerProxyManipulationsSteps tigerProxyManipulationsSteps,
        final TestDriverConfigurationService testDriverConfigurationService,
        final NotificationPusherCleanup notificationPusherCleanup) {
    this.notificationPusherCleanup = notificationPusherCleanup;
    deploymentModificationService = injectedDeploymentModificationService;
    this.tigerProxyManipulationsSteps = tigerProxyManipulationsSteps;
    this.testDriverConfigurationService = testDriverConfigurationService;
    this.performanceIngressRoutingService =
        new PerformanceIngressRoutingService(injectedDeploymentModificationService);
    var jwtSteps = new JwtSteps();
    this.signingKeyPreflight = new SigningKeyPreflight(
        tigerProxyManipulationsSteps,
        new TigerProxyAccessTokenExtractor(),
        jwtSteps,
        new KeycloakSigningKeyResolver(injectedDeploymentModificationService, jwtSteps),
        new HsmSimulatorSigningKeyResolver(injectedDeploymentModificationService, jwtSteps));
    this.oidcDecryptionKeyPreflight = new OidcDecryptionKeyPreflight(
        new KeycloakOidcDecryptionKeyResolver(injectedDeploymentModificationService));
  }

  /** Reserves the shared OIDC driver before any scenario hook can reset the proxy. */
  @Before(order = ORDER_CLEAR_SCENARIO_LIFECYCLE_STATE)
  public void reserveOidcDriver() {
    if (AuthenticationProfile.current() == AuthenticationProfile.OIDC) {
      OidcScenarioContext.acquireDriver();
    }
  }

  /**
   * Global hook to capture tested versions and apply active ZETA Guard modifications before any scenario is run.
   *
   * @throws AssertionError if the modification was not successful
   */
  @BeforeAll
  @SuppressWarnings("unused")
  public static void applyGlobalModifications() throws AssertionError {
    verifyGlobalConfiguration();
    DeploymentVersionCapture.capture(deploymentModificationService);

    if (!ZetaDeploymentConfiguration.isDeploymentModificationAllowed()) {
      return;
    }
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    new DeploymentStateRecovery(deploymentModificationService, namespace).recoverAbandoned();
    new PerformanceIngressRoutingService(deploymentModificationService).recoverAbandonedRouting(namespace);
    new HelmStateRecovery(deploymentModificationService, namespace).recoverAbandoned();
    if (ZetaDeploymentConfiguration.isAslEnabled() || ZetaDeploymentConfiguration.isAslDisabled()) {
      var request = ZetaDeploymentConfiguration.isAslEnabled()
          ? ZetaDeploymentConfiguration.getEnableAslRequest() : ZetaDeploymentConfiguration.getDisableAslRequest();
      var recovery = new HelmStateRecovery(deploymentModificationService, request, namespace);
      globalHelmRecovery = recovery;
      var marker = recovery.begin();
      var result = deploymentModificationService.deployZetaGuardWithHelmOverrides(request, marker, namespace);
      if (result.exitCode() != 0) {
        throw new AssertionError("Applying global modifications failed: " + formatCommandResult(result));
      }
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
    if (globalHelmRecovery != null) {
      var namespace = ZetaDeploymentConfiguration.getNamespace();
      new DeploymentStateRecovery(deploymentModificationService, namespace).recoverAbandoned();
      new PerformanceIngressRoutingService(deploymentModificationService).recoverAbandonedRouting(namespace);
      globalHelmRecovery.finish();
      globalHelmRecovery = null;
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
   * Resets TigerProxy manipulations before and after each scenario, including failed scenarios.
   * Recorded messages remain available for reporting and other cleanup hooks.
   *
   * <p>If the standalone TigerProxy is not configured, the cleanup is skipped. A configured proxy
   * must acknowledge both resets; otherwise stale manipulations could affect later scenarios.</p>
   */
  @Before(order = ORDER_RESET_TIGER_PROXY_STATE)
  @After(order = ORDER_RESET_TIGER_PROXY_STATE)
  public void resetTigerProxyState() {
    tigerProxyManipulationsSteps.resetTigerProxyStateIfAvailable();
  }

  /**
   * Clears standalone history for proxy scenarios before resetting the local assertion history.
   *
   * @param scenario active Cucumber scenario
   * @throws AssertionError if the required standalone message reset is not acknowledged
   */
  @Before(order = ORDER_CLEAR_RECORDED_MESSAGES)
  public void clearRecordedMessages(final Scenario scenario) {
    if (!isScenarioAborted() && !scenario.getSourceTagNames().contains(NO_PROXY_TAG)) {
      tigerProxyManipulationsSteps.resetTigerProxyMessagesStrictly();
    }
    RbelMessageRetriever.getInstance().clearRbelMessages();
  }

  /** Clears the test mailbox before OIDC setup can request its first OTP. */
  @Before(order = ORDER_RESET_MAILCATCHER)
  public void resetMailcatcher() {
    if (isScenarioAborted() || AuthenticationProfile.current() != AuthenticationProfile.OIDC) {
      return;
    }
    try {
      var endpoint = URI.create(TigerGlobalConfiguration.resolvePlaceholders(
          "https://${zeta_base_url}${paths.mailcatcher.messagesPath}"));
      var client = new RestTemplate(DirectHttpClients.testBackendRequestFactory(Duration.ofSeconds(5)));
      var deleted = client.exchange(endpoint, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
      if (deleted.getStatusCode().value() != 204) {
        throw new AssertionError("Mailcatcher reset did not acknowledge deletion with HTTP 204.");
      }
      var body = client.getForObject(endpoint, String.class);
      var messages = body == null ? null : DRIVER_JSON.readTree(body);
      if (messages == null || !messages.isArray() || !messages.isEmpty()) {
        throw new AssertionError("Mailcatcher reset did not leave an empty mailbox.");
      }
      log.info("Mailcatcher reset acknowledged and empty mailbox verified.");
    } catch (RuntimeException failure) {
      throw new AssertionError("Mailcatcher reset failed before OIDC scenario setup.", failure);
    }
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
  @Before(value = REQUIRE_KUBECTL_TAG, order = ORDER_KUBECTL_REQUIREMENT_GUARD)
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

    if (scenario.getSourceTagNames().contains("@smb")) {
      verifyDriverRuntime(AuthenticationProfile.SMB);
    } else if (scenario.getSourceTagNames().contains("@oidc")) {
      verifyDriverRuntime(AuthenticationProfile.OIDC);
    }
  }

  /** Verifies the configured authentication mode for SMC-B scenarios. */
  @Before(value = "@smb", order = ORDER_DRIVER_PROFILE_GUARD)
  public void verifySmbProfile() {
    verifyProfile(AuthenticationProfile.SMB);
  }

  /** Verifies the configured authentication mode for OIDC scenarios. */
  @Before(value = "@oidc", order = ORDER_DRIVER_PROFILE_GUARD)
  public void verifyOidcProfile() {
    verifyProfile(AuthenticationProfile.OIDC);
  }

  /**
   * Checks the selected client configuration unless an earlier hook aborted the scenario.
   *
   * @param profile authentication mode required by the scenario
   */
  private void verifyProfile(final AuthenticationProfile profile) {
    if (!isScenarioAborted()) {
      profile.verifyConfiguredMode(TigerGlobalConfiguration.readStringOptional("paths.client.authMode")
          .map(TigerGlobalConfiguration::resolvePlaceholders).orElse(null));
    }
  }

  /**
   * Verifies the selected driver's declared runtime authentication mode.
   *
   * @param profile authentication mode required by the scenario
   * @throws AssertionError if the selected deployment or its authentication mode is inconsistent
   */
  private void verifyDriverRuntime(final AuthenticationProfile profile) {
    var deployment = driverConfigurationValue("paths.client", "deploymentName");
    var container = driverConfigurationValue("paths.client", "containerName");
    var result = deploymentModificationService.executeKubectlCommand(false,
        "-n", ZetaDeploymentConfiguration.getNamespace(), "get", "deployment", deployment, "-o", "json");
    if (result.exitCode() != 0) {
      throw new AssertionError("Cannot observe the SDK authentication profile");
    }
    var containers = DRIVER_JSON.readTree(result.stdout()).at("/spec/template/spec/containers")
        .valueStream().filter(value -> container.equals(value.path("name").asString())).toList();
    if (containers.size() != 1) {
      throw new AssertionError("Ambiguous testdriver runtime profile");
    }
    var modes = containers.getFirst().path("env").valueStream()
        .filter(value -> "AUTH_MODE".equals(value.path("name").asString())).toList();
    if (modes.size() != 1 || !profile.name().equals(modes.getFirst().path("value").asString())) {
      throw new AssertionError("This scenario requires the " + profile + " SDK profile in the selected deployment");
    }
  }

  /**
   * Verifies the signing key required by a scenario and skips only when local and configured fallback
   * resolution both fail.
   *
   * <p>The local check deliberately uses the existing testclient request and JWT verification glue.
   * The fallback uses the existing Java kubectl service to read the database or test simulator key and
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
      reason = "Skipping: signing-key preflight failed: " + e.getMessage();
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
   * Resolves a required Kubernetes identity from the same configuration prefix as the driver's URLs.
   *
   * @param pathPrefix configuration prefix of the selected regular or native driver
   * @param setting name of the deployment or container setting
   * @return nonblank configured Kubernetes identity
   */
  private String driverConfigurationValue(final String pathPrefix, final String setting) {
    String key = pathPrefix + "." + setting;
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .orElseThrow(() -> new AssertionError("Missing driver configuration " + key + "."));
  }

  /** Selects ASL profiles unless an earlier prerequisite hook already aborted the scenario. */
  @Before(value = "@asl_off or @asl_pep or @asl_rs", order = ORDER_VERIFY_DEPLOYMENT_MODIFICATION)
  public void selectAslProfile(final Scenario scenario) {
    if (isScenarioAborted()) {
      return;
    }
    String requested = AslProfile.selectedTag(new HashSet<>(scenario.getSourceTagNames()));
    String active = AslProfile.activeTag();
    scenario.log("ASL resource profile: requested=" + requested + ", advertised=" + active);
    if (!active.equals(requested)) {
      abortScenario("Profile mismatch: " + requested + " requires a different advertised ASL mode than " + active);
    }
    if ("@asl_rs".equals(requested)) {
      TigerGlobalConfiguration.readStringOptional("asl.rs.upstreamHost")
          .map(TigerGlobalConfiguration::resolvePlaceholders)
          .filter(host -> !host.isBlank())
          .orElseThrow(() -> new AssertionError(
              "RS ASL profile requires asl.rs.upstreamHost and a distinct recorded PEP-to-RS hop"));
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
    new DeploymentStateRecovery(deploymentModificationService, namespace).attach();
  }

  /**
   * Routes PEP, PDP, and performance WebSocket traffic directly to their Kubernetes services.
   *
   * <p>The matching after hook restores the captured ingress state even when the scenario fails.</p>
   *
   * @param scenario active Cucumber scenario
   */
  @Before(value = PERFORMANCE_DIRECT_ROUTING_TAG, order = ORDER_ENABLE_PERFORMANCE_ROUTING)
  public void enableDirectPerformanceRouting(final Scenario scenario) {
    if (!TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false)) {
      throw new AssertionError("Direct routing requires allow_deployment_modification=true.");
    }
    var namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError(
            "Kubernetes namespace is required for direct performance routing."));
    performanceRoutingSnapshot.set(performanceIngressRoutingService.enableDirectRouting(namespace));
    scenario.log("Performance ingress and PEP upstreams temporarily bypass TigerProxy in namespace '"
        + namespace + "'.");
  }

  /** Recovers abandoned routing changes before proxy cleanup or scenario requests. */
  @Before(order = ORDER_RECOVER_PERFORMANCE_ROUTING)
  public void recoverAbandonedPerformanceRouting() {
    if (!TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false)) {
      return;
    }
    TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .filter(namespace -> !namespace.isBlank())
        .ifPresent(namespace -> {
          new DeploymentStateRecovery(deploymentModificationService, namespace).recoverAbandoned();
          performanceIngressRoutingService.recoverAbandonedRouting(namespace);
        });
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
   * The driver identity comes from the same client configuration as the scenario's request URLs.
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
    var pathPrefix = scenario.getSourceTagNames().contains(TLS_NATIVE_CLIENT_FACHDIENST_HOOK_TAG)
        ? "paths.nativeClient"
        : "paths.client";
    var deploymentName = driverConfigurationValue(pathPrefix, "deploymentName");

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
   * Verifies all collected soft assertions for the current scenario.
   */
  @After(order = ORDER_VERIFY_SOFT_ASSERTIONS)
  public void verifySoftAssertions() {
    if (isScenarioAborted()) {
      return;
    }
    SoftAssertionsContext.assertAll();
  }

  /**
   * Restores routing state captured for a direct performance scenario.
   *
   * @param scenario active Cucumber scenario
   */
  @After(value = PERFORMANCE_DIRECT_ROUTING_TAG, order = ORDER_RESTORE_PERFORMANCE_ROUTING)
  public void restorePerformanceRouting(final Scenario scenario) {
    var snapshot = performanceRoutingSnapshot.get();
    if (snapshot == null) {
      log.debug("Performance ingress restore skipped because no routing snapshot was captured.");
      return;
    }
    if (deploymentCleanupFailed.get()) {
      performanceIngressRoutingService.deferRestoration();
      scenario.log("Routing restoration deferred until deployment recovery succeeds; both backups retained.");
      return;
    }

    try {
      performanceIngressRoutingService.restore(snapshot);
      scenario.log("Original performance routing restored in namespace '"
          + snapshot.namespace() + "'.");
    } finally {
      performanceRoutingSnapshot.remove();
    }
  }

  /**
   * Ensures modifications to the Zeta Guard deployment are restored to their original state after scenarios finish.
   *
   * @param scenario active Cucumber scenario
   */
  @After(order = ORDER_RESTORE_DEPLOYMENT_STATE)
  public void restoreDeploymentModifications(final Scenario scenario) {
    var recovery = DeploymentStateRecovery.current();
    if (recovery == null) {
      if (!getCapturedConfigMapBackups().isEmpty() || !CAPTURED_DEPLOYMENT_REPLICAS.get().isEmpty()
          || !CAPTURED_DEPLOYMENT_STRATEGIES.get().isEmpty() || getCapturedPepOriginalImage().isPresent()) {
        deploymentCleanupFailed.set(true);
        throw new AssertionError("Deployment changes were recorded without a durable recovery session; backups retained");
      }
      return;
    }
    try {
      recovery.finish();
    } catch (RuntimeException | AssertionError exception) {
      deploymentCleanupFailed.set(true);
      throw exception;
    }
    var url = TigerGlobalConfiguration.readStringOptional("paths.client.reset").orElse(null);
    if (url != null) {
      try {
        new HttpGlueCode().sendEmptyRequest(Method.GET, new URI(url));
      } catch (Exception exception) {
        throw new AssertionError("Deployment restored, but client reset failed", exception);
      }
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
      var requestFactory = DirectHttpClients.requestFactory(Duration.ofSeconds(10));
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      var body = "{\"mode\":\"always_good\",\"thisUpdateOffsetSeconds\":0,"
          + "\"nextUpdateOffsetSeconds\":43200,\"includeNextUpdate\":true,"
          + "\"responseVariant\":\"normal\",\"ocspAvailable\":true,"
          + "\"crlAvailable\":true}";
      new RestTemplate(requestFactory).exchange(resolveOcspMockAdminUri(), HttpMethod.POST,
          new HttpEntity<>(body, headers), Void.class);
      log.info("OCSP mock post hook: restored mode 'always_good' after scenario '{}'.", scenario.getName());
    } catch (Exception e) {
      var message = "OCSP mock post hook: could not restore mode 'always_good' after scenario '"
          + scenario.getName() + "'.";
      scenario.log(message);
      log.warn(message, e);
    }
  }

  /**
   * Cleans every pool identity, including secondary identities and partly failed setup attempts.
   * Runs without a tag so a missing feature tag cannot leak a reserved identity.
   *
   * @param scenario completed scenario receiving cleanup diagnostics
   */
  @After(order = ORDER_RESET_NOTIFICATION_PUSHERS)
  public void resetNotificationPushers(final Scenario scenario) {
    var identities = OidcScenarioContext.identities();
    if (identities.isEmpty()) {
      return;
    }
    var failure = new AssertionError("Notification pool cleanup was incomplete.");
    for (var identity : identities) {
      boolean clean = false;
      try {
        notificationPusherCleanup.clean(identity.reservation());
        clean = true;
        scenario.log("Cleaned notification KVNR " + identity.reservation().kvnr());
      } catch (RuntimeException | AssertionError e) {
        scenario.log("Notification KVNR remains quarantined: " + identity.reservation().kvnr());
        failure.addSuppressed(e);
      } finally {
        identity.finish(clean);
      }
    }
    try {
      testDriverConfigurationService.reset();
    } catch (RuntimeException | AssertionError e) {
      failure.addSuppressed(e);
    }
    if (failure.getSuppressed().length > 0) {
      throw failure;
    }
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
    deploymentCleanupFailed.remove();
    clearCapturedPepOriginalImage();
    clearCapturedDeploymentReplicaCounts();
    clearCapturedDeploymentStrategies();
    clearCapturedConfigMapBackups();
    performanceRoutingSnapshot.remove();
    clearScenarioAborted();
    SoftAssertionsContext.reset();
    ReportAttachments.clearCurrentScenario();
    OidcScenarioContext.clear();
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
    AuthenticationProfile.current().verifyConfiguredMode(
        TigerGlobalConfiguration.readStringOptional("paths.client.authMode")
            .map(TigerGlobalConfiguration::resolvePlaceholders).orElse(null));
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

  /** Formats command evidence for a visible hook failure. */
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
