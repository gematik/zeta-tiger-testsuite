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

@UseCase_01_04
Funktionalität: Client-Registrierung stationär SC 403

  @A_25661
  @A_26661
  @A_26662
  @TA_A_25661_03
  @TA_A_26661_13
  @TA_A_26662_01
  @normal
  @MASVS-AUTH
  Szenariogrundriss: Client-Registrierung wird wegen Client Policy (<Policy>) abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # OPA Request manipulieren - ungültige Werte setzen
    # Der Guard sendet diese Daten an OPA, wir manipulieren den Request um Policy-Ablehnungen zu testen
    Und TGR setze lokale Variable "opaCondition" auf "isRequest && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "<OpaInputField>" und Wert "<NeuerWert>" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Sicherstellen, dass die Manipulation im OPA Request angekommen ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "<OpaInputField>" überein mit "<NeuerWert>"
    # TA_A_25661_03: Manipulierte Policy Decision verweigert den Zugriff.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "false"

    # Registrierungs-/Token-Request muss mit 403 abgelehnt werden
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    # TA_A_26662_01 - ZETA Guard, HTTP Fehlerdetails
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"

    # TA_A_25661_03, TA_A_26661_13: Rückgabe an den Client ist 403 Forbidden.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    # Policy-Ablehnungsgründe:
    # - professionOID nicht unter den erlaubten Berufsgruppen
    # - product_id kein registriertes Produkt
    # - product_version keine unterstützte Version
    # - scope keine erlaubte Berechtigung
    # - audience kein erreichbarer Dienst
    Beispiele: Ungültige Policy-Werte
      | Policy          | OpaInputField                                         | NeuerWert                    |
      | professionOID   | $.body.input.user_info.professionOID                  | 1.2.276.0.76.4.999           |
      | product_id      | $.body.input.client_registration_data.product_id      | unknown_product              |
      | product_version | $.body.input.client_registration_data.product_version | 99.99.99                     |
      | scopes          | $.body.input.authorization_request.scopes.0           | invalid_scope_xyz            |
      | audience        | $.body.input.authorization_request.audience.0         | https://evil.example.com/api |

  @A_25752
  @TA_A_25752_01
  @minor
  Szenario: Client-Registrierung wird wegen Client Policy abgelehnt und begründet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # OPA Request manipulieren - eine ungültige Client-Eigenschaft setzen
    Und TGR setze lokale Variable "opaCondition" auf "isRequest && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.input.client_registration_data.product_id" und Wert "unknown_product" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_id" überein mit "unknown_product"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    # TA_A_25752_01 - PDP Client-Registrierung - Nutzer über Hintergrund zur Ablehnung der Client-Registrierung informieren - Client-Eigenschaften
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.error_description"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error_description" überein mit ".*(Client product or version is not allowed|Produkt.*(ID|Kennung|Version|nicht zugelassen|nicht registriert|unbekannt)|Client[- ]?Eigenschaften).*"
