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

@UseCase_01_25
Funktionalität: Client_authentisierung_und_autorisierung_refresh_token_without_attest_SC_403

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Gegeben sei TGR setze lokale Variable "proxy" auf "http://${zeta_proxy_url}"
    Gegeben sei Alle Manipulationen im TigerProxy "${proxy}" werden gestoppt


  @dev
  @TA_A_25660_06
  Szenario: Refresh Token wird bei negativer Policy Decision abgelehnt (Negativtest)
    Und TGR setze lokale Variable "accessTokenTtl" auf "5"
    # WICHTIG: expires_in Manipulation MUSS VOR dem ersten Token-Request aktiviert werden!
    # Sonst hat der erste Token normales expires_in (z.B. 300s) und wird nicht refresht
    Wenn TGR setze lokale Variable "tokenResponseCondition" auf "isResponse && request.path =~ '.*/token'"
    Dann Setze im TigerProxy "${proxy}" für die Nachricht "${tokenResponseCondition}" die Manipulation auf Feld "$.body.expires_in" und Wert "${accessTokenTtl}" und 1 Ausführungen

    # Initiale Token holen (mit manipuliertem expires_in)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # OPA Response manipulieren: allow=false simuliert serverseitige Token-Invalidierung/Session-Entzug
    # Diese Manipulation greift erst beim Refresh-Request
    Wenn TGR setze lokale Variable "opaResponseCondition" auf "isResponse && $.body.result.allow"
    Dann Setze im TigerProxy "${proxy}" für die Nachricht "${opaResponseCondition}" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 1 Ausführungen

    # Warten bis Token abgelaufen ist
    Und TGR warte "${accessTokenTtl}" Sekunden

    # Neuer Request löst Refresh aus, OPA lehnt ab
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Erwarte 403 wegen Policy Engine Ablehnung (Session-Entzug)
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.error"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "invalid_grant"
