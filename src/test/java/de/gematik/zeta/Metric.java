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
 */
public class Metric {

  public enum Type {PERCENTILE, MAX, MIN, AVG}

  private final Type type;
  private final double percentile; // only set if type == PERCENTILE

  private Metric(Type type, double percentile) {
    this.type = type;
    this.percentile = percentile;
  }

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
   * Returns the metric type.
   */
  public Type getType() {
    return type;
  }

  /**
   * Returns the percentile value (only for PERCENTILE).
   */
  public double getPercentile() {
    return percentile;
  }
}
