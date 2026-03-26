#
# #%L
# ZETA Testsuite
# %%
# (C) achelos GmbH, 2025, licensed for gematik GmbH
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

@UseCase_TLS_01
Funktionalität: TLS Konformitaet Zeta Guard

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR sende eine leere GET Anfrage an "${paths.tigerProxy.baseUrl}/resetMessages"

  @dev
  @A_18464
  @GS-A_5542
  @TA_A_18464_01
  @TA_GS-A_5542_01
  @no_proxy
  Szenariogrundriss: TLS 1.1 darf nicht unterstützt werden.
    Gegeben sei die TlsTestTool-Konfigurationsdaten für den Host "<host>" mit nur TLS 1.1 wurde erstellt
    # 46 in hexadezimal entspricht 70 in dezimal und ist der Alert Code für TLS Protocol Version Failure.
    Dann akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id "46"
    Beispiele:
      | host             |
      # Zeta Guard Ingress
      | ${zeta_base_url} |

  @dev
  @GS-A_5526
  @TA_GS-A_5526_01
  @no_proxy
  Szenariogrundriss: TLS-Renegotiation-Indication-Extension - ZETA Guard - TLS-Renegotiation-Indication-Extension.
    Gegeben sei die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host "<host>" für TLS Renegotiation
    Dann ist der TLS-Handshake erfolgreich
    Dann ist die Erweiterung renegotiation_info im Server-Hello vorhanden
    Dann wird die TLS-Handshake-renegotiation gestartet
    Dann ist die TLS-Handshake-renegotiation erfolgreich
    Beispiele:
      | host             |
      # Zeta Guard Ingress
      | ${zeta_base_url} |

  @dev
  @A_21275-01
  @TA_A_21275-01_03
  @no_proxy
  Szenariogrundriss: TLS-Verbindungen, zulässige Hashfunktionen bei Signaturen im TLS-Handshake - ZETA Guard - mindestens SHA-256 unterstützen
    Gegeben sei die TlsTestTool-Konfigurationsdaten für den Host "<host>" mit den folgenden nicht unterstützten Hashfunktionen wurden festgelegt:
      | MD5    |
      | SHA1   |
      | SHA224 |
    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Dann akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id "28"
    Beispiele:
      | host             |
      # Zeta Guard Ingress
      | ${zeta_base_url} |

  @dev
  @A_21275-01
  @TA_A_21275-01_01
  @no_proxy
  Szenariogrundriss: TLS-Verbindungen, zulässige Hashfunktionen bei Signaturen im TLS-Handshake - ZETA Guard - erlaubte Hashfunktionen
    Gegeben sei die TlsTestTool-Konfigurationsdaten für den Host "<host>" mit den folgenden unterstützten Hashfunktionen wurden festgelegt:
      | SHA256 |
      | SHA384 |
      | SHA512 |
    Dann verwendet der Server-Schlüsselaustausch eine der unterstützten Hashfunktionen
    Dann ist der TLS-Handshake erfolgreich
    Beispiele:
      | host             |
      # Zeta Guard Ingress
      | ${zeta_base_url} |

  @dev
  @GS-A_4384-03
  @TA_GS-A_4384-03_04
  @no_proxy
  Szenariogrundriss: Cipher-Suiten die nicht in TR-02102-2, Abschnitt 3.3.1 Tabelle 1 aufgeführt sind, werden nicht unterstützt.
    Gegeben sei die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host "<host>" für die nicht unterstützten Ciphersuiten
    Dann wird der Server-Hello Record nicht empfangen
    Beispiele:
      | host             |
      # Zeta Guard Ingress
      | ${zeta_base_url} |

  @dev
  @GS-A_4384-03
  @TA_GS-A_4384-03_03
  @no_proxy
  Szenariogrundriss: TLS-Verbindungen - ZETA Guard - PEP HTTP Proxy - elliptische Kurven.
    # Profile:
    # p256_only: secp256r1 (0017)
    # p384_only: secp384r1 (0018)
    # unsupported_mix: secp160r1, secp192r1, secp224r1, secp521r1, brainpoolP512r1, x25519, x448, ffdhe3072, ffdhe4096
    Gegeben sei die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host "<host>" für das Unterstützte-Gruppen-Profil <gruppenprofil>
    Dann ist der TLS-Handshake <handshake_erwartung>
    Und wird der Server-Key-Exchange-Datensatz <ske_erwartung>
    Beispiele:
      | host             | gruppenprofil   | handshake_erwartung | ske_erwartung |
      # Zeta Guard Ingress
      | ${zeta_base_url} | p256_only       | erfolgreich          | gesendet      |
      | ${zeta_base_url} | p384_only       | erfolgreich          | gesendet      |
      | ${zeta_base_url} | unsupported_mix | nicht erfolgreich    | nicht gesendet |

  @dev
  @GS-A_4384-03
  @TA_GS-A_4384-03_02
  @no_proxy
  Szenariogrundriss: Als Cipher-Suite MÜSSEN TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 und TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 unterstützt werden.
    # Profile:
    # ecdhe_rsa_aes_128_gcm_sha256 -> TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 (0xC0,0x2F)
    # ecdhe_rsa_aes_256_gcm_sha384 -> TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 (0xC0,0x30)
    Gegeben sei die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host "<host>" für das Ciphersuite-Profil <ciphersuite_profil>
    Dann ist der TLS-Handshake erfolgreich
    Beispiele:
      | host             | ciphersuite_profil            |
      # Zeta Guard Ingress
      | ${zeta_base_url} | ecdhe_rsa_aes_128_gcm_sha256 |
      | ${zeta_base_url} | ecdhe_rsa_aes_256_gcm_sha384 |

  @dev
  @GS-A_4384-03
  @TA_GS-A_4384-03_01
  @no_proxy
  Szenariogrundriss: Zur Authentifizierung MUSS eine X.509-Identität gemäß [gemSpec_Krypt#GS-A_4359-*] verwendet werden.
    Gegeben sei die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host "<host>"
    Dann ist der TLS-Handshake erfolgreich
    Dann erhält der Client ein X.509-Zertifikat gemäß [gemSpec_Krypt#GS-A_4359-*] vom Server
    Beispiele:
      | host             |
      # Zeta Guard Ingress
      | ${zeta_base_url} |

  @dev
  @no_proxy
  Szenariogrundriss: RSA-Signaturalgorithmen dürfen für TLS 1.2 nicht unterstützt werden
    Gegeben sei die TlsTestTool-Konfigurationsdaten für den Host "<host>" mit den folgenden TLS 1.2 Signatur-Hash-Algorithmen wurden festgelegt:
      | RSA_MD5    |
      | RSA_SHA1   |
      | RSA_SHA224 |
      | RSA_SHA256 |
      | RSA_SHA384 |
      | RSA_SHA512 |
    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Dann akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id "28"
    Beispiele:
      | host             |
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
    Dann akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id "28"
    Beispiele:
      | host             |
      # Zeta Guard Ingress
      | ${zeta_base_url} |
