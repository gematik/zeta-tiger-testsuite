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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimingGlueTest {

  private static final Instant EARLIER = Instant.ofEpochSecond(100);
  private static final Instant MIDDLE = Instant.ofEpochSecond(101);
  private static final Instant LATER = Instant.ofEpochSecond(102);

  /**
   * Verifies that strict timestamp ordering accepts an instant inside the exclusive interval.
   */
  @Test
  void strictBetweenAcceptsValueInsideRange() {
    assertTrue(TimingGlue.isStrictlyBetween(MIDDLE, EARLIER, LATER));
  }

  /**
   * Verifies that strict timestamp ordering rejects values on the lower boundary.
   */
  @Test
  void strictBetweenRejectsLowerBoundary() {
    assertFalse(TimingGlue.isStrictlyBetween(EARLIER, EARLIER, LATER));
  }

  /**
   * Verifies that strict timestamp ordering rejects values on the upper boundary.
   */
  @Test
  void strictBetweenRejectsUpperBoundary() {
    assertFalse(TimingGlue.isStrictlyBetween(LATER, EARLIER, LATER));
  }
}
