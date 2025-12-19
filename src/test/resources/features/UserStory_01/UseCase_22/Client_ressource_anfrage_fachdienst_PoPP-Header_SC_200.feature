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
    Und TGR setze lokale Variable "proxy" auf "${paths.tigerProxy.baseUrl}"

  @A_26477
  @TA_A_25669_02
  @TA_A_25669_05
  @TA_A_26477_05 # Signatur muss mathematisch gültig sein
  @TA_A_26477_06 # Aktuelles JWKS des PoPP Server muss zur Signaturprüfung benutzt werden
  @TA_A_26477_08 # popp-token.actorId == access_token.identifier
  Szenario: PoPP Token Validation
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    # Sende Resource Anfrage
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # A_25668: "PoPP Token werden im Request Header PoPP übertragen".
    Und TGR speichere Wert des Knotens "$.header.popp" der aktuellen Anfrage in der Variable "PoPP_TOKEN"

    # Schema Prüfung gegen Schema aus der gemSpec_PoPP
    Und decodiere und validiere "${PoPP_TOKEN}" gegen Schema "schemas/mock/popp-token-gemspec_popp.yaml"

    # TA_A_26477_05 Signatur muss mathematisch gültig sein
    Und verifiziere die Signatur des JWT "${PoPP_TOKEN}"

    # TA_A_26477_08 actorID muss identisch sein mit access_token.identifier
    Und TGR speichere Wert des Knotens "$.header.authorization.client_id" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_IDENTIFIER"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.popp.actorId" überein mit "${ACCESS_TOKEN_IDENTIFIER}"

    # Zeitstempel "iat" ist nicht älter als konfiguriert
    Und TGR speichere Wert des Knotens "$.header.popp.body.iat" der aktuellen Anfrage in der Variable "PoPP_TOKEN_IAT"
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_IAT}" in der Vergangenheit liegt

    # Zeitstempel "patientProofTime" ist nicht älter als konfiguriert
    Und TGR speichere Wert des Knotens "$.header.popp.body.patientProofTime" der aktuellen Anfrage in der Variable "PoPP_TOKEN_PPT"
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_PPT}" in der Vergangenheit liegt

