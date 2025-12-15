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

package de.gematik.zeta;

/**
 * Represents a statistical metric like pNN, max, min, avg.
 *
 * @param type       -- GETTER -- Returns the metric type.
 * @param percentile -- GETTER -- Returns the percentile value (only for PERCENTILE). only set if
 *                   type == PERCENTILE
 */
public record Metric(Type type, double percentile) {

  /**
   * Creates a percentile metric (e.g., 0.95 for p95).
   */
  public static Metric percentile(double p) {
    return new Metric(Type.PERCENTILE, p);
  }

  /**
   * Creates a max metric.
   */
  public static Metric max() {
    return new Metric(Type.MAX, Double.NaN);
  }

  /**
   * Creates a min metric.
   */
  public static Metric min() {
    return new Metric(Type.MIN, Double.NaN);
  }

  /**
   * Creates an average (mean) metric.
   */
  public static Metric avg() {
    return new Metric(Type.AVG, Double.NaN);
  }

  /**
   * Enumeration for describing specific metric types.
   */
  public enum Type {
    PERCENTILE, MAX, MIN, AVG
  }

}
