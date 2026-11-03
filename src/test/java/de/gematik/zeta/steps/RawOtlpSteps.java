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
import de.gematik.zeta.services.RawOtlpAttempts;
import de.gematik.zeta.services.RawOtlpCapture;
import de.gematik.zeta.services.RawOtlpOperationClock;
import de.gematik.zeta.services.RawOtlpTraceAssertions;
import de.gematik.zeta.services.RawOtlpTraceAssertions.ExpectedAttributes;
import de.gematik.zeta.services.RawOtlpTraceAssertions.Observation;
import de.gematik.zeta.services.RawOtlpTraceAssertions.Operation;
import de.gematik.zeta.services.RawOtlpTraceAssertions.ProfessionExpectation;
import de.gematik.zeta.services.RecordedMessages;
import de.gematik.zeta.services.TelemetryCollectorDiscovery;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Wenn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * A scoped raw-export fixture and correlated, observed PEP operations.
 */
public class RawOtlpSteps {
  private static final int MAX_REPORTED_OPERATIONS = 50;
  private final List<Operation> operations = new ArrayList<>();
  private final List<OperationEvidence> evidence = new ArrayList<>();
  private final Map<String, long[]> windows = new LinkedHashMap<>();
  private final List<AttemptGroup> attemptGroups = new ArrayList<>();
  private List<OperationEvidence> lastRegistration = List.of();
  private RawOtlpCapture capture;
  private RawOtlpOperationClock clock;
  private String gatewayRelay;
  private String gatewayConfigMap;

  /**
   * Starts the local test collector capture after verifying the gematik delivery path.
   */
  @Gegebensei("eine unveränderte OTLP-Rohdatenerfassung am gematik-Testempfänger")
  public void startCapture() {
    if (!TigerGlobalConfiguration.readBooleanOptional("allow_deployment_modification")
        .orElse(false)) {
      throw new AssertionError(
          "Raw capture requires explicitly enabled test deployment modification");
    }
    var service = ZetaDeploymentConfiguration.getServiceInstance();
    var namespace = ZetaDeploymentConfiguration.getNamespace();
    gatewayConfigMap = TelemetryCollectorDiscovery.gatewayConfigMap(service, namespace);
    gatewayRelay = TelemetryCollectorDiscovery.relayConfig(service, namespace, gatewayConfigMap);
    var relay = new ObjectMapper(new YAMLFactory()).readTree(gatewayRelay);
    var target = TelemetryCollectorDiscovery.captureTarget(service, namespace, relay,
        config("telemetry.gematikTracePipeline"));
    capture = new RawOtlpCapture(service, namespace, target);
    capture.start();
  }

  /**
   * Registers the correlation and HTTP metadata of an observed PEP request and response.
   *
   * @param traceparent observed W3C trace context
   * @param method GET or POST
   * @param target observed PEP request path
   * @param status observed PEP response status
   */
  @Wenn("merke für den OTLP-Rohexport den Tracekontext {tigerResolvedString} mit Methode {word},"
      + " Ziel {tigerResolvedString} und Status {int}")
  public void rememberOperation(String traceparent, String method, String target, int status) {
    var operation = Operation.fromTraceparent(traceparent, method, target, status);
    if (operations.stream().anyMatch(previous -> previous.traceId().equals(operation.traceId())
        && previous.parentId().equals(operation.parentId()))) {
      throw new AssertionError("Each telemetry operation requires its own observed parent context");
    }
    operations.add(operation);
    var added = new OperationEvidence(operation);
    evidence.add(added);
    lastRegistration = List.of(added);
  }

  /**
   * Registers each independently observed HTTP attempt of one isolated trace.
   *
   * @param traceId isolated trace identifier
   * @param method observed HTTP method
   * @param target observed PEP path
   * @param status observed response status
   * @param windowName open clock window containing the requests and complete responses
   */
  @Wenn("merke für den OTLP-Rohexport alle PEP-Versuche der Trace-ID {tigerResolvedString}"
      + " mit Methode {word}, Ziel {tigerResolvedString} und Status {int}"
      + " im Zeitfenster {tigerResolvedString}")
  public void rememberAttempts(String traceId, String method, String target, int status,
      String windowName) {
    var window = windows.get(windowName);
    require(window != null && window[1] == 0, "Attempt snapshot requires an open clock window");
    var observed = RawOtlpAttempts.fromMessages(RecordedMessages.snapshot(), traceId, method,
        target, status);
    var start = evidence.size();
    for (var operation : observed) {
      rememberOperation("00-" + operation.traceId() + "-" + operation.parentId() + "-01",
          method, target, status);
    }
    lastRegistration = List.copyOf(evidence.subList(start, evidence.size()));
    attemptGroups.add(new AttemptGroup(traceId, method, target, status,
        observed.stream().map(Operation::parentId).collect(Collectors.toSet())));
  }

