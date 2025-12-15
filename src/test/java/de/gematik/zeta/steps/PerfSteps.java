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
import de.gematik.zeta.perf.CsvUtils;
import de.gematik.zeta.perf.FileUtils;
import de.gematik.zeta.perf.TigerTraceAnalyzer;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for Tiger trace analysis and CSV-based latency assertions.
 */
@Slf4j
public class PerfSteps {

  private final TigerTraceAnalyzer tigerAnalyzer;

  /**
   * Default constructor setting the tiger trace analyzer.
   */
  public PerfSteps() {
    this.tigerAnalyzer = new TigerTraceAnalyzer();
  }

  /**
   * Analyzes ingress/egress .tgr files and writes a merged latency CSV.
   *
   * @param ingressTgr path to ingress .tgr
   * @param egressTgr  path to egress .tgr
   * @param outCsv     output CSV path
   */
  @Und("analysiere Tiger Traffic aus {string} und {string} nach {string}")
  @And("analyze Tiger traffic from {string} and {string} to {string}")
  public void analyzeTigerTraffic(String ingressTgr, String egressTgr, String outCsv)
      throws Exception {
    Path ingress = FileUtils.resolveExisting(ingressTgr);
    Path egress = FileUtils.resolveExisting(egressTgr);
    Path out = Path.of(outCsv).toAbsolutePath().normalize();

    FileUtils.ensureParentDirectories(out);

    log.info("Analyzing Tiger traffic: ingress={}, egress={}, output={}", ingress, egress, out);
    tigerAnalyzer.analyzeAndWriteCsv(ingress, egress, out);

    FileUtils.requireFileExists(out);
  }

  /**
   * Analyzes end-to-end timing from ingress traffic only.
   *
   * @param ingressTgr path to ingress .tgr
   * @param outCsv     output CSV path
   */
  @Und("analysiere Tiger E2E aus {string} nach {string}")
  @And("analyze Tiger E2E from {string} to {string}")
  public void analyzeTigerE2E(String ingressTgr, String outCsv) throws Exception {
    Path ingress = FileUtils.resolveExisting(ingressTgr);
    Path out = Path.of(outCsv).toAbsolutePath().normalize();

    FileUtils.ensureParentDirectories(out);

    log.info("Analyzing Tiger E2E timing: ingress={}, output={}", ingress, out);
    tigerAnalyzer.analyzeIngressE2EAndWriteCsv(ingress, out);
    FileUtils.requireFileExists(out);
  }

  /**
   * Logs summary stats for key Tiger metrics in a CSV.
   *
   * @param csvPath path to the metrics CSV
   */
  @Und("zeige Tiger Traffic Kennzahlen aus {string} an")
  @And("show Tiger traffic metrics from {string}")
  public void showTigerTrafficSummary(String csvPath) throws IOException {
    log.info("Displaying Tiger traffic metrics from: {}", csvPath);

    Map<String, double[]> metrics = CsvUtils.readNumericColumns(
        Path.of(csvPath),
        "forward_ms", "service_ms", "middleware_overhead_ms", "e2e_ms"
    );

    for (Map.Entry<String, double[]> entry : metrics.entrySet()) {
      printMetricStats(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Asserts a metric (percentile/max/min/avg) for a Tiger column is ≤ threshold.
   *
   * @param csvPath path to CSV
   * @param column  Tiger column name
   * @param metric  metric to compute
   * @param maxMs   upper bound in ms
   */
  @Dann("stelle sicher, dass in {string} für die Spalte {tigerMetric} der {metric}-Wert <= {int} ms ist")
  @Then("ensure that in {string}, for column {tigerMetric}, the {metric} value is <= {int} ms")
  public void assertTigerCsvStatLe(String csvPath, TigerMetric column, Metric metric, Integer maxMs)
      throws IOException {
    assertTigerCsvMetric(csvPath, column, metric, maxMs);
  }

  /**
   * Loads CSV, resolves the effective column, computes the metric and checks it against the
   * threshold.
   *
   * @param csvPath     path to CSV
   * @param tigerMetric requested column
   * @param metric      metric to compute
   * @param maxMs       upper bound in ms
   */
  private void assertTigerCsvMetric(String csvPath, TigerMetric tigerMetric, Metric metric,
      Integer maxMs) throws IOException {
    String columnName = tigerMetric.getColumnName();
    Map<String, double[]> metrics = CsvUtils.readNumericColumns(Path.of(csvPath), columnName);

    // Get the effective column name (handles legacy mappings)
    CsvUtils.CsvData csv = CsvUtils.readCsv(Path.of(csvPath));
    String effectiveColumn = CsvUtils.resolveColumn(columnName, csv.headers());

    double[] values = metrics.get(columnName);
    if (values == null || values.length == 0) {
      throw new AssertionError(
          "No values found for column: " + columnName + " (effective: " + effectiveColumn + ") in "
              + csvPath + " | available columns: " + csv.headers());
    }

    double observed = calculateMetricValue(values, metric);
    String metricName = formatMetricName(metric);

    log.info("[ASSERT TIGER] {} (effective: {}) {} = {} ms (threshold {} ms)",
        columnName, effectiveColumn, metricName,
        String.format(Locale.ROOT, "%.1f", observed), maxMs);

    if (observed > maxMs) {
      throw new AssertionError(String.format(Locale.ROOT,
          "Tiger column '%s' (effective '%s') %s %.1f ms > %d ms in %s",
          columnName, effectiveColumn, metricName, observed, maxMs, csvPath));
    }
  }

  /**
   * Computes the numeric value of a metric over the given samples.
   *
   * @param values samples in ms
   * @param metric metric to compute
   * @return computed value
   */
  private double calculateMetricValue(double[] values, Metric metric) {
    return switch (metric.type()) {
      case PERCENTILE -> CsvUtils.percentile(values, metric.percentile());
      case MAX -> Arrays.stream(values).max().orElse(Double.NaN);
      case MIN -> Arrays.stream(values).min().orElse(Double.NaN);
      case AVG -> Arrays.stream(values).average().orElse(Double.NaN);
      default -> throw new IllegalArgumentException("Unsupported metric type: " + metric.type());
    };
  }

  /**
   * Returns a short display name for the metric.
   *
   * @param metric metric descriptor
   * @return name like p95|max|min|avg
   */
  private String formatMetricName(Metric metric) {
    return switch (metric.type()) {
      case PERCENTILE -> "p" + Math.round(metric.percentile() * 100);
      case MAX -> "max";
      case MIN -> "min";
      case AVG -> "avg";
      default -> metric.type().toString().toLowerCase();
    };
  }

  /**
   * Logs mean, p50, p95 and p99 for a metric column.
   *
   * @param metricName column name
   * @param values     samples in ms
   */
  private void printMetricStats(String metricName, double[] values) {
    if (values.length == 0) {
      log.info("{}: no data", metricName);
      return;
    }

    double avg = Arrays.stream(values).average().orElse(Double.NaN);
    double p50 = CsvUtils.percentile(values, 0.50);
    double p95 = CsvUtils.percentile(values, 0.95);
    double p99 = CsvUtils.percentile(values, 0.99);

    log.info(String.format(Locale.ROOT,
        "%-24s n=%d  mean=%.1f  p50=%.1f  p95=%.1f  p99=%.1f ms",
        metricName, values.length, avg, p50, p95, p99));
  }
}
