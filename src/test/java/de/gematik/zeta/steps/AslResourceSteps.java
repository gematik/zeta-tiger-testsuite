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
import de.gematik.rbellogger.facets.vau.asl.RbelAslEncryptionFacet;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Observes the genuine encrypted SDK request through Tiger's non-PU ASL tracing decoder.
 */
public class AslResourceSteps {
  private static final ObjectMapper JSON = new ObjectMapper();

  private String identity;
  private String marker;
  private String payload;
  private io.restassured.response.Response result;
  private List<RbelElement> messages;
  private final List<RbelElement> inners = new ArrayList<>();
  private final List<RbelElement> transports = new ArrayList<>();
  private RbelElement inner;
  private RbelElement resourceRequest;
  private RbelElement resourceResponse;
  private String connectionPath;
  private RbelElement rsTransport;
  private RbelElement rsTransportResponse;

  /** Rejects ASL handshakes or transports in the SDK resource run selected as ASL-off. */
  @Dann("enthält der Ressourcenaufruf keine ASL-Transportnachrichten")
  public void verifyNoAsl() {
    for (var message : RecordedMessages.snapshot()) {
      var path = value(message, "$.path");
      require(path == null || !(path.equals("/ASL") || path.startsWith("/ASL/")),
          "ASL transport observed although the resource advertises not_supported");
    }
  }

  /**
   * Invokes one actual SDK resource request, retaining the 5/45-second HTTP limits.
   */
  @Wenn("der SDK für KVNR {tigerResolvedString} eine korrelierte ASL-Ressource mit Nutzdaten aufruft")
  public void submit(String identity) {
    require(result == null, "ASL resource request already submitted");
    this.identity = identity;
    require(identity.matches("[A-Z][0-9]{9}"), "Fresh synthetic identity required");
    marker = "TA29380-01-" + UUID.randomUUID();
    publish("ASL_CORRELATION", marker);
    // The testdriver forwards request headers and bodies, but not URL query parameters.
    payload =
        JSON.createObjectNode().put("correlation_id", marker).put("data", identity).toString();
    var timeout =
        HttpClientConfig.httpClientConfig()
            .setParam("http.connection.timeout", 5000)
            .setParam("http.socket.timeout", 45000);
    result =
        TigerHttpClient.givenDefaultSpec()
            .config(RestAssured.config().httpClient(timeout))
            .header("X-Zeta-Test-Correlation", marker)
            .contentType("application/json")
            .body(payload)
            .get(config("paths.client.oidc.helloZeta"));
  }

  /**
   * Observes exactly one successful encrypted transport with the original resource and payload.
   */
  @Dann("enthält genau ein erfolgreicher ASL-Transport die ursprüngliche Ressource und Nutzdaten")
  public void verifyEncryptedRequest() throws InterruptedException {
    require(result != null, "SDK resource request was not submitted");
    require(
        result.statusCode() == 200,
        "Actual SDK ASL resource call failed with " + result.statusCode());
    awaitEncryptedRequest();
    require(
        inners.size() == 1,
        "Expected one authorized encrypted SDK resource request; observed " + inners.size());
    inner = inners.getFirst();
    require(
        config("paths.guard.helloZetaPath").equals(value(inner, "$.path"))
            && payload.equals(value(inner, "$.body")),
        "Inner request lost the original resource or caller data");
  }

