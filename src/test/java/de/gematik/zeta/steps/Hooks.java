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

package de.gematik.zeta.steps;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.traceability.TraceabilityLookup;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
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
  private static final String ZETA_PROXY_CONFIG_KEY = "zeta_proxy";
  private static final String ZETA_PROXY_REQUIRED_VALUE = "proxy";
  private static final int ORDER_PREPARE_SOFT_ASSERTIONS = Integer.MIN_VALUE;
  // leave room after the global reset hook for future cross-cutting checks
  private static final int ORDER_PROXY_REQUIREMENT_GUARD = ORDER_PREPARE_SOFT_ASSERTIONS + 100;
  private static final int ORDER_APPEND_TRACEABILITY = Integer.MAX_VALUE;
  private static final int ORDER_VERIFY_SOFT_ASSERTIONS = ORDER_APPEND_TRACEABILITY - 1;

  /**
   * Clears any soft assertions before each scenario to avoid leaking state across scenarios.
   */
  @Before(order = ORDER_PREPARE_SOFT_ASSERTIONS)
  public void prepareSoftAssertions() {
    SoftAssertionsContext.reset();
  }

  /**
   * Skip proxy-dependent scenarios unless {@code zeta_proxy=proxy} is configured. Scenarios tagged
   * with {@link #NO_PROXY_TAG} are always executed.
   */
  @Before(order = ORDER_PROXY_REQUIREMENT_GUARD)
  public void skipIfProxyMissing(final Scenario scenario) {
    if (scenario == null) {
      return;
    }

    if (scenario.getSourceTagNames().contains(NO_PROXY_TAG)) {
      return;
    }

    var proxyValue = TigerGlobalConfiguration.readStringOptional(ZETA_PROXY_CONFIG_KEY)
        .orElse(null);
    boolean proxyConfigured = ZETA_PROXY_REQUIRED_VALUE.equals(proxyValue);

    if (!proxyConfigured) {
      String reason = "Skipping: standalone Tiger proxy is not configured "
          + "and scenario is not tagged " + NO_PROXY_TAG;
      scenario.log(reason);
      log.info("{} (scenario: '{}', zeta_proxy='{}')", reason, scenario.getName(),
          proxyValue == null ? "<missing>" : proxyValue);
      // noinspection DataFlowIssue
      Assumptions.assumeTrue(false, reason);
    } else {
      log.info("Scenario not skipped, proxy explicitly configured.");
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
}
