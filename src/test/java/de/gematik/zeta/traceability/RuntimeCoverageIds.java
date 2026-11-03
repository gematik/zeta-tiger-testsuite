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

package de.gematik.zeta.traceability;

import io.cucumber.plugin.event.TestCaseFinished;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared identifier, path and small parsing helpers for runtime coverage.
 */
final class RuntimeCoverageIds {

  static final Pattern AFO_TAG = Pattern.compile(
      "^(?:[A-Z0-9]+-)?A_\\d+(?:-\\d+)?(?:_\\d+)?$",
      Pattern.CASE_INSENSITIVE);
  static final Pattern TEST_ASPECT_TAG = Pattern.compile(
      "^TA_(?:[A-Z0-9]+-)?A_\\d+(?:-\\d+)?_\\d+$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern TEST_ASPECT_REQUIREMENT = Pattern.compile(
      "TA_((?:[A-Z0-9]+-)?A_\\d+(?:-\\d+)?)(?:_\\d+)?",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern SCENARIO_OUTLINE_PLACEHOLDER = Pattern.compile("<[^>]+>");

  private RuntimeCoverageIds() {
  }

  /**
   * Resolve requirement and test aspect identifiers from Cucumber tags.
   *
   * @param tags Cucumber tags
   * @param requirementIds target requirement identifiers
   * @param testAspectIds target test aspect identifiers
   */
  static void resolveTags(Collection<String> tags, Set<String> requirementIds,
      Set<String> testAspectIds) {
    tags.stream().filter(Objects::nonNull).forEach(tag -> {
      var id = normalizeTag(tag);
      if (AFO_TAG.matcher(id).matches()) {
        requirementIds.add(id);
      } else if (TEST_ASPECT_TAG.matcher(id).matches()) {
        testAspectIds.add(id);
        resolveRequirementFromTestAspect(id).ifPresent(requirementIds::add);
      }
    });
  }

  /**
   * Resolve the AFO id from a TA identifier.
   *
   * @param testAspectId test aspect identifier
   * @return owning requirement id if encoded in the TA id
   */
  static Optional<String> resolveRequirementFromTestAspect(String testAspectId) {
    var matcher = TEST_ASPECT_REQUIREMENT.matcher(testAspectId);
    return matcher.find() ? Optional.ofNullable(matcher.group(1)) : Optional.empty();
  }

  /**
   * Normalize a Cucumber tag for matching.
   *
   * @param tagName raw tag name
   * @return upper-case tag without a leading at sign
   */
  static String normalizeTag(String tagName) {
    if (tagName == null) {
      return "";
    }
    var trimmed = tagName.trim();
    return (trimmed.startsWith("@") ? trimmed.substring(1) : trimmed).toUpperCase(Locale.ROOT);
  }

  /**
   * Normalize an identifier field for matching.
   *
   * @param value raw identifier
   * @return upper-case normalized identifier
   */
  static String normalizeId(String value) {
    return defaultString(value).trim().toUpperCase(Locale.ROOT);
  }

  /**
   * Normalize a set of identifiers for matching.
   *
   * @param values raw identifiers
   * @return ordered set of normalized identifiers
   */
  static Set<String> normalizeIds(Set<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return values.stream()
        .map(RuntimeCoverageIds::normalizeId)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Normalize a feature path for matching.
   *
   * @param value raw path
   * @return normalized path
   */
  static String normalizePath(String value) {
    var normalized = defaultString(value).trim().replace('\\', '/');
    if (normalized.startsWith("classpath:")) {
      normalized = normalized.substring("classpath:".length());
    }
    if (normalized.startsWith("file:")) {
      try {
        return Path.of(URI.create(normalized)).toString().replace('\\', '/');
      } catch (IllegalArgumentException exception) {
        normalized = normalized.substring("file:".length());
      }
    }
    while (normalized.startsWith("//")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  /**
   * Match a configured feature path against a runtime feature URI or path.
   *
   * @param expected configured path
   * @param actual   runtime path
   * @return true if the paths refer to the same suffix
   */
  static boolean pathMatches(String expected, String actual) {
    var normalizedExpected = normalizePath(expected);
    var normalizedActual = normalizePath(actual);
    if (normalizedExpected.isBlank() || normalizedActual.isBlank()) {
      return false;
    }
    return Objects.equals(normalizedExpected, normalizedActual)
        || normalizedActual.endsWith("/" + normalizedExpected)
        || normalizedExpected.endsWith("/" + normalizedActual);
  }

  /**
   * Match scenario names, treating Cucumber scenario-outline placeholders as wildcard segments.
   *
   * @param expected configured or runtime scenario name
   * @param actual   configured or runtime scenario name
   * @return true if names are equal or one outline template matches the other name
   */
  static boolean scenarioNameMatches(String expected, String actual) {
    var normalizedExpected = defaultString(expected).trim();
    var normalizedActual = defaultString(actual).trim();
    if (normalizedExpected.isBlank() || normalizedActual.isBlank()) {
      return false;
    }
    return Objects.equals(normalizedExpected, normalizedActual)
        || scenarioTemplateMatches(normalizedExpected, normalizedActual)
        || scenarioTemplateMatches(normalizedActual, normalizedExpected);
  }

  /**
   * Match one scenario-outline template against one concrete scenario name.
   *
   * @param template scenario name that may contain placeholders such as {@code <client_name>}
   * @param candidate concrete or partially concrete scenario name
   * @return true if the template matches the candidate
   */
  private static boolean scenarioTemplateMatches(String template, String candidate) {
    var matcher = SCENARIO_OUTLINE_PLACEHOLDER.matcher(template);
    if (!matcher.find()) {
      return false;
    }
    var pattern = new StringBuilder("^");
    var previousEnd = 0;
    do {
      pattern.append(Pattern.quote(template.substring(previousEnd, matcher.start())));
      pattern.append(".*");
      previousEnd = matcher.end();
    } while (matcher.find());
    pattern.append(Pattern.quote(template.substring(previousEnd)));
    pattern.append("$");
    return Pattern.compile(pattern.toString()).matcher(candidate).matches();
  }

  /**
   * Normalize a feature path for event/JSON deduplication.
   *
   * @param value raw feature URI or path
   * @return absolute normalized path, or the normalized value when it is not a local path
   */
  private static String normalizeScenarioKeyPath(String value) {
    var normalized = normalizePath(value);
    if (normalized.isBlank()) {
      return normalized;
    }
    try {
      return Path.of(normalized).toAbsolutePath().normalize().toString().replace('\\', '/');
    } catch (InvalidPathException exception) {
      return normalized;
    }
  }

  /**
   * Convert null to empty string.
   *
   * @param value raw value
   * @return empty string for null values
   */
  static String defaultString(String value) {
    return value == null ? "" : value;
  }

  /**
   * Parse an integer or fall back to zero.
   *
   * @param value raw integer text
   * @return parsed integer or zero
   */
  static int parseInt(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  /**
   * Remove a UTF-8 BOM from the first field if present.
   *
   * @param value value to normalize
   * @return value without BOM prefix
   */
  static String stripBom(String value) {
    if (value == null || value.isEmpty() || value.charAt(0) != '\ufeff') {
      return value;
    }
    return value.substring(1);
  }

  /**
   * Remove the Asciidoc suffix if present.
   *
   * @param value file name
   * @return file name without .adoc suffix
   */
  static String stripAdocSuffix(String value) {
    return value.endsWith(".adoc") ? value.substring(0, value.length() - ".adoc".length()) : value;
  }

  /**
   * Build a stable scenario key for deduplication.
   *
   * @param uri  feature URI
   * @param name scenario name
   * @param line scenario line
   * @return stable scenario key
   */
  static String buildScenarioKey(String uri, String name, Integer line) {
    var normalizedUri = normalizeScenarioKeyPath(uri);
    var normalizedName = defaultString(name).trim();
    var normalizedLine = line == null ? "" : Integer.toString(line);
    return normalizedUri + "#" + normalizedLine + "#" + normalizedName;
  }

  /**
   * Summarize a failed scenario error.
   *
   * @param event finished test case event
   * @return compact error summary
   */
  static String summarizeError(TestCaseFinished event) {
    var scenarioName = event.getTestCase() == null ? "" : event.getTestCase().getName();
    var error = event.getResult() == null ? null : event.getResult().getError();
    var detail = "";
    if (error != null) {
      var message = Optional.ofNullable(error.getMessage()).orElse("").trim();
      if (message.isBlank()) {
        detail = error.getClass().getSimpleName();
      } else {
        detail = error.getClass().getSimpleName() + ": " + message;
      }
    }
    if (detail.isBlank()) {
      detail = "Unbekannter Fehler";
    }
    if (scenarioName == null || scenarioName.isBlank()) {
      return detail;
    }
    return scenarioName + " -> " + detail;
  }
}
