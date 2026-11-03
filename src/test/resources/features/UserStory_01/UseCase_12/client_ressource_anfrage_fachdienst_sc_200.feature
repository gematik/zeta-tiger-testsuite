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
Funktionalität: Client Ressource Anfrage Fachdienst SC 200

  @A_26195
  @A_26639
  @TA_A_26195_01
  @TA_A_26639_01
  @critical
  @websocket
  Szenario: Ingress und PEP HTTP Proxy unterstützt WebSocket Verbindung
    # Ein WebSocket-Roundtrip sollte hier reichen, allerdings könnte man auch noch die Verbindung
      # Ingress <-> PEP HTTP Proxy über den Standalone Tiger Proxy testen.
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Dann wird die WebSocket Verbindung geschlossen

  @A_25660
  @A_25766
  @A_25767
  @A_26492-02
  @A_29381
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_25766_02
  @TA_A_25767_02
  @TA_A_26492-02_02
  @TA_A_29381_01
  @asl_off
  @normal
  @MASVS-PRIVACY
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: DPoP Resource Request - Client sendet DPoP Proof mit Access Token für geschützte Ressource
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # save the access token from /token response for later ath check
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "accessToken"

    # TA_A_25660_04 - Refresh Token muss vorhanden sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.userInfo.root}"

    # Resource Request Validierung
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP .*"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}" überein mit "^\Q${accessToken}\E$"

    # DPoP JWT Validierung
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "resourceDpopJwt"
    Und decodiere und validiere "${resourceDpopJwt}" gegen Schema "schemas/v_2_0/schemas/dpop-token.yaml"
    Und verifiziere ES256 Signatur von DPoP JWT "${resourceDpopJwt}"

    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.typ}" überein mit "dpop+jwt"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.alg}" überein mit "ES256"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.header.jwk.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.header.jwk.kty}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.jwk.kty}" überein mit "EC"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.jwk.crv}" überein mit "P-256"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.jwk.x}" der aktuellen Anfrage in der Variable "resourceDpopJwkX"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.jwk.y}" der aktuellen Anfrage in der Variable "resourceDpopJwkY"
    Und decodiere Base64Url "${resourceDpopJwkX}" und prüfe, dass die Länge 256 bit ist
    Und decodiere Base64Url "${resourceDpopJwkY}" und prüfe, dass die Länge 256 bit ist
    Und TGR speichere Wert des Knotens "${headers.dpop.header.root}" der aktuellen Anfrage in der Variable "resourceDpopHeader"
    Und prüfe dass jwk in "${resourceDpopHeader}" keine privaten Key-Teile enthält

    # DPoP Payload Validierung
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.jti}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.htm}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.htu.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.iat}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htm}" überein mit "GET"
    Und TGR speichere Wert des Knotens "${headers.xForwardedProto}" der aktuellen Anfrage in der Variable "requestScheme"
    Und TGR speichere Wert des Knotens "${headers.xForwardedHost}" der aktuellen Anfrage in der Variable "requestHost"
    Und TGR ersetze ":443$" mit "" im Inhalt der Variable "requestHost"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "requestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "${requestScheme}://${requestHost}${requestPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.body.iat}" der aktuellen Anfrage in der Variable "resourceIat"
    Und validiere, dass der Zeitstempel "${resourceIat}" in der Vergangenheit liegt

    # Access Token Hash (ath) Validierung - nur bei Resource Requests
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.ath}"
    Und berechne SHA256 Hash von "${accessToken}" und speichere in Variable "expectedAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${expectedAth}"

    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^${paths.fachdienst.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"

    # TA_A_26492-02_02 - PEP HTTP Proxy - Weiterleitung von Client-Daten - Defaulteinstellung
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.clientData.root}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und enthält der Ressourcenaufruf keine ASL-Transportnachrichten

  @A_27802-03
  @A_28963
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @TA_A_28963_01
  @normal
  @MASVS-CODE
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: ZETA Guard akzeptiert einen unbekannten nicht kritischen DPoP-Header beim Ressourcenzugriff
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "resourceRequestCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "header.unknown_guard_parameter" auf Wert "unsupported" mit privatem Schlüssel "${dpopKey}" für Pfad "${resourceRequestCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.dpop.header.root}.unknown_guard_parameter" der mit "unsupported" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.dpop.header.root}.crit"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "resourceDpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${resourceDpopJwt}"
    Und berechne JKT aus JWT Header JWK "${resourceDpopJwt}" und speichere in Variable "resourceDpopJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" überein mit "${resourceDpopJkt}"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "authorizationHeader"
    Und extrahiere DPoP Access Token aus Authorization Header "${authorizationHeader}" und speichere in Variable "accessToken"
    Und berechne SHA256 Hash von "${accessToken}" und speichere in Variable "expectedAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${expectedAth}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"

  @A_25783-01
  @A_29852
  @TA_A_25783-01_01
  @smb
  @MASVS-AUTH
  @MASVS-CODE
  Szenariogrundriss: Client folgt Step-Up-Anweisung des PEP HTTP Proxy mit neuem Access Token nach <Statuscode>
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "initialAccessTokenJti"

    Und TGR lösche aufgezeichnete Nachrichten

    Und TGR setze lokale Variable "resourceUnauthorizedCondition" auf "isResponse && request.path =~ '.*${paths.guard.helloZetaPath}'"
    # Simuliert den für TA_A_29852_01/02 jeweils geforderten PEP-Statuscode 401 beziehungsweise 403.
    Und Setze im TigerProxy für die Nachricht "${resourceUnauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<Statuscode>" und 1 Ausführungen
    # Fügt den exakten Headernamen ein, damit der Client die Antwort als PEP-Fehler gemäß TA_A_29852_01/02 erkennt.
    Und Setze im TigerProxy für die Nachricht "${resourceUnauthorizedCondition}" die Manipulation auf Feld "${headers.zeta.errorOrigin.strict}" und Wert "pep" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"

    # TA_A_29852_01/TA_A_29852_02 - Der Fehler ist über den Header als PEP-Fehler gekennzeichnet.
    # Erst dadurch ist der Client für die interne Fehlerbehandlung zuständig (Abgrenzung zu A_29853).
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "pep"

    # TA_A_25783-01_01 - bei 401/403 vom PEP HTTP Proxy wird per Refresh Token oder vollständiger Authentifizierung ein neues Access Token bezogen.
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}|${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${initialAccessTokenJti}"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "newAccessTokenJti"

    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.jti" überein mit "${newAccessTokenJti}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    @TA_A_29852_01
    Beispiele: HTTP 401
      | Statuscode |
      | 401        |

    @TA_A_29852_02
    Beispiele: HTTP 403
      | Statuscode |
      | 403        |

  # Optionaler Zweig aus §5.8.6: Der Client kann vor der vollständigen Authentifizierung Refresh versuchen.
  @A_25783-01
  @TA_A_25783-01_01
  @smb
  @MASVS-AUTH
  Szenariogrundriss: Client authentifiziert sich vollständig nach fehlgeschlagenem optionalem Refresh infolge <Statuscode> des PEP HTTP Proxy
    # Die initiale Sitzung liefert die Referenztoken.
    # F01: Setzt den Client für die initiale Authentifizierung zurück.
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # F02: Stellt eine gültige Sitzung her.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # F03: Wählt die initiale Clientantwort aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    # F04: Bestätigt den erfolgreichen initialen Ressourcenzugriff.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # F05: Wählt die initiale Tokenausgabe aus.
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    # F06: Bestätigt die erfolgreiche Tokenausgabe.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F07: Prüft die Verfügbarkeit der initialen Tokenkennung.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    # F08: Sichert die initiale Tokenkennung für den Vergleich.
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "initialAccessTokenJti"
    # F09: Prüft die Verfügbarkeit eines Refresh Tokens.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    # F10: Sichert das Refresh Token für die spätere Korrelation.
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "initialRefreshToken"

    # F11: Isoliert die Nachrichten der Fehlerbehandlung.
    Und TGR lösche aufgezeichnete Nachrichten

    # PEP-Fehler und anschließende Refresh-Ablehnung werden vorbereitet.
    # F12: Begrenzt den Ressourcenfehler auf Antworten des PEP.
    Und TGR setze lokale Variable "resourceUnauthorizedCondition" auf "isResponse && request.path =~ '.*${paths.guard.helloZetaPath}'"
    # F13: Begrenzt die Ablehnung auf den Token-Endpunkt mit dem vollständigen Refresh-Formparameter.
    Und TGR setze lokale Variable "refreshUnauthorizedCondition" auf "isResponse && request.path == '${paths.guard.tokenEndpointPath}' && 'grant_type=${oauth_parameters.grant_type.refreshToken}' =~ request.bodyAsString.split('&')"
    # F14: Simuliert den initialen Ressourcenfehler.
    Und Setze im TigerProxy für die Nachricht "${resourceUnauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<Statuscode>" und 1 Ausführungen
    # F15: Kennzeichnet den simulierten Ressourcenfehler als PEP-Fehler.
    Und Setze im TigerProxy für die Nachricht "${resourceUnauthorizedCondition}" die Manipulation auf Feld "${headers.zeta.errorOrigin.strict}" und Wert "pep" und 1 Ausführungen
    # F16: Simuliert die Ablehnung des optionalen Refreshs.
    Und Setze im TigerProxy für die Nachricht "${refreshUnauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<RefreshStatuscode>" und 1 Ausführungen

    # F17: Löst den Ressourcenzugriff mit Fehlerbehandlung aus.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Die erste Ressourcenantwort weist den gekennzeichneten PEP-Fehler nach.
    # F18: Wählt die erste PEP-Antwort aus.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    # F19: Bestätigt den simulierten Fehlerstatus.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"
    # F20: Belegt die PEP-Kennzeichnung derselben Fehlerantwort.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "pep"

    # Der optionale Refresh wird mit dem initialen Token abgelehnt.
    # F21: Wählt den optionalen Refresh-Versuch aus.
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    # F22: Bindet den Refresh-Versuch an das initiale Refresh Token.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${initialRefreshToken}"
    # F23: Bestätigt die simulierte Refresh-Ablehnung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<RefreshStatuscode>"

    # Die vollständige Authentifizierung liefert ein neues Access Token.
    # F24: Belegt für TA_A_25783-01_01 die vollständige Authentifizierung nach der Refresh-Ablehnung.
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    # F25: Bestätigt die erfolgreiche erneute Tokenausgabe.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F26: Prüft die Verfügbarkeit der neuen Tokenkennung.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    # F27: Belegt den Wechsel gegenüber dem initialen Access Token.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${initialAccessTokenJti}"
    # F28: Sichert die neue Tokenkennung für den Ressourcenretry.
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "newAccessTokenJti"

    # Der Ressourcenretry verwendet das neue Access Token.
    # F29: Belegt für TA_A_25783-01_01 den Ressourcenretry mit dem neu ausgestellten Access Token.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.body.jti" der mit "\Q${newAccessTokenJti}\E" übereinstimmt
    # F30: Bestätigt den erfolgreichen Ressourcenretry.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F31: Begrenzt den Ressourcenzugriff auf Erstversuch und Retry.
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    # F32: Begrenzt die Tokenanfragen auf Refresh und vollständige Authentifizierung.
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    Beispiele:
      | Statuscode | RefreshStatuscode |
      | 401        | 401               |
      | 401        | 403               |
      | 403        | 401               |
      | 403        | 403               |

  @A_25783-01
  @TA_A_25783-01_01
  # Prüft den optionalen Refresh-Zweig vor der einmaligen vollständigen Authentifizierung.
  @smb
  @MASVS-AUTH
  Szenariogrundriss: Client bricht nach Refresh und vollständiger Authentifizierung bei erneutem <Statuscode> des PEP HTTP Proxy ab
    # Eine gültige Sitzung liefert die Referenztoken für den optionalen Refresh-Zweig.
    # F01: Setzt den Client für die Referenzsitzung zurück.
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    # F02: Stellt eine gültige Sitzung durch einen erfolgreichen Ressourcenzugriff her.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # F03: Wählt die Antwort des initialen Ressourcenzugriffs aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    # F04: Bestätigt den erfolgreichen initialen Ressourcenzugriff.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F05: Wählt die initiale Tokenausgabe aus.
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    # F06: Bestätigt die erfolgreiche initiale Tokenausgabe.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F07: Sichert die Tokenkennung des initialen Ressourcenversuchs.
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "initialAccessTokenJti"
    # F08: Sichert das Refresh Token der Referenzsitzung.
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "initialRefreshToken"
    # F09: Isoliert die Nachrichten der anschließenden Fehlerbehandlung.
    Und TGR lösche aufgezeichnete Nachrichten

    # Drei PEP-Fehler erfassen Erstversuch, Refresh-Retry und Retry nach vollständiger Authentifizierung.
    # F10: Begrenzt die Manipulationen auf Antworten des Ressourcenendpunkts.
    Und TGR setze lokale Variable "resourceErrorCondition" auf "isResponse && request.path == '${paths.guard.helloZetaPath}'"
    # F11: Simuliert den Fehlerstatus für alle drei Ressourcenversuche.
    Und Setze im TigerProxy für die Nachricht "${resourceErrorCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<Statuscode>" und 3 Ausführungen
    # F12: Kennzeichnet alle drei simulierten Antworten als PEP-Fehler.
    Und Setze im TigerProxy für die Nachricht "${resourceErrorCondition}" die Manipulation auf Feld "${headers.zeta.errorOrigin.strict}" und Wert "pep" und 3 Ausführungen
    # F13: Setzt den zum Statuscode passenden Fehlerbody für alle drei Antworten.
    Und Setze im TigerProxy für die Nachricht "${resourceErrorCondition}" die Manipulation auf Feld "$.body" und Wert "!{file('<FehlerBody>')}" und 3 Ausführungen
    # F14: Setzt die DPoP-Authentifizierungsaufforderung für alle drei Antworten.
    Und Setze im TigerProxy für die Nachricht "${resourceErrorCondition}" die Manipulation auf Feld "$.header.['WWW-Authenticate']" und Wert "DPoP" und 3 Ausführungen
    # F15: Löst den Ressourcenzugriff mit der vorbereiteten Sitzung aus.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Der initiale Ressourcenversuch wird mit dem ursprünglichen Access Token abgelehnt.
    # F16: Korreliert den Erstversuch mit dem initialen Access Token.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.body.jti" der mit "\Q${initialAccessTokenJti}\E" übereinstimmt
    # F17: Bestätigt den initialen PEP-Fehlerstatus.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"
    # F18: Belegt die PEP-Kennzeichnung derselben initialen Fehlerantwort.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "pep"

    # Der optionale Refresh ist erfolgreich, sein Ressourcenretry wird erneut abgelehnt.
    # F19: Wählt den optionalen Refresh der isolierten Fehlerbehandlung aus.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    # F20: Bindet den Refresh an die vorbereitete Sitzung.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${initialRefreshToken}"
    # F21: Bestätigt den erfolgreichen Refresh vor dem erneuten Ressourcenfehler.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F22: Sichert die Tokenkennung des Refresh-Retry.
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "refreshAccessTokenJti"
    # F23: Belegt den Tokenwechsel gegenüber dem Erstversuch.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${initialAccessTokenJti}"
    # F24: Belegt für TA_A_25783-01_01 den Ressourcenretry mit dem Refresh-Access-Token.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.body.jti" der mit "\Q${refreshAccessTokenJti}\E" übereinstimmt
    # F25: Bestätigt den Fehlerstatus des Refresh-Retry.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"
    # F26: Belegt die PEP-Kennzeichnung des fehlgeschlagenen Refresh-Retry.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "pep"

    # Auch der Ressourcenretry nach vollständiger Authentifizierung erhält einen PEP-Fehler.
    # F27: Belegt für TA_A_25783-01_01 die vollständige Authentifizierung im Fehlerablauf.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    # F28: Bestätigt die erfolgreiche vollständige Authentifizierung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F29: Sichert die Tokenkennung der vollständigen Authentifizierung.
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "newAccessTokenJti"
    # F30: Belegt den Tokenwechsel gegenüber dem Refresh.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${refreshAccessTokenJti}"
    # F31: Schließt die Wiederverwendung des initialen Access Tokens aus.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${initialAccessTokenJti}"
    # F32: Belegt für TA_A_25783-01_01 den Ressourcenretry mit dem vollständig neu ausgestellten Access Token.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.body.jti" der mit "\Q${newAccessTokenJti}\E" übereinstimmt
    # F33: Bestätigt den Fehlerstatus nach vollständiger Authentifizierung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"
    # F34: Belegt die PEP-Kennzeichnung der abschließenden Ressourcenfehlerantwort.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "pep"

    # A_25783-01 verlangt den Abbruch; der Testdriver muss den PEP-Status und -Body nicht unverändert weitergeben.
    # F35: Wählt die abgeschlossene Anfrage am Testdriver aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    # F36: Bestätigt den erfolglosen Abschluss am Testdriver unabhängig von seiner Fehlerabbildung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "[45][0-9]{2}"
    # F37: Belegt für TA_A_25783-01_01 den Abbruch ohne vierten Ressourcenversuch.
    Dann prüfe, dass genau 3 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    # F38: Begrenzt die Fehlerbehandlung auf einen Refresh und eine vollständige Authentifizierung.
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    Beispiele:
      | Statuscode | FehlerBody                                               |
      | 401        | src/test/resources/mocks/zeta-error-401-invalid-token.json |
      | 403        | src/test/resources/mocks/zeta-error-403-access-denied.json |

  @A_27265
  @TA_A_27265_01
  Szenario: PEP HTTP Proxy leitet Host Header unverändert an den Resource Server weiter
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "hostHeaderCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und TGR setze lokale Variable "manipulierterHostHeader" auf "!{'${zeta_base_url}'.toUpperCase()}"
    Und Setze im TigerProxy für die Nachricht "${hostHeaderCondition}" die Manipulation auf Feld "${headers.host}" und Wert "${manipulierterHostHeader}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.host}" der aktuellen Anfrage in der Variable "manipulierterHostVorPep"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${manipulierterHostHeader}"

    # TA_A_27265_01: PEP HTTP Proxy leitet den Host Header unverändert an den Resource Server weiter
    Und TGR finde die erste Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${manipulierterHostVorPep}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und Alle Manipulationen im TigerProxy werden gestoppt

  @A_25669-01
  @TA_A_25669-01_08
  @TA_A_25669-01_09
  @deployment_modification
  @popp_deployment_toggle
  @MASVS-CODE
  Szenario: PEP fügt keinen zeta-popp-token-content ein, wenn kein PoPP-Header mitgeschickt wurde, und entfernt ursprünglich gleichnamige Header
    Gegeben sei deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppHeaderCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    # popp Header löschen
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "${headers.popp.lineRegex}" und Wert "" und 1 Ausführungen
    # zeta-popp-token-content Header hinzufügen
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Manipulation auf Feld "${headers.zeta.poppTokenContent.strict}" und Wert "FAKE_POPP_CONTENT"
    # zeta-client-data Header hinzufügen
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Manipulation auf Feld "${headers.zeta.clientData.strict}" und Wert "FAKE_CLIENTDATA_CONTENT"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - PoPP-Header ohne Token-Inhalt
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent.root}" überein mit "FAKE_POPP_CONTENT"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.root}" überein mit "FAKE_CLIENTDATA_CONTENT"

    # Prüfe Request NACH PEP - zeta-popp-token-content darf nicht gesetzt sein
    Dann TGR finde die nächste Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.poppTokenContent.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.clientData.root}"

    Und aktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment

  @A_27725-01
  @TA_A_27725-01_27
  Szenario: Telemetrie-Daten enthalten bei erfolgreicher Operation den HTTP-Statuscode 200
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^${paths.fachdienst.helloZetaPath}" übereinstimmt
    Und TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s, 10s Latenzspielraum)
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"

    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                        | start           | end           | limit | tags                                                                                                 |
      | ${telemetry.service.httpProxy} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.target":"${paths.guard.helloZetaPath}","http.method":"GET","http.response.status_code":"200"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_27725-01_27 - http.response.status_code=200 belegt den konkreten erfolgreichen HTTP-Proxy-Statuscode.
    # Die Existenz von traceID bedeutet, dass es mindestens einen Open Telemetry Trace für genau diese Suchparameter gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
  
  @A_28808
  @TA_A_28808_05
  @MASVS-PRIVACY
  Szenario: Sessiondaten nach session_expiry nicht mehr verwendbar
    # TTL-Werte als Variablen definieren (in Sekunden)
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "30"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "100"

    # OPA Decision manipulieren: Kurze TTL für Refresh Token setzen
    # Die OPA-Response bestimmt die tatsächliche Token-Gültigkeit im Authorization Server
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
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
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    # Nachrichten löschen, damit nur der nächste Resource-Request geprüft wird
    Und TGR lösche aufgezeichnete Nachrichten

    Dann TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    
    # Die Tokenabfrage am PDP mit einer abgelaufenen Session darf nicht erfolgreich sein
    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

    # Überprüfe zusätzlich, dass eine neue Session verwendet wird bei automatischem Retry des Clients
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" nicht überein mit "${oldSessionId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" nicht überein mit "${oldSessionId}"

  @A_27496
  @normal
  @smb
  Szenario: PDP und PEP protokollieren Clientdaten für erfolgreiche Ressourcenaufrufe mit demselben Access Token
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "MON_TRACE_ID" auf "!{randomHex(32)}"
    Und TGR setze lokale Variable "MON_TOKEN_PARENT" auf "!{randomHex(16)}"
    Und TGR setze lokale Variable "MON_RESOURCE_1_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'POST' && message.path =~ '^${paths.guard.tokenEndpointPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_TRACE_ID}-${MON_TOKEN_PARENT}-01" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_TRACE_ID}-${MON_RESOURCE_1_PARENT}-01" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_TRACE_ID}-${MON_TOKEN_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "MON_ACCESS_TOKEN"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "MON_CLIENT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "MON_PRODUCT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "MON_PRODUCT_VERSION"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "MON_SMCB_CERTIFICATE"
    Und schreibe Daten aus dem SMC-B Zertifikat "${MON_SMCB_CERTIFICATE}" in die Variable "MON-SMCB"
    Und TGR setze lokale Variable "MON_PROFESSION_OID" auf "${MON-SMCB.professionId}"
    Und TGR prüfe Variable "MON_CLIENT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_PRODUCT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_PRODUCT_VERSION" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_PROFESSION_OID" stimmt überein mit "[0-9]+(\.[0-9]+)+"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${MON_CLIENT_ID}\E" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_id" überein mit "\Q${MON_PRODUCT_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_version" überein mit "\Q${MON_PRODUCT_VERSION}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "\Q${MON_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.decision_id" der aktuellen Antwort in der Variable "MON_POLICY_DECISION_ID"
    Und TGR prüfe Variable "MON_POLICY_DECISION_ID" stimmt überein mit ".+"
    Und merke ab der aktuellen Antwort die Telemetriefrist "MON_POLICY_LOG" von "${testdata.telemetry_policy_decision_timeout_seconds}" Sekunden
    Und TGR speichere Wert des Knotens "$.header.traceparent" der aktuellen Anfrage in der Variable "MON_AS_POLICY_SPAN_ID"
    Und TGR prüfe Variable "MON_AS_POLICY_SPAN_ID" stimmt überein mit "00-${MON_TRACE_ID}-[0-9a-f]{16}-0[01]"
    Und TGR ersetze "^00-[0-9a-f]{32}-([0-9a-f]{16})-[0-9a-f]{2}$" mit "$1" im Inhalt der Variable "MON_AS_POLICY_SPAN_ID"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_TRACE_ID}-${MON_RESOURCE_1_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${MON_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}" und Knoten "$.header.traceparent" der mit "00-${MON_TRACE_ID}-[0-9a-f]{16}-0[01]" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.fachdienst.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "MON_RESOURCE_2_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_TRACE_ID}-${MON_RESOURCE_2_PARENT}-01" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_TRACE_ID}-${MON_RESOURCE_2_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${MON_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}" und Knoten "$.header.traceparent" der mit "00-${MON_TRACE_ID}-[0-9a-f]{16}-0[01]" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.fachdienst.helloZetaPath}" haben
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.guard.tokenEndpointPath}" hat
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat

    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "MON_RESOURCE_3_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_TRACE_ID}-${MON_RESOURCE_3_PARENT}-01" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_TRACE_ID}-${MON_RESOURCE_3_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${MON_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}" und Knoten "$.header.traceparent" der mit "00-${MON_TRACE_ID}-[0-9a-f]{16}-0[01]" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.fachdienst.helloZetaPath}" haben
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.guard.tokenEndpointPath}" hat
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                       | size | track_total_hits |
      | resource.service.name:"${telemetry.service.policyEngine}" AND traceId:"${MON_TRACE_ID}" AND attributes.opa.decision_log.decision_id:"${MON_POLICY_DECISION_ID}" AND attributes.gematik.zeta.kind:"siem" | 2    | true             |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und wiederhole die aktuelle OpenSearch-GET-Abfrage innerhalb der Telemetriefrist "MON_POLICY_LOG" alle 5 Sekunden solange das Ergebnis leer ist
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.timed_out" überein mit "false"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body._shards.failed" überein mit "0"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.relation" überein mit "eq"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.value" überein mit "1"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.hits.hits.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.traceId" überein mit "${MON_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.resource['service.name']" überein mit "${telemetry.service.policyEngine}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['opa.event_type']" überein mit "decision_log"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['opa.decision_log.decision_id']" überein mit "${MON_POLICY_DECISION_ID}"
    Und TGR speichere Wert des Knotens "$.body.hits.hits.0._source.spanId" der aktuellen Antwort in der Variable "MON_POLICY_LOG_SPAN_ID"
    Und TGR speichere Wert des Knotens "$.body.hits.hits.0._source.body" der aktuellen Antwort in der Variable "MON_POLICY_LOG_BODY"
    Und TGR erstellt ein neues Rbel-Objekt "MON_POLICY_INPUT" mit Inhalt "${MON_POLICY_LOG_BODY}"
    Und TGR prüft, dass Rbel-Objekt "MON_POLICY_INPUT" an Stelle "$.decision_id" gleich "${MON_POLICY_DECISION_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_POLICY_INPUT" an Stelle "$.trace_id" gleich "${MON_TRACE_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_POLICY_INPUT" an Stelle "$.span_id" gleich "${MON_POLICY_LOG_SPAN_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_POLICY_INPUT" an Stelle "$.input.client_registration_data.product_id" gleich "${MON_PRODUCT_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_POLICY_INPUT" an Stelle "$.input.client_registration_data.product_version" gleich "${MON_PRODUCT_VERSION}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_POLICY_INPUT" an Stelle "$.input.user_info.professionOID" gleich "${MON_PROFESSION_OID}" ist

    Wenn TGR sende eine leere GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}/${MON_TRACE_ID}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPath}/${MON_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.traceID" überein mit "${MON_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.data.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_TOKEN_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].tags.[?(@.key == 'url.path')].value" überein mit "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_POLICY_LOG_SPAN_ID}')].traceID" überein mit "${MON_TRACE_ID}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.spanID == '${MON_POLICY_LOG_SPAN_ID}')].processID" der aktuellen Antwort in der Variable "MON_POLICY_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_POLICY_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_POLICY_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.policyEngine}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_POLICY_LOG_SPAN_ID}')].operationName" überein mit "${telemetry.span.policyEngine.data}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_POLICY_LOG_SPAN_ID}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_AS_POLICY_SPAN_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_AS_POLICY_SPAN_ID}')].traceID" überein mit "${MON_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_AS_POLICY_SPAN_ID}')].tags.[?(@.key == 'span.kind')].value" überein mit "client"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.spanID == '${MON_AS_POLICY_SPAN_ID}')].processID" der aktuellen Antwort in der Variable "MON_AS_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_AS_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_AS_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.authorizationServer}"

    Und TGR prüfe aktuelle Antwort im Knoten "$.body.data.0.spans" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "array",
        "items": {
          "if": {
            "required": [
              "operationName"
            ],
            "properties": {
              "operationName": {
                "const": "${telemetry.span.httpProxy.pep}"
              }
            }
          },
          "then": {
            "required": [
              "traceID",
              "spanID",
              "processID",
              "references",
              "tags"
            ],
            "properties": {
              "traceID": {
                "const": "${MON_TRACE_ID}"
              },
              "references": {
                "type": "array",
                "contains": {
                  "required": [
                    "refType",
                    "traceID",
                    "spanID"
                  ],
                  "properties": {
                    "refType": {
                      "const": "CHILD_OF"
                    },
                    "traceID": {
                      "const": "${MON_TRACE_ID}"
                    },
                    "spanID": {
                      "enum": [
                        "${MON_RESOURCE_1_PARENT}",
                        "${MON_RESOURCE_2_PARENT}",
                        "${MON_RESOURCE_3_PARENT}"
                      ]
                    }
                  }
                }
              },
              "tags": {
                "type": "array",
                "allOf": [
                  {
                    "contains": {
                      "type": "object",
                      "required": [
                        "key",
                        "value"
                      ],
                      "properties": {
                        "key": {
                          "const": "product.id"
                        },
                        "value": {
                          "const": "${MON_PRODUCT_ID}"
                        }
                      }
                    }
                  },
                  {
                    "contains": {
                      "type": "object",
                      "required": [
                        "key",
                        "value"
                      ],
                      "properties": {
                        "key": {
                          "const": "product.version"
                        },
                        "value": {
                          "const": "${MON_PRODUCT_VERSION}"
                        }
                      }
                    }
                  },
                  {
                    "contains": {
                      "type": "object",
                      "required": [
                        "key",
                        "value"
                      ],
                      "properties": {
                        "key": {
                          "const": "profession.oid"
                        },
                        "value": {
                          "const": "${MON_PROFESSION_OID}"
                        }
                      }
                    }
                  },
                  {
                    "items": {
                      "allOf": [
                        {
                          "if": {
                            "required": [
                              "key"
                            ],
                            "properties": {
                              "key": {
                                "const": "product.id"
                              }
                            }
                          },
                          "then": {
                            "required": [
                              "value"
                            ],
                            "properties": {
                              "value": {
                                "const": "${MON_PRODUCT_ID}"
                              }
                            }
                          }
                        },
                        {
                          "if": {
                            "required": [
                              "key"
                            ],
                            "properties": {
                              "key": {
                                "const": "product.version"
                              }
                            }
                          },
                          "then": {
                            "required": [
                              "value"
                            ],
                            "properties": {
                              "value": {
                                "const": "${MON_PRODUCT_VERSION}"
                              }
                            }
                          }
                        },
                        {
                          "if": {
                            "required": [
                              "key"
                            ],
                            "properties": {
                              "key": {
                                "const": "profession.oid"
                              }
                            }
                          },
                          "then": {
                            "required": [
                              "value"
                            ],
                            "properties": {
                              "value": {
                                "const": "${MON_PROFESSION_OID}"
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
          }
        }
      }
      """

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].traceID" überein mit "${MON_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_RESOURCE_1_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.target')].value" überein mit "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.method')].value" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].tags.[?(@.key == 'product.id')].value" überein mit "\Q${MON_PRODUCT_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].tags.[?(@.key == 'product.version')].value" überein mit "\Q${MON_PRODUCT_VERSION}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].tags.[?(@.key == 'profession.oid')].value" überein mit "\Q${MON_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].spanID" der aktuellen Antwort in der Variable "MON_PEP_1_SPAN_ID"
    Und TGR ersetze "^([0-9a-f]{16}).*$" mit "$1" im Inhalt der Variable "MON_PEP_1_SPAN_ID"
    Und TGR prüfe Variable "MON_PEP_1_SPAN_ID" stimmt überein mit "[0-9a-f]{16}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_1_PARENT}')].processID" der aktuellen Antwort in der Variable "MON_PEP_1_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_PEP_1_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_PEP_1_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.httpProxy}"

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].traceID" überein mit "${MON_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_RESOURCE_2_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].tags.[?(@.key == 'http.target')].value" überein mit "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].tags.[?(@.key == 'http.method')].value" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].tags.[?(@.key == 'product.id')].value" überein mit "\Q${MON_PRODUCT_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].tags.[?(@.key == 'product.version')].value" überein mit "\Q${MON_PRODUCT_VERSION}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].tags.[?(@.key == 'profession.oid')].value" überein mit "\Q${MON_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].spanID" der aktuellen Antwort in der Variable "MON_PEP_2_SPAN_ID"
    Und TGR ersetze "^([0-9a-f]{16}).*$" mit "$1" im Inhalt der Variable "MON_PEP_2_SPAN_ID"
    Und TGR prüfe Variable "MON_PEP_2_SPAN_ID" stimmt überein mit "[0-9a-f]{16}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].spanID" nicht überein mit "${MON_PEP_1_SPAN_ID}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_2_PARENT}')].processID" der aktuellen Antwort in der Variable "MON_PEP_2_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_PEP_2_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_PEP_2_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.httpProxy}"

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].traceID" überein mit "${MON_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_RESOURCE_3_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].tags.[?(@.key == 'http.target')].value" überein mit "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].tags.[?(@.key == 'http.method')].value" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].tags.[?(@.key == 'product.id')].value" überein mit "\Q${MON_PRODUCT_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].tags.[?(@.key == 'product.version')].value" überein mit "\Q${MON_PRODUCT_VERSION}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].tags.[?(@.key == 'profession.oid')].value" überein mit "\Q${MON_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].spanID" der aktuellen Antwort in der Variable "MON_PEP_3_SPAN_ID"
    Und TGR ersetze "^([0-9a-f]{16}).*$" mit "$1" im Inhalt der Variable "MON_PEP_3_SPAN_ID"
    Und TGR prüfe Variable "MON_PEP_3_SPAN_ID" stimmt überein mit "[0-9a-f]{16}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].spanID" nicht überein mit "${MON_PEP_1_SPAN_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].spanID" nicht überein mit "${MON_PEP_2_SPAN_ID}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_RESOURCE_3_PARENT}')].processID" der aktuellen Antwort in der Variable "MON_PEP_3_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_PEP_3_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_PEP_3_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.httpProxy}"
