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

@UseCase_01_26
@deployment_modification
@require_kubectl
Funktionalität: Statische PEP-Routen und ihre logische Audience konfigurieren

  @A_26560-01
  @A_29693
  @TA_A_26560-01_01
  @TA_A_26560-01_02
  @TA_A_29693_02
  Szenario: Die konfigurierte Ziel-URL bestimmt die Weiterleitung bei unveränderter Token-Audience
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "ROUTE_CLIENT"
    Und TGR setze lokale Variable "ROUTE_RESOURCE" auf "https://${zeta_base_url}${paths.guard.pepRoutePrefix}"
    Und TGR setze lokale Variable "ROUTE_AUDIENCE" auf "https://${zeta_base_url}"
    Und TGR setze lokale Variable "ROUTE_AUDIENCE_CLAIMS" auf '{"aud":"${ROUTE_AUDIENCE}"}'
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthProtectedResourcePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.resource" überein mit "\Q${ROUTE_RESOURCE}\E"
    Und Setze im TigerProxy folgende Manipulation:
      """
      {
        "when": {
          "isResponse": true,
          "requestPathMatches": ".*${paths.opa.decisionPath}",
          "requestBody": {
            "$.input.client_registration_data.client_id": "${ROUTE_CLIENT}",
            "$.input.authorization_request.grant_type": "${oauth_parameters.grant_type.tokenExchange}"
          }
        },
        "replace": {
          "path": "$.body.result",
          "value": {
            "allow": true,
            "ttl": {
              "access_token": 300,
              "refresh_token": 86400
            },
            "aud": "${ROUTE_AUDIENCE}"
          }
        },
        "executions": 20
      }
      """
    Und Setze im TigerProxy für die Nachricht "$.path =~ '.*${paths.guard.tokenEndpointPath}$' && $.method == 'POST'" die Regex-Manipulation auf Feld "$.body" mit Regex "(^|&)audience=[^&]*" und Wert "$1audience=!{urlEncoded('${ROUTE_AUDIENCE}')}" und 20 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${ROUTE_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.audience" überein mit "\Q!{urlEncoded('${ROUTE_AUDIENCE}')}\E"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.resource"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token.body" der aktuellen Antwort in der Variable "ROUTE_TOKEN_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${ROUTE_TOKEN_CLAIMS}" in Variable "ROUTE_TOKEN_AUDIENCE"
    Und TGR prüfe Variable "ROUTE_TOKEN_AUDIENCE" stimmt überein mit "\Q${ROUTE_AUDIENCE}\E"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${ROUTE_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.grant_type" überein mit "${oauth_parameters.grant_type.tokenExchange}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.audience.0" überein mit "\Q${ROUTE_AUDIENCE}\E"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.input.authorization_request.audience.1"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.input.authorization_request.resource"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR speichere Wert des Knotens "$.body.result" der aktuellen Antwort in der Variable "ROUTE_POLICY_RESULT"
    Und speichere die einzelne Audience aus JSON "${ROUTE_POLICY_RESULT}" in Variable "ROUTE_POLICY_AUDIENCE"
    Und TGR prüfe Variable "ROUTE_POLICY_AUDIENCE" stimmt überein mit "\Q${ROUTE_AUDIENCE}\E"
    Und setze die erwartete Audience aus Access Token Claims "${ROUTE_AUDIENCE_CLAIMS}" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und setze das statische Weiterleitungsziel "${paths.fachdienst.routingUpstream}${paths.fachdienst.upstreamBasePath}${paths.fachdienst.helloZetaPath}" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${zeta_base_url}\E"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "ROUTE_REQUEST_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${ROUTE_REQUEST_CLAIMS}" in Variable "ROUTE_REQUEST_AUDIENCE"
    Und TGR prüfe Variable "ROUTE_REQUEST_AUDIENCE" stimmt überein mit "\Q${ROUTE_AUDIENCE}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und setze das statische Weiterleitungsziel "${paths.fachdienst.routingUpstream}${paths.fachdienst.upstreamBasePath}${paths.fachdienst.helloZetaProxyErrorPath}" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaProxyErrorPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.cause}" überein mit "Proxy"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "ROUTE_REQUEST_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${ROUTE_REQUEST_CLAIMS}" in Variable "ROUTE_REQUEST_AUDIENCE"
    Und TGR prüfe Variable "ROUTE_REQUEST_AUDIENCE" stimmt überein mit "\Q${ROUTE_AUDIENCE}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^${paths.fachdienst.helloZetaPath}$" hat

    Und Alle Manipulationen im TigerProxy werden gestoppt

  @A_29676
  @TA_A_29676_01
  @MASVS-AUTH
  Szenario: PEP weist eine nicht zur Route passende Audience mit 403 ohne Weiterleitung ab
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "STRICT_CONTROL_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${STRICT_CONTROL_CLAIMS}" in Variable "STRICT_CONTROL_AUDIENCE"
    Und TGR prüfe Variable "STRICT_CONTROL_AUDIENCE" stimmt überein mit "(?!\Qurn:gematik:testsuite:unrelated-route\E$).*"
    Und setze die erwartete Audience aus Access Token Claims "${STRICT_CONTROL_CLAIMS}" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und setze das statische Weiterleitungsziel "${paths.fachdienst.routingUpstream}${paths.fachdienst.upstreamBasePath}${paths.fachdienst.helloZetaPath}" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "STRICT_ACCEPTED_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${STRICT_ACCEPTED_CLAIMS}" in Variable "STRICT_ACCEPTED_AUDIENCE"
    Und TGR prüfe Variable "STRICT_ACCEPTED_AUDIENCE" stimmt überein mit "\Q${STRICT_CONTROL_AUDIENCE}\E"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "STRICT_ACCEPTED_AUTHORIZATION"

    Und setze die erwartete Audience aus Access Token Claims '{"aud":"urn:gematik:testsuite:unrelated-route"}' für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "$.method" der mit "GET" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "\Q${STRICT_ACCEPTED_AUTHORIZATION}\E"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body.exp" der aktuellen Anfrage in der Variable "STRICT_TOKEN_EXP"
    Und validiere, dass der Zeitstempel "${STRICT_TOKEN_EXP}" in der Zukunft liegt
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/(?:testfachdienst/)?achelos_testfachdienst(?:/.*)?$" hat
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26560-01
  @TA_A_26560-01_03
  Szenario: Eine gültige Token-Audience ersetzt keine fehlende statische Pfad-Zuordnung
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "UNMAPPED_CONTROL_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${UNMAPPED_CONTROL_CLAIMS}" in Variable "UNMAPPED_AUDIENCE"
    Und setze das statische Weiterleitungsziel "${paths.fachdienst.routingUpstream}${paths.fachdienst.upstreamBasePath}${paths.fachdienst.helloZetaPath}" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und begrenze die generische PEP-Route im ZETA Deployment auf ihren exakten Pfad
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "UNMAPPED_REQUEST_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${UNMAPPED_REQUEST_CLAIMS}" in Variable "UNMAPPED_REQUEST_AUDIENCE"
    Und TGR prüfe Variable "UNMAPPED_REQUEST_AUDIENCE" stimmt überein mit "\Q${UNMAPPED_AUDIENCE}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}/cc-unmapped"
    Dann TGR finde die erste Anfrage mit Pfad "^${paths.guard.helloZetaPath}/cc-unmapped$" und Knoten "$.method" der mit "GET" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "UNMAPPED_REQUEST_CLAIMS"
    Und speichere die einzelne Audience aus JSON "${UNMAPPED_REQUEST_CLAIMS}" in Variable "UNMAPPED_REQUEST_AUDIENCE"
    Und TGR prüfe Variable "UNMAPPED_REQUEST_AUDIENCE" stimmt überein mit "\Q${UNMAPPED_AUDIENCE}\E"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body.exp" der aktuellen Anfrage in der Variable "UNMAPPED_TOKEN_EXP"
    Und validiere, dass der Zeitstempel "${UNMAPPED_TOKEN_EXP}" in der Zukunft liegt
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.responseCode"
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "^/(?:testfachdienst/)?achelos_testfachdienst(?:/.*)?$" hat
