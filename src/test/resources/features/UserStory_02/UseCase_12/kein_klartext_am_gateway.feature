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

@require_kubectl
@deployment_modification
@oidc
@UseCase_02_12
Funktionalität: Im aktivierten Historienprofil erhält das Gateway keine Klartext-Nachrichteninhalte

  Hintergrund:
    Angenommen im Container "notification-service" des Deployments "${testdata.ccNotification.rsDeployment}" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_HISTORY_ENABLED":"true","NOTIFICATION_ENCRYPTION_ENABLED":"true"}
      """
    Und im Container "notification-service" des Deployments "${testdata.notificationService.fdvDeployment}" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_HISTORY_ENABLED":"true","NOTIFICATION_ENCRYPTION_ENABLED":"true"}
      """
    Und im Container "testfachdienst" des Deployments "testfachdienst" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_SERVICE_RS_BASE_URL":"${paths.fachdienst.routingUpstream}/notification-service-rs"}
      """

  @A_29973
  @TA_A_29973_06
  @normal
  Szenario: Die tatsächlichen Gateway-Nutzdaten enthalten nur den verschlüsselten Nachrichteninhalt
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_PRIVACY_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "privacy-device" und speichere in Variable "CC_PRIVACY_PUSHKEY"
    Und erzeuge eindeutige Test-ID mit Präfix "privacy-message" und speichere in Variable "CC_PRIVACY_MARKER"
    Und ermittle den aktuellen Ready-Pod für das Deployment "${testdata.ccNotification.rsDeployment}" und speichere in der Variable "CC_PRIVACY_POD"
    Und ermittle die konfigurierte Umgebungsvariable "NOTIFICATION_HISTORY_ENABLED" des Containers "notification-service" im Pod "${CC_PRIVACY_POD}" und speichere in der Variable "CC_PRIVACY_HISTORY"
    Und TGR prüfe Variable "CC_PRIVACY_HISTORY" stimmt überein mit "true"
    Und ermittle die konfigurierte Umgebungsvariable "NOTIFICATION_ENCRYPTION_ENABLED" des Containers "notification-service" im Pod "${CC_PRIVACY_POD}" und speichere in der Variable "CC_PRIVACY_ENCRYPTION"
    Und TGR prüfe Variable "CC_PRIVACY_ENCRYPTION" stimmt überein mit "true"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_PRIVACY_PUSHKEY}","appId":"de.gematik.zeta.privacy","appDisplayName":"ZETA Testsuite","deviceDisplayName":"Privacy","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_PRIVACY_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_PRIVACY_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.encryption.iss" der aktuellen Anfrage in der Variable "CC_PRIVACY_ISS"
    Und TGR speichere Wert des Knotens "$.body.encryption.time_iss_created" der aktuellen Anfrage in der Variable "CC_PRIVACY_FIRST_MONTH"
    Und TGR speichere Wert des Knotens "$.body.encryption.key_identifier" der aktuellen Anfrage in der Variable "CC_PRIVACY_KEY_IDENTIFIER"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels":[{"id":"zeta","status":"enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_PRIVACY_PUSHKEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_PRIVACY_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_PRIVACY_POPP"
    Und TGR setze den default header "popp" auf den Wert "${CC_PRIVACY_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_PRIVACY_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_PRIVACY_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_PRIVACY_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_PRIVACY_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_PRIVACY_MARKER}\E" übereinstimmt erfolgt
    Dann TGR finde die nächste Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_PRIVACY_MARKER}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_PRIVACY_ID"
    Und merke den Abschlussnachweis im Container "notification-service" im Pod "${CC_PRIVACY_POD}" mit Muster "Notification ${CC_PRIVACY_ID} dispatch outcome: (failed|dispatched|partially_rejected)"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und TGR speichere Wert des Knotens "$.body.payload" der aktuellen Anfrage in der Variable "CC_PRIVACY_PAYLOAD"
    Dann TGR finde die nächste Anfrage mit Pfad ".*/push/v1/notifyEncrypted/batch" und Knoten "$.body.notifications.0.id" der mit "\Q${CC_PRIVACY_ID}:0\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.notifications.0.notification.device.pushkey" überein mit "\Q${CC_PRIVACY_PUSHKEY}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.notifications.0.notification.key_identifier" überein mit "\Q${CC_PRIVACY_KEY_IDENTIFIER}\E"
    Und TGR prüfe aktueller Request im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": [
          "notifications"
        ],
        "properties": {
          "notifications": {
            "type": "array",
            "minItems": 1,
            "maxItems": 1,
            "items": {
              "type": "object",
              "required": [
                "id",
                "notification"
              ],
              "properties": {
                "notification": {
                  "type": "object",
                  "required": [
                    "ciphertext",
                    "time_message_encrypted",
                    "key_identifier",
                    "device"
                  ],
                  "not": {
                    "required": [
                      "content"
                    ]
                  },
                  "properties": {
                    "ciphertext": {
                      "type": "string",
                      "minLength": 1404,
                      "maxLength": 1404
                    }
                  }
                }
              }
            }
          }
        }
      }
      """
    Und TGR speichere Wert des Knotens "$.body.notifications.0.notification.ciphertext" der aktuellen Anfrage in der Variable "CC_PRIVACY_CIPHERTEXT"
    Und TGR speichere Wert des Knotens "$.body.notifications.0.notification.time_message_encrypted" der aktuellen Anfrage in der Variable "CC_PRIVACY_MONTH"
    Und enthält das Push-Chiffrat "${CC_PRIVACY_CIPHERTEXT}" mit ISS "${CC_PRIVACY_ISS}" vom Monat "${CC_PRIVACY_FIRST_MONTH}" für den Monat "${CC_PRIVACY_MONTH}" den Payload "${CC_PRIVACY_PAYLOAD}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und enthält das Protokoll des Containers "notification-service" im Pod "${CC_PRIVACY_POD}" innerhalb von 45 Sekunden das Muster "Notification ${CC_PRIVACY_ID} dispatch outcome: (failed|dispatched|partially_rejected)"
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Und enthalten alle aufgezeichneten Gateway-Anfragen mit Pfad ".*/push/v1/notify.*" keinen Klartext des Payloads "${CC_PRIVACY_PAYLOAD}"
