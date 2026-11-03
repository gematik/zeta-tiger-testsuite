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
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks policy data against independently authenticated SDK inputs, including a failed proof. */
public class SmcbPolicyInputSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SchemaRegistry SCHEMAS =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);

  record Identity(String identifier, String commonName, String profession, String organization) {}

  record Registration(JsonNode request, JsonNode response, Instant sent, Instant completed) {}

  record Exchange(
      String request,
      JsonNode statement,
      Registration registration,
      Instant sent,
      Instant completed) {}

  record Policy(
      String method,
      String path,
      String input,
      int status,
      String response,
      Instant sent,
      Instant completed) {}

  record Observations(List<Exchange> exchanges, List<Policy> policies) {}

  /** Evaluates mappings without using the output's user/client/scope values for correlation. */
  @Dann(
      "alle validierten SMC-B-Policy-Inputs ab Client {tigerResolvedString} mit JWKS"
          + " {tigerResolvedString} und Zertifikat {tigerResolvedString} stimmen mit"
          + " Softwareversion {tigerResolvedString} und angefordertem"
          + " Scope {tigerResolvedString} überein mit soft assert")
  public void verifyInput(
      String client, String jwks, String certificate, String version, String scope)
      throws Exception {
    var identity = identity(certificate);
    var observed = observe(client, jwks, certificate, identity, "none", version, scope);
    var failures = evaluate(observed, identity);
    ReportAttachments.addText(
        "Validated SMC-B policy input",
        observed.exchanges().size()
            + " independently validated SDK exchanges; "
            + observed.policies().size()
            + " policy observations.\n"
            + (failures.isEmpty()
                ? "All input mappings and order checks passed."
                : String.join("\n", failures)));
    if (!failures.isEmpty()) {
      SoftAssertionsContext.recordSoftFailure(
          "Validated SMC-B policy input", new AssertionError(String.join("\n", failures)));
    }
  }

  /** Valid credentials except one signature must not reach policy evaluation. */
  @Dann(
      "die isoliert ungültige SMC-B-Assertion ab Client {tigerResolvedString} mit JWKS "
          + "{tigerResolvedString} und Zertifikat {tigerResolvedString} "
          + "wird ohne Policy und Tokens abgewiesen mit soft assert")
  public void verifyInvalidProof(String client, String jwks, String certificate) throws Exception {
    // Hard prerequisites precede response assertions, including SDK re-registrations/retries.
    var observed =
        observe(client, jwks, certificate, identity(certificate), "client_assertion", null, null);
    var results = new SoftAssertions();
    results
        .assertThat(observed.policies().size())
        .as("Invalid proof must not reach policy")
        .isZero();
    results
        .assertThatCode(
            () ->
                new SmbExchangeSteps()
                    .verifyReregisteredHistory(
                        client, jwks, "client_assertion", 401, "invalid_client"))
        .as("Every invalid-proof response must satisfy the unchanged rejection contract")
        .doesNotThrowAnyException();
    try {
      results.assertAll();
    } catch (AssertionError error) {
      SoftAssertionsContext.recordSoftFailure("Invalid proof before policy", error);
    }
  }

  static Identity identity(String certificate) {
    var source = SmcbSteps.extractSmcbInfo(certificate);
    var reference =
        new Identity(
            source.telematikId,
            source.commonName,
            source.professionId,
            source.organizationName == null ? "" : source.organizationName);
    for (var value :
        new String[] {reference.identifier(), reference.commonName(), reference.profession()}) {
      require(
          value != null && !value.isBlank() && !value.contains("${"),
          "Missing independent SMC-B identity reference");
    }
    require(!reference.organization().contains("${"), "Unresolved SMC-B organization reference");
    return reference;
  }

  private static Observations observe(
      String initialClient,
      String jwks,
      String certificate,
      Identity identity,
      String fault,
      String version,
      String scope)
      throws Exception {
    var messages = RecordedMessages.snapshot();
    var registrations = new HashMap<String, Registration>();
    final var exchanges = new ArrayList<Exchange>();
    var policies = new ArrayList<Policy>();
    var expectedKeys = JWKSet.parse(jwks).getKeys();
    require(expectedKeys.size() == 1, "Missing independent SDK registration key");
    for (var request : messages) {
      if (request.findRbelPathMembers("$.method").size() != 1) {
        continue;
      }
      var path = value(request, "$.path");
      if (path.equals(config("paths.opa.decisionPath")) || path.equals("/v1/data/authz")) {
        var response = new TimingGlue().findResponseForRequest(messages, request);
        policies.add(
            new Policy(
                value(request, "$.method"),
                path,
                value(request, "$.body"),
                response == null ? 0 : Integer.parseInt(value(response, "$.responseCode")),
                response == null ? "" : value(response, "$.body"),
                time(request),
                response == null ? null : time(response)));
      }
      if (!path.equals(config("paths.guard.registerEndpointPath"))) {
        continue;
      }
      var registration = ParRequestSteps.observation(request);
      require(
          "POST".equals(registration.method())
              && registration
                  .target()
                  .toString()
                  .equals(
                      "https://"
                          + config("zeta_base_url")
                          + config("paths.guard.registerEndpointPath")),
          "Registration was not issued to the actual AS");
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(
          response != null && "201".equals(value(response, "$.responseCode")),
          "Missing successful SDK registration");
      var input = object(registration.body());
      var output = object(value(response, "$.body"));
      var registered = JWKSet.parse(input.path("jwks").toString()).getKeys();
      require(
          registered.size() == 1
              && !registered.getFirst().isPrivate()
              && registered
                  .getFirst()
                  .computeThumbprint()
                  .equals(expectedKeys.getFirst().computeThumbprint()),
          "Registration belongs to another SDK key");
      var client = output.path("client_id").asString();
      require(
          client != null
              && !client.isBlank()
              && output.path("client_id_issued_at").isIntegralNumber(),
          "Registration lacks its own client ID or issuance timestamp");
      require(
          registrations.putIfAbsent(
                  client, new Registration(input, output, registration.sent(), time(response)))
              == null,
          "Ambiguous SDK registration");
    }
    var initial = registrations.get(initialClient);
    require(
        initial != null
            && registrations.values().stream().allMatch(r -> !r.sent().isBefore(initial.sent())),
        "Initial SDK registration is not the first context");
    for (var request : messages) {
      if (request.findRbelPathMembers("$.method").size() != 1
          || !config("paths.guard.tokenEndpointPath").equals(value(request, "$.path"))) {
        continue;
      }
      var token = ParRequestSteps.observation(request);
      var endpoint = "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath");
      require(
          "POST".equals(token.method())
              && endpoint.equals(token.target().toString())
              && token.contentType() != null
              && token
                  .contentType()
                  .matches("(?i)application/x-www-form-urlencoded(?:;\\s*charset=UTF-8)?"),
          "Unexpected SDK token request method, target or media type");
      var client =
          SmbExchangeSteps.verifyProofs(
              token.body(),
              value(request, "$.header.[~'DPoP']"),
              jwks,
              fault,
              token.sent(),
              endpoint);
      var registration = registrations.get(client);
      require(
          registration != null && !registration.completed().isAfter(token.sent()),
          "Token request has no own preceding SDK registration");
      var form = SmbExchangeSteps.form(token.body());
      if ("none".equals(fault)) {
        verifyRequestedScope(token.body(), scope);
      }
      var subject = SignedJWT.parse(form.get("subject_token"));
      SmcbPolicySteps.verifyCertificate(subject, certificate);
      require(
          identity.identifier().equals(subject.getJWTClaimsSet().getSubject()),
          "Subject identity differs from configured SMC-B certificate");
      SmbExchangeSteps.verifyNonceHistory(messages, request, fault);
      var assertion = SignedJWT.parse(form.get("client_assertion"));
      var statement = JSON.readTree(assertion.getPayload().toString()).path("client_statement");
      require(
          schemaValid(statement, "v_2_0/schemas/client-statement.yaml")
              && schemaValid(statement.path("posture"), "v_2_0/schemas/posture-software.yaml")
              && client.equals(statement.path("sub").asString())
              && "software".equals(statement.path("posture_type").asString()),
          "Independent signed software statement is malformed");
      verifyPins(registration.request(), statement);
      require(
          version == null
              || (version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                  && version.equals(statement.path("posture").path("product_version").asString())),
          "The controlled, correctly signed software version was not transmitted");
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(response != null, "SDK exchange has no captured AS response");
      exchanges.add(
          new Exchange(token.body(), statement, registration, token.sent(), time(response)));
    }
    require(
        !exchanges.isEmpty()
            && exchanges.stream()
                .anyMatch(
                    e -> initialClient.equals(SmbExchangeSteps.form(e.request()).get("client_id"))),
        "No exchange from initial SDK context");
    return new Observations(List.copyOf(exchanges), List.copyOf(policies));
  }

  static void verifyPins(JsonNode registration, JsonNode statement) {
    for (var field : List.of("product_id", "platform", "attestation_type")) {
      if (!registration.has(field)) {
        continue;
      }
      var asserted =
          switch (field) {
            case "product_id" -> statement.path("posture").path("product_id");
            case "attestation_type" -> statement.path("posture_type");
            default -> statement.path(field);
          };
      require(
          registration.path(field).isString()
              && asserted.isString()
              && !registration.path(field).asString().isBlank()
              && registration.path(field).equals(asserted),
          "Independent client statement conflicts with its DCR pin: " + field);
    }
  }

  static void verifyRequestedScope(String request, String scope) {
    require(
        scope != null
            && !scope.isBlank()
            && !scope.contains("${")
            && scope.equals(SmbExchangeSteps.form(request).get("scope")),
        "The independently selected requested scope was not transmitted");
  }

  static List<String> evaluate(Observations observed, Identity identity) {
    var failures = new ArrayList<String>();
    if (observed.exchanges().isEmpty()) {
      return List.of("No validated SDK exchange; input assertions cannot be evaluated");
    }
    var used = new HashSet<Integer>();
    for (var exchange : observed.exchanges()) {
      var indexes = new ArrayList<Integer>();
      for (int i = 0; i < observed.policies().size(); i++) {
        var p = observed.policies().get(i);
        if (validInterval(exchange.sent(), exchange.completed())
            && validInterval(p.sent(), p.completed())
            && p.sent().isAfter(exchange.sent())
            && p.completed().isBefore(exchange.completed())) {
          indexes.add(i);
        }
      }
      if (indexes.size() != 1) {
        failures.add(
            "Expected exactly one policy request and completed decision inside each exchange");
      }
      if (indexes.isEmpty()) {
        failures.add("Missing correlated policy input; data assertions cannot be evaluated");
      }
      for (var index : indexes) {
        if (!used.add(index)) {
          failures.add("A policy decision was reused for multiple SDK exchanges");
        }
        failures.addAll(mappingFailures(exchange, observed.policies().get(index), identity));
      }
    }
    if (used.size() != observed.policies().size()) {
      failures.add("Extra, late or uncorrelated policy observations remain");
    }
    return List.copyOf(failures);
  }

  static List<String> mappingFailures(Exchange exchange, Policy policy, Identity identity) {
    var failures = new ArrayList<String>();
    check("POST".equals(policy.method()), "Policy request must use POST", failures);
    // The assigned diagram AND explanation explicitly name this route. Keep the mismatch
    // visible instead of silently adopting the deployment's configured path as the specification.
    check(
        "/v1/data/authz".equals(policy.path()),
        "Policy route differs from assigned /v1/data/authz",
        failures);
    check(policy.status() == 200, "Policy decision must have HTTP 200", failures);
    JsonNode input;
    try {
      input = object(policy.input()).path("input");
      check(input.isObject(), "Policy input must be an object", failures);
    } catch (AssertionError error) {
      failures.add("Malformed policy request; data assertions cannot be evaluated");
      return failures;
    }
    check(
        schemaValid(input, "v_2_0/schemas/policy-engine-input.yaml"),
        "Upstream policy-input schema violated",
        failures);
    try {
      check(
          schemaValid(object(policy.response()), "v_2_0/schemas/pdp-decision.yaml"),
          "Policy decision schema violated",
          failures);
    } catch (AssertionError error) {
      failures.add("Malformed policy decision");
    }
    final var form = SmbExchangeSteps.form(exchange.request());
    var user = input.path("user_info");
    equalText(user, "identifier", identity.identifier(), failures);
    equalText(user, "commonName", identity.commonName(), failures);
    equalText(user, "professionOID", identity.profession(), failures);
    if (identity.organization().isEmpty()) {
      check(
          !user.has("organizationName"),
          "Unexpected organizationName without certificate source",
          failures);
    } else {
      equalText(user, "organizationName", identity.organization(), failures);
    }
    var client = input.path("client_registration_data");
    var registration = exchange.registration();
    var statement = exchange.statement();
    var posture = statement.path("posture");
    equalText(client, "client_id", form.get("client_id"), failures);
    equalText(
        client,
        "product_id",
        registration.request().has("product_id")
            ? registration.request().path("product_id").asString()
            : posture.path("product_id").asString(),
        failures);
    equalText(
        client,
        "platform",
        registration.request().has("platform")
            ? registration.request().path("platform").asString()
            : statement.path("platform").asString(),
        failures);
    equalText(
        client,
        "posture_type",
        registration.request().has("attestation_type")
            ? registration.request().path("attestation_type").asString()
            : statement.path("posture_type").asString(),
        failures);
    equalText(client, "product_version", posture.path("product_version").asString(), failures);
    var timestamp = client.path("registration_timestamp");
    var issued = registration.response().path("client_id_issued_at");
    check(
        timestamp.isIntegralNumber()
            && issued.isIntegralNumber()
            && timestamp
                    .bigIntegerValue()
                    .subtract(issued.bigIntegerValue())
                    .abs()
                    .compareTo(BigInteger.ONE)
                <= 0,
        "registration_timestamp differs from own DCR issuance time (one-second precision)",
        failures);
    if (client.has("attestation_timestamp")) {
      check(
          client.path("attestation_timestamp").equals(statement.path("attestation_timestamp")),
          "attestation_timestamp differs from signed statement",
          failures);
    }
    equalText(
        client.path("device_info"), "os_version", posture.path("os_version").asString(), failures);
    // Schema permits normalized OS naming; exact spelling is not required here.
    check(
        client.path("device_info").path("os").isString()
            && client
                .path("device_info")
                .path("os")
                .asString()
                .equalsIgnoreCase(posture.path("os").asString()),
        "device_info.os differs from signed software platform",
        failures);
    var request = input.path("authorization_request");
    equalText(request, "grant_type", form.get("grant_type"), failures);
    Set<String> scopes =
        form.getOrDefault("scope", "").isEmpty()
            ? Set.of()
            : new HashSet<>(List.of(form.get("scope").split(" ")));
    check(
        stringSet(request.path("scopes")).equals(scopes)
            && request.path("scopes").isArray()
            && request.path("scopes").size() == scopes.size(),
        "authorization_request.scopes differ from requested scope set",
        failures);
    if (form.containsKey("resource")) {
      equalText(input, "version", "1.1", failures);
      equalText(request, "resource", form.get("resource"), failures);
      check(!request.has("audience"), "Resource input must omit legacy audience", failures);
    } else {
      equalText(input, "version", "1.0", failures);
      check(
          form.get("audience") != null
              && request.path("audience").isArray()
              && request.path("audience").size() == 1
              && request.path("audience").get(0).isString()
              && form.get("audience").equals(request.path("audience").get(0).asString()),
          "Legacy input audience differs from own request",
          failures);
      check(!request.has("resource"), "Legacy input must omit resource", failures);
    }
    return List.copyOf(failures);
  }

  private static Set<String> stringSet(JsonNode array) {
    if (!array.isArray() || array.valueStream().anyMatch(n -> !n.isString())) {
      return Set.of("<invalid-array>");
    }
    return new HashSet<>(array.valueStream().map(JsonNode::asString).toList());
  }

  private static void equalText(
      JsonNode parent, String field, String reference, List<String> failures) {
    check(
        reference != null
            && parent.path(field).isString()
            && reference.equals(parent.path(field).asString()),
        field + " differs from its independent source",
        failures);
  }

  private static boolean schemaValid(JsonNode input, String path) {
    return SCHEMAS
        .getSchema(SchemaLocation.of("classpath:schemas/" + path))
        .validate(input)
        .isEmpty();
  }

  private static JsonNode object(String text) {
    try {
      var node = JSON.readTree(text);
      require(node != null && node.isObject(), "Expected a JSON object");
      return node;
    } catch (RuntimeException error) {
      throw new AssertionError("Malformed JSON observation");
    }
  }

  private static boolean validInterval(Instant start, Instant end) {
    return start != null && end != null && !end.isBefore(start);
  }

  private static Instant time(RbelElement element) {
    return element
        .getFacet(RbelMessageTimingFacet.class)
        .orElseThrow()
        .getTransmissionTime()
        .toInstant();
  }

  private static String value(RbelElement element, String path) {
    var values = element.findRbelPathMembers(path);
    require(values.size() == 1, "Missing or ambiguous observation: " + path);
    return values.getFirst().getRawStringContent();
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void check(boolean ok, String message, List<String> failures) {
    if (!ok) {
      failures.add(message);
    }
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new AssertionError(message);
    }
  }
}
