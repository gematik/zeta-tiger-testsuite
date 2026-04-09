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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.Metric;
import de.gematik.zeta.services.SslConfigurationService;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for Prometheus-based performance assertions.
 */
@Slf4j
public class PerfSteps {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration PROMETHEUS_REQUEST_TIMEOUT = Duration.ofSeconds(20);

  private final HttpClient httpClient;

  /**
   * Creates the step class with a preconfigured Prometheus HTTP client.
   */
  public PerfSteps() {
    this.httpClient = buildPrometheusHttpClient();
  }

  /**
   * Builds the HTTP client used for Prometheus queries.
   *
   * <p>Tests routinely talk to ingresses with self-signed or privately issued certificates, so the
   * client intentionally uses the shared trust-all SSL context from the test utilities.</p>
   *
   * @return the configured HTTP client
   */
  private HttpClient buildPrometheusHttpClient() {
    try {
      return HttpClient.newBuilder()
          .connectTimeout(PROMETHEUS_REQUEST_TIMEOUT)
          .sslContext(SslConfigurationService.getTrustAllSslContext())
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create Prometheus HTTP client", e);
    }
  }

  /**
   * Asserts an aggregated Prometheus histogram metric for a specific service/span combination using
   * a second-based range selector.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @param windowSeconds the histogram lookback window in seconds
   * @param metric the metric to assert
   * @param maxMs the maximum allowed latency in milliseconds
   */
  @Dann("stelle sicher, dass in Prometheus für Service {string}, Span {string}, Fenster {tigerResolvedString} Sekunden der {metric}-Wert <= {double} ms ist")
  @Then("ensure that in Prometheus for service {string}, span {string}, window {tigerResolvedString} seconds the {metric} value is <= {double} ms")
  public void assertPrometheusHistogramMetricLeSeconds(
      String serviceName,
      String spanName,
      String windowSeconds,
      Metric metric,
      Double maxMs) {
    int resolvedWindowSeconds = parsePositiveInteger(windowSeconds, "windowSeconds");
    String rangeSelector = resolvedWindowSeconds + "s";
    assertPrometheusHistogramMetricLeInternal(serviceName, spanName, rangeSelector, metric, maxMs);
  }

  /**
   * Asserts the Prometheus error rate for a service/span combination over a second-based lookback
   * window ending at the current evaluation time.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @param windowSeconds the histogram lookback window in seconds
   * @param maxErrorRatePercentStr the maximum allowed error rate in percent
   */
  @Dann("stelle sicher, dass in Prometheus für Service {string}, Span {string}, Fenster {tigerResolvedString} Sekunden die Fehlerrate <= {tigerResolvedString} Prozent ist")
  @Then("ensure that in Prometheus for service {string}, span {string}, window {tigerResolvedString} seconds the error rate is <= {tigerResolvedString} percent")
  public void assertPrometheusErrorRateLeSeconds(
      String serviceName,
      String spanName,
      String windowSeconds,
      String maxErrorRatePercentStr) {
    double maxErrorRatePercent = Double.parseDouble(
        TigerGlobalConfiguration.resolvePlaceholders(maxErrorRatePercentStr).trim().replace(',', '.'));
    int resolvedWindowSeconds = parsePositiveInteger(windowSeconds, "windowSeconds");
    String rangeSelector = resolvedWindowSeconds + "s";
    assertPrometheusErrorRateLeInternal(serviceName, spanName, rangeSelector, maxErrorRatePercent);
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
          + "Divisor {tigerResolvedString} Sekunden die kombinierte Rate >= {int} pro Sekunde ist")
  @Then(
      "ensure that in Prometheus for service {string}, spans {string}, "
          + "window {tigerResolvedString} seconds and divisor {tigerResolvedString} "
          + "seconds the combined rate is >= {int} per second")
  public void assertPrometheusCombinedRateGeSecondsWithDivisor(
      String serviceName,
      String spanNames,
      String windowSeconds,
      String divisorSeconds,
      Integer minRatePerSecond) {
    int resolvedWindowSeconds = parsePositiveInteger(windowSeconds, "windowSeconds");
    int resolvedDivisorSeconds = parsePositiveInteger(divisorSeconds, "divisorSeconds");
    String rangeSelector = resolvedWindowSeconds + "s";
    assertPrometheusCombinedRateGeInternal(serviceName, spanNames, rangeSelector, resolvedDivisorSeconds, minRatePerSecond);
  }

