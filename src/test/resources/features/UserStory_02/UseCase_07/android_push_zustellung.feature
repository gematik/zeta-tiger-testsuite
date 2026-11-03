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

@oidc
@UseCase_02_07
Funktionalität: Verschlüsselte Push-Nachrichten an Android zustellen

  @A_25652
  @A_29971
  @A_29972
  @A_29979
  @TA_A_25652_01
  @TA_A_25652_04
  @TA_A_25652_06
  @TA_A_29971_05
  @TA_A_29972_03
  @TA_A_29979_01
  @normal
  @android
  @smoke_android_push
  Szenario: Android-Client empfängt eine über den Testfachdienst ausgelöste Push-Nachricht
    Gegeben sei Setze im TigerProxy für die Nachricht "isRequest && request.method == 'POST' && request.path == '${paths.guard.notificationPushersSetPath}' && request.body != null && request.body.findElement('$' + '.app_id').orElse(null)?.rawStringContent == '${testdata.androidPush.driverAppId}'" die Manipulation auf Feld "$.body.app_id" und Wert "${android_push_firebase_project_id}.zeta.firebase" und 20 Ausführungen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.androidClient.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.androidClient.resetPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und merke den Android-Push-Kontext zur Bereinigung
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere POST Anfrage an "${paths.androidClient.notifications.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.androidClient.notifications.registerPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "\Q${android_push_firebase_project_id}.zeta.firebase\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${paths.guard.notificationServiceUrl}",["${paths.guard.notificationServiceUrl}"]]}}}
      """
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "androidPushUserId"
    Und TGR prüfe Variable "androidPushUserId" stimmt überein mit "[A-Z][0-9]{9}"
    Und TGR speichere Wert des Knotens "$.body.pushkey" der aktuellen Anfrage in der Variable "androidPushPushkey"
    Und TGR speichere Wert des Knotens "$.body.encryption.key_identifier" der aktuellen Anfrage in der Variable "androidPushKeyIdentifier"
    Wenn TGR sende eine leere POST Anfrage an "${paths.androidClient.notifications.subscribeAll}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.androidClient.notifications.subscribeAllPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "enabled"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "required": ["channels"],
        "properties": {
          "channels": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["id", "status"],
              "properties": {
                "id": {"type": "string"},
                "status": {"enum": ["enabled", "disabled", "not_set"]}
              }
            }
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == 'zeta')].id" überein mit "zeta"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.androidClient.notifications.received}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.androidClient.notifications.receivedPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und erzeuge eindeutige Test-ID mit Präfix "ANDROID-PUSH-SMOKE" und speichere in Variable "androidPushCorrelationId"
    Gegeben sei ein Android-Push-Prüffenster von 60 Sekunden
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "user_id": "${androidPushUserId}",
        "event_type": "registered_client_activity",
        "correlation_id": "${androidPushCorrelationId}",
        "reference": "${androidPushCorrelationId}",
        "title": "ZETA Android Smoke",
        "message": "Grüße vom Testfachdienst – 🔔"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notificationTestEventsPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.notification_id"
    Dann warte innerhalb des Android-Push-Prüffensters auf die Nachricht mit Korrelation "${androidPushCorrelationId}" und Schlüsselkennung "${androidPushKeyIdentifier}" und speichere die Nutzdaten in Variable "androidPushPayload"
    Und TGR prüfe Variable "androidPushPayload.correlation_id" stimmt überein mit "${androidPushCorrelationId}"
    Und TGR prüfe Variable "androidPushPayload.event_type" stimmt überein mit "registered_client_activity"
    Und TGR prüfe Variable "androidPushPayload.title" stimmt überein mit "ZETA Android Smoke"
    Und TGR prüfe Variable "androidPushPayload.message" stimmt überein mit "Grüße vom Testfachdienst – 🔔"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.androidClient.notifications.receivedPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.keyIdentifier == '${androidPushKeyIdentifier}')].decrypted" überein mit "(?s).*\Q${androidPushCorrelationId}\E.*"
    Und TGR setze lokale Variable "androidGatewayItemPath" auf "$.body.notifications.[?(@.notification.identifier == '${androidPushCorrelationId}' && @.notification.device.pushkey == '${androidPushPushkey}')]"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.pushGateway.encryptedDispatchPath}" und Knoten "${androidGatewayItemPath}.notification.key_identifier" der mit "\Q${androidPushKeyIdentifier}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${androidGatewayItemPath}.notification.device.app_id" überein mit "\Q${android_push_firebase_project_id}.zeta.firebase\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${androidGatewayItemPath}.notification.device.pushkey" überein mit "\Q${androidPushPushkey}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${androidGatewayItemPath}.notification.identifier" überein mit "\Q${androidPushCorrelationId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${androidGatewayItemPath}.notification.ciphertext" überein mit "[A-Za-z0-9+/]+={0,2}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${androidGatewayItemPath}.notification.time_message_encrypted" überein mit "[0-9]{4}-(0[1-9]|1[0-2])"
    Und TGR prüfe aktueller Request stimmt im Knoten "${androidGatewayItemPath}.notification.time_message_encrypted" überein mit "${androidPushExpectedMonthPattern}"
    Und TGR prüfe aktueller Request im Knoten "$.body.notifications" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "array",
        "items": {"type": "object", "required": ["id", "notification"]},
        "contains": {
          "type": "object",
          "required": ["notification"],
          "properties": {
            "notification": {
              "type": "object",
              "required": ["identifier", "device"],
              "properties": {
                "identifier": {"const": "${androidPushCorrelationId}"},
                "device": {
                  "type": "object",
                  "required": ["pushkey"],
                  "properties": {"pushkey": {"const": "${androidPushPushkey}"}}
                }
              }
            }
          }
        },
        "minContains": 1,
        "maxContains": 1
      }
      """
    Und TGR speichere Wert des Knotens "${androidGatewayItemPath}.id" der aktuellen Anfrage in der Variable "androidGatewayItemId"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.results.[?(@.id == '${androidGatewayItemId}')].status" überein mit "success"
    Dann warte bis zum Ende des Android-Push-Prüffensters
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "/push/v1/(?:notify(?:/batch)?|notifyEncrypted)/?" hat
