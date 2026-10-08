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

import java.util.Arrays;

/**
 * Expected TLS handshake result tokens used in feature files.
 */
public enum TlsHandshakeExpectation {
  ERFOLGREICH("erfolgreich", "successful"),
  NICHT_ERFOLGREICH("nicht erfolgreich", "not successful");

  private final String deValue;
  private final String enValue;

  TlsHandshakeExpectation(String deValue, String enValue) {
    this.deValue = deValue;
    this.enValue = enValue;
  }

  /**
   * Resolve enum by textual expectation used in feature files.
   *
   * @param value expectation token
   * @return matching handshake expectation
   */
  public static TlsHandshakeExpectation fromValue(String value) {
    return Arrays.stream(values())
        .filter(expectation -> expectation.deValue.equals(value) || expectation.enValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Unsupported TLS handshake expectation: " + value));
  }
}
