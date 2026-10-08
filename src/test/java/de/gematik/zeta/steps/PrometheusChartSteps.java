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

import static de.gematik.zeta.services.PrometheusQuerySupport.combinedCountExpression;
import static de.gematik.zeta.services.PrometheusQuerySupport.histogramLatencyQuery;
import static de.gematik.zeta.services.PrometheusQuerySupport.histogramQuantileQuery;
import static de.gematik.zeta.services.PrometheusQuerySupport.labelMatcher;
import static de.gematik.zeta.services.PrometheusQuerySupport.parsePositiveInteger;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.Metric;
import de.gematik.zeta.services.PrometheusQueryService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Cucumber steps that render Prometheus range-query results as Allure SVG attachments.
 */
@Slf4j
public class PrometheusChartSteps {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final DateTimeFormatter TIME_LABEL_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
  private static final int SVG_WIDTH = 1120;
  private static final int SVG_HEIGHT = 560;
  private static final int PLOT_LEFT = 76;
  private static final int PLOT_TOP = 54;
  private static final int PLOT_WIDTH = 860;
  private static final int PLOT_HEIGHT = 390;
  private static final String[] SERIES_COLORS = {
      "#2f6fed", "#d64550", "#2f9e44", "#8f3ffc", "#f08c00", "#0ca6a6", "#6f4e37", "#c2255c"
  };

  private final PrometheusQueryService prometheus;

  /**
   * Creates the step class with a preconfigured Prometheus HTTP client.
   */
  public PrometheusChartSteps() {
    this.prometheus = new PrometheusQueryService(Duration.ofSeconds(30), 3);
  }

  /**
   * Renders a generic Prometheus range-query chart from a data table.
   *
   * <p>The table must contain {@code name} and {@code query} columns. Each query should return a
   * single time series; if it returns multiple series they are rendered with label details appended
   * to the configured name.</p>
   *
   * @param title chart and attachment title
   * @param windowSeconds chart time range in seconds ending at now
   * @param stepSeconds Prometheus query resolution in seconds
   * @param seriesTable table with {@code name} and {@code query} columns
   */
  @Dann("hänge Prometheus-Diagramm {string} für Fenster {tigerResolvedString} Sekunden und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus chart {string} for window {tigerResolvedString} seconds and step {tigerResolvedString} seconds")
  public void attachPrometheusChart(
      String title,
      String windowSeconds,
      String stepSeconds,
      DataTable seriesTable) {
    var rows = seriesTable.asMaps(String.class, String.class);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("Prometheus chart table must contain at least one row");
    }

