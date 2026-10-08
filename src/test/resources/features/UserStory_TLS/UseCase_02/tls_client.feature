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
Funktionalität: TLS-Konformität ZETA Client und ZETA Native Client

  @no_proxy
  @A_18464
  @GS-A_5542
  @TA_A_18464_02
  @TA_GS-A_5542_03
  @MASVS-CRYPTO
  @MASVS-NETWORK
  Szenariogrundriss: TLS-Verbindungen - <client_name> - TLS 1.1 darf nicht unterstützt werden
    Gegeben sei eine TlsTestTool-Server-Konfiguration nur für TLS 1.1
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und die ClientHello-TLS-Version ist 1.2
    Und ist der TLS-Handshake nicht erfolgreich
    # 46 in hexadezimal entspricht 70 in dezimal und ist der Alert Code für TLS Protocol Version Failure.
    Und lehnt der ZETA Client das ServerHello ab und sendet eine Alert-Nachricht mit Description-ID "46"

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_13
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - bricht ohne verfügbare OCSP-Antwort oder CRL ab
    Gegeben sei der Zertifikatsvalidierungs-Mock stellt weder OCSP noch CRL bereit
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "503"
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/crl/.*" hat
    Und ist der TLS-Handshake nicht erfolgreich

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_01
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - erkennt bereitgestelltes OCSP Stapling
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und der TLS-Test-Tool-Server verwendet eine OCSP-Stapling-Antwort für das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-OpenSSL-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und enthält das ClientHello die OCSP-status_request-Erweiterung
    Und liefert der Server eine OCSP-Stapling-Antwort
    Und ist der TLS-Handshake erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_02
  @A_25340-01
  @TA_A_25340-01_05
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  @MASVS-NETWORK
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - nutzt eine bereitgestellte widerrufene OCSP-Stapling-Antwort
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_revoked"
    Und der TLS-Test-Tool-Server verwendet eine OCSP-Stapling-Antwort für das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate
    # Die widerrufene OCSP-Antwort wurde bereits erzeugt und im TLS-Test-Tool hinterlegt.
    # Der Mock liefert für nachfolgende Live-Abfragen "good", damit allein die Stapling-Antwort die Ablehnung verursacht.
    Und der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-OpenSSL-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und enthält das ClientHello die OCSP-status_request-Erweiterung
    Und liefert der Server eine OCSP-Stapling-Antwort
    Und ist der TLS-Handshake nicht erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_03
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - lehnt eine OCSP-Antwort mit ungültiger Signatur ab
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good" mit OCSP-Antwortvariante "invalid_signature"
    Und der TLS-Test-Tool-Server verwendet eine OCSP-Stapling-Antwort für das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und hat die aktuelle OCSP-Antwort eine ungültige Signatur
    Und der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-OpenSSL-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und enthält das ClientHello die OCSP-status_request-Erweiterung
    Und liefert der Server eine OCSP-Stapling-Antwort
    Und ist der TLS-Handshake nicht erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_04
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - lehnt eine OCSP-Antwort eines nicht autorisierten Signierers ab
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good" mit OCSP-Antwortvariante "wrong_signer"
    Und der TLS-Test-Tool-Server verwendet eine OCSP-Stapling-Antwort für das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und hat die aktuelle OCSP-Antwort eine gültige Signatur von einem nicht autorisierten Signierer
    Und der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-OpenSSL-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und enthält das ClientHello die OCSP-status_request-Erweiterung
    Und liefert der Server eine OCSP-Stapling-Antwort
    Und ist der TLS-Handshake nicht erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_05
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - lehnt eine abgelaufene OCSP-Antwort ab
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good" mit ThisUpdate-Offset -120 und NextUpdate-Offset -60 Sekunden
    Und der TLS-Test-Tool-Server verwendet eine OCSP-Stapling-Antwort für das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und ist die aktuelle OCSP-Antwort abgelaufen
    Und der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-OpenSSL-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und enthält das ClientHello die OCSP-status_request-Erweiterung
    Und liefert der Server eine OCSP-Stapling-Antwort
    Und ist der TLS-Handshake nicht erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_06
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - lehnt eine OCSP-Antwort für ein anderes Zertifikat ab
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good" mit OCSP-Antwortvariante "mismatched_certificate"
    Und der TLS-Test-Tool-Server verwendet eine OCSP-Stapling-Antwort für das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und passt die aktuelle OCSP-Antwort nicht zum angefragten Zertifikat
    Und der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-OpenSSL-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und enthält das ClientHello die OCSP-status_request-Erweiterung
    Und liefert der Server eine OCSP-Stapling-Antwort
    Und ist der TLS-Handshake nicht erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_07
  @A_25340-01
  @TA_A_25340-01_05
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  @MASVS-NETWORK
  Szenariogrundriss: TLS-<tls_version>-Verbindungen - <client_name> - speichert die OCSP-Antwort bis NextUpdate im Cache
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good" mit ThisUpdate-Offset 0 und NextUpdate-Offset 45 Sekunden
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-<tls_version>-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und die aktuelle OCSP-Antwort enthält ein zukünftiges NextUpdate
    Und ist der TLS-Handshake erfolgreich
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-<tls_version>-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und ist der TLS-Handshake erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat
    Und warte bis das NextUpdate der zuletzt geprüften OCSP-Antwort seit 2 Sekunden abgelaufen ist
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-<tls_version>-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und die aktuelle OCSP-Antwort enthält ein zukünftiges NextUpdate
    Und ist der TLS-Handshake erfolgreich

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | tls_version | client_name | hello_path                |
      | 1.2         | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | tls_version | client_name        | hello_path                      |
      | 1.2         | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_10
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - speichert OCSP ohne NextUpdate bis ThisUpdate plus 24 Stunden
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good" ohne NextUpdate mit ThisUpdate-Offset -86370 Sekunden
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und enthält die aktuelle OCSP-Antwort kein NextUpdate und ein vergangenes ThisUpdate
    Und ist der TLS-Handshake erfolgreich
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und ist der TLS-Handshake erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat
    Und warte 35 Sekunden
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_25340-01
  @TA_A_25340-01_05
  @MASVS-NETWORK
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-<tls_version>-Verbindungen - <client_name> - lehnt ein widerrufenes End-Entity-Zertifikat ab
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_revoked"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-<tls_version>-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_different_cn_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_different_cn_certificate ab
    Und die aktuelle OCSP-Antwort meldet das angefragte Zertifikat als widerrufen
    Und ist der TLS-Handshake nicht erfolgreich

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | tls_version | client_name | hello_path                |
      | 1.2         | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | tls_version | client_name        | hello_path                      |
      | 1.2         | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @GS-A_5526
  @TA_GS-A_5526_02
  @MASVS-NETWORK
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - TLS-Renegotiation-Indication-Extension.
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich
    Und das ClientHello enthält TLS_EMPTY_RENEGOTIATION_INFO_SCSV oder eine leere renegotiation_info-Erweiterung

    @critical
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @GS-A_5526
  @TA_GS-A_5526_02
  @MASVS-NETWORK
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - TLS-Renegotiation wird RFC-5746-konform verarbeitet.
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration mit HelloRequest für eine der unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich
    Und wurde die TLS-Handshake-Renegotiation gestartet
    Und war die TLS-Handshake-Renegotiation RFC-5746-konform erfolgreich oder wurde mit no_renegotiation oder handshake_failure abgelehnt

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_21275-01
  @A_25766
  @TA_A_21275-01_05
  @TA_A_21275-01_07
  @TA_A_21275-01_08
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - zulässige Hashfunktionen bei Signaturen im TLS-Handshake - mindestens SHA-256 unterstützen
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für den Hash-Algorithmus "<hash_algorithm>"
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake <handshake_erwartung>
    # supported_mix: sha256, sha384, sha512
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | hash_algorithm | handshake_erwartung | client_name | hello_path                |
      | sha1           | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |
      | sha224         | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |
      | supported_mix  | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | hash_algorithm | handshake_erwartung | client_name        | hello_path                      |
      | sha1           | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | sha224         | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | supported_mix  | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_21275-01
  @A_25766
  @TA_A_21275-01_05
  @TA_A_21275-01_07
  @TA_A_21275-01_08
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - Signature-Schemes.
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello bietet nur unterstützte Signature-Schemes an

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @A_28868
  @TA_A_28868_08
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - es werden keine TLS-1.2-Cipher-Suiten außerhalb der nach TR-02102-2 zulässigen Menge angeboten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello enthält nur Cipher-Suiten aus TR-02102-2, Abschnitt 3.3.1 Tabelle 1

    @critical
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @A_28868
  @TA_A_28868_08
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - es werden keine optionalen TLS-1.2-Cipher-Suiten aus TR-02102-2 zulässigen Menge angeboten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello enthält keine optionalen Cipher-Suiten aus TR-02102-2, Abschnitt 3.3.1 Tabelle 1

    @critical
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @A_28868
  @TA_A_28868_07
  @TA_A_28868_10
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - elliptische Kurven - Negativtest
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello bietet nur unterstützte Kurven an

    @critical
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @A_28868
  @TA_A_28868_07
  @TA_A_28868_10
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - elliptische Kurven
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello bietet nur unterstützte Kurven an
    Und verwendet die Client-Key-Share nur unterstützte Kurven

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @A_28868
  @TA_A_28868_07
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - elliptische Kurven - Positivtest
    # Profile:
    # p256: secp256r1 (0017)
    # p384: secp384r1 (0018)
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützte Gruppe "<supported_group>"
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake <handshake_erwartung>

    @critical
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | supported_group | handshake_erwartung | client_name | hello_path                |
      | p256            | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |
      | p384            | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | supported_group | handshake_erwartung | client_name        | hello_path                      |
      | p256            | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | p384            | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @A_28868
  @TA_A_28868_06
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - als Cipher-Suite MÜSSEN TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 und TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 unterstützt werden.
    # Profile:
    # ecdhe_ecdsa_aes_128_gcm_sha256 -> TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 (0xC0,0x2B)
    # ecdhe_ecdsa_aes_256_gcm_sha384 -> TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 (0xC0,0x2C)
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für das Cipher-Suite-Profil <ciphersuite_profil>
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und ist der TLS-Handshake erfolgreich

    @critical
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | ciphersuite_profil             | client_name | hello_path                |
      | ecdhe_ecdsa_aes_128_gcm_sha256 | ZETA Client | ${paths.client.helloZeta} |
      | ecdhe_ecdsa_aes_256_gcm_sha384 | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | ciphersuite_profil             | client_name        | hello_path                      |
      | ecdhe_ecdsa_aes_128_gcm_sha256 | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | ecdhe_ecdsa_aes_256_gcm_sha384 | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @A_28868
  @TA_A_28868_09
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - bei TLS 1.3 MÜSSEN TLS_AES_128_GCM_SHA256 und TLS_AES_256_GCM_SHA384 unterstützt werden
    # Profile:
    # aes_128_gcm_sha256 -> TLS_AES_128_GCM_SHA256 (0x13,0x01)
    # aes_256_gcm_sha384 -> TLS_AES_256_GCM_SHA384 (0x13,0x02)
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für das Cipher-Suite-Profil <ciphersuite_profil>
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello signalisiert TLS-1.3-Unterstützung, andernfalls wird das Szenario übersprungen
    Und ist der TLS-Handshake erfolgreich

    @critical
    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | ciphersuite_profil | client_name | hello_path                |
      | aes_128_gcm_sha256 | ZETA Client | ${paths.client.helloZeta} |
      | aes_256_gcm_sha384 | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | ciphersuite_profil | client_name        | hello_path                      |
      | aes_128_gcm_sha256 | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | aes_256_gcm_sha384 | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - RSA-Signaturalgorithmen dürfen für TLS 1.2 nicht unterstützt werden
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello bietet keine nicht unterstützten Signaturalgorithmen an

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - RSA-Signaturalgorithmen dürfen für TLS 1.3 nicht unterstützt werden
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und das ClientHello bietet keine nicht unterstützten RSA-TLS-1.3-Signature-Schemes an

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25766
  @TA_A_25766_03
  @MASVS-CRYPTO
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - nicht unterstützten Signaturalgorithmen dürfen für TLS 1.3 nicht unterstützt werden
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und das ClientHello bietet keine nicht unterstützten TLS-1.3-Signature-Schemes an

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25340-01
  @MASVS-NETWORK
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - Zertifikatsprüfung
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit <zertifikat>
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und ist der TLS-Handshake <handshake_erwartung>

    @tls_client_fachdienst_hook
    Beispiele: Erfolgsfall - ZETA Client
      | zertifikat                                       | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_good_certificate | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: Erfolgsfall - ZETA Native Client
      | zertifikat                                       | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_good_certificate | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_01
    @tls_client_fachdienst_hook
    Beispiele: Hostname-Prüfung: Vergleich CN oder SAN mit Hostname - ZETA Client
      | zertifikat                                                   | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_different_cn_certificate     | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_san_certificate    | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_01
    @tls_native_client_fachdienst_hook
    Beispiele: Hostname-Prüfung: Vergleich CN oder SAN mit Hostname - ZETA Native Client
      | zertifikat                                                   | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_different_cn_certificate     | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_san_certificate    | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_02
    @tls_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht vor" - ZETA Client
      | zertifikat                                                | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_02
    @tls_native_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht vor" - ZETA Native Client
      | zertifikat                                                | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_03
    @tls_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht nach" - ZETA Client
      | zertifikat                                          | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_expired_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_03
    @tls_native_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht nach" - ZETA Native Client
      | zertifikat                                          | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_expired_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_04
    @tls_client_fachdienst_hook
    Beispiele: Gültigkeit basiert auf Zertifikatsvertrauenspfad - ZETA Client
      | zertifikat                                               | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_different_ca_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_04
    @tls_native_client_fachdienst_hook
    Beispiele: Gültigkeit basiert auf Zertifikatsvertrauenspfad - ZETA Native Client
      | zertifikat                                               | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_different_ca_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @A_25340-01
  @MASVS-NETWORK
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - Zertifikatsprüfung
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit <zertifikat>
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und ist der TLS-Handshake <handshake_erwartung>

    @tls_client_fachdienst_hook
    Beispiele: Erfolgsfall - ZETA Client
      | zertifikat                                       | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_good_certificate | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: Erfolgsfall - ZETA Native Client
      | zertifikat                                       | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_good_certificate | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_01
    @tls_client_fachdienst_hook
    Beispiele: Hostname-Prüfung: Vergleich CN oder SAN mit Hostname - ZETA Client
      | zertifikat                                                   | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_different_cn_certificate     | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_san_certificate    | erfolgreich         | ZETA Client | ${paths.client.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_01
    @tls_native_client_fachdienst_hook
    Beispiele: Hostname-Prüfung: Vergleich CN oder SAN mit Hostname - ZETA Native Client
      | zertifikat                                                   | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_different_cn_certificate     | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_san_certificate    | erfolgreich         | ZETA Native Client | ${paths.nativeClient.helloZeta} |
      | zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_02
    @tls_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht vor" - ZETA Client
      | zertifikat                                                | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_02
    @tls_native_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht vor" - ZETA Native Client
      | zertifikat                                                | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_03
    @tls_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht nach" - ZETA Client
      | zertifikat                                          | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_expired_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_03
    @tls_native_client_fachdienst_hook
    Beispiele: Gültigkeit Zertifikat "nicht nach" - ZETA Native Client
      | zertifikat                                          | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_expired_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

    @TA_A_25340-01_04
    @tls_client_fachdienst_hook
    Beispiele: Gültigkeit basiert auf Zertifikatsvertrauenspfad - ZETA Client
      | zertifikat                                               | handshake_erwartung | client_name | hello_path                |
      | zeta_tls_test_tool_server_ecdsa_different_ca_certificate | nicht erfolgreich   | ZETA Client | ${paths.client.helloZeta} |

    @TA_A_25340-01_04
    @tls_native_client_fachdienst_hook
    Beispiele: Gültigkeit basiert auf Zertifikatsvertrauenspfad - ZETA Native Client
      | zertifikat                                               | handshake_erwartung | client_name        | hello_path                      |
      | zeta_tls_test_tool_server_ecdsa_different_ca_certificate | nicht erfolgreich   | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @no_proxy
  @GS-A_5542
  @TA_GS-A_5542_03
  @MASVS-NETWORK
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - Alert
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und der ZETA Client hat eine fatale Alert-Nachricht mit einer TLS-Fehlermeldung gesendet

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_11
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  @tls_subca_root
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - lädt und cached die CRL ohne OCSP Stapling
    Gegeben sei TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}/route" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "from": "/crl",
        "to": "http://zeta-cert-validation-mock/crl"
      }
      """
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und der Zertifikatsvalidierungs-Mock stellt die CRL "tls-root" aus der Datei "crl/tls-root.crl.pem" bereit
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_crl_only_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und TGR finde die erste Anfrage mit Pfad "^/crl/tls-root\.crl$" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und enthält die aktuelle Antwort eine gültige CRL mit zukünftigem NextUpdate
    Und ist der TLS-Handshake erfolgreich
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_crl_only_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und ist der TLS-Handshake erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/crl/.*" hat
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/.*" hat

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_25340-01
  @TA_A_25340-01_06
  @MASVS-NETWORK
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  @tls_subca_root
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - lehnt eine widerrufene Sub-CA ab
    Gegeben sei der Zertifikatsvalidierungs-Mock meldet nur das Zertifikat an Position 1 der Zertifikatskette zeta_tls_test_tool_server_ecdsa_subca_chain_certificate als widerrufen
    Und der Zertifikatsvalidierungs-Mock stellt die CRL "tls-subca" aus der Datei "crl/tls-subca.crl.pem" bereit
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_subca_chain_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und wurde eine OCSP-Anfrage für das Zertifikat an Position 1 der Zertifikatskette zeta_tls_test_tool_server_ecdsa_subca_chain_certificate mit widerrufen beantwortet
    Und ist der TLS-Handshake nicht erfolgreich

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_25340-01
  @TA_A_25340-01_06
  @MASVS-NETWORK
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  @tls_subca_root
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - cached die OCSP-Antwort der Sub-CA bis NextUpdate
    Gegeben sei TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}/route" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "from": "/crl",
        "to": "http://zeta-cert-validation-mock/crl"
      }
      """
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good" mit ThisUpdate-Offset 0 und NextUpdate-Offset 45 Sekunden
    Und der Zertifikatsvalidierungs-Mock stellt die CRL "tls-subca" aus der Datei "crl/tls-subca.crl.pem" bereit
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_subca_chain_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat an Position 1 der Zertifikatskette zeta_tls_test_tool_server_ecdsa_subca_chain_certificate ab
    Und die aktuelle OCSP-Antwort enthält ein zukünftiges NextUpdate
    Und ist der TLS-Handshake erfolgreich
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_subca_chain_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und ist der TLS-Handshake erfolgreich
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/ocsp/tls.*" hat
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/crl/.*" hat
    Und warte bis das NextUpdate der zuletzt geprüften OCSP-Antwort seit 2 Sekunden abgelaufen ist
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_subca_chain_certificate
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und TGR finde die erste Anfrage mit Pfad "^/ocsp/tls.*" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat an Position 1 der Zertifikatskette zeta_tls_test_tool_server_ecdsa_subca_chain_certificate ab
    Und die aktuelle OCSP-Antwort enthält ein zukünftiges NextUpdate
    Und ist der TLS-Handshake erfolgreich

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_12
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.2-Verbindungen - <client_name> - fragt ohne OCSP Stapling den OCSP Responder des Zertifikats direkt ab
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Und TGR setze lokale Variable "ocspResponderPath" auf "/ocsp/tls"
    Und TGR setze lokale Variable "ocspResponderPathPattern" auf "^${ocspResponderPath}.*"
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und TGR finde die erste Anfrage mit Pfad "${ocspResponderPathPattern}" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und ist der TLS-Handshake erfolgreich

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |

  @A_27379-01
  @TA_A_27379-01_12
  @MASVS-CRYPTO
  @ocsp_mock_mode_modified
  @require_kubectl
  @deployment_modification
  @reset_tls_revocation_cache
  Szenariogrundriss: TLS-1.3-Verbindungen - <client_name> - fragt ohne OCSP Stapling den OCSP Responder des Zertifikats direkt ab
    Gegeben sei der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit zeta_tls_test_tool_server_ecdsa_good_certificate
    Und TGR setze lokale Variable "ocspResponderPath" auf "/ocsp/tls"
    Und TGR setze lokale Variable "ocspResponderPathPattern" auf "^${ocspResponderPath}.*"
    Wenn TGR sende eine leere GET Anfrage an "<hello_path>"
    Dann werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und wurde die TLS-Certificate-Nachricht übertragen
    Und TGR finde die erste Anfrage mit Pfad "${ocspResponderPathPattern}" und Knoten "${headers.host}" der mit "tiger-proxy(:80)?" übereinstimmt
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat zeta_tls_test_tool_server_ecdsa_good_certificate ab
    Und ist der TLS-Handshake erfolgreich

    @tls_client_fachdienst_hook
    Beispiele: ZETA Client
      | client_name | hello_path                |
      | ZETA Client | ${paths.client.helloZeta} |

    @tls_native_client_fachdienst_hook
    Beispiele: ZETA Native Client
      | client_name        | hello_path                      |
      | ZETA Native Client | ${paths.nativeClient.helloZeta} |
