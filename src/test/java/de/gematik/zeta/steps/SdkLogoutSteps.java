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

import com.nimbusds.jwt.SignedJWT;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.KvnrGenerator;
import de.gematik.zeta.services.RecordedMessages;
import de.gematik.zeta.services.RevocationProofs;
import de.gematik.zeta.services.SdkLogoutRuntime;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real SDK logout stimulation and independent binding to the recorded initial grant.
 */
public class SdkLogoutSteps {
  private Map<String, String> evidence;
  private Map<String, String> verificationKeys;
  private SdkLogoutRuntime runtime;
  private boolean copyKeys;
  private URI grantEndpoint;
  private URI revocationEndpoint;
  private RevocationProofs grantProofs;
  private Map<String, String> grantParameters;
  private String originalRefresh;
  private String originalSession;
  private io.restassured.response.Response refreshResponse;

  /**
   * Prepares a fresh bounded SDK runtime without performing any SDK business operation.
   */
  @Gegebensei("ein steuerbarer SDK-Logout-Kontext mit echter OIDC-Konfiguration")
  public void prepare() throws Exception {
    prepareRuntime(false);
  }

  /**
   * Enables an independent original-key copy when the scenario needs later grant verification.
   */
  @Gegebensei("ein steuerbarer SDK-Logout-Kontext mit echter OIDC-Konfiguration und unabhängigem Grant-Prüfclient")
  public void prepareWithVerifier() throws Exception {
    prepareRuntime(true);
  }

