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

@UseCase_Smoke_01
Funktionalität: Smoke Test

  @A_26640
  @TA_A_26640_01
  @blocker
  @critical
  @no_proxy
  @MASVS-NETWORK
  Szenariogrundriss: Einfache Ressource-Anfrage — Ein Client fordert die "Hello ZETA!" Resource vom Testfachdienst an
    Wenn TGR setze lokale Variable "anfrage" auf "<anfrage>"
    Und TGR gebe variable "anfrage" aus
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    # TA_A_26640_01 - ZETA Guard - HTTP Protokoll-Version HTTP/1.1
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Dann gebe die Antwortzeit vom aktuellen Nachrichtenpaar aus

    Beispiele:
      | anfrage        |
      | erste Anfrage  |
      | zweite Anfrage |
      | dritte Anfrage |

  @normal
  Szenario: SMC-B OCSP-Anfrage wird über Tiger Proxy sichtbar
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "^/ecc-ocsp"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.responseCode"

  # requires globally enabled ASL; see tiger/defaults.yaml -> zeta_k8s_enable_asl_globally
  @Ignore
  @blocker
  @deployment_modification
  @no_proxy
  Szenario: Einfache Ressource-Anfrage mit ASL — Ein Client fordert die "Hello ZETA!" Resource vom Testfachdienst an
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Dann TGR finde die letzte Anfrage mit dem Pfad "^/ASL/[^/]+$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.sender" überein mit "pep-proxy-svc:80"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.decrypted.body.message"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.decrypted.body.message" überein mit "Hello ZETA!"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Dann gebe die Antwortzeit vom aktuellen Nachrichtenpaar aus
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

  @Ignore
  @blocker
  @deployment_modification
  @popp_deployment_toggle
  Szenario: Einfache Ressource-Anfrage mit PoPP Toggle im Deployment
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.insurerId}" der aktuellen Anfrage in der Variable "PoPP_INSURER_ID"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-foreign_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Dann Setze im TigerProxy für JWT in "${headers.popp.root}" das Feld "body.insurerId" auf Wert "${PoPP_INSURER_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 2 Ausführungen und ersetze JWK

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und aktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @Ignore
  @blocker
  @deployment_modification
  @popp_deployment_toggle
  Szenario: Einfache Ressource-Anfrage mit PoPP Toggle und impliziter Wiederherstellung der Konfiguration
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.popp.body.insurerId}" der aktuellen Anfrage in der Variable "PoPP_INSURER_ID"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-foreign_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.insurerId" auf Wert "${PoPP_INSURER_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 2 Ausführungen und ersetze JWK

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
