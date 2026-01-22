#
# #%L
# ZETA Testsuite
# %%
# (C) 2025 achelos GmbH, licensed for gematik GmbH
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

@UseCase_01_22
Funktionalität: Client_ressource_anfrage_fachdienst_PoPP-Header_SC_200

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt

  @A_26493
  @TA_A_26493_01
  @longrunning
  Szenario: PoPP JWKS wird nach einem zweiten Ablauf nicht weiterverwendet, wenn kein neues JWKS ladbar ist
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppJwksPath" auf "${paths.popp.jwks}"
    Und TGR setze lokale Variable "poppJwksResponseCondition" auf "isResponse && request.path =~ '.*${poppJwksPath}'"
    # 24h ist ein JWKS Abruf gültig
    Und TGR setze lokale Variable "poppJwksExpiryWait" auf "86400"
    # Alle 5 Minuten erfolgt ein JWKS Abruf
    Und TGR setze lokale Variable "poppJwksRequestWait" auf "300"

    # Erster Ressource Abruf triggert JWKS-Download mit kurzer Cache-Dauer
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # JWKS ablaufen lassen und erneuten Download für 24h fehlschlagen lassen
    Und Setze im TigerProxy für die Nachricht "${poppJwksResponseCondition}" die Manipulation auf Feld "$.responseCode" und Wert "500"
    Und warte "${poppJwksRequestWait}" Sekunden
    # Prüfen das JWKS beim ersten Interval nicht abgerufen werden konnte
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Prüfen das JWKS nach 24h nicht abgefragt werden konnte
    Und warte "${poppJwksExpiryWait}" Sekunden
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Erneute Ressource Anfrage - bestehendes JWKS wird weiter genutzt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Erneuter Testlauf bei dem nach weiteren 24h JWKS nicht mehr verwendet werden darf
    # JWKS ablaufen lassen und erneuten Download für 24h fehlschlagen lassen
    Und Setze im TigerProxy für die Nachricht "${poppJwksResponseCondition}" die Manipulation auf Feld "$.responseCode" und Wert "500"
    Und warte "${poppJwksRequestWait}" Sekunden
    # Prüfen das JWKS beim nächsten Interval nicht abgerufen werden konnte
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Prüfen das JWKS nach 24h nicht abgefragt werden konnte
    Und warte "${poppJwksExpiryWait}" Sekunden
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Erneute Ressource Anfrage - bestehendes JWKS wird weiter genutzt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    