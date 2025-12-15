/*-
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

import de.gematik.zeta.perf.CsvUtils;
import de.gematik.zeta.perf.CsvUtils.CsvData;
import de.gematik.zeta.perf.CsvUtils.CsvRow;
import de.gematik.zeta.perf.JMeterRunner;
import de.gematik.zeta.perf.JMeterTestConfig;
import de.gematik.zeta.perf.JtlSummarizer;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber step definitions for running JMeter plans and asserting summary metrics.
 */
@Slf4j
public class JMeterSteps {

  private final JMeterRunner jmeterRunner;
  private final JtlSummarizer jtlSummarizer;

  /**
   * Initializes JMeter runner and summarizer.
   */
  public JMeterSteps() {
    this.jmeterRunner = new JMeterRunner();
    this.jtlSummarizer = new JtlSummarizer();
  }

  /**
   * Runs a JMeter plan with optional CLI-like arguments from a DataTable.
   *
   * @param planPath  path to JMeter plan (.jmx)
   * @param argsTable optional flags/values (e.g., -l, -o, -q, -f, -Jkey=val)
   */
  @Wenn("ich JMeter mit dem Plan {tigerResolvedString} starte")
  @When("I start JMeter with plan {tigerResolvedString}")
  public void runJMeterWithPlan(String planPath, DataTable argsTable) throws Exception {
    log.info("Starting JMeter with plan: {}", planPath);

    Map<String, String> parameters = new HashMap<>(); // non -J flags
    Map<String, String> jmeterProps = new HashMap<>(); // -J properties
    Path jtlOutput = null;
    Path htmlOutput = null;
    Path propertiesFile = null;

    // Parse Cucumber table into flags/values.
    if (argsTable != null) {
      for (List<String> row : argsTable.asLists()) {
        if (row == null || row.isEmpty()) {
          continue;
        }

        String flag = row.get(0) != null ? row.get(0).trim() : "";
        String value = row.size() > 1 && row.get(1) != null ? row.get(1).trim() : "";

        if (flag.isEmpty()) {
          continue;
        }

        switch (flag) {
          case "-l" -> { // JTL result file
            if (!value.isEmpty()) {
              jtlOutput = Path.of(value);
            }
          }
          case "-o" -> { // HTML report dir
            if (!value.isEmpty()) {
              htmlOutput = Path.of(value);
            }
          }
          case "-q" -> { // properties file
            if (!value.isEmpty()) {
              propertiesFile = Path.of(value);
            }
          }
          case "-e" -> {
          } // ignore (report handled elsewhere)
          case "-f" -> // overwrite results
              parameters.put(flag, "");
          default -> {
            if (flag.startsWith("-J")) {
              // Accept "-Jk=v" or "-Jk v"
              String propertySpec = flag.contains("=") ? flag : (flag + "=" + value);
              String propertyPart = propertySpec.substring(2);

              String[] parts = propertyPart.split("=", 2);
              if (parts.length == 2) {
                String key = parts[0];
                String val = parts[1];

                // Special handling for REQUEST_BODY - check if it's a file
                if ("REQUEST_BODY".equals(key)) {
                  val = resolveRequestBody(val);
                }

                jmeterProps.put(key, val);
                log.debug("Added JMeter property: {}={}", key,
                    "REQUEST_BODY".equals(key) ? "[REDACTED]" : val);
              }
            } else {
              // General parameter
              parameters.put(flag, value.isEmpty() ? "" : value);
            }
          }
        }
      }
    }

    // Defaults for properties
    int targetRps = Integer.parseInt(jmeterProps.getOrDefault("TARGET_RPS", "300"));
    String baseUrl = jmeterProps.getOrDefault("BASE_URL", "http://localhost:9999");

    JMeterTestConfig config = JMeterTestConfig.builder()
        .planTemplate(Path.of(planPath))
        .targetRps(targetRps)
        .baseUrl(baseUrl)
        .parameters(parameters)
        .jmeterProperties(jmeterProps)
        .jtlOutput(jtlOutput)
        .htmlOutput(htmlOutput)
        .propertiesFile(propertiesFile)
        .build();

    log.info("JMeter config: targetRps={}, baseUrl={}", targetRps, baseUrl);
    jmeterRunner.runTest(config);
  }

  /**
   * Creates temporary properties file for JMeter properties.
   */
  private Path createTempPropertiesFile(Map<String, String> props) throws IOException {
    Path tempFile = Files.createTempFile("jmeter-", ".properties");

    // Debug: Prüfen Sie den REQUEST_BODY vor dem Speichern
    if (props.containsKey("REQUEST_BODY")) {
      String body = props.get("REQUEST_BODY");
      log.info("REQUEST_BODY before storing: {} chars, starts with: {}",
          body.length(), body.substring(0, Math.min(50, body.length())));
    }

    Properties properties = new Properties();
    props.forEach(properties::setProperty);

    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      properties.store(writer, "JMeter properties");
    }

