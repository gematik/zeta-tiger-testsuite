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
@require_kubectl
@oidc
@flow_afo_continuation
@reset_testdriver
Funktionalität: OIDC-Teilabläufe für den fachlichen Zugriff orchestrieren

  @A_29659
  @TA_A_29659_01
  @MASVS-AUTH
  Szenario: Mobiler OIDC-Zugriff verbindet PAR, Authentisierung, Tokenbezug und fachliche Antwort
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "FLOW_KVNR"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "tiger.lib.validation.messageSortOrder" auf "TIMESTAMP"
    Und TGR setze lokale Variable "FLOW_EXPECTED_IDP" auf "${testdata.ccOidcAcr.issuer}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "FLOW_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "FLOW_JWKS"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "FLOW_RESULT"
    Dann TGR finde die erste Anfrage mit Pfad "${testdata.ccPar.outerPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.idp_iss" überein mit "\Q!{urlEncoded('${FLOW_EXPECTED_IDP}')}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.request_uri" der aktuellen Antwort in der Variable "FLOW_OUTER_URI"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.request_uri" der mit "\Q${FLOW_OUTER_URI}\E" übereinstimmt
    Und TGR finde die nächste Nachricht mit Knoten "$.path" der mit "\Q${testdata.ccPar.innerPath}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.request_uri" der aktuellen Antwort in der Variable "FLOW_INNER_URI"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.request_uri" der mit "\Q${FLOW_INNER_URI}\E" übereinstimmt
    Und TGR finde die nächste Nachricht mit Knoten "$.path" der mit "\Q${paths.sectoralIdp.tokenEndpointPath}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "authorization_code"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.id_token"
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "authorization_code"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${FLOW_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${FLOW_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "FLOW_ISSUANCE_REQUEST"
    Und TGR speichere Wert des Knotens "$.body.grant_type" der aktuellen Anfrage in der Variable "FLOW_ISSUANCE_GRANT"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "FLOW_ACCESS"
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${FLOW_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.grant_type" überein mit "\Q!{new('org.apache.commons.codec.net.URLCodec').decode('${FLOW_ISSUANCE_GRANT}')}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "FLOW_POLICY_REQUEST"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.result.allow" der mit "true" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "\Q${paths.opa.decisionPath}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body" überein mit "\Q${FLOW_POLICY_REQUEST}\E"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.access_token" der mit "\Q${FLOW_ACCESS}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "\Q${paths.guard.tokenEndpointPath}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body" überein mit "\Q${FLOW_ISSUANCE_REQUEST}\E"
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.root}" der mit "(?i:DPoP) \Q${FLOW_ACCESS}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "\Q${FLOW_RESULT}\E"
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "\Q${FLOW_RESULT}\E"
