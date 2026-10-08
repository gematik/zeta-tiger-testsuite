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

package de.gematik.zeta.perf;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.Metric;
import io.cucumber.datatable.DataTable;
import java.util.List;
import java.util.Map;

/** Converts compact Cucumber latency tables into typed Prometheus limits. */
public final class PrometheusLatencyLimitTable {

  private static final List<String> LATENCY_COLUMNS = List.of("avg", "p90", "p95", "p99");
  private static final Map<String, Metric> LATENCY_METRICS = Map.of(
      "avg", Metric.avg(),
      "p90", Metric.percentile(0.90),
      "p95", Metric.percentile(0.95),
      "p99", Metric.percentile(0.99));

  private PrometheusLatencyLimitTable() {
  }

  /**
   * Expands each populated latency cell into one scalar Prometheus limit.
   *
   * @param table rows with mandatory {@code service} and {@code span} cells and optional latency
   *     cells
   * @return latency limits in table-row and metric-column order
   * @throws IllegalArgumentException if a mandatory cell is absent or a threshold is not numeric
   */
  public static List<PrometheusLatencyLimit> parse(DataTable table) {
    return table.asMaps(String.class, String.class).stream()
        .flatMap(row -> {
          var serviceName = requiredCell(row, "service");
          var spanName = requiredCell(row, "span");
          return LATENCY_COLUMNS.stream()
              .filter(column -> row.get(column) != null && !row.get(column).isBlank())
              .map(column -> new PrometheusLatencyLimit(
                  serviceName,
                  spanName,
                  LATENCY_METRICS.get(column),
                  Double.parseDouble(TigerGlobalConfiguration.resolvePlaceholders(
                      row.get(column)).trim().replace(',', '.'))));
        })
        .toList();
  }

  /**
   * Returns a mandatory table cell after rejecting absent or blank values.
   *
   * @param row current latency-limit row
   * @param column mandatory column name
   * @return non-blank cell value
   * @throws IllegalArgumentException if the cell is absent or blank
   */
  private static String requiredCell(Map<String, String> row, String column) {
    var value = row.get(column);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing latency table value: " + column);
    }
    return value;
  }
}
