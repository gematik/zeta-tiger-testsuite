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
Funktionalität: Policy-Ablehnung gültiger SDK-Credentials mit korrelierten Gründen

  Grundlage:
    # Unabhängige konfigurierte Testidentität, bevor das SDK ein Subject Token bildet.
    Gegeben sei lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "CC_REQUEST_SMCB"
    Und speichere die Telematik-ID aus SMC-B Zertifikat "${CC_REQUEST_SMCB.crt_b64}" in Variable "CC_REQUEST_IDENTITY"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    # Lesende unabhängige AS-Schlüsselabfrage; alle fachlichen Aktionen erfolgen über das SDK.
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_KEY_AS_JWKS"

    # Unabhängige AuthConfig-Vorgabe des JVM-Testdrivers; nicht aus Request oder Token ableiten.
    Gegeben sei TGR setze lokale Variable "CC_REQUEST_SCOPE" auf "zero:audience"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Ausschließlich die vom SDK selbst abgefragten Discovery-Antworten verwenden.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthProtectedResourcePath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.resource" der aktuellen Antwort in der Variable "CC_REQUEST_RESOURCE"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.resource" überein mit "\Qhttps://${zeta_base_url}${paths.guard.pepRoutePrefix}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthServerPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.token_endpoint" der aktuellen Antwort in der Variable "CC_REQUEST_ENDPOINT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.token_endpoint" überein mit "\Qhttps://${zeta_base_url}${paths.guard.tokenEndpointPath}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_KEY_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_KEY_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Host']" überein mit "\Q${zeta_base_url}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Content-Type']" überein mit "(?i)application/x-www-form-urlencoded(?:;\s*charset=UTF-8)?"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "!{urlEncoded('${oauth_parameters.token_type.jwt}')}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion_type" überein mit "!{urlEncoded('${oauth_parameters.client_assertion_type.jwtBearer}')}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.scope" überein mit "\Q!{urlEncoded('${CC_REQUEST_SCOPE}')}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.audience" überein mit "\Q!{urlEncoded('${CC_REQUEST_RESOURCE}')}\E"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.resource"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.root}.body.htu" überein mit "\Q${CC_REQUEST_ENDPOINT}\E"
    # Die Signaturprüfung unten bindet diese Inhalte an DCR, SMC-B, SDK und tatsächliche AS-Nonce.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.sub" überein mit "\Q${CC_KEY_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.platform" überein mit "linux"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture_type" überein mit "software"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement" der aktuellen Anfrage in der Variable "CC_REQUEST_STATEMENT"
    Und validiere "${CC_REQUEST_STATEMENT}" gegen Schema "schemas/v_2_0/schemas/client-statement.yaml"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture" der aktuellen Anfrage in der Variable "CC_REQUEST_POSTURE"
    Und validiere "${CC_REQUEST_POSTURE}" gegen Schema "schemas/v_2_0/schemas/posture-software.yaml"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "\Q${testdata.product_id}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_version" überein mit "${regex.product_version}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "CC_REQUEST_ATTESTED_AT"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.exp" der aktuellen Anfrage in der Variable "CC_REQUEST_ASSERTION_EXP"
    Und validiere, dass der Zeitstempel "${CC_REQUEST_ATTESTED_AT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${CC_REQUEST_ASSERTION_EXP}" später als "${CC_REQUEST_ATTESTED_AT}" liegt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.x5c.0" überein mit "\Q${CC_REQUEST_SMCB.crt_b64}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.sub" überein mit "\Q${CC_REQUEST_IDENTITY}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_REQUEST_IDENTITY}\E"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${CC_KEY_JWKS}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_KEY_DPOP"
    Und berechne JKT aus JWT Header JWK "${CC_KEY_DPOP}" und speichere in Variable "CC_KEY_JKT"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CC_KEY_ASSERTION"
    Und decodiere und validiere "${CC_KEY_ASSERTION}" gegen Schema "schemas/v_2_0/schemas/client-assertion-jwt.yaml"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_KEY_AT"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_KEY_RT"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_AT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${CC_KEY_RT}" mit KeyStore "${CC_KEY_AS_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.access_token.body" der aktuellen Antwort in der Variable "CC_ISSUED_AT_CLAIMS"
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
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_KEY_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.opa.decisionPath}" haben
    # Eine neue SDK-Instanz verhindert die Verwendung des soeben erhaltenen Access Tokens.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten

  @A_29376
  @TA_A_29376_08
  @smcb_policy_denial
  @normal
  Szenariogrundriss: Gültiger SDK-Exchange bei Policy deny mit <Variante>
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_POLICY_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_POLICY_CLIENT"
    # Nur die Entscheidung dieses Clients ändern. Das SDK behält Registrierung und Schlüssel.
    Und TGR setze lokale Variable "ccDenyPolicy" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}' && request.body.getFirst('input').get().getFirst('client_registration_data').get().getFirst('client_id').get().getFirst('content').get().rawStringContent == '${CC_POLICY_CLIENT}' && request.body.getFirst('input').get().getFirst('authorization_request').get().getFirst('grant_type').get().getFirst('content').get().rawStringContent == '${oauth_parameters.grant_type.tokenExchange}'"
    Und Setze im TigerProxy für die Nachricht "${ccDenyPolicy}" die Manipulation auf Feld "$.body.result" und Wert '<Entscheidung>' und 20 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_POLICY_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${CC_POLICY_CLIENT}\E" übereinstimmt
    # Alle Wiederholungen: unveränderte gültige Nachweise, eigene AS-Nonce und genau eine
    # zugehörige deny-Antwort vor der Tokenantwort. Erst danach den gesamten Fehlervertrag prüfen.
    Und alle SMC-B-Exchanges für Client "${CC_POLICY_CLIENT}" mit JWKS "${CC_POLICY_JWKS}" und SMC-B-Zertifikat "${CC_REQUEST_SMCB.crt_b64}" erfüllen die Policy-Ablehnung mit Gründen '<Gruende>' mit soft assert

    Beispiele:
      | Variante              | Entscheidung                                                                                           | Gruende                                                                          |
      | einem Grund           | {"allow":false,"reasons":{"CC_Policy_Client_Not_Authorized":true}}                                      | {"CC_Policy_Client_Not_Authorized":true}                                           |
      | zwei anderen Gründen  | {"allow":false,"reasons":{"CC_Policy_Scope_Not_Authorized":true,"CC_Policy_Context_Not_Authorized":true}} | {"CC_Policy_Scope_Not_Authorized":true,"CC_Policy_Context_Not_Authorized":true}     |
