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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.plugin.JsonFormatter;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.Plugin;
import io.cucumber.plugin.event.EventHandler;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link RuntimeCoveragePlugin}.
 */
class RuntimeCoveragePluginTest {

  @TempDir
  private Path tempDir;

  /**
   * Verifies CSV rows retain catalogue requirements, titles and runtime counters.
   */
  @Test
  void writesRequirementCoverageFromAsciidocCatalogues() throws Exception {
    var requirements = Files.createDirectories(tempDir.resolve("afos/gemSpec_Krypt"));
    Files.writeString(requirements.resolve("GS-A_5526.adoc"), """
        [#GS-A_5526]
        ==== GS-A_5526 - TLS-Anforderung
        """);
    Files.writeString(requirements.resolve("A_12345-01.adoc"), """
        [#A_12345-01]
        ==== A_12345-01 - Weitere Anforderung
        """);
    Files.writeString(requirements.resolve("readme.adoc"), "== Anforderungskatalog\n");
    var aspects = Files.createDirectories(tempDir.resolve("testaspekte/gemSpec_Krypt/GS-A_5526"));
    Files.writeString(aspects.resolve("TA_GS-A_5526_01.adoc"), """
        [#TA_GS-A_5526_01]
        ===== TA_GS-A_5526_01 - Erster Testaspekt
        """);
    Files.writeString(aspects.resolve("TA_GS-A_5526_02.adoc"), """
        [#TA_GS-A_5526_02]
        ===== TA_GS-A_5526_02 TLS-Test - Zweiter Testaspekt
        """);
    var catalogue = tempDir.resolve("requirements.xml");
    Files.writeString(catalogue, """
        <requirements>
          <requirement id="GS-A_5526">
            <title>TLS-Anforderung</title>
            <testProcedure>Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"</testProcedure>
          </requirement>
        </requirements>
        """);
    var catalog = new RuntimeCoverageCatalog(catalogue, tempDir.resolve("afos"),
        tempDir.resolve("testaspekte"));
    assertThat(catalog.testAspectInfo("TA_GS-A_5526_02").title()).isEqualTo("Zweiter Testaspekt");
    var repository = new RuntimeCoverageCsvRepository(catalog, FailureAnnotations.empty());
    var output = tempDir.resolve("runtime_coverage.csv");
    repository.writeCsv(output,
        Map.of("GS-A_5526", Rollup.fromCounts(1, 1, 0, 0),
            "A_99999", Rollup.fromCounts(1, 0, 1, 0)),
        Map.of("TA_GS-A_5526_01", Rollup.fromCounts(1, 1, 0, 0)),
        Map.of(), Map.of(RuntimeCoverageCsvRepository.META_TOTAL_ID,
            Rollup.fromCounts(2, 1, 1, 0)), List.of());

    try (var reader = Files.newBufferedReader(output)) {
      assertThat(reader.read()).isEqualTo('\ufeff');
      try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
          .get().parse(reader)) {
        assertThat(parser.getHeaderNames()).hasSize(15);
        var rows = parser.getRecords();
        assertThat(rows).allSatisfy(row -> assertThat(row.isConsistent()).isTrue());
        assertThat(rows).extracting(row -> row.get("Anforderung")).containsExactly(
            "A_12345-01", "GS-A_5526", "A_99999", "__SUMMARY__", "__UNTAGGED_SCENARIOS__");
        var pending = rows.getFirst();
        assertThat(pending.get("Titel")).isEqualTo("Weitere Anforderung");
        assertThat(pending.get("Laufzeit-Status")).isEqualTo("nicht ausgeführt");
        assertThat(pending.get("Testaspekte gesamt")).isEqualTo("0");
        var covered = rows.get(1);
        assertThat(covered.get("Titel")).isEqualTo("TLS-Anforderung");
        assertThat(covered.get("Laufzeit-Status")).isEqualTo("bestanden");
        assertThat(covered.get("Szenarien bestanden")).isEqualTo("1");
        assertThat(covered.get("Testaspekte gesamt")).isEqualTo("2");
        assertThat(covered.get("Testaspekte bestanden")).isEqualTo("1");
        assertThat(covered.get("Testaspekte mit Laufzeitergebnis")).isEqualTo("1");
        var unknown = rows.get(2);
        assertThat(unknown.get("Titel")).isEqualTo("N/A");
        assertThat(unknown.get("Laufzeit-Status")).isEqualTo("fehlgeschlagen");
        assertThat(unknown.get("Szenarien fehlgeschlagen")).isEqualTo("1");
        assertThat(rows.get(3).get("Szenarien gesamt")).isEqualTo("2");
        assertThat(rows.getLast().get("Szenarien gesamt")).isEqualTo("0");
      }
    }
  }

  /**
   * Verifies JSON-only results are collected after the JSON formatter closes its report.
   *
   * @param jsonFirst whether the JSON formatter is registered before the coverage plugin
   * @param threads number of Cucumber execution threads
   */
  @ParameterizedTest
  @CsvSource({"true, 1", "false, 1", "true, 2", "false, 2"})
  void readsCompletedJsonRegardlessOfPluginOrder(boolean jsonFirst, int threads) throws Exception {
    var feature = tempDir.resolve("completion with spaces.feature");
    Files.writeString(feature, """
        @TA_A_25663_01
        Feature: Completion
          Scenario: Event scenario
            Given an undefined step
          Scenario: JSON-only scenario
            Given an undefined step
        """);
    var cucumberDir = Files.createDirectory(tempDir.resolve("cucumber"));
    var annotations = tempDir.resolve("annotations.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,completion with spaces.feature,JSON-only scenario,EXAMPLE-JSON,JSON Team
        """);
    var output = tempDir.resolve("runtime_coverage.csv");
    withSystemProperty("serenity.outputDirectory", tempDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () ->
            withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(), () -> {
              var plugin = new RuntimeCoveragePlugin(output.toString());
              ConcurrentEventListener coverage = publisher -> {
                var filtered = mock(EventPublisher.class, delegatesTo(publisher));
                doAnswer(invocation -> {
                  EventHandler<TestCaseFinished> handler = invocation.getArgument(1);
                  publisher.registerHandlerFor(TestCaseFinished.class, event -> {
                    if (!"JSON-only scenario".equals(event.getTestCase().getName())) {
                      handler.receive(event);
                    }
                  });
                  return null;
                }).when(filtered).registerHandlerFor(eq(TestCaseFinished.class), any());
                plugin.setEventPublisher(filtered);
              };
              try (var jsonOutput = Files.newOutputStream(cucumberDir.resolve("run.json"))) {
                var json = new JsonFormatter(jsonOutput);
                var plugins = jsonFirst ? new Plugin[] {json, coverage}
                    : new Plugin[] {coverage, json};
                var options = new CommandlineOptionsParser(System.out)
                    .parse("--dry-run", "--threads", Integer.toString(threads), feature.toString())
                    .build();
                Runtime.builder().withRuntimeOptions(options).withBackendSupplier(List::of)
                    .withAdditionalPlugins(plugins).build().run();
              }
            })));

    for (var path : List.of(output, tempDir.resolve("runtime_coverage_testaspects.csv"))) {
      try (var reader = Files.newBufferedReader(path);
          var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
              .get().parse(reader)) {
        var id = path.equals(output) ? "A_25663" : "TA_A_25663_01";
        var row = parser.getRecords().stream().filter(record -> id.equals(record.get(0)))
            .findFirst().orElseThrow();
        assertThat(row.get("Szenarien gesamt")).isEqualTo("2");
        assertThat(row.get("Scenarios")).isEqualTo("Event scenario | JSON-only scenario");
        assertThat(row.get("Tickets")).isEqualTo("EXAMPLE-JSON");
      }
    }
    assertThat(Files.readString(tempDir.resolve(RuntimeFailureAnnotationAudit.REPORT_FILE)))
        .contains("JSON-only scenario", RuntimeFailureAnnotationAudit.EXECUTION_INCOMPLETE)
        .doesNotContain(RuntimeFailureAnnotationAudit.NOT_EXECUTED);
    try (var reader = Files.newBufferedReader(tempDir.resolve("runtime_coverage_meta.csv"));
        var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
            .get().parse(reader)) {
      assertThat(parser.getRecords().getFirst().get("Szenarien gesamt")).isEqualTo("2");
    }
    assertThat(tempDir.resolve("runtime_coverage_run_count.txt")).doesNotExist();
    assertThat(tempDir.resolve("runtime_coverage_state_scenario_outcomes.csv")).doesNotExist();
  }

  /**
   * Verifies native file URIs and relative report paths identify the same scenario.
   *
   * @param relativePath feature path relative to the working directory
   */
  @ParameterizedTest
  @ValueSource(strings = {"features/demo.feature", "features/with spaces/übung.feature"})
  void matchesScenarioKeysAcrossFileUriForms(String relativePath) {
    var feature = Path.of(relativePath).toAbsolutePath().normalize();
    var expected = RuntimeCoverageIds.buildScenarioKey(relativePath, "Check", 36);
    for (var uri : List.of(feature.toUri().toString(),
        feature.toUri().toString().replace("file:///", "file:/"),
        "file:" + relativePath, feature.toString())) {
      assertThat(RuntimeCoverageIds.buildScenarioKey(uri, "Check", 36))
          .as("scenario key for %s", uri).isEqualTo(expected);
    }
    assertThat(RuntimeCoverageIds.normalizePath(feature.toUri().toString()))
        .isEqualTo(feature.toString().replace('\\', '/'));
  }

  /**
   * Verifies Unix absolute paths and existing relative or classpath forms retain their meaning.
   *
   * @param input feature URI or path
   * @param expected normalized feature path
   */
  @ParameterizedTest
  @CsvSource({
      "file:///home/test/project/demo.feature, /home/test/project/demo.feature",
      "file:/home/test/project/demo.feature, /home/test/project/demo.feature",
      "file:src/test/resources/demo.feature, src/test/resources/demo.feature",
      "classpath:features/demo.feature, features/demo.feature",
      "features/demo.feature, features/demo.feature"
  })
  void preservesFeaturePathForms(String input, String expected) {
    assertThat(RuntimeCoverageIds.normalizePath(input)).isEqualTo(expected);
  }

  /**
   * Verifies runtime CSV rows are enriched from the internal annotation CSV.
   *
   * @param featureUri Cucumber feature URI from a source or packaged run
   */
  @ParameterizedTest
  @ValueSource(strings = {"features/demo.feature", "classpath:features/demo.feature"})
  void enrichesFailedRuntimeCoverageRowsWithInternalAnnotations(String featureUri) throws Exception {
    var cucumberDir = tempDir.resolve("cucumber");
    Files.createDirectories(cucumberDir);
    Files.writeString(cucumberDir.resolve("run.json"), """
        [
          {
            "uri": "%s",
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
        """.formatted(featureUri), StandardCharsets.UTF_8);

    var annotations = tempDir.resolve("runtime_failure_annotations.internal.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,src/test/resources/features/demo.feature,"Rotes Szenario",EXAMPLE-1234,Example Team
        Szenario,src/test/resources/features/demo.feature,"Gruenes Szenario",EXAMPLE-GREEN,Green Team
        """, StandardCharsets.UTF_8);

    var output = tempDir.resolve("runtime_coverage.csv");
    withSystemProperty("serenity.outputDirectory", tempDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () ->
            withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(), () -> {
              var plugin = new RuntimeCoveragePlugin(output.toString());
              invokeMergeAndWriteCsv(plugin);
            })));

    var requirementCsv = Files.readString(output, StandardCharsets.UTF_8);
    var testAspectCsv = Files.readString(tempDir.resolve("runtime_coverage_testaspects.csv"),
        StandardCharsets.UTF_8);

    assertThat(requirementCsv.lines().findFirst()).hasValue(
        "\ufeffAnforderung,Titel,Laufzeit-Status,Szenarien gesamt,"
            + "Szenarien bestanden,Szenarien fehlgeschlagen,Szenarien übersprungen,"
            + "Testaspekte gesamt,Testaspekte bestanden,Testaspekte Quote,"
            + "Testaspekte mit Laufzeitergebnis,Fehler Testrun,Tickets,Verantwortlich,Scenarios");
    assertThat(requirementCsv).doesNotContain("Klassifizierung");
    assertThat(requirementCsv).doesNotContain("Schweregrad");
    assertThat(requirementCsv).doesNotContain("Aktualisiert am");
    assertThat(requirementCsv).contains("A_25663");
    assertThat(requirementCsv).contains("teilweise fehlgeschlagen");
    assertThat(requirementCsv).contains("EXAMPLE-1234");
    assertThat(requirementCsv).contains("Example Team");
    assertThat(requirementCsv).doesNotContain("EXAMPLE-GREEN", "Green Team");
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
    assertThat(testAspectCsv).doesNotContain("EXAMPLE-GREEN", "Green Team");
    assertThat(testAspectCsv).contains("Rotes Szenario");
    assertThat(testAspectCsv).contains("Gruenes Szenario");
    var auditCsv = Files.readString(tempDir.resolve("runtime_failure_annotation_check.csv"),
        StandardCharsets.UTF_8);
    assertThat(auditCsv).contains(RuntimeFailureAnnotationAudit.FAILURE_WITH_TICKET);
    assertThat(auditCsv).contains(RuntimeFailureAnnotationAudit.ANNOTATION_ACTIVE);
    assertThat(auditCsv).doesNotContain(RuntimeFailureAnnotationAudit.FAILURE_WITHOUT_TICKET)
        .doesNotContain(RuntimeFailureAnnotationAudit.NOT_EXECUTED);
    assertThat(Files.readString(tempDir.resolve("runtime_failure_annotation_check.status"),
        StandardCharsets.UTF_8)).isEqualTo("PASS\n");
  }

  /**
   * Verifies metadata uses the proxy resolved from Tiger's effective configuration.
   */
  @Test
  void writesConfiguredProxyToMetadata() throws Exception {
    var output = tempDir.resolve("runtime_coverage_meta.csv");
    try (var configuration = mockStatic(TigerGlobalConfiguration.class)) {
      configuration.when(() -> TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId"))
          .thenReturn(Optional.of("http://tiger-proxy.test.svc:9999"));
      var repository = new RuntimeCoverageCsvRepository(new RuntimeCoverageCatalog(),
          FailureAnnotations.empty());
      repository.writeMetaCsv(output, Map.of());
    }
    assertThat(Files.readString(output)).contains("http://tiger-proxy.test.svc:9999");
  }

  /**
   * Verifies conflicting JSON result sources are merged conservatively.
   */
  @Test
  void mergesConflictingCucumberJsonOutcomes() throws Exception {
    var cucumberDir = tempDir.resolve("cucumber-conflicting");
    Files.createDirectories(cucumberDir);
    var passedJson = """
        [
          {
            "uri": "src/test/resources/features/demo.feature",
            "elements": [
              {
                "type": "scenario",
                "name": "Konflikt-Szenario",
                "line": 4,
                "steps": [
                  {"result": {"status": "passed"}}
                ]
              }
            ]
          }
        ]
        """;
    var absoluteFeature = Path.of("src/test/resources/features/demo.feature").toAbsolutePath()
        .toString().replace('\\', '/');
    var failedJson = passedJson
        .replace("src/test/resources/features/demo.feature", "file:" + absoluteFeature)
        .replace("passed", "failed");
    Files.writeString(cucumberDir.resolve("a-passed.json"), passedJson, StandardCharsets.UTF_8);
    Files.writeString(cucumberDir.resolve("b-failed.json"), failedJson, StandardCharsets.UTF_8);

    var annotations = tempDir.resolve("conflicting-runtime_failure_annotations.internal.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,demo.feature,Konflikt-Szenario,EXAMPLE-1234,Example Team
        """, StandardCharsets.UTF_8);
    var output = tempDir.resolve("conflicting-runtime_coverage.csv");

    withSystemProperty("serenity.outputDirectory", tempDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () ->
            withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(), () -> {
              var plugin = new RuntimeCoveragePlugin(output.toString());
              invokeMergeAndWriteCsv(plugin);
            })));

    var auditCsv = Files.readString(tempDir.resolve(
        RuntimeFailureAnnotationAudit.REPORT_FILE), StandardCharsets.UTF_8);
    assertThat(auditCsv).contains(RuntimeFailureAnnotationAudit.FAILURE_WITH_TICKET)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /**
   * Verifies an unknown Cucumber JSON status is never interpreted as passed.
   */
  @Test
  void reportsUnknownCucumberJsonStatusAsIncomplete() throws Exception {
    var cucumberDir = tempDir.resolve("cucumber-incomplete");
    Files.createDirectories(cucumberDir);
    Files.writeString(cucumberDir.resolve("run.json"), """
        [
          {
            "uri": "demo.feature",
            "elements": [
              {
                "type": "scenario",
                "name": "Unvollständiges JSON-Szenario",
                "line": 4,
                "steps": [
                  {"result": {"status": "pending"}}
                ]
              }
            ]
          }
        ]
        """, StandardCharsets.UTF_8);
    var annotations = tempDir.resolve("incomplete-runtime_failure_annotations.internal.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,demo.feature,Unvollständiges JSON-Szenario,EXAMPLE-5678,Example Team
        """, StandardCharsets.UTF_8);
    var output = tempDir.resolve("incomplete-runtime_coverage.csv");

    withSystemProperty("serenity.outputDirectory", tempDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () ->
            withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(), () -> {
              var plugin = new RuntimeCoveragePlugin(output.toString());
              invokeMergeAndWriteCsv(plugin);
            })));

    var auditCsv = Files.readString(tempDir.resolve(
        RuntimeFailureAnnotationAudit.REPORT_FILE), StandardCharsets.UTF_8);
    assertThat(auditCsv).contains(RuntimeFailureAnnotationAudit.EXECUTION_INCOMPLETE)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /**
   * Verifies a failed Cucumber hook is evaluated as a scenario failure.
   */
  @Test
  void detectsFailedHookInCucumberJson() throws Exception {
    var cucumberDir = tempDir.resolve("cucumber-hook-failure");
    Files.createDirectories(cucumberDir);
    Files.writeString(cucumberDir.resolve("run.json"), """
        [
          {
            "uri": "demo.feature",
            "elements": [
              {
                "type": "scenario",
                "name": "Fehler im Hook-Szenario",
                "line": 4,
                "before": [
                  {"result": {"status": "failed", "error_message": "hook failed"}}
                ],
                "steps": [
                  {"result": {"status": "passed"}}
                ]
              }
            ]
          }
        ]
        """, StandardCharsets.UTF_8);
    var annotations = tempDir.resolve("hook-runtime_failure_annotations.internal.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,demo.feature,Fehler im Hook-Szenario,EXAMPLE-6789,Example Team
        """, StandardCharsets.UTF_8);
    var output = tempDir.resolve("hook-runtime_coverage.csv");

    withSystemProperty("serenity.outputDirectory", tempDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () ->
            withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(), () -> {
              var plugin = new RuntimeCoveragePlugin(output.toString());
              invokeMergeAndWriteCsv(plugin);
            })));

    var auditCsv = Files.readString(tempDir.resolve(
        RuntimeFailureAnnotationAudit.REPORT_FILE), StandardCharsets.UTF_8);
    assertThat(auditCsv).contains(RuntimeFailureAnnotationAudit.FAILURE_WITH_TICKET)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /**
   * Verifies the audit remains in the Serenity directory when coverage uses a custom path.
   */
  @Test
  void writesAuditToSerenityDirectoryForCustomCoveragePath() throws Exception {
    var cucumberDir = tempDir.resolve("cucumber-custom-output");
    var coverageDir = tempDir.resolve("coverage");
    var serenityDir = tempDir.resolve("serenity");
    Files.createDirectories(cucumberDir);
    var output = coverageDir.resolve("runtime_coverage.csv");

    withSystemProperty("serenity.outputDirectory", serenityDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () -> {
          var plugin = new RuntimeCoveragePlugin(output.toString());
          invokeMergeAndWriteCsv(plugin);
        }));

    assertThat(Files.exists(serenityDir.resolve(RuntimeFailureAnnotationAudit.REPORT_FILE))).isTrue();
    assertThat(Files.exists(serenityDir.resolve(RuntimeFailureAnnotationAudit.STATUS_FILE))).isTrue();
    assertThat(Files.exists(coverageDir.resolve(RuntimeFailureAnnotationAudit.REPORT_FILE))).isFalse();
  }

  /**
   * Verifies stale scenario state is discarded when a new run starts without active drivers.
   */
  @Test
  void discardsStaleScenarioOutcomeStateAtRunStart() throws Exception {
    var coverageDir = tempDir.resolve("coverage-stale-state");
    var cucumberDir = tempDir.resolve("cucumber-stale-state");
    final var serenityDir = tempDir.resolve("serenity-stale-state");
    Files.createDirectories(coverageDir);
    Files.createDirectories(cucumberDir);
    Files.writeString(coverageDir.resolve("runtime_coverage_state_scenario_outcomes.csv"), """
        key,feature,scenario,line,status
        demo.feature#4#Altes Szenario,demo.feature,Altes Szenario,4,PASSED
        """, StandardCharsets.UTF_8);
    var annotations = tempDir.resolve("stale-runtime_failure_annotations.internal.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,demo.feature,Altes Szenario,EXAMPLE-9012,Example Team
        """, StandardCharsets.UTF_8);
    var output = coverageDir.resolve("runtime_coverage.csv");

    withSystemProperty("serenity.outputDirectory", serenityDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", cucumberDir.toString(), () ->
            withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(), () -> {
              var plugin = new RuntimeCoveragePlugin(output.toString());
              invokeMergeAndWriteCsv(plugin);
            })));

    var auditCsv = Files.readString(serenityDir.resolve(
        RuntimeFailureAnnotationAudit.REPORT_FILE), StandardCharsets.UTF_8);
    assertThat(auditCsv).contains(RuntimeFailureAnnotationAudit.NOT_EXECUTED)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /**
   * Verifies skipped scenarios retain annotations without requiring failure records.
   */
  @Test
  void enrichesSkippedTestAspectRowsWithoutFailureRecords() {
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
        List.of(new ScenarioOutcome("demo.feature",
            "Rollback bei fehlgeschlagener Aktualisierung stellt stabile Vorversion wieder her",
            4, Status.SKIPPED, Set.of("A_25788"), Set.of("TA_A_25788_01"))));

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
        Set.of(),
        List.of(new ScenarioOutcome(
            "src/test/resources/features/UserStory_TLS/UseCase_02/tls_client.feature",
            "TLS-1.3-Verbindungen - <client_name> - Signature-Schemes.",
            4, Status.FAILED, Set.of("A_21275-01"), Set.of("TA_A_21275-01_05"))));

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
        List.of(new ScenarioOutcome(
            "src/test/resources/features/UserStory_TLS/UseCase_02/tls_client.feature",
            "TLS-1.3-Verbindungen - ZETA Native Client - Signature-Schemes.",
            4, Status.FAILED, Set.of("A_21275-01"), Set.of("TA_A_21275-01_05"))));

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
        List.of(new ScenarioOutcome("demo.feature", "Fehlgeschlagenes Szenario", 4,
            Status.FAILED, Set.of("A_21275-01"), Set.of())));

    assertThat(summary.tickets()).isEqualTo("EXAMPLE-1; EXAMPLE-2");
    assertThat(summary.owners()).isEqualTo("tech@spree; EY; achelos; gematik");
  }

  /**
   * Verifies annotations follow the status of the matching execution.
   *
   * @param status scenario status
   */
  @ParameterizedTest
  @EnumSource(Status.class)
  void annotatesOnlyNonPassingExecutions(Status status) {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Szenario", "", "", "demo.feature", "Check", Set.of("EXAMPLE-1"), "Team")));
    var outcome = new ScenarioOutcome("demo.feature", "Check", 4, status,
        Set.of("A_25663"), Set.of("TA_A_25663_01"));
    var requirement = annotations.findForRequirement("A_25663", Set.of(), List.of(outcome));
    var aspect = annotations.findForTestAspect("TA_A_25663_01", "A_25663",
        List.of(outcome));
    var expected = status == Status.PASSED ? AnnotationSummary.empty()
        : new AnnotationSummary("EXAMPLE-1", "Team");
    assertThat(requirement).isEqualTo(expected);
    assertThat(aspect).isEqualTo(expected);
  }

  /**
   * Verifies feature-only annotations respect execution status, feature paths and tag scope.
   *
   * @param status scenario status
   */
  @ParameterizedTest
  @EnumSource(Status.class)
  void scopesFeatureOnlyAnnotationsToNonPassingExecutions(Status status) {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Szenario", "", "", "demo.feature", "", Set.of("EXAMPLE-1"), "Team")));
    var outcomes = List.of(
        new ScenarioOutcome("features/demo.feature", "Check", 4, status,
            Set.of("A_25663"), Set.of("TA_A_25663_01")),
        new ScenarioOutcome("features/demo.feature", "Green", 8, Status.PASSED,
            Set.of("A_99999"), Set.of("TA_A_99999_01")),
        new ScenarioOutcome("features/foreign.feature", "Check", 4, Status.FAILED,
            Set.of("A_99999"), Set.of("TA_A_99999_01")),
        new ScenarioOutcome("features/notdemo.feature", "Check", 4, Status.FAILED,
            Set.of("A_99999"), Set.of("TA_A_99999_01")));
    var expected = status == Status.PASSED ? AnnotationSummary.empty()
        : new AnnotationSummary("EXAMPLE-1", "Team");

    assertThat(annotations.findForRequirement("A_25663", Set.of(), outcomes)).isEqualTo(expected);
    assertThat(annotations.findForTestAspect("TA_A_25663_01", "A_25663", outcomes))
        .isEqualTo(expected);
    assertThat(annotations.findForRequirement("A_99999", Set.of(), outcomes))
        .isEqualTo(AnnotationSummary.empty());
    assertThat(annotations.findForTestAspect("TA_A_99999_01", "A_99999", outcomes))
        .isEqualTo(AnnotationSummary.empty());
  }

  /**
   * Verifies other features and traceability scopes cannot contribute scenario annotations.
   */
  @Test
  void excludesAnnotationsFromOtherFeaturesAndScopes() {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Szenario", "", "", "demo.feature", "Check", Set.of("EXAMPLE-1"), "Team")));
    var outcomes = List.of(
        new ScenarioOutcome("demo.feature", "Check", 4, Status.PASSED,
            Set.of("A_25663"), Set.of("TA_A_25663_01")),
        new ScenarioOutcome("foreign.feature", "Check", 4, Status.SKIPPED,
            Set.of("A_25663"), Set.of("TA_A_25663_01")),
        new ScenarioOutcome("demo.feature", "Check", 8, Status.SKIPPED,
            Set.of("A_99999"), Set.of("TA_A_99999_01")));
    assertThat(annotations.findForRequirement("A_25663", Set.of(), outcomes))
        .isEqualTo(AnnotationSummary.empty());
    assertThat(annotations.findForTestAspect("TA_A_25663_01", "A_25663", outcomes))
        .isEqualTo(AnnotationSummary.empty());
    assertThat(annotations.findForRequirement("A_99999", Set.of(), outcomes).tickets())
        .isEqualTo("EXAMPLE-1");
  }

  /**
   * Verifies persisted and merged driver outcomes retain their traceability identifiers.
   */
  @Test
  void persistsAndMergesScenarioTraceability() {
    var first = new ScenarioOutcome("demo.feature", "Check", 4, Status.PASSED,
        Set.of("A_25663"), Set.of("TA_A_25663_01"));
    var second = new ScenarioOutcome("demo.feature", "Check", 4, Status.SKIPPED,
        Set.of("A_25664"), Set.of("TA_A_25664_01"));
    var merged = first.merge(second);
    var repository = new RuntimeCoverageCsvRepository(new RuntimeCoverageCatalog(),
        FailureAnnotations.empty());
    var state = tempDir.resolve("outcomes.csv");
    repository.writeScenarioOutcomes(state, Map.of(merged.key(), merged));
    assertThat(repository.readScenarioOutcomes(state)).containsEntry(merged.key(), merged);
    assertThat(merged.status()).isEqualTo(Status.SKIPPED);
    assertThat(merged.requirementIds()).containsExactlyInAnyOrder("A_25663", "A_25664");
    assertThat(merged.testAspectIds())
        .containsExactlyInAnyOrder("TA_A_25663_01", "TA_A_25664_01");
  }

  /**
   * Verifies event results retain scoped annotations across driver aggregation.
   *
   * @param statusName non-passing scenario status
   * @param annotationScenario scenario name, or empty for a feature-only CSV annotation
   */
  @ParameterizedTest
  @CsvSource({"FAILED, Red", "SKIPPED, Red", "UNDEFINED, Red",
      "FAILED, ''", "SKIPPED, ''", "UNDEFINED, ''"})
  void annotatesNonPassingEventsAcrossDrivers(String statusName, String annotationScenario)
      throws Exception {
    var annotations = tempDir.resolve("annotations.csv");
    Files.writeString(annotations, """
        Zuordnung,Feature,Szenario,Tickets,Verantwortlich
        Szenario,demo.feature,Green,EXAMPLE-GREEN,Green Team
        Szenario,demo.feature,%s,EXAMPLE-RED,Red Team
        """.formatted(annotationScenario));
    var output = tempDir.resolve("runtime_coverage.csv");
    withSystemProperty("serenity.outputDirectory", tempDir.toString(), () ->
        withSystemProperty("zeta.cucumber.outputDirectory", tempDir.resolve("empty").toString(),
            () -> withSystemProperty("zeta.runtime.failure.annotations.csv", annotations.toString(),
                () -> {
                  var first = new RuntimeCoveragePlugin(output.toString());
                  var second = new RuntimeCoveragePlugin(output.toString());
                  var method = RuntimeCoveragePlugin.class.getDeclaredMethod(
                      "handleTestCaseFinished", TestCaseFinished.class);
                  method.setAccessible(true);
                  for (var name : List.of("Green", "Red")) {
                    var testCase = mock(TestCase.class);
                    when(testCase.getUri()).thenReturn(URI.create("features/demo.feature"));
                    when(testCase.getName()).thenReturn(name);
                    when(testCase.getTags()).thenReturn(List.of("Green".equals(name)
                        ? "@TA_A_25663_01" : "@TA_A_25663_02"));
                    var status = "Green".equals(name) ? Status.PASSED : Status.valueOf(statusName);
                    var result = new Result(status, Duration.ZERO,
                        status == Status.FAILED ? new AssertionError("boom") : null);
                    method.invoke("Green".equals(name) ? first : second,
                        new TestCaseFinished(Instant.EPOCH, testCase, result));
                  }
                  invokeMergeAndWriteCsv(first);
                  invokeMergeAndWriteCsv(second);
                })));
    for (var path : List.of(output, tempDir.resolve("runtime_coverage_testaspects.csv"))) {
      assertThat(Files.readString(path)).contains("EXAMPLE-RED", "Red Team", "Green", "Red")
          .doesNotContain("EXAMPLE-GREEN", "Green Team");
      try (var reader = Files.newBufferedReader(path);
          var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
              .get().parse(reader)) {
        var rows = parser.getRecords();
        var expected = path.equals(output) ? Map.of("A_25663", "Green | Red")
            : Map.of("TA_A_25663_01", "Green", "TA_A_25663_02", "Red");
        for (var entry : expected.entrySet()) {
          var row = rows.stream().filter(record -> entry.getKey().equals(record.get(0)))
              .findFirst().orElseThrow();
          assertThat(row.get("Scenarios")).isEqualTo(entry.getValue());
          assertThat(row.get("Szenarien gesamt")).isEqualTo(path.equals(output) ? "2" : "1");
          assertThat(row.get("Tickets")).isEqualTo("Green".equals(entry.getValue())
              ? "" : "EXAMPLE-RED");
        }
      }
    }
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
