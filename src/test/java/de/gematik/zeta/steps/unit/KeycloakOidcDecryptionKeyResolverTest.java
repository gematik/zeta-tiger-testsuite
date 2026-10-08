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

package de.gematik.zeta.steps.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.steps.KeycloakOidcDecryptionKeyResolver;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KeycloakOidcDecryptionKeyResolver}.
 */
public class KeycloakOidcDecryptionKeyResolverTest {

  /**
   * Verifies that a matching Keycloak ECDH key pair is accepted.
   *
   * @throws Exception if the EC test key cannot be generated
   */
  @Test
  void resolvesVerifiedEcdhKeyPair() throws Exception {
    configureLookup();
    var keyPair = generateKeyPair();
    var service = new FakeDeploymentModificationService(keyRow("ecdh-component", keyPair));

    var resolved = new KeycloakOidcDecryptionKeyResolver(service).resolve();

    assertEquals("ecdh-component", resolved.keyId());
    assertEquals(keyPair.getPrivate(), resolved.privateKey());
    assertEquals(2, service.kubectlCommandCount);
  }

  /**
   * Verifies that an unrelated public key makes the preflight fail.
   *
   * @throws Exception if the EC test keys cannot be generated
   */
  @Test
  void rejectsMismatchingPublicKey() throws Exception {
    configureLookup();
    var privateKeyPair = generateKeyPair();
    var publicKeyPair = generateKeyPair();
    var row = "ecdh-component|"
        + Base64.getEncoder().encodeToString(privateKeyPair.getPrivate().getEncoded()) + "|"
        + Base64.getEncoder().encodeToString(publicKeyPair.getPublic().getEncoded()) + "\n";
    var service = new FakeDeploymentModificationService(row);

    var error = assertThrows(AssertionError.class,
        () -> new KeycloakOidcDecryptionKeyResolver(service).resolve());

    assertEquals("Keycloak OIDC private and public key do not form one key pair.", error.getMessage());
  }

  /**
   * Configures all resolver values consumed by the test.
   */
  private void configureLookup() {
    var precedence = ConfigurationValuePrecedence.TEST_CONTEXT;
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.databaseName",
        "keycloak", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.databasePodSelector",
        "cnpg.io/cluster=keycloak-db,role=primary", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.databaseContainerName",
        "postgres", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.realmName",
        "zeta-guard", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.oidcDecryptionKey.providerId",
        "ecdh-generated", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.oidcDecryptionKey.privateKeyConfigName",
        "ecdhPrivateKey", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.oidcDecryptionKey.publicKeyConfigName",
        "ecdhPublicKey", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.kubectlRequestTimeout",
        "1s", precedence);
  }

  /**
   * Generates one P-256 key pair for resolver tests.
   *
   * @return generated EC key pair
   * @throws Exception if the platform cannot generate P-256 keys
   */
  private KeyPair generateKeyPair() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  /**
   * Serializes one key pair in the database row format consumed by the resolver.
   *
   * @param componentId Keycloak component ID
   * @param keyPair EC key pair
   * @return pipe-delimited database row
   */
  private String keyRow(final String componentId, final KeyPair keyPair) {
    return componentId + "|"
        + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()) + "|"
        + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()) + "\n";
  }

  /**
   * Fake deployment service returning deterministic pod and key-query results.
   */
  private static final class FakeDeploymentModificationService
      extends ZetaDeploymentModificationService {

    private final String keyRow;
    private int kubectlCommandCount;

    /**
     * Creates a fake service returning the supplied key row.
     *
     * @param keyRow pipe-delimited Keycloak key row
     */
    private FakeDeploymentModificationService(final String keyRow) {
      super(1, 1);
      this.keyRow = keyRow;
    }

    /**
     * Returns the fake database pod first and the configured key row second.
     *
     * @param verbose whether command output should be logged
     * @param arguments ignored kubectl arguments
     * @return deterministic command result
     */
    @Override
    public CommandResult executeKubectlCommand(final boolean verbose, final String... arguments) {
      kubectlCommandCount++;
      var stdout = kubectlCommandCount == 1 ? "pod/keycloak-db-0\n" : keyRow;
      return new CommandResult(List.of("kubectl"), 0, stdout, "");
    }
  }
}
