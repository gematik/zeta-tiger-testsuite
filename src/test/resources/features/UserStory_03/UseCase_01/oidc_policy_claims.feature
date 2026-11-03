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
Funktionalität: OIDC Access-Token-Claims mit der gewährten Policy vergleichen

  @A_25661-01
  @TA_A_25661-01_04
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @require_kubectl
  @oidc
  @Ignore
  Szenario: OIDC Access Token übernimmt Identität, Clientdaten und Grant der Policy-Transaktion
    # Die gewährten Scopes sind eine unabhängige Erwartung der installierten Testpolicy.
    # Eine neue Identität verhindert die Wiederverwendung eines früheren OIDC-Grants.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_OIDC_KVNR"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der echte Code-Austausch belegt den OIDC-Flow; die folgenden Werte stammen aus seiner Tokenausgabe.
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.sectoralIdp.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "authorization_code" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${body.oidc.tokenResponse.idToken.root}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.sub" der aktuellen Anfrage in der Variable "CC_OIDC_CLIENT_ID"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_OIDC_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_OIDC_CLIENT_ID}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_OIDC_ACCESS_TOKEN"
    Und TGR speichere Wert des Knotens "$.body.access_token.body" der aktuellen Antwort in der Variable "CC_OIDC_ACCESS_CLAIMS"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "CC_OIDC_ACCESS_EXP"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.iat" der aktuellen Antwort in der Variable "CC_OIDC_ACCESS_IAT"
    Und decodiere und validiere "${CC_OIDC_ACCESS_TOKEN}" gegen Schema "schemas/v_2_0/schemas/access-token.yaml"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_OIDC_CLIENT_ID}\E" übereinstimmt erfolgt

    # Genau eine Policy-Transaktion im isolierten Lauf; ihre Übertragungszeit liegt im Token-Request.
    # Die asynchrone RBEL-Einlesereihenfolge ist keine verlässliche fachliche Nachrichtenreihenfolge.
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.opa.decisionPath}" haben
    Dann TGR finde die erste Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_OIDC_CLIENT_ID}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.client_id" überein mit "\Q${CC_OIDC_CLIENT_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.identifier" überein mit "\Q${CC_OIDC_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.grant_type" überein mit "${oauth_parameters.grant_type.tokenExchange}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR speichere Wert des Knotens "$.body.input" der aktuellen Anfrage in der Variable "CC_OIDC_POLICY_INPUT"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_OIDC_POLICY_DECISION"
    Und TGR speichere Wert des Knotens "$.body.result.ttl.access_token" der aktuellen Antwort in der Variable "CC_OIDC_POLICY_TTL"
    Und validiere "${CC_OIDC_POLICY_INPUT}" gegen Schema "schemas/v_2_0/schemas/policy-engine-input.yaml"
    Und validiere "${CC_OIDC_POLICY_DECISION}" gegen Schema "schemas/v_2_0/schemas/pdp-decision.yaml"
    Dann prüfe Access-Token-Claims "${CC_OIDC_ACCESS_CLAIMS}" gegen Policy-Input "${CC_OIDC_POLICY_INPUT}", Entscheidung "${CC_OIDC_POLICY_DECISION}" und gewährte Scopes "${testdata.ccPolicy.oidcGrantedScopes}" mit soft assert
    # Maßgeblich für den Claimvergleich ist die signierte Lebensdauer, nicht ein später berechneter expires_in-Restwert.
    Und prüfe dass Token TTL zwischen exp="${CC_OIDC_ACCESS_EXP}" und iat="${CC_OIDC_ACCESS_IAT}" gleich "${CC_OIDC_POLICY_TTL}" Sekunden ist

    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_OIDC_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${CC_OIDC_ACCESS_TOKEN}" mit KeyStore "${CC_OIDC_JWKS}"
