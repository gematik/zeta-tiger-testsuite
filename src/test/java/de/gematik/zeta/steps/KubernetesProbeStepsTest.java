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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.gematik.zeta.services.KubernetesRateLimitConfigurationEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for OpenTelemetry Collector relay configuration matching.
 */
class KubernetesProbeStepsTest {

  /**
   * Verifies that a standard OTLP gRPC exporter referenced by traces is detected with a batch processor.
   */
  @Test
  void parseCollectorRelayConfigDetectsTraceBatchAndOtlpGrpcExporter() {
    var relayConfig = """
        receivers:
          otlp:
            protocols:
              grpc: {}
        processors:
          batch: {}
        exporters:
          otlp:
            endpoint: otel-collector:4317
        service:
          pipelines:
            traces:
              receivers:
                - otlp
              processors:
                - batch
              exporters:
                - otlp
        """;

    assertThat(KubernetesProbeSteps.traceBatchExportEvidence(relayConfig, "otlp"))
        .containsExactly("traces | BATCH=[batch] | OTLP=[otlp]");
    assertThat(KubernetesProbeSteps.configuredOtlpExporters(relayConfig)).containsExactly("otlp");
    assertThat(KubernetesProbeSteps.activeOtlpForwardingProtocols(relayConfig)).containsExactly("OTLP/gRPC");
    assertThat(KubernetesProbeSteps.pipelinesUsingAnyExporter(relayConfig, List.of("otlp"))).containsExactly("traces");
  }

  /**
   * Verifies that an OTLP HTTP/JSON exporter referenced by traces is detected.
   */
  @Test
  void relayConfigMatchingDetectsOtlpHttpJsonExporter() {
    var relayConfig = """
        exporters:
          debug: {}
          otlphttp/main:
            endpoint: https://collector.example.test/v1/traces
            encoding: json
        processors:
          batch/main:
            timeout: 1s
        service:
          pipelines:
            logs:
              exporters:
                - debug
            traces:
              processors:
                - batch/main
              exporters:
                - debug
                - otlphttp/main
        """;

    assertThat(KubernetesProbeSteps.traceBatchExportEvidence(relayConfig, "otlphttp/main"))
        .containsExactly("traces | BATCH=[batch/main] | OTLP=[otlphttp/main]");
    assertThat(KubernetesProbeSteps.configuredOtlpExporters(relayConfig)).containsExactly("otlphttp/main");
    assertThat(KubernetesProbeSteps.activeOtlpForwardingProtocols(relayConfig)).containsExactly("OTLP/HTTP JSON");
    assertThat(KubernetesProbeSteps.pipelinesUsingAnyExporter(relayConfig, List.of("otlphttp/main")))
        .containsExactly("traces");
  }

  /**
   * Verifies that named service pipelines referencing OTLP gRPC exporters are detected.
   */
  @Test
  void relayConfigMatchingDetectsOtlpGrpcExporterInNamedPipelines() {
    var relayConfig = """
        exporters:
          debug: {}
          otlp_grpc/test-monitoring-service:
            endpoint: test-monitoring-collector:4317
        service:
          pipelines:
            logs/dienst_hersteller:
              exporters:
                - debug
                - otlp_grpc/test-monitoring-service
            metrics/dienst_hersteller:
              exporters:
                - debug
            traces/dienst_hersteller:
              exporters:
                - debug
                - otlp_grpc/test-monitoring-service
        """;

    assertThat(KubernetesProbeSteps.activeOtlpForwardingProtocols(relayConfig)).containsExactly("OTLP/gRPC");
    assertThat(KubernetesProbeSteps.pipelinesUsingAnyExporter(
        relayConfig, List.of("otlp_grpc/test-monitoring-service")))
        .containsExactly("logs", "traces");
  }

  /**
   * Verifies that OTLP HTTP exporters without JSON encoding do not satisfy the allowed HTTP/JSON protocol check.
   */
  @Test
  void relayConfigMatchingDoesNotReportOtlpHttpWithoutJsonEncodingAsAllowedProtocol() {
    var relayConfig = """
        exporters:
          otlp_http/test-monitoring-service:
            endpoint: http://test-monitoring-collector-local:4318
        service:
          pipelines:
            logs:
              exporters:
                - otlp_http/test-monitoring-service
        """;

    assertThat(KubernetesProbeSteps.configuredOtlpExporters(relayConfig))
        .containsExactly("otlp_http/test-monitoring-service");
    assertThat(KubernetesProbeSteps.activeOtlpForwardingProtocols(relayConfig)).isEmpty();
    assertThat(KubernetesProbeSteps.pipelinesUsingAnyExporter(
        relayConfig, List.of("otlp_http/test-monitoring-service"))).containsExactly("logs");
  }

