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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.steps.JwtTestHelper;
import de.gematik.zeta.steps.SignatureVerificationSteps;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for signature validation in {@link SignatureVerificationSteps}.
 */
class SignatureVerifikationTest {

  private final SignatureVerificationSteps verifier = new SignatureVerificationSteps();

  /**
   * Tests the verification of an elliptic curve JWK signature with a referenced public key.
   */
  @Test
  public void testVerifyJwtSignatureWithKeyFromJwks() {
    var jwks = """
        {
          "keys": [
            {
              "kid": "3Uc_qfgy3NM9l_k0Fs3ExYeDt3Fl8rCM7JyUcaMPI7k",
              "kty": "EC",
              "alg": "ES256",
              "use": "sig",
              "crv": "P-256",
              "x": "648xZLyAJcbWJeAqXW-E8-8YoqMfM9_JHwjyHXR9j1g",
              "y": "-RhgANrdzCawTwdNRnuQFRuO0l9wXbmDkEthqRhUiVk"
            }
          ]
        }
        """;

    var accessTokenJwt =
        "eyJhbGciOiJFUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICIzVWNfcWZneTNOTTlsX2swRnMzRXhZZUR0M0ZsOH"
            + "JDTTdKeVVjYU1QSTdrIn0"
            + ".eyJleHAiOjE3NzMyMzYwMDIsImlhdCI6MTc3MzIzNTcwMiwianRpIjoib25ydHRlOmNh"
            + "YjEwZTI0LTJjOWQtNTRkYy05ZDRkLWEyMWYzODg4NzY3ZCIsImlzcyI6Imh0dHBzOi8vemV0YS1raW5kLmxvY2FsL2"
            + "F1dGgvcmVhbG1zL3pldGEtZ3VhcmQiLCJhdWQiOiJodHRwczovL3pldGEta2luZC5sb2NhbCIsInN1YiI6IjEtMjAw"
            + "MTQwNjA2MjUiLCJ0eXAiOiJEUG9QIiwiYXpwIjoiYTg4MTk0NTMtNzIyZC00NjI0LTk1N2UtNjE4MmQ5Y2YxMmQzIi"
            + "wic2lkIjoiNkNfbjlGdU9ibngydld0dk9hY3dRc01QIiwiYWNyIjoiMSIsImNuZiI6eyJqa3QiOiJMV2VlVU9wTEZC"
            + "UmlkdVJMakQ5bzZ6VDFNemlHeFpGV3g1enN0MHVncHEwIn0sInNjb3BlIjoiemVybzphdWRpZW5jZSBwcm9maWxlIG"
            + "VtYWlsIiwiZW1haWxfdmVyaWZpZWQiOmZhbHNlLCJ1ZGF0Ijp7InRlbGlkIjoiMS0yMDAxNDA2MDYyNSIsInByb2Yi"
            + "OiIxLjIuMjc2LjAuNzYuNC41MCJ9LCJuYW1lIjoiY0wtYzhsc2FXWTc4YlhlQ0lXcGdWM3RfYl9WemgxTUg5T29INm"
            + "ltcEU4VSBjTC1jOGxzYVdZNzhiWGVDSVdwZ1YzdF9iX1Z6aDFNSDlPb0g2aW1wRThVIiwiY2RhdCI6eyJuYW1lIjoi"
            + "IiwiY2xpZW50X2lkIjoiYTg4MTk0NTMtNzIyZC00NjI0LTk1N2UtNjE4MmQ5Y2YxMmQzIiwibWFudWZhY3R1cmVyX2"
            + "lkIjoiIiwibWFudWZhY3R1cmVyX25hbWUiOiIiLCJvd25lcl9tYWlsIjoidGVzdEBlbWFpbHRlc3QuZGUiLCJyZWdp"
            + "c3RyYXRpb25fdGltZXN0YW1wIjoxNzczMjM1NzAxLCJwbGF0Zm9ybV9wcm9kdWN0X2lkIjp7InBsYXRmb3JtIjoibG"
            + "ludXgiLCJwYWNrYWdpbmdfdHlwZSI6InBhY2thZ2luZ1R5cGUiLCJhcHBsaWNhdGlvbl9pZCI6InRlc3QtZHJpdmVy"
            + "In19LCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJjbC1jOGxzYXd5NzhieGVjaXdwZ3YzdF9iX3Z6aDFtaDlvb2g2aW1wZT"
            + "h1IiwiZ2l2ZW5fbmFtZSI6ImNMLWM4bHNhV1k3OGJYZUNJV3BnVjN0X2JfVnpoMU1IOU9vSDZpbXBFOFUiLCJmYW1p"
            + "bHlfbmFtZSI6ImNMLWM4bHNhV1k3OGJYZUNJV3BnVjN0X2JfVnpoMU1IOU9vSDZpbXBFOFUiLCJlbWFpbCI6ImNsLW"
            + "M4bHNhd3k3OGJ4ZWNpd3BndjN0X2JfdnpoMW1oOW9vaDZpbXBlOHVAZ2VtYXRpay5kZSJ9"
            + ".fhQ3PnRmc60a9FdvPQ6HrRT6OFxxGxcpZytSVd4mjTEoHXNx-Gdd_yD-AHPNTOzhZT4yH3fg06GJcq0eXAsyhA";

    assertDoesNotThrow(
        () -> verifier.verifyJwtSignatureFromKid(accessTokenJwt, jwks),
        "signature verification was not successful");

  }

