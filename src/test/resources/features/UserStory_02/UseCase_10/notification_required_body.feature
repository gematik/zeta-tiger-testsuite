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
@UseCase_02_10
@A_29986
@TA_A_29986_01
@normal
@MASVS-CODE
Funktionalität: Der Notification Service weist formal ungültige Notification-Bodies zurück

  Hintergrund:
    Angenommen im Container "testfachdienst" des Deployments "testfachdienst" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_SERVICE_RS_BASE_URL":"${paths.fachdienst.routingUpstream}/notification-service-rs"}
      """

  Szenario: NotificationRequest ohne erforderlichen Request-Body
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_SCHEMA_KVNR"
    Und TGR setze lokale Variable "schemaValidation.soft" auf "false"
    Und erzeuge eindeutige Test-ID mit Präfix "schema-pusher" und speichere in Variable "CC_SCHEMA_PUSHKEY"
    Und erzeuge eindeutige Test-ID mit Präfix "schema-event" und speichere in Variable "CC_SCHEMA_MARKER"
    Und erzeuge eindeutige Test-ID mit Präfix "schema-key" und speichere in Variable "CC_SCHEMA_KEY"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_SCHEMA_PUSHKEY}","appId":"de.gematik.zeta.schema","appDisplayName":"Schema","deviceDisplayName":"Schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_SCHEMA_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels":[{"id":"zeta","status":"enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_SCHEMA_PUSHKEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_SCHEMA_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_SCHEMA_POPP"
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_SCHEMA_KEY}-before"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_SCHEMA_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Dann TGR finde die letzte Anfrage mit Pfad "^/notifications$" und Knoten "$.header.[~'Idempotency-Key']" der mit "\Q${CC_SCHEMA_KEY}-before\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_SCHEMA_KEY}-before" 1 times
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/v_2_0/openapi/notification-service-rs-endpoint.yaml#/components/schemas/NotificationRequest"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und TGR setze lokale Variable "CC_SCHEMA_CONDITION" auf "isRequest && request.path =~ '^/notification-service-rs/notifications$'"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_CONDITION}" die Manipulation auf Feld "$.body" und Wert ""
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_SCHEMA_KEY}-invalid"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_SCHEMA_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit Pfad "^/notifications$" und Knoten "$.header.[~'Idempotency-Key']" der mit "\Q${CC_SCHEMA_KEY}-invalid\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_SCHEMA_KEY}-invalid" 1 times
    Und TGR prüfe aktueller Request stimmt im Knoten "$" überein mit "(?s).*\r\n\r\n$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_SCHEMA_KEY}-after"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_SCHEMA_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit Pfad "^/notifications$" und Knoten "$.header.[~'Idempotency-Key']" der mit "\Q${CC_SCHEMA_KEY}-after\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_SCHEMA_KEY}-after" 1 times
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/v_2_0/openapi/notification-service-rs-endpoint.yaml#/components/schemas/NotificationRequest"
