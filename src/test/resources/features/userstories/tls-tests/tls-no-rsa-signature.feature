#
# #%L
# ZETA Testsuite
# %%
# (C) 2025 achelos GmbH, licensed for gematik GmbH
# %%
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# *******
#
# For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
# #L%
#
#language:de

  @TLS_Test_01_01
  Funktionalität: RSA-Signaturalgorithmen dürfen für TLS nicht unterstützt werden.

  @dev
  @no_proxy
  Szenariogrundriss: RSA-Signaturalgorithmen dürfen für TLS 1.2 nicht unterstützt werden

    Gegeben sei die TlsTestTool-Konfigurationsdaten für den Host "<host>" mit den folgenden TLS 1.2 Signatur-Hash-Algorithmen wurden festgelegt:
      | RSA_MD5 |
      | RSA_SHA1 |
      | RSA_SHA224 |
      | RSA_SHA256 |
      | RSA_SHA384 |
      | RSA_SHA512 |

    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Dann Akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id "28".

    Beispiele:
      | host |
      # Zeta Guard Ingress
      | ${zeta_base_url} |


    @dev
    @no_proxy
    Szenariogrundriss: RSA-Signaturalgorithmen dürfen für TLS 1.3 nicht unterstützt werden

      Gegeben sei die TlsTestTool-Konfigurationsdaten für den Host "<host>" mit den folgenden TLS 1.3 Signature-Schemes wurden festgelegt:
        | rsa_pkcs1_sha256 |
        | rsa_pkcs1_sha384 |
        | rsa_pkcs1_sha512 |

      # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
      Dann Akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id "28".

      Beispiele:
        | host |
      # Zeta Guard Ingress
        | ${zeta_base_url} |