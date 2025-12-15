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

}
