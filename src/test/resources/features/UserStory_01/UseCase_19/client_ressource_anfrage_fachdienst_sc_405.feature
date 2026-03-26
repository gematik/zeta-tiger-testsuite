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
Funktionalität: Client_ressource_anfrage_fachdienst_SC_405

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR sende eine leere GET Anfrage an "${paths.tigerProxy.baseUrl}/resetMessages"

  @A_26661
  @A_27007
  @TA_A_26661_22
  @TA_A_27007_22
  Szenario: PEP HTTP Proxy antwortet mit 405 bei nicht erlaubter HTTP Methode
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "tokenRequestCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${tokenRequestCondition}" die Regex-Manipulation auf Feld "$.method" mit Regex "GET" und Wert "HEAD"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "405"
