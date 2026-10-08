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
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the active Keycloak ECDSA signing key through the configured database pod.
 */
@Slf4j
public class KeycloakSigningKeyResolver {

  private static final String KEYCLOAK_CONFIG_PREFIX = "zetaDeploymentConfig.keycloak.";

  private final ZetaDeploymentModificationService deploymentModificationService;
  private final JwtSteps jwtSteps;

  /**
   * Creates a resolver backed by the supplied deployment and JWT services.
   *
   * @param deploymentModificationService service used to execute kubectl commands
   * @param jwtSteps JWT verification and PEM normalization helper
   */
  public KeycloakSigningKeyResolver(
      final ZetaDeploymentModificationService deploymentModificationService,
      final JwtSteps jwtSteps) {
    this.deploymentModificationService = deploymentModificationService;
    this.jwtSteps = jwtSteps;
  }

  /**
   * Resolves the one active Keycloak signing key that verifies the supplied Access Token.
   *
   * <p>The database query selects only enabled and active {@code ecdsa-generated} components.
   * Each returned private key is then tested against the exact token issued by the Guard.
   * Ambiguous results are rejected instead of selecting an arbitrary candidate.</p>
   *
   * @param accessToken fresh Access Token issued by the Guard
   * @return PEM encoded private key matching the fresh Access Token
   * @throws AssertionError if kubectl fails or the number of matching keys is not one
   */
  public String resolve(final String accessToken) {
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    var databaseName = readSigningKeyConfig("databaseName");
    var databasePodSelector = readSigningKeyConfig("databasePodSelector");
    var databaseContainerName = readSigningKeyConfig("databaseContainerName");
    var realmName = readSigningKeyConfig("realmName");
    var providerId = readSigningKeyConfig("signingKey.providerId");
    var kubectlRequestTimeout = readSigningKeyConfig("kubectlRequestTimeout");
    log.info("Looking up the active Keycloak signing key in namespace '{}'.", namespace);

    var databasePodResult = deploymentModificationService.executeKubectlCommand(false,
        "-n", namespace,
        "get", "pod",
        "-l", databasePodSelector,
        "-o", "name",
        "--request-timeout=" + kubectlRequestTimeout);
    requireSuccessfulCommand(databasePodResult, "locating the Keycloak database pod");
    var databasePod = databasePodResult.stdout().lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .findFirst()
        .orElseThrow(() -> new AssertionError("kubectl returned no value while locating the Keycloak database pod."));
    var query = """
        select c.id, private_key.value
        from component c
        join realm r on r.id = c.realm_id
        join component_config active on active.component_id = c.id
          and active.name = 'active' and active.value = 'true'
        join component_config enabled on enabled.component_id = c.id
          and enabled.name = 'enabled' and enabled.value = 'true'
        join component_config private_key on private_key.component_id = c.id
          and private_key.name = 'ecdsaPrivateKey'
        where r.name = '%s' and c.provider_id = '%s';
        """.formatted(realmName, providerId);
    var keyResult = deploymentModificationService.executeKubectlCommand(false,
        "-n", namespace,
        "exec", databasePod,
        "-c", databaseContainerName,
        "--request-timeout=" + kubectlRequestTimeout,
        "--", "psql",
        "-d", databaseName,
        "-At", "-F|", "-c", query);
    requireSuccessfulCommand(keyResult, "reading active Keycloak signing keys");

    var matchingKeys = new HashMap<String, String>();
    for (var line : keyResult.stdout().lines().toList()) {
      var columns = line.split("\\|", 2);
      if (columns.length != 2) {
        log.warn("Ignoring an unexpected Keycloak signing-key row returned by psql.");
        continue;
      }
      var componentId = columns[0].trim();
      try {
        var candidateKey = columns[1].trim();
        jwtSteps.verifyJwtSignatureWithPublicKeyFromPrivateKey(accessToken, candidateKey);
        matchingKeys.put(componentId, jwtSteps.normalizePrivateKeyPem(candidateKey));
        log.info("Keycloak signing-key candidate '{}' verifies the fresh Access Token.", componentId);
      } catch (AssertionError | RuntimeException e) {
        log.warn("Ignoring unusable Keycloak signing-key candidate '{}'.", componentId, e);
      }
    }

    if (matchingKeys.size() != 1) {
      throw new AssertionError("Expected exactly one active Keycloak signing key matching the fresh Access Token, found "
          + matchingKeys.size() + ".");
    }
    return matchingKeys.values().iterator().next();
  }

  /**
   * Reads one deployment-specific Keycloak signing-key setting from Tiger configuration.
   *
   * @param settingName leaf name below {@code zetaDeploymentConfig.keycloak}
   * @return configured setting
   * @throws AssertionError if the setting is missing, blank, or unresolved
   */
  private String readSigningKeyConfig(final String settingName) {
    return TigerGlobalConfiguration.readStringOptional(KEYCLOAK_CONFIG_PREFIX + settingName)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .filter(value -> !value.isBlank() && !value.contains("${"))
        .orElseThrow(() -> new AssertionError("Missing Keycloak signing-key configuration: " + settingName));
  }

  /**
   * Fails when a kubectl command did not complete successfully.
   *
   * @param result kubectl command result
   * @param operation description used in failure messages
   */
  private void requireSuccessfulCommand(final CommandResult result, final String operation) {
    if (result == null || result.exitCode() != 0) {
      var details = result == null ? "no command result" : result.stderr().trim();
      throw new AssertionError("kubectl failed while " + operation + ": " + details);
    }
  }
}
