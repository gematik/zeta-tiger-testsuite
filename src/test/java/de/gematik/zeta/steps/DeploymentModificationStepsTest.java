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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DeploymentModificationStepsTest {

  private static final Instant EARLIER = Instant.ofEpochSecond(100);
  private static final Instant SAME_TIME = Instant.ofEpochSecond(100);
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

  @Test
  void takeoverEvidenceRequiresPendingRequestWhenOldPodDisappears() {
    assertFalse(DeploymentModificationSteps.hasTakeoverBeforeRolloutFinalizationEvidence(
        bool(() -> false), bool(() -> true), bool(() -> true)));
  }

  @Test
  void takeoverEvidencePassesWhenPodDisappearsWhilePendingAndRolloutFinalizesLater() {
    assertTrue(DeploymentModificationSteps.hasTakeoverBeforeRolloutFinalizationEvidence(
        bool(() -> true), bool(() -> true), bool(() -> true)));
  }

  @Test
  void takeoverEvidenceFailsWhenRolloutWasNotObservedAfterPodDisappeared() {
    assertFalse(DeploymentModificationSteps.hasTakeoverBeforeRolloutFinalizationEvidence(
        bool(() -> true), bool(() -> false), bool(() -> true)));
  }

  private static boolean bool(BooleanSupplier supplier) {
    return supplier.getAsBoolean();
  }

  private static Instant instant(Supplier<Instant> supplier) {
    return supplier.get();
  }

}
