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

package de.gematik.zeta.services.unit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.services.KvnrGenerator;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KvnrGenerator}.
 */
class KvnrGeneratorTest {

  /**
   * Verifies the published KVNR example and its modified check-digit variant.
   */
  @Test
  void validatesKnownKvnrExamples() {
    assertTrue(KvnrGenerator.isValid("X110411675"));
    assertTrue(KvnrGenerator.isValid("A123456780"));
    assertFalse(KvnrGenerator.isValid("X110411674"));
  }

  /**
   * Verifies that generated KVNRs have the expected shape and check digit.
   */
  @Test
  void generatesValidRandomKvnr() {
    for (var index = 0; index < 100; index++) {
      var kvnr = KvnrGenerator.generateRandomValidKvnr();
      assertTrue(KvnrGenerator.isValid(kvnr));
      assertTrue(kvnr.matches("[A-Z][0-9]{9}"));
    }
  }
}