  /**
   * Verifies that configured OTLP exporters do not count unless a service pipeline references them.
   */
  @Test
  void relayConfigMatchingDoesNotReportUnusedOtlpExporter() {
    var relayConfig = """
        exporters:
          otlp/unused:
            endpoint: otel-collector:4317
          debug: {}
        service:
          pipelines:
            traces:
              processors:
                - memory_limiter
              exporters:
                - debug
        """;

    assertThatThrownBy(() -> KubernetesProbeSteps.traceBatchExportEvidence(relayConfig, "otlp/unused"))
        .isInstanceOf(AssertionError.class);
    assertThat(KubernetesProbeSteps.configuredOtlpExporters(relayConfig)).containsExactly("otlp/unused");
    assertThat(KubernetesProbeSteps.activeOtlpForwardingProtocols(relayConfig)).isEmpty();
    assertThat(KubernetesProbeSteps.pipelinesUsingAnyExporter(relayConfig, List.of("otlp/unused"))).isEmpty();
  }

  /**
   * Rejects configurations without an exporting trace pipeline.
   */
  @Test
  void relayConfigMatchingReportsMissingTracePipeline() {
    assertThatThrownBy(() -> KubernetesProbeSteps.traceBatchExportEvidence("service:\n  pipelines: {}\n", "otlp/main"))
        .isInstanceOf(AssertionError.class);
  }

  /** Rejects a batch processor on another pipeline and undeclared component references. */
  @Test
  void traceBatchExportRequiresCorrelatedDeclaredComponents() {
    var config = """
        processors: {batch/main: {}}
        exporters: {otlp/main: {endpoint: collector:4317}}
        service:
          pipelines:
            traces/main: {processors: [batch/main], exporters: [otlp/main]}
        """;
    assertThat(KubernetesProbeSteps.traceBatchExportEvidence(config, "otlp/main"))
        .containsExactly("traces/main | BATCH=[batch/main] | OTLP=[otlp/main]");
    assertThat(KubernetesProbeSteps.traceBatchExportEvidence(config.replace("batch/main: {}", "batch/main: null"), "otlp/main"))
        .containsExactly("traces/main | BATCH=[batch/main] | OTLP=[otlp/main]");
    for (var broken : List.of(
        config.replace("otlp/main", "otlphttp_invalid/main"),
        config.replace("processors: [batch/main]", "processors: [batch/missing]"),
        config.replace("exporters: [otlp/main]", "exporters: [otlp/missing]"),
        config.replace("traces/main:", "logs/main:"),
        config.replace("{endpoint: collector:4317}", "{}"),
        config.replace("processors: [batch/main], exporters: [otlp/main]",
            "exporters: [otlp/main]}\n    logs: {processors: [batch/main]"),
        config + "    traces/unbatched: {exporters: [otlp/main]}\n")) {
      assertThatThrownBy(() -> KubernetesProbeSteps.traceBatchExportEvidence(broken, "otlp/main"))
          .isInstanceOf(AssertionError.class);
    }
  }

  /** Limits batching requirements to pipelines exporting to the selected telemetry destination. */
  @Test
  void traceBatchExportIgnoresUnbatchedDiagnosticDestination() {
    var config = """
        processors: {batch/main: {}}
        exporters:
          otlp/gematik: {endpoint: gematik.example.test:4317}
          otlp/diagnostics: {endpoint: local-collector:4317}
        service:
          pipelines:
            traces/gematik: {processors: [batch/main], exporters: [otlp/gematik]}
            traces/diagnostics: {exporters: [otlp/diagnostics]}
        """;
    assertThat(KubernetesProbeSteps.traceBatchExportEvidence(config, "otlp/gematik"))
        .containsExactly("traces/gematik | BATCH=[batch/main] | OTLP=[otlp/gematik]");
    assertThatThrownBy(() -> KubernetesProbeSteps.traceBatchExportEvidence(config, "otlp/diagnostics"))
        .isInstanceOf(AssertionError.class).hasMessageContaining("has no declared batch processor");
    assertThatThrownBy(() -> KubernetesProbeSteps.traceBatchExportEvidence(
        config.replace("traces/gematik:", "logs/gematik:"), "otlp/gematik"))
        .isInstanceOf(AssertionError.class).hasMessageContaining("No trace pipeline");
    assertThatThrownBy(() -> KubernetesProbeSteps.traceBatchExportEvidence(config, "otlp/missing"))
        .isInstanceOf(AssertionError.class).hasMessageContaining("Missing OTLP exporter endpoint");
  }

