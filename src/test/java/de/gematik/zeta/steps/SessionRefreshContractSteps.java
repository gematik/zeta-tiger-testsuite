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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import tools.jackson.databind.ObjectMapper;

/** Observes genuine SDK refreshes; never constructs or changes a token request. */
public class SessionRefreshContractSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private Reference reference;
  private List<Observation> rejected;

  /** Retains the successful SDK request and its correlated, successful public-key registration. */
  @Dann("der erfolgreiche SDK-Refresh wird mit registriertem Schlüssel und Access Token {tigerResolvedString} als Session-Kontrolle gesichert")
  public void rememberControl(String accessToken) throws Exception {
    var retriever = RbelMessageRetriever.getInstance();
    var messages = RecordedMessages.snapshot();
    var observation = observe(messages, retriever.getCurrentRequest());
    require(observation.status() == 200, "Control refresh was not successful");
    var tokens = JSON.readTree(observation.response());
    require(accessToken.equals(tokens.path("access_token").asString()),
        "Selected control response is not the retained token pair");
    var client = SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim("client_id");
    var registrations = new ArrayList<String>();
    for (var request : messages) {
      if (!isRequest(request, config("paths.guard.registerEndpointPath"))) {
        continue;
      }
      var reply = new TimingGlue().findResponseForRequest(messages, request);
      if (reply == null || !client.equals(JSON.readTree(value(reply, "$.body"))
          .path("client_id").asString())) {
        continue;
      }
      require("201".equals(value(reply, "$.responseCode")), "SDK registration failed");
      registrations.add(JSON.readTree(value(request, "$.body")).path("jwks").toString());
    }
    require(registrations.size() == 1, "Missing or ambiguous original SDK registration");
    reference = new Reference(observation, registrations.getFirst(), accessToken,
        tokens.path("refresh_token").asString());
    verifyProofs(observation, reference, endpoint());
  }

  /** Captures every target-client retry before the feature clears the recorder for its control. */
  @Dann("alle SDK-Refresh-Versuche der beendeten Zielsession besitzen unveränderte gültige Nachweise und werden gesichert")
  public void rememberRejected() throws Exception {
    require(reference != null, "Missing successful SDK control refresh");
    var messages = RecordedMessages.snapshot();
    var observations = new ArrayList<Observation>();
    var client = SignedJWT.parse(reference.access()).getJWTClaimsSet().getStringClaim("client_id");
    for (var request : messages) {
      if (!isRequest(request, config("paths.guard.tokenEndpointPath"))) {
        continue;
      }
      var form = SmbExchangeSteps.form(value(request, "$.body"));
      if ("refresh_token".equals(form.get("grant_type")) && client.equals(form.get("client_id"))) {
        observations.add(observe(messages, request));
      }
    }
    verifyRequests(observations, reference, endpoint());
    rejected = List.copyOf(observations);
    SerenityReportUtils.addCustomData("Terminated-session SDK evidence",
        rejected.size() + " correlated refresh attempts with the latest unconsumed target token; "
            + "registered client signature, DPoP binding, freshness, nonce and unchanged grant "
            + "parameters independently checked before evaluating the error contract.");
  }

  /** Runs after the independent active-session control, so a contract defect cannot hide it. */
  @Dann("alle gesicherten Antworten der beendeten Session erfüllen HTTP 403, session_terminated, Fehlerschema und Tokenfreiheit")
  public void verifyContract() {
    verifyResponses(rejected);
  }

  record Observation(String request, String proof, String response, int status,
                     Instant sent, String nonce) {}

  record Reference(Observation control, String jwks, String access, String refresh) {}

  static void verifyRequests(List<Observation> observations, Reference reference, String endpoint)
      throws Exception {
    require(observations != null && !observations.isEmpty(), "No target-session SDK refresh observed");
    var proofIds = new HashSet<String>();
    proofIds.add(SignedJWT.parse(reference.control().proof()).getJWTClaimsSet().getJWTID());
    var assertionIds = new HashSet<String>();
    assertionIds.add(SignedJWT.parse(SmbExchangeSteps.form(reference.control().request())
        .get("client_assertion")).getJWTClaimsSet().getJWTID());
    for (var observation : observations) {
      var form = SmbExchangeSteps.form(observation.request());
      require(reference.refresh().equals(form.get("refresh_token")),
          "Refresh did not submit the latest unconsumed target token");
      require(observation.sent().isAfter(reference.control().sent()),
          "Rejected refresh predates the successful control");
      verifyProofs(observation, reference, endpoint);
      require(proofIds.add(SignedJWT.parse(observation.proof()).getJWTClaimsSet().getJWTID())
              && assertionIds.add(SignedJWT.parse(form.get("client_assertion"))
                  .getJWTClaimsSet().getJWTID()),
          "SDK refresh reused an authentication proof");
    }
  }

  static void verifyProofs(Observation observation, Reference reference, String endpoint)
      throws Exception {
    var form = SmbExchangeSteps.form(observation.request());
    var control = SmbExchangeSteps.form(reference.control().request());
    require("refresh_token".equals(form.get("grant_type"))
            && "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                .equals(form.get("client_assertion_type")), "Unexpected refresh authentication type");
    require(stableForm(form).equals(stableForm(control)), "Independent refresh parameters changed");
    var access = SignedJWT.parse(reference.access()).getJWTClaimsSet();
    var refresh = SignedJWT.parse(form.get("refresh_token")).getJWTClaimsSet();
    var client = access.getStringClaim("client_id");
    require(client != null && client.equals(form.get("client_id"))
            && client.equals(refresh.getStringClaim("client_id"))
            && access.getStringClaim("sid").equals(refresh.getStringClaim("sid"))
            && access.getSubject().equals(refresh.getSubject())
            && access.getIssuer().equals(refresh.getIssuer()), "Foreign refresh token or SDK client");
    require(refresh.getExpirationTime() != null
            && refresh.getExpirationTime().toInstant().isAfter(observation.sent().plusSeconds(10)),
        "Refresh token expired or too close to expiry at transmission");
    var keys = JWKSet.parse(reference.jwks()).getKeys();
    require(keys.size() == 1 && keys.getFirst() instanceof ECKey && !keys.getFirst().isPrivate(),
        "Expected one public SDK registration key");
    var key = (ECKey) keys.getFirst();
    var assertion = SignedJWT.parse(form.get("client_assertion"));
    var claims = assertion.getJWTClaimsSet();
    require(JWSAlgorithm.ES256.equals(assertion.getHeader().getAlgorithm())
            && assertion.verify(new ECDSAVerifier(key))
            && client.equals(claims.getIssuer()) && client.equals(claims.getSubject())
            && List.of(endpoint).equals(claims.getAudience()), "Invalid registered client assertion");
    require(claims.getExpirationTime() != null
            && claims.getExpirationTime().toInstant().isAfter(observation.sent())
            && claims.getJWTID() != null && !claims.getJWTID().isBlank(),
        "Expired assertion or missing assertion jti");
    new DpopValidationSteps().verifyDpopSignature(observation.proof());
    var proof = SignedJWT.parse(observation.proof());
    var proofClaims = proof.getJWTClaimsSet();
    require(!proof.getHeader().getJWK().isPrivate()
            && "dpop+jwt".equals(proof.getHeader().getType().toString())
            && proof.getHeader().getJWK().computeThumbprint().toString()
                .equals(access.getJSONObjectClaim("cnf").get("jkt"))
            && "POST".equals(proofClaims.getStringClaim("htm"))
            && endpoint.equals(proofClaims.getStringClaim("htu")), "Invalid DPoP target or key binding");
    require(proofClaims.getIssueTime() != null
            && Math.abs(observation.sent().getEpochSecond()
                - proofClaims.getIssueTime().toInstant().getEpochSecond()) <= 60
            && proofClaims.getJWTID() != null && !proofClaims.getJWTID().isBlank(),
        "Missing or stale DPoP freshness proof");
    require(observation.nonce() != null && !observation.nonce().isBlank()
            && observation.nonce().equals(proofClaims.getStringClaim("nonce")),
        "DPoP nonce was not issued by the AS before this request");
    var statement = claims.getJSONObjectClaim("client_statement");
    var original = SignedJWT.parse(control.get("client_assertion"))
        .getJWTClaimsSet().getJSONObjectClaim("client_statement");
    require(statement != null && "software".equals(statement.get("posture_type"))
            && stableStatement(statement).equals(stableStatement(original)),
        "Independent software attestation changed");
    require(statement.get("attestation_timestamp") instanceof Number time
            && Math.abs(observation.sent().getEpochSecond() - time.longValue()) <= 60,
        "Software attestation is not fresh");
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(key.computeThumbprint().decode());
    var challenge = Base64URL.encode(digest.digest(Base64URL.from(observation.nonce()).decode()));
    require(challenge.toString().equals(((Map<?, ?>) statement.get("posture"))
        .get("attestation_challenge")), "Software attestation is not bound to client key and nonce");
  }

  private static Map<String, String> stableForm(Map<String, String> form) {
    var stable = new HashMap<>(form);
    stable.remove("refresh_token");
    stable.remove("client_assertion");
    return stable;
  }

  private static Map<String, Object> stableStatement(Map<String, Object> statement) {
    require(statement != null && statement.get("posture") instanceof Map<?, ?>,
        "Missing software posture");
    var stable = new HashMap<>(statement);
    var posture = new HashMap<>((Map<?, ?>) statement.get("posture"));
    posture.remove("attestation_challenge");
    stable.put("posture", posture);
    stable.remove("attestation_timestamp");
    return stable;
  }

  static void verifyResponses(List<Observation> observations) {
    require(observations != null && !observations.isEmpty(), "No saved target-session responses");
    var assertions = new SoftAssertions();
    int index = 0;
    for (var observation : observations) {
      index++;
      var body = JSON.readTree(observation.response());
      assertions.assertThat(observation.status()).as("Refresh %s HTTP status", index).isEqualTo(403);
      assertions.assertThat(body.path("error").asString())
          .as("Refresh %s OAuth error", index).isEqualTo("session_terminated");
      for (var token : List.of("access_token", "refresh_token", "id_token", "zeta_attestation_token")) {
        assertions.assertThat(body.has(token)).as("Refresh %s must omit %s", index, token).isFalse();
      }
      assertions.assertThatCode(() -> new SchemaValidationSteps().validateJsonAgainstYamlSchema(
          observation.response(), "schemas/v_2_0/schemas/zeta-error.yaml"))
          .as("Refresh %s error schema", index).doesNotThrowAnyException();
    }
    assertions.assertAll();
  }

  private static Observation observe(List<RbelElement> messages, RbelElement request) {
    require(request != null && isRequest(request, config("paths.guard.tokenEndpointPath")),
        "Expected a genuine SDK token request");
    var response = new TimingGlue().findResponseForRequest(messages, request);
    require(response != null, "SDK refresh has no correlated response");
    var sent = time(request);
    String nonce = null;
    Instant nonceTime = Instant.MIN;
    for (var candidate : messages) {
      if (!"GET".equals(optional(candidate, "$.method"))
          || !config("paths.guard.nonceEndpointPath").equals(optional(candidate, "$.path"))) {
        continue;
      }
      var reply = new TimingGlue().findResponseForRequest(messages, candidate);
      if (reply != null && "200".equals(value(reply, "$.responseCode"))
          && !time(reply).isAfter(sent) && time(reply).isAfter(nonceTime)) {
        nonce = value(reply, "$.body").trim();
        nonceTime = time(reply);
      }
    }
    return new Observation(value(request, "$.body"), value(request, "$.header.[~'DPoP']"),
        value(response, "$.body"), Integer.parseInt(value(response, "$.responseCode")), sent, nonce);
  }

  private static Instant time(RbelElement message) {
    return message.getFacet(RbelMessageTimingFacet.class).orElseThrow()
        .getTransmissionTime().toInstant();
  }

  private static boolean isRequest(RbelElement message, String path) {
    return "POST".equals(optional(message, "$.method")) && path.equals(optional(message, "$.path"));
  }

  private static String optional(RbelElement message, String path) {
    var values = message.findRbelPathMembers(path);
    return values.size() == 1 ? values.getFirst().getRawStringContent() : null;
  }

  private static String value(RbelElement message, String path) {
    var value = optional(message, path);
    require(value != null, "Missing or ambiguous SDK evidence: " + path);
    return value;
  }

  private static String endpoint() {
    return "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath");
  }

  private static String config(String name) {
    return TigerGlobalConfiguration.readStringOptional(name)
        .map(TigerGlobalConfiguration::resolvePlaceholders).orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
