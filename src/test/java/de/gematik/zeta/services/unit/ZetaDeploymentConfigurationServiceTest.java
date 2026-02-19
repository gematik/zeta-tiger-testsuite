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

package de.gematik.zeta.services.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.services.model.KubectlPatchCommandResult;
import de.gematik.zeta.services.model.ZetaAslToggleResult;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaDisableAslRequest;
import de.gematik.zeta.services.model.ZetaEnableAslRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Unit tests for ZetaDeploymentConfigurationService.
 */
public class ZetaDeploymentConfigurationServiceTest {

  private static final ZetaDeploymentDetails details = new ZetaDeploymentDetails(
      "zeta-local",
      "pep-deployment",
      "pep-test-nginx-conf",
      new String[]{"data", "nginx.conf"},
      "pep-well-known",
      new String[]{"data", "oauth-protected-resource"}
  );

  private final ZetaDeploymentConfigurationService service = new ZetaDeploymentConfigurationService(60, 120);

  @Test
  @Ignore
  public void verifyRequirements() {
    final String namespace = "zeta-local";
    service.verifyRequirements(namespace);
    Assert.assertThrows(AssertionError.class, () -> service.verifyRequirements("this-namespace-very-probably-does-not-exist"));
  }

  @Test
  @Ignore
  public void testConfigQuery() {
    CommandResult result =
        service.executeKubectlCommand("-n", "zeta-local", "get", "cm", "pep-test-nginx-conf", "-o", "yaml");

    assertTrue(result.stdout().contains("app.kubernetes.io/component: pep"));
    assertTrue(result.stderr().isBlank());
  }

  @Test
  @Ignore
  public void testConfigNonExistent() {
    CommandResult result =
        service.executeKubectlCommand("-n", "zeta-local", "get", "cm", "this-resource-does-not-exist", "-o", "yaml");

    assertFalse(result.stderr().isBlank());
  }

  @Test
  @Ignore
  public void restartPod() throws TimeoutException, InterruptedException {
    // wait for pod readiness
    service.restartPod("zeta-local", "pep-deployment", true, 120);
  }

  @Test
  @Ignore
  public void toggleAsl() throws TimeoutException, InterruptedException, IOException {
    final String nginxAslConfig =
        """
                    location /ASL {
                      pep                   on;
                      asl                   on;
                      pep_require_aud       https://zeta-kind.local;
                      # pep_require_scope     "";
                      # pep_leeway           60; # s
                    }\
        """;

    final String nginxAslRegex = "location\\s+/ASL\\s+\\{[^}]*}";
    final String wellKnownAslRegex = "\"zeta_asl_use\"\\s*:\\s*\"[^\"]+\"\\s*";

    final ZetaEnableAslRequest enableAslRequest = new ZetaEnableAslRequest(nginxAslRegex,
        "location\\s+/.well-known/\\s+\\{[^}]*}",
        nginxAslConfig,
        wellKnownAslRegex,
        "\"zeta_asl_use\":\"required\"");

    final ZetaDisableAslRequest disableAslRequest = new ZetaDisableAslRequest(nginxAslRegex,
        wellKnownAslRegex,
        "\"zeta_asl_use\":\"not_supported\"");

    ZetaAslToggleResult enableResult = service.enableAsl(details, enableAslRequest);
    assertEquals(0, enableResult.pepNginxResult().commandResult().exitCode());
    assertEquals(0, enableResult.pepWellKnownResult().commandResult().exitCode());

    ZetaAslToggleResult disableResult = service.disableAsl(details, disableAslRequest);
    assertEquals(0, disableResult.pepNginxResult().commandResult().exitCode());
    assertEquals(0, disableResult.pepWellKnownResult().commandResult().exitCode());
  }

  @Test
  @Ignore
  public void togglePoppVerification() throws IOException, InterruptedException, TimeoutException {

    ZetaPoppTokenToggleRequest request = new ZetaPoppTokenToggleRequest(
        "pep_require_popp\\s+(on|off)\\s*;",
        "pep_require_popp      on;",
        "pep_require_popp      off;"
    );

    // modify existing key
    KubectlPatchCommandResult disableResult = service.disablePoppVerification(details, request, "/pep/");
    assertTrue(disableResult.commandResult().exitCode() == 0);

    KubectlPatchCommandResult enableResult = service.enablePoppVerification(details, request, "/pep/");
    assertTrue(enableResult.commandResult().exitCode() == 0);

    // modify non-existing key
    disableResult = service.disablePoppVerification(details, request, "/");
    assertTrue(disableResult.commandResult().exitCode() == 0);

    enableResult = service.enablePoppVerification(details, request, "/");
    assertTrue(enableResult.commandResult().exitCode() == 0);
  }

  @Test
  @Ignore
  public void configMapBackup() throws IOException {
    final String namespace = "zeta-local";
    final String cmName = "pep-test-nginx-conf";

    CommandResult backupResult = service.createConfigMapBackup(namespace, cmName);
    assertEquals(0, backupResult.exitCode());

    CommandResult restoreResult = service.restoreConfigMapBackup(namespace, cmName);
    assertEquals(0, restoreResult.exitCode());

    CommandResult deleteResult = service.deleteConfigMapBackup(namespace, cmName);
    assertEquals(0, deleteResult.exitCode());
  }
}
