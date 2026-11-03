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

import io.cucumber.messages.types.Envelope;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestRunFinished;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects runtime status for AFOs and test aspects based on scenario tags and writes CSV reports.
 *
 * <p>Scenario outcomes are aggregated across parallel driver instances before the annotation
 * audit is written.</p>
 */
@Slf4j
public final class RuntimeCoveragePlugin implements ConcurrentEventListener {

  private static final String DEFAULT_OUTPUT = "target/traceability/runtime_coverage.csv";
  private static final String DEFAULT_SERENITY_OUTPUT = "target/site/serenity";
  private static final Path DEFAULT_FAILURE_ANNOTATIONS_CSV =
      Path.of("docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv");
  private static final String FAILURE_ANNOTATIONS_PROPERTY =
      "zeta.runtime.failure.annotations.csv";
  private static final String FAILURE_ANNOTATIONS_ENV = "ZETA_RUNTIME_FAILURE_ANNOTATIONS_CSV";
  private static final String REQUIREMENT_STATE_FILE = "runtime_coverage_state_afo.csv";
  private static final String TEST_ASPECT_STATE_FILE = "runtime_coverage_state_ta.csv";
  private static final String ERROR_STATE_FILE = "runtime_coverage_state_errors.csv";
  private static final String SCENARIO_OUTCOME_STATE_FILE =
      "runtime_coverage_state_scenario_outcomes.csv";
  private static final String META_STATE_FILE = "runtime_coverage_state_meta.csv";
  private static final String LOCK_FILE = "runtime_coverage.lock";
  private static final String RUN_COUNTER_FILE = "runtime_coverage_run_count.txt";
  private static final String META_OUTPUT_FILE = "runtime_coverage_meta.csv";
  private static final String TEST_ASPECT_OUTPUT_FILE = "runtime_coverage_testaspects.csv";

