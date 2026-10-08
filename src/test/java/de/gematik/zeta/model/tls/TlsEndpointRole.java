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

package de.gematik.zeta.model.tls;

import lombok.Getter;

/**
 * Indicates the TLS endpoint role in a handshake/test scenario.
 *
 * <p>Use {@link #CLIENT} for the side initiating the TLS connection (sending the ClientHello),
 * and {@link #SERVER} for the side accepting the connection (responding with the ServerHello).
 */
@Getter
public enum TlsEndpointRole {

  /**
   * Initiates the TLS connection and sends the ClientHello.
   */
  CLIENT("client"),

  /**
   * Accepts the TLS connection and responds with the ServerHello.
   */
  SERVER("server");

  private final String displayName;

  TlsEndpointRole(String displayName) {
    this.displayName = displayName;
  }

}
