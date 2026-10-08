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

@UseCase_01_11
Funktionalität: Client Authentisierung und Autorisierung Refresh Token without Attest SC 200

  Grundlage:
    # TTL-Werte als Variablen definieren (in Sekunden)
    Und TGR setze lokale Variable "accessTokenTtl" auf "5"

  @A_25660
  @A_25662
  @A_25782
  @A_26281-01
  @A_27725-01
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_25662_01
  @TA_A_25662_02
  @TA_A_25782_05
  @TA_A_26281-01_01
  @TA_A_27725-01_14
  @normal
  @MASVS-AUTH
  @MASVS-CRYPTO
  @MASVS-RESILIENCE
  Szenario: Refresh Token Rotation - Token wird nur einmal verwendet und rotiert
    Gegeben sei TGR sende eine leere "GET" Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "KEY_STORE"
    # SCHRITT 1: expires_in Manipulation aktivieren BEVOR der erste HelloZeta Request
    # 3 Ausführungen: Initial Token Exchange + 2 Refreshes
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 3 Ausführungen

    # Setup: Client zurücksetzen und ersten Access Token holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Ersten Token Request abfangen und Refresh Token speichern (vom Token Exchange)
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "firstRefreshToken"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "firstAccessToken"

    Und verifiziere die ES256 Signatur des JWT "${firstAccessToken}" mit KeyStore "${KEY_STORE}"
    Und verifiziere die ES256 Signatur des JWT "${firstRefreshToken}" mit KeyStore "${KEY_STORE}"

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Warte bis Access Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Zweite Anfrage um Token Refresh auszulösen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Following check is a good way to wait that refresh token request is finished
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Zweiten Token Request abfangen - sollte via grant_type=refresh_token erfolgen (jetzt einziger Token Request)
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25662_02: Validiere dass grant_type=refresh_token verwendet wurde
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.refresh_token"
    # TA_A_25662_01: Validiere dass das zuvor ausgegebene Refresh Token verwendet wird
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${firstRefreshToken}"

    # TA_A_25662_01: Validiere Refresh Token Rotation - neues Token muss unterschiedlich sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "secondRefreshToken"
    # TA_A_25662_01: Rotation erfolgt - neues RT ist unterschiedlich
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "${firstRefreshToken}"

    # TA_A_25662_01: Auch Access Token muss rotiert werden
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token" nicht überein mit "${firstAccessToken}"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "secondAccessToken"
    Und verifiziere die ES256 Signatur des JWT "${secondRefreshToken}" mit KeyStore "${KEY_STORE}"
    Und verifiziere die ES256 Signatur des JWT "${secondAccessToken}" mit KeyStore "${KEY_STORE}"

    # TA_A_25662_01: Validiere dass das rotierte RT tatsächlich verwendbar ist (mehrfache Rotation)
    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    # Warte bis zweiter Access Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Dritte Anfrage um erneuten Token Refresh auszulösen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Following check is a good way to wait that refresh token request is finished
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Dritter Token Request - Client sollte das zweite (rotierte) RT verwenden (jetzt einziger Token Request)
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"

    # TA_A_25662_01: Client muss das rotierte RT (secondRefreshToken) verwenden
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${secondRefreshToken}"

    # TA_A_25662_01: Erneute Rotation - drittes RT und AT unterscheiden sich vom zweiten Paar
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "${secondRefreshToken}"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "thirdRefreshToken"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token" nicht überein mit "${secondAccessToken}"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "thirdAccessToken"
    Und verifiziere die ES256 Signatur des JWT "${thirdRefreshToken}" mit KeyStore "${KEY_STORE}"
    Und verifiziere die ES256 Signatur des JWT "${thirdAccessToken}" mit KeyStore "${KEY_STORE}"

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"200","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"


  @A_25660
  @A_25760
  @A_25782
  @A_26586
  @A_26587-01
  @A_26972-02
  @A_28837
  @TA_A_26586_02
  @TA_A_26587-01_01
  @TA_A_25660_04
  @TA_A_25660_05
  @TA_A_25760_03
  @TA_A_25782_04
  @TA_A_26972-02_01
  @TA_A_26972-02_02
  @TA_A_26972-02_03
  @TA_A_26972-02_04
  @TA_A_28837_01
  @MASVS-AUTH
  Szenario: Session Management - Ausgabe und Verwaltung von Refresh Token
    # expires_in Manipulation aktivieren BEVOR der erste HelloZeta Request
    # 2 Ausführungen: Initial Token Exchange + 1 Refresh
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 2 Ausführungen

    # Setup: Client zurücksetzen und Token holen (mit expires_in durch Manipulation)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25660_04: Validiere Ausgabe von Access Token und Refresh Token
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.expires_in"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_expires_in"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "firstAccessToken"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "firstRefreshToken"
    # TA_A_26587-01_01: PDP Datenbank - Kompatibilität zum Authorization Server über persistente Session-, Nutzer- und Client-Daten
    Und decodiere und validiere "${firstAccessToken}" gegen Schema "schemas/v_1_0/access-token.yaml"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.sid"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.sid"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "sessionId"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "${sessionId}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.sub"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sub" der aktuellen Antwort in der Variable "subject"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.client_id"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.client_id" der aktuellen Antwort in der Variable "clientId"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.jti"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "accessTokenJti1"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.jti" der aktuellen Antwort in der Variable "refreshTokenJti1"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "SUBJECT_TOKEN"
    Und decodiere und validiere "${SUBJECT_TOKEN}" gegen Schema "schemas/v_1_0/subject-token-smb.yaml"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"

    # Warte bis Access Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Zweite Anfrage um Token Refresh auszulösen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Following check is a good way to wait that refresh token request is finished
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25660_05: Validiere Verwaltung - neuer Access Token und Refresh Token ausgegeben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

    # TA_A_25660_05: Validiere dass grant_type=refresh_token verwendet wurde
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"
    # TA_A_25660_05: Validiere dass das zuvor ausgegebene Refresh Token verwendet wird
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${firstRefreshToken}"
    # TA_A_26972-02: Refresh ohne erneutes Subject-Token (Persistenz-Indiz)
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.subject_token"
    # TA_A_26587-01_01: Validiere kompatible Fortführung der Session-Daten und Rotation der Token-IDs nach Refresh
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" überein mit "${sessionId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "${sessionId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "${subject}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "${clientId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${accessTokenJti1}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.jti" nicht überein mit "${refreshTokenJti1}"

    # TA_A_25660_05: Verwaltung bedeutet Rotation - neue Tokens müssen unterschiedlich sein
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token" nicht überein mit "${firstAccessToken}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "${firstRefreshToken}"

    # TA_A_26586_02: Nutzer-Daten im Policy-Engine-Request
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.input.user_info"
    Und TGR speichere Wert des Knotens "$.body.input.user_info" der aktuellen Anfrage in der Variable "OPA_USER_INFO"
    Und validiere "${OPA_USER_INFO}" gegen Schema "schemas/v_1_0/user-info.yaml"
    # TA_A_26972-02_01 identifier
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.identifier" überein mit "${SMCB-INFO.telematikId}"
    # TA_A_26972-02_03 professionOID
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "${SMCB-INFO.professionId}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    # TA_A_26972-02_02 commonName
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.common_name}" überein mit "${SMCB-INFO.commonName}"
    # TA_A_26972-02_04 optional organizationName
    Und prüfe optional: Knoten "${headers.authorization.dpopToken.body.organization_name}" fehlt wenn "${SMCB-INFO.organizationName}" leer ist, sonst gleich und nutze soft assert

  @A_25663
  @A_25766
  @TA_A_25663_01
  @TA_A_25663_02
  @TA_A_25766_02
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: DPoP Token Binding für Access und Refresh Token
    # TA_A_25663_01 + TA_A_25663_02: Token-Binding an Client-Registrierung für AT und RT
    #
    # TA_A_25663_01 Validierung (Access Token Binding):
    # - Access Token enthält cnf.jkt mit dem JKT des DPoP Public Keys
    #
    # TA_A_25663_02 Validierung (Refresh Token Binding):
    # 1. Refresh Token enthält cnf.jkt (expliziter Claim im RT-JWT)
    # 2. Refresh mit gleichem DPoP Key ist erfolgreich
    # 3. Neuer Access Token ist wieder an gleichen DPoP Key gebunden (transitiver Nachweis des RT-Bindings)
    # 4. Siehe auch Negativtest: Refresh mit anderem DPoP Key → 401

    # expires_in manipulieren um Refresh zu erzwingen (2 Ausführungen: Initial + Refresh)
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 2 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Ersten Token Request abfangen
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # DPoP JWT aus Request Header extrahieren und JKT berechnen
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "dpopJwt"
    Und berechne JKT aus JWT Header JWK "${dpopJwt}" und speichere in Variable "dpopJktFirstRequest"

    # TA_A_25663_01: Access Token ist an DPoP Key gebunden (cnf.jkt)
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "${dpopJktFirstRequest}"

    # TA_A_25663_02: Refresh Token ist ebenfalls an DPoP Key gebunden (cnf.jkt)
    # cnf.jkt muss vorhanden sein und mit dem DPoP Key übereinstimmen
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.cnf.jkt" überein mit "${dpopJktFirstRequest}"

    # Refresh Token speichern
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "boundRefreshToken"

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Token Refresh auslösen
    Und warte "${accessTokenTtl}" Sekunden
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Following check is a good way to wait that refresh token request is finished
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Refresh Token Request validieren (jetzt einziger Token Request nach dem Löschen)
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${boundRefreshToken}"

    # DPoP Header muss auch beim Refresh Request gesendet werden
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "refreshDpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${refreshDpopJwt}"
    Und berechne JKT aus JWT Header JWK "${refreshDpopJwt}" und speichere in Variable "dpopJktRefreshRequest"

    # Refresh-Request selbst verwendet denselben DPoP Key
    # Expliziter Nachweis: JKT aus Refresh-Request DPoP Header == JKT aus Initial-Request
    # dpopJktRefreshRequest stammt aus dem aktuellen Refresh-Request DPoP Header
    # dpopJktFirstRequest stammt aus dem Initial-Request DPoP Header
    # Da wir keinen direkten Variablen-Vergleich haben, nutzen wir den Access Token als Proxy:
    # Wenn der neue Access Token cnf.jkt == dpopJktRefreshRequest hat, dann ist der Refresh mit diesem Key erfolgt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "${dpopJktRefreshRequest}"

    # Neuer Access Token ist an denselben DPoP Key gebunden wie der erste
    # Dies beweist indirekt: dpopJktRefreshRequest == dpopJktFirstRequest (transitiv über cnf.jkt)
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "${dpopJktFirstRequest}"

    # TA_A_25663_02: Auch der neue (rotierte) Refresh Token muss an den gleichen DPoP Key gebunden sein
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.cnf.jkt" überein mit "${dpopJktRefreshRequest}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.cnf.jkt" überein mit "${dpopJktFirstRequest}"


  @A_25660
  @A_27867-01
  @TA_A_25660_02
  @TA_A_27867-01_01
  @normal
  @MASVS-AUTH
  Szenario: Session-Daten werden verwaltet (indirekter Nachweis)
    # expires_in Manipulation aktivieren BEVOR der erste HelloZeta Request
    # 2 Ausführungen: Initial Token Exchange + 1 Refresh
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 2 Ausführungen

    # Setup: Client zurücksetzen und Token holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Token Response finden
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Session-ID wird vergeben (sid in AT und RT gleich)
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.sid"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.sid"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "sessionId"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "${sessionId}"

    # subject und client_id vorhanden (gemäß access-token.yaml erforderlich)
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.sub"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sub" der aktuellen Antwort in der Variable "subject"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.client_id"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.client_id" der aktuellen Antwort in der Variable "clientId"

    # session_expiry vorhanden (exp im Refresh Token)
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"

    # jti vorhanden (AT/RT) für Session-Verknüpfung
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.jti"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.jti" der aktuellen Antwort in der Variable "accessTokenJti1"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.jti"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.jti" der aktuellen Antwort in der Variable "refreshTokenJti1"

    # Tokens für Vergleich speichern
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "accessToken1"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "refreshToken1"

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Warte bis Access Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Refresh auslösen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Refresh-Request finden
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Session bleibt gleich, Tokens ändern sich
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" überein mit "${sessionId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "${subject}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "${clientId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.jti" nicht überein mit "${accessTokenJti1}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.jti" nicht überein mit "${refreshTokenJti1}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token" nicht überein mit "${accessToken1}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "${refreshToken1}"