    log.info("Created temp properties file: {}", tempFile);
    return tempFile;
  }

  /**
   * Resolves REQUEST_BODY - either reads from file or returns direct content.
   */
  private String resolveRequestBody(String bodyValue) {
    if (bodyValue == null || bodyValue.trim().isEmpty()) {
      return bodyValue;
    }

    // Simple heuristic: if it contains file separators, try as file
    if (bodyValue.contains("/") || bodyValue.contains("\\")) {
      try {
        Path bodyFile = Path.of(bodyValue);
        if (Files.exists(bodyFile)) {
          String content = Files.readString(bodyFile, StandardCharsets.UTF_8);
          log.info("Loaded request body from file: {} ({} chars)", bodyValue, content.length());
          return content;
        }
      } catch (Exception e) {
        log.debug("Could not read as file '{}', using as literal: {}", bodyValue, e.getMessage());
      }
    }

    // Use as direct content
    return bodyValue;
  }

  /**
   * Summarizes a JTL file into a CSV summary.
   */
  @Dann("erstelle die JMeter-Zusammenfassung aus {string} nach {string}")
  @Then("create the JMeter summary from {string} to {string}")
  public void summarizeJtl(String jtlPath, String summaryPath) throws IOException {
    log.info("Creating JMeter summary: {} -> {}", jtlPath, summaryPath);
    jtlSummarizer.summarize(Path.of(jtlPath), Path.of(summaryPath));
  }

  /**
   * Displays summary metrics from CSV in logs.
   */
  @Und("zeige die Kennzahlen aus {string} an")
  @And("show the metrics from {string}")
  public void showSummaryMetrics(String csvPath) throws IOException {
    log.info("Displaying metrics from: {}", csvPath);
    try (Stream<String> lines = Files.lines(Path.of(csvPath), StandardCharsets.UTF_8)) {
      lines.forEach(line -> log.info("[SUMMARY] {}", line));
    }
  }

  /**
   * Asserts that p95 latency (ms) of a label is ≤ threshold.
   */
  @Und("stelle sicher, dass in {string} das Label {string} p95 <= {int} ms hat")
  @And("ensure in {string} the label {string} has p95 <= {int} ms")
  public void assertJMeterLabelP95(String summaryPath, String requestedLabel, Integer maxMs)
      throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, "p95_ms", maxMs.doubleValue(), "<=");
  }

  /**
   * Asserts that errorRate (%) of a label is ≤ threshold. If errorRate is 0..1, it is normalized to
   * percent.
   */
  @Und("stelle sicher, dass in {string} das Label {string} eine Fehlerquote <= {double} hat")
  @And("ensure in {string} the label {string} has an error rate <= {double}")
  public void assertJMeterLabelErrorRate(String summaryPath, String requestedLabel,
      Double maxErrorRate) throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, "errorRate", maxErrorRate, "<=");
  }

  /**
   * Asserts that a metric of a label is ≤ threshold.
   */
  @Dann("stelle sicher, dass im JMeter-Summary {string} das Label {string} {jmeterMetric} <= {double}")
  @Then("ensure that in the JMeter summary {string} the label {string} has {jmeterMetric} <= {double}")
  public void assertJMeterSummaryLe(String summaryPath, String requestedLabel, String metric,
      double threshold) throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, metric, threshold, "<=");
  }

  /**
   * Asserts that a metric of a label is ≥ threshold.
   */
  @Dann("stelle sicher, dass im JMeter-Summary {string} das Label {string} {jmeterMetric} >= {double}")
  @Then("ensure that in the JMeter summary {string} the label {string} has {jmeterMetric} >= {double}")
  public void assertJMeterSummaryGe(String summaryPath, String requestedLabel, String metric,
      double threshold) throws IOException {
    assertJMeterSummaryMetric(summaryPath, requestedLabel, metric, threshold, ">=");
  }

  /**
   * Loads CSV, resolves label (fuzzy), reads metric, normalizes errorRate if needed, and applies
   * the comparison.
   *
   * @param summaryPath    path to summary CSV
   * @param requestedLabel label name to check
   * @param metricName     metric column name
   * @param threshold      expected threshold
   * @param operator       comparison operator ("<=" or ">=")
   */
  private void assertJMeterSummaryMetric(String summaryPath, String requestedLabel,
      String metricName,
      double threshold, String operator) throws IOException {
    CsvData csv = CsvUtils.readCsv(Path.of(summaryPath));

    // Try to match label exactly or approximately
    String effectiveLabel = CsvUtils.resolveSummaryLabel(csv.getColumn("label"), requestedLabel);

    if (effectiveLabel == null) {
      throw new AssertionError(
          "Label not found in summary: '" + requestedLabel + "'. Available: " + csv.getColumn(
              "label"));
    }

    // Retrieve row for label
    CsvRow labelRow = csv.findRow("label", effectiveLabel);
    if (labelRow == null) {
      throw new AssertionError("Row not found for label: " + effectiveLabel);
    }

    // Read metric value
    String rawValue = labelRow.get(metricName);
    if (rawValue == null) {
      throw new AssertionError("Metric column '" + metricName + "' not found in summary");
    }

    double observed;
    try {
      observed = Double.parseDouble(rawValue.trim());
    } catch (NumberFormatException e) {
      throw new AssertionError("Invalid numeric value for " + metricName + ": '" + rawValue + "'");
    }

    // Normalize errorRate if expressed as fraction
    if ("errorRate".equals(metricName) && observed <= 1.0) {
      observed *= 100.0;
    }

    boolean passed = switch (operator) {
      case "<=" -> observed <= threshold;
      case ">=" -> observed >= threshold;
      default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
    };

    log.info("[ASSERT JMETER] label='{}' {} {} {} {} -> {}",
        requestedLabel, metricName, observed, operator, threshold, passed ? "PASS" : "FAIL");

    if (!passed) {
      throw new AssertionError(String.format(Locale.ROOT,
          "%s(%s) = %.3f %s %.3f (effective label: %s)",
          metricName, requestedLabel, observed,
          operator.equals("<=") ? ">" : "<", threshold, effectiveLabel));
    }
  }
}
