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

@UseCase_01_09
Funktionalität: Client_authentisierung_und_autorisierung_software_attest_SC_403

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Gegeben sei TGR setze lokale Variable "proxy" auf "http://${zeta_proxy_url}"
    Gegeben sei Alle Manipulationen im TigerProxy "${proxy}" werden gestoppt

  @dev
  @TA_A_25661_03
  @TA_A_26661_01
  @TA_A_27401_01
  Szenario: Policy Decision - Zugriffsverweigerung bei allow=false liefert HTTP 403
    # OPA Response manipulieren: allow auf false setzen
    # Bei allow=false muss Authserver mit 403 antworten
    Dann Setze im TigerProxy "${proxy}" für die Nachricht "isResponse" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_27401_01: OPA Response Schema-Validierung
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PDP_DECISION"
    Und validiere "${PDP_DECISION}" soft gegen Schema "schemas/v_1_0/pdp-decision.yaml"

    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.allow"
    # Manipulation verifizieren: allow sollte false sein
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "false"

    # TA_A_25661_03: Token Request muss mit 403 Forbidden fehlschlagen
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

