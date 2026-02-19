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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestRunFinished;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects runtime status for AFOs and test aspects based on scenario tags and writes a CSV report.
 */
@Slf4j
public final class RuntimeCoveragePlugin implements ConcurrentEventListener {

  private static final Pattern AFO_TAG = Pattern.compile(
      "^(?:[A-Z0-9]+-)?A_\\d+(?:-\\d+)?(?:_\\d+)?$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern TEST_ASPECT_TAG = Pattern.compile(
      "^TA_(?:[A-Z0-9]+-)?A_\\d+(?:-\\d+)?_\\d+$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern TEST_ASPECT_REQUIREMENT = Pattern.compile(
      "TA_((?:[A-Z0-9]+-)?A_\\d+(?:-\\d+)?)(?:_\\d+)?",
      Pattern.CASE_INSENSITIVE);
  private static final String DEFAULT_OUTPUT = "target/traceability/runtime_coverage.csv";
  private static final Path PRODUCT_STATUS_CSV =
      Path.of("docs/asciidoc/tables/product_implementation.csv");
  private static final Path TEST_ASPECTS_ROOT = Path.of("docs/asciidoc/testaspekte");
  private static final String CUCUMBER_OUTPUT_DIR_PROPERTY = "zeta.cucumber.outputDirectory";
  private static final String DEFAULT_CUCUMBER_OUTPUT_DIR = "target/cucumber-parallel";
  private static final String REQUIREMENT_STATE_FILE = "runtime_coverage_state_afo.csv";
  private static final String TEST_ASPECT_STATE_FILE = "runtime_coverage_state_ta.csv";
  private static final String ERROR_STATE_FILE = "runtime_coverage_state_errors.csv";
  private static final String META_STATE_FILE = "runtime_coverage_state_meta.csv";
  private static final String LOCK_FILE = "runtime_coverage.lock";
  private static final String RUN_COUNTER_FILE = "runtime_coverage_run_count.txt";
  private static final String META_OUTPUT_FILE = "runtime_coverage_meta.csv";
  private static final String TEST_ASPECT_OUTPUT_FILE = "runtime_coverage_testaspects.csv";
  private static final String META_TOTAL_ID = "__ALL_SCENARIOS__";
  private static final String META_UNTAGGED_ID = "__UNTAGGED_SCENARIOS__";
  private static final String SUMMARY_ID = "__SUMMARY__";
  private static final String NA_VALUE = "N/A";
  private static final DateTimeFormatter META_TIME_FORMAT =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
  private static final List<String> CSV_HEADERS = List.of(
      "Anforderung",
      "Titel",
      "Produkt umgesetzt",
      "Laufzeit-Status",
      "Szenarien gesamt",
      "Szenarien bestanden",
      "Szenarien fehlgeschlagen",
      "Szenarien übersprungen",
      "Testaspekte gesamt",
      "Testaspekte bestanden",
      "Testaspekte Quote",
      "Testaspekte implementiert",
      "Fehler Testrun");
  private static final List<String> TEST_ASPECT_HEADERS = List.of(
      "Testaspekt",
      "Anforderung",
      "Titel",
      "Laufzeit-Status",
      "Szenarien gesamt",
      "Szenarien bestanden",
      "Szenarien fehlgeschlagen",
      "Szenarien übersprungen");
  private static final List<String> META_HEADERS = List.of(
      "Zeitpunkt (UTC)",
      "PROFILE",
      "TigerProxyId",
      "Cucumber-Filter",
      "Szenarien gesamt",
      "Szenarien bestanden",
      "Szenarien fehlgeschlagen",
      "Szenarien übersprungen",
      "Szenarien ohne AFO/TA");

  private final Map<String, Rollup> requirementStats = new ConcurrentHashMap<>();
  private final Map<String, Rollup> testAspectStats = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> runErrors = new ConcurrentHashMap<>();
  private final Set<String> seenScenarioKeys = ConcurrentHashMap.newKeySet();
  private final Rollup totalStats = new Rollup();
  private final Rollup untaggedStats = new Rollup();
  private final Map<String, ProductInfo> productInfo = new LinkedHashMap<>();
  private final Map<String, TestAspectInfo> testAspectCatalog = new LinkedHashMap<>();
  private final Path outputPath;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Creates the plugin using the resolved default output location.
   */
  public RuntimeCoveragePlugin() {
    this(resolveOutputPath());
  }

  /**
   * Creates the plugin with the explicit output path.
   *
   * @param outputPath target file for the runtime CSV report
   */
  public RuntimeCoveragePlugin(String outputPath) {
    this.outputPath = Path.of(outputPath);
    loadProductInfo();
    loadTestAspectCatalog();
    registerRunInstance();
  }

  /**
   * Resolve the target path from system properties, environment, or Serenity settings.
   */
  private static String resolveOutputPath() {
    var explicit = Optional.ofNullable(System.getProperty("zeta.runtime.coverage.csv"))
        .filter(value -> !value.isBlank())
        .orElseGet(() -> Optional.ofNullable(System.getenv("ZETA_RUNTIME_COVERAGE_CSV"))
            .filter(value -> !value.isBlank())
            .orElse(null));
    if (explicit != null) {
      return explicit;
    }
    var serenityDir = Optional.ofNullable(System.getProperty("serenity.outputDirectory"))
        .filter(value -> !value.isBlank())
        .orElse(null);
    if (serenityDir == null) {
      serenityDir = loadSerenityOutputDirectory();
    }
    if (serenityDir != null) {
      return Path.of(serenityDir, "runtime_coverage.csv").toString();
    }
    return DEFAULT_OUTPUT;
  }

