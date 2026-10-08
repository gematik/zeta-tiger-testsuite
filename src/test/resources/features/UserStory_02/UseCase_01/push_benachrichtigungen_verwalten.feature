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

@mobile
@require_kubectl
@require_oidc_testdriver
@UseCase_02_01
Funktionalität: Push-Konfigurationen von ZETA Clients verwalten

  Hintergrund:
    # Jeder Testlauf verwendet eine neue OIDC-Identität, damit vorhandene Broker-Nutzer
    # die Authentifizierung vor dem Notification-Service-Nachweis nicht blockieren.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "OIDC_KVNR"

  @A_25652
  @TA_A_25652_04
  @TA_A_25652_05
  @normal
  @reset_notification_pushers
  Szenario: Notification Service verwaltet die Push-Konfiguration eines ZETA Clients
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-management-proof&appId=de.gematik.zeta.testsuite.management-proof"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Anlegen: Der Notification Service speichert die vom Client übermittelte Push-Konfiguration.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "pushkey": "zeta-testsuite-management-proof",
        "appId": "de.gematik.zeta.testsuite.management-proof",
        "appDisplayName": "ZETA Testsuite",
        "deviceDisplayName": "ZETA Verwaltungsnachweis",
        "profileTag": "verwaltungsnachweis",
        "lang": "de",
        "data": {
          "url": "https://${zeta_base_url}/push-gateway/push/v1/",
          "format": "event_id_only"
        }
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_display_name" überein mit "ZETA Testsuite"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.device_display_name" überein mit "ZETA Verwaltungsnachweis"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.profile_tag" überein mit "verwaltungsnachweis"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.lang" überein mit "de"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.url" überein mit "https://${zeta_base_url}/push-gateway/push/v1/"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.format" überein mit "event_id_only"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.method" überein mit "aes-hmac-sha256"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.time_iss_created" überein mit "[0-9]{4}-[0-9]{2}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.iss" überein mit "[0-9a-f]{64}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.key_identifier" überein mit "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].kind" überein mit "http"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].app_display_name" überein mit "ZETA Testsuite"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].device_display_name" überein mit "ZETA Verwaltungsnachweis"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].profile_tag" überein mit "verwaltungsnachweis"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].lang" überein mit "de"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].data.url" überein mit "https://${zeta_base_url}/push-gateway/push/v1/"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].data.format" überein mit "event_id_only"

    # Aktualisieren: Derselbe Pusher bleibt adressiert und seine geänderten Werte werden gespeichert.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "pushkey": "zeta-testsuite-management-proof",
        "appId": "de.gematik.zeta.testsuite.management-proof",
        "appDisplayName": "ZETA Testsuite geändert",
        "deviceDisplayName": "ZETA Verwaltungsnachweis geändert",
        "profileTag": "verwaltungsnachweis-geaendert",
        "lang": "en",
        "data": {
          "url": "https://${zeta_base_url}/push-gateway/push/v1/",
          "format": "event_id_only"
        }
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_display_name" überein mit "ZETA Testsuite geändert"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.device_display_name" überein mit "ZETA Verwaltungsnachweis geändert"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.profile_tag" überein mit "verwaltungsnachweis-geaendert"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.lang" überein mit "en"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.url" überein mit "https://${zeta_base_url}/push-gateway/push/v1/"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.format" überein mit "event_id_only"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].app_display_name" überein mit "ZETA Testsuite geändert"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].device_display_name" überein mit "ZETA Verwaltungsnachweis geändert"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].profile_tag" überein mit "verwaltungsnachweis-geaendert"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].lang" überein mit "en"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].data.format" überein mit "event_id_only"

    # Löschen: Der Notification Service entfernt genau die zuvor verwaltete Push-Konfiguration.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-management-proof&appId=de.gematik.zeta.testsuite.management-proof"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')]"

  @A_25735-01
  @normal
  @reset_notification_pushers
  Szenariogrundriss: ZETA Client aktiviert, ändert und deaktiviert Push-Benachrichtigungen für <description>
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    # Beide bekannten Test-Pusher werden entfernt, damit ein Abbruch eines vorherigen Beispiels
    # keinen weiterhin aktiven Pusher für denselben Nutzer hinterlässt.
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-ta25735-activity&appId=de.gematik.zeta.ta25735.activity"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-ta25735-registration&appId=de.gematik.zeta.ta25735.registration"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Ein eindeutig adressierter Test-Pusher bindet die spätere Zustellung an dasselbe Testgerät.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "pushkey": "zeta-testsuite-ta25735-<proofId>",
        "appId": "de.gematik.zeta.ta25735.<proofId>",
        "appDisplayName": "TA A_25735-01 <proofId>",
        "deviceDisplayName": "ZETA Testgerät <proofId>",
        "profileTag": "ta25735-<proofId>",
        "lang": "de-DE",
        "data": {
          "url": "https://${zeta_base_url}/push-gateway/push/v1/",
          "format": "event_id_only"
        }
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.ta25735.<proofId>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "notificationUserId"

    # Aktivieren: Die Einstellung wird an den Notification Service übertragen und zurückgelesen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "enabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "enabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "enabled"

    # Ändern: Der Client aktualisiert die bereits gespeicherte Channel-Konfiguration für den Pusher von enabled auf disabled und liest den neuen Zustand zurück.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "disabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "disabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "disabled"

    # Für den getrennten Deaktivierungsnachweis stellt der Client zunächst wieder einen aktiven Zustand her.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "enabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "enabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "enabled"

    # Ein korreliertes Ereignis der vom TestAspect geforderten Kategorie wird jetzt zugestellt.
    Und erzeuge eindeutige Test-ID mit Präfix "TA25735-<proofId>-ENABLED" und speichere in Variable "notificationEnabledCorrelationId"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "user_id": "${notificationUserId}",
        "event_type": "<eventType>",
        "correlation_id": "${notificationEnabledCorrelationId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notificationTestEventsPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_id" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.event_type" überein mit "<eventType>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.correlation_id" überein mit "${notificationEnabledCorrelationId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.notification_id"

    # Deaktivieren: Der Nutzer deaktiviert die zuvor wieder aktivierte Einstellung endgültig.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "disabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "disabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "disabled"

    # Dasselbe Ereignis wird nach der Deaktivierung vom Notification Service nicht mehr angenommen.
    Und erzeuge eindeutige Test-ID mit Präfix "TA25735-<proofId>-DISABLED" und speichere in Variable "notificationDisabledCorrelationId"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "user_id": "${notificationUserId}",
        "event_type": "<eventType>",
        "correlation_id": "${notificationDisabledCorrelationId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notificationTestEventsPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.event_type" überein mit "<eventType>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.correlation_id" überein mit "${notificationDisabledCorrelationId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "no_active_channel"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-ta25735-<proofId>&appId=de.gematik.zeta.ta25735.<proofId>"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    @TA_A_25735-01_01
    Beispiele: Aktivitäten über registrierte Clients
      | description                         | proofId  | eventType                  |
      | Aktivitäten über registrierte Clients | activity | registered_client_activity |

    @TA_A_25735-01_02
    Beispiele: Neuregistrierungen für diesen Nutzer
      | description                          | proofId     | eventType               |
      | Neuregistrierungen für diesen Nutzer | registration | new_client_registration |
