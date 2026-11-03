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
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Correlates real SDK exchanges with their own injected policy denial and untouched AS response.
 */
public class SmcbPolicySteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SchemaRegistry SCHEMAS =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
  private static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";

  record TokenExchange(Instant sent, Instant completed, int status, String response) {}

  record PolicyExchange(
      Instant sent, Instant completed, int status, String input, String response) {}

  /** Checks every retry only after proving intact credentials and the intended policy stimulus. */
  @Dann(
      "alle SMC-B-Exchanges für Client {tigerResolvedString} mit JWKS {tigerResolvedString} "
          + "und SMC-B-Zertifikat {tigerResolvedString} erfüllen die Policy-Ablehnung mit Gründen "
          + "{tigerResolvedString} mit soft assert")
  public void verifyDenial(String client, String jwks, String certificate, String reasons)
      throws Exception {
    var expected = reasonFixture(reasons);
    var messages = RecordedMessages.snapshot();
    var tokens = new ArrayList<TokenExchange>();
    var policies = new ArrayList<PolicyExchange>();
    var endpoint = "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath");
    for (var request : messages) {
      if (request.findRbelPathMembers("$.method").size() != 1
          || !"POST".equals(value(request, "$.method"))) {
        continue;
      }
      var path = value(request, "$.path");
      if (!List.of(config("paths.guard.tokenEndpointPath"), config("paths.opa.decisionPath"))
          .contains(path)) {
        continue;
      }
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(response != null, "Missing correlated response in the completed SDK operation");
      var sent = timestamp(request);
      var completed = timestamp(response);
      int status = Integer.parseInt(value(response, "$.responseCode"));
      if (path.equals(config("paths.guard.tokenEndpointPath"))) {
        var observed = ParRequestSteps.observation(request);
        require(URI.create(endpoint).equals(observed.target()), "Token request targets another AS");
        require(
            observed.contentType() != null
                && observed
                    .contentType()
                    .matches("(?i)application/x-www-form-urlencoded(?:;\\s*charset=UTF-8)?"),
            "SDK token request has the wrong content type");
        var proof = value(request, "$.header.[~'DPoP']");
        require(
            client.equals(
                SmbExchangeSteps.verifyProofs(
                    observed.body(), proof, jwks, "none", sent, endpoint)),
            "A token request belongs to another SDK client");
        var subject = SignedJWT.parse(SmbExchangeSteps.form(observed.body()).get("subject_token"));
        verifyCertificate(subject, certificate);
        SmbExchangeSteps.verifyNonceHistory(messages, request, "none");
        tokens.add(new TokenExchange(sent, completed, status, value(response, "$.body")));
      } else {
        policies.add(
            new PolicyExchange(
                sent, completed, status, value(request, "$.body"), value(response, "$.body")));
      }
    }
    var paired = correlate(tokens, policies, client);
    // Invalid or missing stimuli are prerequisites, never evidence of product rejection behavior.
    paired.forEach(p -> verifyDenyDecision(p, expected));
    var failures = new ArrayList<String>();
    for (int index = 0; index < tokens.size(); index++) {
      final int number = index + 1;
      responseFailures(tokens.get(index), expected)
          .forEach(f -> failures.add("Exchange " + number + ": " + f));
    }
    SerenityReportUtils.addCustomData(
        "Correlated SDK policy denial",
        tokens.size()
            + " exchanges: intact independent credentials and AS nonces; one own policy"
            + " decision inside each exchange; exact reasons and token-free 403/access_denied"
            + " responses checked. Deviations: "
            + failures.size());
    if (!failures.isEmpty()) {
      SoftAssertionsContext.recordSoftFailure(
          "SMC-B policy denial", new AssertionError(String.join("\n", failures)));
    }
  }

  static void verifyCertificate(SignedJWT subject, String certificate) {
    require(
        certificate != null
            && !certificate.isBlank()
            && !certificate.contains("${")
            && subject.getHeader().getX509CertChain() != null
            && !subject.getHeader().getX509CertChain().isEmpty()
            && certificate.equals(subject.getHeader().getX509CertChain().getFirst().toString()),
        "Subject signer differs from the independently configured SMC-B certificate");
  }

  static JsonNode reasonFixture(String text) {
    var reasons = object(text, "Independent reasons fixture");
    require(
        !reasons.isEmpty()
            && reasons.properties().stream()
                .allMatch(
                    e ->
                        !e.getKey().isBlank()
                            && !e.getKey().contains("${")
                            && e.getValue().isBoolean()
                            && e.getValue().asBoolean()),
        "Reasons fixture must contain distinct nonempty reasons with boolean true values");
    return reasons;
  }

  static List<PolicyExchange> correlate(
      List<TokenExchange> tokens, List<PolicyExchange> policies, String client) {
    require(client != null && !client.isBlank() && !client.contains("${"), "Missing SDK client");
    require(!tokens.isEmpty(), "No SDK token exchange observed");
    var own =
        policies.stream()
            .filter(
                p -> {
                  var input = object(p.input(), "Policy request").path("input");
                  return input.path("client_registration_data").path("client_id").isString()
                      && client.equals(
                          input.path("client_registration_data").path("client_id").asString())
                      && GRANT.equals(
                          input.path("authorization_request").path("grant_type").asString());
                })
            .toList();
    require(
        own.size() == tokens.size(),
        "Missing, extra or foreign policy decisions for SDK exchanges");
    var paired = new ArrayList<PolicyExchange>();
    var used = new HashSet<PolicyExchange>();
    for (var token : tokens) {
      require(validInterval(token.sent(), token.completed()), "Invalid token exchange interval");
      var candidates =
          own.stream()
              .filter(
                  p ->
                      validInterval(p.sent(), p.completed())
                          && !p.sent().isBefore(token.sent())
                          && !p.completed().isAfter(token.completed()))
              .toList();
      require(
          candidates.size() == 1 && used.add(candidates.getFirst()),
          "Policy decision is absent, ambiguous, late or shared by multiple exchanges");
      paired.add(candidates.getFirst());
    }
    return List.copyOf(paired);
  }

  static void verifyDenyDecision(PolicyExchange policy, JsonNode reasons) {
    require(policy.status() == 200, "Policy endpoint did not deliver a successful decision");
    var body = object(policy.response(), "Policy decision");
    require(schemaValid(body, "pdp-decision.yaml"), "Injected decision violates its pinned schema");
    var result = body.path("result");
    require(
        result.path("allow").isBoolean()
            && !result.path("allow").asBoolean()
            && reasons.equals(result.path("reasons"))
            && !result.has("ttl")
            && !result.has("aud"),
        "Policy manipulation did not produce exactly the intended deny decision");
  }

  static List<String> responseFailures(TokenExchange token, JsonNode reasons) {
    var failures = new ArrayList<String>();
    if (token.status() != 403) {
      failures.add("Expected HTTP 403; observed " + token.status());
    }
    final JsonNode body;
    try {
      body = object(token.response(), "Token response");
    } catch (AssertionError ex) {
      failures.add("Token response is not a JSON object; body assertions cannot be evaluated");
      return failures;
    }
    if (!body.path("error").isString() || !"access_denied".equals(body.path("error").asString())) {
      failures.add("Expected error access_denied");
    }
    if (!reasons.equals(body.path("reasons"))) {
      failures.add("Response reasons differ from the correlated policy decision");
    }
    for (var field :
        List.of(
            "access_token",
            "refresh_token",
            "zeta_attestation_token",
            "asl_access_token",
            "id_token")) {
      if (body.has(field)) {
        failures.add("Forbidden token field present: " + field);
      }
    }
    for (var schema : List.of("zeta-error.yaml", "token-response.yaml")) {
      if (!schemaValid(body, schema)) {
        failures.add("Response violates schemas/v_2_0/schemas/" + schema);
      }
    }
    return List.copyOf(failures);
  }

  private static boolean schemaValid(JsonNode body, String schema) {
    // Always enforce the pinned schema; scenario-wide soft/additional-property switches cannot
    // turn a malformed policy stimulus into a valid prerequisite.
    return SCHEMAS
        .getSchema(SchemaLocation.of("classpath:schemas/v_2_0/schemas/" + schema))
        .validate(body)
        .isEmpty();
  }

  private static JsonNode object(String text, String label) {
    try {
      var node = JSON.readTree(text);
      require(node != null && node.isObject(), label + " is not a JSON object");
      return node;
    } catch (RuntimeException ex) {
      throw new AssertionError(label + " is not valid JSON");
    }
  }

  private static boolean validInterval(Instant sent, Instant completed) {
    return sent != null && completed != null && !completed.isBefore(sent);
  }

  private static Instant timestamp(RbelElement message) {
    return message
        .getFacet(RbelMessageTimingFacet.class)
        .orElseThrow()
        .getTransmissionTime()
        .toInstant();
  }

  private static String value(RbelElement message, String path) {
    var values = message.findRbelPathMembers(path);
    require(values.size() == 1, "Missing or ambiguous policy observation: " + path);
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
