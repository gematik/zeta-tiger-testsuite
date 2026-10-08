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

import java.util.Objects;
import java.util.Set;

/**
 * One private ticket/owner annotation row.
 *
 * @param matchType     anforderung, testaspekt or scenario; scenario rows are matched by name
 * @param requirementId optional requirement identifier for non-scenario fallback rows
 * @param testAspectId  optional test aspect identifier for non-scenario fallback rows
 * @param feature       optional feature path
 * @param scenario      optional scenario name
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
   * @param scenarios     executed scenario names for the requirement
   * @return true if it applies
   */
  boolean matchesRequirement(String requirementId, Set<FailureRecord> failures,
      Set<String> scenarios) {
    if ("scenario".equals(matchType)) {
      return matchesFailedScenario(failures) || matchesExecutedScenario(scenarios);
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
   * @param failures      failure records for the test aspect
   * @param scenarios     executed scenario names for the test aspect
   * @return true if it applies
   */
  boolean matchesTestAspect(String testAspectId, String requirementId,
      Set<FailureRecord> failures, Set<String> scenarios) {
    if ("scenario".equals(matchType)) {
      return matchesFailedScenario(failures) || matchesExecutedScenario(scenarios);
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
   * Check scenario-level fields against a failure record.
   *
   * @param failure failure context
   * @return true if all populated scenario fields match
   */
  private boolean matchesScenarioFailure(FailureRecord failure) {
    if (failure == null) {
      return false;
    }
    if (!feature.isBlank() && !RuntimeCoverageIds.pathMatches(feature, failure.feature())) {
      return false;
    }
    return scenario.isBlank() || RuntimeCoverageIds.scenarioNameMatches(scenario, failure.scenario());
  }

  /**
   * Check whether this annotation matches any failed scenario.
   *
   * @param failures failure context for an output row
   * @return true if a failed scenario matches
   */
  private boolean matchesFailedScenario(Set<FailureRecord> failures) {
    return failures != null && failures.stream().anyMatch(this::matchesScenarioFailure);
  }

  /**
   * Check whether this annotation matches an executed scenario by name.
   *
   * @param scenarios executed scenario names
   * @return true if the scenario name is present
   */
  private boolean matchesExecutedScenario(Set<String> scenarios) {
    if (scenario.isBlank() || scenarios == null || scenarios.isEmpty()) {
      return false;
    }
    return scenarios
        .stream()
        .anyMatch(candidate -> RuntimeCoverageIds.scenarioNameMatches(scenario, candidate));
  }

  /**
   * Normalize annotation matching granularity.
   *
   * @param value raw match type or German Zuordnung value
   * @return normalized match type
   */
  private static String normalizeMatchType(String value) {
    var normalized = RuntimeCoverageIds.defaultString(value).trim().toLowerCase();
    if ("szenario".equals(normalized)) {
      return "scenario";
    }
    return normalized;
  }
}
