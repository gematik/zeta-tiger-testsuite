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

import io.cucumber.plugin.event.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fallback reader for Cucumber JSON files produced outside plugin event delivery.
 */
@Slf4j
final class CucumberJsonRuntimeCoverageReader {

  private static final String CUCUMBER_OUTPUT_DIR_PROPERTY = "zeta.cucumber.outputDirectory";
  private static final String DEFAULT_CUCUMBER_OUTPUT_DIR = "target/cucumber-parallel";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, Rollup> requirementStats;
  private final Map<String, Rollup> testAspectStats;
  private final Map<String, Set<FailureRecord>> runErrors;
  private final Map<String, Set<String>> requirementScenarios;
  private final Map<String, Set<String>> testAspectScenarios;
  private final Set<String> seenScenarioKeys;
  private final Rollup totalStats;
  private final Rollup untaggedStats;

  /**
   * Create a reader that records into the given runtime state.
   *
   * @param requirementStats     requirement rollups
   * @param testAspectStats      test aspect rollups
   * @param runErrors            runtime errors
   * @param requirementScenarios scenario names by requirement
   * @param testAspectScenarios  scenario names by test aspect
   * @param seenScenarioKeys     deduplication keys
   * @param totalStats           total scenario rollup
   * @param untaggedStats        untagged scenario rollup
   */
  CucumberJsonRuntimeCoverageReader(Map<String, Rollup> requirementStats,
      Map<String, Rollup> testAspectStats,
      Map<String, Set<FailureRecord>> runErrors,
      Map<String, Set<String>> requirementScenarios,
      Map<String, Set<String>> testAspectScenarios,
      Set<String> seenScenarioKeys,
      Rollup totalStats,
      Rollup untaggedStats) {
    this.requirementStats = requirementStats;
    this.testAspectStats = testAspectStats;
    this.runErrors = runErrors;
    this.requirementScenarios = requirementScenarios;
    this.testAspectScenarios = testAspectScenarios;
    this.seenScenarioKeys = seenScenarioKeys;
    this.totalStats = totalStats;
    this.untaggedStats = untaggedStats;
  }

  /**
   * Load runtime results from the configured Cucumber JSON output directory.
   */
  void tryPopulateFromCucumberJson() {
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
        readFeature(feature);
      }
    } catch (JacksonException exception) {
      log.warn("Unable to parse cucumber JSON {} for runtime coverage fallback.", jsonPath,
          exception);
    }
  }

  /**
   * Read all scenario elements from one feature JSON node.
   *
   * @param feature feature JSON node
   */
  private void readFeature(JsonNode feature) {
    var featureUri = feature.path("uri").asText("");
    var elements = feature.path("elements");
    if (!elements.isArray()) {
      return;
    }
    for (var element : elements) {
      if (!"scenario".equals(element.path("type").asText())) {
        continue;
      }
      readScenario(featureUri, element);
    }
  }

  /**
   * Read one scenario JSON element.
   *
   * @param featureUri feature URI
   * @param element    scenario JSON node
   */
  private void readScenario(String featureUri, JsonNode element) {
    var scenarioName = element.path("name").asText("");
    var scenarioLine = element.path("line").isInt() ? element.path("line").asInt() : null;
    var tagNames = extractJsonTagNames(element.path("tags"));
    var scenarioKey = RuntimeCoverageIds.buildScenarioKey(featureUri, scenarioName, scenarioLine,
        tagNames);
    if (!seenScenarioKeys.add(scenarioKey)) {
      return;
    }
    var status = deriveScenarioStatus(element);
    var requirementIds = new LinkedHashSet<String>();
    var testAspectIds = new LinkedHashSet<String>();
    recordTagsFromJson(tagNames, status, requirementIds, testAspectIds);
    recordScenarios(requirementScenarios, requirementIds, scenarioName);
    recordScenarios(testAspectScenarios, testAspectIds, scenarioName);
    recordErrorFromJson(element, status, requirementIds, testAspectIds, featureUri);
  }

  /**
   * Derive a single scenario status from its step results.
   *
   * @param element scenario JSON element
   * @return derived scenario status
   */
  private Status deriveScenarioStatus(JsonNode element) {
    var steps = element.path("steps");
    if (!steps.isArray() || steps.isEmpty()) {
      return Status.SKIPPED;
    }
    var sawSkipped = false;
    for (var step : steps) {
      var status = step.path("result").path("status").asText();
      if ("failed".equalsIgnoreCase(status)) {
        return Status.FAILED;
      }
      if ("skipped".equalsIgnoreCase(status)) {
        sawSkipped = true;
      }
    }
    return sawSkipped ? Status.SKIPPED : Status.PASSED;
  }

  /**
   * Record scenario results based on Cucumber JSON tags.
   *
   * @param tagNames       scenario tag names
   * @param status         derived scenario status
   * @param requirementIds target set for requirement identifiers
   * @param testAspectIds  target set for test aspect identifiers
   */
  private void recordTagsFromJson(List<String> tagNames, Status status,
      Set<String> requirementIds, Set<String> testAspectIds) {
    for (var tagName : tagNames) {
      var testAspectId = RuntimeCoverageIds.normalizeTag(tagName);
      if (testAspectId.isBlank()) {
        continue;
      }
      if (RuntimeCoverageIds.AFO_TAG.matcher(testAspectId).matches()) {
        requirementIds.add(testAspectId);
      } else if (RuntimeCoverageIds.TEST_ASPECT_TAG.matcher(testAspectId).matches()) {
        testAspectIds.add(testAspectId);
        RuntimeCoverageIds.resolveRequirementFromTestAspect(testAspectId)
            .ifPresent(requirementIds::add);
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
  }

  /**
   * Extract tag names from Cucumber JSON.
   *
   * @param tagsNode JSON tag array
   * @return tag names
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
   * @param testAspectIds  resolved TA identifiers
   * @param featureUri     feature URI
   */
  private void recordErrorFromJson(JsonNode element,
      Status status,
      Set<String> requirementIds,
      Set<String> testAspectIds,
      String featureUri) {
    if (status == Status.PASSED || status == Status.SKIPPED
        || requirementIds == null || requirementIds.isEmpty()) {
      return;
    }
    var scenarioName = element.path("name").asText("");
    var error = extractStepError(element);
    var summary = scenarioName.isBlank() ? error : scenarioName + " -> " + error;
    requirementIds.forEach(id -> addError(id, summary, testAspectIds, featureUri, scenarioName));
  }

  /**
   * Extract the first step error from a failed scenario.
   *
   * @param element scenario JSON element
   * @return compact step error
   */
  private String extractStepError(JsonNode element) {
    var steps = element.path("steps");
    if (steps.isArray()) {
      for (var step : steps) {
        var stepError = step.path("result").path("error_message").asText();
        if (!stepError.isBlank()) {
          return summarizeJsonError(stepError, step.path("name").asText(""),
              step.path("line").asInt(-1));
        }
      }
    }
    return "Unbekannter Fehler";
  }

  /**
   * Shorten a multi-line JSON error message to a single-line summary.
   *
   * @param error    raw error message
   * @param stepName step text from the report
   * @param stepLine step line number in the feature file
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
   * Record one executed scenario name for all resolved requirement ids.
   *
   * @param target   target scenario map
   * @param ids      traceability identifiers
   * @param scenario scenario name
   */
  private void recordScenarios(Map<String, Set<String>> target, Set<String> ids,
      String scenario) {
    if (target == null || ids == null || ids.isEmpty() || scenario == null
        || scenario.isBlank()) {
      return;
    }
    ids.forEach(id -> target.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet())
        .add(scenario.trim()));
  }
}
