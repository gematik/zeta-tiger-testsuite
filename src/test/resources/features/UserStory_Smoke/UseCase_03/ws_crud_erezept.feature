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
@UseCase_Smoke_03
Funktionalität: WebSocket/STOMP - E-Rezept CRUD Lifecycle Test

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und setze Anfrage Timeout für WebSocket Verbindungen auf 5 Sekunden
    Und setze Timeout für WebSocket Nachrichten auf 5 Sekunden
    Und TGR setze lokale Feature Variable "uniquePrescriptionId" auf "RX-WS-SMOKE-${free.port.50}"

  Szenario: CREATE - Rezept erstellen und ID speichern
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-create" abonniert wird
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
    Und stimmt empfangene Nachricht als JSON überein mit:
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
    Und wird der Wert des Knotens "$.id" der empfangenen Nachricht in der Variable "wsCreatedId" gespeichert
    Und wird die WebSocket Verbindung geschlossen

  Szenario: READ - Rezept mit Datenbank-ID lesen
    Gegeben sei Variable "wsCreatedId" existiert
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-read" abonniert wird
    Und eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.readPrefix}${wsCreatedId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und stimmt empfangene Nachricht als JSON überein mit:
      """
      {
        "id": ${wsCreatedId},
        "prescriptionId": "${uniquePrescriptionId}",
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "${eRezeptTestData.ERezept1.status}",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}"
      }
      """
    Und wird die WebSocket Verbindung geschlossen

  Szenario: Alle Rezepte AUFLISTEN
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-list" abonniert wird
    Und eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.list}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und wird die WebSocket Verbindung geschlossen

  Szenario: UPDATE - Rezeptstatus auf SIGNED aktualisieren
    Gegeben sei Variable "wsCreatedId" existiert
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-update" abonniert wird
    Und Anfrage an Kanal "${paths.erezept.websocket.appChannels.updatePrefix}${wsCreatedId}" mit folgenden JSON Daten gesendet wird:
      """
      {
        "id": ${wsCreatedId},
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
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und wird die WebSocket Verbindung geschlossen

  Szenario: Aktualisierten Status als gespeichert verifizieren
    Gegeben sei Variable "wsCreatedId" existiert
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-verify" abonniert wird
    Und eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.readPrefix}${wsCreatedId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und stimmt empfangene Nachricht als JSON überein mit:
      """
      {
        "id": ${wsCreatedId},
        "prescriptionId": "${uniquePrescriptionId}",
        "medicationName": "${eRezeptTestData.ERezept1.medicationName}",
        "dosage": "${eRezeptTestData.ERezept1.dosage}",
        "issuedAt": "${eRezeptTestData.ERezept1.issuedAt}",
        "expiresAt": "${eRezeptTestData.ERezept1.expiresAt}",
        "status": "SIGNED",
        "patientId": "${eRezeptTestData.ERezept1.patientId}",
        "practitionerId": "${eRezeptTestData.ERezept1.practitionerId}"
      }
      """
    Und wird die WebSocket Verbindung geschlossen

  Szenario: DELETE - Rezept löschen
    Gegeben sei Variable "wsCreatedId" existiert
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-delete" abonniert wird
    Und eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.deletePrefix}${wsCreatedId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und stimmt empfangene Nachricht als JSON überein mit:
      """
      {
        "id": ${wsCreatedId},
        "status": "deleted"
      }
      """
    Und wird die WebSocket Verbindung geschlossen

  Szenario: Gelöschtes Rezept verifizieren (Fehlermeldung)
    Gegeben sei Variable "wsCreatedId" existiert
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Und der Kanal "${paths.erezept.websocket.userQueue}" mit ID "sub-verify-delete" abonniert wird
    Und eine leere Anfrage an Kanal "${paths.erezept.websocket.appChannels.readPrefix}${wsCreatedId}" gesendet wird
    Dann wird eine Nachricht auf dem Kanal "${paths.erezept.websocket.userQueue}" empfangen
    Und wird die WebSocket Verbindung geschlossen
