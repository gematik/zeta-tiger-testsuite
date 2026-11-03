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

import io.cucumber.plugin.event.Status;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the runtime failure annotation audit.
 */
class RuntimeFailureAnnotationAuditTest {

  /**
   * Verifies missing, active, removable and unexecuted annotations are distinguished.
   */
  @Test
  void distinguishesAnnotationStates() {
    var annotations = new FailureAnnotations(List.of(
        annotation("active.feature", "Aktives Szenario", "ZETA-1"),
        annotation("done.feature", "Erfolgreiches Szenario", "ZETA-2"),
        annotation("later.feature", "Nicht ausgeführtes Szenario", "ZETA-3")));
    var outcomes = List.of(
        outcome("active.feature", "Aktives Szenario", Status.FAILED),
        outcome("done.feature", "Erfolgreiches Szenario", Status.PASSED),
        outcome("missing.feature", "Neues fehlgeschlagenes Szenario", Status.FAILED));

    var result = RuntimeFailureAnnotationAudit.evaluate(annotations, outcomes);

    assertThat(result.missingTickets()).isEqualTo(1);
    assertThat(result.removableTickets()).isEqualTo(1);
    assertThat(result.hasCoverageFindings()).isTrue();
    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .contains(RuntimeFailureAnnotationAudit.FAILURE_WITH_TICKET,
            RuntimeFailureAnnotationAudit.FAILURE_WITHOUT_TICKET,
            RuntimeFailureAnnotationAudit.TICKET_REMOVABLE,
            RuntimeFailureAnnotationAudit.NOT_EXECUTED,
            RuntimeFailureAnnotationAudit.ANNOTATION_ACTIVE);
  }

  /**
   * Verifies that a feature-less mapping is not accepted when names are ambiguous.
   */
  @Test
  void rejectsAmbiguousFeaturelessMapping() {
    var annotations = new FailureAnnotations(List.of(
        annotation("", "Gleiches Szenario", "ZETA-4")));
    var outcomes = List.of(
        outcome("one.feature", "Gleiches Szenario", Status.FAILED),
        outcome("two.feature", "Gleiches Szenario", Status.PASSED));

    var result = RuntimeFailureAnnotationAudit.evaluate(annotations, outcomes);

    assertThat(result.ambiguousMatches()).isEqualTo(2);
    assertThat(result.hasCoverageFindings()).isTrue();
    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .contains(RuntimeFailureAnnotationAudit.FAILURE_AMBIGUOUS,
            RuntimeFailureAnnotationAudit.ANNOTATION_AMBIGUOUS);
  }

  /**
   * Verifies that every non-final Cucumber status prevents ticket removal.
   */
  @Test
  void neverRemovesTicketsForInconclusiveStatuses() {
    var annotations = new FailureAnnotations(List.of(
        annotation("demo.feature", "Unvollständiges Szenario", "ZETA-5")));

    for (var status : List.of(Status.PENDING, Status.UNDEFINED, Status.AMBIGUOUS, Status.UNUSED)) {
      var result = RuntimeFailureAnnotationAudit.evaluate(annotations,
          List.of(outcome("demo.feature", "Unvollständiges Szenario", status)), true);

      assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
          .contains(RuntimeFailureAnnotationAudit.EXECUTION_INCOMPLETE)
          .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
    }
  }

