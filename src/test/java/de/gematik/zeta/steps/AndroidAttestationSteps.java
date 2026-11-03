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

import de.gematik.zeta.services.AndroidKeyAttestation;
import io.cucumber.java.de.Dann;

/** Cucumber bindings for Android certificate and client-key binding verification. */
public class AndroidAttestationSteps {
  /**
   * Validates the hardware attestation certificate chain against configured public roots.
   *
   * @param chain JSON array of DER certificates encoded in Base64
   * @param roots PEM trust-anchor file
   * @throws Exception if the chain or hardware levels are invalid
   */
  @Dann("verifiziere die Android-Attestierungskette {tigerResolvedString} gegen Wurzeln {tigerResolvedString}")
  public void verifyChain(String chain, String roots) throws Exception {
    AndroidKeyAttestation.verifyHardwareChain(chain, roots);
  }

  /**
   * Verifies that the AK certificate binds the supplied client instance key.
   *
   * @param chain JSON array of DER certificates encoded in Base64
   * @param clientJwk public client instance JWK
   * @throws Exception if the challenge or key separation is invalid
   */
  @Dann("verifiziere die Bindung der Android-Attestierungskette {tigerResolvedString} an Clientschlüssel {tigerResolvedString}")
  public void verifyBinding(String chain, String clientJwk) throws Exception {
    AndroidKeyAttestation.verifyClientKeyBinding(chain, clientJwk);
  }
}
