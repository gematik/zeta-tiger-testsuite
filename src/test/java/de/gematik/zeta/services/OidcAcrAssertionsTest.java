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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Counterexamples for profile selection, typed acr copying and transaction identity. */
class OidcAcrAssertionsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String HIGH = "gematik-ehealth-loa-high";
  private static final String EGK = "urn:telematik:auth:eGK";

  @ParameterizedTest
  @CsvSource({"gematik-ehealth-loa-high,urn:telematik:auth:eGK",
      "gematik-ehealth-loa-substantial,urn:telematik:auth:other"})
  void copiesEitherIndependentlySelectedProfile(String acr, String amr) throws Exception {
    var source = claims().put("acr", acr);
    source.putArray("amr").add(amr);
    var policy = policy();
    ((ObjectNode) policy.path("authorization_request")).put("acr", acr);
    var token = signed(source);
    assertDoesNotThrow(() -> verify(token, policy.toString(), acr, amr));
  }

  @ParameterizedTest
  @ValueSource(strings = {"different", "number", "null", "array", "missing", "case", "client", "identity", "grant"})
  void rejectsWrongTypedOrUnrelatedPolicy(String mutation) throws Exception {
    var policy = policy();
    var request = (ObjectNode) policy.path("authorization_request");
    switch (mutation) {
      case "different" -> request.put("acr", "gematik-ehealth-loa-substantial");
      case "number" -> request.put("acr", 2);
      case "null" -> request.putNull("acr");
      case "array" -> request.putArray("acr").add(HIGH);
      case "missing" -> request.remove("acr");
      case "case" -> request.put("acr", "GEMATIK-ehealth-loa-high");
      case "client" -> ((ObjectNode) policy.path("client_registration_data")).put("client_id", "foreign");
      case "identity" -> ((ObjectNode) policy.path("user_info")).put("identifier", "foreign");
      case "grant" -> request.put("grant_type", "refresh_token");
      default -> throw new AssertionError("Unknown fixture");
    }
    var token = signed(claims());
    assertThrows(AssertionError.class, () -> verify(token, policy.toString(), HIGH, EGK));
  }

  @ParameterizedTest
  @ValueSource(strings = {"acr", "acr_type", "issuer", "identity", "audience", "amr", "amr_type", "amr_extra", "expired", "expiry_type"})
  void rejectsWrongSourceProfileAndContext(String mutation) throws Exception {
    var source = claims();
    switch (mutation) {
      case "acr" -> source.put("acr", "gematik-ehealth-loa-substantial");
      case "acr_type" -> source.putArray("acr").add(HIGH);
      case "issuer" -> source.put("iss", "https://foreign.example");
      case "identity" -> source.put("urn:telematik:claims:id", "foreign");
      case "audience" -> source.put("aud", "foreign");
      case "amr" -> source.putArray("amr").add("urn:telematik:auth:other");
      case "amr_type" -> source.put("amr", EGK);
      case "amr_extra" -> source.putArray("amr").add(EGK).add("unexpected");
      case "expired" -> source.put("exp", Instant.now().minusSeconds(60).getEpochSecond());
      case "expiry_type" -> source.put("exp", "9999999999");
      default -> throw new AssertionError("Unknown fixture");
    }
    var token = signed(source);
    assertThrows(AssertionError.class, () -> verify(token, policy().toString(), HIGH, EGK));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "{}", "broken"})
  void malformedOrEmptyPolicyCannotPass(String policy) throws Exception {
    var token = signed(claims());
    assertThrows(AssertionError.class, () -> verify(token, policy, HIGH, EGK));
  }

  @Test
  void malformedTokenCannotBecomeAConstantValueComparison() {
    assertThrows(AssertionError.class, () -> verify("invalid", policy().toString(), HIGH, EGK));
  }

  @Test
  void relyingPartyIsDecodedExactlyOnceFromTheRecordedForm() throws Exception {
    var source = claims().put("aud", "https://rp.example/a+b");
    var token = signed(source);
    var input = policy().toString();
    assertDoesNotThrow(() -> OidcAcrAssertions.verify(token, input, "X123456789", "sdk-client", "https://idp.example",
        "https%3A%2F%2Frp.example%2Fa%2Bb", HIGH, EGK));
    assertThrows(AssertionError.class, () -> OidcAcrAssertions.verify(token, input, "X123456789", "sdk-client", "https://idp.example",
        "https%253A%252F%252Frp.example%252Fa%252Bb", HIGH, EGK));
    assertThrows(AssertionError.class, () -> OidcAcrAssertions.verify(token, input, "X123456789", "sdk-client", "https://idp.example",
        "%ZZ", HIGH, EGK));
  }

  private static void verify(String token, String policy, String acr, String amr) {
    OidcAcrAssertions.verify(token, policy, "X123456789", "sdk-client", "https://idp.example", "relying-party", acr, amr);
  }

  private static ObjectNode claims() {
    var result = JSON.createObjectNode().put("iss", "https://idp.example").put("aud", "relying-party")
        .put("urn:telematik:claims:id", "X123456789").put("acr", HIGH)
        .put("exp", Instant.now().plusSeconds(600).getEpochSecond());
    result.putArray("amr").add(EGK);
    return result;
  }

  private static ObjectNode policy() {
    var result = JSON.createObjectNode();
    result.putObject("client_registration_data").put("client_id", "sdk-client");
    result.putObject("user_info").put("identifier", "X123456789");
    result.putObject("authorization_request").put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange").put("acr", HIGH);
    return result;
  }

  private static String signed(ObjectNode claims) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    var jwt = new JWSObject(new JWSHeader(JWSAlgorithm.ES256), new Payload(claims.toString()));
    jwt.sign(new ECDSASigner(key));
    return jwt.serialize();
  }
}
