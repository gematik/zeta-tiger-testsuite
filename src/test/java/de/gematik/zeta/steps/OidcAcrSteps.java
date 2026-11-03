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

import de.gematik.zeta.services.OidcAcrAssertions;
import io.cucumber.java.de.Dann;

/** OIDC-profile mapping assertions over the original signed ID token and correlated policy input. */
public class OidcAcrSteps {
  /**
   * Verifies exact, typed acr forwarding for the independently selected IDP profile.
   *
   * @param token signed ID token
   * @param policy recorded policy input
   * @param identity selected KVNR
   * @param client registered client
   * @param issuer trusted IDP issuer
   * @param relyingParty IDP request's relying party
   * @param acr selected assurance profile
   * @param amr selected test-IDP method
   */
  @Dann("prüfe OIDC-acr aus ID Token {tigerResolvedString} gegen Policy {tigerResolvedString} für KVNR {tigerResolvedString}, "
      + "Client {tigerResolvedString}, Issuer {tigerResolvedString}, RP {tigerResolvedString}, acr {tigerResolvedString} und amr {tigerResolvedString}")
  public void verify(String token, String policy, String identity, String client, String issuer, String relyingParty, String acr, String amr) {
    OidcAcrAssertions.verify(token, policy, identity, client, issuer, relyingParty, acr, amr);
  }
}
