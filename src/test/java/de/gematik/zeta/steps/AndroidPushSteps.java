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

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.zeta.services.BoundedObservation;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.restassured.http.Method;
import io.restassured.response.Response;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.BiFunction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Observes Android push delivery and releases subscriptions owned by the scenario. */
public class AndroidPushSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final BiFunction<Method, URI, Response> request;
  private URI cleanupEndpoint;
  private long observationDeadline;
  private Instant observationStarted;

  /** Uses the configured Tiger client for driver requests and traffic recording. */
  public AndroidPushSteps() {
    this((method, uri) -> TigerHttpClient.givenDefaultSpec().request(method, uri));
  }

  /** Creates an observer with an injectable driver transport. */
  AndroidPushSteps(BiFunction<Method, URI, Response> request) {
    this.request = request;
  }

  /** Registers subscription cleanup before the first pusher registration attempt. */
  @Gegebensei("merke den Android-Push-Kontext zur Bereinigung")
  public void ownContext() {
    cleanupEndpoint = URI.create(config("paths.androidClient.notifications.unsubscribeAll"));
  }

  /**
   * Starts one monotonic deadline before submitting an event.
   *
   * @param seconds total delivery and follow-up observation budget
   */
  @Gegebensei("ein Android-Push-Prüffenster von {int} Sekunden")
  public void beginObservation(int seconds) {
    require(seconds > 0, "Positive Android push observation budget required");
    require(observationStarted == null, "Android push observation already started");
    observationStarted = Instant.now();
    observationDeadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
  }

  /**
   * Polls the receive buffer and stores the exactly correlated decrypted payload.
   *
   * @param correlationId event correlation identifier
   * @param keyIdentifier encryption key identifier of the registered pusher
   * @param variable scenario-local object receiving the payload fields
   * @throws InterruptedException if the observation is interrupted
   */
  @Dann("warte innerhalb des Android-Push-Prüffensters auf die Nachricht mit Korrelation"
      + " {tigerResolvedString} und Schlüsselkennung {tigerResolvedString}"
      + " und speichere die Nutzdaten in Variable {string}")
  public void awaitPush(String correlationId, String keyIdentifier, String variable)
      throws InterruptedException {
    require(observationStarted != null, "Android push observation has not started");
    require(!correlationId.isBlank(), "Missing push correlation identifier");
    require(!keyIdentifier.isBlank(), "Missing push encryption key identifier");
    var endpoint = URI.create(config("paths.androidClient.notifications.received"));
    var payload = BoundedObservation.awaitUntil(() -> {
      var response = request.apply(Method.GET, endpoint);
      require(response.statusCode() == 200,
          "Android receive endpoint returned HTTP " + response.statusCode());
      return correlatedPayload(response.asString(), correlationId, keyIdentifier);
    }, value -> value != null, observationDeadline, Duration.ofSeconds(1),
        "Android push was not delivered and decrypted for correlation " + correlationId);
    TigerGlobalConfiguration.putValue(variable, JSON.convertValue(payload, Map.class),
        ConfigurationValuePrecedence.TEST_CONTEXT);
    TigerGlobalConfiguration.putValue("androidPushExpectedMonthPattern",
        monthPattern(observationStarted, Instant.now()), ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Waits out the remaining original observation budget.
   *
   * @throws InterruptedException if waiting is interrupted
   */
  @Dann("warte bis zum Ende des Android-Push-Prüffensters")
  public void finishObservation() throws InterruptedException {
    require(observationStarted != null, "Android push observation has not started");
    long remaining;
    while ((remaining = observationDeadline - System.nanoTime()) > 0) {
      Thread.sleep(Math.min(100, Duration.ofNanos(remaining).toMillis() + 1));
    }
  }

  /** Disables owned subscriptions after successful and failed scenarios. */
  @After(value = "@smoke_android_push", order = 100)
  public void cleanup() {
    if (cleanupEndpoint != null) {
      require(request.apply(Method.POST, cleanupEndpoint).statusCode() == 200,
          "Android push subscription cleanup failed");
      cleanupEndpoint = null;
    }
  }

  /** Returns a successfully decrypted payload for the exact event, or null while absent. */
  static JsonNode correlatedPayload(String body, String correlationId, String keyIdentifier) {
    var messages = JSON.readTree(body);
    require(messages != null && messages.isArray(), "Android receive buffer is not a JSON array");
    for (var message : messages) {
      require(message.isObject(), "Android receive buffer contains a non-object entry");
      var receivedKey = message.path("keyIdentifier");
      require(receivedKey.isString() && !receivedKey.asString().isBlank(),
          "Android push is missing its encryption key identifier");
      if (!keyIdentifier.equals(receivedKey.asString())) {
        continue;
      }
      if (!message.path("error").isNull() && !message.path("error").isMissingNode()) {
        throw new AssertionError("Android push decryption failed: " + message.path("error"));
      }
      var decrypted = message.path("decrypted");
      if (decrypted.isString()) {
        var payload = JSON.readTree(decrypted.asString());
        if (payload != null && payload.isObject()
            && correlationId.equals(payload.path("correlation_id").asString())) {
          return payload;
        }
      }
    }
    return null;
  }

  /** Returns the UTC months spanned by an observed send and receipt interval. */
  static String monthPattern(Instant start, Instant end) {
    require(!end.isBefore(start), "Push receipt precedes the observation start");
    var first = YearMonth.from(start.atZone(ZoneOffset.UTC));
    var last = YearMonth.from(end.atZone(ZoneOffset.UTC));
    return first.equals(last) ? first.toString() : "(?:" + first + "|" + last + ")";
  }

  /** Resolves a required endpoint from the scenario configuration. */
  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .orElseThrow(() -> new AssertionError("Missing configuration: " + key));
  }

  /** Rejects an invalid driver response without retrying it as an absent delivery. */
  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
