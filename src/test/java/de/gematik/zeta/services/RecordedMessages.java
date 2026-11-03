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

import de.gematik.rbellogger.RbelMessageHistory;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.test.tiger.LocalProxyRbelMessageListener;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import java.util.Arrays;
import java.util.List;

/** Stable membership of the scenario-filtered history while Tiger receives further messages. */
public final class RecordedMessages {
  private RecordedMessages() {}

  /** Returns a stable copy of Tiger's scenario-filtered message history after its parsing wait. */
  public static List<RbelElement> snapshot() {
    var retriever = RbelMessageRetriever.getInstance();
    var proxy = retriever.getTigerTestEnvMgr().getLocalTigerProxyOrFail();
    proxy.waitForAllCurrentMessagesToBeParsed();
    return copy(proxy.getRbelLogger().getRbelConverter().getMessageHistory(),
        retriever.getLocalProxyRbelMessageListener());
  }

  /**
   * Copies all messages registered after Tiger's last clear operation, including unfinished messages.
   *
   * @param completeHistory complete local proxy history
   * @param listener listener holding the scenario clear marker
   * @return immutable scenario history
   * @throws AssertionError if the marker cannot be read or a sequence number is missing
   */
  static List<RbelElement> copy(RbelMessageHistory.MessageHistory completeHistory,
      LocalProxyRbelMessageListener listener) {
    var marker = lastClearedMessage(listener);
    var cutoff = marker == null ? Long.MIN_VALUE : sequence(marker, "scenario clear marker");
    return copy(completeHistory).stream()
        .filter(message -> sequence(message, "recorded message") > cutoff)
        .toList();
  }

  static List<RbelElement> copy(RbelMessageHistory.MessageHistory history) {
    return Arrays.stream(history.toArray()).map(RbelElement.class::cast).toList();
  }

  private static RbelElement lastClearedMessage(LocalProxyRbelMessageListener listener) {
    try {
      var field = LocalProxyRbelMessageListener.class.getDeclaredField("lastDeletedElement");
      if (field.getType() != RbelElement.class || !field.trySetAccessible()) {
        throw new AssertionError("Cannot access Tiger's scenario clear marker; check Tiger API compatibility");
      }
      return (RbelElement) field.get(listener);
    } catch (ReflectiveOperationException | RuntimeException ex) {
      throw new AssertionError("Cannot read Tiger's scenario clear marker "
          + "LocalProxyRbelMessageListener.lastDeletedElement; check Tiger API compatibility", ex);
    }
  }

  private static long sequence(RbelElement message, String role) {
    return message.getSequenceNumber().orElseThrow(() -> new AssertionError(
        "Cannot determine scenario history: " + role + " " + message.getUuid()
            + " has no sequence number"));
  }
}
