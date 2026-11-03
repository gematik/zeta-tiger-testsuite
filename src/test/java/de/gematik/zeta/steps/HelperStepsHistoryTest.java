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
import static org.mockito.Mockito.mockStatic;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.zeta.services.RecordedMessages;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests response correlation and complete request selection in recorded histories. */
class HelperStepsHistoryTest {
  private final HelperSteps steps = new HelperSteps();

  /** Accepts denied retries while excluding other methods and paths, regardless of message order. */
  @Test
  void acceptsEveryDeniedAttemptAndIgnoresUnrelatedSuccesses() {
    var messages = new ArrayList<RbelElement>();
    messages.addAll(exchange("GET", "/push/v1/pushers", 401));
    messages.addAll(exchange("GET", "/push/v1/pushers", 403));
    messages.addAll(exchange("GET", "/other", 200));
    messages.addAll(exchange("POST", "/push/v1/pushers", 200));
    Collections.reverse(messages);
    try (var history = mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(messages);
      assertDoesNotThrow(() -> steps.verifyRecordedResponseStatuses(
          "GET", "/push/v1/pushers", "401|403"));
      assertDoesNotThrow(() -> steps.verifyRecordedResponseStatuses(
          "GET", "/push/v1/pushers/?", "401|403"));
    }
  }

  /** A permitted rejection cannot conceal a successful, redirected or failing attempt. */
  @ParameterizedTest
  @CsvSource({"401,200", "403,200", "200,401", "401,302", "401,500"})
  void rejectsAnyAttemptOutsideThePermittedStatuses(int firstStatus, int secondStatus) {
    var messages = new ArrayList<>(exchange("GET", "/push/v1/pushers", firstStatus));
    messages.addAll(exchange("GET", "/push/v1/pushers", secondStatus));
    try (var history = mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(messages);
      assertThrows(AssertionError.class, () -> steps.verifyRecordedResponseStatuses(
          "GET", "/push/v1/pushers", "401|403"));
    }
  }

  /** A response belonging to a different request cannot answer an unfinished target request. */
  @Test
  void rejectsUnansweredAttemptsDespiteAnUnrelatedRejection() {
    var messages = new ArrayList<>(exchange("GET", "/push/v1/pushers", 401));
    messages.add(message("GET /push/v1/pushers HTTP/1.1\r\nHost: example.test\r\n\r\n"));
    messages.addAll(exchange("GET", "/other", 403));
    try (var history = mockStatic(RecordedMessages.class)) {
      history.when(RecordedMessages::snapshot).thenReturn(messages);
      assertThrows(AssertionError.class, () -> steps.verifyRecordedResponseStatuses(
          "GET", "/push/v1/pushers", "401|403"));
    }
  }

  /** Histories without a matching request do not establish a response contract. */
  @Test
  void rejectsEmptyOrUnmatchedHistories() {
    for (var messages : List.of(List.<RbelElement>of(), exchange("POST", "/push/v1/pushers", 403),
        exchange("GET", "/push/v1/pushers/other", 403))) {
      try (var history = mockStatic(RecordedMessages.class)) {
        history.when(RecordedMessages::snapshot).thenReturn(messages);
        assertThrows(AssertionError.class, () -> steps.verifyRecordedResponseStatuses(
            "GET", "/push/v1/pushers", "401|403"));
      }
    }
  }

  /** Creates HTTP messages with a shared request/response correlation facet. */
  private static List<RbelElement> exchange(String method, String path, int status) {
    var request = message(method + " " + path + " HTTP/1.1\r\nHost: example.test\r\n\r\n");
    var response = message("HTTP/1.1 " + status + " Status\r\nContent-Length: 0\r\n\r\n");
    var pair = TracingMessagePairFacet.builder().request(request).responses(List.of(response)).build();
    request.addFacet(pair);
    response.addFacet(pair);
    return List.of(request, response);
  }

  /** Parses one HTTP message using the installed RBEL parser. */
  private static RbelElement message(String wire) {
    return RbelLogger.build().getRbelConverter().parseMessage(
        wire.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }
}
