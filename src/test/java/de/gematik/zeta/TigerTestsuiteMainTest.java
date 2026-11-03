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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import de.gematik.test.tiger.common.config.TigerConfigurationException;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.TigerTestsuiteMain.AuthenticationProfile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/** Checks authentication selection and bindings against the maintained scenario catalog. */
@Slf4j
class TigerTestsuiteMainTest {
  private final String originalProfile = System.getProperty("PROFILE");
  private final String originalDefaultProfile = System.getProperty("tiger.lib.defaultProfile");
  private final String originalSelection = System.getProperty("cucumber.filter.tags");

  /** Clears Tiger's loaded configuration before resolving each profile. */
  @BeforeEach
  void resetConfiguration() {
    TigerGlobalConfiguration.reset();
  }

  /** Restores the caller's configuration after nested discovery. */
  @AfterEach
  void restoreProperties() {
    restore("PROFILE", originalProfile);
    restore("cucumber.filter.tags", originalSelection);
    restore("tiger.lib.defaultProfile", originalDefaultProfile);
    TigerGlobalConfiguration.reset();
  }

  /** Ensures wrong routes and unsupported profiles cannot silently become SMB. */
  @Test
  void rejectsMismatchedConfiguration() {
    assertThrows(AssertionError.class, () -> AuthenticationProfile.OIDC.verifyConfiguredMode("SMB"));
    assertThrows(AssertionError.class, () -> AuthenticationProfile.SMB.verifyConfiguredMode(null));
    assertThrows(IllegalArgumentException.class, () -> AuthenticationProfile.fromProfile("unknown"));
    assertThrows(IllegalArgumentException.class, () -> AuthenticationProfile.fromProfile("default"));
    AuthenticationProfile.OIDC.verifyConfiguredMode(" oidc ");
  }

  /**
   * Resolves the authentication selection against the loaded client routes.
   *
   * @param profile explicit Tiger profile, or null when unset
   * @param fallback default Tiger profile, or null when unset
   * @param expected expected authentication mode
   */
  @ParameterizedTest
  @CsvSource(value = {"<unset>, oidc, OIDC", "<unset>, oidc-proxy, OIDC", "<unset>, proxy, SMB",
      "<unset>, <unset>, SMB", "<unset>, missing-profile, SMB", "<unset>, default, SMB",
      "proxy, oidc-proxy, SMB", "oidc-proxy, proxy, OIDC", "oidc, oidc-proxy, OIDC",
      "proxy, missing-profile, SMB"}, nullValues = "<unset>")
  void resolvesExplicitAndDefaultProfiles(String profile, String fallback, AuthenticationProfile expected) {
    restore("PROFILE", profile);
    restore("tiger.lib.defaultProfile", fallback);
    assertThat(TigerGlobalConfiguration.readStringOptional("paths.client.authMode").orElseThrow()).isEqualTo(expected.name());
    assertThat(AuthenticationProfile.current()).isEqualTo(expected);
    System.clearProperty("cucumber.filter.tags");
    assertThat(TigerTestsuiteMain.configureCucumberTags())
        .isEqualTo(expected == AuthenticationProfile.OIDC ? "not @Ignore and not @smb" : "not @Ignore and not @oidc");
  }

  /** Resolves a fallback supplied through Tiger configuration rather than a JVM property. */
  @Test
  void resolvesFallbackFromTigerConfiguration() {
    System.clearProperty("PROFILE");
    System.clearProperty("tiger.lib.defaultProfile");
    TigerGlobalConfiguration.initializeWithCliProperties(Map.of("tiger.lib.defaultProfile", "oidc-proxy"));
    assertThat(TigerGlobalConfiguration.readStringOptional("paths.client.authMode").orElseThrow()).isEqualTo("OIDC");
    assertThat(AuthenticationProfile.current()).isEqualTo(AuthenticationProfile.OIDC);
  }

  /** Uses Tiger's CLI precedence over JVM properties for the explicit profile. */
  @Test
  void resolvesProfileUsingTigerPrecedence() {
    System.setProperty("PROFILE", "proxy");
    TigerGlobalConfiguration.initializeWithCliProperties(Map.of("profile", "oidc-proxy"));
    assertThat(TigerGlobalConfiguration.readStringOptional("paths.client.authMode").orElseThrow()).isEqualTo("OIDC");
    assertThat(AuthenticationProfile.current()).isEqualTo(AuthenticationProfile.OIDC);
  }

  /** Rejects an explicit profile whose configuration file is absent. */
  @Test
  void rejectsMissingExplicitDefaultProfile() {
    System.setProperty("PROFILE", "default");
    assertThrows(TigerConfigurationException.class, AuthenticationProfile::current);
  }

