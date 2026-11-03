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

import de.gematik.zeta.services.model.CommandResult;
import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Scenario-owned TLS configuration of the existing Helm NS; recovery is handled by Hooks. */
final class NotificationTlsDeployment {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CONTAINER = "notification-service";
  private static final String CLIENT_CERT = "PUSH_GATEWAY_MTLS_CLIENT_CERTIFICATE_PATH";
  private static final String CLIENT_KEY = "PUSH_GATEWAY_MTLS_CLIENT_KEY_PATH";
  private static final String CLIENT_PASSWORD = "PUSH_GATEWAY_MTLS_CLIENT_KEY_PASSWORD";

  private NotificationTlsDeployment() {}

  static void gateway(ZetaDeploymentModificationService service, String namespace,
      String deployment, String client) {
    if (!List.of("good", "missing", "foreign").contains(client)) {
      throw new AssertionError("Unknown NS client certificate profile");
    }
    configure(service, namespace, deployment, template -> {
      var container = container(template);
      removeEnvironment(container, List.of(CLIENT_CERT, CLIENT_KEY, CLIENT_PASSWORD));
      if (!"missing".equals(client)) {
        String secret = NotificationTlsFiles.configuration(
            "testdata.notificationTls.gateway.clientSecrets." + client);
        var volume = JSON.createObjectNode().put("name", "cc-ns-gateway-client");
        volume.putObject("secret").put("secretName", secret);
        mount(template, container, volume, "/certs/cc-ns-gateway-client");
        environment(container, CLIENT_CERT, "/certs/cc-ns-gateway-client/tls.crt");
        environment(container, CLIENT_KEY, "/certs/cc-ns-gateway-client/tls.key");
      }
      return template;
    });
  }

  static void rs(ZetaDeploymentModificationService service, String namespace,
      String deployment, int port) {
    configure(service, namespace, deployment, template -> {
      var container = container(template);
      var server = JSON.createObjectNode().put("name", "cc-ns-rs-server");
      server.putObject("secret").put("secretName",
          NotificationTlsFiles.configuration("testdata.notificationTls.rs.serverSecret"));
      mount(template, container, server, "/certs/rs-server");
      var ca = JSON.createObjectNode().put("name", "cc-ns-rs-client-ca");
      var caSecret = ca.putObject("secret").put("secretName",
          NotificationTlsFiles.configuration("testdata.notificationTls.rs.clientCaSecret"));
      caSecret.putArray("items").addObject().put("key", "tls.crt").put("path", "ca.pem");
      mount(template, container, ca, "/certs/rs-client-ca");
      var auth = JSON.createObjectNode().put("name", "cc-ns-rs-auth");
      auth.putObject("configMap").put("name",
          NotificationTlsFiles.configuration("testdata.notificationTls.rs.authConfigMap"));
      mount(template, container, auth, "/certs/rs-auth");
      environment(container, "APP_AUTH_RS_MTLS_ENABLED", "true");
      appendRsConfigLocation(container);
      environment(container, "QUARKUS_HTTP_SSL_PORT", Integer.toString(port));
      environment(container, "QUARKUS_HTTP_ACCESS_LOG_ENABLED", "true");
      environment(container, "QUARKUS_HTTP_ACCESS_LOG_PATTERN",
          "CC_NS_RS %m %U %s %u %{i,X-CC-NS-Test}");
      return template;
    });
  }

  private static void configure(ZetaDeploymentModificationService service, String namespace,
      String deployment, UnaryOperator<ObjectNode> transform) {
    var recovery = DeploymentStateRecovery.currentFor(namespace);
    if (recovery == null) {
      throw new AssertionError("NS TLS configuration requires the deployment modification hook");
    }
    var original = new DeployedNotificationService(service, namespace, deployment);
    original.verify();
    requireSuccess(recovery.changeObject("deployment/" + deployment, "/spec/template", node -> {
      if (!node.equals(original.template())) {
        throw new AssertionError("NS deployment changed before TLS configuration");
      }
      // Runtime image IDs may use local import aliases that cannot be pulled.
      // Keep the configured Helm image reference and verify the running digest after rollout.
      return transform.apply((ObjectNode) node);
    }));
    requireSuccess(service.waitForDeploymentRollout(namespace, deployment,
        service.getPodReadyTimeoutSeconds()));
    var configured = new DeployedNotificationService(service, namespace, deployment);
    if (!original.imageDigest().equals(configured.imageDigest())) {
      throw new AssertionError("NS product image changed during TLS configuration");
    }
  }

