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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.gematik.rbellogger.MessageSortOrder;
import de.gematik.rbellogger.RbelConversionPhase;
import de.gematik.rbellogger.RbelConverter;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.RbelMessageHistory;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.util.RbelMessagesSupplier;
import de.gematik.test.tiger.LocalProxyRbelMessageListener;
import de.gematik.test.tiger.lib.rbel.MockHistoryFacade;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import java.nio.charset.StandardCharsets;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RecordedMessagesTest {
  @Test
  void laterArrivalsInvalidateTheLiveViewButCannotAlterTheCapturedSnapshot() {
    var converter = RbelLogger.build().getRbelConverter();
    var first = parse(converter, 1);
    var history = converter.getMessageHistory();
    var unsafe = history.getMessages().iterator();
    var snapshot = RecordedMessages.copy(history);
    var second = parse(converter, 2);
    assertThrows(ConcurrentModificationException.class, unsafe::next);
    assertEquals(List.of(first), snapshot);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add(second));
    assertEquals(List.of(first, second), RecordedMessages.copy(history));
  }

  @Test
  void theSuppliedScenarioBoundaryIsRetained() {
    var converter = RbelLogger.build().getRbelConverter();
    parse(converter, 1);
    var current = parse(converter, 2);
    var filtered = new TreeMap<Long, RbelElement>();
    filtered.put(current.getSequenceNumber().orElseThrow(), current);
    assertEquals(List.of(current), RecordedMessages.copy(new MockHistoryFacade(filtered)));
    assertTrue(RecordedMessages.copy(new MockHistoryFacade(new TreeMap<>())).isEmpty());
  }

  @Test
  void anUnfinishedMessageCannotHideLaterRequestsAfterTheScenarioBoundary() {
    var converter = RbelLogger.build().getRbelConverter();
    var beforeClear = parse(converter, 0);
    var history = converter.getMessageHistory();
    var listener = listener(history);
    listener.clearValidatableRbelMessages();
    var outerPar = parse(converter, 1);
    var unfinished = parse(converter, 2);
    var innerPar = parse(converter, 3);
    unfinished.setConversionPhase(RbelConversionPhase.UNPARSED);

    assertEquals(List.of(outerPar), List.copyOf(
        history.getMessagesAfter(beforeClear, false, MessageSortOrder.SEQUENCE)));
    assertEquals(List.of(outerPar, unfinished, innerPar), RecordedMessages.copy(history, listener));
  }

  @Test
  void anUnfinishedFirstScenarioMessageCannotHideLaterRequests() {
    var converter = RbelLogger.build().getRbelConverter();
    var beforeClear = parse(converter, 0);
    var history = converter.getMessageHistory();
    var listener = listener(history);
    listener.clearValidatableRbelMessages();
    var unfinished = parse(converter, 1);
    var later = parse(converter, 2);
    unfinished.setConversionPhase(RbelConversionPhase.UNPARSED);

    assertTrue(history.getMessagesAfter(beforeClear, false, MessageSortOrder.SEQUENCE).isEmpty());
    assertEquals(List.of(unfinished, later), RecordedMessages.copy(history, listener));
  }

  @Test
  void anUnfinishedFirstMessageBeforeAnyClearCannotHideLaterRequests() {
    var converter = RbelLogger.build().getRbelConverter();
    var unfinished = parse(converter, 1);
    var later = parse(converter, 2);
    unfinished.setConversionPhase(RbelConversionPhase.UNPARSED);
    var history = converter.getMessageHistory();
    assertEquals(List.of(unfinished, later), RecordedMessages.copy(history, listener(history)));
  }

  @Test
  void anEmptyScenarioHistoryDoesNotExposeMessagesFromBeforeTheClear() {
    var converter = RbelLogger.build().getRbelConverter();
    parse(converter, 0);
    var history = converter.getMessageHistory();
    var listener = listener(history);
    listener.clearValidatableRbelMessages();
    assertTrue(RecordedMessages.copy(history, listener).isEmpty());
  }

  @Test
  void clearingEmptyHistoryDoesNotDiscardTheNextMessage() {
    var converter = RbelLogger.build().getRbelConverter();
    var history = converter.getMessageHistory();
    var listener = listener(history);
    listener.clearValidatableRbelMessages();
    var first = parse(converter, 0);
    assertEquals(List.of(first), RecordedMessages.copy(history, listener));
  }

  @Test
  void anotherClearAdvancesTheBoundaryWithoutChangingEarlierSnapshots() {
    var converter = RbelLogger.build().getRbelConverter();
    var history = converter.getMessageHistory();
    var listener = listener(history);
    parse(converter, 0);
    listener.clearValidatableRbelMessages();
    var first = parse(converter, 1);
    var earlier = RecordedMessages.copy(history, listener);
    listener.clearValidatableRbelMessages();
    var next = parse(converter, 2);
    assertEquals(List.of(next), RecordedMessages.copy(history, listener));
    assertEquals(List.of(first), earlier);
  }

  @Test
  void snapshotUsesTheActualClearMarkerInsteadOfTheParsedScenarioView() {
    var converter = RbelLogger.build().getRbelConverter();
    var history = converter.getMessageHistory();
    parse(converter, 0);
    var listener = listener(history);
    listener.clearValidatableRbelMessages();
    var unfinished = parse(converter, 1);
    final var later = parse(converter, 2);
    unfinished.setConversionPhase(RbelConversionPhase.UNPARSED);
    var retriever = mock(RbelMessageRetriever.class, RETURNS_DEEP_STUBS);
    var proxy = retriever.getTigerTestEnvMgr().getLocalTigerProxyOrFail();
    when(proxy.getRbelLogger().getRbelConverter().getMessageHistory()).thenReturn(history);
    when(retriever.getLocalProxyRbelMessageListener()).thenReturn(listener);
    try (var selected = mockStatic(RbelMessageRetriever.class)) {
      selected.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      assertEquals(List.of(unfinished, later), RecordedMessages.snapshot());
      verify(proxy).waitForAllCurrentMessagesToBeParsed();
      verify(retriever, never()).getMessageHistory();
    }
  }

  @Test
  void missingMessageSequenceFailsWithItsIdentityAndContext() {
    var message = mock(RbelElement.class);
    when(message.getSequenceNumber()).thenReturn(Optional.empty());
    when(message.getUuid()).thenReturn("unsequenced-message");
    var history = mock(RbelMessageHistory.MessageHistory.class);
    when(history.toArray()).thenReturn(new Object[] {message});
    var error = assertThrows(AssertionError.class,
        () -> RecordedMessages.copy(history, listener(history)));
    assertTrue(error.getMessage().contains("recorded message unsequenced-message"));
    assertTrue(error.getMessage().contains("has no sequence number"));
  }

  @Test
  void missingClearMarkerSequenceFailsWithItsIdentityAndContext() {
    var converter = RbelLogger.build().getRbelConverter();
    var marker = parse(converter, 0);
    var history = mock(RbelMessageHistory.MessageHistory.class);
    when(history.isEmpty()).thenReturn(false);
    when(history.getLast()).thenReturn(marker);
    var listener = listener(history);
    listener.clearValidatableRbelMessages();
    var invalidMarker = org.mockito.Mockito.spy(marker);
    when(invalidMarker.getSequenceNumber()).thenReturn(Optional.empty());
    when(history.getLast()).thenReturn(invalidMarker);
    listener.clearValidatableRbelMessages();
    var error = assertThrows(AssertionError.class, () -> RecordedMessages.copy(history, listener));
    assertTrue(error.getMessage().contains("scenario clear marker " + marker.getUuid()));
    assertTrue(error.getMessage().contains("has no sequence number"));
  }

  private static LocalProxyRbelMessageListener listener(RbelMessageHistory.MessageHistory history) {
    var supplier = mock(RbelMessagesSupplier.class);
    when(supplier.getMessageHistory()).thenReturn(history);
    return new LocalProxyRbelMessageListener(supplier);
  }

  @Test
  @Timeout(20)
  void concurrentArrivalsPreserveEveryMemberAndStableIteration() throws Exception {
    var converter = RbelLogger.build().getRbelConverter();
    parse(converter, 0);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var writer = executor.submit(() -> {
        start.await();
        for (int i = 1; i <= 200; i++) {
          parse(converter, i);
        }
        return null;
      });
      var first = RecordedMessages.copy(converter.getMessageHistory());
      start.countDown();
      for (int i = 0; i < 500; i++) {
        var snapshot = RecordedMessages.copy(converter.getMessageHistory());
        assertEquals(snapshot.size(), snapshot.stream().map(RbelElement::getUuid).distinct().count());
        assertEquals(1, first.size());
      }
      writer.get(15, TimeUnit.SECONDS);
      assertEquals(201, RecordedMessages.copy(converter.getMessageHistory()).size());
    }
  }

  private static RbelElement parse(RbelConverter converter, int id) {
    return converter.parseMessage(("GET /snapshot/" + id
        + " HTTP/1.1\r\nHost: example.test\r\n\r\n").getBytes(StandardCharsets.UTF_8),
        new RbelMessageMetadata());
  }
}
