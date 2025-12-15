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

@UseCase_Smoke_01
Funktionalität: Smoke Test

  @no_proxy
  @staging
  @smoke
  @TA_A_26640_01
  Szenariogrundriss: Einfache Ressource-Anfrage — Ein Client fordert die "Hello ZETA!" Resource vom Testfachdienst an
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "anfrage" auf "<anfrage>"
    Und TGR gebe variable "anfrage" aus
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Dann gebe die Antwortzeit vom aktuellen Nachrichtenpaar aus

    Beispiele:
      | anfrage        |
      | erste Anfrage  |
      | zweite Anfrage |
      | dritte Anfrage |
