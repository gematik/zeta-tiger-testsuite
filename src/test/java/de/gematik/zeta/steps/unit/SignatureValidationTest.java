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

import de.gematik.zeta.steps.JwtSteps;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for signature validation in {@link JwtSteps}.
 */
class SignatureValidationTest {

  private static final String ENCODED_POPP_TOKEN_RESOURCE = "mocks/encoded_popp_token.jwt";

  private final JwtSteps validator = new JwtSteps();


  /**
   * Verifies the method "verifyWithEcPublicKey".
   */
  @Test
  void testVerifyJwkSignature() {

    final String clientAssertionJwt =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImp3ayI6eyJraWQiOiJSQWNNd2FLenBUWlNZcTF1RnRESUR5TVg4Ni1"
            + "iSHlnM2FiYUxWYnpxb1JvIiwia3R5IjoiRUMiLCJhbGciOiJFUzI1NiIsInVzZSI6InNpZyIsImNydiI6IlAt"
            + "MjU2IiwieCI6IldlU1RpU3lQQ0dkZ2pCdnlnTDlRYU5FemNjRmZxZDhQUXprb0tIZjZlcGsiLCJ5IjoiMzE0b"
            + "nhkTVdBaHVFTVdPLXkzMG1fajZDYXpQVGNtbDdTOVVDWElhWUR6WSJ9fQ"
            + ".eyJpc3MiOiI3NmM1OTZkYy0yMzZjLTQ2YjAtYTVjZS00ZWU0ZjE4NTdmYzYiLCJzdWIiOiI3NmM1OTZkYy0y"
            + "MzZjLTQ2YjAtYTVjZS00ZWU0ZjE4NTdmYzYiLCJhdWQiOlsiaHR0cHM6Ly96ZXRhLWxvY2FsLndlc3RldXJvc"
            + "GUuY2xvdWRhcHAuYXp1cmUuY29tL2F1dGgvcmVhbG1zL3pldGEtZ3VhcmQvcHJvdG9jb2wvb3BlbmlkLWNvbm"
            + "5lY3QvdG9rZW4iXSwiZXhwIjoxNzY0MDc4NzU2LCJqdGkiOiJbQkBkNWUyN2M2IiwiY2xpZW50X3N0YXRlbWV"
            + "udCI6eyJzdWIiOiI3NmM1OTZkYy0yMzZjLTQ2YjAtYTVjZS00ZWU0ZjE4NTdmYzYiLCJwbGF0Zm9ybSI6Imxp"
            + "bnV4IiwicG9zdHVyZSI6eyJwbGF0Zm9ybV9wcm9kdWN0X2lkIjoiIiwicHJvZHVjdF9pZCI6InRlc3RfcHJve"
            + "HkiLCJwcm9kdWN0X3ZlcnNpb24iOiIwLjEuMCIsIm9zIjoiTGludXgiLCJvc192ZXJzaW9uIjoiNS4xNS4xNj"
            + "cuNC1taWNyb3NvZnQtc3RhbmRhcmQtV1NMMiIsImFyY2giOiJhbWQ2NCIsInB1YmxpY19rZXkiOiJNRmt3RXd"
            + "ZSEtvWkl6ajBDQVFZSUtvWkl6ajBEQVFjRFFnQUVXZVNUaVN5UENHZGdqQnZ5Z0w5UWFORXpjY0ZmcWQ4UFF6"
            + "a29LSGY2ZXBuZlhpZkYweFlDRzRReFk3N0xmU2ItUG9Kck05TnlhWHRMMVFKY2hwZ1BOZyIsImF0dGVzdGF0a"
            + "W9uX2NoYWxsZW5nZSI6Ijl3R1p3QjdnRW5DSVBVT05BZkcwL01DaFhSK01kejIya3locW4wRnFDTFU9In0sIm"
            + "F0dGVzdGF0aW9uX3RpbWVzdGFtcCI6MTc2NDA3ODcyNn19"
            + ".W3F0yI-EoA6AdPlsOVLPBOnqa-ZbgD2hD8efWxC47TKIBryP0kMuvWK17VU5cewDy9B0LJa2hDl-fHDNw495Dw";

    assertDoesNotThrow(
        () -> validator.verifyJwtSignature(clientAssertionJwt),
        "signature verification was not successful");
  }

  @Test
  void testVerifyX5cSignature() {

    final String encodedPoppToken = loadEncodedPoppToken();

    assertDoesNotThrow(
        () -> validator.verifyJwtSignature(encodedPoppToken),
        "signature verification was not successful");
  }

  private static String loadEncodedPoppToken() {
    final String normalized = ENCODED_POPP_TOKEN_RESOURCE;
    try (InputStream in =
        SignatureValidationTest.class.getClassLoader().getResourceAsStream(normalized)) {
      if (in == null) {
        throw new IllegalArgumentException("Resource not found: " + normalized);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read resource: " + normalized, e);
    }
  }

}
