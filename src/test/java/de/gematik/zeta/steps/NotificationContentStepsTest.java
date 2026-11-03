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

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.zeta.services.RecordedMessages;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/** Regression checks for message disclosure outside the selected encrypted request. */
class NotificationContentStepsTest {
  @Test
  void rejectsAnAdditionalUnansweredPlaintextRequest() {
    var encrypted = request("/push/v1/notifyEncrypted/batch", "{\"ciphertext\":\"opaque\"}");
    var leaked = request("/push/v1/notify", "{\"content\":\"own-message-marker\"}");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(encrypted, leaked));
      org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
          () -> new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
              ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}"));
    }
  }

  @Test
  void rejectsAVacuousGatewayHistory() {
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of());
      org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
          () -> new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
              ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsMessageCopiesInHeaders(boolean encoded) {
    var marker = "own-message-marker";
    var value = encoded ? java.util.Base64.getEncoder().encodeToString(
        marker.getBytes(StandardCharsets.UTF_8)) : marker;
    var leaked = request("/push/v1/notifyEncrypted/batch", "{\"ciphertext\":\"opaque\"}",
        "X-Message-Copy: " + value + "\r\n");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(leaked));
      org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
          () -> new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
              ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}"));
    }
  }

  @Test
  void rejectsAnEncodedMessageCopyInTheRequestTarget() {
    var leaked = request("/push/v1/notify?copy=own%2Dmessage%2Dmarker",
        "{\"ciphertext\":\"opaque\"}");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(leaked));
      org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
          () -> new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
              ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"base64-plain", "base64", "base64url", "hex", "percent-base64",
      "repeated-first", "repeated-last"})
  void rejectsEncodedMessageCopiesInQueryValues(String encoding) {
    var marker = encoding.equals("base64-plain") ? "own-message-marker" : "own-message-marker~~~?";
    var bytes = marker.getBytes(StandardCharsets.UTF_8);
    var base64 = Base64.getEncoder().encodeToString(bytes);
    var queryValue = URLEncoder.encode(base64, StandardCharsets.UTF_8);
    var query = switch (encoding) {
      case "base64-plain" -> "copy=" + base64;
      case "base64" -> "copy=" + queryValue;
      case "base64url" -> "copy=" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      case "hex" -> "copy=" + HexFormat.of().formatHex(bytes);
      case "percent-base64" -> "copy=" + base64.chars()
          .mapToObj(value -> "%%%02X".formatted(value)).collect(Collectors.joining());
      case "repeated-first" -> "copy=" + queryValue + "&copy=safe";
      case "repeated-last" -> "copy=safe&copy=" + queryValue;
      default -> throw new IllegalArgumentException("Unknown encoding: " + encoding);
    };
    var encrypted = request("/push/v1/notifyEncrypted/batch", "{\"ciphertext\":\"opaque\"}");
    var leaked = request("GET", "/push/v1/notify?" + query, "", "");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(encrypted, leaked));
      org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
          () -> new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
              ".*/push/v1/notify.*", "{\"message\":\"" + marker + "\"}"));
    }
  }

  @Test
  void allowsAnOpaqueEnvelopeWithUnrelatedMetadata() {
    var encrypted = request("/push/v1/notifyEncrypted/batch", "{\"ciphertext\":\"opaque\"}",
        "X-Request-Id: unrelated-request\r\n");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(encrypted));
      new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
          ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "PUT"})
  void rejectsEncodedCopiesInOtherHttpMethods(String method) {
    var encrypted = request("/push/v1/notifyEncrypted/batch", "{\"ciphertext\":\"opaque\"}");
    var leaked = request(method, "/push/v1/notify?copy=own%2Dmessage%2Dmarker", "", "");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(encrypted, leaked));
      org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
          () -> new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
              ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}"));
    }
  }

  @Test
  void rejectsAnAdditionalPlainTextBody() {
    var leaked = request("/push/v1/notify", "own-message-marker");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(leaked));
      org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
          () -> new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
              ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"probe=ready", "copy=b3BhcXVl", "copy=6f7061717565",
      "copy=safe&copy=b3BhcXVl", "copy=b3BhcXVl&copy=safe", "copy=&copy=safe"})
  void allowsAnEmptyGetWithoutMessageContent(String query) {
    var encrypted = request("/push/v1/notifyEncrypted/batch", "{\"ciphertext\":\"opaque\"}");
    var empty = request("GET", "/push/v1/notify?" + query, "", "");
    try (var history = Mockito.mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(List.of(encrypted, empty));
      new NotificationContentSteps().assertNoPlaintextInGatewayHistory(
          ".*/push/v1/notify.*", "{\"message\":\"own-message-marker\"}");
    }
  }

  /**
   * Parses one request without assigning any response.
   *
   * @param path request path
   * @param body JSON body
   * @return parsed request
   */
  private static RbelElement request(String path, String body) {
    return request(path, body, "");
  }

  /**
   * Parses one request with additional transport headers and no assigned response.
   *
   * @param path request target
   * @param body JSON body
   * @param headers additional HTTP header lines
   * @return parsed request
   */
  private static RbelElement request(String path, String body, String headers) {
    return request("POST", path, body, headers);
  }

  /**
   * Parses an HTTP request with the supplied method and no assigned response.
   *
   * @param method HTTP method
   * @param path request target
   * @param body captured body text
   * @param headers additional HTTP header lines
   * @return parsed request
   */
  private static RbelElement request(String method, String path, String body, String headers) {
    var http = method + " " + path + " HTTP/1.1\r\nHost: gateway.test\r\n"
        + headers
        + "Content-Type: application/json\r\nContent-Length: "
        + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    return RbelLogger.build().getRbelConverter().parseMessage(
        http.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }
}
