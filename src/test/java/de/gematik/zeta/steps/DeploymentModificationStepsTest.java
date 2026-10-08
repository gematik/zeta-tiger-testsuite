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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaPoppTokenValidityRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DeploymentModificationStepsTest {

  private static final Instant EARLIER = Instant.ofEpochSecond(100);
  private static final Instant SAME_TIME = Instant.ofEpochSecond(100);
  private static final Instant MIDDLE = Instant.ofEpochSecond(103);
  private static final Instant LATER = Instant.ofEpochSecond(105);

  @Test
  void responseFirstEvidenceRequiresProgressOrDirectTimestampOrdering() {
    assertFalse(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        true,
        SAME_TIME,
        SAME_TIME,
        false,
        false));
  }

  @Test
  void responseFirstEvidencePassesWhenRolloutWasStillInProgressAfterResponse() {
    assertTrue(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        true,
        EARLIER,
        LATER,
        true,
        false));
  }

  @Test
  void responseFirstEvidencePassesWhenResponseWasObservedBeforeRolloutFinalized() {
    assertTrue(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        true,
        EARLIER,
        LATER,
        false,
        false));
  }

  @Test
  void rolloutFirstEvidenceRequiresPendingResponseOrDirectTimestampOrdering() {
    assertFalse(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        false,
        SAME_TIME,
        SAME_TIME,
        false,
        false));
  }

  @Test
  void rolloutFirstEvidencePassesWhenResponseWasStillPendingAfterRollout() {
    assertTrue(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        false,
        LATER,
        EARLIER,
        false,
        true));
  }

  @Test
  void rolloutFirstEvidencePassesWhenRolloutWasObservedBeforeResponse() {
    assertTrue(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        false,
        LATER,
        EARLIER,
        false,
        false));
  }

  @Test
  void responseFirstEvidenceFailsWhenObservedTimestampsShowReverseOrder() {
    assertFalse(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        true,
        LATER,
        EARLIER,
        false,
        false));
  }

  @Test
  void rolloutFirstEvidenceFailsWhenObservedTimestampsShowReverseOrder() {
    assertFalse(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        false,
        EARLIER,
        LATER,
        false,
        false));
  }

  @Test
  void rolloutFirstEvidenceFailsWithoutObservedTimestamps() {
    assertFalse(DeploymentModificationSteps.hasExpectedRolloutOrderingEvidence(
        false,
        null,
        EARLIER,
        false,
        false));
  }

  @Test
  void deploymentSwitchDetectionUsesPreCapturedOldPod() {
    assertTrue(DeploymentModificationSteps.hasDeploymentSwitchedToDifferentReadyPod(
        "pep-deployment-old", "pep-deployment-new"));
  }

  @Test
  void deploymentSwitchDetectionDoesNotRebaselineToCurrentPod() {
    assertFalse(DeploymentModificationSteps.hasDeploymentSwitchedToDifferentReadyPod(
        "pep-deployment-old", "pep-deployment-old"));
  }

  @Test
  void finalizationPollingEvidenceRequiresObservedProgressWhenProbeStartedAfterFinalization() {
    assertFalse(DeploymentModificationSteps.hasRolloutFinalizationEvidenceAfterObservedProgress(
        bool(() -> false), bool(() -> false), instant(() -> null)));
  }

  @Test
  void finalizationPollingEvidencePassesWhenFinalizationFollowsObservedProgress() {
    assertTrue(DeploymentModificationSteps.hasRolloutFinalizationEvidenceAfterObservedProgress(
        bool(() -> true), bool(() -> false), instant(() -> LATER)));
  }

  @Test
  void finalizationPollingEvidencePassesWhenSuccessfulProbeStartedBeforeFinalization() {
    assertTrue(DeploymentModificationSteps.hasRolloutFinalizationEvidenceAfterObservedProgress(
        bool(() -> false), bool(() -> true), instant(() -> LATER)));
  }

  @Test
  void responseCodeMatcherUsesExactStatusWhenRegexModeIsDisabled() {
    assertTrue(DeploymentModificationSteps.matchesExpectedResponseCode("400", "400", false));
  }

  @Test
  void responseCodeMatcherSupportsRegexPatterns() {
    assertTrue(DeploymentModificationSteps.matchesExpectedResponseCode("401", "40[01]", true));
  }

  @Test
  void backgroundUpdateEvidenceRequiresVisibleRolloutWhileResponseIsStillPending() {
    assertFalse(DeploymentModificationSteps.hasBackgroundUpdateEvidence(
        bool(() -> false), bool(() -> true)));
  }

  @Test
  void backgroundUpdateEvidencePassesWhenImageVisibilityWasObservedWhileResponseWasPending() {
    assertTrue(DeploymentModificationSteps.hasBackgroundUpdateEvidence(
        bool(() -> true), bool(() -> true)));
  }

  /**
   * Verifies that literal takeover timing rejects a response transmitted before the forced pod deletion.
   */
  @Test
  void literalTakeoverTimingRequiresResponseAfterForcedPodDeletion() {
    assertFalse(DeploymentModificationSteps.hasLiteralTakeoverResponseTiming(EARLIER, LATER));
  }

  /**
   * Verifies that literal takeover timing accepts a response transmitted after the forced pod deletion.
   */
  @Test
  void literalTakeoverTimingPassesWhenResponseFollowsForcedPodDeletion() {
    assertTrue(DeploymentModificationSteps.hasLiteralTakeoverResponseTiming(LATER, EARLIER));
  }

  /**
   * Verifies that literal takeover timing requires the new PEP observation between request and response.
   */
  @Test
  void literalTakeoverNewPepTimingRequiresNewPepBetweenRequestAndResponse() {
    assertFalse(DeploymentModificationSteps.hasLiteralTakeoverNewPepTiming(EARLIER, LATER, SAME_TIME));
  }

  /**
   * Verifies that literal takeover timing accepts the new PEP observation between request and response.
   */
  @Test
  void literalTakeoverNewPepTimingPassesWhenNewPepIsBetweenRequestAndResponse() {
    assertTrue(DeploymentModificationSteps.hasLiteralTakeoverNewPepTiming(EARLIER, MIDDLE, LATER));
  }

  /**
   * Verifies that literal takeover rejects rollout finalization before the old pod disappeared.
   */
  @Test
  void literalTakeoverFinalizationTimingRequiresFinalizationAfterOldPodDisappeared() {
    assertFalse(DeploymentModificationSteps.hasLiteralTakeoverFinalizationTiming(LATER, EARLIER));
  }

  /**
   * Verifies that literal takeover accepts rollout finalization after the old pod disappeared.
   */
  @Test
  void literalTakeoverFinalizationTimingPassesWhenFinalizationFollowsOldPodDisappearance() {
    assertTrue(DeploymentModificationSteps.hasLiteralTakeoverFinalizationTiming(EARLIER, LATER));
  }

  /**
   * Verifies that scenario exit waits for an in-flight deployment image update command.
   */
  @Test
  void scenarioExitWaitsForInFlightDeploymentImageUpdate() {
    var imageUpdate = new CompletableFuture<CommandResult>();
    var observations = new ArrayList<String>();

    CompletableFuture.runAsync(() -> {
      try {
        TimeUnit.MILLISECONDS.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      imageUpdate.complete(new CommandResult(List.of("kubectl"), 0, "", ""));
    });

    DeploymentModificationSteps.awaitDeploymentImageUpdateBeforeScenarioExit(
        imageUpdate, "pep-deployment", observations);

    assertTrue(imageUpdate.isDone());
    assertTrue(observations.stream()
        .anyMatch(entry -> entry.startsWith("waitingForImageUpdateCompletionBeforeScenarioExit=")));
    assertEquals("imageUpdateCompletedBeforeScenarioExitExitCode=0", observations.getLast());
  }

  /**
   * Verifies that a configured Helm rollback timeout is passed through without surrounding whitespace.
   */
  @Test
  void helmRollbackTimeoutUsesTrimmedConfiguredValue() {
    assertEquals("12m", DeploymentModificationSteps.resolveHelmRollbackTimeout(" 12m "));
  }

  /**
   * Verifies that a missing Helm rollback timeout keeps the documented default value.
   */
  @Test
  void helmRollbackTimeoutFallsBackForMissingValue() {
    assertEquals("10m", DeploymentModificationSteps.resolveHelmRollbackTimeout(null));
  }

  /**
   * Verifies that a blank Helm rollback timeout keeps the documented default value.
   */
  @Test
  void helmRollbackTimeoutFallsBackForBlankValue() {
    assertEquals("10m", DeploymentModificationSteps.resolveHelmRollbackTimeout(" "));
  }

  /**
   * Verifies that a stale ConfigMap backup deletion failure stops the scenario before modifying deployment state.
   */
  @Test
  void configMapBackupPreparationFailsBeforeModificationWhenStaleBackupCannotBeDeleted() {
    resetScenarioState();
    configurePoppTokenValidityStep();
    var service = new BackupPreparationService(true, 1);
    var steps = new DeploymentModificationSteps(service);

    var error = assertThrows(AssertionError.class, () -> steps.setPoppTokenValidity("300s"));

    assertTrue(error.getMessage().contains("Could not delete pre-existing ConfigMap backup"));
    assertEquals(1, service.deleteConfigMapBackupCalls);
    assertEquals(0, service.setPoppTokenValidityCalls);
    assertFalse(Hooks.getCapturedConfigMapBackups().contains("pep-test-nginx-conf"));
  }

  /**
   * Verifies that backup ownership is recorded after the stale-backup check succeeds.
   */
  @Test
  void configMapBackupPreparationRecordsOwnershipAfterSuccessfulPreconditionCheck() {
    resetScenarioState();
    configurePoppTokenValidityStep();
    var service = new BackupPreparationService(false, 0);
    var steps = new DeploymentModificationSteps(service);

    steps.setPoppTokenValidity("300s");

    assertEquals(0, service.deleteConfigMapBackupCalls);
    assertEquals(1, service.setPoppTokenValidityCalls);
    assertTrue(Hooks.getCapturedConfigMapBackups().contains("pep-test-nginx-conf"));
  }

  private static boolean bool(BooleanSupplier supplier) {
    return supplier.getAsBoolean();
  }

  private static Instant instant(Supplier<Instant> supplier) {
    return supplier.get();
  }

  /**
   * Clears scenario-local hook state for tests that exercise backup ownership.
   */
  private static void resetScenarioState() {
    var hooks = new Hooks(new BackupPreparationService(false, 0), new TigerProxyManipulationsSteps(),
        new TestDriverConfigurationService("http://localhost/reset", "http://localhost/configure"));
    hooks.prepareSoftAssertions();
  }

  /**
   * Writes the minimal Tiger configuration required for PoPP validity deployment steps.
   */
  private static void configurePoppTokenValidityStep() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.podName", "pep-deployment",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.configMapName", "pep-test-nginx-conf",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.keySegments", "data,nginx.conf",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.wellKnown.configMapName", "pep-well-known",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.wellKnown.keySegments", "data,well-known.json",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.poppTokenValidity.enabled", "quarter",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.poppTokenValidity.disabled", "300s",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.poppTokenValidity.regex", "popp-token-validity .*",
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  private static final class BackupPreparationService extends ZetaDeploymentModificationService {

    private final boolean staleBackupExists;
    private final int deleteExitCode;
    private int deleteConfigMapBackupCalls;
    private int setPoppTokenValidityCalls;

    /**
     * Creates a fake deployment modification service for backup precondition tests.
     *
     * @param staleBackupExists whether the fake cluster reports an existing backup
     * @param deleteExitCode exit code returned for stale backup deletion
     */
    private BackupPreparationService(boolean staleBackupExists, int deleteExitCode) {
      super(1, 1);
      this.staleBackupExists = staleBackupExists;
      this.deleteExitCode = deleteExitCode;
    }

    /**
     * Reports whether a stale ConfigMap backup exists.
     */
    @Override
    public boolean hasConfigMapBackup(String namespace, String configMapName) {
      return staleBackupExists;
    }

    /**
     * Records stale ConfigMap backup deletion attempts.
     */
    @Override
    public CommandResult deleteConfigMapBackup(String namespace, String configMapName) {
      deleteConfigMapBackupCalls++;
      return new CommandResult(List.of("kubectl"), deleteExitCode, "", "delete failed");
    }

    /**
     * Records whether the deployment modification was allowed to proceed.
     */
    @Override
    public KubectlPatchCommandResult setPoppTokenValidity(ZetaDeploymentDetails details,
        ZetaPoppTokenValidityRequest request, String validity) {
      setPoppTokenValidityCalls++;
      var commandResult = new CommandResult(List.of("kubectl"), 0, "", "");
      return new KubectlPatchCommandResult(commandResult, "");
    }
  }

}
