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

@UseCase_01_18
Funktionalität: Client_ressource_anfrage_fachdienst_SC_404

  @A_28426
  @TA_A_28426_03
  Szenario: Service Discovery erneut durchführen nach 404 Not Found
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Erster Verbindungsaufbau mit Service Discovery
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die erste Anfrage mit Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR finde die erste Anfrage mit Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"

    # Manipuliere die nächste Response
    Und TGR setze lokale Variable "notFoundCondition" auf "isResponse && request.path =^ '${paths.fachdienst.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${notFoundCondition}" die Manipulation auf Feld "$.responseCode" und Wert "404" und 1 Ausführungen

    Und TGR lösche aufgezeichnete Nachrichten

    # Nächste Resourceanfrage wird mit 404 beantwortet
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"

    # Sende wieder unmanipulierte Responses
    Und Alle Manipulationen im TigerProxy werden gestoppt

    # A_28426_03: Service Discovery muss erneut durchgeführt werden, wenn HTTP-Statuscode 404 empfangen wurde
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR finde die nächste Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200|304"

    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR finde die nächste Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200|304"