  /**
   * Resolves renamed OTLP exporters from the selected pipeline without using diagnostic destinations.
   *
   * @param exporter exporter identifier referenced by the gematik pipeline
   */
  @ParameterizedTest
  @ValueSource(strings = {"otlp_http/tms_ti_sim", "otlp_http/tms_gematik", "otlphttp/renamed", "otlp/grpc"})
  void traceBatchExportResolvesPipelineExporter(String exporter) {
    var config = """
        processors: {batch/main: {}}
        exporters:
          %s: {endpoint: gematik.example.test:4317}
          otlp/diagnostics: {endpoint: local-collector:4317}
        service:
          pipelines:
            traces/gematik: {processors: [batch/main], exporters: [%s]}
            traces/diagnostics: {exporters: [otlp/diagnostics]}
        """.formatted(exporter, exporter);
    assertThat(KubernetesProbeSteps.tracePipelineBatchExportEvidence(config, "traces/gematik"))
        .containsExactly("traces/gematik | BATCH=[batch/main] | OTLP=[" + exporter + "]");
  }

  /** Keeps the selected destination and all of its trace pipelines bound to declared batching. */
  @Test
  void tracePipelineSelectionRejectsMissingDestinationsAndUnbatchedSharedExports() {
    var config = """
        processors: {batch/main: {}}
        exporters: {otlp_http/renamed: {endpoint: gematik.example.test:4318}}
        service:
          pipelines:
            traces/gematik: {processors: [batch/main], exporters: [otlp_http/renamed]}
        """;
    for (var pipeline : List.of("traces/missing", "logs/gematik", "")) {
      assertThatThrownBy(() -> KubernetesProbeSteps.tracePipelineBatchExportEvidence(config, pipeline))
          .isInstanceOf(AssertionError.class);
    }
    for (var broken : List.of(
        config.replace("exporters: [otlp_http/renamed]", "exporters: [otlp_http/missing]"),
        config.replace("endpoint: gematik.example.test:4318", "endpoint: ''"),
        config.replace("processors: [batch/main], ", ""),
        config.replace("exporters: [otlp_http/renamed]", "exporters: []"),
        config.replace("exporters: [otlp_http/renamed]", "exporters: otlp_http/renamed"),
        config + "    traces/shared: {exporters: [otlp_http/renamed]}\n")) {
      assertThatThrownBy(() -> KubernetesProbeSteps.tracePipelineBatchExportEvidence(broken, "traces/gematik"))
          .isInstanceOf(AssertionError.class);
    }
  }

  /**
   * Verifies that OPA bundle polling evidence reports the expected 60 second interval.
   */
  @Test
  void opaBundlePollingEvidenceAcceptsExactSixtySecondPollingInterval() {
    var opaConfig = """
        bundles:
          authz:
            service: registry
            resource: test/policy-bundle:latest
            polling:
              min_delay_seconds: 60
              max_delay_seconds: 60
        """;

    assertThat(KubernetesProbeSteps.opaBundlePollingEvidence("opa-config", opaConfig, 60))
        .isEqualTo("CONFIGMAP=opa-config | bundles.authz.polling.min_delay_seconds=60"
            + " | bundles.authz.polling.max_delay_seconds=60");
  }

  /**
   * Verifies that OPA bundle polling evidence uses the provided expected interval.
   */
  @Test
  void opaBundlePollingEvidenceAcceptsConfiguredPollingInterval() {
    var opaConfig = """
        bundles:
          authz:
            polling:
              min_delay_seconds: 120
              max_delay_seconds: 120
        """;

    assertThat(KubernetesProbeSteps.opaBundlePollingEvidence("opa-config", opaConfig, 120))
        .contains("min_delay_seconds=120", "max_delay_seconds=120");
  }

  /**
   * Verifies that missing bundle polling configuration fails as missing conformance evidence.
   */
  @Test
  void opaBundlePollingEvidenceRejectsMissingPollingConfiguration() {
    var opaConfig = """
        decision_logs:
          console: true
        services:
          registry:
            type: oci
        """;

    assertThatThrownBy(() -> KubernetesProbeSteps.opaBundlePollingEvidence("opa-config", opaConfig, 60))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Missing required OPA config field 'bundles.authz.polling.min_delay_seconds'");
  }

  /**
   * Verifies that bundle polling values other than 60 seconds fail the assertion.
   */
  @Test
  void opaBundlePollingEvidenceRejectsWrongPollingInterval() {
    var opaConfig = """
        bundles:
          authz:
            polling:
              min_delay_seconds: 60
              max_delay_seconds: 61
        """;

    assertThatThrownBy(() -> KubernetesProbeSteps.opaBundlePollingEvidence("opa-config", opaConfig, 60))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("min_delay_seconds=60 and max_delay_seconds=61, expected both to be 60");
  }