  /** Checks native tag selection in the generated Maven suite's inner launcher. */
  @Test
  void generatedSuiteUsesTheSameSelection() throws Exception {
    executeCatalog("oidc-proxy", true);
  }

  /** Checks selected catalog entries and step bindings without executing hooks. */
  @ParameterizedTest
  @ValueSource(strings = {"proxy", "oidc-proxy"})
  void selectedCatalogHasUnambiguousBindings(String profile) throws Exception {
    executeCatalog(profile, false);
  }

  private void executeCatalog(String profile, boolean suite) throws Exception {
    System.setProperty("PROFILE", profile);
    System.clearProperty("cucumber.filter.tags");
    var report = Files.createTempFile("zeta-profile-bindings-", ".json");
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(suite ? selectClass(ProfileSuite.class) : selectClasspathResource("features"))
        .filters(EngineFilter.includeEngines(suite ? "junit-platform-suite" : "cucumber"))
        .configurationParameter("cucumber.glue", "de.gematik.test.tiger.glue,de.gematik.zeta")
        .configurationParameter("cucumber.execution.dry-run", "true")
        .configurationParameter("cucumber.execution.parallel.enabled", "false")
        .configurationParameter("cucumber.filter.tags", TigerTestsuiteMain.configureCucumberTags())
        .configurationParameter("cucumber.plugin", "json:" + report).build();
    var executed = new ArrayList<TestIdentifier>();
    var listener = new SummaryGeneratingListener();
    LauncherFactory.create().execute(request, listener, new TestExecutionListener() {
      @Override
      public void executionStarted(TestIdentifier identifier) {
        if (identifier.isTest()) {
          executed.add(identifier);
        }
      }
    });
    assertSelection(executed);
    assertThat(listener.getSummary().getTestsSucceededCount()).isEqualTo(executed.size());
    assertThat(listener.getSummary().getFailures()).isEmpty();
    log.info("Profile binding check {}: {} cases", profile, executed.size());
  }

  /** Checks that an extra selector cannot enable the other authentication mode. */
  @Test
  void combinesAdditionalSelection() {
    System.setProperty("PROFILE", "proxy");
    System.setProperty("cucumber.filter.tags", "@blocker or @oidc");
    assertThat(TigerTestsuiteMain.configureCucumberTags())
        .isEqualTo("(not @Ignore and not @oidc) and (@blocker or @oidc)");
  }

  private void assertSelection(List<TestIdentifier> cases) {
    var excluded = AuthenticationProfile.current() == AuthenticationProfile.SMB ? "oidc" : "smb";
    assertThat(cases).isNotEmpty();
    assertThat(cases).allSatisfy(identifier -> assertThat(identifier.getTags())
        .noneMatch(tag -> tag.getName().equals(excluded) || tag.getName().equalsIgnoreCase("Ignore")
            || tag.getName().equals("require_oidc_testdriver")));
    assertThat(cases.stream().anyMatch(identifier -> identifier.getTags().stream()
        .anyMatch(tag -> tag.getName().equals("smoke_android_push"))))
        .isEqualTo(AuthenticationProfile.current() == AuthenticationProfile.OIDC);
    assertThat(cases.stream().anyMatch(identifier -> identifier.getDisplayName().equals(
        "SDK fordert das Notification-Service-Token mit dem Resource Indicator an")))
        .isEqualTo(AuthenticationProfile.current() == AuthenticationProfile.OIDC);
    assertThat(cases.stream().filter(identifier -> identifier.getTags().stream().anyMatch(tag ->
        tag.getName().equals("android_attestation_registration")
            || tag.getName().equals("android_attestation_without_play_integrity"))).count())
        .isEqualTo(AuthenticationProfile.current() == AuthenticationProfile.OIDC ? 2L : 0L);
    assertThat(cases.stream().filter(identifier -> identifier.getTags().stream().anyMatch(tag ->
        tag.getName().equals("apple_attestation_registration"))).count())
        .isEqualTo(AuthenticationProfile.current() == AuthenticationProfile.OIDC ? 1L : 0L);
    assertThat(cases).anySatisfy(identifier -> assertThat(identifier.getTags())
        .noneMatch(tag -> tag.getName().equals("oidc") || tag.getName().equals("smb")));
    assertThat(cases).anySatisfy(identifier -> assertThat(identifier.getTags())
        .anyMatch(tag -> tag.getName().equals(excluded.equals("oidc") ? "smb" : "oidc")));
  }

  private static void restore(String property, String value) {
    if (value == null) {
      System.clearProperty(property);
    } else {
      System.setProperty(property, value);
    }
  }

  /** Mirrors the suite annotations used by generated Maven drivers. */
  @Suite
  @IncludeEngines("cucumber")
  @SelectClasspathResource("features")
  public static class ProfileSuite {}
}
