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

package de.gematik.zeta.services;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.Metric;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Utility methods for Prometheus-related step definitions.
 */
public final class PrometheusQuerySupport {

  private PrometheusQuerySupport() {
  }

  /**
   * Parses a positive integer from a Tiger-resolved string.
   *
   * @param value     raw string value
   * @param fieldName logical field name for error reporting
   * @return parsed positive integer
   */
  public static int parsePositiveInteger(String value, String fieldName) {
    var resolvedValue = TigerGlobalConfiguration.resolvePlaceholders(value);
    try {
      var parsed = Integer.parseInt(resolvedValue.trim());
      if (parsed <= 0) {
        throw new IllegalArgumentException(fieldName + " must be > 0");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          fieldName + " must be a positive integer: " + resolvedValue,
          e);
    }
  }

  /**
   * Parses and resolves comma-separated Prometheus span names.
   *
   * @param spanNames comma-separated span name expression
   * @return resolved non-empty span names
   */
  public static List<String> parseSpanNames(String spanNames) {
    var resolvedSpanNames = TigerGlobalConfiguration.resolvePlaceholders(spanNames);
    return Arrays.stream(resolvedSpanNames.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toList());
  }

  /**
   * Builds a Prometheus label matcher for service and span labels.
   *
   * @param serviceName Prometheus {@code service_name} label value
   * @param spanName    Prometheus {@code span_name} label value
   * @return PromQL label matcher fragment or an empty string
   */
  public static String labelMatcher(String serviceName, String spanName) {
    return labelMatcher(serviceName, spanName, null);
  }

  /**
   * Builds a Prometheus label matcher for service, span and optional extra labels.
   *
   * @param serviceName  Prometheus {@code service_name} label value
   * @param spanName     Prometheus {@code span_name} label value
   * @param extraMatcher optional preformatted Prometheus label matcher
   * @return PromQL label matcher fragment or an empty string
   */
  public static String labelMatcher(String serviceName, String spanName, String extraMatcher) {
    var labels = new ArrayList<String>();
    if (serviceName != null && !serviceName.isBlank() && !"*".equals(serviceName.trim())) {
      labels.add("service_name=\"" + escapeLabelValue(serviceName.trim()) + "\"");
    }
    if (spanName != null && !spanName.isBlank() && !"*".equals(spanName.trim())) {
      labels.add("span_name=\"" + escapeLabelValue(spanName.trim()) + "\"");
    }
    if (extraMatcher != null && !extraMatcher.isBlank()) {
      labels.add(extraMatcher.trim());
    }
    return labels.isEmpty() ? "" : "{" + String.join(", ", labels) + "}";
  }

  /**
   * Builds one escaped Prometheus equality matcher.
   *
   * @param labelName  Prometheus label name
   * @param labelValue Prometheus label value
   * @return matcher without surrounding braces
   */
  public static String singleLabelMatcher(String labelName, String labelValue) {
    var resolvedLabelName = TigerGlobalConfiguration.resolvePlaceholders(labelName).trim();
    if (!resolvedLabelName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
      throw new IllegalArgumentException("Invalid Prometheus label name: " + resolvedLabelName);
    }
    var resolvedLabelValue = TigerGlobalConfiguration.resolvePlaceholders(labelValue).trim();
    return resolvedLabelName + "=\"" + escapeLabelValue(resolvedLabelValue) + "\"";
  }

  /**
   * Escapes a label value for safe embedding into a PromQL string literal.
   *
   * @param value raw label value
   * @return escaped label value
   */
  public static String escapeLabelValue(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }

  /**
   * Builds a Prometheus histogram latency query.
   *
   * @param serviceName   Prometheus {@code service_name} label value
   * @param spanName      Prometheus {@code span_name} label value
   * @param rangeSelector Prometheus lookback range selector
   * @param metric        latency metric to query
   * @return PromQL scalar expression
   */
  public static String histogramLatencyQuery(
      String serviceName,
      String spanName,
      String rangeSelector,
      Metric metric) {
    return histogramLatencyQuery(serviceName, spanName, rangeSelector, metric, null);
  }

