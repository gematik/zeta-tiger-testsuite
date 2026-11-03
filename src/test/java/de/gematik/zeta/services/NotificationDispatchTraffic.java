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

import de.gematik.test.tiger.lib.TigerHttpClient;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded RS transport and parallel stimulation; never manufactures a product response.
 */
public final class NotificationDispatchTraffic {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final String url;
  private final String identity;

  /**
   * Selects the real RS route and the independently SDK-registered recipient.
   */
  public NotificationDispatchTraffic(String url, String identity) {
    require(identity.matches("[A-Z][0-9]{9}"), "Expected the fresh test KVNR");
    this.url = url;
    this.identity = identity;
  }

  /**
   * Original HTTP response and its request correlation; the caller verifies the outcome.
   */
  public record Observation(String correlation, int status, String body) {
    @Override
    public String toString() {
      return "Observation[correlation=" + correlation + ", status=" + status + "]";
    }
  }

  /**
   * Sends exactly one event with a five-second connection and 15-second response timeout.
   */
  public Observation send() {
    var marker = "TA29987-04-" + UUID.randomUUID();
    var body = JSON.createObjectNode().put("channel", "zeta");
    body.putObject("user").put("id_type", "kvnr").put("value", identity);
    body.putObject("payload")
        .put("event_type", "registered_client_activity")
        .put("correlation_id", marker);
    var http =
        HttpClientConfig.httpClientConfig()
            .setParam("http.connection.timeout", 5000)
            .setParam("http.socket.timeout", 15000);
    var response =
        TigerHttpClient.givenDefaultSpec()
            .config(RestAssured.config().httpClient(http))
            .header("X-CC-NS-Test", marker)
            .contentType("application/json")
            .body(body.toString())
            .post(url);
    return new Observation(marker, response.statusCode(), response.asString());
  }

  /**
   * Coordinates one 16-request burst with bounded start and completion waits.
   */
  public List<Observation> burst() throws Exception {
    var gate = new CountDownLatch(1);
    var ready = new CountDownLatch(16);
    var jobs = new ArrayList<Callable<Observation>>();
    for (int i = 0; i < 16; i++) {
      jobs.add(
          () -> {
            ready.countDown();
            require(gate.await(10, TimeUnit.SECONDS), "NS burst start deadline exceeded");
            return send();
          });
    }
    var executor = Executors.newFixedThreadPool(16);
    try {
      var futures = jobs.stream().map(executor::submit).toList();
      require(ready.await(10, TimeUnit.SECONDS), "NS burst workers did not become ready");
      gate.countDown();
      var observations = new ArrayList<Observation>();
      for (var future : futures) {
        observations.add(future.get(25, TimeUnit.SECONDS));
      }
      return List.copyOf(observations);
    } finally {
      gate.countDown();
      executor.shutdownNow();
      require(
          executor.awaitTermination(25, TimeUnit.SECONDS), "NS burst workers did not terminate");
    }
  }

  /**
   * Observes recovery with a fresh event every two seconds and a total 20-second deadline.
   */
  public Observation awaitRecovery(List<Observation> observations) throws InterruptedException {
    var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    Observation recovery;
    do {
      Thread.sleep(2000);
      recovery = send();
      observations.add(recovery);
    } while (recovery.status() != 202 && System.nanoTime() < deadline);
    require(
        recovery.status() == 202 && System.nanoTime() <= deadline,
        "NS did not recover within the 20-second test profile deadline");
    return recovery;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
