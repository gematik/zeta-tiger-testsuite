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
package de.gematik.zeta.perf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * JMeter test runner with dynamic template substitution. Handles JMX template rendering, parameter
 * substitution, and test execution.
 */
@Slf4j
public class JMeterRunner {

  private static final String JMETER_HOME = "tools/apache-jmeter-5.6.3";
  private final String jmeterCommand;

  public JMeterRunner() {
    this.jmeterCommand = resolveJMeterCommand();
  }

  /**
   * Executes a JMeter test with the given configuration.
   *
   * @param config test configuration including plan template, properties, and output settings
   * @throws Exception if template rendering, execution, or I/O operations fail
   */
  public void runTest(JMeterTestConfig config) throws Exception {
    log.info("Starting JMeter test: plan={}, targetRps={}",
        config.getPlanTemplate().getFileName(), config.getTargetRps());

    Path renderedPlan = renderTestPlan(config);
    List<String> command = buildCommand(renderedPlan, config);

    createOutputDirectories(config);
    executeJMeter(command);
    logResults(config);

    log.info("JMeter test completed successfully");
  }

  /**
   * Renders the JMX template by applying all necessary substitutions.
   */
  private Path renderTestPlan(JMeterTestConfig config) throws Exception {
    Path templatePath = config.getPlanTemplate().toAbsolutePath().normalize();
    FileUtils.requireFileExists(templatePath);

    String template = Files.readString(templatePath, StandardCharsets.UTF_8);
    String rendered = applySubstitutions(template, config);

    Path outputDir = config.getOutputDirectory() != null ?
        config.getOutputDirectory() : Path.of("target", "jmeter");

    Path renderedPlan = outputDir.resolve(
        FileUtils.getNameWithoutExtension(templatePath) + ".rendered.jmx");

    FileUtils.ensureParentDirectories(renderedPlan);
    Files.writeString(renderedPlan, rendered, StandardCharsets.UTF_8);

    log.debug("Rendered plan: {} -> {}", templatePath.getFileName(), renderedPlan.getFileName());
    return renderedPlan;
  }

  /**
   * Applies all template substitutions including BASE_URL, TARGET_SPM, and dynamic JMeter
   * properties.
   */
  private String applySubstitutions(String template, JMeterTestConfig config) {
    String result = template;
    Map<String, String> jmeterProps = config.getJMeterProperties();

    log.debug("Starting substitutions with {} properties", jmeterProps.size());

    // Core substitutions
    int targetSpm = Math.max(0, config.getTargetRps() * 60);
    result = result.replace("@@TARGET_SPM@@", String.valueOf(targetSpm));
    result = substituteBaseUrlPatterns(result, config.getBaseUrl());

    // Dynamic pattern substitution - handles all ${__P(KEY,DEFAULT)} patterns
    result = substituteDynamicPatterns(result, jmeterProps);

    // Custom parameter substitutions (@@PLACEHOLDER@@ format)
    result = substituteCustomParameters(result, config.getParameters());

    // Final cleanup of any problematic unresolved patterns
    result = cleanupUnresolvedPatterns(result);

    // Post-substitution cleanup - remove empty header elements
    result = removeEmptyHeaderElements(result);

    log.debug("Applied substitutions: TARGET_SPM={}, BASE_URL={}", targetSpm, config.getBaseUrl());
    return result;
  }

  /**
   * Dynamically substitutes all JMeter property patterns ${__P(KEY,DEFAULT)}. Uses regex to find
   * patterns and replaces them with actual values or defaults.
   */
  private String substituteDynamicPatterns(String template, Map<String, String> properties) {
    Pattern jmeterPattern = Pattern.compile("\\$\\{__P\\(([^,)]+),([^)]*)\\)\\}");

    StringBuffer result = new StringBuffer();
    Matcher matcher = jmeterPattern.matcher(template);
    int substitutionCount = 0;

    while (matcher.find()) {
      String key = matcher.group(1);
      String defaultValue = matcher.group(2);

      String value = properties.getOrDefault(key, defaultValue);

      // XML-escape REQUEST_BODY to prevent parsing errors
      if ("REQUEST_BODY".equals(key) && value != null && !value.equals(defaultValue)) {
        value = escapeXml(value);
      }

      matcher.appendReplacement(result, Matcher.quoteReplacement(value));

      substitutionCount++;
      log.debug("Substituted ${__P({},{})} -> {}", key, defaultValue,
          "REQUEST_BODY".equals(key) ? "[REDACTED " + value.length() + " chars]" :
              (value.length() > 50 ? value.substring(0, 50) + "..." : value));
    }

    matcher.appendTail(result);
    log.info("Applied {} dynamic pattern substitutions", substitutionCount);

    return result.toString();
  }

