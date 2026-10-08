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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.steps.TestCertificateSteps;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TestCertificateSteps}.
 */
class TestCertificateStepsTest {

  private final TestCertificateSteps steps = new TestCertificateSteps();

  @TempDir
  Path tempDir;

  /**
   * Remove certificate and deployment overrides after each test run.
   */
  @AfterEach
  void clearConfigurationProperties() {
    System.clearProperty("testCertificates.dir");
    TigerGlobalConfiguration.putValue("testCertificates.dir", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zeta_k8s_helm_deployment_directory", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zeta_k8s_smb_keystore_file", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zeta_k8s_smb_keystore_password_file", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Verify that loading a single entry publishes path and Base64 variables.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void loadCertificateEntryByIndexPublishesPathsAndBase64Values() throws IOException {
    configureManifest(createTestRepo());

    steps.loadCertificateEntryByIndex(1, "perf.client.single");

    assertThat(read("perf.client.single.stem")).isEqualTo("stem-0001");
    assertThat(Path.of(read("perf.client.single.crt_path")))
        .endsWith(Path.of("certs", "block-a", "stem-0001.crt"));
    assertThat(read("perf.client.single.crt_b64")).isEqualTo(
        Base64.getEncoder().encodeToString(new byte[]{0x30, (byte) 0x82, 0x01}));
    assertThat(Base64.getDecoder().decode(read("perf.client.single.keystore_b64"))).isNotEmpty();
    assertThat(read("perf.client.single.prv_pem")).startsWith("-----BEGIN PRIVATE KEY-----");
  }

  /**
   * Verify that the bulk step publishes exactly the requested number of indexed entries.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void loadCertificateEntriesPublishesChosenCountAsIndexedVariables() throws IOException {
    configureManifest(createTestRepo());

    steps.loadCertificateEntries(2, 1, "perf.clients");

    assertThat(read("perf.clients.count")).isEqualTo("2");
    assertThat(read("perf.clients.start_index")).isEqualTo("1");
    assertThat(read("perf.clients.1.manifest_index")).isEqualTo("1");
    assertThat(read("perf.clients.1.stem")).isEqualTo("stem-0001");
    assertThat(read("perf.clients.2.manifest_index")).isEqualTo("2");
    assertThat(read("perf.clients.2.stem")).isEqualTo("stem-0002");
    assertThat(read("perf.clients.2.prv_b64")).isEqualTo(
        Base64.getEncoder().encodeToString(new byte[]{0x30, (byte) 0x81, 0x02}));
  }

  /**
   * Verify that the deployment SMC-B step uses the configured files independent from the Helm
   * deployment directory.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void loadDeploymentSmcbKeystoreUsesConfiguredFilesIndependentFromHelmDirectory()
      throws IOException {
    var helmDirectory = tempDir.resolve("zeta-guard-helm");
    var keystoreDirectory = tempDir.resolve("configured-keystores");
    Files.createDirectories(helmDirectory);
    Files.createDirectories(keystoreDirectory);
    Files.writeString(
        keystoreDirectory.resolve("pdp-keystore.b64"),
        createKeyStoreBase64("zeta.c_smcb_aut", "00") + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        keystoreDirectory.resolve("pdp-keystore-pass"),
        "00\n",
        StandardCharsets.UTF_8);
    configureDeploymentKeystore(helmDirectory, keystoreDirectory.resolve("pdp-keystore.b64"),
        keystoreDirectory.resolve("pdp-keystore-pass"));

    steps.loadDeploymentSmcbKeystore("deployment.smcb");

    assertThat(read("deployment.smcb.stem")).isEqualTo("zeta.c_smcb_aut");
    assertThat(Path.of(read("deployment.smcb.keystore_b64_path")))
        .isEqualTo(keystoreDirectory.resolve("pdp-keystore.b64").toAbsolutePath().normalize());
    assertThat(read("deployment.smcb.keystore_password")).isEqualTo("00");
    assertThat(read("deployment.smcb.keystore_alias")).isEqualTo("zeta.c_smcb_aut");
    assertThat(read("deployment.smcb.prv_pem")).startsWith("-----BEGIN PRIVATE KEY-----");
    assertThat(read("deployment.smcb.issuer_crt_b64")).isEqualTo(read("deployment.smcb.crt_b64"));
  }

  /**
   * Verify that OCSP issuer material must be available for non-self-signed SMC-B certificates.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void loadDeploymentSmcbKeystoreFailsWhenIssuerCertificateIsMissingForNonSelfSignedCertificate()
      throws IOException {
    var helmDirectory = tempDir.resolve("zeta-guard-helm");
    var keystoreDirectory = tempDir.resolve("configured-keystores");
    Files.createDirectories(helmDirectory);
    Files.createDirectories(keystoreDirectory);
    Files.writeString(
        keystoreDirectory.resolve("pdp-keystore.b64"),
        createKeyStoreBase64WithoutIssuerCertificate("zeta.c_smcb_aut", "00") + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        keystoreDirectory.resolve("pdp-keystore-pass"),
        "00" + "\n",
        StandardCharsets.UTF_8);
    configureDeploymentKeystore(helmDirectory, keystoreDirectory.resolve("pdp-keystore.b64"),
        keystoreDirectory.resolve("pdp-keystore-pass"));

    assertThatThrownBy(() -> steps.loadDeploymentSmcbKeystore("deployment.smcb"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("contains a single non-self-signed certificate")
        .hasMessageContaining("no issuer certificate is available in the keystore chain");
  }

  /**
   * Verify that missing deployment SMC-B files fail explicitly instead of falling back to local
   * helper folders.
   */
  @Test
  void loadDeploymentSmcbKeystoreFailsForMissingConfiguredFileWithoutLocalFallback() {
    TigerGlobalConfiguration.putValue("zeta_k8s_smb_keystore_file",
        tempDir.resolve("missing-keystore.b64").toString(),
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zeta_k8s_smb_keystore_password_file",
        tempDir.resolve("missing-keystore-pass").toString(),
        ConfigurationValuePrecedence.TEST_CONTEXT);

    assertThatThrownBy(() -> steps.loadDeploymentSmcbKeystore("deployment.smcb"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("zeta_k8s_smb_keystore_file")
        .hasMessageContaining("does not exist");
  }

  /**
   * Point the certificate step implementation at the temporary certificate repository fixture.
   *
   * @param repoRoot certificate repository root to use
   */
  private void configureManifest(Path repoRoot) {
    System.setProperty("testCertificates.dir", repoRoot.toString());
    TigerGlobalConfiguration.putValue("testCertificates.dir", repoRoot.toString(),
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Point the deployment SMC-B step at a temporary Helm deployment fixture.
   *
   * @param helmDirectory Helm deployment directory
   * @param keystoreFile configured SMC-B keystore file
   * @param passwordFile configured SMC-B keystore password file
   */
  private void configureDeploymentKeystore(
      Path helmDirectory, Path keystoreFile, Path passwordFile) {
    TigerGlobalConfiguration.putValue("zeta_k8s_helm_deployment_directory",
        helmDirectory.toString(), ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zeta_k8s_smb_keystore_file", keystoreFile.toString(),
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("zeta_k8s_smb_keystore_password_file",
        passwordFile.toString(), ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Read one Tiger variable from the test context.
   *
   * @param key configuration key
   * @return resolved value
   */
  private String read(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .orElseThrow(() -> new AssertionError("Missing Tiger variable: " + key));
  }

  /**
   * Create a temporary certificate repository fixture for step-level tests.
   *
   * @return repository root directory
   * @throws IOException on filesystem errors
   */
  private Path createTestRepo() throws IOException {
    var repoRoot = tempDir.resolve("zeta-test-certificates");
    var manifestDir = repoRoot.resolve("manifest");
    var certDir = repoRoot.resolve("certs/block-a");
    var keyStoreDir = repoRoot.resolve("keystores/block-a");

    Files.createDirectories(manifestDir);
    Files.createDirectories(certDir);
    Files.createDirectories(keyStoreDir);

    writeBytes(certDir.resolve("stem-0001.crt"), new byte[]{0x30, (byte) 0x82, 0x01});
    writeBytes(certDir.resolve("stem-0001.prv"), new byte[]{0x30, (byte) 0x81, 0x01});
    writeBytes(certDir.resolve("stem-0001.pub"), new byte[]{0x30, 0x5a, 0x01});
    writeBytes(certDir.resolve("stem-0002.crt"), new byte[]{0x30, (byte) 0x82, 0x02});
    writeBytes(certDir.resolve("stem-0002.prv"), new byte[]{0x30, (byte) 0x81, 0x02});
    writeBytes(certDir.resolve("stem-0002.pub"), new byte[]{0x30, 0x5a, 0x02});
    var keyStoreBase64 = createKeyStoreBase64("zeta.c_smcb_aut", "00");
    Files.writeString(keyStoreDir.resolve("stem-0001.b64"), keyStoreBase64 + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(keyStoreDir.resolve("stem-0002.b64"), keyStoreBase64 + "\n",
        StandardCharsets.UTF_8);

    var manifest = String.join("\n",
        "stem\tcrt\tprv\tpub\tkeystore_b64\tkeystore_password\tkeystore_alias\tstore_type",
        "stem-0001\tcerts/block-a/stem-0001.crt\tcerts/block-a/stem-0001.prv\tcerts/block-a/stem-0001.pub\tkeystores/block-a/stem-0001.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "stem-0002\tcerts/block-a/stem-0002.crt\tcerts/block-a/stem-0002.prv\tcerts/block-a/stem-0002.pub\tkeystores/block-a/stem-0002.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "");
    Files.writeString(manifestDir.resolve("cert-manifest.tsv"), manifest, StandardCharsets.UTF_8);

    return repoRoot;
  }

  /**
   * Create a Base64-encoded PKCS#12 keystore fixture with one private key entry.
   *
   * @param alias key entry alias
   * @param password keystore password
   * @return Base64-encoded PKCS#12 payload
   */
  private String createKeyStoreBase64(String alias, String password) {
    try {
      var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      var keyPair = keyPairGenerator.generateKeyPair();
      var certificate = createSelfSignedCertificate(keyPair);
      var keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, password.toCharArray());
      keyStore.setKeyEntry(alias, keyPair.getPrivate(), password.toCharArray(),
          new Certificate[]{certificate});

      try (var output = new ByteArrayOutputStream()) {
        keyStore.store(output, password.toCharArray());
        return Base64.getEncoder().encodeToString(output.toByteArray());
      }
    } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
      throw new AssertionError("Failed to create PKCS#12 test fixture.", e);
    }
  }

  /**
   * Create a Base64-encoded PKCS#12 keystore fixture with one non-self-signed certificate entry and no issuer certificate in the chain.
   *
   * @param alias    key entry alias
   * @param password keystore password
   * @return Base64-encoded PKCS#12 payload
   */
  private String createKeyStoreBase64WithoutIssuerCertificate(String alias, String password) {
    try {
      var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      var leafKeyPair = keyPairGenerator.generateKeyPair();
      var issuerKeyPair = keyPairGenerator.generateKeyPair();
      var certificate = createIssuedCertificate(leafKeyPair, issuerKeyPair);
      var keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, password.toCharArray());
      keyStore.setKeyEntry(alias, leafKeyPair.getPrivate(), password.toCharArray(),
          new Certificate[]{certificate});

      try (var output = new ByteArrayOutputStream()) {
        keyStore.store(output, password.toCharArray());
        return Base64.getEncoder().encodeToString(output.toByteArray());
      }
    } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
      throw new AssertionError("Failed to create PKCS#12 test fixture.", e);
    }
  }

  /**
   * Create a self-signed X.509 certificate for one generated fixture key pair.
   *
   * @param keyPair key pair used as subject and issuer
   * @return self-signed certificate
   * @throws CertificateException if the certificate cannot be converted
   * @throws OperatorCreationException if the content signer cannot be created
   */
  private X509Certificate createSelfSignedCertificate(KeyPair keyPair)
      throws CertificateException, OperatorCreationException {
    var now = Instant.now();
    var subject = new X500Name("CN=Test Certificate");
    var signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
    var certificateBuilder = new JcaX509v3CertificateBuilder(
        subject,
        BigInteger.ONE,
        Date.from(now.minusSeconds(60)),
        Date.from(now.plusSeconds(3600)),
        subject,
        keyPair.getPublic());
    return new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));
  }

  /**
   * Create an X.509 certificate signed by a distinct issuer key pair.
   *
   * @param leafKeyPair   subject key pair
   * @param issuerKeyPair issuer key pair
   * @return issued certificate
   * @throws CertificateException      if the certificate cannot be converted
   * @throws OperatorCreationException if the content signer cannot be created
   */
  private X509Certificate createIssuedCertificate(KeyPair leafKeyPair, KeyPair issuerKeyPair)
      throws CertificateException, OperatorCreationException {
    var now = Instant.now();
    var issuer = new X500Name("CN=Issuer Certificate");
    var subject = new X500Name("CN=Leaf Certificate");
    var signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeyPair.getPrivate());
    var certificateBuilder = new JcaX509v3CertificateBuilder(
        issuer,
        BigInteger.ONE,
        Date.from(now.minusSeconds(60)),
        Date.from(now.plusSeconds(3600)),
        subject,
        leafKeyPair.getPublic());
    return new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));
  }

  /**
   * Write one binary fixture file.
   *
   * @param path target path
   * @param value file content
   * @throws IOException on filesystem errors
   */
  private void writeBytes(Path path, byte[] value) throws IOException {
    Files.write(path, value);
  }
}
