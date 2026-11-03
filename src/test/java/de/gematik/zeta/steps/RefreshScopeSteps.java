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

import de.gematik.zeta.services.RefreshScopeAssertions;
import io.cucumber.java.de.Dann;

/** Independent assertions over recorded SDK refresh traffic; these steps never send token requests. */
public class RefreshScopeSteps {

  /**
   * Compares the recorded form request with the independent R1/S1 or R1/S2 fixture.
   *
   * @param body recorded request body
   * @param resource expected resource
   * @param scope expected scope set
   */
  @Dann("der Refresh-Request {tigerResolvedString} verwendet Resource {tigerResolvedString} und Scope {tigerResolvedString}")
  public void verifyRequest(String body, String resource, String scope) {
    RefreshScopeAssertions.verifyRequest(body, resource, scope);
  }

  /**
   * Checks the complete selected policy tuple rather than merely a matching scope substring.
   *
   * @param input recorded policy input
   * @param client registered client ID
   * @param resource expected resource/audience
   * @param scope complete requested scope set
   */
  @Dann("prüfe Refresh-Policy {tigerResolvedString} für Client {tigerResolvedString}, Resource {tigerResolvedString} und Scope {tigerResolvedString}")
  public void verifyPolicy(String input, String client, String resource, String scope) {
    RefreshScopeAssertions.verifyPolicy(input, client, resource, scope);
  }

  /**
   * Checks independently expected scopes in an access token verified against the AS JWKS.
   *
   * @param token recorded signed access token
   * @param scopes complete expected effective scopes
   * @throws Exception if the token is malformed
   */
  @Dann("das Access Token {tigerResolvedString} enthält genau die Scopes {tigerResolvedString}")
  public void verifyScopes(String token, String scopes) throws Exception {
    RefreshScopeAssertions.verifyScopes(token, scopes);
  }

  /**
   * Requires the changed SDK grant to preserve the established session and binding.
   *
   * @param previousAccess previous signed access token
   * @param previousRefresh latest unconsumed refresh token
   * @param response recorded direct AS response
   * @param scopes complete independently expected effective scopes
   * @throws Exception if a token is malformed
   */
  @Dann("die Refresh-Antwort {tigerResolvedString} wechselt von Access Token {tigerResolvedString} und Refresh Token {tigerResolvedString} zu Scopes {tigerResolvedString} in derselben Sitzung")
  public void verifyChangedGrant(String response, String previousAccess, String previousRefresh, String scopes) throws Exception {
    RefreshScopeAssertions.verifyChangedGrant(previousAccess, previousRefresh, response, scopes);
  }
}
