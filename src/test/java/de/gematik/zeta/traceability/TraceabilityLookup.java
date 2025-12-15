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

package de.gematik.zeta.traceability;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable container for the aggregated traceability data produced by the docs pipeline.
 */
@Slf4j
public record TraceabilityLookup(
    Map<String, RequirementInfo> requirements,
    Map<String, TestAspectInfo> testAspects,
    Map<String, UseCaseInfo> useCases,
    Map<TraceabilityKey, TraceabilityLinkInfo> linksByKey,
    Map<String, List<TraceabilityLinkInfo>> linksByUseCase,
    Map<String, List<TraceabilityLinkInfo>> linksByScenario,
    Map<String, String> anchorIndex,
    boolean available
) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(
      new JsonFactory().enable(JsonParser.Feature.AUTO_CLOSE_SOURCE));

  private static final List<Path> DEFAULT_JSON_LOCATIONS = List.of(
      Paths.get("target/generated-docs/traceability.json"),
      Paths.get("target/generated/traceability.json"),
      Paths.get("target/site/serenity/traceability.json")
  );

  /**
   * Canonical constructor that defensively copies all collection inputs.
   */
  public TraceabilityLookup {
    requirements = Map.copyOf(requirements);
    testAspects = Map.copyOf(testAspects);
    useCases = Map.copyOf(useCases);
    linksByKey = Map.copyOf(linksByKey);
    linksByUseCase = immutableListMap(linksByUseCase);
    linksByScenario = immutableListMap(linksByScenario);
    anchorIndex = Map.copyOf(anchorIndex);
  }

  /**
   * Load the most recent traceability snapshot.
   */
  public static @NotNull TraceabilityLookup load() {
    var jsonPath = resolveTraceabilityJson();
    if (jsonPath == null) {
      log.warn("Traceability JSON not found – Serenity traceability blocks will be skipped.");
      return unavailable();
    }
    try (Reader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
      var root = OBJECT_MAPPER.readTree(reader);
      var requirements = parseRequirements(root.path("requirements"));
      var testAspects = parseTestAspects(root.path("test_aspects"));
      var useCases = parseUseCases(root.path("use_cases"));
      var links = parseLinks(root.path("traceability"));
      var linksByKey = buildLinkIndex(links);
      var linksByUseCase = buildUseCaseIndex(links);
      var linksByScenario = buildScenarioIndex(links);
      var anchorIndex = buildAnchorIndex(useCases);
      log.info("Loaded {} traceability links from {}", links.size(), jsonPath);
      return new TraceabilityLookup(requirements, testAspects, useCases, linksByKey,
          linksByUseCase, linksByScenario, anchorIndex, true);
    } catch (IOException exception) {
      log.warn("Unable to read traceability data from {}", jsonPath, exception);
      return unavailable();
    }
  }

  /**
   * Render the collected entries as a Markdown table consumed by Serenity. Uses a plain pipe table
   * to keep Serenity's Markdown rendering stable.
   */
  private static @NotNull String render(@NotNull List<TraceabilityEntry> entries) {
    var builder = new StringBuilder();
    builder.append("| Anforderung | Testaspekt | Use Case | Letzter Status |\n");
    builder.append("|-------------|------------|----------|----------------|\n");
    entries.forEach(entry -> builder.append("| ")
        .append(formatIdentifier(entry.requirementId(), entry.requirementTitle()))
        .append(" | ")
        .append(formatIdentifier(entry.testAspectId(), entry.testAspectTitle()))
        .append(" | ")
        .append(formatUseCase(entry))
        .append(" | ")
        .append(entry.lastStatusHuman() == null ? "-" : escape(entry.lastStatusHuman()))
        .append(" |\n"));
    return builder.toString();
  }

  /**
   * Format identifiers as inline code to improve readability in the report.
   */
  private static @NotNull String formatIdentifier(String id, String title) {
    if (id == null && title == null) {
      return "-";
    }
    if (title == null || title.isBlank()) {
      return escape(id);
    }
    if (id == null || id.isBlank()) {
      return escape(title);
    }
    return "`" + escape(id) + "` " + escape(title);
  }

  /**
   * Provide a compact use case label showing the owning user story when available.
   */
  private static String formatUseCase(@NotNull TraceabilityEntry entry) {
    if (entry.useCaseTitle() == null || entry.useCaseTitle().isBlank()) {
      return escape(entry.useCaseAnchor());
    }
    var builder = new StringBuilder();
    if (entry.userStoryId() != null && !entry.userStoryId().isBlank()) {
      builder.append('[').append(escape(entry.userStoryId())).append("] ");
    }
    builder.append(escape(entry.useCaseTitle()));
    return builder.toString();
  }

  /**
   * Combine execution status and timestamp into a compact label.
   */
  private static String formatStatus(String status, String lastRunHuman, String lastSuccessHuman) {
    if ((status == null || status.isBlank()) && (lastRunHuman == null || lastRunHuman.isBlank())) {
      return lastSuccessHuman;
    }
    var label = switch (status == null ? "" : status.toLowerCase()) {
      case "passed" -> "bestanden";
      case "failed" -> "fehlgeschlagen";
      case "skipped" -> "übersprungen";
      case "unknown" -> "unbekannt";
      default -> status;
    };
    if (lastRunHuman != null && !lastRunHuman.isBlank()) {
      return label + " (" + lastRunHuman + ")";
    }
    return label;
  }

  /**
   * Escape table cell content so Markdown parsing inside Serenity remains stable.
   *
   * @param value raw string to escape
   * @return escaped value or "-" when {@code null}
   */
  private static String escape(String value) {
    return value == null ? "-" : value.replace("|", "\\|");
  }

  /**
   * Convert the requirement JSON section into immutable value objects.
   */
  private static @NotNull Map<String, RequirementInfo> parseRequirements(@NotNull JsonNode node) {
    Map<String, RequirementInfo> map = new LinkedHashMap<>();
    for (var entry : node.properties()) {
      var value = entry.getValue();
      map.put(entry.getKey(), new RequirementInfo(entry.getKey(), value.path("title").asText("")));
    }
    return map;
  }

  /**
   * Convert the test-aspect section into immutable value objects.
   */
  private static @NotNull Map<String, TestAspectInfo> parseTestAspects(@NotNull JsonNode node) {
    Map<String, TestAspectInfo> map = new LinkedHashMap<>();
    for (var entry : node.properties()) {
      var value = entry.getValue();
      map.put(entry.getKey(), new TestAspectInfo(entry.getKey(),
          value.path("title").asText(""),
          value.path("requirement_id").asText("")));
    }
    return map;
  }

  /**
   * Convert use-case metadata into a lookup map.
   */
  private static @NotNull Map<String, UseCaseInfo> parseUseCases(@NotNull JsonNode node) {
    Map<String, UseCaseInfo> map = new LinkedHashMap<>();
    for (var entry : node.properties()) {
      var value = entry.getValue();
      var featureFiles = new ArrayList<String>();
      if (value.path("feature_files").isArray()) {
        value.path("feature_files").forEach(item -> featureFiles.add(item.asText()));
      }
      map.put(entry.getKey(), new UseCaseInfo(entry.getKey(),
          value.path("tag_id").asText(entry.getKey()),
          value.path("title").asText(entry.getKey()),
          value.path("user_story_id").asText(""),
          featureFiles));
    }
    return map;
  }

  /**
   * Parse the flattened requirement/test-aspect/use-case links.
   */
  private static List<TraceabilityLinkInfo> parseLinks(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    return StreamSupport.stream(node.spliterator(), false)
        .map(linkNode -> {
          var requirement = linkNode.path("requirement").asText(null);
          var testAspect = linkNode.path("test_aspect").asText(null);
          var useCase = nullIfBlank(linkNode.path("use_case").asText(null));
          if (requirement == null || testAspect == null) {
            return null;
          }
          List<String> scenarios = new ArrayList<>();
          if (linkNode.path("scenarios").isArray()) {
            linkNode.path("scenarios").forEach(item -> {
              var scenario = item.asText(null);
              if (scenario != null && !scenario.isBlank()) {
                scenarios.add(scenario);
              }
            });
          }
          var lastRunHuman = nullIfBlank(linkNode.path("last_run_human").asText(null));
          var lastRunStatus = nullIfBlank(linkNode.path("last_run_status").asText(null));
          var lastSuccess = nullIfBlank(linkNode.path("last_success_human").asText(null));
          return new TraceabilityLinkInfo(requirement, testAspect, useCase,
              linkNode.path("implemented").asBoolean(false),
              linkNode.path("product_implemented").asBoolean(false),
              lastRunHuman, lastRunStatus, lastSuccess, scenarios);
        })
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Provide fast lookup by combining the test-aspect and use-case identifiers.
   */
  private static Map<TraceabilityKey, TraceabilityLinkInfo> buildLinkIndex(
      List<TraceabilityLinkInfo> links) {
    return links.stream()
        .filter(link -> link.useCaseAnchor() != null)
        .collect(Collectors.toMap(
            link -> new TraceabilityKey(link.testAspectId(), link.useCaseAnchor()),
            link -> link,
            (a, b) -> b,
            LinkedHashMap::new));
  }

  /**
   * Group traceability links by use case to support requirement-only scenarios.
   */
  private static Map<String, List<TraceabilityLinkInfo>> buildUseCaseIndex(
      List<TraceabilityLinkInfo> links) {
    return links.stream()
        .filter(link -> link.useCaseAnchor() != null && !link.useCaseAnchor().isBlank())
        .collect(Collectors.groupingBy(TraceabilityLinkInfo::useCaseAnchor,
            LinkedHashMap::new, Collectors.toList()));
  }

  /**
   * Group traceability links by scenario name for the fallback resolution mode.
   */
  private static Map<String, List<TraceabilityLinkInfo>> buildScenarioIndex(
      List<TraceabilityLinkInfo> links) {
    Map<String, List<TraceabilityLinkInfo>> map = new LinkedHashMap<>();
    for (var link : links) {
      for (var scenario : link.scenarios()) {
        map.computeIfAbsent(scenario, key -> new ArrayList<>()).add(link);
      }
    }
    return map;
  }

  /**
   * Map the various UseCase tag variants to their canonical anchor ids.
   */
  private static Map<String, String> buildAnchorIndex(Map<String, UseCaseInfo> useCases) {
    Map<String, String> map = new LinkedHashMap<>();
    for (var useCase : useCases.values()) {
      map.put(useCase.anchorId(), useCase.anchorId());
      map.putIfAbsent(useCase.tagId(), useCase.anchorId());
      for (var featurePath : useCase.featureFiles()) {
        var normalised = featurePath.replace('\\', '/');
        var segments = normalised.split("/");
        for (var i = 0; i < segments.length; i++) {
          var segment = segments[i];
          if (segment.startsWith("UseCase_")) {
            map.putIfAbsent(segment, useCase.anchorId());
            if (i > 0) {
              var storySegment = segments[i - 1];
              if (storySegment.startsWith("UserStory_")) {
                var derived = storySegment.replace("UserStory_", "UseCase_")
                    + "_" + segment.substring("UseCase_".length());
                map.putIfAbsent(derived, useCase.anchorId());
              }
            }
          }
        }
      }
    }
    return map;
  }

  /**
   * Create an immutable map with defensive copies of each list entry.
   */
  private static Map<String, List<TraceabilityLinkInfo>> immutableListMap(
      Map<String, List<TraceabilityLinkInfo>> source) {
    if (source.isEmpty()) {
      return Map.of();
    }
    Map<String, List<TraceabilityLinkInfo>> target = new LinkedHashMap<>();
    source.forEach((key, value) -> target.put(key, List.copyOf(value)));
    return Map.copyOf(target);
  }

  /**
   * Construct an empty lookup that signals unavailable traceability data.
   *
   * @return lookup placeholder with {@code available=false}
   */
  private static TraceabilityLookup unavailable() {
    return new TraceabilityLookup(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of(), false);
  }

  /**
   * Locate the latest traceability snapshot via overrides and default paths.
   *
   * @return path to the JSON file or {@code null} when not found
   */
  private static Path resolveTraceabilityJson() {
    var override = System.getProperty("traceability.json.path");
    if (override == null || override.isBlank()) {
      override = System.getenv("TRACEABILITY_JSON");
    }
    if (override != null && !override.isBlank()) {
      var candidate = Paths.get(override);
      if (Files.exists(candidate)) {
        return candidate;
      }
      log.warn("Configured traceability JSON {} does not exist", candidate);
    }
    return DEFAULT_JSON_LOCATIONS.stream().filter(Files::exists).findFirst().orElse(null);
  }

  /**
   * Convert blank strings to {@code null} to simplify later comparisons.
   *
   * @param value value to inspect
   * @return {@code null} when the input is blank, otherwise the unchanged input
   */
  private static String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * Creates the Markdown snippet for the current scenario if matching links exist.
   */
  public Optional<String> buildReport(String scenarioName, Collection<String> rawTags) {
    if (!available || rawTags == null) {
      return Optional.empty();
    }
    var entries = findEntries(scenarioName, rawTags);
    return entries.isEmpty() ? Optional.empty() : Optional.of(render(entries));
  }

  /**
   * Resolve all matching traceability links for the current scenario context.
   *
   * @param scenarioName serenity scenario name
   * @param rawTags      cucumber tags (possibly including {@code @})
   * @return immutable list of entries to render
   */
  private List<TraceabilityEntry> findEntries(String scenarioName, Collection<String> rawTags) {
    var normalisedTags = normaliseTags(rawTags);

    var useCaseTags = collectTagsWithPrefix(normalisedTags, "UseCase");
    var testAspectTags = collectTagsWithPrefix(normalisedTags, "TA_");
    var requirementTags = collectTagsWithPrefix(normalisedTags, "A_");

    var useCaseAnchors = resolveUseCaseAnchors(useCaseTags);
    var result = new LinkedHashMap<TraceabilityKey, TraceabilityEntry>();

    if (!useCaseAnchors.isEmpty() && !testAspectTags.isEmpty()) {
      for (var anchor : useCaseAnchors) {
        for (var testAspect : testAspectTags) {
          var link = linksByKey.get(new TraceabilityKey(testAspect, anchor));
          putEntryIfPresent(result, link, testAspect, anchor);
        }
      }
    }

    if (result.isEmpty() && !useCaseAnchors.isEmpty() && !requirementTags.isEmpty()) {
      for (var anchor : useCaseAnchors) {
        var links = linksByUseCase.getOrDefault(anchor, List.of());
        for (var link : links) {
          if (requirementTags.contains(link.requirementId())) {
            putEntryIfPresent(result, link, link.testAspectId(), anchor);
          }
        }
      }
    }

    List<TraceabilityLinkInfo> scenarioLinks = List.of();
    if (scenarioName != null && !scenarioName.isBlank()) {
      scenarioLinks = linksByScenario.getOrDefault(scenarioName, List.of());
    }

    if (result.isEmpty() && !scenarioLinks.isEmpty()) {
      for (var link : scenarioLinks) {
        putEntryIfPresent(result, link, link.testAspectId(), link.useCaseAnchor());
      }
    }

    return List.copyOf(result.values());
  }

  /**
   * Convert raw Cucumber tags into normalised identifiers without leading {@code @}.
   *
   * @param rawTags tags reported by Cucumber
   * @return ordered set of normalised tags
   */
  private Set<String> normaliseTags(Collection<String> rawTags) {
    return rawTags.stream()
        .filter(Objects::nonNull)
        .map(tag -> tag.startsWith("@") ? tag.substring(1) : tag)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Collect all tags that share a given prefix.
   *
   * @param tags   normalised tags
   * @param prefix expected prefix (e.g. {@code UseCase})
   * @return ordered set of matching tags
   */
  private Set<String> collectTagsWithPrefix(Set<String> tags, String prefix) {
    return tags.stream()
        .filter(tag -> tag.startsWith(prefix))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Resolve use-case tags to the canonical anchors known to the documentation pipeline.
   *
   * @param useCaseTags tags collected from feature/scenario levels
   * @return ordered set of known anchor identifiers
   */
  private Set<String> resolveUseCaseAnchors(@NotNull Set<String> useCaseTags) {
    if (useCaseTags.isEmpty()) {
      return Set.of();
    }
    return useCaseTags.stream()
        .map(tag -> anchorIndex.getOrDefault(tag, tag))
        .filter(useCases::containsKey)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Convert a link definition to the flattened entry used in the Serenity table.
   *
   * @param link resolved link metadata
   * @return entry ready for rendering
   */
  private @NotNull TraceabilityEntry toEntry(@NotNull TraceabilityLinkInfo link) {
    var requirement = requirements.getOrDefault(link.requirementId(),
        new RequirementInfo(link.requirementId(), ""));
    var testAspect = testAspects.getOrDefault(link.testAspectId(),
        new TestAspectInfo(link.testAspectId(), "", link.requirementId()));
    var useCase = useCases.getOrDefault(link.useCaseAnchor(),
        new UseCaseInfo(link.useCaseAnchor(), link.useCaseAnchor(), link.useCaseAnchor(), "",
            List.of()));
    return new TraceabilityEntry(
        requirement.id(),
        requirement.title(),
        testAspect.id(),
        testAspect.title(),
        useCase.anchorId(),
        useCase.title(),
        useCase.userStoryId(),
        formatStatus(link.lastRunStatus(), link.lastRunHuman(), link.lastSuccessHuman())
    );
  }

  /**
   * Add a rendered entry to the result map if the underlying link exists. Preserves the first entry
   * for a given key to keep ordering deterministic.
   */
  private void putEntryIfPresent(Map<TraceabilityKey, TraceabilityEntry> result,
      TraceabilityLinkInfo link, String testAspectId, String useCaseAnchor) {
    if (link == null) {
      return;
    }
    var key = new TraceabilityKey(testAspectId, useCaseAnchor);
    result.putIfAbsent(key, toEntry(link));
  }

  /**
   * Minimal requirement representation loaded from the generated traceability payload.
   *
   * @param id    Requirement identifier (e.g. {@code A_12345})
   * @param title Optional requirement title
   */
  private record RequirementInfo(String id, String title) {

  }

  /**
   * Captures the identifier, title and owning requirement for a test aspect.
   *
   * @param id            Test aspect identifier (e.g. {@code TA_A_12345_01})
   * @param title         Optional human-readable name
   * @param requirementId Requirement this test aspect verifies
   */
  private record TestAspectInfo(String id, String title, String requirementId) {

  }

  /**
   * Describes a use case reference that bundles multiple feature files.
   *
   * @param anchorId     Canonical anchor that is referenced inside documentation
   * @param tagId        Original tag name used in feature files
   * @param title        Use case human-readable title
   * @param userStoryId  Owning user story identifier
   * @param featureFiles All feature files that contribute to this use case
   */
  private record UseCaseInfo(String anchorId, String tagId, String title, String userStoryId,
                             List<String> featureFiles) {

    private UseCaseInfo {
      featureFiles = featureFiles == null ? List.of() : List.copyOf(featureFiles);
    }
  }

  /**
   * Links a requirement/test aspect pair to a concrete use case.
   *
   * @param requirementId      Requirement identifier
   * @param testAspectId       Test aspect identifier
   * @param useCaseAnchor      Canonical use case anchor
   * @param implemented        {@code true} if scenarios exist for the combination
   * @param productImplemented {@code true} if tagged as implemented in the product
   * @param lastRunHuman       Human-readable timestamp of the most recent run
   * @param lastRunStatus      Status of the most recent run (passed/failed/skipped/unknown)
   * @param lastSuccessHuman   Human-readable timestamp of the last success
   * @param scenarios          Scenario names that cover this combination
   */
  private record TraceabilityLinkInfo(String requirementId, String testAspectId,
                                      String useCaseAnchor, boolean implemented,
                                      boolean productImplemented,
                                      String lastRunHuman, String lastRunStatus,
                                      String lastSuccessHuman, List<String> scenarios) {

    private TraceabilityLinkInfo {
      scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }
  }

  /**
   * Simple DTO used to render the traceability table for Serenity.
   *
   * @param requirementId    Requirement identifier
   * @param requirementTitle Requirement title
   * @param testAspectId     Test aspect identifier
   * @param testAspectTitle  Test aspect title
   * @param useCaseAnchor    Use case anchor
   * @param useCaseTitle     Use case title
   * @param userStoryId      User story identifier
   * @param lastStatusHuman  Last test status incl. timestamp in human readable form
   */
  private record TraceabilityEntry(String requirementId, String requirementTitle,
                                   String testAspectId, String testAspectTitle,
                                   String useCaseAnchor, String useCaseTitle,
                                   String userStoryId, String lastStatusHuman) {

  }

  /**
   * Map key describing a single test-aspect/use-case combination.
   *
   * @param testAspectId  Test aspect identifier
   * @param useCaseAnchor Use case anchor identifier
   */
  private record TraceabilityKey(String testAspectId, String useCaseAnchor) {

  }
}
