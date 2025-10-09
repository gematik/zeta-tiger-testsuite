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

Funktionalität: Prototyp - Proof of Concept - Smoke Test

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze Anfrage Timeout auf 20 Sekunden

  Szenario: Einfache Ressource-Anfrage — Testsuite fordert über Client die Ressource vom Testfachdienst an
    Wenn TGR sende eine leere GET Anfrage an "http://zetaClient/proxy/achelos_testfachdienst/hellozeta"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/proxy/achelos_testfachdienst/hellozeta"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und gebe die Antwortzeit vom aktuellen Nachrichtenpaar aus

  Szenario: Einfache Ressource-Anfrage — Testsuite fordert über Guard die Ressource vom Testfachdienst an
    Wenn TGR sende eine leere GET Anfrage an "http://zetaGuard/achelos_testfachdienst/hellozeta"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/achelos_testfachdienst/hellozeta"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und gebe die Antwortzeit vom aktuellen Nachrichtenpaar aus



