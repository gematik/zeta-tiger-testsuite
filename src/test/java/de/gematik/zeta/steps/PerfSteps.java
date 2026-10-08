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

package de.gematik.zeta.steps;

import static de.gematik.zeta.services.PrometheusQuerySupport.histogramCountQuery;
import static de.gematik.zeta.services.PrometheusQuerySupport.histogramLatencyQuery;
import static de.gematik.zeta.services.PrometheusQuerySupport.parsePositiveInteger;
import static de.gematik.zeta.services.PrometheusQuerySupport.parseSpanNames;
import static de.gematik.zeta.services.PrometheusQuerySupport.singleLabelMatcher;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.Metric;
import de.gematik.zeta.perf.PrometheusLatencyLimitTable;
import de.gematik.zeta.services.PrometheusQueryService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for Prometheus-based performance assertions.
 */
@Slf4j
public class PerfSteps {

  private final PrometheusQueryService prometheus;

  /**
   * Creates the step class with a preconfigured Prometheus HTTP client.
   */
  public PerfSteps() {
    this.prometheus = new PrometheusQueryService(Duration.ofSeconds(20));
  }

  /**
   * Asserts all populated latency limits in a table, requiring samples for every metric.
   *
   * @param windowSeconds the histogram lookback window in seconds
   * @param limits rows with {@code service}, {@code span}, and latency columns {@code avg},
   *     {@code p90}, {@code p95}, and {@code p99}; blank latency cells are ignored
   */
  @Dann(
      "stelle sicher, dass in Prometheus im Fenster {tigerResolvedString} Sekunden "
          + "folgende Latenzgrenzen in ms eingehalten werden")
  @Then(
      "ensure that in Prometheus within the {tigerResolvedString} second window "
          + "the following latency limits in ms are met")
  public void assertPrometheusLatencyLimits(String windowSeconds, DataTable limits) {
    var rangeSelector = secondsRange(windowSeconds);
    PrometheusLatencyLimitTable.parse(limits).forEach(
        limit -> assertPrometheusHistogramMetricLeInternal(
        limit.serviceName(), limit.spanName(), rangeSelector, limit.metric(), limit.maxMs()));
  }

  /**
   * Asserts all populated labeled latency limits in a table, requiring matching samples.
   *
   * @param labelName the additional Prometheus label name
   * @param labelValue the additional Prometheus label value
   * @param windowSeconds the histogram lookback window in seconds
   * @param limits rows with {@code service}, {@code span}, and latency columns {@code avg},
   *     {@code p90}, {@code p95}, and {@code p99}; blank latency cells are ignored
   */
  @Dann(
      "stelle sicher, dass in Prometheus für Label {string}={string}, Fenster "
          + "{tigerResolvedString} Sekunden Samples für folgende Latenzgrenzen in ms vorhanden sind")
  @Then(
      "ensure that in Prometheus for label {string}={string}, window {tigerResolvedString} "
          + "seconds samples exist for the following latency limits in ms")
  public void assertPrometheusLabeledLatencyLimits(
      String labelName,
      String labelValue,
      String windowSeconds,
      DataTable limits) {
    var rangeSelector = secondsRange(windowSeconds);
    var extraMatcher = singleLabelMatcher(labelName, labelValue);
    PrometheusLatencyLimitTable.parse(limits).forEach(
        limit -> assertPrometheusLabeledHistogramMetricLeInternal(
        limit.serviceName(),
        limit.spanName(),
        rangeSelector,
        extraMatcher,
        limit.metric(),
        limit.maxMs(),
        true));
  }

  /**
   * Asserts all populated labeled latency limits in a table when matching samples exist.
   *
   * @param labelName the additional Prometheus label name
   * @param labelValue the additional Prometheus label value
   * @param windowSeconds the histogram lookback window in seconds
   * @param limits rows with {@code service}, {@code span}, and latency columns {@code avg},
   *     {@code p90}, {@code p95}, and {@code p99}; blank latency cells are ignored
   */
  @Dann(
      "falls in Prometheus für Label {string}={string}, Fenster {tigerResolvedString} Sekunden "
          + "Samples vorhanden sind, gelten folgende Latenzgrenzen in ms")
  @Then(
      "if in Prometheus for label {string}={string}, window {tigerResolvedString} seconds "
          + "samples exist, the following latency limits in ms apply")
  public void assertPrometheusOptionalLabeledLatencyLimits(
      String labelName,
      String labelValue,
      String windowSeconds,
      DataTable limits) {
    var rangeSelector = secondsRange(windowSeconds);
    var extraMatcher = singleLabelMatcher(labelName, labelValue);
    PrometheusLatencyLimitTable.parse(limits).forEach(
        limit -> assertPrometheusLabeledHistogramMetricLeInternal(
        limit.serviceName(),
        limit.spanName(),
        rangeSelector,
        extraMatcher,
        limit.metric(),
        limit.maxMs(),
        false));
  }