  /**
   * Map a Cucumber result status to the internal scenario status.
   *
   * @param event finished test case event
   * @return classified status
   */
  private static ScenarioStatus classifyStatus(TestCaseFinished event) {
    var status = event.getResult().getStatus();
    return switch (status) {
      case PASSED -> ScenarioStatus.PASSED;
      case SKIPPED -> ScenarioStatus.SKIPPED;
      default -> ScenarioStatus.FAILED;
    };
  }

  /**
   * Update the shared run counter and return the new value.
   *
   * @param counterPath counter file path
   * @param delta       delta to apply
   * @return updated counter value
   */
  private static int updateRunCounter(Path counterPath, int delta) throws IOException {
    var current = readRunCounter(counterPath);
    var next = Math.max(0, current + delta);
    Files.createDirectories(counterPath.toAbsolutePath().getParent());
    try (var writer = Files.newBufferedWriter(counterPath, StandardCharsets.UTF_8)) {
      writer.write(Integer.toString(next));
    }
    return next;
  }

  /**
   * Read the current run counter value.
   *
   * @param counterPath counter file path
   * @return current counter value
   */
  private static int readRunCounter(Path counterPath) throws IOException {
    if (!Files.exists(counterPath)) {
      return 0;
    }
    try (var reader = Files.newBufferedReader(counterPath, StandardCharsets.UTF_8)) {
      return parseInt(reader.readLine());
    }
  }

  /**
   * Delete temporary aggregation state files once the run completes.
   *
   * @param requirementStatePath AFO state file path
   * @param testAspectStatePath  test aspect state file path
   * @param counterPath          run counter file path
   */
  private static void cleanupStateFiles(Path requirementStatePath,
      Path testAspectStatePath,
      Path errorStatePath,
      Path counterPath) {
    try {
      Files.deleteIfExists(requirementStatePath);
      Files.deleteIfExists(testAspectStatePath);
      Files.deleteIfExists(errorStatePath);
      Files.deleteIfExists(counterPath);
    } catch (IOException exception) {
      log.warn("Unable to delete runtime coverage state files.", exception);
    }
  }

