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
import de.gematik.zeta.services.JaegerQueryService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for diagnostic Jaeger trace collection.
 *
 * <p>These steps query Jaeger through {@link JaegerQueryService}, which uses its own direct
 * {@link java.net.http.HttpClient} instead of the {@code TGR sende GET Anfrage} step. Routing
 * through the local Tiger proxy fails against the Achelos ingress under Tiger 4.4: the proxy's
 * binary-bridge forwarding sends the resolved IP as TLS SNI, which the ingress rejects with
 * {@code unrecognized_name(112)}, and the request hangs because that step has no HTTP timeout.
 * The direct client keeps the hostname as SNI (like {@link
 * de.gematik.zeta.services.PrometheusQueryService}) and carries an explicit timeout.</p>
 *
 * <p>The trace collection is purely diagnostic (outlier post-analysis); it never fails the
 * scenario. A failed Jaeger query is recorded as a soft failure and attached to the report.</p>
 */
@Slf4j
public class JaegerSteps {

  private final JaegerQueryService jaeger;

  /**
   * Creates the step class with a preconfigured Jaeger HTTP client (20s timeout, two retries).
   */
  public JaegerSteps() {
    this.jaeger = new JaegerQueryService(Duration.ofSeconds(20), 2);
  }

  /**
   * Collects Jaeger traces for diagnostic post-analysis and attaches them to the report.
   *
   * <p>The data table holds exactly one row with a required {@code service} column and the
   * optional columns {@code operation}, {@code start}, {@code end}, {@code limit}, {@code tags}
   * and {@code minDuration}. Absent columns are omitted from the Jaeger query, so the same step
   * serves both the error-tag and the {@code minDuration} outlier variants.</p>
   *
   * @param traceQuery single-row table describing the Jaeger trace search
   */
  @Wenn("Jaeger-Traces mit folgenden Daten abgefragt werden:")
  @When("Jaeger traces are queried with the following data:")
  public void collectJaegerTraces(DataTable traceQuery) {
    var rows = traceQuery.asMaps(String.class, String.class);
    if (rows.size() != 1) {
      throw new AssertionError(
          "Expected exactly one row for the Jaeger trace query, got " + rows.size());
    }
    var row = rows.get(0);

    var service = TigerGlobalConfiguration.resolvePlaceholders(column(row, "service"));
    try {
      var result = jaeger.searchTraces(
          service,
          column(row, "operation"),
          column(row, "start"),
          column(row, "end"),
          column(row, "limit"),
          column(row, "tags"),
          column(row, "minDuration"));

      var reportText = String.format(
          Locale.ROOT,
          "Jaeger traces: service=%s%s, tracesFound=%d",
          result.service(),
          result.operation() == null || result.operation().isBlank()
              ? ""
              : ", operation=" + result.operation(),
          result.traceCount());
      log.info("[JAEGER TRACE COLLECTION] {}", reportText);
      ReportAttachments.addText("Jaeger trace collection", reportText);
      ReportAttachments.addText(
          "Jaeger response (" + result.service() + ")", result.responseBody());
    } catch (Exception e) {
      // Diagnostic step: never fail the scenario on a Jaeger lookup problem.
      var message = String.format(
          Locale.ROOT,
          "Jaeger trace collection failed for service=%s: %s",
          service,
          e.getMessage());
      log.warn("[JAEGER TRACE COLLECTION] {}", message);
      ReportAttachments.addText("Jaeger trace collection", message);
      SoftAssertionsContext.recordSoftFailure(message, new AssertionError(message, e));
    }
  }

  /**
   * Returns the trimmed value of an optional data-table column, or an empty string when absent.
   *
   * @param row    the data-table row
   * @param column the column name
   * @return the column value, or an empty string if the column is missing or blank
   */
  private static String column(Map<String, String> row, String column) {
    var value = row.get(column);
    return value == null ? "" : value.trim();
  }
}