  /**
   * Asserts the combined Prometheus error rate across multiple spans over a second-based lookback
   * window ending at the current evaluation time.
   *
   * @param serviceName the Prometheus service label value
   * @param spanNames comma-separated Prometheus span label values
   * @param windowSeconds the histogram lookback window in seconds
   * @param maxErrorRatePercentStr the maximum allowed combined error rate in percent
   */
  @Dann(
      "stelle sicher, dass in Prometheus für Service {string}, Spans {string}, "
          + "Fenster {tigerResolvedString} Sekunden die kombinierte Fehlerrate <= "
          + "{tigerResolvedString} Prozent ist")
  @Then(
      "ensure that in Prometheus for service {string}, spans {string}, "
          + "window {tigerResolvedString} seconds the combined error rate is <= "
          + "{tigerResolvedString} percent")
  public void assertPrometheusCombinedErrorRateLeSeconds(
      String serviceName,
      String spanNames,
      String windowSeconds,
      String maxErrorRatePercentStr) {
    try {
      double maxErrorRatePercent = Double.parseDouble(
          TigerGlobalConfiguration.resolvePlaceholders(maxErrorRatePercentStr).trim().replace(',', '.'));
      int resolvedWindowSeconds = parsePositiveInteger(windowSeconds, "windowSeconds");
      String rangeSelector = resolvedWindowSeconds + "s";
      assertPrometheusCombinedErrorRateLeInternal(
          serviceName,
          spanNames,
          rangeSelector,
          maxErrorRatePercent);
    } catch (RuntimeException e) {
      var ex = new AssertionError("Prometheus combined error rate assertion setup failed: " + e.getMessage(), e);
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
    }
  }

  /**
   * Asserts the combined Prometheus request rate across multiple spans over a second-based lookback
   * window ending at the current evaluation time while dividing by the explicit test duration.
   *
   * @param serviceName the Prometheus service label value
   * @param spanNames comma-separated Prometheus span label values
   * @param windowSeconds the histogram lookback window in seconds
   * @param divisorSeconds the divisor used to normalize the count to requests per second
   * @param minRatePerSecond the minimum expected rate in requests per second
   */
  @Dann(
      "stelle sicher, dass in Prometheus für Service {string}, "
          + "Spans {string}, Fenster {tigerResolvedString} Sekunden und "
          + "Divisor {tigerResolvedString} Sekunden die kombinierte Rate >= {tigerResolvedString} pro Sekunde ist")
  @Then(
      "ensure that in Prometheus for service {string}, spans {string}, "
          + "window {tigerResolvedString} seconds and divisor {tigerResolvedString} "
          + "seconds the combined rate is >= {tigerResolvedString} per second")
  public void assertPrometheusCombinedRateGeSecondsWithDivisor(
      String serviceName,
      String spanNames,
      String windowSeconds,
      String divisorSeconds,
      String minRatePerSecond) {
    try {
      int resolvedWindowSeconds = parsePositiveInteger(windowSeconds, "windowSeconds");
      int resolvedDivisorSeconds = parsePositiveInteger(divisorSeconds, "divisorSeconds");
      int resolvedMinRatePerSecond = parsePositiveInteger(minRatePerSecond, "minRatePerSecond");
      String rangeSelector = resolvedWindowSeconds + "s";
      assertPrometheusCombinedRateGeInternal(
          serviceName,
          spanNames,
          rangeSelector,
          resolvedDivisorSeconds,
          resolvedMinRatePerSecond);
    } catch (RuntimeException e) {
      var ex = new AssertionError("Prometheus combined rate assertion setup failed: " + e.getMessage(), e);
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
    }
  }

