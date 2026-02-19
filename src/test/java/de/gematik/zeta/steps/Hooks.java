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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.glue.HttpGlueCode;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentConfigurationServiceFactory;
import de.gematik.zeta.traceability.TraceabilityLookup;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.restassured.http.Method;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;
import org.junit.jupiter.api.Assumptions;

/**
 * Injects traceability information into every Serenity scenario report.
 */
@Slf4j
public class Hooks {

  private static final TraceabilityLookup TRACEABILITY = TraceabilityLookup.load();
  private static final String NO_PROXY_TAG = "@no_proxy";
  private static final String DEPLOYMENT_MODIFICATION_TAG = "@deployment_modification";
  private static final String TIGER_ALLOW_DEPLOYMENT_MODIFICATION = "allow_deployment_modification";
  private static final String TIGER_PROXY_ID_CONFIG_KEY = "tiger.tigerProxy.proxyId";
  private static final int ORDER_RESTORE_DEPLOYMENT_STATE = Integer.MIN_VALUE;
  private static final int ORDER_PREPARE_SOFT_ASSERTIONS = ORDER_RESTORE_DEPLOYMENT_STATE + 1;
  // leave room after the global reset hook for future cross-cutting checks
  private static final int ORDER_PROXY_REQUIREMENT_GUARD = ORDER_PREPARE_SOFT_ASSERTIONS + 100;
  private static final int ORDER_VERIFY_DEPLOYMENT_MODIFICATION = ORDER_PROXY_REQUIREMENT_GUARD + 1;
  private static final int ORDER_APPEND_TRACEABILITY = Integer.MAX_VALUE;
  private static final int ORDER_VERIFY_SOFT_ASSERTIONS = ORDER_APPEND_TRACEABILITY - 1;

  private final ZetaDeploymentConfigurationService deploymentConfigurationService = ZetaDeploymentConfigurationServiceFactory.getInstance();

  /**
   * Clears any soft assertions before each scenario to avoid leaking state across scenarios.
   */
  @Before(order = ORDER_PREPARE_SOFT_ASSERTIONS)
  public void prepareSoftAssertions() {
    SoftAssertionsContext.reset();
  }

  /**
   * Skip proxy-dependent scenarios unless the TigerProxy configuration is present. Scenarios
   * tagged with {@link #NO_PROXY_TAG} are always executed.
   */
  @Before(order = ORDER_PROXY_REQUIREMENT_GUARD)
  public void skipIfProxyMissing(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    if (scenario.getSourceTagNames().contains(NO_PROXY_TAG)) {
      return;
    }

    var proxyId = TigerGlobalConfiguration.readStringOptional(TIGER_PROXY_ID_CONFIG_KEY)
        .orElse(null);
    boolean proxyConfigured = proxyId != null && !proxyId.isBlank();

    if (!proxyConfigured) {
      String reason = "Skipping: standalone Tiger proxy is not configured "
          + "and scenario is not tagged " + NO_PROXY_TAG;
      scenario.log(reason);
      log.warn("{} (scenario: '{}', tigerProxyId='{}', envProfile='{}', sysProfile='{}')",
          reason, scenario.getName(),
          proxyId == null ? "<missing>" : proxyId,
          System.getenv("PROFILE"),
          System.getProperty("PROFILE"));
      // noinspection DataFlowIssue
      Assumptions.assumeTrue(false, reason);
    } else {
      log.info("Scenario not skipped, proxy explicitly configured.");
    }
  }

  /**
   * Performs checks to verify that deployment modification is correctly set up if enabled at all.
   *
   * <p>Failure in verfication checks lead to skipping the scenario</p>
   *
   * @param scenario Scenario to be executed
   */
  @Before(order = ORDER_VERIFY_DEPLOYMENT_MODIFICATION)
  public void verifyDeploymentModification(final Scenario scenario) {
    // only run checks if scenario is properly tagged
    if (scenario == null || !scenario.getSourceTagNames().contains(DEPLOYMENT_MODIFICATION_TAG)) {
      log.debug("Deployment modification verify: tag '{}' was not found, ignore further checks",
          DEPLOYMENT_MODIFICATION_TAG);
      return;
    }

    // verify that deployment modifications are generally allowed in this run
    if (!TigerGlobalConfiguration.readBooleanOptional(TIGER_ALLOW_DEPLOYMENT_MODIFICATION)
        .orElse(false)) {
      String reason = String.format("Skipping: deployment modification is not allowed; scenario is tagged with %s",
          DEPLOYMENT_MODIFICATION_TAG);
      scenario.log(reason);
      // noinspection DataFlowIssue
      Assumptions.assumeTrue(false, reason);
    }

    // check if requirements for deployment modifications are given
    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElse("");

    if (namespace == null || namespace.isBlank()) {
      String reason = "Skipping: namespace for deployment modification is not defined";
      scenario.log(reason);
      // noinspection DataFlowIssue
      Assumptions.assumeTrue(false, reason);
    }

    try {
      deploymentConfigurationService.verifyRequirements(namespace);
    } catch (Exception e) {
      String reason = "Skipping: verification check for deployment modification failed";
      scenario.log(reason);
      log.error("Unexpected error while verifying deployment modification requirements", e);
      // noinspection DataFlowIssue
      Assumptions.assumeTrue(false, reason);
    }
  }