  private void prepareRuntime(boolean copyKeys) throws Exception {
    require(runtime == null, "SDK logout runtime already exists");
    var identity = KvnrGenerator.generateRandomValidKvnr();
    this.copyKeys = copyKeys;
    runtime = new SdkLogoutRuntime();
    runtime.start(identity, config("oidcTestData.emailDomain"), copyKeys);
    TigerGlobalConfiguration.putValue(
        "CC_SDK_LOGOUT_IDENTITY", identity, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /**
   * Invokes precisely the selected public SDK operation on the same retained context.
   */
  @Wenn("der isolierte SDK-Logout-Kontext die Operation {word} ausführt")
  public void operation(String command) throws Exception {
    require(runtime != null, "SDK runtime has not been prepared");
    require(
        List.of("create", "discover", "register", "authenticate", "logout").contains(command),
        "Unknown SDK operation");
    evidence = runtime.operation(command);
    if (copyKeys && "authenticate".equals(command)) {
      require(
          "true".equals(evidence.get("proof_keys_copied")),
          "Missing original software verification-key copy");
      verificationKeys = runtime.verificationKeys();
    }
    SerenityReportUtils.addCustomData(
        "Actual SDK operation: " + command, new ObjectMapper().writeValueAsString(evidence));
  }

  /**
   * Binds independent test keys and the recorded initial SDK grant without issuing a request.
   */
  @Gegebensei("ein unabhängiger Logout-Grant-Prüfclient mit Discovery {tigerResolvedString}, Token-Request {tigerResolvedString} und Token-Antwort {tigerResolvedString}")
  public void prepareGrantVerifier(String discovery, String tokenRequest, String tokenResponse)
      throws Exception {
    require(verificationKeys != null, "Missing independent software test-key copy");
    var json = new ObjectMapper();
    var metadata = json.readTree(discovery);
    var endpoint = validateEndpoint(text(metadata, "token_endpoint"), config("zeta_base_url"));
    final var revocation =
        validateEndpoint(text(metadata, "revocation_endpoint"), config("zeta_base_url"));
    var parameters = form(tokenRequest);
    var tokens = json.readTree(tokenResponse);
    var access = text(tokens, "access_token");
    var refresh = text(tokens, "refresh_token");
    final var proofs =
        new RevocationProofs(
            RevocationProofs.loadKey(
                verificationKeys.get("client_public_key"),
                verificationKeys.get("client_private_key")),
            RevocationProofs.loadKey(
                verificationKeys.get("dpop_public_key"), verificationKeys.get("dpop_private_key")),
            parameters.get("client_assertion"),
            access,
            endpoint);
    verificationKeys = null;
    verifyGrant(evidence, access, refresh, proofs.clientId());
    final var initial = SignedJWT.parse(access).getJWTClaimsSet();
    var rt = SignedJWT.parse(refresh).getJWTClaimsSet();
    require(
        initial.getSubject().equals(rt.getSubject())
            && initial.getIssuer().equals(rt.getIssuer())
            && initial.getStringClaim("sid").equals(rt.getStringClaim("sid"))
            && proofs.clientId().equals(rt.getStringClaim("client_id")),
        "Original SDK refresh token belongs to another grant");
    require(
        rt.getExpirationTime() != null
            && rt.getExpirationTime().toInstant().isAfter(Instant.now().plusSeconds(30)),
        "Original SDK refresh token is too close to expiry");
    grantEndpoint = endpoint;
    revocationEndpoint = revocation;
    grantProofs = proofs;
    grantParameters = parameters;
    originalRefresh = refresh;
    originalSession = initial.getStringClaim("sid");
  }

  /**
   * Attempts only a refresh of the original grant, with a fresh AS nonce and bound test-key proofs.
   */
  @Wenn("der unabhängige Logout-Prüfclient das ursprüngliche Refresh Token mit frischen Proofs verwendet")
  public void attemptOriginalRefresh() throws Exception {
    require(
        grantProofs != null && refreshResponse == null,
        "Missing independent grant verifier or repeated refresh");
    var nonceReply =
        TigerHttpClient.givenDefaultSpec()
            .get("https://" + config("zeta_base_url") + config("paths.guard.nonceEndpointPath"));
    require(
        nonceReply.statusCode() == 200 && nonceReply.asString().matches("[A-Za-z0-9_-]+"),
        "Could not obtain a fresh AS nonce for the logout verification");
    var nonce = nonceReply.asString();
    var now = Instant.now();
    var request = originalRefreshParameters(grantParameters);
    request.put("grant_type", "refresh_token");
    request.put("refresh_token", originalRefresh);
    request.put("client_id", grantProofs.clientId());
    request.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
    request.put("client_assertion", grantProofs.refreshAssertion(nonce, now));
    refreshResponse =
        TigerHttpClient.givenDefaultSpec()
            .header("DPoP", grantProofs.dpop("POST", grantEndpoint, nonce, now))
            .contentType("application/x-www-form-urlencoded")
            .formParams(request)
            .post(grantEndpoint);
  }

  /**
   * Checks the actual SDK POST, independent refresh rejection and authoritative session absence.
   */
  @Dann("beendet der tatsächliche SDK-Logout den ursprünglichen Grant nachweislich auf dem Server")
  public void verifyServerLogout() throws Exception {
    require(refreshResponse != null, "Independent original-grant refresh was not attempted");
    var json = new ObjectMapper();
    var body = json.readTree(refreshResponse.asString());
    var inventory =
        new SessionTerminationSteps().inspectServerSession(grantProofs.clientId(), originalSession);
    var posted =
        successfulSdkRevocation(revocationEndpoint, originalRefresh, grantProofs.clientId());
    SerenityReportUtils.addCustomData(
        "Independent SDK logout grant verification",
        json.writeValueAsString(
            Map.of(
                "sdk_revocation_post",
                posted,
                "refresh_status",
                refreshResponse.statusCode(),
                "server_session",
                inventory)));
    verifyServerOutcome(posted, refreshResponse.statusCode(), body, inventory);
  }

  static Map<String, String> originalRefreshParameters(Map<String, String> original) {
    // Preserve the successful SDK exchange's optional parameters, including their absence.
    // Requesting another resource/scope here would introduce an unrelated reason for rejection.
    var result = new HashMap<String, String>();
    for (var name : List.of("resource", "audience", "scope", "requested_token_type")) {
      if (original.containsKey(name)) {
        result.put(name, original.get(name));
      }
    }
    return result;
  }

  private static boolean successfulSdkRevocation(URI endpoint, String refresh, String client) {
    var messages = RecordedMessages.snapshot();
    var timing = new TimingGlue();
    for (var message : messages) {
      var path = message.findRbelPathMembers("$.path");
      var method = message.findRbelPathMembers("$.method");
      if (path.size() != 1
          || !endpoint.getRawPath().equals(path.getFirst().getRawStringContent())
          || method.size() != 1
          || !"POST".equals(method.getFirst().getRawStringContent())) {
        continue;
      }
      var token = message.findRbelPathMembers("$.body.token");
      var subject = message.findRbelPathMembers("$.body.client_assertion.body.sub");
      if (token.size() != 1
          || !refresh.equals(token.getFirst().getRawStringContent())
          || subject.size() != 1
          || !client.equals(subject.getFirst().getRawStringContent())) {
        continue;
      }
      var response = timing.findResponseForRequest(messages, message);
      if (response != null
          && response.findRbelPathMembers("$.responseCode").stream()
              .anyMatch(v -> "200".equals(v.getRawStringContent()))) {
        return true;
      }
    }
    return false;
  }

  static void verifyServerOutcome(boolean posted, int status, JsonNode body, JsonNode inventory) {
    var failures = new ArrayList<String>();
    if (!posted) {
      failures.add(
          "SDK did not successfully POST its original refresh token to the published revocation"
              + " endpoint");
    }
    if (status < 400 || status >= 500) {
      failures.add("Original SDK grant was not rejected after logout (HTTP " + status + ")");
    }
    try {
      SessionTerminationSteps.verifyError(body);
    } catch (AssertionError failure) {
      failures.add(
          "Independent refresh did not reject the terminated grant without issuing tokens");
    }
    if (!inventory.path("admin_sessions").isIntegralNumber()
        || inventory.path("admin_sessions").asInt() != 0) {
      failures.add("Original SDK session remains active on the authorization server");
    }
    require(failures.isEmpty(), String.join("; ", failures));
  }

  private static Map<String, String> form(String body) {
    var result = new HashMap<String, String>();
    for (var part : body.split("&", -1)) {
      var pair = part.split("=", 2);
      require(pair.length == 2, "Malformed original SDK token request");
      try {
        var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
        require(
            result.putIfAbsent(key, URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) == null,
            "Ambiguous original SDK token parameter");
      } catch (IllegalArgumentException failure) {
        throw new AssertionError("Malformed original SDK token encoding");
      }
    }
    return result;
  }

  private static String text(JsonNode node, String field) {
    require(
        node.path(field).isString() && !node.path(field).asString().isBlank(),
        "Missing logout verification field: " + field);
    return node.path(field).asString();
  }

  /**
   * Releases only the independent in-memory test fixture; it never modifies SDK/product storage.
   */
  @After
  public void clearVerificationKeys() throws Exception {
    try {
      if (runtime != null) {
        runtime.close();
      }
    } finally {
      verificationKeys = null;
      evidence = null;
      grantProofs = null;
      grantParameters = null;
      originalRefresh = null;
      refreshResponse = null;
    }
  }

  /**
   * Binds both recorded tokens to the SDK's original storage without exporting its private keys.
   *
   * @param access access token from the successful original AS response
   * @param refresh refresh token from that same response
   * @param clientId client assertion subject from that request
   * @throws Exception if token decoding or hashing fails
   */
  @Dann("der SDK-Logout gehört zu Access Token {tigerResolvedString}, Refresh Token {tigerResolvedString} und Client {tigerResolvedString}")
  public void verifyGrant(String access, String refresh, String clientId) throws Exception {
    verifyGrant(evidence, access, refresh, clientId);
  }

  static void verifyGrant(Map<String, String> proof, String access, String refresh, String clientId)
      throws Exception {
    require(
        proof != null && "sdk-logout-v1".equals(proof.get("protocol")),
        "SDK logout proof is absent");
    require(
        "HAS_ACCESS_AND_REFRESH_TOKEN".equals(proof.get("status_before")),
        "SDK lacked an authenticated token pair");
    require(
        Long.parseLong(proof.get("control_socket_writes")) > 0,
        "The independent SDK socket observer did not record the successful control flow");
    require(
        hash(access).equals(proof.get("access_sha256"))
            && hash(refresh).equals(proof.get("refresh_sha256")),
        "Recorded token pair does not match the SDK context before logout");
    var claims = SignedJWT.parse(access).getJWTClaimsSet();
    require(
        proof.get("identity").equals(claims.getSubject())
            && clientId.equals(claims.getStringClaim("client_id")),
        "Recorded access token belongs to another identity or client");
    long authentication = Long.parseLong(proof.get("authentication_started_ms"));
    long started = Long.parseLong(proof.get("logout_started_ms"));
    long completed = Long.parseLong(proof.get("logout_completed_ms"));
    require(authentication <= started && started <= completed, "Inconsistent SDK operation order");
    require(
        claims.getExpirationTime() != null
            && claims.getExpirationTime().getTime() > completed + 30000,
        "Initial SDK access token expired too close to logout");
    require(
        "true".equals(proof.get("logout_success")),
        "The real SDK logout did not complete successfully");
  }

  /** Checks the selected genuine SDK request against its registration, grant and logout interval. */
  @Dann("der aufgezeichnete SDK-Widerruf für Access Token {tigerResolvedString}, Refresh Token "
      + "{tigerResolvedString} und Client {tigerResolvedString} an {tigerResolvedString} enthält "
      + "gültige frische Client- und DPoP-Nachweise ohne Attestierung oder Nonce")
  public void verifyRequest(String access, String refresh, String clientId, String endpoint)
      throws Exception {
    verifyGrant(evidence, access, refresh, clientId);
    SdkRevocationRequest.verifyRecorded(evidence, access, refresh, clientId, endpoint);
  }

  /**
   * Allows the test to observe only the HTTPS revocation endpoint published by this Guard.
   *
   * @param endpoint original AS discovery value
   */
  @Dann("verwende den veröffentlichten SDK-Revocation-Endpunkt {tigerResolvedString}")
  public void endpoint(String endpoint) {
    var uri = validateEndpoint(endpoint, config("zeta_base_url"));
    TigerGlobalConfiguration.putValue(
        "CC_SDK_REVOCATION_PATH", uri.getRawPath(), ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  static URI validateEndpoint(String endpoint, String guardHost) {
    var uri = URI.create(endpoint);
    require(
        "https".equals(uri.getScheme())
            && guardHost.equals(uri.getHost())
            && uri.getRawUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && uri.getRawPath() != null
            && !uri.getRawPath().isBlank()
            && !"/".equals(uri.getRawPath()),
        "Discovery revocation endpoint is not a plain HTTPS endpoint of the configured Guard");
    return uri;
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
