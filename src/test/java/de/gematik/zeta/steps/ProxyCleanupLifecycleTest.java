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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.TestDriverConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

class ProxyCleanupLifecycleTest {
  /** Clears the scenario-abort marker after each isolated lifecycle check. */
  @AfterEach
  void clearScenarioState() {
    Hooks.clearScenarioAborted();
  }

  @Test
  void unconditionalAfterHookResetsManipulationsBeforeDeploymentRestoration() throws Exception {
    var reset = Hooks.class.getMethod("resetTigerProxyState").getAnnotation(After.class);
    var restore =
        Hooks.class
            .getMethod("restoreDeploymentModifications", Scenario.class)
            .getAnnotation(After.class);
    assertEquals("", reset.value());
    assertTrue(reset.order() > restore.order()); // Cucumber runs After hooks in descending order.
    var manipulations = mock(TigerProxyManipulationsSteps.class);
    var hooks =
        new Hooks(
            mock(ZetaDeploymentModificationService.class),
            manipulations,
            mock(TestDriverConfigurationService.class));
    hooks.resetTigerProxyState();
    verify(manipulations).resetTigerProxyStateIfAvailable();
    verifyNoMoreInteractions(manipulations);
  }

  /**
   * Keeps message clearing after the proxy guard and before driver setup and preflight traffic.
   *
   * @throws NoSuchMethodException if a lifecycle hook changes its signature
   */
  @Test
  void standaloneResetRunsAfterProxyGuardBeforeScenarioSetup() throws NoSuchMethodException {
    var guard = Hooks.class.getMethod("skipIfProxyMissing", Scenario.class)
        .getAnnotation(Before.class).order();
    var clear = Hooks.class.getMethod("clearRecordedMessages", Scenario.class)
        .getAnnotation(Before.class).order();
    var driver = Hooks.class.getMethod("verifyOidcProfile")
        .getAnnotation(Before.class).order();
    assertTrue(guard < clear);
    assertTrue(clear < driver);
    assertNull(Hooks.class.getMethod("clearRecordedMessages", Scenario.class)
        .getAnnotation(After.class));
  }

  /** Requires one acknowledged standalone reset before clearing the local assertion history. */
  @Test
  void proxyScenarioResetsStandaloneBeforeLocalHistory() {
    var manipulations = mock(TigerProxyManipulationsSteps.class);
    var local = mock(RbelMessageRetriever.class);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@oidc"));
    try (var retriever = mockStatic(RbelMessageRetriever.class)) {
      retriever.when(RbelMessageRetriever::getInstance).thenReturn(local);
      hooks(manipulations).clearRecordedMessages(scenario);
      var order = inOrder(manipulations, local);
      order.verify(manipulations).resetTigerProxyMessagesStrictly();
      order.verify(local).clearRbelMessages();
      order.verifyNoMoreInteractions();
    }
  }

  /** Keeps no-proxy scenarios independent of the standalone message reset. */
  @Test
  void noProxyScenarioOnlyClearsLocalHistory() {
    var manipulations = mock(TigerProxyManipulationsSteps.class);
    var local = mock(RbelMessageRetriever.class);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@no_proxy"));
    try (var retriever = mockStatic(RbelMessageRetriever.class)) {
      retriever.when(RbelMessageRetriever::getInstance).thenReturn(local);
      hooks(manipulations).clearRecordedMessages(scenario);
      verifyNoInteractions(manipulations);
      verify(local).clearRbelMessages();
    }
  }

  /** Propagates a failed standalone reset instead of treating a local clear as successful setup. */
  @Test
  void failedStandaloneResetFailsTheBeforeHook() {
    var manipulations = mock(TigerProxyManipulationsSteps.class);
    var local = mock(RbelMessageRetriever.class);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of());
    var failure = new AssertionError("standalone reset rejected");
    doThrow(failure).when(manipulations).resetTigerProxyMessagesStrictly();
    try (var retriever = mockStatic(RbelMessageRetriever.class)) {
      retriever.when(RbelMessageRetriever::getInstance).thenReturn(local);
      assertSame(failure, assertThrows(AssertionError.class,
          () -> hooks(manipulations).clearRecordedMessages(scenario)));
      verifyNoInteractions(local);
    }
  }

  /** Does not contact the standalone proxy after an earlier guard has aborted the scenario. */
  @Test
  void abortedScenarioOnlyClearsLocalHistory() {
    var manipulations = mock(TigerProxyManipulationsSteps.class);
    var local = mock(RbelMessageRetriever.class);
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of());
    var hooks = hooks(manipulations);
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var retriever = mockStatic(RbelMessageRetriever.class)) {
      config.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.empty());
      assertThrows(TestAbortedException.class, () -> hooks.skipIfProxyMissing(scenario));
      retriever.when(RbelMessageRetriever::getInstance).thenReturn(local);
      hooks.clearRecordedMessages(scenario);
      verifyNoInteractions(manipulations);
      verify(local).clearRbelMessages();
    }
  }

  /**
   * Creates isolated lifecycle hooks with the supplied standalone proxy operations.
   *
   * @param manipulations standalone proxy operations
   * @return lifecycle hooks backed by mocked deployment and driver services
   */
  private Hooks hooks(TigerProxyManipulationsSteps manipulations) {
    return new Hooks(mock(ZetaDeploymentModificationService.class), manipulations,
        mock(TestDriverConfigurationService.class));
  }
}
