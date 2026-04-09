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

package de.gematik.zeta.steps.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/**
 * Benchmark for /load/create_instances.
 *
 * <p>No Tiger, no system properties, just run the test directly in IntelliJ.</p>
 *
 * <p>Two tests:</p>
 *  - countSweepWithoutAutoInit: creates instances only (no init), CSV: ids_count only
 *  - countSweepWithAutoInit:    creates + fully initializes, CSV: ids_count + ready/not_ready
 *
 * <p>Edit BASE_URL, FACHDIENST_URL, SMCB_KEYSTORE_MANIFEST, CREATE_CHUNK_SIZE,
 * AUTHENTICATE_PARALLELISM, AUTHENTICATE_INSTANCES_PER_THREAD, and COUNT_SWEEP below as needed.
 */
public class LoadBenchmark {

  private static final String BASE_URL = "https://zeta-kind.local";

  private static final List<Integer> COUNT_SWEEP = List.of(
      1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
      200, 300, 400, 500, 600, 700, 800, 900, 1000,
      2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000,
      20000, 30000, 40000, 50000, 100000, 200000, 300000
  );

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Nonce endpoint as used by the SDK
  private static final String NONCE_ENDPOINT =
      BASE_URL + "/auth/realms/zeta-guard/zeta-guard-nonce";
  // Token endpoint as used by the SDK
  private static final String TOKEN_ENDPOINT =
      BASE_URL + "/auth/realms/zeta-guard/protocol/openid-connect/token";

