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

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.RecordedMessages;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Read-only evidence for a genuine SDK request; never constructs or sends a product request. */
final class SdkRevocationRequest {
  private SdkRevocationRequest() {}

  record Observation(String method, String target, String host, String contentType,
                     String body, String proof, Instant sent) {}

  record Reference(String access, String refresh, String client, String jwks,
                   String originalAssertionId, String originalProofId,
                   URI tokenEndpoint, URI revocationEndpoint, Instant started, Instant completed) {}

  static void verifyRecorded(Map<String, String> evidence, String access, String refresh,
      String client, String endpoint) throws Exception {
    var retriever = RbelMessageRetriever.getInstance();
    var request = retriever.getCurrentRequest();
    verifyRecorded(RecordedMessages.snapshot(), request,
        evidence, access, refresh, client, endpoint);
  }

  static void verifyRecorded(List<RbelElement> messages, RbelElement request,
      Map<String, String> evidence, String access, String refresh, String client, String endpoint)
      throws Exception {
    require(request != null, "No selected SDK revocation request");
    var registrations = new ArrayList<String>();
    var grants = new ArrayList<RbelElement>();
    var json = new ObjectMapper();
    for (var candidate : messages) {
      if (!"POST".equals(optional(candidate, "$.method"))) {
        continue;
      }
      var path = optional(candidate, "$.path");
      if (!config("paths.guard.registerEndpointPath").equals(path)
          && !config("paths.guard.tokenEndpointPath").equals(path)) {
        continue;
      }
      var response = new TimingGlue().findResponseForRequest(messages, candidate);
      if (response == null) {
        continue;
      }
      var body = json.readTree(value(response, "$.body"));
      if (config("paths.guard.registerEndpointPath").equals(path)
          && client.equals(body.path("client_id").asString())
          && "201".equals(optional(response, "$.responseCode"))) {
        registrations.add(json.readTree(value(candidate, "$.body")).path("jwks").toString());
      }
      if (config("paths.guard.tokenEndpointPath").equals(path)
          && "200".equals(optional(response, "$.responseCode"))
          && access.equals(body.path("access_token").asString())
          && refresh.equals(body.path("refresh_token").asString())) {
        grants.add(candidate);
      }
    }
    require(registrations.size() == 1 && grants.size() == 1,
        "Missing or ambiguous original SDK registration/grant");
    var grant = grants.getFirst();
    var originalForm = SmbExchangeSteps.form(value(grant, "$.body"));
    var reference = new Reference(access, refresh, client, registrations.getFirst(),
        SignedJWT.parse(originalForm.get("client_assertion")).getJWTClaimsSet().getJWTID(),
        SignedJWT.parse(value(grant, "$.header.[~'DPoP']")).getJWTClaimsSet().getJWTID(),
        URI.create("https://" + config("zeta_base_url") + config("paths.guard.tokenEndpointPath")),
        SdkLogoutSteps.validateEndpoint(endpoint, config("zeta_base_url")),
        Instant.ofEpochMilli(Long.parseLong(evidence.get("logout_started_ms"))),
        Instant.ofEpochMilli(Long.parseLong(evidence.get("logout_completed_ms"))));
    var firstLine = request.getRawStringContent().split("\\r?\\n", 2)[0].split(" ", -1);
    require(firstLine.length == 3, "Missing unambiguous HTTP request target");
    verify(new Observation(value(request, "$.method"), firstLine[1],
        value(request, "$.header.[~'Host']"), value(request, "$.header.[~'Content-Type']"),
        value(request, "$.body"), value(request, "$.header.[~'DPoP']"),
        request.getFacet(RbelMessageTimingFacet.class).orElseThrow()
            .getTransmissionTime().toInstant()), reference);
  }

  static void verify(Observation request, Reference reference) throws Exception {
    verify(request, reference, reference.refresh(), "refresh_token", "valid", true);
  }

