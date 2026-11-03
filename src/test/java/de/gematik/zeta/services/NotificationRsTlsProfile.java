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

package de.gematik.zeta.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** RS TLS operations through the verified TCP service of the configured Helm NS pod. */
public final class NotificationRsTlsProfile implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final String deployment;
  private final String tlsDeployment;
  private final String owner = "cc-rs-tls-" + UUID.randomUUID();
  private DeployedNotificationService ns;
  private TlsTestToolEndpoint tool;
  private Map<String, byte[]> credentials;
  private String host;
  private int port;
  private String principal;
  private String clientHash;
  private String serverHash;

  /** Uses an installed NS deployment; only the test peer owns temporary resources. */
  public NotificationRsTlsProfile(
      ZetaDeploymentModificationService service,
      String namespace,
      String deployment,
      String tlsDeployment) {
    this.service = service;
    this.namespace = namespace;
    this.deployment = deployment;
    this.tlsDeployment = tlsDeployment;
  }

  /** Reads credentials, configures the existing Helm NS, and observes the ready replacement pod. */
  public void start() throws Exception {
    host = NotificationTlsFiles.configuration("testdata.notificationTls.rs.host");
    require(host.matches("[A-Za-z0-9.-]+"), "Expected the installed NS certificate hostname");
    port = Integer.parseInt(NotificationTlsFiles.configuration("testdata.notificationTls.rs.port"));
    require(port > 0 && port <= 65535, "Invalid installed NS TLS port");
    principal = NotificationTlsFiles.configuration("testdata.notificationTls.rs.principal");
    Path directory = NotificationTlsFiles.rsDirectory(service, namespace);
    credentials = Map.of(
        "client-cert.pem", NotificationTlsFiles.read(directory, "client-cert.pem"),
        "client-key.pem", NotificationTlsFiles.read(directory, "client-key.pem"),
        "ca.pem", NotificationTlsFiles.read(directory, "ca.pem"));
    clientHash = NotificationTlsFiles.fingerprint(credentials.get("client-cert.pem"));
    serverHash = NotificationTlsFiles.fingerprint(
        NotificationTlsFiles.read(directory, "server-cert.pem"));
    NotificationTlsDeployment.rs(service, namespace, deployment, port);
    ns = new DeployedNotificationService(service, namespace, deployment);
    NotificationTlsDeployment.exposeRs(namespace, host, port);
    tool = new TlsTestToolEndpoint(service, namespace, tlsDeployment);
    tool.start(Map.of(host, ns.address()), owner);
  }

  /** Queries the SDK recipient's channels over a fresh authenticated RS connection. */
  public JsonNode queryChannels(String identity, String marker) {
    validateRecipient(identity, marker);
    return exchange("GET", "/users/" + identity + "/channels?id_type=kvnr", marker + "-get", "");
  }

  /** Submits one event over a fresh authenticated RS connection. */
  public JsonNode submitNotification(String identity, String marker) {
    validateRecipient(identity, marker);
    var body = JSON.createObjectNode().put("channel", "zeta");
    body.putObject("user").put("id_type", "kvnr").put("value", identity);
    body.putObject("payload")
        .put("event_type", "registered_client_activity")
        .put("correlation_id", marker);
    return exchange("POST", "/notifications", marker + "-post", body.toString());
  }

  private void validateRecipient(String identity, String marker) {
    require(
        ns != null && identity.matches("[A-Z][0-9]{9}") && marker.matches("[A-Za-z0-9-]+"),
        "Invalid isolated RS operation");
  }

  private JsonNode exchange(String method, String path, String marker, String body) {
    // Component TLS-test exception: the TLS service stimulates the Helm-deployed NS directly.
    try {
      String request =
          method
              + " "
              + path
              + " HTTP/1.1\r\nHost: "
              + host
              + "\r\nX-CC-NS-Test: "
              + marker
              + "\r\nContent-Type: application/json\r\nContent-Length: "
              + body.getBytes(StandardCharsets.UTF_8).length
              + "\r\nConnection: close\r\n\r\n"
              + body;
      String configuration =
          de.gematik.zeta.steps.tls.TlsTestToolConfigBuilder.buildTls12ApplicationConfig(
              false, host, port, request);
      tool.configure(
          configuration, credentials, "client-cert.pem", "client-key.pem", "ca.pem");
      tool.api().startAsTlsClient(true);
      String log = tool.api().getLogs();
      var evidence = Path.of("target", "notification-tls", "evidence");
      Files.createDirectories(evidence);
      Files.writeString(evidence.resolve(marker + ".log"), log);
      var result = de.gematik.zeta.steps.tls.MbedTlsEvidence.parse(log, false);
      de.gematik.zeta.steps.tls.MbedTlsEvidence.verifySentRequest(log, request);
      result.put("request_base64", encode(method + " " + path + " HTTP/1.1"));
      result.put("expected_peer", tool.serviceAddress(host));
      result.put("ns_pod_ip", ns.address());
      result.put("ns_image_digest", ns.imageDigest());
      tool.verify();
      ns.verify();
      return result;
    } catch (Exception e) {
      throw new AssertionError("TLS service RS exchange failed", e);
    }
  }

  /** Waits at most five seconds for the two request-correlated deployed NS access entries. */
  public String awaitAccessLogs(List<String> correlations) throws InterruptedException {
    var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    String result;
    do {
      result = logs();
      final String snapshot = result;
      if (correlations.stream().allMatch(
          correlation -> snapshot.lines().anyMatch(line -> line.endsWith(" " + correlation)))) {
        break;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    return result;
  }

  /** Observes product logs; the suite does not inject access logging or role mappings. */
  public String logs() {
    return ns.logs();
  }

  /** Independently supplied certificate fingerprint, separate from the observed handshake. */
  public String fingerprint(String role) {
    return switch (role) {
      case "client" -> clientHash;
      case "server" -> serverHash;
      default -> throw new IllegalArgumentException("Unknown RS TLS role");
    };
  }

  /** Expected technical user from the installed profile's independent role assignment. */
  public String principal() {
    return principal;
  }

  /** Actual NS TCP service address, with its sole endpoint pinned to the observed NS pod. */
  public String address() {
    return tool.serviceAddress(host);
  }

  /** Removes the test peer even after failure and verifies that the installed NS is unchanged. */
  @Override
  public void close() {
    var failures = new ArrayList<Throwable>();
    if (tool != null) {
      try {
        tool.close();
      } catch (AssertionError | RuntimeException e) {
        failures.add(e);
      }
    }
    if (ns != null) {
      try {
        ns.verify();
      } catch (AssertionError | RuntimeException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      var error = new AssertionError("Deployed NS RS TLS peer cleanup failed");
      failures.forEach(error::addSuppressed);
      throw error;
    }
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
