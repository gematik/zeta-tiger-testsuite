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
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;

/** Reads an exportable test simulator key, never a production HSM key. */
public class HsmSimulatorSigningKeyResolver {

  private static final String CONFIG_PREFIX = "zetaDeploymentConfig.keycloak.signingKey.hsmSimulator.";

  private final ZetaDeploymentModificationService deploymentService;
  private final JwtSteps jwtSteps;

  /**
   * Creates a resolver using the existing bounded, non-verbose kubectl transport.
   *
   * @param deploymentService Kubernetes command service
   * @param jwtSteps key normalization and token signature verification
   */
  public HsmSimulatorSigningKeyResolver(ZetaDeploymentModificationService deploymentService, JwtSteps jwtSteps) {
    this.deploymentService = deploymentService;
    this.jwtSteps = jwtSteps;
  }

  /**
   * Reads the configured simulator PEM and proves that it signed the supplied unmodified token.
   *
   * @param accessToken observed Access Token, not a token obtained from another client context
   * @return verified PEM key
   * @throws AssertionError if retrieval, parsing or signature verification fails
   */
  public String resolve(String accessToken) {
    var deployment = readConfig("deploymentName");
    var container = readConfig("containerName");
    var keyFile = readConfig("keyFile");
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    CommandResult result;
    try {
      result = deploymentService.executeKubectlCommand(false,
          "-n", namespace, "exec", "deployment/" + deployment, "-c", container,
          "--", "cat", "--", keyFile);
    } catch (AssertionError | RuntimeException e) {
      // Do not attach transport exceptions or command output to reports containing key material.
      throw new AssertionError("HSM simulator signing-key retrieval failed while executing kubectl.");
    }
    if (result == null || result.exitCode() != 0) {
      throw new AssertionError("HSM simulator signing-key retrieval failed; check deployment, container, "
          + "file and kubectl permissions (exit code: " + (result == null ? "unavailable" : result.exitCode()) + ").");
    }
    String key;
    try {
      key = jwtSteps.normalizePrivateKeyPem(result.stdout());
      jwtSteps.verifyJwtSignatureWithPublicKeyFromPrivateKey(accessToken, key);
    } catch (AssertionError | RuntimeException e) {
      throw new AssertionError("HSM simulator signing key is invalid or does not verify the observed Access Token.");
    }
    return key;
  }

  /**
   * Requires deployment settings without assuming a production HSM exposes files.
   *
   * @param name simulator setting name
   * @return resolved setting
   */
  private String readConfig(String name) {
    return TigerGlobalConfiguration.readStringOptional(CONFIG_PREFIX + name)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .filter(value -> !value.isBlank() && !value.contains("${"))
        .orElseThrow(() -> new AssertionError("Missing HSM simulator signing-key configuration: " + name));
  }
}
