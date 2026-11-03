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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.cucumber.plugin.event.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads and writes runtime coverage CSV files.
 */
@Slf4j
final class RuntimeCoverageCsvRepository {

  static final String META_TOTAL_ID = "__ALL_SCENARIOS__";
  static final String META_UNTAGGED_ID = "__UNTAGGED_SCENARIOS__";
  static final String NA_VALUE = "N/A";
  private static final String SUMMARY_ID = "__SUMMARY__";
  private static final DateTimeFormatter META_TIME_FORMAT =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
  private static final String[] CSV_HEADERS = {
      "Anforderung",
      "Titel",
      "Laufzeit-Status",
      "Szenarien gesamt",
      "Szenarien bestanden",
      "Szenarien fehlgeschlagen",
      "Szenarien übersprungen",
      "Testaspekte gesamt",
      "Testaspekte bestanden",
      "Testaspekte Quote",
      "Testaspekte mit Laufzeitergebnis",
      "Fehler Testrun",
      "Tickets",
      "Verantwortlich",
      "Scenarios"
  };
  private static final String[] TEST_ASPECT_HEADERS = {
      "Testaspekt",
      "Anforderung",
      "Titel",
      "Laufzeit-Status",
      "Szenarien gesamt",
      "Szenarien bestanden",
      "Szenarien fehlgeschlagen",
      "Szenarien übersprungen",
      "Tickets",
      "Verantwortlich",
      "Fehler Testrun",
      "Scenarios"
  };
  private static final String[] META_HEADERS = {
      "Zeitpunkt (UTC)",
      "PROFILE",
      "TigerProxyId",
      "Cucumber-Filter",
      "Szenarien gesamt",
      "Szenarien bestanden",
      "Szenarien fehlgeschlagen",
      "Szenarien übersprungen",
      "Szenarien ohne AFO/TA"
  };
  private static final String[] ROLLUP_STATE_HEADERS = {
      "id",
      "gesamt",
      "bestanden",
      "fehlgeschlagen",
      "uebersprungen"
  };
  private static final String[] ERROR_STATE_HEADERS = {
      "id",
      "error",
      "testaspekte",
      "feature",
      "szenario"
  };
  private static final String[] SCENARIO_OUTCOME_STATE_HEADERS = {
      "key",
      "feature",
      "scenario",
      "line",
      "status",
      "anforderungen",
      "testaspekte"
  };

  private final RuntimeCoverageCatalog catalog;
  private final FailureAnnotations failureAnnotations;

  /**
   * Create a repository for the current catalog and its coverage annotations.
   *
   * @param catalog            requirement and test-aspect catalog
   * @param failureAnnotations annotation catalog for coverage CSV columns
   */
  RuntimeCoverageCsvRepository(RuntimeCoverageCatalog catalog,
      FailureAnnotations failureAnnotations) {
    this.catalog = catalog;
    this.failureAnnotations = failureAnnotations;
  }

  /**
   * Create a CSV format with a fixed header.
   *
   * @param headers CSV headers
   * @return CSV format
   */
  static CSVFormat csvFormat(String... headers) {
    return CSVFormat.DEFAULT.builder()
        .setHeader(headers)
        .get();
  }

  /**
   * Return the CSV record field at the given index or null.
   *
   * @param record CSV record
   * @param index  field index
   * @return field value or null
   */
  static String getField(CSVRecord record, int index) {
    if (record == null || index < 0 || index >= record.size()) {
      return null;
    }
    return record.get(index);
  }

