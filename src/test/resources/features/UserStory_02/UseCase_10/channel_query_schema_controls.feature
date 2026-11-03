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

  Szenariogrundriss: Schema-konforme RS-Channel-Abfrage <Fall>
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
    Und erzeuge eindeutige Test-ID mit Präfix "5-2-KH-SCHEMA" und speichere in Variable "CC_SCHEMA_TELEMATIK"
    Und TGR setze den default header "popp" auf den Wert "${CC_SCHEMA_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}/users/<Nutzer>/channels/query" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"id_type":"<Typ>"<Optional>}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit dem Pfad "^/users/<Nutzer>/channels.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<Status>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "<Kontrollknoten>" überein mit "<Kontrollwert>"

    Beispiele:
      | Fall | Nutzer | Typ | Optional | Status | Kontrollknoten | Kontrollwert |
      | default | ${CC_SCHEMA_KVNR} | kvnr |  | 200 | $.body.user.id_type | kvnr |
      | false | ${CC_SCHEMA_KVNR} | kvnr | ,"include_inactive":false | 200 | $.body.user.id_type | kvnr |
      | true | ${CC_SCHEMA_KVNR} | kvnr | ,"include_inactive":true | 200 | $.body.user.id_type | kvnr |
      | telematik | ${CC_SCHEMA_TELEMATIK} | telematik-id |  | 404 | $.body.errorCode | USER_NOT_FOUND |
      | max-identifier | AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA | telematik-id |  | 404 | $.body.errorCode | USER_NOT_FOUND |
