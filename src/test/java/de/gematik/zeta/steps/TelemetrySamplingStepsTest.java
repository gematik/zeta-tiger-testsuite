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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks that local overrides, unused components and stale configuration cannot distort default sampling evidence. */
class TelemetrySamplingStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PIPELINE = "traces/ti_sim";
  private static final String POINTER = "/chart/values/zeta-guard/telemetry-gateway/config";
  private static final String NO_SAMPLING = """
      {"receivers":{"otlp":{}},"processors":{"batch":{},"tail_sampling":{"policies":[]}},
       "service":{"pipelines":{"traces/ti_sim":{"receivers":["otlp"],"processors":["batch"]}}}}
      """;

  @ParameterizedTest
  @ValueSource(strings = {"zeta-testenv-local", "zeta-testenv-staging", "custom-guard-release"})
  void samplingEvidenceUsesDeployedCollectorRelease(String releaseName) throws IOException {
    var service = mock(ZetaDeploymentModificationService.class);
    var selector = "app.kubernetes.io/name=telemetry-gateway,component=standalone-collector";
    var pods = JSON.createObjectNode().set("items", collectorPods(releaseName, releaseName));
    when(service.executeKubectlCommand(false, "-n", "staging", "get", "pods", "-l", selector, "-o", "json"))
        .thenReturn(new CommandResult(List.of(), 0, JSON.writeValueAsString(pods), ""));
    var release = JSON.readTree(release(JSON.readTree(NO_SAMPLING), JSON.createObjectNode()));
    var deployed = JSON.createObjectNode();
    deployed.set("chart", release.path("chart"));
    deployed.put("name", releaseName);
    deployed.putObject("info").put("status", "deployed");
    var secrets = JSON.createObjectNode();
    secrets.putArray("items").addObject().putObject("data")
        .put("release", encodeRelease(JSON.writeValueAsString(deployed)));
    when(service.executeKubectlCommand(false, "-n", "staging", "get", "secret", "-l",
        "owner=helm,name=" + releaseName + ",status=deployed", "-o", "json"))
        .thenReturn(new CommandResult(List.of(), 0, JSON.writeValueAsString(secrets), ""));
    when(service.executeKubectlCommand(false, "-n", "staging", "get", "pods", "-l",
        selector + ",app.kubernetes.io/instance=" + releaseName, "-o", "json"))
        .thenReturn(new CommandResult(List.of(), 0, "{\"items\":[]}", ""));
    try (var deployment = mockStatic(ZetaDeploymentConfiguration.class);
        var configuration = mockStatic(TigerGlobalConfiguration.class);
        var report = mockStatic(SerenityReportUtils.class)) {
      deployment.when(ZetaDeploymentConfiguration::getServiceInstance).thenReturn(service);
      configuration.when(() -> TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace"))
          .thenReturn(Optional.of("staging"));
      configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders("staging")).thenReturn("staging");
      var steps = new TelemetrySamplingSteps();
      assertDoesNotThrow(() -> steps.verifyDefaultSamplingDisabled(POINTER, PIPELINE));
      assertThrows(AssertionError.class, steps::verifyRuntimeSamplingDisabled);
      verify(service).executeKubectlCommand(false, "-n", "staging", "get", "secret", "-l",
          "owner=helm,name=" + releaseName + ",status=deployed", "-o", "json");
      verify(service).executeKubectlCommand(false, "-n", "staging", "get", "pods", "-l",
          selector + ",app.kubernetes.io/instance=" + releaseName, "-o", "json");
      configuration.verify(() -> TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.helmReleaseName"),
          org.mockito.Mockito.never());
    }
  }

  @Test
  void replicasResolveToOneRelease() {
    assertEquals("staging", TelemetrySamplingSteps.collectorReleaseName(collectorPods("staging", "staging")));
  }

  @Test
  void collectorsFromDifferentReleasesAreRejected() {
    var error = assertThrows(AssertionError.class,
        () -> TelemetrySamplingSteps.collectorReleaseName(collectorPods("first", "second")));
    assertEquals("Telemetry collector pods belong to multiple Helm releases: [first, second]", error.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "{}", "null", "[{}]",
      "[{\"metadata\":{\"labels\":{\"app.kubernetes.io/instance\":\"\"}}}]",
      "[{\"metadata\":{\"labels\":{\"app.kubernetes.io/instance\":\" \"}}}]"})
  void missingCollectorOrReleaseCannotPass(String pods) {
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.collectorReleaseName(JSON.readTree(pods)));
  }

  @Test
  void inactiveSamplingDefinitionDoesNotEnableSampling() {
    assertEquals(List.of("batch"), TelemetrySamplingSteps.requireSamplingDisabled(JSON.readTree(NO_SAMPLING), PIPELINE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"tail_sampling", "tail_sampling/custom", "probabilistic_sampler"})
  void activeSamplingIsRejectedEvenWithOneHundredPercentPolicy(String processor) {
    var collector = activeSampler(processor);
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.requireSamplingDisabled(collector, PIPELINE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"traces/missing", "metrics/ti_sim", "traces"})
  void absentOrWrongSignalPipelineCannotPass(String pipeline) {
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.requireSamplingDisabled(JSON.readTree(NO_SAMPLING), pipeline));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"receivers\":[\"otlp\"]}",
      "{\"receivers\":[],\"processors\":[]}",
      "{\"receivers\":[\"routing/gematik\"],\"processors\":[\"batch\"]}",
      "{\"receivers\":[\"otlp/undeclared\"],\"processors\":[\"batch\"]}",
      "{\"receivers\":[\"otlp\"],\"processors\":[\"undeclared\"]}"})
  void incompleteOrIndirectPipelineCannotPass(String pipeline) {
    var collector = JSON.createObjectNode();
    collector.putObject("receivers").putObject("otlp");
    collector.putObject("processors").putObject("batch");
    collector.putObject("service").putObject("pipelines").set(PIPELINE, JSON.readTree(pipeline));
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.requireSamplingDisabled(collector, PIPELINE));
  }

  @Test
  void localDisabledOverrideCannotHideEnabledShippedDefault() throws IOException {
    var decoded = TelemetrySamplingSteps.decodeRelease(encodeRelease(release(activeSampler("tail_sampling"), JSON.readTree(NO_SAMPLING))));
    assertThrows(AssertionError.class,
        () -> TelemetrySamplingSteps.requireSamplingDisabled(TelemetrySamplingSteps.shippedDefaults(decoded, POINTER), PIPELINE));
  }

  @Test
  void localEnabledOverrideDoesNotChangeShippedDefaultEvidence() throws IOException {
    var decoded = TelemetrySamplingSteps.decodeRelease(encodeRelease(release(JSON.readTree(NO_SAMPLING), activeSampler("tail_sampling"))));
    assertDoesNotThrow(
        () -> TelemetrySamplingSteps.requireSamplingDisabled(TelemetrySamplingSteps.shippedDefaults(decoded, POINTER), PIPELINE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/config/zeta-guard/telemetry-gateway/config", "/manifest", "/chart/values"})
  void defaultSourceCannotPointAtInstallationOverrides(String pointer) {
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.shippedDefaults(JSON.createObjectNode(), pointer));
  }

  @Test
  void malformedReleaseNeverLeaksSecretParserContent() throws IOException {
    var secret = "credential-sentinel-that-must-not-be-reported";
    var error = assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.decodeRelease(encodeRelease("{\"" + secret + "\":")));
    assertFalse(error.toString().contains(secret));
    assertNull(error.getCause());
    var outerError = assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.parseEvidenceJson("{\"" + secret + "\":"));
    assertFalse(outerError.toString().contains(secret));
    assertNull(outerError.getCause());
  }

  @Test
  void configChangedAfterStartupCannotRepresentRunningConfiguration() {
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.requireConfigPredatesProcess(
        metadata("2026-09-19T06:00:00Z", "2026-09-19T08:00:00Z"), Instant.parse("2026-09-19T07:00:00Z")));
  }

  @Test
  void recreatedConfigCannotRepresentRunningConfiguration() {
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.requireConfigPredatesProcess(
        metadata("2026-09-19T08:00:00Z", "2026-09-19T06:00:00Z"), Instant.parse("2026-09-19T07:00:00Z")));
  }

  @Test
  void absentModificationHistoryCannotPass() {
    var metadata = JSON.createObjectNode().put("creationTimestamp", "2026-09-19T06:00:00Z");
    assertThrows(AssertionError.class, () -> TelemetrySamplingSteps.requireConfigPredatesProcess(metadata, Instant.parse("2026-09-19T07:00:00Z")));
  }

  @Test
  void unchangedMountedConfigurationPredatingStartupIsAccepted() {
    assertDoesNotThrow(() -> TelemetrySamplingSteps.requireConfigPredatesProcess(
        metadata("2026-09-19T06:00:00Z", "2026-09-19T06:30:00Z"), Instant.parse("2026-09-19T07:00:00Z")));
  }

  private JsonNode activeSampler(String name) {
    var collector = JSON.createObjectNode();
    collector.putObject("receivers").putObject("otlp");
    collector.putObject("processors").putObject(name).put("sampling_percentage", 100);
    var pipeline = collector.putObject("service").putObject("pipelines").putObject(PIPELINE);
    pipeline.putArray("receivers").add("otlp");
    pipeline.putArray("processors").add(name);
    return collector;
  }

  private JsonNode collectorPods(String... releases) {
    var pods = JSON.createArrayNode();
    for (var release : releases) {
      pods.addObject().putObject("metadata").putObject("labels").put("app.kubernetes.io/instance", release);
    }
    return pods;
  }

  private String release(JsonNode defaults, JsonNode overrides) {
    var release = JSON.createObjectNode();
    release.putObject("chart").putObject("values").putObject("zeta-guard").putObject("telemetry-gateway").set("config", defaults);
    release.putObject("config").putObject("zeta-guard").putObject("telemetry-gateway").set("config", overrides);
    return JSON.writeValueAsString(release);
  }

  private String encodeRelease(String json) throws IOException {
    var buffer = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(buffer)) {
      gzip.write(json.getBytes(StandardCharsets.UTF_8));
    }
    return Base64.getEncoder().encodeToString(Base64.getEncoder().encode(buffer.toByteArray()));
  }

  private JsonNode metadata(String created, String changed) {
    var metadata = JSON.createObjectNode().put("creationTimestamp", created);
    metadata.putArray("managedFields").addObject().put("time", changed);
    return metadata;
  }
}
