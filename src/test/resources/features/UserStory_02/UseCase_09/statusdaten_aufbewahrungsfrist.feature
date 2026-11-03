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

@UseCase_02_09
@require_kubectl
@oidc
Funktionalität: Der Notification Service begrenzt die Speicherung von Verarbeitungsdaten

  @A_29988
  @TA_A_29988_01
  @normal
  @ns_retention_profile
  @notification_pusher_lifecycle
  @deployment_modification
  @MASVS-PRIVACY
  Szenario: Zwei tatsächliche Verarbeitungssätze besitzen eine kurze individuelle Aufbewahrungsfrist
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_NS_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "zeta-lifecycle-TA29988" und speichere in Variable "CC_NS_PUSHKEY"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${CC_NS_PUSHKEY}"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.appId" auf "de.gematik.zeta.ta29988.retention"
    Und die gezielte Bereinigung für Pusher "${CC_NS_PUSHKEY}", Anwendung "de.gematik.zeta.ta29988.retention" und Nutzer "${CC_NS_KVNR}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${CC_NS_PUSHKEY}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels": [{"id": "zeta", "status": "enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_NS_PUSHKEY}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "enabled"

    Gegeben sei ein lokales NS-Aufbewahrungsprofil mit 120 Sekunden TTL und höchstens 5 Sekunden Löschbudget
    Und erzeuge eindeutige Test-ID mit Präfix "TA29988-01" und speichere in Variable "CC_NS_FIRST_MARKER"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_NS_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_NS_POPP"
    Und TGR setze den default header "popp" auf den Wert "${CC_NS_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_NS_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_NS_FIRST_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.client.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_NS_FIRST_MARKER}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_NS_FIRST_ID"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_NS_FIRST_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_id" überein mit "\Q${CC_NS_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_NS_FIRST_ID}\E"
    Und hat die Aufbewahrungsnachricht erste die eindeutige ID "${CC_NS_FIRST_ID}" und Korrelation "${CC_NS_FIRST_MARKER}"
    Und hat die Aufbewahrungsnachricht erste einen frischen Verarbeitungsdatensatz mit 120 Sekunden TTL
    Wenn warte 2 Sekunden
    Und erzeuge eindeutige Test-ID mit Präfix "TA29988-01" und speichere in Variable "CC_NS_SECOND_MARKER"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_NS_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_NS_POPP"
    Und TGR setze den default header "popp" auf den Wert "${CC_NS_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_NS_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_NS_SECOND_MARKER}"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.client.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_NS_SECOND_MARKER}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_NS_SECOND_ID"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationTestEventsPath}" und Knoten "$.body.correlation_id" der mit "\Q${CC_NS_SECOND_MARKER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_NS_KVNR}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_id" überein mit "\Q${CC_NS_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.notification_id" überein mit "\Q${CC_NS_SECOND_ID}\E"
    Und hat die Aufbewahrungsnachricht zweite die eindeutige ID "${CC_NS_SECOND_ID}" und Korrelation "${CC_NS_SECOND_MARKER}"
    Und hat die Aufbewahrungsnachricht zweite einen frischen Verarbeitungsdatensatz mit 120 Sekunden TTL
    Wenn warte 2 Sekunden
    Dann besitzen die Aufbewahrungsnachrichten erste und zweite individuelle Ablaufzeiten
    Und ist die Aufbewahrungsnachricht erste im NS-Verarbeitungslog eindeutig korreliert
    Und ist die Aufbewahrungsnachricht zweite im NS-Verarbeitungslog eindeutig korreliert
    Und ist die Aufbewahrungsnachricht erste spätestens 5 Sekunden nach ihrer TTL physisch gelöscht
    Und ist die Aufbewahrungsnachricht zweite spätestens 5 Sekunden nach ihrer TTL physisch gelöscht