  /**
   * Verifies that a filtered run cannot make a ticket removable.
   */
  @Test
  void neverRemovesTicketsForFilteredRuns() {
    var annotations = new FailureAnnotations(List.of(
        annotation("demo.feature", "Gefiltertes Szenario", "ZETA-6")));
    var result = RuntimeFailureAnnotationAudit.evaluate(annotations,
        List.of(outcome("demo.feature", "Gefiltertes Szenario", Status.PASSED)), false);

    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .contains(RuntimeFailureAnnotationAudit.EXECUTION_INCOMPLETE)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /** Prevents profile-selected runs from authorizing removal of catalog-wide annotations. */
  @Test
  void authenticationSelectionIsAnIncompleteCatalogRun() {
    String original = System.getProperty("cucumber.filter.tags");
    try {
      System.setProperty("cucumber.filter.tags", "not @Ignore and not @smb");
      var annotations = new FailureAnnotations(List.of(
          annotation("demo.feature", "Profile scenario", "ZETA-13")));
      var result = RuntimeFailureAnnotationAudit.evaluate(annotations,
          List.of(outcome("demo.feature", "Profile scenario", Status.PASSED)));
      assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
          .contains(RuntimeFailureAnnotationAudit.EXECUTION_INCOMPLETE)
          .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
    } finally {
      if (original == null) {
        System.clearProperty("cucumber.filter.tags");
      } else {
        System.setProperty("cucumber.filter.tags", original);
      }
    }
  }

  /**
   * Verifies that a direct Cucumber feature selection cannot make a ticket removable.
   */
  @Test
  void neverRemovesTicketsForFeatureSelection() {
    var annotations = new FailureAnnotations(List.of(
        annotation("demo.feature", "Eingegrenztes Szenario", "ZETA-12")));
    var originalFeatures = System.getProperty("cucumber.features");
    try {
      System.setProperty("cucumber.features", "src/test/resources/features/demo.feature");

      var result = RuntimeFailureAnnotationAudit.evaluate(annotations,
          List.of(outcome("demo.feature", "Eingegrenztes Szenario", Status.PASSED)));

      assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
          .contains(RuntimeFailureAnnotationAudit.EXECUTION_INCOMPLETE)
          .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
    } finally {
      restoreSystemProperty("cucumber.features", originalFeatures);
    }
  }

  /**
   * Verifies that a short feature name matching multiple paths is reported as ambiguous.
   */
  @Test
  void rejectsFeaturePathSuffixCollision() {
    var annotations = new FailureAnnotations(List.of(
        annotation("foo.feature", "Gleiches Szenario", "ZETA-7")));
    var outcomes = List.of(
        outcome("features/one/foo.feature", "Gleiches Szenario", Status.PASSED),
        outcome("features/two/foo.feature", "Gleiches Szenario", Status.PASSED));

    var result = RuntimeFailureAnnotationAudit.evaluate(annotations, outcomes, true);

    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .contains(RuntimeFailureAnnotationAudit.ANNOTATION_AMBIGUOUS)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /**
   * Verifies a feature name is not matched inside another file name.
   */
  @Test
  void doesNotMatchFeatureNameWithoutPathBoundary() {
    var annotations = new FailureAnnotations(List.of(
        annotation("foo.feature", "Gleiches Szenario", "ZETA-10")));
    var result = RuntimeFailureAnnotationAudit.evaluate(annotations,
        List.of(outcome("features/notfoo.feature", "Gleiches Szenario", Status.PASSED)), true);

    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .contains(RuntimeFailureAnnotationAudit.NOT_EXECUTED)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /**
   * Verifies that unknown annotation mapping types are reported as invalid.
   */
  @Test
  void rejectsUnknownAnnotationMappingType() {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Scenaro", "", "", "demo.feature", "Szenario", Set.of("ZETA-8"), "Team")));

    var result = RuntimeFailureAnnotationAudit.evaluate(annotations, List.of(), true);

    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .containsExactly(RuntimeFailureAnnotationAudit.INVALID_ANNOTATION);
    assertThat(result.hasCoverageFindings()).isTrue();
  }

  /**
   * Verifies known non-scenario mappings without their required ID are invalid.
   */
  @Test
  void rejectsKnownMappingWithoutRequiredId() {
    var annotations = new FailureAnnotations(List.of(new FailureAnnotation(
        "Anforderung", "", "", "", "", Set.of("ZETA-11"), "Team")));

    var result = RuntimeFailureAnnotationAudit.evaluate(annotations, List.of(), true);

    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .containsExactly(RuntimeFailureAnnotationAudit.INVALID_ANNOTATION);
    assertThat(result.hasCoverageFindings()).isTrue();
  }

  /**
   * Verifies a feature-only CSV annotation cannot satisfy the scenario-specific audit.
   */
  @Test
  void rejectsScenarioAnnotationWithoutScenarioName() {
    var annotation = annotation("demo.feature", "", "ZETA-14");
    var outcome = outcome("demo.feature", "Fehlgeschlagenes Szenario", Status.FAILED);

    var result = RuntimeFailureAnnotationAudit.evaluate(
        new FailureAnnotations(List.of(annotation)), List.of(outcome), true);

    assertThat(annotation.matchesScenario(outcome)).isFalse();
    assertThat(result.invalidAnnotations()).isEqualTo(1);
    assertThat(result.missingTickets()).isEqualTo(1);
    assertThat(result.hasCoverageFindings()).isTrue();
    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .containsExactlyInAnyOrder(RuntimeFailureAnnotationAudit.INVALID_ANNOTATION,
            RuntimeFailureAnnotationAudit.FAILURE_WITHOUT_TICKET);
  }

  /**
   * Verifies that an outcome without a feature identity cannot match an annotation.
   */
  @Test
  void rejectsOutcomeWithoutFeatureIdentity() {
    var annotations = new FailureAnnotations(List.of(
        annotation("demo.feature", "Szenario", "ZETA-9")));

    var result = RuntimeFailureAnnotationAudit.evaluate(annotations,
        List.of(outcome("", "Szenario", Status.PASSED)), true);

    assertThat(result.rows()).extracting(RuntimeFailureAnnotationAudit.AuditRow::type)
        .contains(RuntimeFailureAnnotationAudit.EXECUTION_INCOMPLETE,
            RuntimeFailureAnnotationAudit.NOT_EXECUTED)
        .doesNotContain(RuntimeFailureAnnotationAudit.TICKET_REMOVABLE);
  }

  /**
   * Create one scenario annotation for an audit test.
   *
   * @param feature  feature path
   * @param scenario scenario name
   * @param ticket   ticket identifier
   * @return annotation
   */
  private FailureAnnotation annotation(String feature, String scenario, String ticket) {
    return new FailureAnnotation("Szenario", "", "", feature, scenario, Set.of(ticket), "Team");
  }

  /**
   * Create one scenario outcome for an audit test.
   *
   * @param feature  feature path
   * @param scenario scenario name
   * @param status   scenario status
   * @return outcome
   */
  private ScenarioOutcome outcome(String feature, String scenario, Status status) {
    return new ScenarioOutcome(feature, scenario, null, status);
  }

  /**
   * Restore one system property after temporarily changing it for a test.
   *
   * @param key           property key
   * @param originalValue value before the test, or null when it was absent
   */
  private void restoreSystemProperty(String key, String originalValue) {
    if (originalValue == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, originalValue);
  }
}
