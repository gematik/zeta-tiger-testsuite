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

import de.gematik.zeta.services.ClientKeyProof;
import io.cucumber.java.de.Dann;

/** Cucumber binding for detached client-key possession signatures. */
public class ClientKeyProofSteps {
  /**
   * Verifies the detached possession signature using the registered client key.
   *
   * @param signature Base64url DER ECDSA signature
   * @param clientJwk public client instance JWK
   * @throws Exception if the signature is invalid
   */
  @Dann("verifiziere die Client-Besitzsignatur {tigerResolvedString} mit Clientschlüssel {tigerResolvedString}")
  public void verifyPossession(String signature, String clientJwk) throws Exception {
    ClientKeyProof.verifyPossession(clientJwk, signature);
  }
}
