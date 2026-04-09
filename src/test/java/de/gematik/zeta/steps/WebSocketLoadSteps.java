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

import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import de.gematik.zeta.services.WebSocketLoadService;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber steps for keeping many raw WebSocket connections open during performance scenarios.
 */
@Slf4j
public class WebSocketLoadSteps {

  private final WebSocketLoadService webSocketLoadService;

  /**
   * Creates WebSocket load steps with the default load service.
   */
  public WebSocketLoadSteps() {
    this(new WebSocketLoadService());
  }

  /**
   * Creates WebSocket load steps with an injected load service for tests.
   *
   * @param webSocketLoadService service used to manage the connection pool
   */
  WebSocketLoadSteps(WebSocketLoadService webSocketLoadService) {
    this.webSocketLoadService = Objects.requireNonNull(webSocketLoadService, "webSocketLoadService");
  }

  /**
   * Opens the requested number of WebSocket connections against the target URL.
   *
   * @param connectionCount the number of connections to open
   * @param url the target WebSocket URL
   */
  @Wenn("{int} WebSocket Verbindungen zu {tigerResolvedString} aufgebaut werden")
  @When("{int} WebSocket connections to {tigerResolvedString} are opened")
  public void openWebSocketConnections(int connectionCount, String url) {
    webSocketLoadService.openConnections(url, connectionCount);
    reportPoolState("WebSocket load setup", connectionCount);
  }

  /**
   * Verifies that at least the requested number of WebSocket connections are still open.
   *
   * <p>Records a soft assertion failure instead of aborting the scenario immediately.</p>
   *
   * @param minimumConnectionCount the minimum number of open connections
   */
  @Dann("sind mindestens {int} WebSocket Verbindungen offen")
  @Then("at least {int} WebSocket connections are open")
  public void assertOpenWebSocketConnections(int minimumConnectionCount) {
    int openConnections = webSocketLoadService.getOpenConnectionCount();
    reportPoolState("WebSocket load verification", minimumConnectionCount);

    if (openConnections < minimumConnectionCount) {
      String message = String.format(
          Locale.ROOT,
          "Expected at least %d open WebSocket connections but found %d. Recent failures: %s",
          minimumConnectionCount,
          openConnections,
          webSocketLoadService.getRecentFailures(10));
      SoftAssertionsContext.recordSoftFailure(message, new AssertionError(message));
    }
  }

  /**
   * Closes all WebSocket connections opened by the load harness.
   */
  @Dann("werden alle aufgebauten WebSocket Verbindungen geschlossen")
  @Then("all opened WebSocket connections are closed")
  public void closeAllWebSocketConnections() {
    reportPoolState("WebSocket load cleanup", webSocketLoadService.getOpenConnectionCount());
    webSocketLoadService.close();
  }

  /**
   * Ensures background WebSocket connections do not leak into the next scenario.
   */
  @After
  public void cleanupAfterScenario() {
    webSocketLoadService.close();
  }

  /**
   * Writes the current pool state to the log and Serenity report.
   *
   * @param title the report title
   * @param expectedConnections the expected number of open connections for context
   */
  private void reportPoolState(String title, int expectedConnections) {
    int openConnections = webSocketLoadService.getOpenConnectionCount();
    int failures = webSocketLoadService.getFailureCount();
    String reportText = String.format(
        Locale.ROOT,
        "expected=%d, open=%d, failures=%d, recentFailures=%s",
        expectedConnections,
        openConnections,
        failures,
        webSocketLoadService.getRecentFailures(5));
    log.warn("[WEBSOCKET LOAD] {} {}", title, reportText);
    SerenityReportUtils.addCustomData(title, reportText);
  }
}
