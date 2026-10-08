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
  @MASVS-NETWORK
  Szenario: Ingress und PEP HTTP Proxy unterstützt WebSocket Verbindung
    # Ein WebSocket-Roundtrip sollte hier reichen, allerdings könnte man auch noch die Verbindung
      # Ingress <-> PEP HTTP Proxy über den Standalone Tiger Proxy testen.
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Dann wird die WebSocket Verbindung geschlossen

  @A_25660
  @A_25766
  @A_25767
  @A_26492-02
  @A_28963
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_25766_02
  @TA_A_25767_02
  @TA_A_26492-02_02
  @TA_A_28963_01
  @normal
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
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"

    # Resource Request Validierung
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP .*"

    # DPoP JWT Validierung
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "resourceDpopJwt"
    Und decodiere und validiere "${resourceDpopJwt}" gegen Schema "schemas/v_1_0/dpop-token.yaml"
    Und verifiziere ES256 Signatur von DPoP JWT "${resourceDpopJwt}"

    # DPoP Header Validierung
    # @TA_A_28963_01 - typ muss "dpop+jwt" sein
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

    # TA_A_26492-02_02 - PEP HTTP Proxy - Weiterleitung von Client-Daten - Defaulteinstellung
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.clientData.root}"

    # Nonce wird bei Resource Requests nicht mitgeschickt

  @A_25783-01
  @TA_A_25783-01_01
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
    Und Setze im TigerProxy für die Nachricht "${resourceUnauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<Statuscode>" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"

    # TA_A_25783-01_01 - bei 401/403 vom PEP HTTP Proxy wird per Refresh Token oder vollständiger Authentifizierung ein neues Access Token bezogen.
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}|${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${initialAccessTokenJti}"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "newAccessTokenJti"

    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.jti" überein mit "${newAccessTokenJti}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Beispiele:
      | Statuscode |
      | 401        |
      | 403        |

  # Optionaler Zweig aus §5.8.6: Der Client kann vor der vollständigen Authentifizierung Refresh versuchen.
  @TA_A_25783-01_01
  Szenariogrundriss: Client authentifiziert sich vollständig nach fehlgeschlagenem optionalem Refresh infolge <Statuscode> des PEP HTTP Proxy
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "initialAccessTokenJti"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "initialRefreshToken"

    Und TGR lösche aufgezeichnete Nachrichten

    Und TGR setze lokale Variable "resourceUnauthorizedCondition" auf "isResponse && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und TGR setze lokale Variable "refreshUnauthorizedCondition" auf "isResponse && request.path =~ '.*${paths.guard.tokenEndpointPath}' && request.body.grant_type =~ '.*${oauth_parameters.grant_type.refreshToken}'"
    Und Setze im TigerProxy für die Nachricht "${resourceUnauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<Statuscode>" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${refreshUnauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<RefreshStatuscode>" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"

    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${initialRefreshToken}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<RefreshStatuscode>"

    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${initialAccessTokenJti}"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "newAccessTokenJti"

    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.jti" überein mit "${newAccessTokenJti}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    Beispiele:
      | Statuscode | RefreshStatuscode |
      | 401        | 401               |
      | 401        | 403               |
      | 403        | 401               |
      | 403        | 403               |

  @A_25783-01
  @TA_A_25783-01_01
  Szenariogrundriss: Client bricht nach erneutem <Statuscode> des PEP HTTP Proxy ohne weitere Ressourcenanfrage ab
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten

    Und TGR setze lokale Variable "resourceErrorCondition" auf "isResponse && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${resourceErrorCondition}" die Manipulation auf Feld "$.responseCode" und Wert "<Statuscode>" und 2 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${resourceErrorCondition}" die Manipulation auf Feld "$.body" und Wert "!{file('<FehlerBody>')}" und 2 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${resourceErrorCondition}" die Manipulation auf Feld "$.header.['WWW-Authenticate']" und Wert "DPoP" und 2 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "errorBody"
    Und validiere "${errorBody}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"

    # TA_A_25783-01_01 - der Client führt die erlaubte Step-Up- oder Re-Authentifizierung einmal aus.
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "newAccessTokenJti"

    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.jti" überein mit "${newAccessTokenJti}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Statuscode>"

    # TA_A_25783-01_01 - nach dem fehlgeschlagenen Retry wird abgebrochen und keine dritte Ressourcenanfrage gesendet.
    Dann prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben

    Beispiele:
      | Statuscode | FehlerBody                                                 |
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

  @A_26561
  @TA_A_26561_01
  Szenario: PEP HTTP Proxy nutzt gecachte Response-Inhalte
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

  @A_25669-01
  @TA_A_25669-01_08
  @TA_A_25669-01_09
  @deployment_modification
  @popp_deployment_toggle
  @MASVS-AUTH
  Szenario: PEP fügt keinen zeta-popp-token-content ein, wenn kein PoPP-Header mitgeschickt wurde, und entfernt ursprünglich gleichnamige Header
    Gegeben sei deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppHeaderCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    # popp Header löschen
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "${headers.popp.lineRegex}" und Wert ""
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
  @MASVS-RESILIENCE
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
    Und warte 70 Sekunden
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