  /**
   * Verifies that non-numeric bundle polling values fail before they can be accepted as evidence.
   */
  @Test
  void opaBundlePollingEvidenceRejectsNonNumericPollingInterval() {
    var opaConfig = """
        bundles:
          authz:
            polling:
              min_delay_seconds: sixty
              max_delay_seconds: 60
        """;

    assertThatThrownBy(() -> KubernetesProbeSteps.opaBundlePollingEvidence("opa-config", opaConfig, 60))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("must be an integer but was 'sixty'");
  }

  /**
   * Verifies that malformed OPA YAML fails with a parsing error.
   */
  @Test
  void opaBundlePollingEvidenceRejectsMalformedYaml() {
    var opaConfig = "bundles:\n  authz: [:\n";

    assertThatThrownBy(() -> KubernetesProbeSteps.opaBundlePollingEvidence("opa-config", opaConfig, 60))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Could not parse opa.yaml from OPA ConfigMap 'opa-config'");
  }

  /**
   * Verifies that the literal dotted ConfigMap key opa.yaml is extracted from ConfigMap JSON.
   */
  @Test
  void extractOpaConfigYamlReadsLiteralDottedDataKey() {
    var configMapJson = """
        {
          "apiVersion": "v1",
          "kind": "ConfigMap",
          "data": {
            "opa.yaml": "bundles:\\n  authz:\\n    polling:\\n      min_delay_seconds: 60\\n      max_delay_seconds: 60\\n"
          }
        }
        """;

    assertThat(KubernetesProbeSteps.extractOpaConfigYaml("opa-config", configMapJson))
        .contains("bundles:\n  authz:\n")
        .contains("min_delay_seconds: 60");
  }

  /**
   * Verifies that missing opa.yaml keys are reported as missing ConfigMap data.
   */
  @Test
  void extractOpaConfigYamlRejectsMissingDataKey() {
    var configMapJson = """
        {
          "apiVersion": "v1",
          "kind": "ConfigMap",
          "data": {
            "other.yaml": "bundles: {}\\n"
          }
        }
        """;

    assertThatThrownBy(() -> KubernetesProbeSteps.extractOpaConfigYaml("opa-config", configMapJson))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("contains no data key 'opa.yaml'");
  }

  /**
   * Verifies that rate-limit configuration evidence is detected per endpoint in Kubernetes resources.
   */
  @Test
  void rateLimitConfigurationEvidenceMatchesEndpointAndConfiguration() {
    var resourcesJson = """
        {
          "items": [
            {
              "kind": "ConfigMap",
              "metadata": { "name": "pep-test-nginx-conf" },
              "data": {
                "nginx.conf": "location /.well-known/oauth-protected-resource { limit_req zone=pep; }"
              }
            },
            {
              "kind": "Ingress",
              "metadata": {
                "name": "authserver",
                "annotations": {
                  "nginx.ingress.kubernetes.io/limit-rps": "10"
                }
              },
              "spec": {
                "rules": [
                  {
                    "http": {
                      "paths": [
                        { "path": "/register" }
                      ]
                    }
                  }
                ]
              }
            }
          ]
        }
        """;

    assertThat(KubernetesRateLimitConfigurationEvidence.find(
        resourcesJson, "/.well-known/oauth-protected-resource", "limit_req"))
        .contains("ENDPOINT=/.well-known/oauth-protected-resource | CONFIG=limit_req | "
            + "RESOURCE=ConfigMap/pep-test-nginx-conf");
    assertThat(KubernetesRateLimitConfigurationEvidence.find(
        resourcesJson, "/register", "nginx.ingress.kubernetes.io/limit-rps"))
        .contains("ENDPOINT=/register | CONFIG=nginx.ingress.kubernetes.io/limit-rps | "
            + "RESOURCE=Ingress/authserver");
  }

  /**
   * Verifies that resources with an endpoint but without the expected config marker do not satisfy the precondition.
   */
  @Test
  void rateLimitConfigurationEvidenceIgnoresEndpointWithoutExpectedConfiguration() {
    var resourcesJson = """
        {
          "items": [
            {
              "kind": "Ingress",
              "metadata": { "name": "authserver" },
              "spec": {
                "rules": [
                  {
                    "http": {
                      "paths": [
                        { "path": "/token" }
                      ]
                    }
                  }
                ]
              }
            }
          ]
        }
        """;

    assertThat(KubernetesRateLimitConfigurationEvidence.find(resourcesJson, "/token", "limit_req")).isEmpty();
  }
}