  /**
   * Tests that the signature verification is skipped if the algorithm is not ES256.
   */
  @Test
  void testVerifyJwtSignatureFromKidRejectsNonEs256BeforeKeystoreLookup() {
    var certsResponse = """
        {
          "keys": [
            {
              "kid": "different-kid",
              "kty": "EC",
              "alg": "ES256",
              "use": "sig",
              "crv": "P-256",
              "x": "648xZLyAJcbWJeAqXW-E8-8YoqMfM9_JHwjyHXR9j1g",
              "y": "-RhgANrdzCawTwdNRnuQFRuO0l9wXbmDkEthqRhUiVk"
            }
          ]
        }
        """;
    var hs512Jwt =
        "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCIsImtpZCI6Im1pc3Npbmcta2lkIn0"
            + ".eyJzdWIiOiJ0ZXN0In0"
            + ".c2lnbmF0dXJl";

    var error = assertThrows(
        AssertionError.class,
        () -> verifier.verifyJwtSignatureFromKid(hs512Jwt, certsResponse));

    assertTrue(error.getMessage().contains("JWT must use ES256"));
  }

  /**
   * Tests that cryptographic verification by {@code kid} works for unquoted JOSE header keys.
   */
  @Test
  void hasCryptographicallyValidEs256SignatureFromKidAcceptsUnquotedHeaderKeys()
      throws Exception {
    var keyPair = createP256KeyPair();
    var jwks = createJwks("unquoted-kid", (ECPublicKey) keyPair.getPublic());
    var jwt = JwtTestHelper.createSignedJwt(
        "{alg:\"ES256\",kid:\"unquoted-kid\",typ:\"JWT\"}",
        "{\"sub\":\"test-subject\"}",
        keyPair.getPrivate());

    assertTrue(verifier.hasCryptographicallyValidEs256SignatureFromKid(jwt, jwks));
  }

  /**
   * Tests that cryptographic verification by {@code kid} works for unquoted payload keys.
   */
  @Test
  void hasCryptographicallyValidEs256SignatureFromKidAcceptsUnquotedPayloadKeys()
      throws Exception {
    var keyPair = createP256KeyPair();
    var jwks = createJwks("payload-kid", (ECPublicKey) keyPair.getPublic());
    var jwt = JwtTestHelper.createSignedJwt(
        "{\"alg\":\"ES256\",\"kid\":\"payload-kid\",\"typ\":\"JWT\"}",
        "{sub:\"test-subject\"}",
        keyPair.getPrivate());

    assertTrue(verifier.hasCryptographicallyValidEs256SignatureFromKid(jwt, jwks));
  }

