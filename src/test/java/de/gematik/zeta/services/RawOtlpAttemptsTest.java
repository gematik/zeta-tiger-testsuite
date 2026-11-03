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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

import de.gematik.rbellogger.RbelConverter;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Request identity, response completeness and trace-context fixtures. */
class RawOtlpAttemptsTest {
  private static final String TRACE = "a".repeat(32);
  private static final String TARGET = "/pep/resource";
  private final RbelConverter converter = RbelLogger.build().getRbelConverter();

  @Test
  void everyActualAttemptRetainsItsOwnObservedParent() {
    var first = attempt(context("b"), "GET", TARGET, 401, "");
    var second = attempt(context("c"), "GET", TARGET, 401, "");
    var messages = new ArrayList<>(first);
    messages.addAll(second);
    var actual = read(messages);
    assertEquals(2, actual.size());
    assertEquals("b".repeat(16), actual.get(0).parentId());
    assertEquals("c".repeat(16), actual.get(1).parentId());
    assertEquals(TRACE, actual.get(1).traceId());
    assertEquals("GET", actual.get(1).method());
    assertEquals(TARGET, actual.get(1).target());
    assertEquals(401, actual.get(1).status());
  }

  @Test
  void readsTheConfiguredTraceContextNode() {
    var messages = attempt(context("b"), "GET", TARGET, 401,
        "X-Test-Trace: " + context("c") + "\r\n");
    var operations = read(messages, Map.of("headers.traceparent.root", "$.header.X-Test-Trace"));
    assertEquals("c".repeat(16), operations.getFirst().parentId());
  }

  @Test
  void rejectsAuthorizationAtTheConfiguredNode() {
    var messages = attempt(context("b"), "GET", TARGET, 401,
        "X-Test-Authorization: DPoP credential\r\n");
    assertThrows(AssertionError.class, () -> read(messages,
        Map.of("headers.authorization.root", "$.header.X-Test-Authorization")));
  }

