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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Tests Notification Service scope grants and token-free OAuth rejections. */
class NotificationTokenStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RESOURCE = "https://guard.example/notification-service";
  private final NotificationTokenSteps steps = new NotificationTokenSteps();

  @Test
  void acceptsBoundSignedGrantAndMatchingResponseScopes() throws Exception {
    var grant = grant("notification.pusher.read notification.channel.read", RESOURCE);
    assertDoesNotThrow(() -> verify(grant, "0"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"profile", "email", "notification.unlisted"})
  void rejectsForbiddenScopesEvenWhenOperationScopeIsPresent(String extra) throws Exception {
    var grant = grant("notification.pusher.read " + extra, RESOURCE);
    assertThrows(AssertionError.class, () -> verify(grant, "1"));
  }

  @Test
  void historyScopeRequiresIndependentEnabledFlag() throws Exception {
    var grant = grant("notification.pusher.read notification.history.read", RESOURCE);
    assertThrows(AssertionError.class, () -> verify(grant, "0"));
    assertDoesNotThrow(() -> verify(grant, "1"));
    assertThrows(AssertionError.class, () -> verify(grant, "unknown"));
  }

  @Test
  void rejectsDifferentAudience() throws Exception {
    var grant = grant("notification.pusher.read", RESOURCE + "/other");
    assertThrows(AssertionError.class, () -> verify(grant, "0"));
  }

  @Test
  void rejectsResponseScopeThatConcealsForbiddenTokenScope() throws Exception {
    var grant = grant("notification.pusher.read email", RESOURCE);
    var body = JSON.createObjectNode().put("access_token", grant.token())
        .put("scope", "notification.pusher.read").toString();
    assertThrows(AssertionError.class,
        () -> steps.verifyScopeResponse(body, "200", RESOURCE, "0", grant.jwks()));
  }

  @Test
  void rejectsForbiddenScopeOnlyPresentInResponse() throws Exception {
    var grant = grant("notification.pusher.read", RESOURCE);
    var body = JSON.createObjectNode().put("access_token", grant.token())
        .put("scope", "notification.pusher.read profile").toString();
    assertThrows(AssertionError.class,
        () -> steps.verifyScopeResponse(body, "200", RESOURCE, "0", grant.jwks()));
  }

  @Test
  void acceptsResponseWithoutOptionalScopeField() throws Exception {
    var grant = grant("notification.pusher.read", RESOURCE);
    var body = JSON.createObjectNode().put("access_token", grant.token()).toString();
    assertDoesNotThrow(
        () -> steps.verifyScopeResponse(body, "200", RESOURCE, "0", grant.jwks()));
  }

  @Test
  void rejectsTokenWithForeignSigningKey() throws Exception {
    var grant = grant("notification.pusher.read", RESOURCE);
    var other = grant("notification.pusher.read", RESOURCE);
    assertThrows(AssertionError.class,
        () -> steps.verifyScopeResponse(grant.body(), "200", RESOURCE, "0", other.jwks()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"400", "403"})
  void acceptsTokenFreeOauthRejection(String status) {
    assertDoesNotThrow(() -> steps.verifyScopeResponse(
        "{\"error\":\"invalid_scope\"}", status, RESOURCE, "0", "{}"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"access_token", "refresh_token", "id_token"})
  void rejectsOauthErrorThatStillIssuesAToken(String field) {
    var body = JSON.createObjectNode().put("error", "invalid_scope").put(field, "issued");
    assertThrows(AssertionError.class, () -> steps.verifyScopeResponse(
        body.toString(), "400", RESOURCE, "0", "{}"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "[]", "{\"error\":\"\"}"})
  void rejectsMalformedOauthError(String body) {
    assertThrows(AssertionError.class, () -> steps.verifyScopeResponse(
        body, "400", RESOURCE, "0", "{}"));
  }

  @Test
  void rejectsSuccessWithoutTokenAndServerErrorAsScopeRejection() {
    assertThrows(AssertionError.class, () -> steps.verifyScopeResponse(
        "{\"error\":\"invalid_scope\"}", "200", RESOURCE, "0", "{}"));
    assertThrows(AssertionError.class, () -> steps.verifyScopeResponse(
        "{\"error\":\"server_error\"}", "500", RESOURCE, "0", "{}"));
  }

  private void verify(Grant grant, String history) throws Exception {
    steps.verifyScopeResponse(grant.body(), "200", RESOURCE, history, grant.jwks());
  }

  private static Grant grant(String scopes, String resource) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).keyID("scope-test").generate();
    var claims = new JWTClaimsSet.Builder().audience(resource).claim("scope", scopes).build();
    var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("scope-test").build(), claims);
    token.sign(new ECDSASigner(key));
    var compact = token.serialize();
    return new Grant(compact, JSON.writeValueAsString(Map.of("access_token", compact, "scope", scopes)),
        new JWKSet(List.of(key.toPublicJWK())).toString());
  }

  private record Grant(String token, String body, String jwks) {
  }
}
