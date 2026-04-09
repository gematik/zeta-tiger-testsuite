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

@UseCase_TLS_02
@no_proxy
@tls_client_fachdienst_hook
Funktionalität: TLS-Konformität ZETA Client

  @A_18464
  @GS-A_5542
  @TA_A_18464_02
  @TA_GS-A_5542_04
  Szenario: TLS-Verbindungen - ZETA Client - TLS 1.1 darf nicht unterstützt werden.
    Gegeben sei die TlsTestTool-Server-Konfigurationsdaten wurden nur für TLS 1.1 erstellt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und die ClientHello-TLS-Version ist 1.2
    # 46 in hexadezimal entspricht 70 in dezimal und ist der Alert Code für TLS Protocol Version Failure.
    Und akzeptiert der ZETA Client das ServerHello nicht und sendet eine Alert Nachricht mit Description Id "46"

  @GS-A_5526
  @TA_GS-A_5526_02
  Szenario: TLS-Verbindungen - ZETA Client - TLS-Renegotiation-Indication-Extension.
    Gegeben sei die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten mit HelloRequest für eine der unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und ist der TLS-Handshake erfolgreich
    Und der ClientHello-Record enthält TLS_EMPTY_RENEGOTIATION_INFO_SCSV oder eine leere renegotiation_info extension
    Und ist die TLS-Server initiierte renegotiation erfolgreich

  @A_21275-01
  @TA_A_21275-01_05
  @TA_A_21275-01_07
  @TA_A_21275-01_08
  Szenariogrundriss: TLS-Verbindungen - ZETA Client - zulässige Hashfunktionen bei Signaturen im TLS-Handshake - mindestens SHA-256 unterstützen
    Gegeben sei die TlsTestTool-Server-Konfigurationsdaten für den Hash-Algorithmus "<hash_algorithm>"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und ist der TLS-Handshake <handshake_erwartung>
    # supported_mix: sha256, sha384, sha512
    Beispiele:
      | hash_algorithm  | handshake_erwartung |
      | sha1            | nicht erfolgreich   |
      | sha224          | nicht erfolgreich   |
      | supported_mix   | erfolgreich         |

  @GS-A_4384-03
  @TA_GS-A_4384-03_11
  Szenario: TLS-Verbindungen - ZETA Client - Cipher-Suiten, die nicht in TR-02102-2, Abschnitt 3.3.1 Tabelle 1 aufgeführt sind, werden nicht unterstützt.
    Gegeben sei die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und der ClientHello-Record enthält nur Cipher-Suiten aus TR-02102-2, Abschnitt 3.3.1 Tabelle 1

  @GS-A_4384-03
  @TA_GS-A_4384-03_10
  Szenario: TLS-Verbindungen - ZETA Client - elliptische Kurven - Negativtest.
    Gegeben sei die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und der ClientHello bietet nur die unterstützten Kurven an

  @GS-A_4384-03
  @TA_GS-A_4384-03_10
  Szenariogrundriss: TLS-Verbindungen - ZETA Client - elliptische Kurven - Positivtest.
    # Profile:
    # p256: secp256r1 (0017)
    # p384: secp384r1 (0018)
    Gegeben sei die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützte Gruppe "<supported_group>"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und ist der TLS-Handshake <handshake_erwartung>
    Beispiele:
      | supported_group  | handshake_erwartung |
      | secp256r1        | erfolgreich         |
      | secp384r1        | erfolgreich         |

  @GS-A_4384-03
  @TA_GS-A_4384-03_09
  # Currently a ECC certificate is used, possible a RSA certificates will not be used in the future. We expect a change in specification here.
  Szenariogrundriss: TLS-Verbindungen - ZETA Client - als Cipher-Suite MÜSSEN TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 und TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 unterstützt werden.
    # Profile:
    # ecdhe_rsa_aes_128_gcm_sha256 -> TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 (0xC0,0x2F)
    # ecdhe_rsa_aes_256_gcm_sha384 -> TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 (0xC0,0x30)
    Gegeben sei die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für das Cipher-Suite-Profil <ciphersuite_profil>
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und ist der TLS-Handshake erfolgreich
    Beispiele:
      | ciphersuite_profil            |
      | ecdhe_rsa_aes_128_gcm_sha256  |
      | ecdhe_rsa_aes_256_gcm_sha384  |

  Szenario: TLS-Verbindungen - ZETA Client - RSA-Signaturalgorithmen dürfen für TLS 1.2 nicht unterstützt werden
    Gegeben sei die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und der ClientHello bietet keine nicht unterstützten Signaturalgorithmen an

  @A_25340-01
  Szenariogrundriss: TLS-Verbindungen - ZETA Client - ZETA Client Zertifikatsprüfung
    Gegeben sei die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützten Cipher-Suiten mit <zertifikat>
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann die Tls-Test-Tool-Server-Protokolle abrufen
    Und ist der TLS-Handshake <handshake_erwartung>

    Beispiele: Erfolgsfall
      | zertifikat                                                   | handshake_erwartung |
      | zeta_tls_test_tool_server_ecdsa_good_certificate             | erfolgreich         |

    @TA_A_25340-01_01
    Beispiele: Hostname-Prüfung: Vergleich CN oder SAN mit Hostname
      | zertifikat                                                   | handshake_erwartung |
      | zeta_tls_test_tool_server_ecdsa_different_cn_certificate     | erfolgreich         |
      | zeta_tls_test_tool_server_ecdsa_different_san_certificate    | erfolgreich         |
      | zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate | nicht erfolgreich   |

    @TA_A_25340-01_02
    Beispiele:  Gültigkeit Zertifikat "nicht vor"
      | zertifikat                                                   | handshake_erwartung |
      | zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate    | nicht erfolgreich   |

    @TA_A_25340-01_03
    Beispiele: Gültigkeit Zertifikat "nicht nach"
      | zertifikat                                                   | handshake_erwartung |
      | zeta_tls_test_tool_server_ecdsa_expired_certificate          | nicht erfolgreich   |

    @TA_A_25340-01_04
    Beispiele: Gültigkeit basiert auf Zertifikatsvertrauenspfad
      | zertifikat                                                   | handshake_erwartung |
      | zeta_tls_test_tool_server_ecdsa_different_ca_certificate     | nicht erfolgreich   |
