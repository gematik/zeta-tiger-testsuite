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

package de.gematik.zeta;

import de.gematik.test.tiger.lib.TigerDirector;
import de.gematik.test.tiger.lib.TigerInitializer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Standalone entry point for running the Tiger/Cucumber testsuite without Maven.
 *
 * <p>Maps environment variables to the expected Tiger and Cucumber system properties and delegates
 * execution to the JUnit Platform with the Cucumber engine. Ensures Tiger is initialized first so
 * the run behaves like the TigerCucumberRunner. Exits non-zero when discovery yields no tests or
 * when scenarios fail.</p>
 */
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
   * @param args optional arguments (currently ignored; configuration is read from env vars)
   */
  public static void main(String[] args) {
    configureSystemPropertiesFromEnv();

    int exitCode = 0;
    try {
      new TigerInitializer().runWithSafelyInitialized(TigerTestsuiteMain::runCucumberSuite);
    } catch (RuntimeException ex) {
      exitCode = 1;
      ex.printStackTrace(System.err);
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

  private static void runCucumberSuite() {
    ensureDir(System.getProperty("serenity.outputDirectory"));
    var cucumberOutputDir =
        System.getProperty(CUCUMBER_OUTPUT_DIR_PROPERTY, DEFAULT_CUCUMBER_OUTPUT_DIR);
    ensureDir(cucumberOutputDir);

    List<DiscoverySelector> selectors = new ArrayList<>();
    selectors.add(DiscoverySelectors.selectClasspathResource("features"));

    var glue = System.getProperty("cucumber.glue", DEFAULT_GLUE);
    var plugin = System.getProperty("cucumber.plugin", defaultPlugin(cucumberOutputDir));

    var builder = LauncherDiscoveryRequestBuilder.request()
        .configurationParameter("cucumber.glue", glue)
        .configurationParameter("cucumber.plugin", plugin)
        .configurationParameter("cucumber.publish.quiet", "true");

    var tags = System.getProperty("cucumber.filter.tags");

    if (tags != null && !tags.isBlank()) {
      builder.configurationParameter("cucumber.filter.tags", tags);
    }

    var request = builder
        .selectors(selectors)
        .build();

    var summaryListener = new SummaryGeneratingListener();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(summaryListener);
    launcher.execute(request);

    var summary = summaryListener.getSummary();
    summary.printTo(new PrintWriter(System.out, true));
    if (summary.getTestsFoundCount() == 0) {
      throw new IllegalStateException("No tests found (Cucumber features not discovered)");
    }
    if (summary.getTotalFailureCount() > 0) {
      summary.printFailuresTo(new PrintWriter(System.err, true));
      throw new IllegalStateException("Tests failed: " + summary.getTotalFailureCount());
    }
  }

  /**
   * Map common environment variables to system properties expected by Tiger/Cucumber.
   */
  private static void configureSystemPropertiesFromEnv() {
    var env = System.getenv();

    setIfAbsent("zeta_base_url", env.get("ZETA_BASE_URL"));
    setIfAbsent("zeta_proxy_url", env.get("ZETA_PROXY_URL"));
    setIfAbsent("zeta_proxy", env.get("ZETA_PROXY"));
    setIfAbsent("environment", env.get("TIGER_ENVIRONMENT"));
    setIfAbsent("cucumber.filter.tags",
        firstNonEmpty(env.get("CUCUMBER_FILTER_TAGS"), env.get("CUCUMBER_TAGS")));
    setIfAbsent("tiger.testenv.cfgfile",
        firstNonEmpty(env.get("TIGER_TESTENV_CFGFILE"), existingTigerConfigPath()));
    setIfAbsent("serenity.outputDirectory",
        env.getOrDefault("SERENITY_EXPORT_DIR", "target/site/serenity"));

    setIfAbsent(CUCUMBER_OUTPUT_DIR_PROPERTY,
        firstNonEmpty(env.get("CUCUMBER_EXPORT_DIR"), DEFAULT_CUCUMBER_OUTPUT_DIR));

    setIfAbsent("tiger.lib.activateWorkflowUi", "false");
    setIfAbsent("tiger.lib.startBrowser", "false");
    setIfAbsent("tiger.lib.trafficVisualization", "false");
    setIfAbsent("tiger.lib.rbelAnsiColors", "false");
    setIfAbsent("tiger.lib.runTestsOnStart", "true");
    setIfAbsent("cucumber.publish.quiet", "true");
    setIfAbsent("cucumber.plugin",
        defaultPlugin(System.getProperty(CUCUMBER_OUTPUT_DIR_PROPERTY, DEFAULT_CUCUMBER_OUTPUT_DIR)));
    setIfAbsent("cucumber.glue", DEFAULT_GLUE);
  }

  private static String defaultPlugin(String cucumberOutputDir) {
    return "io.cucumber.core.plugin.TigerSerenityReporterPlugin"
        + ",json:" + cucumberOutputDir + "/main.json"
        + ",junit:" + cucumberOutputDir + "/cucumber.xml";
  }

  /**
   * Set a system property if it is not already present and the candidate value is non-blank.
   *
   * @param key   system property name
   * @param value candidate value
   */
  private static void setIfAbsent(String key, String value) {
    Optional.ofNullable(value)
        .filter(Predicate.not(String::isBlank))
        .ifPresent(v -> System.getProperties().putIfAbsent(key, v));
  }

  /**
   * Ensure the given directory exists so report output does not fail in headless runs.
   *
   * @param path directory path to create if missing
   */
  private static void ensureDir(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    try {
      Files.createDirectories(Path.of(path));
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to create directory: " + path, ex);
    }
  }

  /**
   * Return the first non-empty string from the provided candidates.
   *
   * @param values candidate values in priority order
   * @return the first non-empty candidate or {@code null}
   */
  private static String firstNonEmpty(String... values) {
    return Stream.of(values)
        .filter(Objects::nonNull)
        .filter(Predicate.not(String::isBlank))
        .findFirst()
        .orElse(null);
  }

  /**
   * Resolve a tiger.yaml/yml path from the working directory if present.
   *
   * @return absolute path or {@code null} if not found
   */
  private static String existingTigerConfigPath() {
    for (var candidate : List.of("tiger.yaml", "tiger.yml")) {
      var path = Path.of(candidate);
      if (Files.exists(path)) {
        return path.toAbsolutePath().toString();
      }
    }
    return null;
  }
}