  /**
   * Tests that unquoted-key variants still reject tokens with an invalid signature.
   */
  @Test
  void hasCryptographicallyValidEs256SignatureFromKidRejectsInvalidUnquotedHeaderSignature()
      throws Exception {
    var keyPair = createP256KeyPair();
    var jwks = createJwks("invalid-signature-kid", (ECPublicKey) keyPair.getPublic());
    var validJwt = JwtTestHelper.createSignedJwt(
        "{alg:\"ES256\",kid:\"invalid-signature-kid\",typ:\"JWT\"}",
        "{\"sub\":\"test-subject\"}",
        keyPair.getPrivate());
    var invalidJwt = validJwt.substring(0, validJwt.lastIndexOf('.') + 1) + "AAAA";

    assertFalse(verifier.hasCryptographicallyValidEs256SignatureFromKid(invalidJwt, jwks));
  }

  /**
   * Tests the verification of a JWT signature using the JWK from a JOSE header.
   */
  @Test
  void testVerifyJwtSignatureWithJwk() {

    final var clientAssertionJwt =
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
        () -> verifier.verifyJwtSignature(clientAssertionJwt),
        "JWK signature verification was not successful");
  }

  /**
   * Tests the verification of a JWT signature using the X.509 certificate
   * from the JOSE header.
   */
  @Test
  void testVerifyJwtSignatureWithX5c() {

    var subjectToken =
        "eyJ0eXAiOiJKV1QiLCJraWQiOiJ3NjI5MTVJaUw1bzFVZ2o0Q3NHbzR0MUVid3EweDhiNTdwVmFldS1qemFvIiwieDV"
            + "jIjpbIk1JSURORENDQXR1Z0F3SUJBZ0lIQWxEK0dyZ01uakFLQmdncWhrak9QUVFEQWpDQmxURUxNQWtHQTFV"
            + "RUJoTUNSRVV4R2pBWUJnTlZCQW9NRVdkbGJXRjBhV3NnVGs5VUxWWkJURWxFTVVnd1JnWURWUVFMREQ5SmJuT"
            + "jBhWFIxZEdsdmJpQmtaWE1nUjJWemRXNWthR1ZwZEhOM1pYTmxibk10UTBFZ1pHVnlJRlJsYkdWdFlYUnBhMm"
            + "x1Wm5KaGMzUnlkV3QwZFhJeElEQWVCZ05WQkFNTUYwZEZUUzVUVFVOQ0xVTkJOVGNnVkVWVFZDMVBUa3haTUI"
            + "0WERUSTFNRFl3TlRJeU1EQXdNRm9YRFRNd01EWXdOVEl4TlRrMU9Wb3dYREVMTUFrR0ExVUVCaE1DUkVVeEhE"
            + "QWFCZ05WQkFvTUV6TXdNREEyTURZeU5TQk9UMVF0VmtGTVNVUXhMekF0QmdOVkJBTU1Ka0Z5ZW5Sd2NtRjRhW"
            + "E1nUVc1dUxVSmxZWFJ5YVhobElGcGxkR0VnVkVWVFZDMVBUa3haTUZvd0ZBWUhLb1pJemowQ0FRWUpLeVFEQX"
            + "dJSUFRRUhBMElBQkZRdUVrTENYNWtKY1dhR1lYZGFSVGRUQWpBaEVrRGw5Q1dXZDh2RkhZR1NobWpoY0ZobTV"
            + "iSWV4NFIzSkVxZ2h2a1AwZkpnemdvOUF6QWF1Ukx6WkRHamdnRkxNSUlCUnpBT0JnTlZIUThCQWY4RUJBTUNC"
            + "NEF3REFZRFZSMFRBUUgvQkFJd0FEQXNCZ05WSFI4RUpUQWpNQ0dnSDZBZGhodG9kSFJ3T2k4dlpXaGpZUzVuW"
            + "lcxaGRHbHJMbVJsTDJOeWJDOHdSUVlGS3lRSUF3TUVQREE2TURnd05qQTBNREl3Rmd3VVFtVjBjbWxsWW5Oem"
            + "RNT2tkSFJsSUVGeWVuUXdDUVlIS29JVUFFd0VNaE1OTVMweU1EQXhOREEyTURZeU5UQWRCZ05WSFE0RUZnUVV"
            + "QeDhZMW82QVNBbi80aWlXVjE2OFBtQ3JLek13RXdZRFZSMGxCQXd3Q2dZSUt3WUJCUVVIQXdJd0lBWURWUjBn"
            + "QkJrd0Z6QUtCZ2dxZ2hRQVRBU0JJekFKQmdjcWdoUUFUQVJOTUI4R0ExVWRJd1FZTUJhQUZMWHZkWDZabWhmS"
            + "jAzY3ZXeEhGaERNdkJaeFJNRHNHQ0NzR0FRVUZCd0VCQkM4d0xUQXJCZ2dyQmdFRkJRY3dBWVlmYUhSMGNEb3"
            + "ZMMlZvWTJFdVoyVnRZWFJwYXk1a1pTOWxZMk10YjJOemNEQUtCZ2dxaGtqT1BRUURBZ05IQURCRUFpQVdGeWt"
            + "4RGNQSzhhdTZRVXJrZ21wZzU5bUdFb2lnbklQRS8rL2pFeURsQ2dJZ1pCV1FCL0FTR2VQanJZV2FpZUl4ekNp"
            + "MSt3RUJxalZQUTgzeDdET1pEdUE9Il0sImFsZyI6IkVTMjU2In0"
            + ".eyJpc3MiOiJlN2E0OTEwNy0yMDYxLTRmOTMtODhkZC1jY2M2ZWMzZGVkZjQiLCJleHAiOjE3Nzg1MTY5MTks"
            + "ImF1ZCI6WyJodHRwczovL3pldGEta2luZC5sb2NhbC9hdXRoLyJdLCJzdWIiOiIxLTIwMDE0MDYwNjI1Iiwia"
            + "WF0IjoxNzc4NTE2ODg5LCJub25jZSI6Im9memRfWXM5VjVLdFFFdldRdFZHbHciLCJqdGkiOiI0YjQzNGVlMC"
            + "0wMTUxLTRjZjMtYTI0MS1hMzIwN2E5Y2ZhYzAiLCJ0eXAiOiJCZWFyZXIiLCJjbGllbnRfa2V5Ijp7ImprdCI"
            + "6IlozZWpzNWpzcDZzSmFGREpWVzZtSUZvc2pST1dSVjd6b0ZRVEtwcmJQYkkifSwiZHBvcF9rZXkiOnsiamt0"
            + "IjoicWVwT184MDlFN0xrUjExUFRnNmhlY3o4NW92ZC1sQ3labmZjU2NtNUM5RSJ9fQ"
            + ".lE0buRB4kHpwlXTfPDMb1m1MVoejIiNDi6z7EjYUwgtiNk-2UCffGMu3DusW_-EBH35P29G0G8yUYtjfdV_pOA";

    assertDoesNotThrow(
        () -> verifier.verifyJwtSignature(subjectToken),
        "X5C signature verification was not successful");

    assertTrue(
        verifier.hasCryptographicallyValidEs256SignatureWithEmbeddedCertificate(subjectToken),
        "signature verification with embedded certificate was not successful");
  }

  /**
   * Creates a P-256 key pair for focused ES256 verification tests.
   *
   * @return generated P-256 key pair
   * @throws Exception if the platform cannot generate the key pair
   */
  private static KeyPair createP256KeyPair() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  /**
   * Creates a minimal JWKS document for one EC public key.
   *
   * @param kid key identifier
   * @param publicKey EC public key
   * @return JWKS JSON
   */
  private static String createJwks(String kid, ECPublicKey publicKey) {
    return """
        {
          "keys": [
            {
              "kid": "%s",
              "kty": "EC",
              "alg": "ES256",
              "use": "sig",
              "crv": "P-256",
              "x": "%s",
              "y": "%s"
            }
          ]
        }
        """.formatted(
            kid,
            base64UrlUnsigned(publicKey.getW().getAffineX()),
            base64UrlUnsigned(publicKey.getW().getAffineY()));
  }

  /**
   * Encodes an unsigned EC coordinate as unpadded Base64URL.
   *
   * @param value positive coordinate value
   * @return fixed-width Base64URL encoded coordinate
   */
  private static String base64UrlUnsigned(BigInteger value) {
    var bytes = value.toByteArray();
    if (bytes.length > 32) {
      bytes = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
    }
    if (bytes.length < 32) {
      var padded = new byte[32];
      System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
      bytes = padded;
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

}
