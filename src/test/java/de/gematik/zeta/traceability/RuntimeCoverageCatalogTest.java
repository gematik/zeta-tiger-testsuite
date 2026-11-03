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

package de.gematik.zeta.traceability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies requirement selection and catalogue completeness.
 */
class RuntimeCoverageCatalogTest {

  @TempDir
  private Path tempDir;

  /**
   * Verifies filtered XML requirements survive missing Asciidoc files and repeated procedures.
   *
   * @throws Exception if the fixture cannot be written
   */
  @Test
  void loadsSelectedRequirementsWithoutAsciidocFiles() throws Exception {
    var catalogue = tempDir.resolve("requirements.xml");
    Files.writeString(catalogue, """
        <requirements>
          <requirement id="GS-A_5526">
            <title>TLS-Anforderung</title>
            <testProcedure>Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"</testProcedure>
          </requirement>
          <requirement id="GS-A_5526">
            <title>Andere Prüfung</title>
            <testProcedure>Sicherheitsgutachten</testProcedure>
          </requirement>
          <requirement id="TIP1-A_6519-01">
            <title>Weitere Anforderung</title>
            <testProcedure>Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"</testProcedure>
          </requirement>
          <requirement id="A_99999">
            <title>Nicht ausgewählt</title>
            <testProcedure>Sicherheitsgutachten</testProcedure>
          </requirement>
        </requirements>
        """);

    var catalog = new RuntimeCoverageCatalog(catalogue, tempDir.resolve("afos"),
        tempDir.resolve("testaspekte"));

    assertThat(catalog.requirementIds()).containsExactly("GS-A_5526", "TIP1-A_6519-01");
    assertThat(catalog.requirementTitle("GS-A_5526")).contains("TLS-Anforderung");
    assertThat(catalog.requirementTitle("TIP1-A_6519-01")).contains("Weitere Anforderung");
  }

  /**
   * Verifies maintained definitions are retained independently of tag syntax.
   *
   * @param identifier maintained requirement identifier
   * @throws Exception if the fixture cannot be written
   */
  @ParameterizedTest
  @ValueSource(strings = {"GS-A_5526", "GS-A_5542", "TIP1-A_6519", "TIP1-A_6519-01",
      "A_12345-01", "CUSTOM_REQUIREMENT"})
  void includesEveryMaintainedRequirementFile(String identifier) throws Exception {
    var catalogue = writeMinimalCatalogue();
    var definitions = Files.createDirectory(tempDir.resolve("afos"));
    Files.writeString(definitions.resolve(identifier + ".adoc"),
        "==== " + identifier + " - Zusätzliche Anforderung\n");
    Files.writeString(definitions.resolve("README.adoc"), "== Index\n");

    var catalog = new RuntimeCoverageCatalog(catalogue, definitions,
        tempDir.resolve("testaspekte"));

    assertThat(catalog.requirementIds()).containsExactlyInAnyOrder("A_100", identifier);
    assertThat(catalog.requirementTitle(identifier)).contains("Zusätzliche Anforderung");
  }

  /**
   * Verifies the default catalogue includes every maintained non-index requirement.
   *
   * @throws Exception if the repository definitions cannot be read
   */
  @Test
  void defaultCatalogueMatchesAllMaintainedRequirementFiles() throws Exception {
    try (var paths = Files.walk(Path.of("docs/asciidoc/afos"))) {
      var identifiers = paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".adoc"))
          .filter(path -> !path.getFileName().toString().equalsIgnoreCase("readme.adoc"))
          .map(path -> RuntimeCoverageIds.stripAdocSuffix(path.getFileName().toString()))
          .toList();

      assertThat(new RuntimeCoverageCatalog().requirementIds())
          .containsExactlyInAnyOrderElementsOf(identifiers)
          .contains("GS-A_5526", "GS-A_5542", "TIP1-A_6519", "TIP1-A_6519-01", "A_28868");
    }
  }

  /**
   * Verifies invalid or empty source catalogues cannot silently reduce coverage.
   *
   * @param xml invalid or empty catalogue contents
   * @throws Exception if the fixture cannot be written
   */
  @ParameterizedTest
  @ValueSource(strings = {"<requirements>", "<requirements/>"})
  void rejectsInvalidOrEmptyCatalogue(String xml) throws Exception {
    var catalogue = tempDir.resolve("requirements.xml");
    Files.writeString(catalogue, xml);

    assertThatThrownBy(() -> new RuntimeCoverageCatalog(catalogue, tempDir, tempDir))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * Verifies a missing catalogue is reported even when additional definitions exist.
   */
  @Test
  void rejectsMissingCatalogue() {
    assertThatThrownBy(() -> new RuntimeCoverageCatalog(tempDir.resolve("missing.xml"),
        tempDir, tempDir)).isInstanceOf(UncheckedIOException.class);
  }

  /**
   * Write a catalogue containing one product-test requirement.
   *
   * @return catalogue path
   * @throws Exception if the fixture cannot be written
   */
  private Path writeMinimalCatalogue() throws Exception {
    var catalogue = tempDir.resolve("requirements.xml");
    Files.writeString(catalogue, """
        <requirements><requirement id="A_100">
          <title>Kataloganforderung</title>
          <testProcedure>Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"</testProcedure>
        </requirement></requirements>
        """);
    return catalogue;
  }
}
