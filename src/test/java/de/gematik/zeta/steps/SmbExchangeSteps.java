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
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import tools.jackson.databind.ObjectMapper;

/** Independent checks for the actual stationary SDK exchange, including isolated bad assertions. */
public class SmbExchangeSteps {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Verifies the currently selected SDK request's independent SMC-B, DPoP and DCR key bindings.
   *
   * @param registeredJwks public key recorded in this SDK's fresh registration
   * @param expected valid or invalid client assertion signature
   */
  @Dann("der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine {word} Client-Assertion zum registrierten JWKS {tigerResolvedString}")
  public void verifyRequest(String expected, String registeredJwks) throws Exception {
    require(List.of("gültige", "ungültige").contains(expected), "Unknown assertion case");
    verifyIsolatedRequest("gültige".equals(expected) ? "none" : "client_assertion", registeredJwks);
  }

  /** Verifies one isolated mutation and its independent proofs at the selected request's transmission time. */
  @Dann("der SMC-B-Exchange enthält ausschließlich den Fehler {word} bei registriertem JWKS {tigerResolvedString}")
  public void verifyIsolatedRequest(String fault, String registeredJwks) throws Exception {
    var retriever = RbelMessageRetriever.getInstance();
    var request = retriever.getCurrentRequest();
    require(request != null, "Missing SDK request");
    var sent = request.getFacet(RbelMessageTimingFacet.class)
        .orElseThrow(() -> new AssertionError("Missing SDK request transmission time"))
        .getTransmissionTime().toInstant();
    var body = retriever.findElementInCurrentRequest("$.body").getRawStringContent();
    var dpop = retriever.findElementInCurrentRequest("$.header.[~'DPoP']").getRawStringContent();
    var client =
        verifyProofs(
            body,
            dpop,
            registeredJwks,
            fault,
            sent,
            "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath"));
    verifyNonceHistory(RecordedMessages.snapshot(), request, fault);
    SerenityReportUtils.addCustomData(
        "Stationary exchange proof",
        "Actual SDK client "
            + client
            + ": independent signatures, subject client/DPoP bindings, targets, nonces and"
            + " validity checked with exactly the declared exception "
            + fault
            + ".");
  }

  static String verifyProofs(
      String body,
      String dpop,
      String registeredJwks,
      boolean assertionValid,
      Instant now,
      String endpoint)
      throws Exception {
    return verifyProofs(
        body, dpop, registeredJwks, assertionValid ? "none" : "client_assertion", now, endpoint);
  }

  static String verifyProofs(
      String body,
      String dpop,
      String registeredJwks,
      String fault,
      Instant now,
      String endpoint)
      throws Exception {
    require(
        List.of("none", "client_assertion", "subject_signature", "dpop_signature",
            "client_key", "dpop_key", "empty_subject_type", "invalid_scope",
            "resource_fragment", "assertion_typ", "assertion_iss", "assertion_sub",
            "assertion_aud", "assertion_expired", "assertion_missing_iss", "assertion_missing_sub",
            "assertion_missing_aud", "assertion_missing_exp", "assertion_missing_jti",
            "subject_nonce_empty", "subject_nonce_unknown", "dpop_nonce_missing",
            "subject_expired").contains(fault),
        "Unknown isolated exchange fault");
    final boolean assertionValid = !"client_assertion".equals(fault);
    var form = form(body);
    var scope = form.get("scope");
    if ("invalid_scope".equals(fault)) {
      require(scope != null && scope.length() > 1
              && (scope.endsWith("\"") || scope.endsWith("\\"))
              && validScope(scope.substring(0, scope.length() - 1)),
          "Expected only one forbidden scope character after a valid scope");
    } else if (scope != null && !scope.isEmpty()) {
      require(validScope(scope), "An independent scope is malformed");
    }
    var resource = form.get("resource");
    if (resource != null || "resource_fragment".equals(fault)) {
      require(resource != null && !resource.isBlank() && !form.containsKey("audience"),
          "Expected the isolated resource contract without legacy audience");
      var uri = URI.create(resource);
      require(uri.isAbsolute(), "Resource must be an absolute URI");
      require("resource_fragment".equals(fault)
              ? "cc-invalid-target".equals(uri.getFragment()) : uri.getFragment() == null,
          "Unexpected resource fragment; RFC 8707 prohibits fragments");
    }
    require(
        "urn:ietf:params:oauth:grant-type:token-exchange".equals(form.get("grant_type")),
        "Expected the actual SDK token-exchange grant");
    require(
        ("empty_subject_type".equals(fault)
                ? "".equals(form.get("subject_token_type"))
                : "urn:ietf:params:oauth:token-type:jwt".equals(form.get("subject_token_type")))
            && "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                .equals(form.get("client_assertion_type")),
        "Unexpected SDK grant authentication types");
    var client = form.get("client_id");
    require(client != null && !client.isBlank(), "Missing SDK client ID");
    var registered = JWKSet.parse(registeredJwks).getKeys();
    require(
        registered.size() == 1 && registered.getFirst() instanceof ECKey,
        "Expected exactly one public SDK DCR key");
    var key = (ECKey) registered.getFirst();
    require(!key.isPrivate(), "DCR contains private key material");
    var assertion = SignedJWT.parse(form.get("client_assertion"));
    var missingClaim = fault.startsWith("assertion_missing_")
        ? fault.substring("assertion_missing_".length()) : "";
    if (!missingClaim.isEmpty()) {
      require(!JSON.readTree(assertion.getPayload().toString()).has(missingClaim),
          "The isolated assertion claim must be absent, not null or empty");
    }
    require(assertion.getHeader().getType() != null
            && ("assertion_typ".equals(fault) ? "cc-invalid-assertion" : "JWT")
                .equals(assertion.getHeader().getType().toString())
            && JWSAlgorithm.ES256.equals(assertion.getHeader().getAlgorithm()),
        "Independent client assertion must use typ JWT and alg ES256");
    require("jti".equals(missingClaim) || (assertion.getJWTClaimsSet().getJWTID() != null
            && !assertion.getJWTClaimsSet().getJWTID().isBlank()),
        "Independent client assertion must contain a non-empty jti");
    require(assertion.getHeader().getJWK() instanceof ECKey assertionKey
            && !assertionKey.isPrivate() && Curve.P_256.equals(assertionKey.getCurve())
            && assertionKey.computeThumbprint().equals(key.computeThumbprint()),
        "Independent assertion JWK must match the registered public P-256 key");
    require(
        ("iss".equals(missingClaim) || ("assertion_iss".equals(fault) ? "cc-invalid-issuer" : client)
                .equals(assertion.getJWTClaimsSet().getIssuer()))
            && ("sub".equals(missingClaim)
                || ("assertion_sub".equals(fault) ? "cc-invalid-subject" : client)
                    .equals(assertion.getJWTClaimsSet().getSubject())),
        "Assertion belongs to another client");
    require(
        assertion.verify(new ECDSAVerifier(key)) == assertionValid,
        "Unexpected assertion signature integrity");
    var statement = assertion.getJWTClaimsSet().getJSONObjectClaim("client_statement");
    require(
        statement != null
            && List.of("linux", "windows", "macos").contains(statement.get("platform")),
        "Expected a stationary SDK platform statement");
    if (!assertionValid) {
      require(
          "cc-invalid-assertion".equals(assertion.getJWTClaimsSet().getJWTID()),
          "The isolated unsigned jti mutation was not applied");
    }
    var subject = SignedJWT.parse(form.get("subject_token"));
    require(subject.getHeader().getType() != null
            && "JWT".equals(subject.getHeader().getType().toString()),
        "Independent subject token must use typ JWT");
    require(subject.getJWTClaimsSet().getJWTID() != null
            && !subject.getJWTClaimsSet().getJWTID().isBlank()
            && subject.getJWTClaimsSet().getSubject() != null
            && !subject.getJWTClaimsSet().getSubject().isBlank()
            && client.equals(subject.getJWTClaimsSet().getIssuer())
            && subject.getJWTClaimsSet().getIssueTime() != null,
        "Independent subject token requires jti, SMC-B subject, client issuer and iat");
    require(
        subject.getHeader().getX509CertChain() != null
            && !subject.getHeader().getX509CertChain().isEmpty(),
        "Missing actual SMC-B certificate");
    if ("subject_signature".equals(fault)) {
      require(
          JWSAlgorithm.ES256.equals(subject.getHeader().getAlgorithm())
              && !new SignatureVerificationSteps()
                  .hasCryptographicallyValidEmbeddedEs256Signature(form.get("subject_token")),
          "Expected only the invalid SMC-B signature");
    } else {
      new SignatureVerificationSteps().verifyJwtSignature(form.get("subject_token"));
    }
    var proof = SignedJWT.parse(dpop);
    require(proof.getHeader().getType() != null
            && "dpop+jwt".equals(proof.getHeader().getType().toString()),
        "Independent DPoP proof must use typ dpop+jwt");
    require(proof.getJWTClaimsSet().getJWTID() != null
            && !proof.getJWTClaimsSet().getJWTID().isBlank(),
        "Independent DPoP proof must contain a non-empty jti");
    if ("dpop_signature".equals(fault)) {
      require(
          JWSAlgorithm.ES256.equals(proof.getHeader().getAlgorithm())
              && proof.getHeader().getJWK() instanceof ECKey proofKey
              && Curve.P_256.equals(proofKey.getCurve())
              && !proofKey.isPrivate()
              && "cc-invalid-dpop".equals(proof.getJWTClaimsSet().getJWTID())
              && !proof.verify(new ECDSAVerifier(proofKey)),
          "Expected only the marked invalid DPoP signature");
    } else {
      new DpopValidationSteps().verifyDpopSignature(dpop);
    }
    require(
        key.computeThumbprint().toString().equals(thumbprint(subject, "client_key"))
            != "client_key".equals(fault),
        "Unexpected SMC-B subject/DCR client key binding");
    require(
        proof
            .getHeader()
            .getJWK()
            .computeThumbprint()
            .toString()
            .equals(thumbprint(subject, "dpop_key")) != "dpop_key".equals(fault),
        "Unexpected SMC-B subject/DPoP key binding");
    require("POST".equals(proof.getJWTClaimsSet().getStringClaim("htm")), "Wrong DPoP method");
    var target = proof.getJWTClaimsSet().getStringClaim("htu");
    require(
        endpoint.equals(target)
            && ("aud".equals(missingClaim) || ("assertion_aud".equals(fault)
                ? List.of("https://invalid.example/token")
                    .equals(assertion.getJWTClaimsSet().getAudience())
                : assertion.getJWTClaimsSet().getAudience().contains(endpoint)))
            && subject.getJWTClaimsSet().getAudience().contains(endpoint),
        "DPoP, assertion or subject token does not target the actual token endpoint");
    var nonce = subject.getJWTClaimsSet().getStringClaim("nonce");
    if ("dpop_nonce_missing".equals(fault)) {
      require(!JSON.readTree(proof.getPayload().toString()).has("nonce"),
          "The isolated DPoP nonce must be absent, not null or empty");
    }
    verifyNonceValues(nonce, proof.getJWTClaimsSet().getStringClaim("nonce"), fault);
    if ("subject_expired".equals(fault)) {
      var claims = subject.getJWTClaimsSet();
      require(claims.getIssueTime() != null && claims.getExpirationTime() != null
              && claims.getIssueTime().before(claims.getExpirationTime())
              && !claims.getExpirationTime().toInstant().isAfter(now),
          "Expected an expired Subject with a coherent original validity interval");
      require(claims.getNotBeforeTime() == null
              || claims.getNotBeforeTime().before(claims.getExpirationTime()),
          "Expired Subject must not contain a second, inconsistent not-before condition");
    }
    for (var token : List.of(subject, assertion)) {
      if (token == subject && "subject_expired".equals(fault)) {
        continue;
      }
      if (token == assertion && "exp".equals(missingClaim)) {
        continue;
      }
      require(
          token.getJWTClaimsSet().getExpirationTime() != null
              && (token == assertion && "assertion_expired".equals(fault)
                  ? token.getJWTClaimsSet().getExpirationTime().toInstant()
                      .equals(Instant.ofEpochSecond(1700000000))
                      && token.getJWTClaimsSet().getExpirationTime().toInstant().isBefore(now)
                  : token.getJWTClaimsSet().getExpirationTime().toInstant().isAfter(now)),
          "An independent credential is already expired");
    }
    require(
        proof.getJWTClaimsSet().getIssueTime() != null
            && Math.abs(
                    now.getEpochSecond()
                        - proof.getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond())
                <= 60,
        "DPoP is not fresh");
    return client;
  }

  /** Checks every recorded retry, including independent status and error-code assertions. */
  @Dann("alle aufgezeichneten SMC-B-Exchanges für Client {tigerResolvedString} mit JWKS {tigerResolvedString} und isoliertem Fehler {word} liefern Status {int} und Fehler {word} ohne Tokens")
  public void verifyRejectedHistory(
      String client, String jwks, String fault, int status, String error) throws Exception {
    var messages = RecordedMessages.snapshot();
    var exchanges = recordedExchanges(messages, fault);
    verifyRejectedExchanges(exchanges, client, jwks, fault, status, error,
        "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath"));
    SerenityReportUtils.addCustomData("Rejected SDK exchanges including retries",
        exchanges.size() + " correlated exchanges: each isolated fault and token-free response "
            + "checked independently, including status and error code.");
  }

  /** Checks the independently discovered target and scope in every malformed-resource retry. */
  @Dann("alle aufgezeichneten SMC-B-Exchanges verwenden ausschließlich Resource {tigerResolvedString}"
      + " mit Fragment cc-invalid-target und Scope {tigerResolvedString}")
  public void verifyResourceFragmentHistory(String resource, String scope) throws Exception {
    var exchanges = recordedExchanges(RecordedMessages.snapshot(), "resource_fragment");
    verifyResourceFragmentTargets(
        exchanges.stream().map(ExchangeObservation::request).toList(), resource, scope);
  }

  static void verifyResourceFragmentTargets(List<String> requests, String resource, String scope) {
    require(resource != null && !resource.isBlank() && !resource.contains("${"),
        "Missing independent resource expectation");
    var uri = URI.create(resource);
    require(uri.isAbsolute() && uri.getFragment() == null,
        "Independent resource must be absolute and fragment-free");
    require(scope != null && !scope.isBlank() && validScope(scope) && !scope.contains("${"),
        "Missing independent scope expectation");
    require(requests != null && !requests.isEmpty(), "No resource exchanges observed");
    for (var request : requests) {
      var values = form(request);
      require((resource + "#cc-invalid-target").equals(values.get("resource"))
              && scope.equals(values.get("scope")) && !values.containsKey("audience"),
          "A retry changed more than the isolated resource fragment");
    }
  }

  /** Correlates automatic SDK re-registration without accepting another signing key. */
  @Dann("alle SMC-B-Exchanges derselben SDK-Schlüsselinstanz ab Client {tigerResolvedString} "
      + "mit JWKS {tigerResolvedString} und isoliertem Fehler {word} liefern Status {int} und Fehler {word} ohne Tokens")
  public void verifyReregisteredHistory(
      String initialClient, String jwks, String fault, int status, String error) throws Exception {
    var messages = RecordedMessages.snapshot();
    var registrations = new ArrayList<RegistrationObservation>();
    for (var request : messages) {
      if (request.findRbelPathMembers("$.method").size() != 1
          || !"POST".equals(value(request, "$.method"))
          || !config("paths.guard.registerEndpointPath").equals(value(request, "$.path"))) {
        continue;
      }
      var observed = ParRequestSteps.observation(request);
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(response != null, "An SDK registration has no correlated response");
      var data = JSON.readTree(value(response, "$.body"));
      registrations.add(new RegistrationObservation(data.path("client_id").asString(),
          JSON.readTree(observed.body()).path("jwks").toString(), observed.target(),
          Integer.parseInt(value(response, "$.responseCode")), observed.sent(),
          response.getFacet(RbelMessageTimingFacet.class).orElseThrow()
              .getTransmissionTime().toInstant()));
    }
    var exchanges = recordedExchanges(messages, fault);
    verifyRegisteredExchanges(registrations, exchanges, initialClient, jwks, fault, status, error,
        URI.create("https://" + config("zeta_base_url") + config("paths.guard.registerEndpointPath")),
        "https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath"));
    SerenityReportUtils.addCustomData("SDK re-registration and rejected exchanges",
        registrations.size() + " successful registrations of the same signing key; "
            + exchanges.size() + " exchanges independently checked against their own prior DCR.");
  }

  private static List<ExchangeObservation> recordedExchanges(List<RbelElement> messages, String fault)
      throws Exception {
    var exchanges = new ArrayList<ExchangeObservation>();
    for (var request : messages) {
      if (request.findRbelPathMembers("$.method").size() != 1
          || !"POST".equals(value(request, "$.method"))
          || !config("paths.guard.tokenEndpointPath").equals(value(request, "$.path"))) {
        continue;
      }
      var body = value(request, "$.body");
      if (!"urn:ietf:params:oauth:grant-type:token-exchange".equals(form(body).get("grant_type"))) {
        continue;
      }
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(response != null, "A recorded token exchange has no correlated response");
      verifyNonceHistory(messages, request, fault);
      var sent = request.getFacet(RbelMessageTimingFacet.class).orElseThrow()
          .getTransmissionTime().toInstant();
      exchanges.add(new ExchangeObservation(body, value(request, "$.header.[~'DPoP']"),
          value(response, "$.body"), Integer.parseInt(value(response, "$.responseCode")), sent,
          response.findRbelPathMembers("$.header.[~'DPoP-Nonce']").stream()
              .map(RbelElement::getRawStringContent).toList()));
    }
    return exchanges;
  }

  record ExchangeObservation(String request, String proof, String response, int status, Instant sent,
      List<String> challengeNonces) {
    ExchangeObservation(String request, String proof, String response, int status, Instant sent) {
      this(request, proof, response, status, sent, List.of());
    }
  }

  record RegistrationObservation(
      String client, String jwks, URI endpoint, int status, Instant requested, Instant completed) {}

  static void verifyRegisteredExchanges(
      List<RegistrationObservation> registrations, List<ExchangeObservation> exchanges,
      String initialClient, String initialJwks, String fault, int status, String error,
      URI registrationEndpoint, String tokenEndpoint) throws Exception {
    require(!registrations.isEmpty(), "Missing original SDK registration");
    var initialKey = registeredKeyThumbprint(initialJwks);
    var byClient = new HashMap<String, RegistrationObservation>();
    var keys = new HashMap<String, String>();
    for (var registration : registrations) {
      require(registration.client() != null && !registration.client().isBlank()
              && registrationEndpoint.equals(registration.endpoint())
              && registration.status() == 201
              && registration.requested() != null && registration.completed() != null
              && !registration.completed().isBefore(registration.requested()),
          "Invalid independent registration evidence");
      require(initialKey.equals(registeredKeyThumbprint(registration.jwks())),
          "SDK retry changed the original signing key");
      require(byClient.putIfAbsent(registration.client(), registration) == null,
          "Ambiguous client registration");
      keys.put(registration.client(), registration.jwks());
    }
    var initial = byClient.get(initialClient);
    require(initial != null && registrations.stream()
            .allMatch(r -> !r.requested().isBefore(initial.requested())),
        "Initial SDK registration was not independently observed first");
    require(exchanges.stream().anyMatch(e -> initialClient.equals(form(e.request()).get("client_id"))),
        "No exchange from the initial SDK registration");
    for (var exchange : exchanges) {
      var registration = byClient.get(form(exchange.request()).get("client_id"));
      require(registration != null && exchange.sent() != null
              && !registration.completed().isAfter(exchange.sent()),
          "Exchange lacks its own preceding successful SDK registration");
    }
    verifyRejectedExchanges(exchanges, keys, fault, status, error, tokenEndpoint);
  }

  private static String registeredKeyThumbprint(String jwks) throws Exception {
    var keys = JWKSet.parse(jwks).getKeys();
    require(keys.size() == 1 && keys.getFirst() instanceof ECKey key
            && !key.isPrivate() && Curve.P_256.equals(key.getCurve()),
        "Expected one public P-256 SDK registration key");
    return keys.getFirst().computeThumbprint().toString();
  }

  record NonceObservation(
      String method, URI endpoint, int status, String nonce, Instant requested, Instant received) {}

  static void verifyNonceHistory(
      List<RbelElement> messages, RbelElement tokenRequest, String fault)
      throws Exception {
    var token = ParRequestSteps.observation(tokenRequest);
    var subject = SignedJWT.parse(form(token.body()).get("subject_token"));
    var proof = SignedJWT.parse(value(tokenRequest, "$.header.[~'DPoP']"));
    var endpoint = URI.create("https://" + config("zeta_base_url")
        + config("paths.guard.nonceEndpointPath"));
    var nonces = new ArrayList<NonceObservation>();
    for (var request : messages) {
      if (request.findRbelPathMembers("$.method").size() != 1
          || !"GET".equals(value(request, "$.method"))
          || !config("paths.guard.nonceEndpointPath").equals(value(request, "$.path"))) {
        continue;
      }
      var observed = ParRequestSteps.observation(request);
      var response = new TimingGlue().findResponseForRequest(messages, request);
      if (response == null) {
        continue;
      }
      nonces.add(new NonceObservation(observed.method(), observed.target(),
          Integer.parseInt(value(response, "$.responseCode")), value(response, "$.body"),
          observed.sent(), response.getFacet(RbelMessageTimingFacet.class).orElseThrow()
              .getTransmissionTime().toInstant()));
    }
    verifyNonceContext(nonces, subject.getJWTClaimsSet().getStringClaim("nonce"),
        proof.getJWTClaimsSet().getStringClaim("nonce"), fault, token.sent(), endpoint);
  }

  private static void verifyNonceValues(String subject, String proof, String fault) {
    if ("dpop_nonce_missing".equals(fault)) {
      require(proof == null && subject != null && !subject.isBlank(),
          "Only the DPoP nonce may be absent; the Subject nonce must remain valid");
      return;
    }
    require(proof != null && !proof.isBlank(), "Independent DPoP nonce must remain present");
    if ("subject_nonce_empty".equals(fault)) {
      require("".equals(subject), "Expected present empty Subject nonce, not null or absent");
    } else if ("subject_nonce_unknown".equals(fault)) {
      require("cc-unissued-subject-nonce".equals(subject) && !subject.equals(proof),
          "Expected only the marked unknown Subject nonce");
    } else {
      require(subject != null && !subject.isBlank() && subject.equals(proof),
          "Subject and DPoP nonces differ");
    }
  }

  static void verifyNonceContext(
      List<NonceObservation> observations, String subject, String proof, String fault,
      Instant sent, URI endpoint) {
    verifyNonceValues(subject, proof, fault);
    // Every present, unmodified nonce needs a genuine, preceding AS response.
    if (!"dpop_nonce_missing".equals(fault)) {
      verifyNonceIssuedBefore(observations, proof, sent, endpoint);
    }
    if ("subject_nonce_unknown".equals(fault)) {
      require(observations.stream().noneMatch(o -> o != null && subject.equals(o.nonce())),
          "The marked unknown Subject nonce was actually observed from the AS");
    } else if (!"subject_nonce_empty".equals(fault)) {
      verifyNonceIssuedBefore(observations, subject, sent, endpoint);
    }
  }

  static void verifyNonceIssuedBefore(
      List<NonceObservation> observations, String nonce, Instant sent, URI endpoint) {
    require(observations != null && nonce != null && !nonce.isBlank()
            && sent != null && endpoint != null,
        "Missing independent AS nonce evidence");
    require(observations.stream().anyMatch(o -> o != null
            && "GET".equals(o.method()) && endpoint.equals(o.endpoint()) && o.status() == 200
            && nonce.equals(o.nonce()) && o.requested() != null && o.received() != null
            && !o.received().isBefore(o.requested()) && !o.received().isAfter(sent)),
        "Subject nonce was not issued by the actual AS before this token request");
  }

  static void verifyRejectedExchanges(
      List<ExchangeObservation> exchanges, String client, String jwks, String fault,
      int status, String error, String endpoint) throws Exception {
    verifyRejectedExchanges(exchanges, Map.of(client, jwks), fault, status, error, endpoint);
  }

  private static void verifyRejectedExchanges(
      List<ExchangeObservation> exchanges, Map<String, String> registrations, String fault,
      int status, String error, String endpoint) throws Exception {
    require(!exchanges.isEmpty(), "No original SDK exchanges observed");
    var responses = new SoftAssertions();
    int index = 0;
    for (var exchange : exchanges) {
      index++;
      // Prerequisites fail immediately: a second invalid credential cannot explain the rejection.
      var client = form(exchange.request()).get("client_id");
      require(client != null && registrations.containsKey(client),
          "An exchange belongs to another SDK client");
      var jwks = registrations.get(client);
      require(client.equals(verifyProofs(exchange.request(), exchange.proof(), jwks, fault,
          exchange.sent(), endpoint)), "An exchange belongs to another SDK client");
      var body = JSON.readTree(exchange.response());
      require(body != null && body.isObject(), "Token endpoint did not return a JSON object");
      for (var token : List.of("access_token", "refresh_token", "zeta_attestation_token")) {
        responses.assertThat(body.has(token)).as("Exchange %s must omit %s", index, token).isFalse();
      }
      responses.assertThat(exchange.status()).as("Exchange %s HTTP status", index).isEqualTo(status);
      responses.assertThat(body.path("error").asString())
          .as("Exchange %s OAuth error", index).isEqualTo(error);
      if ("dpop_nonce_missing".equals(fault)) {
        responses.assertThat(exchange.challengeNonces())
            .as("Exchange %s must supply exactly one nonempty RFC 9449 nonce", index)
            .hasSize(1).allSatisfy(nonce -> org.assertj.core.api.Assertions.assertThat(nonce)
                .matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+"));
      }
      responses.assertThatCode(() -> new SchemaValidationSteps()
              .validateJsonAgainstYamlSchema(exchange.response(), "schemas/v_2_0/schemas/zeta-error.yaml"))
          .as("Exchange %s error schema", index).doesNotThrowAnyException();
    }
    responses.assertAll();
  }

  private static String value(RbelElement message, String path) {
    var elements = message.findRbelPathMembers(path);
    require(elements.size() == 1, "Missing or ambiguous exchange observation: " + path);
    return elements.getFirst().getRawStringContent();
  }

  private static String thumbprint(SignedJWT subject, String claim) throws Exception {
    var binding = subject.getJWTClaimsSet().getJSONObjectClaim(claim);
    require(
        binding != null && binding.get("jkt") instanceof String,
        "Missing subject-token public key thumbprint: " + claim);
    var value = (String) binding.get("jkt");
    require(value.matches("[A-Za-z0-9_-]{43}"), "Malformed subject-token key binding");
    return value;
  }

  private static boolean validScope(String value) {
    // RFC 6749 section 3.3 excludes double quote, backslash and control/non-ASCII characters.
    return value.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+( [\\x21\\x23-\\x5B\\x5D-\\x7E]+)*");
  }

  /**
   * Decodes form parameters, rejecting empty names and duplicate decoded names.
   *
   * @param body URL-encoded form body
   * @return decoded parameter names and values, or an empty map for an empty body
   * @throws AssertionError if a name is empty or occurs more than once
   * @throws IllegalArgumentException if percent encoding is malformed
   */
  public static Map<String, String> form(String body) {
    if (body.isEmpty()) {
      return Map.of();
    }
    var values = new HashMap<String, String>();
    for (var item : body.split("&")) {
      var pair = item.split("=", 2);
      require(!pair[0].isEmpty(), "Missing SDK form parameter name");
      var name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
      // A form flag without '=' has an empty value; required protocol fields are checked below.
      var value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
      require(values.putIfAbsent(name, value) == null, "Duplicate SDK form parameter");
    }
    return Map.copyOf(values);
  }

  private static String config(String name) {
    return TigerGlobalConfiguration.readStringOptional(name)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
