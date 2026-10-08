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

import static org.assertj.core.api.Assertions.assertThat;

import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;

/**
 * Step definitions for validating timestamps.
 */
@Slf4j
public class TimestampValidationSteps {

  /**
   * Stores the current epoch seconds timestamp in a Tiger variable.
   *
   * @param varName target Tiger variable name
   */
  @Und("speichere den aktuellen Unix-Zeitstempel in der Variable {tigerResolvedString}")
  @And("store the current Unix timestamp in variable {tigerResolvedString}")
  public void storeCurrentUnixTimestamp(String varName) {
    var now = Instant.now();

    TigerGlobalConfiguration.putValue(
        varName,
        String.valueOf(now.getEpochSecond()),
        ConfigurationValuePrecedence.TEST_CONTEXT);

    log.info("Gespeicherter aktueller Unix-Zeitstempel: {}", now);
  }

  /**
   * Cucumber step definition for validating a timestamp.
   *
   * @param timestamp the epoch seconds
   * @throws AssertionError if the value is invalid or the timestamp is not in the future
   */
  @Und("validiere, dass der Zeitstempel {tigerResolvedString} in der Zukunft liegt")
  @And("validate that the timestamp {tigerResolvedString} is in the future")
  public void validateTimestampIsNotYetExpired(String timestamp) throws AssertionError {

    Instant futureTimestamp;
    try {
      futureTimestamp = Instant.ofEpochSecond(Long.parseLong(timestamp));
    } catch (NumberFormatException e) {
      throw new AssertionError("Ungültiges Zeitstempel Format: " + timestamp);
    }

    assertThat(Instant.now())
        .as("Zeitstempel muss in der Zukunft liegen")
        .isBefore(futureTimestamp);

    log.info("Validierung erfolgreich: Zeitstempel {} ist noch nicht abgelaufen.", futureTimestamp);
  }

  /**
   * Cucumber step definition for validating a timestamp.
   *
   * @param timestamp the epoch seconds
   * @throws AssertionError if the value is invalid or the timestamp is neither in the past nor now
   */
  @Und("validiere, dass der Zeitstempel {tigerResolvedString} in der Vergangenheit liegt")
  @And("validate that the timestamp {tigerResolvedString} is in the past")
  public void validateTimestampIsExpired(String timestamp) throws AssertionError {

    Instant earlierTimestamp;
    try {
      earlierTimestamp = Instant.ofEpochSecond(Long.parseLong(timestamp));
    } catch (NumberFormatException e) {
      throw new AssertionError("Ungültiges Zeitstempel Format: " + timestamp);
    }

    assertThat(Instant.now())
        .as("Zeitstempel muss in der Vergangenheit liegen oder jetzt sein")
        .isAfterOrEqualTo(earlierTimestamp);

    log.info("Validierung erfolgreich: Zeitstempel {} ist bereits abgelaufen.", earlierTimestamp);
  }

  /**
   * Cucumber step definition for validating a timestamp.
   *
   * @param after the epoch seconds of the earlier timestamp
   * @param before the epoch seconds of the later timestamp
   * @throws AssertionError if the value is invalid or the timestamp is not in the future
   */
  @Und("validiere, dass der Zeitstempel {tigerResolvedString} später als {tigerResolvedString} liegt")
  @And("validate that the timestamp {tigerResolvedString} is after {tigerResolvedString}")
  public void validateTimestampIsLaterThan(String after, String before) throws AssertionError {

    Instant earlier;
    Instant later;
    try {
      later = Instant.ofEpochSecond(Long.parseLong(after));
    } catch (NumberFormatException e) {
      throw new AssertionError("Ungültiges Zeitstempel Format: " + after);
    }
    try {
      earlier = Instant.ofEpochSecond(Long.parseLong(before));
    } catch (NumberFormatException e) {
      throw new AssertionError("Ungültiges Zeitstempel Format: " + before);
    }

    assertThat(later)
        .as("Zeitstempel 2 ist nicht später als Zeitstempel 1.")
        .isAfter(earlier);

    log.info("Validierung erfolgreich: Zeitstempel {} ist später als {}.", after, before);
  }

  /**
   * Cucumber step definition for validating that a timestamp is not older than a configured age.
   *
   * @param timestamp the epoch seconds
   * @param maxAgeSecondsText the maximum accepted age in seconds
   * @throws AssertionError if one value is invalid, the timestamp is in the future, or the timestamp is too old
   */
  @Und("validiere, dass der Zeitstempel {tigerResolvedString} höchstens {tigerResolvedString} Sekunden alt ist")
  @And("validate that the timestamp {tigerResolvedString} is at most {tigerResolvedString} seconds old")
  public void validateTimestampMaximumAge(String timestamp, String maxAgeSecondsText) throws AssertionError {
    var value = parseEpochSeconds(timestamp, "Zeitstempel");
    var maxAgeSeconds = parseNonNegativeLong(maxAgeSecondsText, "Maximales Alter");
    var now = Instant.now();

    assertThat(value)
        .as("Zeitstempel darf nicht in der Zukunft liegen")
        .isBeforeOrEqualTo(now);
    assertThat(value)
        .as("Zeitstempel darf höchstens %s Sekunden alt sein", maxAgeSeconds)
        .isAfterOrEqualTo(now.minusSeconds(maxAgeSeconds));

    log.info("Validierung erfolgreich: Zeitstempel {} ist höchstens {} Sekunden alt.", value, maxAgeSeconds);
  }

