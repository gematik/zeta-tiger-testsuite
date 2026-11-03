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

@UseCase_04_06
Funktionalität: JSON-Metadatenaktualisierung auf den Clientnamen begrenzen

  @A_30005
  @TA_A_30005_06
  @normal
  @Ignore
  Szenariogrundriss: redirect_uris können mit RAT nicht über JSON-PUT geändert werden (<Variante>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_MANAGED_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.registration_access_token" der aktuellen Antwort in der Variable "CC_MANAGEMENT_RAT"
    Und TGR speichere Wert des Knotens "$.body.registration_client_uri" der aktuellen Antwort in der Variable "CC_MANAGEMENT_URI"
    Und TGR prüfe Variable "CC_MANAGEMENT_URI" stimmt überein mit "https://\Q${zeta_base_url}\E(?::[0-9]+)?/[^?#]+"
    Und TGR setze lokale Feature Variable "CC_MANAGEMENT_PATH" auf "${CC_MANAGEMENT_URI}"
    Und TGR ersetze "^https://[^/]+" mit "" im Inhalt der Variable "CC_MANAGEMENT_PATH"
    Und TGR prüfe Variable "CC_MANAGEMENT_PATH" stimmt überein mit "/[^?#]+/\Q${CC_MANAGED_CLIENT}\E"

    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/registration/update" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"client_id":"${CC_MANAGED_CLIENT}","client_name":"cc-allowed-rename"}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/registration/update"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_MANAGEMENT_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_METADATA_UPDATE"
    Und übernimm ein rotiertes Registration Access Token aus "${CC_METADATA_UPDATE}" in Variable "CC_MANAGEMENT_RAT", falls vorhanden
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/registration/read" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/registration/read"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_MANAGEMENT_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.client_name" überein mit "cc-allowed-rename"
    Und TGR speichere Wert des Knotens "$.body.redirect_uris" der aktuellen Antwort in der Variable "CC_ORIGINAL_REDIRECTS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.redirect_uris" nicht überein mit ".*cc-forbidden-redirect.*"

    Und TGR lösche aufgezeichnete Nachrichten
    Und Setze im TigerProxy für die Nachricht "isRequest && request.method == 'PUT' && request.path == '${CC_MANAGEMENT_PATH}'" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      <Update>
      """
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/registration/update" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"client_id":"${CC_MANAGED_CLIENT}","client_name":"cc-valid-sdk-update"}
      """
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/registration/update"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_MANAGEMENT_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "4[0-9][0-9]"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}/testdriver-api/control/registration/read" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "/testdriver-api/control/registration/read"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_MANAGEMENT_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.redirect_uris" überein mit "\Q${CC_ORIGINAL_REDIRECTS}\E"

    Beispiele:
      | Variante                | Update                                                                                                                                                               |
      | Nur geschütztes Feld    | {"client_id":"${CC_MANAGED_CLIENT}","redirect_uris":["https://example.invalid/cc-forbidden-redirect"]}                                                               |
      | Mit erlaubtem Namen     | {"client_id":"${CC_MANAGED_CLIENT}","client_name":"cc-mixed-rename","redirect_uris":["https://example.invalid/cc-forbidden-redirect"]}                              |
