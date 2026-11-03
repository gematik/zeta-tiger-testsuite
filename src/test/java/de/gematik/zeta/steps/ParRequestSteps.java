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
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Read-only correlation of original DCR, PAR and authorize messages; sends no requests. */
public class ParRequestSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private String client;
  private String jwks;
  private List<String> redirects;
  private Instant registered;
  private Observation outer;
  private String requestUri;
  private Instant issued;

  record Observation(String method, URI target, String contentType, String body, Instant sent) {}

  /** Captures the public key from this scenario's selected successful SDK registration. */
  @Dann("merke die originale PAR-Clientregistrierung für Client {tigerResolvedString}")
  public void registration(String expectedClient) throws Exception {
    var request = current();
    var observation = observation(request);
    require("POST".equals(observation.method())
        && endpoint(config("paths.guard.registerEndpointPath")).equals(observation.target()),
        "Selected request is not the SDK registration");
    var response = response(request, 201);
    require(expectedClient.equals(JSON.readTree(value(response, "$.body"))
        .path("client_id").asString()), "Registration belongs to another SDK client");
    var body = JSON.readTree(observation.body());
    require("private_key_jwt".equals(body.path("token_endpoint_auth_method").asString()),
        "SDK did not register private_key_jwt authentication");
    require(client == null, "PAR registration already captured");
    client = expectedClient;
    jwks = body.path("jwks").toString();
    redirects = new ArrayList<>();
    body.path("redirect_uris").forEach(uri -> redirects.add(uri.asString()));
    registered = time(response);
  }

  /** Verifies original authentication and keeps the paired URI for a later authorize request. */
  @Dann("merke den authentisierten äußeren PAR und seine ausgegebene request_uri")
  public void outer() throws Exception {
    require(client != null && outer == null, "Missing registration or duplicate PAR observation");
    var request = current();
    var candidate = observation(request);
    var form = formAt(candidate, endpoint(config("testdata.ccPar.outerPath")));
    require(!candidate.sent().isBefore(registered), "PAR predates its SDK registration");
    verifyAssertion(form, client, jwks, candidate.sent(), List.of(
        config("paths.guard.issuerUrl"), endpoint(config("paths.guard.tokenEndpointPath")).toString(),
        endpoint(config("testdata.ccPar.outerPath")).toString()));
    var response = response(request, 201);
    requestUri = JSON.readTree(value(response, "$.body")).path("request_uri").asString();
    require(requestUri != null && !requestUri.isBlank() && URI.create(requestUri).isAbsolute(),
        "PAR response lacks an absolute request_uri");
    issued = time(response);
    require(!issued.isBefore(candidate.sent()), "PAR response predates its request");
    outer = candidate;
  }

  /** Checks outer PAR values against the captured SDK context. */
  @Dann("entspricht der originale äußere PAR seinem SDK-Kontext")
  public void outerContext() {
    require(outer != null, "Missing original outer PAR");
    verifyOuterContext(SmbExchangeSteps.form(outer.body()), client, redirects,
        config("oidcTestData.pdp.entityStatement.redirectUris.redirectUri"),
        config("oidcTestData.pdp.entityStatement.redirectUris.oidcRedirectUri"),
        config("testdata.ccPolicy.grantedScopes"), config("testdata.ccOidcAcr.issuer"));
  }

  /** Checks the SDK's actual GET, after the paired response, at the configured AS endpoint. */
  @Dann("verwendet der originale SDK-Authorize-Aufruf genau die ausgegebene äußere request_uri")
  public void authorize() {
    require(outer != null, "Missing original outer PAR");
    verifyAuthorize(observation(current()), endpoint(config("testdata.ccPar.authorizePath")),
        client, requestUri, issued);
  }

  static void verifyAssertion(Map<String, String> form, String client, String jwks,
      Instant sent, List<String> audiences) throws Exception {
    verifyAssertion(form, client, jwks, sent, audiences, true);
  }

  static void verifyAssertion(Map<String, String> form, String client, String jwks,
      Instant sent, List<String> audiences, boolean validSignature) throws Exception {
    require("urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
        .equals(form.get("client_assertion_type")), "Missing private_key_jwt assertion type");
    var keys = JWKSet.parse(jwks).getKeys();
    require(keys.size() == 1 && keys.getFirst() instanceof ECKey && !keys.getFirst().isPrivate(),
        "Expected the original registered public EC key");
    var jwt = SignedJWT.parse(required(form, "client_assertion"));
    verifyAssertionHeader(jwt.getHeader(), (ECKey) keys.getFirst());
    var claims = jwt.getJWTClaimsSet();
    require(JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
        && jwt.verify(new ECDSAVerifier((ECKey) keys.getFirst())) == validSignature,
        "PAR assertion has invalid registered-key signature");
    verifyAssertionClaims(claims, client, sent, audiences);
    require(!form.containsKey("client_id") || client.equals(form.get("client_id")),
        "PAR contains conflicting client identities");
    // This authentication check does not replace the mandatory PAR client_id parameter.
  }

  static void verifyAssertionHeader(com.nimbusds.jose.JWSHeader header, ECKey signer) throws Exception {
    require(JWSAlgorithm.ES256.equals(header.getAlgorithm())
        && header.getType() != null && "JWT".equals(header.getType().toString()),
        "PAR assertion header requires alg ES256 and typ JWT");
    // Ordinary fixtures negotiate no critical extensions; a dedicated case proves its own exception.
    // Unknown noncritical parameters remain allowed by RFC 7515 section 4.
    require(header.getCriticalParams() == null,
        "Unrelated PAR proof must not contain an unverified critical JOSE extension");
    var embedded = header.getJWK();
    require(embedded instanceof ECKey && !embedded.isPrivate()
        && com.nimbusds.jose.jwk.Curve.P_256.equals(((ECKey) embedded).getCurve())
        && signer.computeThumbprint().equals(embedded.computeThumbprint()),
        "PAR assertion header requires the signer's public EC/P-256 JWK");
  }

  static void verifyAssertionClaims(JWTClaimsSet claims, String client, Instant sent,
      List<String> audiences) {
    // OIDC Core 9 requires a string jti for private_key_jwt; iat remains optional.
    // Presence alone does not prove uniqueness or server-side replay protection.
    require(claims.getClaim("jti") instanceof String,
        "PAR private_key_jwt assertion lacks its required string jti");
    require(client.equals(claims.getIssuer()) && client.equals(claims.getSubject())
        && claims.getAudience().stream().anyMatch(audiences::contains),
        "PAR assertion has invalid client identity or AS audience");
    require(claims.getExpirationTime() != null
        && claims.getExpirationTime().toInstant().isAfter(sent)
        && (claims.getNotBeforeTime() == null
            || !claims.getNotBeforeTime().toInstant().isAfter(sent)),
        "PAR assertion is expired or not yet valid at the actual request time");
  }

  static void verifyOuterContext(Map<String, String> form, String client, List<String> registeredRedirects,
      String appRedirect, String oidcRedirect, String scope, String idp) {
    common(form);
    require(!form.containsKey("state") || form.get("state").matches("[\\x20-\\x7E]+"),
        "PAR state violates the OAuth VSCHAR grammar");
    require(appRedirect.equals(form.get("redirect_uri"))
        && oidcRedirect.equals(form.get("oidc_redirect_uri"))
        && registeredRedirects.containsAll(List.of(appRedirect, oidcRedirect)),
        "PAR redirect URIs do not belong to the configured, registered SDK context");
    require(scope.equals(form.get("scope")) && idp.equals(form.get("idp_iss")),
        "PAR does not carry the requested scope and IDP issuer");
    require(!form.containsKey("client_id") || client.equals(form.get("client_id")),
        "Outer PAR uses another client identity");
  }

  static void verifyAuthorize(Observation actual, URI endpoint, String client,
      String requestUri, Instant issued) {
    var uri = actual.target();
    require("GET".equals(actual.method()) && uri.getRawQuery() != null
        && uri.toString().substring(0, uri.toString().indexOf('?')).equals(endpoint.toString())
        && uri.getRawFragment() == null && !actual.sent().isBefore(issued),
        "Authorize has wrong method, endpoint or time");
    var query = SmbExchangeSteps.form(uri.getRawQuery());
    require(client.equals(query.get("client_id")) && requestUri.equals(query.get("request_uri")),
        "Authorize changed the client or the issued outer request_uri");
  }

  static Map<String, String> formAt(Observation request, URI endpoint) {
    require("POST".equals(request.method()) && endpoint.equals(request.target()),
        "PAR has wrong method or endpoint");
    require(request.contentType() != null && "application/x-www-form-urlencoded"
        .equalsIgnoreCase(request.contentType().split(";", 2)[0].trim()),
        "PAR is not form encoded");
    return SmbExchangeSteps.form(request.body());
  }

  private static void common(Map<String, String> form) {
    require((!form.containsKey("response_type") || "code".equals(form.get("response_type")))
        && "S256".equals(form.get("code_challenge_method"))
        && required(form, "code_challenge").matches("[A-Za-z0-9_-]{43}"),
        "PAR lacks its code/S256 challenge");
    require(!form.containsKey("request_uri"), "PAR must not contain request_uri");
  }

  static Observation observation(RbelElement request) {
    var first = request.getRawStringContent().split("\\r?\\n", 2)[0].split(" ", -1);
    require(first.length == 3, "Ambiguous HTTP request target");
    var uri = URI.create(first[1]);
    if (!uri.isAbsolute()) {
      require(first[1].startsWith("/") && !first[1].startsWith("//"), "Invalid request target");
      uri = URI.create("https://" + value(request, "$.header.[~'Host']") + first[1]);
    }
    require("https".equals(uri.getScheme()) && uri.getRawUserInfo() == null,
        "Unexpected PAR request authority");
    // Normalize the default TLS port without changing the path, query or case-sensitive values.
    if (uri.getPort() == 443) {
      uri = URI.create("https://" + uri.getHost() + uri.getRawPath()
          + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
    }
    var types = request.findRbelPathMembers("$.header.[~'Content-Type']");
    return new Observation(first[0], uri,
        types.size() == 1 ? types.getFirst().getRawStringContent() : null,
        request.findRbelPathMembers("$.body").isEmpty() ? "" : value(request, "$.body"),
        time(request));
  }

  private static RbelElement current() {
    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    require(request != null, "Missing selected PAR evidence");
    return request;
  }

  private static RbelElement response(RbelElement request, int code) {
    var messages = RecordedMessages.snapshot();
    var response = new TimingGlue().findResponseForRequest(messages, request);
    require(response != null && Integer.toString(code).equals(value(response, "$.responseCode")),
        "Missing successful response paired with the selected request");
    return response;
  }

  private static Instant time(RbelElement message) {
    return message.getFacet(RbelMessageTimingFacet.class).orElseThrow()
        .getTransmissionTime().toInstant();
  }

  private static URI endpoint(String path) {
    return URI.create("https://" + config("zeta_base_url") + path);
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders).orElseThrow();
  }

  private static String value(RbelElement message, String path) {
    var found = message.findRbelPathMembers(path);
    require(found.size() == 1, "Missing or ambiguous PAR evidence: " + path);
    return found.getFirst().getRawStringContent();
  }

  private static String required(Map<String, String> form, String field) {
    var value = form.get(field);
    require(value != null && !value.isBlank(), "PAR lacks required field: " + field);
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
