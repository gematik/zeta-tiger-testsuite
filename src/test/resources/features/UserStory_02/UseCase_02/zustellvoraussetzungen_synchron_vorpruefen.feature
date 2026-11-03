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
@oidc
@UseCase_02_02
Funktionalität: Zustellvoraussetzungen synchron vorprüfen

  Hintergrund:
    # Die Nutzeridentität wird für das Szenario vorbereitet.
    # F01: Reserviert eine bereinigte Pool-KVNR im OIDC-Testdriver.
    Gegeben sei eine freie KVNR aus dem Notification-Pool
    Und erzeuge eindeutige Test-ID mit Präfix "zeta-lifecycle-precondition" und speichere in Variable "testdata.notificationService.pusher.pushkey"
    Und die gezielte Bereinigung für Pusher "${testdata.notificationService.pusher.pushkey}", Anwendung "${testdata.notificationService.pusher.appId}" und Nutzer "${oidcTestData.kvnr}"
    Und TGR setze lokale Variable "schemaValidation.soft" auf "true"

  @A_29966
  @TA_A_29966_01
  @normal
  @notification_pusher_lifecycle
  Szenario: Notification Service weist den Versand an einen Nutzer ohne registrierten Pusher zurück
    # Die Pusher-Abfrage belegt die Vorbedingung für denselben Nutzer.
    # F02: Isoliert die Pusher-Abfrage im lokalen Nachrichtenstand.
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    # F03: Speichert die KVNR des adressierten Nutzers.
    Und TGR setze lokale Variable "notificationUserId" auf "${oidcTestData.kvnr}"
    # F04: Fragt die Pusher der konfigurierten Identität ab.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    # F05: Der backendseitige Pfad wählt die tatsächlich beim Notification Service eingegangene Pusher-Abfrage aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    # F06: Der Token-Subject bindet die leere Pusher-Liste an exakt den anschließend adressierten Nutzer.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    # F07: Prüft den Erfolg der Pusher-Abfrage.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F08: Validiert die Pusher-Antwort gegen das referenzierte Schema und verlangt eine vorhandene, leere Liste.
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "maxItems": 0
          }
        }
      }
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "emptyPushersResponse"
    Und validiere "${emptyPushersResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

    # Der Resource Server adressiert den Nutzer ohne registrierten Pusher.
    # F09: Isoliert die Notification-Anfrage im lokalen Nachrichtenstand.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F10: Die eindeutige Korrelation bindet Request und Response des POST /notifications an denselben Nachweis.
    Und erzeuge eindeutige Test-ID mit Präfix "TA29966-01" und speichere in Variable "notificationCorrelationId"
    # F11: Sendet die gemeinsame Notification-Vorlage mit Nutzer und Korrelations-ID an die RS-Schnittstelle.
    Wenn TGR sende eine POST Anfrage an "${paths.guard.notificationServiceRs}/notifications" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_request.json')}
      """

    # Die direkte Antwort wird der backendseitigen Notification-Anfrage zugeordnet.
    # F12: Wählt die backendseitige Anfrage über Pfad und Korrelations-ID aus.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationServiceRsPath}" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    # F13: Die Methode schließt andere Operationen als das von TA_A_29966_01 geforderte POST /notifications aus.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    # F14: Der korrelierte Request adressiert nachweislich den Nutzer ohne registrierten Pusher.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "${notificationUserId}"
    # F15: Prüft den Channel der backendseitigen Anfrage.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channel" überein mit "zeta"
    # F16: Der Korrelationswert bindet alle Assertions an denselben Request und dieselbe Antwort.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.payload.correlation_id" überein mit "${notificationCorrelationId}"

    # Die korrelierte Antwort muss die Ablehnung des unbekannten Nutzers ausweisen.
    # F17: Belegt den von TA_A_29966_01 geforderten HTTP-Status 404.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    # F18: Belegt USER_NOT_FOUND für TA_A_29966_01 und verwirft andere Fehlercodes.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.errorCode" überein mit "USER_NOT_FOUND"


    # Derselbe zunächst unbekannte Nutzer wird jetzt registriert und muss am RS bekannt sein.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      !{file('src/test/resources/testdata/push/pusher_lifecycle_response.schema.json')}
      """
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["pushers"],"properties":{"pushers":{"type":"array","minItems":1,"maxItems":1}}}
      """

    # Positivkontrolle derselben RS-Nutzersuche: Ein registrierter Nutzer ist bekannt.
    # Der deaktivierte Channel liefert 202/no_active_channel ohne Gateway-Zustellung.
    Und TGR setze lokale Variable "testdata.notificationService.notificationChannel.id" auf "zeta"
    Und TGR setze lokale Variable "testdata.notificationService.notificationChannel.status" auf "disabled"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/notification_channels.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.[?(@.id == 'zeta')].status" überein mit "disabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == 'zeta')].status" überein mit "disabled"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und erzeuge eindeutige Test-ID mit Präfix "TA29966-KNOWN" und speichere in Variable "notificationCorrelationId"
    Und TGR sende eine POST Anfrage an "${paths.guard.notificationServiceRs}/notifications" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_request.json')}
      """
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationServiceRsPath}" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.id_type" überein mit "kvnr"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "no_active_channel"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"

  @A_29966
  @TA_A_29966_01
  @normal
  @notification_pusher_lifecycle
  Szenario: Notification Service weist den Versand nach Löschung des letzten Pushers zurück
    # Die Vorbereitung entfernt ausschließlich den test-eigenen Pusher.
    # F02: Speichert die Identität für Registrierung, Löschung und Versand.
    Gegeben sei TGR setze lokale Variable "notificationUserId" auf "${oidcTestData.kvnr}"
    # F03: Isoliert die idempotente Bereinigung.
    Und TGR lösche aufgezeichnete Nachrichten
    # F04: Entfernt einen möglicherweise verbliebenen Test-Pusher.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${testdata.notificationService.pusher.pushkey}&appId=${testdata.notificationService.pusher.appId}"
    # F05: Wählt die Antwort auf die Bereinigung.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    # F06: Prüft den erfolgreichen Abschluss der Bereinigung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Ein registrierter Pusher stellt den zuvor bekannten Nutzer her.
    # F07: Isoliert die Registrierung.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F07c: Bewahrt den Anzeigenamen des Testgeräts in der gemeinsamen Vorlage.
    Und TGR setze lokale Variable "testdata.notificationService.pusher.deviceDisplayName" auf "TA29966 letzter Pusher"
    # F07d: Bewahrt das Profil des Testgeräts in der gemeinsamen Vorlage.
    Und TGR setze lokale Variable "testdata.notificationService.pusher.profileTag" auf "ta29966-last-pusher"
    # F08: Registriert das Testgerät mit der vorbereiteten gemeinsamen Pusher-Vorlage.
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    # F09: Wählt die Registrierung am Notification Service.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    # F10: Ordnet die Registrierung dem später adressierten Nutzer zu.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    # F11: Prüft die erfolgreiche Registrierung am Backend.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F12: Isoliert das Zurücklesen des registrierten Geräts.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F13: Fragt die gespeicherten Pusher ab.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    # F14: Wählt die direkte Pusher-Abfrage am Notification Service.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    # F15: Bindet den gespeicherten Pusher an dieselbe KVNR.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    # F16: Prüft die erfolgreiche Bestandsabfrage.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F17: Belegt genau den Test-Pusher als letztes Gerät vor der Löschung.
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "minItems": 1,
            "maxItems": 1,
            "items": {
              "type": "object",
              "required": ["pushkey", "app_id", "kind"],
              "properties": {
                "pushkey": {"const": "${testdata.notificationService.pusher.pushkey}"},
                "app_id": {"const": "${testdata.notificationService.pusher.appId}"},
                "kind": {"const": "http"}
              }
            }
          }
        }
      }
      """

    # F17a: Übernimmt dieselbe korrelierte Bestandsantwort für die Schema-Prüfung.
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "registeredPushersResponse"
    # F17b: Prüft das vollständige Pusher-Schema ohne Abbruch des Löschungsnachweises.
    Und validiere "${registeredPushersResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"


    # Positivkontrolle derselben RS-Nutzersuche: Ein registrierter Nutzer ist bekannt.
    # Der deaktivierte Channel liefert 202/no_active_channel ohne Gateway-Zustellung.
    Und TGR setze lokale Variable "testdata.notificationService.notificationChannel.id" auf "zeta"
    Und TGR setze lokale Variable "testdata.notificationService.notificationChannel.status" auf "disabled"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/notification_channels.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.[?(@.id == 'zeta')].status" überein mit "disabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == 'zeta')].status" überein mit "disabled"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und erzeuge eindeutige Test-ID mit Präfix "TA29966-KNOWN" und speichere in Variable "notificationCorrelationId"
    Und TGR sende eine POST Anfrage an "${paths.guard.notificationServiceRs}/notifications" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_request.json')}
      """
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationServiceRsPath}" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.id_type" überein mit "kvnr"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "no_active_channel"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"

    # Die Löschung entfernt das letzte Gerät ohne Wechsel der Nutzeridentität.
    # F18: Isoliert die Löschung des nachgewiesenen Pushers.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F19: Löscht genau das zuvor registrierte Testgerät.
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${testdata.notificationService.pusher.pushkey}&appId=${testdata.notificationService.pusher.appId}"
    # F20: Wählt die backendseitige Löschoperation.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    # F21: Bindet die Löschung an denselben Nutzer.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    # F22: Prüft den Pushkey des zu löschenden Geräts.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${testdata.notificationService.pusher.pushkey}"
    # F23: Prüft die App-Zuordnung des zu löschenden Geräts.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    # F24: Prüft die Löschsemantik der backendseitigen Pusher-Operation.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    # F25: Prüft die erfolgreiche Löschung am Backend.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F26: Isoliert die Bestandsabfrage nach der Löschung.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F27: Fragt die verbleibenden Pusher derselben Identität ab.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    # F28: Wählt die direkte Bestandsabfrage am Notification Service.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    # F29: Bindet den leeren Bestand an den zuvor bekannten Nutzer.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    # F30: Prüft die erfolgreiche Bestandsabfrage nach der Löschung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F31: Belegt eine vorhandene, leere Pusher-Liste nach Löschung des letzten Geräts.
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "maxItems": 0
          }
        }
      }
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "emptyPushersResponse"
    Und validiere "${emptyPushersResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

    # Der zuvor bekannte Nutzer muss beim Versand wieder als unbekannt gelten.
    # F32: Isoliert die Notification-Anfrage nach der Löschung.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F33: Erzeugt die eindeutige Korrelation für den Versandnachweis.
    Und erzeuge eindeutige Test-ID mit Präfix "TA29966-01-DELETED" und speichere in Variable "notificationCorrelationId"
    # F34: Sendet die gemeinsame Notification-Vorlage an den Nutzer ohne verbleibendes Gerät.
    Wenn TGR sende eine POST Anfrage an "${paths.guard.notificationServiceRs}/notifications" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_request.json')}
      """
    # F35: Wählt die direkte Notification-Anfrage über Pfad und Korrelations-ID.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationServiceRsPath}" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    # F36: Prüft die für TA_A_29966_01 geforderte Versandoperation.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    # F37: Prüft den Identifikatortyp der korrelierten Notification.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.id_type" überein mit "kvnr"
    # F38: Bindet den Versand an den Nutzer des gelöschten Pushers.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "${notificationUserId}"
    # F39: Prüft den zulässigen Channel der Notification.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channel" überein mit "zeta"
    # F40: Belegt für TA_A_29966_01 die Ablehnung nach Löschung des letzten Pushers.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    # F41: Belegt USER_NOT_FOUND für TA_A_29966_01 statt eines verbliebenen Nutzerzustands.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.errorCode" überein mit "USER_NOT_FOUND"
