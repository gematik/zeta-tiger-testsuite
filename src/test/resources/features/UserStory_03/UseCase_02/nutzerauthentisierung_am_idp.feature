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

@UseCase_03_02
@require_kubectl
@oidc
@reset_testdriver
Funktionalität: Erfolgreiche Nutzerauthentisierung am sektoralen IDP

  @A_29671
  @TA_A_29671_01
  @normal
  Szenario: Der SDK-Fluss verwendet den inneren PAR und übernimmt den IDP-Code mit seinem State
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_IDP_KVNR"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}?cc_pkce_flow=${CC_IDP_KVNR}" ohne auf Antwort zu warten
    Und warte auf die erfolgreiche PKCE-Testdriver-Antwort am Pfad "${paths.client.helloZetaPath}" für Markierung "${CC_IDP_KVNR}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${testdata.ccPar.innerPath}" haben
    Dann TGR finde die letzte Anfrage mit dem Pfad "${testdata.ccPar.innerPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "POST"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "201"
    Und TGR speichere Wert des Knotens "${body.oidc.parRequest.clientId}" der aktuellen Anfrage in der Variable "CC_IDP_CLIENT"
    Und TGR speichere Wert des Knotens "${body.oidc.parRequest.state}" der aktuellen Anfrage in der Variable "CC_IDP_STATE"
    Und TGR speichere Wert des Knotens "${body.oidc.parRequest.redirectUri}" der aktuellen Anfrage in der Variable "CC_IDP_REDIRECT"
    Und TGR speichere Wert des Knotens "${body.oidc.parResponse.requestUri}" der aktuellen Antwort in der Variable "CC_IDP_REQUEST_URI"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.sectoralIdp.authorizationEndpointPath}" und Knoten "${body.oidc.authorizationRequest.requestUri}" der mit "\Q${CC_IDP_REQUEST_URI}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.authorizationRequest.clientId}" überein mit "client_id=\Q${CC_IDP_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.authorizationRequest.requestUri}" überein mit "\Q${CC_IDP_REQUEST_URI}\E"
    Und TGR speichere Wert des Knotens "${headers.host}" der aktuellen Anfrage in der Variable "CC_IDP_HOST"

    Dann TGR finde die erste Nachricht mit Knoten "${headers.location.state.lenient}" der mit "state=\Q${CC_IDP_STATE}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${CC_IDP_HOST}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "302"
    Und TGR speichere Wert des Knotens "${headers.location.basicPath.lenient}" der aktuellen Antwort in der Variable "CC_IDP_CALLBACK"
    Und TGR assert "!{urlEncoded('${CC_IDP_CALLBACK}')}" matches "\Q${CC_IDP_REDIRECT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.location.code.lenient}" überein mit ".+"
    Und TGR speichere Wert des Knotens "${headers.location.code.lenient}" der aktuellen Antwort in der Variable "CC_IDP_CODE"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.brokerEndpointPath}" und Knoten "${body.oidc.callbackRequest.code}" der mit "\Q${CC_IDP_CODE}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.callbackRequest.code}" überein mit "\Q${CC_IDP_CODE}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.callbackRequest.state}" überein mit "state=\Q${CC_IDP_STATE}\E"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.sectoralIdp.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.tokenRequest.code}" überein mit "\Q!{urlEncoded('${CC_IDP_CODE}')}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
