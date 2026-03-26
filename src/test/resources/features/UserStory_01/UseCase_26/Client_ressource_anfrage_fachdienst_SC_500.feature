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
Funktionalität: Client_ressource_anfrage_fachdienst_SC_500

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR sende eine leere GET Anfrage an "${paths.tigerProxy.baseUrl}/resetMessages"

  @A_26560
  @A_26661
  @A_26662
  @A_26974
  @A_27007
  @TA_A_26560_01
  @TA_A_26661_26
  @TA_A_26662_01
  @TA_A_26974_01
  @TA_A_27007_26
  Szenario: PEP HTTP Proxy antwortet mit 500 bei ZETA-Cause Proxy vom Resource Server
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZetaProxyError}"

    # TA_A_26560_01: Request NACH PEP - Pfad wurde gemäß Weiterleitungskonfiguration transformiert
    Und TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaProxyErrorPath}$"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "${paths.fachdienst.helloZetaProxyErrorPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.cause}" überein mit "Proxy"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "errorBody"

    # TA_A_26560_01: Request-Weiterleitung gemäß URL-Konfiguration vor PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaProxyErrorPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.zeta.cause}"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" nicht überein mit ".*${errorBody}.*"