  /** Validates the observed SDK proof including an explicitly isolated proxy fault. */
  static void verify(Observation request, Reference reference, String expectedToken,
      String hint, String fault, boolean requireLiveRefresh) throws Exception {
    require(java.util.Set.of("valid", "missing", "expired", "wrong_client", "wrong_signature")
        .contains(fault), "Unknown isolated revocation fault");
    var endpoint = reference.revocationEndpoint();
    require(reference.originalAssertionId() != null && !reference.originalAssertionId().isBlank()
            && reference.originalProofId() != null && !reference.originalProofId().isBlank(),
        "Original SDK grant lacks proof ids for the independent freshness comparison");
    require(
        "POST".equals(request.method())
            && (endpoint.getRawPath().equals(request.target())
                || endpoint.toString().equals(request.target()))
            && sameAuthority(endpoint, request.host()),
        "SDK revocation did not use the complete published endpoint");
    require(
        request.contentType() != null
            && "application/x-www-form-urlencoded"
                .equalsIgnoreCase(request.contentType().split(";", 2)[0].trim()),
        "SDK revocation is not form encoded");
    require(
        !request.sent().isBefore(reference.started())
            && !request.sent().isAfter(reference.completed()),
        "Recorded request is outside the actual SDK logout operation");
    var form = SmbExchangeSteps.form(request.body());
    require(
        expectedToken.equals(form.get("token"))
            && hint.equals(form.get("token_type_hint"))
            && "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                .equals(form.get("client_assertion_type")),
        "SDK revocation lacks the original refresh token, hint or authentication type");
    require(
        !form.containsKey("client_id") || reference.client().equals(form.get("client_id")),
        "SDK revocation carries a foreign client id");
    var access = SignedJWT.parse(reference.access()).getJWTClaimsSet();
    var refresh = SignedJWT.parse(reference.refresh()).getJWTClaimsSet();
    require(
        reference.client().equals(access.getStringClaim("client_id"))
            && reference.client().equals(refresh.getStringClaim("client_id"))
            && access.getSubject() != null
            && access.getSubject().equals(refresh.getSubject())
            && access.getIssuer() != null
            && access.getIssuer().equals(refresh.getIssuer())
            && access.getStringClaim("sid") != null
            && access.getStringClaim("sid").equals(refresh.getStringClaim("sid"))
            && refresh.getExpirationTime() != null
            && (!requireLiveRefresh
                || refresh.getExpirationTime().toInstant().isAfter(request.sent())),
        "Refresh token is expired or belongs to another SDK grant");
    var keys = JWKSet.parse(reference.jwks()).getKeys();
    require(
        keys.size() == 1 && keys.getFirst() instanceof ECKey && !keys.getFirst().isPrivate(),
        "Expected one public key from the actual successful SDK registration");
    if ("missing".equals(fault)) {
      require(!form.containsKey("client_assertion"), "Proxy did not remove the client assertion");
    } else {
      require(
          form.get("client_assertion") != null && !form.get("client_assertion").isBlank(),
          "Missing expected client assertion");
      var assertion = SignedJWT.parse(form.get("client_assertion"));
      var claims = assertion.getJWTClaimsSet();
      require(
          JWSAlgorithm.ES256.equals(assertion.getHeader().getAlgorithm())
              && assertion.verify(new ECDSAVerifier((ECKey) keys.getFirst()))
                  != "wrong_signature".equals(fault)
              && claims.getIssuer() != null
              && claims.getIssuer().equals(claims.getSubject())
              && reference.client().equals(claims.getIssuer()) != "wrong_client".equals(fault)
              && claims.getAudience().contains(reference.tokenEndpoint().toString()),
          "Invalid registered-client signature, identity or AS audience");
      require(
          claims.getExpirationTime() != null
              && ("expired".equals(fault)
                  ? claims
                      .getExpirationTime()
                      .toInstant()
                      .isBefore(request.sent().minusSeconds(120))
                  : claims.getExpirationTime().toInstant().isAfter(request.sent()))
              && (claims.getNotBeforeTime() == null
                  || !claims.getNotBeforeTime().toInstant().isAfter(request.sent()))
              && ("expired".equals(fault)
                  ? claims.getIssueTime() != null
                      && claims.getIssueTime().before(claims.getExpirationTime())
                  : claims.getIssueTime() == null
                      || Math.abs(
                              request.sent().getEpochSecond()
                                  - claims.getIssueTime().toInstant().getEpochSecond())
                          <= 60)
              && claims.getJWTID() != null
              && !claims.getJWTID().isBlank()
              && !claims.getJWTID().equals(reference.originalAssertionId()),
          "Expired, premature or reused SDK client assertion");
      require(
          !claims.getClaims().containsKey("client_statement"),
          "SDK revocation assertion unexpectedly includes attestation");
    }
    new DpopValidationSteps().verifyDpopSignature(request.proof());
    var proof = SignedJWT.parse(request.proof());
    var proofClaims = proof.getJWTClaimsSet();
    require(
        new JOSEObjectType("dpop+jwt").equals(proof.getHeader().getType())
            && !proof.getHeader().getJWK().isPrivate()
            && access.getJSONObjectClaim("cnf") != null
            && proof
                .getHeader()
                .getJWK()
                .computeThumbprint()
                .toString()
                .equals(access.getJSONObjectClaim("cnf").get("jkt"))
            && "POST".equals(proofClaims.getStringClaim("htm"))
            && endpoint.toString().equals(proofClaims.getStringClaim("htu"))
            && !proofClaims.getClaims().containsKey("nonce"),
        "Invalid SDK DPoP target, grant-key binding, type or unexpected nonce");
    require(
        proofClaims.getIssueTime() != null
            && Math.abs(
                    request.sent().getEpochSecond()
                        - proofClaims.getIssueTime().toInstant().getEpochSecond())
                <= 60
            && proofClaims.getJWTID() != null
            && !proofClaims.getJWTID().isBlank()
            && !proofClaims.getJWTID().equals(reference.originalProofId()),
        "Stale or reused SDK DPoP proof");
  }

  private static boolean sameAuthority(URI endpoint, String host) {
    if (host == null) {
      return false;
    }
    var actual = URI.create("https://" + host);
    return endpoint.getHost().equalsIgnoreCase(actual.getHost())
        && (endpoint.getPort() == -1 ? 443 : endpoint.getPort())
            == (actual.getPort() == -1 ? 443 : actual.getPort())
        && actual.getRawUserInfo() == null
        && actual.getRawPath().isEmpty()
        && actual.getRawQuery() == null
        && actual.getRawFragment() == null;
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders).orElseThrow();
  }

  private static String optional(RbelElement element, String path) {
    var values = element.findRbelPathMembers(path);
    return values.size() == 1 ? values.getFirst().getRawStringContent() : null;
  }

  private static String value(RbelElement element, String path) {
    var result = optional(element, path);
    require(result != null, "Missing or ambiguous SDK request evidence: " + path);
    return result;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
