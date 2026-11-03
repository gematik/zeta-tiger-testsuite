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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Configures a gateway TLS endpoint and reads its connection records. */
public final class NotificationTlsProfile implements AutoCloseable {
  private final ZetaDeploymentModificationService service;
  private final String namespace;
  private final String deployment;
  private final String tlsDeployment;
  private final URI gateway;
  private final NotificationGatewayTlsTarget target;
  private final String owner = "cc-mtls-" + UUID.randomUUID();
  private final Map<String, String> fingerprints = new LinkedHashMap<>();
  private DeployedNotificationService ns;
  private TlsTestToolEndpoint tool;
  private NotificationGatewayTlsRoute route;
  private String nsIp;
  private String nsLogBaseline;
  private String gatewayProfile;
  private boolean selected;
  private boolean started;

  /**
   * Creates a gateway profile for the configured Notification Service.
   *
   * @param service deployment service
   * @param namespace target namespace
   * @param deployment Notification Service deployment
   * @param gatewayUrl registered gateway URL
   * @param tlsDeployment TLS service deployment
   * @param targetName configured gateway target
   */
  public NotificationTlsProfile(
      ZetaDeploymentModificationService service,
      String namespace,
      String deployment,
      String gatewayUrl,
      String tlsDeployment,
      String targetName) {
    this.service = service;
    this.namespace = namespace;
    this.deployment = deployment;
    this.tlsDeployment = tlsDeployment;
    target = NotificationGatewayTlsTarget.load(NotificationTlsFiles::configuration).get(targetName);
    require(target != null, "Unknown isolated gateway TLS target");
    gateway = target.url();
    require(
        gateway.equals(URI.create(gatewayUrl)),
        "SDK pusher differs from the isolated gateway target");
  }

  /**
   * Applies certificate configuration to the Notification Service and gateway endpoint.
   *
   * @param client client certificate profile
   * @param server server certificate profile
   * @throws Exception if configuration fails
   */
  public void variant(String client, String server) throws Exception {
    require(
        List.of("good", "foreign", "missing").contains(client)
            && List.of("good", "foreign", "wrong-host").contains(server)
            && ("good".equals(client) || "good".equals(server)),
        "Unknown TLS profile");
    require(!selected, "Each gateway scenario observes exactly one certificate variant");
    target.reserve(namespace);
    selected = true;
    NotificationTlsDeployment.gateway(service, namespace, deployment, client);
    ns = new DeployedNotificationService(service, namespace, deployment);
    ns.verify();
    require(
        List.of(ns.environment("PUSH_GATEWAY_ALLOWED_BASE_URLS").split(","))
            .contains(gateway.toString()),
        "SDK pusher URL differs from the gateway allowlist of the deployed NS");
    var material = NotificationTlsFiles.gatewayDirectory(service, namespace);
    var certificates = new LinkedHashMap<String, byte[]>();
    certificates.put("ca.pem", NotificationTlsFiles.read(material, "ca.pem"));
    String role = "server-" + server;
    certificates.put(role + "-cert.pem", NotificationTlsFiles.read(material, role + "-cert.pem"));
    certificates.put(role + "-key.pem", NotificationTlsFiles.read(material, role + "-key.pem"));
    fingerprints.put(role, NotificationTlsFiles.fingerprint(certificates.get(role + "-cert.pem")));
    if (!"missing".equals(client)) {
      fingerprints.put(
          "ns-" + client,
          NotificationTlsFiles.fingerprint(
              NotificationTlsFiles.read(material, "ns-" + client + "-cert.pem")));
    }
    if ("foreign".equals(client)) {
      NotificationTlsFiles.requireSelectableUntrustedClient(
          NotificationTlsFiles.read(material, "ns-foreign-cert.pem"), certificates.get("ca.pem"));
    }
    nsIp = ns.address();
    nsLogBaseline = ns.logs();
    gatewayProfile = server;
    tool = new TlsTestToolEndpoint(service, namespace, tlsDeployment);
    tool.start(Map.of(), owner);
    tool.configure(
        de.gematik.zeta.steps.tls.TlsTestToolConfigBuilder.buildTls12ApplicationConfig(
            true, "0.0.0.0", 4433, ""),
        certificates,
        role + "-cert.pem",
        role + "-key.pem",
        "ca.pem");
    route = new NotificationGatewayTlsRoute(service, namespace, target.service());
  }

  /**
   * Starts the configured TLS server and attaches its gateway route.
   *
   * @throws InterruptedException if route activation is interrupted
   */
  public void startGateway() throws InterruptedException {
    require(tool != null && route != null && !started, "Gateway TLS endpoint is not ready to start");
    tool.api().startAsTlsServer();
    route.start(tool);
    started = true;
  }

