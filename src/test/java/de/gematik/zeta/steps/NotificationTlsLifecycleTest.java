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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.NotificationRsTlsProfile;
import de.gematik.zeta.services.NotificationTlsProfile;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.jackson.databind.ObjectMapper;

/** Regression checks for ownership across split steps and independent operation diagnostics. */
class NotificationTlsLifecycleTest {
  @AfterEach
  void clearSoftFailures() {
    SoftAssertionsContext.reset();
  }

  private MockedStatic<TigerGlobalConfiguration> configuration() {
    var config = mockStatic(TigerGlobalConfiguration.class);
    config
        .when(() -> TigerGlobalConfiguration.readBooleanOptional(anyString()))
        .thenReturn(Optional.of(true));
    config
        .when(() -> TigerGlobalConfiguration.readStringOptional(anyString()))
        .thenReturn(Optional.of("fixture"));
    config
        .when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
        .thenAnswer(call -> call.getArgument(0));
    return config;
  }

  @Test
  void rsHookRetainsOwnershipAfterPartialSetupFailure() throws Exception {
    try (var config = configuration();
        var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var profiles =
            mockConstruction(
                NotificationRsTlsProfile.class,
                (profile, context) ->
                    doThrow(new AssertionError("partial setup")).when(profile).start())) {
      var steps = new NotificationRsTlsSteps();
      assertThrows(AssertionError.class, () -> steps.prepare("X123456789"));
      steps.cleanup();
      var profile = profiles.constructed().getFirst();
      verify(profile).start();
      verify(profile).close();
      verifyNoMoreInteractions(profile);
    }
  }

  @Test
  void gatewayHookRetainsOwnershipAfterPartialVariantFailure() throws Exception {
    try (var config = configuration();
        var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var profiles =
            mockConstruction(
                NotificationTlsProfile.class,
                (profile, context) ->
                    doThrow(new AssertionError("partial variant"))
                        .when(profile)
                        .variant("good", "good"))) {
      var steps = new NotificationTlsSteps();
      steps.prepare("own-key", "baseline");
      assertThrows(AssertionError.class, () -> steps.selectProfile("good", "good"));
      steps.cleanup();
      var profile = profiles.constructed().getFirst();
      verify(profile).variant("good", "good");
      verify(profile).close();
      verifyNoMoreInteractions(profile);
    }
  }

  @Test
  void gatewayHookRetainsOwnershipAfterPartialStartFailure() throws Exception {
    try (var config = configuration();
        var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var profiles = mockConstruction(NotificationTlsProfile.class, (profile, context) ->
            doThrow(new AssertionError("partial start")).when(profile).startGateway())) {
      var steps = new NotificationTlsSteps();
      steps.prepare("own-key", "baseline");
      steps.selectProfile("good", "good");
      assertThrows(AssertionError.class, steps::startGateway);
      steps.cleanup();
      var profile = profiles.constructed().getFirst();
      verify(profile).variant("good", "good");
      verify(profile).startGateway();
      verify(profile).close();
      verifyNoMoreInteractions(profile);
    }
  }

  @Test
  void gatewayObservationUsesTheAcceptedNotificationId() throws Exception {
    try (var config = configuration();
        var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var profiles = mockConstruction(NotificationTlsProfile.class)) {
      var steps = new NotificationTlsSteps();
      steps.prepare("own-key", "baseline");
      steps.selectProfile("good", "good");
      steps.startGateway();
      var id = "12345678-1234-1234-1234-123456789012";
      assertThrows(AssertionError.class, () -> steps.verifyConnection(id, "own-marker"));
      var profile = profiles.constructed().getFirst();
      verify(profile).variant("good", "good");
      verify(profile).startGateway();
      verify(profile).awaitObservations(id);
      steps.cleanup();
      verify(profile).close();
      verifyNoMoreInteractions(profile);
    }
  }

  @Test
  void malformedCorrelationCannotReadGatewayEvidence() throws Exception {
    try (var config = configuration();
        var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var profiles = mockConstruction(NotificationTlsProfile.class)) {
      var steps = new NotificationTlsSteps();
      steps.prepare("own-key", "baseline");
      steps.selectProfile("good", "good");
      assertThrows(AssertionError.class, () -> steps.verifyConnection("old-id", "own-marker"));
      assertThrows(AssertionError.class,
          () -> steps.verifyConnection("12345678-1234-1234-1234-123456789012", ""));
      var profile = profiles.constructed().getFirst();
      verify(profile).variant("good", "good");
      steps.cleanup();
      verify(profile).close();
      verifyNoMoreInteractions(profile);
    }
  }

  @Test
  void bothRsCallsRunAndTheirFailuresRemainVisibleBeforeScenarioFails() throws Exception {
    var row = new ObjectMapper().createObjectNode().put("status", 401);
    try (var config = configuration();
        var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var reports = mockStatic(SerenityReportUtils.class);
        var profiles =
            mockConstruction(
                NotificationRsTlsProfile.class,
                (profile, context) -> {
                  when(profile.queryChannels(anyString(), anyString())).thenReturn(row);
                  when(profile.submitNotification(anyString(), anyString())).thenReturn(row);
                  when(profile.awaitAccessLogs(org.mockito.ArgumentMatchers.anyList()))
                      .thenReturn("");
                })) {
      var steps = new NotificationRsTlsSteps();
      steps.prepare("X123456789");
      steps.queryChannels();
      steps.submitNotification();
      steps.verify("getActiveChannels");
      steps.verify("submitNotification");
      var failure = assertThrows(AssertionError.class, SoftAssertionsContext::assertAll);
      assertTrue(failure.getMessage().contains("getActiveChannels"));
      assertTrue(failure.getMessage().contains("submitNotification"));
      steps.cleanup();
      var profile = profiles.constructed().getFirst();
      verify(profile).queryChannels(anyString(), anyString());
      verify(profile).submitNotification(anyString(), anyString());
      verify(profile).awaitAccessLogs(org.mockito.ArgumentMatchers.anyList());
      verify(profile).close();
    }
  }
}
