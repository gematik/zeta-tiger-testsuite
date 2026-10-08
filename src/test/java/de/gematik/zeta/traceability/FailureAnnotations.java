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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parsed annotation catalogue with requirement and test aspect lookup helpers.
 *
 * @param annotations annotation rows
 */
record FailureAnnotations(List<FailureAnnotation> annotations) {

  /**
   * Copy annotation rows defensively.
   */
  FailureAnnotations {
    annotations = annotations == null ? List.of() : List.copyOf(annotations);
  }

  /**
   * Return an empty catalogue.
   *
   * @return empty annotation catalogue
   */
  static FailureAnnotations empty() {
    return new FailureAnnotations(List.of());
  }

  /**
   * Find annotations for a requirement output row.
   *
   * @param requirementId requirement identifier
   * @param failures      failure records for the requirement
   * @param scenarios     executed scenario names for the requirement
   * @return aggregate annotation summary
   */
  AnnotationSummary findForRequirement(String requirementId, Set<FailureRecord> failures,
      Set<String> scenarios) {
    return summarize(annotations.stream()
        .filter(FailureAnnotation::hasOutput)
        .filter(annotation -> annotation.matchesRequirement(requirementId, failures, scenarios))
        .toList());
  }

  /**
   * Find annotations for a test aspect output row.
   *
   * @param testAspectId  test aspect identifier
   * @param requirementId requirement identifier
   * @param failures      failure records for the test aspect
   * @param scenarios     executed scenario names for the test aspect
   * @return aggregate annotation summary
   */
  AnnotationSummary findForTestAspect(String testAspectId, String requirementId,
      Set<FailureRecord> failures, Set<String> scenarios) {
    return summarize(annotations.stream()
        .filter(FailureAnnotation::hasOutput)
        .filter(annotation -> annotation.matchesTestAspect(testAspectId, requirementId, failures,
            scenarios))
        .toList());
  }

  /**
   * Aggregate annotation rows into rendered CSV cell values.
   *
   * @param annotations matching annotation rows
   * @return rendered summary
   */
  private static AnnotationSummary summarize(List<FailureAnnotation> annotations) {
    if (annotations == null || annotations.isEmpty()) {
      return AnnotationSummary.empty();
    }
    var tickets = new LinkedHashSet<String>();
    var owners = new LinkedHashSet<String>();
    for (var annotation : annotations) {
      tickets.addAll(annotation.tickets());
      if (!annotation.owner().isBlank()) {
        owners.addAll(RuntimeCoverageCsvRepository.parseMultiValue(annotation.owner()));
      }
    }
    return new AnnotationSummary(
        String.join("; ", tickets),
        String.join("; ", owners));
  }
}
