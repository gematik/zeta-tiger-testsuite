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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the simulator boundary with real key parsing and signature verification. */
class HsmSimulatorSigningKeyResolverTest {

  private final Map<String, String> previousValues = new HashMap<>();
  private final RecordingService service = new RecordingService();
  private final HsmSimulatorSigningKeyResolver resolver = new HsmSimulatorSigningKeyResolver(service, new JwtSteps());
  private String matchingKey;
  private String token;

  /**
   * Configures an isolated deployment and signs the observed token using a fixed test key.
   *
   * @throws Exception if key loading or signing fails
   */
  @BeforeEach
  void setUp() throws Exception {
    configure("namespace", "simulator-test");
    configure("keycloak.signingKey.hsmSimulator.deploymentName", "test-hsm");
    configure("keycloak.signingKey.hsmSimulator.containerName", "simulator");
    configure("keycloak.signingKey.hsmSimulator.keyFile", "/keys/token.p256.key.pem");
    matchingKey = Files.readString(Path.of("src/test/resources/keys/popp-token-server_ecKey.pem"));
    token = JwtTestHelper.createSignedJwt(matchingKey);
  }

  /** Restores shared Tiger configuration after each test. */
  @AfterEach
  void tearDown() {
    previousValues.forEach((key, value) ->
        TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT));
  }

  /** Proves the matching key is read with non-verbose argument-vector execution. */
  @Test
  void readsAndVerifiesMatchingKeyWithoutLoggingOutput() {
    service.result = new CommandResult(List.of(), 0, matchingKey, "");

    var key = resolver.resolve(token);

    new JwtSteps().verifyJwtSignatureWithPublicKeyFromPrivateKey(token, key);
    assertThat(service.verbose).isFalse();
    assertThat(service.arguments).containsExactly("-n", "simulator-test", "exec", "deployment/test-hsm",
        "-c", "simulator", "--", "cat", "--", "/keys/token.p256.key.pem");
  }

  /**
   * An unrelated private key is rejected even though it is valid PEM.
   *
   * @throws Exception if the foreign fixture cannot be read
   */
  @Test
  void rejectsWrongKeyWithoutExposingIt() throws Exception {
    var foreignKey = Files.readString(Path.of("src/test/resources/keys/popp-token-foreign_ecKey.pem"));
    service.result = new CommandResult(List.of(), 0, foreignKey, "");

    assertThatThrownBy(() -> resolver.resolve(token)).isInstanceOf(AssertionError.class)
        .hasMessage("HSM simulator signing key is invalid or does not verify the observed Access Token.")
        .hasNoCause();
  }

  /**
   * Empty or malformed PEM cannot reach the destination key file or leak into error messages.
   *
   * @param output malformed command output
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-private-key", "-----BEGIN PRIVATE KEY-----\nsecret-invalid-material\n-----END PRIVATE KEY-----"})
  void rejectsInvalidKeyMaterial(String output) {
    service.result = new CommandResult(List.of(), 0, output, "");

    assertThatThrownBy(() -> resolver.resolve(token)).isInstanceOf(AssertionError.class)
        .hasMessage("HSM simulator signing key is invalid or does not verify the observed Access Token.")
        .hasNoCause();
  }

  /**
   * File-not-found, permission and unavailable-pod failures do not expose raw command output.
   *
   * @param exitCode failing command status
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 126, 137})
  void rejectsFailedRetrievalWithoutLeakingOutput(int exitCode) {
    service.result = new CommandResult(List.of(), exitCode, matchingKey, "sensitive-error-output");

    assertThatThrownBy(() -> resolver.resolve(token)).isInstanceOf(AssertionError.class)
        .hasMessageContaining("retrieval failed").hasMessageContaining("exit code: " + exitCode)
        .hasMessageNotContaining("sensitive-error-output").hasMessageNotContaining("BEGIN PRIVATE KEY")
        .hasNoCause();
  }

  /** Transport timeouts stay distinguishable from cryptographic mismatches. */
  @Test
  void sanitizesTransportFailure() {
    service.failure = new AssertionError("sensitive-transport-output");

    assertThatThrownBy(() -> resolver.resolve(token)).isInstanceOf(AssertionError.class)
        .hasMessage("HSM simulator signing-key retrieval failed while executing kubectl.").hasNoCause();
  }

  /** A missing path is rejected before any Kubernetes command. */
  @Test
  void rejectsMissingConfiguration() {
    configure("keycloak.signingKey.hsmSimulator.keyFile", "${missingKeyFile}");

    assertThatThrownBy(() -> resolver.resolve(token)).isInstanceOf(AssertionError.class)
        .hasMessageContaining("Missing HSM simulator signing-key configuration: keyFile");
    assertThat(service.arguments).isNull();
  }

  /**
   * Saves and overrides one deployment setting.
   *
   * @param suffix setting below zetaDeploymentConfig
   * @param value isolated test value
   */
  private void configure(String suffix, String value) {
    var key = "zetaDeploymentConfig." + suffix;
    previousValues.putIfAbsent(key, TigerGlobalConfiguration.readStringOptional(key).orElse(""));
    TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /** Captures command arguments without touching a cluster. */
  private static final class RecordingService extends ZetaDeploymentModificationService {
    private CommandResult result;
    private AssertionError failure;
    private boolean verbose;
    private String[] arguments;

    /** Creates a bounded fake transport. */
    private RecordingService() {
      super(1, 1);
    }

    /**
     * Records the requested transport mode and returns controlled output.
     *
     * @param verbose whether command output may be logged
     * @param arguments kubectl arguments
     * @return controlled command result
     */
    @Override
    public CommandResult executeKubectlCommand(boolean verbose, String... arguments) {
      this.verbose = verbose;
      this.arguments = arguments;
      if (failure != null) {
        throw failure;
      }
      return result;
    }
  }
}
