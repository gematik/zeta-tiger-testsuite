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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.proxy.handler.SingleConnectionParser.SingleConnectionParserMarkerFacet;
import de.gematik.zeta.services.RecordedMessages;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TimingGlueTest {
  @ParameterizedTest
  @CsvSource({"1,true", "0,false", "-1,false"})
  void requestMustFollowItsReferenceResponse(int offset, boolean valid) {
    var reference = message("GET /resource HTTP/1.1\r\nHost: example.test\r\n\r\n", 0);
    var response = message("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n", 2);
    var current = message("POST /token HTTP/1.1\r\nHost: example.test\r\n\r\n", 2 + offset);
    var pair = TracingMessagePairFacet.builder().request(reference).responses(List.of(response)).build();
    reference.addFacet(pair);
    response.addFacet(pair);
    try (var recorded = mockStatic(RecordedMessages.class);
        var singleton = mockStatic(RbelMessageRetriever.class)) {
      var retriever = mock(RbelMessageRetriever.class);
      singleton.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      when(retriever.getCurrentRequest()).thenReturn(current);
      recorded.when(RecordedMessages::snapshot).thenReturn(List.of(current, response, reference));
      var glue = new TimingGlue();
      if (valid) {
        assertDoesNotThrow(() -> glue.verifyCurrentRequestSentAfterResponse(reference.getUuid()));
      } else {
        assertThrows(AssertionError.class,
            () -> glue.verifyCurrentRequestSentAfterResponse(reference.getUuid()));
      }
      assertThrows(AssertionError.class, () -> glue.verifyCurrentRequestSentAfterResponse("unknown"));
      var unanswered = message("GET /other HTTP/1.1\r\nHost: example.test\r\n\r\n", 0);
      recorded.when(RecordedMessages::snapshot).thenReturn(List.of(unanswered, current));
      assertThrows(AssertionError.class,
          () -> glue.verifyCurrentRequestSentAfterResponse(unanswered.getUuid()));
    }
  }

  /** Slow downloads, concurrent startup fetches and missing responses must not distort cache age. */
  @ParameterizedTest
  @CsvSource({"4,7,8,true", "4,2,3,false", "4,9,10,false", "-1,2,3,false",
      "4,7,9,false", "4,7,-1,false", "4,7,6,false"})
  void refreshRequiresReceivedCacheResponseAndUsesItsLifetime(
      int responseSeconds, int requestSeconds, int completedSeconds, boolean valid) {
    var previous = message("GET /jwks HTTP/1.1\r\nHost: example.test\r\n\r\n", 0);
    var current = message("GET /jwks HTTP/1.1\r\nHost: example.test\r\n\r\n", requestSeconds);
    var messages = new ArrayList<>(List.of(previous, current));
    if (responseSeconds >= 0) {
      var response = message("HTTP/1.1 200 OK\r\nCache-Control: max-age=5\r\nContent-Length: 0\r\n\r\n", responseSeconds);
      var pair = TracingMessagePairFacet.builder().request(previous).responses(List.of(response)).build();
      previous.addFacet(pair);
      response.addFacet(pair);
      messages.add(response);
    }
    if (completedSeconds >= 0) {
      var response = message("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", completedSeconds);
      var pair = TracingMessagePairFacet.builder().request(current).responses(List.of(response)).build();
      current.addFacet(pair);
      response.addFacet(pair);
      messages.add(response);
    }
    try (var recorded = mockStatic(RecordedMessages.class);
        var singleton = mockStatic(RbelMessageRetriever.class)) {
      var retriever = mock(RbelMessageRetriever.class);
      singleton.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      when(retriever.getCurrentRequest()).thenReturn(current);
      recorded.when(RecordedMessages::snapshot).thenReturn(messages);
      var glue = new TimingGlue();
      if (valid) {
        assertDoesNotThrow(() -> glue.verifyCurrentDownloadCompletedBeforePreviousMatchingResponseExpired("/jwks", 5));
      } else {
        assertThrows(AssertionError.class,
            () -> glue.verifyCurrentDownloadCompletedBeforePreviousMatchingResponseExpired("/jwks", 5));
      }
    }
  }

  private static RbelElement message(String content, int seconds) {
    var result = RbelLogger.build().getRbelConverter().parseMessage(
        content.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    result.addOrReplaceFacet(new RbelMessageTimingFacet(Instant.ofEpochSecond(100 + seconds).atZone(ZoneOffset.UTC)));
    return result;
  }

  @Test
  void waitsForLateParsedNestedRequestWithinTheOriginalInterval() {
    var parent = message("POST /parent HTTP/1.1\r\nHost: example.test\r\n\r\n", 0);
    var response = message("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n", 4);
    final var child = message("GET /nested HTTP/1.1\r\nHost: example.test\r\nX-Event: own-event\r\n\r\n", 2);
    var pair = TracingMessagePairFacet.builder().request(parent).responses(List.of(response)).build();
    parent.addFacet(pair);
    response.addFacet(pair);
    var retriever = mock(RbelMessageRetriever.class);
    when(retriever.getCurrentRequest()).thenReturn(parent);
    try (var history = mockStatic(RecordedMessages.class);
        var current = mockStatic(RbelMessageRetriever.class)) {
      current.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      history.when(RecordedMessages::snapshot).thenReturn(
          List.of(parent, response), List.of(parent, response), List.of(parent, child, response));
      assertDoesNotThrow(() -> new TimingGlue()
          .verifyMatchingRequestOccursBetweenCurrentRequestAndResponse(
              "/nested", "$.header.X-Event", "own-event"));
    }
  }

  @Test
  void assembledResponseRequiresItsLastChunkAndRejectsMissingTimingEvidence() {
    var response = message("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", 4);
    var first = message("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", 4);
    var last = message("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", 10);
    response.addFacet(new SingleConnectionParserMarkerFacet(List.of(first.getUuid(), last.getUuid())));
    assertEquals(Instant.ofEpochSecond(110), TimingGlue.responseReceivedAt(List.of(first, last), response));
    assertThrows(AssertionError.class, () -> TimingGlue.responseReceivedAt(List.of(first), response));
    response.addOrReplaceFacet(new SingleConnectionParserMarkerFacet(List.of()));
    assertThrows(AssertionError.class, () -> TimingGlue.responseReceivedAt(List.of(first, last), response));
  }

  @Test
  void cacheEvidenceRequiresObservedStartAndRequestStrictlyBeforeExpiry() {
    var start = Instant.parse("2026-09-20T00:00:00Z");
    assertTrue(TimingGlue.isWithinCacheLifetime(start, start.plusSeconds(4), 5));
    assertFalse(TimingGlue.isWithinCacheLifetime(null, start, 5));
    assertFalse(TimingGlue.isWithinCacheLifetime(start, start.minusNanos(1), 5));
    assertFalse(TimingGlue.isWithinCacheLifetime(start, start.plusSeconds(5), 5));
    assertFalse(TimingGlue.isWithinCacheLifetime(start, start, 0));
  }

  private static final Instant EARLIER = Instant.ofEpochSecond(100);
  private static final Instant MIDDLE = Instant.ofEpochSecond(101);
  private static final Instant LATER = Instant.ofEpochSecond(102);

  /**
   * Verifies that strict timestamp ordering accepts an instant inside the exclusive interval.
   */
  @Test
  void strictBetweenAcceptsValueInsideRange() {
    assertTrue(TimingGlue.isStrictlyBetween(MIDDLE, EARLIER, LATER));
  }

  /**
   * Verifies that strict timestamp ordering rejects values on the lower boundary.
   */
  @Test
  void strictBetweenRejectsLowerBoundary() {
    assertFalse(TimingGlue.isStrictlyBetween(EARLIER, EARLIER, LATER));
  }

  /**
   * Verifies that strict timestamp ordering rejects values on the upper boundary.
   */
  @Test
  void strictBetweenRejectsUpperBoundary() {
    assertFalse(TimingGlue.isStrictlyBetween(LATER, EARLIER, LATER));
  }
}
