/*-
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

import io.cucumber.java.de.Dann;
import io.cucumber.java.en.And;
import java.util.Base64;

/**
 * Step definitions for validating Nonce-Values.
 */
public class NonceSteps {

  /**
   * Cucumber step definition for validating a Base64Url encoded string and it's length.
   *
   * @param nonce  the received nonce to validate
   * @param length expected length of the decoded nonce
   * @throws AssertionError if the nonce is not Base64Url encoded or the length is not correct
   */
  @Dann("decodiere Base64Url {tigerResolvedString} und prüfe das die Länge {int} bit ist")
  @And("decode Base64Url {tigerResolvedString} and check that the length is {int} bit")
  public void validateBase64UrlEncodedNonce(String nonce, int length) throws AssertionError {
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(nonce);
      if (decoded.length != length / 8) {
        throw new AssertionError(String.format(
            "Die Länge %s Bits des Nonce entspricht nicht der erwarteten Länge von %s Bits",
            decoded.length * 8, length));
      }
    } catch (IllegalArgumentException e) {
      throw new AssertionError("Das Nonce ist nicht korrekt Base64url codiert |" + nonce + "|");
    }
  }
}