  /**
   * Asserts the combined Prometheus request rate for several spans of one service.
   *
   * @param serviceName the Prometheus service label value
   * @param spanNames comma-separated Prometheus span label values
   * @param rangeSelector the Prometheus lookback range selector
   * @param divisorSeconds divisor used to normalize the count to requests per second
   * @param minRatePerSecond the minimum expected rate in requests per second
   */
  private void assertPrometheusCombinedRateGeInternal(
      String serviceName,
      String spanNames,
      String rangeSelector,
      int divisorSeconds,
      Integer minRatePerSecond) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);

    List<String> spans = parseSpanNames(spanNames);

    String sumParts = spans.stream()
        .map(span -> histogramCountQuery(resolvedServiceName, span, rangeSelector))
        .collect(Collectors.joining(" + "));
    String ratePromQl = "(" + sumParts + ") / " + divisorSeconds;

    try {
      Map<String, Double> spanCounts = new HashMap<>();
      for (String span : spans) {
        String countPromQl = histogramCountQuery(resolvedServiceName, span, rangeSelector);
        spanCounts.put(span, prometheus.queryScalar(countPromQl));
      }
      double combinedCount = spanCounts.values().stream()
          .filter(v -> v != null && Double.isFinite(v))
          .mapToDouble(Double::doubleValue)
          .sum();
      double ratePerSecond = combinedCount / divisorSeconds;
      String rateText = String.format(Locale.ROOT, "%.2f", ratePerSecond);
      String samplesText = spans.stream()
          .map(span -> {
            Double count = spanCounts.get(span);
            String countText = count == null ? "null" : String.format(Locale.ROOT, "%.2f", count);
            return span + "=" + countText;
          })
          .collect(Collectors.joining(", "));
      String reportText = String.format(Locale.ROOT,
          "service=%s, spans=%s, samples={%s}, window=%s, divisor=%ds, rate=%s/s, threshold=%d/s%nquery=%s",
          resolvedServiceName, spans, samplesText, rangeSelector, divisorSeconds, rateText, minRatePerSecond, ratePromQl);

      log.warn("[ASSERT PROMETHEUS COMBINED RATE] {}", reportText);
      ReportAttachments.addText("Prometheus combined rate", reportText);

      if (combinedCount <= 0) {
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus has no samples for service=%s spans=%s window=%s",
            resolvedServiceName, spans, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      } else if (ratePerSecond < minRatePerSecond) {
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus combined rate %.2f/s < %d/s for service=%s spans=%s window=%s",
            ratePerSecond, minRatePerSecond, resolvedServiceName, spans, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      } else {
        log.info("[ASSERT PROMETHEUS COMBINED RATE] PASS rate={}/s >= {}/s for service={} spans={}",
            rateText, minRatePerSecond, resolvedServiceName, spans);
        ReportAttachments.addText("Prometheus combined rate check",
            String.format(Locale.ROOT, "PASS rate=%s/s >= %d/s", rateText, minRatePerSecond));
      }
    } catch (Exception e) {
      SoftAssertionsContext.recordSoftFailure("Prometheus combined rate query failed: " + e.getMessage(), new AssertionError("Prometheus combined rate query failed: " + e.getMessage(), e));
    }
  }

  /**
   * Asserts the combined Prometheus error rate for several spans of one service.
   *
   * @param serviceName the Prometheus service label value
   * @param spanNames comma-separated Prometheus span label values
   * @param rangeSelector the Prometheus lookback range selector
   * @param maxErrorRatePercent the maximum allowed error rate in percent
   */
  private void assertPrometheusCombinedErrorRateLeInternal(
      String serviceName,
      String spanNames,
      String rangeSelector,
      double maxErrorRatePercent) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    List<String> spans = parseSpanNames(spanNames);

    try {
      Map<String, Double> errorCounts = queryPrometheusSpanCounts(
          resolvedServiceName,
          spans,
          rangeSelector,
          "zeta_test_status_code=\"STATUS_CODE_ERROR\"");
      Map<String, Double> labeledCounts = queryPrometheusSpanCounts(
          resolvedServiceName,
          spans,
          rangeSelector,
          "zeta_test_status_code=~\".+\"");
      Map<String, Double> totalCounts = queryPrometheusSpanCounts(
          resolvedServiceName,
          spans,
          rangeSelector,
          null);

      double errorCount = sumFiniteCounts(errorCounts);
      double labeledCount = sumFiniteCounts(labeledCounts);
      double totalCount = sumFiniteCounts(totalCounts);

      String totalCountText = String.format(Locale.ROOT, "%.1f", totalCount);
      String samplesText = spans.stream()
          .map(span -> String.format(
              Locale.ROOT,
              "%s={error=%s,labeled=%s,total=%s}",
              span,
              formatPrometheusCount(errorCounts.get(span), true),
              formatPrometheusCount(labeledCounts.get(span), true),
              formatPrometheusCount(totalCounts.get(span), false)))
          .collect(Collectors.joining(", "));

      if (totalCount <= 0) {
        String reportText = String.format(Locale.ROOT,
            "service=%s, spans=%s, samples={%s}, window=%s, totalCount=%s - no samples",
            resolvedServiceName, spans, samplesText, rangeSelector, totalCountText);
        log.warn("[ASSERT PROMETHEUS COMBINED ERROR RATE] {}", reportText);
        ReportAttachments.addText("Prometheus combined error rate", reportText);
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus combined error rate check has no samples for service=%s spans=%s window=%s - "
                + "verify span names and telemetry pipeline",
            resolvedServiceName, spans, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      if (labeledCount <= 0) {
        boolean noErrors = errorCount <= 0;
        String reportText = String.format(Locale.ROOT,
            "service=%s, spans=%s, samples={%s}, window=%s, totalCount=%s - %s",
            resolvedServiceName, spans, samplesText, rangeSelector, totalCountText,
            noErrors
                ? "all spans successful (zeta_test_status_code not set = no HTTP 4xx/5xx), errorRate=0.00%% -> PASS"
                : "errorCount=" + String.format(Locale.ROOT, "%.1f", errorCount)
                    + " but zeta_test_status_code not set on any span -> FAIL");
        log.warn("[ASSERT PROMETHEUS COMBINED ERROR RATE] {}", reportText);
        ReportAttachments.addText("Prometheus combined error rate", reportText);
        if (!noErrors) {
          var ex = new AssertionError(String.format(Locale.ROOT,
              "Prometheus combined error rate check has error spans for service=%s spans=%s window=%s "
                  + "but no labeled total - cannot compute rate, treating as failure",
              resolvedServiceName, spans, rangeSelector));
          SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        }
        return;
      }

      double errorRatePercent = errorCount > 0
          ? errorCount / totalCount * 100.0
          : 0.0;
      String errorCountText = errorCount <= 0
          ? "0 (no error spans)"
          : String.format(Locale.ROOT, "%.1f", errorCount);
      String rateText = String.format(Locale.ROOT, "%.2f", errorRatePercent);
      String reportText = String.format(Locale.ROOT,
          "service=%s, spans=%s, samples={%s}, window=%s, errorCount=%s, totalCount=%s, "
              + "errorRate=%s%%, threshold=%.1f%%",
          resolvedServiceName, spans, samplesText, rangeSelector, errorCountText, totalCountText,
          rateText, maxErrorRatePercent);
      log.warn("[ASSERT PROMETHEUS COMBINED ERROR RATE] {}", reportText);
      ReportAttachments.addText("Prometheus combined error rate", reportText);

      if (errorRatePercent > maxErrorRatePercent) {
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus combined error rate %.2f%% > %.1f%% for service=%s spans=%s window=%s",
            errorRatePercent, maxErrorRatePercent, resolvedServiceName, spans, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      } else {
        log.info("[ASSERT PROMETHEUS COMBINED ERROR RATE] PASS errorRate={}% <= {}% for service={} spans={}",
            rateText, maxErrorRatePercent, resolvedServiceName, spans);
        ReportAttachments.addText("Prometheus combined error rate check",
            String.format(Locale.ROOT, "PASS errorRate=%s%% <= %.1f%%", rateText, maxErrorRatePercent));
      }
    } catch (Exception e) {
      SoftAssertionsContext.recordSoftFailure(
          "Prometheus combined error rate query failed: " + e.getMessage(),
          new AssertionError("Prometheus combined error rate query failed: " + e.getMessage(), e));
    }
  }

  /**
   * Asserts the Prometheus request rate for a service/span combination over a second-based lookback
   * window ending at the current evaluation time while dividing by the explicit test duration.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @param windowSeconds the histogram lookback window in seconds
   * @param divisorSeconds the divisor used to normalize the count to requests per second
   * @param minRatePerSecond the minimum expected rate in requests per second
   */
  @Dann(
      "stelle sicher, dass in Prometheus für Service {string}, Span {string}, "
          + "Fenster {tigerResolvedString} Sekunden und Divisor {tigerResolvedString} Sekunden "
          + "die Rate >= {tigerResolvedString} pro Sekunde ist")
  @Then(
      "ensure that in Prometheus for service {string}, span {string}, "
          + "window {tigerResolvedString} seconds and divisor {tigerResolvedString} seconds "
          + "the rate is >= {tigerResolvedString} per second")
  public void assertPrometheusRateGeSecondsWithDivisor(
      String serviceName,
      String spanName,
      String windowSeconds,
      String divisorSeconds,
      String minRatePerSecond) {
    try {
      int resolvedWindowSeconds = parsePositiveInteger(windowSeconds, "windowSeconds");
      int resolvedDivisorSeconds = parsePositiveInteger(divisorSeconds, "divisorSeconds");
      int resolvedMinRatePerSecond = parsePositiveInteger(minRatePerSecond, "minRatePerSecond");
      String rangeSelector = resolvedWindowSeconds + "s";
      assertPrometheusRateGeInternal(
          serviceName,
          spanName,
          rangeSelector,
          resolvedDivisorSeconds,
          resolvedMinRatePerSecond);
    } catch (RuntimeException e) {
      var ex = new AssertionError("Prometheus rate assertion setup failed: " + e.getMessage(), e);
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
    }
  }

  /**
   * Asserts the Prometheus request rate for one service/span combination.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @param rangeSelector the Prometheus lookback range selector
   * @param divisorSeconds divisor used to normalize the count to requests per second
   * @param minRatePerSecond the minimum expected rate in requests per second
   */
  private void assertPrometheusRateGeInternal(
      String serviceName,
      String spanName,
      String rangeSelector,
      int divisorSeconds,
      Integer minRatePerSecond) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    String resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);

    String countPromQl = histogramCountQuery(resolvedServiceName, resolvedSpanName, rangeSelector);
    String ratePromQl = countPromQl + " / " + divisorSeconds;

    try {
      Double totalCount = prometheus.queryScalar(countPromQl);

      String countText = totalCount == null ? "null" : String.format(Locale.ROOT, "%.1f", totalCount);

      if (totalCount == null || !Double.isFinite(totalCount) || totalCount <= 0) {
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus has no samples for service=%s span=%s window=%s (count=%s)",
            resolvedServiceName, resolvedSpanName, rangeSelector, countText));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      double ratePerSecond = totalCount / divisorSeconds;
      String rateText = String.format(Locale.ROOT, "%.2f", ratePerSecond);
      String reportText = String.format(Locale.ROOT,
          "service=%s, span=%s, window=%s, totalCount=%s, rate=%s/s, threshold=%d/s%nquery=%s",
          resolvedServiceName, resolvedSpanName, rangeSelector, countText, rateText, minRatePerSecond, ratePromQl);

      log.warn("[ASSERT PROMETHEUS RATE] {}", reportText);
      ReportAttachments.addText("Prometheus rate", reportText);

      if (ratePerSecond < minRatePerSecond) {
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus rate %.2f/s < %d/s for service=%s span=%s window=%s",
            ratePerSecond, minRatePerSecond, resolvedServiceName, resolvedSpanName, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      } else {
        log.info("[ASSERT PROMETHEUS RATE] PASS rate={}/s >= {}/s for service={} span={}",
            rateText, minRatePerSecond, resolvedServiceName, resolvedSpanName);
        ReportAttachments.addText("Prometheus rate check",
            String.format(Locale.ROOT, "PASS rate=%s/s >= %d/s", rateText, minRatePerSecond));
      }
    } catch (Exception e) {
      var ex = new AssertionError("Prometheus rate query failed: " + e.getMessage(), e);
      SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
    }
  }

  /**
   * Asserts one Prometheus histogram-derived latency metric for a service/span combination.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @param rangeSelector the Prometheus lookback range selector
   * @param metric the metric to assert
   * @param maxMs the maximum allowed latency in milliseconds
   */
  private void assertPrometheusHistogramMetricLeInternal(
      String serviceName,
      String spanName,
      String rangeSelector,
      Metric metric,
      Double maxMs) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    String resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);
    String promQl = histogramLatencyQuery(resolvedServiceName, resolvedSpanName, rangeSelector, metric);
    String countPromQl = histogramCountQuery(resolvedServiceName, resolvedSpanName, rangeSelector);
    String thresholdText = formatThresholdMs(maxMs);

    try {
      Double sampleCountValue = prometheus.queryScalar(countPromQl);
      String sampleCountText = sampleCountValue == null
          ? "null"
          : String.format(Locale.ROOT, "%.1f", sampleCountValue);
      String sampleCountReport = String.format(
          Locale.ROOT,
          "service=%s, span=%s, window=%s, samples=%s%nquery=%s",
          resolvedServiceName,
          resolvedSpanName,
          rangeSelector,
          sampleCountText,
          countPromQl);
      log.warn("[ASSERT PROMETHEUS COUNT] {}", sampleCountReport);
      ReportAttachments.addText("Prometheus sample count", sampleCountReport);

      if (sampleCountValue == null || !Double.isFinite(sampleCountValue) || sampleCountValue <= 0d) {
        var ex = new AssertionError(String.format(
            Locale.ROOT,
            "Prometheus histogram has no samples for service=%s span=%s window=%s (samples=%s, query=%s)",
            resolvedServiceName,
            resolvedSpanName,
            rangeSelector,
            sampleCountText,
            countPromQl));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      Double observedValue = prometheus.queryScalar(promQl);
      String metricName = formatMetricName(metric);
      String observedText = observedValue == null
          ? "null"
          : String.format(Locale.ROOT, "%.1f", observedValue);
      String reportText = String.format(
          Locale.ROOT,
          "service=%s, span=%s, window=%s, metric=%s, observed=%s ms, threshold=%s ms%nquery=%s",
          resolvedServiceName,
          resolvedSpanName,
          rangeSelector,
          metricName,
          observedText,
          thresholdText,
          promQl);
      log.warn("[ASSERT PROMETHEUS] {}", reportText);
      ReportAttachments.addText("Prometheus metric", reportText);

      if (observedValue == null) {
        var ex = new AssertionError(String.format(
            Locale.ROOT,
            "Prometheus metric %s returned no result for service=%s span=%s window=%s (query=%s)",
            metricName,
            resolvedServiceName,
            resolvedSpanName,
            rangeSelector,
            promQl));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      double observed = observedValue;
      if (Double.isNaN(observed) || Double.isInfinite(observed)) {
        var ex = new AssertionError(String.format(
            Locale.ROOT,
            "Prometheus metric %s is not finite for service=%s span=%s window=%s (observed=%s, query=%s)",
            metricName,
            resolvedServiceName,
            resolvedSpanName,
            rangeSelector,
            observedText,
            promQl));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      if (observed > maxMs) {
        var ex = new AssertionError(String.format(
            Locale.ROOT,
            "Prometheus metric %s %.1f ms > %s ms for service=%s span=%s window=%s (query=%s)",
            metricName,
            observed,
            thresholdText,
            resolvedServiceName,
            resolvedSpanName,
            rangeSelector,
            promQl));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      }
    } catch (Exception e) {
      SoftAssertionsContext.recordSoftFailure(
          "Prometheus query failed for service=" + resolvedServiceName
              + ", span=" + resolvedSpanName
              + ", window=" + rangeSelector,
          e);
    }
  }

  /**
   * Asserts one labeled Prometheus latency metric, optionally requiring matching samples.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @param rangeSelector the Prometheus lookback range selector
   * @param extraMatcher additional Prometheus label matcher
   * @param metric the metric to assert
   * @param maxMs the maximum allowed latency in milliseconds
   * @param samplesRequired whether missing samples must produce a soft failure
   */
  private void assertPrometheusLabeledHistogramMetricLeInternal(
      String serviceName,
      String spanName,
      String rangeSelector,
      String extraMatcher,
      Metric metric,
      Double maxMs,
      boolean samplesRequired) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    String resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);
    String countPromQl = histogramCountQuery(
        resolvedServiceName,
        resolvedSpanName,
        rangeSelector,
        extraMatcher);
    try {
      Double sampleCountValue = prometheus.queryScalar(countPromQl);
      assertPrometheusLabeledHistogramMetricLeInternal(
          serviceName,
          spanName,
          rangeSelector,
          extraMatcher,
          metric,
          maxMs,
          samplesRequired,
          sampleCountValue,
          countPromQl);
    } catch (Exception e) {
      SoftAssertionsContext.recordSoftFailure(
          "Prometheus labeled query failed for service=" + resolvedServiceName
              + ", span=" + resolvedSpanName
              + ", matcher=" + extraMatcher
              + ", window=" + rangeSelector,
          e);
    }
  }

  /**
   * Evaluates one labeled Prometheus latency metric using an already queried sample count.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @param rangeSelector the Prometheus lookback range selector
   * @param extraMatcher additional Prometheus label matcher
   * @param metric the metric to assert
   * @param maxMs the maximum allowed latency in milliseconds
   * @param samplesRequired whether missing samples must produce a soft failure
   * @param sampleCountValue already queried sample count
   * @param countPromQl PromQL query used for the sample count
   */
  private void assertPrometheusLabeledHistogramMetricLeInternal(
      String serviceName,
      String spanName,
      String rangeSelector,
      String extraMatcher,
      Metric metric,
      Double maxMs,
      boolean samplesRequired,
      Double sampleCountValue,
      String countPromQl) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    String resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);
    String promQl = histogramLatencyQuery(
        resolvedServiceName,
        resolvedSpanName,
        rangeSelector,
        metric,
        extraMatcher);
    String thresholdText = formatThresholdMs(maxMs);

    try {
      String sampleCountText = sampleCountValue == null
          ? "null"
          : String.format(Locale.ROOT, "%.1f", sampleCountValue);
      String sampleCountReport = String.format(
          Locale.ROOT,
          "service=%s, span=%s, matcher=%s, window=%s, samples=%s%nquery=%s",
          resolvedServiceName,
          resolvedSpanName,
          extraMatcher,
          rangeSelector,
          sampleCountText,
          countPromQl);
      log.warn("[ASSERT PROMETHEUS OPTIONAL COUNT] {}", sampleCountReport);
      ReportAttachments.addText("Prometheus optional sample count", sampleCountReport);

      if (sampleCountValue == null || !Double.isFinite(sampleCountValue) || sampleCountValue <= 0d) {
        if (samplesRequired) {
          var ex = new AssertionError(String.format(
              Locale.ROOT,
              "Expected Prometheus samples for service=%s span=%s matcher=%s window=%s "
                  + "but found: %s query=%s",
              resolvedServiceName,
              resolvedSpanName,
              extraMatcher,
              rangeSelector,
              sampleCountText,
              countPromQl));
          SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
          return;
        }
        String skipText = String.format(
            Locale.ROOT,
            "SKIP optional Prometheus assertion: no samples for service=%s span=%s matcher=%s window=%s",
            resolvedServiceName,
            resolvedSpanName,
            extraMatcher,
            rangeSelector);
        log.warn("[ASSERT PROMETHEUS OPTIONAL] {}", skipText);
        ReportAttachments.addText("Prometheus optional metric skipped", skipText);
        return;
      }

      Double observedValue = prometheus.queryScalar(promQl);
      String metricName = formatMetricName(metric);
      String observedText = observedValue == null
          ? "null"
          : String.format(Locale.ROOT, "%.1f", observedValue);
      String reportText = String.format(
          Locale.ROOT,
          "service=%s, span=%s, matcher=%s, window=%s, metric=%s, observed=%s ms, threshold=%s ms%nquery=%s",
          resolvedServiceName,
          resolvedSpanName,
          extraMatcher,
          rangeSelector,
          metricName,
          observedText,
          thresholdText,
          promQl);
      log.warn("[ASSERT PROMETHEUS OPTIONAL] {}", reportText);
      ReportAttachments.addText("Prometheus optional metric", reportText);

      if (observedValue == null) {
        var ex = new AssertionError(String.format(
            Locale.ROOT,
            "Prometheus metric %s returned no result for service=%s span=%s matcher=%s window=%s (query=%s)",
            metricName,
            resolvedServiceName,
            resolvedSpanName,
            extraMatcher,
            rangeSelector,
            promQl));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      double observed = observedValue;
      if (Double.isNaN(observed) || Double.isInfinite(observed)) {
        var ex = new AssertionError(String.format(
            Locale.ROOT,
            "Prometheus metric %s is not finite for service=%s span=%s matcher=%s window=%s (observed=%s, query=%s)",
            metricName,
            resolvedServiceName,
            resolvedSpanName,
            extraMatcher,
            rangeSelector,
            observedText,
            promQl));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      if (observed > maxMs) {
        var ex = new AssertionError(String.format(
            Locale.ROOT,
            "Prometheus metric %s %.1f ms > %s ms for service=%s span=%s matcher=%s window=%s (query=%s)",
            metricName,
            observed,
            thresholdText,
            resolvedServiceName,
            resolvedSpanName,
            extraMatcher,
            rangeSelector,
            promQl));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      }
    } catch (Exception e) {
      SoftAssertionsContext.recordSoftFailure(
          "Prometheus optional query failed for service=" + resolvedServiceName
              + ", span=" + resolvedSpanName
              + ", matcher=" + extraMatcher
              + ", window=" + rangeSelector,
          e);
    }
  }

  /**
   * Converts a positive second count to a Prometheus range selector.
   *
   * @param windowSeconds resolved or placeholder-backed second count
   * @return Prometheus range selector ending in {@code s}
   * @throws IllegalArgumentException if the value is not a positive integer
   */
  private static String secondsRange(String windowSeconds) {
    return parsePositiveInteger(windowSeconds, "windowSeconds") + "s";
  }

  /**
   * Queries Prometheus counts for each span independently.
   *
   * <p>PromQL vector addition can drop the whole combined expression when one part has no series.
   * Querying spans independently lets absent error-series count as zero without hiding errors from
   * other spans.</p>
   *
   * @param serviceName the resolved Prometheus service label value
   * @param spanNames the resolved Prometheus span label values
   * @param rangeSelector the Prometheus lookback range selector
   * @param extraMatcher optional additional Prometheus label matcher
   * @return count values keyed by span name; missing Prometheus series are represented as
   *     {@code null}
   * @throws Exception when one Prometheus query fails
   */
  private Map<String, Double> queryPrometheusSpanCounts(
      String serviceName,
      List<String> spanNames,
      String rangeSelector,
      String extraMatcher) throws Exception {
    Map<String, Double> counts = new HashMap<>();
    for (String span : spanNames) {
      String promQl = histogramCountQuery(
          serviceName,
          span,
          rangeSelector,
          extraMatcher);
      counts.put(span, prometheus.queryScalar(promQl));
    }
    return counts;
  }

  /**
   * Sums finite Prometheus count values, treating absent series as zero.
   *
   * @param counts count values keyed by span name
   * @return finite sum of all count values
   */
  private double sumFiniteCounts(Map<String, Double> counts) {
    return counts.values().stream()
        .filter(value -> value != null && Double.isFinite(value))
        .mapToDouble(Double::doubleValue)
        .sum();
  }

  /**
   * Formats a Prometheus count for diagnostic output.
   *
   * @param count count value, or {@code null} when Prometheus returned no series
   * @param nullAsZero whether absent series should be displayed as zero
   * @return formatted count
   */
  private String formatPrometheusCount(Double count, boolean nullAsZero) {
    if (count == null || !Double.isFinite(count)) {
      return nullAsZero ? "0.0" : "null";
    }
    return String.format(Locale.ROOT, "%.1f", count);
  }

  /**
   * Formats latency thresholds for log and assertion messages without forcing integer output.
   *
   * @param thresholdMs the threshold in milliseconds
   * @return the formatted threshold text
   */
  private String formatThresholdMs(Double thresholdMs) {
    if (thresholdMs == null) {
      return "null";
    }
    if (thresholdMs % 1d == 0d) {
      return String.format(Locale.ROOT, "%.0f", thresholdMs);
    }
    return String.format(Locale.ROOT, "%.3f", thresholdMs).replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  /**
   * Formats a metric identifier for assertion messages.
   *
   * @param metric the metric to format
   * @return a short metric name
   */
  private String formatMetricName(Metric metric) {
    return switch (metric.type()) {
      case PERCENTILE -> "p" + Math.round(metric.percentile() * 100);
      case AVG -> "avg";
    };
  }
}
