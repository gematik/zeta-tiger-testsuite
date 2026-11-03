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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class NotificationRsTlsStepsTest {
  private static final String IDENTITY = "X110411675";
  private static final String PRINCIPAL = "CN=technical-rs";
  private static final String QUERY_URI = "/users/" + IDENTITY + "/channels?id_type=kvnr";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void correlatesBothOperationsWithTheirCompleteUriAndTechnicalIdentity() {
    for (boolean get : List.of(true, false)) {
      assertDoesNotThrow(() -> check(row(get), access(get), get));
    }
  }

  @Test
  void rejectsOmittedOrChangedQueryPrincipalCorrelationAndDuplicateEntries() {
    String log = access(true);
    for (String invalid : List.of(
        log.replace("?id_type=kvnr", ""),
        log.replace("id_type=kvnr", "id_type=email"),
        log.replace(PRINCIPAL, "CN=foreign"),
        log.replace("marker-get", "foreign-get"),
        log + "\n" + log)) {
      assertThrows(AssertionError.class, () -> check(row(true), invalid, true));
    }
  }

  @Test
  void accessSuccessCannotReplaceTheActualPeerAndCertificateEvidence() {
    for (String field : List.of("peer", "client_sha", "server_sha")) {
      assertThrows(AssertionError.class,
          () -> check(row(true).put(field, "foreign"), access(true), true));
    }
    assertThrows(AssertionError.class,
        () -> check(row(true).put("handshake", false), access(true), true));
  }

  @Test
  void distinguishesPeerMismatchFromHandshakeAndServerCertificateFailures() {
    var wrongPeer = assertThrows(AssertionError.class,
        () -> check(row(true).put("peer", "10.0.0.99"), access(true), true));
    assertTrue(wrongPeer.getMessage().contains("10.0.0.99"));
    assertTrue(wrongPeer.getMessage().contains("verified NS TCP service 10.0.0.1"));
    var wrongServer = assertThrows(AssertionError.class,
        () -> check(row(true).put("server_sha", "foreign"), access(true), true));
    assertTrue(wrongServer.getMessage().contains("server certificate"));
    var failedTls = assertThrows(AssertionError.class,
        () -> check(row(true).put("handshake", false), access(true), true));
    assertTrue(failedTls.getMessage().contains("did not complete validated TLS"));
  }

  private static ObjectNode row(boolean get) {
    String request = get ? "GET " + QUERY_URI : "POST /notifications";
    String body = get ? """
        {"user":{"id_type":"kvnr","value":"X110411675"},"has_registered_devices":true,
        "registered_device_count":1,"channels":[{"id":"zeta","status":"enabled"}]}
        """ : """
        {"notification_id":"12345678-1234-1234-1234-123456789012","status":"accepted",
        "channel":"zeta","accepted_at":"1970-01-01T00:00:01Z"}
        """;
    return JSON.createObjectNode().put("peer", "10.0.0.1").put("handshake", true)
        .put("failure", "").put("client_sha", "client").put("server_sha", "server")
        .put("status", get ? 200 : 202)
        .put("request_base64", encoded(request + " HTTP/1.1"))
        .put("body_base64", encoded(body));
  }

  private static String access(boolean get) {
    return "INFO CC_NS_RS " + (get ? "GET " + QUERY_URI + " 200" : "POST /notifications 202")
        + " " + PRINCIPAL + " marker-" + (get ? "get" : "post");
  }

  private static void check(ObjectNode row, String logs, boolean get) {
    NotificationRsTlsSteps.verifyOperation(row, logs, get, IDENTITY, "marker", "10.0.0.1",
        "client", "server", PRINCIPAL, 1000, 1100);
  }

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
