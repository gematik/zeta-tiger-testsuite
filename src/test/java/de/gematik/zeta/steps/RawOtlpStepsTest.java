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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.RawOtlpCapture;
import de.gematik.zeta.services.RawOtlpOperationClock;
import de.gematik.zeta.services.RawOtlpTraceAssertions;
import de.gematik.zeta.services.RawOtlpTraceAssertions.Operation;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validates independent operation contexts registered for one raw capture. */
class RawOtlpStepsTest {
  @Test
  void fourOperationsCanShareATraceWithDistinctParents() {
    var steps = new RawOtlpSteps();
    assertDoesNotThrow(() -> {
      steps.rememberOperation(context("b"), "GET", "/pep/resource", 200);
      steps.rememberOperation(context("c"), "POST", "/pep/other", 200);
      steps.rememberOperation(context("d"), "GET", "/pep/missing", 404);
      steps.rememberOperation(context("e"), "GET", "/pep/resource", 401);
    });
  }

  @Test
  void oneContextCannotStandInForTwoOperations() {
    var steps = new RawOtlpSteps();
    steps.rememberOperation(context("b"), "GET", "/pep/resource", 200);
    assertThrows(AssertionError.class,
        () -> steps.rememberOperation(context("b"), "GET", "/pep/resource", 401));
  }

  @Test
  void cannotAttachExpectationsWithoutAnObservedOperation() {
    var steps = new RawOtlpSteps();
    assertThrows(AssertionError.class, () -> steps.expectProduct("product", "1.0"));
    assertThrows(AssertionError.class, () -> steps.expectRouting("/pep/", "resource.example.org"));
    assertThrows(AssertionError.class, steps::expectNoProfession);
  }

  @Test
  void professionCannotBeBothPresentAndAbsent() {
    var steps = new RawOtlpSteps();
    steps.rememberOperation(context("b"), "GET", "/pep/resource", 200);
    steps.expectProfession("1.2.276.0.76.4.49");
    assertThrows(AssertionError.class, steps::expectNoProfession);
  }

  @Test
  void cannotAssociateAnUnobservedTimeWindow() {
    var steps = new RawOtlpSteps();
    steps.rememberOperation(context("b"), "GET", "/pep/resource", 200);
    assertThrows(AssertionError.class, () -> steps.assignWindow("missing"));
  }

  @Test
  void refusesFullParameterCheckWithoutIndependentExpectations() {
    var steps = new RawOtlpSteps();
    steps.rememberOperation(context("b"), "GET", "/pep/resource", 200);
    assertThrows(AssertionError.class, steps::verifyTraceParameters);
  }

  @Test
  void operationMappingExcludesTargetsAndHasAFixedSizeBound() {
    var operations = IntStream.range(1, 1001)
        .mapToObj(index -> Operation.fromTraceparent(
            "00-" + "a".repeat(32) + "-" + String.format("%016x", index) + "-01",
            "GET", "/pep/resource?token=sensitive-target", 200)).toList();
    var summary = RawOtlpSteps.operationSummary(operations);
    assertEquals(50, summary.lines().filter(line -> line.startsWith("traceId=")).count());
    assertTrue(summary.contains("Registered PEP operations: 1000"));
    assertTrue(summary.contains("Additional operation mappings omitted: 950"));
    assertTrue(summary.contains("method=GET status=200"));
    assertFalse(summary.contains("/pep/resource"));
    assertFalse(summary.contains("sensitive-target"));
    assertTrue(summary.getBytes(StandardCharsets.UTF_8).length < 8192);
  }