  private final Map<String, Rollup> requirementStats = new ConcurrentHashMap<>();
  private final Map<String, Rollup> testAspectStats = new ConcurrentHashMap<>();
  private final Map<String, Set<FailureRecord>> runErrors = new ConcurrentHashMap<>();
  private final Map<String, ScenarioOutcome> scenarioOutcomes = new ConcurrentHashMap<>();
  private final Set<String> seenScenarioKeys = ConcurrentHashMap.newKeySet();
  private final Rollup totalStats = new Rollup();
  private final Rollup untaggedStats = new Rollup();
  private final RuntimeCoverageCsvRepository csvRepository;
  private final FailureAnnotations failureAnnotations;
  private final CucumberJsonRuntimeCoverageReader cucumberJsonReader;
  private final Path outputPath;
  private final Path auditOutputDirectory;

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
    auditOutputDirectory = resolveSerenityOutputDirectory();
    var catalog = new RuntimeCoverageCatalog();
    failureAnnotations = RuntimeCoverageCsvRepository.loadFailureAnnotations(
        resolveFailureAnnotationsPath());
    csvRepository = new RuntimeCoverageCsvRepository(catalog, failureAnnotations);
    cucumberJsonReader = new CucumberJsonRuntimeCoverageReader(requirementStats, testAspectStats,
        runErrors, seenScenarioKeys, totalStats, untaggedStats, scenarioOutcomes);
    registerRunInstance();
  }

  /**
   * Resolve the target path from system properties, environment, or Serenity settings.
   *
   * @return runtime coverage output path
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
   * Resolve the optional internal CSV with ticket and owner annotations.
   *
   * @return annotation CSV path
   */
  private static Path resolveFailureAnnotationsPath() {
    var explicit = Optional.ofNullable(System.getProperty(FAILURE_ANNOTATIONS_PROPERTY))
        .filter(value -> !value.isBlank())
        .orElseGet(() -> Optional.ofNullable(System.getenv(FAILURE_ANNOTATIONS_ENV))
            .filter(value -> !value.isBlank())
            .orElse(null));
    if (explicit != null) {
      return Path.of(explicit);
    }
    return DEFAULT_FAILURE_ANNOTATIONS_CSV;
  }

  /**
   * Read the Serenity output directory from serenity.properties if available.
   *
   * @return Serenity output directory or null
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
   * Resolve the directory in which Serenity and audit artifacts are written.
   *
   * @return Serenity output directory
   */
  private static Path resolveSerenityOutputDirectory() {
    var serenityDir = Optional.ofNullable(System.getProperty("serenity.outputDirectory"))
        .filter(value -> !value.isBlank())
        .orElseGet(RuntimeCoveragePlugin::loadSerenityOutputDirectory);
    return Path.of(serenityDir == null ? DEFAULT_SERENITY_OUTPUT : serenityDir);
  }

  /**
   * Update the shared run counter and return the new value.
   *
   * @param counterPath counter file path
   * @param delta       delta to apply
   * @return updated counter value
   * @throws IOException when counter access fails
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
   * @throws IOException when counter access fails
   */
  private static int readRunCounter(Path counterPath) throws IOException {
    if (!Files.exists(counterPath)) {
      return 0;
    }
    try (var reader = Files.newBufferedReader(counterPath, StandardCharsets.UTF_8)) {
      return RuntimeCoverageIds.parseInt(reader.readLine());
    }
  }

  /**
   * Delete temporary aggregation state files once the run completes.
   *
   * @param requirementStatePath AFO state file path
   * @param testAspectStatePath  test aspect state file path
   * @param errorStatePath       error state file path
   * @param metaStatePath        meta state file path
   * @param counterPath          run counter file path
   * @throws IOException when a state file cannot be removed
   */
  private static void cleanupStateFiles(Path requirementStatePath,
      Path testAspectStatePath,
      Path errorStatePath,
      Path metaStatePath,
      Path counterPath) throws IOException {
    Files.deleteIfExists(requirementStatePath.resolveSibling(SCENARIO_OUTCOME_STATE_FILE));
    Files.deleteIfExists(requirementStatePath);
    Files.deleteIfExists(testAspectStatePath);
    Files.deleteIfExists(errorStatePath);
    Files.deleteIfExists(metaStatePath);
    Files.deleteIfExists(counterPath);
  }

  /**
   * Merge per-run stats into the shared state map.
   *
   * @param target    target rollups
   * @param additions rollups to add
   */
  private static void mergeState(Map<String, Rollup> target, Map<String, Rollup> additions) {
    for (var entry : additions.entrySet()) {
      var rollup = target.computeIfAbsent(entry.getKey(), key -> new Rollup());
      rollup.add(entry.getValue());
    }
  }

  /**
   * Merge error summaries.
   *
   * @param target    target errors
   * @param additions errors to add
   */
  private static void mergeErrors(Map<String, Set<FailureRecord>> target,
      Map<String, Set<FailureRecord>> additions) {
    if (additions == null || additions.isEmpty()) {
      return;
    }
    additions.forEach(
        (key1, value) -> target.computeIfAbsent(key1, key -> new LinkedHashSet<>())
            .addAll(value));
  }

  /**
   * Register the plugin handlers with the Cucumber event publisher.
   *
   * <p>The final-message handler is appended after all formatters have registered their handlers.
   * It runs after they close their JSON outputs.</p>
   *
   * @param publisher event publisher provided by Cucumber
   */
  @Override
  public void setEventPublisher(EventPublisher publisher) {
    log.debug("Runtime coverage plugin registered (output: {}).", outputPath);
    publisher.registerHandlerFor(TestCaseFinished.class, this::handleTestCaseFinished);
    publisher.registerHandlerFor(TestRunFinished.class, event ->
        publisher.registerHandlerFor(Envelope.class, this::handleTestRunFinished));
  }

  /**
   * Capture scenario results and map them to AFOs and test aspects via tags.
   *
   * @param event finished test case event
   */
  private void handleTestCaseFinished(TestCaseFinished event) {
    var status = event.getResult().getStatus();
    if (log.isDebugEnabled()) {
      var testCase = event.getTestCase();
      var name = testCase == null ? "<unknown>" : testCase.getName();
      var tags = testCase == null ? List.of() : testCase.getTags();
      log.debug("Runtime coverage saw scenario '{}' with status {} and {} tags.",
          name, status, tags.size());
    }

    var testCase = event.getTestCase();
    if (testCase == null) {
      recordUntagged(status);
      return;
    }

    var tags = testCase.getTags();
    var scenarioKey = RuntimeCoverageIds.buildScenarioKey(
        testCase.getUri() == null ? null : testCase.getUri().toString(),
        testCase.getName(),
        testCase.getLocation() == null ? null : testCase.getLocation().getLine());
    seenScenarioKeys.add(scenarioKey);
    var requirementIds = new LinkedHashSet<String>();
    var testAspectIds = new LinkedHashSet<String>();
    RuntimeCoverageIds.resolveTags(tags, requirementIds, testAspectIds);
    recordScenarioOutcome(testCase, status, requirementIds, testAspectIds);
    if (status != Status.PASSED && status != Status.SKIPPED) {
      var error = RuntimeCoverageIds.summarizeError(event);
      var feature = testCase.getUri() == null ? "" : testCase.getUri().toString();
      requirementIds.forEach(id -> addError(id, error, testAspectIds, feature, testCase.getName()));
    }
    recordStats(status, requirementIds, testAspectIds);
  }

  /**
   * Record scenario counters for resolved traceability ids.
   *
   * @param status         scenario status
   * @param requirementIds requirement identifiers
   * @param testAspectIds  test aspect identifiers
   */
  private void recordStats(Status status, Set<String> requirementIds, Set<String> testAspectIds) {
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
   * Record an untagged scenario.
   *
   * @param status scenario status
   */
  private void recordUntagged(Status status) {
    totalStats.record(status);
    untaggedStats.record(status);
  }

  /**
   * Finalizes the aggregated output after formatters consume the final message.
   *
   * @param event Cucumber message envelope
   */
  private void handleTestRunFinished(Envelope event) {
    if (event.getTestRunFinished().isEmpty()) {
      return;
    }
    log.debug("Runtime coverage test run finished. Requirements: {}, test aspects: {}, errors: {}.",
        requirementStats.size(), testAspectStats.size(), countErrors(runErrors));
    mergeAndWriteCsv();
  }

  /**
   * Merge per-driver state and write the consolidated CSV output.
   */
  private void mergeAndWriteCsv() {
    try {
      cucumberJsonReader.tryPopulateFromCucumberJson();
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
        writeMergedOutputs(outputDir, requirementStatePath, testAspectStatePath, errorStatePath,
            metaStatePath);
        var remainingRuns = updateRunCounter(counterPath, -1);
        if (remainingRuns == 0) {
          cleanupStateFiles(requirementStatePath, testAspectStatePath, errorStatePath,
              metaStatePath, counterPath);
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to write runtime coverage CSV", exception);
    }
  }

  /**
   * Merge state files and write final CSV outputs.
   *
   * @param outputDir            output directory
   * @param requirementStatePath AFO state path
   * @param testAspectStatePath  TA state path
   * @param errorStatePath       error state path
   * @param metaStatePath        meta state path
   * @throws IOException when output writing fails
   */
  private void writeMergedOutputs(Path outputDir,
      Path requirementStatePath,
      Path testAspectStatePath,
      Path errorStatePath,
      Path metaStatePath) throws IOException {
    var mergedRequirements = csvRepository.readState(requirementStatePath);
    mergeState(mergedRequirements, requirementStats);
    csvRepository.writeState(requirementStatePath, mergedRequirements);

    var mergedTestAspects = csvRepository.readState(testAspectStatePath);
    mergeState(mergedTestAspects, testAspectStats);
    csvRepository.writeState(testAspectStatePath, mergedTestAspects);

    var mergedErrors = csvRepository.readErrors(errorStatePath);
    mergeErrors(mergedErrors, runErrors);
    csvRepository.writeErrors(errorStatePath, mergedErrors);

    var scenarioOutcomeStatePath = outputDir.resolve(SCENARIO_OUTCOME_STATE_FILE);
    var mergedScenarioOutcomes = csvRepository.readScenarioOutcomes(scenarioOutcomeStatePath);
    mergeScenarioOutcomes(mergedScenarioOutcomes, scenarioOutcomes);
    csvRepository.writeScenarioOutcomes(scenarioOutcomeStatePath, mergedScenarioOutcomes);

    var mergedMeta = csvRepository.readState(metaStatePath);
    mergeState(mergedMeta, buildMetaStats());
    csvRepository.writeState(metaStatePath, mergedMeta);

    csvRepository.writeCsv(outputPath, mergedRequirements, mergedTestAspects, mergedErrors,
        mergedMeta, mergedScenarioOutcomes.values());
    writeRuntimeFailureAnnotationAudit(mergedScenarioOutcomes);
    csvRepository.writeTestAspectCsv(outputDir.resolve(TEST_ASPECT_OUTPUT_FILE), mergedTestAspects,
        mergedErrors, mergedScenarioOutcomes.values());
    csvRepository.writeMetaCsv(outputDir.resolve(META_OUTPUT_FILE), mergedMeta);
    log.info("Runtime coverage CSV written to {}", outputPath);
  }

  /**
   * Evaluate and persist the failure annotation audit for the merged scenario outcomes.
   *
   * @param mergedScenarioOutcomes scenario outcomes aggregated from all active driver instances
   * @throws IOException when an audit artifact cannot be written
   */
  private void writeRuntimeFailureAnnotationAudit(
      Map<String, ScenarioOutcome> mergedScenarioOutcomes) throws IOException {
    var audit = RuntimeFailureAnnotationAudit.evaluate(failureAnnotations,
        mergedScenarioOutcomes.values());
    RuntimeFailureAnnotationAudit.write(auditOutputDirectory, audit);
    log.info("Runtime failure annotation audit: {}", RuntimeFailureAnnotationAudit.summary(audit));
  }

  /**
   * Resolve and create the output directory for coverage artifacts.
   *
   * @return output directory path
   * @throws IOException when directory creation fails
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
   * Register this plugin instance as active for cleanup coordination.
   */
  private void registerRunInstance() {
    try {
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
        if (readRunCounter(counterPath) == 0) {
          cleanupStateFiles(requirementStatePath, testAspectStatePath, errorStatePath,
              metaStatePath, counterPath);
          Files.deleteIfExists(auditOutputDirectory.resolve(
              RuntimeFailureAnnotationAudit.REPORT_FILE));
          Files.deleteIfExists(auditOutputDirectory.resolve(
              RuntimeFailureAnnotationAudit.STATUS_FILE));
        }
        updateRunCounter(counterPath, 1);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to register runtime coverage run", exception);
    }
  }

  /**
   * Record one scenario outcome for the exact end-of-run annotation audit.
   *
   * @param testCase finished Cucumber test case
   * @param status   scenario status
   * @param requirementIds requirement identifiers attached to the scenario
   * @param testAspectIds test aspect identifiers attached to the scenario
   */
  private void recordScenarioOutcome(io.cucumber.plugin.event.TestCase testCase, Status status,
      Set<String> requirementIds, Set<String> testAspectIds) {
    if (testCase == null) {
      return;
    }
    var feature = testCase.getUri() == null ? "" : testCase.getUri().toString();
    var line = testCase.getLocation() == null ? null : testCase.getLocation().getLine();
    var outcome = new ScenarioOutcome(feature, testCase.getName(), line, status,
        requirementIds, testAspectIds);
    scenarioOutcomes.merge(outcome.key(), outcome, ScenarioOutcome::merge);
  }

  /**
   * Add one error message to the per-requirement error map.
   *
   * @param requirementId requirement identifier
   * @param error         error message
   * @param testAspectIds test aspect ids
   * @param feature       feature path
   * @param scenario      scenario name
   */
  private void addError(String requirementId, String error, Set<String> testAspectIds,
      String feature, String scenario) {
    if (requirementId == null || requirementId.isBlank() || error == null || error.isBlank()) {
      return;
    }
    runErrors.computeIfAbsent(requirementId, key -> ConcurrentHashMap.newKeySet())
        .add(new FailureRecord(error.trim(), testAspectIds, feature, scenario));
  }

  /**
   * Merge scenario outcomes from one driver into the shared state.
   *
   * @param target    shared scenario outcomes
   * @param additions outcomes observed by one driver
   */
  private static void mergeScenarioOutcomes(Map<String, ScenarioOutcome> target,
      Map<String, ScenarioOutcome> additions) {
    if (additions == null || additions.isEmpty()) {
      return;
    }
    additions.forEach((key, value) -> target.merge(key, value, ScenarioOutcome::merge));
  }

  /**
   * Build the per-run meta statistics snapshot.
   *
   * @return meta rollups
   */
  private Map<String, Rollup> buildMetaStats() {
    var meta = new LinkedHashMap<String, Rollup>();
    meta.put(RuntimeCoverageCsvRepository.META_TOTAL_ID, totalStats.copy());
    meta.put(RuntimeCoverageCsvRepository.META_UNTAGGED_ID, untaggedStats.copy());
    return meta;
  }

  /**
   * Count all error messages across requirements.
   *
   * @param errors error map
   * @return total number of errors
   */
  private int countErrors(Map<String, Set<FailureRecord>> errors) {
    return errors.values().stream().mapToInt(Set::size).sum();
  }
}
