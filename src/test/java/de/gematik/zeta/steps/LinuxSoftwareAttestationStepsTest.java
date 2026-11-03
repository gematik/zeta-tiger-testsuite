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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.lib.json.JsonSchemaChecker;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.PickleDocString;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LinuxSoftwareAttestationStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant SENT = Instant.ofEpochSecond(1800000000);

  @Test
  void softwarePublicKeyMustMatchRegisteredKeyForDerAndPem() throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate().toPublicJWK();
    var jwks = new JWKSet(key).toString();
    var der = Base64.getEncoder().encodeToString(key.toECPublicKey().getEncoded());
    assertDoesNotThrow(() -> LinuxSoftwareAttestationSteps.verifyPostureKey(der, jwks));
    assertDoesNotThrow(() -> LinuxSoftwareAttestationSteps.verifyPostureKey(
        "-----BEGIN PUBLIC KEY-----\n" + der + "\n-----END PUBLIC KEY-----", jwks));
    var other = new JWKSet(new ECKeyGenerator(Curve.P_256).generate().toPublicJWK()).toString();
    assertThrows(AssertionError.class, () -> LinuxSoftwareAttestationSteps.verifyPostureKey(der, other));
  }

  private static ObjectNode claims() {
    return (ObjectNode) JSON.readTree("""
        {"exp":1800000060,"client_statement":{
          "sub":"client","platform":"linux","posture_type":"software",
          "attestation_timestamp":1800000000,"posture":{
            "product_id":"test","product_version":"1.0","os":"Linux","os_version":"6.1",
            "arch":"x86_64","public_key":"public-key"}}}
        """);
  }

  @Test
  void optionalProductIdMayBeAbsentOrValidButMustNotBeMalformed() {
    var claims = claims();
    assertDoesNotThrow(() -> LinuxSoftwareAttestationSteps.validateStatement(claims, "gültig", null, SENT));
    var posture = (ObjectNode) claims.at("/client_statement/posture");
    posture.set("platform_product_id", JSON.readTree("{\"platform\":\"linux\",\"packaging_type\":\"deb\",\"application_id\":\"test\"}"));
    assertDoesNotThrow(() -> LinuxSoftwareAttestationSteps.validateStatement(claims, "gültig", null, SENT));
    for (var invalid : List.of("null", "{}", "[]", "\"linux\"",
        "{\"platform\":\"windows\",\"packaging_type\":\"deb\",\"application_id\":\"test\"}")) {
      posture.set("platform_product_id", JSON.readTree(invalid));
      assertThrows(AssertionError.class,
          () -> LinuxSoftwareAttestationSteps.validateStatement(claims, "gültig", null, SENT));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"platform", "posture_type", "os", "arch", "public_key", "os_version"})
  void missingOsVersionCannotHideAnotherInvalidCondition(String other) {
    var claims = claims();
    var statement = (ObjectNode) claims.path("client_statement");
    var posture = (ObjectNode) statement.path("posture");
    posture.remove("os_version");
    assertDoesNotThrow(() -> LinuxSoftwareAttestationSteps.validateStatement(claims,
        "os_version_fehlend", "6.1", SENT));
    if (List.of("platform", "posture_type").contains(other)) {
      statement.put(other, "tpm");
    } else if (other.equals("os_version")) {
      posture.putNull(other);
    } else {
      posture.remove(other);
    }
    assertThrows(AssertionError.class,
        () -> LinuxSoftwareAttestationSteps.validateStatement(claims, "os_version_fehlend", "6.1", SENT));
  }

  @Test
  void negativeInputRequiresSuccessfulControlAndCoherentTimestamp() {
    var claims = claims();
    ((ObjectNode) claims.at("/client_statement/posture")).remove("os_version");
    assertThrows(AssertionError.class, () -> LinuxSoftwareAttestationSteps.validateStatement(claims,
        "os_version_fehlend", null, SENT));
    ((ObjectNode) claims.path("client_statement")).put("attestation_timestamp", 1800000100);
    assertThrows(AssertionError.class, () -> LinuxSoftwareAttestationSteps.validateStatement(claims,
        "os_version_fehlend", "6.1", SENT));
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "access_token", "refresh_token", "id_token", "zeta_attestation_token"})
  void negativeFeatureSchemaForbidsEveryTokenEvenWhenNull(String fault) throws Exception {
    var body = (ObjectNode) JSON.readTree("{\"error\":\"invalid_request\"}");
    if (!fault.equals("none")) {
      body.putNull(fault);
    }
    var schema = featureSchema("\"anyOf\"");
    if (fault.equals("none")) {
      assertDoesNotThrow(() -> new JsonSchemaChecker().compareToSchema(body.toString(), schema));
    } else {
      assertThrows(AssertionError.class,
          () -> new JsonSchemaChecker().compareToSchema(body.toString(), schema));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "access_token", "refresh_token", "hardware_token", "token_type"})
  void positiveFeatureSchemaRequiresSoftwareTokens(String fault) throws Exception {
    var body = (ObjectNode) JSON.readTree("{\"access_token\":\"at\",\"refresh_token\":\"rt\"}");
    if (List.of("access_token", "refresh_token").contains(fault)) {
      body.remove(fault);
    } else if (fault.equals("hardware_token")) {
      body.putNull("zeta_attestation_token");
    } else if (fault.equals("token_type")) {
      body.put("access_token", 42);
    }
    var schema = featureSchema("\"access_token\":");
    if (fault.equals("none")) {
      assertDoesNotThrow(() -> new JsonSchemaChecker().compareToSchema(body.toString(), schema));
    } else {
      assertThrows(AssertionError.class,
          () -> new JsonSchemaChecker().compareToSchema(body.toString(), schema));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "client_id", "platform", "posture_type", "product_id",
      "product_version", "os", "os_version", "device_info"})
  void policyFeatureSchemaCannotAcceptAnotherClientOrSoftwareState(String fault) throws Exception {
    var input = (ObjectNode) JSON.readTree("""
        {"client_id":"client","platform":"linux","posture_type":"software",
          "product_id":"test","product_version":"1.0","device_info":{"os":"LINUX","os_version":"6.1"}}
        """);
    var schema = featureSchema("\"client_id\":").replace("${SOFTWARE_CLIENT_ID}", "client")
        .replace("${PRODUCT_ID_INSTANCE_1}", "test").replace("${SOFTWARE_PRODUCT_VERSION}", "1.0")
        .replace("${SOFTWARE_OS}", "Linux").replace("${SOFTWARE_OS_VERSION}", "6.1");
    if (List.of("os", "os_version").contains(fault)) {
      ((ObjectNode) input.path("device_info")).put(fault, "wrong");
    } else if (fault.equals("device_info")) {
      input.remove(fault);
    } else if (!fault.equals("none")) {
      input.put(fault, "wrong");
    }
    if (fault.equals("none")) {
      assertDoesNotThrow(() -> new JsonSchemaChecker().compareToSchema(input.toString(), schema));
    } else {
      assertThrows(AssertionError.class,
          () -> new JsonSchemaChecker().compareToSchema(input.toString(), schema));
    }
  }

  @Test
  void errorSchemaRequiresStringCodeWithoutFixingItsValue() {
    var schema = "{\"$ref\":\"classpath:schemas/v_2_0/schemas/zeta-error.yaml\"}";
    for (var code : List.of("invalid_request", "invalid_token", "custom_error")) {
      assertDoesNotThrow(() -> new JsonSchemaChecker()
          .compareToSchema("{\"error\":\"" + code + "\"}", schema));
    }
    for (var body : List.of("{}", "{\"error\":42}", "{\"error\":null}")) {
      assertThrows(AssertionError.class, () -> new JsonSchemaChecker().compareToSchema(body, schema));
    }
  }

  @Test
  void policyFeatureSchemaRequiresBooleanAllow() throws Exception {
    var schema = featureSchema("\"result\":");
    assertDoesNotThrow(() -> new JsonSchemaChecker()
        .compareToSchema("{\"result\":{\"allow\":true}}", schema));
    for (var input : List.of("{}", "{\"result\":{}}", "{\"result\":{\"allow\":false}}",
        "{\"result\":{\"allow\":\"true\"}}")) {
      assertThrows(AssertionError.class, () -> new JsonSchemaChecker().compareToSchema(input, schema));
    }
  }

  @Test
  void policyObservationExcludesOtherClientsMethodsAndOutOfWindowTraffic() {
    var own = policy("POST", "client", SENT);
    var other = policy("POST", "other", SENT.plusSeconds(1));
    var messages = List.of(own, other, policy("POST", "client", SENT.minusSeconds(1)),
        policy("POST", "client", SENT.plusSeconds(3)), policy("GET", "client", SENT.plusSeconds(1)));
    assertEquals(List.of(own), LinuxSoftwareAttestationSteps.policyRequests(messages, SENT,
        SENT.plusSeconds(2), "/policy", "client"));
    assertEquals(List.of(own, other), LinuxSoftwareAttestationSteps.policyRequests(messages, SENT,
        SENT.plusSeconds(2), "/policy", null));
    assertThrows(AssertionError.class, () -> LinuxSoftwareAttestationSteps.policyRequests(messages,
        SENT.plusSeconds(2), SENT, "/policy", null));
  }

  @Test
  void responseStepsAccumulateIndependentFailuresWithoutSkippingLaterChecks() throws Exception {
    var request = policy("POST", "client", SENT);
    var response = RbelLogger.build().getRbelConverter().parseMessage(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 25\r\n\r\n{\"access_token\":\"issued\"}"
            .getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    response.addOrReplaceFacet(TracingMessagePairFacet.builder().request(request)
        .responses(List.of(response)).build());
    var retriever = mock(RbelMessageRetriever.class);
    when(retriever.getCurrentRequest()).thenReturn(request);
    SoftAssertionsContext.reset();
    try (var retrievers = mockStatic(RbelMessageRetriever.class);
         var messages = mockStatic(RecordedMessages.class)) {
      retrievers.when(RbelMessageRetriever::getInstance).thenReturn(retriever);
      messages.when(RecordedMessages::snapshot).thenReturn(List.of(request, response));
      var steps = new LinuxSoftwareAttestationSteps();
      steps.verifyResponseStatus(400);
      steps.verifyResponseSchema("$.body", featureSchema("\"anyOf\""));
      assertEquals(2, SoftAssertionsContext.softly().errorsCollected().size());
      assertThrows(AssertionError.class, SoftAssertionsContext::assertAll);
    } finally {
      SoftAssertionsContext.reset();
    }
  }

  private static RbelElement policy(String method, String client, Instant sent) {
    var body = "{\"input\":{\"client_registration_data\":{\"client_id\":\"" + client + "\"}}}";
    var raw = method + " /policy HTTP/1.1\r\nHost: guard.example\r\nContent-Type: application/json\r\n"
        + "Content-Length: " + body.length() + "\r\n\r\n" + body;
    var request = RbelLogger.build().getRbelConverter().parseMessage(
        raw.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
    request.addOrReplaceFacet(new RbelMessageTimingFacet(sent.atZone(ZoneOffset.UTC)));
    return request;
  }

  private static String featureSchema(String marker) throws Exception {
    var feature = Path.of("src/test/resources/features/UserStory_01/UseCase_06/"
        + "client_authentisierung_und_autorisierung_software_attest_sc_200.feature");
    try (var messages = GherkinParser.builder().build().parse(feature)) {
      return messages.flatMap(message -> message.getPickle().stream())
          .filter(pickle -> pickle.getName().equals("Client Assertion JWT enthält Software Attestation für Linux"))
          .flatMap(pickle -> pickle.getSteps().stream())
          .flatMap(step -> step.getArgument().stream())
          .flatMap(argument -> argument.getDocString().stream())
          .map(PickleDocString::getContent).filter(schema -> schema.contains(marker))
          .findFirst().orElseThrow();
    }
  }
}
