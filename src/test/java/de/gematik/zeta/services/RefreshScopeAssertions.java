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

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Semantic refresh-grant checks; the scenario separately verifies signatures against AS JWKS. */
public final class RefreshScopeAssertions {
  private static final ObjectMapper JSON = new ObjectMapper();

  private RefreshScopeAssertions() {
  }

  /**
   * Parses a resolved OAuth scope set independently of ordering or repeated scope names.
   *
   * @param value space-separated scopes
   * @return distinct scope names
   */
  public static Set<String> scopes(String value) {
    require(value != null && !value.isBlank() && !value.contains("${"), "Expected a resolved nonempty scope set");
    require(value.chars().allMatch(c -> c == 32 || c == 33 || c >= 35 && c <= 91 || c >= 93 && c <= 126),
        "Invalid OAuth scope characters");
    var parts = value.trim().split(" +");
    var result = new HashSet<>(Arrays.asList(parts));
    return Set.copyOf(result);
  }

  /**
   * Checks the recorded wire request after form decoding, without nested expression evaluation.
   *
   * @param body recorded request body
   * @param resource independently configured resource
   * @param scope independently configured scope set
   */
  public static void verifyRequest(String body, String resource, String scope) {
    var form = new HashMap<String, String>();
    for (var entry : body.split("&")) {
      var pair = entry.split("=", 2);
      require(pair.length == 2, "Recorded refresh request is not form encoded");
      try {
        var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
        var value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        require(form.putIfAbsent(key, value) == null, "Duplicate parameter in recorded refresh request");
      } catch (IllegalArgumentException e) {
        throw new AssertionError("Malformed form encoding in recorded refresh request");
      }
    }
    require("refresh_token".equals(form.get("grant_type")), "Recorded request is not a refresh");
    require(resource.equals(form.get("resource")), "Recorded refresh changed the requested resource");
    require(!form.containsKey("audience"), "Resource-based refresh must not also send a legacy audience");
    require(scopes(scope).equals(scopes(form.get("scope"))), "Recorded refresh has unexpected requested scopes");
  }

  /**
   * Checks the policy tuple belonging to the independently recorded changed-scope refresh.
   *
   * @param input recorded policy input object
   * @param client expected registered client
   * @param resource independently configured resource indicator
   * @param scope complete requested scope set
   */
  public static void verifyPolicy(String input, String client, String resource, String scope) {
    JsonNode policy;
    try {
      policy = JSON.readTree(input);
    } catch (RuntimeException e) {
      throw new AssertionError("Cannot parse the recorded refresh policy input");
    }
    require(policy != null && policy.isObject(), "Refresh policy input must be an object");
    require(policy.path("version").isTextual() && "1.1".equals(policy.path("version").textValue()),
        "Resource-based refresh requires policy input version 1.1");
    require(client.equals(policy.path("client_registration_data").path("client_id").asString()), "Refresh policy belongs to another client");
    var request = policy.path("authorization_request");
    require("refresh_token".equals(request.path("grant_type").asString()), "Policy input is not a refresh");
    require(request.path("resource").isTextual() && resource.equals(request.path("resource").textValue()),
        "Policy input changed the requested resource");
    require(!request.has("audience"), "Resource-based policy input must not contain a legacy audience");
    require(scopes(scope).equals(strings(request.path("scopes"))), "Policy input did not receive the exact requested scope set");
  }

  private static Set<String> strings(JsonNode values) {
    require(values.isArray() && !values.isEmpty()
        && values.valueStream().allMatch(v -> v.isTextual() && !v.textValue().isBlank()), "Policy grant field must be a nonempty string array");
    return values.valueStream().map(JsonNode::textValue).collect(Collectors.toSet());
  }

  /**
   * Requires the exact effective scope set, including independently configured realm defaults.
   *
   * @param accessToken access token whose signature is verified by the scenario
   * @param expectedScopes independent expected scopes
   * @throws Exception if the token is malformed
   */
  public static void verifyScopes(String accessToken, String expectedScopes) throws Exception {
    var claims = SignedJWT.parse(accessToken).getJWTClaimsSet();
    require(scopes(expectedScopes).equals(scopes(claims.getStringClaim("scope"))), "Access token has unexpected effective scopes");
  }

  /**
   * Checks the direct response for a scope change in the same resource/client/user/session context.
   *
   * @param previousAccess previous signed access token
   * @param previousRefresh latest unconsumed refresh token submitted in the request
   * @param response direct JSON token response
   * @param expectedScopes independently configured new grant
   * @throws Exception if a token or response is malformed
   */
  public static void verifyChangedGrant(String previousAccess, String previousRefresh, String response, String expectedScopes) throws Exception {
    JsonNode body;
    try {
      body = JSON.readTree(response);
    } catch (RuntimeException e) {
      throw new AssertionError("Cannot parse the direct refresh response");
    }
    require(body != null && body.isObject(), "Refresh response must be an object");
    var access = text(body, "access_token");
    var refresh = text(body, "refresh_token");
    require(previousRefresh != null && !previousRefresh.isBlank(), "Missing submitted refresh token");
    require(!access.equals(previousAccess) && !refresh.equals(previousRefresh), "Scope change must issue a new access and refresh token");
    require("DPoP".equalsIgnoreCase(text(body, "token_type")), "Refresh response must issue DPoP-bound tokens");
    require(body.path("expires_in").isIntegralNumber() && body.path("expires_in").asLong() > 0, "Refresh response lacks a positive lifetime");
    var before = SignedJWT.parse(previousAccess).getJWTClaimsSet();
    final var after = SignedJWT.parse(access).getJWTClaimsSet();
    var expected = scopes(expectedScopes);
    require(!expected.equals(scopes(before.getStringClaim("scope"))), "The expected grant must change the effective scope set");
    verifyScopes(access, expectedScopes);
    if (body.has("scope")) {
      require(expected.equals(scopes(text(body, "scope"))), "Response scope differs from the expected and signed grant");
    }
    for (var claim : new String[] {"iss", "sub", "client_id", "sid"}) {
      var original = before.getStringClaim(claim);
      require(original != null && !original.isBlank() && original.equals(after.getStringClaim(claim)),
          "Scope change altered the established " + claim + " binding");
    }
    require(before.getAudience() != null && !before.getAudience().isEmpty()
            && new HashSet<>(before.getAudience()).equals(new HashSet<>(after.getAudience())),
        "Scope change altered the audience of the unchanged resource");
    require(thumbprint(before).equals(thumbprint(after)), "Scope change altered the DPoP key binding");
    var expires = after.getExpirationTime();
    require(expires != null && expires.toInstant().isAfter(Instant.now().plusSeconds(15)), "Changed access token is too close to expiry");
  }

  private static String thumbprint(JWTClaimsSet claims) throws Exception {
    var confirmation = claims.getJSONObjectClaim("cnf");
    require(confirmation != null && confirmation.get("jkt") instanceof String, "Access token lacks its DPoP key binding");
    var value = (String) confirmation.get("jkt");
    require(!value.isBlank(), "Empty DPoP key binding");
    return value;
  }

  private static String text(JsonNode body, String field) {
    var value = body.path(field);
    require(value.isTextual() && !value.textValue().isBlank(), "Refresh response lacks a nonempty " + field);
    return value.textValue();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
