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

@UseCase_01_08
Funktionalität: Client_authentisierung_und_autorisierung_software_attest_SC_401

  @dev
  @A_25783-01
  @A_27007
  @A_26661
  @TA_A_25783-01_02
  @TA_A_27007_03
  @TA_A_26661_03
  Szenario: Erneute Authentifizierung nach 401 Unauthorized
    Gegeben sei TGR setze lokale Variable "unauthorizedCondition" auf "isResponse && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    Und Setze im TigerProxy für die Nachricht "${unauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "401" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Erste Token-Anfrage mit 401 (manipuliert)
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    # TA_A_27007_03 - ZETA Client - HTTP Statuscodes - Authentifizierung mit Client Assertion JWT - 401 Unauthorized
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    # TA_A_25783-01_02 - Anweisung befolgen erneute Authentifizierung (Nutzer-Token erneuern)
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "firstSubjectToken"

    # Nonce-Anfrage nach 401
    Und TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"

    # Zweiter Token-Request (Retry)
    Und TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    # TA_A_25783-01_02 - Anweisung befolgen erneute Authentifizierung
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token" nicht überein mit "${firstSubjectToken}"
