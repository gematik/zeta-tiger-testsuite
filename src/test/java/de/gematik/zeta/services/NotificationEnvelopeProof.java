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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

/** Authenticates an observed push envelope and compares its decrypted JSON with a known message. */
public final class NotificationEnvelopeProof {
  private static final ObjectMapper JSON = new ObjectMapper();

  private NotificationEnvelopeProof() {}

  /**
   * Authenticates the AES-GCM envelope using the registration's monthly key chain.
   *
   * @param encoded Base64 envelope containing nonce, padded ciphertext, and authentication tag
   * @param issHex registration's 32-byte initial shared secret in hexadecimal
   * @param firstMonth registration month in yyyy-MM format
   * @param encryptedMonth envelope month in yyyy-MM format
   * @param expectedPayload independently known message JSON
   * @throws AssertionError if authentication, framing, or message comparison fails
   */
  public static void verify(String encoded, String issHex, String firstMonth,
      String encryptedMonth, String expectedPayload) {
    try {
      var first = YearMonth.parse(firstMonth);
      var last = YearMonth.parse(encryptedMonth);
      var months = first.until(last, ChronoUnit.MONTHS);
      require(months >= 0 && months <= 12, "Envelope month exceeds the registration window");
      var secret = HexFormat.of().parseHex(issHex);
      require(secret.length == 32, "Initial shared secret must contain 32 bytes");
      byte[] key = null;
      for (long month = 0; month <= months; month++) {
        var info = first.plusMonths(month).toString().getBytes(StandardCharsets.UTF_8);
        var prk = hmac(new byte[32], secret);
        var firstBlock = hmac(prk, join(info, new byte[]{1}));
        key = hmac(prk, join(firstBlock, info, new byte[]{2}));
        secret = firstBlock;
      }
      var envelope = Base64.getDecoder().decode(encoded);
      require(envelope.length == 1052, "Envelope has an unexpected length");
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(envelope, 12)));
      var padded = cipher.doFinal(envelope, 12, envelope.length - 12);
      require(padded.length == 1024
          && "PNM1".equals(new String(padded, 0, 4, StandardCharsets.US_ASCII)),
          "Decrypted payload has an invalid frame");
      var offset = 6 + ((padded[4] & 0xff) << 8) + (padded[5] & 0xff);
      require(offset >= 6 && offset <= padded.length, "Invalid message padding length");
      var actual = JSON.readTree(new String(padded, offset,
          padded.length - offset, StandardCharsets.UTF_8));
      require(JSON.readTree(expectedPayload).equals(actual),
          "Authenticated envelope does not contain the expected message");
    } catch (GeneralSecurityException | RuntimeException exception) {
      throw new AssertionError("Push envelope could not be authenticated", exception);
    }
  }

  private static byte[] hmac(byte[] key, byte[] value) throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(value);
  }

  private static byte[] join(byte[]... parts) {
    var length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
    var joined = new byte[length];
    var offset = 0;
    for (var part : parts) {
      System.arraycopy(part, 0, joined, offset, part.length);
      offset += part.length;
    }
    return joined;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
