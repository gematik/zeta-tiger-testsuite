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

import de.gematik.zeta.services.TelemetryCollectorDiscovery.CaptureTarget;
import de.gematik.zeta.services.model.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fault-injection checks for collector restoration and integrity of the raw capture fixture.
 */
class RawOtlpCaptureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OWNER = "tests.zeta.gematik.de/raw-otlp-capture";

  @Test
  void normalCloseRestoresConfigPreservesOtherAnnotationsAndRemovesTemporaryFiles() {
    var cluster = new Cluster();
    final var original = cluster.relay();
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      assertTrue(cluster.deployment.at("/spec/template/metadata/annotations").has(OWNER));
      assertFalse(original.equals(cluster.relay()));
    }
    assertEquals(original, cluster.relay());
    assertEquals(
        "unchanged",
        cluster.deployment.at("/spec/template/metadata/annotations/existing").asString());
    assertFalse(cluster.deployment.at("/spec/template/metadata/annotations").has(OWNER));
    assertTrue(cluster.temporaryFiles.stream().noneMatch(Files::exists));
  }

  @Test
  void failedRolloutStillAllowsCompleteRestoration() {
    var cluster = new Cluster();
    final var original = cluster.relay();
    var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"));
    cluster.failNextRollout = true;
    assertThrows(AssertionError.class, capture::start);
    capture.close();
    assertEquals(original, cluster.relay());
    assertFalse(cluster.deployment.at("/spec/template/metadata/annotations").has(OWNER));
  }

  @Test
  void concurrentConfigChangeIsNotOverwrittenByCleanup() {
    var cluster = new Cluster();
    var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"));
    capture.start();
    ((ObjectNode) cluster.configMap.path("data")).put("relay", "concurrent operator configuration");
    assertThrows(AssertionError.class, capture::close);
    assertEquals("concurrent operator configuration", cluster.relay());
  }

  @Test
  void anotherCaptureOwnerIsNotModified() {
    var cluster = new Cluster();
    ((ObjectNode) cluster.deployment.at("/spec/template/metadata/annotations"))
        .put(OWNER, "someone-else");
    final var original = cluster.relay();
    var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"));
    assertThrows(AssertionError.class, capture::start);
    capture.close();
    assertEquals(original, cluster.relay());
    assertEquals(
        "someone-else",
        cluster.deployment.at("/spec/template/metadata/annotations").path(OWNER).asString());
  }

  @Test
  void changedCaptureOwnerStopsCleanupBeforeAnyConfigurationWrite() {
    var cluster = new Cluster();
    var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"));
    capture.start();
    final var installed = cluster.relay();
    ((ObjectNode) cluster.deployment.at("/spec/template/metadata/annotations"))
        .put(OWNER, "someone-else");
    assertThrows(AssertionError.class, capture::close);
    assertEquals(installed, cluster.relay());
    assertEquals(
        "someone-else",
        cluster.deployment.at("/spec/template/metadata/annotations").path(OWNER).asString());
  }

  @Test
  void restartedCollectorCannotProvideCompleteCaptureEvidence() {
    var cluster = new Cluster();
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      cluster.restartCount++;
      assertThrows(AssertionError.class, capture::batches);
    }
  }

  @Test
  void malformedRawDataFailsWithoutLeakingParserContext() {
    var cluster = new Cluster();
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      cluster.logs += "{\"resourceSpans\": sensitive-sentinel";
      var error = assertThrows(AssertionError.class, capture::batches);
      assertFalse(error.toString().contains("sensitive-sentinel"));
      assertNull(error.getCause());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "{\"", "{\"resou", "{\"resourceSpans\":["})
  void incompleteLastRecordMustFinishWithinTheUnchangedFullWindow(String prefix) throws Exception {
    var cluster = new Cluster();
    var clock = new AtomicLong();
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      cluster.logs += prefix;
      var operations =
          List.of(
              new RawOtlpTraceAssertions.Operation(
                  "a".repeat(32), "b".repeat(16), "GET", "/pep/test", 200));
      assertFalse(
          capture.observeServiceNames(
              operations,
              clock::get,
              millis -> {
                if (clock.get() == 0) {
                  cluster.logs += "{\"resourceSpans\":[]}".substring(prefix.length()) + "\n";
                }
                clock.addAndGet(java.time.Duration.ofMillis(millis).toNanos());
              }));
      assertEquals(java.time.Duration.ofSeconds(30).toNanos(), clock.get());
      assertEquals(1, capture.batches().size());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "{\"", "{\"resou", "{\"resourceSpans\":[{"})
  void incompleteFinalRecordCannotBeIgnoredOrRemoved(String prefix) {
    var cluster = new Cluster();
    var clock = new AtomicLong();
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      cluster.logs += matchingExport() + "\n" + prefix;
      var operations =
          List.of(
              new RawOtlpTraceAssertions.Operation(
                  "a".repeat(32), "b".repeat(16), "GET", "/pep/test", 200));
      assertThrows(
          AssertionError.class,
          () ->
              capture.observeServiceNames(
                  operations,
                  clock::get,
                  millis -> clock.addAndGet(java.time.Duration.ofMillis(millis).toNanos())));
      assertEquals(java.time.Duration.ofSeconds(30).toNanos(), clock.get());
      cluster.logs = "collector started\n{\"resourceSpans\":[]}\n";
      assertThrows(AssertionError.class, capture::batches);
    }
  }

  @Test
  void completeCollectorDiagnosticsRemainAllowed() {
    var cluster = new Cluster();
    cluster.logs += "{\"level\":\"info\",\"message\":\"resourceSpans\"}\n";
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      cluster.logs += "{\"level\":\"info\",\"message\":\"export completed\"}\n";
      assertTrue(capture.batches().isEmpty());
    }
  }

  @Test
  void malformedCompletedOrConcatenatedRecordsAreNeverRetried() {
    for (var suffix :
        List.of(
            "{\"resourceSpans\":[]} {\"resourceSpans\":[]}",
            "{\"resourceSpans\":[\ncollector log",
            "{\"resou\ncollector log",
            "{\"resourceSpans\": bad}")) {
      var cluster = new Cluster();
      try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
        capture.start();
        cluster.logs += suffix;
        assertThrows(
            AssertionError.class,
            () ->
                capture.observeServiceNames(
                    List.of(
                        new RawOtlpTraceAssertions.Operation(
                            "a".repeat(32), "b".repeat(16), "GET", "/pep/test", 200)),
                    () -> 0,
                    millis -> {
                      throw new IllegalStateException(
                          "Malformed completed data must fail before retry");
                    }));
      }
    }
  }

  @Test
  void rawJsonFieldsAreNotTransformedDuringCollection() {
    var cluster = new Cluster();
    cluster.logs = "{\"resourceSpans\":[{\"unchanged\":\"1789805946890000000\"}]}\n";
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      assertEquals(List.of(JSON.readTree(cluster.logs)), capture.batches());
    }
  }

  @Test
  void lostLogPrefixCannotHidePreviouslyExportedSpans() {
    var cluster = new Cluster();
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      capture.start();
      cluster.logs += "{\"resourceSpans\":[]}\n";
      assertEquals(1, capture.batches().size());
      cluster.logs = "{\"resourceSpans\":[]}\n";
      assertThrows(AssertionError.class, capture::batches);
    }
  }

  @Test
  void emptyStartupLogsCannotEstablishACompleteCapture() {
    var cluster = new Cluster();
    cluster.logs = "";
    final var original = cluster.relay();
    try (var capture = new RawOtlpCapture(cluster, "test", new CaptureTarget("collector", "collector-config", "otlp/gematik"))) {
      assertThrows(AssertionError.class, capture::start);
    }
    assertEquals(original, cluster.relay());
  }

  private static String matchingExport() {
    return JSON.writeValueAsString(
        JSON.readTree(
            """
            {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name",
             "value":{"stringValue":"ZETA Guard PEP HTTP proxy"}}]},"scopeSpans":[{
             "scope":{"name":"nginx"},"spans":[{"traceId":"%s","parentSpanId":"%s",
             "spanId":"%s","kind":2,"attributes":[
             {"key":"http.method","value":{"stringValue":"GET"}},
             {"key":"http.target","value":{"stringValue":"/pep/test"}},
             {"key":"http.response.status_code","value":{"intValue":"200"}}]}]}]}]}
            """
                .formatted("a".repeat(32), "b".repeat(16), "c".repeat(16))));
  }

  private static final class Cluster extends ZetaDeploymentModificationService {
    private ObjectNode configMap;
    private final ObjectNode deployment;
    private final List<Path> temporaryFiles = new ArrayList<>();
    private boolean failNextRollout;
    private int restartCount;
    private String logs = "collector started\n";

    private Cluster() {
      super(1, 1);
      configMap = JSON.createObjectNode();
      configMap.putObject("metadata").put("resourceVersion", "1");
      configMap
          .putObject("data")
          .put(
              "relay",
              """
              {"receivers":{"otlp/gematik":{}},"exporters":{"otlp/jaeger":{}},
               "service":{"pipelines":{"traces":{"receivers":["otlp/gematik"],"exporters":["otlp/jaeger"]}}}}
              """);
      deployment =
          (ObjectNode)
              JSON.readTree(
                  """
                  {"metadata":{"resourceVersion":"1"},"spec":{"replicas":1,"template":{
                   "metadata":{"annotations":{"existing":"unchanged"}},"spec":{
                   "containers":[{"args":["--config=/conf/relay.yaml"]}],
                   "volumes":[{"configMap":{"name":"collector-config"}}]}}}}
                  """);
    }

    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) {
      var command = arguments.subList(2, arguments.size());
      String stdout = "";
      try {
        switch (command.getFirst()) {
          case "get" ->
              stdout =
                  JSON.writeValueAsString(
                      switch (command.get(1)) {
                        case "deployment" -> {
                          assertEquals("collector", command.get(2));
                          yield deployment;
                        }
                        case "configmap" -> {
                          assertEquals("collector-config", command.get(2));
                          yield configMap;
                        }
                        case "pod" -> pod();
                        case "pods" ->
                            JSON.createObjectNode().set("items", JSON.createArrayNode().add(pod()));
                        default -> throw new AssertionError("Unexpected test read");
                      });
          case "replace" -> configMap = (ObjectNode) readTemporary(command);
          case "patch" -> {
            var patch = readTemporary(command);
            assertEquals(deployment.at("/metadata/resourceVersion"), patch.get(0).path("value"));
            ((ObjectNode) deployment.at("/spec/template/metadata"))
                .set("annotations", patch.get(1).path("value"));
          }
          case "rollout" -> {
            if (failNextRollout) {
              failNextRollout = false;
              return new CommandResult(arguments, 1, "", "simulated rollout failure");
            }
          }
          case "logs" -> stdout = logs;
          default -> throw new AssertionError("Unexpected test operation");
        }
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      return new CommandResult(arguments, 0, stdout, "");
    }

    private JsonNode readTemporary(List<String> command) throws IOException {
      var path = Path.of(command.getLast());
      temporaryFiles.add(path);
      return JSON.readTree(Files.readString(path));
    }

    private String relay() {
      return configMap.path("data").path("relay").asString();
    }

    private JsonNode pod() {
      var pod = JSON.createObjectNode();
      pod.putObject("metadata")
          .put("name", "collector-pod")
          .set("annotations", deployment.at("/spec/template/metadata/annotations").deepCopy());
      pod.putObject("status")
          .putArray("containerStatuses")
          .addObject()
          .put("ready", true)
          .put("restartCount", restartCount);
      return pod;
    }
  }
}
