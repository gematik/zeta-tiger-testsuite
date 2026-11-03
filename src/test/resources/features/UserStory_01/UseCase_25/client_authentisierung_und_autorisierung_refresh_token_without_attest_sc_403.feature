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

@UseCase_01_25
Funktionalität: Client Authentisierung und Autorisierung Refresh Token without Attest SC 403


  @A_25660
  @A_27725-01
  @TA_A_25660_06
  @TA_A_27725-01_17
  @MASVS-AUTH
  Szenario: Refresh Token wird bei negativer Policy Decision abgelehnt (Negativtest)
    Gegeben sei TGR setze lokale Variable "accessTokenTtl" auf "5"
    # WICHTIG: expires_in Manipulation MUSS VOR dem ersten Token-Request aktiviert werden!
    # Sonst hat der erste Token normales expires_in (z.B. 300s) und wird nicht refresht
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen

    # Initiale Token holen (mit manipuliertem expires_in)
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR lösche aufgezeichnete Nachrichten

    # OPA Response manipulieren: allow=false simuliert serverseitige Token-Invalidierung/Session-Entzug
    # Diese Manipulation greift erst beim Refresh-Request
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 1 Ausführungen

    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    # Warten bis Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Neuer Request löst Refresh aus, OPA lehnt ab
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Erwarte 403 wegen Policy Engine Ablehnung (Session-Entzug)
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.error"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"403","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

  @A_29849
  @TA_A_29849_01
  @TA_A_29849_02
  Szenariogrundriss: Client authentifiziert nach terminierter Session ohne weiteren Refresh erneut (<terminationError>)
    Gegeben sei TGR setze lokale Variable "accessTokenTtl" auf "5"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "300"
    Und TGR setze lokale Variable "accessTokenExpiryWait" auf "!{${accessTokenTtl} + 2}"
    Und TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "initialRefreshToken"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "initialSessionExpiry"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "initialAccessTokenExpiry"

    Und warte "${accessTokenExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${initialAccessTokenExpiry}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${initialSessionExpiry}" in der Zukunft liegt
    Und TGR lösche aufgezeichnete Nachrichten

    Und TGR setze lokale Variable "rejectedRefreshCondition" auf "isResponse && request.path == '${paths.guard.tokenEndpointPath}' && 'grant_type=${oauth_parameters.grant_type.refreshToken}' =~ request.bodyAsString.split('&')"
    Und Setze im TigerProxy für die Nachricht "${rejectedRefreshCondition}" die Manipulation auf Feld "$.responseCode" und Wert "403" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${rejectedRefreshCondition}" die Manipulation auf Feld "$.body" und Wert "!{file('src/test/resources/mocks/<errorBody>')}" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "^\Q${initialRefreshToken}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "<terminationError>"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"

    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Beispiele:
      | terminationError      | errorBody                               |
      | session_terminated    | zeta-error-403-session-terminated.json    |
      | refresh_token_revoked | zeta-error-403-refresh-token-revoked.json |
