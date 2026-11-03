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

@UseCase_04_09
@require_kubectl
@smb
Funktionalität: SDK-DPoP-Proofs und Sitzungsschlüssel im stationären Profil

  Grundlage:
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    # Lesende unabhängige AS-Schlüsselabfrage; alle fachlichen Aktionen erfolgen über das SDK.
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_KEY_AS_JWKS"

  @A_29376
  @TA_A_29376_13
  @normal
  Szenario: SDK signiert den Token-Exchange-Proof mit seinem DPoP-Schlüssel und der vorherigen AS-Nonce
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_KEY_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_KEY_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${CC_KEY_JWKS}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_KEY_DPOP"
    Und berechne JKT aus JWT Header JWK "${CC_KEY_DPOP}" und speichere in Variable "CC_KEY_JKT"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CC_KEY_ASSERTION"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_KEY_AT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_KEY_RT"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_AT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_RT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "\Q${CC_KEY_JKT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.cnf.jkt" überein mit "\Q${CC_KEY_JKT}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "CC_KEY_SID"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" überein mit ".+"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "\Q${CC_KEY_SID}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "CC_KEY_PRIVATE"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CC_KEY_CLIENT_PRIVATE"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_DPOP}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_PRIVATE}"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_ASSERTION}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_CLIENT_PRIVATE}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.root}" der mit "(?i:DPoP) \Q${CC_KEY_AT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Authorization']" überein mit "(?i:DPoP) \Q${CC_KEY_AT}\E"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_RESOURCE_DPOP"
    Und verifiziere ES256 Signatur von DPoP JWT "${CC_RESOURCE_DPOP}"
    Und verifiziere die ES256 Signatur des JWT "${CC_RESOURCE_DPOP}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_PRIVATE}"

  @A_29376
  @TA_A_29376_12
  @normal
  @Ignore
  Szenario: Dieselbe SDK-Clientinstanz erneuert den DPoP-Schlüssel erst für eine neue Sitzung
    # Nur die serverseitig verwendete Policy-TTL ändern; keine Token oder SDK-Schlüssel ersetzen.
    Gegeben sei TGR setze lokale Variable "keyOpaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Und Setze im TigerProxy für die Nachricht "${keyOpaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "60" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${keyOpaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "60" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_KEY_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_KEY_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${CC_KEY_JWKS}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_KEY_DPOP"
    Und berechne JKT aus JWT Header JWK "${CC_KEY_DPOP}" und speichere in Variable "CC_KEY_JKT"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CC_KEY_ASSERTION"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_KEY_AT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_KEY_RT"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_AT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_RT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "\Q${CC_KEY_JKT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.cnf.jkt" überein mit "\Q${CC_KEY_JKT}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "CC_KEY_SID"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" überein mit ".+"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "\Q${CC_KEY_SID}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "CC_KEY_PRIVATE"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CC_KEY_CLIENT_PRIVATE"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_DPOP}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_PRIVATE}"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_ASSERTION}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_CLIENT_PRIVATE}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.root}" der mit "(?i:DPoP) \Q${CC_KEY_AT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Authorization']" überein mit "(?i:DPoP) \Q${CC_KEY_AT}\E"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_RESOURCE_DPOP"
    Und verifiziere ES256 Signatur von DPoP JWT "${CC_RESOURCE_DPOP}"
    Und verifiziere die ES256 Signatur des JWT "${CC_RESOURCE_DPOP}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_PRIVATE}"
    Und TGR speichere Wert des Knotens "${headers.dpop.body.jti}" der aktuellen Anfrage in der Variable "CC_FIRST_RESOURCE_JTI"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.body.jti}" der aktuellen Anfrage in der Variable "CC_FIRST_TOKEN_JTI"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "CC_FIRST_AT_EXP"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "CC_FIRST_RT_EXP"
    Und TGR setze lokale Variable "CC_FIRST_SID" auf "${CC_KEY_SID}"
    Und TGR setze lokale Variable "CC_FIRST_JKT" auf "${CC_KEY_JKT}"
    Und TGR setze lokale Variable "CC_FIRST_CLIENT_PRIVATE" auf "${CC_KEY_CLIENT_PRIVATE}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    # Warme Kontrolle: identisches Token und derselbe Schlüssel, kein zusätzlicher Tokenrequest.
    Und validiere, dass der Zeitstempel "${CC_FIRST_AT_EXP}" in der Zukunft liegt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.dpop.body.jti}" der mit "(?!\Q${CC_FIRST_RESOURCE_JTI}\E$).+" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Authorization']" überein mit "(?i:DPoP) \Q${CC_KEY_AT}\E"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_RESOURCE_DPOP"
    Und verifiziere ES256 Signatur von DPoP JWT "${CC_RESOURCE_DPOP}"
    Und verifiziere die ES256 Signatur des JWT "${CC_RESOURCE_DPOP}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_PRIVATE}"
    Und validiere, dass der Zeitstempel "${CC_FIRST_AT_EXP}" in der Zukunft liegt
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
    # Die signierten Ablaufwerte sind maßgeblich; der Wartewert allein ist kein Ablaufnachweis.
    Und warte "65" Sekunden
    Und validiere, dass der Zeitstempel "${CC_FIRST_AT_EXP}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${CC_FIRST_RT_EXP}" in der Vergangenheit liegt
    # Kein reset, Logout, Storage-Eingriff oder Neuregistrieren zwischen den Sitzungen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.body.jti}" der mit "(?!\Q${CC_FIRST_TOKEN_JTI}\E$).+" übereinstimmt
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
    Dann prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${CC_KEY_JWKS}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_KEY_DPOP"
    Und berechne JKT aus JWT Header JWK "${CC_KEY_DPOP}" und speichere in Variable "CC_KEY_JKT"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CC_KEY_ASSERTION"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_KEY_AT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_KEY_RT"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_AT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_RT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "\Q${CC_KEY_JKT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.cnf.jkt" überein mit "\Q${CC_KEY_JKT}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "CC_KEY_SID"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" überein mit ".+"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "\Q${CC_KEY_SID}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "CC_KEY_PRIVATE"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CC_KEY_CLIENT_PRIVATE"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_DPOP}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_PRIVATE}"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_ASSERTION}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_CLIENT_PRIVATE}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.root}" der mit "(?i:DPoP) \Q${CC_KEY_AT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Authorization']" überein mit "(?i:DPoP) \Q${CC_KEY_AT}\E"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_RESOURCE_DPOP"
    Und verifiziere ES256 Signatur von DPoP JWT "${CC_RESOURCE_DPOP}"
    Und verifiziere die ES256 Signatur des JWT "${CC_RESOURCE_DPOP}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_KEY_PRIVATE}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" nicht überein mit "\Q${CC_FIRST_SID}\E"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_ASSERTION}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CC_FIRST_CLIENT_PRIVATE}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" nicht überein mit "\Q${CC_FIRST_JKT}\E"
