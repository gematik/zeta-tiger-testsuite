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

@no_proxy
@UseCase_Smoke_02
Funktionalität: REST API - E-Rezept CRUD Lebenszyklus Test

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze den default header "Content-Type" auf den Wert "application/json"

  @staging
  Szenariogrundriss: CRUD - Rezept anlegen lesen aktualisieren löschen
    # Setup
    Und TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-SMOKE-<lauf>-${free.port.50}"

    # CREATE
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}" mit folgenden mehrzeiligen Daten:
      """
      {
        "medicationName": "${eRezeptTestData.<erezept>.medicationName}",
        "dosage": "${eRezeptTestData.<erezept>.dosage}",
        "issuedAt": "${eRezeptTestData.<erezept>.issuedAt}",
        "expiresAt": "${eRezeptTestData.<erezept>.expiresAt}",
        "status": "${eRezeptTestData.<erezept>.status}",
        "patientId": "${eRezeptTestData.<erezept>.patientId}",
        "practitionerId": "${eRezeptTestData.<erezept>.practitionerId}",
        "prescriptionId": "${uniquePrescriptionId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Dann TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als JSON überein mit:
    """
      {
        "medicationName" : "${eRezeptTestData.<erezept>.medicationName}",
        "dosage" : "${eRezeptTestData.<erezept>.dosage}",
        "issuedAt" : "${eRezeptTestData.<erezept>.issuedAt}",
        "expiresAt" : "${eRezeptTestData.<erezept>.expiresAt}",
        "status" : "${eRezeptTestData.<erezept>.status}",
        "patientId" : "${eRezeptTestData.<erezept>.patientId}",
        "practitionerId" : "${eRezeptTestData.<erezept>.practitionerId}",
        "prescriptionId" : "${uniquePrescriptionId}"
      }
    """
    Und TGR speichere Wert des Knotens "$.body.id" der aktuellen Antwort in der Variable "dbCreatedId"

    # READ by DB-ID
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${dbCreatedId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Dann TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als JSON überein mit:
    """
      {
        "id" : "${dbCreatedId}",
        "prescriptionId" : "${uniquePrescriptionId}",
        "medicationName" : "${eRezeptTestData.<erezept>.medicationName}",
        "dosage" : "${eRezeptTestData.<erezept>.dosage}",
        "issuedAt" : "${eRezeptTestData.<erezept>.issuedAt}",
        "expiresAt" : "${eRezeptTestData.<erezept>.expiresAt}",
        "status" : "${eRezeptTestData.<erezept>.status}",
        "patientId" : "${eRezeptTestData.<erezept>.patientId}",
        "practitionerId" : "${eRezeptTestData.<erezept>.practitionerId}"
      }
      """

    # READ by prescriptionId
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/by-prescription/${uniquePrescriptionId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/by-prescription/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als JSON überein mit:
    """
      {
        "id" : "${dbCreatedId}",
        "prescriptionId" : "${uniquePrescriptionId}",
        "medicationName" : "${eRezeptTestData.<erezept>.medicationName}",
        "dosage" : "${eRezeptTestData.<erezept>.dosage}",
        "issuedAt" : "${eRezeptTestData.<erezept>.issuedAt}",
        "expiresAt" : "${eRezeptTestData.<erezept>.expiresAt}",
        "status" : "${eRezeptTestData.<erezept>.status}",
        "patientId" : "${eRezeptTestData.<erezept>.patientId}",
        "practitionerId" : "${eRezeptTestData.<erezept>.practitionerId}"
      }
      """

    # LIST
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.0.prescriptionId"

    # UPDATE
    Wenn TGR sende eine PUT Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${dbCreatedId}" mit folgenden mehrzeiligen Daten:
      """
      {
        "id": ${dbCreatedId},
        "medicationName": "${eRezeptTestData.<erezept>.medicationName}",
        "dosage": "${eRezeptTestData.<erezept>.dosage}",
        "issuedAt": "${eRezeptTestData.<erezept>.issuedAt}",
        "expiresAt": "${eRezeptTestData.<erezept>.expiresAt}",
        "status": "SIGNED",
        "patientId": "${eRezeptTestData.<erezept>.patientId}",
        "practitionerId": "${eRezeptTestData.<erezept>.practitionerId}",
        "prescriptionId": "${uniquePrescriptionId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # VERIFY UPDATE
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${dbCreatedId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als JSON überein mit:
    """
      {
        "id" : "${dbCreatedId}",
        "prescriptionId" : "${uniquePrescriptionId}",
        "medicationName" : "${eRezeptTestData.<erezept>.medicationName}",
        "dosage" : "${eRezeptTestData.<erezept>.dosage}",
        "issuedAt" : "${eRezeptTestData.<erezept>.issuedAt}",
        "expiresAt" : "${eRezeptTestData.<erezept>.expiresAt}",
        "status" : "SIGNED",
        "patientId" : "${eRezeptTestData.<erezept>.patientId}",
        "practitionerId" : "${eRezeptTestData.<erezept>.practitionerId}"
      }
      """

    # DELETE
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${dbCreatedId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "204"

    # VERIFY DELETE (404)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${dbCreatedId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"

    Beispiele:
      | lauf | erezept  |
      | 1    | ERezept1 |
      | 2    | ERezept2 |
      | 3    | ERezept3 |

  Szenario: CREATE - Doppelte PrescriptionId gibt 409 CONFLICT zurück
    Und TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-ERROR-${free.port.50}"
    # Erstes Rezept erstellen
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}" mit folgenden mehrzeiligen Daten:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "${uniquePrescriptionId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.id" der aktuellen Antwort in der Variable "duplicateTestId"

    # Versuch doppeltes Rezept zu erstellen - erwarte 409 CONFLICT
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}" mit folgenden mehrzeiligen Daten:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept2.medicationName}",
        "dosage": "${eRezeptTestData.ERezept2.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept2.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept2.expiresAt}",
        "status": "${eRezeptTestData.ERezept2.status}",
        "patientId": "${eRezeptTestData.ERezept2.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept2.practitionerId}",
        "prescriptionId": "${uniquePrescriptionId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "409"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @staging
  Szenario: CREATE - Fehlende Pflichtfelder geben 400 BAD REQUEST zurück
    Und TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-ERROR-${free.port.50}"
    # Fehlendes Pflichtfeld: medicationName
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}" mit folgenden mehrzeiligen Daten:
      """
      {
        "dosage": "${eRezeptTestData.ERezept2.dosage}",
        "patientId": "PAT-ERROR-001",
        "patientId": "${eRezeptTestData.ERezept2.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept2.practitionerId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @staging
  Szenario: CREATE - Fehlerhaftes JSON gibt 400 BAD REQUEST zurück
    Und TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-ERROR-${free.port.50}"
    # Fehlerhaftes JSON senden
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}" mit folgenden mehrzeiligen Daten:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept2.medicationName}",
        "dosage": "${eRezeptTestData.ERezept2.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept2.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept2.expiresAt}",
        "status": "${eRezeptTestData.ERezept2.status}",
        "patientId": "${eRezeptTestData.ERezept2.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept2.practitionerId}",
        "prescriptionId":
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  Szenario: READ - Nicht existierende ID gibt 404 NOT FOUND zurück
    Und TGR setze lokale Feature Variable "nonExistentId" auf "999999"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${nonExistentId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  Szenario: READ nach PrescriptionId - Nicht existierende gibt 404 NOT FOUND zurück
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/by-prescription/RX-DOES-NOT-EXIST"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/by-prescription/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  Szenario: UPDATE - Nicht existierende ID gibt 404 NOT FOUND zurück
    Und TGR setze lokale Feature Variable "nonExistentId" auf "999999"
    Und TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-ERROR-${free.port.50}"
    Wenn TGR sende eine PUT Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${nonExistentId}" mit folgenden mehrzeiligen Daten:
      """
      {
        "id": ${nonExistentId},
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "SIGNED",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "${uniquePrescriptionId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  Szenario: UPDATE - Fehlende Pflichtfelder geben 400 BAD REQUEST zurück
    Gegeben sei Variable "duplicateTestId" existiert
    # Aktualisierung ohne Pflichtfeld: medicationName
    Wenn TGR sende eine PUT Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${duplicateTestId}" mit folgenden mehrzeiligen Daten:
      """
      {
        "id": ${duplicateTestId},
        "dosage": "2",
        "patientId": "PAT-ERROR-001",
        "practitionerId": "PRAC-ERROR-999"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  Szenario: DELETE - Nicht existierende ID gibt 404 NOT FOUND zurück
    Und TGR setze lokale Feature Variable "nonExistentId" auf "999999"
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${nonExistentId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  Szenario: DELETE -  Bereits gelöschtes Rezept gibt 404 NOT FOUND zurück
    Gegeben sei Variable "duplicateTestId" existiert
    # Rezept aus Duplikat-Test löschen
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${duplicateTestId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "204"

    # Zweiter Löschversuch - erwarte 404 NOT FOUND
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}/${duplicateTestId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}/.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @staging
  Szenario: CREATE - Ungültiges Datumsformat gibt 400 BAD REQUEST zurück
    Und TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-ERROR-${free.port.50}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.baseUrl}${paths.erezept.rest.proxyPath}" mit folgenden mehrzeiligen Daten:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "invalid-date-format",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "${uniquePrescriptionId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.erezept.rest.proxyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
