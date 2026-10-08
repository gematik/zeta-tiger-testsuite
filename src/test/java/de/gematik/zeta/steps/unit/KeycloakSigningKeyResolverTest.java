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
import de.gematik.zeta.steps.JwtSteps;
import de.gematik.zeta.steps.JwtTestHelper;
import de.gematik.zeta.steps.KeycloakSigningKeyResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KeycloakSigningKeyResolver}.
 */
public class KeycloakSigningKeyResolverTest {

  /**
   * Verifies that exactly one matching key is selected when the database contains two candidates.
   *
   * @throws Exception if the test key fixture cannot be loaded or signed
   */
  @Test
  void selectsMatchingCandidateAmongTwoKeys() throws Exception {
    configureSigningKeyLookup();
    var matchingKeyPem = readKeyFixture("src/test/resources/keys/popp-token-server_ecKey.pem");
    var nonMatchingKeyPem = readKeyFixture("src/test/resources/keys/zeta-staging.spree.de-ecKey.pem");
    var accessToken = JwtTestHelper.createSignedJwt(matchingKeyPem);
    var deploymentService = new FakeSigningKeyDeploymentModificationService(
        "wrong-component|" + toRawPkcs8Base64(nonMatchingKeyPem) + "\n"
            + "matching-component|" + toRawPkcs8Base64(matchingKeyPem) + "\n");
    var resolver = new KeycloakSigningKeyResolver(deploymentService, new JwtSteps());

    var resolvedKey = resolver.resolve(accessToken);

    assertEquals("-----BEGIN PRIVATE KEY-----\n" + toRawPkcs8Base64(matchingKeyPem)
        + "\n-----END PRIVATE KEY-----", resolvedKey);
    assertEquals(2, deploymentService.kubectlCommandCount);
  }

  /**
   * Verifies that two candidates validating the same token are treated as ambiguous.
   *
   * @throws Exception if the test key fixture cannot be loaded or signed
   */
  @Test
  void rejectsTwoMatchingCandidates() throws Exception {
    configureSigningKeyLookup();
    var matchingKeyPem = readKeyFixture("src/test/resources/keys/popp-token-server_ecKey.pem");
    var accessToken = JwtTestHelper.createSignedJwt(matchingKeyPem);
    var rawMatchingKey = toRawPkcs8Base64(matchingKeyPem);
    var deploymentService = new FakeSigningKeyDeploymentModificationService(
        "matching-component-a|" + rawMatchingKey + "\n"
            + "matching-component-b|" + rawMatchingKey + "\n");
    var resolver = new KeycloakSigningKeyResolver(deploymentService, new JwtSteps());

    var exception = assertThrows(AssertionError.class, () -> resolver.resolve(accessToken));

    assertEquals("Expected exactly one active Keycloak signing key matching the fresh Access Token, found 2.",
        exception.getMessage());
  }

  /**
   * Verifies that no candidate validating the token is rejected instead of being selected arbitrarily.
   *
   * @throws Exception if the test key fixture cannot be loaded or signed
   */
  @Test
  void rejectsWhenNoCandidateMatches() throws Exception {
    configureSigningKeyLookup();
    var matchingKeyPem = readKeyFixture("src/test/resources/keys/popp-token-server_ecKey.pem");
    var nonMatchingKeyPem = readKeyFixture("src/test/resources/keys/zeta-staging.spree.de-ecKey.pem");
    var accessToken = JwtTestHelper.createSignedJwt(matchingKeyPem);
    var deploymentService = new FakeSigningKeyDeploymentModificationService(
        "wrong-component|" + toRawPkcs8Base64(nonMatchingKeyPem) + "\n");
    var resolver = new KeycloakSigningKeyResolver(deploymentService, new JwtSteps());

    var exception = assertThrows(AssertionError.class, () -> resolver.resolve(accessToken));

    assertEquals("Expected exactly one active Keycloak signing key matching the fresh Access Token, found 0.",
        exception.getMessage());
  }

  /**
   * Configures the deployment values consumed by the Keycloak signing-key lookup.
   */
  private void configureSigningKeyLookup() {
    var precedence = ConfigurationValuePrecedence.TEST_CONTEXT;
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.namespace", "zeta-local", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.databaseName", "keycloak", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.databasePodSelector",
        "cnpg.io/cluster=keycloak-db,role=primary", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.databaseContainerName",
        "postgres", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.realmName", "zeta-guard", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.signingKey.providerId",
        "ecdsa-generated", precedence);
    TigerGlobalConfiguration.putValue("zetaDeploymentConfig.keycloak.kubectlRequestTimeout",
        "1s", precedence);
  }

  /**
   * Reads one PEM test fixture.
   *
   * @param path fixture path
   * @return PEM content
   * @throws IOException if the fixture cannot be read
   */
  private String readKeyFixture(final String path) throws IOException {
    return Files.readString(Path.of(path), StandardCharsets.UTF_8);
  }

  /**
   * Converts a PKCS#8 PEM fixture to the single-line form returned by the database query.
   *
   * @param privateKeyPem PEM encoded private key
   * @return Base64-encoded PKCS#8 content without PEM delimiters
   */
  private String toRawPkcs8Base64(final String privateKeyPem) {
    return privateKeyPem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
  }

  /**
   * Fake deployment service returning deterministic Keycloak pod and database responses.
   */
  private static final class FakeSigningKeyDeploymentModificationService extends ZetaDeploymentModificationService {

    private final String signingKeyRows;
    private int kubectlCommandCount;

    /**
     * Creates a fake service with the supplied database rows.
     *
     * @param signingKeyRows pipe-delimited component ID and key rows
     */
    private FakeSigningKeyDeploymentModificationService(final String signingKeyRows) {
      super(1, 1);
      this.signingKeyRows = signingKeyRows;
    }

    /**
     * Returns a fake primary pod for the first command and the configured rows for the database query.
     *
     * @param verbose whether command output should be logged
     * @param arguments kubectl arguments
     * @return deterministic command result
     */
    @Override
    public CommandResult executeKubectlCommand(final boolean verbose, final String... arguments) {
      kubectlCommandCount++;
      var stdout = kubectlCommandCount == 1 ? "pod/keycloak-db-0\n" : signingKeyRows;
      return new CommandResult(List.of("kubectl"), 0, stdout, "");
    }
  }
}
