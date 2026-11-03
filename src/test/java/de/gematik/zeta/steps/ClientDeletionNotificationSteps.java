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
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.ClientDeletionMailAssertions;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RedirectConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.http.impl.client.DefaultHttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks the explicitly captured Tiger mail response and polls new deliveries through Tiger.
 */
public class ClientDeletionNotificationSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration MAIL_REQUEST_LIMIT = Duration.ofSeconds(5);
  private static final Duration MAIL_RETRY_INTERVAL = Duration.ofSeconds(1);
  private Map<Integer, JsonNode> baseline;
  private String email;
  private Pattern completedNotice;

  /**
   * Reads through Tiger, retrying HTTP 404 for at most five seconds and saving the successful request ID.
   *
   * @param url inventory or message-text URL
   * @param variable variable receiving the successful request's X-Zeta-Mail-Capture header
   * @throws AssertionError if the read fails or its deadline expires
   */
  @Wenn("der E-Mail-Inhalt von {tigerResolvedString} mit begrenzter Wartezeit und Anfragekennung in Variable {string} abgerufen wird")
  public void readMail(String url, String variable) {
    var read = readMailResult(url, System.nanoTime() + MAIL_REQUEST_LIMIT.toNanos(), true);
    TigerGlobalConfiguration.putValue(variable, read.captureId(), ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Records the mail response checked in the feature and requires both enrollment OTP controls.
   */
  @Gegebensei("der E-Mail-Bestand {tigerResolvedString} vor der Löschung von Client {tigerResolvedString} ist für F1 {tigerResolvedString} erfasst")
  public void capture(String mailBody, String targetClient, String boundEmail) {
    require(boundEmail.matches("[A-Z][0-9]{9}@[A-Za-z0-9.-]+"), "Expected fresh synthetic F1");
    require(targetClient.matches("[A-Za-z0-9_.-]+"), "Expected resolved target client ID");
    email = boundEmail.toLowerCase(Locale.ROOT);
    completedNotice =
            ClientDeletionMailAssertions.noticePattern(
                    config("testdata.ccDeletionNotification.completedNoticePattern")
                            .replace("{clientId}", Pattern.quote(targetClient)));
    baseline = ClientDeletionMailAssertions.index(JSON.readTree(mailBody));
    long controls =
            baseline.values().stream()
                    .filter(m -> ClientDeletionMailAssertions.deliveredTo(m, email))
                    .filter(m -> ClientDeletionMailAssertions.OTP_SUBJECT.equals(m.path("subject").asString()))
                    .count();
    require(controls >= 2, "Both successful SDK enrollments need delivered F1 mail controls");
    SerenityReportUtils.addCustomData(
            "Pre-deletion F1 delivery controls",
            "Delivered enrollment OTPs: "
                    + controls
                    + "; F1 message IDs before deletion: "
                    + baseline.values().stream()
                    .filter(m -> ClientDeletionMailAssertions.deliveredTo(m, email))
                    .map(m -> m.path("id").asInt())
                    .sorted()
                    .toList());
  }

  /**
   * Requires a fresh F1 delivery with completed-deletion text; all mail GETs use the Tiger client.
   */
  @Dann("F1 erhält eine neue E-Mail über die ausgeführte Client-Löschung")
  public void verify() throws InterruptedException {
    require(baseline != null, "Missing pre-deletion mailbox baseline");
    int seconds = Integer.parseInt(config("testdata.ccDeletionNotification.waitSeconds"));
    require(seconds >= 1 && seconds <= 120, "Mail observation bound must be 1..120 seconds");
    String messagesUrl = config("paths.mailcatcher.messages");
    var inspected = new HashMap<Integer, Boolean>();
    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    BoundedObservation.awaitUntil(
            () -> ClientDeletionMailAssertions.index(JSON.readTree(readMailBody(messagesUrl, deadline))),
            current -> {
              for (var message : ClientDeletionMailAssertions.newDeliveries(baseline, current, email)) {
                int id = message.path("id").asInt();
                boolean matches = inspected.computeIfAbsent(
                        id,
                        ignored -> completedNotice.matcher(readMailBody(messagesUrl + "/" + id + ".plain", deadline)).matches());
                if (matches) {
                  return true;
                }
              }
              return false;
            },
            deadline,
            Duration.ofSeconds(1),
            "No new F1 email confirming the executed client deletion within the configured "
                    + seconds
                    + "-second observation bound");
    SerenityReportUtils.addCustomData(
            "Client deletion notification",
            "New delivery to the independently verified F1 with completed-deletion text; "
                    + "correlated against the unchanged pre-delete mail response. "
                    + "Mail requests and responses are recorded by Tiger.");
  }

  static String readMailBody(String url, long observationDeadline) {
    return readMailResult(url, observationDeadline, false).body();
  }

  private static MailRead readMailResult(String url, long observationDeadline, boolean correlated) {
    require(observationDeadline > System.nanoTime(), "Mail observation deadline reached before GET " + url);
    int unavailable = 0;
    while (System.nanoTime() < observationDeadline) {
      String captureId = correlated ? UUID.randomUUID().toString() : null;
      var headers = correlated ? Map.of("X-Zeta-Mail-Capture", captureId) : Map.<String, String>of();
      var body = readMailAttempt(url, observationDeadline, headers);
      if (body.isPresent()) {
        return new MailRead(body.get(), captureId);
      }
      unavailable++;
      long remaining = observationDeadline - System.nanoTime();
      if (remaining > 0) {
        try {
          TimeUnit.NANOSECONDS.sleep(Math.min(remaining, MAIL_RETRY_INTERVAL.toNanos()));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("MailCatcher GET retry interrupted: " + url, interrupted);
        }
      }
    }
    throw new AssertionError("MailCatcher GET " + url + " returned HTTP 404 on " + unavailable
            + " attempts until the capture-read deadline; mail retrieval unavailable; delivery has not been evaluated.");
  }

  /** Carries the unchanged response body and the optional request capture header. */
  private record MailRead(String body, String captureId) {}

  /** Returns an empty result only for HTTP 404; other unsuccessful reads fail immediately. */
  private static Optional<String> readMailAttempt(
      String url, long observationDeadline, Map<String, String> headers) {
    long deadline = Math.min(observationDeadline, System.nanoTime() + MAIL_REQUEST_LIMIT.toNanos());
    require(deadline > System.nanoTime(), "Mail observation deadline reached before GET " + url);
    var client = new DefaultHttpClient();
    var timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    // Socket timeouts only limit inactivity; close this request's connection also for a slow stream.
    var abort = timer.schedule(() -> client.getConnectionManager().shutdown(),
            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    try {
      int timeout = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
      var http = HttpClientConfig.httpClientConfig().httpClientFactory(() -> client)
              .setParam("http.connection.timeout", timeout)
              .setParam("http.socket.timeout", timeout)
              .setParam("http.conn-manager.timeout", (long) timeout);
      var reply = TigerHttpClient.givenDefaultSpec()
              .headers(headers)
              .config(RestAssured.config().httpClient(http)
                      .redirect(RedirectConfig.redirectConfig().followRedirects(false)))
              .get(url);
      int status = reply.statusCode();
      require(System.nanoTime() <= deadline, "MailCatcher GET exceeded its deadline: " + url);
      if (status == 404) {
        return Optional.empty();
      }
      require(
              status == 200,
              "MailCatcher GET " + url + " returned HTTP " + status
                      + "; expected 200. Mail retrieval failed; delivery has not been evaluated.");
      var body = reply.asString();
      require(System.nanoTime() <= deadline, "MailCatcher GET exceeded its deadline: " + url);
      return Optional.of(body);
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AssertionError("MailCatcher GET failed: " + url, failure);
    } finally {
      abort.cancel(false);
      client.getConnectionManager().shutdown();
      timer.shutdownNow();
    }
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
            .map(TigerGlobalConfiguration::resolvePlaceholders)
            .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
