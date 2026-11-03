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

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One private ticket/owner annotation row.
 *
 * @param matchType     anforderung, testaspekt or scenario; scenario rows match feature/name fields
 * @param requirementId optional requirement identifier for non-scenario fallback rows
 * @param testAspectId  optional test aspect identifier for non-scenario fallback rows
 * @param feature       optional feature path
 * @param scenario      optional scenario name for CSV lookup; required for the scenario audit
 * @param tickets       ticket identifiers or URLs
 * @param owner         responsible party
 */
record FailureAnnotation(String matchType, String requirementId, String testAspectId,
                         String feature, String scenario, Set<String> tickets,
                         String owner) {

  /**
   * Normalize annotation fields for stable matching.
   */
  FailureAnnotation {
    matchType = normalizeMatchType(matchType);
    requirementId = RuntimeCoverageIds.normalizeId(requirementId);
    testAspectId = RuntimeCoverageIds.normalizeId(testAspectId);
    feature = RuntimeCoverageIds.normalizePath(feature);
    scenario = RuntimeCoverageIds.defaultString(scenario).trim();
    tickets = tickets == null ? Set.of()
        : RuntimeCoverageCsvRepository.parseMultiValue(String.join(";", tickets));
    owner = RuntimeCoverageIds.defaultString(owner).trim();
  }

  /**
   * Check whether this annotation applies to a requirement output row.
   *
   * @param requirementId requirement identifier
   * @param failures      failure records for the requirement
   * @param outcomes      scenario outcomes for the requirement
   * @return true if it applies
   */
  boolean matchesRequirement(String requirementId, Set<FailureRecord> failures,
      Collection<ScenarioOutcome> outcomes) {
    if ("scenario".equals(matchType)) {
      return matchesNonPassingScenario(outcomes);
    }
    var normalizedRequirement = RuntimeCoverageIds.normalizeId(requirementId);
    if (!this.requirementId.isBlank()
        && !Objects.equals(this.requirementId, normalizedRequirement)) {
      return false;
    }
    if ("testaspekt".equals(matchType)) {
      return !testAspectId.isBlank()
          && failures != null
          && failures.stream().anyMatch(failure -> failure.testAspectIds().contains(testAspectId));
    }
    return !this.requirementId.isBlank()
        && Objects.equals(this.requirementId, normalizedRequirement);
  }

  /**
   * Check whether this annotation applies to a test aspect output row.
   *
   * @param testAspectId  test aspect identifier
   * @param requirementId requirement identifier
   * @param outcomes      scenario outcomes for the test aspect
   * @return true if it applies
   */
  boolean matchesTestAspect(String testAspectId, String requirementId,
      Collection<ScenarioOutcome> outcomes) {
    if ("scenario".equals(matchType)) {
      return matchesNonPassingScenario(outcomes);
    }
    var normalizedTestAspect = RuntimeCoverageIds.normalizeId(testAspectId);
    var normalizedRequirement = RuntimeCoverageIds.normalizeId(requirementId);
    if (!this.requirementId.isBlank()
        && !Objects.equals(this.requirementId, normalizedRequirement)) {
      return false;
    }
    if ("testaspekt".equals(matchType)) {
      return Objects.equals(this.testAspectId, normalizedTestAspect);
    }
    return !this.requirementId.isBlank()
        && Objects.equals(this.requirementId, normalizedRequirement);
  }

  /**
   * Check whether the annotation contains useful output.
   *
   * @return true if there is at least one reportable value
   */
  boolean hasOutput() {
    return !tickets.isEmpty() || !owner.isBlank();
  }

  /**
   * Check whether this row is a scenario-level mapping.
   *
   * @return true for scenario mappings
   */
  boolean isScenarioMapping() {
    return "scenario".equals(matchType);
  }

  /**
   * Check whether this row contains at least one ticket.
   *
   * @return true when a ticket is configured
   */
  boolean hasTicket() {
    return !tickets.isEmpty();
  }

  /**
   * Check whether a named runtime scenario matches this annotation for auditing.
   *
   * @param outcome runtime scenario outcome
   * @return true when feature and scenario identify the same scenario
   */
  boolean matchesScenario(ScenarioOutcome outcome) {
    if (!isScenarioMapping() || outcome == null || scenario.isBlank()) {
      return false;
    }
    if (!feature.isBlank() && !RuntimeCoverageIds.pathMatches(feature, outcome.feature())) {
      return false;
    }
    return RuntimeCoverageIds.scenarioNameMatches(scenario, outcome.scenario());
  }

  /**
   * Check whether populated feature and name fields match a non-passing scenario.
   *
   * @param outcomes scoped scenario outcomes
   * @return true when a matching non-passing outcome exists
   */
  private boolean matchesNonPassingScenario(Collection<ScenarioOutcome> outcomes) {
    if (outcomes == null) {
      return false;
    }
    return outcomes
        .stream()
        .filter(outcome -> !outcome.isPassed())
        .anyMatch(outcome -> scenario.isBlank()
            ? feature.isBlank() || RuntimeCoverageIds.pathMatches(feature, outcome.feature())
            : matchesScenario(outcome));
  }

  /**
   * Normalize annotation matching granularity.
   *
   * @param value raw match type or German Zuordnung value
   * @return normalized match type
   */
  private static String normalizeMatchType(String value) {
    var normalized = RuntimeCoverageIds.defaultString(value).trim().toLowerCase(Locale.ROOT);
    if ("szenario".equals(normalized)) {
      return "scenario";
    }
    return normalized;
  }
}
