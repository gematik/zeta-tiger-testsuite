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

@UseCase_01_15
Funktionalität: Client Ressource Anfrage Fachdienst SC 400

  @A_26477
  @A_26661
  @A_26988
  @A_27007
  @A_27725-01
  @TA_A_26477_04
  @TA_A_26661_18
  @TA_A_26988_03
  @TA_A_27007_18
  @TA_A_27725-01_30
  @MASVS-AUTH
  @MASVS-RESILIENCE
  Szenario: Fehlender PoPP-Header bei Ressource-Anfrage
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Und TGR setze lokale Variable "poppHeaderCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "${headers.popp.lineRegex}" und Wert ""
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s)
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"

    # TA_A_26988_03 - HTTP Proxy: Fehlermeldung wird vom Telemetriedaten Service gesammelt und ist in Jaeger auffindbar.
    # TA_A_27725-01_30 - Der 400-HTTP-Proxy-Request ist mit Statuscode und URL in Jaeger auffindbar.
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                        | operation                       | start           | end           | limit | tags                                                                                               |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"400","http.target":"${paths.guard.helloZetaPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.spans.*.tags.[?(@.value == 'dienst_hersteller' || @.value == 'siem' || @.value == 'monitoring')]"
