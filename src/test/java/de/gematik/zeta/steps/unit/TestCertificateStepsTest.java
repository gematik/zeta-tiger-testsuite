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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.steps.TestCertificateSteps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
   * Remove the manifest override after each test run.
   */
  @AfterEach
  void clearManifestProperty() {
    System.clearProperty("testCertificates.dir");
    TigerGlobalConfiguration.putValue("testCertificates.dir", "",
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
    assertThat(read("perf.client.single.crt_path")).endsWith("certs/block-a/stem-0001.crt");
    assertThat(read("perf.client.single.crt_b64")).isEqualTo(
        Base64.getEncoder().encodeToString(new byte[]{0x30, (byte) 0x82, 0x01}));
    assertThat(read("perf.client.single.keystore_b64")).isEqualTo("BASE64-1");
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
    Files.writeString(keyStoreDir.resolve("stem-0001.b64"), "BASE64-1\n", StandardCharsets.UTF_8);
    Files.writeString(keyStoreDir.resolve("stem-0002.b64"), "BASE64-2\n", StandardCharsets.UTF_8);

    var manifest = String.join("\n",
        "stem\tcrt\tprv\tpub\tkeystore_b64\tkeystore_password\tkeystore_alias\tstore_type",
        "stem-0001\tcerts/block-a/stem-0001.crt\tcerts/block-a/stem-0001.prv\tcerts/block-a/stem-0001.pub\tkeystores/block-a/stem-0001.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "stem-0002\tcerts/block-a/stem-0002.crt\tcerts/block-a/stem-0002.prv\tcerts/block-a/stem-0002.pub\tkeystores/block-a/stem-0002.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "");
    Files.writeString(manifestDir.resolve("cert-manifest.tsv"), manifest, StandardCharsets.UTF_8);

    return repoRoot;
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
