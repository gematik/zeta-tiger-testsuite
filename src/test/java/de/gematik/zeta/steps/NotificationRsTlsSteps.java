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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.NotificationRsTlsProfile;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Independently authenticates both actual RS operations as the assigned certificate principal. */
public class NotificationRsTlsSteps {
  private static final ObjectMapper JSON = new ObjectMapper();

  private NotificationRsTlsProfile profile;
  private String identity;
  private String marker;
  private JsonNode channels;
  private JsonNode notification;
  private String logs;
  private long started;
  private long ended;

  /** Selects the deployed Helm NS and independently assigned RS certificate principal. */
  @Gegebensei(
      "ein RS-mTLS-Prüfprofil des deployten Notification Service für Nutzer {tigerResolvedString}")
  public void prepare(String recipient) throws Exception {
    require(profile == null, "RS TLS profile already exists");
    require(
        TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false),
        "The TLS test peer requires enabled deployment modification");
    require(recipient.matches("[A-Z][0-9]{9}"), "Invalid SDK recipient");
    identity = recipient;
    marker = "TA29980-01-" + UUID.randomUUID();
    // Register ownership before setup so the hook also cleans up partial setup failures.
    profile =
        new NotificationRsTlsProfile(
            ZetaDeploymentConfiguration.getServiceInstance(),
            ZetaDeploymentConfiguration.getNamespace(),
            config("testdata.notificationTls.rs.deployment"),
            config("tlsTestTool.deployment"));
    profile.start();
  }

  /** Invokes exactly one RS channel query on a fresh TLS connection. */
  @Wenn("der technische RS-Nutzer die aktiven Kanäle über mTLS abfragt")
  public void queryChannels() {
    require(profile != null && channels == null, "Missing profile or repeated channel query");
    channels = profile.queryChannels(identity, marker);
    report("getActiveChannels", channels);
  }

  /** Invokes exactly one RS notification submission on another fresh TLS connection. */
  @Wenn("der technische RS-Nutzer eine Benachrichtigung über mTLS einreicht")
  public void submitNotification() {
    require(profile != null && notification == null, "Missing profile or repeated submission");
    started = System.currentTimeMillis();
    notification = profile.submitNotification(identity, marker);
    ended = System.currentTimeMillis();
    report("submitNotification", notification);
  }

  /**
   * Checks the authenticated principal and the complete response of one RS operation. Existing soft
   * assertions preserve independent diagnostics for both operations.
   */
  @Dann(
      "ist die RS-mTLS-Operation {word} vollständig dem technischen Nutzer und SDK-Empfänger"
          + " zugeordnet")
  public void verify(String operation) throws InterruptedException {
    require(
        List.of("getActiveChannels", "submitNotification").contains(operation),
        "Unknown RS operation");
    boolean get = "getActiveChannels".equals(operation);
    var row = get ? channels : notification;
    require(row != null, "RS operation has not been invoked");
    require(channels != null && notification != null, "Both independent operations are required");
    if (logs == null) {
      logs = profile.awaitAccessLogs(List.of(marker + "-get", marker + "-post"));
    }
    try {
      verifyOperation(
          row,
          logs,
          get,
          identity,
          marker,
          profile.address(),
          profile.fingerprint("client"),
          profile.fingerprint("server"),
          profile.principal(),
          started,
          ended);
    } catch (AssertionError | RuntimeException e) {
      SoftAssertionsContext.recordSoftFailure(operation, e);
    }
    if (logs.contains("quarkus.http.ssl.client-auth")
        && logs.contains("build time fixed to 'none'")) {
      SoftAssertionsContext.recordSoftFailure(
          operation,
          new AssertionError(
              "Product image has quarkus.http.ssl.client-auth fixed to none at build time"));
    }
  }

  /** Releases every owned resource, including after setup, stimulus or assertion failure. */
  @After(value = "@ns_rs_tls_profile", order = 100)
  public void cleanup() {
    if (profile != null) {
      profile.close();
    }
  }

  private void report(String operation, JsonNode row) {
    SerenityReportUtils.addCustomData(
        "Actual RS mTLS: " + operation,
        "Observed HTTP status "
            + row.path("status").asInt()
            + "; client certificate SHA-256: "
            + row.path("client_sha").asString()
            + "; server certificate SHA-256: "
            + row.path("server_sha").asString()
            + "; transport/parser failure: "
            + row.path("failure").asString()
            + ". Expected technical principal: "
            + profile.principal()
            + ". Fresh connection through the verified NS TCP service; no bearer token.\n\n"
            + row.toPrettyString());
  }

  static void verifyOperation(
      JsonNode row,
      String logs,
      boolean get,
      String identity,
      String marker,
      String address,
      String clientHash,
      String serverHash,
      String principal,
      long started,
      long ended) {
    String method = get ? "GET" : "POST";
    String path = get ? "/users/" + identity + "/channels" : "/notifications";
    String uri = path + (get ? "?id_type=kvnr" : "");
    final String request = method + " " + uri + " HTTP/1.1";
    final String correlation = marker + (get ? "-get" : "-post");
    final int expected = get ? 200 : 202;
    require(
        row.path("handshake").isBoolean()
            && row.path("handshake").asBoolean()
            && row.path("failure").isString()
            && row.path("failure").asString().isEmpty(),
        "Actual NS peer did not complete validated TLS");
    require(
        address.equals(row.path("peer").asString()),
        "RS TLS connection reached " + row.path("peer").asString()
            + " instead of the verified NS TCP service " + address);
    require(
        serverHash.equals(row.path("server_sha").asString()),
        "Actual NS server certificate differs from the installed TLS fixture");
    require(
        clientHash.equals(row.path("client_sha").asString()),
        "Configured RS certificate was not used in the TLS connection (HTTP "
            + row.path("status").asInt()
            + ")");
    require(
        row.path("status").isIntegralNumber() && row.path("status").asInt() == expected,
        "Authenticated RS operation returned "
            + row.path("status").asInt()
            + " instead of "
            + expected);
    require(
        request.equals(decode(row.path("request_base64").asString())), "Different RS operation");
    var entries =
        logs.lines()
            .filter(l -> l.contains("CC_NS_RS ") && l.endsWith(" " + correlation))
            .map(l -> l.substring(l.indexOf("CC_NS_RS ")))
            .toList();
    require(
        entries.size() == 1
            && entries
                .getFirst()
                .equals(
                    "CC_NS_RS "
                        + method
                        + " "
                        + uri
                        + " "
                        + expected
                        + " "
                        + principal
                        + " "
                        + correlation),
        "NS did not authenticate the exact request as the independently assigned technical"
            + " principal");
    var reply = JSON.readTree(decode(row.path("body_base64").asString()));
    if (get) {
      require(
          "kvnr".equals(reply.at("/user/id_type").asString())
              && identity.equals(reply.at("/user/value").asString())
              && reply.path("has_registered_devices").isBoolean()
              && reply.path("has_registered_devices").asBoolean()
              && reply.path("registered_device_count").isIntegralNumber()
              && reply.path("registered_device_count").asInt() == 1,
          "Active-channel result differs from the fresh SDK recipient/device");
      var channels = reply.path("channels");
      require(
          channels.isArray()
              && channels.size() == 1
              && "zeta".equals(channels.get(0).path("id").asString())
              && "enabled".equals(channels.get(0).path("status").asString()),
          "Active-channel result differs from the SDK-enabled channel");
    } else {
      var id = reply.path("notification_id").asString();
      require(
          id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
              && UUID.fromString(id).toString().equalsIgnoreCase(id)
              && List.of("accepted", "queued").contains(reply.path("status").asString())
              && "zeta".equals(reply.path("channel").asString()),
          "Notification was not accepted for the enabled SDK recipient");
      long accepted = Instant.parse(reply.path("accepted_at").asString()).toEpochMilli();
      require(
          accepted >= started - 5000 && accepted <= ended + 5000,
          "Notification receipt is not fresh");
    }
  }

  private static String decode(String value) {
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
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
