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

import de.gematik.test.tiger.lib.TigerDirector;
import java.security.interfaces.ECPrivateKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the PDP Authorization Server's OIDC decryption key into the local RBEL parser.
 */
@Slf4j
public class OidcDecryptionKeyPreflight {

  private static final ThreadLocal<ECPrivateKey> CURRENT_KEY = new ThreadLocal<>();

  private final KeycloakOidcDecryptionKeyResolver keyResolver;

  /**
   * Creates a preflight backed by the supplied Keycloak resolver.
   *
   * @param keyResolver resolver for the active OIDC decryption key
   */
  public OidcDecryptionKeyPreflight(final KeycloakOidcDecryptionKeyResolver keyResolver) {
    this.keyResolver = keyResolver;
  }

  /**
   * Resolves, verifies, and registers the active OIDC decryption key with RBEL.
   */
  public void ensureAvailable() {
    CURRENT_KEY.remove();
    var resolvedKey = keyResolver.resolve();
    var localTigerProxy = TigerDirector.getTigerTestEnvMgr().getLocalTigerProxyOrFail();
    localTigerProxy.addKey(resolvedKey.keyId(), resolvedKey.privateKey());
    var registeredKey = localTigerProxy.getRbelLogger()
        .getRbelKeyManager()
        .findKeyByName(resolvedKey.keyId())
        .orElseThrow(() -> new AssertionError(
            "RBEL did not retain the verified Keycloak OIDC decryption key."));
    if (!registeredKey.getKey().equals(resolvedKey.privateKey())) {
      throw new AssertionError("RBEL retained an unexpected OIDC decryption key.");
    }
    CURRENT_KEY.set(resolvedKey.privateKey());
    log.info("Registered verified Keycloak OIDC decryption key '{}' with RBEL.",
        resolvedKey.keyId());
  }

  /**
   * Returns the verified OIDC decryption key for the current scenario.
   *
   * @return scenario-local private key
   */
  static ECPrivateKey currentKey() {
    var key = CURRENT_KEY.get();
    if (key == null) {
      throw new AssertionError("No verified OIDC decryption key is available for this scenario.");
    }
    return key;
  }

  /**
   * Removes the scenario-local OIDC decryption key.
   */
  static void clear() {
    CURRENT_KEY.remove();
  }
}
