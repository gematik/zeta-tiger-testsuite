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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class NotificationTlsStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private ObjectNode positive() {
    var row =
        JSON.createObjectNode()
            .put("peer", "10.0.0.1")
            .put("handshake", true)
            .put("client_sha", "client")
            .put("server_sha", "server")
            .put("offered_server_sha", "server")
            .put("failure", "");
    row.put("request_base64", encoded("POST /push/v1/notify/batch HTTP/1.1"));
    row.put(
        "body_base64",
        encoded(
            """
{"notifications":[{"id":"id","notification":{"content":{"correlation_id":"marker",
"event_type":"registered_client_activity"},"devices":[{"pushkey":"key","app_id":"de.gematik.zeta.ta29982.mtls"}]}}]}
        """));
    return row;
  }

  private void check(ObjectNode row, String client, String server) {
    NotificationTlsSteps.verifyObservation(
        row,
        "10.0.0.1",
        "POST /push/v1/notify/batch HTTP/1.1",
        "client",
        "server",
        client,
        server,
        "id",
        "marker",
        "key");
  }

  @Test
  void requiresBothActualIdentitiesAndTheCompleteOriginalEventBinding() {
    assertDoesNotThrow(() -> check(positive(), "good", "good"));
    for (var field :
        java.util.List.of(
            "peer",
            "client_sha",
            "server_sha",
            "offered_server_sha",
            "failure",
            "request_base64")) {
      var row = positive().put(field, field.equals("request_base64") ? encoded("wrong") : "wrong");
      assertThrows(AssertionError.class, () -> check(row, "good", "good"), field);
    }
    for (var value :
        java.util.List.of(
            "id", "marker", "key", "de.gematik.zeta.ta29982.mtls", "registered_client_activity")) {
      var row = positive();
      var body =
          new String(
              Base64.getDecoder().decode(row.path("body_base64").asString()),
              StandardCharsets.UTF_8);
      row.put("body_base64", encoded(body.replace("\"" + value + "\"", "\"foreign\"")));
      assertThrows(AssertionError.class, () -> check(row, "good", "good"), value);
    }
  }

  @Test
  void rejectionRequiresAnActualFailedHandshakeWithNoHttpAndTheExpectedClientProof() {
    var row =
        JSON.createObjectNode()
            .put("peer", "10.0.0.1")
            .put("handshake", false)
            .put("client_sha", "client")
            .put("server_sha", "")
            .put("offered_server_sha", "server")
            .put("failure", "TLS_AUTHENTICATION_FAILED")
            .put("failure_reason", "CLIENT_CERTIFICATE_REJECTED")
            .put("failure_source", "gateway")
            .put("request_base64", "")
            .put("body_base64", "");
    assertDoesNotThrow(() -> check(row, "foreign", "good"));
    assertThrows(AssertionError.class, () -> check(row, "missing", "good"));
    assertThrows(
        AssertionError.class,
        () -> check(row.deepCopy().put("handshake", true), "foreign", "good"));
    assertThrows(
        AssertionError.class,
        () -> check(row.deepCopy().put("body_base64", encoded("body")), "foreign", "good"));
    assertThrows(
        AssertionError.class,
        () -> check(row.deepCopy().put("failure", "SocketTimeoutException"), "foreign", "good"));
    row.put("client_sha", "");
    assertThrows(AssertionError.class, () -> check(row, "missing", "good"));
    row.put("failure_reason", "CLIENT_CERTIFICATE_MISSING");
    assertDoesNotThrow(() -> check(row, "missing", "good"));
    assertThrows(AssertionError.class, () -> check(row, "good", "foreign"));
    row.put("failure_reason", "SERVER_CERTIFICATE_UNTRUSTED").put("failure_source", "deployed NS");
    assertDoesNotThrow(() -> check(row, "good", "foreign"));
    assertThrows(AssertionError.class, () -> check(row, "good", "wrong-host"));
    row.put("failure_reason", "SERVER_HOSTNAME_MISMATCH");
    assertDoesNotThrow(() -> check(row, "good", "wrong-host"));
    assertThrows(AssertionError.class, () -> check(row, "good", "foreign"));
    assertThrows(AssertionError.class, () -> check(row, "good", "missing"));
    for (var field : java.util.List.of("failure_reason", "failure_source")) {
      assertThrows(AssertionError.class,
          () -> check(row.deepCopy().put(field, ""), "good", "wrong-host"));
    }
    assertThrows(AssertionError.class,
        () -> check(row.deepCopy().put("failure_source", "gateway"), "good", "wrong-host"));
  }
}
