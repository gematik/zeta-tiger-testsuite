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

@UseCase_02_06
@require_kubectl
@oidc
Funktionalität: Der Notification Service antwortet bei temporärer Nichtverfügbarkeit selbst mit 503

  @A_29987
  @TA_A_29987_04
  @normal
  @ns_dispatch_profile
  @reset_notification_pushers
  @Ignore
  Szenario: Eine volle NS-Versandwarteschlange lehnt gültige Notifications vorübergehend ab
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_NS_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "TA29987-04" und speichere in Variable "CC_NS_PUSHKEY"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${CC_NS_PUSHKEY}"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.appId" auf "de.gematik.zeta.ta29987.backlog"
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

    # Das SDK bereitet den Empfänger vor. Der separate RS-Akteur verwendet die vorhandene NS-mTLS-Route.
    # Die Testdriver-API stellt keine kontrollierte parallele RS-Annahmeprüfung mit NS-Logkorrelation bereit.
    Gegeben sei ein isoliertes NS-Versandprofil mit einem Worker und einem Warteschlangenplatz
    Wenn der RS für Nutzer "${CC_NS_KVNR}" eine Kontrollnachricht vor der NS-Überlastung einreicht
    Dann ist die Kontrollnachricht vor der NS-Überlastung erfolgreich angenommen
    Wenn warte 2 Sekunden
    Dann stimmen alle bisherigen RS-Antworten mit eindeutig korrelierten NS-Zugriffslogs überein
    Wenn das lokale Push-Gateway für die NS-Warteschlangenprüfung pausiert wird
    Und der RS 16 Benachrichtigungen gleichzeitig in die NS-Warteschlange einreicht
    Dann belegt die NS-Antwortserie die volle Warteschlange mit mindestens einem HTTP 503
    Wenn das lokale Push-Gateway nach der NS-Warteschlangenprüfung fortgesetzt wird
    Dann nimmt der NS innerhalb von 20 Sekunden wieder eine neue Benachrichtigung an
    Wenn warte 2 Sekunden
    Dann stimmen alle bisherigen RS-Antworten mit eindeutig korrelierten NS-Zugriffslogs überein
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationServiceRsPath}" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_NS_UNAVAILABLE_CORRELATION}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "\Q${CC_NS_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "503"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.errorCode" überein mit "SERVICE_UNAVAILABLE"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationServiceRsPath}" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_NS_RECOVERY_CORRELATION}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
