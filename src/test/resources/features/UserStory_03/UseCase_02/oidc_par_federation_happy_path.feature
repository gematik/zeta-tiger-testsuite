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

# language: de
@UseCase_03_02
@require_kubectl
@oidc
@reset_testdriver
Funktionalität: Authorization Request mit PAR und erstmaliger Federation-Beschaffung

  @A_29660
  @TA_A_29660_01
  @deployment_modification
  @normal
  Szenario: Authorization Request gelingt bei beidseitig unbekannten Entity Statements
    Wenn starte das Deployment "${zetaDeploymentConfig.authserver.podName}" neu und warte auf Bereitschaft
    Und starte das Deployment "${zetaDeploymentConfig.sekidp.podName}" neu und warte auf Bereitschaft
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "PAR_FED_KVNR"
    Und TGR setze lokale Variable "tiger.lib.validation.messageSortOrder" auf "TIMESTAMP"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "PAR_FED_CLIENT"
    Und merke die originale PAR-Clientregistrierung für Client "${PAR_FED_CLIENT}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${testdata.ccPar.innerPath}"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${testdata.ccPar.outerPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${testdata.ccPar.innerPath}" haben
    Dann TGR finde die erste Anfrage mit Pfad "${testdata.ccPar.outerPath}"
    Und merke den authentisierten äußeren PAR und seine ausgegebene request_uri
    Und TGR setze lokale Variable "schemaValidation.soft" auf "true"
    Und validiere "!{new('tools.jackson.databind.ObjectMapper').writeValueAsString(new('de.gematik.zeta.steps.SmbExchangeSteps').form(rbel:currentRequestAsString('$.body')))}" gegen OpenAPI-Schema "schemas/v_2_0/openapi/as-par-endpoint.yaml#/paths/~1par/post/requestBody/content/application~1x-www-form-urlencoded/schema"
    Und TGR setze lokale Variable "schemaValidation.soft" auf "false"
    Und entspricht der originale äußere PAR seinem SDK-Kontext
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["request_uri","expires_in"],"properties":{"request_uri":{"type":"string","minLength":1},"expires_in":{"type":"integer","const":5}}}
      """
    Und TGR speichere Wert des Knotens "$.body.request_uri" der aktuellen Antwort in der Variable "PAR_FED_OUTER_URI"
    Und TGR finde die nächste Nachricht mit Knoten "${body.oidc.parResponse.requestUri}" der mit "\Q${PAR_FED_OUTER_URI}\E" übereinstimmt
    Und TGR finde die nächste Anfrage mit Pfad "${testdata.ccPar.authorizePath}.*" und Knoten "$.path.request_uri.value" der mit "\Q${PAR_FED_OUTER_URI}\E" übereinstimmt
    Und verwendet der originale SDK-Authorize-Aufruf genau die ausgegebene äußere request_uri
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"

    Dann TGR finde die nächste Nachricht mit Knoten "$.body.body.iss" der mit "\Q${testdata.ccOidcAcr.issuer}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "(?:/sekidp)?/\.well-known/openid-federation"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.sub" überein mit "\Q${testdata.ccOidcAcr.issuer}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PAR_FED_IDP_STATEMENT"
    Und TGR speichere Wert des Knotens "$.body.body.jwks" der aktuellen Antwort in der Variable "PAR_FED_IDP_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${PAR_FED_IDP_STATEMENT}" mit KeyStore "${PAR_FED_IDP_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.body.metadata.openid_provider.authorization_endpoint" der aktuellen Antwort in der Variable "PAR_FED_AUTHORIZATION_ENDPOINT"

    Dann TGR finde die erste Anfrage mit Pfad "\Q${testdata.ccParFederation.fetchPath}\E.*" und Knoten "$.path.sub.value" der mit "\Q${testdata.ccOidcAcr.issuer}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path.basicPath" überein mit "\Q${testdata.ccParFederation.fetchPath}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path.sub.value" überein mit "\Q${testdata.ccOidcAcr.issuer}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path.iss.value" überein mit "\Q${testdata.ccParFederation.masterIssuer}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.sub" überein mit "\Q${testdata.ccOidcAcr.issuer}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.iss" überein mit "\Q${testdata.ccParFederation.masterIssuer}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.header.alg" überein mit "ES256"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"

    Dann TGR finde die nächste Anfrage mit dem Pfad "${testdata.ccPar.innerPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.guard.wellKnownFederationPath}" und Knoten "$.method" der mit "GET" übereinstimmt erfolgt

    Und TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.wellKnownFederationPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.iss" überein mit "\Q${paths.guard.issuerUrl}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.body.sub" überein mit "\Q${paths.guard.issuerUrl}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PAR_FED_GUARD_STATEMENT"
    Und TGR speichere Wert des Knotens "$.body.body.jwks" der aktuellen Antwort in der Variable "PAR_FED_GUARD_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${PAR_FED_GUARD_STATEMENT}" mit KeyStore "${PAR_FED_GUARD_JWKS}"

    Dann TGR finde die nächste Nachricht mit Knoten "$.body.request_uri" der mit ".+" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "\Q${testdata.ccPar.innerPath}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["request_uri","expires_in"],"properties":{"request_uri":{"type":"string","pattern":"^[a-zA-Z][a-zA-Z0-9+.-]*:.+"},"expires_in":{"type":"integer","minimum":1,"maximum":90}}}
      """
    Und TGR speichere Wert des Knotens "$.body.request_uri" der aktuellen Antwort in der Variable "PAR_FED_INNER_URI"

    Dann TGR finde die erste Anfrage mit Pfad "${testdata.ccPar.authorizePath}.*"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path.basicPath" überein mit "\Q${testdata.ccPar.authorizePath}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path.client_id.value" überein mit "\Q${PAR_FED_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path.request_uri.value" überein mit "\Q${PAR_FED_OUTER_URI}\E"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${testdata.ccPar.innerPath}" und Knoten "$.method" der mit "POST" übereinstimmt erfolgt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "302"
    Und TGR speichere Wert des Knotens "$.header.[~'Location']" der aktuellen Antwort in der Variable "PAR_FED_LOCATION"
    Und TGR setze lokale Variable "PAR_FED_DECODED_LOCATION" auf "!{new('org.apache.commons.codec.net.URLCodec').decode('${PAR_FED_LOCATION}')}"
    Und TGR prüfe Variable "PAR_FED_DECODED_LOCATION" stimmt überein mit "\Q${PAR_FED_AUTHORIZATION_ENDPOINT}\E\?(?=(?:[^#]*&)?client_id=\Q${paths.guard.issuerUrl}\E(?:&|$))(?=(?:[^#]*&)?request_uri=\Q${PAR_FED_INNER_URI}\E(?:&|$))[^#]+"