  /**
   * Measures raw HTTP latency for fetchNonce and tokenExchange against achelos,
   * printing per-call breakdown so we can see exactly where the time goes.
   * TokenExchange is sent with a deliberately invalid body so we get a fast 400/401
   * without needing a real SMC-B key — we only want the server-round-trip time.
   *
   * @throws Exception if the HTTP client cannot be built
   */
  @Test
  void measureAuthSubSteps() throws Exception {
    var client = buildUnsafeHttpClient();
    int iterations = 20;

    System.out.println("\n=== measureAuthSubSteps against " + BASE_URL + " ===");
    System.out.printf("%-5s  %-12s  %-15s%n", "iter", "fetchNonce_ms", "tokenExchange_ms");

    long totalNonce = 0;
    long totalToken = 0;
    for (int i = 0; i < iterations; i++) {
      // Step 1: fetchNonce
      long t0 = System.nanoTime();
      var nonceReq = HttpRequest.newBuilder(URI.create(NONCE_ENDPOINT))
          .GET().timeout(READ_TIMEOUT).build();
      var nonceResp = client.send(nonceReq, HttpResponse.BodyHandlers.ofString());
      long nonceMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

      // Step 2: tokenExchange (invalid body → fast reject, we only measure network+server time)
      long t1 = System.nanoTime();
      var tokenBody = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
          + "&client_id=benchmark-probe"
          + "&client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer"
          + "&client_assertion=invalid"
          + "&subject_token=invalid"
          + "&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt";
      var tokenReq = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
          .timeout(READ_TIMEOUT).build();
      var tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
      long tokenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);

      totalNonce += nonceMs;
      totalToken += tokenMs;
      System.out.printf("%-5d  %-12d  %-15d  [nonce=%d token=%d]%n",
          i + 1, nonceMs, tokenMs, nonceResp.statusCode(), tokenResp.statusCode());
    }
    System.out.printf("%-5s  %-12d  %-15d  (avg over %d iterations)%n",
        "avg", totalNonce / iterations, totalToken / iterations, iterations);
  }

  /**
   * Benchmarks {@code /load/create_instances} without auto-init and without the load-test request
   * body.
   *
   * @throws Exception if the benchmark setup fails
   */

  @Test
  void countSweepWithoutAutoInit() throws Exception {
    HttpClient client = buildUnsafeHttpClient();
    Path outputCsv = Path.of("out/load-benchmark-no-autoinit.csv");
    Files.createDirectories(outputCsv.getParent());

    try (BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
      writer.write("timestamp,count,status_code,duration_ms,ids_count,success,error");
      writer.newLine();

      for (int count : COUNT_SWEEP) {
        cleanup(client);
        CreateResult r = sendCreate(client, count, false);
        writer.write(String.join(",",
            csv(r.timestamp()), String.valueOf(r.count()), String.valueOf(r.statusCode()),
            String.valueOf(r.durationMs()), String.valueOf(r.idsCount()),
            String.valueOf(r.success()), csv(r.error())));
        writer.newLine();
        writer.flush();
        System.out.printf("autoInit=false  count=%-8d  status=%d  ms=%-7d  ids=%d%s%n",
            count, r.statusCode(), r.durationMs(), r.idsCount(),
            r.success() ? "" : "  ERROR: " + r.error());
        cleanup(client);
        if (!r.success()) {
          System.out.println("Stopping after first failure at count=" + count);
          break;
        }
      }
    }
    System.out.println("Done. CSV: " + outputCsv.toAbsolutePath());
  }

  @Test
  void countSweepWithAutoInit() throws Exception {
    HttpClient client = buildUnsafeHttpClient();
    Path outputCsv = Path.of("out/load-benchmark-autoinit.csv");
    Files.createDirectories(outputCsv.getParent());

    try (BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
      writer.write("timestamp,count,status_code,duration_ms,ids_count,ready_count,not_ready_count,success,error");
      writer.newLine();

      for (int count : COUNT_SWEEP) {
        cleanup(client);
        CreateResult r = sendCreate(client, count, true);
        InstanceStats stats = listInstances(client);
        writer.write(String.join(",",
            csv(r.timestamp()), String.valueOf(r.count()), String.valueOf(r.statusCode()),
            String.valueOf(r.durationMs()), String.valueOf(r.idsCount()),
            String.valueOf(stats.ready()), String.valueOf(stats.notReady()),
            String.valueOf(r.success()), csv(r.error())));
        writer.newLine();
        writer.flush();
        System.out.printf(
            "autoInit=true   count=%-8d  status=%d  ms=%-7d  ids=%-8d  ready=%-8d  not_ready=%d%s%n",
            count, r.statusCode(), r.durationMs(), r.idsCount(), stats.ready(), stats.notReady(),
            r.success() ? "" : "  ERROR: " + r.error());
        cleanup(client);
        if (!r.success()) {
          System.out.println("Stopping after first failure at count=" + count);
          break;
        }
      }
    }
  }

  // ── HTTP helpers ─────────────────────────────────────────────────────────────

  private CreateResult sendCreate(HttpClient client, int count, boolean autoInit) {
    Instant start = Instant.now();
    long t0 = System.nanoTime();
    try {
      URI uri = URI.create(
          BASE_URL + "/load/create_instances?count=" + count + "&autoInit=" + autoInit);
      String payload = "{\"count\":" + count + ",\"autoInit\":" + autoInit + "}";
      HttpRequest req = HttpRequest.newBuilder(uri)
          .timeout(READ_TIMEOUT)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

      int idsCount = 0;
      String error = "";
      boolean success = resp.statusCode() == 200;
      try {
        JsonNode node = MAPPER.readTree(resp.body());
        if (node.has("ids") && node.get("ids").isArray()) {
          idsCount = node.get("ids").size();
        }
        if (success) {
          success = idsCount == count;
          if (!success) {
            error = "mismatch(ids=" + idsCount + ";expected=" + count + ")";
          }
        }
      } catch (Exception e) {
        if (success) {
          success = false;
          error = "parse:" + e.getMessage();
        }
      }
      if (!success && error.isEmpty()) {
        error = "HTTP_" + resp.statusCode();
      }
      return new CreateResult(start.toString(), count, resp.statusCode(), ms, idsCount,
          success, error);
    } catch (Exception e) {
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
      return new CreateResult(start.toString(), count, 0, ms, 0, false,
          e.getClass().getSimpleName() + ":" + e.getMessage());
    }
  }

  private InstanceStats listInstances(HttpClient client) {
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/load/list_instances"))
          .GET().timeout(Duration.ofSeconds(30)).build();
      HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
      int ready = 0;
      int notReady = 0;
      JsonNode arr = MAPPER.readTree(resp.body());
      if (arr.isArray()) {
        for (JsonNode instance : arr) {
          if ("READY".equals(instance.path("state").asText(""))) {
            ready++;
          } else {
            notReady++;
          }
        }
      }
      return new InstanceStats(ready, notReady);
    } catch (Exception e) {
      System.out.println("list_instances failed: " + e.getMessage());
      return new InstanceStats(-1, -1);
    }
  }

  private void cleanup(HttpClient client) {
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/load/delete_instances"))
          .DELETE().timeout(Duration.ofSeconds(30)).build();
      client.send(req, HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      System.out.println("Cleanup failed: " + e.getMessage());
    }
  }

  private HttpClient buildUnsafeHttpClient() throws Exception {
    TrustManager[] trustAll = {new X509TrustManager() {
      public void checkClientTrusted(X509Certificate[] c, String a) {}

      public void checkServerTrusted(X509Certificate[] c, String a) {}

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    }
    };
    var ssl = SSLContext.getInstance("TLS");
    ssl.init(null, trustAll, new SecureRandom());
    SSLParameters params = new SSLParameters();
    params.setEndpointIdentificationAlgorithm(null);
    return HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .sslContext(ssl)
        .sslParameters(params)
        .build();
  }

  private String csv(String v) {
    return v == null ? "" : v.replace("\n", " ").replace("\r", " ").replace(",", ";");
  }

  private record CreateResult(
      String timestamp, int count, int statusCode, long durationMs,
      int idsCount, boolean success, String error) {}

  private record InstanceStats(int ready, int notReady) {}
}
