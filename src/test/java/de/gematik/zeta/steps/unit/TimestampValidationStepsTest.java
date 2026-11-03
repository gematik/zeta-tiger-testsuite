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

package de.gematik.zeta.steps.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.zeta.steps.TimestampValidationSteps;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TimestampValidationSteps}.
 */
class TimestampValidationStepsTest {

  @Test
  void ccNegativeTimestampStaysOutsideQuarterGraceAtYearAndQuarterBoundaries() {
    org.junit.jupiter.api.Assertions.assertEquals(Instant.parse("2025-09-30T23:59:59Z"),
        TimestampValidationSteps.timestampOutsideQuarterGrace(Instant.parse("2026-01-01T00:00:01Z")));
    org.junit.jupiter.api.Assertions.assertEquals(Instant.parse("2025-12-31T23:59:59Z"),
        TimestampValidationSteps.timestampOutsideQuarterGrace(Instant.parse("2026-04-01T00:04:59Z")));
    org.junit.jupiter.api.Assertions.assertEquals(Instant.parse("2025-12-31T23:59:59Z"),
        TimestampValidationSteps.timestampOutsideQuarterGrace(Instant.parse("2026-04-01T00:05:01Z")));
  }


  private final TimestampValidationSteps validator = new TimestampValidationSteps();


  /**
   * Verifies the method "timestampIsExpired".
   */
  @Test
  void testTimestampIsExpired() {

    Instant now = Instant.now();
    Instant futureTS = now.plus(30, ChronoUnit.SECONDS);
    Instant pastTS = now.minus(30, ChronoUnit.SECONDS);

    assertDoesNotThrow(
        () -> validator.validateTimestampIsExpired(String.valueOf(pastTS.getEpochSecond())),
        "Past timestamp was not recognized as expired");
    assertThrows(AssertionError.class,
        () -> validator.validateTimestampIsExpired(String.valueOf(futureTS.getEpochSecond())),
        "Future timestamp was recognized as expired");
  }

  /**
   * Verifies boundary behavior: "now" is accepted as expired.
   */
  @Test
  void testTimestampIsExpiredNowBoundary() {
    Instant now = Instant.now();

    assertDoesNotThrow(
        () -> validator.validateTimestampIsExpired(String.valueOf(now.getEpochSecond())),
        "Current timestamp should be accepted as expired");
  }

  /**
   * Verifies the method "timestampIsNotYetExpired".
   */
  @Test
  void testTimestampIsNotYetExpired1() {

    Instant now = Instant.now();
    Instant futureTS = now.plus(30, ChronoUnit.SECONDS);
    Instant pastTS = now.minus(30, ChronoUnit.SECONDS);

    assertDoesNotThrow(
        () -> validator.validateTimestampIsNotYetExpired(
            String.valueOf(futureTS.getEpochSecond())),
        "Future timestamp was recognized as not yet expired");
    assertThrows(AssertionError.class,
        () -> validator.validateTimestampIsNotYetExpired(String.valueOf(pastTS.getEpochSecond())),
        "Past timestamp was not recognized as not yet expired");
  }

  /**
   * Verifies that recent timestamps pass the maximum age validation.
   */
  @Test
  void validateTimestampMaximumAgeAcceptsRecentTimestamp() {
    Instant recent = Instant.now().minus(30, ChronoUnit.SECONDS);

    assertDoesNotThrow(
        () -> validator.validateTimestampMaximumAge(String.valueOf(recent.getEpochSecond()), "60"),
        "Recent timestamp should be accepted");
  }

  /**
   * Verifies that timestamps older than the configured age are rejected.
   */
  @Test
  void validateTimestampMaximumAgeRejectsOldTimestamp() {
    Instant old = Instant.now().minus(120, ChronoUnit.SECONDS);

    assertThrows(AssertionError.class,
        () -> validator.validateTimestampMaximumAge(String.valueOf(old.getEpochSecond()), "60"),
        "Old timestamp should be rejected");
  }

  /**
   * Verifies that timestamps from the current quarter are accepted.
   */
  @Test
  void validateTimestampIsInCurrentQuarterAcceptsCurrentQuarter() {
    Instant now = Instant.now();

    assertDoesNotThrow(
        () -> validator.validateTimestampIsInCurrentQuarter(String.valueOf(now.getEpochSecond())),
        "Current timestamp should be accepted as current quarter");
  }

  /**
   * Verifies that the quarter-grace helper produces an expired timestamp outside the current quarter.
   */
  @Test
  void timestampOutsideQuarterGraceIsExpiredAndOutsideCurrentQuarter() {
    String storedTimestamp = String.valueOf(
        TimestampValidationSteps.timestampOutsideQuarterGrace(Instant.now()).getEpochSecond());
    assertThrows(AssertionError.class,
        () -> validator.validateTimestampIsInCurrentQuarter(storedTimestamp),
        "Timestamp outside quarter grace should not be accepted as current quarter");
    assertDoesNotThrow(
        () -> validator.validateTimestampIsExpired(storedTimestamp),
        "Timestamp outside quarter grace should be in the past");
  }
}
