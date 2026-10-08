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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.serenitybdd.model.di.ModelInfrastructure;
import net.serenitybdd.reports.email.SinglePageHtmlReporter;
import net.serenitybdd.reports.json.JsonSummaryReporter;
import net.thucydides.core.reports.ExtendedReport;
import net.thucydides.core.reports.html.HtmlAggregateStoryReporter;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.reports.OutcomeFormat;
import net.thucydides.model.reports.TestOutcomeLoader;
import net.thucydides.model.reports.json.JSONTestOutcomeReporter;
import net.thucydides.model.requirements.FileSystemRequirements;

/** Generates the Serenity extended reports used by the runtime-only quality-gate image. */
public final class SerenityExtendedReportsMain {

  static final List<String> REPORT_NAMES = List.of("single-page-html", "json-summary");

  private SerenityExtendedReportsMain() {
    // utility class
  }

  /**
   * Generate the standard and configured extended reports from Serenity JSON outcomes.
   *
   * @param args source directory, output directory, and requirements directory
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Expected Serenity source, output, and requirements directories");
    }

    var sourceDirectory = Path.of(args[0]).toAbsolutePath().normalize();
    var requirementsDirectory = Path.of(args[2]).toAbsolutePath().normalize();
    if (!Files.isDirectory(sourceDirectory)) {
      throw new IllegalArgumentException(
          "Serenity source directory does not exist: " + sourceDirectory);
    }
    if (!Files.isDirectory(requirementsDirectory)) {
      throw new IllegalArgumentException(
          "Serenity requirements directory does not exist: " + requirementsDirectory);
    }

    // Test execution and report aggregation use separate JVMs in the runtime image.
    enrichOutcomesWithRequirementTags(sourceDirectory);

    var outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
    ModelInfrastructure.getConfiguration().setOutputDirectory(sourceDirectory.toFile());
    var requirements = new FileSystemRequirements(requirementsDirectory.toString());
    var standardReport = new HtmlAggregateStoryReporter("default", requirements);
    standardReport.setSourceDirectory(sourceDirectory.toFile());
    standardReport.setOutputDirectory(outputDirectory.toFile());
    standardReport.setGenerateTestOutcomeReports();
    standardReport.generateReportsForTestResultsFrom(sourceDirectory.toFile());

    for (var report : extendedReports(sourceDirectory, outputDirectory)) {
      report.setSourceDirectory(sourceDirectory);
      report.setOutputDirectory(outputDirectory);
      var output = report.generateReport();
      if (output == null || !Files.isRegularFile(output)) {
        throw new IllegalStateException(
            "Serenity report '" + report.getName() + "' did not produce an output file");
      }
      System.out.printf("Generated Serenity report '%s' at %s%n", report.getName(), output);
    }
  }

  /**
   * Create the extended reporters in the same order as the Tiger Maven plugin.
   *
   * @param sourceDirectory Serenity outcome directory
   * @param outputDirectory report output directory
   * @return single-page HTML and JSON summary report providers
   */
  static List<ExtendedReport> extendedReports(
      Path sourceDirectory, Path outputDirectory) {
    var singlePageReporter = new SinglePageHtmlReporter();
    var jsonReporter = new JsonSummaryReporter(
        new SystemEnvironmentVariables(), sourceDirectory, outputDirectory);
    return List.of(singlePageReporter, jsonReporter);
  }

  /**
   * Add the requirements tags used by the extended summaries to every Serenity outcome.
   *
   * @param sourceDirectory Serenity outcome directory
   */
  static void enrichOutcomesWithRequirementTags(Path sourceDirectory) throws Exception {
    var outcomes = TestOutcomeLoader.loadTestOutcomes()
        .inFormat(OutcomeFormat.JSON)
        .from(sourceDirectory.toFile())
        .withRequirementsTags();
    var reporter = new JSONTestOutcomeReporter();
    reporter.setOutputDirectory(sourceDirectory.toFile());
    for (var outcome : outcomes.getOutcomes()) {
      reporter.generateReportFor(outcome);
    }
  }
}
