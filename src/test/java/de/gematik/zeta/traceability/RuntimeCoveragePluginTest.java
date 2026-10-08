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

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link RuntimeCoveragePlugin}.
 */
class RuntimeCoveragePluginTest {

  @TempDir
  private Path tempDir;

  /**
   * Verifies runtime CSV rows are enriched from the internal annotation CSV.
   */
  @Test
  void enrichesFailedRuntimeCoverageRowsWithInternalAnnotations() throws Exception {
    var cucumberDir = tempDir.resolve("cucumber");
    Files.createDirectories(cucumberDir);
    Files.writeString(cucumberDir.resolve("run.json"), """
        [
          {
            "uri": "demo.feature",
            "elements": [
              {
                "type": "scenario",
                "name": "Rotes Szenario",
                "line": 4,
                "tags": [
                  {"name": "@TA_A_25663_01"}
                ],
                "steps": [
                  {
                    "name": "TGR prüfe aktuelle Antwort",
                    "line": 5,
                    "result": {
                      "status": "failed",
                      "error_message": "java.lang.AssertionError: boom\\n  at example.Trace"
                    }
                  }
                ]
              },
              {
                "type": "scenario",
                "name": "Gruenes Szenario",
                "line": 8,
                "tags": [
                  {"name": "@TA_A_25663_01"}
                ],
                "steps": [
                  {
                    "name": "TGR prüfe erfolgreiche Antwort",
                    "line": 9,
                    "result": {
                      "status": "passed"
                    }
                  }
                ]
              }
            ]
          }
        ]
        """, StandardCharsets.UTF_8);

    var annotations = tempDir.resolve("runtime_failure_annotations.internal.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,demo.feature,"Rotes Szenario",EXAMPLE-1234,Example Team
        """, StandardCharsets.UTF_8);

    var output = tempDir.resolve("runtime_coverage.csv");
    withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () ->
        withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(), () -> {
          var plugin = new RuntimeCoveragePlugin(output.toString());
          invokeMergeAndWriteCsv(plugin);
        }));

    var requirementCsv = Files.readString(output, StandardCharsets.UTF_8);
    var testAspectCsv = Files.readString(tempDir.resolve("runtime_coverage_testaspects.csv"),
        StandardCharsets.UTF_8);

    assertThat(requirementCsv.lines().findFirst()).hasValue(
        "\ufeffAnforderung,Titel,Produkt umgesetzt,Laufzeit-Status,Szenarien gesamt,"
            + "Szenarien bestanden,Szenarien fehlgeschlagen,Szenarien übersprungen,"
            + "Testaspekte gesamt,Testaspekte bestanden,Testaspekte Quote,"
            + "Testaspekte implementiert,Fehler Testrun,Tickets,Verantwortlich,Scenarios");
    assertThat(requirementCsv).doesNotContain("Klassifizierung");
    assertThat(requirementCsv).doesNotContain("Schweregrad");
    assertThat(requirementCsv).doesNotContain("Aktualisiert am");
    assertThat(requirementCsv).contains("A_25663");
    assertThat(requirementCsv).contains("teilweise fehlgeschlagen");
    assertThat(requirementCsv).contains("EXAMPLE-1234");
    assertThat(requirementCsv).contains("Example Team");
    assertThat(requirementCsv).contains("Rotes Szenario");
    assertThat(requirementCsv).contains("Gruenes Szenario");
    assertThat(testAspectCsv.lines().findFirst()).hasValue(
        "\ufeffTestaspekt,Anforderung,Titel,Laufzeit-Status,Szenarien gesamt,"
            + "Szenarien bestanden,Szenarien fehlgeschlagen,Szenarien übersprungen,"
            + "Tickets,Verantwortlich,Fehler Testrun,Scenarios");
    assertThat(testAspectCsv).contains("TA_A_25663_01");
    assertThat(testAspectCsv).contains("teilweise fehlgeschlagen");
    assertThat(testAspectCsv).contains("java.lang.AssertionError: boom");
    assertThat(testAspectCsv).contains("EXAMPLE-1234");
    assertThat(testAspectCsv).contains("Example Team");
    assertThat(testAspectCsv).contains("Rotes Szenario");
    assertThat(testAspectCsv).contains("Gruenes Szenario");
  }

  /**
   * Verifies scenario annotations can enrich TA rows through executed scenario names.
   */
  @Test
  void enrichesTestAspectRowsFromScenarioNamesWhenFailureRecordsDoNotMatch() {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Szenario",
        "",
        "",
        "demo.feature",
        "Rollback bei fehlgeschlagener Aktualisierung stellt stabile Vorversion wieder her",
        Set.of("EXAMPLE-5678"),
        "Example Team")));

    var summary = annotations.findForTestAspect(
        "TA_A_25788_01",
        "A_25788",
        Set.of(),
        Set.of("Rollback bei fehlgeschlagener Aktualisierung stellt stabile Vorversion wieder her"));

    assertThat(summary.tickets()).isEqualTo("EXAMPLE-5678");
    assertThat(summary.owners()).isEqualTo("Example Team");
  }

  /**
   * Verifies expanded scenario-outline annotation names match runtime outline template names,
   * independent of legacy AFO/TA fields.
   */
  @Test
  void enrichesRowsWhenRuntimeFailureUsesScenarioOutlineTemplateName() {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Szenario",
        "A_00000",
        "TA_A_00000_01",
        "tls_client.feature",
        "TLS-1.3-Verbindungen - ZETA Client - Signature-Schemes.",
        Set.of("EXAMPLE-9012"),
        "Example Team")));

    var summary = annotations.findForRequirement(
        "A_21275-01",
        Set.of(new FailureRecord(
            "TLS-1.3-Verbindungen - <client_name> - Signature-Schemes. -> boom",
            Set.of("TA_A_21275-01_05"),
            "src/test/resources/features/UserStory_TLS/UseCase_02/tls_client.feature",
            "TLS-1.3-Verbindungen - <client_name> - Signature-Schemes.")),
        Set.of());

    assertThat(summary.tickets()).isEqualTo("EXAMPLE-9012");
    assertThat(summary.owners()).isEqualTo("Example Team");
  }

  /**
   * Verifies scenario-outline annotation templates match expanded runtime scenario names.
   */
  @Test
  void enrichesRowsWhenAnnotationUsesScenarioOutlineTemplateName() {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Szenario",
        "A_21275-01",
        "TA_A_21275-01_05",
        "tls_client.feature",
        "TLS-1.3-Verbindungen - <client_name> - Signature-Schemes.",
        Set.of("EXAMPLE-3456"),
        "Example Team")));

    var summary = annotations.findForTestAspect(
        "TA_A_21275-01_05",
        "A_21275-01",
        Set.of(new FailureRecord(
            "TLS-1.3-Verbindungen - ZETA Native Client - Signature-Schemes. -> boom",
            Set.of("TA_A_21275-01_05"),
            "src/test/resources/features/UserStory_TLS/UseCase_02/tls_client.feature",
            "TLS-1.3-Verbindungen - ZETA Native Client - Signature-Schemes.")),
        Set.of());

    assertThat(summary.tickets()).isEqualTo("EXAMPLE-3456");
    assertThat(summary.owners()).isEqualTo("Example Team");
  }

  /**
   * Verifies owner values are deduplicated after splitting semicolon-separated annotation cells.
   */
  @Test
  void deduplicatesOwnersAcrossCompoundAnnotationValues() {
    var annotations = new FailureAnnotations(List.of(
        new FailureAnnotation(
            "Szenario",
            "A_21275-01",
            "",
            "demo.feature",
            "Fehlgeschlagenes Szenario",
            Set.of("EXAMPLE-1"),
            "tech@spree; EY"),
        new FailureAnnotation(
            "Szenario",
            "A_21275-01",
            "",
            "demo.feature",
            "Fehlgeschlagenes Szenario",
            Set.of("EXAMPLE-2"),
            "EY; achelos; gematik")));

    var summary = annotations.findForRequirement(
        "A_21275-01",
        Set.of(),
        Set.of("Fehlgeschlagenes Szenario"));

    assertThat(summary.tickets()).isEqualTo("EXAMPLE-1; EXAMPLE-2");
    assertThat(summary.owners()).isEqualTo("tech@spree; EY; achelos; gematik");
  }

  /**
   * Invoke the private aggregate writer used at the end of a Cucumber run.
   *
   * @param plugin runtime coverage plugin under test
   */
  private void invokeMergeAndWriteCsv(RuntimeCoveragePlugin plugin) {
    try {
      var method = RuntimeCoveragePlugin.class.getDeclaredMethod("mergeAndWriteCsv");
      method.setAccessible(true);
      method.invoke(plugin);
    } catch (NoSuchMethodException exception) {
      throw new AssertionError("Runtime coverage aggregate writer is missing", exception);
    } catch (IllegalAccessException exception) {
      throw new AssertionError("Runtime coverage aggregate writer is not accessible", exception);
    } catch (InvocationTargetException exception) {
      var cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new AssertionError("Runtime coverage aggregate writer failed", cause);
    }
  }

  /**
   * Temporarily set a system property while running an action.
   *
   * @param key    property key
   * @param value  temporary property value
   * @param action action to run
   */
  private void withSystemProperty(String key, String value, ThrowingRunnable action)
      throws Exception {
    var previous = System.getProperty(key);
    System.setProperty(key, value);
    try {
      action.run();
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  /**
   * Runnable variant that permits checked exceptions.
   */
  @FunctionalInterface
  private interface ThrowingRunnable {

    /**
     * Run the action.
     */
    void run() throws Exception;
  }
}
