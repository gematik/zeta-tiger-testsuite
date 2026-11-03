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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies deployment restart failure handling and rollout ordering. */
class DeploymentRestartStepsTest {

  private final ZetaDeploymentModificationService service = mock(ZetaDeploymentModificationService.class);
  private final DeploymentModificationSteps steps = new DeploymentModificationSteps(service);

  /** Configures the target namespace and deployment modification permission. */
  @BeforeEach
  void configure() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /** Clears configuration written by each test. */
  @AfterEach
  void clearConfiguration() {
    TigerGlobalConfiguration.clearTestVariables();
  }

  @Test
  void waitsForReplacementRolloutAfterSuccessfulRestart() {
    when(service.executeKubectlCommand("-n", "zeta-local", "rollout", "restart", "deployment/authserver"))
        .thenReturn(result(0, ""));
    when(service.getPodReadyTimeoutSeconds()).thenReturn(45);
    when(service.waitForDeploymentRollout("zeta-local", "authserver", 45)).thenReturn(result(0, ""));

    steps.restartDeployment("authserver");

    var order = inOrder(service);
    order.verify(service).executeKubectlCommand("-n", "zeta-local", "rollout", "restart", "deployment/authserver");
    order.verify(service).getPodReadyTimeoutSeconds();
    order.verify(service).waitForDeploymentRollout("zeta-local", "authserver", 45);
    verifyNoMoreInteractions(service);
  }

  @Test
  void rejectsRestartWhenDeploymentModificationIsDisabled() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "false",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var error = assertThrows(AssertionError.class, () -> steps.restartDeployment("authserver"));

    assertEquals("Deployment modification is not allowed, won't execute step", error.getMessage());
    assertNull(error.getCause());

    verifyNoInteractions(service);
  }

  @Test
  void rejectsMissingNamespaceBeforeRestart() {
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);

    var error = assertThrows(AssertionError.class, () -> steps.restartDeployment("authserver"));

    assertEquals("Deployment restart requires a namespace and deployment name", error.getMessage());
    assertNull(error.getCause());

    verifyNoInteractions(service);
  }

  @Test
  void failedRestartDoesNotWaitForExistingReadyPods() {
    when(service.executeKubectlCommand("-n", "zeta-local", "rollout", "restart", "deployment/authserver"))
        .thenReturn(result(1, "restart denied"));

    var error = assertThrows(AssertionError.class, () -> steps.restartDeployment("authserver"));

    assertEquals("Deployment restart failed for authserver: restart denied", error.getMessage());
    assertNull(error.getCause());
    var order = inOrder(service);
    order.verify(service).executeKubectlCommand("-n", "zeta-local", "rollout", "restart", "deployment/authserver");
    verifyNoMoreInteractions(service);
  }

  @Test
  void failedReplacementRolloutFailsTheStep() {
    when(service.executeKubectlCommand("-n", "zeta-local", "rollout", "restart", "deployment/authserver"))
        .thenReturn(result(0, ""));
    when(service.getPodReadyTimeoutSeconds()).thenReturn(45);
    when(service.waitForDeploymentRollout("zeta-local", "authserver", 45))
        .thenReturn(result(1, "rollout timed out"));

    var error = assertThrows(AssertionError.class, () -> steps.restartDeployment("authserver"));

    assertEquals("Deployment restart rollout failed for authserver: rollout timed out", error.getMessage());
    assertNull(error.getCause());
  }

  /** Verifies that execution errors retain their cause and prerequisite classification. */
  @Test
  void commandExecutionFailureIsAnUnmetPrerequisite() {
    var cause = new IllegalStateException("kubectl unavailable");
    when(service.executeKubectlCommand("-n", "zeta-local", "rollout", "restart", "deployment/authserver"))
        .thenThrow(cause);

    var error = assertThrows(AssertionError.class, () -> steps.restartDeployment("authserver"));

    assertTrue(error.getMessage().startsWith("Test prerequisite not fulfilled:"));
    assertTrue(error.getMessage().contains("kubectl unavailable"));
    assertSame(cause, error.getCause());
    var order = inOrder(service);
    order.verify(service).executeKubectlCommand("-n", "zeta-local", "rollout", "restart", "deployment/authserver");
    verifyNoMoreInteractions(service);
  }

  /**
   * Creates a captured command result.
   *
   * @param exitCode process exit status
   * @param error diagnostic text
   * @return command result with empty standard output
   */
  private static CommandResult result(int exitCode, String error) {
    return new CommandResult(List.of("kubectl"), exitCode, "", error);
  }
}
