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
@no_proxy
Funktionalität: TLS-Konformität ZETA Guard

  Szenariogrundriss: <Komponente> - eingehende TLS-1.2-Verbindungen terminieren
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich

    @A_26666-01
    @TA_A_26666-01_01
    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente     | Host                  |
      | PEP HTTP Proxy | ${pep_http_proxy_url} |

    @A_26669-01
    @TA_A_26669-01_01
    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente               | Host            |
      | PDP Authorization Server | ${keycloak_url} |

  @A_18464
  @GS-A_5542
  @TA_A_18464_01
  @TA_GS-A_5542_01
  @MASVS-CRYPTO
  @MASVS-NETWORK
  Szenariogrundriss: <Komponente> - TLS 1.1 darf nicht unterstützt werden.
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" nur für TLS 1.1
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    # 46 in hexadezimal entspricht 70 in dezimal und ist der Alert Code für TLS Protocol Version Failure.
    Und lehnt der ZETA Guard Endpunkt das ClientHello ab und sendet eine Alert-Nachricht mit Description-ID "46"
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @GS-A_5526
  @TA_GS-A_5526_01
  @MASVS-NETWORK
  Szenariogrundriss: <Komponente> - TLS-1.2-Renegotiation-Indication-Extension
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich
    Und das ServerHello enthält die Erweiterung renegotiation_info
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @GS-A_5526
  @TA_GS-A_5526_01
  @MASVS-NETWORK
  Szenariogrundriss: <Komponente> - TLS-1.2-Renegotiation wird RFC-5746-konform verarbeitet
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" für TLS-Renegotiation
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich
    Und wurde die TLS-Handshake-Renegotiation gestartet
    Und war die TLS-Handshake-Renegotiation RFC-5746-konform erfolgreich oder wurde mit no_renegotiation oder handshake_failure abgelehnt
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @GS-A_5526
  @TA_GS-A_5526_01
  @MASVS-NETWORK
  Szenariogrundriss: <Komponente> - fehlerhafte TLS-1.2-renegotiation_info-Erweiterung im initialen ClientHello wird abgelehnt
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" mit einer fehlerhaften renegotiation_info-Erweiterung
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake nicht erfolgreich
    Und wurde kein ServerHello-Record empfangen
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_21275-01
  @TA_A_21275-01_03
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - TLS-1.2-Verbindungen, zulässige Hashfunktionen bei Signaturen im TLS-Handshake - mindestens SHA-256 unterstützen
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden nicht unterstützten Hashfunktionen:
      | MD5    |
      | SHA1   |
      | SHA224 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Und lehnt der ZETA Guard Endpunkt das ClientHello ab und sendet eine Alert-Nachricht mit Description-ID "28"
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_21275-01
  @TA_A_21275-01_01
  @TA_A_21275-01_04
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - TLS-1.2-Verbindungen, zulässige Hashfunktionen bei Signaturen im TLS-Handshake - erlaubte Hashfunktionen
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden unterstützten Hashfunktionen:
      | SHA256 |
      | SHA384 |
      | SHA512 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und verwendet der Server-Schlüsselaustausch eine der unterstützten Hashfunktionen
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_21275-01
  @TA_A_21275-01_01
  @TA_A_21275-01_04
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - TLS-1.3-Verbindungen, zulässige Hashfunktionen bei Signaturen im TLS-Handshake - erlaubte Hashfunktionen
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden TLS-1.3-Signature-Schemes:
      | ecdsa_secp256r1_sha256            |
      | ecdsa_secp384r1_sha384            |
      | ecdsa_brainpoolP256r1tls13_sha256 |
      | ecdsa_brainpoolP384r1tls13_sha384 |
      | ecdsa_brainpoolP512r1tls13_sha512 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und verwendet der Certificate-Verify eine der unterstützten Hashfunktionen
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_28868
  @TA_A_28868_05
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - Die Zeta-Guard-Instanz verwendet ausschließlich Schlüssellängen und Domainparameter, insbesondere ECC-Kurvenparameter, die den Empfehlungen der TR-02102-2 entsprechen.
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden unterstützten Hashfunktionen:
      | SHA256 |
      | SHA384 |
      | SHA512 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und verwendet der Server ein Zertifikat mit Schlüssellängen und Domainparameter nach [TR-02102-2]
    Und verwendet der Server-Schlüsselaustausch eine der unterstützten Kurven
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |


  @A_28868
  @TA_A_28868_05
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - Die Zeta-Guard-Instanz verwendet ausschließlich Schlüssellängen und Domainparameter, insbesondere ECC-Kurvenparameter, die den Empfehlungen der TR-02102-2 entsprechen für TLS 1.3.
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden TLS-1.3-Signature-Schemes:
      | ecdsa_secp256r1_sha256            |
      | ecdsa_secp384r1_sha384            |
      | ecdsa_brainpoolP256r1tls13_sha256 |
      | ecdsa_brainpoolP384r1tls13_sha384 |
      | ecdsa_brainpoolP512r1tls13_sha512 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und verwendet der Server ein Zertifikat mit Schlüssellängen und Domainparameter nach [TR-02102-2]
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_28868
  @TA_A_28868_03
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - Cipher-Suiten außerhalb der nach TR-02102-2 für TLS 1.2 zulässigen Menge werden nicht ausgehandelt.
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" für die nicht unterstützten Cipher-Suiten
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde kein ServerHello-Record empfangen
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_28868
  @TA_A_28868_03
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - Optionale Cipher-Suiten aus TR-02102-2 für TLS 1.2 zulässigen Menge werden nicht ausgehandelt.
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" für die optional unterstützten Cipher-Suiten
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde kein ServerHello-Record empfangen
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_28868
  @TA_A_28868_02
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - TLS-1.2-Verbindung - elliptische Kurven
    # Profile:
    # p256: secp256r1 (0017)
    # p384: secp384r1 (0018)
    # unsupported_mix: alle nach Tls12Policy verbotenen Gruppen.
    # Beispiele: brainpoolP256r1tls13, secp192r1, secp224r1, secp521r1, secp256k1, x25519, x448, ffdhe*
    # Hinweis zu secp384r1:
    # ClientHello mit ausschließlich p384 kann gegen ein ECDSA-P256-Server-Zertifikat
    # nicht erfolgreich sein, da TLS 1.2 die supported_groups an die
    # Zertifikatskurve bindet (RFC 8422 §5.1).
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" für das unterstützte-Gruppen-Profil "<Supported_Group>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wird der Server-Key-Exchange-Datensatz <SKE_Erwartung>
    Und ist der TLS-Handshake <Handshake_Erwartung>
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             | Supported_Group | Handshake_Erwartung | SKE_Erwartung  |
      | Ingress    | ${zeta_base_url} | p256            | erfolgreich         | gesendet       |
      | Ingress    | ${zeta_base_url} | p384            | erfolgreich         | gesendet       |
      | Ingress    | ${zeta_base_url} | unsupported_mix | nicht erfolgreich   | nicht gesendet |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            | Supported_Group | Handshake_Erwartung | SKE_Erwartung  |
      | PDP        | ${keycloak_url} | p256            | erfolgreich         | gesendet       |
      | PDP        | ${keycloak_url} | p384            | erfolgreich         | gesendet       |
      | PDP        | ${keycloak_url} | unsupported_mix | nicht erfolgreich   | nicht gesendet |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  | Supported_Group | Handshake_Erwartung | SKE_Erwartung  |
      | PEP        | ${pep_http_proxy_url} | p256            | erfolgreich         | gesendet       |
      | PEP        | ${pep_http_proxy_url} | p384            | erfolgreich         | gesendet       |
      | PEP        | ${pep_http_proxy_url} | unsupported_mix | nicht erfolgreich   | nicht gesendet |

  @A_28868
  @TA_A_28868_02
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - TLS-1.2-Verbindung - ECDHE mit secp384r1
    # Bietet beide Mandatory-Gruppen an (secp384r1 zuerst, dann secp256r1), damit
    # das ECDSA-P256-Serverzertifikat verwendbar bleibt (seine Kurve ist im
    # ClientHello enthalten), während der ephemere ECDHE-Schlüssel die bevorzugte
    # Kurve secp384r1 nutzen muss. So wird secp384r1-Unterstützung nachgewiesen,
    # ohne ein p384-only-ClientHello (das mit einem ECDSA-P256-Zertifikat nicht
    # erfolgreich sein kann).
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" für das unterstützte-Gruppen-Profil "mandatory_mix"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wird der Server-Key-Exchange-Datensatz gesendet
    Und verwendet der Server-Schlüsselaustausch das unterstützte-Gruppen-Profil "p384"
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_28868
  @TA_A_28868_02
  @TA_A_28868_05
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - TLS-1.3-Verbindung - elliptische Kurven
    # Profile:
    # p256: secp256r1 (0017)
    # p384: secp384r1 (0018)
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" für das unterstützte-Gruppen-Profil "<Supported_Group>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und verwendet die Server-Key-Share das unterstützte-Gruppen-Profil "<Supported_Group>"
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             | Supported_Group |
      | Ingress    | ${zeta_base_url} | p256            |
      | Ingress    | ${zeta_base_url} | p384            |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            | Supported_Group |
      | PDP        | ${keycloak_url} | p256            |
      | PDP        | ${keycloak_url} | p384            |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  | Supported_Group |
      | PEP        | ${pep_http_proxy_url} | p256            |
      | PEP        | ${pep_http_proxy_url} | p384            |

  @A_28868
  @GS-A_5542
  @TA_A_28868_02
  @TA_GS-A_5542_01
  @MASVS-CRYPTO
  @MASVS-NETWORK
  Szenariogrundriss: <Komponente> - TLS-1.3-Verbindung - nicht unterstützte elliptische Kurven
    # Profile:
    # unsupported_mix: nur nach Tls13Policy verbotene Gruppen, die OpenSSL im ClientHello
    # anbieten kann: brainpoolP256r1tls13, brainpoolP384r1tls13, brainpoolP512r1tls13,
    # secp521r1, x25519, x448, ffdhe2048..ffdhe8192.
    # (secp192r1/secp224r1/secp256k1 sind zwar verboten, aber in TLS 1.3 nicht anbietbar.)
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" für das unterstützte-Gruppen-Profil "unsupported_mix"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Und lehnt der ZETA Guard Endpunkt das ClientHello ab und sendet eine Alert-Nachricht mit Description-ID "28"
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @A_28868
  @TA_A_28868_01
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - Bei TLS 1.2 MÜSSEN TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 und TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 unterstützt werden.
    # Profile:
    # ecdhe_ecdsa_aes_128_gcm_sha256 -> TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 (0xC0,0x2B)
    # ecdhe_ecdsa_aes_256_gcm_sha384 -> TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 (0xC0,0x2C)
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" für das Cipher-Suite-Profil <Cipher_Suite_Profil>
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             | Cipher_Suite_Profil            |
      | Ingress    | ${zeta_base_url} | ecdhe_ecdsa_aes_128_gcm_sha256 |
      | Ingress    | ${zeta_base_url} | ecdhe_ecdsa_aes_256_gcm_sha384 |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            | Cipher_Suite_Profil            |
      | PDP        | ${keycloak_url} | ecdhe_ecdsa_aes_128_gcm_sha256 |
      | PDP        | ${keycloak_url} | ecdhe_ecdsa_aes_256_gcm_sha384 |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  | Cipher_Suite_Profil            |
      | PEP        | ${pep_http_proxy_url} | ecdhe_ecdsa_aes_128_gcm_sha256 |
      | PEP        | ${pep_http_proxy_url} | ecdhe_ecdsa_aes_256_gcm_sha384 |

  @A_28868
  @TA_A_28868_04
  @MASVS-CRYPTO
  Szenariogrundriss: <Komponente> - Bei TLS 1.3 MÜSSEN TLS_AES_128_GCM_SHA256 und TLS_AES_256_GCM_SHA384 unterstützt werden.
    # Profile:
    # aes_128_gcm_sha256 -> TLS_AES_128_GCM_SHA256 (0x13,0x01)
    # aes_256_gcm_sha384 -> TLS_AES_256_GCM_SHA384 (0x13,0x02)
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" für das Cipher-Suite-Profil <Cipher_Suite_Profil>
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wird TLS 1.3 unterstützt, andernfalls wird das Szenario übersprungen
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             | Cipher_Suite_Profil |
      | Ingress    | ${zeta_base_url} | aes_128_gcm_sha256 |
      | Ingress    | ${zeta_base_url} | aes_256_gcm_sha384 |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            | Cipher_Suite_Profil |
      | PDP        | ${keycloak_url} | aes_128_gcm_sha256 |
      | PDP        | ${keycloak_url} | aes_256_gcm_sha384 |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  | Cipher_Suite_Profil |
      | PEP        | ${pep_http_proxy_url} | aes_128_gcm_sha256 |
      | PEP        | ${pep_http_proxy_url} | aes_256_gcm_sha384 |

  @A_26964-01
  @TA_A_26964-01_01
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard stellt OCSP Stapling bereit.
    Gegeben sei die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host "<host>" mit OCSP Status Request
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und ist der TLS-Handshake erfolgreich
    Und liefert der Server eine OCSP-Stapling-Antwort
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | host             |
      | ${zeta_base_url} |

  Szenariogrundriss: <Komponente> - RSA-Signaturalgorithmen dürfen für TLS 1.2 nicht unterstützt werden
    Gegeben sei eine TLS-1.2-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden TLS-1.2-Signatur-Hash-Algorithmen:
      | RSA_MD5    |
      | RSA_SHA1   |
      | RSA_SHA224 |
      | RSA_SHA256 |
      | RSA_SHA384 |
      | RSA_SHA512 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Und lehnt der ZETA Guard Endpunkt das ClientHello ab und sendet eine Alert-Nachricht mit Description-ID "28"
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  @GS-A_5542
  @TA_GS-A_5542_01
  @MASVS-NETWORK
  Szenariogrundriss: <Komponente> - RSA-Signaturalgorithmen (pkcs1) dürfen für TLS 1.3 nicht unterstützt werden
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden nicht empfohlenen TLS-1.3-Signature-Schemes:
      | rsa_pkcs1_sha256 |
      | rsa_pkcs1_sha384 |
      | rsa_pkcs1_sha512 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Und lehnt der ZETA Guard Endpunkt das ClientHello ab und sendet eine Alert-Nachricht mit Description-ID "28"
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  Szenariogrundriss: <Komponente> - Erlaubt-Signaturalgorithmen dürfen für TLS 1.3 unterstützt werden
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden TLS-1.3-Signature-Schemes:
      | ecdsa_secp256r1_sha256            |
      | ecdsa_secp384r1_sha384            |
      | ecdsa_brainpoolP256r1tls13_sha256 |
      | ecdsa_brainpoolP384r1tls13_sha384 |
      | ecdsa_brainpoolP512r1tls13_sha512 |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |

  Szenariogrundriss: <Komponente> - Unerlaubt-Signaturalgorithmen (kein RSA) dürfen für TLS 1.3 nicht unterstützt werden
    Gegeben sei eine TLS-1.3-TlsTestTool-Konfiguration für den Host "<Host>" mit den folgenden TLS-1.3-Signature-Schemes:
      | rsa_pss_rsae_sha256           |
      | rsa_pss_rsae_sha384           |
      | rsa_pss_rsae_sha512           |
      | rsa_pss_pss_sha256            |
      | rsa_pss_pss_sha384            |
      | rsa_pss_pss_sha512            |
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    # 28 in hexadezimal entspricht 40 in dezimal und ist der Alert Code für TLS Handshake Failure.
    Und lehnt der ZETA Guard Endpunkt das ClientHello ab und sendet eine Alert-Nachricht mit Description-ID "28"
    @ingress_tls
    Beispiele: ZETA Guard Ingress
      | Komponente | Host             |
      | Ingress    | ${zeta_base_url} |

    @pdp_tls
    Beispiele: PDP Authorization Server
      | Komponente | Host            |
      | PDP        | ${keycloak_url} |

    @pep_tls
    Beispiele: PEP HTTP Proxy
      | Komponente | Host                  |
      | PEP        | ${pep_http_proxy_url} |
