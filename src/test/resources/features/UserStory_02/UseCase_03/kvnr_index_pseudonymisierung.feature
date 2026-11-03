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

@A_29960
@TA_A_29960_05
@normal
@oidc
@UseCase_02_03
@notification_index_observation
@require_kubectl
@MASVS-PRIVACY
Funktionalität: Der Notification Service indiziert die KVNR ausschließlich pseudonym

  Szenario: SDK-Pusher und Channels verwenden pseudonyme KVNR-Indexschlüssel
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_NS_INDEX_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "zeta-lifecycle-TA29960" und speichere in Variable "CC_NS_INDEX_PUSHKEY"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${CC_NS_INDEX_PUSHKEY}"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.appId" auf "de.gematik.zeta.ta29960.index"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_INDEX_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.pushers" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"array","maxItems":0}
      """
    Gegeben sei der aktuelle NS-Indexbestand als Ausgangszustand
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_NS_INDEX_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_INDEX_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und besitzt der NS neue Indexeinträge ohne Klartext-KVNR "${CC_NS_INDEX_KVNR}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_INDEX_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.pushers" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"array","minItems":1,"maxItems":1,"items":{"type":"object","required":["pushkey","app_id"],"properties":{"pushkey":{"const":"${CC_NS_INDEX_PUSHKEY}"},"app_id":{"const":"de.gematik.zeta.ta29960.index"}}}}
      """
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels":[{"id":"zeta","status":"enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_NS_INDEX_PUSHKEY}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_INDEX_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "enabled"
    Und besitzt der NS neue Indexeinträge ohne Klartext-KVNR "${CC_NS_INDEX_KVNR}"
