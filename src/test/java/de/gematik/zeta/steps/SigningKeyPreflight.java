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
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.zeta.JwtVariant;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import io.restassured.http.Method;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes the signing key required by JWT manipulation scenarios available and verifiable.
 */
@Slf4j
public class SigningKeyPreflight {

  private static final String SIGNING_KEY_PATH_CONFIG_KEY = "paths.guard.ecKeyFile";

  private final TigerProxyManipulationsSteps tigerProxyManipulationsSteps;
  private final TigerProxyAccessTokenExtractor accessTokenExtractor;
  private final JwtSteps jwtSteps;
  private final KeycloakSigningKeyResolver keycloakSigningKeyResolver;
  private final HsmSimulatorSigningKeyResolver hsmSimulatorSigningKeyResolver;

  /** Creates scenario steps when Cucumber instantiates this glue class through reflection. */
  @SuppressWarnings("unused")
  public SigningKeyPreflight() {
    this(new TigerProxyManipulationsSteps(), new TigerProxyAccessTokenExtractor(), new JwtSteps(),
        new KeycloakSigningKeyResolver(ZetaDeploymentConfiguration.getServiceInstance(), new JwtSteps()),
        new HsmSimulatorSigningKeyResolver(ZetaDeploymentConfiguration.getServiceInstance(), new JwtSteps()));
  }

  /**
   * Creates a signing-key preflight with its required collaborators.
   *
   * @param tigerProxyManipulationsSteps helper used to reset TigerProxy state
   * @param accessTokenExtractor reader for the freshly issued Access Token
   * @param jwtSteps JWT signature verification helper
   * @param keycloakSigningKeyResolver fallback resolver for the active Keycloak key
   * @param hsmSimulatorSigningKeyResolver fallback resolver for the configured test HSM simulator
   */
  public SigningKeyPreflight(
      final TigerProxyManipulationsSteps tigerProxyManipulationsSteps,
      final TigerProxyAccessTokenExtractor accessTokenExtractor,
      final JwtSteps jwtSteps,
      final KeycloakSigningKeyResolver keycloakSigningKeyResolver,
      final HsmSimulatorSigningKeyResolver hsmSimulatorSigningKeyResolver) {
    this.tigerProxyManipulationsSteps = tigerProxyManipulationsSteps;
    this.accessTokenExtractor = accessTokenExtractor;
    this.jwtSteps = jwtSteps;
    this.keycloakSigningKeyResolver = keycloakSigningKeyResolver;
    this.hsmSimulatorSigningKeyResolver = hsmSimulatorSigningKeyResolver;
  }

  /**
   * Verifies the configured signing key and automatically resolves a replacement when necessary.
   *
   * @throws AssertionError if no usable signing key can be made available
   */
  public void ensureAvailable() {
    var keyPath = resolveSigningKeyPath();
    ensureAvailableForToken(keyPath, issueFreshAccessToken());
  }

  /**
   * Prepares the signing key against the scenario's observed token without resetting its client or issuing another request.
   *
   * <p>The invalid-signature variant deliberately keeps its separately configured foreign key.
   * Other variants are rejected here so this step cannot silently skip a required key check.</p>
   *
   * @param variant requested signature behavior of the manipulation
   * @param accessToken unmodified Access Token captured at the scenario's protected endpoint
   */
  @Dann("stelle für JWT-Variante {jwtVariant} den Signaturschlüssel anhand des Access Tokens {tigerResolvedString} bereit")
  @Then("prepare the signing key for JWT variant {jwtVariant} using Access Token {tigerResolvedString}")
  public void ensureAvailableForAccessToken(final JwtVariant variant, final String accessToken) {
    if (variant == JwtVariant.INVALID_SIGNATURE) {
      return;
    }
    if (variant != JwtVariant.RESIGN_CORRECT_KEY) {
      throw new IllegalArgumentException("Signing-key preparation does not support JWT variant " + variant);
    }
    ensureAvailableForToken(resolveSigningKeyPath(), accessToken);
  }

