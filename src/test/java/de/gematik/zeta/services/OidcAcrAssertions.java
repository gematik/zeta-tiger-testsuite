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

import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict mapping checks; the scenario separately verifies the original ID-token signature. */
public final class OidcAcrAssertions {
  private static final ObjectMapper JSON = new ObjectMapper();

  private OidcAcrAssertions() {
  }

  /**
   * Checks a genuine IDP-issued profile and the corresponding AS policy request.
   *
   * @param token unmodified signed ID token, decrypted from the selected IDP response
   * @param policyInput policy input from the correlated token exchange
   * @param identifier independently selected test KVNR
   * @param client registered SDK client
   * @param issuer configured trusted test-IDP issuer
   * @param relyingParty form-encoded client_id from the IDP authorization-code request
   * @param acr independently selected IDP profile's acr
   * @param amr independently selected IDP profile's method
   */
  public static void verify(String token, String policyInput, String identifier, String client,
      String issuer, String relyingParty, String acr, String amr) {
    JsonNode claims;
    JsonNode policy;
    SignedJWT signed;
    try {
      signed = SignedJWT.parse(token);
      claims = JSON.readTree(signed.getPayload().toString());
      policy = JSON.readTree(policyInput);
      require(claims != null && claims.isObject() && policy != null && policy.isObject(), "ID-token claims and policy must be objects");
      var decodedParty = URLDecoder.decode(relyingParty, StandardCharsets.UTF_8);
      require(!decodedParty.isBlank() && List.of(decodedParty).equals(signed.getJWTClaimsSet().getAudience()),
          "ID token belongs to another relying party");
    } catch (Exception e) {
      throw new AssertionError("Cannot parse the signed ID token or the recorded policy input");
    }
    equalText(claims, "iss", issuer, "ID-token issuer");
    equalText(claims, "urn:telematik:claims:id", identifier, "ID-token identity");
    equalText(claims, "acr", acr, "Issued IDP profile");
    var methods = claims.path("amr");
    require(methods.isArray() && methods.size() == 1 && methods.get(0).isTextual()
        && amr.equals(methods.get(0).textValue()), "Issued IDP method differs from the selected profile");
    var expiry = claims.path("exp");
    require(expiry.isIntegralNumber() && expiry.asLong() > Instant.now().getEpochSecond(), "ID token is not current");
    equalText(policy.path("client_registration_data"), "client_id", client, "Policy client");
    equalText(policy.path("user_info"), "identifier", identifier, "Policy identity");
    var request = policy.path("authorization_request");
    equalText(request, "grant_type", "urn:ietf:params:oauth:grant-type:token-exchange", "Policy grant type");
    equalText(request, "acr", claims.path("acr").textValue(), "Policy acr must copy the ID token unchanged");
  }

  private static void equalText(JsonNode node, String field, String expected, String label) {
    require(expected != null && !expected.isBlank() && !expected.contains("${"), "Unresolved expected " + label);
    require(node != null && node.path(field).isTextual() && expected.equals(node.path(field).textValue()), label + " differs or is not a string");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
