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

@UseCase_01_19
Funktionalität: Client Ressource Anfrage Fachdienst SC 405

  @A_26661
  @A_27007
  @A_27265
  @A_27725-01
  @TA_A_26661_22
  @TA_A_27007_22
  @TA_A_27265_02
  @TA_A_27725-01_34
  @normal
  @MASVS-AUTH
  @MASVS-RESILIENCE
  Szenario: PEP HTTP Proxy antwortet mit 405 bei nicht erlaubter HTTP Methode
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Hole einen Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"

    # HTTP Methode muss mit dpop übereinstimmen
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.htm" auf Wert "DELETE" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen

    Und TGR setze lokale Variable "tokenRequestCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${tokenRequestCondition}" die Regex-Manipulation auf Feld "$.method" mit Regex "GET" und Wert "DELETE"
    Und TGR lösche aufgezeichnete Nachrichten
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # TA_A_27265_02: PEP HTTP Proxy leitet die manipulierte Request-Zeile unverändert an den Resource Server weiter
    Und TGR finde die erste Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR speichere Wert des Knotens "$.method" der aktuellen Anfrage in der Variable "fachdienstRequestMethod"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "fachdienstRequestPath"
    Und TGR speichere Wert des Knotens "$.httpVersion" der aktuellen Anfrage in der Variable "fachdienstRequestHttpVersion"
    # Veränderte Request.Method aus Request Zeile wird unverändert weitergereicht
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "DELETE"

    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "405"
    # Request Zeile wird in Gänze unverändert weitergereicht
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "${fachdienstRequestMethod}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.httpVersion" überein mit "${fachdienstRequestHttpVersion}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "${fachdienstRequestPath}"

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                        | operation                       | start           | end           | limit | tags                                                                                               |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"405","http.target":"${paths.guard.helloZetaPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
