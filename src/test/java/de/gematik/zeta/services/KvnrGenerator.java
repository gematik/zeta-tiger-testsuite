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

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Creates and validates ten-character Krankenversichertennummern (KVNR).
 *
 * <p>The implementation follows the published KVNR rule: the first character is an uppercase
 * letter without umlaut, followed by eight digits and one check digit. For the check digit, the
 * letter is replaced by its two-digit alphabetic value ({@code A=01} through {@code Z=26}); the
 * resulting ten digits use alternating weights {@code 1,2,1,2,1,2,1,2,1,2}. Each product is
 * reduced to its digit sum, all reduced products are added, and the sum modulo 10 is used as the
 * check digit.</p>
 *
 * @see <a href="https://de.wikipedia.org/wiki/Krankenversichertennummer">Krankenversichertennummer – Wikipedia</a>
 * @see <a href="https://www.gkv-datenaustausch.de/media/dokumente/kvnr/Anlage_1_20230102_Pruefziffernberechnung-KVNR.pdf">GKV-Datenaustausch – Prüfziffernberechnung für die KVNR</a>
 */
public final class KvnrGenerator {

  private static final int RANDOM_DIGIT_COUNT = 8;
  private static final int KVNR_LENGTH = 10;
  private static final Pattern KVNR_PATTERN = Pattern.compile("[A-Z][0-9]{9}");
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Prevents instantiation of this utility class.
   */
  private KvnrGenerator() {
    // Utility class.
  }

  /**
   * Generates a random valid ten-character KVNR.
   *
   * <p>The first character and the following eight digits are random. The final character is
   * calculated according to the modulo-10 rule documented in the class Javadoc.</p>
   *
   * @return randomly generated valid KVNR
   */
  public static String generateRandomValidKvnr() {
    var kvnrWithoutCheckDigit = new StringBuilder(KVNR_LENGTH - 1)
        .append((char) ('A' + RANDOM.nextInt(26)));
    for (var index = 0; index < RANDOM_DIGIT_COUNT; index++) {
      kvnrWithoutCheckDigit.append(RANDOM.nextInt(10));
    }

    return kvnrWithoutCheckDigit.append(calculateCheckDigit(kvnrWithoutCheckDigit.toString())).toString();
  }

  /**
   * Checks the shape and check digit of a ten-character KVNR.
   *
   * @param kvnr value to validate
   * @return {@code true} when the value is a valid KVNR
   */
  public static boolean isValid(String kvnr) {
    if (kvnr == null || !KVNR_PATTERN.matcher(kvnr).matches()) {
      return false;
    }

    return Character.digit(kvnr.charAt(KVNR_LENGTH - 1), 10)
        == calculateCheckDigit(kvnr.substring(0, KVNR_LENGTH - 1));
  }

  /**
   * Calculates the check digit for the first nine characters of a KVNR.
   *
   * <p>The letter occupies the first two numeric positions after conversion to its alphabetic
   * value. The remaining eight digits are then processed directly with the alternating weights,
   * avoiding a temporary numeric array.</p>
   *
   * @param kvnrWithoutCheckDigit KVNR prefix containing the letter and eight digits
   * @return check digit in the range 0 to 9
   */
  private static int calculateCheckDigit(String kvnrWithoutCheckDigit) {
    if (kvnrWithoutCheckDigit == null
        || kvnrWithoutCheckDigit.length() != KVNR_LENGTH - 1
        || kvnrWithoutCheckDigit.charAt(0) < 'A'
        || kvnrWithoutCheckDigit.charAt(0) > 'Z') {
      throw new IllegalArgumentException("KVNR prefix must contain one uppercase letter and eight digits");
    }

    return calculateChecksum(kvnrWithoutCheckDigit) % 10;
  }

  /**
   * Calculates the unmodulated checksum for a KVNR prefix.
   *
   * <p>This is the sum of the reduced products from the alternating-weight calculation. The
   * final modulo-10 operation is kept in {@link #calculateCheckDigit(String)} so this method
   * represents the intermediate value named in the published algorithm.</p>
   *
   * @param kvnrWithoutCheckDigit KVNR prefix containing the letter and eight digits
   * @return checksum before applying modulo 10
   */
  private static int calculateChecksum(String kvnrWithoutCheckDigit) {
    var letterValue = kvnrWithoutCheckDigit.charAt(0) - 'A' + 1;
    var checksum = reduceProduct(letterValue / 10);
    checksum += reduceProduct((letterValue % 10) * 2);

    for (var index = 1; index < kvnrWithoutCheckDigit.length(); index++) {
      var digit = kvnrWithoutCheckDigit.charAt(index) - '0';
      if (digit < 0 || digit > 9) {
        throw new IllegalArgumentException("KVNR prefix must contain one uppercase letter and eight digits");
      }
      checksum += reduceProduct(digit * (index % 2 == 1 ? 1 : 2));
    }
    return checksum;
  }

  /**
   * Reduces a weighted product to its decimal digit sum as required by the KVNR algorithm.
   *
   * @param product weighted digit product
   * @return product digit sum in the range 0 to 9
   */
  private static int reduceProduct(int product) {
    return product >= 10 ? product - 9 : product;
  }
}
