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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client wrapper for Prometheus instant and range queries.
 */
@Slf4j
public class PrometheusQueryService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Duration requestTimeout;
  private final int maxRetries;
  private volatile HttpClient httpClient;

  /**
   * Creates a Prometheus query service without request retries.
   *
   * @param requestTimeout request timeout
   */
  public PrometheusQueryService(Duration requestTimeout) {
    this(requestTimeout, 0);
  }

  /**
   * Creates a Prometheus query service.
   *
   * @param requestTimeout request timeout
   * @param maxRetries     maximum retry count after I/O failures
   */
  public PrometheusQueryService(Duration requestTimeout, int maxRetries) {
    this.requestTimeout = requestTimeout;
    this.maxRetries = maxRetries;
    this.httpClient = buildPrometheusHttpClient();
  }

  /**
   * Executes a Prometheus instant query and returns its scalar result.
   *
   * @param promQl PromQL query to execute
   * @return scalar value, or {@code null} if Prometheus returned no series
   * @throws Exception if the request or JSON parsing fails
   */
  public Double queryScalar(String promQl) throws Exception {
    var endpoint = TigerGlobalConfiguration.resolvePlaceholders(
        "${paths.prometheus.baseUrl}${paths.prometheus.prometheusMetricsSearchPath}");
    log.info("[PROMETHEUS QUERY] endpoint={} query={}", endpoint, promQl);
    var uri = URI.create(endpoint + "?query=" + URLEncoder.encode(promQl, StandardCharsets.UTF_8));
    var request = HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Accept-Encoding", "gzip")
        .GET()
        .build();

    var responseBody = executeRequest(request, "query", promQl);
    var result = readResultNode(responseBody, "Prometheus query failed");
    if (!result.isArray() || result.isEmpty()) {
      return null;
    }
    if (result.size() != 1) {
      throw new IllegalStateException(
          "Expected exactly one Prometheus result but got " + result.size() + " for query: " + promQl);
    }

    var valueNode = result.get(0).path("value");
    if (!valueNode.isArray() || valueNode.size() < 2) {
      throw new IllegalStateException("Prometheus result has no scalar value: " + result.get(0));
    }

    return parsePrometheusDouble(valueNode.get(1).asText());
  }

  /**
   * Executes a Prometheus range query.
   *
   * @param promQl      PromQL query to execute
   * @param start       query start timestamp
   * @param end         query end timestamp
   * @param stepSeconds query resolution in seconds
   * @return range series returned by Prometheus
   * @throws Exception if the request or JSON parsing fails
   */
  public List<RangeSeries> queryRange(
      String promQl,
      Instant start,
      Instant end,
      int stepSeconds) throws Exception {
    var endpoint = TigerGlobalConfiguration.resolvePlaceholders(
        "${paths.prometheus.baseUrl}${paths.prometheus.prometheusMetricsRangeSearchPath}");
    log.info("[PROMETHEUS RANGE QUERY] endpoint={} query={}", endpoint, promQl);
    var uri = URI.create(endpoint
        + "?query=" + URLEncoder.encode(promQl, StandardCharsets.UTF_8)
        + "&start=" + start.getEpochSecond()
        + "&end=" + end.getEpochSecond()
        + "&step=" + stepSeconds + "s");
    var request = HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Accept-Encoding", "gzip")
        .GET()
        .build();

    var responseBody = executeRequest(request, "range query", promQl);
    var result = readResultNode(responseBody, "Prometheus range query failed");
    if (!result.isArray() || result.isEmpty()) {
      return List.of();
    }

    var series = new ArrayList<RangeSeries>();
    for (var resultItem : result) {
      series.add(new RangeSeries(
          parseMetricLabels(resultItem.path("metric")),
          parseRangePoints(resultItem.path("values"))));
    }
    return series;
  }

  /**
   * Builds the HTTP client used for Prometheus queries.
   *
   * @return configured HTTP client
   */
  private HttpClient buildPrometheusHttpClient() {
    try {
      var builder = HttpClient.newBuilder()
          .connectTimeout(requestTimeout)
          .version(HttpClient.Version.HTTP_1_1);
      var endpoint = TigerGlobalConfiguration.resolvePlaceholders("${paths.prometheus.baseUrl}");
      if (endpoint.startsWith("https://")) {
        builder.sslContext(SslConfigurationService.getTrustAllSslContext());
      }
      return builder.build();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create Prometheus HTTP client", e);
    }
  }

  /**
   * Executes an HTTP request and decodes the response body.
   *
   * @param request     HTTP request
   * @param requestKind request kind for error messages
   * @param promQl      PromQL query
   * @return decoded response body
   * @throws IOException          if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  private String executeRequest(
      HttpRequest request,
      String requestKind,
      String promQl) throws IOException, InterruptedException {
    var response = sendWithRetries(request, requestKind);
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Prometheus returned HTTP " + response.statusCode() + " for " + requestKind + ": " + promQl);
    }
    return decodeResponseBody(
        response.body(),
        response.headers().firstValue("Content-Encoding").orElse(""));
  }

  /**
   * Sends an HTTP request with optional retries after I/O failures.
   *
   * @param request     HTTP request
   * @param requestKind request kind for log output
   * @return HTTP response
   * @throws IOException          if all attempts fail
   * @throws InterruptedException if retry sleep or the request is interrupted
   */
  private HttpResponse<byte[]> sendWithRetries(
      HttpRequest request,
      String requestKind) throws IOException, InterruptedException {
    HttpResponse<byte[]> response = null;
    IOException lastException = null;
    for (var attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        if (attempt > 0) {
          log.warn(
              "[PROMETHEUS] Retrying {} attempt {}/{} after {}",
              requestKind,
              attempt,
              maxRetries,
              lastException.getClass().getSimpleName());
          Thread.sleep(500L * attempt);
          this.httpClient = buildPrometheusHttpClient();
        }
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        lastException = null;
        break;
      } catch (IOException e) {
        lastException = e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw e;
      }
    }
    if (response == null) {
      throw new IOException("Prometheus " + requestKind + " failed after "
          + maxRetries + " retries", lastException);
    }
    return response;
  }

  /**
   * Reads the Prometheus {@code data.result} node from a response body.
   *
   * @param responseBody  decoded response body
   * @param failurePrefix error message prefix for failed Prometheus responses
   * @return result node
   * @throws IOException if JSON parsing fails
   */
  private JsonNode readResultNode(String responseBody, String failurePrefix) throws IOException {
    var root = JSON.readTree(responseBody);
    if (!"success".equals(root.path("status").asText())) {
      throw new IllegalStateException(failurePrefix + ": " + root);
    }
    return root.path("data").path("result");
  }

  /**
   * Parses Prometheus metric labels.
   *
   * @param metricNode Prometheus metric label node
   * @return labels in response order
   */
  private Map<String, String> parseMetricLabels(JsonNode metricNode) {
    var labels = new LinkedHashMap<String, String>();
    if (metricNode.isObject()) {
      metricNode.properties()
          .forEach(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
    }
    return labels;
  }

  /**
   * Parses Prometheus matrix values into range points.
   *
   * @param valuesNode Prometheus {@code values} node
   * @return parsed points sorted by timestamp
   */
  private List<RangePoint> parseRangePoints(JsonNode valuesNode) {
    if (!valuesNode.isArray()) {
      return List.of();
    }
    var points = new ArrayList<RangePoint>();
    for (var valueNode : valuesNode) {
      if (valueNode.isArray() && valueNode.size() >= 2) {
        var timestamp = Instant.ofEpochSecond(valueNode.get(0).asLong());
        var value = parsePrometheusDouble(valueNode.get(1).asText());
        if (Double.isFinite(value)) {
          points.add(new RangePoint(timestamp, value));
        }
      }
    }
    points.sort(Comparator.comparing(RangePoint::timestamp));
    return points;
  }

  /**
   * Decodes a Prometheus response body, handling optional gzip compression.
   *
   * @param bodyBytes       raw response body
   * @param contentEncoding response content encoding
   * @return decoded response body
   * @throws IOException if gzip decoding fails
   */
  private String decodeResponseBody(byte[] bodyBytes, String contentEncoding) throws IOException {
    if (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
      try (var gzipStream = new GZIPInputStream(new ByteArrayInputStream(bodyBytes))) {
        return new String(gzipStream.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    return new String(bodyBytes, StandardCharsets.UTF_8);
  }

  /**
   * Parses a Prometheus floating-point value, including infinity aliases.
   *
   * @param value raw Prometheus value text
   * @return parsed double
   */
  private double parsePrometheusDouble(String value) {
    return switch (value) {
      case "Inf", "+Inf" -> Double.POSITIVE_INFINITY;
      case "-Inf" -> Double.NEGATIVE_INFINITY;
      default -> Double.parseDouble(value);
    };
  }

  /**
   * Prometheus range-query series.
   *
   * @param metric labels returned for the series
   * @param points parsed series points
   */
  public record RangeSeries(Map<String, String> metric, List<RangePoint> points) {

  }

  /**
   * Prometheus range-query point.
   *
   * @param timestamp point timestamp
   * @param value     point value
   */
  public record RangePoint(Instant timestamp, double value) {

  }
}
