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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestCertificateManifestService;
import de.gematik.zeta.services.TestCertificateManifestService.TestCertificateEntry;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Enumeration;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for consuming certificate assets from the {@code zeta-test-certificates} repo.
 */
@Slf4j
public class TestCertificateSteps {

  private final TestCertificateManifestService manifestService;

  /**
   * Create the step definitions with the default manifest service.
   */
  public TestCertificateSteps() {
    this(new TestCertificateManifestService());
  }

  /**
   * Visible for tests so a manifest service can be injected.
   *
   * @param manifestService manifest service to use
   */
  TestCertificateSteps(TestCertificateManifestService manifestService) {
    this.manifestService = manifestService;
  }

  /**
   * Load one certificate entry by one-based index and publish its metadata, file paths, and
   * Base64-safe inline values into Tiger variables under the given prefix.
   *
   * @param oneBasedIndex one-based manifest row index excluding the header
   * @param variablePrefix Tiger variable prefix
   * @throws IOException on I/O errors
   */
  @Dann("lade Zertifikat-Eintrag Nummer {int} aus dem Testzertifikat-Manifest in Variablen mit Präfix {tigerResolvedString}")
  @Then("load certificate entry number {int} from the test certificate manifest into variables with prefix {tigerResolvedString}")
  public void loadCertificateEntryByIndex(int oneBasedIndex, String variablePrefix)
      throws IOException {
    var manifestPath = manifestService.resolveManifestPath(null);
    var entry = manifestService.findByIndex(manifestPath, oneBasedIndex);
    publishEntry(variablePrefix, entry);
  }

  /**
   * Load one certificate entry by stem and publish its metadata, file paths, and Base64-safe
   * inline values into Tiger variables under the given prefix.
   *
   * @param stem manifest stem
   * @param variablePrefix Tiger variable prefix
   * @throws IOException on I/O errors
   */
  @Dann("lade Zertifikat mit Stem {tigerResolvedString} aus dem Testzertifikat-Manifest in Variablen mit Präfix {tigerResolvedString}")
  @Then("load certificate with stem {tigerResolvedString} from the test certificate manifest into variables with prefix {tigerResolvedString}")
  public void loadCertificateEntryByStem(String stem, String variablePrefix) throws IOException {
    var manifestPath = manifestService.resolveManifestPath(null);
    var entry = manifestService.findByStem(manifestPath, stem);
    publishEntry(variablePrefix, entry);
  }

  /**
   * Load the SMC-B identity that is configured for the current ZETA deployment and publish its
   * certificate and private key into Tiger variables under the given prefix.
   *
   * <p>The Kind deployment receives this key material through the configured
   * {@code zeta_k8s_smb_keystore_file} and {@code zeta_k8s_smb_keystore_password_file} values. The
   * step intentionally does not fall back to local helper folders so CI failures expose missing
   * deployment configuration directly.</p>
   *
   * @param variablePrefix Tiger variable prefix
   */
  @Dann("lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix {tigerResolvedString}")
  @Then("load SMC-B keystore of the ZETA deployment into variables with prefix {tigerResolvedString}")
  public void loadDeploymentSmcbKeystore(String variablePrefix) {
    var keyStorePath = resolveDeploymentFile("zeta_k8s_smb_keystore_file");
    var passwordPath = resolveDeploymentFile("zeta_k8s_smb_keystore_password_file");
    publishSmcbKeystore(variablePrefix, keyStorePath, passwordPath, "Deployment SMC-B keystore");
  }

  /**
   * Load a configurable number of certificate entries and publish them into indexed Tiger
   * variables under the given prefix.
   *
   * @param count number of entries to load
   * @param startOneBased one-based start index excluding the header
   * @param variablePrefix Tiger variable prefix
   * @throws IOException on I/O errors
   */
  @Dann("lade {int} Zertifikat-Einträge ab Nummer {int} aus dem Testzertifikat-Manifest in Variablen mit Präfix {tigerResolvedString}")
  @Then("load {int} certificate entries starting at number {int} from the test certificate manifest into variables with prefix {tigerResolvedString}")
  public void loadCertificateEntries(int count, int startOneBased, String variablePrefix)
      throws IOException {
    var manifestPath = manifestService.resolveManifestPath(null);
    var entries = manifestService.findRange(manifestPath, startOneBased, count);

    put(variablePrefix + ".count", Integer.toString(entries.size()));
    put(variablePrefix + ".start_index", Integer.toString(startOneBased));

    for (int i = 0; i < entries.size(); i++) {
      var entry = entries.get(i);
      var indexedPrefix = variablePrefix + "." + (i + 1);
      put(indexedPrefix + ".manifest_index", Integer.toString(startOneBased + i));
      publishEntry(indexedPrefix, entry);
    }

    log.info("Loaded {} test certificate entries from {} into Tiger variable prefix '{}'", count,
        manifestPath, variablePrefix);
  }