  /**
   * Verifies the configured key against exactly the supplied token and resolves a matching replacement when necessary.
   *
   * @param keyPath destination of the private signing key used by the manipulation
   * @param accessToken observed, unmodified Access Token
   */
  private void ensureAvailableForToken(final Path keyPath, final String accessToken) {
    try {
      jwtSteps.verifyJwtSignatureWithPublicKeyFromPrivateKey(accessToken, readSigningKey(keyPath));
      log.info("Signing-key preflight passed with the configured key '{}'; no kubectl lookup is needed.", keyPath);
      return;
    } catch (AssertionError | RuntimeException e) {
      log.debug("Configured signing key '{}' cannot verify the fresh Access Token: {}", keyPath, e.getMessage());
    }

    log.warn("Configured signing key '{}' is missing or does not verify the Access Token; "
        + "trying the Keycloak database and then the HSM simulator.", keyPath);
    var resolvedSigningKey = resolveVerifiedSigningKey(accessToken);
    writeSigningKey(keyPath, resolvedSigningKey);
    jwtSteps.verifyJwtSignatureWithPublicKeyFromPrivateKey(accessToken, readSigningKey(keyPath));
    log.info("Resolved and verified the signing key at '{}'.", keyPath);
  }

  /**
   * Tries both remote sources in order, keeping retrieval separate from destination file writes.
   *
   * @param accessToken exact observed token whose signing key is required
   * @return a key verified against the supplied token
   */
  private String resolveVerifiedSigningKey(String accessToken) {
    String databaseFailure;
    try {
      var key = keycloakSigningKeyResolver.resolve(accessToken);
      jwtSteps.verifyJwtSignatureWithPublicKeyFromPrivateKey(accessToken, key);
      log.info("Signing-key resolution succeeded through the Keycloak database.");
      return key;
    } catch (AssertionError | RuntimeException e) {
      databaseFailure = e.getMessage();
      log.info("Keycloak database did not provide a verified signing key; trying the HSM simulator.");
    }
    try {
      var key = hsmSimulatorSigningKeyResolver.resolve(accessToken);
      jwtSteps.verifyJwtSignatureWithPublicKeyFromPrivateKey(accessToken, key);
      log.info("Signing-key resolution succeeded through the HSM simulator.");
      return key;
    } catch (AssertionError | RuntimeException e) {
      throw new AssertionError("Automatic signing-key resolution failed. Keycloak database: " + databaseFailure
          + "; HSM simulator: " + e.getMessage());
    }
  }

  /**
   * Resolves the configured path of the private signing key.
   *
   * @return configured signing-key path
   * @throws AssertionError if the path is missing or unresolved
   */
  private Path resolveSigningKeyPath() {
    return TigerGlobalConfiguration.readStringOptional(SIGNING_KEY_PATH_CONFIG_KEY)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .filter(path -> !path.isBlank() && !path.contains("${"))
        .map(Path::of)
        .orElseThrow(() -> new AssertionError("Signing key path is not configured."));
  }

  /**
   * Issues a fresh testclient request and extracts the Access Token received by the Guard.
   *
   * @return compact Access Token used for signing-key verification
   */
  private String issueFreshAccessToken() {
    var previousMessageIds = accessTokenExtractor.captureMessageIds();
    try {
      tigerProxyManipulationsSteps.resetTigerProxyStateIfAvailable();
      var httpGlueCode = new HttpGlueCode();
      httpGlueCode.sendEmptyRequest(Method.GET,
          new URI(TigerGlobalConfiguration.resolvePlaceholders("${paths.client.reset}")));
      httpGlueCode.sendEmptyRequest(Method.GET,
          new URI(TigerGlobalConfiguration.resolvePlaceholders("${paths.client.helloZeta}")));
    } catch (Exception e) {
      throw new AssertionError("Could not issue a fresh testclient request for signing key verification.", e);
    }

    var accessToken = accessTokenExtractor.extractLatestAccessToken(previousMessageIds);
    log.debug("Fresh Guard Access Token extracted for signing-key verification.");
    return accessToken;
  }

  /**
   * Reads and validates the configured PEM key before it is passed to the JWT glue code.
   *
   * @param keyPath configured signing-key path
   * @return PEM encoded signing key
   */
  private String readSigningKey(final Path keyPath) {
    if (!Files.isRegularFile(keyPath)) {
      throw new AssertionError("Signing key file does not exist: " + keyPath);
    }
    try {
      var signingKey = Files.readString(keyPath, StandardCharsets.UTF_8).trim();
      if (signingKey.isBlank() || signingKey.contains("<unset>")) {
        throw new AssertionError("Signing key file is empty or unset: " + keyPath);
      }
      return signingKey;
    } catch (IOException e) {
      throw new AssertionError("Could not read signing key file: " + keyPath, e);
    }
  }

  /**
   * Stores the resolved signing key at the configured path.
   *
   * @param keyPath configured signing-key path
   * @param signingKey PEM encoded signing key
   */
  private void writeSigningKey(final Path keyPath, final String signingKey) {
    try {
      var parent = keyPath.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(keyPath, signingKey, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new AssertionError("Could not store the resolved signing key at " + keyPath + ".", e);
    }
  }
}
