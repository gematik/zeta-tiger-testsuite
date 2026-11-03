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

  Szenariogrundriss: NotificationRequest mit isoliertem Schemafehler <Fall>
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
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und TGR setze lokale Variable "CC_SCHEMA_VALID_CONDITION" auf "isRequest && request.path =~ '^/notification-service-rs/notifications$'"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_VALID_CONDITION}" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}","extra":{"nested":"allowed","number":1}},"prio":"high","reference":"RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"}
      """
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Und TGR setze den default header "Idempotency-Key" auf den Wert "${CC_SCHEMA_KEY}-before"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_SCHEMA_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Und TGR lösche default header "Idempotency-Key"
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit Pfad "^/notifications$" und Knoten "$.header.[~'Idempotency-Key']" der mit "\Q${CC_SCHEMA_KEY}-before\E" übereinstimmt
    Und verify current request contains header "Idempotency-Key" with value "${CC_SCHEMA_KEY}-before" 1 times
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/v_2_0/openapi/notification-service-rs-endpoint.yaml#/components/schemas/NotificationRequest"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.prio" überein mit "high"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.reference" überein mit "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.payload.extra.nested" überein mit "allowed"
    Und TGR setze lokale Variable "CC_SCHEMA_CONDITION" auf "isRequest && request.path =~ '^/notification-service-rs/notifications$'"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_CONDITION}" die Manipulation auf Feld "$.body" und Wert '<Body>'
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
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/v_2_0/openapi/notification-service-rs-endpoint.yaml#/components/schemas/NotificationRequest" mit Fehlern "<Fehler>" ausschließlich bei "<Position>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR setze lokale Variable "CC_SCHEMA_VALID_CONDITION" auf "isRequest && request.path =~ '^/notification-service-rs/notifications$'"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_VALID_CONDITION}" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}","extra":{"nested":"allowed","number":1}},"prio":"normal","reference":"RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"}
      """
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
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.prio" überein mit "normal"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.reference" überein mit "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.payload.extra.nested" überein mit "allowed"

    Beispiele:
      | Fall | Body | Fehler | Position |
      | missing-user | {"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | required |  |
      | missing-channel | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | required |  |
      | missing-payload | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta"} | required |  |
      | missing-user.id_type | {"user":{"value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | required | /user |
      | missing-user.value | {"user":{"id_type":"kvnr"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | required | /user |
      | type-user | {"user":3,"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | type | /user |
      | type-channel | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":3,"payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | type | /channel |
      | type-payload | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":3} | type | /payload |
      | type-user.value | {"user":{"id_type":"kvnr","value":3},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | type | /user/value |
      | type-reference | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"},"reference":3} | type | /reference |
      | type-id-type | {"user":{"id_type":3,"value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | type,enum | /user/id_type |
      | enum-id-type | {"user":{"id_type":"invalid","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | enum | /user/id_type |
      | empty-user-value | {"user":{"id_type":"kvnr","value":""},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | minLength,pattern | /user/value |
      | pattern-user-value | {"user":{"id_type":"kvnr","value":"!"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | pattern | /user/value |
      | long-user-value | {"user":{"id_type":"kvnr","value":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"}} | maxLength,pattern | /user/value |
      | long-reference | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"},"reference":"rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr"} | maxLength | /reference |
      | enum-prio | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"},"prio":"invalid"} | enum | /prio |
      | type-prio | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"},"prio":3} | type,enum | /prio |
      | additional-property | {"user":{"id_type":"kvnr","value":"${CC_SCHEMA_KVNR}"},"channel":"zeta","payload":{"event_type":"registered_client_activity","correlation_id":"${CC_SCHEMA_MARKER}"},"unexpected":true} | additionalProperties |  |
      | root-type | true | type |  |