  /**
   * Export a one-based manifest slice as TSV with resolved absolute paths and inline keystore
   * values. This is intended for external tooling configured with a tab delimiter.
   *
   * @param startOneBased one-based start index excluding the header
   * @param count number of entries to export
   * @param outputPath output file path
   * @throws IOException on I/O errors
   */
  @Dann("exportiere {int} Zertifikat-Einträge ab Nummer {int} aus dem Testzertifikat-Manifest nach {tigerResolvedString}")
  @Then("export {int} certificate entries starting at number {int} from the test certificate manifest to {tigerResolvedString}")
  public void exportCertificateEntries(int count, int startOneBased, String outputPath)
      throws IOException {
    var manifestPath = manifestService.resolveManifestPath(null);
    var writtenPath = manifestService.exportTsvSlice(manifestPath, startOneBased, count,
        Path.of(outputPath));

    TigerGlobalConfiguration.putValue("test_certificates.last_export_path", writtenPath.toString(),
        ConfigurationValuePrecedence.TEST_CONTEXT);
    log.info("Exported {} test certificate entries from {} to {}", count, manifestPath,
        writtenPath);
  }

  /**
   * Publish one manifest entry into Tiger variables under the provided prefix.
   *
   * @param variablePrefix target variable prefix
   * @param entry manifest entry to publish
   * @throws IOException on I/O errors while reading certificate assets
   */
  private void publishEntry(String variablePrefix, TestCertificateEntry entry) throws IOException {
    put(variablePrefix + ".stem", entry.stem());
    put(variablePrefix + ".crt_path", entry.crtPath().toString());
    put(variablePrefix + ".prv_path", entry.prvPath().toString());
    put(variablePrefix + ".pub_path", entry.pubPath().toString());
    put(variablePrefix + ".keystore_b64_path", entry.keystoreB64Path().toString());
    put(variablePrefix + ".crt_b64", entry.crtBase64());
    put(variablePrefix + ".prv_b64", entry.prvBase64());
    put(variablePrefix + ".prv_pem", pemEncode("PRIVATE KEY", loadPrivateKey(entry).getEncoded()));
    put(variablePrefix + ".pub_b64", entry.pubBase64());
    put(variablePrefix + ".keystore_b64", entry.keystoreB64Content());
    put(variablePrefix + ".keystore_password", entry.keystorePassword());
    put(variablePrefix + ".keystore_alias", entry.keystoreAlias());
    put(variablePrefix + ".store_type", entry.storeType());

    log.info("Loaded test certificate entry {} into Tiger variable prefix '{}'", entry.stem(),
        variablePrefix);
  }

