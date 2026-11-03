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

@UseCase_02_07
@require_kubectl
@oidc
Funktionalität: Entfernen ungültiger Pusher nach einer echten Gateway-Antwort

  @A_29971
  @TA_A_29971_03
  @TA_A_29971_06
  @TA_A_29971_07
  @normal
  @rejected_pusher_response
  Szenariogrundriss: Genau der Pusher an Geräteposition <abgelehntePosition> verschwindet aus API und tatsächlichem Speicher
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_REJECT_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "TA29971-07-a" und speichere in Variable "CC_PUSHER_A"
    Und erzeuge eindeutige Test-ID mit Präfix "TA29971-07-b" und speichere in Variable "CC_PUSHER_B"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.appId" auf "de.gematik.zeta.ta29971.rejected"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${CC_PUSHER_A}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_PUSHER_A}","appId":"de.gematik.zeta.ta29971.rejected","appDisplayName":"ZETA Testsuite","deviceDisplayName":"A","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_PUSHER_A}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_REJECT_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${CC_PUSHER_A}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_PUSHER_A}","appId":"de.gematik.zeta.ta29971.rejected","appDisplayName":"ZETA Testsuite","deviceDisplayName":"A","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_PUSHER_A}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_REJECT_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${CC_PUSHER_A}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels": [{"id": "zeta", "status": "enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_PUSHER_A}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${CC_PUSHER_B}"
    Wenn TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_PUSHER_B}","appId":"de.gematik.zeta.ta29971.rejected","appDisplayName":"ZETA Testsuite","deviceDisplayName":"B","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_PUSHER_B}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_REJECT_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${CC_PUSHER_B}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels": [{"id": "zeta", "status": "enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_PUSHER_B}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Gegeben sei erzeuge eindeutige Test-ID mit Präfix "TA29971-07" und speichere in Variable "CC_REJECT_MARKER"
    Und eine NS-Pusherprüfung für Nutzer "${CC_REJECT_KVNR}", Pusher "${CC_PUSHER_A}" und "${CC_PUSHER_B}" und Korrelation "${CC_REJECT_MARKER}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "array",
        "minItems": 2,
        "maxItems": 2,
        "items": {
          "type": "object",
          "required": ["pushkey", "app_id"],
          "properties": {
            "pushkey": {"enum": ["${CC_PUSHER_A}", "${CC_PUSHER_B}"]},
            "app_id": {"const": "de.gematik.zeta.ta29971.rejected"},
            "encryption": {"type": "null"}
          }
        },
        "allOf": [
          {"contains": {"properties": {"pushkey": {"const": "${CC_PUSHER_A}"}}}},
          {"contains": {"properties": {"pushkey": {"const": "${CC_PUSHER_B}"}}}}
        ]
      }
      """
    Und enthält der NS-Speicher genau beide ursprünglichen Pusher
    Und Setze im TigerProxy für die Nachricht "isResponse && request.path =~ '.*/push/v1/notify/batch' && request.bodyAsString =~ '(?s).*${CC_REJECT_MARKER}.*'" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      {
        "results": [{
          "id": "!{request.body.findElement('$' + '.notifications.0.id').get().rawStringContent}",
          "status": "partial",
          "rejected": ["!{request.body.findElement('$' + '.notifications.0.notification.devices.<abgelehntePosition>.pushkey').get().rawStringContent}"]
        }],
        "summary": {"total": 1, "successful": 0, "failed": 0, "partial": 1}
      }
      """
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_REJECT_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_REJECT_POPP"
    Und TGR setze den default header "popp" auf den Wert "${CC_REJECT_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_REJECT_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_REJECT_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.client.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_REJECT_MARKER}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_REJECT_ID"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_REJECT_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_REJECT_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_id" überein mit "\Q${CC_REJECT_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.event_type" überein mit "registered_client_activity"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_REJECT_ID}\E"
    Dann hat der NS die Benachrichtigung für die Pusherprüfung angenommen
    Dann TGR finde die letzte Anfrage mit Pfad "/push/v1/notify/batch" und Knoten "$.body.notifications.0.notification.content.correlation_id" der mit "\Q${CC_REJECT_MARKER}\E" übereinstimmt
    Und TGR speichere Wert des Knotens "$.body.notifications.0.notification.devices.<abgelehntePosition>.pushkey" der aktuellen Anfrage in der Variable "CC_REJECT_BAD"
    Und TGR speichere Wert des Knotens "$.body.notifications.0.notification.devices.<erhaltenePosition>.pushkey" der aktuellen Anfrage in der Variable "CC_REJECT_GOOD"
    Und weist die korrelierte Gateway-Antwort ausschließlich den Pushkey "${CC_REJECT_BAD}" zurück und erhält den Pushkey "${CC_REJECT_GOOD}"
    Und enthalten SDK-Pusher-API und NS-Speicher innerhalb des Pusher-Prüfbudgets nur den erhaltenen Pusher
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Gegeben sei erzeuge eindeutige Test-ID mit Präfix "TA29971-follow" und speichere in Variable "CC_REJECT_FOLLOW"
    Dann Setze im TigerProxy für die Nachricht "isResponse && request.path =~ '.*/push/v1/notify/batch' && request.bodyAsString =~ '(?s).*${CC_REJECT_FOLLOW}.*'" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      {
        "results": [{
          "id": "!{request.body.findElement('$' + '.notifications.0.id').get().rawStringContent}",
          "status": "success",
          "rejected": []
        }],
        "summary": {"total": 1, "successful": 1, "failed": 0, "partial": 0}
      }
      """
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_REJECT_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_REJECT_FOLLOW_POPP"
    Und TGR setze den default header "popp" auf den Wert "${CC_REJECT_FOLLOW_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_REJECT_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_REJECT_FOLLOW}"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.client.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_REJECT_FOLLOW}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_REJECT_FOLLOW_ID"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_REJECT_FOLLOW}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_REJECT_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_id" überein mit "\Q${CC_REJECT_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.event_type" überein mit "registered_client_activity"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_REJECT_FOLLOW_ID}\E"
    Dann hat der NS das Folgeereignis mit Korrelation "${CC_REJECT_FOLLOW}" und einer neuen Notification-ID angenommen
    Und wird das Folgeereignis ausschließlich an den erhaltenen Pusher versandt
    Dann TGR finde die letzte Anfrage mit Pfad "/push/v1/notify/batch" und Knoten "$.body.notifications.0.notification.content.correlation_id" der mit "\Q${CC_REJECT_FOLLOW}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und wird im vollständigen Pusher-Prüffenster kein weiterer Versand an den abgelehnten Pushkey beobachtet
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "array",
        "minItems": 1,
        "maxItems": 1,
        "items": {
          "type": "object",
          "required": ["pushkey", "app_id"],
          "properties": {
            "pushkey": {"const": "${CC_REJECT_GOOD}"},
            "app_id": {"const": "de.gematik.zeta.ta29971.rejected"},
            "encryption": {"type": "null"}
          }
        }
      }
      """
    Und enthält der NS-Speicher weiterhin genau den erhaltenen Pusher

    Beispiele:
      | abgelehntePosition | erhaltenePosition |
      | 0                  | 1                 |
      | 1                  | 0                 |
