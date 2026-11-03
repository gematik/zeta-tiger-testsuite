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

@UseCase_03_04
@require_kubectl
@oidc
@require_oidc_decryption_key
@flow_afo_continuation
@reset_testdriver
Funktionalität: Inneren und äußeren OIDC-Code im Softwarepfad einlösen

  @A_29672
  @TA_A_29672_02
  Szenario: OIDC-Tokenbezug folgt beiden Rücksprüngen und der erlaubenden Software-Policy
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "FLOW_KVNR"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "tiger.lib.validation.messageSortOrder" auf "TIMESTAMP"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "FLOW_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "FLOW_JWKS"
    Und TGR speichere Wert des Knotens "$.body.jwks.keys.0.x" der aktuellen Anfrage in der Variable "TOKEN_CLIENT_X"
    Und TGR speichere Wert des Knotens "$.body.jwks.keys.0.y" der aktuellen Anfrage in der Variable "TOKEN_CLIENT_Y"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthServerPath}"
    Und TGR speichere Wert des Knotens "$.body.redirection_endpoint" der aktuellen Antwort in der Variable "TOKEN_REDIRECTION_ENDPOINT"
    Und TGR setze lokale Feature Variable "TOKEN_REDIRECTION_PATH" auf "${TOKEN_REDIRECTION_ENDPOINT}"
    Und TGR ersetze "^https?://[^/]+" mit "" im Inhalt der Variable "TOKEN_REDIRECTION_PATH"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${FLOW_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "TOKEN_REAUTH_RESOURCE" auf "isResponse && request.path == '${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${TOKEN_REAUTH_RESOURCE}" die Manipulation auf Feld "$.responseCode" und Wert "401" und 2 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${TOKEN_REAUTH_RESOURCE}" die Manipulation auf Feld "${headers.zeta.errorOrigin.strict}" und Wert "PEP" und 2 Ausführungen
    Und TGR setze lokale Variable "TOKEN_FRESH_BROWSER" auf "isRequest && path =~ '^${testdata.ccPar.authorizePath}.*'"
    Und Setze im TigerProxy für die Nachricht "${TOKEN_FRESH_BROWSER}" die Manipulation auf Feld "$.header.[~'Cookie']" und Wert "" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "PEP"
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "PEP"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${testdata.ccPar.outerPath}"
    Und TGR speichere Wert des Knotens "$.body.state" der aktuellen Anfrage in der Variable "TOKEN_OUTER_STATE"
    Dann TGR finde die nächste Anfrage mit dem Pfad "${testdata.ccPar.innerPath}"
    Und TGR speichere Wert des Knotens "$.body.state" der aktuellen Anfrage in der Variable "TOKEN_INNER_STATE"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "TOKEN_IDP_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.redirect_uri" der aktuellen Anfrage in der Variable "TOKEN_IDP_REDIRECT"
    Dann TGR finde die nächste Anfrage mit dem Pfad "\Q${TOKEN_REDIRECTION_PATH}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit ".*[?&]state=\Q${TOKEN_INNER_STATE}\E(&.*)?"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "TOKEN_INNER_CALLBACK"
    Und TGR setze lokale Feature Variable "TOKEN_INNER_CODE" auf "${TOKEN_INNER_CALLBACK}"
    Und TGR ersetze "^.*[?&]code=([^&]+).*$" mit "$1" im Inhalt der Variable "TOKEN_INNER_CODE"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "302"
    Und TGR speichere Wert des Knotens "$.header.[~'Location']" der aktuellen Antwort in der Variable "TOKEN_OUTER_CALLBACK"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.[~'Location']" überein mit "\Q${oidcTestData.pdp.entityStatement.redirectUris.redirectUri}\E\?.+"
    Und TGR prüfe Variable "TOKEN_OUTER_CALLBACK" stimmt überein mit ".*[?&]state=\Q${TOKEN_OUTER_STATE}\E(&.*)?"
    Und TGR setze lokale Feature Variable "TOKEN_OUTER_CODE" auf "${TOKEN_OUTER_CALLBACK}"
    Und TGR ersetze "^.*[?&]code=([^&]+).*$" mit "$1" im Inhalt der Variable "TOKEN_OUTER_CODE"
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.sectoralIdp.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "authorization_code"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.code" überein mit "\Q${TOKEN_INNER_CODE}\E"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.code_verifier"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${TOKEN_IDP_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.redirect_uri" überein mit "\Q${TOKEN_IDP_REDIRECT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.id_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.token_type" überein mit "Bearer"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.expires_in"
    Und entschlüssle das ID Token "$.body.id_token" der aktuellen Antwort als signiertes JWT in Variable "TOKEN_SIGNED_ID"
    Und decodiere und validiere "${TOKEN_SIGNED_ID}" gegen Schema "schemas/oidc-flow-id-token-header.yaml"
    Und entschlüssle JWE "$.body.id_token" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.id}" in Variable "TOKEN_IDENTITY"
    Und TGR prüfe Variable "TOKEN_IDENTITY" stimmt überein mit "\Q${FLOW_KVNR}\E"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.id_token" der mit ".+" übereinstimmt
    Und TGR finde die nächste Nachricht mit Knoten "$.header.[~'Location']" der mit "\Q${TOKEN_OUTER_CALLBACK}\E" übereinstimmt
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "authorization_code"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.code" überein mit "\Q${TOKEN_OUTER_CODE}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${FLOW_CLIENT}\E"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.code_verifier"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.redirect_uri" überein mit "\Q!{urlEncoded('${oidcTestData.pdp.entityStatement.redirectUris.redirectUri}')}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture_type" überein mit "software"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.jwk.x" überein mit "\Q${TOKEN_CLIENT_X}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.jwk.y" überein mit "\Q${TOKEN_CLIENT_Y}\E"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.public_key" der aktuellen Anfrage in der Variable "TOKEN_CLIENT_KEY"
    Und prüfe Software-Schlüssel "${TOKEN_CLIENT_KEY}" gehört zum registrierten JWKS "${FLOW_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "TOKEN_ASSERTION"
    Und verifiziere die ES256 Signatur des JWT "${TOKEN_ASSERTION}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "TOKEN_DPOP"
    Und verifiziere ES256 Signatur von DPoP JWT "${TOKEN_DPOP}"
    Und berechne JKT aus JWT Header JWK "${TOKEN_DPOP}" und speichere in Variable "TOKEN_JKT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.token_type" überein mit "DPoP"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.expires_in"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.zg_att_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.zeta_attestation_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "\Q${TOKEN_JKT}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "TOKEN_ACCESS"
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${FLOW_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.identifier" überein mit "\Q${TOKEN_IDENTITY}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.grant_type" überein mit "authorization_code"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.input.client_registration_data"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.input.client_registration_data.device_info.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.result.allow" der mit "true" übereinstimmt
    Und TGR finde die nächste Nachricht mit Knoten "$.body.access_token" der mit "\Q${TOKEN_ACCESS}\E" übereinstimmt

