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

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.IdpOnlyInventory;
import de.gematik.zeta.services.KvnrGenerator;
import de.gematik.zeta.services.RecordedMessages;
import de.gematik.zeta.services.RevocationProofs;
import de.gematik.zeta.services.SdkIdpOnlyRuntime;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Actual SDK original-factor controls followed by an independent, deliberately incomplete flow.
 */
public class IdpOnlySteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
  private Map<String, String> evidence;
  private Map<String, String> keys;
  private SdkIdpOnlyRuntime runtime;
  private String jwks;
  private Flow pendingFlow;
  private Set<Integer> replacementBaseline;
  private io.restassured.response.Response replacementResponse;
  private Set<Integer> resendBaseline;
  private io.restassured.response.Response resendResponse;
  private final Set<String> verifiedControls = new HashSet<>();
  private JsonNode before;
  private RevocationProofs proofs;
  private String bindingToken;
  private URI tokenEndpoint;
  private String issuer;
  private Map<String, String> controlParameters;
  private int attemptedStatus;
  private JsonNode attemptedBody;
  private String ownerBindingToken;

  /**
   * Prepares only the isolated command process; all SDK actions remain explicit.
   */
  @Gegebensei("ein steuerbarer SDK-Adapter für getrennte IDP-Faktorkontexte")
  public void prepare() throws Exception {
    runtime = new SdkIdpOnlyRuntime();
    runtime.start(
        KvnrGenerator.generateRandomValidKvnr(), config("oidcTestData.emailDomain"));
  }

  /**
   * Runs exactly one public SDK action on the selected persistent context.
   */
  @Wenn("der IDP-SDK-Kontext {string} die Operation {string} ausführt")
  public void sdkOperation(String role, String action) throws Exception {
    require(Set.of("owner", "attacker", "control").contains(role), "Unknown SDK factor role");
    require(
        Set.of("create", "discover", "register", "authenticate", "otp", "withhold", "observe")
            .contains(action),
        "Unknown SDK factor operation");
    evidence = runtime.operation(role + "_" + action);
  }

  /**
   * Independently checks fixture separation and the committed initial factor inventory.
   */
  @Dann("die drei IDP-SDK-Kontexte besitzen getrennte Schlüssel und den erwarteten Faktorbestand")
  public void verifyInventory() throws Exception {
    verifyFixture(evidence);
    SerenityReportUtils.addCustomData("IDP-only SDK fixture", JSON.writeValueAsString(evidence));
    var metadata =
        getJson(
            "https://" + config("zeta_base_url") + config("paths.guard.wellKnownOAuthServerPath"));
    issuer = text(metadata, "issuer");
    tokenEndpoint =
        SdkLogoutSteps.validateEndpoint(text(metadata, "token_endpoint"), config("zeta_base_url"));
    jwks =
        getJson("https://" + config("zeta_base_url") + config("paths.guard.certsEndpointPath"))
            .toString();
    before = new IdpOnlyInventory().read(evidence);
    IdpOnlyInventory.verifyInitial(
        before, evidence, evidence.get("identity") + "@" + config("oidcTestData.emailDomain"));
  }

  /**
   * Checks one actual original-factor grant, its SDK tokens and possession signatures.
   */
  @Dann("der IDP-SDK-Kontext {string} hat einen vollständig gebundenen Faktor-Grant erhalten")
  public void verifyControl(String role) throws Exception {
    require(Set.of("owner", "control").contains(role), "Unknown original-factor control");
    require(before != null, "Missing committed fixture inventory");
    var flow = flow(evidence.get(role + "_client"), EXCHANGE);
    var access = text(flow.body(), "access_token");
    var refresh = text(flow.body(), "refresh_token");
    require(
        hash(access).equals(evidence.get(role + "_access_sha"))
            && hash(refresh).equals(evidence.get(role + "_refresh_sha")),
        "Control token pair does not belong to the actual SDK store");
    verifyAccess(access, role, jwks);
    verifyClientAssertion(flow, role);
    var binding = flow(evidence.get(role + "_client"), "authorization_code");
    verifyBinding(text(binding.body(), "access_token"), role, jwks);
    require(
        text(binding.body(), "access_token").equals(flow.parameters().get("subject_token")),
        "Control exchange did not use its original binding token");
    verifyFactorSubmission(text(binding.body(), "access_token"));
    if (role.equals("owner")) {
      ownerBindingToken = text(binding.body(), "access_token");
    }
    if (role.equals("control")) {
      controlParameters = flow.parameters();
    }
    verifiedControls.add(role);
  }

  /**
   * Verifies the actual pending IDP authentication without supplying an original factor.
   */
  @Dann("der IDP-SDK-Kontext {string} besitzt nur ein IDP-Binding ohne bestätigten Faktor")
  public void verifyPending(String role) throws Exception {
    require(role.equals("attacker"), "Only the pending C2 context is eligible");
    require(
        verifiedControls.containsAll(Set.of("owner", "control")),
        "Missing otherwise equivalent original-factor controls");
    var pending = flow(evidence.get("attacker_client"), "authorization_code");
    pendingFlow = pending;
    bindingToken = text(pending.body(), "access_token");
    verifyBinding(bindingToken, "attacker", jwks);
    verifyClientAssertion(pending, "attacker");
    require(
        !pending.body().has("refresh_token"),
        "IDP-only authorization unexpectedly issued a refresh token");
    verifyNoFactorSubmission(bindingToken);
    verifyActualIdpAuthentication();
  }

  /**
   * Prepares the independent negative-test proof client from only C2's synthetic keys.
   */
  @Gegebensei("ein unabhängiger Prüfclient mit den eigenen Schlüsseln des wartenden IDP-Clients")
  public void prepareProofClient() throws Exception {
    require(pendingFlow != null && bindingToken != null, "Missing verified pending SDK flow");
    keys = runtime.verificationKeys();
    runtime.close();
    runtime = null;
    require(
        keys != null
            && hash(keys.get("client_public_key")).equals(evidence.get("attacker_public_sha")),
        "Independent key copy belongs to another SDK context");
    proofs =
        new RevocationProofs(
            RevocationProofs.loadKey(keys.get("client_public_key"), keys.get("client_private_key")),
            RevocationProofs.loadKey(keys.get("dpop_public_key"), keys.get("dpop_private_key")),
            pendingFlow.parameters().get("client_assertion"),
            bindingToken,
            tokenEndpoint);
    keys = null;
    require(
        proofs.clientId().equals(evidence.get("attacker_client")),
        "Independent proof identity mismatch");
    SerenityReportUtils.addCustomData(
        "IDP-only prerequisites",
        "Three distinct clients/keys, same verified identity/F1; signed AS binding token; two"
            + " successful original-factor SDK controls; no C2 OTP submission.");
  }

  /**
   * Bypasses the SDK's deliberate OTP wait, using only C2's fresh valid keys and binding token.
   *
   * @throws Exception if fresh proof generation or the independent request fails
   */
  private void bypass() throws Exception {
    require(proofs != null, "Missing genuine IDP-only fixture");
    var now = Instant.now();
    require(
        SignedJWT.parse(bindingToken)
            .getJWTClaimsSet()
            .getExpirationTime()
            .toInstant()
            .isAfter(now.plusSeconds(30)),
        "The binding token is too close to expiry for a meaningful bypass attempt");
    var reply =
        TigerHttpClient.givenDefaultSpec()
            .get("https://" + config("zeta_base_url") + config("paths.guard.nonceEndpointPath"));
    require(
        reply.statusCode() == 200 && reply.asString().matches("[A-Za-z0-9_-]+"),
        "Missing fresh AS nonce");
    var nonce = reply.asString();
    var request = exchangeParameters(controlParameters, bindingToken, proofs.clientId());
    request.put("client_assertion", proofs.refreshAssertion(nonce, now));
    var response =
        TigerHttpClient.givenDefaultSpec()
            .header("DPoP", proofs.dpop("POST", tokenEndpoint, nonce, now))
            .contentType("application/x-www-form-urlencoded")
            .formParams(request)
            .post(tokenEndpoint);
    attemptedStatus = response.statusCode();
    attemptedBody = JSON.readTree(response.asString());
    awaitRecordedAttempt(request);
    SerenityReportUtils.addCustomData(
        "IDP-only active exchange",
        JSON.writeValueAsString(
            Map.of(
                "status",
                attemptedStatus,
                "error",
                attemptedBody.path("error").asString("absent"),
                "access_token_present",
                attemptedBody.has("access_token"),
                "refresh_token_present",
                attemptedBody.has("refresh_token"))));
  }

  private void awaitRecordedAttempt(Map<String, String> sent) throws Exception {
    final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    do {
      var messages = RecordedMessages.snapshot();
      for (var request : messages) {
        if (!config("paths.guard.tokenEndpointPath").equals(optional(request, "$.path"))
            || !"POST".equals(optional(request, "$.method"))) {
          continue;
        }
        var parameters = form(required(request, "$.body"));
        if (!sent.get("client_assertion").equals(parameters.get("client_assertion"))) {
          continue;
        }
        require(
            sent.equals(parameters),
            "Recorded bypass parameters differ from the independent request");
        var response = new TimingGlue().findResponseForRequest(messages, request);
        if (response != null) {
          require(
              Integer.toString(attemptedStatus).equals(required(response, "$.responseCode"))
                  && attemptedBody.equals(JSON.readTree(required(response, "$.body"))),
              "Recorded bypass response differs from the direct result");
          return;
        }
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "Active bypass request and response were not retained by Tiger within ten seconds");
  }

  /**
   * Sends one active token exchange while deliberately withholding the original factor.
   */
  @Wenn("der IDP-only-Prüfclient den Tokenaustausch ohne ursprünglichen Faktor versucht")
  public void attempt() throws Exception {
    bypass();
  }

  /**
   * Proves the original E1 binding through the recorded successful SDK request.
   */
  @Dann("die ursprüngliche E-Mail-Bindung wurde tatsächlich vom ersten SDK-Kontext angelegt")
  public void verifyOriginalBinding() {
    require(proofs != null && ownerBindingToken != null, "Missing original-factor fixture");
    verifyInitialEmailBinding();
  }

  /**
   * Resends only E1 as a positive authorization control; its OTP body stays unread.
   */
  @Wenn("der IDP-only-Prüfclient die ursprüngliche Challenge als positive Proof-Kontrolle erneut anfordert")
  public void resendControl() throws Exception {
    resendBaseline = mailIds(mailbox());
    resendResponse = protectedPost(config("paths.guard.bindEmailPath") + "/resend", Map.of());
    if (resendResponse.statusCode() == 429) {
      // Preserve the existing single bounded retry after the local cooldown.
      Thread.sleep(60000);
      resendResponse = protectedPost(config("paths.guard.bindEmailPath") + "/resend", Map.of());
    }
  }

  /**
   * Offers only E2 without accepting or reading any resulting factor challenge.
   */
  @Wenn("der IDP-only-Prüfclient eine neue ungebundene E-Mail als Ersatz anbietet")
  public void offerReplacement() throws Exception {
    replacementBaseline = mailIds(mailbox());
    var replacement = evidence.get("identity") + "-new@" + config("oidcTestData.emailDomain");
    replacementResponse =
        protectedPost(config("paths.guard.bindEmailPath"), Map.of("email", replacement));
  }

  /**
   * Fulfills only an actually offered E2 challenge, never the original E1 factor.
   */
  @Wenn("der IDP-only-Prüfclient ausschließlich eine angebotene Ersatz-E-Mail-Challenge bestätigt")
  public void fulfillReplacement() throws Exception {
    require(
        replacementResponse != null && replacementBaseline != null, "Missing replacement offer");
    var response = replacementResponse;
    var baseline = replacementBaseline;
    var original = evidence.get("identity") + "@" + config("oidcTestData.emailDomain");
    var replacement = evidence.get("identity") + "-new@" + config("oidcTestData.emailDomain");
    int delivered = 0;
    String destination = "none";
    if (response.statusCode() == 202) {
      // A server may ignore the proposed replacement and challenge E1 again. Only an E2 code
      // may be read/submitted here; reading or fulfilling an E1 challenge would invalidate C2.
      final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
      List<JsonNode> newMessages;
      do {
        newMessages = newChallengeMessages(mailbox(), baseline, Set.of(original, replacement));
        if (!newMessages.isEmpty()) {
          break;
        }
        Thread.sleep(100);
      } while (System.nanoTime() < deadline);
      require(newMessages.size() == 1, "Expected one correlated replacement-attempt challenge");
      var message = newMessages.getFirst();
      var recipient =
          message.path("recipients").get(0).asString().replace("<", "").replace(">", "");
      if (replacement.equalsIgnoreCase(recipient)) {
        destination = "replacement";
        var id = message.path("id").asInt();
        require(
            !Integer.toString(id).equals(evidence.get("attacker_withheld_message_id")),
            "Refusing to read the withheld original-factor message");
        var mail =
            TigerHttpClient.givenDefaultSpec()
                .get(config("paths.mailcatcher.messages") + "/" + id + ".plain");
        require(mail.statusCode() == 200, "Replacement challenge body unavailable");
        var code = uniqueOtp(mail.asString());
        var verified =
            protectedPost(config("paths.guard.bindEmailVerifyPath"), Map.of("code", code));
        require(
            verified.statusCode() == 200,
            "Offered replacement challenge could not be completed; recovery result would be"
                + " inconclusive");
        delivered = 1;
      } else {
        require(original.equalsIgnoreCase(recipient), "Unexpected challenge recipient");
        destination = "original_unread";
      }
    } else {
      var body = JSON.readTree(response.asString());
      require(
          (response.statusCode() == 403
                  && "insufficient_scope".equals(body.path("error").asString()))
              || (response.statusCode() == 401
                  && "unauthorized".equals(body.path("status").asString())),
          "Replacement was not denied by the bound-factor authorization check");
    }
    SerenityReportUtils.addCustomData(
        "Unbound replacement attempt",
        JSON.writeValueAsString(
            Map.of(
                "status",
                response.statusCode(),
                "challenge_destination",
                destination,
                "replacement_otp_submitted",
                delivered,
                "original_otp_submitted",
                0)));
  }

  private void verifyInitialEmailBinding() {
    var messages = RecordedMessages.snapshot();
    int controls = 0;
    for (var request : messages) {
      if (!config("paths.guard.bindEmailPath").equals(optional(request, "$.path"))
          || !"POST".equals(optional(request, "$.method"))
          || !("DPoP " + ownerBindingToken).equals(optional(request, "$.header.Authorization"))) {
        continue;
      }
      var params = form(required(request, "$.body"));
      require(
          params.equals(
              Map.of("email", evidence.get("identity") + "@" + config("oidcTestData.emailDomain"))),
          "Initial successful binding used another request profile or factor");
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(
          response != null && "202".equals(required(response, "$.responseCode")),
          "Initial email-binding control failed");
      controls++;
    }
    require(controls == 1, "Missing unambiguous successful initial email-binding control");
  }

  /**
   * Checks the accepted proof and exactly one new E1 challenge without reading its body.
   */
  @Dann("die positive IDP-only-Proof-Kontrolle hat genau eine ursprüngliche Challenge zugestellt")
  public void verifyResendControl() throws Exception {
    require(resendBaseline != null && resendResponse != null, "Missing resend control");
    var originalEmail = evidence.get("identity") + "@" + config("oidcTestData.emailDomain");
    var baseline = resendBaseline;
    var response = resendResponse;
    require(
        response.statusCode() >= 200 && response.statusCode() < 300,
        "Pending-client authentication/proof control failed (resend HTTP "
            + response.statusCode()
            + ")");
    final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    List<JsonNode> messages;
    do {
      messages = newChallengeMessages(mailbox(), baseline, Set.of(originalEmail));
      if (!messages.isEmpty()) {
        break;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    require(
        messages.size() == 1,
        "Accepted pending-client proof did not produce exactly one original-factor challenge");
    SerenityReportUtils.addCustomData(
        "Pending-client proof control",
        JSON.writeValueAsString(
            Map.of(
                "status",
                response.statusCode(),
                "original_factor_challenge_resent",
                true,
                "otp_body_read",
                false)));
  }

  private io.restassured.response.Response protectedPost(
      String path, Map<String, String> parameters) throws Exception {
    var endpoint =
        SdkLogoutSteps.validateEndpoint(
            "https://" + config("zeta_base_url") + path, config("zeta_base_url"));
    var nonceReply =
        TigerHttpClient.givenDefaultSpec()
            .get("https://" + config("zeta_base_url") + config("paths.guard.nonceEndpointPath"));
    require(
        nonceReply.statusCode() == 200 && nonceReply.asString().matches("[A-Za-z0-9_-]+"),
        "Missing fresh AS nonce for protected operation");
    return TigerHttpClient.givenDefaultSpec()
        .header("Authorization", "DPoP " + bindingToken)
        .header(
            "DPoP",
            proofs.resourceProof(
                "POST", endpoint, bindingToken, nonceReply.asString(), Instant.now()))
        .contentType("application/x-www-form-urlencoded")
        .formParams(parameters)
        .post(endpoint);
  }

  private static JsonNode mailbox() {
    var messages = getJson(config("paths.mailcatcher.messages"));
    require(messages.isArray(), "Mailbox metadata is not an array");
    return messages;
  }

  private static Set<Integer> mailIds(JsonNode messages) {
    var ids = new HashSet<Integer>();
    for (var message : messages) {
      require(
          message.path("id").isIntegralNumber() && ids.add(message.path("id").asInt()),
          "Invalid or duplicate mailbox ID");
    }
    return Set.copyOf(ids);
  }

  static List<JsonNode> newChallengeMessages(
      JsonNode messages, Set<Integer> baseline, Set<String> recipients) {
    mailIds(messages);
    return messages
        .valueStream()
        .filter(message -> !baseline.contains(message.path("id").asInt()))
        .filter(
            message ->
                message.path("subject").isString()
                    && message
                        .path("subject")
                        .asString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("verification code"))
        .filter(
            message ->
                message.path("recipients").isArray() && message.path("recipients").size() == 1)
        .filter(
            message ->
                recipients.stream()
                    .anyMatch(
                        expected ->
                            expected.equalsIgnoreCase(
                                message
                                    .path("recipients")
                                    .get(0)
                                    .asString()
                                    .replace("<", "")
                                    .replace(">", ""))))
        .toList();
  }

  static String uniqueOtp(String body) {
    var codes = new HashSet<String>();
    var matcher = java.util.regex.Pattern.compile("\\b[0-9]{6}\\b").matcher(body);
    while (matcher.find()) {
      codes.add(matcher.group());
    }
    require(codes.size() == 1, "Ambiguous replacement OTP");
    return codes.iterator().next();
  }

  /**
   * Requires a grant-specific rejection and unchanged committed identity and public factor
   * bindings.
   */
  @Dann("der IDP-only-Abschluss wird abgelehnt und alle ursprünglichen Bindungen bleiben erhalten")
  public void verify() {
    require(attemptedBody != null, "No active IDP-only attempt was executed");
    var after = new IdpOnlyInventory().read(evidence);
    var failures = new ArrayList<String>();
    try {
      verifyRejection(attemptedStatus, attemptedBody);
    } catch (AssertionError failure) {
      failures.add(failure.getMessage());
    }
    try {
      IdpOnlyInventory.verifyUnchanged(before, after);
    } catch (AssertionError failure) {
      failures.add(failure.getMessage());
    }
    SerenityReportUtils.addCustomData(
        "IDP-only committed inventory", JSON.writeValueAsString(after));
    require(failures.isEmpty(), String.join("; ", failures));
  }

  static void verifyRejection(int status, JsonNode body) {
    require(
        status == 400
            && body != null
            && body.isObject()
            && "invalid_grant".equals(body.path("error").asString())
            && !body.has("access_token")
            && !body.has("refresh_token")
            && !body.has("id_token"),
        "The unconfirmed binding grant was not rejected without tokens (expected 400"
            + " invalid_grant)");
  }

  static Map<String, String> exchangeParameters(
      Map<String, String> original, String token, String client) {
    require(
        EXCHANGE.equals(original.get("grant_type"))
            && "urn:ietf:params:oauth:token-type:access_token"
                .equals(original.get("subject_token_type"))
            && "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                .equals(original.get("client_assertion_type")),
        "Unsupported successful control exchange profile");
    var known =
        Set.of(
            "grant_type",
            "client_id",
            "client_assertion_type",
            "client_assertion",
            "subject_token",
            "subject_token_type",
            "requested_token_type",
            "resource",
            "audience",
            "scope");
    require(
        known.containsAll(original.keySet()),
        "Unexpected control exchange parameter requires explicit review");
    var request = new HashMap<>(original);
    request.put("subject_token", token);
    request.put("client_id", client);
    request.remove("client_assertion");
    return request;
  }

  static void verifyFixture(Map<String, String> proof) {
    require(
        proof != null && "sdk-idp-only-v1".equals(proof.get("protocol")),
        "Missing real SDK fixture proof");
    var clients = new HashSet<String>();
    var publicKeys = new HashSet<String>();
    var messages = new HashSet<String>();
    for (var role : List.of("owner", "attacker", "control")) {
      require(
          proof.getOrDefault(role + "_client", "").matches("[A-Za-z0-9-]{36}")
              && clients.add(proof.get(role + "_client")),
          "SDK contexts share a client ID");
      require(
          proof.getOrDefault(role + "_public_sha", "").matches("[a-f0-9]{64}")
              && publicKeys.add(proof.get(role + "_public_sha")),
          "SDK contexts share an instance key");
      var message =
          proof.get(
              role.equals("attacker") ? "attacker_withheld_message_id" : role + "_otp_message_id");
      require(
          message != null && message.matches("[0-9]+") && messages.add(message),
          "OTP challenges are not independent");
      var count = role.equals("attacker") ? "0" : "1";
      for (var field : List.of("at_count", "rt_count", "otp_delivered")) {
        require(
            count.equals(proof.get(role + "_" + field)), "Unexpected token/OTP state for " + role);
      }
      if (!role.equals("attacker")) {
        require(
            "HAS_ACCESS_AND_REFRESH_TOKEN".equals(proof.get(role + "_status")),
            "Missing authenticated original-factor control");
      }
    }
    require(
        proof.get("attacker_client").equals(proof.get("attacker_pending_client"))
            && proof.get("attacker_public_sha").equals(proof.get("attacker_pending_public_sha"))
            && "0".equals(proof.get("attacker_pending_at_count"))
            && "0".equals(proof.get("attacker_pending_rt_count"))
            && "otp".equals(proof.get("attacker_awaiting"))
            && "false".equals(proof.get("attacker_auth_completed"))
            && "true".equals(proof.get("attacker_pending_after_control")),
        "New client received an original factor or completed authentication");
    require(
        Long.parseLong(proof.get("owner_completed_ms"))
                <= Long.parseLong(proof.get("attacker_started_ms"))
            && Long.parseLong(proof.get("attacker_started_ms"))
                <= Long.parseLong(proof.get("attacker_pending_ms"))
            && Long.parseLong(proof.get("attacker_pending_ms"))
                <= Long.parseLong(proof.get("control_started_ms"))
            && Long.parseLong(proof.get("control_started_ms"))
                <= Long.parseLong(proof.get("control_completed_ms")),
        "Invalid fixture operation order");
  }

  private void verifyAccess(String token, String role, String jwks) throws Exception {
    new SignatureVerificationSteps().verifyJwtSignatureFromKid(token, jwks);
    var claims = SignedJWT.parse(token).getJWTClaimsSet();
    require(issuer.equals(claims.getIssuer()), "AS token issuer differs from discovery");
    require(
        evidence.get("identity").equals(claims.getSubject())
            && evidence.get(role + "_client").equals(claims.getStringClaim("client_id")),
        "AS token belongs to another IDP identity or SDK client");
    require(
        claims.getExpirationTime() != null
            && claims.getExpirationTime().toInstant().isAfter(Instant.now().plusSeconds(30)),
        "AS fixture token is expired or too close to expiry");
  }

  private void verifyActualIdpAuthentication() throws Exception {
    var verifier = new SignatureVerificationSteps();
    var entity = getText(config("testdata.ccOidcAcr.entityStatementUrl"));
    var entityClaims = SignedJWT.parse(entity).getJWTClaimsSet();
    require(
        config("testdata.ccOidcAcr.issuer").equals(entityClaims.getIssuer()),
        "Unexpected test-IDP entity issuer");
    var entityKeys = JSON.writeValueAsString(entityClaims.getJSONObjectClaim("jwks"));
    verifier.verifyJwtSignatureFromKid(entity, entityKeys);
    var signedKeys = getText(config("testdata.ccOidcAcr.signedJwksUrl"));
    verifier.verifyJwtSignatureFromKid(signedKeys, entityKeys);
    var keyClaims = SignedJWT.parse(signedKeys).getJWTClaimsSet();
    require(
        config("testdata.ccOidcAcr.issuer").equals(keyClaims.getIssuer()),
        "Unexpected signed IDP-key issuer");
    var messages = RecordedMessages.snapshot();
    int matches = 0;
    for (var request : messages) {
      if (!config("paths.sectoralIdp.tokenEndpointPath").equals(optional(request, "$.path"))
          || !"POST".equals(optional(request, "$.method"))) {
        continue;
      }
      long timestamp =
          request
              .getFacet(RbelMessageTimingFacet.class)
              .orElseThrow()
              .getTransmissionTime()
              .toInstant()
              .toEpochMilli();
      if (timestamp < Long.parseLong(evidence.get("attacker_started_ms"))
          || timestamp > Long.parseLong(evidence.get("attacker_pending_ms"))) {
        continue;
      }
      var grant = URLDecoder.decode(required(request, "$.body.grant_type"), StandardCharsets.UTF_8);
      final var relyingParty =
          URLDecoder.decode(required(request, "$.body.client_id"), StandardCharsets.UTF_8);
      require("authorization_code".equals(grant), "Unexpected IDP authentication flow");
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(
          response != null && "200".equals(required(response, "$.responseCode")),
          "Sectoral IDP did not authenticate C2");
      var signed =
          OidcTokenSteps.decryptSignedToken(
              text(JSON.readTree(required(response, "$.body")), "id_token"),
              OidcDecryptionKeyPreflight.currentKey());
      verifier.verifyJwtSignatureFromKid(signed, JSON.writeValueAsString(keyClaims.toJSONObject()));
      var claims = SignedJWT.parse(signed).getJWTClaimsSet();
      require(
          config("testdata.ccOidcAcr.issuer").equals(claims.getIssuer())
              && evidence.get("identity").equals(claims.getStringClaim("urn:telematik:claims:id"))
              && List.of(relyingParty).equals(claims.getAudience()),
          "IDP authentication belongs to another identity or relying party");
      require(
          claims.getExpirationTime() != null
              && claims.getExpirationTime().getTime()
                  > Long.parseLong(evidence.get("attacker_pending_ms")),
          "IDP authentication was expired at the original-factor challenge");
      matches++;
    }
    require(
        matches == 1,
        "Expected exactly one signed successful IDP authentication during C2's isolated flow");
  }

  private void verifyBinding(String token, String role, String jwks) throws Exception {
    new SignatureVerificationSteps().verifyJwtSignatureFromKid(token, jwks);
    var claims = SignedJWT.parse(token).getJWTClaimsSet();
    var client = evidence.get(role + "_client");
    var original = IdpOnlyInventory.row(before, client);
    // The deployed restricted token names the realm user in sub and the client in azp.
    // Full resource tokens instead name the IDP identity in sub and use client_id.
    require(
        issuer.equals(claims.getIssuer())
            && original.path("user_id").asString().equals(claims.getSubject())
            && client.equals(claims.getStringClaim("azp")),
        "Restricted binding token is not tied to the committed original identity and new client");
    require(
        claims.getExpirationTime() != null
            && claims.getExpirationTime().toInstant().isAfter(Instant.now().plusSeconds(30)),
        "Restricted binding token is expired or too close to expiry");
    require(
        claims.getAudience().isEmpty() && "zeta:email-verify".equals(claims.getStringClaim("scope"))
            || role.equals("owner")
                && claims.getAudience().isEmpty()
                && Set.of(claims.getStringClaim("scope").split(" "))
                    .equals(Set.of("zeta:email-binding", "zeta:email-verify")),
        "Unexpected restricted binding-token profile");
  }

  private void verifyClientAssertion(Flow flow, String role) throws Exception {
    var jwt = SignedJWT.parse(flow.parameters().get("client_assertion"));
    var claims = jwt.getJWTClaimsSet();
    var client = evidence.get(role + "_client");
    require(
        client.equals(claims.getIssuer())
            && client.equals(claims.getSubject())
            && claims.getAudience().contains(tokenEndpoint.toString()),
        "Original assertion identity/audience mismatch");
    var row = IdpOnlyInventory.row(before, client);
    var jwks = row.path("public_keys").path("jwks.string");
    require(jwks.isString(), "Runtime profile requires the registered public JWKS");
    var candidates = JWKSet.parse(jwks.asString()).getKeys();
    require(candidates.size() == 1, "Expected exactly one registered SDK instance key");
    int verified = 0;
    for (var key : candidates) {
      if (key instanceof ECKey ec && jwt.verify(new ECDSAVerifier(ec.toECPublicKey()))) {
        verified++;
      }
    }
    require(verified == 1, "Recorded client assertion is not bound to exactly one registered key");
  }

  private record Flow(Map<String, String> parameters, JsonNode body) {}

  private Flow flow(String client, String grant) throws Exception {
    final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    do {
      var found = recordedFlow(client, grant);
      if (found != null) {
        return found;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "Selected SDK grant did not reach the local Tiger recorder within ten seconds");
  }

  private Flow recordedFlow(String client, String grant) throws Exception {
    var matches = new ArrayList<Flow>();
    var messages = RecordedMessages.snapshot();
    for (var message : messages) {
      if (!config("paths.guard.tokenEndpointPath").equals(optional(message, "$.path"))
          || !"POST".equals(optional(message, "$.method"))) {
        continue;
      }
      var parameters = form(required(message, "$.body"));
      if (!grant.equals(parameters.get("grant_type"))
          || !parameters.containsKey("client_assertion")) {
        continue;
      }
      if (!client.equals(
          SignedJWT.parse(parameters.get("client_assertion")).getJWTClaimsSet().getSubject())) {
        continue;
      }
      var response = new TimingGlue().findResponseForRequest(messages, message);
      if (response == null) {
        return null;
      }
      require(
          "200".equals(required(response, "$.responseCode")),
          "The selected SDK grant did not succeed");
      matches.add(new Flow(parameters, JSON.readTree(required(response, "$.body"))));
    }
    require(
        matches.size() <= 1,
        "Expected exactly one successful SDK grant for the selected client and grant type; found "
            + matches.size());
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private static void verifyFactorSubmission(String binding) {
    require(
        factorSubmissions(binding, true) == 1,
        "Original-factor control has no unique successful OTP verification");
  }

  private static void verifyNoFactorSubmission(String binding) {
    require(factorSubmissions(binding, false) == 0, "The IDP-only client submitted an OTP");
  }

  private static int factorSubmissions(String binding, boolean requireSuccess) {
    var messages = RecordedMessages.snapshot();
    int count = 0;
    for (var request : messages) {
      if (!config("paths.guard.bindEmailVerifyPath").equals(optional(request, "$.path"))
          || !"POST".equals(optional(request, "$.method"))) {
        continue;
      }
      var headers = request.findRbelPathMembers("$.header.Authorization");
      if (headers.size() != 1
          || !("DPoP " + binding).equals(headers.getFirst().getRawStringContent())) {
        continue;
      }
      if (requireSuccess) {
        var response = new TimingGlue().findResponseForRequest(messages, request);
        require(
            response != null && "200".equals(required(response, "$.responseCode")),
            "Original-factor verification failed");
      }
      count++;
    }
    return count;
  }

  static Map<String, String> form(String body) {
    var result = new HashMap<String, String>();
    try {
      for (var part : body.split("&", -1)) {
        var pair = part.split("=", 2);
        require(pair.length == 2, "Malformed SDK form");
        var name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
        require(
            result.putIfAbsent(name, URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) == null,
            "Duplicate SDK form parameter");
      }
    } catch (IllegalArgumentException failure) {
      throw new AssertionError("Malformed SDK form encoding");
    }
    return result;
  }

  private static JsonNode getJson(String url) {
    return JSON.readTree(getText(url));
  }

  private static String getText(String url) {
    var reply = TigerHttpClient.givenDefaultSpec().get(url);
    require(reply.statusCode() == 200, "Fixture metadata endpoint unavailable");
    return reply.asString();
  }

  private static String optional(RbelElement node, String path) {
    var values = node.findRbelPathMembers(path);
    return values.size() == 1 ? values.getFirst().getRawStringContent() : null;
  }

  private static String required(RbelElement node, String path) {
    var value = optional(node, path);
    require(value != null, "Missing or ambiguous protocol node: " + path);
    return value;
  }

  private static String text(JsonNode node, String field) {
    require(
        node.path(field).isString() && !node.path(field).asString().isBlank(),
        "Missing IDP-only protocol field: " + field);
    return node.path(field).asString();
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

  /**
   * Releases only the independent software-test key references, never changes product storage.
   */
  @After
  public void clear() throws Exception {
    try {
      if (runtime != null) {
        runtime.close();
      }
    } finally {
      runtime = null;
      pendingFlow = null;
      replacementBaseline = null;
      replacementResponse = null;
      resendBaseline = null;
      resendResponse = null;
      verifiedControls.clear();
      keys = null;
      proofs = null;
      bindingToken = null;
      ownerBindingToken = null;
      evidence = null;
      before = null;
      controlParameters = null;
      attemptedBody = null;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
