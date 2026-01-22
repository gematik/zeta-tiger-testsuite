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

@UseCase_01_12
Funktionalität: Client_ressource_anfrage_fachdienst_SC_200_integrationstest

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt

  @A_26639
  @A_26195
  @TA_A_26639_01
  @TA_A_26195_01
  Szenario: Ingress und PEP HTTP Proxy unterstützt WebSocket Verbindung
    # Ein WebSocket-Roundtrip sollte hier reichen, allerdings könnte man auch noch die Verbindung
      # Ingress <-> PEP HTTP Proxy über den Standalone Tiger Proxy testen.
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Dann wird die WebSocket Verbindung geschlossen

  @A_27802
  @TA_A_27802_01
  @TA_A_27802_02
  @TA_A_27802_03
  @TA_A_27802_04
  @TA_A_27802_05
  @TA_A_27802_10
  @TA_A_27802_11
  Szenariogrundriss:  ZETA Guard Integrationstest, JWT Prüfung
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Holen der Keys: Guard Key (signingKey) für Access Token, Client Key (dpopKey) für DPoP
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"
    Und TGR setze lokale Variable "wrongSigningKey" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Client-DPoP-Key vom Storage holen (für ath-Aktualisierung)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"

    # JWT-Manipulation setzen: Access Token manipulieren UND DPoP ath automatisch aktualisieren
    Dann Setze im TigerProxy für Access Token das Feld "<JwtField>" auf Wert "<NeuerWert>" mit Access Token Key "<AccessTokenKey>" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen

    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "$.header.authorization.BearerToken.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    Beispiele: Manipulationen (JWT)
      | JwtField   | NeuerWert        | ResponseCode | AccessTokenKey       |
      | header.alg | ES256            | 200          | ${signingKey}        |
      | header.alg | ES256            | 401          | ${wrongSigningKey}   |
      | header.alg | RS1              | 401          | ${signingKey}        |
      | header.typ | dpop             | 401          | ${signingKey}        |
      | body.exp   | 1758719276       | 401          | ${signingKey}        |
      | body.aud   | unknown          | 401          | ${signingKey}        |
      | body.iss   | someone          | 401          | ${signingKey}        |
      | body.nonce | 1234567890123456 | 401          | ${signingKey}        |

  @dev
  @A_25660
  @A_26560
  @A_25762
  @A_25767
  @A_26492-01
  @A_27802
  @A_27853
  @A_25766
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_26560_01
  @TA_A_25762_04
  @TA_A_25767_02
  @TA_A_26492-01_02
  @TA_A_27802_10
  @TA_A_27802_11
  @TA_A_27853_01
  @TA_A_25766_02
  Szenario: DPoP Resource Request - Client sendet DPoP Proof mit Access Token für geschützte Ressource
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # save the acces token from /token response for later ath check
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "accessToken"

    # TA_A_25660_04 - Refresh Token muss vorhanden sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # TA_A_26560_01 - Antwortdaten stammen vom Resource Server
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"

    # Resource Request Validierung
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.authorization"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.authorization" überein mit "^DPoP .*"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header" nicht überein mit ".*ZETA-Client-Data.*"

    # DPoP JWT Validierung
    Und TGR speichere Wert des Knotens "$.header.dpop" der aktuellen Anfrage in der Variable "resourceDpopJwt"
    Und decodiere und validiere "${resourceDpopJwt}" gegen Schema "schemas/v_1_0/dpop-token.yaml"
    Und verifiziere ES256 Signatur von DPoP JWT "${resourceDpopJwt}"

    # DPoP Header Validierung
    # @TA_A_27802_10 - typ muss "dpop+jwt" sein
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.header.typ" überein mit "dpop+jwt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.header.alg" überein mit "ES256"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.header.jwk"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.header.jwk.kty"
    Und TGR speichere Wert des Knotens "$.header.dpop.header" der aktuellen Anfrage in der Variable "resourceDpopHeader"
    Und prüfe dass jwk in "${resourceDpopHeader}" keine privaten Key-Teile enthält

    # DPoP Payload Validierung
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.jti"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.htm"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.htu"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.iat"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.htm" überein mit "GET"
    Und TGR speichere Wert des Knotens "$.header.X-Forwarded-Proto" der aktuellen Anfrage in der Variable "requestScheme"
    Und TGR speichere Wert des Knotens "$.header.X-Forwarded-Host" der aktuellen Anfrage in der Variable "requestHost"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "requestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.htu" überein mit "${requestScheme}://${requestHost}${requestPath}"
    Und TGR speichere Wert des Knotens "$.header.dpop.body.iat" der aktuellen Anfrage in der Variable "resourceIat"
    Und prüfe dass Timestamp "${resourceIat}" in der Vergangenheit liegt

    # Access Token Hash (ath) Validierung - nur bei Resource Requests
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.ath"
    Und berechne SHA256 Hash von "${accessToken}" und speichere in Variable "expectedAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.ath" überein mit "${expectedAth}"

    # Nonce wird bei Resource Requests nicht mitgeschickt

    # ZETA-API-Version Header muss vorhanden sein und SemVer entsprechen (Regex: ^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$; Beispiel: 1.2.3-rc.1+build.5)
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.header.ZETA-API-Version"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.ZETA-API-Version" überein mit "${regex.zetaApiVersion}"

  @dev
  @A_25762
  @A_25767
  @TA_A_25762_04
  @TA_A_25767_02
  Szenario: DPoP Resource Request - Guard lehnt fremdes JWK (Binding) ab
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"

    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "$.header.dpop" das Feld "header.typ" auf Wert "dpop+jwt" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    # zweites reset, damit der Client beim nächsten Aufruf ein neues DPoP Keypair verwendet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @dev
  @A_25762
  @A_25767
  @TA_A_25762_04
  @TA_A_25767_02
  Szenario: DPoP Resource Request - Wiederverwendung desselben jti wird abgewiesen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "$.header.dpop" das Feld "body.jti" auf Wert "replay-jti" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 2 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @dev
  @A_25767
  @A_27802
  @TA_A_25767_02
  @TA_A_27802_10
  Szenariogrundriss: DPoP JWT Manipulation Test - Resource Anfrage
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "<JwtLocation>" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Vorladen der Nachrichten, damit die nachfolgende Suche nach dem manipulierten Wert schneller durchläuft
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "<JwtLocation>.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    Beispiele: Manipulationen
      | JwtLocation   | JwtField   | NeuerWert                  | ResponseCode |
      | $.header.dpop | header.typ | JWT                        | 401          |
      | $.header.dpop | header.alg | RS999                      | 401          |
      | $.header.dpop | body.iat   | 1600000000                 | 401          |
      | $.header.dpop | body.ath   | wronghash                  | 401          |
      | $.header.dpop | body.htm   | POST                       | 401          |
      | $.header.dpop | body.htu   | https://wrong.url/resource | 401          |

  @dev
  @A_25669
  @TA_A_25669_01
  @TA_A_25669_02
  @TA_A_25669_03
  @TA_A_25669_07
  Szenario: PEP fügt alle ZETA-Header ein (User-Info, PoPP-Token-Content, Client-Data)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - keine ZETA-Header vorhanden
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_OLD_HEADERS"

    Und prüfe aktueller Request enthält keinen Knoten "$.header.ZETA-User-Info" und nutze soft assert
    Und prüfe aktueller Request enthält keinen Knoten "$.header.ZETA-PoPP-Token-Content" und nutze soft assert
    Und prüfe aktueller Request enthält keinen Knoten "$.header.ZETA-Client-Data" und nutze soft assert

    # PoPP-Header (JWT) muss vorhanden sein
    Und TGR prüfe aktueller Request enthält Knoten "$.header.popp"

    # Prüfe Request NACH PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "^/achelos_testfachdienst/hellozeta"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_HEADERS"

    # TA_A_25669_01: ZETA-User-Info wurde eingefügt
    Und TGR prüfe aktueller Request enthält Knoten "$.header.ZETA-User-Info"
    Und TGR speichere Wert des Knotens "$.header.ZETA-User-Info" der aktuellen Anfrage in der Variable "USER_INFO"
    Und prüfe ob der Knoten "${USER_INFO}" MAX 350 Byte groß ist und nutze soft assert
    Und prüfe "${USER_INFO}" ist striktes Base64-URL Format
    Und decodiere "${USER_INFO}" von Base64-URL und speichere in der Variable "USER_INFO_decoded"
    Und validiere "${USER_INFO_decoded}" gegen Schema "schemas/v_1_0/user-info.yaml"

    # TA_A_25669_02: ZETA-PoPP-Token-Content wurde eingefügt
    Und TGR prüfe aktueller Request enthält Knoten "$.header.ZETA-PoPP-Token-Content"
    Und TGR speichere Wert des Knotens "$.header.ZETA-PoPP-Token-Content" der aktuellen Anfrage in der Variable "POPP_CONTENT"
    Und prüfe ob der Knoten "${POPP_CONTENT}" MAX 450 Byte groß ist und nutze soft assert
    Und prüfe "${POPP_CONTENT}" ist striktes Base64-URL Format
    Und decodiere "${POPP_CONTENT}" von Base64-URL und speichere in der Variable "POPP_CONTENT_decoded"
    # Vergleich: ZETA-PoPP-Token-Content muss dem Payload des PoPP-JWT entsprechen
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "$.header.popp.body" der aktuellen Anfrage in der Variable "POPP_JWT_PAYLOAD"
    Und prüfe Knoten "${POPP_JWT_PAYLOAD}" enthält mindestens alle Kindknoten von "${POPP_CONTENT_decoded}"

    # TA_A_25669_03: ZETA-Client-Data wurde eingefügt - zurück zum Request NACH PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "^/achelos_testfachdienst/hellozeta"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.ZETA-Client-Data"
    Und TGR speichere Wert des Knotens "$.header.ZETA-Client-Data" der aktuellen Anfrage in der Variable "CLIENT_DATA"
    Und prüfe ob der Knoten "${CLIENT_DATA}" MAX 2500 Byte groß ist und nutze soft assert
    Und prüfe "${CLIENT_DATA}" ist striktes Base64-URL Format
    Und decodiere "${CLIENT_DATA}" von Base64-URL und speichere in der Variable "CLIENT_DATA_decoded"
    Und validiere "${CLIENT_DATA_decoded}" soft gegen Schema "schemas/v_1_0/client-instance.yaml"

    # TA_A_25669_07: Alle anderen Header wurden weitergeleitet
    Und prüfe Knoten "${ALL_HEADERS}" enthält mindestens alle Header aus "${ALL_OLD_HEADERS}" und nutze soft assert

  @dev
  @A_25669
  @TA_A_25669_04
  @TA_A_25669_05
  @TA_A_25669_06
  Szenario: PEP überschreibt vom Client gesetzte ZETA-Header
    # Setze gefälschte ZETA-Header als Default-Header - diese werden vom Client mitgesendet
    Gegeben sei TGR setze den default header "zeta-user-info" auf den Wert "FAKE_USER_INFO"
    Und TGR setze den default header "zeta-popp-token-content" auf den Wert "FAKE_POPP_CONTENT"
    Und TGR setze den default header "zeta-client-data" auf den Wert "FAKE_CLIENT_DATA"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - gefälschte Header wurden vom Client gesendet
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.zeta-user-info" überein mit "FAKE_USER_INFO"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.zeta-popp-token-content" überein mit "FAKE_POPP_CONTENT"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.zeta-client-data" überein mit "FAKE_CLIENT_DATA"

    # Prüfe Request NACH PEP - Header wurden vom PEP überschrieben (nicht mehr FAKE_*)
    Dann TGR finde die letzte Anfrage mit dem Pfad "^/achelos_testfachdienst/hellozeta"

    # Prüfe, dass alle drei ZETA-Header vorhanden sind
    Und TGR prüfe aktueller Request enthält Knoten "$.header.ZETA-User-Info"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.ZETA-PoPP-Token-Content"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.ZETA-Client-Data"

    # TA_A_25669_04: ZETA-User-Info wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.ZETA-User-Info" nicht überein mit "FAKE_USER_INFO"

    # TA_A_25669_05: ZETA-PoPP-Token-Content wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.ZETA-PoPP-Token-Content" nicht überein mit "FAKE_POPP_CONTENT"

    # TA_A_25669_06: ZETA-Client-Data wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.ZETA-Client-Data" nicht überein mit "FAKE_CLIENT_DATA"

  @dev
  @A_27492-01
  @TA_A_27492-01_03
  Szenariogrundriss: OpenTelemetry Unterstützung von HTTP Proxy, Authorization Server, Policy Engine und Notification Service
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                   | size |
      | resource.k8s.namespace.name:zeta-local AND resource.k8s.container.name:<containerName> AND attributes.log.file.path:/var/log/pods/* | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    Beispiele:
      | containerName           |

      # HTTP Proxy
      | nginx                   |

      # Authorization Server
      | keycloak                |

      # Policy Engine
      | opa                     |

      # TODO: Notification Service fehlt noch im Deployment
      #| notification-service    |