  @Test
  void failedVerificationAttachesOnlyTheAllowlistedOperationMapping() throws Exception {
    var capture = mock(RawOtlpCapture.class);
    var steps = configuredSteps(capture);
    var failure = new AssertionError("Raw span validation failed");
    when(capture.observeServiceNames(anyList())).thenThrow(failure);
    var attachment = new AtomicReference<String>();
    try (var report = mockStatic(SerenityReportUtils.class)) {
      report.when(() -> SerenityReportUtils.addCustomData(anyString(), anyString()))
          .thenAnswer(invocation -> {
            attachment.set(invocation.getArgument(1));
            return null;
          });
      assertSame(failure, assertThrows(AssertionError.class, steps::verifyTraceParameters));
      report.verify(() -> SerenityReportUtils.addCustomData(
          "OTLP operation mapping (verification failed)", attachment.get()));
      report.verifyNoMoreInteractions();
    }
    var summary = attachment.get();
    assertTrue(summary.contains("traceId=" + "a".repeat(32)));
    assertTrue(summary.contains("parentId=" + "b".repeat(16)));
    assertTrue(summary.contains("method=GET status=200"));
    assertFalse(summary.contains("sensitive"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"assertion", "runtime", "interrupt"})
  void reportingFailurePreservesTheOriginalVerificationFailure(String kind) throws Exception {
    var capture = mock(RawOtlpCapture.class);
    var steps = configuredSteps(capture);
    Throwable failure = switch (kind) {
      case "assertion" -> new AssertionError("Original assertion");
      case "runtime" -> new IllegalStateException("Original runtime failure");
      default -> new InterruptedException("Original interruption");
    };
    doThrow(failure).when(capture).observeServiceNames(anyList());
    var reportingFailure = new IllegalStateException("Report unavailable");
    try (var report = mockStatic(SerenityReportUtils.class)) {
      report.when(() -> SerenityReportUtils.addCustomData(anyString(), anyString()))
          .thenThrow(reportingFailure);
      var actual = assertThrows(failure.getClass(), steps::verifyTraceParameters);
      assertSame(failure, actual);
      assertEquals(1, actual.getSuppressed().length);
      assertSame(reportingFailure, actual.getSuppressed()[0]);
    }
  }

  @Test
  void reportingErrorCannotReplaceTheOriginalAssertion() throws Exception {
    var capture = mock(RawOtlpCapture.class);
    var steps = configuredSteps(capture);
    var failure = new AssertionError("Original assertion");
    var reportingFailure = new AssertionError("Report unavailable");
    when(capture.observeServiceNames(anyList())).thenThrow(failure);
    try (var report = mockStatic(SerenityReportUtils.class)) {
      report.when(() -> SerenityReportUtils.addCustomData(anyString(), anyString()))
          .thenThrow(reportingFailure);
      var actual = assertThrows(AssertionError.class, steps::verifyTraceParameters);
      assertSame(failure, actual);
      assertSame(reportingFailure, actual.getSuppressed()[0]);
    }
  }

  @Test
  void repeatedOriginalAssertionDoesNotCauseSelfSuppression() throws Exception {
    var capture = mock(RawOtlpCapture.class);
    var steps = configuredSteps(capture);
    var failure = new AssertionError("Original assertion");
    when(capture.observeServiceNames(anyList())).thenThrow(failure);
    try (var report = mockStatic(SerenityReportUtils.class)) {
      report.when(() -> SerenityReportUtils.addCustomData(anyString(), anyString()))
          .thenThrow(failure);
      assertSame(failure, assertThrows(AssertionError.class, steps::verifyTraceParameters));
      assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void successfulVerificationCreatesNoReportAttachment() throws Exception {
    var capture = mock(RawOtlpCapture.class);
    var steps = configuredSteps(capture);
    setField(steps, "gatewayRelay", "unchanged");
    when(capture.observeServiceNames(anyList())).thenReturn(true);
    when(capture.batches()).thenReturn(List.of());
    var service = mock(ZetaDeploymentModificationService.class);
    when(service.executeKubectlCommand(false, List.of("-n", "test", "get", "configmap", "gateway", "-o", "json")))
        .thenReturn(new CommandResult(List.of(), 0, "{\"data\":{\"relay\":\"unchanged\"}}", ""));
    try (var report = mockStatic(SerenityReportUtils.class);
        var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var assertions = mockStatic(RawOtlpTraceAssertions.class)) {
      deployment.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
      deployment.when(ZetaDeploymentConfiguration::getNamespace).thenReturn("test");
      assertions.when(() -> RawOtlpTraceAssertions.verifyParameters(anyList(), anyList())).thenReturn(true);
      assertDoesNotThrow(steps::verifyTraceParameters);
      assertions.verify(() -> RawOtlpTraceAssertions.verifyParameters(anyList(), anyList()));
      report.verifyNoInteractions();
    }
  }

  /**
   * Creates a complete registered operation using a local clock stub.
   *
   * @param capture capture stub
   * @return configured step instance
   * @throws ReflectiveOperationException if fixture fields cannot be set
   */
  private static RawOtlpSteps configuredSteps(RawOtlpCapture capture) throws ReflectiveOperationException {
    var steps = new RawOtlpSteps();
    var clock = mock(RawOtlpOperationClock.class);
    when(clock.now()).thenReturn(1000L, 2000L);
    setField(steps, "capture", capture);
    setField(steps, "gatewayConfigMap", "gateway");
    setField(steps, "clock", clock);
    steps.beginWindow("operation");
    steps.rememberOperation(context("b"), "GET", "/pep/resource?token=sensitive-target", 200);
    steps.endWindow("operation");
    steps.assignWindow("operation");
    steps.expectRouting("sensitive-route", "resource.example.org");
    steps.expectProduct("sensitive-product", "sensitive-version");
    steps.expectProfession("sensitive-profession");
    return steps;
  }

  /**
   * Assigns an isolated dependency to a step fixture.
   *
   * @param steps step instance
   * @param name field name
   * @param value field value
   * @throws ReflectiveOperationException if the field cannot be accessed
   */
  private static void setField(RawOtlpSteps steps, String name, Object value) throws ReflectiveOperationException {
    var field = RawOtlpSteps.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(steps, value);
  }

  private static String context(String parentDigit) {
    return "00-" + "a".repeat(32) + "-" + parentDigit.repeat(16) + "-01";
  }
}
