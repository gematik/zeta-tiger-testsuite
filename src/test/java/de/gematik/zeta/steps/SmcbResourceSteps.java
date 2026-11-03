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

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Proves resource-token output against a controlled decision without replacing the SDK grant. */
public class SmcbResourceSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SchemaRegistry SCHEMAS =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);

  record Grant(SmcbPolicySteps.TokenExchange exchange, String client, String subject, String jkt) {}

  /** Requires intact SDK credentials and an exact own policy stimulus before evaluating output. */
  @Dann(
      "alle SDK-Resource-Grants für Client {tigerResolvedString} mit JWKS {tigerResolvedString}"
          + " und Zertifikat {tigerResolvedString} für Resource {tigerResolvedString} und Scope"
          + " {tigerResolvedString} übernehmen Entscheidung {tigerResolvedString} mit AS-JWKS"
          + " {tigerResolvedString} mit soft assert")
  public void verify(
      String client,
      String jwks,
      String certificate,
      String resource,
      String scope,
      String decision,
      String asJwks)
      throws Exception {
    var expected = decisionFixture(decision, resource);
    var messages = RecordedMessages.snapshot();
    var grants = new ArrayList<Grant>();
    var policies = new ArrayList<SmcbPolicySteps.PolicyExchange>();
    var endpoint = "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath");
    for (var request : messages) {
      if (request.findRbelPathMembers("$.method").size() != 1) {
        continue;
      }
      var path = value(request, "$.path");
      if (!List.of(
              config("paths.guard.tokenEndpointPath"),
              config("paths.opa.decisionPath"),
              "/v1/data/authz")
          .contains(path)) {
        continue;
      }
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(response != null, "SDK or policy operation lacks its correlated response");
      require("POST".equals(value(request, "$.method")), "Unexpected grant or policy method");
      int status = Integer.parseInt(value(response, "$.responseCode"));
      if (!path.equals(config("paths.guard.tokenEndpointPath"))) {
        policies.add(
            new SmcbPolicySteps.PolicyExchange(
                time(request),
                time(response),
                status,
                value(request, "$.body"),
                value(response, "$.body")));
        continue;
      }
      var observed = ParRequestSteps.observation(request);
      var form = ParRequestSteps.formAt(observed, URI.create(endpoint));
      require(
          resource.equals(form.get("resource")) && !form.containsKey("audience"),
          "SDK request did not carry exactly the prepared resource contract");
      SmcbPolicyInputSteps.verifyRequestedScope(observed.body(), scope);
      var proof = value(request, "$.header.[~'DPoP']");
      require(
          client.equals(
              SmbExchangeSteps.verifyProofs(
                  observed.body(), proof, jwks, "none", observed.sent(), endpoint)),
          "Resource grant belongs to another SDK client");
      var subject = SignedJWT.parse(form.get("subject_token"));
      SmcbPolicySteps.verifyCertificate(subject, certificate);
      SmbExchangeSteps.verifyNonceHistory(messages, request, "none");
      var jkt =
          ((ECKey) SignedJWT.parse(proof).getHeader().getJWK()).computeThumbprint().toString();
      grants.add(
          new Grant(
              new SmcbPolicySteps.TokenExchange(
                  observed.sent(), time(response), status, value(response, "$.body")),
              client,
              subject.getJWTClaimsSet().getSubject(),
              jkt));
    }
    var exchanges = grants.stream().map(Grant::exchange).toList();
    verifyPolicyStimuli(exchanges, policies, client, expected);
    var failures = new ArrayList<String>();
    for (int i = 0; i < grants.size(); i++) {
      for (var error : outputFailures(grants.get(i), expected, resource, asJwks)) {
        failures.add("Exchange " + (i + 1) + ": " + error);
      }
    }
    ReportAttachments.addText(
        "Resource contract issuance",
        grants.size()
            + " independently valid SDK grants and exact own allow decisions.\n"
            + (failures.isEmpty() ? "All output assertions passed." : String.join("\n", failures)));
    if (!failures.isEmpty()) {
      SoftAssertionsContext.recordSoftFailure(
          "Resource contract issuance", new AssertionError(String.join("\n", failures)));
    }
  }

  static JsonNode decisionFixture(String text, String resource) {
    var result = object(text);
    require(
        result.path("allow").isBoolean()
            && result.path("allow").asBoolean()
            && result.path("ttl").path("access_token").isIntegralNumber()
            && result.path("ttl").path("access_token").bigIntegerValue().signum() > 0
            && result.path("ttl").path("refresh_token").isIntegralNumber()
            && result.path("ttl").path("refresh_token").bigIntegerValue().signum() > 0
            && !result.has("reasons"),
        "Missing independently prepared allow/TTL decision");
    var wrapped = JSON.createObjectNode().set("result", result);
    require(
        SCHEMAS
            .getSchema(SchemaLocation.of("classpath:schemas/v_2_0/schemas/pdp-decision.yaml"))
            .validate(wrapped)
            .isEmpty(),
        "Policy fixture violates the pinned decision schema");
    var reference = JSON.createObjectNode().put("ver", 2).set("aud", result.get("aud"));
    require(
        !new TokenClaimMappingSteps()
            .evaluateResourceIssuanceClaims(reference.toString(), wrapped.toString(), resource)
            .troubleFound(),
        "Invalid audience fixture");
    return result;
  }

  static void verifyPolicyStimuli(
      List<SmcbPolicySteps.TokenExchange> tokens,
      List<SmcbPolicySteps.PolicyExchange> policies,
      String client,
      JsonNode expected) {
    var paired = SmcbPolicySteps.correlate(tokens, policies, client);
    require(policies.size() == tokens.size(), "Extra foreign policy observation");
    for (int i = 0; i < tokens.size(); i++) {
      var token = tokens.get(i);
      var policy = paired.get(i);
      require(
          policy.sent().isAfter(token.sent()) && policy.completed().isBefore(token.completed()),
          "Policy must complete strictly inside the own grant");
      require(
          policy.status() == 200 && expected.equals(object(policy.response()).path("result")),
          "The own policy response does not contain the exact prepared allow decision");
    }
  }

  static List<String> outputFailures(
      Grant grant, JsonNode decision, String resource, String asJwks) {
    var errors = new ArrayList<String>();
    var exchange = grant.exchange();
    if (exchange.status() != 200) {
      errors.add("status: allow decision must issue a successful token response");
    }
    final JsonNode body;
    final SignedJWT access;
    try {
      body = object(exchange.response());
      require(
          body.path("access_token").isString() && !body.path("access_token").asString().isBlank(),
          "No access token");
      access = SignedJWT.parse(body.path("access_token").asString());
    } catch (Exception | AssertionError error) {
      errors.add("access_token: missing or malformed; signature/audience/version not evaluated");
      return List.copyOf(errors);
    }
    try {
      new SignatureVerificationSteps().verifyJwtSignatureFromKid(access.serialize(), asJwks);
    } catch (AssertionError error) {
      errors.add("signature: access token does not verify with the independently fetched AS keys");
    }
    final JsonNode claims;
    try {
      claims = object(access.getPayload().toString());
    } catch (AssertionError error) {
      errors.add("claims: malformed; audience/version not evaluated");
      return List.copyOf(errors);
    }
    if (!grant.client().equals(claims.path("client_id").asString())
        || !grant.subject().equals(claims.path("sub").asString())
        || !grant.jkt().equals(claims.path("cnf").path("jkt").asString())) {
      errors.add("binding: access token does not belong to this authenticated SDK grant");
    }
    var report =
        new TokenClaimMappingSteps()
            .evaluateResourceIssuanceClaims(
                claims.toString(),
                JSON.createObjectNode().set("result", decision).toString(),
                resource);
    if (report.troubleFound()) {
      errors.add(report.report());
    }
    return List.copyOf(errors);
  }

  private static JsonNode object(String value) {
    try {
      var result = JSON.readTree(value);
      require(result != null && result.isObject(), "Expected JSON object");
      return result;
    } catch (RuntimeException error) {
      throw new AssertionError("Malformed JSON object");
    }
  }

  private static Instant time(RbelElement value) {
    return value
        .getFacet(RbelMessageTimingFacet.class)
        .orElseThrow()
        .getTransmissionTime()
        .toInstant();
  }

  private static String value(RbelElement element, String path) {
    var values = element.findRbelPathMembers(path);
    require(values.size() == 1, "Missing or ambiguous resource evidence: " + path);
    return values.getFirst().getRawStringContent();
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
