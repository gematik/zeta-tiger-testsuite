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

package de.gematik.zeta.steps;

import de.gematik.zeta.Metric;
import de.gematik.zeta.TigerMetric;
import io.cucumber.java.ParameterType;
import java.util.Locale;

/**
 * Central Cucumber parameter types for custom step definitions.
 */
public class ParamTypes {

  /**
   * Defines a parameter type for performance metrics. Matches patterns like pNN (e.g., p95, p99) or
   * keywords max, min, avg.
   *
   * @param token metric identifier from the step
   * @return Metric object representing the requested metric
   */
  @ParameterType("p\\d{1,3}|max|min|avg")
  public Metric metric(String token) {
    String t = token.toLowerCase(Locale.ROOT).trim();
    if (t.startsWith("p")) {
      int nn = Integer.parseInt(t.substring(1));
      if (nn < 0 || nn > 100) {
        throw new IllegalArgumentException("Percentile out of range: " + nn);
      }
      return Metric.percentile(nn / 100.0);
    }
    return switch (t) {
      case "max" -> Metric.max();
      case "min" -> Metric.min();
      case "avg" -> Metric.avg();
      default -> throw new IllegalArgumentException("Unsupported metric: " + token);
    };
  }

  /**
   * Defines a parameter type for Tiger metrics. Allowed values: e2e_ms, forward_ms, service_ms,
   * return_ms, middleware_overhead_ms.
   *
   * @param value column name in Tiger CSV
   * @return TigerMetric matching the given value
   */
  @ParameterType("e2e_ms|forward_ms|service_ms|return_ms|middleware_overhead_ms")
  public TigerMetric tigerMetric(String value) {
    for (TigerMetric m : TigerMetric.values()) {
      if (m.getColumnName().equalsIgnoreCase(value)) {
        return m;
      }
    }
    throw new IllegalArgumentException("Unknown Tiger metric: " + value);
  }


  /**
   * Defines a parameter type for JMeter summary metrics. Allowed values: errorRate, rps, avg_ms,
   * pNN_ms, max_ms, min_ms.
   *
   * @param m metric column name in JMeter CSV
   * @return the given string as metric name
   */
  @ParameterType("errorRate|rps|avg_ms|p\\d{1,3}_ms|max_ms|min_ms")
  public String jmeterMetric(String m) {
    return m;
  }
}

