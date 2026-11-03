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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.LoadDispenserClient;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableTypeRegistry;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/** Regression checks for terminal dispenser recovery without hiding scenario failures. */
class LoadDispenserStepsTest {
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(2);

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void failedRunCapturesLogsRestartsAndKeepsOriginalFailure() {
    try (var fixture = new Fixture()) {
      var failure = fixture.failedRun();
      assertEquals("Load dispenser ended with status FAILED", failure.getMessage());
      assertEquals(0, failure.getSuppressed().length);
      verify(fixture.client, times(1)).start();
      verify(fixture.client, times(0)).abort();
      var order = inOrder(fixture.service);
      order.verify(fixture.service).executeKubectlCommand(false, "-n", "test-ns",
          "logs", "deployment/load-dispenser", "--all-containers=true", "--tail=500", "--timestamps=true");
      order.verify(fixture.service).executeKubectlCommand(
          "-n", "test-ns", "rollout", "restart", "deployment/load-dispenser");
      order.verify(fixture.service).waitForDeploymentRollout("test-ns", "load-dispenser", 1);
      fixture.reports.verify(() -> ReportAttachments.addText("Load dispenser recovery",
          "Restarted test-ns/load-dispenser and verified STOPPED."));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ABORTED", "STOPPED"})
  void abortThatWouldChangeFailedStatusCannotDefeatRecovery(String abortStatus) {
    try (var fixture = new Fixture()) {
      var status = new AtomicReference<>("FAILED");
      when(fixture.client.status()).thenAnswer(call -> status.get());
      doAnswer(call -> {
        status.set(abortStatus);
        return null;
      }).when(fixture.client).abort();
      when(fixture.service.waitForDeploymentRollout("test-ns", "load-dispenser", 1))
          .thenAnswer(call -> {
            status.set("STOPPED");
            return Fixture.SUCCESS;
          });

      assertEquals(0, fixture.failedRun().getSuppressed().length);
      verify(fixture.client, times(0)).abort();
      fixture.verifyRestart();
      assertEquals("STOPPED", status.get());
    }
  }

  @Test
  void stoppedRunDoesNotAbortOrRestart() throws Exception {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("STOPPED");
      new LoadDispenserSteps().startAndRunToCompletion(fixture.client, RUN_TIMEOUT);
      verifyNoInteractions(fixture.service);
      fixture.deployment.verify(ZetaDeploymentConfiguration::getServiceInstance, times(0));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"SETUP", "RUNNING", "STOPPED", "ABORTED", "UNKNOWN"})
  void startFailureDoesNotRestartOtherStates(String status) {
    try (var fixture = new Fixture()) {
      var original = Status.FAILED_PRECONDITION.asRuntimeException();
      doThrow(original).when(fixture.client).start();
      when(fixture.client.status()).thenReturn(status);
      assertSame(original, assertThrows(RuntimeException.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, RUN_TIMEOUT)));
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void activeSetupAtTimeoutIsAbortedWithoutRestart() {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("SETUP");
      var failure = assertThrows(AssertionError.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, Duration.ZERO));
      assertTrue(failure.getMessage().contains("did not complete"));
      verify(fixture.client).abort();
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void preexistingFailedStateIsRecoveredButStartErrorIsRetained() {
    try (var fixture = new Fixture()) {
      var original = Status.FAILED_PRECONDITION.withDescription("test already active (FAILED)")
          .asRuntimeException();
      doThrow(original).when(fixture.client).start();
      when(fixture.client.status()).thenReturn("FAILED", "FAILED", "STOPPED");
      assertSame(original, assertThrows(RuntimeException.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, RUN_TIMEOUT)));
      fixture.verifyRestart();
    }
  }

  @Test
  void recoveryCanBeDisabled() {
    try (var fixture = new Fixture()) {
      fixture.configuration.when(() ->
          TigerGlobalConfiguration.readBooleanOptional("load_dispenser_restart_on_failure"))
          .thenReturn(Optional.of(false));
      fixture.failedRun();
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void deploymentPermissionIsRequired() {
    try (var fixture = new Fixture()) {
      fixture.deployment.when(ZetaDeploymentConfiguration::isDeploymentModificationAllowed).thenReturn(false);
      fixture.failedRun();
      verifyNoInteractions(fixture.service);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"SETUP", "RUNNING", "STOPPED", "ABORTED", "UNKNOWN"})
  void statusChangeBeforeRestartPreventsMutation(String status) {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("FAILED", "FAILED", status);
      fixture.failedRun();
      verify(fixture.client, times(0)).abort();
      verify(fixture.service, times(0)).executeKubectlCommand(
          "-n", "test-ns", "rollout", "restart", "deployment/load-dispenser");
    }
  }

  @Test
  void failedStatusQueryRetainsOriginalFailureAndStillAborts() {
    try (var fixture = new Fixture()) {
      var original = Status.FAILED_PRECONDITION.asRuntimeException();
      var statusFailure = Status.UNAVAILABLE.asRuntimeException();
      doThrow(original).when(fixture.client).start();
      when(fixture.client.status()).thenThrow(statusFailure);

      assertSame(original, assertThrows(RuntimeException.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, RUN_TIMEOUT)));
      assertEquals(1, original.getSuppressed().length);
      assertSame(statusFailure, original.getSuppressed()[0]);
      verify(fixture.client).abort();
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void failureCausedByAbortDoesNotAuthorizeRestart() {
    try (var fixture = new Fixture()) {
      var original = Status.FAILED_PRECONDITION.asRuntimeException();
      var status = new AtomicReference<>("RUNNING");
      doThrow(original).when(fixture.client).start();
      when(fixture.client.status()).thenAnswer(call -> status.get());
      doAnswer(call -> {
        status.set("FAILED");
        return null;
      }).when(fixture.client).abort();

      assertSame(original, assertThrows(RuntimeException.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, RUN_TIMEOUT)));
      verify(fixture.client).abort();
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void unavailableStatusDoesNotAuthorizeRestart() {
    try (var fixture = new Fixture()) {
      var original = Status.UNAVAILABLE.asRuntimeException();
      when(fixture.client.status()).thenThrow(original);
      assertSame(original, assertThrows(RuntimeException.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, RUN_TIMEOUT)));
      verifyNoInteractions(fixture.service);
      assertEquals(0, original.getSuppressed().length);
    }
  }

  @Test
  void restartFailureIsSuppressedOnOriginalScenarioFailure() {
    try (var fixture = new Fixture()) {
      when(fixture.service.executeKubectlCommand(
          "-n", "test-ns", "rollout", "restart", "deployment/load-dispenser"))
          .thenReturn(new CommandResult(List.of(), 1, "", "forbidden"));
      var failure = fixture.failedRun();
      assertTrue(failure.getSuppressed()[0].getMessage().contains("restart failed"));
      assertTrue(failure.getSuppressed()[0].getMessage().contains("forbidden"));
      verify(fixture.service, times(0)).waitForDeploymentRollout("test-ns", "load-dispenser", 1);
    }
  }

  @Test
  void rolloutFailureIsSuppressedWithoutClaimingRecovery() {
    try (var fixture = new Fixture()) {
      when(fixture.service.waitForDeploymentRollout("test-ns", "load-dispenser", 1))
          .thenReturn(new CommandResult(List.of(), 1, "", "rollout timeout"));
      var failure = fixture.failedRun();
      assertTrue(failure.getSuppressed()[0].getMessage().contains("rollout failed"));
      verify(fixture.client, times(3)).status();
    }
  }

  @Test
  void diagnosticFailureDoesNotPreventRecovery() {
    try (var fixture = new Fixture()) {
      when(fixture.service.executeKubectlCommand(false, "-n", "test-ns",
          "logs", "deployment/load-dispenser", "--all-containers=true", "--tail=500", "--timestamps=true"))
          .thenThrow(new AssertionError("logs unavailable"));
      assertEquals(0, fixture.failedRun().getSuppressed().length);
      fixture.verifyRestart();
    }
  }

  @Test
  void reconnectingEndpointCanBecomeStopped() {
    try (var fixture = new Fixture()) {
      when(fixture.service.getPodReadyTimeoutSeconds()).thenReturn(3);
      when(fixture.service.waitForDeploymentRollout("test-ns", "load-dispenser", 3))
          .thenReturn(Fixture.SUCCESS);
      when(fixture.client.status()).thenReturn("FAILED", "FAILED", "FAILED")
          .thenThrow(Status.UNAVAILABLE.asRuntimeException()).thenReturn("STOPPED");
      assertEquals(0, fixture.failedRun().getSuppressed().length);
      fixture.verifyRestart();
      verify(fixture.client, times(5)).status();
    }
  }

  @Test
  void endpointTimeoutKeepsOriginalFailureAndReportsReconnectRequirement() {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("FAILED", "FAILED", "FAILED")
          .thenThrow(Status.UNAVAILABLE.asRuntimeException());
      var failure = fixture.failedRun();
      assertTrue(failure.getSuppressed()[0].getMessage().contains("did not return to STOPPED"));
      assertTrue(failure.getSuppressed()[0].getMessage().contains("port-forward"));
    }
  }

  @Test
  void nonTransientEndpointErrorIsNotRetried() {
    try (var fixture = new Fixture()) {
      var endpointError = Status.PERMISSION_DENIED.asRuntimeException();
      when(fixture.client.status()).thenReturn("FAILED", "FAILED", "FAILED").thenThrow(endpointError);
      assertSame(endpointError, fixture.failedRun().getSuppressed()[0]);
      verify(fixture.client, times(4)).status();
    }
  }

  @Test
  void unsuccessfulPostRestartStatusIsNotAccepted() {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("FAILED", "FAILED", "FAILED", "RUNNING");
      assertTrue(fixture.failedRun().getSuppressed()[0].getMessage().contains("after restart: RUNNING"));
    }
  }

  @Test
  void interruptedRunDoesNotRestart() {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("SETUP");
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, RUN_TIMEOUT));
      verify(fixture.client).abort();
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void interruptedRecoveryPreservesInterruptAndOriginalFailure() {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("FAILED", "FAILED", "FAILED").thenAnswer(call -> {
        Thread.currentThread().interrupt();
        throw Status.UNAVAILABLE.asRuntimeException();
      });
      var failure = fixture.failedRun();
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(failure.getSuppressed()[0] instanceof InterruptedException);
    }
  }

  @Test
  void followingScenarioCanConfigureAndRunAfterFailedScenarioRecovery() throws Exception {
    try (var fixture = new Fixture();
        var clients = mockConstruction(LoadDispenserClient.class, (client, context) -> {
          when(client.setConfig(any(Struct.class))).thenAnswer(call -> call.getArgument(0));
          if (context.getCount() == 1) {
            when(client.status()).thenReturn("STOPPED", "FAILED", "FAILED", "FAILED", "STOPPED");
          } else {
            when(client.status()).thenReturn("STOPPED");
          }
        })) {
      fixture.configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(call -> {
            String value = call.getArgument(0);
            return value.contains("paths.loadDispenser.endpoint") ? "localhost:50051" : value;
          });
      var table = DataTable.create(List.of(List.of("runtime_s", "1")),
          new DataTableTypeRegistryTableConverter(new DataTableTypeRegistry(Locale.GERMAN)));
      assertThrows(AssertionError.class, () -> new LoadDispenserSteps().runLoadDispenser(table));
      new LoadDispenserSteps().runLoadDispenser(table);
      fixture.verifyRestart();
      assertEquals(2, clients.constructed().size());
      for (var client : clients.constructed()) {
        verify(client, times(1)).start();
        verify(client).setConfig(any(Struct.class));
        verify(client).close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"STOPPED", "FAILED", "RESTART_ERROR", "DISABLED"})
  void newRunStartsOnlyAfterSuccessfulRecovery(String initialState) throws Exception {
    try (var fixture = new Fixture();
        var clients = mockConstruction(LoadDispenserClient.class, (client, context) -> {
          when(client.setConfig(any(Struct.class))).thenAnswer(call -> call.getArgument(0));
          when(client.status()).thenReturn("STOPPED".equals(initialState) ? "STOPPED" : "FAILED",
              "FAILED", "STOPPED", "STOPPED", "STOPPED");
          if ("STOPPED".equals(initialState) || "DISABLED".equals(initialState)) {
            when(client.status()).thenReturn(initialState.equals("STOPPED") ? "STOPPED" : "FAILED");
          }
        })) {
      fixture.configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
          .thenAnswer(call -> {
            String value = call.getArgument(0);
            return value.contains("paths.loadDispenser.endpoint") ? "localhost:50051" : value;
          });
      if ("RESTART_ERROR".equals(initialState)) {
        when(fixture.service.executeKubectlCommand(
            "-n", "test-ns", "rollout", "restart", "deployment/load-dispenser"))
            .thenReturn(new CommandResult(List.of(), 1, "", "restart rejected"));
      }
      if ("DISABLED".equals(initialState)) {
        fixture.configuration.when(() ->
            TigerGlobalConfiguration.readBooleanOptional("load_dispenser_restart_on_failure"))
            .thenReturn(Optional.of(false));
      }
      var table = DataTable.create(List.of(List.of("runtime_s", "1")),
          new DataTableTypeRegistryTableConverter(new DataTableTypeRegistry(Locale.GERMAN)));
      boolean blocked = "RESTART_ERROR".equals(initialState) || "DISABLED".equals(initialState);
      if (blocked) {
        var failure = assertThrows(IllegalStateException.class,
            () -> new LoadDispenserSteps().runLoadDispenser(table));
        if ("RESTART_ERROR".equals(initialState)) {
          assertTrue(failure.getSuppressed()[0].getMessage().contains("restart rejected"));
        }
      } else {
        new LoadDispenserSteps().runLoadDispenser(table);
      }
      var client = clients.constructed().getFirst();
      verify(client, times(blocked ? 0 : 1)).setConfig(any(Struct.class));
      verify(client, times(blocked ? 0 : 1)).start();
      verify(client).close();
      if ("FAILED".equals(initialState)) {
        var order = inOrder(fixture.service, client);
        order.verify(fixture.service).waitForDeploymentRollout("test-ns", "load-dispenser", 1);
        order.verify(client).setConfig(any(Struct.class));
        order.verify(client).start();
      }
      if ("STOPPED".equals(initialState) || "DISABLED".equals(initialState)) {
        verifyNoInteractions(fixture.service);
      }
    }
  }

  @Test
  void abortErrorDoesNotHideActiveRunTimeout() {
    try (var fixture = new Fixture()) {
      when(fixture.client.status()).thenReturn("RUNNING");
      doThrow(new AssertionError("abort failed")).when(fixture.client).abort();
      var failure = assertThrows(AssertionError.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(fixture.client, Duration.ZERO));
      assertTrue(failure.getMessage().contains("did not complete"));
      assertEquals(0, failure.getSuppressed().length);
      verify(fixture.client).abort();
      verifyNoInteractions(fixture.service);
    }
  }

  /** Controlled control-API and Kubernetes responses; no cluster or network is accessed. */
  private static final class Fixture implements AutoCloseable {
    private static final CommandResult SUCCESS = new CommandResult(List.of(), 0, "ok", "");
    private final LoadDispenserClient client = mock(LoadDispenserClient.class);
    private final ZetaDeploymentModificationService service = mock(ZetaDeploymentModificationService.class);
    private final MockedStatic<TigerGlobalConfiguration> configuration = mockStatic(TigerGlobalConfiguration.class);
    private final MockedStatic<ZetaDeploymentConfiguration> deployment = mockStatic(ZetaDeploymentConfiguration.class);
    private final MockedStatic<ReportAttachments> reports = mockStatic(ReportAttachments.class);

    private Fixture() {
      configuration.when(() ->
          TigerGlobalConfiguration.readBooleanOptional("load_dispenser_restart_on_failure"))
          .thenReturn(Optional.of(true));
      configuration.when(() ->
          TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.loadDispenser.deploymentName"))
          .thenReturn(Optional.of("load-dispenser"));
      deployment.when(ZetaDeploymentConfiguration::isDeploymentModificationAllowed).thenReturn(true);
      deployment.when(ZetaDeploymentConfiguration::getNamespace).thenReturn("test-ns");
      deployment.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
      when(client.status()).thenReturn("FAILED", "FAILED", "FAILED", "STOPPED");
      when(service.getPodReadyTimeoutSeconds()).thenReturn(1);
      when(service.executeKubectlCommand(false, "-n", "test-ns", "logs", "deployment/load-dispenser",
          "--all-containers=true", "--tail=500", "--timestamps=true")).thenReturn(SUCCESS);
      when(service.executeKubectlCommand(
          "-n", "test-ns", "rollout", "restart", "deployment/load-dispenser")).thenReturn(SUCCESS);
      when(service.waitForDeploymentRollout("test-ns", "load-dispenser", 1)).thenReturn(SUCCESS);
    }

    private AssertionError failedRun() {
      var failure = assertThrows(AssertionError.class,
          () -> new LoadDispenserSteps().startAndRunToCompletion(client, RUN_TIMEOUT));
      assertEquals("Load dispenser ended with status FAILED", failure.getMessage());
      return failure;
    }

    private void verifyRestart() {
      verify(service).executeKubectlCommand(
          "-n", "test-ns", "rollout", "restart", "deployment/load-dispenser");
    }

    @Override
    public void close() {
      reports.close();
      deployment.close();
      configuration.close();
    }
  }
}