  /**
   * Substitutes all BASE_URL patterns with the configured base URL.
   */
  private String substituteBaseUrlPatterns(String template, String baseUrl) {
    String[] baseUrlPatterns = {
        "${BASE_URL}",
        "${__P(BASE_URL,http://localhost:9999)}",
        "${__P(BASE_URL,http://localhost:9999/achelos_testfachdienst/hellozeta)}"
    };

    String result = template;
    for (String pattern : baseUrlPatterns) {
      if (result.contains(pattern)) {
        result = result.replace(pattern, baseUrl);
        log.debug("Replaced BASE_URL pattern: {} -> {}", pattern, baseUrl);
      }
    }

    return result;
  }

  /**
   * Substitutes custom parameters with @@PLACEHOLDER@@ format.
   */
  private String substituteCustomParameters(String template, Map<String, String> parameters) {
    String result = template;
    for (Map.Entry<String, String> param : parameters.entrySet()) {
      String placeholder = "@@" + param.getKey() + "@@";
      result = result.replace(placeholder, param.getValue());
      log.debug("Replaced custom parameter: {} -> {}", placeholder, param.getValue());
    }
    return result;
  }

  /**
   * Cleans up any remaining unresolved patterns that could cause issues. Applies safe defaults for
   * problematic patterns like empty header names.
   */
  private String cleanupUnresolvedPatterns(String template) {
    Pattern remainingPattern = Pattern.compile("\\$\\{__P\\(([^,)]+),([^)]*)\\)\\}");
    Matcher matcher = remainingPattern.matcher(template);

    StringBuffer result = new StringBuffer();
    int cleanupCount = 0;

    while (matcher.find()) {
      String key = matcher.group(1);
      String defaultValue = matcher.group(2);

      String cleanValue = getCleanupValue(key, defaultValue);

      if (!cleanValue.equals(defaultValue)) {
        cleanupCount++;
        log.warn("Cleaned up problematic pattern: ${__P({},{})} -> '{}'", key, defaultValue,
            cleanValue);
      }

      matcher.appendReplacement(result, Matcher.quoteReplacement(cleanValue));
    }

    matcher.appendTail(result);

    if (cleanupCount > 0) {
      log.warn("Cleaned up {} problematic unresolved patterns", cleanupCount);
    }

    return result.toString();
  }