  /** Temporarily exposes the RS TLS port through its existing DNS service. */
  static void exposeRs(String namespace, String host, int port) {
    var recovery = DeploymentStateRecovery.currentFor(namespace);
    if (recovery == null) {
      throw new AssertionError("RS TLS service requires deployment recovery");
    }
    String name = host.split("\\.")[0];
    requireSuccess(recovery.changeObject("service/" + name, "/spec", node -> {
      var spec = (ObjectNode) node;
      if (!"ClusterIP".equals(spec.path("type").asString("ClusterIP"))) {
        throw new AssertionError("RS TLS port changes require a ClusterIP Service; refusing mutation");
      }
      var ports = array(spec, "ports");
      for (var existing : ports) {
        if (existing.path("port").asInt() == port) {
          if (existing.path("targetPort").asInt() != port || !"TCP".equals(existing.path("protocol").asString())) {
            throw new AssertionError("RS TLS service port conflicts with existing routing");
          }
          return spec;
        }
      }
      if (ports.size() == 1 && ports.get(0).path("name").asString().isBlank()) {
        var existing = (ObjectNode) ports.get(0);
        existing.put("name", "cc-rs-" + existing.path("port").asInt());
      }
      ports.addObject().put("name", "cc-rs-tls").put("port", port).put("targetPort", port).put("protocol", "TCP");
      return spec;
    }));
  }

  private static ObjectNode container(ObjectNode template) {
    var matches = template.at("/spec/containers").valueStream()
        .filter(c -> CONTAINER.equals(c.path("name").asString())).toList();
    if (matches.size() != 1) {
      throw new AssertionError("Expected exactly one NS container");
    }
    return (ObjectNode) matches.getFirst();
  }

  private static void appendRsConfigLocation(ObjectNode container) {
    String path = "/certs/rs-auth/rs-tls.properties";
    String locations = "";
    for (var entry : array(container, "env")) {
      if ("QUARKUS_CONFIG_LOCATIONS".equals(entry.path("name").asString())) {
        if (!entry.path("value").isString()) {
          throw new AssertionError("Cannot append to an indirectly configured Quarkus location");
        }
        locations = entry.path("value").asString();
      }
    }
    environment(container, "QUARKUS_CONFIG_LOCATIONS",
        locations.isBlank() ? path : locations + "," + path);
  }

  private static void environment(ObjectNode container, String name, String value) {
    removeEnvironment(container, List.of(name));
    array(container, "env").addObject().put("name", name).put("value", value);
  }

  private static void removeEnvironment(ObjectNode container, List<String> names) {
    var env = array(container, "env");
    for (int i = env.size() - 1; i >= 0; i--) {
      if (names.contains(env.get(i).path("name").asString())) {
        env.remove(i);
      }
    }
  }

  private static void mount(ObjectNode template, ObjectNode container,
      ObjectNode volume, String path) {
    var volumes = array((ObjectNode) template.path("spec"), "volumes");
    var mounts = array(container, "volumeMounts");
    if (volumes.valueStream().anyMatch(v -> volume.path("name").equals(v.path("name")))
        || mounts.valueStream().anyMatch(m -> path.equals(m.path("mountPath").asString()))) {
      throw new AssertionError("TLS fixture mount conflicts with the installed NS configuration");
    }
    volumes.add(volume);
    mounts.addObject().put("name", volume.path("name").asString())
        .put("mountPath", path).put("readOnly", true);
  }

  private static ArrayNode array(ObjectNode node, String key) {
    if (!node.has(key)) {
      return node.putArray(key);
    }
    return (ArrayNode) node.path(key);
  }

  private static void requireSuccess(CommandResult result) {
    if (result.exitCode() != 0) {
      throw new AssertionError("NS TLS reconfiguration or rollout failed: " + result.stderr());
    }
  }
}
