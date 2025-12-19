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

@UseCase_01_11
Funktionalität: Client_authentisierung_und_autorisierung_refresh_token_without_attest_SC_200

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Gegeben sei Alle Manipulationen im TigerProxy "${paths.tigerProxy.baseUrl}" werden gestoppt
    # TTL-Werte als Variablen definieren (in Sekunden)
    Und TGR setze lokale Variable "accessTokenTtl" auf "5"

  @dev
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_25662_01
  @TA_A_25662_02
  Szenario: Refresh Token Rotation - Token wird nur einmal verwendet und rotiert
    # SCHRITT 1: expires_in Manipulation aktivieren BEVOR der erste HelloZeta Request
    # 3 Ausführungen: Initial Token Exchange + 2 Refreshes
    Wenn TGR setze lokale Variable "condition" auf "isResponse && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${paths.tigerProxy.baseUrl}" für die Nachricht "${condition}" die Manipulation auf Feld "$.body.expires_in" und Wert "${accessTokenTtl}" und 3 Ausführungen

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
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25662_02: Validiere dass grant_type=refresh_token verwendet wurde
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "refresh_token"
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

    # TA_A_25662_01: Validiere dass das rotierte RT tatsächlich verwendbar ist (mehrfache Rotation)
    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

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
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "refresh_token"

    # TA_A_25662_01: Client muss das rotierte RT (secondRefreshToken) verwenden
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${secondRefreshToken}"

    # TA_A_25662_01: Erneute Rotation - drittes RT und AT unterscheiden sich vom zweiten Paar
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "${secondRefreshToken}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token" nicht überein mit "${secondAccessToken}"


  @dev
  @TA_A_25660_04
  @TA_A_25660_05
  @TA_A_25760_03
  Szenario: Session Management - Ausgabe und Verwaltung von Refresh Token
    # expires_in Manipulation aktivieren BEVOR der erste HelloZeta Request
    # 2 Ausführungen: Initial Token Exchange + 1 Refresh
    Wenn TGR setze lokale Variable "condition" auf "isResponse && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${paths.tigerProxy.baseUrl}" für die Nachricht "${condition}" die Manipulation auf Feld "$.body.expires_in" und Wert "${accessTokenTtl}" und 2 Ausführungen

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

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Warte bis Access Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Zweite Anfrage um Token Refresh auszulösen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Following check is a good way to wait that refresh token request is finished
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25660_05: Validiere Verwaltung - neuer Access Token und Refresh Token ausgegeben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

    # TA_A_25660_05: Validiere dass grant_type=refresh_token verwendet wurde
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "refresh_token"
    # TA_A_25660_05: Validiere dass das zuvor ausgegebene Refresh Token verwendet wird
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${firstRefreshToken}"

    # TA_A_25660_05: Verwaltung bedeutet Rotation - neue Tokens müssen unterschiedlich sein
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token" nicht überein mit "${firstAccessToken}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "${firstRefreshToken}"


  @dev
  @TA_A_26945_01
  Szenario: Refresh Token wird ausgegeben
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Refresh Token muss vorhanden sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

    # Validiere Refresh Token Attribute gemäß refresh-token.yaml
    # Required: jti, iss, exp, iat, cnf.jkt
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.jti"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.iss"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.iat"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.cnf.jkt"


  @dev
  @TA_A_25663_01
  @TA_A_25663_02
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
    Wenn TGR setze lokale Variable "condition" auf "isResponse && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${paths.tigerProxy.baseUrl}" für die Nachricht "${condition}" die Manipulation auf Feld "$.body.expires_in" und Wert "${accessTokenTtl}" und 2 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Ersten Token Request abfangen
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # DPoP JWT aus Request Header extrahieren und JKT berechnen
    Und TGR speichere Wert des Knotens "$.header.dpop" der aktuellen Anfrage in der Variable "dpopJwt"
    Und berechne JKT aus DPoP JWT "${dpopJwt}" und speichere in Variable "dpopJktFirstRequest"

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
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "refresh_token"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${boundRefreshToken}"

    # DPoP Header muss auch beim Refresh Request gesendet werden
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop"
    Und TGR speichere Wert des Knotens "$.header.dpop" der aktuellen Anfrage in der Variable "refreshDpopJwt"
    Und verifiziere Signatur von DPoP JWT "${refreshDpopJwt}"
    Und berechne JKT aus DPoP JWT "${refreshDpopJwt}" und speichere in Variable "dpopJktRefreshRequest"

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


  @dev
  @TA_A_25662_02
  Szenario: Refresh Token Reuse wird vom Authorization Server abgelehnt (Negativtest)
    # expires_in Manipulation aktivieren BEVOR der erste HelloZeta Request
    # (3 Ausführungen: Initial Token Exchange + 1. Refresh + 2. Refresh, alle mit manipuliertem expires_in)
    Wenn TGR setze lokale Variable "condition" auf "isResponse && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${paths.tigerProxy.baseUrl}" für die Nachricht "${condition}" die Manipulation auf Feld "$.body.expires_in" und Wert "${accessTokenTtl}" und 3 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Refresh Token aus dem Token Exchange speichern
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "usedRefreshToken"

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Warte bis Access Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Ersten Refresh durchführen (verwendet usedRefreshToken)
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Verifiziere dass der Refresh mit dem gespeicherten Token erfolgte (jetzt einziger Token Request)
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${usedRefreshToken}"

    # Versuche das bereits verwendete Refresh Token nochmal zu verwenden
    # Manipuliere den Request, um das alte Refresh Token zurückzuspielen
    # Hinweis: Token Request Body ist application/x-www-form-urlencoded (Form-Data), nicht JSON
    # TigerProxy hat keinen RbelHttpFormDataWriter, daher muss Regex auf $.body verwendet werden
    Wenn TGR setze lokale Variable "replayCondition" auf "isRequest && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${paths.tigerProxy.baseUrl}" für die Nachricht "${replayCondition}" die Regex-Manipulation auf Feld "$.body" mit Regex "refresh_token=[^&]*" und Wert "refresh_token=${usedRefreshToken}"

    Und TGR lösche aufgezeichnete Nachrichten

    # Zweiter Refresh-Versuch sollte fehlschlagen
    Und warte "${accessTokenTtl}" Sekunden
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"

    # Finde den Refresh Token Request (nicht den Token Exchange der danach folgt)
    # Wir suchen explizit nach dem Request mit grant_type=refresh_token
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${usedRefreshToken}"

    # Erwarte invalid_grant
    # Grund: Refresh Token wurde bereits verwendet (Rotation-Verletzung)
    # Die Response zu diesem Request sollte 400 mit invalid_grant sein
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.error"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "invalid_grant"
    # Zusätzliche Schema-Validierung
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "errorBody"
    Und validiere "${errorBody}" gegen Schema "schemas/v_1_0/zeta-error.yaml"


  @dev
  @TA_A_25663_02
  Szenario: Refresh Token Binding - Refresh mit anderem DPoP Key scheitert (Negativtest)
    # TA_A_25663_02: Dieser Test verifiziert die DPoP Refresh Token Binding Anforderung
    # Testszenario: Angreifer stiehlt Refresh Token, hat aber eigenen (anderen) DPoP Key
    # Erwartetes Verhalten: Token-Endpoint MUSS mit 401 Unauthorized antworten

    # SCHRITT 1: Erste Session erstellen (Attacker-Session) und DPoP-Key speichern
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Attacker-Key aus Storage holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "attackerDpopKey"

    # expires_in auf 5 Sekunden setzen um Refresh zu erzwingen (nur 1 Ausführung für Victim-Session)
    Wenn TGR setze lokale Variable "condition" auf "isResponse && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${paths.tigerProxy.baseUrl}" für die Nachricht "${condition}" die Manipulation auf Feld "$.body.expires_in" und Wert "${accessTokenTtl}" und 1 Ausführungen

    # SCHRITT 2: Neue Session erstellen (Victim-Session mit neuem DPoP Key)
    # Der Refresh Token dieser Session ist an den NEUEN DPoP Key gebunden (via jkt Claim)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # SCHRITT 3: Manipulation aktivieren - DPoP-Proof mit ATTACKER-Key signieren und JWK ersetzen
    # RFC 9449: "such a client MUST present a DPoP proof for the same key that was used to
    #           obtain the refresh token each time that refresh token is used"
    # Dieser Test verletzt diese Anforderung absichtlich mit einem anderen Key
    Wenn TGR setze lokale Variable "dpopCondition" auf "isRequest && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${paths.tigerProxy.baseUrl}" für JWT in "$.header.dpop" das Feld "body.jti" auf Wert "attacker-jti" mit privatem Schlüssel "${attackerDpopKey}" für Pfad "${dpopCondition}" und 1 Ausführungen und ersetze JWK

    Und TGR lösche aufgezeichnete Nachrichten

    # Warte auf Token Expiry und Trigger Refresh
    Und warte "${accessTokenTtl}" Sekunden
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist (sollte fehlschlagen)
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"

    # VERIFIZIERUNG: Token-Endpoint MUSS Refresh mit falschem DPoP-Key ablehnen
    # Erwarteter Fehler: 401 Unauthorized (DPoP Key Thumbprint != jkt im gebundenen Refresh Token)
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

