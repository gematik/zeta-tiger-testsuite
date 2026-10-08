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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.steps.unit.HooksTest;
import io.cucumber.java.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class HooksConfigMapBackupTest {

  /**
   * Verifies that stale cluster backups are ignored when the current scenario did not capture them.
   */
  @Test
  void restoreDeploymentModificationsIgnoresBackupsNotCapturedByScenario() {
    configureDeploymentRestore();

    var service = new FakeDeploymentModificationService();
    var hooks = new Hooks(service, new TigerProxyManipulationsSteps(), new HooksTest.FakeTestDriverConfigurationService());
    var scenario = deploymentModificationScenario();

    hooks.prepareSoftAssertions();
    hooks.restoreDeploymentModifications(scenario);

    assertTrue(service.restoredConfigMaps.isEmpty());
    assertTrue(service.deletedConfigMapBackups.isEmpty());
    assertEquals(0, service.restartCount);
  }

  /**
   * Verifies that a scenario-local ConfigMap backup is restored and removed during cleanup.
   */
  @Test
  void restoreDeploymentModificationsRestoresCapturedBackupAndDeletesIt() {
    configureDeploymentRestore();

    var service = new FakeDeploymentModificationService();
    var hooks = new Hooks(service, new TigerProxyManipulationsSteps(), new HooksTest.FakeTestDriverConfigurationService());
    var scenario = deploymentModificationScenario();

    hooks.prepareSoftAssertions();
    Hooks.rememberConfigMapBackupIfAbsent("pep-test-nginx-conf");
    hooks.restoreDeploymentModifications(scenario);

    assertEquals(List.of("pep-test-nginx-conf"), service.restoredConfigMaps);
    assertEquals(List.of("pep-test-nginx-conf"), service.deletedConfigMapBackups);
    assertEquals(1, service.restartCount);
  }

  /**
   * Verifies that final scenario lifecycle cleanup removes remembered ConfigMap backup ownership.
   */
  @Test
  void clearScenarioLifecycleStateClearsCapturedConfigMapBackups() {
    var hooks = new Hooks(new FakeDeploymentModificationService(), new TigerProxyManipulationsSteps(), new HooksTest.FakeTestDriverConfigurationService());

    hooks.prepareSoftAssertions();
    Hooks.rememberConfigMapBackupIfAbsent("pep-test-nginx-conf");

    assertTrue(Hooks.getCapturedConfigMapBackups().contains("pep-test-nginx-conf"));

    hooks.clearScenarioLifecycleState();

    assertTrue(Hooks.getCapturedConfigMapBackups().isEmpty());
  }

  /**
   * Writes the minimal Tiger configuration required for deployment restore tests.
   */
  private void configureDeploymentRestore() {
    TigerGlobalConfiguration.putValue("allow_deployment_modification", "true",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.configMapName", "pep-test-nginx-conf",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.podName", "pep-deployment",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.nginx.containerName", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.pep.image.versionUpdate", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("paths.client.reset", "%",
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Creates a fake Cucumber scenario tagged for deployment modification.
   *
   * @return deployment modification scenario
   */
  private Scenario deploymentModificationScenario() {
    var scenario = mock(Scenario.class);
    when(scenario.getSourceTagNames()).thenReturn(Set.of("@deployment_modification"));
    return scenario;
  }

  private static final class FakeDeploymentModificationService extends ZetaDeploymentModificationService {

    private final List<String> restoredConfigMaps = new ArrayList<>();
    private final List<String> deletedConfigMapBackups = new ArrayList<>();
    private int restartCount;

    /**
     * Creates a fake deployment modification service with short timeouts.
     */
    private FakeDeploymentModificationService() {
      super(1, 1);
    }

    /**
     * Reports all backup lookups as present so tests can prove scenario-local filtering.
     */
    @Override
    public boolean hasConfigMapBackup(String namespace, String configMapName) {
      return true;
    }

    /**
     * Records the ConfigMap restored by cleanup.
     */
    @Override
    public CommandResult restoreConfigMapBackup(String namespace, String configMapName) {
      restoredConfigMaps.add(configMapName);
      return new CommandResult(List.of("kubectl"), 0, "", "");
    }

    /**
     * Records the backup removed after successful restore.
     */
    @Override
    public CommandResult deleteConfigMapBackup(String namespace, String configMapName) {
      deletedConfigMapBackups.add(configMapName);
      return new CommandResult(List.of("kubectl"), 0, "", "");
    }

    /**
     * Records that cleanup requested a PEP restart.
     */
    @Override
    public void restartPod(String namespace, String podName, boolean requireReady, int timeout)
        throws InterruptedException, TimeoutException {
      restartCount++;
    }
  }
}
