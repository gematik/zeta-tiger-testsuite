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

@UseCase_02_08
@require_kubectl
@oidc
Funktionalität: Authentisierung beider RS-Operationen mit technischem mTLS-Nutzer

  @A_29980
  @TA_A_29980_01
  @normal
  @deployment_modification
  @reset_notification_pushers
  @ns_rs_tls_profile
  Szenario: Der echte NS authentisiert beide RS-Operationen mit dem zugeordneten Zertifikat
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_RS_MTLS_KVNR"
    Und erzeuge eindeutige Test-ID mit Präfix "TA29980-01" und speichere in Variable "CC_RS_MTLS_PUSHER"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.appId" auf "de.gematik.zeta.ta29980.rs"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${CC_RS_MTLS_PUSHER}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_RS_MTLS_PUSHER}","appId":"de.gematik.zeta.ta29980.rs","appDisplayName":"ZETA Testsuite","deviceDisplayName":"mTLS","lang":"de","data":{"url":"${testdata.notificationService.pusher.url}","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_RS_MTLS_PUSHER}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_RS_MTLS_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels": [{"id": "zeta", "status": "enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_RS_MTLS_PUSHER}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Gegeben sei ein RS-mTLS-Prüfprofil des deployten Notification Service für Nutzer "${CC_RS_MTLS_KVNR}"
    Wenn der technische RS-Nutzer die aktiven Kanäle über mTLS abfragt
    Und der technische RS-Nutzer eine Benachrichtigung über mTLS einreicht
    Dann ist die RS-mTLS-Operation getActiveChannels vollständig dem technischen Nutzer und SDK-Empfänger zugeordnet
    Und ist die RS-mTLS-Operation submitNotification vollständig dem technischen Nutzer und SDK-Empfänger zugeordnet
