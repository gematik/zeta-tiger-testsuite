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
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client wrapper for Jaeger trace-search queries.
 *
 * <p>Unlike the {@code TGR sende GET Anfrage} Cucumber step, which routes through the local Tiger
 * proxy, this service uses its own {@link HttpClient} that connects directly to the Jaeger
 * endpoint. The direct connection preserves the target hostname in the TLS SNI extension, which
 * the Tiger proxy's binary-bridge forwarding (Tiger 4.4) does not — there the resolved IP is sent
 * as SNI, triggering {@code unrecognized_name(112)} against the Achelos ingress. Mirrors
 * {@link PrometheusQueryService}, which relies on the same direct-client pattern for the same
 * reason.</p>
 */
@Slf4j
public class JaegerQueryService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Duration requestTimeout;
  private final int maxRetries;
  private volatile HttpClient httpClient;

  /**
   * Creates a Jaeger query service without request retries.
   *
   * @param requestTimeout request timeout
   */
  public JaegerQueryService(Duration requestTimeout) {
    this(requestTimeout, 0);
  }

  /**
   * Creates a Jaeger query service.
   *
   * @param requestTimeout request timeout
   * @param maxRetries     maximum retry count after I/O failures
   */
  public JaegerQueryService(Duration requestTimeout, int maxRetries) {
    this.requestTimeout = requestTimeout;
    this.maxRetries = maxRetries;
    this.httpClient = buildJaegerHttpClient();
  }

  /**
   * Executes a Jaeger trace search and returns the parsed result.
   *
   * <p>Every parameter except {@code service} is optional; blank values are omitted from the
   * query string so the same method serves both the error-tag and the {@code minDuration}
   * variants used by the performance scenarios.</p>
   *
   * @param service     Jaeger {@code service} label (required)
   * @param operation   Jaeger {@code operation} filter, or blank to omit
   * @param startMicros lookback window start in epoch microseconds, or blank to omit
   * @param endMicros   lookback window end in epoch microseconds, or blank to omit
   * @param limit       maximum number of traces to return, or blank to omit
   * @param tags        Jaeger {@code tags} filter as JSON (e.g. {@code {"error":"true"}}), or blank
   * @param minDuration Jaeger {@code minDuration} filter (e.g. {@code 1s}), or blank to omit
   * @return the search result, including the number of traces found
   * @throws Exception if the request or JSON parsing fails
   */
  public TraceSearchResult searchTraces(
      String service,
      String operation,
      String startMicros,
      String endMicros,
      String limit,
      String tags,
      String minDuration) throws Exception {
    var query = new StringJoiner("&");
    query.add("service=" + encode(requireService(service)));
    appendIfPresent(query, "operation", operation);
    appendIfPresent(query, "start", startMicros);
    appendIfPresent(query, "end", endMicros);
    appendIfPresent(query, "limit", limit);
    appendIfPresent(query, "tags", tags);
    appendIfPresent(query, "minDuration", minDuration);

    var baseUrl = TigerGlobalConfiguration.resolvePlaceholders(
        "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}");
    var uri = URI.create(baseUrl + "?" + query);
    log.info("[JAEGER QUERY] uri={}", uri);
    var request = HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Accept-Encoding", "gzip")
        .GET()
        .build();

    var responseBody = executeRequest(request, service);
    var root = JSON.readTree(responseBody);
    var data = root.path("data");
    var traceCount = data.isArray() ? data.size() : 0;
    return new TraceSearchResult(service, operation, traceCount, responseBody);
  }

  /**
   * Builds the HTTP client used for Jaeger queries.
   *
   * @return configured HTTP client
   */
  private HttpClient buildJaegerHttpClient() {
    try {
      var builder = HttpClient.newBuilder()
          .connectTimeout(requestTimeout)
          // Ignore the JVM-wide proxy that Tiger installs (https.proxyHost=localhost). The Tiger
          // test lib routes all traffic through the local Tiger proxy via system properties, which
          // java.net.http.HttpClient would otherwise honor and then fail to CONNECT-tunnel HTTPS.
          // NO_PROXY makes this client connect directly, like curl does.
          .proxy(ProxySelector.of(null))
          .version(HttpClient.Version.HTTP_1_1);
      var baseUrl = TigerGlobalConfiguration.resolvePlaceholders("${paths.jaeger.baseUrl}");
      if (baseUrl.startsWith("https://")) {
        builder.sslContext(SslConfigurationService.getTrustAllSslContext());
      }
      return builder.build();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create Jaeger HTTP client", e);
    }
  }

  /**
   * Executes an HTTP request and decodes the response body.
   *
   * @param request HTTP request
   * @param service Jaeger service for error messages
   * @return decoded response body
   * @throws IOException          if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  private String executeRequest(HttpRequest request, String service)
      throws IOException, InterruptedException {
    var response = sendWithRetries(request, service);
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Jaeger returned HTTP " + response.statusCode() + " for service: " + service);
    }
    return decodeResponseBody(
        response.body(),
        response.headers().firstValue("Content-Encoding").orElse(""));
  }

  /**
   * Sends an HTTP request with optional retries after I/O failures.
   *
   * @param request HTTP request
   * @param service Jaeger service for log output
   * @return HTTP response
   * @throws IOException          if all attempts fail
   * @throws InterruptedException if retry sleep or the request is interrupted
   */
  private HttpResponse<byte[]> sendWithRetries(HttpRequest request, String service)
      throws IOException, InterruptedException {
    HttpResponse<byte[]> response = null;
    IOException lastException = null;
    for (var attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        if (attempt > 0) {
          log.warn(
              "[JAEGER] Retrying service {} attempt {}/{} after {}",
              service,
              attempt,
              maxRetries,
              lastException.getClass().getSimpleName());
          Thread.sleep(500L * attempt);
          this.httpClient = buildJaegerHttpClient();
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
      throw new IOException("Jaeger search for service " + service + " failed after "
          + maxRetries + " retries", lastException);
    }
    return response;
  }

  /**
   * Decodes a Jaeger response body, handling optional gzip compression.
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
   * Appends a query parameter when the resolved value is present.
   *
   * @param query the query accumulator
   * @param name  the parameter name
   * @param value the raw (possibly placeholder) value
   */
  private void appendIfPresent(StringJoiner query, String name, String value) {
    var resolved = TigerGlobalConfiguration.resolvePlaceholders(value == null ? "" : value).trim();
    if (!resolved.isEmpty()) {
      query.add(name + "=" + encode(resolved));
    }
  }

  private String requireService(String service) {
    var resolved = TigerGlobalConfiguration.resolvePlaceholders(service == null ? "" : service).trim();
    if (resolved.isEmpty()) {
      throw new IllegalArgumentException("Jaeger trace search requires a non-blank service");
    }
    return resolved;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Parses the labels of the first span of the first returned trace, for diagnostic reporting.
   *
   * @param responseBody decoded Jaeger response body
   * @return process/service tags of the first span, or an empty map
   * @throws IOException if JSON parsing fails
   */
  public Map<String, String> firstTraceTags(String responseBody) throws IOException {
    var labels = new LinkedHashMap<String, String>();
    var data = JSON.readTree(responseBody).path("data");
    if (data.isArray() && !data.isEmpty()) {
      var tags = data.get(0).path("spans").path(0).path("tags");
      if (tags.isArray()) {
        for (JsonNode tag : tags) {
          labels.put(tag.path("key").asText(), tag.path("value").asText());
        }
      }
    }
    return labels;
  }

  /**
   * Result of a Jaeger trace search.
   *
   * @param service      the queried service
   * @param operation    the queried operation, or blank
   * @param traceCount   the number of traces returned
   * @param responseBody the decoded response body for reporting
   */
  public record TraceSearchResult(
      String service, String operation, int traceCount, String responseBody) {

  }
}
