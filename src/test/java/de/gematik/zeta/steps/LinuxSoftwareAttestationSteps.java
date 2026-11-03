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

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.json.JsonSchemaChecker;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.rbel.RequestParameter;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks software fallback data and its own policy/AS outcome without replacing SDK operations. */
public class LinuxSoftwareAttestationSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SOFTWARE_STATEMENT_SCHEMA = """
      {
        "type":"object", "required":["sub","platform","posture_type","posture","attestation_timestamp"],
        "properties":{
          "platform":{"const":"linux"}, "posture_type":{"const":"software"},
          "posture":{
            "allOf":[{"$ref":"classpath:schemas/v_2_0/schemas/posture-software.yaml"}],
            "properties":{"platform_product_id":{
              "$ref":"classpath:schemas/v_2_0/schemas/product-id-linux.yaml"}}
          }
        }
      }
      """;
  private RbelElement verifiedExchange;

  /** Checks only the selected exchange's independent signatures, nonces and key bindings. */
  @Dann("prüfe die unabhängigen Nachweise des Software-Exchange zum registrierten JWKS {tigerResolvedString}")
  public void verifyRequestProofs(String jwks) throws Exception {
    var request = currentExchange();
    var messages = RecordedMessages.snapshot();
    var observed = ParRequestSteps.observation(request);
    var endpoint = "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath");
    require("POST".equals(observed.method()) && endpoint.equals(observed.target().toString()),
        "Selected exchange has the wrong method or endpoint");
    SmbExchangeSteps.verifyProofs(observed.body(), value(request, "$.header.[~'DPoP']"),
        jwks, "none", observed.sent(), endpoint);
    SmbExchangeSteps.verifyNonceHistory(messages, request, "none");
    verifiedExchange = request;
  }

  /** Validates only the signed Linux/software statement and its timestamp at transmission. */
  @Dann("prüfe das signierte Linux-Software-Statement ist schemakonform")
  public void verifyValidStatement() throws Exception {
    verifyStatement("gültig", null);
  }

  /** Verifies one absent required field while validating all remaining input conditions. */
  @Dann("prüfe dem Linux-Software-Statement fehlt ausschließlich os_version bei ursprünglichem Wert {tigerResolvedString}")
  public void verifyMissingOsVersion(String originalOsVersion) throws Exception {
    verifyStatement("os_version_fehlend", originalOsVersion);
  }

  private void verifyStatement(String state, String originalOsVersion) throws Exception {
    var request = currentExchange();
    require(request == verifiedExchange, "Software exchange proofs have not been verified");
    var form = SmbExchangeSteps.form(value(request, "$.body"));
    var claims = JSON.readTree(SignedJWT.parse(form.get("client_assertion")).getPayload().toString());
    var statement = validateStatement(claims, state, originalOsVersion, time(request));
    require(form.get("client_id").equals(statement.path("sub").asString()),
        "Software statement has another client");
  }

  /** Checks the software public key independently of the policy and token outcomes. */
  @Dann("prüfe Software-Schlüssel {tigerResolvedString} gehört zum registrierten JWKS {tigerResolvedString}")
  public void verifySoftwareKey(String encoded, String jwks) throws Exception {
    verifyPostureKey(encoded, jwks);
  }

  /** Selects exactly one policy pair inside the verified exchange's request/response window. */
  @Dann("wähle die einzige Policy-Transaktion des geprüften Software-Exchange")
  public void selectPolicyTransaction() {
    var exchange = currentExchange();
    require(exchange == verifiedExchange, "Software exchange proofs have not been verified");
    var messages = RecordedMessages.snapshot();
    var response = responseTo(messages, exchange);
    var client = SmbExchangeSteps.form(value(exchange, "$.body")).get("client_id");
    var policies = policyRequests(messages, time(exchange), time(response),
        config("paths.opa.decisionPath"), client);
    require(policies.size() == 1, "Expected exactly one policy transaction for this software client");
    var policy = policies.getFirst();
    require(!time(responseTo(messages, policy)).isAfter(time(response)),
        "Policy decision did not complete before the AS response");
    var retriever = RbelMessageRetriever.getInstance();
    retriever.filterRequestsAndStoreInContext(RequestParameter.builder()
        .path(config("paths.opa.decisionPath")).method("POST").requireRequestMessage(true)
        .rbelPath("$.body").value(Pattern.quote(value(policy, "$.body"))).build());
    require(policy.getUuid().equals(retriever.getCurrentRequest().getUuid()),
        "Selected policy request is outside the verified exchange");
  }

  /** Checks policy absence only within the selected exchange's complete response window. */
  @Dann("prüfe der Software-Exchange erzeugt keine Policy-Anfrage und nutze soft assert")
  public void verifyNoPolicyRequest() {
    var exchange = currentExchange();
    require(exchange == verifiedExchange, "Software exchange proofs have not been verified");
    var messages = RecordedMessages.snapshot();
    var response = responseTo(messages, exchange);
    SoftAssertionsContext.softly().assertThat(policyRequests(messages, time(exchange), time(response),
        config("paths.opa.decisionPath"), null)).as("No policy for malformed software").isEmpty();
  }

  /** Checks one correlated response status without preventing subsequent outcome checks. */
  @Dann("prüfe aktuelle Antwort hat HTTP-Status {int} und nutze soft assert")
  public void verifyResponseStatus(int expected) {
    SoftAssertionsContext.softly().assertThat(value(currentResponse(), "$.responseCode"))
        .as("Correlated response HTTP status").isEqualTo(Integer.toString(expected));
  }

  /** Applies a feature-defined schema while preserving independent outcome checks on failure. */
  @Dann("prüfe aktuelle Antwort im Knoten {tigerResolvedString} stimmt als JSON_SCHEMA überein mit und nutze soft assert:")
  public void verifyResponseSchema(String path, String schema) {
    try {
      new JsonSchemaChecker().compareToSchema(value(currentResponse(), path),
          TigerGlobalConfiguration.resolvePlaceholders(schema));
    } catch (AssertionError error) {
      SoftAssertionsContext.recordSoftFailure("Correlated response schema at " + path, error);
    }
  }

  /** Keeps policy-input field checks in Gherkin without stopping the negative control on failure. */
  @Dann("prüfe aktueller Request im Knoten {tigerResolvedString} stimmt als JSON_SCHEMA überein mit und nutze soft assert:")
  public void verifyRequestSchema(String path, String schema) {
    try {
      new JsonSchemaChecker().compareToSchema(
          value(RbelMessageRetriever.getInstance().getCurrentRequest(), path),
          TigerGlobalConfiguration.resolvePlaceholders(schema));
    } catch (AssertionError error) {
      SoftAssertionsContext.recordSoftFailure("Request schema at " + path, error);
    }
  }

  static void verifyPostureKey(String encoded, String jwks) throws Exception {
    var pem = encoded.contains("-----BEGIN") ? encoded
        : "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    var publicKey = JWK.parseFromPEMEncodedObjects(pem);
    var registered = JWKSet.parse(jwks).getKeys();
    require(registered.size() == 1 && !publicKey.isPrivate()
        && publicKey.computeThumbprint().equals(registered.getFirst().computeThumbprint()),
        "Software public key is not the registered client signing key");
  }

  static JsonNode validateStatement(JsonNode claims, String state, String osVersion, Instant sent) {
    require(List.of("gültig", "os_version_fehlend").contains(state), "Unknown software input case");
    var statement = claims.path("client_statement");
    require(statement.isObject(), "Missing signed software statement");
    var copy = statement.deepCopy();
    if ("os_version_fehlend".equals(state)) {
      require(osVersion != null && !osVersion.isBlank(), "Missing successful SDK software control");
      require(!statement.path("posture").has("os_version"),
          "Expected only the absent required os_version, not null or empty");
      require(copy.path("posture") instanceof tools.jackson.databind.node.ObjectNode,
          "Missing software posture");
      // Restore only the deliberately removed field to validate all unmodified input conditions.
      ((tools.jackson.databind.node.ObjectNode) copy.path("posture")).put("os_version", osVersion);
    }
    new JsonSchemaChecker().compareToSchema(copy.toString(), SOFTWARE_STATEMENT_SCHEMA);
    var timestamp = statement.path("attestation_timestamp");
    require(timestamp.isIntegralNumber() && claims.path("exp").isIntegralNumber()
        && timestamp.asLong() <= sent.getEpochSecond() + 1
        && timestamp.asLong() < claims.path("exp").asLong(),
        "Independent attestation timestamp is invalid");
    return statement;
  }

  static List<RbelElement> policyRequests(List<RbelElement> messages, Instant start, Instant end,
      String path, String client) {
    require(!end.isBefore(start), "AS response precedes its token request");
    return messages.stream().filter(m -> m.findRbelPathMembers("$.method").size() == 1
        && "POST".equals(value(m, "$.method")) && path.equals(value(m, "$.path"))
        && !time(m).isBefore(start) && !time(m).isAfter(end)
        && (client == null || client.equals(JSON.readTree(value(m, "$.body"))
            .at("/input/client_registration_data/client_id").asString()))).toList();
  }

  private static RbelElement currentExchange() {
    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    require(request != null && config("paths.guard.tokenEndpointPath").equals(value(request, "$.path")),
        "Missing selected SDK token exchange");
    return request;
  }

  private static RbelElement currentResponse() {
    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    require(request != null, "Missing selected request");
    return responseTo(RecordedMessages.snapshot(), request);
  }

  private static RbelElement responseTo(List<RbelElement> messages, RbelElement request) {
    var response = new TimingGlue().findResponseForRequest(messages, request);
    require(response != null, "Missing correlated response");
    return response;
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.resolvePlaceholders("${" + key + "}");
  }

  private static String value(RbelElement message, String path) {
    var elements = message.findRbelPathMembers(path);
    require(elements.size() == 1, "Missing or ambiguous software observation: " + path);
    return elements.getFirst().getRawStringContent();
  }

  private static Instant time(RbelElement message) {
    return message.getFacet(RbelMessageTimingFacet.class).orElseThrow()
        .getTransmissionTime().toInstant();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
