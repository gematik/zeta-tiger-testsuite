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
import org.junit.jupiter.api.Test;

/**
 * Unit tests for signature validation in {@link JwtSteps}.
 */
class SignatureValidationTest {


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

  // TODO: fix and activate again
  //  @Test
  //  void testVerifyX5cSignature() {
  //    String subjectToken =
  //        "eyJ0eXAiOiJKV1QiLCJraWQiOiJ3NjI5MTVJaUw1bzFVZ2o0Q3NHbzR0MUVid3EweDhiNTdwVmFldS1qemFvIiwieDVj"
  //            + "IjpbIk1JSURORENDQXR1Z0F3SUJBZ0lIQWxEK0dyZ01uakFLQmdncWhrak9QUVFEQWpDQmxURUxNQWtHQTFVRU"
  //            + "JoTUNSRVV4R2pBWUJnTlZCQW9NRVdkbGJXRjBhV3NnVGs5VUxWWkJURWxFTVVnd1JnWURWUVFMREQ5SmJuTjBh"
  //            + "WFIxZEdsdmJpQmtaWE1nUjJWemRXNWthR1ZwZEhOM1pYTmxibk10UTBFZ1pHVnlJRlJsYkdWdFlYUnBhMmx1Wm"
  //            + "5KaGMzUnlkV3QwZFhJeElEQWVCZ05WQkFNTUYwZEZUUzVUVFVOQ0xVTkJOVGNnVkVWVFZDMVBUa3haTUI0WERU"
  //            + "STFNRFl3TlRJeU1EQXdNRm9YRFRNd01EWXdOVEl4TlRrMU9Wb3dYREVMTUFrR0ExVUVCaE1DUkVVeEhEQWFCZ0"
  //            + "5WQkFvTUV6TXdNREEyTURZeU5TQk9UMVF0VmtGTVNVUXhMekF0QmdOVkJBTU1Ka0Z5ZW5Sd2NtRjRhWE1nUVc1"
  //            + "dUxVSmxZWFJ5YVhobElGcGxkR0VnVkVWVFZDMVBUa3haTUZvd0ZBWUhLb1pJemowQ0FRWUpLeVFEQXdJSUFRRU"
  //            + "hBMElBQkZRdUVrTENYNWtKY1dhR1lYZGFSVGRUQWpBaEVrRGw5Q1dXZDh2RkhZR1NobWpoY0ZobTViSWV4NFIz"
  //            + "SkVxZ2h2a1AwZkpnemdvOUF6QWF1Ukx6WkRHamdnRkxNSUlCUnpBT0JnTlZIUThCQWY4RUJBTUNCNEF3REFZRF"
  //            + "ZSMFRBUUgvQkFJd0FEQXNCZ05WSFI4RUpUQWpNQ0dnSDZBZGhodG9kSFJ3T2k4dlpXaGpZUzVuWlcxaGRHbHJM"
  //            + "bVJsTDJOeWJDOHdSUVlGS3lRSUF3TUVQREE2TURnd05qQTBNREl3Rmd3VVFtVjBjbWxsWW5OemRNT2tkSFJsSU"
  //            + "VGeWVuUXdDUVlIS29JVUFFd0VNaE1OTVMweU1EQXhOREEyTURZeU5UQWRCZ05WSFE0RUZnUVVQeDhZMW82QVNB"
  //            + "bi80aWlXVjE2OFBtQ3JLek13RXdZRFZSMGxCQXd3Q2dZSUt3WUJCUVVIQXdJd0lBWURWUjBnQkJrd0Z6QUtCZ2"
  //            + "dxZ2hRQVRBU0JJekFKQmdjcWdoUUFUQVJOTUI4R0ExVWRJd1FZTUJhQUZMWHZkWDZabWhmSjAzY3ZXeEhGaERN"
  //            + "dkJaeFJNRHNHQ0NzR0FRVUZCd0VCQkM4d0xUQXJCZ2dyQmdFRkJRY3dBWVlmYUhSMGNEb3ZMMlZvWTJFdVoyVn"
  //            + "RZWFJwYXk1a1pTOWxZMk10YjJOemNEQUtCZ2dxaGtqT1BRUURBZ05IQURCRUFpQVdGeWt4RGNQSzhhdTZRVXJr"
  //            + "Z21wZzU5bUdFb2lnbklQRS8rL2pFeURsQ2dJZ1pCV1FCL0FTR2VQanJZV2FpZUl4ekNpMSt3RUJxalZQUTgzeD"
  //            + "dET1pEdUE9Il0sImFsZyI6IkVTMjU2In0.eyJpc3MiOiJiYTNiZmFmZi0xMzRiLTQzYzAtOTZiYS0zMDQ1NjI4"
  //            + "MDIwYmQiLCJleHAiOjE3NjQxNzEyNDIsImF1ZCI6WyJodHRwczovL3pldGEtbG9jYWwud2VzdGV1cm9wZS5jbG"
  //            + "91ZGFwcC5henVyZS5jb20vYXV0aC8iXSwic3ViIjoiMS0yMDAxNDA2MDYyNSIsImlhdCI6MTc2NDE3MTIxMiwi"
  //            + "bm9uY2UiOiJVZGt0aFh4cTdXNGNIdlJ4TTlBZHlBIiwianRpIjoiOTE1OTY1NGYtYzcwNy00ZDFlLTgwYTUtMm"
  //            + "RlY2JhMmMzOGQ2IiwidHlwIjoiQmVhcmVyIn0.cgmlFZugxuuIqN36dlk6VhEteWiPVeKNyHUInHLCXvZ6sr34"
  //            + "d-cumVvcta-hrMw7pld8gBq3HLWS8tyAuPoz0w";
  //
  //    assertDoesNotThrow(
  //        () -> validator.verifyJwtSignature(subjectToken),
  //        "signature verification was not successful");
  //  }

}
