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
import de.gematik.zeta.services.NotificationTlsProfile;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Gherkin bindings for gateway TLS configuration and connection records. */
public class NotificationTlsSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String APP = "de.gematik.zeta.ta29982.mtls";

  private NotificationTlsProfile profile;
  private String pushkey;
  private String client;
  private String server;

  /**
   * Creates the gateway profile for a registered pusher.
   *
   * @param pusher registered pusher key
   * @param target configured gateway target
   */
  @Gegebensei(
      "ein Gateway-mTLS-Prüfprofil des deployten Notification Service für Pusher"
          + " {tigerResolvedString} am Ziel {word}")
  public void prepare(String pusher, String target) {
    require(profile == null, "Gateway TLS profile already exists");
    require(
        TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification").orElse(false),
        "Gateway test-peer routing requires enabled deployment modification");
    require(pusher.matches("[A-Za-z0-9-]+"), "Invalid pusher key");
    pushkey = pusher;
    profile =
        new NotificationTlsProfile(
            ZetaDeploymentConfiguration.getServiceInstance(),
            ZetaDeploymentConfiguration.getNamespace(),
            config("testdata.ccNotification.rsDeployment"),
            config("testdata.notificationService.pusher.url"),
            config("tlsTestTool.deployment"),
            target);
  }

  /**
   * Applies the selected client and server certificate profiles.
   *
   * @param clientProfile Notification Service client certificate profile
   * @param serverProfile gateway server certificate profile
   * @throws Exception if configuration or startup fails
   */
  @Gegebensei("das installierte NS-Zertifikatprofil {word} und das Gateway-Zertifikatprofil {word}")
  public void selectProfile(String clientProfile, String serverProfile) throws Exception {
    require(profile != null, "Gateway TLS profile has not been prepared");
    require(
        List.of("good", "missing", "foreign").contains(clientProfile)
            && List.of("good", "foreign", "wrong-host").contains(serverProfile)
            && ("good".equals(clientProfile) || "good".equals(serverProfile)),
        "Unknown certificate profile");
    client = clientProfile;
    server = serverProfile;
    profile.variant(client, server);
  }

  /**
   * Starts the configured gateway TLS endpoint and attaches its route.
   *
   * @throws InterruptedException if route activation is interrupted
   */
  @Wenn("starte den konfigurierten Gateway-TLS-Endpunkt")
  public void startGateway() throws InterruptedException {
    require(profile != null && client != null, "Gateway TLS profile has not been selected");
    profile.startGateway();
  }

  /**
   * Checks a gateway TLS connection against its notification and pusher identifiers.
   *
   * @param notificationId accepted notification identifier
   * @param marker event correlation identifier
   * @throws InterruptedException if observation is interrupted
   */
  @Dann("entspricht die tatsächliche Gateway-Verbindung für Notification {tigerResolvedString} mit Korrelation {tigerResolvedString} dem gewählten mTLS-Profil")
  public void verifyConnection(String notificationId, String marker) throws InterruptedException {
    require(profile != null && client != null, "Gateway TLS profile has not been selected");
    require(
        notificationId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
            && UUID.fromString(notificationId).toString().equals(notificationId)
            && marker.matches("[A-Za-z0-9-]+"),
        "Invalid notification correlation");
    var observations = profile.awaitObservations(notificationId);
    require(!observations.isEmpty(), "Actual NS TLS connection is missing: " + label());
    for (var observation : observations) {
      SerenityReportUtils.addCustomData(
          "TLS 1.2 / mbed TLS gateway evidence: " + label(), observation.toPrettyString());
      verifyObservation(
          observation,
          profile.nsAddress(),
          profile.requestLine(),
          profile.fingerprint("ns-" + client),
          profile.fingerprint("server-" + server),
          client,
          server,
          notificationId,
          marker,
          pushkey);
    }
  }

  /** Closes the gateway profile when one was created. */
  @After(value = "@ns_gateway_tls_profile", order = 100)
  public void cleanup() {
    if (profile != null) {
      profile.close();
    }
  }

  private String label() {
    return "client=" + client + ", gateway=" + server;
  }

  static void verifyObservation(
      JsonNode row,
      String ns,
      String requestLine,
      String clientHash,
      String serverHash,
      String client,
      String server,
      String id,
      String marker,
      String pushkey) {
    require(
        ns.equals(row.path("peer").asString()),
        "Gateway connection did not originate at the actual NS");
    for (var field :
        List.of(
            "client_sha",
            "server_sha",
            "offered_server_sha",
            "request_base64",
            "body_base64",
            "failure")) {
      require(row.path(field).isString(), "Incomplete gateway evidence: " + field);
    }
    require(
        serverHash.equals(row.path("offered_server_sha").asString()),
        "TLS handshake selected a different gateway certificate");
    var positive = "good".equals(client) && "good".equals(server);
    if (!positive) {
      require(
          row.path("handshake").isBoolean()
              && !row.path("handshake").asBoolean()
              && row.path("request_base64").asString().isEmpty()
              && row.path("body_base64").asString().isEmpty()
              && "TLS_AUTHENTICATION_FAILED".equals(row.path("failure").asString()),
          "Invalid/missing peer proof did not fail during TLS authentication: client="
              + client
              + ", server="
              + server);
      require(
          ("foreign".equals(client) ? clientHash : "").equals(row.path("client_sha").asString()),
          "Negative TLS profile used a different client identity");
      String reason;
      if ("good".equals(server)) {
        reason =
            switch (client) {
              case "missing" -> "CLIENT_CERTIFICATE_MISSING";
              case "foreign" -> "CLIENT_CERTIFICATE_REJECTED";
              default -> throw new AssertionError("Unknown negative client profile");
            };
      } else {
        require("good".equals(client), "Certificate violations must be isolated");
        reason =
            switch (server) {
              case "foreign" -> "SERVER_CERTIFICATE_UNTRUSTED";
              case "wrong-host" -> "SERVER_HOSTNAME_MISMATCH";
              default -> throw new AssertionError("Unknown negative gateway profile");
            };
      }
      require(
          reason.equals(row.path("failure_reason").asString())
              && ("good".equals(server) ? "gateway" : "deployed NS")
                  .equals(row.path("failure_source").asString()),
          "TLS rejection does not prove the selected certificate violation: " + reason);
      return;
    }
    require(
        row.path("handshake").asBoolean()
            && row.path("failure").asString().isEmpty()
            && clientHash.equals(row.path("client_sha").asString())
            && serverHash.equals(row.path("server_sha").asString()),
        "Mutual TLS did not authenticate the configured NS and gateway identities");
    require(
        requestLine.equals(decode(row.path("request_base64").asString())),
        "NS contacted a different gateway operation");
    var items = JSON.readTree(decode(row.path("body_base64").asString())).path("notifications");
    require(
        items.isArray() && items.size() == 1 && id.equals(items.get(0).path("id").asString()),
        "TLS request is not the original accepted notification");
    var notification = items.get(0).path("notification");
    require(
        marker.equals(notification.at("/content/correlation_id").asString())
            && "registered_client_activity"
                .equals(notification.at("/content/event_type").asString()),
        "Gateway payload differs from the RS event");
    var devices = notification.path("devices");
    require(
        devices.isArray()
            && devices.size() == 1
            && pushkey.equals(devices.get(0).path("pushkey").asString())
            && APP.equals(devices.get(0).path("app_id").asString()),
        "Gateway recipient differs from the original SDK pusher");
  }

  private static String decode(String input) {
    return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.resolvePlaceholders("${" + key + "}");
  }

  private static void require(boolean value, String message) {
    if (!value) {
      throw new AssertionError(message);
    }
  }
}