  /**
   * Removes entire header elements that have empty names to prevent HTTP parsing errors.
   */
  private String removeEmptyHeaderElements(String template) {
    // Pattern to match complete header elements with empty names
    Pattern emptyHeaderPattern = Pattern.compile(
        "<!--[^>]*Custom Header[^>]*-->\\s*" +
            "<elementProp[^>]+elementType=\"Header\"[^>]*>\\s*" +
            "<stringProp name=\"Header\\.name\">\\s*</stringProp>\\s*" +
            "<stringProp name=\"Header\\.value\">[^<]*</stringProp>\\s*" +
            "</elementProp>",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    String result = emptyHeaderPattern.matcher(template)
        .replaceAll("<!-- Empty header removed -->");

    // Also remove headers where name is just whitespace or placeholder text
    Pattern problematicHeaderPattern = Pattern.compile(
        "<elementProp[^>]+elementType=\"Header\"[^>]*>\\s*" +
            "<stringProp name=\"Header\\.name\">\\s*(X-Skip-Header|\\s*)\\s*</stringProp>\\s*" +
            "<stringProp name=\"Header\\.value\">[^<]*</stringProp>\\s*" +
            "</elementProp>",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    result = problematicHeaderPattern.matcher(result)
        .replaceAll("<!-- Problematic header removed -->");

    log.debug("Removed empty header elements from template");
    return result;
  }

  /**
   * Returns a safe cleanup value for potentially problematic patterns.
   */
  private String getCleanupValue(String key, String defaultValue) {
    // Header names cannot be empty - use safe placeholder
    if (key.endsWith("_NAME") && defaultValue.trim().isEmpty()) {
      return "X-Skip-Header";
    }

    // Provide reasonable defaults for numeric parameters
    if (key.matches("(THREADS|DURATION_S|RAMP_S|THINK_MS|TARGET_RPS)")) {
      return switch (key) {
        case "THREADS" -> "1";
        case "DURATION_S" -> "30";
        case "RAMP_S" -> "5";
        case "THINK_MS" -> "100";
        case "TARGET_RPS" -> "10";
        default -> defaultValue;
      };
    }

    return defaultValue;
  }

  /**
   * Escapes XML special characters to prevent parsing errors.
   */
  private String escapeXml(String text) {
    if (text == null) {
      return "";
    }

    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  /**
   * Builds the JMeter command line arguments. Excludes REQUEST_BODY and empty header parameters
   * from command line.
   */
  private List<String> buildCommand(Path renderedPlan, JMeterTestConfig config) {
    List<String> command = new ArrayList<>();
    command.add(jmeterCommand);
    command.add("-n");
    command.add("-t");
    command.add(renderedPlan.toString());

    // Output options
    if (config.getJtlOutput() != null) {
      command.add("-l");
      command.add(config.getJtlOutput().toString());
    }

    if (config.getHtmlOutput() != null) {
      command.add("-e");
      command.add("-o");
      command.add(config.getHtmlOutput().toString());
    }

    if (config.getPropertiesFile() != null) {
      FileUtils.requireFileExists(config.getPropertiesFile());
      command.add("-q");
      command.add(config.getPropertiesFile().toString());
    }

    // Add JMeter properties as command line arguments (with filtering)
    config.getJMeterProperties().entrySet().stream()
        .filter(entry -> entry.getValue() != null && !entry.getValue().trim().isEmpty())
        .filter(entry -> !"REQUEST_BODY".equals(entry.getKey()))  // Template handles this
        .filter(entry -> !isEmptyHeaderParameter(entry.getKey(),
            entry.getValue())) // Skip empty headers
        .forEach(entry -> {
          String propertyArg = "-J" + entry.getKey() + "=" + entry.getValue();
          command.add(propertyArg);
          log.debug("Added command-line property: {}={}", entry.getKey(), entry.getValue());
        });

    // Additional parameters
    config.getParameters().entrySet().stream()
        .filter(entry -> !"-e".equals(entry.getKey()))
        .forEach(entry -> {
          command.add(entry.getKey());
          if (entry.getValue() != null && !entry.getValue().isEmpty()) {
            command.add(entry.getValue());
          }
        });

    return command;
  }

  /**
   * Checks if a parameter represents an empty header that should be excluded from command line.
   */
  private boolean isEmptyHeaderParameter(String key, String value) {
    // Skip header name parameters that are empty (would cause "empty headers not allowed")
    if (key.matches("HEADER[0-9]+_NAME") && (value == null || value.trim().isEmpty())) {
      log.debug("Skipping empty header name parameter: {}", key);
      return true;
    }
    return false;
  }

  /**
   * Creates necessary output directories for JTL and HTML reports.
   */
  private void createOutputDirectories(JMeterTestConfig config) throws Exception {
    if (config.getJtlOutput() != null) {
      FileUtils.ensureParentDirectories(config.getJtlOutput());
    }
    if (config.getHtmlOutput() != null) {
      FileUtils.ensureParentDirectories(config.getHtmlOutput().resolve("index.html"));
    }
  }

  /**
   * Executes the JMeter process and streams output to logs.
   */
  private void executeJMeter(List<String> command) throws Exception {
    log.info("Executing: {}", String.join(" ", command));

    Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .start();

    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.info("[jmeter] {}", line);
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new RuntimeException("JMeter failed with exit code: " + exitCode);
    }
  }

  /**
   * Logs the locations of generated JTL and HTML reports.
   */
  private void logResults(JMeterTestConfig config) {
    if (config.getJtlOutput() != null && Files.exists(config.getJtlOutput())) {
      log.info("JTL output: {}", config.getJtlOutput().toAbsolutePath());
    }

    if (config.getHtmlOutput() != null) {
      Path index = config.getHtmlOutput().resolve("index.html");
      if (Files.exists(index)) {
        log.info("HTML report: {}", index.toAbsolutePath());
      }
    }
  }

  /**
   * Resolves the JMeter executable path from the Maven-extracted directory.
   */
  private String resolveJMeterCommand() {
    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    String exe = isWindows ? "jmeter.bat" : "jmeter";
    Path jmeterPath = Path.of(JMETER_HOME, "bin", exe).toAbsolutePath();
    if (!Files.isRegularFile(jmeterPath)) {
      throw new IllegalStateException("JMeter executable not found at " + jmeterPath);
    }
    return jmeterPath.toString();
  }
}