    var queries = rows.stream()
        .map(row -> new ChartQuery(
            requireTableValue(row, "name"),
            TigerGlobalConfiguration.resolvePlaceholders(requireTableValue(row, "query"))))
        .toList();
    attachChartFromQueries(title, windowSeconds, stepSeconds, queries);
  }

  /**
   * Renders one configured Prometheus chart from a YAML definition.
   *
   * @param chartId chart id under the YAML {@code charts} node
   * @param configPath YAML file path or classpath resource
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  @Dann("hänge Prometheus-Diagramm {string} aus YAML {string}, Fenster {tigerResolvedString} Sekunden, Rollup {tigerResolvedString} Sekunden und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus chart {string} from YAML {string}, window {tigerResolvedString} seconds, rollup {tigerResolvedString} seconds and step {tigerResolvedString} seconds")
  public void attachPrometheusChartFromYaml(
      String chartId,
      String configPath,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var config = readYamlConfig(configPath);
    attachConfiguredChart(config, chartId, windowSeconds, rollupSeconds, stepSeconds);
  }

  /**
   * Renders all Prometheus charts listed in a YAML group definition.
   *
   * @param groupId group id under the YAML {@code groups} node
   * @param configPath YAML file path or classpath resource
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  @Dann("hänge Prometheus-Diagrammgruppe {string} aus YAML {string}, Fenster {tigerResolvedString} Sekunden, Rollup {tigerResolvedString} Sekunden und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus chart group {string} from YAML {string}, window {tigerResolvedString} seconds, rollup {tigerResolvedString} seconds and step {tigerResolvedString} seconds")
  public void attachPrometheusChartGroupFromYaml(
      String groupId,
      String configPath,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var config = readYamlConfig(configPath);
    var group = config.path("groups").path(groupId);
    if (!group.isArray() || group.isEmpty()) {
      throw new IllegalArgumentException("Prometheus chart group '" + groupId + "' is missing or empty in " + configPath);
    }
    for (var chartId : group) {
      attachConfiguredChart(config, chartId.asText(), windowSeconds, rollupSeconds, stepSeconds);
    }
  }

  /**
   * Renders all Prometheus charts listed in a reusable YAML chart set.
   *
   * @param setId set id under the YAML {@code sets} node
   * @param titlePrefix dynamic title prefix for all generated charts
   * @param configPath YAML file path or classpath resource
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  @Dann("hänge Prometheus-Diagrammset {string} mit Titelpräfix {string} aus YAML {string}, "
      + "Fenster {tigerResolvedString} Sekunden, Rollup {tigerResolvedString} Sekunden "
      + "und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus chart set {string} with title prefix {string} from YAML {string}, "
      + "window {tigerResolvedString} seconds, rollup {tigerResolvedString} seconds "
      + "and step {tigerResolvedString} seconds")
  public void attachPrometheusChartSetFromYaml(
      String setId,
      String titlePrefix,
      String configPath,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var config = readYamlConfig(configPath);
    var set = config.path("sets").path(setId);
    if (!set.isObject()) {
      throw new IllegalArgumentException("Prometheus chart set '" + setId + "' is missing in " + configPath);
    }
    var charts = set.path("charts");
    if (!charts.isArray() || charts.isEmpty()) {
      throw new IllegalArgumentException("Prometheus chart set '" + setId + "' must define at least one chart");
    }
    var rollup = parsePositiveInteger(rollupSeconds, "rollupSeconds");
    for (var chart : charts) {
      attachChartSetItem(config, setId, chart, titlePrefix, windowSeconds, rollup, stepSeconds);
    }
  }

  /**
   * Renders all Prometheus charts from a reusable YAML profile for one or more targets.
   *
   * @param profileId profile id under the YAML {@code profiles} node
   * @param targetIds comma-separated target ids under the YAML {@code targets} node
   * @param titlePrefix dynamic title prefix for all generated charts
   * @param configPath YAML file path or classpath resource
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  @Dann("hänge Prometheus-Diagrammprofil {string} für Ziele {string} mit Titelpräfix {string} "
      + "aus YAML {string}, Fenster {tigerResolvedString} Sekunden, "
      + "Rollup {tigerResolvedString} Sekunden und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus chart profile {string} for targets {string} with title prefix {string} "
      + "from YAML {string}, window {tigerResolvedString} seconds, "
      + "rollup {tigerResolvedString} seconds and step {tigerResolvedString} seconds")
  public void attachPrometheusChartProfileFromYaml(
      String profileId,
      String targetIds,
      String titlePrefix,
      String configPath,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var config = readYamlConfig(configPath);
    var profile = config.path("profiles").path(profileId);
    if (!profile.isObject()) {
      throw new IllegalArgumentException("Prometheus chart profile '" + profileId + "' is missing in " + configPath);
    }
    var charts = profile.path("charts");
    if (!charts.isArray() || charts.isEmpty()) {
      throw new IllegalArgumentException("Prometheus chart profile '" + profileId + "' must define at least one chart");
    }
    var rollup = parsePositiveInteger(rollupSeconds, "rollupSeconds");
    for (var targetId : parseCommaSeparatedValues(targetIds)) {
      var target = target(config, targetId, profileId);
      for (var chart : charts) {
        attachProfileChartItem(config, profileId, chart, target, titlePrefix, windowSeconds, rollup, stepSeconds);
      }
    }
  }

  /**
   * Renders a latency chart with avg, p90, p95 and p99 lines for one service/span pair.
   *
   * @param title chart and attachment title
   * @param serviceName Prometheus {@code service_name} label value
   * @param spanName Prometheus {@code span_name} label value
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling histogram window in seconds for each chart point
   * @param stepSeconds Prometheus query resolution in seconds
   */
  @Dann("hänge Prometheus-Latenzdiagramm {string} für Service {string}, Span {string}, "
      + "Fenster {tigerResolvedString} Sekunden, Rollup {tigerResolvedString} Sekunden "
      + "und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus latency chart {string} for service {string}, span {string}, "
      + "window {tigerResolvedString} seconds, rollup {tigerResolvedString} seconds "
      + "and step {tigerResolvedString} seconds")
  public void attachPrometheusLatencyChart(
      String title,
      String serviceName,
      String spanName,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var resolvedServiceName = TigerGlobalConfiguration.resolvePlaceholders(serviceName);
    var resolvedSpanName = TigerGlobalConfiguration.resolvePlaceholders(spanName);
    var rollup = parsePositiveInteger(rollupSeconds, "rollupSeconds") + "s";
    var matcher = labelMatcher(resolvedServiceName, resolvedSpanName, null);
    var queries = List.of(
        new ChartQuery("avg", histogramLatencyQuery(
            resolvedServiceName,
            resolvedSpanName,
            rollup,
            Metric.avg()), "ms"),
        new ChartQuery("p90", histogramQuantileQuery(0.90d, matcher, rollup), "ms"),
        new ChartQuery("p95", histogramQuantileQuery(0.95d, matcher, rollup), "ms"),
        new ChartQuery("p99", histogramQuantileQuery(0.99d, matcher, rollup), "ms"));
    attachChartFromQueries(title + " (ms)", windowSeconds, stepSeconds, queries);
  }

  /**
   * Renders a request-rate chart for several span names of one service.
   *
   * @param title chart and attachment title
   * @param serviceName Prometheus {@code service_name} label value
   * @param spanNames comma-separated Prometheus {@code span_name} label values
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling count window in seconds for each chart point
   * @param stepSeconds Prometheus query resolution in seconds
   */
  @Dann("hänge Prometheus-Ratendiagramm {string} für Service {string}, Spans {string}, "
      + "Fenster {tigerResolvedString} Sekunden, Rollup {tigerResolvedString} Sekunden "
      + "und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus rate chart {string} for service {string}, spans {string}, window {tigerResolvedString} seconds, rollup {tigerResolvedString} seconds and step {tigerResolvedString} seconds")
  public void attachPrometheusRateChart(
      String title,
      String serviceName,
      String spanNames,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var rollup = parsePositiveInteger(rollupSeconds, "rollupSeconds");
    var countExpression = combinedCountExpression(serviceName, spanNames, rollup + "s", null);
    var query = "(" + countExpression + ") / " + rollup;
    attachChartFromQueries(title + " (req/s)", windowSeconds, stepSeconds, List.of(new ChartQuery("rate", query, "req/s")));
  }

  /**
   * Renders an error-rate chart for several span names of one service.
   *
   * @param title chart and attachment title
   * @param serviceName Prometheus {@code service_name} label value
   * @param spanNames comma-separated Prometheus {@code span_name} label values
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling count window in seconds for each chart point
   * @param stepSeconds Prometheus query resolution in seconds
   */
  @Dann("hänge Prometheus-Fehlerratendiagramm {string} für Service {string}, Spans {string}, "
      + "Fenster {tigerResolvedString} Sekunden, Rollup {tigerResolvedString} Sekunden "
      + "und Schritt {tigerResolvedString} Sekunden an")
  @Then("attach Prometheus error-rate chart {string} for service {string}, spans {string}, "
      + "window {tigerResolvedString} seconds, rollup {tigerResolvedString} seconds "
      + "and step {tigerResolvedString} seconds")
  public void attachPrometheusErrorRateChart(
      String title,
      String serviceName,
      String spanNames,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var rollup = parsePositiveInteger(rollupSeconds, "rollupSeconds") + "s";
    var errorExpression = combinedCountExpression(
        serviceName,
        spanNames,
        rollup,
        "zeta_test_status_code=\"STATUS_CODE_ERROR\"");
    var totalExpression = combinedCountExpression(serviceName, spanNames, rollup, null);
    var query = "100 * (" + errorExpression + ") / clamp_min((" + totalExpression + "), 1)";
    attachChartFromQueries(title + " (%)", windowSeconds, stepSeconds, List.of(new ChartQuery("error rate", query, "%")));
  }

  /**
   * Resolves a configured chart by id and attaches it.
   *
   * @param config parsed YAML configuration
   * @param chartId chart id under {@code charts}
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  private void attachConfiguredChart(
      JsonNode config,
      String chartId,
      String windowSeconds,
      String rollupSeconds,
      String stepSeconds) {
    var chart = config.path("charts").path(chartId);
    if (!chart.isObject()) {
      throw new IllegalArgumentException("Prometheus chart '" + chartId + "' is missing in YAML configuration");
    }
    var title = textValue(chart, "title", chartId);
    var scale = ChartScale.from(textValue(chart, "scale", "raw"));
    var seriesNode = chart.path("series");
    if (!seriesNode.isArray() || seriesNode.isEmpty()) {
      throw new IllegalArgumentException("Prometheus chart '" + chartId + "' must define at least one series");
    }
    var rollup = parsePositiveInteger(rollupSeconds, "rollupSeconds");
    var queries = new ArrayList<ChartQuery>();
    for (var series : seriesNode) {
      queries.add(buildConfiguredQuery(config, chartId, chart, series, rollup));
    }
    attachChartFromQueries(title, windowSeconds, stepSeconds, queries, scale);
  }

  /**
   * Resolves one chart-set item and attaches the generated chart.
   *
   * @param config parsed YAML configuration
   * @param setId parent set id for error reporting
   * @param chart YAML chart item
   * @param titlePrefix dynamic title prefix
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  private void attachChartSetItem(
      JsonNode config,
      String setId,
      JsonNode chart,
      String titlePrefix,
      String windowSeconds,
      int rollupSeconds,
      String stepSeconds) {
    var targetId = requiredText(chart, "target", setId);
    var type = requiredText(chart, "type", setId);
    var target = target(config, targetId, setId);
    var title = chart.hasNonNull("title")
        ? textValue(chart, "title", "")
        : buildChartSetTitle(titlePrefix, target, type);
    var queries = buildChartSetQueries(config, setId, chart, target, type, rollupSeconds);
    var scale = ChartScale.from(textValue(chart, "scale", defaultChartScale(type)));
    attachChartFromQueries(title, windowSeconds, stepSeconds, queries, scale);
  }

  /**
   * Resolves one profile chart item for one target and attaches generated chart(s).
   *
   * @param config parsed YAML configuration
   * @param profileId parent profile id for error reporting
   * @param chart YAML chart item
   * @param target inherited target node
   * @param titlePrefix dynamic title prefix
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  private void attachProfileChartItem(
      JsonNode config,
      String profileId,
      JsonNode chart,
      JsonNode target,
      String titlePrefix,
      String windowSeconds,
      int rollupSeconds,
      String stepSeconds) {
    var chartObject = chartObject(chart);
    var type = chartType(chartObject, profileId);
    if ("latency".equals(type) && target.path("latencies").isArray() && !target.path("latencies").isEmpty()) {
      for (var latency : target.path("latencies")) {
        var latencyChart = chartObject.deepCopy();
        ((ObjectNode) latencyChart).put("span", requiredText(latency, "span", profileId));
        ((ObjectNode) latencyChart).put("latencyName", textValue(latency, "name", latencyLabel(target)));
        attachOneProfileChart(config, profileId, latencyChart, target, type, titlePrefix, windowSeconds, rollupSeconds, stepSeconds);
      }
      return;
    }
    attachOneProfileChart(config, profileId, chartObject, target, type, titlePrefix, windowSeconds, rollupSeconds, stepSeconds);
  }

  /**
   * Attaches one concrete profile chart item.
   *
   * @param config parsed YAML configuration
   * @param profileId parent profile id for error reporting
   * @param chart YAML chart item
   * @param target inherited target node
   * @param type chart type
   * @param titlePrefix dynamic title prefix
   * @param windowSeconds chart time range in seconds ending at now
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @param stepSeconds Prometheus query resolution in seconds
   */
  private void attachOneProfileChart(
      JsonNode config,
      String profileId,
      JsonNode chart,
      JsonNode target,
      String type,
      String titlePrefix,
      String windowSeconds,
      int rollupSeconds,
      String stepSeconds) {
    var title = chart.hasNonNull("title")
        ? textValue(chart, "title", "")
        : buildChartSetTitle(titlePrefix, target, chart, type);
    var queries = buildChartSetQueries(config, profileId, chart, target, type, rollupSeconds);
    var scale = ChartScale.from(textValue(chart, "scale", defaultChartScale(type)));
    attachChartFromQueries(title, windowSeconds, stepSeconds, queries, scale);
  }

  /**
   * Builds all queries for one chart-set item.
   *
   * @param config parsed YAML configuration
   * @param setId parent set id for error reporting
   * @param chart YAML chart item
   * @param target inherited target node
   * @param type chart type
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @return chart queries
   */
  private List<ChartQuery> buildChartSetQueries(
      JsonNode config,
      String setId,
      JsonNode chart,
      JsonNode target,
      String type,
      int rollupSeconds) {
    var namePrefix = textValue(target, "name", "");
    var rollup = rollupSeconds + "s";
    return switch (type) {
      case "rate" -> List.of(rateQuery(
          textValue(chart, "name", "rate"),
          inheritedText(config, target, chart, "service", "service", setId),
          inheritedText(config, target, chart, "spans", "spans", setId),
          rollup,
          rollupSeconds));
      case "error_rate" -> List.of(errorRateQuery(
          textValue(chart, "name", "error rate"),
          inheritedText(config, target, chart, "service", "service", setId),
          inheritedText(config, target, chart, "spans", "spans", setId),
          rollup));
      case "latency" -> latencyQueries(
          config,
          setId,
          target,
          chart,
          rollup);
      case "correlation" -> List.of(
          rateQuery(
              namePrefix.isBlank() ? "Rate" : namePrefix + " Rate",
              inheritedText(config, target, chart, "service", "service", setId),
              inheritedText(config, target, chart, "spans", "spans", setId),
              rollup,
              rollupSeconds),
          errorRateQuery(
              namePrefix.isBlank() ? "Fehlerrate" : namePrefix + " Fehlerrate",
              inheritedText(config, target, chart, "service", "service", setId),
              inheritedText(config, target, chart, "spans", "spans", setId),
              rollup),
          latencyQuantileQuery(
              textValue(chart, "p95Name", latencyLabel(target) + " p95"),
              inheritedText(config, target, chart, "service", "service", setId),
              inheritedText(config, target, chart, "span", "latencySpan", setId),
              0.95d,
              rollup),
          latencyQuantileQuery(
              textValue(chart, "p99Name", latencyLabel(target) + " p99"),
              inheritedText(config, target, chart, "service", "service", setId),
              inheritedText(config, target, chart, "span", "latencySpan", setId),
              0.99d,
              rollup));
      default -> throw new IllegalArgumentException(
          "Prometheus chart set '" + setId + "' contains unsupported chart type '" + type + "'");
    };
  }

  /**
   * Builds a default chart title for a chart-set item.
   *
   * @param titlePrefix dynamic title prefix
   * @param target inherited target node
   * @param type chart type
   * @return chart title
   */
  private String buildChartSetTitle(String titlePrefix, JsonNode target, String type) {
    return buildChartSetTitle(titlePrefix, target, YAML.createObjectNode(), type);
  }

  /**
   * Builds a default chart title for a chart-set item.
   *
   * @param titlePrefix dynamic title prefix
   * @param target inherited target node
   * @param chart YAML chart item
   * @param type chart type
   * @return chart title
   */
  private String buildChartSetTitle(String titlePrefix, JsonNode target, JsonNode chart, String type) {
    var prefix = TigerGlobalConfiguration.resolvePlaceholders(titlePrefix).trim();
    var targetName = textValue(target, "name", "");
    var latencyName = textValue(chart, "latencyName", latencyLabel(target));
    var topic = switch (type) {
      case "rate" -> targetName + " Rate (req/s)";
      case "error_rate" -> targetName + " Fehlerrate (%)";
      case "latency" -> targetName + " " + latencyName + " Latenz (ms)";
      case "correlation" -> targetName + " Korrelation";
      default -> targetName + " " + type;
    };
    return prefix.isBlank() ? topic.trim() : prefix + " - " + topic.trim();
  }

  /**
   * Returns the default scale for a generated chart-set type.
   *
   * @param type chart type
   * @return default scale value
   */
  private String defaultChartScale(String type) {
    return "correlation".equals(type) ? "normalized" : "raw";
  }

  /**
   * Builds one chart query from a YAML series definition.
   *
   * @param config parsed YAML configuration
   * @param chartId parent chart id for error messages
   * @param chart YAML chart node
   * @param series YAML series node
   * @param rollupSeconds rolling window in seconds for generated PromQL expressions
   * @return chart query
   */
  private ChartQuery buildConfiguredQuery(
      JsonNode config,
      String chartId,
      JsonNode chart,
      JsonNode series,
      int rollupSeconds) {
    var name = requiredText(series, "name", chartId);
    if (series.hasNonNull("query")) {
      return new ChartQuery(
          name,
          TigerGlobalConfiguration.resolvePlaceholders(requiredText(series, "query", chartId)),
          textValue(series, "unit", ""));
    }

    var type = requiredText(series, "type", chartId);
    var service = inheritedText(config, chart, series, "service", "service", chartId);
    var rollup = rollupSeconds + "s";
    return switch (type) {
      case "rate" -> {
        var countExpression = combinedCountExpression(
            service,
            inheritedText(config, chart, series, "spans", "spans", chartId),
            rollup,
            null);
        yield new ChartQuery(name, "(" + countExpression + ") / " + rollupSeconds, "req/s");
      }
      case "error_rate" -> {
        var spans = inheritedText(config, chart, series, "spans", "spans", chartId);
        var errorExpression = combinedCountExpression(
            service,
            spans,
            rollup,
            "zeta_test_status_code=\"STATUS_CODE_ERROR\"");
        var totalExpression = combinedCountExpression(service, spans, rollup, null);
        yield new ChartQuery(name, "100 * (" + errorExpression + ") / clamp_min((" + totalExpression + "), 1)", "%");
      }
      case "latency_avg" -> {
        var resolvedService = TigerGlobalConfiguration.resolvePlaceholders(service);
        var resolvedSpan = TigerGlobalConfiguration.resolvePlaceholders(
            inheritedText(config, chart, series, "span", "latencySpan", chartId));
        yield new ChartQuery(
            name,
            histogramLatencyQuery(resolvedService, resolvedSpan, rollup, Metric.avg()),
            "ms");
      }
      case "latency_p90" -> latencyQuantileQuery(
          name,
          service,
          inheritedText(config, chart, series, "span", "latencySpan", chartId),
          0.90d,
          rollup);
      case "latency_p95" -> latencyQuantileQuery(
          name,
          service,
          inheritedText(config, chart, series, "span", "latencySpan", chartId),
          0.95d,
          rollup);
      case "latency_p99" -> latencyQuantileQuery(
          name,
          service,
          inheritedText(config, chart, series, "span", "latencySpan", chartId),
          0.99d,
          rollup);
      default -> throw new IllegalArgumentException(
          "Prometheus chart '" + chartId + "' contains unsupported series type '" + type + "'");
    };
  }

  /**
   * Builds one latency quantile chart query.
   *
   * @param name chart series name
   * @param serviceName Prometheus {@code service_name} label value
   * @param spanName Prometheus {@code span_name} label value
   * @param quantile histogram quantile
   * @param rollup Prometheus range selector duration
   * @return chart query
   */
  private ChartQuery latencyQuantileQuery(
      String name,
      String serviceName,
      String spanName,
      double quantile,
      String rollup) {
    var matcher = labelMatcher(
        TigerGlobalConfiguration.resolvePlaceholders(serviceName),
        TigerGlobalConfiguration.resolvePlaceholders(spanName),
        null);
    return new ChartQuery(name, histogramQuantileQuery(quantile, matcher, rollup), "ms");
  }

  /**
   * Builds one request-rate query.
   *
   * @param name chart series name
   * @param serviceName Prometheus {@code service_name} label value
   * @param spanNames comma-separated Prometheus {@code span_name} label values
   * @param rollup Prometheus range selector duration
   * @param rollupSeconds rolling window in seconds
   * @return chart query
   */
  private ChartQuery rateQuery(
      String name,
      String serviceName,
      String spanNames,
      String rollup,
      int rollupSeconds) {
    var countExpression = combinedCountExpression(serviceName, spanNames, rollup, null);
    return new ChartQuery(name, "(" + countExpression + ") / " + rollupSeconds, "req/s");
  }

  /**
   * Builds one error-rate query.
   *
   * @param name chart series name
   * @param serviceName Prometheus {@code service_name} label value
   * @param spanNames comma-separated Prometheus {@code span_name} label values
   * @param rollup Prometheus range selector duration
   * @return chart query
   */
  private ChartQuery errorRateQuery(String name, String serviceName, String spanNames, String rollup) {
    var errorExpression = combinedCountExpression(
        serviceName,
        spanNames,
        rollup,
        "zeta_test_status_code=\"STATUS_CODE_ERROR\"");
    var totalExpression = combinedCountExpression(serviceName, spanNames, rollup, null);
    return new ChartQuery(name, "100 * (" + errorExpression + ") / clamp_min((" + totalExpression + "), 1)", "%");
  }

  /**
   * Builds avg, p90, p95 and p99 latency queries for one chart-set item.
   *
   * @param config parsed YAML configuration
   * @param setId parent set id for error reporting
   * @param target inherited target node
   * @param chart YAML chart item
   * @param rollup Prometheus range selector duration
   * @return latency chart queries
   */
  private List<ChartQuery> latencyQueries(
      JsonNode config,
      String setId,
      JsonNode target,
      JsonNode chart,
      String rollup) {
    var service = inheritedText(config, target, chart, "service", "service", setId);
    var span = chart.path("span").asText("");
    if (span.isBlank()) {
      span = inheritedText(config, target, chart, "span", "latencySpan", setId);
    }
    var matcher = labelMatcher(
        TigerGlobalConfiguration.resolvePlaceholders(service),
        TigerGlobalConfiguration.resolvePlaceholders(span),
        null);
    return List.of(
        new ChartQuery("avg", histogramLatencyQuery(
            TigerGlobalConfiguration.resolvePlaceholders(service),
            TigerGlobalConfiguration.resolvePlaceholders(span),
            rollup,
            Metric.avg()), "ms"),
        new ChartQuery("p90", histogramQuantileQuery(0.90d, matcher, rollup), "ms"),
        new ChartQuery("p95", histogramQuantileQuery(0.95d, matcher, rollup), "ms"),
        new ChartQuery("p99", histogramQuantileQuery(0.99d, matcher, rollup), "ms"));
  }

  /**
   * Resolves a named target from the YAML configuration.
   *
   * @param config parsed YAML configuration
   * @param targetId target id
   * @param ownerId chart or set id for error reporting
   * @return target YAML node
   */
  private JsonNode target(JsonNode config, String targetId, String ownerId) {
    var target = config.path("targets").path(targetId);
    if (!target.isObject()) {
      throw new IllegalArgumentException(
          "Prometheus chart configuration '" + ownerId + "' references missing target '" + targetId + "'");
    }
    return target;
  }

  /**
   * Returns the display label for the target latency span.
   *
   * @param target inherited target node
   * @return latency label
   */
  private String latencyLabel(JsonNode target) {
    var latencyName = textValue(target, "latencyName", "");
    if (!latencyName.isBlank()) {
      return latencyName;
    }
    var latencies = target.path("latencies");
    if (latencies.isArray() && !latencies.isEmpty()) {
      return textValue(latencies.get(0), "name", "Latenz");
    }
    return "Latenz";
  }

  /**
   * Reads a YAML chart configuration from a filesystem path or classpath resource.
   *
   * @param configPath YAML file path or classpath resource
   * @return parsed YAML root node
   */
  private JsonNode readYamlConfig(String configPath) {
    var resolvedPath = TigerGlobalConfiguration.resolvePlaceholders(configPath);
    try {
      var directPath = Path.of(resolvedPath);
      if (Files.isRegularFile(directPath)) {
        return YAML.readTree(directPath.toFile());
      }
      var resourcePath = resolvedPath.startsWith("/") ? resolvedPath.substring(1) : resolvedPath;
      var sourceResourcePath = Path.of("src", "test", "resources").resolve(resourcePath);
      if (Files.isRegularFile(sourceResourcePath)) {
        return YAML.readTree(sourceResourcePath.toFile());
      }
      try (var resource = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
        if (resource != null) {
          return YAML.readTree(resource);
        }
      }
      throw new IllegalArgumentException("Prometheus chart YAML not found: " + resolvedPath);
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to read Prometheus chart YAML: " + resolvedPath, e);
    }
  }

  /**
   * Executes all configured queries, renders one SVG chart and attaches it to the report.
   *
   * @param title chart and attachment title
   * @param windowSeconds chart time range in seconds ending at now
   * @param stepSeconds Prometheus query resolution in seconds
   * @param queries chart series queries
   */
  private void attachChartFromQueries(
      String title,
      String windowSeconds,
      String stepSeconds,
      List<ChartQuery> queries) {
    attachChartFromQueries(title, windowSeconds, stepSeconds, queries, ChartScale.RAW);
  }

  /**
   * Executes all configured queries, renders one SVG chart and attaches it to the report.
   *
   * @param title chart and attachment title
   * @param windowSeconds chart time range in seconds ending at now
   * @param stepSeconds Prometheus query resolution in seconds
   * @param queries chart series queries
   * @param scale chart value scaling mode
   */
  private void attachChartFromQueries(
      String title,
      String windowSeconds,
      String stepSeconds,
      List<ChartQuery> queries,
      ChartScale scale) {
    var resolvedTitle = TigerGlobalConfiguration.resolvePlaceholders(title);
    var window = parsePositiveInteger(windowSeconds, "windowSeconds");
    var step = parsePositiveInteger(stepSeconds, "stepSeconds");
    var end = Instant.now();
    var start = end.minusSeconds(window);

    try {
      var allSeries = new ArrayList<ChartSeries>();
      for (var query : queries) {
        allSeries.addAll(queryPrometheusRange(query, start, end, step));
      }
      var renderedSeries = scale == ChartScale.NORMALIZED ? normalizeSeries(allSeries) : allSeries;
      var renderedTitle = scale == ChartScale.NORMALIZED ? resolvedTitle + " (normalisiert 0-100)" : resolvedTitle;
      var svg = renderSvg(renderedTitle, start, end, step, renderedSeries);
      var chartPath = writeChart(resolvedTitle, svg);
      ReportAttachments.addFile("Prometheus chart - " + resolvedTitle, chartPath, "image/svg+xml");
      ReportAttachments.addText(
          "Prometheus chart queries - " + resolvedTitle,
          buildQueryReport(resolvedTitle, start, end, step, queries, chartPath));
    } catch (Exception e) {
      var message = "Prometheus chart generation failed for '" + resolvedTitle + "'";
      log.warn("[PROMETHEUS CHART] {}", message, e);
      ReportAttachments.addText(
          "Prometheus chart generation failed - " + resolvedTitle,
          buildFailureReport(resolvedTitle, start, end, step, queries, e));
      SoftAssertionsContext.recordSoftFailure(message, e);
    }
  }

  /**
   * Executes one Prometheus {@code query_range} request.
   *
   * @param query configured chart series query
   * @param start query start timestamp
   * @param end query end timestamp
   * @param stepSeconds query resolution in seconds
   * @return chart series returned by Prometheus
   * @throws Exception when the request or response parsing fails
   */
  private List<ChartSeries> queryPrometheusRange(
      ChartQuery query,
      Instant start,
      Instant end,
      int stepSeconds) throws Exception {
    var rangeSeries = prometheus.queryRange(query.query(), start, end, stepSeconds);
    if (rangeSeries.isEmpty()) {
      return List.of(new ChartSeries(query.name(), List.of(), query.unit(), ""));
    }

    var series = new ArrayList<ChartSeries>();
    for (var resultItem : rangeSeries) {
      series.add(new ChartSeries(
          buildSeriesName(query.name(), resultItem.metric()),
          resultItem.points().stream()
              .map(point -> new ChartPoint(point.timestamp(), point.value()))
              .toList(),
          query.unit(),
          ""));
    }
    return series;
  }

  /**
   * Normalizes each series independently to a {@code 0..100} value range.
   *
   * @param series raw chart series
   * @return normalized chart series with raw-value summaries
   */
  private List<ChartSeries> normalizeSeries(List<ChartSeries> series) {
    return series.stream()
        .map(this::normalizeSeries)
        .toList();
  }

  /**
   * Normalizes one series independently to a {@code 0..100} value range.
   *
   * @param series raw chart series
   * @return normalized chart series
   */
  private ChartSeries normalizeSeries(ChartSeries series) {
    var finiteValues = series.points().stream()
        .mapToDouble(ChartPoint::value)
        .filter(Double::isFinite)
        .toArray();
    if (finiteValues.length == 0) {
      return new ChartSeries(series.name(), List.of(), "%", "Rohwerte: n/a");
    }
    var min = java.util.Arrays.stream(finiteValues).min().orElse(0d);
    var max = java.util.Arrays.stream(finiteValues).max().orElse(0d);
    var latest = series.points().stream()
        .mapToDouble(ChartPoint::value)
        .filter(Double::isFinite)
        .reduce((first, second) -> second)
        .orElse(Double.NaN);
    var normalizedPoints = series.points().stream()
        .map(point -> new ChartPoint(point.timestamp(), normalizedValue(point.value(), min, max)))
        .toList();
    return new ChartSeries(
        series.name(),
        normalizedPoints,
        "%",
        "Roh: " + formatNumberWithUnit(latest, series.unit())
            + " (" + formatNumberWithUnit(min, series.unit())
            + ".." + formatNumberWithUnit(max, series.unit()) + ")");
  }

  /**
   * Normalizes one value to {@code 0..100}; constant series are centered.
   *
   * @param value raw value
   * @param min series minimum
   * @param max series maximum
   * @return normalized value
   */
  private double normalizedValue(double value, double min, double max) {
    if (!Double.isFinite(value)) {
      return value;
    }
    if (Double.compare(min, max) == 0) {
      return 50d;
    }
    return (value - min) / (max - min) * 100d;
  }

  /**
   * Builds a chart series label from the configured name and returned metric labels.
   *
   * @param baseName configured series name
   * @param metricLabels Prometheus metric labels
   * @return display series name
   */
  private String buildSeriesName(String baseName, Map<String, String> metricLabels) {
    if (metricLabels.isEmpty()) {
      return baseName;
    }
    var labels = new StringJoiner(", ");
    metricLabels.forEach((key, value) -> {
      if (!"__name__".equals(key)) {
        labels.add(key + "=" + value);
      }
    });
    var labelText = labels.toString();
    return labelText.isBlank() ? baseName : baseName + " {" + labelText + "}";
  }

  /**
   * Renders chart data as a standalone SVG document.
   *
   * @param title chart title
   * @param start chart start timestamp
   * @param end chart end timestamp
   * @param stepSeconds query resolution in seconds
   * @param series chart series
   * @return SVG document
   */
  private String renderSvg(
      String title,
      Instant start,
      Instant end,
      int stepSeconds,
      List<ChartSeries> series) {
    var finiteValues = series.stream()
        .flatMap(item -> item.points().stream())
        .mapToDouble(ChartPoint::value)
        .filter(Double::isFinite)
        .toArray();
    var min = finiteValues.length == 0 ? 0d : java.util.Arrays.stream(finiteValues).min().orElse(0d);
    var max = finiteValues.length == 0 ? 1d : java.util.Arrays.stream(finiteValues).max().orElse(1d);
    if (min >= 0d) {
      min = 0d;
    }
    if (Double.compare(min, max) == 0) {
      var padding = Math.max(1d, Math.abs(min) * 0.1d);
      max += padding;
      if (min < 0d) {
        min -= padding;
      }
    }

    var document = new StringBuilder();
    document.append("""
        <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
          <rect width="100%%" height="100%%" fill="#ffffff"/>
        """.formatted(SVG_WIDTH, SVG_HEIGHT, SVG_WIDTH, SVG_HEIGHT));
    document.append(text(28, 32, 20, "#1f2937", "600", title));
    document.append(text(28, 52, 12, "#6b7280", "400",
        "start=" + start + " end=" + end + " step=" + stepSeconds + "s"));
    appendGridAndAxes(document, start, end, min, max);
    appendSeries(document, series, start, end, min, max);
    appendLegend(document, series);
    if (finiteValues.length == 0) {
      document.append(text(PLOT_LEFT + 250, PLOT_TOP + 190, 18, "#9ca3af", "600",
          "Keine Prometheus-Datenpunkte im abgefragten Zeitraum"));
    }
    document.append("</svg>").append(System.lineSeparator());
    return document.toString();
  }

  /**
   * Appends chart grid lines and axis labels to the SVG document.
   *
   * @param document SVG document builder
   * @param start chart start timestamp
   * @param end chart end timestamp
   * @param min minimum y value
   * @param max maximum y value
   */
  private void appendGridAndAxes(StringBuilder document, Instant start, Instant end, double min, double max) {
    document.append("""
          <rect x="%d" y="%d" width="%d" height="%d" fill="#fbfdff" stroke="#d1d5db"/>
        """.formatted(PLOT_LEFT, PLOT_TOP, PLOT_WIDTH, PLOT_HEIGHT));
    for (int i = 0; i <= 5; i++) {
      var y = PLOT_TOP + (PLOT_HEIGHT * i / 5.0d);
      var value = max - ((max - min) * i / 5.0d);
      document.append(line(PLOT_LEFT, y, PLOT_LEFT + PLOT_WIDTH, y, "#e5e7eb", 1));
      document.append(text(14, y + 4, 11, "#4b5563", "400", formatNumber(value)));
    }
    var durationSeconds = Math.max(1L, end.getEpochSecond() - start.getEpochSecond());
    for (int i = 0; i <= 6; i++) {
      var x = PLOT_LEFT + (PLOT_WIDTH * i / 6.0d);
      var instant = start.plusSeconds(durationSeconds * i / 6L);
      document.append(line(x, PLOT_TOP, x, PLOT_TOP + PLOT_HEIGHT, "#f3f4f6", 1));
      document.append(text(x - 24, PLOT_TOP + PLOT_HEIGHT + 24, 11, "#4b5563", "400",
          TIME_LABEL_FORMATTER.format(instant)));
    }
  }

  /**
   * Appends all chart series as SVG polylines.
   *
   * @param document SVG document builder
   * @param series chart series
   * @param start chart start timestamp
   * @param end chart end timestamp
   * @param min minimum y value
   * @param max maximum y value
   */
  private void appendSeries(
      StringBuilder document,
      List<ChartSeries> series,
      Instant start,
      Instant end,
      double min,
      double max) {
    for (int i = 0; i < series.size(); i++) {
      var item = series.get(i);
      var color = SERIES_COLORS[i % SERIES_COLORS.length];
      if (item.points().isEmpty()) {
        continue;
      }
      var pointText = item.points().stream()
          .map(point -> formatPoint(point, start, end, min, max))
          .collect(Collectors.joining(" "));
      document.append("""
            <polyline fill="none" stroke="%s" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round" points="%s"/>
          """.formatted(color, pointText));
      for (var point : item.points()) {
        document.append(String.format(
            Locale.ROOT,
            "    <circle cx=\"%.2f\" cy=\"%.2f\" r=\"2.7\" fill=\"%s\" stroke=\"#ffffff\" stroke-width=\"1\"/>%n",
            xcoordinate(point.timestamp(), start, end),
            ycoordinate(point.value(), min, max),
            color));
      }
      if (item.points().size() == 1) {
        var onlyPoint = item.points().get(0);
        document.append(String.format(
            Locale.ROOT,
            "    <circle cx=\"%.2f\" cy=\"%.2f\" r=\"3.5\" fill=\"%s\"/>%n",
            xcoordinate(onlyPoint.timestamp(), start, end),
            ycoordinate(onlyPoint.value(), min, max),
            color));
      }
    }
  }

  /**
   * Appends the chart legend and per-series summary values.
   *
   * @param document SVG document builder
   * @param series chart series
   */
  private void appendLegend(StringBuilder document, List<ChartSeries> series) {
    var x = 960;
    var y = 80;
    document.append(text(x, y - 24, 13, "#374151", "600", "Reihen"));
    for (int i = 0; i < series.size(); i++) {
      var item = series.get(i);
      var color = SERIES_COLORS[i % SERIES_COLORS.length];
      var latest = item.points().isEmpty()
          ? "n/a"
          : formatNumberWithUnit(item.points().get(item.points().size() - 1).value(), item.unit());
      document.append("""
            <rect x="%d" y="%d" width="10" height="10" fill="%s"/>
          """.formatted(x, y - 9, color));
      document.append(text(x + 16, y, 11, "#111827", "600", abbreviate(item.name(), 24)));
      var summary = item.summary().isBlank() ? "letzter Wert: " + latest : item.summary();
      document.append(text(x + 16, y + 15, 10, "#6b7280", "400", abbreviate(summary, 28)));
      y += 38;
    }
  }

  /**
   * Formats one chart point as an SVG polyline coordinate.
   *
   * @param point chart point
   * @param start chart start timestamp
   * @param end chart end timestamp
   * @param min minimum y value
   * @param max maximum y value
   * @return SVG coordinate pair
   */
  private String formatPoint(ChartPoint point, Instant start, Instant end, double min, double max) {
    return String.format(
        Locale.ROOT,
        "%.2f,%.2f",
        xcoordinate(point.timestamp(), start, end),
        ycoordinate(point.value(), min, max));
  }

  /**
   * Calculates the SVG x coordinate for one timestamp.
   *
   * @param timestamp point timestamp
   * @param start chart start timestamp
   * @param end chart end timestamp
   * @return x coordinate
   */
  private double xcoordinate(Instant timestamp, Instant start, Instant end) {
    var total = Math.max(1d, end.getEpochSecond() - start.getEpochSecond());
    var offset = Math.max(0d, timestamp.getEpochSecond() - start.getEpochSecond());
    return PLOT_LEFT + Math.min(1d, offset / total) * PLOT_WIDTH;
  }

  /**
   * Calculates the SVG y coordinate for one value.
   *
   * @param value point value
   * @param min minimum y value
   * @param max maximum y value
   * @return y coordinate
   */
  private double ycoordinate(double value, double min, double max) {
    return PLOT_TOP + (max - value) / (max - min) * PLOT_HEIGHT;
  }

  /**
   * Writes one chart SVG to the target directory.
   *
   * @param title chart title
   * @param svg SVG document
   * @return written chart path
   * @throws IOException when the file cannot be written
   */
  private Path writeChart(String title, String svg) throws IOException {
    var chartDir = Path.of("target", "prometheus-charts");
    Files.createDirectories(chartDir);
    var file = chartDir.resolve(safeFileName(title) + "-" + System.currentTimeMillis() + ".svg");
    Files.writeString(file, svg, StandardCharsets.UTF_8);
    return file;
  }

  /**
   * Builds a text report with the exact Prometheus queries used for one chart.
   *
   * @param title chart title
   * @param start query start timestamp
   * @param end query end timestamp
   * @param stepSeconds query resolution in seconds
   * @param queries chart series queries
   * @param chartPath written chart file path
   * @return text report
   */
  private String buildQueryReport(
      String title,
      Instant start,
      Instant end,
      int stepSeconds,
      List<ChartQuery> queries,
      Path chartPath) {
    var report = new StringBuilder();
    report.append("title=").append(title).append(System.lineSeparator());
    report.append("file=").append(chartPath.toAbsolutePath().normalize()).append(System.lineSeparator());
    report.append("start=").append(start).append(System.lineSeparator());
    report.append("end=").append(end).append(System.lineSeparator());
    report.append("stepSeconds=").append(stepSeconds).append(System.lineSeparator());
    for (var query : queries) {
      report.append("[").append(query.name()).append("] ").append(query.query()).append(System.lineSeparator());
    }
    return report.toString();
  }

  /**
   * Builds a text report for a Prometheus chart generation failure.
   *
   * @param title chart title
   * @param start query start timestamp
   * @param end query end timestamp
   * @param stepSeconds query resolution in seconds
   * @param queries chart series queries
   * @param cause chart generation failure
   * @return text report
   */
  private String buildFailureReport(
      String title,
      Instant start,
      Instant end,
      int stepSeconds,
      List<ChartQuery> queries,
      Exception cause) {
    var report = new StringBuilder();
    report.append("title=").append(title).append(System.lineSeparator());
    report.append("start=").append(start).append(System.lineSeparator());
    report.append("end=").append(end).append(System.lineSeparator());
    report.append("stepSeconds=").append(stepSeconds).append(System.lineSeparator());
    report.append("errorClass=").append(cause.getClass().getName()).append(System.lineSeparator());
    report.append("errorMessage=").append(cause.getMessage()).append(System.lineSeparator());
    for (var query : queries) {
      report.append("[").append(query.name()).append("] ").append(query.query()).append(System.lineSeparator());
    }
    return report.toString();
  }

  /**
   * Reads a required data-table value.
   *
   * @param row data-table row
   * @param key column name
   * @return non-blank cell value
   */
  private String requireTableValue(Map<String, String> row, String key) {
    var value = row.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Prometheus chart table column '" + key + "' must not be blank");
    }
    return value;
  }

  /**
   * Reads a required text value from a YAML node.
   *
   * @param node YAML object node
   * @param key property name
   * @param chartId chart id for error reporting
   * @return non-blank resolved text value
   */
  private String requiredText(JsonNode node, String key, String chartId) {
    var value = node.path(key).asText("");
    if (value.isBlank()) {
      throw new IllegalArgumentException(
          "Prometheus chart '" + chartId + "' has a series with missing '" + key + "'");
    }
    return TigerGlobalConfiguration.resolvePlaceholders(value);
  }

  /**
   * Reads an optional text value from a YAML node.
   *
   * @param node YAML object node
   * @param key property name
   * @param defaultValue value used when the property is absent or blank
   * @return resolved text value
   */
  private String textValue(JsonNode node, String key, String defaultValue) {
    var value = node.path(key).asText("");
    return TigerGlobalConfiguration.resolvePlaceholders(value.isBlank() ? defaultValue : value);
  }

  /**
   * Parses comma-separated values from a Tiger-resolved string.
   *
   * @param value raw comma-separated value
   * @return resolved non-empty values
   */
  private List<String> parseCommaSeparatedValues(String value) {
    return java.util.Arrays.stream(TigerGlobalConfiguration.resolvePlaceholders(value).split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .toList();
  }

  /**
   * Converts a textual chart item to an object node.
   *
   * @param chart YAML chart item
   * @return chart object node
   */
  private JsonNode chartObject(JsonNode chart) {
    if (chart.isTextual()) {
      return YAML.createObjectNode().put("type", chart.asText());
    }
    if (chart.isObject()) {
      return chart;
    }
    throw new IllegalArgumentException("Prometheus chart profile item must be a chart type string or object");
  }

  /**
   * Reads the chart type from a profile chart item.
   *
   * @param chart YAML chart item
   * @param profileId profile id for error reporting
   * @return chart type
   */
  private String chartType(JsonNode chart, String profileId) {
    return requiredText(chart, "type", profileId);
  }

  /**
   * Reads a YAML value from a series, chart or named target.
   *
   * @param config parsed YAML configuration
   * @param chart YAML chart node
   * @param series YAML series node
   * @param key direct property name on series or chart
   * @param targetKey property name on the inherited target
   * @param chartId chart id for error reporting
   * @return resolved inherited text value
   */
  private String inheritedText(
      JsonNode config,
      JsonNode chart,
      JsonNode series,
      String key,
      String targetKey,
      String chartId) {
    var directValue = series.path(key).asText("");
    if (!directValue.isBlank()) {
      return TigerGlobalConfiguration.resolvePlaceholders(directValue);
    }
    directValue = chart.path(key).asText("");
    if (!directValue.isBlank()) {
      return TigerGlobalConfiguration.resolvePlaceholders(directValue);
    }

    var targetId = series.path("target").asText("");
    if (targetId.isBlank()) {
      targetId = chart.path("target").asText("");
    }
    if (!targetId.isBlank()) {
      var target = target(config, targetId, chartId);
      var targetValue = target.path(targetKey).asText("");
      if (targetValue.isBlank() && !targetKey.equals(key)) {
        targetValue = target.path(key).asText("");
      }
      if (!targetValue.isBlank()) {
        return TigerGlobalConfiguration.resolvePlaceholders(targetValue);
      }
      if ("latencySpan".equals(targetKey)) {
        var latencies = target.path("latencies");
        if (latencies.isArray() && !latencies.isEmpty()) {
          var firstSpan = latencies.get(0).path("span").asText("");
          if (!firstSpan.isBlank()) {
            return TigerGlobalConfiguration.resolvePlaceholders(firstSpan);
          }
        }
      }
    } else if ("latencySpan".equals(targetKey)) {
      var latencies = chart.path("latencies");
      if (latencies.isArray() && !latencies.isEmpty()) {
        var firstSpan = latencies.get(0).path("span").asText("");
        if (!firstSpan.isBlank()) {
          return TigerGlobalConfiguration.resolvePlaceholders(firstSpan);
        }
      }
    }

    throw new IllegalArgumentException(
        "Prometheus chart '" + chartId + "' has a series with missing '" + key + "'");
  }

  /**
   * Formats a numeric axis or summary value.
   *
   * @param value numeric value
   * @return formatted value
   */
  private String formatNumber(double value) {
    if (!Double.isFinite(value)) {
      return "n/a";
    }
    if (Math.abs(value) >= 100d) {
      return String.format(Locale.ROOT, "%.0f", value);
    }
    if (Math.abs(value) >= 10d) {
      return String.format(Locale.ROOT, "%.1f", value);
    }
    return String.format(Locale.ROOT, "%.2f", value);
  }

  /**
   * Formats a numeric value with an optional unit.
   *
   * @param value numeric value
   * @param unit value unit
   * @return formatted value with unit suffix
   */
  private String formatNumberWithUnit(double value, String unit) {
    var formatted = formatNumber(value);
    if (unit == null || unit.isBlank() || "n/a".equals(formatted)) {
      return formatted;
    }
    return formatted + " " + unit;
  }

  /**
   * Builds an SVG line element.
   *
   * @param x1 start x coordinate
   * @param y1 start y coordinate
   * @param x2 end x coordinate
   * @param y2 end y coordinate
   * @param color stroke color
   * @param width stroke width
   * @return SVG line element
   */
  private String line(double x1, double y1, double x2, double y2, String color, int width) {
    return String.format(
        Locale.ROOT,
        "  <line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"%d\"/>%n",
        x1,
        y1,
        x2,
        y2,
        color,
        width);
  }

  /**
   * Builds an SVG text element.
   *
   * @param x x coordinate
   * @param y y coordinate
   * @param size font size
   * @param color fill color
   * @param weight font weight
   * @param content text content
   * @return SVG text element
   */
  private String text(double x, double y, int size, String color, String weight, String content) {
    return String.format(
        Locale.ROOT,
        "  <text x=\"%.2f\" y=\"%.2f\" font-family=\"Arial, sans-serif\" font-size=\"%d\" "
            + "font-weight=\"%s\" fill=\"%s\">%s</text>%n",
        x,
        y,
        size,
        weight,
        color,
        escapeXml(content));
  }

  /**
   * Escapes XML text nodes.
   *
   * @param value raw value
   * @return escaped XML value
   */
  private String escapeXml(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /**
   * Builds a filesystem-safe file name stem.
   *
   * @param title chart title
   * @return safe file name stem
   */
  private String safeFileName(String title) {
    var safe = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    safe = safe.replaceAll("^-+", "").replaceAll("-+$", "");
    return safe.isBlank() ? "prometheus-chart" : safe;
  }

  /**
   * Abbreviates long legend labels.
   *
   * @param value raw label
   * @param maxLength maximum label length
   * @return abbreviated label
   */
  private String abbreviate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value == null ? "" : value;
    }
    return value.substring(0, Math.max(0, maxLength - 1)) + "...";
  }

  /**
   * Chart query definition.
   *
   * @param name chart series name
   * @param query PromQL expression
   * @param unit displayed value unit
   */
  private record ChartQuery(String name, String query, String unit) {

    /**
     * Creates a chart query without a value unit.
     *
     * @param name chart series name
     * @param query PromQL expression
     */
    private ChartQuery(String name, String query) {
      this(name, query, "");
    }
  }

  /**
   * Chart series data.
   *
   * @param name chart series name
   * @param points chart points
   * @param unit displayed value unit
   * @param summary optional legend summary
   */
  private record ChartSeries(String name, List<ChartPoint> points, String unit, String summary) {
  }

  /**
   * One chart point.
   *
   * @param timestamp point timestamp
   * @param value point value
   */
  private record ChartPoint(Instant timestamp, double value) {
  }

  /**
   * Chart value scaling mode.
   */
  private enum ChartScale {
    RAW,
    NORMALIZED;

    /**
     * Parses the configured scale value.
     *
     * @param value raw scale value
     * @return chart scale
     */
    private static ChartScale from(String value) {
      return switch (value.toLowerCase(Locale.ROOT).trim()) {
        case "raw" -> RAW;
        case "normalized", "normalisiert" -> NORMALIZED;
        default -> throw new IllegalArgumentException("Unsupported Prometheus chart scale: " + value);
      };
    }
  }
}
