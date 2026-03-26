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
import java.io.IOException;
import java.nio.file.Path;
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
   * values. This is intended for perf tooling such as JMeter CSV Data Set Config configured with a
   * tab delimiter.
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
    put(variablePrefix + ".pub_b64", entry.pubBase64());
    put(variablePrefix + ".keystore_b64", entry.keystoreB64Content());
    put(variablePrefix + ".keystore_password", entry.keystorePassword());
    put(variablePrefix + ".keystore_alias", entry.keystoreAlias());
    put(variablePrefix + ".store_type", entry.storeType());

    log.info("Loaded test certificate entry {} into Tiger variable prefix '{}'", entry.stem(),
        variablePrefix);
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
