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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares scenario-level runtime outcomes with the internal failure annotations.
 *
 * <p>The audit status is {@code FAIL} when ticket coverage is missing, invalid, or ambiguous.
 * A skipped or incomplete execution is reported for follow-up, but never treated as proof that a
 * ticket can be removed. Ticket removal is also suppressed for Cucumber and Maven filters
 * propagated to the test JVM. Filters applied only outside that JVM must expose an equivalent
 * runtime property or be reviewed with the generated audit report.</p>
 */
final class RuntimeFailureAnnotationAudit {

  static final String REPORT_FILE = "runtime_failure_annotation_check.csv";
  static final String STATUS_FILE = "runtime_failure_annotation_check.status";
  static final String FAILURE_AMBIGUOUS = "FAILURE_AMBIGUOUS";
  static final String FAILURE_WITHOUT_TICKET = "FAILURE_WITHOUT_TICKET";
  static final String FAILURE_WITH_TICKET = "FAILURE_WITH_TICKET";
  static final String IGNORED_ANNOTATION = "IGNORED_ANNOTATION";
  static final String INVALID_ANNOTATION = "INVALID_ANNOTATION";
  static final String NOT_EXECUTED = "NOT_EXECUTED";
  static final String ANNOTATION_AMBIGUOUS = "ANNOTATION_AMBIGUOUS";
  static final String ANNOTATION_ACTIVE = "ANNOTATION_ACTIVE";
  static final String EXECUTION_INCOMPLETE = "EXECUTION_INCOMPLETE";
  static final String TICKET_REMOVABLE = "TICKET_REMOVABLE";
  private static final String[] HEADERS = {
      "Typ", "Feature", "Szenario", "Zeile", "Testrun-Status", "Tickets",
      "Verantwortlich", "Hinweis"
  };

  private RuntimeFailureAnnotationAudit() {
  }

  /**
   * Evaluate all failed outcomes and all scenario annotation rows.
   *
   * @param annotations configured internal annotations
   * @param outcomes    scenario outcomes from the current test run
   * @return audit result
   */
  static AuditResult evaluate(FailureAnnotations annotations,
      Collection<ScenarioOutcome> outcomes) {
    return evaluate(annotations, outcomes, isCompleteRun());
  }