  /** Waits for the correlated SDK transport, excluding an explicitly identified RS upstream hop. */
  private void awaitEncryptedRequest() throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    do {
      messages = RecordedMessages.snapshot();
      inners.clear();
      transports.clear();
      for (var message : messages) {
        if (isRsObservation(message)) {
          continue;
        }
        if (!"POST".equals(value(message, "$.method"))) {
          continue;
        }
        var body = message.findRbelPathMembers("$.body");
        if (body.size() != 1) {
          continue;
        }
        var asl = body.getFirst().getFacet(RbelAslEncryptionFacet.class);
        if (asl.isEmpty()) {
          continue;
        }
        var inner = asl.get().getNestedElement();
        if (!marker.equals(value(inner, "$.header.[~'X-Zeta-Test-Correlation']"))) {
          continue;
        }
        var authorization = value(inner, "$.header.[~'Authorization']");
        if (!isDpopAuthorization(authorization)) {
          continue;
        }
        var response = new TimingGlue().findResponseForRequest(messages, message);
        if (response == null) {
          continue;
        }
        var responseBodies = response.findRbelPathMembers("$.body");
        if (responseBodies.size() != 1) {
          continue;
        }
        var responseAsl = responseBodies.getFirst().getFacet(RbelAslEncryptionFacet.class);
        if (responseAsl.isEmpty()
            || !"200".equals(value(responseAsl.get().getNestedElement(), "$.responseCode"))) {
          continue;
        }
        require(
            inner.getRawStringContent().contains(authorization),
            "ASL decoder did not expose the actual inner HTTP request");
        require(
            !body.getFirst().getRawStringContent().contains(authorization),
            "Transport carries plaintext authorization");
        inners.add(inner);
        transports.add(message);
      }
      if (!inners.isEmpty()) {
        break;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
  }

  /**
   * Checks every observed hop of the original Fach-Client call, without assuming a hop count.
   */
  @Dann("ist der korrelierte Fach-Client-Aufruf an jedem beobachteten Hop erfolgreich und unverändert")
  public void verifyCaller() {
    require(inner != null, "Encrypted SDK request has not been observed");
    var calls =
        messages.stream()
            .filter(
                r ->
                    "GET".equals(value(r, "$.method"))
                        && config("paths.client.oidc.helloZetaPath").equals(value(r, "$.path"))
                        && marker.equals(value(r, "$.header.[~'X-Zeta-Test-Correlation']")))
            .toList();
    // The same caller operation can be recorded at both the local proxy and the ingress.
    // Validate every observation of our unique correlation marker rather than counting hops.
    require(!calls.isEmpty(), "Missing correlated Fach-Client call");
    for (var call : calls) {
      require(payload.equals(value(call, "$.body")), "Caller data changed between proxy hops");
      var response = new TimingGlue().findResponseForRequest(messages, call);
      require(
          response != null && "200".equals(value(response, "$.responseCode")),
          "Fach-Client call did not complete successfully at every observed hop");
    }
  }

  /**
   * Checks signature, identity, target, method, freshness and token/key binding of the inner proof.
   */
  @Dann("passt der innere ASL-DPoP-Proof vollständig zum ursprünglichen SDK-Ressourcenaufruf")
  public void verifyInnerProof() throws Exception {
    require(inner != null, "Encrypted SDK request has not been observed");
    var access = value(inner, "$.header.[~'Authorization']").substring(5);
    var target = "https://" + config("zeta_base_url") + config("paths.guard.helloZetaPath");
    var time =
        transports
            .getFirst()
            .getFacet(RbelMessageTimingFacet.class)
            .orElseThrow()
            .getTransmissionTime()
            .toInstant();
    verifyProof(
        value(inner, "$.header.[~'DPoP']"),
        access,
        identity,
        target,
        value(inner, "$.method"),
        time);
  }

  /**
   * Requires the inner access token to be the original token of one observed successful SDK grant.
   */
  @Dann("stammt das innere ASL-Access-Token aus genau einem erfolgreichen ursprünglichen SDK-Grant")
  public void verifyIssuedToken() throws InterruptedException {
    require(inner != null, "Encrypted SDK request has not been observed");
    var access = value(inner, "$.header.[~'Authorization']").substring(5);
    int issued = 0;
    // Remote token and ASL traffic are parsed asynchronously; their arrival order is not
    // protocol order. Require the original matching grant after bounded recorder convergence.
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    do {
      messages = RecordedMessages.snapshot();
      issued = 0;
      for (var request : messages) {
        if (!"POST".equals(value(request, "$.method"))
            || !config("paths.guard.tokenEndpointPath").equals(value(request, "$.path"))) {
          continue;
        }
        var response = new TimingGlue().findResponseForRequest(messages, request);
        if (response != null
            && "200".equals(value(response, "$.responseCode"))
            && access.equals(
                JSON.readTree(value(response, "$.body")).path("access_token").asString())) {
          issued++;
        }
      }
      if (issued != 0) {
        break;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    require(issued == 1, "Expected one matching original SDK token grant; observed " + issued);
    SerenityReportUtils.addCustomData(
        "SDK inner ASL resource proof",
        "Actual SDK ASL ciphertext independently decrypted through non-PU tracing. One authorized"
            + " inner GET preserves the Fach-Client's resource and exact request body. Its original"
            + " issued access token belongs to the fresh KVNR; ES256 possession signature, method,"
            + " target, freshness, ath and cnf.jkt match.");
  }

  /** Correlates both handshake exchanges with the connection used by this SDK call. */
  @Dann("gehört der vollständige ASL-Handshake zum Tunnel dieses SDK-Aufrufs")
  public void verifyHandshake() {
    require(inner != null, "Encrypted SDK request has not been observed");
    var transport = transports.getFirst();
    connectionPath = value(transport, "$.path");
    require(connectionPath != null && connectionPath.startsWith("/ASL/"),
        "Missing connection-specific ASL resource endpoint");
    var first = messages.stream().filter(r -> "POST".equals(value(r, "$.method")))
        .filter(r -> !isRsObservation(r))
        .filter(r -> "M1".equals(value(r, "$.body.MessageType")))
        .filter(r -> {
          var reply = new TimingGlue().findResponseForRequest(messages, r);
          return reply != null
              && connectionPath.equals(value(reply, "$.header.[~'ZETA-ASL-CID']"));
        }).toList();
    var third = messages.stream().filter(r -> "POST".equals(value(r, "$.method")))
        .filter(r -> !isRsObservation(r))
        .filter(r -> connectionPath.equals(value(r, "$.path")))
        .filter(r -> "M3".equals(value(r, "$.body.MessageType"))).toList();
    require(first.size() == 1 && third.size() == 1,
        "Expected one M1/M2 and M3/M4 exchange for this ASL connection");
    var m1 = first.getFirst();
    var m2 = new TimingGlue().findResponseForRequest(messages, m1);
    var m3 = third.getFirst();
    var m4 = new TimingGlue().findResponseForRequest(messages, m3);
    require(m4 != null, "Missing correlated handshake completion");
    require("/ASL".equals(value(m1, "$.path"))
            && "200".equals(value(m2, "$.responseCode"))
            && "200".equals(value(m4, "$.responseCode"))
            && "M2".equals(value(m2, "$.body.MessageType"))
            && "M4".equals(value(m4, "$.body.MessageType")),
        "ASL handshake did not complete at the expected PEP endpoint");
    var caller = callerRequests().stream().map(AslResourceSteps::sent).min(Instant::compareTo)
        .orElseThrow(() -> new AssertionError("Missing original Fach-Client call"));
    requireOrdered(List.of(caller, sent(m1), sent(m2), sent(m3), sent(m4), sent(transport)));
  }

  /** Checks ciphertext and the request/response envelope of the actual SDK connection. */
  @Dann("trägt dieser ASL-Tunnel die inneren Nachweise ausschließlich verschlüsselt")
  public void verifyCiphertext() {
    require(connectionPath != null, "Connection-specific handshake has not been checked");
    var request = transports.getFirst();
    var response = new TimingGlue().findResponseForRequest(messages, request);
    require(response != null && "200".equals(value(response, "$.responseCode")),
        "Missing successful outer ASL response");
    var requestBody = element(request, "$.body");
    var responseBody = element(response, "$.body");
    require(requestBody.hasFacet(RbelAslEncryptionFacet.class)
            && responseBody.hasFacet(RbelAslEncryptionFacet.class),
        "Both directions must be independently ASL-decryptable");
    require("POST".equals(value(request, "$.method"))
            && connectionPath.equals(value(request, "$.path")),
        "Resource request left its established ASL connection");
    verifyEnvelope(requestBody.getRawContent(), responseBody.getRawContent());
    publish("ASL_TRANSPORT_PATH", connectionPath);
    for (var plaintext : List.of(payload, value(inner, "$.header.[~'Authorization']"),
        value(inner, "$.header.[~'DPoP']"))) {
      require(!requestBody.getRawStringContent().contains(plaintext),
          "Outer ASL request contains inner plaintext");
    }
  }

  /** Observes actual plaintext forwarding after PEP termination, inside the encrypted exchange. */
  @Dann("leitet der PEP genau einen korrelierten entschlüsselten Ressourcenaufruf weiter")
  public void verifyForwarding() throws InterruptedException {
    require(inner != null, "Encrypted SDK request has not been observed");
    List<RbelElement> calls;
    // The remote upstream observation may arrive after the locally decrypted ASL response.
    // Refresh the recorder within the same bounded window; never repeat the SDK operation.
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    do {
      messages = RecordedMessages.snapshot();
      calls = messages.stream()
          .filter(r -> marker.equals(value(r, "$.header.[~'X-Zeta-Test-Correlation']")))
          .filter(r -> config("paths.fachdienst.helloZetaPath").equals(value(r, "$.path")))
          .toList();
      if (calls.size() > 1 || (calls.size() == 1
          && new TimingGlue().findResponseForRequest(messages, calls.getFirst()) != null)) {
        break;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    require(calls.size() == 1,
        "Expected exactly one correlated upstream resource request; observed " + calls.size());
    resourceRequest = calls.getFirst();
    resourceResponse = new TimingGlue().findResponseForRequest(messages, resourceRequest);
    require("GET".equals(value(resourceRequest, "$.method"))
            && payload.equals(value(resourceRequest, "$.body")),
        "PEP forwarding changed the original method or request body");
    require(resourceResponse != null && "200".equals(value(resourceResponse, "$.responseCode")),
        "Correlated resource operation did not succeed");
    var outer = transports.getFirst();
    var response = new TimingGlue().findResponseForRequest(messages, outer);
    require(response != null, "Missing correlated outer ASL response");
    requireOrdered(List.of(sent(outer), sent(resourceRequest), sent(resourceResponse), sent(response)));
    require(!element(resourceResponse, "$.body").hasFacet(RbelAslEncryptionFacet.class),
        "RS response is still ASL-encrypted; this is not the PEP-termination profile");
  }

  /** Checks the distinct outer SDK credential and its original ASL token grant. */
  @Dann("passt der äußere ASL-DPoP-Proof zum ASL-Endpunkt und seinem eigenen Access Token")
  public void verifyOuterProof() throws Exception {
    require(inner != null, "Encrypted SDK request has not been observed");
    var request = transports.getFirst();
    var authorization = value(request, "$.header.[~'Authorization']");
    require(isDpopAuthorization(authorization), "Missing outer ASL access token");
    var access = authorization.substring(5);
    var proof = value(request, "$.header.[~'DPoP']");
    require(!access.equals(value(inner, "$.header.[~'Authorization']").substring(5))
            && !java.util.Objects.equals(proof, value(inner, "$.header.[~'DPoP']")),
        "Outer ASL credentials must be distinct from inner resource credentials");
    verifyProof(proof, access, identity,
        "https://" + config("zeta_base_url") + connectionPath, "POST", sent(request));
    int grants = 0;
    for (var grant : messages) {
      if (!"POST".equals(value(grant, "$.method"))
          || !config("paths.guard.tokenEndpointPath").equals(value(grant, "$.path"))) {
        continue;
      }
      var reply = new TimingGlue().findResponseForRequest(messages, grant);
      if (reply != null && "200".equals(value(reply, "$.responseCode"))
          && access.equals(JSON.readTree(value(reply, "$.body")).path("asl_access_token").asString())) {
        grants++;
      }
    }
    require(grants == 1, "Outer token did not originate in exactly one successful SDK ASL grant");
  }

  /** Correlates both SDK handshake exchanges with their unchanged RS upstream copies. */
  @Dann("wird der ASL-Verbindungsaufbau unverändert zum Resource Server durchgereicht")
  public void verifyRsHandshake() throws InterruptedException {
    require(connectionPath != null, "ASL handshake has not been checked");
    config("asl.rs.upstreamHost");
    for (var type : List.of("M1", "M3")) {
      var source = messages.stream().filter(r -> !isRsObservation(r))
          .filter(r -> "POST".equals(value(r, "$.method")))
          .filter(r -> type.equals(value(r, "$.body.MessageType")))
          .filter(r -> {
            var reply = new TimingGlue().findResponseForRequest(messages, r);
            return "M1".equals(type)
                ? reply != null && connectionPath.equals(value(reply, "$.header.[~'ZETA-ASL-CID']"))
                : connectionPath.equals(value(r, "$.path"));
          }).toList();
      require(source.size() == 1, "Missing unique SDK handshake " + type);
      var request = source.getFirst();
      List<RbelElement> copies;
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
      do {
        messages = RecordedMessages.snapshot();
        copies = messages.stream().filter(this::isRsObservation)
            .filter(r -> "POST".equals(value(r, "$.method")))
            .filter(r -> java.util.Objects.equals(value(request, "$.path"), value(r, "$.path")))
            .filter(r -> Arrays.equals(element(request, "$.body").getRawContent(),
                element(r, "$.body").getRawContent())).toList();
        if (copies.size() > 1 || (copies.size() == 1
            && new TimingGlue().findResponseForRequest(messages, copies.getFirst()) != null)) {
          break;
        }
        Thread.sleep(100);
      } while (System.nanoTime() < deadline);
      require(copies.size() == 1, "Missing unique RS handshake observation " + type);
      var upstream = copies.getFirst();
      var reply = new TimingGlue().findResponseForRequest(messages, upstream);
      var returned = new TimingGlue().findResponseForRequest(messages, request);
      require(reply != null && returned != null && "200".equals(value(reply, "$.responseCode"))
              && Arrays.equals(element(reply, "$.body").getRawContent(),
                  element(returned, "$.body").getRawContent()),
          "PEP did not relay the successful RS handshake response unchanged");
      requireOrdered(List.of(sent(request), sent(upstream), sent(reply), sent(returned),
          sent(transports.getFirst())));
    }
  }

  /** Observes unchanged ASL ciphertext at the configured RS observation boundary. */
  @Dann("leitet der PEP den ASL-Request unverändert verschlüsselt zum Resource Server weiter")
  public void verifyRsTransport() throws InterruptedException {
    require(connectionPath != null, "ASL handshake has not been checked");
    config("asl.rs.upstreamHost");
    var original = transports.getFirst();
    List<RbelElement> observed;
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    do {
      messages = RecordedMessages.snapshot();
      observed = messages.stream().filter(this::isRsObservation)
          .filter(r -> "POST".equals(value(r, "$.method")))
          .filter(r -> connectionPath.equals(value(r, "$.path")))
          .filter(r -> Arrays.equals(element(original, "$.body").getRawContent(),
              element(r, "$.body").getRawContent())).toList();
      if (observed.size() > 1 || (observed.size() == 1
          && new TimingGlue().findResponseForRequest(messages, observed.getFirst()) != null)) {
        break;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    require(observed.size() == 1, "Missing or ambiguous encrypted RS upstream observation");
    rsTransport = observed.getFirst();
    rsTransportResponse = new TimingGlue().findResponseForRequest(messages, rsTransport);
    require(rsTransportResponse != null && "200".equals(value(rsTransportResponse, "$.responseCode")),
        "Missing successful RS ASL response");
    requireOrdered(List.of(sent(original), sent(rsTransport), sent(rsTransportResponse),
        sent(new TimingGlue().findResponseForRequest(messages, original))));
  }

  /** Requires the observed RS request to decrypt to the original authorized resource operation. */
  @Dann("terminiert der Resource Server ASL und verarbeitet den ursprünglichen inneren Ressourcenaufruf")
  public void verifyRsTermination() throws InterruptedException {
    require(rsTransport != null, "Encrypted RS forwarding has not been observed");
    var decoded = element(rsTransport, "$.body").getFacet(RbelAslEncryptionFacet.class)
        .orElseThrow(() -> new AssertionError("RS ASL tracing observation is unavailable"))
        .getNestedElement();
    require(Arrays.equals(inner.getRawContent(), decoded.getRawContent()),
        "RS decrypted another inner resource request");
    verifyForwarding();
    requireOrdered(List.of(sent(rsTransport), sent(resourceRequest), sent(resourceResponse),
        sent(rsTransportResponse)));
  }

  /** Compares RS-generated response ciphertext and its decoded resource response with the return path. */
  @Dann("verschlüsselt der Resource Server die Ressourcenantwort und reicht der PEP sie unverändert durch")
  public void verifyRsResponse() {
    require(resourceResponse != null && rsTransportResponse != null, "Missing RS resource processing");
    var rsBody = element(rsTransportResponse, "$.body");
    var decoded = rsBody.getFacet(RbelAslEncryptionFacet.class)
        .orElseThrow(() -> new AssertionError("Missing encrypted RS response")).getNestedElement();
    require("200".equals(value(decoded, "$.responseCode")), "Wrong inner RS response status");
    verifyResponseBytes(element(resourceResponse, "$.body").getRawContent(),
        List.of(element(decoded, "$.body").getRawContent()));
    var outerResponse = new TimingGlue().findResponseForRequest(messages, transports.getFirst());
    require(outerResponse != null
            && Arrays.equals(rsBody.getRawContent(), element(outerResponse, "$.body").getRawContent()),
        "PEP changed the ASL response ciphertext produced by the RS");
    verifyEncryptedResponse();
  }

  /** Separates the explicitly configured RS observation hop from the SDK transport. */
  private boolean isRsObservation(RbelElement message) {
    var host = TigerGlobalConfiguration.readStringOptional("asl.rs.upstreamHost")
        .map(TigerGlobalConfiguration::resolvePlaceholders).orElse("");
    return !host.isBlank() && host.equals(value(message, "$.header.[~'Host']"));
  }

  /** Publishes observed protocol values for regular TGR assertions in the feature. */
  private static void publish(String name, String value) {
    require(value != null, "Missing observed ASL value: " + name);
    TigerGlobalConfiguration.putValue(name, value, ConfigurationValuePrecedence.TEST_CONTEXT);
  }

  /** Compares the actual RS response with the independently decrypted PEP ciphertext. */
  @Dann("verschlüsselt der PEP genau die beobachtete Ressourcenantwort")
  public void verifyEncryptedResponse() {
    require(resourceResponse != null, "Upstream resource response has not been observed");
    var outer = new TimingGlue().findResponseForRequest(messages, transports.getFirst());
    require(outer != null, "Missing paired ASL response");
    var body = element(outer, "$.body");
    var decrypted = body.getFacet(RbelAslEncryptionFacet.class).orElseThrow().getNestedElement();
    require("200".equals(value(decrypted, "$.responseCode")),
        "PEP encrypted a different resource status");
    verifyResponseBytes(element(resourceResponse, "$.body").getRawContent(),
        List.of(element(decrypted, "$.body").getRawContent()));
    require(!body.getRawStringContent().contains(value(resourceResponse, "$.body")),
        "Outer ASL response exposes the plaintext resource body");
  }

  /** Checks actual SDK decoding and every correlated return hop without claiming the RS profile. */
  @Dann("erhält der Fach-Client die vom SDK entschlüsselte Ressourcenantwort unverändert")
  public void verifyReturnedResponse() {
    require(resourceResponse != null, "Upstream resource response has not been observed");
    var bodies = new ArrayList<byte[]>();
    var outer = new TimingGlue().findResponseForRequest(messages, transports.getFirst());
    require(outer != null, "Missing paired ASL response");
    for (var request : callerRequests()) {
      var response = new TimingGlue().findResponseForRequest(messages, request);
      require(response != null && "200".equals(value(response, "$.responseCode")),
          "Missing successful Fach-Client return hop");
      requireOrdered(List.of(sent(outer), sent(response)));
      bodies.add(element(response, "$.body").getRawContent());
    }
    require(!bodies.isEmpty(), "Missing correlated Fach-Client return traffic");
    require(result.statusCode() == 200, "Fach-Client did not receive HTTP 200");
    bodies.add(result.asByteArray());
    verifyResponseBytes(element(resourceResponse, "$.body").getRawContent(), bodies);
    SerenityReportUtils.addCustomData("ASL response correlation",
        "One connection-specific M1/M2/M3/M4 handshake precedes the SDK ciphertext. One"
            + " correlated plaintext upstream operation occurs inside its request/response interval."
            + " Actual RS bytes match the independently decrypted ASL response and every SDK"
            + " return hop. This positive run does not prove isolated credential rejection.");
  }

  private List<RbelElement> callerRequests() {
    return messages.stream().filter(r -> "GET".equals(value(r, "$.method")))
        .filter(r -> config("paths.client.oidc.helloZetaPath").equals(value(r, "$.path")))
        .filter(r -> marker.equals(value(r, "$.header.[~'X-Zeta-Test-Correlation']"))).toList();
  }

  static void requireOrdered(List<Instant> times) {
    require(times.size() >= 2 && times.stream().allMatch(java.util.Objects::nonNull),
        "Incomplete ASL protocol timing");
    for (int i = 1; i < times.size(); i++) {
      require(!times.get(i).isBefore(times.get(i - 1)), "ASL protocol order is reversed");
    }
  }

  static void verifyResponseBytes(byte[] original, List<byte[]> received) {
    require(original != null && original.length > 0 && !received.isEmpty(),
        "Missing characteristic resource response");
    for (var body : received) {
      require(Arrays.equals(original, body), "Resource response bytes changed on the ASL return path");
    }
  }

  static void verifyEnvelope(byte[] request, byte[] response) {
    require(request != null && response != null && request.length > 71 && response.length > 71,
        "Truncated ASL envelope");
    // ASL v2 non-PU header: version, environment, direction, counter (8), key ID (32).
    require(request[0] == 2 && response[0] == 2 && request[1] == 0 && response[1] == 0
            && request[2] == 1 && response[2] == 2,
        "Wrong ASL version, environment or direction");
    require(Arrays.equals(Arrays.copyOfRange(request, 3, 43), Arrays.copyOfRange(response, 3, 43)),
        "ASL response belongs to another request counter or connection key");
  }

  private static RbelElement element(RbelElement node, String path) {
    var values = node.findRbelPathMembers(path);
    require(values.size() == 1, "Missing or ambiguous ASL observation: " + path);
    return values.getFirst();
  }

  private static Instant sent(RbelElement message) {
    return message.getFacet(RbelMessageTimingFacet.class)
        .orElseThrow(() -> new AssertionError("Missing ASL message transmission time"))
        .getTransmissionTime().toInstant();
  }

  /** Checks resource GET or outer ASL POST proof against its exact token, identity and target. */
  static void verifyProof(
      String dpop, String access, String identity, String target, String method, Instant sent)
      throws Exception {
    new DpopValidationSteps().verifyDpopSignature(dpop);
    var proof = SignedJWT.parse(dpop);
    final var token = SignedJWT.parse(access).getJWTClaimsSet();
    var claims = proof.getJWTClaimsSet();
    var proofType = proof.getHeader().getType();
    require(proofType != null && "dpop+jwt".equals(proofType.toString()), "Wrong inner proof type");
    require(
        ("GET".equals(method) || "POST".equals(method))
            && method.equals(claims.getStringClaim("htm"))
            && target.equals(claims.getStringClaim("htu")),
        "Inner proof targets another operation");
    var hash =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(access.getBytes(StandardCharsets.US_ASCII)));
    require(hash.equals(claims.getStringClaim("ath")), "Inner proof is bound to another token");
    require(
        token.getJSONObjectClaim("cnf") != null
            && proof
                .getHeader()
                .getJWK()
                .computeThumbprint()
                .toString()
                .equals(token.getJSONObjectClaim("cnf").get("jkt")),
        "Inner token is bound to another proof key");
    require(
        identity.equals(token.getSubject()) && token.getStringClaim("client_id") != null,
        "Inner token belongs to another identity or lacks its SDK client");
    require(
        token.getExpirationTime() != null && token.getExpirationTime().toInstant().isAfter(sent),
        "Expired inner access token");
    require(
        claims.getIssueTime() != null
            && Math.abs(sent.getEpochSecond() - claims.getIssueTime().toInstant().getEpochSecond())
                <= 60
            && claims.getJWTID() != null
            && !claims.getJWTID().isBlank(),
        "Stale or unidentifiable inner proof");
  }

  static boolean isDpopAuthorization(String authorization) {
    // HTTP authentication schemes are case-insensitive, including inside the ASL envelope.
    return authorization != null
        && authorization.length() > 5
        && authorization.regionMatches(true, 0, "DPoP ", 0, 5);
  }

  private static String value(RbelElement node, String path) {
    var elements = node.findRbelPathMembers(path);
    return elements.size() == 1 ? elements.getFirst().getRawStringContent() : null;
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("Missing ASL configuration: " + key));
  }

  private static void require(boolean value, String message) {
    if (!value) {
      throw new AssertionError(message);
    }
  }
}
