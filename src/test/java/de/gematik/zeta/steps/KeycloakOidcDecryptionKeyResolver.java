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
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.bouncycastle.jce.ECNamedCurveTable;

/**
 * Resolves and verifies the Keycloak ECDH key used to decrypt sectoral-IDP {@code id_token}s.
 */
public class KeycloakOidcDecryptionKeyResolver {

  private static final String CONFIG_PREFIX = "zetaDeploymentConfig.keycloak.";

  private final ZetaDeploymentModificationService deploymentModificationService;

  /**
   * Creates a resolver backed by the supplied deployment service.
   *
   * @param deploymentModificationService service used to execute read-only kubectl commands
   */
  public KeycloakOidcDecryptionKeyResolver(
      final ZetaDeploymentModificationService deploymentModificationService) {
    this.deploymentModificationService = deploymentModificationService;
  }

  /**
   * Resolves the unique OIDC decryption key and verifies it against its stored public key.
   *
   * @return verified private key together with its Keycloak component ID
   * @throws AssertionError if lookup, parsing, uniqueness, or key-pair verification fails
   */
  public ResolvedOidcDecryptionKey resolve() {
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    var databasePodResult = deploymentModificationService.executeKubectlCommand(false,
        "-n", namespace,
        "get", "pod",
        "-l", readConfig("databasePodSelector"),
        "-o", "name",
        "--request-timeout=" + readConfig("kubectlRequestTimeout"));
    requireSuccessfulCommand(databasePodResult, "locating the Keycloak database pod");
    var databasePod = databasePodResult.stdout().lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "kubectl returned no value while locating the Keycloak database pod."));

    var query = """
        select c.id, private_key.value, public_key.value
        from component c
        join realm r on r.id = c.realm_id
        join component_config private_key on private_key.component_id = c.id
          and private_key.name = '%s'
        join component_config public_key on public_key.component_id = c.id
          and public_key.name = '%s'
        where r.name = '%s' and c.provider_id = '%s';
        """.formatted(
        readConfig("oidcDecryptionKey.privateKeyConfigName"),
        readConfig("oidcDecryptionKey.publicKeyConfigName"),
        readConfig("realmName"),
        readConfig("oidcDecryptionKey.providerId"));
    var keyResult = deploymentModificationService.executeKubectlCommand(false,
        "-n", namespace,
        "exec", databasePod,
        "-c", readConfig("databaseContainerName"),
        "--request-timeout=" + readConfig("kubectlRequestTimeout"),
        "--", "psql",
        "-d", readConfig("databaseName"),
        "-At", "-F|", "-c", query);
    requireSuccessfulCommand(keyResult, "reading the Keycloak OIDC decryption key");

    var rows = keyResult.stdout().lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .toList();
    if (rows.size() != 1) {
      throw new AssertionError("Expected exactly one Keycloak OIDC decryption key, found "
          + rows.size() + ".");
    }

    var columns = rows.getFirst().split("\\|", 3);
    if (columns.length != 3) {
      throw new AssertionError("Keycloak returned an unexpected OIDC decryption-key row.");
    }
    var privateKey = parsePrivateKey(columns[1]);
    var publicKey = parsePublicKey(columns[2]);
    if (!derivePublicKey(privateKey).equals(publicKey)) {
      throw new AssertionError("Keycloak OIDC private and public key do not form one key pair.");
    }
    return new ResolvedOidcDecryptionKey(columns[0], privateKey);
  }

  /**
   * Reads one required resolver setting.
   *
   * @param settingName configuration leaf name
   * @return resolved nonblank setting
   */
  private String readConfig(final String settingName) {
    return TigerGlobalConfiguration.readStringOptional(CONFIG_PREFIX + settingName)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .filter(value -> !value.isBlank() && !value.contains("${"))
        .orElseThrow(() -> new AssertionError(
            "Missing Keycloak OIDC decryption-key configuration: " + settingName));
  }

  /**
   * Parses one raw PKCS#8 EC private key returned by Keycloak.
   *
   * @param encodedKey Base64-encoded PKCS#8 key
   * @return parsed EC private key
   */
  private ECPrivateKey parsePrivateKey(final String encodedKey) {
    try {
      var key = KeyFactory.getInstance("EC").generatePrivate(
          new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedKey.trim())));
      if (key instanceof ECPrivateKey ecPrivateKey) {
        return ecPrivateKey;
      }
      throw new AssertionError("Keycloak OIDC decryption key is not an EC private key.");
    } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
      throw new AssertionError("Could not parse the Keycloak OIDC private key.", e);
    }
  }

  /**
   * Parses one raw X.509 EC public key returned by Keycloak.
   *
   * @param encodedKey Base64-encoded SubjectPublicKeyInfo
   * @return parsed EC public key
   */
  private ECPublicKey parsePublicKey(final String encodedKey) {
    try {
      var key = KeyFactory.getInstance("EC").generatePublic(
          new X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey.trim())));
      if (key instanceof ECPublicKey ecPublicKey) {
        return ecPublicKey;
      }
      throw new AssertionError("Keycloak OIDC encryption key is not an EC public key.");
    } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
      throw new AssertionError("Could not parse the Keycloak OIDC public key.", e);
    }
  }

  /**
   * Derives the P-256 public key corresponding to the supplied private key.
   *
   * @param privateKey EC private key
   * @return derived EC public key
   */
  private ECPublicKey derivePublicKey(final ECPrivateKey privateKey) {
    var curveSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
    if (curveSpec == null) {
      throw new AssertionError("P-256 curve parameters are unavailable.");
    }
    var point = curveSpec.getG().multiply(privateKey.getS()).normalize();
    var publicPoint = new java.security.spec.ECPoint(
        point.getAffineXCoord().toBigInteger(), point.getAffineYCoord().toBigInteger());
    try {
      return (ECPublicKey) KeyFactory.getInstance("EC")
          .generatePublic(new ECPublicKeySpec(publicPoint, privateKey.getParams()));
    } catch (java.security.GeneralSecurityException e) {
      throw new AssertionError("Could not derive the Keycloak OIDC public key.", e);
    }
  }

  /**
   * Fails when a kubectl command did not complete successfully.
   *
   * @param result kubectl command result
   * @param operation operation description used in the failure message
   */
  private void requireSuccessfulCommand(final CommandResult result, final String operation) {
    if (result == null || result.exitCode() != 0) {
      var details = result == null ? "no command result" : result.stderr().trim();
      throw new AssertionError("kubectl failed while " + operation + ": " + details);
    }
  }

  /**
   * Verified OIDC decryption key together with its stable Keycloak component identifier.
   *
   * @param keyId Keycloak component identifier
   * @param privateKey private ECDH key
   */
  public record ResolvedOidcDecryptionKey(String keyId, ECPrivateKey privateKey) {
  }
}
