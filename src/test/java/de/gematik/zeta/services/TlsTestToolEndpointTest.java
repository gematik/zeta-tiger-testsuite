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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.model.CommandResult;
import de.gematik.zeta.steps.tls.TlsTestToolCertificateFixtures;
import de.gematik.zeta.steps.tls.TlsTestToolRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class TlsTestToolEndpointTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private org.mockito.MockedStatic<TigerGlobalConfiguration> configuration;
  private org.mockito.MockedStatic<TlsTestToolCertificateFixtures> fixtures;

  @org.junit.jupiter.api.BeforeEach
  void configureOrdinaryTlsPrerequisites() {
    configuration = mockStatic(TigerGlobalConfiguration.class);
    configuration.when(() -> TigerGlobalConfiguration.readStringOptional("tlsTestTool.serviceUrl"))
        .thenReturn(java.util.Optional.of("http://tool-" + java.util.UUID.randomUUID()));
    configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> call.getArgument(0));
    fixtures = mockStatic(TlsTestToolCertificateFixtures.class);
    fixtures.when(TlsTestToolCertificateFixtures::resolveCaCertificatePath).thenReturn(Path.of("ca.pem"));
    fixtures.when(() -> TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> Path.of((String) call.getArgument(0)));
  }

  @org.junit.jupiter.api.AfterEach
  void releaseOrdinaryTlsPrerequisites() {
    configuration.verify(() -> TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace"), never());
    fixtures.close();
    configuration.close();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void ordinaryTlsRunsStartAndCleanUpWithoutKubernetesOrNamespace(boolean client) {
    var api = mock(TlsTestToolService.class);
    try (var commands = forbiddenKubernetes();
        var factory = ordinaryFactory();
        var context = new TlsTestToolRunContext()) {
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(api);
      if (client) {
        context.runClient("mode=client");
        verify(api).startAsTlsClient();
      } else {
        context.runServer("mode=server");
        verify(api).startAsTlsServer();
      }
      verify(api, times(1)).reset();
      assertTrue(commands.constructed().isEmpty());
    }
    verify(api, times(2)).reset();
    assertNull(TlsTestToolServiceFactory.currentSession());
  }

  @Test
  void setupWaitsForEndpointConvergenceButObservationDoesNotRetryChanges() throws Exception {
    var cluster = new Cluster();
    cluster.endpointReplies.add("{\"items\":[]}");
    cluster.endpointReplies.add("{\"items\":[{\"endpoints\":[{\"conditions\":{\"ready\":true},\"addresses\":[\"10.0.0.99\"]}]}]}");
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api())) {
      endpoint.start(Map.of("rs.test", "10.0.0.9"), "rs");
      assertEquals(3, cluster.endpointReads);
      cluster.rsEndpoint.putArray("addresses").add("10.0.0.99");
      assertThrows(AssertionError.class, endpoint::verify);
      assertEquals(4, cluster.endpointReads);
      cluster.rsEndpoint.putArray("addresses").add("10.0.0.9");
    }
  }

  @Test
  void endpointReadFailuresAreNotRetriedAsConvergence() {
    var cluster = new Cluster();
    cluster.endpointReplies.add(null);
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api())) {
      var failure = assertThrows(AssertionError.class,
          () -> endpoint.start(Map.of("rs.test", "10.0.0.9"), "rs"));
      assertTrue(failure.getMessage().contains("TLS service operation failed"));
      assertEquals(1, cluster.endpointReads);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void ordinaryTlsRunsRequireManagedRecoveryOfAbandonedGatewayRoutes(boolean client)
      throws Exception {
    var cluster = new Cluster();
    var api = cluster.api();
    var abandoned = new TlsTestToolEndpoint(cluster, "test", "tool", api);
    abandoned.start(Map.of(), "gateway");
    abandoned.reserveRoute(cluster.gateway.deepCopy(), abandoned.selector());
    ((ObjectNode) cluster.gateway.path("spec")).set("selector", abandoned.selector());
    cluster.failDetachment = true;
    assertThrows(AssertionError.class, abandoned::close);
    cluster.failDetachment = false;
    var otherApi = mock(TlsTestToolService.class);
    try (var commands = forbiddenKubernetes();
        var factory = ordinaryFactory();
        var context = new TlsTestToolRunContext()) {
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(otherApi);
      var failure = assertThrows(AssertionError.class, () -> {
        if (client) {
          context.runClient("mode=client");
        } else {
          context.runServer("mode=server");
        }
      });
      assertTrue(failure.getCause().getMessage().contains("route cleanup is pending"));
      org.mockito.Mockito.verifyNoInteractions(otherApi);
      assertTrue(commands.constructed().isEmpty());
    }
    try (var recovered = new TlsTestToolEndpoint(cluster, "test", "tool", api)) {
      recovered.start(Map.of(), "recovered");
    }
    try (var commands = forbiddenKubernetes();
        var factory = ordinaryFactory();
        var context = new TlsTestToolRunContext()) {
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(api);
      if (client) {
        context.runClient("mode=client");
        verify(api).startAsTlsClient();
      } else {
        context.runServer("mode=server");
        verify(api).startAsTlsServer();
      }
      assertEquals("idle", cluster.gateway.at("/spec/selector/app").asString());
      verify(api, times(5)).reset();
      assertFalse(cluster.journal.path("data").has("owner"));
      assertTrue(commands.constructed().isEmpty());
    }
    assertFalse(cluster.journal.path("data").has("owner"));
    verify(api, times(6)).reset();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void ordinaryTlsRunsCannotReconfigureALiveOwnersTool(boolean client) throws Exception {
    var cluster = new Cluster();
    var otherApi = mock(TlsTestToolService.class);
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api());
        var commands = forbiddenKubernetes();
        var factory = ordinaryFactory();
        var context = new TlsTestToolRunContext()) {
      endpoint.start(Map.of(), "owner");
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(otherApi);
      assertThrows(AssertionError.class, () -> {
        if (client) {
          context.runClient("mode=client");
        } else {
          context.runServer("mode=server");
        }
      });
      org.mockito.Mockito.verifyNoInteractions(otherApi);
      assertTrue(commands.constructed().isEmpty());
    }
  }

  @Test
  void ordinaryTlsPreparationFailureStillResetsAndReleasesOwnership() {
    var cluster = new Cluster();
    var api = cluster.api();
    doThrow(new AssertionError("upload failed")).when(api).updateConfig(any(Path.class));
    try (var commands = forbiddenKubernetes();
        var factory = ordinaryFactory();
        var context = new TlsTestToolRunContext()) {
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(api);
      assertThrows(AssertionError.class, () -> context.runClient("mode=client"));
      assertTrue(commands.constructed().isEmpty());
    }
    assertNull(cluster.journal);
    verify(api, times(2)).reset();
    verify(api, never()).startAsTlsClient();
  }

  /**
   * Leaves the active owner's runtime and reservation intact after explicit-file setup fails.
   *
   * @throws Exception if endpoint verification fails
   */
  @Test
  void explicitFixtureReservationFailureAndCleanupCannotStopTheOwner() throws Exception {
    var cluster = new Cluster();
    var ownerApi = cluster.api();
    var contenderApi = mock(TlsTestToolService.class);
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", ownerApi);
        var factory = ordinaryFactory()) {
      endpoint.start(Map.of(), "owner");
      org.mockito.Mockito.clearInvocations(ownerApi);
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(contenderApi);
      var steps = new de.gematik.zeta.steps.TlsTestToolSteps();
      assertThrows(AssertionError.class,
          () -> steps.runServerWithFiles("cert.pem", "key.pem", "ca.pem", "mode=server"));
      for (int attempt = 0; attempt < 2; attempt++) {
        var hooks = java.util.Arrays.stream(de.gematik.zeta.steps.TlsTestToolSteps.class.getMethods())
            .filter(method -> method.isAnnotationPresent(io.cucumber.java.After.class))
            .sorted(java.util.Comparator.comparingInt(
                (java.lang.reflect.Method method) -> method.getAnnotation(io.cucumber.java.After.class).order())
                .reversed()).toList();
        for (var hook : hooks) {
          hook.invoke(steps);
        }
      }
      org.mockito.Mockito.verifyNoInteractions(contenderApi, ownerApi);
      endpoint.verify();
      try (var contender = new TlsTestToolSession(contenderApi)) {
        assertThrows(AssertionError.class, () -> contender.start("", "another-contender"));
      }
      org.mockito.Mockito.verifyNoInteractions(contenderApi, ownerApi);
    }
    try (var successor = new TlsTestToolSession(contenderApi)) {
      successor.start("", "successor");
      successor.assertOwned();
    }
    verify(contenderApi).reset();
    assertNull(TlsTestToolServiceFactory.currentSession());
  }

  @Test
  void managedContextUsesOneSessionAndDetachesBeforeReconfiguration() throws Exception {
    var cluster = new Cluster();
    var api = cluster.api();
    try (var context = new TlsTestToolRunContext();
        var ordinary = ordinaryFactory()) {
      context.reserveGateway(new TlsTestToolEndpoint(cluster, "test", "tool", api), "gateway");
      context.runServer("mode=server", Path.of("cert.pem"), Path.of("key.pem"), Path.of("ca.pem"));
      context.attachGateway("gateway");
      assertEquals("shared", cluster.gateway.at("/spec/selector/app").asString());
      when(api.getLogs()).thenReturn("Handshake successful");
      try (var reporting = mockStatic(net.serenitybdd.core.Serenity.class)) {
        var report = mock(net.serenitybdd.core.reports.ReportDataSaver.class);
        reporting.when(net.serenitybdd.core.Serenity::recordReportData).thenReturn(report);
        when(report.withTitle(any())).thenReturn(report);
        assertEquals("Handshake successful", context.retrieveLogs());
      }
      context.runServer("mode=server", Path.of("cert.pem"), Path.of("key.pem"), Path.of("ca.pem"));
      assertEquals("idle", cluster.gateway.at("/spec/selector/app").asString());
      context.attachGateway("gateway");
      assertEquals(2, cluster.gatewayAttachments);
      ordinary.verify(TlsTestToolServiceFactory::getSession, never());
    }
    verify(api, times(2)).startAsTlsServer();
    verify(api, times(4)).reset();
    assertFalse(cluster.journal.path("data").has("owner"));
  }

  @Test
  void ownedFixtureDeletionBeforeTlsCleanupStillRequiresEndpointDetachment() throws Exception {
    var cluster = new Cluster();
    cluster.gatewayPresent = false;
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    var context = new TlsTestToolRunContext();
    var api = cluster.api();
    boolean resourcesRestored = false;
    try {
      recovery.create(cluster.gatewayFixture());
      context.reserveGateway(new TlsTestToolEndpoint(cluster, "test", "tool", api), "gateway");
      context.runServer("mode=server", Path.of("cert.pem"), Path.of("key.pem"), Path.of("ca.pem"));
      context.attachGateway("gateway");
      var snapshot = JSON.readTree(cluster.journal.at("/data/snapshot").asString());
      assertEquals(cluster.gateway.at("/metadata/annotations/testsuite.gematik.de~1resource-owner"),
          snapshot.at("/routes/0/fixtureOwner"));
      recovery.finish();
      resourcesRestored = true;
      assertFalse(cluster.gatewayPresent);
      cluster.failDetachment = true;
      assertThrows(AssertionError.class, context::close);
      verify(api, times(2)).reset();
      assertTrue(cluster.journal.path("data").has("owner"));
      try (var contender = new TlsTestToolSession(mock(TlsTestToolService.class))) {
        assertThrows(AssertionError.class, () -> contender.start("", "ordinary"));
      }
    } finally {
      cluster.failDetachment = false;
      if (!resourcesRestored) {
        recovery.finish();
      }
      context.close();
      try (var recovered = new TlsTestToolEndpoint(cluster, "test", "tool", api)) {
        recovered.start(Map.of(), "recovery");
      }
    }
    assertFalse(cluster.journal.path("data").has("owner"));
  }

  @Test
  void failedOwnedRouteRestorationRetainsMarkerUntilManagedRecovery() throws Exception {
    var cluster = new Cluster();
    cluster.gatewayPresent = false;
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    var context = new TlsTestToolRunContext();
    var api = cluster.api();
    boolean resourcesRestored = false;
    try {
      recovery.create(cluster.gatewayFixture());
      context.reserveGateway(new TlsTestToolEndpoint(cluster, "test", "tool", api), "gateway");
      context.runServer("mode=server", Path.of("cert.pem"), Path.of("key.pem"), Path.of("ca.pem"));
      context.attachGateway("gateway");
      cluster.failGatewayPatch = true;
      assertThrows(AssertionError.class, context::close);
      verify(api, times(2)).reset();
      assertEquals("shared", cluster.gateway.at("/spec/selector/app").asString());
      assertTrue(cluster.journal.path("data").has("owner"));
      try (var contender = new TlsTestToolSession(mock(TlsTestToolService.class))) {
        assertThrows(AssertionError.class, () -> contender.start("", "ordinary"));
      }
      recovery.finish();
      resourcesRestored = true;
      assertFalse(cluster.gatewayPresent);
    } finally {
      cluster.failGatewayPatch = false;
      if (!resourcesRestored) {
        recovery.finish();
      }
      context.close();
      try (var recovered = new TlsTestToolEndpoint(cluster, "test", "tool", api)) {
        recovered.start(Map.of(), "recovery");
      }
    }
    assertFalse(cluster.journal.path("data").has("owner"));
  }

  @Test
  void missingUnownedGatewayCannotBeAcceptedAsFixtureCleanup() throws Exception {
    var cluster = new Cluster();
    var api = cluster.api();
    var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", api);
    endpoint.start(Map.of(), "unowned");
    endpoint.attachGateway("gateway");
    cluster.gatewayPresent = false;
    try {
      assertThrows(AssertionError.class, endpoint::close);
      verify(api, times(1)).reset();
      assertTrue(cluster.journal.path("data").has("owner"));
    } finally {
      cluster.gatewayPresent = true;
      endpoint.close();
    }
  }

  @Test
  void tcpPeerUsesServiceIpWhileItsOnlyEndpointRemainsTheObservedNsPod() throws Exception {
    var cluster = new Cluster();
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api())) {
      endpoint.start(Map.of("rs.test", "10.0.0.9"), "rs");
      assertEquals("10.96.0.9", endpoint.serviceAddress("rs.test"));
      assertThrows(AssertionError.class, () -> endpoint.serviceAddress("unobserved"));
      ((ObjectNode) cluster.rs.path("metadata")).put("resourceVersion", "2");
      assertEquals("10.96.0.9", endpoint.serviceAddress("rs.test"));
      ((ObjectNode) cluster.rs.path("spec")).put("clusterIP", "None");
      assertThrows(AssertionError.class, endpoint::verify);
      ((ObjectNode) cluster.rs.path("spec")).put("clusterIP", "10.96.0.9");
      ((ObjectNode) cluster.rs.path("metadata")).put("uid", "replacement");
      assertThrows(AssertionError.class, endpoint::verify);
      ((ObjectNode) cluster.rs.path("metadata")).put("uid", "rs-uid");
      cluster.rsEndpoint.putArray("addresses").add("10.0.0.99");
      assertThrows(AssertionError.class, endpoint::verify);
      cluster.rsEndpoint.putArray("addresses").add("10.0.0.9");
      ((ObjectNode) cluster.rsEndpoint.path("conditions")).put("ready", false);
      assertThrows(AssertionError.class, endpoint::verify);
      ((ObjectNode) cluster.rsEndpoint.path("conditions")).put("ready", true);
    }
  }

  @Test
  void headlessServiceUsesItsVerifiedPodAddress() throws Exception {
    var cluster = new Cluster();
    ((ObjectNode) cluster.rs.path("spec")).put("clusterIP", "None");
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api())) {
      endpoint.start(Map.of("rs.test", "10.0.0.9"), "headless");
      assertEquals("10.0.0.9", endpoint.serviceAddress("rs.test"));
    }
  }

  @Test
  void wrongNsEndpointFailsSetupAndStillReleasesTheOwnedTool() {
    var cluster = new Cluster();
    var api = cluster.api();
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", api)) {
      var failure = assertThrows(AssertionError.class,
          () -> endpoint.start(Map.of("rs.test", "10.0.0.99"), "wrong-pod"));
      assertTrue(failure.getMessage().contains("did not converge"));
      assertTrue(cluster.endpointReads > 1);
    }
    assertFalse(cluster.journal.path("data").has("owner"));
    verify(api, times(1)).reset();
  }

  @Test
  void sharedToolRejectsConcurrentOwnerWithoutResetAndDetachesBeforeCleanup() throws Exception {
    var cluster = new Cluster();
    var api = cluster.api();
    var first = new TlsTestToolEndpoint(cluster, "test", "tool", api);
    var secondApi = mock(TlsTestToolService.class);
    var second = new TlsTestToolEndpoint(cluster, "test", "tool", secondApi);
    first.start(Map.of(), "first");
    assertThrows(AssertionError.class, () -> second.start(Map.of(), "second"));
    second.close();
    verify(secondApi, never()).reset();
    first.reserveRoute(cluster.gateway.deepCopy(), first.selector());
    ((ObjectNode) cluster.gateway.path("spec")).set("selector", first.selector());
    first.close();
    first.close();
    verify(api, times(2)).reset();
    assertEquals("idle", cluster.gateway.at("/spec/selector/app").asString());
    assertFalse(cluster.journal.path("data").has("owner"));
  }

  @Test
  void failedDetachmentRetainsBackupAndNextOwnerRecoversBeforeReset() throws Exception {
    var cluster = new Cluster();
    var api = cluster.api();
    var first = new TlsTestToolEndpoint(cluster, "test", "tool", api);
    first.start(Map.of(), "first");
    first.reserveRoute(cluster.gateway.deepCopy(), first.selector());
    ((ObjectNode) cluster.gateway.path("spec")).set("selector", first.selector());
    cluster.failDetachment = true;
    assertThrows(AssertionError.class, first::close);
    verify(api, times(1)).reset();
    assertTrue(cluster.journal.path("data").has("owner"));
    cluster.failDetachment = false;
    try (var recovered = new TlsTestToolEndpoint(cluster, "test", "tool", api)) {
      recovered.start(Map.of(), "recovered");
      assertEquals("idle", cluster.gateway.at("/spec/selector/app").asString());
      verify(api, times(3)).reset();
    }
    assertFalse(cluster.journal.path("data").has("owner"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"namespace", "cluster", "missing-journal"})
  void unrelatedManagedSessionCannotReleasePendingGatewayRecovery(String changedIdentity) throws Exception {
    var cluster = new Cluster();
    var first = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api());
    first.start(Map.of(), "first");
    first.reserveRoute(cluster.gateway.deepCopy(), first.selector());
    ((ObjectNode) cluster.gateway.path("spec")).set("selector", first.selector());
    cluster.failDetachment = true;
    assertThrows(AssertionError.class, first::close);
    var pendingJournal = cluster.journal.deepCopy();
    var otherCluster = new Cluster();
    String otherNamespace = "namespace".equals(changedIdentity) ? "other" : "test";
    if (!"missing-journal".equals(changedIdentity)) {
      otherCluster.journal = pendingJournal.deepCopy();
      otherCluster.journal.putObject("data");
      ((ObjectNode) otherCluster.journal.path("metadata"))
          .put("namespace", otherNamespace)
          .put("uid", "cluster".equals(changedIdentity) ? otherCluster.journalUid : cluster.journalUid);
    }
    var otherJournal = otherCluster.journal == null ? null : otherCluster.journal.deepCopy();
    var otherApi = mock(TlsTestToolService.class);
    try (var other = new TlsTestToolEndpoint(otherCluster, otherNamespace, "tool", otherApi)) {
      assertThrows(AssertionError.class, () -> other.start(Map.of(), "unrelated"));
    }
    org.mockito.Mockito.verifyNoInteractions(otherApi);
    assertEquals(otherJournal, otherCluster.journal);
    assertEquals(pendingJournal, cluster.journal);
    try (var ordinary = new TlsTestToolSession(otherApi)) {
      assertThrows(AssertionError.class, () -> ordinary.start("", "ordinary"));
    }
    org.mockito.Mockito.verifyNoInteractions(otherApi);
    cluster.failDetachment = false;
    try (var recovered = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api())) {
      recovered.start(Map.of(), "recovered");
      assertEquals("idle", cluster.gateway.at("/spec/selector/app").asString());
    }
    assertFalse(cluster.journal.path("data").has("owner"));
    try (var ordinary = new TlsTestToolSession(otherApi)) {
      ordinary.start("", "ordinary");
      ordinary.reset();
    }
    verify(otherApi, times(2)).reset();
  }

  @Test
  void freshConfigurationClearsOldCredentialsAndDeletesTemporaryUploads() throws Exception {
    var cluster = new Cluster();
    var api = cluster.api();
    var uploaded = new ArrayList<Path>();
    doAnswer(call -> {
      uploaded.add(call.getArgument(0));
      return null;
    })
        .when(api).updateConfig(any(Path.class));
    doAnswer(call -> {
      uploaded.add(call.getArgument(0));
      return null;
    })
        .when(api).updateCaCertificate(any(Path.class));
    try (var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", api)) {
      endpoint.start(Map.of(), "fresh");
      endpoint.configure("mode=client", Map.of("ca", new byte[] {1}), null, null, "ca");
      verify(api, times(2)).reset();
      verify(api, never()).updateCertificate(any(Path.class), any(Path.class));
      assertEquals(2, uploaded.size());
      uploaded.forEach(file -> assertFalse(Files.exists(file)));
    }
  }

  @Test
  void changedPodAndExternalRouteArePreservedAndCannotTriggerReset() throws Exception {
    var cluster = new Cluster();
    var api = cluster.api();
    var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", api);
    endpoint.start(Map.of(), "changed");
    endpoint.reserveRoute(cluster.gateway.deepCopy(), endpoint.selector());
    ((ObjectNode) cluster.gateway.path("spec")).putObject("selector").put("app", "foreign");
    assertThrows(AssertionError.class, endpoint::close);
    verify(api, times(1)).reset();
    assertEquals("foreign", cluster.gateway.at("/spec/selector/app").asString());
    assertTrue(cluster.journal.path("data").has("owner"));
    // Restore only this fake's external change so its durable claim can be safely cleaned up.
    ((ObjectNode) cluster.gateway.path("spec")).set("selector", endpoint.selector());
    ((ObjectNode) cluster.pod.path("metadata")).put("uid", "replacement");
    assertThrows(AssertionError.class, endpoint::verify);
    assertThrows(AssertionError.class, endpoint::close);
    verify(api, times(1)).reset();
    ((ObjectNode) cluster.pod.path("metadata")).put("uid", "pod-uid");
    endpoint.close();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void interruptedForwardingPreservesCleanupFailureAndTerminatesProcess(boolean failedDetachment)
      throws Exception {
    var cluster = new Cluster();
    var endpoint = new TlsTestToolEndpoint(cluster, "test", "tool", cluster.api());
    endpoint.start(Map.of(), "interrupted");
    if (failedDetachment) {
      endpoint.reserveRoute(cluster.gateway.deepCopy(), endpoint.selector());
      ((ObjectNode) cluster.gateway.path("spec")).set("selector", endpoint.selector());
      cluster.failDetachment = true;
    }
    var process = mock(Process.class);
    when(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
        .thenThrow(new InterruptedException("interrupted forwarding"));
    var forwarding = TlsTestToolEndpoint.class.getDeclaredField("forwarding");
    forwarding.setAccessible(true);
    forwarding.set(endpoint, process);
    try {
      final var failure = assertThrows(AssertionError.class, endpoint::close);
      assertTrue(Thread.currentThread().isInterrupted());
      verify(process).destroy();
      verify(process).destroyForcibly();
      assertEquals(failedDetachment, cluster.journal.path("data").has("owner"));
      if (failedDetachment) {
        assertEquals(
            "TLS gateway routes could not be detached; reset and ownership release refused",
            failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals("Interrupted while closing TLS forwarding",
            failure.getSuppressed()[1].getMessage());
      } else {
        assertEquals("Interrupted while closing TLS forwarding", failure.getMessage());
        assertEquals(0, failure.getSuppressed().length);
      }
    } finally {
      // Clear only this test's injected interrupt before recovery and subsequent tests.
      Thread.interrupted();
      forwarding.set(endpoint, null);
      cluster.failDetachment = false;
      endpoint.close();
    }
  }

  @Test
  void stagedOcspResponseSurvivesServerPreparationInTheSameOwnedScenario() {
    var cluster = new Cluster();
    var api = cluster.api();
    try (var commands = forbiddenKubernetes();
        var factory = ordinaryFactory();
        var context = new TlsTestToolRunContext()) {
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(api);
      var staged = new byte[] {1, 2, 3};
      TlsTestToolServiceFactory.getSession().api().updateOcspResponse(staged);
      context.runServer("mode=server");
      verify(api).updateOcspResponse(staged);
      verify(api, times(1)).reset();
      verify(api).startAsTlsServer();
      assertNull(cluster.journal);
      assertTrue(commands.constructed().isEmpty());
    }
    verify(api, times(2)).reset();
    assertNull(cluster.journal);
  }

  @Test
  void cleanupAlsoReleasesOcspPreparationWhenNoTlsRunWasStarted() {
    var cluster = new Cluster();
    var api = cluster.api();
    try (var commands = forbiddenKubernetes();
        var factory = ordinaryFactory();
        var context = new TlsTestToolRunContext()) {
      factory.when(TlsTestToolServiceFactory::getInstance).thenReturn(api);
      TlsTestToolServiceFactory.getSession().api().updateOcspResponse(new byte[] {1});
      assertTrue(commands.constructed().isEmpty());
    }
    verify(api, times(2)).reset();
    assertNull(cluster.journal);
  }

  private static org.mockito.MockedStatic<TlsTestToolServiceFactory> ordinaryFactory() {
    var factory = mockStatic(TlsTestToolServiceFactory.class);
    factory.when(TlsTestToolServiceFactory::getSession).thenCallRealMethod();
    factory.when(TlsTestToolServiceFactory::currentSession).thenCallRealMethod();
    factory.when(TlsTestToolServiceFactory::serviceUrl).thenCallRealMethod();
    factory.when(() -> TlsTestToolServiceFactory.releaseSession(any(TlsTestToolSession.class))).thenCallRealMethod();
    return factory;
  }

  private static org.mockito.MockedConstruction<ZetaDeploymentModificationService> forbiddenKubernetes() {
    return org.mockito.Mockito.mockConstruction(ZetaDeploymentModificationService.class, (executor, context) -> {
      throw new AssertionError("Ordinary TLS runs must not construct a Kubernetes client");
    });
  }

  private static class Cluster extends ZetaDeploymentModificationService {
    final ObjectNode deployment = (ObjectNode) JSON.readTree("""
        {"spec":{"selector":{"matchLabels":{"app":"shared"}},"template":{"spec":{"containers":[{"image":"tool:test"}]}}}}
        """);
    final ObjectNode pod = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"tool-pod","uid":"pod-uid"},"spec":{"containers":[{"image":"tool:test"}]},
        "status":{"phase":"Running","podIP":"10.0.0.8","containerStatuses":[{"ready":true,"restartCount":0}]}}
        """);
    final ObjectNode gateway = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"gateway","uid":"gateway-uid","resourceVersion":"1",
          "annotations":{"testsuite.gematik.de/tls-peer":"true"}},
         "spec":{"selector":{"app":"idle"},"ports":[{"port":443,"targetPort":4433,"protocol":"TCP"}]}}
        """);
    final ObjectNode rs = (ObjectNode) JSON.readTree("""
        {"metadata":{"name":"rs","uid":"rs-uid","resourceVersion":"1"},
        "spec":{"clusterIP":"10.96.0.9","selector":{"app":"ns"}}}
        """);
    final ObjectNode rsEndpoint = (ObjectNode) JSON.readTree("""
        {"conditions":{"ready":true},"addresses":["10.0.0.9"]}
        """);
    ObjectNode journal;
    final String journalUid = java.util.UUID.randomUUID().toString();
    int version;
    boolean failDetachment;
    boolean failGatewayPatch;
    boolean gatewayPresent = true;
    int gatewayAttachments;
    final Map<String, ObjectNode> fixtureResources = new java.util.LinkedHashMap<>();
    final List<String> endpointReplies = new ArrayList<>();
    int endpointReads;

    Cluster() {
      super(5, 1);
    }

    TlsTestToolService api() {
      var api = mock(TlsTestToolService.class);
      doAnswer(call -> {
        assertTrue(!gatewayPresent || "idle".equals(gateway.at("/spec/selector/app").asString()),
            "Reset must follow observed route detachment or owned fixture deletion");
        return null;
      }).when(api).reset();
      return api;
    }

    private ObjectNode gatewayFixture() {
      var manifest = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Service");
      manifest.putObject("metadata").put("name", "gateway").putObject("annotations")
          .put("testsuite.gematik.de/tls-peer", "true");
      manifest.set("spec", gateway.path("spec").deepCopy());
      return manifest;
    }

    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> args) {
      try {
        if (args.contains("--raw")) {
          var options = JSON.readTree(Files.readString(Path.of(args.get(args.indexOf("-f") + 1))));
          assertEquals(gateway.at("/metadata/uid"), options.at("/preconditions/uid"));
          assertEquals(gateway.at("/metadata/resourceVersion"), options.at("/preconditions/resourceVersion"));
          gatewayPresent = false;
          return new CommandResult(args, 0, "deleted", "");
        }
        if (args.contains("--for=delete")) {
          return new CommandResult(args, gatewayPresent ? 1 : 0, "", "");
        }
        if (args.contains("get") && args.get(args.indexOf("get") + 1).contains("/")) {
          var target = args.get(args.indexOf("get") + 1);
          var found = "service/gateway".equals(target) ? (gatewayPresent ? gateway : null) : fixtureResources.get(target);
          return new CommandResult(args, 0, found == null ? "" : found.toString(), "");
        }
        String output;
        switch (args.get(2)) {
          case "get" -> output = switch (args.get(3)) {
            case "deployment" -> deployment.toString();
            case "pods" -> JSON.createObjectNode().set("items", JSON.createArrayNode().add(pod)).toString();
            case "pod" -> pod.toString();
            case "service" -> "rs".equals(args.get(4)) ? rs.toString() : gatewayPresent ? gateway.toString() : "";
            case "configmap" -> {
              var found = "testsuite-tls-tool".equals(args.get(4)) ? journal : fixtureResources.get("configmap/" + args.get(4));
              yield found == null ? "" : found.toString();
            }
            case "endpointslices" -> {
              if (args.contains("kubernetes.io/service-name=rs")) {
                endpointReads++;
                if (!endpointReplies.isEmpty()) {
                  yield endpointReplies.removeFirst();
                }
                yield JSON.createObjectNode().set("items", JSON.createArrayNode().add(
                    JSON.createObjectNode().set("endpoints", JSON.createArrayNode().add(rsEndpoint))))
                    .toString();
              }
              if (failDetachment) {
                yield null;
              }
              if (gatewayPresent && "shared".equals(gateway.at("/spec/selector/app").asString())) {
                yield """
                    {"items":[{"endpoints":[{"conditions":{"ready":true},"addresses":["10.0.0.8"],
                      "targetRef":{"name":"tool-pod"}}]}]}
                    """;
              }
              yield "{\"items\":[]}";
            }
            default -> throw new AssertionError("Unexpected read: " + args);
          };
          case "create", "replace" -> {
            var resource = (ObjectNode) JSON.readTree(Files.readString(Path.of(args.get(4))));
            var resourceName = resource.at("/metadata/name").asString();
            if (!"testsuite-tls-tool".equals(resourceName)) {
              var metadata = (ObjectNode) resource.path("metadata");
              if (!metadata.has("uid")) {
                metadata.put("uid", java.util.UUID.randomUUID().toString());
              }
              metadata.put("resourceVersion", Integer.toString(++version));
              if ("Service".equals(resource.path("kind").asString())) {
                gateway.removeAll();
                gateway.setAll(resource);
                gatewayPresent = true;
              } else {
                fixtureResources.put("configmap/" + resourceName, resource);
              }
              return new CommandResult(args, 0, resource.toString(), "");
            }
            if (args.get(2).equals("create")) {
              assertNull(journal);
              ((ObjectNode) resource.path("metadata")).put("uid", journalUid);
            } else {
              assertEquals(journal.at("/metadata/resourceVersion"), resource.at("/metadata/resourceVersion"));
            }
            ((ObjectNode) resource.path("metadata")).put("resourceVersion", Integer.toString(++version));
            journal = resource;
            output = journal.toString();
          }
          case "patch" -> {
            assertEquals("service", args.get(3), "Shared tool pods must not be mutated");
            if (failGatewayPatch) {
              return new CommandResult(args, 1, "", "selector restoration failed");
            }
            for (var operation : JSON.readTree(Files.readString(Path.of(args.getLast())))) {
              if (operation.path("op").asString().equals("test")) {
                assertEquals(gateway.at(operation.path("path").asString()), operation.path("value"));
              } else {
                ((ObjectNode) gateway.path("spec")).set("selector", operation.path("value"));
              }
            }
            if ("shared".equals(gateway.at("/spec/selector/app").asString())) {
              gatewayAttachments++;
            }
            output = gateway.toString();
          }
          default -> throw new AssertionError("Unexpected mutation: " + args);
        }
        return new CommandResult(args, output == null ? 1 : 0, output == null ? "" : output, "");
      } catch (java.io.IOException exception) {
        throw new AssertionError(exception);
      }
    }
  }
}
