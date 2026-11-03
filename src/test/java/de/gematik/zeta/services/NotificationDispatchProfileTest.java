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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.services.model.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Failure-oriented checks for test-deployment isolation, cleanup and original server evidence.
 */
class NotificationDispatchProfileTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void retentionProfileRestoresExistingValuesAndCannotDisablePayloadProtection() {
    var cluster = new Cluster();
    var environment = (ArrayNode) cluster.environment();
    environment
        .addObject()
        .put("name", "NOTIFICATION_PERSISTENCE_SEALING_ENABLED")
        .put("value", "false");
    environment.addObject().put("name", "NOTIFICATION_HISTORY_ENABLED").put("value", "false");
    environment.addObject().put("name", "NOTIFICATION_PERSISTENCE_RETENTION").put("value", "PT24H");
    environment.addObject().put("name", "PUSH_GATEWAY_RETRY_GIVE_UP_AFTER").put("value", "PT1H");
    var original = cluster.environment().deepCopy();
    var desired = NotificationDispatchProfile.retentionEnvironment(original, "owner");
    assertEquals(
        1,
        desired
            .valueStream()
            .filter(e -> "NOTIFICATION_PERSISTENCE_RETENTION".equals(e.path("name").asString()))
            .count());
    assertTrue(
        desired
            .valueStream()
            .anyMatch(
                e ->
                    "NOTIFICATION_PERSISTENCE_RETENTION".equals(e.path("name").asString())
                        && "PT2M".equals(e.path("value").asString())));
    assertEquals(
        List.of("PT1M"),
        desired.valueStream()
            .filter(e -> "PUSH_GATEWAY_RETRY_GIVE_UP_AFTER".equals(e.path("name").asString()))
            .map(e -> e.path("value").asString())
            .toList());
    assertEquals(original, cluster.environment());
    var protectedEnv =
        JSON.readTree(
            "[{\"name\":\"NOTIFICATION_PERSISTENCE_SEALING_ENABLED\",\"value\":\"true\"},{\"name\":\"NOTIFICATION_HISTORY_ENABLED\",\"value\":\"false\"}]");
    assertThrows(
        AssertionError.class,
        () -> NotificationDispatchProfile.retentionEnvironment(protectedEnv, "owner"));
  }

  @Test
  void retentionRoutingRestoresTheOriginalServiceEvenAfterPreparationFails() {
    for (boolean failAfterRouting : new boolean[] {false, true}) {
      var cluster = new Cluster();
      var source = (ArrayNode) cluster.environment();
      source.addObject().put("name", "NOTIFICATION_PERSISTENCE_SEALING_ENABLED").put("value", "false");
      source.addObject().put("name", "NOTIFICATION_HISTORY_ENABLED").put("value", "false");
      final var original = source.deepCopy();
      var fixture = new NotificationDispatchProfile(cluster, "test", "ns");
      cluster.failRoutedLogs = failAfterRouting;
      if (failAfterRouting) {
        assertThrows(AssertionError.class, fixture::startRetention);
      } else {
        fixture.startRetention();
      }
      assertTrue(cluster.route.at("/spec/selector").has("cc-ns-retention"));
      assertTrue(cluster.clone.at("/spec/containers/0/env").valueStream().anyMatch(
          entry -> "NOTIFICATION_PERSISTENCE_RETENTION".equals(entry.path("name").asString())
              && "PT2M".equals(entry.path("value").asString())));
      fixture.close();
      assertEquals("ns", cluster.route.at("/spec/selector/app").asString());
      assertEquals(original, cluster.environment());
      assertTrue(cluster.clone == null);
      fixture.close();
    }
  }

  @Test
  void closesNormallyAndRestoresAfterFailedRollout() {
    for (boolean failure : List.of(false, true)) {
      var cluster = new Cluster();
      var original = cluster.environment().deepCopy();
      cluster.failRollout = failure;
      var fixture = new NotificationDispatchProfile(cluster, "test", "ns");
      if (failure) {
        assertThrows(AssertionError.class, fixture::start);
      } else {
        fixture.start();
        assertEquals(original, cluster.environment());
        assertFalse(original.equals(cluster.clone.at("/spec/containers/0/env")));
        assertEquals(original.get(0), cluster.clone.at("/spec/containers/0/env/0"));
        assertEquals(cluster.image, cluster.clone.at("/spec/containers/0/image").asString());
        assertEquals(
            "IfNotPresent", cluster.clone.at("/spec/containers/0/imagePullPolicy").asString());
      }
      fixture.close();
      assertEquals(original, cluster.environment());
      assertEquals("ns", cluster.route.at("/spec/selector/app").asString());
      assertTrue(cluster.clone == null);
      assertTrue(cluster.temporary.stream().noneMatch(Files::exists));
    }
  }

  @Test
  void concurrentOperatorChangeCannotBeOverwrittenDuringCleanup() {
    var cluster = new Cluster();
    var fixture = new NotificationDispatchProfile(cluster, "test", "ns");
    fixture.start();
    ((ObjectNode) cluster.environment().get(0)).put("value", "operator-change");
    var changed = cluster.environment().deepCopy();
    assertThrows(AssertionError.class, fixture::close);
    assertEquals(changed, cluster.environment());
  }

  @Test
  void concurrentServiceRouteIsPreservedWhileOwnedPodIsStillRemoved() {
    var cluster = new Cluster();
    var fixture = new NotificationDispatchProfile(cluster, "test", "ns");
    fixture.start();
    ((ObjectNode) cluster.route.path("spec"))
        .set("selector", JSON.createObjectNode().put("app", "operator"));
    assertThrows(AssertionError.class, fixture::close);
    assertEquals("operator", cluster.route.at("/spec/selector/app").asString());
    assertTrue(cluster.clone == null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"restart", "lost-logs", "pod-not-ready", "configuration", "image"})
  void observationRejectsLostIsolation(String mutation) {
    var cluster = new Cluster();
    var fixture = new NotificationDispatchProfile(cluster, "test", "ns");
    fixture.start();
    switch (mutation) {
      case "restart" -> cluster.restarts++;
      case "lost-logs" -> cluster.logs = "rotated\n";
      case "pod-not-ready" -> cluster.ready = false;
      case "configuration" -> ((ObjectNode) cluster.environment().get(0)).put("value", "modified");
      case "image" -> cluster.image = "different-image";
      default -> throw new AssertionError();
    }
    assertThrows(AssertionError.class, fixture::logs);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[{\"name\":\"same\"},{\"name\":\"same\"}]",
        "[{\"name\":\"ZETA_TEST_BACKLOG_PROBE\"}]"
      })
  void unsafeSourceEnvironmentCannotBeClaimed(String source) {
    assertThrows(
        AssertionError.class,
        () -> NotificationDispatchProfile.probeEnvironment(JSON.readTree(source), "ours"));
  }

  @Test
  void accessLogRequiresExactlyTheServerOperationAndStatus() {
    var good = "INFO CC_NS_BACKLOG POST /notifications 503 marker-1\n";
    assertDoesNotThrow(() -> NotificationDispatchProfile.verifyAccessLog(good, "marker-1", 503));
    for (var bad :
        List.of(
            "",
            good + good,
            good.replace("503", "502"),
            good.replace("marker-1", "other"),
            good.replace("POST", "GET"),
            good.replace("/notifications", "/proxy/notifications"))) {
      assertThrows(
          AssertionError.class,
          () -> NotificationDispatchProfile.verifyAccessLog(bad, "marker-1", 503));
    }
  }

  @Test
  void uncertainGatewayStopStillResumesTheSameProcessAndRestoresNs() {
    var cluster = new Cluster();
    final var original = cluster.environment().deepCopy();
    var fixture = new NotificationDispatchProfile(cluster, "test", "ns", cluster::runtime);
    fixture.start();
    cluster.failStop = true;
    assertThrows(AssertionError.class, () -> fixture.pauseGateway("gateway", "test-kind"));
    assertTrue(cluster.gatewayPaused);
    assertTrue(cluster.watchdogInstalled);
    fixture.resumeGateway();
    fixture.close();
    assertFalse(cluster.gatewayPaused);
    assertEquals(original, cluster.environment());
  }

  @Test
  void doesNotClaimOrResumeAnAlreadyStoppedGateway() {
    var cluster = new Cluster();
    try (var fixture = new NotificationDispatchProfile(cluster, "test", "ns", cluster::runtime)) {
      fixture.start();
      cluster.gatewayPaused = true;
      assertThrows(AssertionError.class, () -> fixture.pauseGateway("gateway", "test-kind"));
      fixture.resumeGateway();
      assertTrue(cluster.gatewayPaused);
      assertFalse(cluster.watchdogInstalled);
    }
  }

  @Test
  void replacedGatewayContainerCannotBecomeTheCleanupTarget() {
    var cluster = new Cluster();
    final var original = cluster.environment().deepCopy();
    var fixture = new NotificationDispatchProfile(cluster, "test", "ns", cluster::runtime);
    fixture.start();
    fixture.pauseGateway("gateway", "test-kind");
    cluster.gatewayRestarts++;
    assertThrows(AssertionError.class, fixture::resumeGateway);
    assertThrows(AssertionError.class, fixture::close);
    assertTrue(cluster.gatewayPaused);
    assertEquals(original, cluster.environment());
  }

  @Test
  void watchdogResumeIsIdempotentAndAnotherKindClusterIsNotTouched() {
    var cluster = new Cluster();
    try (var fixture = new NotificationDispatchProfile(cluster, "test", "ns", cluster::runtime)) {
      fixture.start();
      assertThrows(AssertionError.class, () -> fixture.pauseGateway("gateway", "other-kind"));
      assertFalse(cluster.watchdogInstalled);
      fixture.pauseGateway("gateway", "test-kind");
      cluster.gatewayPaused = false;
      assertDoesNotThrow(fixture::resumeGateway);
    }
  }

  private static final class Cluster extends ZetaDeploymentModificationService {
    private final ObjectNode deployment =
        (ObjectNode)
            JSON.readTree(
                """
                {"metadata":{"uid":"deployment-1","resourceVersion":"1"},"spec":{"replicas":1,
                "selector":{"matchLabels":{"app":"ns"}},"template":{"spec":{"containers":[{"name":"ns","env":[
                {"name":"UNCHANGED","value":"original"},{"name":"NOTIFICATION_DISPATCH_CONCURRENCY","valueFrom":{"configMapKeyRef":{"name":"cfg","key":"workers"}}}
                ]}]}}}}
                """);
    private final List<Path> temporary = new ArrayList<>();
    private boolean failRollout;
    private boolean failRoutedLogs;
    private boolean ready = true;
    private int restarts;
    private String image = "registry/ns@sha256:" + "a".repeat(64);
    private ObjectNode clone;
    private final ObjectNode route =
        (ObjectNode)
            JSON.readTree(
                "{\"metadata\":{\"uid\":\"service-1\",\"resourceVersion\":\"1\"},\"spec\":{\"selector\":{\"app\":\"ns\"}}}");
    private String logs = "NS started\n";
    private boolean gatewayPaused;
    private boolean watchdogInstalled;
    private boolean failStop;
    private int gatewayRestarts;
    private final String task = "a".repeat(64);

    private Cluster() {
      super(1, 1);
    }

    private JsonNode environment() {
      return deployment.at("/spec/template/spec/containers/0/env");
    }

    private String runtime(List<String> command) {
      if (command.contains("inspect")) {
        return "{\"io.x-k8s.kind.cluster\":\"test-kind\"}";
      }
      if (command.getLast().equals("list")) {
        return task + " 42 " + (gatewayPaused ? "PAUSED" : "RUNNING");
      }
      if (command.stream().anyMatch(arg -> arg.contains("tasks pause"))) {
        watchdogInstalled =
            command.stream().anyMatch(arg -> arg.contains("sleep 12; ctr -n k8s.io tasks resume"));
        gatewayPaused = true;
        if (failStop) {
          throw new AssertionError("Unknown outcome of pause command");
        }
        return "";
      }
      if (command.contains("resume")) {
        gatewayPaused = false;
        return "";
      }
      throw new AssertionError("Unexpected runtime operation");
    }

    private ObjectNode pod(boolean gateway) {
      var pod = JSON.createObjectNode();
      pod.putObject("metadata")
          .put("name", gateway ? "gateway-pod" : "ns-pod")
          .put("uid", gateway ? "gateway-uid" : "ns-uid");
      var spec = pod.putObject("spec");
      spec.put("nodeName", "test-kind-worker");
      spec.putArray("containers").addObject().set("env", environment().deepCopy());
      pod.set("status", status(gateway));
      return pod;
    }

    private ObjectNode status(boolean gateway) {
      var status = JSON.createObjectNode();
      status.put("podIP", "10.0.0.2");
      status
          .putArray("containerStatuses")
          .addObject()
          .put("name", gateway ? "gateway" : "ns")
          .put("imageID", image)
          .put("ready", ready)
          .put("restartCount", gateway ? gatewayRestarts : restarts)
          .put("containerID", "containerd://" + task);
      return status;
    }

    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) {
      var args = arguments.subList(2, arguments.size());
      String output = "";
      int result = 0;
      switch (args.get(0)) {
        case "get" -> {
          switch (args.get(1)) {
            case "deployment" -> {
              if (args.get(2).equals("gateway")) {
                var gateway = deployment.deepCopy();
                ((ObjectNode) gateway.at("/spec/selector/matchLabels")).put("app", "gateway");
                ((ObjectNode) gateway.at("/spec/template/spec/containers/0"))
                    .put("name", "gateway");
                output = gateway.toString();
              } else {
                output = deployment.toString();
              }
            }
            case "service" -> output = route.toString();
            case "endpoints" -> {
              var endpoints = JSON.createObjectNode();
              endpoints
                  .putArray("subsets")
                  .addObject()
                  .putArray("addresses")
                  .addObject()
                  .putObject("targetRef")
                  .put("uid", "clone-uid");
              output = endpoints.toString();
            }
            case "pods" ->
                output =
                    JSON.createObjectNode()
                        .set("items", JSON.createArrayNode().add(pod(args.contains("app=gateway"))))
                        .toString();
            case "pod" -> {
              if (args.get(2).startsWith("cc-ns-retention-")) {
                if (clone != null) {
                  output = clone.deepCopy().set("status", status(false)).toString();
                }
              } else {
                output = pod(args.get(2).equals("gateway-pod")).toString();
              }
            }
            default -> throw new AssertionError("Unexpected resource read");
          }
        }
        case "create", "patch" -> {
          var file = Path.of(args.getLast());
          temporary.add(file);
          try {
            var data = JSON.readTree(Files.readString(file));
            if (args.get(0).equals("create")) {
              clone = (ObjectNode) data;
              ((ObjectNode) clone.path("metadata")).put("uid", "clone-uid");
            } else {
              // A deployment mutation would re-resolve its mutable image tag. Only the owned route
              // may change.
              assertEquals("service", args.get(1));
              assertEquals("test", data.get(0).path("op").asString());
              assertEquals("/metadata/resourceVersion", data.get(0).path("path").asString());
              assertEquals("/spec/selector", data.get(1).path("path").asString());
              ((ObjectNode) route.path("spec"))
                  .set("selector", data.get(1).path("value").deepCopy());
            }
          } catch (IOException e) {
            throw new AssertionError(e);
          }
        }
        case "wait" -> {
          result = failRollout ? 1 : 0;
          failRollout = false;
        }
        case "delete" -> clone = null;
        case "logs" -> {
          if (failRoutedLogs && route.at("/spec/selector").has("cc-ns-retention")) {
            throw new AssertionError("Routed NS observation failed");
          }
          output = logs;
        }
        default -> throw new AssertionError("Unexpected test command");
      }
      return new CommandResult(arguments, result, output, "");
    }
  }
}
