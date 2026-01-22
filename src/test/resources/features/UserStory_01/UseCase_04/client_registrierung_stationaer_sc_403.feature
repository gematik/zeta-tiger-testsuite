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

@UseCase_01_04
Funktionalität: client_registrierung_stationaer_sc_403

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "proxy" auf "http://${zeta_proxy_url}"
    Und Alle Manipulationen im TigerProxy "${proxy}" werden gestoppt

  @dev
  @A_25653
  @A_25752
  @TA_A_25653_02
  @TA_A_25752_01
  Szenariogrundriss: Client-Registrierung wird wegen Client Policy abgelehnt und begründet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # OPA Request manipulieren - ungültige Werte setzen
    # Der Guard sendet diese Daten an OPA, wir manipulieren den Request um Policy-Ablehnungen zu testen
    Und TGR setze lokale Variable "opaCondition" auf "isRequest && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy "${proxy}" für die Nachricht "${opaCondition}" die Manipulation auf Feld "<OpaInputField>" und Wert "<NeuerWert>" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # OPA Decision prüfen - sollte allow=false liefern
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "false"

    # Registrierungs-/Token-Request muss mit 403 abgelehnt werden
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error_description" überein mit ".*<ErwarteterHinweis>.*"

    # Policy-Ablehnungsgründe:
    # - professionOID nicht unter den erlaubten Berufsgruppen
    # - product_id kein registriertes Produkt
    # - product_version keine unterstützte Version
    # - scope keine erlaubte Berechtigung
    # - audience kein erreichbarer Dienst
    Beispiele: Ungültige Policy-Werte
      | OpaInputField                                         | NeuerWert                    | ErwarteterHinweis                                               |
      | $.body.input.user_info.professionOID                  | 1.2.276.0.76.4.999           | ${testdata.policy_rejection.invalid_profession_oid_error_hint}  |
      | $.body.input.client_assertion.posture.product_id      | unknown_product              | ${testdata.policy_rejection.invalid_product_id_error_hint}      |
      | $.body.input.client_assertion.posture.product_version | 99.99.99                     | ${testdata.policy_rejection.invalid_product_version_error_hint} |
      | $.body.input.authorization_request.scopes.0           | invalid_scope_xyz            | ${testdata.policy_rejection.invalid_scope_error_hint}           |
      | $.body.input.authorization_request.aud.0              | https://evil.example.com/api | ${testdata.policy_rejection.invalid_audience_error_hint}        |
