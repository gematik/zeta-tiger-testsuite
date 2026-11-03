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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.JwtVariant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies scenario-token key preparation without additional client or proxy traffic. */
class SigningKeyPreflightTest {

  private static final String KEY_PATH_CONFIG = "paths.guard.ecKeyFile";

  @TempDir
  Path temporaryDirectory;

  private Path keyPath;
  private String previousKeyPath;
  private HsmSimulatorSigningKeyResolver simulator;
  private String matchingKey;
  private String foreignKey;

  /**
   * Uses an isolated key destination and the existing cryptographic test fixtures.
   *
   * @throws IOException if a fixture cannot be read
   */
  @BeforeEach
  void setUp() throws IOException {
    previousKeyPath = TigerGlobalConfiguration.readStringOptional(KEY_PATH_CONFIG).orElse("");
    simulator = mock(HsmSimulatorSigningKeyResolver.class);
    keyPath = temporaryDirectory.resolve("signing-key.pem");
    TigerGlobalConfiguration.putValue(KEY_PATH_CONFIG, keyPath.toString(), ConfigurationValuePrecedence.TEST_CONTEXT);
    matchingKey = Files.readString(Path.of("src/test/resources/keys/popp-token-server_ecKey.pem"));
    foreignKey = Files.readString(Path.of("src/test/resources/keys/popp-token-foreign_ecKey.pem"));
  }

  /** Restores the shared configuration after each test. */
  @AfterEach
  void tearDown() {
    TigerGlobalConfiguration.putValue(KEY_PATH_CONFIG, previousKeyPath, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Proves that the captured token is sufficient when the configured key matches.
   *
   * @throws Exception if key storage or fixture signing fails
   */
  @Test
  void usesExistingKeyWithoutResetOrTokenRequest() throws Exception {
    Files.writeString(keyPath, matchingKey);
    var resolver = new RecordingResolver(foreignKey);
    var token = JwtTestHelper.createSignedJwt(matchingKey);

    preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token);

    assertThat(resolver.observedToken).isNull();
    assertThat(Files.readString(keyPath)).isEqualTo(matchingKey);
    verifyNoInteractions(simulator);
  }

  /**
   * Proves that missing and stale files resolve a key for this exact scenario token.
   *
   * @param staleKey whether an unrelated key already exists
   * @throws Exception if key storage or fixture signing fails
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void resolvesMatchingKeyForTheSuppliedToken(boolean staleKey) throws Exception {
    if (staleKey) {
      Files.writeString(keyPath, foreignKey);
    }
    var resolver = new RecordingResolver(matchingKey);
    var token = JwtTestHelper.createSignedJwt(matchingKey);

    preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token);

    assertThat(resolver.observedToken).isEqualTo(token);
    new JwtSteps().verifyJwtSignatureWithPublicKeyFromPrivateKey(token, Files.readString(keyPath));
    verifyNoInteractions(simulator);
  }

  /**
   * Rejects a fallback that supplies a key for another token instead of accepting an unrelated valid key.
   *
   * @throws Exception if fixture signing fails
   */
  @Test
  void rejectsFallbackKeyForAnotherToken() throws Exception {
    Files.writeString(keyPath, foreignKey);
    var resolver = new RecordingResolver(foreignKey);
    var token = JwtTestHelper.createSignedJwt(matchingKey);

    assertThatThrownBy(() -> preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("JWT signature must verify");
    assertThat(resolver.observedToken).isEqualTo(token);
    assertThat(Files.readString(keyPath)).isEqualTo(foreignKey);
  }

  /**
   * Falls back from an unrelated database key and checks the exact supplied token before storing the simulator key.
   *
   * @throws Exception if fixture signing or file access fails
   */
  @Test
  void resolvesFromSimulatorAfterDatabaseKeyMismatch() throws Exception {
    var resolver = new RecordingResolver(foreignKey);
    var token = JwtTestHelper.createSignedJwt(matchingKey);
    when(simulator.resolve(token)).thenAnswer(invocation -> {
      assertThat(resolver.observedToken).isEqualTo(token);
      return matchingKey;
    });

    preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token);

    verify(simulator).resolve(token);
    assertThat(resolver.observedToken).isEqualTo(token);
    assertThat(Files.readString(keyPath)).isEqualTo(matchingKey);
  }

  /**
   * A local write failure after successful resolution must not trigger a different remote lookup.
   *
   * @throws Exception if fixture signing or directory creation fails
   */
  @Test
  void doesNotTrySimulatorOnDestinationWriteFailure() throws Exception {
    Files.createDirectory(keyPath);
    var resolver = new RecordingResolver(matchingKey);
    var token = JwtTestHelper.createSignedJwt(matchingKey);

    assertThatThrownBy(() -> preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Could not store the resolved signing key");

    verifyNoInteractions(simulator);
    assertThat(keyPath).isDirectory();
  }

  /**
   * Neither remote mismatch can overwrite a pre-existing local key.
   *
   * @throws Exception if fixture signing or file access fails
   */
  @Test
  void rejectsSimulatorMismatchBeforeWriting() throws Exception {
    Files.writeString(keyPath, foreignKey);
    var resolver = new RecordingResolver(foreignKey);
    var token = JwtTestHelper.createSignedJwt(matchingKey);
    when(simulator.resolve(token)).thenReturn(foreignKey);

    assertThatThrownBy(() -> preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token))
        .isInstanceOf(AssertionError.class).hasMessageContaining("JWT signature must verify");

    assertThat(Files.readString(keyPath)).isEqualTo(foreignKey);
    assertThat(resolver.observedToken).isEqualTo(token);
  }

  /**
   * Database access or lookup errors still allow a matching simulator key to be used.
   *
   * @param databaseError database failure reason
   * @throws Exception if fixture signing or file access fails
   */
  @ParameterizedTest
  @ValueSource(strings = {"No matching database key", "kubectl database access denied"})
  void resolvesFromSimulatorAfterDatabaseFailure(String databaseError) throws Exception {
    var resolver = new RecordingResolver(null);
    resolver.failure = new AssertionError(databaseError);
    var token = JwtTestHelper.createSignedJwt(matchingKey);
    when(simulator.resolve(token)).thenReturn(matchingKey);

    preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token);

    assertThat(resolver.observedToken).isEqualTo(token);
    verify(simulator).resolve(token);
    assertThat(Files.readString(keyPath)).isEqualTo(matchingKey);
  }

