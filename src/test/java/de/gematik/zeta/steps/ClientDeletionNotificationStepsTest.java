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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientDeletionNotificationStepsTest {
  private static final String URL = "http://zetaClient/mailcatcher/messages";

  @ParameterizedTest
  @ValueSource(strings = {"", "/3.plain"})
  void correlatedReadPublishesOnlyTheSuccessfulAttemptAfter404(String suffix) throws Exception {
    var captures = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String path = "/mailcatcher/messages" + suffix;
    server.createContext(path, exchange -> {
      captures.add(exchange.getRequestHeaders().getFirst("X-Zeta-Mail-Capture"));
      if (captures.size() == 1) {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
      } else {
        byte[] body = "captured content".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
          output.write(body);
        }
      }
    });
    server.start();
    var originalConfig = RestAssured.config;
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenAnswer(call -> RestAssured.given());
      new ClientDeletionNotificationSteps().readMail(
          "http://127.0.0.1:" + server.getAddress().getPort() + path, "CC_MAIL_CAPTURE");
      assertEquals(2, captures.size());
      assertTrue(captures.stream().allMatch(id -> id != null && !id.isBlank()));
      assertEquals(2, captures.stream().distinct().count());
      config.verify(() -> TigerGlobalConfiguration.putValue(
          "CC_MAIL_CAPTURE", captures.getLast(), ConfigurationValuePrecedence.TEST_CONTEXT));
      config.verifyNoMoreInteractions();
      assertSame(originalConfig, RestAssured.config);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void correlatedReadStopsPersistent404AtTheOriginalFiveSecondDeadline() {
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var missing = mock(Response.class);
    when(request.get(URL)).thenReturn(missing);
    when(missing.statusCode()).thenReturn(404);
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      long started = System.nanoTime();
      var failure = assertThrows(AssertionError.class,
          () -> new ClientDeletionNotificationSteps().readMail(URL, "CC_MAIL_CAPTURE"));
      long elapsed = System.nanoTime() - started;
      assertTrue(elapsed >= Duration.ofSeconds(5).toNanos());
      assertTrue(elapsed < Duration.ofSeconds(8).toNanos());
      assertTrue(failure.getMessage().contains("HTTP 404"));
      assertFalse(failure.getMessage().contains("No new F1 email"));
      config.verifyNoInteractions();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 401, 403, 500})
  void correlatedReadDoesNotRetryOtherFailuresOrPublishTheirCapture(int status) {
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var response = mock(Response.class);
    when(request.get(URL)).thenReturn(response);
    when(response.statusCode()).thenReturn(status);
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      var failure = assertThrows(AssertionError.class,
          () -> new ClientDeletionNotificationSteps().readMail(URL, "CC_MAIL_CAPTURE"));
      assertTrue(failure.getMessage().contains("HTTP " + status));
      verify(request).get(URL);
      config.verifyNoInteractions();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 401, 403, 404, 500})
  void unsuccessfulTigerReadsCannotBeReportedAsMissingOrDeliveredMail(int status) {
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var response = mock(Response.class);
    when(request.get(URL)).thenReturn(response);
    when(response.statusCode()).thenReturn(status);
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      var failure = assertThrows(AssertionError.class,
          () -> ClientDeletionNotificationSteps.readMailBody(
              URL, System.nanoTime() + Duration.ofMillis(200).toNanos()));
      assertTrue(failure.getMessage().contains(URL));
      assertTrue(failure.getMessage().contains("HTTP " + status));
      assertFalse(failure.getMessage().contains("No new F1 email"));
      verify(request).get(URL);
    }
  }

  @Test
  void successfulTigerReadReturnsTheUnchangedMailResponse() {
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var response = mock(Response.class);
    when(request.get(URL)).thenReturn(response);
    when(response.statusCode()).thenReturn(200);
    when(response.asString()).thenReturn("[]");
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      assertEquals("[]", readMailBody(URL));
      verify(request).get(URL);
    }
  }

  @Test
  void tigerTransportFailureRetainsItsCause() {
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var cause = new IllegalStateException("Connection refused");
    when(request.get(URL)).thenThrow(cause);
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      var failure = assertThrows(AssertionError.class,
          () -> readMailBody(URL));
      assertSame(cause, failure.getCause());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void verifiesFreshDeletionMailThroughTheSharedTigerRoute(boolean transient404) throws InterruptedException {
    String baseline = otpBaseline();
    String after = baseline.strip().replace("]\n", "]");
    after = after.substring(0, after.length() - 1)
        + ",{\"id\":3,\"subject\":\"Client deleted\",\"recipients\":[\"<I329317829@mailgun.com>\"]}]";
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var metadata = mock(Response.class);
    var text = mock(Response.class);
    when(request.get(URL)).thenReturn(metadata);
    when(request.get(URL + "/3.plain")).thenReturn(text);
    when(metadata.statusCode()).thenReturn(200);
    when(metadata.asString()).thenReturn(after);
    when(text.statusCode()).thenReturn(200);
    when(text.asString()).thenReturn("Your client was deleted.");
    if (transient404) {
      var missing = mock(Response.class);
      when(missing.statusCode()).thenReturn(404);
      when(request.get(URL)).thenReturn(missing, metadata);
      when(request.get(URL + "/3.plain")).thenReturn(missing, text);
    }
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var reports = mockStatic(SerenityReportUtils.class);
        var tiger = mockStatic(TigerHttpClient.class)) {
      for (var entry : java.util.Map.of(
          "testdata.ccDeletionNotification.completedNoticePattern", "Your client was deleted\\.",
          "testdata.ccDeletionNotification.waitSeconds", "4",
          "paths.mailcatcher.messages", URL).entrySet()) {
        config.when(() -> TigerGlobalConfiguration.readStringOptional(entry.getKey()))
            .thenReturn(Optional.of(entry.getValue()));
        config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(entry.getValue()))
            .thenReturn(entry.getValue());
      }
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      var steps = new ClientDeletionNotificationSteps();
      steps.capture(baseline, "target-client", "I329317829@mailgun.com");
      steps.verify();
      verify(request, times(transient404 ? 2 : 1)).get(URL);
      verify(request, times(transient404 ? 2 : 1)).get(URL + "/3.plain");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/3.plain"})
  void retries404ForInventoryAndTextWithinTheGivenDeadline(String suffix) {
    String url = URL + suffix;
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var missing = mock(Response.class);
    var available = mock(Response.class);
    when(missing.statusCode()).thenReturn(404);
    when(available.statusCode()).thenReturn(200);
    when(available.asString()).thenReturn("captured content");
    when(request.get(url)).thenReturn(missing, available);
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      assertEquals("captured content", ClientDeletionNotificationSteps.readMailBody(
          url, System.nanoTime() + Duration.ofSeconds(3).toNanos()));
      verify(request, times(2)).get(url);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/3.plain"})
  void persistent404DoesNotRestartTheDeadline(String suffix) {
    String url = URL + suffix;
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var missing = mock(Response.class);
    when(missing.statusCode()).thenReturn(404);
    when(request.get(url)).thenReturn(missing);
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      long started = System.nanoTime();
      long deadline = started + Duration.ofMillis(1500).toNanos();
      var failure = assertThrows(AssertionError.class,
          () -> ClientDeletionNotificationSteps.readMailBody(url, deadline));
      assertTrue(System.nanoTime() >= deadline);
      assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
      assertTrue(failure.getMessage().contains("HTTP 404"));
      assertTrue(failure.getMessage().contains("deadline"));
      assertFalse(failure.getMessage().contains("No new F1 email"));
      verify(request, times(2)).get(url);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void distinguishesUnavailableCaptureFromMissingNotification(boolean unavailable) {
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var metadata = mock(Response.class);
    when(request.get(URL)).thenReturn(metadata);
    when(metadata.statusCode()).thenReturn(unavailable ? 404 : 200);
    when(metadata.asString()).thenReturn(otpBaseline());
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var reports = mockStatic(SerenityReportUtils.class);
        var tiger = mockStatic(TigerHttpClient.class)) {
      for (var entry : java.util.Map.of(
          "testdata.ccDeletionNotification.completedNoticePattern", "Your client was deleted\\.",
          "testdata.ccDeletionNotification.waitSeconds", "1",
          "paths.mailcatcher.messages", URL).entrySet()) {
        config.when(() -> TigerGlobalConfiguration.readStringOptional(entry.getKey()))
            .thenReturn(Optional.of(entry.getValue()));
        config.when(() -> TigerGlobalConfiguration.resolvePlaceholders(entry.getValue()))
            .thenReturn(entry.getValue());
      }
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      var steps = new ClientDeletionNotificationSteps();
      steps.capture(otpBaseline(), "target-client", "I329317829@mailgun.com");
      var failure = assertThrows(AssertionError.class, steps::verify);
      assertEquals(!unavailable, failure.getMessage().contains("No new F1 email"));
      assertEquals(unavailable, failure.getMessage().contains("HTTP 404"));
    }
  }

  @Test
  void interruptionStops404RetriesAndPreservesTheInterruptFlag() {
    var request = mock(RequestSpecification.class, RETURNS_SELF);
    var missing = mock(Response.class);
    when(request.get(URL)).thenReturn(missing);
    when(missing.statusCode()).thenReturn(404);
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenReturn(request);
      Thread.currentThread().interrupt();
      var failure = assertThrows(AssertionError.class,
          () -> readMailBody(URL));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(failure.getCause() instanceof InterruptedException);
      verify(request).get(URL);
    } finally {
      Thread.interrupted();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/mailcatcher/messages", "/mailcatcher/messages/3.plain"})
  void actualRedirectIsRejectedWithoutFetchingItsTarget(String path) throws Exception {
    var redirected = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(path, exchange -> {
      exchange.getResponseHeaders().set("Location", "/other-capture");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/other-capture", exchange -> {
      redirected.incrementAndGet();
      byte[] body = "Your client was deleted.".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    var originalConfig = RestAssured.config;
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenAnswer(call -> RestAssured.given());
      var failure = assertThrows(AssertionError.class, () -> readMailBody(
          "http://127.0.0.1:" + server.getAddress().getPort() + path));
      assertTrue(failure.getMessage().contains("HTTP 302"));
      assertEquals(0, redirected.get());
      assertSame(originalConfig, RestAssured.config);
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void mailDeadlineStopsStalledBodiesAndContinuousSlowStreams(boolean stream) throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/warm", exchange -> {
      exchange.sendResponseHeaders(200, 2);
      try (var output = exchange.getResponseBody()) {
        output.write("[]".getBytes(StandardCharsets.UTF_8));
      }
    });
    server.createContext("/slow", exchange -> {
      exchange.sendResponseHeaders(200, 100);
      entered.countDown();
      try (var output = exchange.getResponseBody()) {
        if (stream) {
          for (int i = 0; i < 100; i++) {
            output.write(' ');
            output.flush();
            Thread.sleep(30);
          }
        } else {
          release.await(5, TimeUnit.SECONDS);
        }
      } catch (java.io.IOException expectedDisconnect) {
        // The client aborts its incomplete response at the request deadline.
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    try (var tiger = mockStatic(TigerHttpClient.class)) {
      tiger.when(TigerHttpClient::givenDefaultSpec).thenAnswer(call -> RestAssured.given());
      assertEquals("[]", readMailBody(base + "/warm"));
      long start = System.nanoTime();
      var failure = assertThrows(AssertionError.class, () -> ClientDeletionNotificationSteps.readMailBody(
          base + "/slow", start + Duration.ofMillis(500).toNanos()));
      assertEquals(0, entered.getCount(), "Fixture must accept the request before timeout");
      assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
      assertTrue(failure.getMessage().contains("MailCatcher GET"));
      assertFalse(failure.getMessage().contains("No new F1 email"));
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test
  void baselineUsesTheCapturedResponseAndRequiresTwoOtpDeliveriesToBoundF1() {
    String body = """
        [{"id":1,"subject":"Your email verification code","recipients":["<I329317829@mailgun.com>"]},
         {"id":2,"subject":"Your email verification code","recipients":["<i329317829@mailgun.com>"]}]
        """;
    try (var config = mockStatic(TigerGlobalConfiguration.class);
        var reports = mockStatic(SerenityReportUtils.class);
        var tiger = mockStatic(TigerHttpClient.class)) {
      config.when(() -> TigerGlobalConfiguration.readStringOptional(
          "testdata.ccDeletionNotification.completedNoticePattern"))
          .thenReturn(Optional.of("Your client was deleted\\."));
      config.when(() -> TigerGlobalConfiguration.resolvePlaceholders("Your client was deleted\\."))
          .thenReturn("Your client was deleted\\.");
      new ClientDeletionNotificationSteps().capture(body, "target-client", "I329317829@mailgun.com");
      assertThrows(AssertionError.class,
          () -> new ClientDeletionNotificationSteps().capture(
              body.replace("<i329317829@mailgun.com>", "<foreign@mailgun.com>"),
              "target-client", "I329317829@mailgun.com"));
      tiger.verifyNoInteractions();
    }
  }

  private static String otpBaseline() {
    return """
        [{"id":1,"subject":"Your email verification code","recipients":["<I329317829@mailgun.com>"]},
         {"id":2,"subject":"Your email verification code","recipients":["<I329317829@mailgun.com>"]}]
        """;
  }

  private static String readMailBody(String url) {
    return ClientDeletionNotificationSteps.readMailBody(
        url, System.nanoTime() + Duration.ofSeconds(5).toNanos());
  }
}