  /**
   * Records the PEP clock before an independently stimulated operation.
   *
   * @param name unique operation window name
   */
  @Wenn("merke den Beginn des PEP-Operationsfensters {tigerResolvedString}")
  public void beginWindow(String name) {
    require(!windows.containsKey(name), "Operation window name was already used");
    if (clock == null) {
      clock = new RawOtlpOperationClock(ZetaDeploymentConfiguration.getServiceInstance(),
          ZetaDeploymentConfiguration.getNamespace(), config("zetaDeploymentConfig.pep.podName"),
          config("zetaDeploymentConfig.pep.nginx.containerName"));
    }
    windows.put(name, new long[] {clock.now(), 0});
  }

  /**
   * Records the PEP clock after the operation's response was independently observed.
   *
   * @param name previously opened operation window
   */
  @Wenn("merke das Ende des PEP-Operationsfensters {tigerResolvedString}")
  public void endWindow(String name) {
    var window = windows.get(name);
    require(window != null && window[1] == 0, "Operation window is missing or already closed");
    window[1] = clock.now();
    require(window[1] >= window[0], "PEP clock moved backwards during the operation");
  }

  /**
   * Associates an independently bounded clock interval with the last registered operation.
   *
   * @param name closed operation window
   */
  @Wenn("ordne dem letzten OTLP-Operationsnachweis das Zeitfenster {tigerResolvedString} zu")
  public void assignWindow(String name) {
    var window = windows.get(name);
    require(window != null && window[1] > 0, "Operation window is not complete");
    updateLast(last -> {
      require(last.earliest == null, "Operation already has a clock window");
      last.earliest = window[0];
      last.latest = window[1];
    });
  }

  /**
   * Registers the route and independently observed destination of the last operation.
   *
   * @param route expected configured route
   * @param serverAddress destination FQDN observed on the PEP request
   */
  @Wenn("erwarte für den letzten OTLP-Operationsnachweis die Route {tigerResolvedString}"
      + " und den Zielserver {tigerResolvedString}")
  public void expectRouting(String route, String serverAddress) {
    updateLast(last -> {
      require(last.route == null, "Operation routing was already registered");
      last.route = route;
      last.serverAddress = serverAddress;
    });
  }

  /**
   * Registers the product values of the independently observed client statement.
   *
   * @param productId calling client product ID
   * @param productVersion calling client product version
   */
  @Wenn("erwarte für den letzten OTLP-Operationsnachweis die Produkt-ID {tigerResolvedString}"
      + " und Produktversion {tigerResolvedString}")
  public void expectProduct(String productId, String productVersion) {
    updateLast(last -> {
      require(last.productId == null, "Operation product values were already registered");
      last.productId = productId;
      last.productVersion = productVersion;
    });
  }

  /**
   * Registers the profession read independently from the calling client's SM-B certificate.
   *
   * @param oid certificate profession OID
   */
  @Wenn("erwarte für den letzten OTLP-Operationsnachweis die Profession-OID {tigerResolvedString}")
  public void expectProfession(String oid) {
    updateLast(last -> {
      require(last.profession == null && oid != null && !oid.isBlank(),
          "Missing or previously registered certificate profession");
      last.profession = new ProfessionExpectation(oid);
    });
  }

  /** Registers the absence of a profession for an independently SM-B-free operation. */
  @Wenn("erwarte für den letzten OTLP-Operationsnachweis keine Profession-OID")
  public void expectNoProfession() {
    updateLast(last -> {
      require(last.profession == null, "Profession expectation was already registered");
      last.profession = new ProfessionExpectation(null);
    });
  }

  /**
   * Checks the original span parameters against the independently observed operation context.
   *
   * @throws InterruptedException if raw-export observation is interrupted
   */
  @Dann("entsprechen alle markierten PEP-Operationen im OTLP-Rohexport ihren beobachteten"
      + " Zeitfenstern und Traceparametern")
  public void verifyTraceParameters() throws InterruptedException {
    try {
      verifyRecordedParameters();
    } catch (AssertionError | RuntimeException | InterruptedException failure) {
      reportFailure(failure);
      throw failure;
    }
  }

