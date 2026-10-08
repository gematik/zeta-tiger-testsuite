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
Funktionalität: Client Ressource Anfrage Fachdienst SC 500

  @A_26560
  @A_26661
  @A_26662
  @A_26988
  @A_26974-01
  @A_27007
  @A_27725-01
  @A_28796
  @TA_A_26560_01
  @TA_A_26661_26
  @TA_A_26662_01
  @TA_A_26988_11
  @TA_A_26974-01_01
  @TA_A_27007_26
  @TA_A_27725-01_36
  @TA_A_28796_01
  @MASVS-RESILIENCE
  Szenario: PEP HTTP Proxy antwortet mit 500 bei zeta-cause Proxy vom Resource Server
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZetaProxyError}"

    # TA_A_26560_01: Request NACH PEP - Pfad wurde gemäß Weiterleitungskonfiguration transformiert
    Und TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaProxyErrorPath}$"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "${paths.fachdienst.helloZetaProxyErrorPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.cause}" überein mit "Proxy"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "errorBody"

    # TA_A_26560_01: Request-Weiterleitung gemäß URL-Konfiguration vor PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaProxyErrorPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.zeta.cause}"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" nicht überein mit ".*${errorBody}.*"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s)
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"

    # TA_A_26988_11 - Resource Server: Fehlermeldung wird vom Telemetriedaten Service gesammelt und ist auffindbar.
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                             | start           | end           | limit | tags                                                                                                           |
      | ${telemetry.service.resourceServer} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.url":"${paths.fachdienst.helloZetaProxyErrorPath}","method":"GET","status":"400"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von traceID bedeutet, dass es mindestens einen Open Telemetry Trace für genau diese Suchparameter gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
    Und TGR speichere Wert des Knotens "$.body.data.0.traceID" der aktuellen Antwort in der Variable "TRACE_ID"

    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                        | operation                       | start           | end           | limit | tags                                                                                                    |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"500","http.target":"${paths.guard.helloZetaProxyErrorPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

    # TA_A_28796_01 - ZETA Guard - Korrelation der Telemetriedaten des Security Monitorings bzgl. A_28783
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.traceID" überein mit "${TRACE_ID}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.spans.*.tags.[?(@.key == 'gematik.zeta.kind' && @.value == 'siem')]"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.*.serviceName" überein mit "${telemetry.service.httpProxy}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.*.serviceName" überein mit "${telemetry.service.resourceServer}"
