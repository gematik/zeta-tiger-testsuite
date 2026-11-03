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

@UseCase_02_05
@require_kubectl
@oidc
Funktionalität: Beidseitige Authentisierung der tatsächlichen NS-Gateway-Verbindung

  @A_29982
  @TA_A_29982_01
  @normal
  @deployment_modification
  @reset_notification_pushers
  @ns_gateway_tls_profile
  Szenariogrundriss: Der echte NS authentisiert die Gateway-Verbindung - <ziel>
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_MTLS_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "TA29982-01" und speichere in Variable "CC_MTLS_PUSHER"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.url" auf "<gateway_url>"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.appId" auf "de.gematik.zeta.ta29982.mtls"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${CC_MTLS_PUSHER}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_MTLS_PUSHER}","appId":"de.gematik.zeta.ta29982.mtls","appDisplayName":"ZETA Testsuite","deviceDisplayName":"mTLS","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_MTLS_PUSHER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_MTLS_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels": [{"id": "zeta", "status": "enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_MTLS_PUSHER}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Gegeben sei ein Gateway-mTLS-Prüfprofil des deployten Notification Service für Pusher "${CC_MTLS_PUSHER}" am Ziel <ziel>
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Gegeben sei das installierte NS-Zertifikatprofil <client> und das Gateway-Zertifikatprofil <server>
    Und erzeuge eindeutige Test-ID mit Präfix "TA29982-01" und speichere in Variable "CC_MTLS_MARKER"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_MTLS_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_MTLS_POPP"
    Und TGR setze den default header "popp" auf den Wert "${CC_MTLS_POPP}"
    Wenn starte den konfigurierten Gateway-TLS-Endpunkt
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_MTLS_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_MTLS_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.client.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_MTLS_MARKER}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_MTLS_ID"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_MTLS_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_MTLS_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_id" überein mit "\Q${CC_MTLS_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.event_type" überein mit "registered_client_activity"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_MTLS_ID}\E"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.client.notificationTestEventsPath}" haben
    Und entspricht die tatsächliche Gateway-Verbindung für Notification "${CC_MTLS_ID}" mit Korrelation "${CC_MTLS_MARKER}" dem gewählten mTLS-Profil

    Beispiele:
      | ziel       | client  | server     | gateway_url                                                   |
      | baseline   | good    | good       | ${testdata.notificationTls.gateway.targets.baseline.url}       |
      | missing    | missing | good       | ${testdata.notificationTls.gateway.targets.missing.url}        |
      | foreign    | foreign | good       | ${testdata.notificationTls.gateway.targets.foreign.url}        |
      | untrusted  | good    | foreign    | ${testdata.notificationTls.gateway.targets.untrusted.url}      |
      | wrong-host | good    | wrong-host | ${testdata.notificationTls.gateway.targets.wrong-host.url}     |
      | control    | good    | good       | ${testdata.notificationTls.gateway.targets.control.url}        |