  /**
   * Builds a Prometheus histogram latency query with an optional additional matcher.
   *
   * @param serviceName   Prometheus {@code service_name} label value
   * @param spanName      Prometheus {@code span_name} label value
   * @param rangeSelector Prometheus lookback range selector
   * @param metric        latency metric to query
   * @param extraMatcher  optional additional Prometheus label matcher
   * @return PromQL scalar expression
   */
  public static String histogramLatencyQuery(
      String serviceName,
      String spanName,
      String rangeSelector,
      Metric metric,
      String extraMatcher) {
    if (rangeSelector == null || rangeSelector.isBlank()) {
      throw new IllegalArgumentException("rangeSelector must not be blank");
    }
    var matcher = labelMatcher(serviceName, spanName, extraMatcher);
    var range = "[" + rangeSelector + "]";

    return switch (metric.type()) {
      case AVG -> "sum(increase(traces_span_metrics_duration_milliseconds_sum"
          + matcher + range + ")) / sum(increase(traces_span_metrics_duration_milliseconds_count"
          + matcher + range + "))";
      case PERCENTILE -> histogramQuantileQuery(metric.percentile(), matcher, rangeSelector);
    };
  }

  /**
   * Builds a Prometheus histogram count query for a service/span combination.
   *
   * @param serviceName   Prometheus {@code service_name} label value
   * @param spanName      Prometheus {@code span_name} label value
   * @param rangeSelector Prometheus lookback range selector
   * @return PromQL scalar expression
   */
  public static String histogramCountQuery(
      String serviceName,
      String spanName,
      String rangeSelector) {
    return histogramCountQuery(serviceName, spanName, rangeSelector, null);
  }

  /**
   * Builds a Prometheus histogram count query with an optional additional matcher.
   *
   * @param serviceName   Prometheus {@code service_name} label value
   * @param spanName      Prometheus {@code span_name} label value
   * @param rangeSelector Prometheus lookback range selector
   * @param extraMatcher  optional additional Prometheus label matcher
   * @return PromQL scalar expression
   */
  public static String histogramCountQuery(
      String serviceName,
      String spanName,
      String rangeSelector,
      String extraMatcher) {
    var matcher = labelMatcher(serviceName, spanName, extraMatcher);
    return "sum(increase(traces_span_metrics_duration_milliseconds_count"
        + matcher + "[" + rangeSelector + "]))";
  }

  /**
   * Builds a Prometheus histogram quantile query.
   *
   * @param quantile      quantile value in the range {@code 0..1}
   * @param matcher       Prometheus label matcher fragment
   * @param rangeSelector Prometheus range selector duration
   * @return PromQL expression
   */
  public static String histogramQuantileQuery(
      double quantile,
      String matcher,
      String rangeSelector) {
    return String.format(
        Locale.ROOT,
        "histogram_quantile(%.2f, sum by (le) "
            + "(increase(traces_span_metrics_duration_milliseconds_bucket%s[%s])))",
        quantile,
        matcher,
        rangeSelector);
  }

  /**
   * Builds a sum of Prometheus count expressions for comma-separated span names.
   *
   * @param serviceName   Prometheus {@code service_name} label value
   * @param spanNames     comma-separated Prometheus {@code span_name} label values
   * @param rangeSelector Prometheus range selector duration
   * @param extraMatcher  optional additional label matcher
   * @return PromQL expression
   */
  public static String combinedCountExpression(
      String serviceName,
      String spanNames,
      String rangeSelector,
      String extraMatcher) {
    var resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    return parseSpanNames(spanNames).stream()
        .map(span -> {
          var matcher = labelMatcher(resolvedServiceName, span, extraMatcher);
          return "(sum(increase(traces_span_metrics_duration_milliseconds_count"
              + matcher + "[" + rangeSelector + "])) or vector(0))";
        })
        .collect(Collectors.joining(" + "));
  }
}
