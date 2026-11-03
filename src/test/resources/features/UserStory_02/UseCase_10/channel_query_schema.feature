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
Funktionalität: Der Notification Service validiert die Parameter der Channel-Abfrage

  Hintergrund:
    Angenommen im Container "testfachdienst" des Deployments "testfachdienst" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_SERVICE_RS_BASE_URL":"${paths.fachdienst.routingUpstream}/notification-service-rs"}
      """

  Szenariogrundriss: RS-Channel-Abfrage mit isoliertem Parameterfehler <Fall>
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_SCHEMA_KVNR"
    Und TGR setze lokale Variable "schemaValidation.soft" auf "false"
    Und erzeuge eindeutige Test-ID mit Präfix "schema-pusher" und speichere in Variable "CC_SCHEMA_PUSHKEY"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_SCHEMA_PUSHKEY}","appId":"de.gematik.zeta.schema","appDisplayName":"Schema","deviceDisplayName":"Schema","profileTag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_SCHEMA_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_SCHEMA_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_SCHEMA_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_SCHEMA_POPP"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und TGR setze lokale Variable "CC_SCHEMA_CONTROL_CONDITION" auf "isRequest && request.path =~ '^/notification-service-rs/users/${CC_SCHEMA_KVNR}/channels'"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_CONTROL_CONDITION}" die Manipulation auf Feld "$.path.id_type" und Wert "id_type=kvnr&probe=kvnr"
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}/users/${CC_SCHEMA_KVNR}/channels/query" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"id_type":"kvnr","include_inactive":true}
      """
    Und TGR lösche default header "popp"
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die nächste Anfrage mit dem Pfad "^/users/[^/]+/channels.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR setze lokale Variable "CC_SCHEMA_CONDITION" auf "isRequest && request.path =~ '^/notification-service-rs/users/${CC_SCHEMA_KVNR}/channels'"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_CONDITION}" die Manipulation auf Feld "<Feld>" und Wert '<Wert>'
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}/users/${CC_SCHEMA_KVNR}/channels/query" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"id_type":"kvnr","include_inactive":true}
      """
    Und TGR lösche default header "popp"
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die nächste Anfrage mit dem Pfad "^/users/[^/]+/channels.*"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit "\Q<NS-Pfad>\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR setze lokale Variable "CC_SCHEMA_CONTROL_CONDITION" auf "isRequest && request.path =~ '^/notification-service-rs/users/${CC_SCHEMA_KVNR}/channels'"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_CONTROL_CONDITION}" die Manipulation auf Feld "$.path.id_type" und Wert "id_type=kvnr&probe=kvnr"
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}/users/${CC_SCHEMA_KVNR}/channels/query" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"id_type":"kvnr","include_inactive":true}
      """
    Und TGR lösche default header "popp"
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die nächste Anfrage mit dem Pfad "^/users/[^/]+/channels.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Beispiele:
      | Fall | Feld | Wert | NS-Pfad |
      | missing-id_type | $.path.id_type.key | probe | /users/${CC_SCHEMA_KVNR}/channels?probe=kvnr&include_inactive=true |
      | empty-id_type | $.path.id_type.value |  | /users/${CC_SCHEMA_KVNR}/channels?id_type=&include_inactive=true |
      | invalid-id_type | $.path.id_type.value | invalid | /users/${CC_SCHEMA_KVNR}/channels?id_type=invalid&include_inactive=true |
      | invalid-boolean | $.path.include_inactive.value | banana | /users/${CC_SCHEMA_KVNR}/channels?id_type=kvnr&include_inactive=banana |
      | identifier-pattern | $.path | /notification-service-rs/users/!/channels?id_type=kvnr&include_inactive=true | /users/!/channels?id_type=kvnr&include_inactive=true |
      | identifier-length | $.path | /notification-service-rs/users/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/channels?id_type=kvnr&include_inactive=true | /users/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/channels?id_type=kvnr&include_inactive=true |
