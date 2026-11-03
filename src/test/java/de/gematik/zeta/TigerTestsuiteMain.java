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

package de.gematik.zeta;

import static de.gematik.test.tiger.common.config.TigerConfigurationKeys.TIGER_DEFAULT_PROFILE;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerDirector;
import io.cucumber.junit.TigerCucumberRunner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Standalone entry point for running the Tiger/Cucumber testsuite without Maven.
 *
 * <p>Delegates execution to Tiger's JUnit Platform runner and exits non-zero when discovery yields
 * no tests or when scenarios fail.
 */
@Slf4j
public final class TigerTestsuiteMain {

  private static final String DEFAULT_GLUE = "de.gematik.test.tiger.glue,de.gematik.zeta";
  private static final String CUCUMBER_OUTPUT_DIR_PROPERTY = "zeta.cucumber.outputDirectory";
  private static final String DEFAULT_CUCUMBER_OUTPUT_DIR = "target/cucumber-parallel";

  private TigerTestsuiteMain() {
    // utility class
  }

  /**
   * Launch the testsuite using the Tiger JUnit runner.
   *
   * @param args optional arguments (currently ignored; configuration is read from system properties)
   */
  public static void main(String[] args) {
    int exitCode = 0;
    try {
      runCucumberSuite();
    } catch (RuntimeException ex) {
      exitCode = 1;
      log.error("Testsuite execution failed", ex);
    } finally {
      try {
        if (TigerDirector.isInitialized()) {
          TigerDirector.waitForAcknowledgedQuit();
        }
      } finally {
        System.exit(exitCode);
      }
    }
  }

  /**
   * Discover and execute all Cucumber features on the classpath.
   */
  private static void runCucumberSuite() {
    var cucumberOutputDir =
        System.getProperty(CUCUMBER_OUTPUT_DIR_PROPERTY, DEFAULT_CUCUMBER_OUTPUT_DIR);

    var glue = System.getProperty("cucumber.glue", DEFAULT_GLUE);
    var plugin = System.getProperty("cucumber.plugin", defaultPlugin(cucumberOutputDir));

    var builder =
        LauncherDiscoveryRequestBuilder.request()
            .configurationParameter("cucumber.glue", glue)
            .configurationParameter("cucumber.plugin", plugin)
            .configurationParameter("cucumber.publish.quiet", "true");

    builder.configurationParameter("cucumber.filter.tags", configureCucumberTags());

    var request = builder.selectors(DiscoverySelectors.selectClasspathResource("features")).build();

    var summaryListener = new SummaryGeneratingListener();
    TigerCucumberRunner.discoverAndRunTests(request, summaryListener);

    var summary = summaryListener.getSummary();
    var summaryOutput = new StringWriter();
    summary.printTo(new PrintWriter(summaryOutput));
    log.info("{}", summaryOutput);
    if (summary.getTestsFoundCount() == 0) {
      throw new IllegalStateException("No tests found (Cucumber features not discovered)");
    }
    if (summary.getTotalFailureCount() > 0) {
      var failureOutput = new StringWriter();
      summary.printFailuresTo(new PrintWriter(failureOutput));
      log.error("{}", failureOutput);
      throw new IllegalStateException("Tests failed: " + summary.getTotalFailureCount());
    }
  }

  /**
   * Build the default Cucumber plugin configuration for the generated reports.
   *
   * @param cucumberOutputDir report output directory for Cucumber JSON and JUnit XML files
   * @return plugin configuration string for the Cucumber engine
   */
  private static String defaultPlugin(String cucumberOutputDir) {
    return "io.cucumber.core.plugin.TigerSerenityReporterPlugin"
        + ",json:" + cucumberOutputDir + "/main.json"
        + ",junit:" + cucumberOutputDir + "/cucumber.xml"
        + ",io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm"
        + ",de.gematik.zeta.traceability.RuntimeCoveragePlugin";
  }

  /**
   * Applies profile selection and an optional selector through Cucumber's native tag property.
   *
   * @return effective Cucumber tag expression
   */
  static String configureCucumberTags() {
    var selection = AuthenticationProfile.current().selectionExpression();
    var additional = System.getProperty("cucumber.filter.tags", System.getenv("CUCUMBER_FILTER_TAGS"));
    var tags = additional == null || additional.isBlank() ? selection
        : "(" + selection + ") and (" + additional + ")";
    System.setProperty("cucumber.filter.tags", tags);
    return tags;
  }

  /** Authentication mode selected by the Tiger runtime profile. */
  public enum AuthenticationProfile {
    SMB,
    OIDC;

    /**
     * Resolves the explicit or existing fallback profile through Tiger configuration.
     *
     * @return selected authentication mode
     */
    public static AuthenticationProfile current() {
      var profile = TigerGlobalConfiguration.readStringOptional("profile")
          .or(() -> TIGER_DEFAULT_PROFILE.getValue().filter(value -> Files.exists(
              TigerGlobalConfiguration.resolveRelativePathToTigerYaml("tiger-" + value + ".yaml"))));
      return fromProfile(profile.orElse(null));
    }

    /**
     * Maps a maintained Tiger profile to its authentication mode.
     *
     * @param profile Tiger profile name, or null for the default configuration
     * @return selected authentication mode
     * @throws IllegalArgumentException for unsupported profiles
     */
    public static AuthenticationProfile fromProfile(String profile) {
      return switch (profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT)) {
        case "", "proxy" -> SMB;
        case "oidc", "oidc-proxy" -> OIDC;
        default -> throw new IllegalArgumentException("Unsupported authentication profile: " + profile);
      };
    }

    /**
     * Checks that the resolved route configuration declares the selected authentication mode.
     *
     * @param configuredMode resolved paths.client.authMode value
     * @throws AssertionError when the runtime profile and route configuration disagree
     */
    public void verifyConfiguredMode(String configuredMode) {
      if (configuredMode == null || !name().equalsIgnoreCase(configuredMode.trim())) {
        throw new AssertionError("Authentication profile " + this
            + " does not match paths.client.authMode=" + configuredMode);
      }
    }

    /**
     * Returns the mandatory scenario selection for this authentication mode.
     *
     * @return Cucumber-compatible tag expression
     */
    public String selectionExpression() {
      return "not @Ignore and not @" + (this == SMB ? "oidc" : "smb");
    }
  }
}
