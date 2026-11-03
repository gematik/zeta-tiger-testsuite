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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One scenario outcome used by the runtime failure annotation audit.
 *
 * <p>Repeated observations are merged conservatively because parallel drivers and the Cucumber
 * JSON fallback can report the same scenario independently.</p>
 *
 * @param feature  feature URI or path
 * @param scenario scenario name
 * @param line     scenario line when available
 * @param status   Cucumber scenario status
 * @param requirementIds requirement identifiers attached to this execution
 * @param testAspectIds test aspect identifiers attached to this execution
 */
record ScenarioOutcome(String feature, String scenario, Integer line, Status status,
                       Set<String> requirementIds, Set<String> testAspectIds) {

  /**
   * Normalize the values used for matching and state persistence.
   */
  ScenarioOutcome {
    feature = RuntimeCoverageIds.normalizePath(feature);
    scenario = RuntimeCoverageIds.defaultString(scenario).trim();
    status = status == null ? Status.UNDEFINED : status;
    requirementIds = RuntimeCoverageIds.normalizeIds(requirementIds);
    testAspectIds = RuntimeCoverageIds.normalizeIds(testAspectIds);
  }

  /**
   * Create an outcome without traceability identifiers.
   *
   * @param feature feature URI or path
   * @param scenario scenario name
   * @param line scenario line when available
   * @param status Cucumber scenario status
   */
  ScenarioOutcome(String feature, String scenario, Integer line, Status status) {
    this(feature, scenario, line, status, Set.of(), Set.of());
  }

  /**
   * Build a stable identity for one feature/scenario occurrence.
   *
   * @return scenario identity
   */
  String key() {
    return RuntimeCoverageIds.buildScenarioKey(feature, scenario, line);
  }

  /**
   * Check whether the outcome contains the identity required for a scenario audit.
   *
   * @return true when feature and scenario are present
   */
  boolean hasIdentity() {
    return !feature.isBlank() && !scenario.isBlank();
  }

  /**
   * Check whether the outcome is neither successful nor skipped, but also not a concrete failure.
   *
   * @return true for an inconclusive Cucumber status
   */
  boolean isIncomplete() {
    return status != Status.PASSED && status != Status.SKIPPED && status != Status.FAILED;
  }

  /**
   * Check whether this outcome is a concrete Cucumber failure.
   *
   * @return true when the scenario needs failure-ticket coverage
   */
  boolean isFailure() {
    return status == Status.FAILED;
  }

  /**
   * Check whether this outcome is a successful execution.
   *
   * @return true for a passed scenario
   */
  boolean isPassed() {
    return status == Status.PASSED;
  }

  /**
   * Check whether this outcome was skipped.
   *
   * @return true for a skipped scenario
   */
  boolean isSkipped() {
    return status == Status.SKIPPED;
  }

  /**
   * Merge repeated observations of the same scenario conservatively.
   *
   * @param other another observation of the same scenario
   * @return the outcome with the more severe status
   */
  ScenarioOutcome merge(ScenarioOutcome other) {
    if (other == null || !key().equals(other.key())) {
      return this;
    }
    var selected = severity(status) >= severity(other.status) ? this : other;
    var selectedFeature = feature.isBlank() ? other.feature : feature;
    var selectedScenario = scenario.isBlank() ? other.scenario : scenario;
    var selectedLine = line == null ? other.line : line;
    var mergedRequirements = new LinkedHashSet<>(requirementIds);
    mergedRequirements.addAll(other.requirementIds);
    var mergedTestAspects = new LinkedHashSet<>(testAspectIds);
    mergedTestAspects.addAll(other.testAspectIds);
    return new ScenarioOutcome(selectedFeature, selectedScenario, selectedLine,
        selected.status, mergedRequirements, mergedTestAspects);
  }

  /**
   * Assign an ordering to Cucumber statuses for conservative duplicate merging.
   *
   * @param value Cucumber status
   * @return severity rank
   */
  private static int severity(Status value) {
    if (value == Status.PASSED) {
      return 0;
    }
    if (value == Status.SKIPPED) {
      return 1;
    }
    if (value == Status.FAILED) {
      return 3;
    }
    return 2;
  }
}