  /**
   * Evaluate outcomes with an explicit run-completeness flag.
   *
   * @param annotations configured internal annotations
   * @param outcomes    scenario outcomes from the current test run
   * @param completeRun true when all selected scenarios were executed
   * @return audit result
   */
  static AuditResult evaluate(FailureAnnotations annotations,
      Collection<ScenarioOutcome> outcomes, boolean completeRun) {
    var uniqueOutcomes = uniqueOutcomes(outcomes);
    var rows = new ArrayList<AuditRow>();
    var missingTickets = 0;
    var invalidAnnotations = 0;
    var ambiguousMatches = 0;

    for (var outcome : uniqueOutcomes.values()) {
      if (!outcome.hasIdentity() || outcome.isIncomplete()) {
        rows.add(new AuditRow(EXECUTION_INCOMPLETE, outcome.feature(), outcome.scenario(),
            outcome.line(), renderStatus(outcome.status()), "", "",
            "Feature, Szenarioname oder Status ist nicht vollständig auswertbar; Ticket nicht "
                + "automatisch entfernen."));
        continue;
      }
      if (!outcome.isFailure()) {
        continue;
      }
      var matches = matchingAnnotations(annotations, outcome);
      var ticketMatches = matches.stream().filter(FailureAnnotation::hasTicket).toList();
      var ambiguous = isAmbiguous(matches, uniqueOutcomes.values());
      if (ambiguous) {
        ambiguousMatches++;
        rows.add(new AuditRow(FAILURE_AMBIGUOUS, outcome.feature(), outcome.scenario(),
            outcome.line(), renderStatus(outcome.status()), joinTickets(ticketMatches),
            joinOwners(ticketMatches),
            "Die Zuordnung kann mehr als einen Feature-Pfad treffen."));
      } else if (ticketMatches.isEmpty()) {
        missingTickets++;
        rows.add(new AuditRow(FAILURE_WITHOUT_TICKET, outcome.feature(), outcome.scenario(),
            outcome.line(), renderStatus(outcome.status()), joinTickets(matches),
            joinOwners(matches), "Für dieses fehlgeschlagene Szenario ist kein Ticket hinterlegt."));
      } else {
        rows.add(new AuditRow(FAILURE_WITH_TICKET, outcome.feature(), outcome.scenario(),
            outcome.line(), renderStatus(outcome.status()), joinTickets(ticketMatches),
            joinOwners(ticketMatches), "Fehlgeschlagenes Szenario ist durch mindestens ein Ticket abgedeckt."));
      }
    }

    var configuredAnnotations = annotations == null ? List.<FailureAnnotation>of() : annotations.rows();
    for (var annotation : configuredAnnotations) {
      if (!isKnownMappingType(annotation)) {
        invalidAnnotations++;
        rows.add(new AuditRow(INVALID_ANNOTATION, annotation.feature(), annotation.scenario(), null,
            "", joinTickets(annotation), annotation.owner(),
            "Unbekannte Zuordnung='" + annotation.matchType() + "'."));
        continue;
      }
      if (!annotation.isScenarioMapping()) {
        if (!hasRequiredMappingId(annotation)) {
          invalidAnnotations++;
          rows.add(new AuditRow(INVALID_ANNOTATION, annotation.feature(), annotation.scenario(),
              null, "", joinTickets(annotation), annotation.owner(),
              "Für Zuordnung='" + annotation.matchType()
                  + "' muss die zugehörige ID gesetzt sein."));
          continue;
        }
        rows.add(new AuditRow(IGNORED_ANNOTATION, annotation.feature(), annotation.scenario(), null, "",
            joinTickets(annotation), annotation.owner(),
            "Nur Zuordnung=Szenario wird im szenariogenauen Gegencheck ausgewertet."));
        continue;
      }
      if (annotation.scenario().isBlank() || !annotation.hasTicket()) {
        invalidAnnotations++;
        rows.add(new AuditRow(INVALID_ANNOTATION, annotation.feature(), annotation.scenario(),
            null, "", joinTickets(annotation), annotation.owner(),
            annotation.scenario().isBlank()
                ? "Szenario muss gesetzt sein."
                : "Für eine Szenario-Annotation muss mindestens ein Ticket gesetzt sein."));
        continue;
      }
      var matches = uniqueOutcomes.values().stream()
          .filter(annotation::matchesScenario)
          .toList();
      if (matches.isEmpty()) {
        rows.add(new AuditRow(NOT_EXECUTED, annotation.feature(), annotation.scenario(), null,
            "", joinTickets(annotation), annotation.owner(),
            "Im aktuellen Lauf nicht beobachtet; daraus folgt keine Löschfreigabe."));
      } else if (isAmbiguous(matches)) {
        ambiguousMatches++;
        rows.add(new AuditRow(ANNOTATION_AMBIGUOUS, annotation.feature(), annotation.scenario(),
            null, "", joinTickets(annotation), annotation.owner(),
            "Die Annotation passt zu mehreren Feature-Pfaden."));
      } else if (matches.stream().anyMatch(ScenarioOutcome::isFailure)) {
        rows.add(new AuditRow(ANNOTATION_ACTIVE, annotation.feature(), annotation.scenario(), null,
            renderStatuses(matches), joinTickets(annotation), annotation.owner(),
            "Mindestens eine passende Ausführung ist fehlgeschlagen."));
      } else if (matches.stream().anyMatch(ScenarioOutcome::isSkipped)) {
        rows.add(new AuditRow(EXECUTION_INCOMPLETE, annotation.feature(), annotation.scenario(),
            null, renderStatuses(matches), joinTickets(annotation), annotation.owner(),
            "Nur teilweise oder gar nicht ausgeführt; Ticket nicht automatisch entfernen."));
      } else if (!completeRun) {
        rows.add(new AuditRow(EXECUTION_INCOMPLETE, annotation.feature(), annotation.scenario(),
            null, renderStatuses(matches), joinTickets(annotation), annotation.owner(),
            "Der Lauf ist gefiltert und belegt nicht alle passenden Ausführungen; Ticket nicht "
                + "automatisch entfernen."));
      } else if (matches.stream().allMatch(ScenarioOutcome::isPassed)) {
        rows.add(new AuditRow(TICKET_REMOVABLE, annotation.feature(), annotation.scenario(), null,
            renderStatuses(matches), joinTickets(annotation), annotation.owner(),
            "Alle passenden Ausführungen waren erfolgreich; Ticket kann geprüft und entfernt werden."));
      } else {
        rows.add(new AuditRow(EXECUTION_INCOMPLETE, annotation.feature(), annotation.scenario(),
            null, renderStatuses(matches), joinTickets(annotation), annotation.owner(),
            "Ausführung enthält einen nicht abschließend bewertbaren Status; Ticket nicht automatisch entfernen."));
      }
    }

    rows.sort(Comparator.comparing(AuditRow::type)
        .thenComparing(AuditRow::feature)
        .thenComparing(AuditRow::scenario));
    return new AuditResult(rows, missingTickets, invalidAnnotations, ambiguousMatches);
  }

