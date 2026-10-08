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

@UseCase_01_14
Funktionalität: Client Ressource Anfrage Fachdienst SC 302

  @A_26661
  @A_27725-01
  @TA_A_26661_17
  @TA_A_27725-01_29
  @MASVS-RESILIENCE
  Szenario: PEP HTTP Proxy protokolliert 302 Found
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "redirectLocation" auf "https://example.invalid/achelos_testfachdienst/temporaer"
    Und TGR setze lokale Variable "redirectCondition" auf "isResponse && request.path =~ '.*${paths.fachdienst.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${redirectCondition}" die Manipulation auf Feld "$.responseCode" und Wert "302" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${redirectCondition}" die Manipulation auf Feld "${headers.location.strict}" und Wert "${redirectLocation}" und 1 Ausführungen
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    # TA_A_26661_17 - ZETA Guard - HTTP Statuscodes - HTTP Proxy - 302 Found
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "302"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.location.lenient}" überein mit "${redirectLocation}"
    Und Alle Manipulationen im TigerProxy werden gestoppt

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    # TA_A_27725-01_29 - ZETA Guard, Telemetriedaten Service, Status Codes - HTTP Proxy - 302 Found
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                        | operation                       | start           | end           | limit | tags                                                                                               |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"302","http.target":"${paths.guard.helloZetaPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
