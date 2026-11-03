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

package de.gematik.zeta.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AsReadOnlyRuntimeTest {
  @Test
  void parsesOnlyTheActualServerEpochObservation() {
    assertEquals(Instant.ofEpochSecond(1800000000),
        AsReadOnlyRuntime.parseClockObservation("1800000000\n"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "not-a-clock", "-1800000000", "1800000000 extra",
      "1800000000\n1800000001", "123", "1800000000000"})
  void rejectsMissingMalformedAndAmbiguousServerClockEvidence(String value) {
    assertThrows(AssertionError.class, () -> AsReadOnlyRuntime.parseClockObservation(value));
  }
}
