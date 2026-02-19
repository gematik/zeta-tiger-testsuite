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

@UseCase_01_12
Funktionalität: Client_ressource_anfrage_fachdienst_SC_200_integrationstest

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt

  @A_26639
  @A_26195
  @TA_A_26639_01
  @TA_A_26195_01
  @websocket
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
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "$.header.authorization.DpopToken.<JwtField>" der mit "<NeuerWert>" übereinstimmt
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
  @A_25762
  @A_25767
  @A_26492-01
  @A_27802
  @A_27853
  @A_25766
  @TA_A_25660_01
  @TA_A_25660_04
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
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.header.jwk.kty" überein mit "EC"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.header.jwk.crv" überein mit "P-256"
    Und TGR speichere Wert des Knotens "$.header.dpop.header.jwk.x" der aktuellen Anfrage in der Variable "resourceDpopJwkX"
    Und TGR speichere Wert des Knotens "$.header.dpop.header.jwk.y" der aktuellen Anfrage in der Variable "resourceDpopJwkY"
    Und decodiere Base64Url "${resourceDpopJwkX}" und prüfe das die Länge 256 bit ist
    Und decodiere Base64Url "${resourceDpopJwkY}" und prüfe das die Länge 256 bit ist
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
    Und TGR ersetze ":443$" mit "" im Inhalt der Variable "requestHost"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "requestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.htu" überein mit "${requestScheme}://${requestHost}${requestPath}"
    Und TGR speichere Wert des Knotens "$.header.dpop.body.iat" der aktuellen Anfrage in der Variable "resourceIat"
    Und prüfe dass Timestamp "${resourceIat}" in der Vergangenheit liegt

    # Access Token Hash (ath) Validierung - nur bei Resource Requests
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.ath"
    Und berechne SHA256 Hash von "${accessToken}" und speichere in Variable "expectedAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.ath" überein mit "${expectedAth}"

    # Nonce wird bei Resource Requests nicht mitgeschickt

    # TA_A_27853_01 - ZETA-API-Version Header muss vorhanden sein und SemVer entsprechen (Regex: ^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$; Beispiel: 1.2.3-rc.1+build.5)
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.header.ZETA-API-Version"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.ZETA-API-Version" überein mit "${regex.zetaApiVersion}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.ZETA-API-Version" überein mit "${testdata.semVer}"

  @TA_A_26561_01
  Szenario: PEP HTTP Proxy nutzt gecachte Response-Inhalte
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR setze lokale Variable "cachedMessage" auf "Hello ZETA (cached)"
    Und TGR setze lokale Variable "cacheCondition" auf "isResponse && request.path =~ '^${paths.fachdienst.helloZetaPath}'"
    Dann Setze im TigerProxy für die Nachricht "${cacheCondition}" die Manipulation auf Feld "$.body.message" und Wert "${cachedMessage}" und 1 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "${cachedMessage}"

    Und Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "${cachedMessage}"

  @dev
  @A_25762
  @A_25767
  @A_27007
  @A_26661
  @TA_A_25762_04
  @TA_A_25767_02
  @TA_A_27007_19
  @TA_A_26661_19
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
  @A_26589
  @A_26661
  @A_27007
  @TA_A_25669_01
  @TA_A_25669_02
  @TA_A_25669_03
  @TA_A_25669_07
  @TA_A_26589_01
  @TA_A_26661_15
  @TA_A_27007_15
  Szenario: PEP fügt alle ZETA-Header ein (User-Info, PoPP-Token-Content, Client-Data)
    # Access Token holen und User-Daten aus dem Access Token ermitteln
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.authorization.DpopToken.body.udat.telid"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.authorization.DpopToken.body.udat.prof"
    Und TGR speichere Wert des Knotens "$.header.authorization.DpopToken.body.udat.telid" der aktuellen Anfrage in der Variable "expectedIdentifier"
    Und TGR speichere Wert des Knotens "$.header.authorization.DpopToken.body.udat.prof" der aktuellen Anfrage in der Variable "expectedProfessionOid"

    # Nachrichten löschen und Resource Request mit manipulierten PDP-DB-Daten senden
    Und TGR lösche aufgezeichnete Nachrichten
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
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_HEADERS"

    # TA_A_25669_01: ZETA-User-Info wurde eingefügt
    Und TGR prüfe aktueller Request enthält Knoten "$.header.ZETA-User-Info"
    Und TGR speichere Wert des Knotens "$.header.ZETA-User-Info" der aktuellen Anfrage in der Variable "USER_INFO"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.ZETA-User-Info.decoded.identifier" überein mit "${expectedIdentifier}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.ZETA-User-Info.decoded.professionOID" überein mit "${expectedProfessionOid}"
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
    Und prüfe Knoten "${POPP_CONTENT_decoded}" enthält mindestens alle Kindknoten von "${POPP_JWT_PAYLOAD}"

    # TA_A_25669_03: ZETA-Client-Data wurde eingefügt - zurück zum Request NACH PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
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
  @TA_A_28439_01
  Szenario: PEP überschreibt vom Client gesetzte ZETA-Header und aktualisiert den Forwarded-Header
    # Setze gefälschte ZETA-Header als Default-Header - diese werden vom Client mitgesendet
    Gegeben sei TGR setze den default header "zeta-user-info" auf den Wert "FAKE_USER_INFO"
    Und TGR setze den default header "zeta-popp-token-content" auf den Wert "FAKE_POPP_CONTENT"
    Und TGR setze den default header "zeta-client-data" auf den Wert "FAKE_CLIENT_DATA"
    Und TGR setze den default header "Forwarded" auf den Wert "for=client;proto=http"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - gefälschte Header wurden vom Client gesendet
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.helloZetaPath}" übereinstimmt
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.zeta-user-info" überein mit "FAKE_USER_INFO"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.zeta-popp-token-content" überein mit "FAKE_POPP_CONTENT"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.zeta-client-data" überein mit "FAKE_CLIENT_DATA"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.[~'Forwarded']"
    Und TGR speichere Wert des Knotens "$.header.[~'Forwarded']" der aktuellen Anfrage in der Variable "forwardedBefore"

    # Prüfe Request NACH PEP - Header wurden vom PEP überschrieben (nicht mehr FAKE_*)
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.[~'Forwarded']"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Forwarded']" nicht überein mit "${forwardedBefore}"
    Und TGR speichere Wert des Knotens "$.header.[~'Forwarded']" der aktuellen Anfrage in der Variable "forwardedAfter"
    # RFC 7239: bestehender Forwarded-Header bleibt erhalten und es wird ein weiteres gültiges Forwarded-Element (for|by|proto|host) angehängt
    Und TGR prüfe Variable "forwardedAfter" stimmt überein mit "^for=client;proto=http\\s*,\\s*(for|by|proto|host)=.+$"

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
  @A_27264
  Szenariogrundriss: OpenTelemetry Metrics für ZETA Guard Komponenten (ohne Datenbanken)
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                                                    | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:<containerName> AND body:"\\\'msg\\\':\\\'Sent response.\\\'" AND body:"\\\'req_method\\\':\\\'GET\\\'" AND body:"\\\'resp_status\\\':\\\'200\\\'" AND body:"\\\'req_path\\\':\\\'\\\/metrics\\\'" | 1    |

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    @TA_A_27264_10
    Beispiele: Ingress
      | containerName |
      | controller    |

    # TODO: TA_A_27264_11, Egress               <- Dedizierter Container/Gateway fehlt noch
    @TA_A_27264_11
    Beispiele: Egress
      | containerName |
      | controller    |

    @TA_A_27264_12
    Beispiele: HTTP Proxy
      | containerName |
      | nginx         |

    @TA_A_27264_13
    Beispiele: Authorization Server
      | containerName |
      | keycloak      |

    @TA_A_27264_14
    Beispiele: Policy Engine
      | containerName |
      | opa           |

    # TODO: TA_A_27264_15, Notification Service <- Container fehlt noch
    # @TA_A_27264_15
    # Beispiele: Notification Service
    #   | containerName         |
    #   | notification-service  |

    # TODO: TA_A_27264_16, Management Service   <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_27264_16
    # Beispiele: Management Service
    #   | containerName        |
    #   | management-service   |

    @TA_A_27264_17
    Beispiele: Telemetrie Daten Service
      | containerName           |
      | opentelemetry-collector |

    Beispiele: Zulieferer für Telemetrie Daten Service
      | containerName |
      | log-collector |

    Beispiele: Zulieferer für Telemetrie Daten Service, Exporteur zu gematik
      | containerName     |
      | telemetry-gateway |

    @TA_A_27264_18
    Beispiele: Resource Server
      | containerName   |
      | testfachdienst  |

  @dev
  @A_27399
  @TA_A_27399_05
  Szenario: Sessiondaten nach session_expiry nicht mehr verwendbar
    # TTL-Werte als Variablen definieren (in Sekunden)
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "30"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "100"

    # OPA Decision manipulieren: Kurze TTL für Refresh Token setzen
    # Die OPA-Response bestimmt die tatsächliche Token-Gültigkeit im Authorization Server
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*/v1/data/zeta/authz/decision'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen

    # Client zurücksetzen und ersten Token holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "oldSessionId"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    # Warte bis session_expiry erreicht ist
    Und warte "${refreshTokenTtl}" Sekunden
    Und prüfe dass Timestamp "${session_expiry}" in der Vergangenheit liegt

    # Nachrichten löschen, damit nur der nächste Resource-Request geprüft wird
    Und TGR lösche aufgezeichnete Nachrichten

    # DPoP-Key holen und Access Token manipulieren (jti alt)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für Access Token das Feld "body.sid" auf Wert "${oldSessionId}" mit Access Token Key "${signingKey}" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" nicht überein mit "200"

    # Überprüfe zusätzlich, dass eine neue Session verwendet wird
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" nicht überein mit "${oldSessionId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" nicht überein mit "${oldSessionId}"