  /**
   * Cucumber step definition for validating that a timestamp belongs to the current quarter.
   *
   * @param timestamp the epoch seconds
   * @throws AssertionError if the value is invalid or the timestamp is not in the current quarter
   */
  @Und("validiere, dass der Zeitstempel {tigerResolvedString} im aktuellen Quartal liegt")
  @And("validate that the timestamp {tigerResolvedString} is in the current quarter")
  public void validateTimestampIsInCurrentQuarter(String timestamp) throws AssertionError {
    var value = parseEpochSeconds(timestamp, "Zeitstempel");
    var now = Instant.now();

    assertThat(quarterKey(value))
        .as("Zeitstempel muss im aktuellen Quartal liegen")
        .isEqualTo(quarterKey(now));

    log.info("Validierung erfolgreich: Zeitstempel {} liegt im aktuellen Quartal.", value);
  }

  /**
   * Cucumber step definition for validating that two timestamps belong to the same quarter.
   *
   * @param firstTimestamp the first epoch seconds value
   * @param secondTimestamp the second epoch seconds value
   * @throws AssertionError if one value is invalid or the timestamps are not in the same quarter
   */
  @Und("validiere, dass die Zeitstempel {tigerResolvedString} und {tigerResolvedString} im gleichen Quartal liegen")
  @And("validate that the timestamps {tigerResolvedString} and {tigerResolvedString} are in the same quarter")
  public void validateTimestampsAreInSameQuarter(String firstTimestamp, String secondTimestamp) throws AssertionError {
    var first = parseEpochSeconds(firstTimestamp, "Erster Zeitstempel");
    var second = parseEpochSeconds(secondTimestamp, "Zweiter Zeitstempel");

    assertThat(quarterKey(first))
        .as("Zeitstempel müssen im gleichen Quartal liegen")
        .isEqualTo(quarterKey(second));

    log.info("Validierung erfolgreich: Zeitstempel {} und {} liegen im gleichen Quartal.", first, second);
  }

  /**
   * Stores the last second of the previous UTC quarter in a Tiger variable.
   *
   * @param varName target Tiger variable name
   */
  @Und("speichere einen Zeitstempel aus dem vorherigen Quartal in der Variable {tigerResolvedString}")
  @And("store a timestamp from the previous quarter in variable {tigerResolvedString}")
  public void storeTimestampFromPreviousQuarter(String varName) {
    var now = Instant.now();
    var previousQuarterTimestamp = firstInstantOfCurrentQuarter(now).minusSeconds(1);

    TigerGlobalConfiguration.putValue(
        varName,
        String.valueOf(previousQuarterTimestamp.getEpochSecond()),
        ConfigurationValuePrecedence.TEST_CONTEXT);

    log.info("Gespeicherter Zeitstempel für vorheriges Quartal: {}", previousQuarterTimestamp);
  }

  /**
   * Parses epoch seconds into an {@link Instant}.
   *
   * @param timestamp the epoch seconds text
   * @param label field label used in assertion messages
   * @return parsed instant
   */
  private Instant parseEpochSeconds(String timestamp, String label) {
    try {
      return Instant.ofEpochSecond(Long.parseLong(timestamp));
    } catch (NumberFormatException e) {
      throw new AssertionError("Ungültiges Zeitstempel Format für " + label + ": " + timestamp);
    }
  }

  /**
   * Parses a non-negative long value.
   *
   * @param value the text to parse
   * @param label field label used in assertion messages
   * @return parsed value
   */
  private long parseNonNegativeLong(String value, String label) {
    try {
      var parsed = Long.parseLong(value.trim());
      if (parsed < 0) {
        throw new AssertionError(label + " darf nicht negativ sein: " + value);
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new AssertionError("Ungültiges Zahlenformat für " + label + ": " + value);
    }
  }

  /**
   * Builds a stable quarter key in UTC for the given instant.
   *
   * @param value the instant to classify
   * @return quarter key formatted as {@code yyyy-Qn}
   */
  private String quarterKey(Instant value) {
    ZonedDateTime dateTime = value.atZone(ZoneOffset.UTC);
    int quarter = ((dateTime.getMonthValue() - 1) / 3) + 1;
    return dateTime.getYear() + "-Q" + quarter;
  }

  /**
   * Determines the first instant of the current quarter in UTC.
   *
   * @param value instant that defines the current quarter
   * @return first instant of the quarter containing {@code value}
   */
  private Instant firstInstantOfCurrentQuarter(Instant value) {
    var dateTime = value.atZone(ZoneOffset.UTC);
    int firstMonthOfQuarter = ((dateTime.getMonthValue() - 1) / 3) * 3 + 1;
    return ZonedDateTime
        .of(dateTime.getYear(), firstMonthOfQuarter, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        .toInstant();
  }
}
