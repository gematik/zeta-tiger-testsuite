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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.restassured.http.Method;
import io.restassured.response.Response;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Verifies correlation, driver failures, and subscription cleanup for Android push observation. */
class AndroidPushStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Selects an exact correlation despite unrelated deliveries and reordered payload fields. */
  @Test
  void selectsExactDecryptedCorrelation() {
    var body = JSON.createArrayNode();
    body.addObject().put("keyIdentifier", "own-key").put("decrypted", "{\"correlation_id\":\"own-other\"}");
    body.addObject().put("keyIdentifier", "own-key")
        .put("decrypted", "{\"message\":\"expected\",\"correlation_id\":\"own\"}");
    assertEquals("expected",
        AndroidPushSteps.correlatedPayload(body.toString(), "own", "own-key").path("message").asString());
    assertNull(AndroidPushSteps.correlatedPayload(body.toString(), "absent", "own-key"));
  }

  /** Empty buffers and absent decrypted content remain pending observations. */
  @ParameterizedTest
  @ValueSource(strings = {"[]", "[{\"keyIdentifier\":\"own-key\",\"decrypted\":null,\"error\":null}]"})
  void absentDeliveryRemainsPending(String body) {
    assertNull(AndroidPushSteps.correlatedPayload(body, "own", "own-key"));
  }

  /** Decryption failures and invalid envelopes cannot count as successful delivery. */
  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "[null]", "[{\"error\":\"bad ciphertext\"}]",
      "[{\"keyIdentifier\":\"own-key\",\"error\":\"bad ciphertext\"}]"})
  void rejectsInvalidDriverResult(String body) {
    assertThrows(AssertionError.class, () -> AndroidPushSteps.correlatedPayload(body, "own", "own-key"));
  }

  /** Invalid JSON remains a parsing failure instead of an empty receive buffer. */
  @Test
  void rejectsMalformedJson() {
    assertThrows(RuntimeException.class,
        () -> AndroidPushSteps.correlatedPayload("not JSON", "own", "own-key"));
    assertThrows(RuntimeException.class,
        () -> AndroidPushSteps.correlatedPayload(
            "[{\"keyIdentifier\":\"own-key\",\"decrypted\":\"not JSON\"}]", "own", "own-key"));
  }

  /** An old key's decryption error cannot obscure the current registration's correlated delivery. */
  @Test
  void distinguishesPreviousRegistrationFromCurrentKey() {
    var messages = JSON.createArrayNode();
    messages.addObject().put("keyIdentifier", "old-key").put("error", "AEADBadTagException");
    messages.addObject().put("keyIdentifier", "old-key")
        .put("decrypted", "{\"correlation_id\":\"own\"}");
    assertNull(AndroidPushSteps.correlatedPayload(messages.toString(), "own", "own-key"));
    messages.addObject().put("keyIdentifier", "own-key")
        .put("decrypted", "{\"correlation_id\":\"own\",\"message\":\"Grüße 🔔\"}");
    assertEquals("Grüße 🔔", AndroidPushSteps.correlatedPayload(
        messages.toString(), "own", "own-key").path("message").asString());
  }

  /** Repeated observation exposes the received payload fields as scenario-local variables. */
  @Test
  void pollsUntilCorrelatedDeliveryAndStoresPayload() throws InterruptedException {
    var key = "paths.androidClient.notifications.received";
    TigerGlobalConfiguration.putValue(key, "http://localhost/received",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      var payload = JSON.createObjectNode().put("correlation_id", "own").put("title", "Smoke");
      var messages = JSON.createArrayNode();
      messages.addObject().put("keyIdentifier", "own-key").put("decrypted", payload.toString()).putNull("error");
      var response = mock(Response.class);
      when(response.statusCode()).thenReturn(200);
      when(response.asString()).thenReturn("[]", messages.toString());
      var steps = new AndroidPushSteps((method, uri) -> response);
      steps.beginObservation(5);
      steps.awaitPush("own", "own-key", "androidUnitPayload");
      assertEquals("own", TigerGlobalConfiguration.readString("androidUnitPayload.correlation_id"));
      assertEquals("Smoke", TigerGlobalConfiguration.readString("androidUnitPayload.title"));
    } finally {
      TigerGlobalConfiguration.clearTestVariables();
    }
  }

  /** A healthy buffer without the requested event fails when its observation budget expires. */
  @Test
  void absentCorrelationTimesOut() {
    TigerGlobalConfiguration.putValue("paths.androidClient.notifications.received",
        "http://localhost/received", ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      var response = mock(Response.class);
      when(response.statusCode()).thenReturn(200);
      when(response.asString()).thenReturn("[]");
      var steps = new AndroidPushSteps((method, uri) -> response);
      steps.beginObservation(1);
      assertThrows(de.gematik.zeta.services.BoundedObservation.ObservationTimeoutException.class,
          () -> steps.awaitPush("own", "own-key", "androidUnitPayload"));
    } finally {
      TigerGlobalConfiguration.clearTestVariables();
    }
  }

  /** A receive endpoint failure is reported immediately, before another poll. */
  @Test
  void propagatesReceiveEndpointFailure() {
    var key = "paths.androidClient.notifications.received";
    TigerGlobalConfiguration.putValue(key, "http://localhost/received",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      var response = mock(Response.class);
      when(response.statusCode()).thenReturn(503);
      var requests = new ArrayList<URI>();
      var steps = new AndroidPushSteps((method, uri) -> {
        requests.add(uri);
        assertEquals(Method.GET, method);
        return response;
      });
      steps.beginObservation(1);
      assertThrows(AssertionError.class, () -> steps.awaitPush("own", "own-key", "payload"));
      assertEquals(List.of(URI.create("http://localhost/received")), requests);
    } finally {
      TigerGlobalConfiguration.clearTestVariables();
    }
  }

  /** A delivery returned after the original deadline cannot become a successful observation. */
  @Test
  void rejectsResponseCompletingAfterOriginalDeadline() {
    TigerGlobalConfiguration.putValue("paths.androidClient.notifications.received",
        "http://localhost/received", ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      var response = mock(Response.class);
      when(response.statusCode()).thenReturn(200);
      when(response.asString()).thenAnswer(invocation -> {
        Thread.sleep(1100);
        return "[{\"keyIdentifier\":\"own-key\",\"decrypted\":\"{\\\"correlation_id\\\":\\\"own\\\"}\"}]";
      });
      var steps = new AndroidPushSteps((method, uri) -> response);
      steps.beginObservation(1);
      assertThrows(de.gematik.zeta.services.BoundedObservation.ObservationTimeoutException.class,
          () -> steps.awaitPush("own", "own-key", "androidUnitPayload"));
    } finally {
      TigerGlobalConfiguration.clearTestVariables();
    }
  }

  /** Completing the absence window consumes the original budget without starting a fresh poll. */
  @Test
  void completedWindowCannotBeRestarted() throws InterruptedException {
    TigerGlobalConfiguration.putValue("paths.androidClient.notifications.received",
        "http://localhost/received", ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      var requests = new ArrayList<URI>();
      var steps = new AndroidPushSteps((method, uri) -> {
        requests.add(uri);
        throw new AssertionError("Expired observation must not issue a new receive request");
      });
      assertThrows(AssertionError.class, () -> steps.beginObservation(0));
      assertThrows(AssertionError.class, steps::finishObservation);
      steps.beginObservation(1);
      steps.finishObservation();
      assertThrows(AssertionError.class, () -> steps.beginObservation(1));
      assertThrows(de.gematik.zeta.services.BoundedObservation.ObservationTimeoutException.class,
          () -> steps.awaitPush("own", "own-key", "androidUnitPayload"));
      assertEquals(List.of(), requests);
    } finally {
      TigerGlobalConfiguration.clearTestVariables();
    }
  }

  /** UTC month and year boundaries allow only months spanned by the actual send interval. */
  @Test
  void expectedMonthsFollowTheUtcSendAndReceiptInterval() {
    assertEquals("2026-10", AndroidPushSteps.monthPattern(
        Instant.parse("2026-10-19T12:00:00Z"), Instant.parse("2026-10-19T12:00:01Z")));
    var pattern = AndroidPushSteps.monthPattern(
        Instant.parse("2026-12-31T23:59:59Z"), Instant.parse("2027-01-01T00:00:01Z"));
    assertEquals("(?:2026-12|2027-01)", pattern);
    org.junit.jupiter.api.Assertions.assertFalse("2026-11".matches(pattern));
    assertThrows(AssertionError.class, () -> AndroidPushSteps.monthPattern(
        Instant.parse("2026-10-19T12:00:01Z"), Instant.parse("2026-10-19T12:00:00Z")));
  }

  /** Cleanup retries a failed unsubscribe and releases only an explicitly owned context. */
  @Test
  void cleanupRetainsOwnershipUntilUnsubscribeSucceeds() {
    var key = "paths.androidClient.notifications.unsubscribeAll";
    TigerGlobalConfiguration.putValue(key, "http://localhost/unsubscribe",
        ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      var response = mock(Response.class);
      when(response.statusCode()).thenReturn(503, 200);
      var requests = new ArrayList<URI>();
      var steps = new AndroidPushSteps((method, uri) -> {
        requests.add(uri);
        assertEquals(Method.POST, method);
        return response;
      });
      steps.cleanup();
      assertEquals(0, requests.size());
      steps.ownContext();
      assertThrows(AssertionError.class, steps::cleanup);
      steps.cleanup();
      steps.cleanup();
      assertEquals(List.of(URI.create("http://localhost/unsubscribe"),
          URI.create("http://localhost/unsubscribe")), requests);
    } finally {
      TigerGlobalConfiguration.clearTestVariables();
    }
  }
}