  /**
   * Reads the TLS connection and correlated certificate rejection.
   *
   * @param notificationId accepted notification identifier
   * @return connection records
   * @throws InterruptedException if observation is interrupted
   */
  public List<JsonNode> awaitObservations(String notificationId) throws InterruptedException {
    require(started, "Gateway TLS endpoint has not been started");
    try {
      String log = tool.api().getLogs();
      var evidence = Path.of("target", "notification-tls", "evidence");
      Files.createDirectories(evidence);
      Files.writeString(evidence.resolve(owner + ".log"), log);
      var row = de.gematik.zeta.steps.tls.MbedTlsEvidence.parse(log, true);
      if (!row.path("handshake").asBoolean() && !"good".equals(gatewayProfile)) {
        CertificateRejection rejection;
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
          var logs = ns.logs();
          require(
              logs.startsWith(nsLogBaseline),
              "Deployed NS logs changed before certificate observation");
          rejection =
              certificateValidationRejection(
                  logs.substring(nsLogBaseline.length()), gateway, notificationId);
          if (rejection != null) {
            break;
          }
          Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        if (rejection != null) {
          row.put("failure", "TLS_AUTHENTICATION_FAILED");
          row.put("failure_reason", rejection.reason());
          row.put("failure_source", "deployed NS").put("failure_cause", rejection.cause());
        }
      }
      tool.verify();
      route.verify();
      ns.verify();
      return List.of(row);
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new AssertionError("Cannot read deployed NS gateway TLS evidence", e);
    }
  }

  record CertificateRejection(String reason, String cause) {}

  static CertificateRejection certificateValidationRejection(String logs, URI gateway, String id) {
    require(id != null && !id.isBlank(), "Missing current notification correlation");
    var host = java.util.regex.Pattern.quote(gateway.getHost());
    var dispatch =
        java.util.regex.Pattern.compile(
            "\\bnotification "
                + java.util.regex.Pattern.quote(id)
                + " to "
                + java.util.regex.Pattern.quote(gateway.toString())
                + "(?=\\s|$)");
    var wrongHost =
        java.util.regex.Pattern.compile(
            "No subject alternative (?:DNS )?name matching "
                + host
                + " found"
                + "|No name matching "
                + host
                + " found"
                + "|Hostname "
                + host
                + " not verified"
                + "|Certificate for <"
                + host
                + "> doesn't match");
    for (var line : logs.lines().toList()) {
      var correlated = dispatch.matcher(line);
      if (!correlated.find()) {
        continue;
      }
      String cause = line.substring(correlated.end());
      if (cause.contains("PKIX path building failed")
          || cause.contains("unable to find valid certification path")) {
        return new CertificateRejection("SERVER_CERTIFICATE_UNTRUSTED", cause);
      }
      if (wrongHost.matcher(cause).find()) {
        return new CertificateRejection("SERVER_HOSTNAME_MISMATCH", cause);
      }
    }
    return null;
  }

  /** Returns the Notification Service pod address. */
  public String nsAddress() {
    return nsIp;
  }

  /** Returns the configured certificate fingerprint for a role. */
  public String fingerprint(String role) {
    return fingerprints.getOrDefault(role, "");
  }

  /** Returns the gateway request line including its configured path prefix. */
  public String requestLine() {
    return "POST " + gateway.getPath() + "notify/batch HTTP/1.1";
  }

  private void stopVariant() {
    var failures = new ArrayList<Throwable>();
    if (route != null) {
      try {
        route.close();
        route = null;
      } catch (AssertionError | RuntimeException e) {
        failures.add(e);
      }
    }
    if (tool != null) {
      try {
        tool.close();
        tool = null;
      } catch (AssertionError | RuntimeException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      var error = new AssertionError("Gateway TLS peer cleanup/restoration failed");
      failures.forEach(error::addSuppressed);
      throw error;
    }
    nsIp = null;
  }

  /** Closes the gateway endpoint and checks the Notification Service state. */
  @Override
  public void close() {
    var failures = new ArrayList<Throwable>();
    try {
      stopVariant();
    } catch (AssertionError | RuntimeException e) {
      failures.add(e);
    }
    if (ns != null) {
      try {
        ns.verify();
      } catch (AssertionError | RuntimeException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      var error = new AssertionError("Deployed NS gateway TLS cleanup failed");
      failures.forEach(error::addSuppressed);
      throw error;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
