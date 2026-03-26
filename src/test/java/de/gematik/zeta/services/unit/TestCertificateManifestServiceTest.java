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

package de.gematik.zeta.services.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TestCertificateManifestService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TestCertificateManifestService}.
 */
class TestCertificateManifestServiceTest {

  private final TestCertificateManifestService service = new TestCertificateManifestService();

  @TempDir
  Path tempDir;

  /**
   * Clear certificate manifest configuration overrides after each test.
   */
  @AfterEach
  void clearCertificateManifestOverrides() {
    System.clearProperty("testCertificates.dir");
    TigerGlobalConfiguration.putValue("testCertificates.dir", "",
        ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Verify that one manifest entry exposes Base64-safe content for binary certificate files.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void findByIndexResolvesEntryAndExposesBase64SafeContent() throws IOException {
    var manifestPath = createTestRepo().resolve("manifest/cert-manifest.tsv");

    var entry = service.findByIndex(manifestPath, 2);

    assertThat(entry.stem()).isEqualTo("stem-0002");
    assertThat(entry.crtPath().toString()).endsWith("certs/block-a/stem-0002.crt");
    assertThat(entry.crtBase64()).isEqualTo(Base64.getEncoder()
        .encodeToString(new byte[]{0x30, (byte) 0x82, 0x02}));
    assertThat(entry.prvBase64()).isEqualTo(Base64.getEncoder()
        .encodeToString(new byte[]{0x30, (byte) 0x81, 0x02}));
    assertThat(entry.pubBase64()).isEqualTo(Base64.getEncoder()
        .encodeToString(new byte[]{0x30, 0x5a, 0x02}));
    assertThat(entry.keystoreB64Content()).isEqualTo("BASE64-2");
    assertThat(entry.keystorePassword()).isEqualTo("00");
    assertThat(entry.keystoreAlias()).isEqualTo("zeta.c_smcb_aut");
    assertThat(entry.storeType()).isEqualTo("PKCS12");
  }

  /**
   * Verify that lookup by an unknown stem fails fast.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void findByStemThrowsWhenStemDoesNotExist() throws IOException {
    var manifestPath = createTestRepo().resolve("manifest/cert-manifest.tsv");

    assertThrows(IllegalArgumentException.class,
        () -> service.findByStem(manifestPath, "missing-stem"));
  }

  /**
   * Verify that TSV export keeps file paths absolute and embeds the keystore payload.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void exportTsvSliceWritesAbsolutePathsAndInlineKeystore() throws IOException {
    var repoRoot = createTestRepo();
    var manifestPath = repoRoot.resolve("manifest/cert-manifest.tsv");
    var outputPath = tempDir.resolve("out/cert-slice.tsv");

    var writtenPath = service.exportTsvSlice(manifestPath, 2, 1, outputPath);

    assertThat(writtenPath).isEqualTo(outputPath.toAbsolutePath().normalize());
    var exported = Files.readString(outputPath, StandardCharsets.UTF_8);
    assertThat(exported).contains("stem\tcrt_path\tprv_path");
    assertThat(exported).contains("stem-0002");
    assertThat(exported).contains(repoRoot.resolve("certs/block-a/stem-0002.crt").toString());
    assertThat(exported).contains("BASE64-2");
    assertThat(exported).contains("zeta.c_smcb_aut");
  }

  /**
   * Verify that range reads preserve the expected entry order.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void findRangeReturnsEntriesInRequestedOrder() throws IOException {
    var manifestPath = createTestRepo().resolve("manifest/cert-manifest.tsv");

    var entries = service.findRange(manifestPath, 1, 2);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).stem()).isEqualTo("stem-0001");
    assertThat(entries.get(1).stem()).isEqualTo("stem-0002");
  }

  /**
   * Verify that the manifest resolves from the configured certificate repository root.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void resolveManifestPathUsesConfiguredRepoRoot() throws IOException {
    var repoRoot = createTestRepo();
    System.setProperty("testCertificates.dir", repoRoot.toString());

    var manifestPath = service.resolveManifestPath(null);
    var entry = service.findByIndex(manifestPath, 1);

    assertThat(manifestPath)
        .isEqualTo(repoRoot.resolve("manifest/cert-manifest.tsv").toAbsolutePath().normalize());
    assertThat(entry.crtPath()).isEqualTo(
        repoRoot.resolve("certs/block-a/stem-0001.crt").toAbsolutePath().normalize());
  }

  /**
   * Verify that an explicit manifest path keeps resolving assets relative to that manifest.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void resolveManifestPathUsesExplicitManifestParameter() throws IOException {
    var explicitManifestRelativePath = Path.of("custom/manifests/cert-manifest.tsv");
    var explicitRepoRoot = createTestRepo(tempDir.resolve("explicit-repo"), explicitManifestRelativePath);
    var explicitManifestPath = explicitRepoRoot.resolve(explicitManifestRelativePath);

    var manifestPath = service.resolveManifestPath(explicitManifestPath.toString());
    var entry = service.findByIndex(manifestPath, 1);

    assertThat(manifestPath).isEqualTo(explicitManifestPath.toAbsolutePath().normalize());
    assertThat(entry.crtPath()).isEqualTo(
        explicitRepoRoot.resolve("certs/block-a/stem-0001.crt").toAbsolutePath().normalize());
  }

  /**
   * Verify that an explicit manifest in a non-default subdirectory still resolves relative assets
   * against the repository root.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void findByIndexResolvesExplicitManifestOutsideDefaultLayout() throws IOException {
    var manifestRelativePath = Path.of("custom/manifests/cert-manifest.tsv");
    var repoRoot = createTestRepo(manifestRelativePath);
    var manifestPath = repoRoot.resolve(manifestRelativePath);

    var entry = service.findByIndex(manifestPath, 2);

    assertThat(entry.crtPath()).isEqualTo(
        repoRoot.resolve("certs/block-a/stem-0002.crt").toAbsolutePath().normalize());
    assertThat(entry.keystoreB64Path()).isEqualTo(
        repoRoot.resolve("keystores/block-a/stem-0002.b64").toAbsolutePath().normalize());
  }

  /**
   * Verify that lookup by stem skips earlier broken rows until the requested stem matches.
   *
   * @throws IOException on filesystem errors
   */
  @Test
  void findByStemSkipsBrokenRowsBeforeTheRequestedStem() throws IOException {
    var repoRoot = createTestRepo();
    var manifestPath = repoRoot.resolve("manifest/cert-manifest.tsv");
    var manifest = String.join("\n",
        "stem\tcrt\tprv\tpub\tkeystore_b64\tkeystore_password\tkeystore_alias\tstore_type",
        "broken-stem\tcerts/block-a/missing.crt\tcerts/block-a/missing.prv\tcerts/block-a/missing.pub\tkeystores/block-a/missing.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "stem-0002\tcerts/block-a/stem-0002.crt\tcerts/block-a/stem-0002.prv\tcerts/block-a/stem-0002.pub\tkeystores/block-a/stem-0002.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "");
    write(manifestPath, manifest);

    var entry = service.findByStem(manifestPath, "stem-0002");

    assertThat(entry.stem()).isEqualTo("stem-0002");
    assertThat(entry.crtPath()).isEqualTo(
        repoRoot.resolve("certs/block-a/stem-0002.crt").toAbsolutePath().normalize());
  }

  /**
   * Create a temporary certificate repository fixture with binary cert assets and a TSV manifest.
   *
   * @return repository root directory
   * @throws IOException on filesystem errors
   */
  private Path createTestRepo() throws IOException {
    var repoRoot = tempDir.resolve("zeta-test-certificates");
    return createTestRepo(repoRoot, Path.of("manifest/cert-manifest.tsv"));
  }

  /**
   * Create a temporary certificate repository fixture with a custom manifest location.
   *
   * @param manifestRelativePath manifest path relative to the repository root
   * @return repository root directory
   * @throws IOException on filesystem errors
   */
  private Path createTestRepo(Path manifestRelativePath) throws IOException {
    var repoRoot = tempDir.resolve("zeta-test-certificates");
    return createTestRepo(repoRoot, manifestRelativePath);
  }

  /**
   * Create a temporary certificate repository fixture with binary cert assets and a TSV manifest.
   *
   * @param repoRoot repository root directory
   * @param manifestRelativePath manifest path relative to the repository root
   * @return repository root directory
   * @throws IOException on filesystem errors
   */
  private Path createTestRepo(Path repoRoot, Path manifestRelativePath) throws IOException {
    var manifestDir = repoRoot.resolve("manifest");
    var certDir = repoRoot.resolve("certs/block-a");

    manifestDir = repoRoot.resolve(manifestRelativePath).getParent();
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
    write(keyStoreDir.resolve("stem-0001.b64"), "BASE64-1\n");
    write(keyStoreDir.resolve("stem-0002.b64"), "BASE64-2\n");

    var manifest = String.join("\n",
        "stem\tcrt\tprv\tpub\tkeystore_b64\tkeystore_password\tkeystore_alias\tstore_type",
        "stem-0001\tcerts/block-a/stem-0001.crt\tcerts/block-a/stem-0001.prv\tcerts/block-a/stem-0001.pub\tkeystores/block-a/stem-0001.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "stem-0002\tcerts/block-a/stem-0002.crt\tcerts/block-a/stem-0002.prv\tcerts/block-a/stem-0002.pub\tkeystores/block-a/stem-0002.b64\t00\tzeta.c_smcb_aut\tPKCS12",
        "");
    write(repoRoot.resolve(manifestRelativePath), manifest);

    return repoRoot;
  }

  /**
   * Write one UTF-8 text fixture file.
   *
   * @param path target path
   * @param value file content
   * @throws IOException on filesystem errors
   */
  private void write(Path path, String value) throws IOException {
    Files.writeString(path, value, StandardCharsets.UTF_8);
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