  /**
   * Check whether an annotation uses one of the supported mapping types.
   *
   * @param annotation annotation row
   * @return true for known mapping types
   */
  private static boolean isKnownMappingType(FailureAnnotation annotation) {
    return annotation != null
        && List.of("scenario", "anforderung", "testaspekt").contains(annotation.matchType());
  }

  /**
   * Check whether a non-scenario annotation has the ID needed for its mapping type.
   *
   * @param annotation annotation row
   * @return true when the mapping can be resolved by its ID
   */
  private static boolean hasRequiredMappingId(FailureAnnotation annotation) {
    if (annotation == null) {
      return false;
    }
    if ("anforderung".equals(annotation.matchType())) {
      return !annotation.requirementId().isBlank();
    }
    return !annotation.testAspectId().isBlank();
  }

  /**
   * Write the human- and machine-readable audit files.
   *
   * @param outputDirectory target report directory
   * @param result          audit result
   * @throws IOException when a report cannot be written
   */
  static void write(Path outputDirectory, AuditResult result) throws IOException {
    Files.createDirectories(outputDirectory);
    var report = outputDirectory.resolve(REPORT_FILE);
    var temporaryReport = report.resolveSibling(report.getFileName() + ".tmp");
    try (var writer = Files.newBufferedWriter(temporaryReport, StandardCharsets.UTF_8);
        var printer = RuntimeCoverageCsvRepository.csvFormat(HEADERS).print(writer)) {
      for (var row : result.rows()) {
        printer.printRecord(row.type(), row.feature(), row.scenario(),
            row.line() == null ? "" : row.line(), row.runStatus(), row.tickets(), row.owner(),
            row.note());
      }
    }
    Files.move(temporaryReport, report, StandardCopyOption.REPLACE_EXISTING);

    var status = outputDirectory.resolve(STATUS_FILE);
    var temporaryStatus = status.resolveSibling(status.getFileName() + ".tmp");
    Files.writeString(temporaryStatus, result.hasCoverageFindings() ? "FAIL\n" : "PASS\n",
        StandardCharsets.UTF_8);
    Files.move(temporaryStatus, status, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Render a concise audit summary for the test-run log.
   *
   * @param result audit result
   * @return summary text
   */
  static String summary(AuditResult result) {
    return "missingTickets=" + result.missingTickets()
        + ", invalidAnnotations=" + result.invalidAnnotations()
        + ", ambiguousMatches=" + result.ambiguousMatches()
        + ", removableTickets=" + result.removableTickets();
  }

  /**
   * Deduplicate outcomes that were observed both through events and fallback JSON.
   *
   * @param outcomes raw outcomes
   * @return outcomes by stable identity
   */
  private static Map<String, ScenarioOutcome> uniqueOutcomes(Collection<ScenarioOutcome> outcomes) {
    var unique = new LinkedHashMap<String, ScenarioOutcome>();
    if (outcomes == null) {
      return unique;
    }
    for (var outcome : outcomes) {
      if (outcome == null) {
        continue;
      }
      unique.merge(outcome.key(), outcome, ScenarioOutcome::merge);
    }
    return unique;
  }

  /**
   * Find all scenario annotations matching one outcome.
   *
   * @param annotations annotation catalogue
   * @param outcome     scenario outcome
   * @return matching annotations
   */
  private static List<FailureAnnotation> matchingAnnotations(FailureAnnotations annotations,
      ScenarioOutcome outcome) {
    if (annotations == null) {
      return List.of();
    }
    return annotations.rows().stream()
        .filter(FailureAnnotation::isScenarioMapping)
        .filter(annotation -> annotation.matchesScenario(outcome))
        .toList();
  }

  /**
   * Check whether a failure annotation resolves to more than one feature path.
   *
   * @param matches  matching annotations
   * @param outcomes all outcomes
   * @return true when one annotation could refer to multiple features
   */
  private static boolean isAmbiguous(List<FailureAnnotation> matches,
      Collection<ScenarioOutcome> outcomes) {
    return matches.stream()
        .map(annotation -> outcomes.stream()
            .filter(annotation::matchesScenario)
            .toList())
        .anyMatch(RuntimeFailureAnnotationAudit::isAmbiguous);
  }

  /**
   * Check whether matching outcomes span multiple feature paths.
   *
   * @param outcomes   matching outcomes
   * @return true when the mapping is ambiguous
   */
  private static boolean isAmbiguous(List<ScenarioOutcome> outcomes) {
    return outcomes.stream()
        .map(ScenarioOutcome::feature)
        .filter(feature -> !feature.isBlank())
        .collect(Collectors.toSet())
        .size() > 1;
  }

  /**
   * Render the ticket values from matching annotations.
   *
   * @param annotations matching annotations
   * @return distinct ticket values
   */
  private static String joinTickets(Collection<FailureAnnotation> annotations) {
    if (annotations == null) {
      return "";
    }
    return annotations.stream()
        .flatMap(annotation -> annotation.tickets().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new))
        .stream()
        .collect(Collectors.joining("; "));
  }

  /**
   * Render the ticket value from one annotation.
   *
   * @param annotation annotation row
   * @return ticket values
   */
  private static String joinTickets(FailureAnnotation annotation) {
    return annotation == null ? "" : String.join("; ", annotation.tickets());
  }

  /**
   * Render distinct owners from matching annotations.
   *
   * @param annotations matching annotations
   * @return distinct owner values
   */
  private static String joinOwners(Collection<FailureAnnotation> annotations) {
    if (annotations == null) {
      return "";
    }
    return annotations.stream()
        .flatMap(annotation -> RuntimeCoverageCsvRepository.parseMultiValue(annotation.owner()).stream())
        .collect(Collectors.toCollection(LinkedHashSet::new))
        .stream()
        .collect(Collectors.joining("; "));
  }

  /**
   * Render one Cucumber status in German.
   *
   * @param status Cucumber status
   * @return rendered status
   */
  private static String renderStatus(Status status) {
    if (status == Status.PASSED) {
      return "bestanden";
    }
    if (status == Status.SKIPPED) {
      return "übersprungen";
    }
    if (status == Status.FAILED) {
      return "fehlgeschlagen";
    }
    return "unvollständig";
  }

  /**
   * Determine whether the current Cucumber invocation is an unfiltered full run.
   *
   * <p>This check can only detect selections propagated into the test JVM. Build-tool profile
   * filters which do not expose a system property or environment variable cannot be observed
   * here and require review of the generated audit report before ticket removal.</p>
   *
   * @return true when no propagated Cucumber or Maven test filter is configured
   */
  private static boolean isCompleteRun() {
    return !hasText(System.getProperty("cucumber.filter.tags"))
        && !hasText(System.getProperty("cucumber.filter.name"))
        && !hasText(System.getProperty("cucumber.features"))
        && !hasText(System.getProperty("cucumber.execution.limit"))
        && !hasText(System.getProperty("it.test"))
        && !hasText(System.getenv("CUCUMBER_FILTER_TAGS"))
        && !hasText(System.getenv("CUCUMBER_TAGS"))
        && !hasText(System.getenv("CUCUMBER_FEATURES"))
        && !hasText(System.getenv("CUCUMBER_EXECUTION_LIMIT"));
  }

  /**
   * Check whether a configuration value contains text.
   *
   * @param value configuration value
   * @return true when the value is non-empty
   */
  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * Render all statuses in a matching outcome set.
   *
   * @param outcomes matching outcomes
   * @return distinct rendered statuses
   */
  private static String renderStatuses(Collection<ScenarioOutcome> outcomes) {
    return outcomes.stream()
        .map(outcome -> renderStatus(outcome.status()))
        .distinct()
        .collect(Collectors.joining("; "));
  }

  /**
   * Audit output row.
   *
   * @param type       row classification
   * @param feature    feature path
   * @param scenario   scenario name
   * @param line       scenario line
   * @param runStatus  runtime status
   * @param tickets    ticket values
   * @param owner      responsible party
   * @param note       actionable explanation
   */
  record AuditRow(String type, String feature, String scenario, Integer line, String runStatus,
                  String tickets, String owner, String note) {
  }

  /**
   * Complete result of the annotation audit.
   *
   * @param rows               report rows
   * @param missingTickets     failed scenarios without a ticket
   * @param invalidAnnotations malformed or ticket-less scenario annotations
   * @param ambiguousMatches   mappings that cannot be proven uniquely
   */
  record AuditResult(List<AuditRow> rows, int missingTickets, int invalidAnnotations,
                     int ambiguousMatches) {

    /**
     * Copy report rows defensively.
     */
    AuditResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * Check whether the audit status should report coverage findings.
     *
     * @return true when required proof is missing or ambiguous
     */
    boolean hasCoverageFindings() {
      return missingTickets > 0 || invalidAnnotations > 0 || ambiguousMatches > 0;
    }

    /**
     * Count annotations whose scenario passed in the current run.
     *
     * @return number of removable-ticket recommendations
     */
    int removableTickets() {
      return (int) rows.stream().filter(row -> TICKET_REMOVABLE.equals(row.type())).count();
    }
  }
}
