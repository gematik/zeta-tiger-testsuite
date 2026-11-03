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

import de.gematik.zeta.services.AppleAppAttestation;
import io.cucumber.java.de.Dann;

/** Cucumber bindings for Apple App Attest trust and client-key binding. */
public class AppleAttestationSteps {
  /**
   * Validates the App Attest credential certificate chain against configured roots.
   *
   * @param object Base64 CBOR App Attest object
   * @param roots PEM trust-anchor file
   * @throws Exception if the object or chain is invalid
   */
  @Dann("verifiziere das Apple-Attestierungsobjekt {tigerResolvedString} gegen Wurzeln {tigerResolvedString}")
  public void verifyChain(String object, String roots) throws Exception {
    AppleAppAttestation.verifyChain(object, roots);
  }

  /**
   * Verifies the nonce binding between the attestation object and the client key.
   *
   * @param object Base64 CBOR App Attest object
   * @param clientJwk public client instance JWK
   * @throws Exception if the nonce or key separation is invalid
   */
  @Dann("verifiziere die Bindung des Apple-Attestierungsobjekts {tigerResolvedString} an Clientschlüssel {tigerResolvedString}")
  public void verifyBinding(String object, String clientJwk) throws Exception {
    AppleAppAttestation.verifyClientKeyBinding(object, clientJwk);
  }
}
