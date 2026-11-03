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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.gematik.zeta.services.model.CommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class NotificationTlsDeploymentTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String IMAGE = "docker.io/library/zeta-notification-service:local@sha256:"
      + "b".repeat(64);
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"NodePort", "LoadBalancer", "ExternalName"})
  void unsupportedServicesAreRejectedBeforeAnyJournalOrServiceWrite(String type) {
    var cluster = mock(ZetaDeploymentModificationService.class);
    var args = List.of("-n", "test", "get", "service/rs", "-o", "json");
    when(cluster.executeKubectlCommand(false, args)).thenReturn(new CommandResult(args, 0,
        "{\"metadata\":{\"uid\":\"rs-uid\",\"resourceVersion\":\"1\"},\"spec\":{\"type\":\""
            + type + "\",\"ports\":[{\"port\":8080}]}}", ""));
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      var failure = assertThrows(AssertionError.class,
          () -> NotificationTlsDeployment.exposeRs("test", "rs.test", 8443));
      assertTrue(failure.getMessage().contains("ClusterIP"));
    } finally {
      recovery.finish();
    }
    verify(cluster).executeKubectlCommand(false, args);
    verifyNoMoreInteractions(cluster);
  }

  @Test
  void exposesRsTlsPortWithoutReplacingHttpRoutingAndRejectsConflicts() {
    var recovery = mock(DeploymentStateRecovery.class);
    var spec = JSON.readTree("{\"selector\":{\"app\":\"ns\"},\"clusterIP\":\"10.0.0.1\",\"ports\":[{\"name\":\"http\",\"port\":8080,\"targetPort\":8080,\"protocol\":\"TCP\"}]}");
    var changed = new AtomicReference<JsonNode>();
    when(recovery.changeObject(eq("service/notification-service-rs"), eq("/spec"), any()))
        .thenAnswer(call -> {
          UnaryOperator<JsonNode> transform = call.getArgument(2);
          changed.set(transform.apply(spec.deepCopy()));
          return result(false);
        });
    try (var context = mockStatic(DeploymentStateRecovery.class)) {
      assertThrows(AssertionError.class,
          () -> NotificationTlsDeployment.exposeRs("test", "notification-service-rs.test", 8443));
      context.when(() -> DeploymentStateRecovery.currentFor("test")).thenReturn(recovery);
      NotificationTlsDeployment.exposeRs("test", "notification-service-rs.test", 8443);
      assertEquals(spec.path("selector"), changed.get().path("selector"));
      assertEquals(spec.path("clusterIP"), changed.get().path("clusterIP"));
      assertEquals(spec.at("/ports/0"), changed.get().at("/ports/0"));
      assertEquals(8443, changed.get().at("/ports/1/targetPort").asInt());
      ((tools.jackson.databind.node.ObjectNode) spec.path("ports").get(0)).put("port", 8443);
      assertThrows(AssertionError.class,
          () -> NotificationTlsDeployment.exposeRs("test", "notification-service-rs.test", 8443));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 8080, 8443, 65535})
  void exposesTlsAndRestoresUnnamedServicePort(int originalPort) {
    var resource = (ObjectNode) JSON.readTree("""
        {"metadata":{"uid":"rs-uid","resourceVersion":"1"},"spec":{
        "type":"ClusterIP","selector":{"app":"ns"},"clusterIP":"10.0.0.1",
        "ports":[{"port":%d,"targetPort":%d,"protocol":"TCP"}]}}
        """.formatted(originalPort, originalPort));
    var originalSpec = resource.path("spec").deepCopy();
    var cluster = mock(ZetaDeploymentModificationService.class);
    when(cluster.executeKubectlCommand(eq(false), anyList())).thenAnswer(call -> {
      List<String> args = call.getArgument(1);
      assertEquals("service/rs", args.get(3));
      if ("patch".equals(args.get(2))) {
        var patch = JSON.readTree(Files.readString(
            Path.of(args.get(args.indexOf("--patch-file") + 1))));
        assertEquals(resource.at("/metadata/resourceVersion"), patch.get(0).path("value"));
        assertEquals("/spec", patch.get(1).path("path").asString());
        var desired = patch.get(1).path("value");
        if (desired.path("ports").size() > 1) {
          for (var port : desired.path("ports")) {
            if (port.path("name").asString().isBlank()) {
              return new CommandResult(args, 1, "", "spec.ports[0].name: Required value");
            }
          }
        }
        resource.set("spec", desired.deepCopy());
        ((ObjectNode) resource.path("metadata")).put("resourceVersion",
            Integer.toString(Integer.parseInt(resource.at("/metadata/resourceVersion").asString()) + 1));
      } else {
        assertEquals("get", args.get(2));
      }
      return new CommandResult(args, 0, resource.toString(), "");
    });
    try (var journals = mockConstruction(PerformanceRoutingJournal.class)) {
      var recovery = new DeploymentStateRecovery(cluster, "test");
      recovery.attach();
      try {
        NotificationTlsDeployment.exposeRs("test", "rs.test", 8443);
        if (originalPort == 8443) {
          assertEquals(originalSpec, resource.path("spec"));
        } else {
          assertEquals(2, resource.at("/spec/ports").size());
          var namedOriginal = originalSpec.at("/ports/0").deepCopy();
          ((ObjectNode) namedOriginal).put("name", "cc-rs-" + originalPort);
          assertEquals(namedOriginal, resource.at("/spec/ports/0"));
          assertEquals(JSON.readTree("""
              {"name":"cc-rs-tls","port":8443,"targetPort":8443,"protocol":"TCP"}
              """), resource.at("/spec/ports/1"));
        }
      } finally {
        recovery.finish();
      }
      assertEquals(originalSpec, resource.path("spec"));
      assertFalse(resource.at("/spec/ports/0").has("name"));
      verify(journals.constructed().getFirst()).complete("test");
    }
  }

  @Test
  void refusesMutationWithoutScenarioRecovery() {
    try (var context = mockStatic(DeploymentStateRecovery.class)) {
      assertThrows(AssertionError.class,
          () -> NotificationTlsDeployment.gateway(null, "test", "ns", "missing"));
    }
  }

  @Test
  void preservesConfiguredImageDespiteDifferentRuntimeIdentityForEachClientProfile() {
    for (String client : List.of("good", "missing", "foreign")) {
      exercise(client, false, false);
    }
  }

  @Test
  void configuresIncomingTlsWithoutChangingOtherContainersOrHealthPorts() {
    exercise("rs", false, false);
  }

  @Test
  void failedPatchCannotStartRolloutAndFailedRolloutCannotPassSetup() {
    exercise("good", true, false);
    exercise("rs", false, true);
  }

  @Test
  void changedProductImageCannotPassSetup() {
    exercise("good", false, false, true);
  }

  private void exercise(String profile, boolean failPatch, boolean failRollout) {
    exercise(profile, failPatch, failRollout, false);
  }

  private void exercise(String profile, boolean failPatch, boolean failRollout,
      boolean changedImage) {
    var original = JSON.readTree("""
        {"metadata":{"labels":{"keep":"yes"}},"spec":{
        "containers":[{"name":"notification-service","image":"%s",
        "ports":[{"name":"http","containerPort":8080}],
        "env":[{"name":"KEEP","value":"original"},
        {"name":"QUARKUS_CONFIG_LOCATIONS","value":"/existing.properties"},
        {"name":"PUSH_GATEWAY_MTLS_CLIENT_CERTIFICATE_PATH","value":"old.crt"},
        {"name":"PUSH_GATEWAY_MTLS_CLIENT_KEY_PATH","value":"old.key"},
        {"name":"PUSH_GATEWAY_MTLS_CLIENT_KEY_PASSWORD","value":"old.password"}]},
        {"name":"sidecar","image":"sidecar:original"}],
        "volumes":[{"name":"keep","emptyDir":{}}]}}
        """.formatted(IMAGE));
    var desired = new AtomicReference<JsonNode>();
    var service = mock(ZetaDeploymentModificationService.class);
    var recovery = mock(DeploymentStateRecovery.class);
    when(service.getPodReadyTimeoutSeconds()).thenReturn(45);
    when(service.waitForDeploymentRollout("test", "ns", 45))
        .thenReturn(result(failRollout));
    when(recovery.changeObject(eq("deployment/ns"), eq("/spec/template"), any()))
        .thenAnswer(call -> {
          UnaryOperator<JsonNode> transform = call.getArgument(2);
          desired.set(transform.apply(original.deepCopy()));
          return result(failPatch);
        });
    try (var context = mockStatic(DeploymentStateRecovery.class);
        var files = mockStatic(NotificationTlsFiles.class);
        var pods = mockConstruction(DeployedNotificationService.class, (pod, constructionContext) -> {
          when(pod.template()).thenReturn(original);
          when(pod.imageDigest()).thenReturn(changedImage && constructionContext.getCount() == 2
              ? "sha256:" + "c".repeat(64) : DIGEST);
        })) {
      context.when(() -> DeploymentStateRecovery.currentFor("test")).thenReturn(recovery);
      files.when(() -> NotificationTlsFiles.configuration(anyString()))
          .thenAnswer(call -> "fixture-" + call.<String>getArgument(0).substring(
              call.<String>getArgument(0).lastIndexOf('.') + 1));
      Runnable prepare = "rs".equals(profile)
          ? () -> NotificationTlsDeployment.rs(service, "test", "ns", 8443)
          : () -> NotificationTlsDeployment.gateway(service, "test", "ns", profile);
      if (failPatch || failRollout || changedImage) {
        assertThrows(AssertionError.class, prepare::run);
      } else {
        prepare.run();
      }
      if (failPatch) {
        verify(service, never()).waitForDeploymentRollout(anyString(), anyString(), anyInt());
      } else {
        verify(service).waitForDeploymentRollout("test", "ns", 45);
      }
      var changed = desired.get();
      assertEquals(IMAGE, changed.at("/spec/containers/0/image").asString());
      assertEquals(original.at("/spec/containers/1"), changed.at("/spec/containers/1"));
      assertEquals(original.at("/spec/containers/0/ports"), changed.at("/spec/containers/0/ports"));
      assertEquals(original.path("metadata"), changed.path("metadata"));
      assertEquals("original", env(changed, "KEEP"));
      if ("rs".equals(profile)) {
        assertEquals("true", env(changed, "APP_AUTH_RS_MTLS_ENABLED"));
        assertEquals("/existing.properties,/certs/rs-auth/rs-tls.properties",
            env(changed, "QUARKUS_CONFIG_LOCATIONS"));
        assertEquals("8443", env(changed, "QUARKUS_HTTP_SSL_PORT"));
        assertEquals("fixture-serverSecret", changed.at("/spec/volumes/1/secret/secretName").asString());
        assertEquals("fixture-authConfigMap", changed.at("/spec/volumes/3/configMap/name").asString());
      } else if ("missing".equals(profile)) {
        assertEquals("", env(changed, "PUSH_GATEWAY_MTLS_CLIENT_CERTIFICATE_PATH"));
        assertEquals("", env(changed, "PUSH_GATEWAY_MTLS_CLIENT_KEY_PATH"));
        assertFalse(changed.at("/spec/containers/0").has("volumeMounts"));
      } else {
        assertEquals("fixture-" + profile, changed.at("/spec/volumes/1/secret/secretName").asString());
        assertTrue(env(changed, "PUSH_GATEWAY_MTLS_CLIENT_CERTIFICATE_PATH").endsWith("/tls.crt"));
        assertEquals("", env(changed, "PUSH_GATEWAY_MTLS_CLIENT_KEY_PASSWORD"));
      }
      verify(pods.constructed().getFirst()).verify();
    }
  }

  private String env(JsonNode template, String name) {
    return template.at("/spec/containers/0/env").valueStream()
        .filter(e -> name.equals(e.path("name").asString())).findFirst()
        .map(e -> e.path("value").asString()).orElse("");
  }

  private CommandResult result(boolean failure) {
    return new CommandResult(List.of(), failure ? 1 : 0, "", failure ? "failed" : "");
  }
}
