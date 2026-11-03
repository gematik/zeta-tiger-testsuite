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

@UseCase_04_07
@require_kubectl
@smb
Funktionalität: Server widerruft Refresh Token mit unabhängigen frischen Besitznachweisen

  Grundlage:
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/discover" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/discover"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.completed" überein mit "true"
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/register" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/register"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.completed" überein mit "true"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_REVOCATION_CLIENT"
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/issue" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/issue"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.completed" überein mit "true"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${CC_REVOCATION_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_REVOCATION_TOKEN_REQUEST"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_REVOCATION_TOKEN_RESPONSE"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_REVOCATION_DISCOVERY"
    Und bereite den SDK-Kontext für Revocation mit Discovery "${CC_REVOCATION_DISCOVERY}", Token-Request "${CC_REVOCATION_TOKEN_REQUEST}" und Token-Antwort "${CC_REVOCATION_TOKEN_RESPONSE}" vor
    Wenn der SDK-Kontext einen Refresh mit seinem aktuellen Refresh Token sendet
    Dann der SDK-Refresh ist erfolgreich und übernimmt das neue Refresh Token

  @A_29997
  @TA_A_29997_04
  @normal
  @Ignore
  Szenariogrundriss: Revocation weist eine fehlerhafte Client Assertion mit HTTP 401 zurück (<Assertion>)
    Gegeben sei TigerProxy bereitet den Widerruf von Token "unknown" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "unknown" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200
    Gegeben sei TigerProxy bereitet den Widerruf von Token "own" mit Client Assertion "<Assertion>" vor
    Wenn der SDK-Kontext das Token "own" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 401 mit soft assert
    Und die direkte Antwort an den SDK-Kontext enthält Fehler "invalid_client" und keine neuen Token
    Wenn der SDK-Kontext einen Refresh mit seinem aktuellen Refresh Token sendet
    Dann der SDK-Refresh ist erfolgreich und übernimmt das neue Refresh Token
    Gegeben sei TigerProxy bereitet den Widerruf von Token "own" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "own" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200

    Beispiele:
      | Assertion       |
      | missing         |
      | wrong_signature |
      | expired         |
      | wrong_client    |

  @A_29999
  @TA_A_29999_01
  @A_30001
  @TA_A_30001_11
  @normal
  @Ignore
  Szenario: Revocation antwortet für gültige, widerrufene, abgelaufene und unbekannte Refresh Token neutral mit HTTP 200
    Gegeben sei TigerProxy bereitet den Widerruf von Token "unknown" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "unknown" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200
    Wenn der SDK-Kontext einen Refresh mit seinem aktuellen Refresh Token sendet
    Dann der SDK-Refresh ist erfolgreich und übernimmt das neue Refresh Token
    Gegeben sei TigerProxy bereitet den Widerruf von Token "unknown" mit Client Assertion "wrong_signature" vor
    Wenn der SDK-Kontext das Token "unknown" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 401 mit soft assert
    Und die direkte Antwort an den SDK-Kontext enthält Fehler "invalid_client" und keine neuen Token
    Und die aktuelle Refresh-Token-Session ist als aktive Widerrufskontrolle gesichert
    Gegeben sei TigerProxy bereitet den Widerruf von Token "own" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "own" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200
    Und die gesicherte Refresh-Token-Session ist durch den eigenen Widerruf beendet

    Gegeben sei TigerProxy bereitet den Widerruf von Token "revoked" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "revoked" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/discover" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/discover"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.completed" überein mit "true"
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/register" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/register"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.completed" überein mit "true"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_EXPIRY_CLIENT"
    Und TGR setze lokale Variable "ccExpiryPolicy" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}' && request.body.getFirst('input').get().getFirst('client_registration_data').get().getFirst('client_id').get().getFirst('content').get().rawStringContent == '${CC_EXPIRY_CLIENT}' && request.body.getFirst('input').get().getFirst('authorization_request').get().getFirst('grant_type').get().getFirst('content').get().rawStringContent == '${oauth_parameters.grant_type.tokenExchange}'"
    Und Setze im TigerProxy für die Nachricht "${ccExpiryPolicy}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "45" und 1 Ausführungen
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/issue" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/issue"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.completed" überein mit "true"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${CC_EXPIRY_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_EXPIRY_TOKEN_REQUEST"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_EXPIRY_TOKEN_RESPONSE"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.client_id" überein mit "${CC_EXPIRY_CLIENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.ttl.refresh_token" überein mit "45"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/jwks" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/jwks"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.completed" überein mit "true"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_EXPIRY_JWKS"
    Und bereite den SDK-Kontext für Revocation mit Discovery "${CC_REVOCATION_DISCOVERY}", Token-Request "${CC_EXPIRY_TOKEN_REQUEST}" und Token-Antwort "${CC_EXPIRY_TOKEN_RESPONSE}" vor
    Und das aktuelle unverbrauchte Refresh Token hat die Laufzeit 45 Sekunden, eine gültige AS-Signatur mit JWKS "${CC_EXPIRY_JWKS}" und eine aktive Session
    Und die lokale Wartezeit für den regulären Ablauf des gesicherten Refresh Tokens ist beendet

    Gegeben sei TigerProxy bereitet den Widerruf von Token "unknown" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "unknown" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200
    Wenn die aktuelle Uhrzeit des unveränderten Authorization Servers gelesen wird
    Dann das gesicherte Refresh Token ist regulär seit mindestens zwei Sekunden abgelaufen
    Gegeben sei TigerProxy bereitet den Widerruf von Token "expired" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "expired" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200
    Gegeben sei TigerProxy bereitet den Widerruf von Token "unknown" mit Client Assertion "wrong_signature" vor
    Wenn der SDK-Kontext das Token "unknown" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 401 mit soft assert
    Und die direkte Antwort an den SDK-Kontext enthält Fehler "invalid_client" und keine neuen Token

  @A_29998
  @TA_A_29998_01
  @TA_A_25661-01_16
  @normal
  @Ignore
  Szenario: Eigenes Refresh Token ist nach erfolgreichem Widerruf unbrauchbar
    Gegeben sei TigerProxy bereitet den Widerruf von Token "own" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "own" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200
    Wenn der SDK-Kontext einen Refresh mit seinem aktuellen Refresh Token sendet
    Dann die direkte Tokenantwort erfüllt HTTP 403, Fehler "refresh_token_revoked", Fehlerschema und Tokenfreiheit

  @A_30000
  @TA_A_30000_01
  @normal
  @Ignore
  Szenario: Widerrufsversuch eines Access Tokens erhält die serverseitige Nutzbarkeit
    Wenn der SDK-Kontext das unveränderte Access Token am geschützten Fachdienst verwendet
    Dann das unveränderte Access Token ist am geschützten Fachdienst weiterhin nutzbar
    Gegeben sei TigerProxy bereitet den Widerruf von Token "access" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "access" widerruft
    Dann der Access-Token-Widerruf liefert HTTP 200 oder HTTP 400 mit unsupported_token_type
    Wenn der SDK-Kontext das unveränderte Access Token am geschützten Fachdienst verwendet
    Dann das unveränderte Access Token ist am geschützten Fachdienst weiterhin nutzbar
    Wenn der SDK-Kontext einen Refresh mit seinem aktuellen Refresh Token sendet
    Dann der SDK-Refresh ist erfolgreich und übernimmt das neue Refresh Token
    Gegeben sei TigerProxy bereitet den Widerruf von Token "own" mit Client Assertion "valid" vor
    Wenn der SDK-Kontext das Token "own" widerruft
    Dann die direkte Antwort an den SDK-Kontext hat HTTP-Status 200
