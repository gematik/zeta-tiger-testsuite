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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Loads requirement and test-aspect catalogues needed by runtime coverage output.
 */
final class RuntimeCoverageCatalog {

  private static final Path REQUIREMENT_CATALOGUE = Path.of(
      "docs/specifications/2.0.0-cc/derived/gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xml");
  private static final String PRODUCT_TEST_PROCEDURE =
      "Festlegungen zur funktionalen Eignung \"Produkttest/Produktübergreifender Test\"";
  private static final Path REQUIREMENTS_ROOT = Path.of("docs/asciidoc/afos");
  private static final Path TEST_ASPECTS_ROOT = Path.of("docs/asciidoc/testaspekte");

  private final Map<String, String> requirementTitles = new TreeMap<>();
  private final Map<String, TestAspectInfo> testAspectCatalog = new LinkedHashMap<>();

  /**
   * Create and load the runtime coverage catalogues.
   */
  RuntimeCoverageCatalog() {
    this(REQUIREMENT_CATALOGUE, REQUIREMENTS_ROOT, TEST_ASPECTS_ROOT);
  }

  /**
   * Load runtime coverage catalogues from the given directories.
   *
   * @param requirementCatalogue requirement XML catalogue
   * @param requirementsRoot maintained requirement directory
   * @param testAspectsRoot test-aspect catalogue directory
   */
  RuntimeCoverageCatalog(Path requirementCatalogue, Path requirementsRoot, Path testAspectsRoot) {
    loadRequirementCatalogue(requirementCatalogue);
    loadRequirementCatalog(requirementsRoot);
    loadTestAspectCatalog(testAspectsRoot);
  }

  /**
   * Load product-test requirement identifiers and titles from the XML catalogue.
   *
   * @param catalogue requirement XML file
   * @throws UncheckedIOException if the catalogue cannot be read
   * @throws IllegalStateException if the XML cannot be parsed
   */
  private void loadRequirementCatalogue(Path catalogue) {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var document = factory.newDocumentBuilder().parse(catalogue.toFile());
      var requirements = document.getElementsByTagName("requirement");
      for (var index = 0; index < requirements.getLength(); index++) {
        var requirement = (Element) requirements.item(index);
        if (childText(requirement, "testProcedure").contains(PRODUCT_TEST_PROCEDURE)) {
          var id = requirement.getAttribute("id");
          if (id.isBlank()) {
            throw new IllegalStateException("Missing requirement id in " + catalogue);
          }
          requirementTitles.put(id, childText(requirement, "title"));
        }
      }
      if (requirementTitles.isEmpty()) {
        throw new IllegalStateException("No product-test requirements in " + catalogue);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read requirement catalogue " + catalogue, exception);
    } catch (ParserConfigurationException | SAXException exception) {
      throw new IllegalStateException("Unable to parse requirement catalogue " + catalogue, exception);
    }
  }

  /**
   * Return the trimmed text of a catalogue child element.
   *
   * @param parent catalogue element
   * @param name child element name
   * @return text, or an empty string if absent
   */
  private static String childText(Element parent, String name) {
    var child = parent.getElementsByTagName(name).item(0);
    return child == null ? "" : child.getTextContent().strip();
  }

  /**
   * Return all known requirement identifiers.
   *
   * @return requirement ids
   */
  Set<String> requirementIds() {
    return requirementTitles.keySet();
  }

  /**
   * Return the title for the given requirement.
   *
   * @param id requirement id
   * @return requirement title, or empty if no title is available
   */
  Optional<String> requirementTitle(String id) {
    return Optional.ofNullable(requirementTitles.get(id)).filter(title -> !title.isBlank());
  }

  /**
   * Return all known test aspect identifiers.
   *
   * @return test aspect ids
   */
  Set<String> testAspectIds() {
    return testAspectCatalog.keySet();
  }

  /**
   * Return test-aspect metadata for the given id.
   *
   * @param id test aspect id
   * @return test-aspect metadata or null
   */
  TestAspectInfo testAspectInfo(String id) {
    return testAspectCatalog.get(id);
  }

  /**
   * Compute the passed/total test aspect coverage for an AFO.
   *
   * @param requirementId     requirement id
   * @param mergedTestAspects merged test aspect state
   * @return two-element array with total and passed count
   */
  int[] computeTestAspectCoverage(String requirementId,
      Map<String, Rollup> mergedTestAspects) {
    if (testAspectCatalog.isEmpty()) {
      return new int[] {0, 0};
    }
    var total = 0;
    var passed = 0;
    for (var info : testAspectCatalog.values()) {
      if (!Objects.equals(requirementId, info.requirementId())) {
        continue;
      }
      total++;
      var rollup = mergedTestAspects.getOrDefault(info.id(), Rollup.empty());
      if (rollup.isPassed()) {
        passed++;
      }
    }
    if (total == 0) {
      return new int[] {0, 0};
    }
    return new int[] {total, passed};
  }

  /**
   * Count test aspects with recorded scenario results for the given requirement.
   *
   * @param requirementId     requirement id
   * @param mergedTestAspects merged test aspect state
   * @return test aspects with results or null if the catalog is empty
   */
  Integer computeTestAspectReportedCount(String requirementId,
      Map<String, Rollup> mergedTestAspects) {
    if (testAspectCatalog.isEmpty()) {
      return null;
    }
    var reported = 0;
    for (var info : testAspectCatalog.values()) {
      if (!Objects.equals(requirementId, info.requirementId())) {
        continue;
      }
      var rollup = mergedTestAspects.getOrDefault(info.id(), Rollup.empty());
      if (rollup.total() > 0) {
        reported++;
      }
    }
    return reported;
  }

  /**
   * Load requirement identifiers and titles from Asciidoc files.
   *
   * @param root requirement catalogue directory
   */
  private void loadRequirementCatalog(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".adoc"))
          .filter(path -> !path.getFileName().toString().equalsIgnoreCase("readme.adoc"))
          .sorted()
          .forEach(path -> {
            var id = RuntimeCoverageIds.stripAdocSuffix(path.getFileName().toString());
            var title = readTitle(path, id);
            if (!title.isBlank() || !requirementTitles.containsKey(id)) {
              requirementTitles.put(id, title);
            }
          });
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read requirement catalog", exception);
    }
  }

  /**
   * Load the test aspect catalog from generated Asciidoc files.
   *
   * @param root test-aspect catalogue directory
   */
  private void loadTestAspectCatalog(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().startsWith("TA_"))
          .filter(path -> path.getFileName().toString().endsWith(".adoc"))
          .sorted()
          .forEach(path -> {
            var id = RuntimeCoverageIds.stripAdocSuffix(path.getFileName().toString());
            var title = readTitle(path, id);
            var requirementId = RuntimeCoverageIds.resolveRequirementFromTestAspect(id).orElse("");
            testAspectCatalog.put(id, new TestAspectInfo(id, title, requirementId));
          });
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read test aspect catalog", exception);
    }
  }

  /**
   * Extract a catalogue title from its Asciidoc header.
   *
   * @param path Asciidoc path
   * @param id catalogue entry id
   * @return title or empty string
   */
  private static String readTitle(Path path, String id) {
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      for (var i = 0; i < 5 && (line = reader.readLine()) != null; i++) {
        if (line.contains(id) && line.contains(" - ")) {
          return line.substring(line.indexOf(" - ") + 3).trim();
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read catalogue title from " + path, exception);
    }
    return "";
  }
}
