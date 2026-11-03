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
@UseCase_02_11
Funktionalität: Der Notification Service gibt die ursprüngliche ID bei Schlüsselwiederholung zurück

  Hintergrund:
    Angenommen im Container "notification-service" des Deployments "${testdata.ccNotification.rsDeployment}" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_PERSISTENCE_RETENTION":"PT24H"}
      """
    Und im Container "testfachdienst" des Deployments "testfachdienst" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_SERVICE_RS_BASE_URL":"${paths.fachdienst.routingUpstream}/notification-service-rs"}
      """

  @A_29992
  @TA_A_29992_01
  @normal
  Szenariogrundriss: Derselbe Idempotency-Key liefert dieselbe ID <Referenzvariante>
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_IDEM_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "idem-pusher" und speichere in Variable "CC_IDEM_PUSHKEY"
    Und erzeuge eindeutige Test-ID mit Präfix "idem-key" und speichere in Variable "CC_IDEM_KEY"
    Und erzeuge eindeutige Test-ID mit Präfix "idem-control" und speichere in Variable "CC_IDEM_NEW_KEY"
    Und erzeuge eindeutige Test-ID mit Präfix "idem-event" und speichere in Variable "CC_IDEM_MARKER"
    Und erzeuge eindeutige Test-ID mit Präfix "idem-reference" und speichere in Variable "CC_IDEM_REFERENCE"
    Und ermittle den aktuellen Ready-Pod für das Deployment "${testdata.ccNotification.rsDeployment}" und speichere in der Variable "CC_IDEM_POD"
    Und ermittle die konfigurierte Umgebungsvariable "NOTIFICATION_PERSISTENCE_RETENTION" des Containers "notification-service" im Pod "${CC_IDEM_POD}" und speichere in der Variable "CC_IDEM_RETENTION"
    Und TGR prüfe Variable "CC_IDEM_RETENTION" stimmt überein mit "PT24H"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_IDEM_PUSHKEY}","appId":"de.gematik.zeta.idempotency","appDisplayName":"ZETA Testsuite","deviceDisplayName":"Idempotency","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_IDEM_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels":[{"id":"zeta","status":"enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_IDEM_PUSHKEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_IDEM_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_IDEM_POPP"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "CC_IDEM_START"
    Und TGR setze den default header "popp" auf den Wert "${CC_IDEM_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_IDEM_KEY}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_IDEM_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_IDEM_MARKER}"<Referenzfeld>}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_IDEM_FD_ID"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt erfolgt
    Dann TGR finde die nächste Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_IDEM_KEY}" 1 times
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und prüfe optional: Knoten "$.body.reference" fehlt wenn "<Referenzwert>" leer ist, sonst gleich
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_IDEM_FD_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit ".+"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_IDEM_ORIGINAL_ID"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_IDEM_ORIGINAL_BODY"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "CC_IDEM_NS_PATH"
    Und validiere, dass der Zeitstempel "${CC_IDEM_START}" höchstens "86399" Sekunden alt ist
    Und TGR setze den default header "popp" auf den Wert "${CC_IDEM_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_IDEM_KEY}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_IDEM_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_IDEM_MARKER}"<Referenzfeld>}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_IDEM_FD_ID"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt erfolgt
    Dann TGR finde die nächste Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_IDEM_KEY}" 1 times
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und prüfe optional: Knoten "$.body.reference" fehlt wenn "<Referenzwert>" leer ist, sonst gleich
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_IDEM_FD_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body" überein mit "\Q${CC_IDEM_ORIGINAL_BODY}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_IDEM_ORIGINAL_ID}\E"
    Und validiere, dass der Zeitstempel "${CC_IDEM_START}" höchstens "86399" Sekunden alt ist
    Und TGR setze den default header "popp" auf den Wert "${CC_IDEM_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_IDEM_KEY}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_IDEM_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_IDEM_MARKER}"<Referenzfeld>}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_IDEM_FD_ID"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt erfolgt
    Dann TGR finde die nächste Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_IDEM_KEY}" 1 times
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und prüfe optional: Knoten "$.body.reference" fehlt wenn "<Referenzwert>" leer ist, sonst gleich
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_IDEM_FD_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body" überein mit "\Q${CC_IDEM_ORIGINAL_BODY}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_IDEM_ORIGINAL_ID}\E"
    Und validiere, dass der Zeitstempel "${CC_IDEM_START}" höchstens "86399" Sekunden alt ist
    Und TGR setze den default header "popp" auf den Wert "${CC_IDEM_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_IDEM_NEW_KEY}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_IDEM_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_IDEM_MARKER}"<Referenzfeld>}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_IDEM_FD_ID"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt erfolgt
    Dann TGR finde die nächste Anfrage mit Pfad ".*/notifications" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_IDEM_MARKER}\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_IDEM_NEW_KEY}" 1 times
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "\Q${CC_IDEM_KVNR}\E"
    Und prüfe optional: Knoten "$.body.reference" fehlt wenn "<Referenzwert>" leer ist, sonst gleich
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_IDEM_FD_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body" überein mit "\Q${CC_IDEM_ORIGINAL_BODY}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "(?!\Q${CC_IDEM_ORIGINAL_ID}\E$).+"
    Und validiere, dass der Zeitstempel "${CC_IDEM_START}" höchstens "86399" Sekunden alt ist
    Und prüfe, dass genau 4 aufgezeichnete Anfragen den Pfad "${CC_IDEM_NS_PATH}" haben

    Beispiele:
      | Referenzvariante | Referenzfeld                        | Referenzwert           |
      | mit Referenz     | ,"reference":"${CC_IDEM_REFERENCE}" | ${CC_IDEM_REFERENCE}   |
      | ohne Referenz    |                                     |                        |