  private void assertPrometheusCombinedRateGeInternal(
      String serviceName,
      String spanNames,
      String rangeSelector,
      int divisorSeconds,
      Integer minRatePerSecond) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);

    List<String> spans = Arrays.stream(spanNames.split(","))
        .map(String::trim)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());

    // Aggregate each span to a scalar first so spans with different labels can be added safely.
    String sumParts = spans.stream()
        .map(span -> "sum(increase(traces_span_metrics_duration_milliseconds_count{"
            + "service_name=\"" + resolvedServiceName + "\", "
            + "span_name=\"" + span + "\"}["
            + rangeSelector + "]))")
        .collect(Collectors.joining(" + "));
    String ratePromQl = "(" + sumParts + ") / " + divisorSeconds;

    try {
      // Compute per-span counts locally from the combined query result to avoid N+1 queries.
      // Individual span counts are queried only for the log report.
      Map<String, Double> spanCounts = new HashMap<>();
      for (String span : spans) {
        String countPromQl = "sum(increase(traces_span_metrics_duration_milliseconds_count{"
            + "service_name=\"" + resolvedServiceName + "\", "
            + "span_name=\"" + span + "\"}["
            + rangeSelector + "]))";
        spanCounts.put(span, queryPrometheusScalar(countPromQl));
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
      SerenityReportUtils.addCustomData("Prometheus combined rate", reportText);

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
        SerenityReportUtils.addCustomData("Prometheus combined rate check",
            String.format(Locale.ROOT, "PASS rate=%s/s >= %d/s", rateText, minRatePerSecond));
      }
    } catch (Exception e) {
      throw new AssertionError("Prometheus combined rate query failed: " + e.getMessage(), e);
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
  @Dann("stelle sicher, dass in Prometheus für Service {string}, Span {string}, Fenster {tigerResolvedString} Sekunden und Divisor {tigerResolvedString} Sekunden die Rate >= {int} pro Sekunde ist")
  @Then("ensure that in Prometheus for service {string}, span {string}, window {tigerResolvedString} seconds and divisor {tigerResolvedString} seconds the rate is >= {int} per second")
  public void assertPrometheusRateGeSecondsWithDivisor(
      String serviceName,
      String spanName,
      String windowSeconds,
      String divisorSeconds,
      Integer minRatePerSecond) {
    int resolvedWindowSeconds = parsePositiveInteger(windowSeconds, "windowSeconds");
    int resolvedDivisorSeconds = parsePositiveInteger(divisorSeconds, "divisorSeconds");
    String rangeSelector = resolvedWindowSeconds + "s";
    assertPrometheusRateGeInternal(serviceName, spanName, rangeSelector, resolvedDivisorSeconds, minRatePerSecond);
  }

  private void assertPrometheusErrorRateLeInternal(
      String serviceName,
      String spanName,
      String rangeSelector,
      double maxErrorRatePercent) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    String resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);

    String errorCountPromQl = "sum(increase(traces_span_metrics_duration_milliseconds_count{"
        + "service_name=\"" + resolvedServiceName + "\", "
        + "span_name=\"" + resolvedSpanName + "\", "
        + "status_code=\"STATUS_CODE_ERROR\"}["
        + rangeSelector + "]))";
    String totalCountPromQl = "sum(increase(traces_span_metrics_duration_milliseconds_count{"
        + "service_name=\"" + resolvedServiceName + "\", "
        + "span_name=\"" + resolvedSpanName + "\"}["
        + rangeSelector + "]))";

    try {
      Double errorCount = queryPrometheusScalar(errorCountPromQl);
      Double totalCount = queryPrometheusScalar(totalCountPromQl);

      String totalCountText = totalCount == null ? "null" : String.format(Locale.ROOT, "%.1f", totalCount);

      if (totalCount == null || !Double.isFinite(totalCount) || totalCount <= 0) {
        String reportText = String.format(Locale.ROOT,
            "service=%s, span=%s, window=%s, totalCount=%s — no samples",
            resolvedServiceName, resolvedSpanName, rangeSelector, totalCountText);
        log.warn("[ASSERT PROMETHEUS ERROR RATE] {}", reportText);
        SerenityReportUtils.addCustomData("Prometheus error rate", reportText);
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus error rate check has no samples for service=%s span=%s window=%s — "
                + "verify span name and telemetry pipeline",
            resolvedServiceName, resolvedSpanName, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
        return;
      }

      double errorRatePercent = (errorCount != null && errorCount > 0)
          ? errorCount / totalCount * 100
          : 0.0;
      String errorCountText = errorCount == null ? "0 (no error spans)" : String.format(Locale.ROOT, "%.1f", errorCount);
      String rateText = String.format(Locale.ROOT, "%.2f", errorRatePercent);
      String reportText = String.format(Locale.ROOT,
          "service=%s, span=%s, window=%s, errorCount=%s, totalCount=%s, errorRate=%s%%, threshold=%.1f%%%nquery=%s",
          resolvedServiceName, resolvedSpanName, rangeSelector, errorCountText, totalCountText, rateText, maxErrorRatePercent, errorCountPromQl);
      log.warn("[ASSERT PROMETHEUS ERROR RATE] {}", reportText);
      SerenityReportUtils.addCustomData("Prometheus error rate", reportText);

      if (errorRatePercent > maxErrorRatePercent) {
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus error rate %.2f%% > %.1f%% for service=%s span=%s window=%s",
            errorRatePercent, maxErrorRatePercent, resolvedServiceName, resolvedSpanName, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      } else {
        log.info("[ASSERT PROMETHEUS ERROR RATE] PASS errorRate={}% <= {}% for service={} span={}",
            rateText, maxErrorRatePercent, resolvedServiceName, resolvedSpanName);
        SerenityReportUtils.addCustomData("Prometheus error rate check",
            String.format(Locale.ROOT, "PASS errorRate=%s%% <= %.1f%%", rateText, maxErrorRatePercent));
      }
    } catch (Exception e) {
      throw new AssertionError("Prometheus error rate query failed: " + e.getMessage(), e);
    }
  }

  private void assertPrometheusRateGeInternal(
      String serviceName,
      String spanName,
      String rangeSelector,
      int divisorSeconds,
      Integer minRatePerSecond) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    String resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);

    String countPromQl = buildPrometheusHistogramCountQuery(resolvedServiceName, resolvedSpanName, rangeSelector);
    String ratePromQl = countPromQl + " / " + divisorSeconds;

    try {
      Double totalCount = queryPrometheusScalar(countPromQl);

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
      SerenityReportUtils.addCustomData("Prometheus rate", reportText);

      if (ratePerSecond < minRatePerSecond) {
        var ex = new AssertionError(String.format(Locale.ROOT,
            "Prometheus rate %.2f/s < %d/s for service=%s span=%s window=%s",
            ratePerSecond, minRatePerSecond, resolvedServiceName, resolvedSpanName, rangeSelector));
        SoftAssertionsContext.recordSoftFailure(ex.getMessage(), ex);
      } else {
        log.info("[ASSERT PROMETHEUS RATE] PASS rate={}/s >= {}/s for service={} span={}",
            rateText, minRatePerSecond, resolvedServiceName, resolvedSpanName);
        SerenityReportUtils.addCustomData("Prometheus rate check",
            String.format(Locale.ROOT, "PASS rate=%s/s >= %d/s", rateText, minRatePerSecond));
      }
    } catch (Exception e) {
      throw new AssertionError("Prometheus rate query failed: " + e.getMessage(), e);
    }
  }

  private void assertPrometheusHistogramMetricLeInternal(
      String serviceName,
      String spanName,
      String rangeSelector,
      Metric metric,
      Double maxMs) {
    String resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    String resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);
    String promQl = buildPrometheusHistogramQuery(resolvedServiceName, resolvedSpanName, rangeSelector, metric);
    String countPromQl = buildPrometheusHistogramCountQuery(resolvedServiceName, resolvedSpanName, rangeSelector);
    String thresholdText = formatThresholdMs(maxMs);

    try {
      Double sampleCountValue = queryPrometheusScalar(countPromQl);
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
      SerenityReportUtils.addCustomData("Prometheus sample count", sampleCountReport);

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

      Double observedValue = queryPrometheusScalar(promQl);
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
      SerenityReportUtils.addCustomData("Prometheus metric", reportText);

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

  private String buildPrometheusHistogramQuery(String serviceName, String spanName, String rangeSelector, Metric metric) {
    if (rangeSelector == null || rangeSelector.isBlank()) {
      throw new IllegalArgumentException("rangeSelector must not be blank");
    }
    String matcher = buildPrometheusLabelMatcher(serviceName, spanName);
    String range = "[" + rangeSelector + "]";

    return switch (metric.type()) {
      case AVG -> "sum(increase(traces_span_metrics_duration_milliseconds_sum"
          + matcher + range + ")) / sum(increase(traces_span_metrics_duration_milliseconds_count"
          + matcher + range + "))";
      case PERCENTILE -> String.format(
          Locale.ROOT,
          "histogram_quantile(%.2f, sum by (le) (increase(traces_span_metrics_duration_milliseconds_bucket%s%s)))",
          metric.percentile(), matcher, range);
      case MAX, MIN -> throw new IllegalArgumentException(
          "Prometheus histogram assertions support avg and percentiles only");
    };
  }

  private String buildPrometheusHistogramCountQuery(String serviceName, String spanName, String rangeSelector) {
    String matcher = buildPrometheusLabelMatcher(serviceName, spanName);
    return "sum(increase(traces_span_metrics_duration_milliseconds_count" + matcher + "[" + rangeSelector + "]))";
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
   * Parses a positive integer from a Tiger-resolved string.
   *
   * @param value the raw string value
   * @param fieldName the logical field name for error reporting
   * @return the parsed positive integer
   */
  private int parsePositiveInteger(String value, String fieldName) {
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed <= 0) {
        throw new IllegalArgumentException(fieldName + " must be > 0");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(fieldName + " must be a positive integer: " + value, e);
    }
  }

  /**
   * Builds a Prometheus label matcher for service and span labels.
   *
   * @param serviceName the Prometheus service label value
   * @param spanName the Prometheus span label value
   * @return a PromQL label matcher fragment or an empty string
   */
  private String buildPrometheusLabelMatcher(String serviceName, String spanName) {
    List<String> labels = new ArrayList<>();
    if (serviceName != null && !serviceName.isBlank() && !"*".equals(serviceName.trim())) {
      labels.add("service_name=\"" + escapePrometheusLabelValue(serviceName.trim()) + "\"");
    }
    if (spanName != null && !spanName.isBlank() && !"*".equals(spanName.trim())) {
      labels.add("span_name=\"" + escapePrometheusLabelValue(spanName.trim()) + "\"");
    }
    return labels.isEmpty() ? "" : "{" + String.join(", ", labels) + "}";
  }

  /**
   * Escapes a label value for safe embedding into a PromQL string literal.
   *
   * @param value the raw label value
   * @return the escaped label value
   */
  private String escapePrometheusLabelValue(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }

  /**
   * Executes a Prometheus instant query and returns its scalar result.
   *
   * @param promQl the PromQL query to execute
   * @return the scalar value, or {@code null} if Prometheus returned no series
   * @throws Exception if the request or JSON parsing fails
   */
  private Double queryPrometheusScalar(String promQl) throws Exception {
    String endpoint = TigerGlobalConfiguration.resolvePlaceholders(
        "${paths.prometheus.baseUrl}${paths.prometheus.prometheusMetricsSearchPath}");
    log.info("[PROMETHEUS QUERY] endpoint={} query={}", endpoint, promQl);
    var uri = URI.create(endpoint + "?query=" + URLEncoder.encode(promQl, StandardCharsets.UTF_8));
    var request = HttpRequest.newBuilder(uri)
        .timeout(PROMETHEUS_REQUEST_TIMEOUT)
        .header("Accept-Encoding", "gzip")
        .GET()
        .build();

    var response = httpClient.send(
        request,
        HttpResponse.BodyHandlers.ofByteArray());

    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Prometheus returned HTTP " + response.statusCode() + " for query: " + promQl);
    }

    String responseBody = decodePrometheusResponseBody(
        response.body(),
        response.headers().firstValue("Content-Encoding").orElse(""));
    JsonNode root = JSON.readTree(responseBody);
    if (!"success".equals(root.path("status").asText())) {
      throw new IllegalStateException("Prometheus query failed: " + root);
    }

    JsonNode result = root.path("data").path("result");
    if (!result.isArray() || result.isEmpty()) {
      return null;
    }
    if (result.size() != 1) {
      throw new IllegalStateException(
          "Expected exactly one Prometheus result but got " + result.size() + " for query: " + promQl);
    }

    JsonNode valueNode = result.get(0).path("value");
    if (!valueNode.isArray() || valueNode.size() < 2) {
      throw new IllegalStateException("Prometheus result has no scalar value: " + result.get(0));
    }

    return Double.parseDouble(valueNode.get(1).asText());
  }

  /**
   * Decodes a Prometheus response body, handling optional gzip compression.
   *
   * @param bodyBytes the raw response body
   * @param contentEncoding the response content encoding header value
   * @return the decoded UTF-8 response body
   * @throws IOException if gzip decoding fails
   */
  private String decodePrometheusResponseBody(byte[] bodyBytes, String contentEncoding)
      throws IOException {
    if (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
      try (var gzipStream = new GZIPInputStream(new ByteArrayInputStream(bodyBytes))) {
        return new String(gzipStream.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    return new String(bodyBytes, StandardCharsets.UTF_8);
  }

  private String formatMetricName(Metric metric) {
    return switch (metric.type()) {
      case PERCENTILE -> "p" + Math.round(metric.percentile() * 100);
      case AVG -> "avg";
      default -> metric.type().toString().toLowerCase();
    };
  }
}