  /**
   * Find a header value ignoring case.
   *
   * @param fields header fields
   * @param value  expected header name
   * @return header index or -1
   */
  static int indexOf(ArrayList<String> fields, String value) {
    for (var i = 0; i < fields.size(); i++) {
      if (value.equalsIgnoreCase(fields.get(i).trim())) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Split a semicolon-separated CSV field into normalized values.
   *
   * @param value field value
   * @return ordered set of non-empty values
   */
  static Set<String> parseMultiValue(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Pattern.compile(";")
        .splitAsStream(value)
        .map(String::trim)
        .filter(part -> !part.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Load the private failure annotation CSV if it exists.
   *
   * @param path annotation CSV path
   * @return parsed annotation catalogue
   */
  static FailureAnnotations loadFailureAnnotations(Path path) {
    if (path == null || !Files.exists(path)) {
      return FailureAnnotations.empty();
    }
    var annotations = new ArrayList<FailureAnnotation>();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        var parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()
            .parse(reader)) {
      var headerFields = new ArrayList<>(parser.getHeaderMap().keySet());
      if (headerFields.isEmpty()) {
        return FailureAnnotations.empty();
      }
      headerFields.set(0, RuntimeCoverageIds.stripBom(headerFields.getFirst()));
      var matchTypeIndex = indexOf(headerFields, "zuordnung");
      var requirementIndex = indexOf(headerFields, "anforderung");
      var testAspectIndex = indexOf(headerFields, "testaspekt");
      var featureIndex = indexOf(headerFields, "feature");
      var scenarioIndex = indexOf(headerFields, "szenario");
      var ticketsIndex = indexOf(headerFields, "tickets");
      var ownerIndex = indexOf(headerFields, "verantwortlich");

      for (var record : parser) {
        annotations.add(new FailureAnnotation(
            getField(record, matchTypeIndex),
            getField(record, requirementIndex),
            getField(record, testAspectIndex),
            getField(record, featureIndex),
            RuntimeCoverageIds.defaultString(getField(record, scenarioIndex)).trim(),
            parseMultiValue(getField(record, ticketsIndex)),
            RuntimeCoverageIds.defaultString(getField(record, ownerIndex)).trim()));
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read runtime failure annotations", exception);
    }
    log.info("Runtime failure annotations loaded from {} ({} entries)", path, annotations.size());
    return new FailureAnnotations(annotations);
  }

  /**
   * Format errors for CSV output.
   *
   * @param errors failure records
   * @return rendered error summary
   */
  private static String formatErrors(Set<FailureRecord> errors) {
    if (errors == null || errors.isEmpty()) {
      return "";
    }
    return errors.stream()
        .map(FailureRecord::error)
        .collect(Collectors.joining(" | "));
  }

  /**
   * Format executed scenario names for CSV output.
   *
   * @param outcomes scoped scenario outcomes
   * @return rendered scenario list
   */
  private static String formatScenarios(Collection<ScenarioOutcome> outcomes) {
    return outcomes.stream()
        .map(ScenarioOutcome::scenario)
        .filter(name -> !name.isBlank())
        .distinct()
        .sorted()
        .collect(Collectors.joining(" | "));
  }

  /**
   * Sort string values for stable CSV output.
   *
   * @param values raw values
   * @return sorted non-empty values
   */
  private static List<String> sortedValues(Set<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream()
        .map(RuntimeCoverageIds::defaultString)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .sorted()
        .toList();
  }

  /**
   * Render the test-aspect coverage percentage.
   *
   * @param coverage two-element array with total and passed count
   * @return rendered percentage or N/A
   */
  private static String renderCoverage(int[] coverage) {
    if (coverage[0] == 0) {
      return NA_VALUE;
    }
    var percent = Math.round((coverage[1] * 100.0f) / coverage[0]);
    return percent + "%";
  }

  /**
   * Check whether an output row should be enriched with annotations.
   *
   * @param rollup runtime counters
   * @return true for failed or skipped runtime rows
   */
  private static boolean hasReportableAnnotationStatus(Rollup rollup) {
    return rollup != null && (rollup.failed() > 0 || rollup.skipped() > 0);
  }

  /**
   * Read aggregated run state from disk.
   *
   * @param stateFile state file path
   * @return rollups by identifier
   */
  Map<String, Rollup> readState(Path stateFile) {
    if (!Files.exists(stateFile)) {
      return new LinkedHashMap<>();
    }
    Map<String, Rollup> state = new LinkedHashMap<>();
    try (var reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8);
        var parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()
            .parse(reader)) {
      for (var record : parser) {
        if (record.size() < 5) {
          continue;
        }
        var id = record.get(0);
        if (id == null || id.isBlank()) {
          continue;
        }
        state.put(id, Rollup.fromCounts(
            RuntimeCoverageIds.parseInt(record.get(1)),
            RuntimeCoverageIds.parseInt(record.get(2)),
            RuntimeCoverageIds.parseInt(record.get(3)),
            RuntimeCoverageIds.parseInt(record.get(4))));
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read runtime coverage state", exception);
    }
    return state;
  }

  /**
   * Persist aggregated run state to disk.
   *
   * @param stateFile state file path
   * @param state     rollups by identifier
   */
  void writeState(Path stateFile, Map<String, Rollup> state) {
    try {
      Files.createDirectories(stateFile.toAbsolutePath().getParent());
      try (var writer = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8);
          var printer = csvFormat(ROLLUP_STATE_HEADERS).print(writer)) {
        for (var entry : state.entrySet()) {
          var rollup = entry.getValue();
          printer.printRecord(entry.getKey(), rollup.total(), rollup.passed(), rollup.failed(),
              rollup.skipped());
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write runtime coverage state", exception);
    }
  }

  /**
   * Read stored error summaries from disk.
   *
   * @param stateFile error state file path
   * @return failure records by requirement
   */
  Map<String, Set<FailureRecord>> readErrors(Path stateFile) {
    if (!Files.exists(stateFile)) {
      return new LinkedHashMap<>();
    }
    Map<String, Set<FailureRecord>> errors = new LinkedHashMap<>();
    try (var reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8);
        var parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()
            .parse(reader)) {
      for (var record : parser) {
        if (record.size() < 2) {
          continue;
        }
        var id = RuntimeCoverageIds.defaultString(record.get(0)).trim();
        var error = RuntimeCoverageIds.defaultString(getField(record, 1)).trim();
        if (!id.isBlank() && !error.isBlank()) {
          var testAspects = record.size() > 2 ? parseMultiValue(getField(record, 2)) : Set.<String>of();
          var feature = record.size() > 3
              ? RuntimeCoverageIds.defaultString(getField(record, 3)).trim() : "";
          var scenario = record.size() > 4
              ? RuntimeCoverageIds.defaultString(getField(record, 4)).trim() : "";
          errors.computeIfAbsent(id, key -> new LinkedHashSet<>())
              .add(new FailureRecord(error, testAspects, feature, scenario));
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read runtime coverage errors", exception);
    }
    return errors;
  }

  /**
   * Persist error summaries to disk.
   *
   * @param stateFile error state file path
   * @param errors    failure records by requirement
   */
  void writeErrors(Path stateFile, Map<String, Set<FailureRecord>> errors) {
    try {
      Files.createDirectories(stateFile.toAbsolutePath().getParent());
      try (var writer = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8);
          var printer = csvFormat(ERROR_STATE_HEADERS).print(writer)) {
        for (var entry : errors.entrySet()) {
          var id = entry.getKey();
          for (var error : entry.getValue()) {
            printer.printRecord(id, error.error(), String.join(";", error.testAspectIds()),
                error.feature(), error.scenario());
          }
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write runtime coverage errors", exception);
    }
  }

  /**
   * Read persisted scenario outcomes from disk.
   *
   * @param stateFile scenario outcome state file
   * @return outcomes by stable scenario identity
   */
  Map<String, ScenarioOutcome> readScenarioOutcomes(Path stateFile) {
    if (!Files.exists(stateFile)) {
      return new LinkedHashMap<>();
    }
    var outcomes = new LinkedHashMap<String, ScenarioOutcome>();
    try (var reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8);
        var parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()
            .parse(reader)) {
      for (var record : parser) {
        if (record.size() < 5) {
          continue;
        }
        var feature = RuntimeCoverageIds.defaultString(getField(record, 1)).trim();
        var scenario = RuntimeCoverageIds.defaultString(getField(record, 2)).trim();
        if (feature.isBlank() && scenario.isBlank()) {
          continue;
        }
        var outcome = new ScenarioOutcome(feature, scenario,
            parseLine(getField(record, 3)), parseStatus(getField(record, 4)),
            parseMultiValue(getField(record, 5)), parseMultiValue(getField(record, 6)));
        outcomes.merge(outcome.key(), outcome, ScenarioOutcome::merge);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read runtime scenario outcomes", exception);
    }
    return outcomes;
  }

  /**
   * Persist scenario outcomes for the final run aggregation.
   *
   * @param stateFile scenario outcome state file
   * @param outcomes  outcomes by stable scenario identity
   */
  void writeScenarioOutcomes(Path stateFile, Map<String, ScenarioOutcome> outcomes) {
    try {
      Files.createDirectories(stateFile.toAbsolutePath().getParent());
      var temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
      try (var writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8);
          var printer = csvFormat(SCENARIO_OUTCOME_STATE_HEADERS).print(writer)) {
        outcomes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
              var outcome = entry.getValue();
              try {
                printer.printRecord(entry.getKey(), outcome.feature(), outcome.scenario(),
                    outcome.line() == null ? "" : outcome.line(), outcome.status().name(),
                    String.join(";", sortedValues(outcome.requirementIds())),
                    String.join(";", sortedValues(outcome.testAspectIds())));
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            });
      }
      Files.move(temporaryFile, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write runtime scenario outcomes", exception);
    }
  }

  /**
   * Parse an optional scenario line number.
   *
   * @param value line value
   * @return parsed line or null
   */
  private static Integer parseLine(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  /**
   * Parse a persisted Cucumber status.
   *
   * @param value status value
   * @return parsed status or unknown
   */
  private static Status parseStatus(String value) {
    try {
      return Status.valueOf(RuntimeCoverageIds.defaultString(value).trim()
          .toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return Status.UNDEFINED;
    }
  }

  /**
   * Write the meta CSV with run information.
   *
   * @param outputFile target file
   * @param mergedMeta merged meta counters
   * @throws IOException when the file cannot be written
   */
  void writeMetaCsv(Path outputFile, Map<String, Rollup> mergedMeta) throws IOException {
    var tempFile = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
    var total = mergedMeta.getOrDefault(META_TOTAL_ID, Rollup.empty());
    var untagged = mergedMeta.getOrDefault(META_UNTAGGED_ID, Rollup.empty());
    var timestamp = META_TIME_FORMAT.format(Instant.now());
    var profile = Optional.ofNullable(System.getProperty("PROFILE"))
        .orElse(System.getenv("PROFILE"));
    var proxyId = TigerGlobalConfiguration.readStringOptional("tiger.tigerProxy.proxyId")
        .orElse(System.getenv("TIGER_PROXY_ID"));
    var cucumberFilter = Optional.ofNullable(System.getProperty("cucumber.filter.tags"))
        .orElse(System.getenv("CUCUMBER_FILTER_TAGS"));

    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8);
        var printer = csvFormat(META_HEADERS).print(writer)) {
      printer.printRecord(timestamp, RuntimeCoverageIds.defaultString(profile),
          RuntimeCoverageIds.defaultString(proxyId), RuntimeCoverageIds.defaultString(cucumberFilter),
          total.total(), total.passed(), total.failed(), total.skipped(), untagged.total());
    }
    Files.move(tempFile, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Write the runtime coverage CSV.
   *
   * @param outputFile         target file
   * @param mergedRequirements merged AFO state
   * @param mergedTestAspects  merged test aspect state
   * @param mergedErrors       merged failure records
   * @param mergedMeta         merged meta counters
   * @param outcomes           merged scenario outcomes with traceability identifiers
   * @throws IOException when the file cannot be written
   */
  void writeCsv(Path outputFile,
      Map<String, Rollup> mergedRequirements,
      Map<String, Rollup> mergedTestAspects,
      Map<String, Set<FailureRecord>> mergedErrors,
      Map<String, Rollup> mergedMeta, Collection<ScenarioOutcome> outcomes) throws IOException {
    var tempFile = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      writer.write('\ufeff');
      try (var printer = csvFormat(CSV_HEADERS).print(writer)) {
        writeRequirements(printer, mergedRequirements, mergedTestAspects, mergedErrors,
            mergedMeta, outcomes);
      }
    }
    Files.move(tempFile, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Write the runtime coverage CSV for test aspects.
   *
   * @param outputFile        target file
   * @param mergedTestAspects merged test aspect state
   * @param mergedErrors      merged failure records
   * @param outcomes         merged scenario outcomes with traceability identifiers
   * @throws IOException when the file cannot be written
   */
  void writeTestAspectCsv(Path outputFile,
      Map<String, Rollup> mergedTestAspects,
      Map<String, Set<FailureRecord>> mergedErrors,
      Collection<ScenarioOutcome> outcomes)
      throws IOException {
    var tempFile = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      writer.write('\ufeff');
      try (var printer = csvFormat(TEST_ASPECT_HEADERS).print(writer)) {
        Set<String> ids = new LinkedHashSet<>(catalog.testAspectIds());
        mergedTestAspects.keySet().stream()
            .filter(id -> !ids.contains(id))
            .sorted()
            .forEach(ids::add);

        for (var id : ids) {
          writeTestAspectRow(printer, id, mergedTestAspects, mergedErrors, outcomes);
        }
      }
    }
    Files.move(tempFile, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Write one test-aspect output row.
   *
   * @param printer           CSV output sink
   * @param id                test aspect id
   * @param mergedTestAspects merged test aspect state
   * @param mergedErrors      merged failure records
   * @param outcomes         merged scenario outcomes with traceability identifiers
   * @throws IOException when the row cannot be written
   */
  private void writeTestAspectRow(CSVPrinter printer,
      String id,
      Map<String, Rollup> mergedTestAspects,
      Map<String, Set<FailureRecord>> mergedErrors,
      Collection<ScenarioOutcome> outcomes)
      throws IOException {
    var info = catalog.testAspectInfo(id);
    var requirementId = info != null ? info.requirementId()
        : RuntimeCoverageIds.resolveRequirementFromTestAspect(id).orElse("");
    var title = info == null ? "" : info.title();
    var rollup = mergedTestAspects.getOrDefault(id, Rollup.empty());
    var failures = failureRecordsForTestAspect(id, requirementId, mergedErrors);
    var scopedOutcomes = outcomes.stream()
        .filter(outcome -> outcome.testAspectIds().contains(id))
        .toList();
    var annotationSummary = hasReportableAnnotationStatus(rollup)
        ? failureAnnotations.findForTestAspect(id, requirementId, scopedOutcomes)
        : AnnotationSummary.empty();
    printer.printRecord(id, requirementId.isBlank() ? NA_VALUE : requirementId,
        title.isBlank() ? NA_VALUE : title, rollup.status(), rollup.total(), rollup.passed(),
        rollup.failed(), rollup.skipped(), annotationSummary.tickets(),
        annotationSummary.owners(), formatErrors(failures), formatScenarios(scopedOutcomes));
  }

  /**
   * Select failure records that are relevant for a test aspect row.
   *
   * @param testAspectId  test aspect identifier
   * @param requirementId owning requirement identifier
   * @param mergedErrors  merged failure records by requirement
   * @return matching failure records
   */
  private Set<FailureRecord> failureRecordsForTestAspect(String testAspectId,
      String requirementId,
      Map<String, Set<FailureRecord>> mergedErrors) {
    if (mergedErrors == null || mergedErrors.isEmpty()) {
      return Set.of();
    }
    var normalizedTestAspect = RuntimeCoverageIds.normalizeId(testAspectId);
    var normalizedRequirement = RuntimeCoverageIds.normalizeId(requirementId);
    var candidates = mergedErrors.getOrDefault(normalizedRequirement, Set.of());
    return candidates.stream()
        .filter(record -> record.testAspectIds().contains(normalizedTestAspect))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Write AFO rows using merged runtime results.
   *
   * @param printer            CSV output sink
   * @param mergedRequirements merged AFO state
   * @param mergedTestAspects  merged test aspect state
   * @param mergedErrors       merged failure records
   * @param mergedMeta         merged meta counters
   * @param outcomes           merged scenario outcomes with traceability identifiers
   * @throws IOException when a row cannot be written
   */
  private void writeRequirements(CSVPrinter printer,
      Map<String, Rollup> mergedRequirements,
      Map<String, Rollup> mergedTestAspects,
      Map<String, Set<FailureRecord>> mergedErrors,
      Map<String, Rollup> mergedMeta, Collection<ScenarioOutcome> outcomes) throws IOException {
    Set<String> ids = new LinkedHashSet<>(catalog.requirementIds());
    mergedRequirements.keySet().stream()
        .filter(id -> !ids.contains(id))
        .sorted()
        .forEach(ids::add);

    for (var id : ids) {
      var rollup = mergedRequirements.getOrDefault(id, Rollup.empty());
      var testAspectCoverage = catalog.computeTestAspectCoverage(id, mergedTestAspects);
      var testAspectReported = catalog.computeTestAspectReportedCount(id, mergedTestAspects);
      var failures = mergedErrors.get(id);
      var scopedOutcomes = outcomes.stream()
          .filter(outcome -> outcome.requirementIds().contains(id))
          .toList();
      var annotationSummary = hasReportableAnnotationStatus(rollup)
          ? failureAnnotations.findForRequirement(id, failures, scopedOutcomes)
          : AnnotationSummary.empty();
      writeRow(printer, id, catalog.requirementTitle(id).orElse(NA_VALUE),
          rollup.status(), rollup.total(),
          rollup.passed(), rollup.failed(), rollup.skipped(), testAspectCoverage[0],
          testAspectCoverage[1], renderCoverage(testAspectCoverage), testAspectReported,
          formatErrors(failures), annotationSummary.tickets(), annotationSummary.owners(),
          formatScenarios(scopedOutcomes));
    }

    writeSummaryRows(printer, mergedMeta);
  }

  /**
   * Write one requirement CSV row.
   *
   * @param printer               CSV output sink
   * @param id                    requirement id
   * @param title                 requirement title
   * @param runtimeStatus         runtime status
   * @param total                 total scenarios
   * @param passed                passed scenarios
   * @param failed                failed scenarios
   * @param skipped               skipped scenarios
   * @param testAspectTotal       total test aspects
   * @param testAspectPassed      passed test aspects
   * @param testAspectCoverage    test aspect coverage
   * @param testAspectReported    test aspects with a recorded scenario result
   * @param errorSummary          runtime error summary
   * @param tickets               annotation tickets
   * @param owner                 annotation owner
   * @param scenarios             executed scenario names
   * @throws IOException when the row cannot be written
   */
  private void writeRow(CSVPrinter printer,
      String id,
      String title,
      String runtimeStatus,
      int total,
      int passed,
      int failed,
      int skipped,
      Integer testAspectTotal,
      Integer testAspectPassed,
      String testAspectCoverage,
      Integer testAspectReported,
      String errorSummary,
      String tickets,
      String owner,
      String scenarios) throws IOException {
    printer.printRecord(id, title, runtimeStatus, total, passed, failed,
        skipped, testAspectTotal == null ? "" : testAspectTotal,
        testAspectPassed == null ? "" : testAspectPassed, testAspectCoverage,
        testAspectReported == null ? "" : testAspectReported, errorSummary, tickets,
        owner, scenarios);
  }

  /**
   * Write summary rows for total and untagged scenarios.
   *
   * @param printer    CSV output sink
   * @param mergedMeta merged meta counters
   * @throws IOException when a row cannot be written
   */
  private void writeSummaryRows(CSVPrinter printer, Map<String, Rollup> mergedMeta)
      throws IOException {
    var total = mergedMeta.getOrDefault(META_TOTAL_ID, Rollup.empty());
    var untagged = mergedMeta.getOrDefault(META_UNTAGGED_ID, Rollup.empty());

    writeRow(printer, SUMMARY_ID, "Szenarien gesamt (alle)", total.status(),
        total.total(), total.passed(), total.failed(), total.skipped(), null, null, NA_VALUE, null,
        "", "", "", "");

    writeRow(printer, META_UNTAGGED_ID, "Szenarien ohne AFO/TA Tags", untagged.status(),
        untagged.total(), untagged.passed(), untagged.failed(), untagged.skipped(), null, null,
        NA_VALUE, null, "", "", "", "");
  }
}
