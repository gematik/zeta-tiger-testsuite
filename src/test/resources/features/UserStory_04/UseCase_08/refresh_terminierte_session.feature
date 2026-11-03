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

@UseCase_04_08
@require_kubectl
@oidc
@additional_sdk_instance
@controlled_session_refresh
Funktionalität: Einen Refresh nach gezielter Session-Terminierung ablehnen

  @A_29848
  @TA_A_29848_02
  @A_25661-01
  @TA_A_25661-01_11
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @Ignore
  Szenario: Nur die beendete Session verliert ihre Refresh-Berechtigung
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_SESSION_OWNER"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.client_id" der aktuellen Antwort in der Variable "CC_SESSION_TARGET_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_SESSION_INITIAL_RT"

    # Der erste echte SDK-Refresh belegt dieselben Nachweise und den aktiven Ausgangsgrant.
    # Nur die Ressourcen-Antwort erhält eine kontrollierte 401; AS-Requests/-Antworten bleiben unverändert.
    Gegeben sei die Ressourcen-URL "${paths.client.oidc.helloZeta}" gehört zum konfigurierten SDK-Testdriver
    Und TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}${paths.tigerProxy.modificationPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "name": "sdk-refresh-trigger",
        "condition": "isResponse && request.method == 'GET' && request.path == '${paths.guard.helloZetaPath}'",
        "targetElement": "$",
        "replaceWith": "HTTP/1.1 401 Unauthorized\r\nzeta-error-origin: pep\r\nWWW-Authenticate: DPoP error=\"invalid_token\"\r\nContent-Type: application/json\r\nContent-Length: 74\r\n\r\n{\"error\":\"invalid_token\",\"error_description\":\"Controlled refresh trigger\"}",
        "deleteAfterNExecutions": 1
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.tigerProxy.modificationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "\Q${CC_SESSION_INITIAL_RT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${CC_SESSION_TARGET_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_SESSION_TARGET_AT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_SESSION_TARGET_RT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "\Q${CC_SESSION_INITIAL_RT}\E"
    Und der erfolgreiche SDK-Refresh wird mit registriertem Schlüssel und Access Token "${CC_SESSION_TARGET_AT}" als Session-Kontrolle gesichert

    # C2 besitzt einen eigenen SDK-Speicher und eine eigene Session derselben synthetischen Identität.
    Gegeben sei eine zusätzliche unabhängige SDK-Instanz für KVNR "${CC_SESSION_OWNER}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${CC_SECOND_SDK_PATH}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^\Q${CC_SECOND_SDK_PATH}\E$" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_SECOND_SDK_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_SESSION_CONTROL_AT"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.client_id" der aktuellen Antwort in der Variable "CC_SESSION_CONTROL_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "CC_SESSION_CONTROL_SID"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_SESSION_CONTROL_RT"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_SESSION_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${CC_SESSION_TARGET_AT}" mit KeyStore "${CC_SESSION_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${CC_SESSION_CONTROL_AT}" mit KeyStore "${CC_SESSION_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${CC_SESSION_TARGET_RT}" mit KeyStore "${CC_SESSION_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${CC_SESSION_CONTROL_RT}" mit KeyStore "${CC_SESSION_JWKS}"
    Gegeben sei bereite die Session-Terminierung mit Access Token "${CC_SESSION_TARGET_AT}", Refresh Token "${CC_SESSION_TARGET_RT}", Kontrolltoken "${CC_SESSION_CONTROL_AT}" und KVNR "${CC_SESSION_OWNER}" vor

    # Das SDK bietet keine administrative Fremdterminierung. Keycloak beendet daher genau die
    # unabhängig zugeordnete Testsitzung; die Testsuite liest Admin-API und committed DB zurück.
    # Weder /revoke noch Client-/Nutzerlöschung stehen für die Session-Beendigung stellvertretend.
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn nur die vorbereitete Zielsession administrativ beendet wird
    Dann die Zielsession bleibt terminiert und die unabhängige Kontrollsession bleibt aktiv
    Gegeben sei die Ressourcen-URL "${paths.client.oidc.helloZeta}" gehört zum konfigurierten SDK-Testdriver
    Und TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}${paths.tigerProxy.modificationPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "name": "sdk-refresh-trigger",
        "condition": "isResponse && request.method == 'GET' && request.path == '${paths.guard.helloZetaPath}'",
        "targetElement": "$",
        "replaceWith": "HTTP/1.1 401 Unauthorized\r\nzeta-error-origin: pep\r\nWWW-Authenticate: DPoP error=\"invalid_token\"\r\nContent-Type: application/json\r\nContent-Length: 74\r\n\r\n{\"error\":\"invalid_token\",\"error_description\":\"Controlled refresh trigger\"}",
        "deleteAfterNExecutions": 1
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.tigerProxy.modificationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "\Q${CC_SESSION_TARGET_RT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${CC_SESSION_TARGET_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "4[0-9][0-9]"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_SESSION_REJECTION"
    Und die Session-Refresh-Antwort "${CC_SESSION_REJECTION}" verweigert neue Token für das gültige Refresh Token "${CC_SESSION_TARGET_RT}"
    Und alle SDK-Refresh-Versuche der beendeten Zielsession besitzen unveränderte gültige Nachweise und werden gesichert
    Und die Zielsession bleibt terminiert und die unabhängige Kontrollsession bleibt aktiv

    # Auch nach der negativen Probe kann C2 mit seinem neuesten, unbenutzten RT refreshen.
    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei die Ressourcen-URL "${paths.client.baseUrl}${CC_SECOND_SDK_PATH}" gehört zum konfigurierten SDK-Testdriver
    Und TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}${paths.tigerProxy.modificationPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "name": "sdk-refresh-trigger",
        "condition": "isResponse && request.method == 'GET' && request.path == '${paths.guard.helloZetaPath}'",
        "targetElement": "$",
        "replaceWith": "HTTP/1.1 401 Unauthorized\r\nzeta-error-origin: pep\r\nWWW-Authenticate: DPoP error=\"invalid_token\"\r\nContent-Type: application/json\r\nContent-Length: 74\r\n\r\n{\"error\":\"invalid_token\",\"error_description\":\"Controlled refresh trigger\"}",
        "deleteAfterNExecutions": 1
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.tigerProxy.modificationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${CC_SECOND_SDK_PATH}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "\Q${CC_SESSION_CONTROL_RT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${CC_SESSION_CONTROL_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" überein mit "\Q${CC_SESSION_CONTROL_SID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "\Q${CC_SESSION_CONTROL_RT}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_SESSION_CONTROL_NEW_AT"
    Und verifiziere die ES256 Signatur des JWT "${CC_SESSION_CONTROL_NEW_AT}" mit KeyStore "${CC_SESSION_JWKS}"
    Und die Zielsession bleibt terminiert und die unabhängige Kontrollsession bleibt aktiv
    Und alle gesicherten Antworten der beendeten Session erfüllen HTTP 403, session_terminated, Fehlerschema und Tokenfreiheit
