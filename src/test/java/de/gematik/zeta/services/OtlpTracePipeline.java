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

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Resolves declared OTLP destinations of one configured trace pipeline. */
public final class OtlpTracePipeline {
  /** Prevents construction of the configuration utility. */
  private OtlpTracePipeline() {
  }

  /**
   * Resolves only OTLP exporters referenced by the selected trace pipeline.
   *
   * @param collector parsed OpenTelemetry Collector configuration
   * @param pipelineName exact trace pipeline identifier
   * @return nonempty list of declared OTLP exporters with nonempty endpoints
   * @throws AssertionError if the pipeline or a referenced OTLP destination is missing
   */
  public static List<String> exporters(JsonNode collector, String pipelineName) {
    if (collector == null || !collector.isObject()) {
      throw new AssertionError("Missing OpenTelemetry Collector configuration");
    }
    if (pipelineName == null || !pipelineName.matches("traces(?:/[^/]+)?")) {
      throw new AssertionError("Expected one configured trace pipeline: " + pipelineName);
    }
    var references = collector.path("service").path("pipelines").path(pipelineName).path("exporters");
    if (!references.isArray() || references.isEmpty()) {
      throw new AssertionError("Trace pipeline '" + pipelineName + "' has no exporters");
    }
    var exporters = new ArrayList<String>();
    for (var reference : references) {
      if (!reference.isString() || reference.asString().isBlank()) {
        throw new AssertionError("Invalid exporter reference in trace pipeline '" + pipelineName + "'");
      }
      var name = reference.asString();
      if (!name.matches("otlp(?:http|_grpc|_http)?(?:/[^/]+)?")) {
        continue;
      }
      var declaration = collector.path("exporters").path(name);
      if (!declaration.isObject() || !declaration.path("endpoint").isString()
          || declaration.path("endpoint").asString().isBlank()) {
        throw new AssertionError("Missing OTLP exporter endpoint: " + name);
      }
      exporters.add(name);
    }
    if (exporters.isEmpty()) {
      throw new AssertionError("Trace pipeline '" + pipelineName + "' has no declared OTLP exporter");
    }
    return List.copyOf(exporters);
  }
}