  /**
   * Extract the test aspect title from its Asciidoc header.
   */
  private static String readTestAspectTitle(Path path, String id) {
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      for (var i = 0; i < 5 && (line = reader.readLine()) != null; i++) {
        if (line.contains(id) && line.contains(" - ")) {
          return line.substring(line.indexOf(" - ") + 3).trim();
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read test aspect title from " + path, exception);
    }
    return "";
  }

  /**
   * Resolve the AFO id from a TA identifier.
   */
  private static Optional<String> resolveRequirementFromTestAspect(String testAspectId) {
    var matcher = TEST_ASPECT_REQUIREMENT.matcher(testAspectId);
    return matcher.find() ? Optional.ofNullable(matcher.group(1)) : Optional.empty();
  }

  /**
   * Remove the leading @ from a tag.
   */
  private static String stripTagPrefix(String tagName) {
    return tagName.startsWith("@") ? tagName.substring(1) : tagName;
  }

  /**
   * Normalize a tag for matching.
   */
  private static String normalizeTag(String tagName) {
    return tagName == null ? "" : stripTagPrefix(tagName.trim()).toUpperCase();
  }

  /**
   * Remove the suffix if present.
   */
  private static String stripSuffix(String value, String suffix) {
    return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
  }

  /**
   * Find a header value ignoring case.
   */
  private static int indexOf(List<String> fields, String value) {
    for (var i = 0; i < fields.size(); i++) {
      if (value.equalsIgnoreCase(fields.get(i).trim())) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Return the field at the given index or null.
   */
  private static String getField(List<String> fields, int index) {
    if (index < 0 || index >= fields.size()) {
      return null;
    }
    return fields.get(index);
  }

  /**
   * Convert null to empty string.
   */
  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  /**
   * Escape values for CSV output.
   */
  private static String csv(String value) {
    var safe = defaultString(value);
    if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
      return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    return safe;
  }

  /**
   * Read the Serenity output directory from serenity.properties if available.
   */
  private static String loadSerenityOutputDirectory() {
    var serenityProperties = Path.of("serenity.properties");
    if (!Files.exists(serenityProperties)) {
      return null;
    }
    var properties = new Properties();
    try (var input = Files.newInputStream(serenityProperties)) {
      properties.load(input);
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read serenity.properties", exception);
    }
    var outputDir = properties.getProperty("serenity.outputDirectory");
    if (outputDir == null || outputDir.isBlank()) {
      return null;
    }
    return outputDir.trim();
  }

  /**
   * Parse a CSV line with simple quote handling.
   */
  private static List<String> parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    var current = new StringBuilder();
    var inQuotes = false;
    for (var i = 0; i < line.length(); i++) {
      var c = line.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == ',' && !inQuotes) {
        fields.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    fields.add(current.toString());
    return fields;
  }

  /**
   * Read aggregated run state from disk.
   */
  private static Map<String, Rollup> readState(Path stateFile) {
    if (!Files.exists(stateFile)) {
      return new LinkedHashMap<>();
    }
    Map<String, Rollup> state = new LinkedHashMap<>();
    try (var reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
      var header = reader.readLine();
      if (header == null) {
        return state;
      }
      String line;
      while ((line = reader.readLine()) != null) {
        var fields = parseCsvLine(line);
        if (fields.size() < 5) {
          continue;
        }
        var id = fields.get(0);
        if (id == null || id.isBlank()) {
          continue;
        }
        var total = parseInt(fields.get(1));
        var passed = parseInt(fields.get(2));
        var failed = parseInt(fields.get(3));
        var skipped = parseInt(fields.get(4));
        state.put(id, Rollup.fromCounts(total, passed, failed, skipped));
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read runtime coverage state", exception);
    }
    return state;
  }

  /**
   * Persist aggregated run state to disk.
   */
  private static void writeState(Path stateFile, Map<String, Rollup> state) {
    try {
      Files.createDirectories(stateFile.toAbsolutePath().getParent());
      try (var writer = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
        writer.write("id,gesamt,bestanden,fehlgeschlagen,uebersprungen");
        writer.write(System.lineSeparator());
        for (var entry : state.entrySet()) {
          var rollup = entry.getValue();
          writer.append(csv(entry.getKey())).append(',')
              .append(Integer.toString(rollup.total())).append(',')
              .append(Integer.toString(rollup.passed())).append(',')
              .append(Integer.toString(rollup.failed())).append(',')
              .append(Integer.toString(rollup.skipped()))
              .append(System.lineSeparator());
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write runtime coverage state", exception);
    }
  }

  /**
   * Merge per-run stats into the shared state map.
   */
  private static void mergeState(Map<String, Rollup> target, Map<String, Rollup> additions) {
    for (var entry : additions.entrySet()) {
      var rollup = target.computeIfAbsent(entry.getKey(), key -> new Rollup());
      rollup.add(entry.getValue());
    }
  }

  /**
   * Read stored error summaries from disk (per requirement).
   */
  private static Map<String, Set<String>> readErrors(Path stateFile) {
    if (!Files.exists(stateFile)) {
      return new LinkedHashMap<>();
    }
    Map<String, Set<String>> errors = new LinkedHashMap<>();
    try (var reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
      var header = reader.readLine();
      if (header == null) {
        return errors;
      }
      String line;
      while ((line = reader.readLine()) != null) {
        var fields = parseCsvLine(line);
        if (fields.size() < 2) {
          continue;
        }
        var id = defaultString(fields.get(0)).trim();
        var error = defaultString(fields.get(1)).trim();
        if (!id.isBlank() && !error.isBlank()) {
          errors.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(error);
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read runtime coverage errors", exception);
    }
    return errors;
  }

  /**
   * Persist error summaries to disk (per requirement).
   */
  private static void writeErrors(Path stateFile, Map<String, Set<String>> errors) {
    try {
      Files.createDirectories(stateFile.toAbsolutePath().getParent());
      try (var writer = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
        writer.write("id,error");
        writer.write(System.lineSeparator());
        for (var entry : errors.entrySet()) {
          var id = entry.getKey();
          for (var error : entry.getValue()) {
            writer.append(csv(id)).append(',')
                .append(csv(error))
                .append(System.lineSeparator());
          }
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write runtime coverage errors", exception);
    }
  }

  /**
   * Merge error summaries (per requirement).
   */
  private static void mergeErrors(Map<String, Set<String>> target,
      Map<String, Set<String>> additions) {
    if (additions == null || additions.isEmpty()) {
      return;
    }
    additions.forEach(
        (key1, value) -> target.computeIfAbsent(key1, key -> new LinkedHashSet<>())
            .addAll(value));
  }

  /**
   * Format errors for CSV output.
   */
  private static String formatErrors(Set<String> errors) {
    if (errors == null || errors.isEmpty()) {
      return "";
    }
    return String.join(" | ", errors);
  }

  /**
   * Summarize a failed scenario error.
   */
  private static String summarizeError(TestCaseFinished event) {
    var scenarioName = event.getTestCase() == null ? "" : event.getTestCase().getName();
    var error = event.getResult() == null ? null : event.getResult().getError();
    var detail = "";
    if (error != null) {
      var message = Optional.ofNullable(error.getMessage()).orElse("").trim();
      if (message.isBlank()) {
        detail = error.getClass().getSimpleName();
      } else {
        detail = error.getClass().getSimpleName() + ": " + message;
      }
    }
    if (detail.isBlank()) {
      detail = "Unbekannter Fehler";
    }
    if (scenarioName == null || scenarioName.isBlank()) {
      return detail;
    }
    return scenarioName + " -> " + detail;
  }

  /**
   * Build a stable scenario key for deduplication.
   */
  private static String buildScenarioKey(String uri, String name, Integer line, List<String> tags) {
    var normalizedUri = uri == null ? "" : uri.trim();
    var normalizedName = name == null ? "" : name.trim();
    var normalizedLine = line == null ? "" : Integer.toString(line);
    var normalizedTags = tags == null ? "" : tags.stream()
        .filter(Objects::nonNull)
        .map(RuntimeCoveragePlugin::normalizeTag)
        .sorted()
        .collect(Collectors.joining("|"));
    return normalizedUri + "#" + normalizedLine + "#" + normalizedName + "#" + normalizedTags;
  }

  /**
   * Parse an integer or fall back to zero.
   */
  private static int parseInt(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  /**
   * Register the plugin handlers with the Cucumber event publisher.
   *
   * @param publisher event publisher provided by Cucumber
   */
  @Override
  public void setEventPublisher(EventPublisher publisher) {
    log.debug("Runtime coverage plugin registered (output: {}).", outputPath);
    publisher.registerHandlerFor(TestCaseFinished.class, this::handleTestCaseFinished);
    publisher.registerHandlerFor(TestRunFinished.class, this::handleTestRunFinished);
  }

  /**
   * Capture scenario results and map them to AFOs and test aspects via tags.
   *
   * @param event finished test case event
   */
  private void handleTestCaseFinished(TestCaseFinished event) {
    var status = classifyStatus(event);
    if (log.isDebugEnabled()) {
      var testCase = event.getTestCase();
      var name = testCase == null ? "<unknown>" : testCase.getName();
      var tags = testCase == null ? List.of() : testCase.getTags();
      log.debug("Runtime coverage saw scenario '{}' with status {} and {} tags.",
          name, status, tags.size());
    }

    var testCase = event.getTestCase();
    if (testCase == null) {
      totalStats.record(status);
      untaggedStats.record(status);
      return;
    }
    var tags = testCase.getTags();
    var scenarioKey = buildScenarioKey(
        testCase.getUri() == null ? null : testCase.getUri().toString(),
        testCase.getName(),
        testCase.getLocation() == null ? null : testCase.getLocation().getLine(),
        tags);
    seenScenarioKeys.add(scenarioKey);

    var requirementIds = new LinkedHashSet<String>();
    var testAspectIds = new LinkedHashSet<String>();
    tags.stream()
        .filter(Objects::nonNull)
        .forEach(tagName -> {
          var testAspectId = normalizeTag(tagName);
          if (AFO_TAG.matcher(testAspectId).matches()) {
            requirementIds.add(testAspectId);
          } else if (TEST_ASPECT_TAG.matcher(testAspectId).matches()) {
            testAspectIds.add(testAspectId);
            resolveRequirementFromTestAspect(testAspectId).ifPresent(requirementIds::add);
          }
        });

    if (status == ScenarioStatus.FAILED) {
      var error = summarizeError(event);
      requirementIds.forEach(id -> addError(id, error));
    }

    totalStats.record(status);
    if (requirementIds.isEmpty() && testAspectIds.isEmpty()) {
      untaggedStats.record(status);
    }

    requirementIds.forEach(id -> requirementStats
        .computeIfAbsent(id, key -> new Rollup())
        .record(status));
    testAspectIds.forEach(id -> testAspectStats
        .computeIfAbsent(id, key -> new Rollup())
        .record(status));
  }

  /**
   * Finalizes the aggregated output when the test run finishes.
   *
   * @param event test run finished event
   */
  private void handleTestRunFinished(TestRunFinished event) {
    log.debug("Runtime coverage test run finished. Requirements: {}, test aspects: {}, errors: {}.",
        requirementStats.size(), testAspectStats.size(), countErrors(runErrors));
    mergeAndWriteCsv();
  }

  /**
   * Merge per-driver state and write the consolidated CSV output.
   */
  private void mergeAndWriteCsv() {
    try {
      tryPopulateFromCucumberJson();
      var outputDir = resolveOutputDir();
      var requirementStatePath = outputDir.resolve(REQUIREMENT_STATE_FILE);
      var testAspectStatePath = outputDir.resolve(TEST_ASPECT_STATE_FILE);
      var errorStatePath = outputDir.resolve(ERROR_STATE_FILE);
      var metaStatePath = outputDir.resolve(META_STATE_FILE);
      var lockPath = outputDir.resolve(LOCK_FILE);
      var counterPath = outputDir.resolve(RUN_COUNTER_FILE);

      try (var channel = FileChannel.open(lockPath,
          StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          var ignored = channel.lock()) {
        var mergedRequirements = readState(requirementStatePath);
        mergeState(mergedRequirements, requirementStats);
        writeState(requirementStatePath, mergedRequirements);

        var mergedTestAspects = readState(testAspectStatePath);
        mergeState(mergedTestAspects, testAspectStats);
        writeState(testAspectStatePath, mergedTestAspects);

        var mergedErrors = readErrors(errorStatePath);
        mergeErrors(mergedErrors, runErrors);
        writeErrors(errorStatePath, mergedErrors);

        var mergedMeta = readState(metaStatePath);
        mergeState(mergedMeta, buildMetaStats());
        writeState(metaStatePath, mergedMeta);

        writeCsv(outputPath, mergedRequirements, mergedTestAspects, mergedErrors, mergedMeta);
        writeTestAspectCsv(outputDir.resolve(TEST_ASPECT_OUTPUT_FILE), mergedTestAspects);
        writeMetaCsv(outputDir.resolve(META_OUTPUT_FILE), mergedMeta);
        log.info("Runtime coverage CSV written to {}", outputPath);

        var remainingRuns = updateRunCounter(counterPath, -1);
        if (remainingRuns == 0) {
          cleanupStateFiles(requirementStatePath, testAspectStatePath, errorStatePath, counterPath);
          Files.deleteIfExists(metaStatePath);
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to write runtime coverage CSV", exception);
    }
  }

  /**
   * Fallback: load runtime results from the Cucumber JSON output if no events were recorded.
   */
  private void tryPopulateFromCucumberJson() {
    var outputDir = Optional.ofNullable(System.getProperty(CUCUMBER_OUTPUT_DIR_PROPERTY))
        .filter(value -> !value.isBlank())
        .map(Path::of)
        .orElse(Path.of(DEFAULT_CUCUMBER_OUTPUT_DIR));
    if (!Files.isDirectory(outputDir)) {
      log.debug("Runtime coverage fallback skipped; cucumber output dir not found: {}.",
          outputDir.toAbsolutePath());
      return;
    }
    try (var paths = Files.list(outputDir)) {
      paths.filter(path -> path.getFileName().toString().endsWith(".json"))
          .forEach(this::readCucumberJson);
    } catch (IOException exception) {
      log.warn("Unable to read cucumber output from {} for runtime coverage fallback.",
          outputDir.toAbsolutePath(), exception);
    }
  }

  /**
   * Parse one Cucumber JSON report file and record runtime coverage.
   *
   * @param jsonPath path to the JSON report
   */
  private void readCucumberJson(Path jsonPath) {
    try {
      var root = objectMapper.readTree(jsonPath.toFile());
      if (!root.isArray()) {
        return;
      }
      for (var feature : root) {
        var featureUri = feature.path("uri").asText("");
        var elements = feature.path("elements");
        if (!elements.isArray()) {
          continue;
        }
        for (var element : elements) {
          if (!"scenario".equals(element.path("type").asText())) {
            continue;
          }
          var scenarioName = element.path("name").asText("");
          var scenarioLine = element.path("line").isInt() ? element.path("line").asInt() : null;
          var tagNames = extractJsonTagNames(element.path("tags"));
          var scenarioKey = buildScenarioKey(featureUri, scenarioName, scenarioLine, tagNames);
          if (!seenScenarioKeys.add(scenarioKey)) {
            continue;
          }
          var status = deriveScenarioStatus(element);
          var requirementIds = recordTagsFromJson(tagNames, status);
          recordErrorFromJson(element, status, requirementIds);
        }
      }
    } catch (IOException exception) {
      log.warn("Unable to parse cucumber JSON {} for runtime coverage fallback.", jsonPath,
          exception);
    }
  }

  /**
   * Derive a single scenario status from its step results.
   *
   * @param element scenario JSON element
   * @return derived scenario status
   */
  private ScenarioStatus deriveScenarioStatus(JsonNode element) {
    var steps = element.path("steps");
    if (!steps.isArray() || steps.isEmpty()) {
      return ScenarioStatus.SKIPPED;
    }
    var sawSkipped = false;
    for (var step : steps) {
      var status = step.path("result").path("status").asText();
      if ("failed".equalsIgnoreCase(status)) {
        return ScenarioStatus.FAILED;
      }
      if ("skipped".equalsIgnoreCase(status)) {
        sawSkipped = true;
      }
    }
    return sawSkipped ? ScenarioStatus.SKIPPED : ScenarioStatus.PASSED;
  }

  /**
   * Record scenario results based on Cucumber JSON tags.
   *
   * @param status   derived scenario status
   * @return resolved AFO identifiers
   */
  private Set<String> recordTagsFromJson(List<String> tagNames, ScenarioStatus status) {
    var requirementIds = new LinkedHashSet<String>();
    var testAspectIds = new LinkedHashSet<String>();
    for (var tagName : tagNames) {
      var testAspectId = normalizeTag(tagName);
      if (testAspectId.isBlank()) {
        continue;
      }
      if (AFO_TAG.matcher(testAspectId).matches()) {
        requirementIds.add(testAspectId);
      } else if (TEST_ASPECT_TAG.matcher(testAspectId).matches()) {
        testAspectIds.add(testAspectId);
        resolveRequirementFromTestAspect(testAspectId).ifPresent(requirementIds::add);
      }
    }
    totalStats.record(status);
    if (requirementIds.isEmpty() && testAspectIds.isEmpty()) {
      untaggedStats.record(status);
    }
    requirementIds.forEach(id -> requirementStats
        .computeIfAbsent(id, key -> new Rollup())
        .record(status));
    testAspectIds.forEach(id -> testAspectStats
        .computeIfAbsent(id, key -> new Rollup())
        .record(status));
    return requirementIds;
  }

  /**
   * Extract tag names from Cucumber JSON.
   */
  private List<String> extractJsonTagNames(JsonNode tagsNode) {
    if (!tagsNode.isArray()) {
      return List.of();
    }
    var tags = new ArrayList<String>();
    for (var tag : tagsNode) {
      var tagName = tag.path("name").asText();
      if (!tagName.isBlank()) {
        tags.add(tagName);
      }
    }
    return tags;
  }

  /**
   * Record scenario errors based on Cucumber JSON data.
   *
   * @param element        scenario JSON element
   * @param status         derived scenario status
   * @param requirementIds resolved AFO identifiers
   */
  private void recordErrorFromJson(JsonNode element,
      ScenarioStatus status,
      Set<String> requirementIds) {
    if (status != ScenarioStatus.FAILED) {
      return;
    }
    if (requirementIds == null || requirementIds.isEmpty()) {
      return;
    }
    var scenarioName = element.path("name").asText("");
    var error = "";
    var steps = element.path("steps");
    if (steps.isArray()) {
      for (var step : steps) {
        var stepError = step.path("result").path("error_message").asText();
        if (!stepError.isBlank()) {
          var stepName = step.path("name").asText("");
          var stepLine = step.path("line").asInt(-1);
          error = summarizeJsonError(stepError, stepName, stepLine);
          break;
        }
      }
    }
    if (error.isBlank()) {
      error = "Unbekannter Fehler";
    }
    if (!scenarioName.isBlank()) {
      var summary = scenarioName + " -> " + error;
      requirementIds.forEach(id -> addError(id, summary));
    } else {
      var summary = error;
      requirementIds.forEach(id -> addError(id, summary));
    }
  }

  /**
   * Add one error message to the per-requirement error map.
   *
   * @param requirementId requirement identifier
   * @param error         error message
   */
  private void addError(String requirementId, String error) {
    if (requirementId == null || requirementId.isBlank() || error == null || error.isBlank()) {
      return;
    }
    runErrors.computeIfAbsent(requirementId, key -> ConcurrentHashMap.newKeySet())
        .add(error.trim());
  }

  /**
   * Build the per-run meta statistics snapshot.
   */
  private Map<String, Rollup> buildMetaStats() {
    var meta = new LinkedHashMap<String, Rollup>();
    meta.put(META_TOTAL_ID, totalStats.copy());
    meta.put(META_UNTAGGED_ID, untaggedStats.copy());
    return meta;
  }

  /**
   * Count all error messages across requirements.
   *
   * @param errors error map
   * @return total number of errors
   */
  private int countErrors(Map<String, Set<String>> errors) {
    return errors.values().stream().mapToInt(Set::size).sum();
  }

  /**
   * Write a small meta CSV with run information.
   */
  private void writeMetaCsv(Path outputFile, Map<String, Rollup> mergedMeta) throws IOException {
    var tempFile = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
    var total = mergedMeta.getOrDefault(META_TOTAL_ID, Rollup.empty());
    var untagged = mergedMeta.getOrDefault(META_UNTAGGED_ID, Rollup.empty());
    var timestamp = META_TIME_FORMAT.format(Instant.now());
    var profile = Optional.ofNullable(System.getProperty("PROFILE"))
        .orElse(System.getenv("PROFILE"));
    var proxyId = Optional.ofNullable(System.getProperty("tiger.tigerProxy.proxyId"))
        .orElse(System.getenv("TIGER_PROXY_ID"));
    var cucumberFilter = Optional.ofNullable(System.getProperty("cucumber.filter.tags"))
        .orElse(System.getenv("CUCUMBER_FILTER_TAGS"));

    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      writer.write(String.join(",", META_HEADERS));
      writer.write(System.lineSeparator());
      writer.append(csv(timestamp)).append(',')
          .append(csv(defaultString(profile))).append(',')
          .append(csv(defaultString(proxyId))).append(',')
          .append(csv(defaultString(cucumberFilter))).append(',')
          .append(Integer.toString(total.total())).append(',')
          .append(Integer.toString(total.passed())).append(',')
          .append(Integer.toString(total.failed())).append(',')
          .append(Integer.toString(total.skipped())).append(',')
          .append(Integer.toString(untagged.total()))
          .append(System.lineSeparator());
    }
    Files.move(tempFile, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Shorten a multi-line JSON error message to a single-line summary.
   *
   * @param error raw error message
   * @return compact error summary
   */
  private String summarizeJsonError(String error, String stepName, int stepLine) {
    if (error == null || error.isBlank()) {
      return "";
    }
    var trimmed = error.strip();
    var lines = trimmed.split("\\R");
    var limit = Math.min(lines.length, 5);
    var summary = new StringBuilder();
    for (var i = 0; i < limit; i++) {
      var line = lines[i].strip();
      if (line.isEmpty()) {
        continue;
      }
      if (!summary.isEmpty()) {
        summary.append(' ');
      }
      summary.append(line);
    }
    appendStepContext(summary, stepName, stepLine);
    return summary.toString();
  }

  /**
   * Append step context to the error summary if available.
   *
   * @param summary  current summary builder
   * @param stepName step text from the report
   * @param stepLine step line number in the feature file
   */
  private void appendStepContext(StringBuilder summary, String stepName, int stepLine) {
    if (stepName == null || stepName.isBlank()) {
      return;
    }
    if (!summary.isEmpty()) {
      summary.append(" | ");
    }
    summary.append("Step: ").append(stepName.strip());
    if (stepLine > 0) {
      summary.append(" (line ").append(stepLine).append(')');
    }
  }

  /**
   * Register this plugin instance as active for cleanup coordination.
   */
  private void registerRunInstance() {
    try {
      var outputDir = resolveOutputDir();
      var lockPath = outputDir.resolve(LOCK_FILE);
      var counterPath = outputDir.resolve(RUN_COUNTER_FILE);
      try (var channel = FileChannel.open(lockPath,
          StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          var ignored = channel.lock()) {
        updateRunCounter(counterPath, 1);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to register runtime coverage run", exception);
    }
  }

  /**
   * Resolve and create the output directory for coverage artifacts.
   *
   * @return output directory path
   */
  private Path resolveOutputDir() throws IOException {
    var parent = outputPath.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
      return parent;
    }
    return Path.of(".");
  }

  /**
   * Write the runtime coverage CSV.
   *
   * @param outputFile         target file
   * @param mergedRequirements merged AFO state
   * @param mergedTestAspects  merged test aspect state
   */
  private void writeCsv(Path outputFile,
      Map<String, Rollup> mergedRequirements,
      Map<String, Rollup> mergedTestAspects,
      Map<String, Set<String>> mergedErrors,
      Map<String, Rollup> mergedMeta) throws IOException {
    var tempFile = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      writer.write('\ufeff');
      writer.write(String.join(",", CSV_HEADERS));
      writer.write(System.lineSeparator());

      writeRequirements(writer, mergedRequirements, mergedTestAspects, mergedErrors, mergedMeta);
    }
    Files.move(tempFile, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Write the runtime coverage CSV for test aspects.
   *
   * @param outputFile        target file
   * @param mergedTestAspects merged test aspect state
   */
  private void writeTestAspectCsv(Path outputFile,
      Map<String, Rollup> mergedTestAspects) throws IOException {
    var tempFile = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      writer.write('\ufeff');
      writer.write(String.join(",", TEST_ASPECT_HEADERS));
      writer.write(System.lineSeparator());

      Set<String> ids = new LinkedHashSet<>(testAspectCatalog.keySet());
      mergedTestAspects.keySet().stream()
          .filter(id -> !ids.contains(id))
          .sorted()
          .forEach(ids::add);

      for (var id : ids) {
        var info = testAspectCatalog.get(id);
        var requirementId = info != null ? info.requirementId()
            : resolveRequirementFromTestAspect(id).orElse("");
        var title = info == null ? "" : info.title();
        var rollup = mergedTestAspects.getOrDefault(id, Rollup.empty());
        writer.append(csv(id)).append(',')
            .append(csv(requirementId.isBlank() ? NA_VALUE : requirementId)).append(',')
            .append(csv(title.isBlank() ? NA_VALUE : title)).append(',')
            .append(csv(rollup.status())).append(',')
            .append(Integer.toString(rollup.total())).append(',')
            .append(Integer.toString(rollup.passed())).append(',')
            .append(Integer.toString(rollup.failed())).append(',')
            .append(Integer.toString(rollup.skipped()))
            .append(System.lineSeparator());
      }
    }
    Files.move(tempFile, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Write AFO rows using merged runtime results.
   *
   * @param writer             output sink
   * @param mergedRequirements merged AFO state
   * @param mergedTestAspects  merged test aspect state
   */
  private void writeRequirements(Appendable writer,
      Map<String, Rollup> mergedRequirements,
      Map<String, Rollup> mergedTestAspects,
      Map<String, Set<String>> mergedErrors,
      Map<String, Rollup> mergedMeta) throws IOException {
    Set<String> ids = new LinkedHashSet<>(productInfo.keySet());
    mergedRequirements.keySet().stream()
        .filter(id -> !ids.contains(id))
        .sorted()
        .forEach(ids::add);

    for (var id : ids) {
      var info = productInfo.get(id);
      var rollup = mergedRequirements.getOrDefault(id, Rollup.empty());
      var testAspectCoverage = computeTestAspectCoverage(id, mergedTestAspects);
      var testAspectImplemented = computeTestAspectImplementedCount(id, mergedTestAspects);
      var errorSummary = formatErrors(mergedErrors.get(id));
      writeRow(writer,
          id,
          info == null ? NA_VALUE : info.title(),
          info == null ? NA_VALUE : info.implemented(),
          rollup.status(),
          rollup.total(),
          rollup.passed(),
          rollup.failed(),
          rollup.skipped(),
          testAspectCoverage.total(),
          testAspectCoverage.passed(),
          testAspectCoverage.coverage(),
          testAspectImplemented,
          errorSummary);
    }

    writeSummaryRows(writer, mergedMeta);
  }

  /**
   * Write one CSV row for an AFO.
   */
  private void writeRow(Appendable writer,
      String id,
      String title,
      String productImplemented,
      String runtimeStatus,
      int total,
      int passed,
      int failed,
      int skipped,
      Integer testAspectTotal,
      Integer testAspectPassed,
      String testAspectCoverage,
      Integer testAspectImplemented,
      String errorSummary) throws IOException {
    writer.append(csv(id)).append(',')
        .append(csv(title)).append(',')
        .append(csv(productImplemented)).append(',')
        .append(csv(runtimeStatus)).append(',')
        .append(Integer.toString(total)).append(',')
        .append(Integer.toString(passed)).append(',')
        .append(Integer.toString(failed)).append(',')
        .append(Integer.toString(skipped)).append(',')
        .append(testAspectTotal == null ? "" : Integer.toString(testAspectTotal)).append(',')
        .append(testAspectPassed == null ? "" : Integer.toString(testAspectPassed)).append(',')
        .append(csv(testAspectCoverage)).append(',')
        .append(testAspectImplemented == null ? "" : Integer.toString(testAspectImplemented))
        .append(',')
        .append(csv(errorSummary))
        .append(System.lineSeparator());
  }

  /**
   * Write summary rows for total and untagged scenarios.
   */
  private void writeSummaryRows(Appendable writer, Map<String, Rollup> mergedMeta)
      throws IOException {
    var total = mergedMeta.getOrDefault(META_TOTAL_ID, Rollup.empty());
    var untagged = mergedMeta.getOrDefault(META_UNTAGGED_ID, Rollup.empty());

    writeRow(writer,
        SUMMARY_ID,
        "Szenarien gesamt (alle)",
        NA_VALUE,
        total.status(),
        total.total(),
        total.passed(),
        total.failed(),
        total.skipped(),
        null,
        null,
        NA_VALUE,
        null,
        "");

    writeRow(writer,
        META_UNTAGGED_ID,
        "Szenarien ohne AFO/TA Tags",
        NA_VALUE,
        untagged.status(),
        untagged.total(),
        untagged.passed(),
        untagged.failed(),
        untagged.skipped(),
        null,
        null,
        NA_VALUE,
        null,
        "");
  }

  /**
   * Load product implementation metadata from the CSV used by the test plan.
   */
  private void loadProductInfo() {
    if (!Files.exists(PRODUCT_STATUS_CSV)) {
      log.info("Product status CSV not found at {}, runtime coverage will omit product metadata.",
          PRODUCT_STATUS_CSV);
      return;
    }
    try (var reader = Files.newBufferedReader(PRODUCT_STATUS_CSV,
        StandardCharsets.UTF_8)) {
      var header = reader.readLine();
      if (header == null) {
        return;
      }
      var headerFields = parseCsvLine(header);
      var requirementIndex = indexOf(headerFields, "Anforderung");
      var titleIndex = indexOf(headerFields, "Titel");
      var implementedIndex = indexOf(headerFields, "umgesetzt");
      var hintIndex = indexOf(headerFields, "Hinweis");

      String line;
      while ((line = reader.readLine()) != null) {
        var fields = parseCsvLine(line);
        var requirement = getField(fields, requirementIndex);
        if (requirement == null || requirement.isBlank()) {
          continue;
        }
        productInfo.put(requirement, new ProductInfo(
            requirement,
            defaultString(getField(fields, titleIndex)),
            defaultString(getField(fields, implementedIndex)),
            defaultString(getField(fields, hintIndex))));
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read product implementation CSV", exception);
    }
  }

  /**
   * Load the test aspect catalog from generated Asciidoc files.
   */
  private void loadTestAspectCatalog() {
    if (!Files.exists(TEST_ASPECTS_ROOT)) {
      return;
    }
    try (var paths = Files.walk(TEST_ASPECTS_ROOT)) {
      paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().startsWith("TA_"))
          .filter(path -> path.getFileName().toString().endsWith(".adoc"))
          .forEach(path -> {
            var id = stripSuffix(path.getFileName().toString(), ".adoc");
            var title = readTestAspectTitle(path, id);
            var requirementId = resolveRequirementFromTestAspect(id).orElse("");
            testAspectCatalog.put(id, new TestAspectInfo(id, title, requirementId));
          });
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read test aspect catalog", exception);
    }
  }

  /**
   * Compute the passed/total test aspect coverage for an AFO.
   */
  private TestAspectCoverage computeTestAspectCoverage(String requirementId,
      Map<String, Rollup> mergedTestAspects) {
    if (testAspectCatalog.isEmpty()) {
      return TestAspectCoverage.empty();
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
      return TestAspectCoverage.empty();
    }
    return new TestAspectCoverage(total, passed);
  }

  /**
   * Count implemented test aspects for the given requirement.
   */
  private Integer computeTestAspectImplementedCount(String requirementId,
      Map<String, Rollup> mergedTestAspects) {
    if (testAspectCatalog.isEmpty()) {
      return null;
    }
    var implemented = 0;
    for (var info : testAspectCatalog.values()) {
      if (!Objects.equals(requirementId, info.requirementId())) {
        continue;
      }
      var rollup = mergedTestAspects.getOrDefault(info.id(), Rollup.empty());
      if (rollup.total() > 0) {
        implemented++;
      }
    }
    return implemented;
  }

  /**
   * Supported scenario outcome statuses.
   */
  private enum ScenarioStatus {
    PASSED,
    FAILED,
    SKIPPED
  }

  /**
   * Aggregates scenario counts for one requirement or test aspect.
   */
  private static final class Rollup {

    private static final Rollup EMPTY = new Rollup();
    private int total;
    private int passed;
    private int failed;
    private int skipped;

    /**
     * Return the shared empty rollup instance.
     *
     * @return shared empty rollup instance
     */
    static Rollup empty() {
      return EMPTY;
    }

    /**
     * Create a rollup from persisted counters.
     */
    static Rollup fromCounts(int total, int passed, int failed, int skipped) {
      var rollup = new Rollup();
      rollup.total = Math.max(0, total);
      rollup.passed = Math.max(0, passed);
      rollup.failed = Math.max(0, failed);
      rollup.skipped = Math.max(0, skipped);
      return rollup;
    }

    /**
     * Record one scenario outcome.
     *
     * @param status scenario status
     */
    synchronized void record(ScenarioStatus status) {
      total++;
      if (status == ScenarioStatus.PASSED) {
        passed++;
      } else if (status == ScenarioStatus.SKIPPED) {
        skipped++;
      } else {
        failed++;
      }
    }

    /**
     * Resolve the aggregate status for reporting.
     */
    synchronized String status() {
      if (failed > 0) {
        return "fehlgeschlagen";
      }
      if (passed > 0) {
        return "bestanden";
      }
      if (skipped > 0) {
        return "übersprungen";
      }
      return "nicht ausgeführt";
    }

    /**
     * Returns true if all recorded scenarios passed and at least one ran.
     */
    synchronized boolean isPassed() {
      return total > 0 && failed == 0 && passed > 0;
    }

    /**
     * Return the total number of recorded scenarios.
     *
     * @return total number of recorded scenarios
     */
    synchronized int total() {
      return total;
    }

    /**
     * Return the number of passed scenarios.
     *
     * @return number of passed scenarios
     */
    synchronized int passed() {
      return passed;
    }

    /**
     * Return the number of failed scenarios.
     *
     * @return number of failed scenarios
     */
    synchronized int failed() {
      return failed;
    }

    /**
     * Return the number of skipped scenarios.
     *
     * @return number of skipped scenarios
     */
    synchronized int skipped() {
      return skipped;
    }

    /**
     * Add another rollup into this instance.
     *
     * @param other rollup to merge
     */
    synchronized void add(Rollup other) {
      if (other == null) {
        return;
      }
      total += other.total;
      passed += other.passed;
      failed += other.failed;
      skipped += other.skipped;
    }

    /**
     * Create a snapshot copy of this rollup.
     */
    synchronized Rollup copy() {
      return fromCounts(total, passed, failed, skipped);
    }
  }

  /**
   * Stores product implementation metadata per requirement.
   *
   * @param id          requirement identifier
   * @param title       requirement title
   * @param implemented product implementation status
   * @param hint        additional note from the source CSV
   */
  private record ProductInfo(
      String id,
      String title,
      String implemented,
      String hint) {

    /**
     * Return the requirement title.
     *
     * @return requirement title
     */
    @Override
    public String title() {
      return title;
    }

    /**
     * Return the product implementation status.
     *
     * @return product implementation status
     */
    @Override
    public String implemented() {
      return implemented;
    }

  }

  /**
   * Stores metadata for a test aspect entry.
   *
   * @param id            test aspect identifier
   * @param title         test aspect title
   * @param requirementId linked requirement identifier
   */
  private record TestAspectInfo(
      String id,
      String title,
      String requirementId) {

    /**
     * Return the test aspect identifier.
     *
     * @return test aspect identifier
     */
    @Override
    public String id() {
      return id;
    }

    /**
     * Return the linked requirement identifier.
     *
     * @return linked requirement identifier
     */
    @Override
    public String requirementId() {
      return requirementId;
    }

  }

  /**
   * Summarizes test aspect coverage counts.
   *
   * @param total  total test aspects
   * @param passed passed test aspects
   */
  private record TestAspectCoverage(int total, int passed) {

    /**
     * Build an empty coverage snapshot.
     */
    static TestAspectCoverage empty() {
      return new TestAspectCoverage(0, 0);
    }

    /**
     * Return the total number of test aspects.
     *
     * @return total test aspects
     */
    @Override
    public int total() {
      return total;
    }

    /**
     * Return the number of passed test aspects.
     *
     * @return passed test aspects
     */
    @Override
    public int passed() {
      return passed;
    }

    /**
     * Render the percentage string or empty if there are no test aspects.
     */
    String coverage() {
      if (total == 0) {
        return NA_VALUE;
      }
      var percent = Math.round((passed * 100.0f) / total);
      return percent + "%";
    }
  }
}
