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

@UseCase_01_09
Funktionalität: Client Authentisierung und Autorisierung Software-Attest SC 403

  @A_25661-01
  @A_26661
  @A_26662
  @A_26988
  @A_27401
  @A_27725-01
  @TA_A_25661-01_03
  @TA_A_26661_04
  @TA_A_26662_01
  @TA_A_26988_05
  @TA_A_27401_01
  @TA_A_27725-01_04
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @smb
  Szenario: Policy Decision - Zugriffsverweigerung bei allow=false liefert HTTP 403
    # TA_A_25661-01_03, TA_A_26661_04: OPA-Decision gezielt auf allow=false setzen.
    Gegeben sei TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 1 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_27401_01: PDP Decision entspricht pdp-decision.yaml.
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PDP_DECISION"
    Und validiere "${PDP_DECISION}" gegen Schema "schemas/v_2_0/schemas/pdp-decision.yaml"

    # TA_A_25661-01_03: Manipulierte Policy Decision verweigert den Zugriff.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "false"

    # TA_A_25661-01_03, TA_A_26661_04: Authorization Server quittiert allow=false am Token-Endpunkt mit 403 Forbidden.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    # TA_A_26662_01: Fehlerantwort enthält ein JSON-Objekt gemäß zeta-error.yaml.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "access_denied"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"

    # TA_A_25661-01_03, TA_A_26661_04: Rückgabe an den Client ist 403 Forbidden.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s)
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"

    # TA_A_26988_05 - Authorization Server: Fehlermeldung wird vom Telemetriedaten Service gesammelt und ist in Jaeger auffindbar.
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"403","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_27725-01_04 - Der 403-Token-Request ist mit Statuscode und URL in Jaeger auffindbar.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
