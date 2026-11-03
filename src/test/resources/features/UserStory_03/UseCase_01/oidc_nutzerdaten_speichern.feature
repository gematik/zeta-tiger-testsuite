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

@UseCase_03_01
Funktionalität: OIDC-Nutzerdaten in der PDP-Datenbank speichern

  @A_26973-02
  @TA_A_26973-02_02
  @normal
  @require_kubectl
  @oidc
  @require_oidc_decryption_key
  @Ignore
  Szenario: Die persistierte Session enthält die Pflicht-Nutzerdaten aus dem echten ID Token
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_STORED_KVNR"
    Wenn TGR sende eine leere GET Anfrage an "${testdata.ccOidcAcr.entityStatementUrl}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/.well-known/openid-federation"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.iss" überein mit "\Q${testdata.ccOidcAcr.issuer}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_STORED_IDP_ENTITY"
    Und TGR speichere Wert des Knotens "$.body.body.jwks" der aktuellen Antwort in der Variable "CC_STORED_IDP_ENTITY_KEYS"
    Und verifiziere die ES256 Signatur des JWT "${CC_STORED_IDP_ENTITY}" mit KeyStore "${CC_STORED_IDP_ENTITY_KEYS}"
    Wenn TGR sende eine leere GET Anfrage an "${testdata.ccOidcAcr.signedJwksUrl}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/jws.json"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.iss" überein mit "\Q${testdata.ccOidcAcr.issuer}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_STORED_IDP_SIGNED_KEYS"
    Und TGR speichere Wert des Knotens "$.body.body" der aktuellen Antwort in der Variable "CC_STORED_IDP_TOKEN_KEYS"
    Und verifiziere die ES256 Signatur des JWT "${CC_STORED_IDP_SIGNED_KEYS}" mit KeyStore "${CC_STORED_IDP_ENTITY_KEYS}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.sectoralIdp.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "authorization_code" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.id}" in Variable "CC_STORED_IDP_IDENTIFIER"
    Und TGR prüfe Variable "CC_STORED_IDP_IDENTIFIER" stimmt überein mit "\Q${CC_STORED_KVNR}\E"
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.profession}" in Variable "CC_STORED_IDP_PROFESSION"
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.organization}" in Variable "CC_STORED_IDP_ORGANIZATION"
    Und entschlüssle das ID Token "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort als signiertes JWT in Variable "CC_STORED_ORIGINAL_ID_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${CC_STORED_ORIGINAL_ID_TOKEN}" mit KeyStore "${CC_STORED_IDP_TOKEN_KEYS}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.sub" der aktuellen Anfrage in der Variable "CC_STORED_CLIENT_ID"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_STORED_IDP_IDENTIFIER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_STORED_CLIENT_ID}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "CC_STORED_SESSION_ID"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_STORED_ACCESS_TOKEN"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_STORED_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${CC_STORED_ACCESS_TOKEN}" mit KeyStore "${CC_STORED_JWKS}"

    Dann prüfe OIDC-Persistenz: Client "${CC_STORED_CLIENT_ID}", Session "${CC_STORED_SESSION_ID}", KVNR "${CC_STORED_IDP_IDENTIFIER}", Profession "${CC_STORED_IDP_PROFESSION}", Organisation "${CC_STORED_IDP_ORGANIZATION}"
    Und prüfe das gespeicherte OIDC-ID-Token für Client "${CC_STORED_CLIENT_ID}", Session "${CC_STORED_SESSION_ID}" gegen das signierte Original "${CC_STORED_ORIGINAL_ID_TOKEN}"
