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
Funktionalität: Scope beim Refresh für dieselbe Resource erweitern

  @A_29843
  @TA_A_29843_03
  @normal
  @require_kubectl
  @oidc
  @Ignore
  Szenario: Ein Refresh gewährt den erlaubten zusätzlichen Scope ohne neue Anmeldung
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_REFRESH_KVNR"
    # Zwei kurze, vom AS signierte Kontroll-Grants lösen danach echte SDK-Refreshes aus.
    Und TGR setze lokale Variable "CC_REFRESH_TTL_CONDITION" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${CC_REFRESH_TTL_CONDITION}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "5" und 2 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.sub" der aktuellen Anfrage in der Variable "CC_REFRESH_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_REFRESH_INITIAL_AT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_REFRESH_INITIAL_RT"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_REFRESH_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${CC_REFRESH_INITIAL_AT}" mit KeyStore "${CC_REFRESH_JWKS}"
    Und das Access Token "${CC_REFRESH_INITIAL_AT}" enthält genau die Scopes "${testdata.ccPolicy.oidcGrantedScopes}"
    # Ab hier zählt ausschließlich die Refresh-Phase; der OIDC-Bootstrap enthält zusätzlich einen Code-Austausch.
    Und TGR lösche aufgezeichnete Nachrichten

    # Das SDK sendet audience; Tiger ersetzt es durch resource für denselben R1/S1-Kontrollgrant.
    # Ein bereits resource-basierter SDK-Request bleibt unverändert; beide Parameter zusammen sind unzulässig.
    # Aufgelöste Zwischenvariablen verhindern verschachtelte URL-Encoding-Ausdrücke.
    Und TGR setze lokale Variable "CC_REFRESH_RESOURCE" auf "${testdata.ccRefresh.resource}"
    Und TGR setze lokale Variable "CC_REFRESH_CHANGED_SCOPE" auf "${testdata.ccRefresh.changedScope}"
    Und TGR setze lokale Variable "CC_REFRESH_CONDITION" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    Dann Setze im TigerProxy für die Nachricht "${CC_REFRESH_CONDITION}" die Regex-Manipulation auf Feld "$.body" mit Regex "(^|&)audience=[^&]*" und Wert "$1resource=!{urlEncoded('${CC_REFRESH_RESOURCE}')}"
    Und warte "5" Sekunden
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "\Q${CC_REFRESH_INITIAL_RT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${CC_REFRESH_CLIENT}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_REFRESH_CONTROL_REQUEST"
    Und der Refresh-Request "${CC_REFRESH_CONTROL_REQUEST}" verwendet Resource "${testdata.ccRefresh.resource}" und Scope "${testdata.ccRefresh.initialScope}"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_REFRESH_CONTROL_AT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_REFRESH_CONTROL_RT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "\Q${CC_REFRESH_INITIAL_RT}\E"
    Und verifiziere die ES256 Signatur des JWT "${CC_REFRESH_CONTROL_AT}" mit KeyStore "${CC_REFRESH_JWKS}"
    Und das Access Token "${CC_REFRESH_CONTROL_AT}" enthält genau die Scopes "${testdata.ccPolicy.oidcGrantedScopes}"

    # Der zweite echte SDK-Refresh behält R1; Tiger ändert ausschließlich den angeforderten Scope.
    Dann Setze im TigerProxy für die Nachricht "${CC_REFRESH_CONDITION}" die Regex-Manipulation auf Feld "$.body" mit Regex "(^|&)audience=[^&]*" und Wert "$1resource=!{urlEncoded('${CC_REFRESH_RESOURCE}')}"
    Dann Setze im TigerProxy für die Nachricht "${CC_REFRESH_CONDITION}" die Regex-Manipulation auf Feld "$.body" mit Regex "((?:^|&)scope=)[^&]*" und Wert "$1!{urlEncoded('${CC_REFRESH_CHANGED_SCOPE}')}"
    Und warte "5" Sekunden
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "\Q${CC_REFRESH_CONTROL_RT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${CC_REFRESH_CLIENT}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_REFRESH_CHANGED_REQUEST"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_REFRESH_CHANGED_RESPONSE"
    Und der Refresh-Request "${CC_REFRESH_CHANGED_REQUEST}" verwendet Resource "${testdata.ccRefresh.resource}" und Scope "${testdata.ccRefresh.changedScope}"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_REFRESH_CHANGED_AT"
    Und verifiziere die ES256 Signatur des JWT "${CC_REFRESH_CHANGED_AT}" mit KeyStore "${CC_REFRESH_JWKS}"
    # Das SDK muss genau das neue Access Token verwenden, ohne erneuten Token Exchange.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${CC_REFRESH_CHANGED_AT}\E"
    Dann prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Dann die Refresh-Antwort "${CC_REFRESH_CHANGED_RESPONSE}" wechselt von Access Token "${CC_REFRESH_CONTROL_AT}" und Refresh Token "${CC_REFRESH_CONTROL_RT}" zu Scopes "${testdata.ccRefresh.oidcChangedScopes}" in derselben Sitzung
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.scopes" der mit "(?s).*\Q${testdata.ccRefresh.additionalScope}\E.*" übereinstimmt erfolgt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.scopes" der mit "(?s).*\Q${testdata.ccRefresh.additionalScope}\E.*" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.client_id" überein mit "\Q${CC_REFRESH_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.grant_type" überein mit "refresh_token"
    Und TGR speichere Wert des Knotens "$.body.input" der aktuellen Anfrage in der Variable "CC_REFRESH_POLICY_INPUT"
    Und prüfe Refresh-Policy "${CC_REFRESH_POLICY_INPUT}" für Client "${CC_REFRESH_CLIENT}", Resource "${testdata.ccRefresh.resource}" und Scope "${testdata.ccRefresh.changedScope}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
