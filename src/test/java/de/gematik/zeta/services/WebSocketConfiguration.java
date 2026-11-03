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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;

/** Reads WebSocket timing defaults; fallbacks support unit tests without Tiger YAML bootstrap. */
public final class WebSocketConfiguration {

  /** Utility class. */
  private WebSocketConfiguration() {
  }

  /** Returns the transport connection and default STOMP handshake timeout in seconds. */
  public static int connectionTimeoutSeconds() {
    return positiveSeconds("connectionTimeoutSeconds", 5);
  }

  /** Returns the default wait for an application message in seconds. */
  public static int messageTimeoutSeconds() {
    return positiveSeconds("messageTimeoutSeconds", 10);
  }

  /** Returns the raw load-connection handshake timeout in seconds. */
  public static int loadConnectionTimeoutSeconds() {
    return positiveSeconds("loadConnectionTimeoutSeconds", 10);
  }

  /** Returns the bounded wait for a peer Close frame in seconds. */
  public static int closeTimeoutSeconds() {
    return positiveSeconds("closeTimeoutSeconds", 1);
  }

  /** Rejects non-positive timing configuration before starting transport operations. */
  private static int positiveSeconds(String name, int fallback) {
    var key = "websocket." + name;
    int value = TigerGlobalConfiguration.readIntegerOptional(key).orElse(fallback);
    if (value <= 0) {
      throw new IllegalArgumentException(key + " must be greater than zero");
    }
    return value;
  }
}
