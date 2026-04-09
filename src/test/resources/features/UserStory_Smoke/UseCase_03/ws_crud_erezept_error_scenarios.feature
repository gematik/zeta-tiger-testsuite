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

@websocket
@stomp
@no_proxy
@UseCase_Smoke_03
@dev
Funktionalität: WebSocket/STOMP - E-Rezept Fehlerbehandlung und Grenzfälle

  Grundlage:
    Und setze Anfrage Timeout für WebSocket Verbindungen auf 5 Sekunden
    Und setze Timeout für WebSocket Nachrichten auf 5 Sekunden

  Szenario: CREATE - Doppelte PrescriptionId gibt Konflikt zurück
    Gegeben sei TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-WS-ERROR-${free.port.50}"
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-dup1" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
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
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "prescriptionId" den Wert "${uniquePrescriptionId}"
    Wenn Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
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
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "409"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: CREATE - Fehlende Pflichtfelder geben Validierungsfehler zurück
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-validation" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "400"
    Und hat die empfangene Nachricht im Feld "message" den Wert "Validation failed"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: CREATE - Fehlerhaftes JSON gibt BAD REQUEST zurück
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-invalid-json" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId":
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "400"
    Und hat die empfangene Nachricht im Feld "message" den Wert "Invalid message format or missing required fields"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: CREATE - Ungültiges Datumsformat gibt BAD REQUEST zurück
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-invalid-date" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "INVALID-DATE-FORMAT",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "RX-INVALID-DATE-${free.port.53}"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "400"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: READ - Nicht existierende ID gibt NOT FOUND zurück
    Gegeben sei TGR setze lokale Feature Variable "nonExistentId" auf "9223372036854775807"
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-notfound" abonniert wird
    Und eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.readPrefix}${nonExistentId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "404"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: UPDATE - Nicht existierende ID gibt NOT FOUND zurück
    Gegeben sei TGR setze lokale Feature Variable "nonExistentId" auf "9223372036854775806"
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-update-notfound" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.updatePrefix}${nonExistentId}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "3",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "RX-NONEXISTENT",
        "status": "SIGNED"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "404"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: UPDATE - Fehlende Pflichtfelder geben BAD REQUEST zurück
    Gegeben sei TGR setze lokale Feature Variable "updateValidationPrescriptionId" auf "RX-WS-UPDATE-VALIDATION-${free.port.54}"
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-update-validation" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "${updateValidationPrescriptionId}"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "prescriptionId" den Wert "${updateValidationPrescriptionId}"
    Und wird der Wert des Knotens "$.id" der empfangenen Nachricht in der Variable "updateValidationId" gespeichert
    Wenn Anfrage an Kanal "${paths.erezept.websocket.appChannels.updatePrefix}${updateValidationId}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "id": ${updateValidationId},
        "dosage": "2",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "400"
    Und hat die empfangene Nachricht im Feld "message" den Wert "Validation failed"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: DELETE - Nicht existierende ID gibt NOT FOUND zurück
    Gegeben sei TGR setze lokale Feature Variable "nonExistentId" auf "9223372036854775805"
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-delete-notfound" abonniert wird
    Und eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.deletePrefix}${nonExistentId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "404"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: DELETE - Bereits gelöschtes Rezept gibt NOT FOUND zurück
    Gegeben sei TGR setze lokale Feature Variable "doubleDeletePrescriptionId" auf "RX-WS-DOUBLE-DELETE-${free.port.55}"
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-double-delete" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "${doubleDeletePrescriptionId}"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "prescriptionId" den Wert "${doubleDeletePrescriptionId}"
    Und wird der Wert des Knotens "$.id" der empfangenen Nachricht in der Variable "doubleDeleteId" gespeichert
    Wenn eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.deletePrefix}${doubleDeleteId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "deleted"
    Wenn eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.deletePrefix}${doubleDeleteId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "404"
    Und wird die WebSocket Verbindung geschlossen

  Szenario: UPDATE - PrescriptionId Konflikt gibt Fehler zurück
    Gegeben sei TGR setze lokale Feature Variable "conflictIdA" auf "RX-WS-CONFLICT-A-${free.port.51}"
    Und TGR setze lokale Feature Variable "conflictIdB" auf "RX-WS-CONFLICT-B-${free.port.52}"
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und eine STOMP Verbindung basierend auf der zuvor geöffneten WebSocket Verbindung aufgebaut wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-conflict" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "${conflictIdA}"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "prescriptionId" den Wert "${conflictIdA}"
    Und wird der Wert des Knotens "$.id" der empfangenen Nachricht in der Variable "wsConflictIdA" gespeichert

    Wenn Anfrage an Kanal "${paths.erezept.websocket.appChannels.create}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept2.medicationName}",
        "dosage": "${eRezeptTestData.ERezept2.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept2.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept2.expiresAt}",
        "status": "${eRezeptTestData.ERezept2.status}",
        "patientId": "${eRezeptTestData.ERezept2.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept2.practitionerId}",
        "prescriptionId": "${conflictIdB}"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "prescriptionId" den Wert "${conflictIdB}"

    Wenn Anfrage an Kanal "${paths.erezept.websocket.appChannels.updatePrefix}${wsConflictIdA}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}",
        "prescriptionId": "${conflictIdB}",
        "status": "SIGNED"
      }
      """
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und hat die empfangene Nachricht im Feld "status" den Wert "409"
    Und wird die WebSocket Verbindung geschlossen
