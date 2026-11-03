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

@UseCase_04_10
Funktionalität: Geforderte Scopes nach einer PEP-Step-Up-Challenge übernehmen

  @A_29859
  @TA_A_29859_05
  @TA_A_29859_07
  @normal
  @MASVS-CODE
  @reset_testdriver
  @oidc
  Szenariogrundriss: Das SDK übernimmt <Scopes> nach <Fehler> in seinen Refresh-Request
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_STEP_UP_KVNR"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.sub" der aktuellen Anfrage in der Variable "CC_STEP_UP_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_STEP_UP_INITIAL_AT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "${CC_STEP_UP_CLIENT}"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "CC_STEP_UP_EXP"
    Und validiere, dass der Zeitstempel "!{${CC_STEP_UP_EXP} - 60}" in der Zukunft liegt
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token.body.scope.[?('<Scopes>'.split(' ') =~ content.split(' '))]"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_STEP_UP_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${CC_STEP_UP_INITIAL_AT}" mit KeyStore "${CC_STEP_UP_JWKS}"
    Und das Access Token "${CC_STEP_UP_INITIAL_AT}" enthält genau die Scopes "${testdata.ccPolicy.oidcGrantedScopes}"

    Und TGR setze lokale Variable "CC_STEP_UP_HEADER" auf 'DPoP error="<Fehler>", scope="<Scopes>"'
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "CC_STEP_UP_CONDITION" auf "isResponse && request.path == '${paths.guard.helloZetaPath}' && (request.headers['Authorization'] ?: request.headers['authorization'])[0].replaceFirst('(?i)^DPoP ', 'DPoP ') == 'DPoP ${CC_STEP_UP_INITIAL_AT}'"
    Dann Setze im TigerProxy für die Nachricht "${CC_STEP_UP_CONDITION}" die Manipulation auf Feld "$.responseCode" und Wert "401" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${CC_STEP_UP_CONDITION}" die Manipulation auf Feld "${headers.zeta.errorOrigin.strict}" und Wert "pep" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${CC_STEP_UP_CONDITION}" die Manipulation auf Feld "$.header.['WWW-Authenticate']" und Wert "${CC_STEP_UP_HEADER}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${CC_STEP_UP_CONDITION}" die Manipulation auf Feld "$.header.['Content-Type']" und Wert "application/json" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${CC_STEP_UP_CONDITION}" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      {
        "error": "<Fehler>",
        "error_description": "The resource requires additional authorization"
      }
      """
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "$.method" der mit "GET" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${CC_STEP_UP_INITIAL_AT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.strict}" überein mit "pep"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.[~'www-authenticate']" überein mit "\Q${CC_STEP_UP_HEADER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "<Fehler>"
    Und TGR setze lokale Variable "CC_STEP_UP_REQUEST_ID" auf "!{rbel:currentRequest('$').uuid}"
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_assertion.body.sub" der mit "${CC_STEP_UP_CLIENT}" übereinstimmt
    Und prüfe, dass die aktuelle Anfrage nach der Antwort auf Anfrage "${CC_STEP_UP_REQUEST_ID}" gesendet wurde
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.client_id.[?(content != '${CC_STEP_UP_CLIENT}')]"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "${CC_STEP_UP_CLIENT}"
    Und TGR setze lokale Variable "CC_STEP_UP_REQUESTED_SCOPES" auf "!{rbel:currentRequest('$' + '.body.scope').rawStringContent}"
    Und TGR setze lokale Variable "CC_STEP_UP_DECODED_SCOPES" auf "!{new('org.apache.commons.codec.net.URLCodec').decode(getValue('CC_STEP_UP_REQUESTED_SCOPES'))}"
    Und TGR prüfe Variable "CC_STEP_UP_DECODED_SCOPES" stimmt überein mit "[\x20\x21\x23-\x5B\x5D-\x7E]+"
    Und TGR setze lokale Variable "CC_STEP_UP_SCOPES_PRESENT" auf "!{'<Scopes>'.split(' ') =~ getValue('CC_STEP_UP_DECODED_SCOPES').split(' ')}"
    Und TGR prüfe Variable "CC_STEP_UP_SCOPES_PRESENT" stimmt überein mit "true"

    Beispiele: insufficient_scope als Step-Up erkennen
      | Fehler             | Scopes                                |
      | insufficient_scope | notification.pusher.read              |
      | insufficient_scope | zero:audience notification.pusher.read |