  /**
   * Both failed lookups remain diagnosable without changing or creating a key file.
   *
   * @param existingFile whether the destination already holds an unrelated key
   * @throws Exception if fixture signing or file access fails
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reportsBothLookupFailures(boolean existingFile) throws Exception {
    if (existingFile) {
      Files.writeString(keyPath, foreignKey);
    }
    var resolver = new RecordingResolver(null);
    resolver.failure = new IllegalStateException("database unavailable");
    var token = JwtTestHelper.createSignedJwt(matchingKey);
    when(simulator.resolve(token)).thenThrow(new AssertionError("simulator key file missing"));

    assertThatThrownBy(() -> preflight(resolver).ensureAvailableForAccessToken(JwtVariant.RESIGN_CORRECT_KEY, token))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Keycloak database: database unavailable")
        .hasMessageContaining("HSM simulator: simulator key file missing");

    assertThat(resolver.observedToken).isEqualTo(token);
    verify(simulator).resolve(token);
    if (existingFile) {
      assertThat(Files.readString(keyPath)).isEqualTo(foreignKey);
    } else {
      assertThat(keyPath).doesNotExist();
    }
  }

  /**
   * Keeps the intentional invalid-signature branch independent of a valid Authorization Server private key.
   *
   * @throws IOException if the fixture cannot be stored
   */
  @Test
  void preservesForeignKeyForInvalidSignature() throws IOException {
    Files.writeString(keyPath, foreignKey);
    var resolver = new RecordingResolver(matchingKey);

    preflight(resolver).ensureAvailableForAccessToken(JwtVariant.INVALID_SIGNATURE, "unused-token");

    assertThat(resolver.observedToken).isNull();
    assertThat(Files.readString(keyPath)).isEqualTo(foreignKey);
    verifyNoInteractions(simulator);
  }

  /** Rejects unsupported variants instead of silently omitting their key prerequisites. */
  @Test
  void rejectsUnsupportedVariants() {
    assertThatThrownBy(() -> preflight(new RecordingResolver(matchingKey))
        .ensureAvailableForAccessToken(JwtVariant.ALG_NONE, "unused-token"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Builds a preflight whose HTTP collaborators reject any reset or second token acquisition.
   *
   * @param resolver fallback whose input token is recorded
   * @return preflight for the scenario-token path
   */
  private SigningKeyPreflight preflight(RecordingResolver resolver) {
    var proxy = mock(TigerProxyManipulationsSteps.class, invocation -> {
      throw new AssertionError("Scenario-token preparation must not call proxy control.");
    });
    var extractor = new TigerProxyAccessTokenExtractor() {
      /**
       * Rejects a second token acquisition before it can issue any client request.
       *
       * @return never returns a remote history
       */
      @Override
      public Set<String> captureMessageIds() {
        throw new AssertionError("Scenario-token preparation must not issue another authentication request.");
      }
    };
    return new SigningKeyPreflight(proxy, extractor, new JwtSteps(), resolver, simulator);
  }

  /** Records the exact token used for fallback without connecting to a deployment. */
  private static final class RecordingResolver extends KeycloakSigningKeyResolver {

    private final String result;
    private String observedToken;
    private Throwable failure;

    /**
     * Configures the returned fixture without a deployment service.
     *
     * @param result fixture private key
     */
    private RecordingResolver(String result) {
      super(null, new JwtSteps());
      this.result = result;
    }

    /**
     * Records the supplied token and returns the controlled key.
     *
     * @param accessToken scenario token whose signature must be checked
     * @return configured fixture key
     */
    @Override
    public String resolve(String accessToken) {
      observedToken = accessToken;
      if (failure instanceof AssertionError assertionError) {
        throw assertionError;
      }
      if (failure instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      return result;
    }
  }
}