  @Test
  void rejectsAnEmptyConfiguredNode() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "");
    assertThrows(AssertionError.class, () -> read(messages, Map.of("body.path", "")));
  }

  @Test
  void repeatedSnapshotMembershipDoesNotCreateAnotherAttempt() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "");
    assertEquals(1, read(List.of(messages.get(0), messages.get(1), messages.get(0))).size());
  }

  @Test
  void distinctRequestsCannotReuseTheSameParent() {
    var messages = new ArrayList<>(attempt(context("b"), "GET", TARGET, 401, ""));
    messages.addAll(attempt(context("b"), "GET", TARGET, 401, ""));
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @Test
  void requestWithoutAResponseCannotDisappear() {
    var request = request(context("b"), "GET", TARGET, "");
    assertThrows(AssertionError.class, () -> read(List.of(request)));
    var pair = TracingMessagePairFacet.builder().request(request).build();
    request.addOrReplaceFacet(pair);
    assertThrows(AssertionError.class, () -> read(List.of(request)));
  }

  @Test
  void responseMustBelongToTheSuppliedSnapshot() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "");
    assertThrows(AssertionError.class, () -> read(List.of(messages.getFirst())));
  }

  @Test
  void incompleteResponseSequenceCannotProvideAnAttempt() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "");
    messages.getFirst().getFacet(TracingMessagePairFacet.class).orElseThrow()
        .markAsResponseIncomplete();
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @Test
  void responseCorrelationCannotSelectAnotherRequest() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "");
    var other = request(context("c"), "GET", TARGET, "");
    messages.get(1).addOrReplaceFacet(
        TracingMessagePairFacet.builder().request(other).responses(List.of(messages.get(1))).build());
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @Test
  void requestCorrelationCannotReferToAnotherRequest() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "");
    var other = request(context("c"), "GET", TARGET, "");
    messages.getFirst().addOrReplaceFacet(
        TracingMessagePairFacet.builder().request(other).responses(List.of(messages.get(1))).build());
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @Test
  void informationalResponseDoesNotReplaceTheTerminalResponse() {
    var request = request(context("b"), "GET", TARGET, "");
    var informational = response(100);
    var terminal = response(401);
    pair(request, List.of(informational, terminal));
    assertEquals(1, read(List.of(request, informational, terminal)).size());
  }

  @Test
  void multipleTerminalResponsesAreAmbiguous() {
    var request = request(context("b"), "GET", TARGET, "");
    var first = response(401);
    var second = response(401);
    pair(request, List.of(first, second));
    assertThrows(AssertionError.class, () -> read(List.of(request, first, second)));
  }

  @ParameterizedTest
  @CsvSource({"POST,/pep/resource,401", "GET,/pep/other,401", "GET,/pep/resource,200"})
  void actualHttpMetadataMustMatchTheIndependentOperation(String method, String target, int status) {
    var messages = attempt(context("b"), method, target, status, "");
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @ParameterizedTest
  @ValueSource(strings = {"00", "01", "wrong-version", "zero-parent", "invalid-flags", "extra-field"})
  void malformedOrUnusableMatchingContextFails(String variant) {
    var context = switch (variant) {
      case "00", "01" -> variant + "-" + TRACE;
      case "wrong-version" -> "01-" + TRACE + "-" + "b".repeat(16) + "-01";
      case "zero-parent" -> "00-" + TRACE + "-" + "0".repeat(16) + "-01";
      case "invalid-flags" -> "00-" + TRACE + "-" + "b".repeat(16) + "-gg";
      default -> context("b") + "-extra";
    };
    var messages = attempt(context, "GET", TARGET, 401, "");
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @Test
  void competingTraceparentHeadersAreAmbiguous() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "traceparent: " + context("c") + "\r\n");
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @Test
  void rejectedAttemptMustHaveNoAuthorizationHeader() {
    var messages = attempt(context("b"), "GET", TARGET, 401, "Authorization: DPoP value\r\n");
    assertThrows(AssertionError.class, () -> read(messages));
  }

  @Test
  void unrelatedTrafficCannotProvideOrChangeTheIndependentAttempts() {
    var unrelated = attempt("00-" + "d".repeat(32) + "-" + "e".repeat(16) + "-01", "GET", TARGET, 401, "");
    var upstream = attempt(context("f"), "GET", "/resource", 401, "");
    var messages = new ArrayList<>(unrelated);
    messages.addAll(upstream);
    assertThrows(AssertionError.class, () -> read(messages));
    messages.addAll(attempt(context("b"), "GET", TARGET, 401, ""));
    assertEquals(1, read(messages).size());
    assertThrows(AssertionError.class, () -> read(List.of()));
  }

  @Test
  void standaloneJsonObjectCannotProvideAnHttpRequest() {
    var message = parse("{\"method\":\"GET\",\"path\":\"/pep/resource\",\"header\":{\"traceparent\":\""
        + context("b") + "\"}}");
    assertThrows(AssertionError.class, () -> read(List.of(message)));
  }

  private List<RawOtlpTraceAssertions.Operation> read(List<RbelElement> messages) {
    return read(messages, Map.of());
  }

  private List<RawOtlpTraceAssertions.Operation> read(List<RbelElement> messages,
      Map<String, String> overrides) {
    var nodes = new HashMap<>(Map.of(
        "headers.traceparent.root", "$.header.[~'traceparent']",
        "headers.authorization.root", "$.header.[~'Authorization']",
        "body.method", "$.method",
        "body.path", "$.path",
        "body.responseCode", "$.responseCode"));
    nodes.putAll(overrides);
    try (var config = mockStatic(TigerGlobalConfiguration.class)) {
      nodes.forEach((key, path) -> config.when(() -> TigerGlobalConfiguration.readStringOptional(key))
          .thenReturn(Optional.of(path)));
      return RawOtlpAttempts.fromMessages(messages, TRACE, "GET", TARGET, 401);
    }
  }

  private List<RbelElement> attempt(String context, String method, String target, int status,
      String headers) {
    var request = request(context, method, target, headers);
    var response = response(status);
    pair(request, List.of(response));
    return List.of(request, response);
  }

  private RbelElement request(String context, String method, String target, String headers) {
    return parse(method + " " + target + " HTTP/1.1\r\nHost: zeta.test\r\ntraceparent: "
        + context + "\r\n" + headers + "\r\n");
  }

  private RbelElement response(int status) {
    return parse("HTTP/1.1 " + status + " Response\r\nContent-Length: 0\r\n\r\n");
  }

  private static void pair(RbelElement request, List<RbelElement> responses) {
    var pair = TracingMessagePairFacet.builder().request(request).responses(responses).build();
    request.addOrReplaceFacet(pair);
    responses.forEach(response -> response.addOrReplaceFacet(pair));
  }

  private RbelElement parse(String message) {
    return converter.parseMessage(message.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }

  private static String context(String character) {
    return "00-" + TRACE + "-" + character.repeat(16) + "-01";
  }
}