  /**
   * Publish certificate and private key from one SMC-B PKCS#12 keystore.
   *
   * @param variablePrefix target Tiger variable prefix
   * @param keyStorePath path to a Base64-encoded PKCS#12 keystore file
   * @param passwordPath path to the plain text keystore password file
   * @param sourceDescription source description for assertion diagnostics
   */
  private void publishSmcbKeystore(
      String variablePrefix, Path keyStorePath, Path passwordPath, String sourceDescription) {
    try {
      var password = Files.readString(passwordPath, StandardCharsets.UTF_8).trim();
      var passwordChars = password.toCharArray();
      var keyStore = loadKeyStore(
          "PKCS12",
          Files.readString(keyStorePath, StandardCharsets.UTF_8),
          passwordChars);

      var alias = findPrivateKeyAlias(keyStore);
      var key = keyStore.getKey(alias, passwordChars);
      if (!(key instanceof PrivateKey privateKey)) {
        throw new AssertionError(
            sourceDescription + " alias '" + alias + "' does not contain a private key.");
      }
      var certificateChain = keyStore.getCertificateChain(alias);
      final var leafCertificate = requireX509Certificate(
          certificateChain != null && certificateChain.length > 0
              ? certificateChain[0]
              : keyStore.getCertificate(alias),
          sourceDescription + " alias '" + alias + "'");

      put(variablePrefix + ".stem", alias);
      put(variablePrefix + ".crt_path", keyStorePath.toString());
      put(variablePrefix + ".keystore_b64_path", keyStorePath.toString());
      put(variablePrefix + ".crt_b64", Base64.getEncoder()
          .encodeToString(leafCertificate.getEncoded()));
      put(variablePrefix + ".prv_pem", pemEncode("PRIVATE KEY", privateKey.getEncoded()));
      put(variablePrefix + ".keystore_password", password);
      put(variablePrefix + ".keystore_alias", alias);
      put(variablePrefix + ".store_type", "PKCS12");
      if (certificateChain != null && certificateChain.length > 1) {
        var issuerCertificate = requireX509Certificate(certificateChain[1],
            "Issuer certificate for " + sourceDescription + " alias '" + alias + "'");
        put(variablePrefix + ".issuer_crt_b64", Base64.getEncoder()
            .encodeToString(issuerCertificate.getEncoded()));
      } else if (leafCertificate.getSubjectX500Principal()
          .equals(leafCertificate.getIssuerX500Principal())) {
        put(variablePrefix + ".issuer_crt_b64", Base64.getEncoder()
            .encodeToString(leafCertificate.getEncoded()));
      } else {
        throw new AssertionError(
            sourceDescription + " alias '" + alias + "' contains a single non-self-signed certificate"
                + " but no issuer certificate is available in the keystore chain.");
      }

      log.info("Loaded {} alias {} into Tiger variable prefix '{}'",
          sourceDescription, alias, variablePrefix);
    } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
      throw new AssertionError(
          "Failed to load " + sourceDescription + " from " + keyStorePath + ".", e);
    }
  }

  /**
   * Require that a keystore certificate entry is an X.509 certificate.
   *
   * @param certificate keystore certificate entry
   * @param description certificate description for assertion diagnostics
   * @return X.509 certificate
   */
  private X509Certificate requireX509Certificate(Certificate certificate, String description) {
    if (certificate instanceof X509Certificate x509Certificate) {
      return x509Certificate;
    }
    throw new AssertionError(description + " does not contain an X.509 certificate.");
  }

  /**
   * Loads a Base64-encoded Java keystore.
   *
   * @param storeType keystore type, for example {@code PKCS12}
   * @param keyStoreBase64 Base64-encoded keystore payload
   * @param password keystore password
   * @return loaded keystore
   * @throws GeneralSecurityException if keystore parsing fails
   * @throws IOException if the keystore cannot be loaded
   */
  private KeyStore loadKeyStore(String storeType, String keyStoreBase64, char[] password)
      throws GeneralSecurityException, IOException {
    var keyStoreBytes = Base64.getDecoder().decode(keyStoreBase64.replaceAll("\\s", ""));
    var keyStore = KeyStore.getInstance(storeType);
    try (var input = new ByteArrayInputStream(keyStoreBytes)) {
      keyStore.load(input, password);
    }
    return keyStore;
  }

  /**
   * Resolve a configured file path and fail when it does not point to an existing regular file.
   *
   * @param configKey Tiger configuration key containing the preferred file path
   * @return normalized absolute path to an existing file
   */
  private Path resolveDeploymentFile(String configKey) {
    var configuredValue = TigerGlobalConfiguration.readStringOptional(configKey)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .orElseThrow(() -> new AssertionError(
            "Deployment file configuration " + configKey + " must be set."));

    var resolvedPath = Path.of(configuredValue).toAbsolutePath().normalize();
    if (!Files.isRegularFile(resolvedPath)) {
      throw new AssertionError(
          "Configured deployment file for " + configKey + " does not exist: " + resolvedPath);
    }
    return resolvedPath;
  }

  /**
   * Find the first keystore alias that contains a private key.
   *
   * @param keyStore loaded keystore
   * @return alias containing a key entry
   * @throws GeneralSecurityException if keystore alias lookup fails
   */
  private String findPrivateKeyAlias(KeyStore keyStore) throws GeneralSecurityException {
    Enumeration<String> aliases = keyStore.aliases();
    while (aliases.hasMoreElements()) {
      var alias = aliases.nextElement();
      if (keyStore.isKeyEntry(alias)) {
        return alias;
      }
    }
    throw new AssertionError("Deployment SMC-B keystore does not contain a private key entry.");
  }

  /**
   * Loads the private key from the entry keystore so the returned key contains its algorithm
   * parameters in PKCS#8 form.
   *
   * @param entry manifest entry containing keystore metadata
   * @return private key from the configured keystore alias
   */
  private PrivateKey loadPrivateKey(TestCertificateEntry entry) {
    try {
      var password = entry.keystorePassword().toCharArray();
      var keyStore = loadKeyStore(entry.storeType(), entry.keystoreB64Content(), password);
      var key = keyStore.getKey(entry.keystoreAlias(), password);
      if (key instanceof PrivateKey privateKey) {
        return privateKey;
      }
      throw new AssertionError(
          "Keystore alias '" + entry.keystoreAlias() + "' does not contain a private key.");
    } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
      throw new AssertionError(
          "Failed to load private key from keystore for certificate entry " + entry.stem(), e);
    }
  }

  /**
   * Encodes binary DER payload as PEM text.
   *
   * @param label PEM block label
   * @param der DER payload
   * @return PEM block text
   */
  private String pemEncode(String label, byte[] der) {
    var wrappedPayload = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(der);
    return "-----BEGIN " + label + "-----\n"
        + wrappedPayload
        + "\n-----END " + label + "-----";
  }

  /**
   * Store one value in the Tiger test context.
   *
   * @param key configuration key
   * @param value configuration value
   */
  private void put(String key, String value) {
    TigerGlobalConfiguration.putValue(key, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }
}