  /**
   * Append the traceability table after each scenario has finished.
   *
   * @param scenario active Cucumber scenario
   */
  @After(order = ORDER_APPEND_TRACEABILITY)
  public void attachTraceability(final Scenario scenario) {
    if (scenario == null) {
      return;
    }
    TRACEABILITY.buildReport(scenario.getName(), scenario.getSourceTagNames())
        .ifPresent(markdown -> {
          log.debug("Adding traceability block to scenario '{}'", scenario.getName());
          Serenity.recordReportData()
              .withTitle("Traceability")
              .andContents(markdown);
        });
  }

  /**
   * Verifies all collected soft assertions at the very end of the scenario lifecycle. Runs after
   * the traceability appendix so the report is always populated even when soft assertions fail.
   */
  @After(order = ORDER_VERIFY_SOFT_ASSERTIONS)
  public void verifySoftAssertions() {
    SoftAssertionsContext.assertAll();
  }

  /**
   * Ensures modifications to the Zeta Guard deployment are restored to their original state
   * after scenarios finish.
   *
   * @param scenario active Cucumber scenario
   */
  @After(order = ORDER_RESTORE_DEPLOYMENT_STATE)
  public void restoreDeploymentModifications(final Scenario scenario) {
    if (!scenario.getSourceTagNames().contains(DEPLOYMENT_MODIFICATION_TAG)) {
      log.debug("Restore deployment modification: skipping because {} tag was not found", DEPLOYMENT_MODIFICATION_TAG);
      return;
    }

    if (!TigerGlobalConfiguration.readBooleanOptional(TIGER_ALLOW_DEPLOYMENT_MODIFICATION)
        .orElse(false)) {
      log.warn("Restore deployment modification: skipping because deployment modification is not allowed");
      return;
    }

    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElse("");

    if (namespace == null || namespace.isBlank()) {
      log.warn("Restore deployment modification: could not restore original Zeta deployment because"
          + " namespace is not configured");
      return;
    }

    String pepNginxConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.configMapName")
        .orElse("");
    if (pepNginxConfigMapName == null || pepNginxConfigMapName.isBlank()) {
      log.warn("Restore deployment modification: could not restore original Zeta deployment because"
          + " pep.nginx.configMapName is not configured");
    } else {
      try {
        deploymentConfigurationService.restoreConfigMapBackup(namespace, pepNginxConfigMapName);
      } catch (Exception e) {
        log.error("Restore deployment modification: could not restore original nginx config in Zeta deployment because "
            + "an unexpected error occurred", e);
      }
    }

    String pepWellKnownConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.configMapName")
        .orElse("");
    if (pepWellKnownConfigMapName == null || pepWellKnownConfigMapName.isBlank()) {
      log.warn("Restore deployment modification: could not restore Zeta deployment because"
          + " pep.wellKnown.configMapName is not configured");
    } else {
      try {
        deploymentConfigurationService.restoreConfigMapBackup(namespace, pepWellKnownConfigMapName);
      } catch (Exception e) {
        log.error("Restore deployment modification: could not restore original well-known config because "
            + "an unexpected error occurred", e);
      }
    }

    String url = TigerGlobalConfiguration.readStringOptional("paths.client.reset").orElse(null);
    if (url == null) {
      log.warn("Restore deployment modification: could not issue client reset because "
          + "client URL not set (expected at: paths.client.reset)");
      return;
    }

    try {
      new HttpGlueCode().sendEmptyRequest(Method.GET, new URI(url));
    } catch (Exception e) {
      log.error("Restore deployment modification: could not issue client reset because "
          + "an unexpected error occurred while sending request", e);
    }
  }
}
