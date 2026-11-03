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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.gematik.zeta.services.TelemetryCollectorDiscovery.CaptureTarget;
import de.gematik.zeta.services.model.CommandResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Checks namespace, Service, mount and receiver correlation before capture can mutate resources. */
class TelemetryCollectorDiscoveryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final CaptureTarget TARGET =
      new CaptureTarget("collector-deployment", "mounted-collector-config", "otlp/arrival");

  @ParameterizedTest
  @CsvSource({"zeta-local, receiver, otlp_http/tms_ti_sim",
      "zeta-staging, receiver.zeta-staging.svc.cluster.local, otlp_http/tms_ti_sim",
      "zeta-achelos-2, receiver.zeta-achelos-2.svc.cluster.local, otlp_http/tms_gematik"})
  void resolvesIndependentResourceNamesAcrossEnvironments(String namespace, String host, String exporter) {
    var fixture = new Fixture(namespace);
    fixture.gateway = (ObjectNode) JSON.readTree(fixture.gateway.toString().replace("otlp_http/selected", exporter));
    fixture.exporter().put("endpoint", "http://" + host + ":4338");
    assertEquals(TARGET, fixture.resolve());
  }

  @ParameterizedTest
  @ValueSource(strings = {"receiver", "receiver.test", "receiver.test.svc",
      "receiver.test.svc.cluster.local", "receiver.test.svc.cluster.local.", "RECEIVER.TEST.SVC.CLUSTER.LOCAL"})
  void acceptsDnsAliasesOfTheSameService(String host) {
    var fixture = new Fixture("test");
    fixture.exporter().put("endpoint", "http://" + host + ":4338/");
    assertEquals(TARGET, fixture.resolve());
  }

  @Test
  void followsServicePortTranslationInsteadOfAssumingTheReceiverPort() {
    var fixture = new Fixture("test");
    fixture.exporter().put("endpoint", "http://receiver");
    fixture.servicePort().put("port", 80);
    assertEquals(TARGET, fixture.resolve());
    fixture.servicePort().put("targetPort", 4338);
    assertEquals(TARGET, fixture.resolve());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-pipeline", "extra-exporter", "grpc", "other-namespace", "external-host",
      "other-cluster", "credentials", "https", "path", "query", "fragment", "wrong-service",
      "external-name", "no-selector", "ambiguous-deployment", "missing-deployment", "wrong-port",
      "udp-port", "missing-named-port", "wrong-target-port", "wrong-mount", "sub-path",
      "missing-configmap", "wrong-projection", "missing-receiver", "ambiguous-receiver",
      "unused-receiver", "tls-receiver", "traces-endpoint"})
  void refusesUnrelatedOrAmbiguousCaptureTargets(String fault) {
    var fixture = new Fixture("test");
    switch (fault) {
      case "missing-pipeline" -> ((ObjectNode) fixture.gateway.at("/service/pipelines")).remove("traces/ti_sim");
      case "extra-exporter" -> ((ArrayNode) fixture.gateway.at("/service/pipelines/traces~1ti_sim/exporters")).add("debug");
      case "grpc" -> fixture.gateway = (ObjectNode) JSON.readTree(
          fixture.gateway.toString().replace("otlp_http/selected", "otlp_grpc/selected"));
      case "other-namespace" -> fixture.exporter().put("endpoint", "http://receiver.other.svc.cluster.local:4338");
      case "external-host" -> fixture.exporter().put("endpoint", "http://example.invalid:4338");
      case "other-cluster" -> fixture.exporter().put("endpoint", "http://receiver.test.svc.example.invalid:4338");
      case "credentials" -> fixture.exporter().put("endpoint", "http://user:password@receiver:4338");
      case "https" -> fixture.exporter().put("endpoint", "https://receiver:4338");
      case "path" -> fixture.exporter().put("endpoint", "http://receiver:4338/other");
      case "query" -> fixture.exporter().put("endpoint", "http://receiver:4338?target=other");
      case "fragment" -> fixture.exporter().put("endpoint", "http://receiver:4338#other");
      case "wrong-service" -> ((ObjectNode) fixture.service.path("metadata")).put("name", "other");
      case "external-name" -> ((ObjectNode) fixture.service.path("spec")).put("type", "ExternalName");
      case "no-selector" -> ((ObjectNode) fixture.service.path("spec")).remove("selector");
      case "ambiguous-deployment" -> fixture.deployments.add(fixture.deployment.deepCopy());
      case "missing-deployment" -> fixture.deployments.removeAll();
      case "wrong-port" -> fixture.servicePort().put("port", 4339);
      case "udp-port" -> fixture.servicePort().put("protocol", "UDP");
      case "missing-named-port" -> fixture.servicePort().put("targetPort", "missing");
      case "wrong-target-port" -> fixture.servicePort().put("targetPort", 4348);
      case "wrong-mount" -> ((ObjectNode) fixture.container.at("/volumeMounts/0")).put("name", "other");
      case "sub-path" -> ((ObjectNode) fixture.container.at("/volumeMounts/0")).put("subPath", "relay.yaml");
      case "missing-configmap" -> ((ObjectNode) fixture.deployment.at("/spec/template/spec/volumes/0")).remove("configMap");
      case "wrong-projection" -> ((ObjectNode) fixture.deployment.at("/spec/template/spec/volumes/0/configMap/items/0"))
          .put("path", "other.yaml");
      case "missing-receiver" -> ((ObjectNode) fixture.relay.path("receivers")).remove("otlp/arrival");
      case "ambiguous-receiver" -> ((ObjectNode) fixture.relay.path("receivers"))
          .set("otlp/duplicate", fixture.relay.at("/receivers/otlp~1arrival").deepCopy());
      case "unused-receiver" -> ((ArrayNode) fixture.relay.at("/service/pipelines/traces/receivers")).removeAll();
      case "tls-receiver" -> ((ObjectNode) fixture.relay.at("/receivers/otlp~1arrival/protocols/http")).putObject("tls");
      case "traces-endpoint" -> fixture.exporter().put("traces_endpoint", "http://other:4338/v1/traces");
      default -> throw new AssertionError(fault);
    }
    assertThrows(AssertionError.class, fixture::resolve);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void gatewayDiscoveryRequiresExactlyOneLabelledConfigMap(int count) {
    var fixture = new Fixture("test");
    for (int i = 0; i < count; i++) {
      fixture.gateways.addObject().putObject("metadata").put("name", "gateway-" + i);
    }
    if (count == 1) {
      assertEquals("gateway-0", TelemetryCollectorDiscovery.gatewayConfigMap(fixture.commands, "test"));
    } else {
      assertThrows(AssertionError.class,
          () -> TelemetryCollectorDiscovery.gatewayConfigMap(fixture.commands, "test"));
    }
  }

  @Test
  void failedKubernetesReadStopsBeforeAnyCaptureMutation() {
    var fixture = new Fixture("test");
    doReturn(new CommandResult(List.of(), 1, "", "unavailable"))
        .when(fixture.commands).executeKubectlCommand(eq(false), anyList());
    assertThrows(AssertionError.class, fixture::resolve);
  }

  @Test
  void relayReadPreservesWhitespaceAndQuotesForGatewayChangeDetection() {
    var fixture = new Fixture("test");
    var original = "exporters:\n  selected: {endpoint: 'http://receiver:4338'}\n";
    var response = JSON.createObjectNode().set("data", JSON.createObjectNode().put("relay", original));
    doReturn(new CommandResult(List.of(), 0, response.toString(), ""))
        .when(fixture.commands).executeKubectlCommand(eq(false), anyList());
    assertEquals(original, TelemetryCollectorDiscovery.relayConfig(fixture.commands, "test", "gateway"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"data\":{\"relay\":42}}", "{\"data\":{\"relay\":\"\"}}",
      "{\"data\":{\"relay\":\" \\n\"}}"})
  void relayReadRejectsMissingNontextualAndBlankConfiguration(String response) {
    var fixture = new Fixture("test");
    doReturn(new CommandResult(List.of(), 0, response, ""))
        .when(fixture.commands).executeKubectlCommand(eq(false), anyList());
    assertThrows(AssertionError.class,
        () -> TelemetryCollectorDiscovery.relayConfig(fixture.commands, "test", "gateway"));
  }

  @Test
  void relayReadRejectsFailedKubernetesCommands() {
    var fixture = new Fixture("test");
    doReturn(new CommandResult(List.of(), 1, "", "unavailable"))
        .when(fixture.commands).executeKubectlCommand(eq(false), anyList());
    assertThrows(AssertionError.class,
        () -> TelemetryCollectorDiscovery.relayConfig(fixture.commands, "test", "gateway"));
  }

  /** Supplies distinct resource names through a read-only Kubernetes command adapter. */
  private static final class Fixture {
    private final ZetaDeploymentModificationService commands = mock(ZetaDeploymentModificationService.class);
    private final String namespace;
    private ObjectNode gateway;
    private final ObjectNode service;
    private final ObjectNode deployment;
    private final ArrayNode deployments = JSON.createArrayNode();
    private final ArrayNode gateways = JSON.createArrayNode();
    private final ObjectNode container;
    private final ObjectNode relay;

    /** Creates an independently named HTTP receiver and mounted collector config. */
    private Fixture(String namespace) {
      this.namespace = namespace;
      gateway = (ObjectNode) JSON.readTree("""
          {"exporters":{"otlp_http/selected":{"endpoint":"http://receiver:4338"}},
           "service":{"pipelines":{"traces/ti_sim":{"exporters":["otlp_http/selected"]}}}}
          """);
      service = (ObjectNode) JSON.readTree("""
          {"metadata":{"name":"receiver","namespace":"%s"},
           "spec":{"selector":{"app":"collector"},
           "ports":[{"port":4338,"targetPort":"otlp-in","protocol":"TCP"}]}}
          """.formatted(namespace));
      deployment = (ObjectNode) JSON.readTree("""
          {"metadata":{"name":"collector-deployment"},"spec":{"template":{
           "metadata":{"labels":{"app":"collector"}},"spec":{
           "containers":[{"args":["--config=/conf/relay.yaml"],
           "ports":[{"name":"otlp-in","containerPort":4338}],
           "volumeMounts":[{"name":"config","mountPath":"/conf"}]}],
           "volumes":[{"name":"config","configMap":{"name":"mounted-collector-config",
           "items":[{"key":"relay","path":"relay.yaml"}]}}]}}}}
          """);
      deployments.add(deployment);
      container = (ObjectNode) deployment.at("/spec/template/spec/containers/0");
      relay = (ObjectNode) JSON.readTree("""
          {"receivers":{"otlp/arrival":{"protocols":{"http":{"endpoint":"${env:MY_POD_IP}:4338"}}}},
           "service":{"pipelines":{"traces":{"receivers":["otlp/arrival"]}}}}
          """);
      when(commands.executeKubectlCommand(eq(false), anyList())).thenAnswer(call -> {
        List<String> args = call.getArgument(1);
        assertEquals(List.of("-n", namespace, "get"), args.subList(0, 3));
        assertEquals(List.of("-o", "json"), args.subList(args.size() - 2, args.size()));
        var resource = args.subList(3, args.size() - 2);
        JsonNode response;
        if (resource.equals(List.of("service", "receiver"))) {
          response = service;
        } else if (resource.equals(List.of("deployment"))) {
          response = JSON.createObjectNode().set("items", deployments);
        } else if (resource.equals(List.of("configmap", "mounted-collector-config"))) {
          response = JSON.createObjectNode().set("data", JSON.createObjectNode().put("relay", relay.toString()));
        } else if (resource.equals(List.of("configmap", "-l",
            "app.kubernetes.io/name=telemetry-gateway,app.kubernetes.io/component=standalone-collector"))) {
          response = JSON.createObjectNode().set("items", gateways);
        } else {
          throw new AssertionError("Unexpected Kubernetes operation: " + resource);
        }
        return new CommandResult(args, 0, response.toString(), "");
      });
    }

    /** Resolves the fixture using the production Kubernetes lookup chain. */
    private CaptureTarget resolve() {
      return TelemetryCollectorDiscovery.captureTarget(commands, namespace, gateway, "traces/ti_sim");
    }

    /** Returns the selected exporter independently of its environment-specific name. */
    private ObjectNode exporter() {
      return (ObjectNode) gateway.path("exporters").valueStream().findFirst().orElseThrow();
    }

    /** Returns the advertised Service port for fault injection. */
    private ObjectNode servicePort() {
      return (ObjectNode) service.at("/spec/ports/0");
    }
  }
}
