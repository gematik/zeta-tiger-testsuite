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

import com.nimbusds.jwt.SignedJWT;
import de.gematik.zeta.services.RefreshScopeAssertions;
import io.cucumber.java.de.Dann;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Validates scope grants and token-free OAuth rejections for the Notification Service. */
public class NotificationTokenSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SCOPES = Set.of("notification.pusher.read",
      "notification.pusher.write", "notification.channel.read", "notification.channel.write");

  /**
   * Checks a correlated token response against the Notification Service scope table.
   *
   * @param status recorded HTTP status
   * @param body recorded response body
   * @param resource independently discovered Notification Service resource
   * @param historyScopeCount independently observed History flag, zero or one
   * @param keyStore authorization server JWKS used to verify issued access tokens
   * @throws ParseException if an issued token is malformed
   */
  @Dann("die Tokenantwort {tigerResolvedString} bei HTTP {tigerResolvedString} für Resource"
      + " {tigerResolvedString} hält die Notification-Scope-Grenzen mit History-Status"
      + " {tigerResolvedString} und JWKS {tigerResolvedString} ein")
  public void verifyScopeResponse(String body, String status, String resource,
      String historyScopeCount, String keyStore) throws ParseException {
    require(Set.of("0", "1").contains(historyScopeCount), "Ambiguous Notification History flag");
    var response = JSON.readTree(body);
    require(response != null && response.isObject(), "Token response must be an object");
    if (!"200".equals(status)) {
      require(Set.of("400", "403").contains(status), "Unexpected token rejection status");
      require(response.path("error").isString() && !response.path("error").asString().isBlank(),
          "Token rejection must contain an OAuth error");
      require(!response.has("access_token") && !response.has("refresh_token")
          && !response.has("id_token"), "Rejected grant must not issue tokens");
      return;
    }
    require(!response.has("error"), "Successful token response contains an OAuth error");
    var access = response.path("access_token");
    require(access.isString() && !access.asString().isBlank(), "Missing issued access token");
    new SignatureVerificationSteps().verifyJwtSignatureFromKid(access.asString(), keyStore);
    var claims = SignedJWT.parse(access.asString()).getJWTClaimsSet();
    require(claims.getAudience().equals(java.util.List.of(resource)),
        "Issued access token is not exclusively bound to the Notification Service");
    var granted = RefreshScopeAssertions.scopes(claims.getStringClaim("scope"));
    var allowed = new HashSet<>(SCOPES);
    if ("1".equals(historyScopeCount)) {
      allowed.add("notification.history.read");
    }
    require(allowed.containsAll(granted), "Issued access token contains forbidden scopes: "
        + granted.stream().filter(scope -> !allowed.contains(scope)).toList());
    if (response.has("scope")) {
      require(response.path("scope").isString(), "Token response scope must be a string");
      require(allowed.containsAll(RefreshScopeAssertions.scopes(response.path("scope").asString())),
          "Token response contains forbidden scopes");
    }
  }

  /**
   * Rejects an invalid token response.
   *
   * @param condition response invariant
   * @param message diagnostic for a violated invariant
   */
  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
