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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SmcbPolicyInputStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.ofEpochSecond(1800000000);
  private static final SmcbPolicyInputSteps.Identity IDENTITY =
      new SmcbPolicyInputSteps.Identity("1-test", "Testpraxis", "1.2.276.0.76.4.50", "Testbetrieb");
  private static final String DECISION =
      "{\"result\":{\"allow\":true,\"ttl\":{\"access_token\":60,\"refresh_token\":120}}}";
  private static final String FORM =
      "client_id=own&grant_type="
          + "urn:ietf:params:oauth:grant-type:token-exchange&scope=read+write&audience=https://resource.example/";

  @Test
  void readsIdentityDirectlyWithTheExistingCertificateParser() throws Exception {
    assertEquals(IDENTITY, SmcbPolicyInputSteps.identity(certificate(true, true)));
    assertThrows(AssertionError.class, () -> SmcbPolicyInputSteps.identity("not-a-certificate"));
  }

  @Test
  void absentCertificateOrganizationIsOptionalButAdmissionIdentityIsRequired() throws Exception {
    assertEquals(
        new SmcbPolicyInputSteps.Identity("1-test", "Testpraxis", "1.2.276.0.76.4.50", ""),
        SmcbPolicyInputSteps.identity(certificate(false, true)));
    var missingAdmission = certificate(true, false);
    assertThrows(AssertionError.class, () -> SmcbPolicyInputSteps.identity(missingAdmission));
  }

  private static String certificate(boolean organization, boolean admission) throws Exception {
    var key = new ECKeyGenerator(Curve.P_256).generate();
    var name = new X500Name("CN=Testpraxis" + (organization ? ",O=Testbetrieb" : ""));
    var builder =
        new JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date.from(NOW.minusSeconds(60)),
            Date.from(NOW.plusSeconds(600)),
            name,
            key.toECPublicKey());
    if (admission) {
      var profession =
          new DERSequence(
              new ASN1Encodable[] {
                new DERSequence(new DERPrintableString("Arztpraxis")),
                new DERSequence(new ASN1ObjectIdentifier("1.2.276.0.76.4.50")),
                new DERPrintableString("1-test")
              });
      builder.addExtension(
          new ASN1ObjectIdentifier("1.3.36.8.3.3"),
          false,
          new DERSequence(new DERSequence(new DERSequence(new DERSequence(profession)))));
    }
    return Base64.getEncoder()
        .encodeToString(
            builder
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(key.toECPrivateKey()))
                .getEncoded());
  }

  private static SmcbPolicyInputSteps.Exchange exchange() {
    var registration =
        new SmcbPolicyInputSteps.Registration(
            JSON.readTree("{}"),
            JSON.readTree("{\"client_id\":\"own\",\"client_id_issued_at\":1800000000}"),
            NOW.minusSeconds(2),
            NOW.minusSeconds(1));
    var statement =
        JSON.readTree(
            """
            {"sub":"own","platform":"linux","posture_type":"software",
             "attestation_timestamp":1800000000,"posture":{"product_id":"test-product",
             "product_version":"9.9.91","os":"Linux","os_version":"6.1","arch":"x86_64"}}
            """);
    return new SmcbPolicyInputSteps.Exchange(
        FORM, statement, registration, NOW, NOW.plusSeconds(3));
  }

  private static ObjectNode input() {
    return (ObjectNode)
        JSON.readTree(
            """
            {"input":{"version":"1.0","user_info":{"identifier":"1-test",
            "commonName":"Testpraxis","professionOID":"1.2.276.0.76.4.50",
            "organizationName":"Testbetrieb"},"client_registration_data":{"client_id":"own",
            "product_id":"test-product","product_version":"9.9.91","platform":"linux",
            "posture_type":"software","registration_timestamp":1800000000,
            "attestation_timestamp":1800000000,"device_info":{"os":"linux","os_version":"6.1"},
            "attestation_result":{}},"authorization_request":{"scopes":["read","write"],
            "audience":["https://resource.example/"],"grant_type":"urn:ietf:params:oauth:grant-type:token-exchange",
            "ip_address":"192.0.2.1","previous_ip_address":"192.0.2.1",
            "acr":"gematik-ehealth-loa-substantial","amr":["urn:telematik:auth:sc"]}}}
            """);
  }

  @Test
  void confirmsTheIndependentScopeStimulusBeforeInspectingPolicyData() {
    SmcbPolicyInputSteps.verifyRequestedScope(FORM, "read write");
    SmcbPolicyInputSteps.verifyRequestedScope(
        FORM.replace("read+write", "read%20write"), "read write");
    for (var expected : List.of("read", "read write extra", "${missing}", "")) {
      assertThrows(
          AssertionError.class, () -> SmcbPolicyInputSteps.verifyRequestedScope(FORM, expected));
    }
    assertThrows(
        AssertionError.class,
        () ->
            SmcbPolicyInputSteps.verifyRequestedScope(
                FORM.replace("&scope=read+write", ""), "read write"));
  }

  @Test
  void legacyAndMatchingPinnedRegistrationsRemainValidPrerequisites() {
    var statement = exchange().statement();
    SmcbPolicyInputSteps.verifyPins(JSON.readTree("{}"), statement);
    SmcbPolicyInputSteps.verifyPins(
        JSON.readTree(
            """
            {"product_id":"test-product","platform":"linux","attestation_type":"software"}
            """),
        statement);
  }

  @ParameterizedTest
  @ValueSource(strings = {"product_id", "platform", "attestation_type"})
  void conflictingPinCannotBeMisclassifiedAsPolicyInputFailure(String field) {
    var registration = JSON.createObjectNode().put(field, "foreign");
    assertThrows(
        AssertionError.class,
        () -> SmcbPolicyInputSteps.verifyPins(registration, exchange().statement()));
    registration.putNull(field);
    assertThrows(
        AssertionError.class,
        () -> SmcbPolicyInputSteps.verifyPins(registration, exchange().statement()));
  }

  private static SmcbPolicyInputSteps.Policy policy(String input) {
    return new SmcbPolicyInputSteps.Policy(
        "POST", "/v1/data/authz", input, 200, DECISION, NOW.plusSeconds(1), NOW.plusSeconds(2));
  }

  private static List<String> evaluate(
      SmcbPolicyInputSteps.Exchange exchange, List<SmcbPolicyInputSteps.Policy> policies) {
    return SmcbPolicyInputSteps.evaluate(
        new SmcbPolicyInputSteps.Observations(List.of(exchange), policies), IDENTITY);
  }

  @Test
  void validInputMapsAuthenticatedSourcesAndIgnoresScopeOrder() {
    var body = input();
    ((ObjectNode) body.at("/input/authorization_request"))
        .putArray("scopes")
        .add("write")
        .add("read");
    assertTrue(evaluate(exchange(), List.of(policy(body.toString()))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "user_info/identifier",
        "user_info/commonName",
        "user_info/professionOID",
        "user_info/organizationName",
        "client_registration_data/client_id",
        "client_registration_data/product_id",
        "client_registration_data/product_version",
        "client_registration_data/platform",
        "client_registration_data/posture_type",
        "client_registration_data/device_info/os_version",
        "client_registration_data/device_info/os",
        "authorization_request/grant_type"
      })
  void rejectsChangedMappedFieldsWithoutFilteringOutWrongClient(String path) {
    var body = input();
    var full = "/input/" + path;
    ((ObjectNode) body.at(full.substring(0, full.lastIndexOf('/'))))
        .put(full.substring(full.lastIndexOf('/') + 1), "foreign");
    var failures = evaluate(exchange(), List.of(policy(body.toString())));
    assertFalse(failures.isEmpty());
    assertTrue(failures.stream().noneMatch(s -> s.contains("cannot be evaluated")));
    assertTrue(failures.stream().anyMatch(s -> s.contains("differs")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "[\"read\"]",
        "[\"read\",\"write\",\"email\"]",
        "[\"read\",\"read\",\"write\"]",
        "[\"read\",42]",
        "null",
        "\"read write\""
      })
  void rejectsMissingExtraDefaultDuplicatedOrMalformedRequestedScopes(String scopes) {
    var body = input();
    ((ObjectNode) body.at("/input/authorization_request")).set("scopes", JSON.readTree(scopes));
    assertTrue(
        evaluate(exchange(), List.of(policy(body.toString()))).stream()
            .anyMatch(s -> s.contains("scopes differ")));
  }

  @Test
  void optionalEmptyScopeIsAnEmptyArrayNotMissingOrMalformed() {
    var old = exchange();
    final var e =
        new SmcbPolicyInputSteps.Exchange(
            FORM.replace("&scope=read+write", ""),
            old.statement(),
            old.registration(),
            old.sent(),
            old.completed());
    var body = input();
    ((ObjectNode) body.at("/input/authorization_request")).putArray("scopes");
    assertTrue(evaluate(e, List.of(policy(body.toString()))).isEmpty());
    ((ObjectNode) body.at("/input/authorization_request")).remove("scopes");
    assertFalse(evaluate(e, List.of(policy(body.toString()))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void allowsOnlyTheDocumentedOneSecondTimestampPrecision(long delta) {
    var body = input();
    ((ObjectNode) body.at("/input/client_registration_data"))
        .put("registration_timestamp", 1800000000 + delta);
    assertTrue(evaluate(exchange(), List.of(policy(body.toString()))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1799999998",
        "1800000002",
        "1800007200",
        "1799992800",
        "18446744075509551616",
        "1800000000.0",
        "\"1800000000\"",
        "null"
      })
  void rejectsRegistrationTimezoneShiftsOverflowOrWrongTimestampTypes(String timestamp) {
    var body = input();
    ((ObjectNode) body.at("/input/client_registration_data"))
        .set("registration_timestamp", JSON.readTree(timestamp));
    assertTrue(
        evaluate(exchange(), List.of(policy(body.toString()))).stream()
            .anyMatch(s -> s.contains("registration_timestamp differs")));
  }

  @Test
  void checksAttestationTimestampWhenPresentWithoutMakingItRequired() {
    var body = input();
    var client = (ObjectNode) body.at("/input/client_registration_data");
    client.remove("attestation_timestamp");
    assertTrue(evaluate(exchange(), List.of(policy(body.toString()))).isEmpty());
    client.put("attestation_timestamp", 1800000100);
    assertTrue(
        evaluate(exchange(), List.of(policy(body.toString()))).stream()
            .anyMatch(s -> s.contains("attestation_timestamp differs")));
  }

  @Test
  void pinnedIdentityUsesRegistrationButDynamicVersionUsesSignedStatement() {
    var original = exchange();
    var request =
        JSON.readTree(
            "{\"product_id\":\"pinned-product\",\"platform\":\"windows\",\"attestation_type\":\"software\"}");
    var registration =
        new SmcbPolicyInputSteps.Registration(
            request,
            original.registration().response(),
            original.registration().sent(),
            original.registration().completed());
    final var e =
        new SmcbPolicyInputSteps.Exchange(
            FORM, original.statement(), registration, original.sent(), original.completed());
    assertFalse(evaluate(e, List.of(policy(input().toString()))).isEmpty());
    var body = input();
    ((ObjectNode) body.at("/input/client_registration_data"))
        .put("product_id", "pinned-product")
        .put("platform", "windows");
    assertTrue(evaluate(e, List.of(policy(body.toString()))).isEmpty());
    ((ObjectNode) body.at("/input/client_registration_data")).put("product_version", "old-version");
    assertTrue(
        evaluate(e, List.of(policy(body.toString()))).stream()
            .anyMatch(s -> s.contains("product_version differs")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"audience", "extra_audience", "resource", "version", "audience_type"})
  void keepsTheLegacyTargetAndContractVersion(String fault) {
    var body = input();
    var request = (ObjectNode) body.at("/input/authorization_request");
    switch (fault) {
      case "audience" -> request.putArray("audience").add("foreign");
      case "extra_audience" ->
          request.putArray("audience").add("https://resource.example/").add("foreign");
      case "resource" -> request.put("resource", "https://resource.example/");
      case "version" -> ((ObjectNode) body.path("input")).put("version", "1.1");
      case "audience_type" -> request.put("audience", "https://resource.example/");
      default -> throw new AssertionError("Unknown counterexample");
    }
    assertFalse(evaluate(exchange(), List.of(policy(body.toString()))).isEmpty());
  }

  @Test
  void resourceMappingIsDifferentFromLegacyAudience() {
    var original = exchange();
    final var e =
        new SmcbPolicyInputSteps.Exchange(
            FORM.replace("audience=", "resource="),
            original.statement(),
            original.registration(),
            original.sent(),
            original.completed());
    var body = input();
    var input = (ObjectNode) body.path("input");
    input.put("version", "1.1");
    var request = (ObjectNode) input.path("authorization_request");
    request.remove("audience");
    request.put("resource", "https://resource.example/");
    assertTrue(evaluate(e, List.of(policy(body.toString()))).isEmpty());
    request.putArray("audience").add("https://resource.example/");
    assertFalse(evaluate(e, List.of(policy(body.toString()))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "duplicate",
        "early",
        "late",
        "inverted",
        "missing_response",
        "extra",
        "shared",
        "wrong_method",
        "deployment_route",
        "decision_status",
        "malformed_decision",
        "same_request_boundary",
        "same_response_boundary"
      })
  void policyOrderingAndProtocolErrorsCannotHideBehindValidData(String fault) {
    var original = policy(input().toString());
    var p = original;
    var policies = List.of(p);
    var exchanges = List.of(exchange());
    switch (fault) {
      case "missing" -> policies = List.of();
      case "duplicate" -> policies = List.of(p, p);
      case "same_request_boundary" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(), p.path(), p.input(), 200, p.response(), NOW, p.completed());
      case "same_response_boundary" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(), p.path(), p.input(), 200, p.response(), p.sent(), NOW.plusSeconds(3));
      case "early" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(),
                  p.path(),
                  p.input(),
                  200,
                  p.response(),
                  NOW.minusSeconds(1),
                  p.completed());
      case "late" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(), p.path(), p.input(), 200, p.response(), p.sent(), NOW.plusSeconds(4));
      case "inverted" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(), p.path(), p.input(), 200, p.response(), p.completed(), p.sent());
      case "missing_response" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(), p.path(), p.input(), 0, "", p.sent(), null);
      case "extra" ->
          policies =
              List.of(
                  p,
                  new SmcbPolicyInputSteps.Policy(
                      p.method(),
                      p.path(),
                      p.input(),
                      200,
                      p.response(),
                      NOW.plusSeconds(4),
                      NOW.plusSeconds(5)));
      case "shared" -> exchanges = List.of(exchange(), exchange());
      case "wrong_method" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  "GET", p.path(), p.input(), 200, p.response(), p.sent(), p.completed());
      case "deployment_route" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(),
                  "/v1/data/policies/zeta/authz/decision",
                  p.input(),
                  200,
                  p.response(),
                  p.sent(),
                  p.completed());
      case "decision_status" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(), p.path(), p.input(), 500, p.response(), p.sent(), p.completed());
      case "malformed_decision" ->
          p =
              new SmcbPolicyInputSteps.Policy(
                  p.method(), p.path(), p.input(), 200, "[]", p.sent(), p.completed());
      default -> throw new AssertionError("Unknown counterexample");
    }
    if (p != original) {
      policies = List.of(p);
    }
    var result =
        SmcbPolicyInputSteps.evaluate(
            new SmcbPolicyInputSteps.Observations(exchanges, policies), IDENTITY);
    assertFalse(result.isEmpty());
    if (fault.equals("missing")) {
      assertTrue(result.stream().anyMatch(s -> s.contains("cannot be evaluated")));
    }
  }

  @Test
  void malformedInputReportsUnevaluatedDataWithoutPretendingPass() {
    var result = evaluate(exchange(), List.of(policy("not-json")));
    assertTrue(result.stream().anyMatch(s -> s.contains("data assertions cannot be evaluated")));
  }

  @Test
  void organizationIsAbsentOnlyWhenTheIndependentCertificateLacksIt() {
    var identity =
        new SmcbPolicyInputSteps.Identity(
            IDENTITY.identifier(), IDENTITY.commonName(), IDENTITY.profession(), "");
    var body = input();
    assertFalse(
        SmcbPolicyInputSteps.mappingFailures(exchange(), policy(body.toString()), identity)
            .isEmpty());
    ((ObjectNode) body.at("/input/user_info")).remove("organizationName");
    assertTrue(
        SmcbPolicyInputSteps.mappingFailures(exchange(), policy(body.toString()), identity)
            .isEmpty());
  }

  @Test
  void independentInputFailuresAreCollectedTogether() {
    var body = input();
    ((ObjectNode) body.at("/input/user_info")).put("identifier", "foreign").remove("commonName");
    ((ObjectNode) body.at("/input/client_registration_data")).put("product_version", "stale");
    var failures = evaluate(exchange(), List.of(policy(body.toString())));
    assertEquals(4, failures.size());
    assertTrue(failures.stream().anyMatch(s -> s.contains("identifier differs")));
    assertTrue(failures.stream().anyMatch(s -> s.contains("commonName differs")));
    assertTrue(failures.stream().anyMatch(s -> s.contains("product_version differs")));
  }
}
