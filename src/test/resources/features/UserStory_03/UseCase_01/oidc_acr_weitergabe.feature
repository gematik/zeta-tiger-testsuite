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
Funktionalität: acr des sektoralen ID Tokens unverändert an die Policy übergeben

  @A_29994
  @TA_A_29994_02
  @normal
  @require_kubectl
  @oidc
  @require_oidc_decryption_key
  @Ignore
  Szenariogrundriss: Die Policy erhält das acr des ausgeführten IDP-Profils (<Profil>)
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_ACR_KVNR"
    # Öffentliche IDP-Schlüssel dienen nur zur unabhängigen Prüfung der echten Tokenausgabe.
    Wenn TGR sende eine leere GET Anfrage an "${testdata.ccOidcAcr.entityStatementUrl}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/.well-known/openid-federation"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.iss" überein mit "\Q${testdata.ccOidcAcr.issuer}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_ACR_ENTITY_STATEMENT"
    Und TGR speichere Wert des Knotens "$.body.body.jwks" der aktuellen Antwort in der Variable "CC_ACR_ENTITY_KEYS"
    Und verifiziere die ES256 Signatur des JWT "${CC_ACR_ENTITY_STATEMENT}" mit KeyStore "${CC_ACR_ENTITY_KEYS}"
    Wenn TGR sende eine leere GET Anfrage an "${testdata.ccOidcAcr.signedJwksUrl}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/jws.json"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.iss" überein mit "\Q${testdata.ccOidcAcr.issuer}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_ACR_SIGNED_JWKS"
    Und TGR speichere Wert des Knotens "$.body.body" der aktuellen Antwort in der Variable "CC_ACR_TOKEN_KEYS"
    Und verifiziere die ES256 Signatur des JWT "${CC_ACR_SIGNED_JWKS}" mit KeyStore "${CC_ACR_ENTITY_KEYS}"

    # Der Test-IDP unterstützt beide Profile im Anmeldeformular. Auswahl vor Ausgabe, keine Tokenänderung.
    Und TGR setze lokale Variable "CC_ACR_PROFILE_REQUEST" auf "isRequest && request.path =~ '.*/auth' && request.url =~ '.*[?&]user_id=${CC_ACR_KVNR}(&.*|$)' && request.url =~ '.*[?&]acr_value=.*'"
    Dann Setze im TigerProxy für die Nachricht "${CC_ACR_PROFILE_REQUEST}" die Regex-Manipulation auf Feld "$.path" mit Regex "([?&]acr_value=)[^&]*" und Wert "$1<acr>"
    Dann Setze im TigerProxy für die Nachricht "${CC_ACR_PROFILE_REQUEST}" die Regex-Manipulation auf Feld "$.path" mit Regex "([?&]amr_value=)[^&]*" und Wert "$1!{urlEncoded('<amr>')}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die signierten Originalclaims unten belegen Identität und tatsächliche Profilausgabe.
    # Tiger serialisiert die manipulierten Query-Knoten im lokalen RBEL-Profil unvollständig.
    Dann TGR finde die letzte Anfrage mit dem Pfad "/auth"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "302"

    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.sectoralIdp.tokenEndpointPath}" haben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.sectoralIdp.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "authorization_code" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "CC_ACR_RELYING_PARTY"
    Und entschlüssle das ID Token "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort als signiertes JWT in Variable "CC_ACR_ID_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${CC_ACR_ID_TOKEN}" mit KeyStore "${CC_ACR_TOKEN_KEYS}"

    # Frische KVNR, exakter Client und Übertragungszeit korrelieren ID-Token-Kontext und Policy-Transaktion.
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.sub" der aktuellen Anfrage in der Variable "CC_ACR_CLIENT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_ACR_KVNR}\E"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_ACR_CLIENT}\E" übereinstimmt erfolgt
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.opa.decisionPath}" haben
    Dann TGR finde die erste Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_ACR_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR speichere Wert des Knotens "$.body.input" der aktuellen Anfrage in der Variable "CC_ACR_POLICY_INPUT"
    Dann prüfe OIDC-acr aus ID Token "${CC_ACR_ID_TOKEN}" gegen Policy "${CC_ACR_POLICY_INPUT}" für KVNR "${CC_ACR_KVNR}", Client "${CC_ACR_CLIENT}", Issuer "${testdata.ccOidcAcr.issuer}", RP "${CC_ACR_RELYING_PARTY}", acr "<acr>" und amr "<amr>"

    Beispiele:
      | Profil             | acr                            | amr                    |
      | high mit eGK       | gematik-ehealth-loa-high        | urn:telematik:auth:eGK  |
      | substantial other  | gematik-ehealth-loa-substantial | urn:telematik:auth:other |
