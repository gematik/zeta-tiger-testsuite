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

package de.gematik.zeta.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.thucydides.core.reports.ExtendedReport;
import net.thucydides.model.domain.ReportType;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link SerenityExtendedReportsMain}. */
class SerenityExtendedReportsMainTest {

  @TempDir
  Path tempDir;

  /** Verifies that the extended reporters use the same generation order as Maven. */
  @Test
  void createsExtendedReportersInMavenOrder() {
    assertThat(SerenityExtendedReportsMain.extendedReports(tempDir, tempDir))
        .extracting(ExtendedReport::getName)
        .containsExactlyElementsOf(SerenityExtendedReportsMain.REPORT_NAMES);
  }

  /** Verifies that both configured reports are generated from a Serenity JSON outcome. */
  @Test
  void generatesSinglePageAndJsonSummaryReports() throws Exception {
    var sourceDirectory = Files.createDirectory(tempDir.resolve("source"));
    var requirementsDirectory = Files.createDirectory(tempDir.resolve("features"));
    Files.writeString(requirementsDirectory.resolve("example_feature.feature"), """
        Feature: Example feature

          Scenario: Successful scenario
            Given a fulfilled precondition
        """);
    var outputDirectory = Files.createDirectory(tempDir.resolve("output"));
    var story = Story.withIdAndPath(
        "example_feature", "Example feature", "example_feature").asFeature();
    var outcome = TestOutcome.forTestInStory("Successful scenario", story)
        .withResult(TestResult.SUCCESS);
    var outcomeReport = sourceDirectory.resolve(outcome.getReportName(ReportType.JSON));
    Files.writeString(outcomeReport, outcome.toJson());

    Files.writeString(
        sourceDirectory.resolve(DeploymentVersions.SERENITY_BUILD_INFO_FILE), """
            {
              "zeta-staging/guard-123": {
                "sectionName": "zeta-staging/guard-123",
                "values": {
                  "guard [container]":
                    "zeta/zeta-guard/guard:1.2.3 | zeta/zeta-guard/guard@sha256:abc"
                }
              }
            }
            """);

    SerenityExtendedReportsMain.main(new String[]{
        sourceDirectory.toString(),
        outputDirectory.toString(),
        requirementsDirectory.toString()
    });

    assertThat(outputDirectory.resolve("index.html")).isNotEmptyFile();
    assertThat(outputDirectory.resolve("serenity-summary.html")).isNotEmptyFile();
    assertThat(outputDirectory.resolve("serenity-summary.json")).isNotEmptyFile();
    var summary = new ObjectMapper().readTree(
        outputDirectory.resolve("serenity-summary.json").toFile());
    assertThat(summary.path("coverage").isArray()).isTrue();
    assertThat(summary.path("coverage").isEmpty()).isFalse();
    assertThat(summary.path("coverage").toString()).contains("Example feature");
    var enrichedOutcome = new ObjectMapper().readTree(outcomeReport.toFile());
    assertThat(enrichedOutcome.path("tags").findValuesAsText("type")).contains("feature");

    var indexHtml = Files.readString(outputDirectory.resolve("index.html"));
    var requirementLink = Pattern.compile(
        "<a href=\"([a-f0-9]{64}\\.html)\">Example feature</a>")
        .matcher(indexHtml);
    assertThat(requirementLink.find()).isTrue();
    assertThat(outputDirectory.resolve(requirementLink.group(1))).isNotEmptyFile();
    assertThat(outputDirectory.resolve("build-info.html"))
        .content()
        .contains(
            "zeta-staging/guard-123",
            "guard [container]",
            "zeta/zeta-guard/guard:1.2.3",
            "zeta/zeta-guard/guard@sha256:abc")
        .doesNotContain("Item 0001");
  }
}
