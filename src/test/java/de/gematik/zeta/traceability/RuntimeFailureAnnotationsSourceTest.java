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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Scenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks that private failure annotations refer to scenarios in maintained feature sources. */
class RuntimeFailureAnnotationsSourceTest {

  private static final Path ANNOTATIONS =
      Path.of("docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv");
  private static final Path FEATURES = Path.of("src/test/resources/features");

  @TempDir
  Path tempDir;

  /**
   * Checks each CSV entry against scenario declarations and expanded example names.
   *
   * @return one source consistency test per annotation
   * @throws IOException if a feature source cannot be read
   */
  @TestFactory
  Stream<DynamicTest> annotatedScenariosExistInSource() throws IOException {
    assumeTrue(Files.exists(ANNOTATIONS), "Private failure annotations are absent in public exports");
    assertThat(ANNOTATIONS).isRegularFile();
    var annotations = RuntimeCoverageCsvRepository.loadFailureAnnotations(ANNOTATIONS).rows();
    var sources = readSourceScenarios(FEATURES);
    assertThat(sources).as("Maintained feature sources").isNotEmpty();
    return IntStream.range(0, annotations.size()).mapToObj(index -> {
      var annotation = annotations.get(index);
      var label = "CSV-Eintrag " + (index + 1) + ": " + annotation.feature()
          + " :: " + annotation.scenario();
      return DynamicTest.dynamicTest(label, () -> {
        assertThat(annotation.isScenarioMapping()).as("Szenario-Zuordnung: %s", label).isTrue();
        assertThat(existsInSource(sources, annotation.feature(), annotation.scenario()))
            .as("Kein Szenario im Quellcode gefunden: %s", label).isTrue();
      });
    });
  }

  /**
   * Exercises source lookup for ordinary, ignored, outlined and removed scenarios.
   *
   * @param feature annotation feature path
   * @param scenario annotation scenario name
   * @param expected whether the source contains the referenced scenario
   * @throws IOException if a fixture cannot be read or written
   */
  @ParameterizedTest
  @CsvSource({
      "demo.feature, Vorhandenes Szenario, true",
      "demo.feature, Ignoriertes Szenario, true",
      "demo.feature, Variante <wert>, true",
      "demo.feature, Variante aktuell, true",
      "demo.feature, Variante entfernt, false",
      "demo.feature, Entferntes Szenario, false",
      "demo.feature, Nur im Kommentar, false",
      "demo.feature, Nur im DocString, false",
      "missing.feature, Vorhandenes Szenario, false",
      "notdemo.feature, Vorhandenes Szenario, false",
      "classpath:features/demo.feature, Vorhandenes Szenario, true",
      ", Vorhandenes Szenario, true",
      "demo.feature, , false"
  })
  void resolvesOnlyExistingSourceScenarios(String feature, String scenario, boolean expected)
      throws IOException {
    var features = Files.createDirectory(tempDir.resolve("features"));
    Files.writeString(features.resolve("demo.feature"), """
        # language: de
        Funktionalität: Quelldatei
          # Szenario: Nur im Kommentar
          Szenario: Vorhandenes Szenario
            Angenommen eine Beschreibung
              \"\"\"
              Szenario: Nur im DocString
              \"\"\"

          @Ignore
          Szenario: Ignoriertes Szenario
            Dann bleibt es im Quellcode

          Regel: Varianten
            @Ignore
            Szenariogrundriss: Variante <wert>
              Dann gilt <wert>

              Beispiele:
                | wert    |
                | aktuell |
        """);

    assertThat(existsInSource(readSourceScenarios(features), feature, scenario)).isEqualTo(expected);
  }

  /**
   * Rejects invalid feature sources instead of treating them as an empty scenario catalogue.
   *
   * @throws IOException if a fixture cannot be written
   */
  @Test
  void rejectsInvalidFeatureSources() throws IOException {
    Files.writeString(tempDir.resolve("invalid.feature"),
        "Feature: Invalid\nScenario: Broken\n  Given a step\n  \"\"\"\n  unterminated");
    assertThrows(AssertionError.class, () -> readSourceScenarios(tempDir));
  }

  /**
   * Parses all feature sources without applying runtime tag or profile filters.
   *
   * @param root maintained feature root
   * @return declared and expanded scenario names by normalized feature path
   * @throws IOException if a feature source cannot be read
   */
  private static Map<String, Set<String>> readSourceScenarios(Path root) throws IOException {
    var sources = new LinkedHashMap<String, Set<String>>();
    var parser = GherkinParser.builder().build();
    try (var paths = Files.walk(root)) {
      for (var path : paths.filter(Files::isRegularFile)
          .filter(file -> file.toString().endsWith(".feature")).sorted().toList()) {
        var names = new HashSet<String>();
        try (var envelopes = parser.parse(path)) {
          for (var envelope : envelopes.toList()) {
            assertThat(envelope.getParseError()).as("Feature parses: %s", path).isEmpty();
            envelope.getPickle().ifPresent(pickle -> names.add(pickle.getName().trim()));
            envelope.getGherkinDocument().flatMap(document -> document.getFeature())
                .ifPresent(feature -> feature.getChildren().forEach(child -> {
                  child.getScenario().map(Scenario::getName).ifPresent(name -> names.add(name.trim()));
                  child.getRule().ifPresent(rule -> rule.getChildren().forEach(ruleChild ->
                      ruleChild.getScenario().map(Scenario::getName)
                          .ifPresent(name -> names.add(name.trim()))));
                }));
          }
        }
        sources.put(RuntimeCoverageIds.normalizePath(path.toString()), names);
      }
    }
    return sources;
  }

  /**
   * Finds an exact declaration or expanded example name within the annotation's feature scope.
   *
   * @param sources parsed feature sources
   * @param feature optional annotation feature path
   * @param scenario annotation scenario name
   * @return whether a matching scenario exists
   */
  private static boolean existsInSource(Map<String, Set<String>> sources, String feature,
      String scenario) {
    var normalizedFeature = RuntimeCoverageIds.normalizePath(feature);
    var normalizedScenario = RuntimeCoverageIds.defaultString(scenario).trim();
    return !normalizedScenario.isBlank() && sources.entrySet().stream()
        .filter(entry -> normalizedFeature.isBlank()
            || RuntimeCoverageIds.pathMatches(normalizedFeature, entry.getKey()))
        .anyMatch(entry -> entry.getValue().contains(normalizedScenario));
  }
}
