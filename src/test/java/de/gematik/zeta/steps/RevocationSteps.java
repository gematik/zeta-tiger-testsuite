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
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.AsReadOnlyRuntime;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.DriverControlClient;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit SDK revocation and refresh operations retaining original tokens independently of logout.
 */
public class RevocationSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private DriverControlClient driver;
  private String clientId;
  private URI tokenEndpoint;
  private URI revocationEndpoint;
  private String refreshToken;
  private String accessToken;
  private Response response;
  private Response resourceResponse;
  private AsReadOnlyRuntime expiryClock;
  private Instant observedServerTime;
  private String preparedTokenResponse;
  private String lastRevocationToken;
  private String lastAssertionVariant;
  private String registeredJwks;
  private String previousAssertionId;
  private String previousProofId;
  private String preparedKind;
  private String unknownToken;
  private final TigerProxyManipulationsSteps proxy = new TigerProxyManipulationsSteps();
  private int refreshAttempts;
  private boolean ownRevocationAttempted;
  private TokenState activeReference;
  private TokenState expiryReference;
  private boolean revocationVerified;
  private boolean expiryVerified;

  /**
   * Retains fixtures from the observed SDK grant to check the SDK's unchanged token selection.
   *
   * @param discovery full AS discovery response
   * @param tokenRequest successful form-encoded token request
   * @param tokenResponse successful JSON token response
   * @throws Exception if fixtures do not belong to the successful client
   */
  @Gegebensei(
      "bereite den SDK-Kontext für Revocation mit Discovery {tigerResolvedString}, Token-Request"
          + " {tigerResolvedString} und Token-Antwort {tigerResolvedString} vor")
  public void prepare(String discovery, String tokenRequest, String tokenResponse)
      throws Exception {
    resetStateEvidence();
    var metadata = JSON.readTree(discovery);
    tokenEndpoint = trustedEndpoint(text(metadata, "token_endpoint"));
    revocationEndpoint = trustedEndpoint(text(metadata, "revocation_endpoint"));
    final var parameters = parseForm(tokenRequest);
    var tokens = JSON.readTree(tokenResponse);
    refreshToken = text(tokens, "refresh_token");
    accessToken = text(tokens, "access_token");
    preparedTokenResponse = tokenResponse;
    var assertion = SignedJWT.parse(parameters.get("client_assertion")).getJWTClaimsSet();
    clientId = assertion.getIssuer();
    require(
        clientId != null
            && clientId.equals(assertion.getSubject())
            && assertion.getAudience().contains(tokenEndpoint.toString()),
        "Observed grant has inconsistent client identity or audience");
    tokenState(accessToken, refreshToken, clientId, Instant.now());
    driver = DriverControlClient.primary();
    var messages = RecordedMessages.snapshot();
    var registrations =
        messages.stream()
            .filter(
                m ->
                    "POST".equals(optional(m, "$.method"))
                        && config("paths.guard.registerEndpointPath").equals(optional(m, "$.path")))
            .filter(
                m -> {
                  var reply = new TimingGlue().findResponseForRequest(messages, m);
                  return reply != null && clientId.equals(optional(reply, "$.body.client_id"));
                })
            .toList();
    require(registrations.size() == 1, "Missing unique successful SDK registration");
    registeredJwks =
        JSON.readTree(required(registrations.getFirst(), "$.body")).path("jwks").toString();
    var grants = messages.stream().filter(m -> tokenRequest.equals(optional(m, "$.body"))).toList();
    require(grants.size() == 1, "Missing unique original SDK grant");
    previousAssertionId = assertion.getJWTID();
    previousProofId =
        SignedJWT.parse(required(grants.getFirst(), "$.header.[~'DPoP']"))
            .getJWTClaimsSet()
            .getJWTID();
  }

  /** Prepares proxy mutations separately from the genuine SDK revocation action. */
  @Gegebensei(
      "TigerProxy bereitet den Widerruf von Token {string} mit Client Assertion {string} vor")
  public void prepareMutation(String tokenKind, String fault) throws Exception {
    require(
        driver != null
            && java.util.Set.of("own", "revoked", "expired", "access", "unknown")
                .contains(tokenKind)
            && java.util.Set.of("valid", "missing", "wrong_signature", "expired", "wrong_client")
                .contains(fault),
        "Unsupported or unprepared revocation mutation");
    preparedKind = tokenKind;
    lastAssertionVariant = fault;
    unknownToken = null;
    var path = java.util.regex.Pattern.quote(revocationEndpoint.getPath());
    var condition = "isRequest && request.path =~ '.*" + revocationEndpoint.getPath() + "'";
    if (tokenKind.equals("unknown")) {
      unknownToken = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
      proxy.setTigerProxyRegexManipulation(
          condition, "$.body", "(^|&)token=[^&]*",
          "$1token=" + URLEncoder.encode(unknownToken, StandardCharsets.UTF_8));
    }
    switch (fault) {
      case "valid" -> {}
      case "missing" ->
          proxy.setTigerProxyRegexManipulation(
              condition, "$.body", "(^|&)client_assertion=[^&]*(&|$)", "$1");
      case "wrong_signature" ->
          proxy.setTigerProxyJwtManipulationWithConditionNoResign(
              "$.body.client_assertion", "variant.name", "signature_bit_flip", path, 1);
      default -> {
        var storageReply = TigerHttpClient.givenDefaultSpec().get(config("paths.client.storage"));
        require(storageReply.statusCode() == 200, "Cannot read the SDK signing fixture");
        var storage = JSON.readTree(storageReply.asString());
        var key = storage.path("client_private_key").asString();
        require(key != null && !key.isBlank(), "SDK signing fixture is missing");
        if (fault.equals("expired")) {
          proxy.expireJwt("$.body.client_assertion", key, path, 1);
        } else {
          var foreign =
              tools.jackson.databind.node.JsonNodeFactory.instance.textNode(
                  "foreign-" + java.util.UUID.randomUUID());
          proxy.configureAtomicJwtFields(
              "$.body.client_assertion",
              key,
              path,
              1,
              Map.of("body.iss", foreign, "body.sub", foreign));
        }
      }
    }
  }

  /**
   * Sends a fresh authenticated control refresh with the latest unconsumed token.
   *
   * @throws Exception if fresh proof construction fails
   */
  @Wenn("der SDK-Kontext einen Refresh mit seinem aktuellen Refresh Token sendet")
  public void refresh() throws Exception {
    sendRefresh();
  }

  private void sendRefresh() throws Exception {
    require(driver != null, "SDK revocation context has not been prepared");
    verifyRefreshLifetime(refreshToken, Instant.now());
    refreshAttempts++;
    var before = RecordedMessages.snapshot().stream().map(RbelElement::getUuid)
        .collect(java.util.stream.Collectors.toSet());
    try {
      response = driver.response("refresh", Map.of(), "POST", tokenEndpoint.getPath());
      var requests = RecordedMessages.snapshot().stream()
          .filter(m -> !before.contains(m.getUuid())
              && "POST".equals(optional(m, "$.method"))
              && tokenEndpoint.getPath().equals(optional(m, "$.path")))
          .toList();
      require(requests.size() == 1, "Missing unique observed SDK refresh");
      verifyRefreshTokenSelection(required(requests.getFirst(), "$.body"), refreshToken);
    } finally {
      proxy.stopAllTigerProxyManipulationsStrictly();
    }
  }

  static void verifyRefreshTokenSelection(String body, String expectedToken) {
    var form = parseForm(body);
    require("refresh_token".equals(form.get("grant_type"))
            && expectedToken != null && !expectedToken.isBlank()
            && expectedToken.equals(form.get("refresh_token")),
        "SDK refresh did not send the captured current refresh token unchanged");
  }

  static void verifyRefreshLifetime(String token, Instant now) throws Exception {
    var claims = SignedJWT.parse(token).getJWTClaimsSet();
    require(
        claims.getExpirationTime() != null
            && claims.getExpirationTime().toInstant().isAfter(now.plusSeconds(10)),
        "Independent refresh token is expired or too close to expiry");
    require(
        claims.getNotBeforeTime() == null || !claims.getNotBeforeTime().toInstant().isAfter(now),
        "Independent refresh token is not yet valid");
  }

  /** Requires and adopts the successful control's newly issued tokens, respecting RT rotation. */
  @Dann("der SDK-Refresh ist erfolgreich und übernimmt das neue Refresh Token")
  public void acceptRefresh() throws Exception {
    status(200);
    var body = JSON.readTree(response.asString());
    accessToken = text(body, "access_token");
    refreshToken = text(body, "refresh_token");
    tokenState(accessToken, refreshToken, clientId, Instant.now());
  }

  /**
   * Sends a form-encoded revocation, changing only the requested assertion failure.
   *
   * @param tokenKind own, unknown, access, independently revoked, or genuinely expired
   * @throws Exception if fresh proof construction fails
   */
  @Wenn("der SDK-Kontext das Token {string} widerruft")
  public void revoke(String tokenKind) throws Exception {
    require(driver != null, "SDK revocation context has not been prepared");
    require(tokenKind.equals(preparedKind), "Revocation fixture has not been prepared in Tiger");
    final var assertionVariant = lastAssertionVariant;
    require(
        java.util.Set.of("own", "unknown", "access", "revoked", "expired").contains(tokenKind),
        "Unknown revocation token fixture");
    var now = Instant.now();
    if (tokenKind.equals("revoked")) {
      require(
          revocationVerified
              && activeReference != null
              && activeReference.refresh().equals(refreshToken),
          "The repeated revocation lacks an independently terminated, unchanged token");
      verifyRefreshLifetime(refreshToken, now);
    }
    if (tokenKind.equals("expired")) {
      require(
          expiryVerified
              && expiryReference != null
              && !ownRevocationAttempted
              && expiryReference.refresh().equals(refreshToken),
          "The expiration variant lacks an unrevoked, independently expired token");
      verifyExpired(expiryReference, now);
      requireNoRecordedRefresh(expiryReference.refresh());
    }
    lastRevocationToken =
        switch (tokenKind) {
          case "own", "revoked", "expired" -> refreshToken;
          case "access" -> accessToken;
          default -> unknownToken;
        };
    lastAssertionVariant = assertionVariant;
    if (refreshToken.equals(lastRevocationToken)) {
      ownRevocationAttempted = true;
    }
    var kind =
        switch (tokenKind) {
          case "access" -> "access";
          default -> "refresh";
        };
    var before = RecordedMessages.snapshot();
    var started = Instant.now();
    try {
      response = driver.response(
          "revoke", Map.of("tokenKind", kind), "POST", revocationEndpoint.getPath());
      var completed = Instant.now();
      // The remote proxy's WebSocket history can arrive after the driver's HTTP reply.
      var requests =
          BoundedObservation.await(
              () ->
                  RecordedMessages.snapshot().stream()
                      .filter(
                          m ->
                              !before.contains(m)
                                  && "POST".equals(optional(m, "$.method"))
                                  && revocationEndpoint.getPath().equals(optional(m, "$.path")))
                      .toList(),
              found -> !found.isEmpty(),
              Duration.ofSeconds(5),
              Duration.ofMillis(100),
              "No observed SDK revocation within the evidence deadline");
      require(requests.size() == 1, "Missing unique observed SDK revocation");
      var request = requests.getFirst();
      var line = request.getRawStringContent().split("\\r?\\n", 2)[0].split(" ", -1);
      require(line.length == 3, "Missing complete SDK request target");
      var proof = required(request, "$.header.[~'DPoP']");
      SdkRevocationRequest.verify(
          new SdkRevocationRequest.Observation(
              "POST",
              line[1],
              required(request, "$.header.[~'Host']"),
              required(request, "$.header.[~'Content-Type']"),
              required(request, "$.body"),
              proof,
              request
                  .getFacet(RbelMessageTimingFacet.class)
                  .orElseThrow()
                  .getTransmissionTime()
                  .toInstant()),
          new SdkRevocationRequest.Reference(
              accessToken,
              refreshToken,
              clientId,
              registeredJwks,
              previousAssertionId,
              previousProofId,
              tokenEndpoint,
              revocationEndpoint,
              started,
              completed),
          lastRevocationToken,
          kind.equals("access") ? "access_token" : "refresh_token",
          assertionVariant,
          false);
      previousProofId = SignedJWT.parse(proof).getJWTClaimsSet().getJWTID();
      if (!assertionVariant.equals("missing")) {
        var assertion =
            SignedJWT.parse(parseForm(required(request, "$.body")).get("client_assertion"));
        require(
            JSON.readTree(assertion.getPayload().toString()).path("aud").isArray(),
            "Controlled SDK assertion audience must conform to the array schema");
        previousAssertionId = assertion.getJWTClaimsSet().getJWTID();
      }
    } finally {
      preparedKind = null;
      proxy.stopAllTigerProxyManipulationsStrictly();
    }
  }

  private static String required(RbelElement element, String path) {
    var result = optional(element, path);
    require(result != null, "Missing unambiguous revocation evidence: " + path);
    return result;
  }

  private static String optional(RbelElement element, String path) {
    var values = element.findRbelPathMembers(path);
    return values.size() == 1 ? values.getFirst().getRawStringContent() : null;
  }

  record TokenState(
      String refresh, String client, String session, Instant issued, Instant expires) {}

  /** Retains one demonstrably active session before the feature's own revocation action. */
  @Dann("die aktuelle Refresh-Token-Session ist als aktive Widerrufskontrolle gesichert")
  public void rememberActiveSession() throws Exception {
    var state = tokenState(accessToken, refreshToken, clientId, Instant.now());
    verifySessionCount(
        new SessionTerminationSteps().inspectServerSession(state.client(), state.session()), 1);
    activeReference = state;
    revocationVerified = false;
  }

  /** Distinguishes a genuinely revoked token from an implementation that always returns 200. */
  @Dann("die gesicherte Refresh-Token-Session ist durch den eigenen Widerruf beendet")
  public void verifyOwnRevocation() throws Exception {
    require(
        activeReference != null
            && activeReference.refresh().equals(refreshToken)
            && refreshToken.equals(lastRevocationToken)
            && "valid".equals(lastAssertionVariant),
        "No valid own revocation of the previously active control token");
    status(200);
    verifyRefreshLifetime(refreshToken, Instant.now());
    var inventory =
        new SessionTerminationSteps()
            .inspectServerSession(activeReference.client(), activeReference.session());
    verifySessionCount(inventory, 0);
    revocationVerified = true;
    SerenityReportUtils.addCustomData("Revoked-token precondition", inventory.toString());
  }

  /** Pins a fresh, AS-signed, unconsumed token with the actual short policy-issued lifetime. */
  @Dann(
      "das aktuelle unverbrauchte Refresh Token hat die Laufzeit {int} Sekunden, eine gültige"
          + " AS-Signatur mit JWKS {tigerResolvedString} und eine aktive Session")
  public void rememberExpiringToken(int seconds, String jwks) throws Exception {
    require(
        refreshAttempts == 0 && !ownRevocationAttempted,
        "The expiration fixture must not already have been refreshed or revoked");
    new SignatureVerificationSteps().verifyJwtSignatureFromKid(accessToken, jwks);
    new SignatureVerificationSteps().verifyJwtSignatureFromKid(refreshToken, jwks);
    var state = tokenState(accessToken, refreshToken, clientId, Instant.now());
    verifyIssuedLifetime(preparedTokenResponse, state, seconds);
    requireNoRecordedRefresh(state.refresh());
    verifySessionCount(
        new SessionTerminationSteps().inspectServerSession(state.client(), state.session()), 1);
    expiryReference = state;
    expiryVerified = false;
    expiryClock = new AsReadOnlyRuntime("testdata.ccIdentityRetention");
  }

  /** Waits locally before reading the independently bound AS container clock. */
  @Dann("die lokale Wartezeit für den regulären Ablauf des gesicherten Refresh Tokens ist beendet")
  public void waitForExpiration() throws Exception {
    require(
        expiryReference != null
            && expiryReference.refresh().equals(refreshToken)
            && refreshAttempts == 0
            && !ownRevocationAttempted,
        "Missing unconsumed, unrevoked expiration fixture");
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    BoundedObservation.awaitUntil(
        Instant::now,
        time -> !time.isBefore(expiryReference.expires().plusSeconds(3)),
        deadline,
        Duration.ofMillis(250),
        "Refresh-token expiration exceeds the bounded test profile");
  }

  /** Read-only prerequisite observation; neither stimulates the AS nor consumes the SDK token. */
  @Wenn("die aktuelle Uhrzeit des unveränderten Authorization Servers gelesen wird")
  public void observeServerClock() {
    require(expiryClock != null && expiryReference != null, "Missing expiration context");
    observedServerTime = expiryClock.currentTime();
  }

  /** Confirms real expiration using the unchanged AS runtime's independently observed clock. */
  @Dann("das gesicherte Refresh Token ist regulär seit mindestens zwei Sekunden abgelaufen")
  public void awaitExpiration() {
    require(expiryReference != null && refreshAttempts == 0 && !ownRevocationAttempted
            && expiryReference.refresh().equals(refreshToken)
            && observedServerTime != null,
        "Missing live AS clock observation");
    var serverTime = observedServerTime;
    verifyExpired(expiryReference, serverTime);
    requireNoRecordedRefresh(expiryReference.refresh());
    expiryVerified = true;
    SerenityReportUtils.addCustomData(
        "Actual refresh-token expiration",
        "Issued="
            + expiryReference.issued()
            + "; exp="
            + expiryReference.expires()
            + "; live AS container time="
            + serverTime
            + "; no refresh/revocation of the retained token.");
  }

  static TokenState tokenState(String access, String refresh, String client, Instant now)
      throws Exception {
    require(
        access != null && refresh != null && !access.equals(refresh),
        "Missing distinct original token pair");
    var at = SignedJWT.parse(access).getJWTClaimsSet();
    var rt = SignedJWT.parse(refresh).getJWTClaimsSet();
    require(
        client != null
            && client.equals(at.getStringClaim("client_id"))
            && client.equals(rt.getStringClaim("client_id"))
            && at.getStringClaim("sid") != null
            && at.getStringClaim("sid").equals(rt.getStringClaim("sid"))
            && at.getSubject() != null
            && at.getSubject().equals(rt.getSubject())
            && at.getIssuer() != null
            && at.getIssuer().equals(rt.getIssuer())
            && at.getJSONObjectClaim("cnf") != null
            && at.getJSONObjectClaim("cnf").equals(rt.getJSONObjectClaim("cnf")),
        "Refresh token does not belong to the successful SDK client and session");
    verifyRefreshLifetime(refresh, now);
    require(
        rt.getIssueTime() != null && !rt.getIssueTime().toInstant().isAfter(now.plusSeconds(5)),
        "Refresh-token issuance time is absent or in the future");
    return new TokenState(
        refresh,
        client,
        rt.getStringClaim("sid"),
        rt.getIssueTime().toInstant(),
        rt.getExpirationTime().toInstant());
  }

  static void verifyIssuedLifetime(String tokenResponse, TokenState state, int seconds) {
    var body = JSON.readTree(tokenResponse);
    require(
        seconds > 10
            && seconds <= 60
            && state.refresh().equals(text(body, "refresh_token"))
            && body.path("refresh_expires_in").isIntegralNumber()
            && body.path("refresh_expires_in").canConvertToInt()
            && body.path("refresh_expires_in").asInt() == seconds
            && Duration.between(state.issued(), state.expires())
                .equals(Duration.ofSeconds(seconds)),
        "The AS did not issue the original token with the requested bounded refresh lifetime");
  }

  static void verifyExpired(TokenState state, Instant serverTime) {
    require(
        !serverTime.isBefore(state.expires().plusSeconds(2)),
        "The retained refresh token has not actually expired");
  }

  static void verifySessionCount(JsonNode inventory, int expected) {
    require(
        inventory != null
            && inventory.path("admin_sessions").isIntegralNumber()
            && inventory.path("admin_sessions").canConvertToInt()
            && inventory.path("admin_sessions").asInt() == expected,
        "The authoritative session state does not establish the required token condition");
  }

  private void requireNoRecordedRefresh(String token) {
    var messages = RecordedMessages.snapshot();
    verifyNoRecordedRefresh(messages, tokenEndpoint.getRawPath(), token);
  }

  static void verifyNoRecordedRefresh(List<RbelElement> messages, String tokenPath, String token) {
    boolean attempted =
        messages.stream()
            .anyMatch(
                message ->
                    message.findRbelPathMembers("$.body.refresh_token").stream()
                            .anyMatch(value -> token.equals(value.getRawStringContent()))
                        && message.findRbelPathMembers("$.method").stream()
                            .anyMatch(value -> "POST".equals(value.getRawStringContent()))
                        && message.findRbelPathMembers("$.path").stream()
                            .anyMatch(value -> tokenPath.equals(value.getRawStringContent())));
    require(!attempted, "The expiration token was submitted to a refresh operation before expiry");
  }

  private void resetStateEvidence() {
    preparedTokenResponse = null;
    lastRevocationToken = null;
    lastAssertionVariant = null;
    refreshAttempts = 0;
    ownRevocationAttempted = false;
    activeReference = null;
    expiryReference = null;
    revocationVerified = false;
    expiryVerified = false;
    expiryClock = null;
    observedServerTime = null;
  }

  /**
   * Uses the same still-valid access token before and after its attempted revocation.
   *
   * @throws Exception if proof generation or token parsing fails
   */
  @Wenn("der SDK-Kontext das unveränderte Access Token am geschützten Fachdienst verwendet")
  public void useAccessToken() throws Exception {
    var now = Instant.now();
    var expires = SignedJWT.parse(accessToken).getJWTClaimsSet().getExpirationTime();
    require(
        expires != null && expires.toInstant().isAfter(now.plusSeconds(15)),
        "Access-token fixture is too close to expiry");
    resourceResponse =
        driver.response(
            "resource", Map.of("path", config("paths.guard.helloZetaPath")),
            "GET", config("paths.guard.helloZetaPath"));
    var requests = RecordedMessages.snapshot().stream()
        .filter(m -> "GET".equals(optional(m, "$.method"))
            && config("paths.guard.helloZetaPath").equals(optional(m, "$.path")))
        .toList();
    require(!requests.isEmpty()
            && ("DPoP " + accessToken).equals(
                optional(requests.getLast(), "$.header.[~'Authorization']")),
        "SDK resource operation changed the observed access token");
  }

  /** Checks the exact independent resource response without overwriting the revocation response. */
  @Dann("das unveränderte Access Token ist am geschützten Fachdienst weiterhin nutzbar")
  public void verifyResourceAccess() {
    require(resourceResponse != null, "No independent resource request was sent");
    require(
        resourceResponse.statusCode() == 200,
        "Protected resource rejected the still-valid access token with HTTP "
            + resourceResponse.statusCode());
  }

  /**
   * Accepts both explicit CC alternatives, without treating arbitrary HTTP 400 errors as success.
   */
  @Dann("der Access-Token-Widerruf liefert HTTP 200 oder HTTP 400 mit unsupported_token_type")
  public void accessTokenAlternative() {
    verifyAccessTokenAlternative(response);
  }

  static void verifyAccessTokenAlternative(Response result) {
    require(result != null, "No independent request has been sent");
    if (result.statusCode() == 400) {
      verifyError(result, "unsupported_token_type");
    } else {
      require(
          result.statusCode() == 200,
          "Expected HTTP 200 or 400 but received " + result.statusCode());
    }
  }

  /**
   * Checks the direct response to the last independent request, never an unrelated recorded call.
   *
   * @param expected expected HTTP status
   */
  @Dann("die direkte Antwort an den SDK-Kontext hat HTTP-Status {int}")
  public void status(int expected) {
    verifyStatus(response, expected);
  }

  /** Retains an exact status failure while allowing the independent token-state cases to run. */
  @Dann("die direkte Antwort an den SDK-Kontext hat HTTP-Status {int} mit soft assert")
  public void collectStatus(int expected) {
    collectStatus(response, expected);
  }

  static void collectStatus(Response response, int expected) {
    require(response != null, "No independent request has been sent");
    try {
      verifyStatus(response, expected);
    } catch (AssertionError failure) {
      SoftAssertionsContext.recordSoftFailure(
          "Independent revocation response HTTP " + expected, failure);
    }
  }

  private static void verifyStatus(Response response, int expected) {
    require(response != null, "No independent request has been sent");
    require(
        response.statusCode() == expected,
        "Expected HTTP " + expected + " but received " + response.statusCode());
  }

  /**
   * Checks the precise error and absence of issued tokens in the direct response.
   *
   * @param expected expected error code
   */
  @Dann("die direkte Antwort an den SDK-Kontext enthält Fehler {string} und keine neuen Token")
  public void error(String expected) {
    verifyError(response, expected);
  }

  /** Checks the full token-error contract even if the product returns the wrong status or code. */
  @Dann(
      "die direkte Tokenantwort erfüllt HTTP {int}, Fehler {string}, Fehlerschema und"
          + " Tokenfreiheit")
  public void tokenError(int expectedStatus, String expectedError) {
    verifyTokenError(response, expectedStatus, expectedError);
  }

  static void verifyTokenError(Response result, int status, String error) {
    require(result != null, "No independent token request has been sent");
    var body = JSON.readTree(result.asString());
    require(body != null && body.isObject(), "Expected a token error JSON object");
    var assertions = new SoftAssertions();
    assertions.assertThat(result.statusCode()).as("Token response HTTP status").isEqualTo(status);
    assertions
        .assertThat(body.path("error").asString())
        .as("Token response error")
        .isEqualTo(error);
    for (var token : new String[] {"access_token", "refresh_token", "zeta_attestation_token"}) {
      assertions.assertThat(body.has(token)).as("Token response must omit %s", token).isFalse();
    }
    assertions
        .assertThatCode(
            () ->
                new SchemaValidationSteps()
                    .validateJsonAgainstYamlSchema(
                        result.asString(), "schemas/v_2_0/schemas/zeta-error.yaml"))
        .as("Token response error schema")
        .doesNotThrowAnyException();
    assertions.assertAll();
  }

  private static void verifyError(Response result, String expected) {
    require(result != null, "No independent request has been sent");
    var body = JSON.readTree(result.asString());
    require(
        expected.equals(text(body, "error")), "Unexpected error code from the independent request");
    require(
        !body.has("access_token") && !body.has("refresh_token"), "Error response issued tokens");
    new SchemaValidationSteps()
        .validateJsonAgainstYamlSchema(result.asString(), "schemas/v_2_0/schemas/zeta-error.yaml");
  }

  /** Drops local observation fixtures; cleanup performs no SDK network operation. */
  @After
  public void clear() {
    clearLocalState();
  }

  private void clearLocalState() {
    resetStateEvidence();
    driver = null;
    clientId = null;
    refreshToken = null;
    accessToken = null;
    response = null;
    resourceResponse = null;
    registeredJwks = null;
    previousAssertionId = null;
    previousProofId = null;
    preparedKind = null;
    unknownToken = null;
  }

  private static Map<String, String> parseForm(String body) {
    var form = new HashMap<String, String>();
    for (var entry : body.split("&")) {
      var pair = entry.split("=", 2);
      require(pair.length == 2, "Captured token request is not form encoded");
      var name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
      require(!form.containsKey(name), "Duplicate captured token request parameter");
      form.put(name, URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    require(form.containsKey("client_assertion"), "Captured request lacks a client assertion");
    return form;
  }

  private static URI trustedEndpoint(String value) {
    var uri = URI.create(value);
    require(
        "https".equals(uri.getScheme())
            && config("zeta_base_url").equals(uri.getRawAuthority())
            && uri.getUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null,
        "Discovered endpoint must be an HTTPS URL on the configured test Guard");
    return uri;
  }

  private static String text(JsonNode body, String field) {
    var value = body == null ? null : body.get(field);
    require(
        value != null && value.isString() && !value.asString().isBlank(),
        "Fixture is missing nonempty field " + field);
    return value.asString();
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key).orElseThrow();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
