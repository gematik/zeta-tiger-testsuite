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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Tests authenticated decryption against a fixed independently generated push envelope. */
class NotificationEnvelopeProofTest {
  private static final String ISS =
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
  private static final String PAYLOAD = "{\"message\":\"private-message-5da4a573\"}";
  private static final String ENVELOPE =
      "AAECAwQFBgcICQoL3wiIY93rBa1LEuG9r61x978J+90xflVIMQMt1U6VUH/x2cfrHBT/92Hb6TIk"
        + "T/Bc1gvc+bagHATp5wpIx/eB23s7RjIi1+3TPTrwTlEFtnvUyiZqm34Z/tXR0wctXTZrE/RPjCZS"
        + "8hveEREzu8TsbunXMbKTRGTLe4uXSOW5PhqdX/QjIpumaLWk3WVXnMZC5s9emDtchGsBG7MRYIBn"
        + "6MkpkIFJaNvHT2NpWOOXg2blQc3wa3k2p+I9TCKwicDm+/0qphfz+37cl6NuKnbC2H60Wcc/pnox"
        + "UPi8PNT2571X/6XynC57Y/QT0Mpp0cN0xqlJ/ZB7QWVGV5AN+RE2R7FUlZ4DnsbFNR2Od487ITTE"
        + "Jt1WGf+9JVW9DiNL3lXHH+mcZE9kcDQwykW8Sgogtw11Yz8jNduUSketON5lGq0fYCMWk5TVzCbt"
        + "FdCImFYIBnF4UjPpgwjkZkceLrwZCqw9R5u91MOLBuKO2ppWXnWEMrbWt+yPO68mRpDdQWJMbXGT"
        + "/w4H1aZzXHvwJr3lCGGQooDdRnTt9NCJqkAZRIHpSlHV1IFv65GB/SjjQUJre3rQuG1CH9ADjHRe"
        + "fq/ycdoQXc9vJuboRIgzo8Lf5xn9AfmfbawbY8b20Z/NCUQQcq9lDUybDcO2aCaLSKaFgvACzUGE"
        + "tF63QCGURXmTYof9rR2bSCOe9SpV1nBaoWWwfMfpcDCqoJ06K0jgtQtVTo0t6LIcuHW9fqE9P63n"
        + "VwnwpUSTXqkrh/qMY+7EciK5pO5mvGhYEAjX7lJbXmHBg5kmDK5FzjbhVRQRaJ7LIkHsvP/NvSVH"
        + "quBn+XYgNblOM/ahNugK4VrdtG8+7I7h7QRYTOlSEPhv1sie78aYLbcxV4TIYrFKPbQ6ZcHb1CPD"
        + "hbwo7/Sb6KLXzYTZ8oMy9ZDhSHFjMIeQMy3KkDO7StvXQ+0UayKy1j4YQf6YhsLG0X3k7Rai+MS9"
        + "PLjMNCOzIAuzv8+fDdvdMgnGlI9ghQXYsqWM5zHrmpZdpJzqs2yjo8GjYH9dpEiwMA50MHY0Khor"
        + "raZK9W5yLKOOXWQNLqqKJCaYgcAWskp54FIiBDfkA+YcwxQu6Tdhr/VQV71ACrrLTUqoJvpsr/o3"
        + "VWO+6toXbvt7m0vlcnbuu2VmG/UdON+LQfZ4pNS0nO191Qh7jg1bwO+oy5J1OvD8gm3H8gTL++NJ"
        + "OrL+FbWdoFoXD044qvMdaGcf9IOd5J61gR6mUqMhCD23nx1UYCLdtu8jI2ohLF64sXh0vLJXIC0D"
        + "tYTwwpunQNoAxJ0ZV1l+rn8hou2MA1wjYxJmf8Zy+1rtLUdFWhgMfr3SyI0ab3UWnwytjQmSTIL7"
        + "P6xSidNo2AvEoSW4a88NnDvcKxER6Ar4Uzk=";

  @Test
  void authenticatesAndRecoversKnownPayload() {
    assertThatCode(() -> NotificationEnvelopeProof.verify(
        ENVELOPE, ISS, "2026-10", "2026-10", PAYLOAD)).doesNotThrowAnyException();
  }

  @Test
  void rejectsChangedCiphertext() {
    var modified = Base64.getDecoder().decode(ENVELOPE);
    modified[42] ^= 1;
    assertThatThrownBy(() -> NotificationEnvelopeProof.verify(
        Base64.getEncoder().encodeToString(modified), ISS, "2026-10", "2026-10", PAYLOAD))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void rejectsAnUnrelatedOriginalMessage() {
    assertThatThrownBy(() -> NotificationEnvelopeProof.verify(
        ENVELOPE, ISS, "2026-10", "2026-10", "{}"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void rejectsAnEnvelopeOutsideTheRegistrationWindow() {
    assertThatThrownBy(() -> NotificationEnvelopeProof.verify(
        ENVELOPE, ISS, "2026-10", "2026-09", PAYLOAD))
        .isInstanceOf(AssertionError.class);
  }
}
