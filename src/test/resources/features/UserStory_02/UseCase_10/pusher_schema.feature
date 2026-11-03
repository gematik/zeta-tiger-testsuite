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
@UseCase_02_10
@A_29986
@TA_A_29986_01
@normal
@MASVS-CODE
Funktionalität: Der Notification Service validiert das formale Pusher-Schema

  Szenariogrundriss: Pusher mit isoliertem Schemafehler <Fall>
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
    Und TGR speichere Wert des Knotens "$.body.encryption" der aktuellen Anfrage in der Variable "CC_SCHEMA_ENCRYPTION"
    Und TGR speichere Wert des Knotens "$.body.encryption.method" der aktuellen Anfrage in der Variable "CC_SCHEMA_METHOD"
    Und TGR speichere Wert des Knotens "$.body.encryption.time_iss_created" der aktuellen Anfrage in der Variable "CC_SCHEMA_TIME_ISS_CREATED"
    Und TGR speichere Wert des Knotens "$.body.encryption.iss" der aktuellen Anfrage in der Variable "CC_SCHEMA_ISS"
    Und TGR speichere Wert des Knotens "$.body.encryption.key_identifier" der aktuellen Anfrage in der Variable "CC_SCHEMA_KEY_IDENTIFIER"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers~1set/post/requestBody/content/application~1json/schema"
    Und erzeuge eindeutige Test-ID mit Präfix "schema-before" und speichere in Variable "CC_SCHEMA_PUSHKEY"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und TGR setze lokale Variable "CC_SCHEMA_VALID_CONDITION" auf "isRequest && request.path =~ '.*/pushers/set' && request.bodyAsString.contains('${CC_SCHEMA_PUSHKEY}')"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_VALID_CONDITION}" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false}
      """
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_SCHEMA_PUSHKEY}","appId":"de.gematik.zeta.schema","appDisplayName":"Schema","deviceDisplayName":"Schema","profileTag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_SCHEMA_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_SCHEMA_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers~1set/post/requestBody/content/application~1json/schema"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.append" überein mit "false"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.profile_tag" überein mit "schema"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.format" überein mit "event_id_only"
    Und erzeuge eindeutige Test-ID mit Präfix "schema-invalid" und speichere in Variable "CC_SCHEMA_PUSHKEY"
    Und TGR setze lokale Variable "CC_SCHEMA_CONDITION" auf "isRequest && request.path =~ '.*/pushers/set' && request.bodyAsString.contains('${CC_SCHEMA_PUSHKEY}')"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_CONDITION}" die Manipulation auf Feld "$.body" und Wert '<Body>'
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_SCHEMA_PUSHKEY}","appId":"de.gematik.zeta.schema","appDisplayName":"Schema","deviceDisplayName":"Schema","profileTag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_SCHEMA_KVNR}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers~1set/post/requestBody/content/application~1json/schema" mit Fehlern "<Fehler>" ausschließlich bei "<Position>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und erzeuge eindeutige Test-ID mit Präfix "schema-after" und speichere in Variable "CC_SCHEMA_PUSHKEY"
    Und TGR setze lokale Variable "CC_SCHEMA_VALID_CONDITION" auf "isRequest && request.path =~ '.*/pushers/set' && request.bodyAsString.contains('${CC_SCHEMA_PUSHKEY}')"
    Und Setze im TigerProxy für die Nachricht "${CC_SCHEMA_VALID_CONDITION}" die Manipulation auf Feld "$.body" und folgenden JSON-Wert:
      """
      {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false}
      """
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_SCHEMA_PUSHKEY}","appId":"de.gematik.zeta.schema","appDisplayName":"Schema","deviceDisplayName":"Schema","profileTag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_SCHEMA_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_SCHEMA_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_SCHEMA_BODY"
    Und validiere "${CC_SCHEMA_BODY}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers~1set/post/requestBody/content/application~1json/schema"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.append" überein mit "false"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.profile_tag" überein mit "schema"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.format" überein mit "event_id_only"

    Beispiele:
      | Fall | Body | Fehler | Position |
      | missing-kind | {"app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | required |  |
      | missing-app_id | {"kind":"http","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | required |  |
      | missing-pushkey | {"kind":"http","app_id":"de.gematik.zeta.schema","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | required |  |
      | type-kind | {"kind":3,"app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /kind |
      | type-app_id | {"kind":"http","app_id":3,"pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /app_id |
      | type-pushkey | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":3,"app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /pushkey |
      | type-app_display_name | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":3,"device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /app_display_name |
      | type-device_display_name | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":3,"profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /device_display_name |
      | type-profile_tag | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":3,"lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /profile_tag |
      | type-lang | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":3,"data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /lang |
      | type-data | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":3,"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /data |
      | type-encryption | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":3,"append":false} | type | /encryption |
      | type-append | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":3} | type | /append |
      | type-data-url | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":3,"format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /data/url |
      | type-data-format | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":3},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | type | /data/format |
      | format-url | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","data":{"url":"not a URI","format":"event_id_only"},"encryption":${CC_SCHEMA_ENCRYPTION},"append":false} | format | /data/url |
      | root-type | true | type |  |
      | type-encryption-method | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":{"method":3,"time_iss_created":"${CC_SCHEMA_TIME_ISS_CREATED}","iss":"${CC_SCHEMA_ISS}","key_identifier":"${CC_SCHEMA_KEY_IDENTIFIER}"},"app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","append":false} | type | /encryption/method |
      | type-encryption-time_iss_created | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":{"method":"${CC_SCHEMA_METHOD}","time_iss_created":3,"iss":"${CC_SCHEMA_ISS}","key_identifier":"${CC_SCHEMA_KEY_IDENTIFIER}"},"app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","append":false} | type | /encryption/time_iss_created |
      | type-encryption-iss | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":{"method":"${CC_SCHEMA_METHOD}","time_iss_created":"${CC_SCHEMA_TIME_ISS_CREATED}","iss":3,"key_identifier":"${CC_SCHEMA_KEY_IDENTIFIER}"},"app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","append":false} | type | /encryption/iss |
      | type-encryption-key_identifier | {"kind":"http","app_id":"de.gematik.zeta.schema","pushkey":"${CC_SCHEMA_PUSHKEY}","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"},"encryption":{"method":"${CC_SCHEMA_METHOD}","time_iss_created":"${CC_SCHEMA_TIME_ISS_CREATED}","iss":"${CC_SCHEMA_ISS}","key_identifier":3},"app_display_name":"Schema","device_display_name":"Schema","profile_tag":"schema","lang":"de","append":false} | type | /encryption/key_identifier |
