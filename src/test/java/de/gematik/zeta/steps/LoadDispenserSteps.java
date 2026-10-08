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

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.perf.LoadDispenserRunTiming;
import de.gematik.zeta.services.LoadDispenserClient;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Wenn;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Cucumber steps that configure and run the in-cluster load dispenser. */
@Slf4j
public class LoadDispenserSteps {

  private static final String ENDPOINT_CONFIG = "${paths.loadDispenser.endpoint}";
  private static final String CONTROL_TIMEOUT = "control_timeout_s";
  private static final String SETUP_DURATION_TARGET = "setup_duration_target_var";
  private static final String RUN_START_TARGET = "run_start_epoch_target_var";
  private static final String RUN_END_TARGET = "run_end_epoch_target_var";

  /**
   * Configures, starts, and waits for a complete load-dispenser run.
   *
   * @param table complete dispenser configuration and optional control fields
   * @throws InterruptedException if the scenario thread is interrupted while polling
   */
  @Wenn("der Load Dispenser mit folgender Konfiguration ausgeführt wird")
  @When("the load dispenser is run with the following configuration")
  public void runLoadDispenser(DataTable table) throws InterruptedException {
    var values = parseTable(table);
    var controlTimeout = Duration.ofSeconds(parseLong(values.remove(CONTROL_TIMEOUT), 1800));
    var setupDurationTarget = values.remove(SETUP_DURATION_TARGET);
    var runStartTarget = values.remove(RUN_START_TARGET);
    var runEndTarget = values.remove(RUN_END_TARGET);
    var runtime = Duration.ofSeconds(parseLong(values.get("runtime_s"), 0));
    var ramp = Duration.ofSeconds(parseLong(values.get("ramp_s"), 0));
    var endpoint = TigerGlobalConfiguration.resolvePlaceholders(ENDPOINT_CONFIG);
    if (endpoint.isBlank() || endpoint.contains("${")) {
      throw new IllegalStateException("Missing Tiger configuration: " + ENDPOINT_CONFIG);
    }
    var config = toStruct(values);

    try (var client = new LoadDispenserClient(endpoint)) {
      var effective = client.setConfig(config);
      ReportAttachments.addText("Load dispenser effective configuration", effective.toString());
      log.info("Starting load dispenser at {} with {}", endpoint, values);
      var timing = startAndRunToCompletion(client, controlTimeout.plus(runtime).plus(ramp));
      putTestVariable(setupDurationTarget, timing.setupDuration().toSeconds());
      putTestVariable(runStartTarget, timing.runStart().getEpochSecond());
      putTestVariable(runEndTarget, timing.runEnd().getEpochSecond());
    }
  }

  /**
   * Starts the run and aborts it if polling fails or the scenario is interrupted.
   *
   * @param client connected load-dispenser client
   * @param timeout maximum time allowed for setup, ramp-up, and execution
   * @return observed setup and run timing boundaries
   * @throws InterruptedException if the scenario thread is interrupted while polling
   */
  private LoadDispenserRunTiming startAndRunToCompletion(
      LoadDispenserClient client,
      Duration timeout)
      throws InterruptedException {
    try {
      client.start();
      return waitForCompletion(client, timeout);
    } catch (InterruptedException | RuntimeException | Error ex) {
      abortQuietly(client);
      throw ex;
    }
  }

  /**
   * Polls until the dispenser reaches a terminal state or the deadline expires.
   *
   * @param client connected load-dispenser client
   * @param timeout maximum polling duration
   * @return observed setup and run timing boundaries for a stopped run
   * @throws InterruptedException if the scenario thread is interrupted while polling
   * @throws AssertionError if the run fails, is aborted, reports an unknown status, or times out
   */
  private LoadDispenserRunTiming waitForCompletion(LoadDispenserClient client, Duration timeout)
      throws InterruptedException {
    var started = Instant.now();
    Duration setupDuration = Duration.ZERO;
    Instant runStart = started;
    var deadline = Instant.now().plus(timeout);
    String previous = null;
    while (Instant.now().isBefore(deadline)) {
      var status = client.status();
      if (!status.equals(previous)) {
        log.info("Load dispenser status: {}", status);
        if ("RUNNING".equals(status)) {
          setupDuration = Duration.between(started, Instant.now());
          runStart = Instant.now();
        }
        previous = status;
      }
      switch (status) {
        case "STOPPED" -> {
          return new LoadDispenserRunTiming(setupDuration, runStart, Instant.now());
        }
        case "FAILED", "ABORTED" -> throw new AssertionError("Load dispenser ended with status " + status);
        case "SETUP", "RUNNING" -> Thread.sleep(1000);
        default -> throw new AssertionError("Unknown load dispenser status: " + status);
      }
    }
    throw new AssertionError("Load dispenser did not complete within " + timeout.toSeconds() + " seconds");
  }

  /**
   * Performs best-effort cleanup while preserving the original test failure.
   *
   * @param client connected load-dispenser client
   */
  private void abortQuietly(LoadDispenserClient client) {
    try {
      client.abort();
    } catch (RuntimeException ex) {
      log.warn("Could not abort load dispenser run", ex);
    }
  }

  /**
   * Converts the two-column Cucumber table and resolves Tiger placeholders in its values.
   *
   * @param table configuration keys and values from the scenario
   * @return mutable configuration map preserving table order
   */
  private static Map<String, String> parseTable(DataTable table) {
    var result = new LinkedHashMap<>(table.asMap(String.class, String.class));
    result.replaceAll((key, value) -> TigerGlobalConfiguration.resolvePlaceholders(value).trim());
    return result;
  }

  /**
   * Converts dispenser configuration values to protobuf's JSON-compatible struct type.
   *
   * @param values resolved dispenser configuration values
   * @return protobuf configuration passed to {@code SetConfig}
   */
  private static Struct toStruct(Map<String, String> values) {
    var builder = Struct.newBuilder();
    values.forEach((key, value) -> builder.putFields(key, scalarValue(value)));
    return builder.build();
  }

  /**
   * Converts booleans and numbers to their native protobuf scalar representation.
   *
   * @param value resolved textual configuration value
   * @return boolean, numeric, or string protobuf value
   */
  private static Value scalarValue(String value) {
    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
      return Value.newBuilder().setBoolValue(Boolean.parseBoolean(value)).build();
    }
    try {
      return Value.newBuilder().setNumberValue(Double.parseDouble(value)).build();
    } catch (NumberFormatException ignored) {
      return Value.newBuilder().setStringValue(value).build();
    }
  }

  /**
   * Parses an optional integral control value.
   *
   * @param value textual integral value, or {@code null} when omitted
   * @param defaultValue value returned when the input is absent or blank
   * @return parsed or default value
   * @throws NumberFormatException if a non-blank value is not integral
   */
  private static long parseLong(String value, long defaultValue) {
    return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
  }

  /**
   * Stores an optional timing result in the Tiger test context.
   *
   * @param name target variable name, or {@code null} when no result was requested
   * @param value timing value to store
   */
  private static void putTestVariable(String name, long value) {
    if (name != null && !name.isBlank()) {
      TigerGlobalConfiguration.putValue(
          name, Long.toString(value), ConfigurationValuePrecedence.TEST_CONTEXT);
    }
  }

}
