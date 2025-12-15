/*-
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

package de.gematik.zeta.perf;

import lombok.Builder;

/**
 * Statistics about traffic correlation issues and results.
 */
@Builder
public record CorrelationStats(int ingressJMeterReq, int ingressBackgroundReq, int egressJMeterReq,
                               int egressBackgroundReq, int inMissingResp, int inRespBeforeReq,
                               int inDuplicateResp, int egMissingResp, int egRespBeforeReq,
                               int egDuplicateResp, int nonMonotonicPairsSkipped) {

  /**
   * Returns true if any correlation issues exist.
   */
  public boolean hasIssues() {
    return inMissingResp > 0 || inRespBeforeReq > 0 || inDuplicateResp > 0 || egMissingResp > 0
        || egRespBeforeReq > 0 || egDuplicateResp > 0 || nonMonotonicPairsSkipped > 0;
  }

  /**
   * Returns a short text summary of detected issues, or "No correlation issues" if none.
   */
  public String getSummary() {
    if (!hasIssues()) {
      return "No correlation issues";
    }

    StringBuilder sb = new StringBuilder();
    if (inMissingResp > 0) {
      sb.append("ingress missing responses: ").append(inMissingResp).append(", ");
    }
    if (egMissingResp > 0) {
      sb.append("egress missing responses: ").append(egMissingResp).append(", ");
    }
    if (nonMonotonicPairsSkipped > 0) {
      sb.append("non-monotonic flows: ").append(nonMonotonicPairsSkipped).append(", ");
    }

    String result = sb.toString();
    return result.endsWith(", ") ? result.substring(0, result.length() - 2) : result;
  }
}