  private void verifyRecordedParameters() throws InterruptedException {
    var observations = evidence.stream().map(OperationEvidence::observation).toList();
    observe();
    var batches = capture.batches();
    for (var group : attemptGroups) {
      var current = RawOtlpAttempts.fromMessages(RecordedMessages.snapshot(), group.traceId(),
          group.method(), group.target(), group.status());
      require(group.parents().equals(current.stream().map(Operation::parentId)
          .collect(Collectors.toSet())), "Additional PEP attempts arrived after the clock window closed");
      for (var batch : batches) {
        for (var resource : batch.path("resourceSpans")) {
          for (var scope : resource.path("scopeSpans")) {
            for (var span : scope.path("spans")) {
              if (group.traceId().equals(span.path("traceId").asString())
                  && span.path("kind").asInt() == 2
                  && "nginx".equals(scope.path("scope").path("name").asString())) {
                require(group.parents().contains(span.path("parentSpanId").asString()),
                    "Exported PEP attempt has no independently observed request");
              }
            }
          }
        }
      }
    }
    require(RawOtlpTraceAssertions.verifyParameters(batches, observations),
        "A marked operation has no original exported PEP span");
  }

  private void observe() throws InterruptedException {
    require(capture != null && !operations.isEmpty(), "No marked PEP operations were recorded");
    final boolean complete = capture.observeServiceNames(operations);
    if (!complete) {
      throw new AssertionError(
          "Not every marked PEP operation arrived in the complete raw capture within the 30-second"
              + " test deadline");
    }
    if (!gatewayRelay.equals(TelemetryCollectorDiscovery.relayConfig(
        ZetaDeploymentConfiguration.getServiceInstance(), ZetaDeploymentConfiguration.getNamespace(), gatewayConfigMap))) {
      throw new AssertionError("Gematik export configuration changed during observation");
    }
  }

  private void reportFailure(Throwable failure) {
    try {
      SerenityReportUtils.addCustomData("OTLP operation mapping (verification failed)",
          operationSummary(operations));
    } catch (RuntimeException | Error reportingFailure) {
      if (reportingFailure != failure) {
        failure.addSuppressed(reportingFailure);
      }
    }
  }

  /**
   * Formats a bounded mapping of validated correlation and HTTP status fields.
   *
   * @param recorded registered operations
   * @return mapping without request targets or attribute values
   */
  static String operationSummary(List<Operation> recorded) {
    var summary = new StringBuilder("Registered PEP operations: ").append(recorded.size());
    recorded.stream().limit(MAX_REPORTED_OPERATIONS).forEach(operation -> summary
        .append("\ntraceId=").append(operation.traceId())
        .append(" parentId=").append(operation.parentId())
        .append(" method=").append(operation.method())
        .append(" status=").append(operation.status()));
    if (recorded.size() > MAX_REPORTED_OPERATIONS) {
      summary.append("\nAdditional operation mappings omitted: ")
          .append(recorded.size() - MAX_REPORTED_OPERATIONS);
    }
    return summary.toString();
  }

  private void updateLast(Consumer<OperationEvidence> update) {
    require(!lastRegistration.isEmpty(), "No PEP operation was registered");
    lastRegistration.forEach(update);
  }

  private record AttemptGroup(String traceId, String method, String target, int status,
      Set<String> parents) {
  }

  private static final class OperationEvidence {
    private final Operation operation;
    private String route;
    private String serverAddress;
    private String productId;
    private String productVersion;
    private ProfessionExpectation profession;
    private Long earliest;
    private Long latest;

    private OperationEvidence(Operation operation) {
      this.operation = operation;
    }

    private Observation observation() {
      require(route != null && serverAddress != null && profession != null && earliest != null
          && latest != null, "Operation has incomplete independent parameter expectations");
      require(operation.status() == 401 || productId != null && productVersion != null,
          "Authenticated operation has no independently observed product context");
      return new Observation(operation, new ExpectedAttributes(route, serverAddress, productId,
          productVersion, profession, earliest, latest));
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  /**
   * Restores the test collector even after an assertion or setup failure.
   */
  @After(value = "@raw_otlp_capture", order = 100)
  public void restoreCapture() {
    if (capture != null) {
      capture.close();
      capture = null;
    }
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow();
  }
}
