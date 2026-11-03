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
@deployment_modification
@UseCase_02_05
@A_29991
@TA_A_29991_04
@normal
Funktionalität: Der Notification Service verwendet zum Gateway zulässige TLS-Versionen

  Hintergrund:
    Gegeben sei eine eindeutige Test-ID mit Präfix "ns-tls" in Variable "CC_TLS_PEER"
    Und erstelle die temporäre TLS-Gegenstelle "${CC_TLS_PEER}" mit ECDSA-Serverzertifikat
    Und im Container "notification-service" des Deployments "${testdata.ccNotification.rsDeployment}" gelten folgende Umgebungsvariablen:
      """
      {"PUSH_GATEWAY_RETRY_GIVE_UP_AFTER":"PT20S"}
      """
    Und ergänze die TLS-Gegenstelle "${CC_TLS_PEER}" als Push-Gateway-Ziel im Deployment "${testdata.ccNotification.rsDeployment}"
    Und ergänze die TLS-Gegenstelle "${CC_TLS_PEER}" als Push-Gateway-Ziel im Deployment "${testdata.notificationService.fdvDeployment}"
    Und im Container "testfachdienst" des Deployments "testfachdienst" gelten folgende Umgebungsvariablen:
      """
      {"NOTIFICATION_SERVICE_RS_BASE_URL":"${paths.fachdienst.routingUpstream}/notification-service-rs"}
      """

  Szenario: Angebotenes TLS 1.3 funktioniert zwischen TLS-1.2-Kontrollen
    Gegeben sei eine freie KVNR aus dem Notification-Pool in der Variablen "CC_TLS_KVNR"
    Und TGR setze lokale Variable "schemaValidation.soft" auf "false"
    Und erzeuge eindeutige Test-ID mit Präfix "tls-pusher" und speichere in Variable "CC_TLS_PUSHKEY"
    Und erzeuge eindeutige Test-ID mit Präfix "tls-event" und speichere in Variable "CC_TLS_MARKER"
    Und erzeuge eindeutige Test-ID mit Präfix "tls-key" und speichere in Variable "CC_TLS_KEY"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"pushkey":"${CC_TLS_PUSHKEY}","appId":"de.gematik.zeta.tlsversions","appDisplayName":"Schema","deviceDisplayName":"Schema","lang":"de","data":{"url":"https://${CC_TLS_PEER}/push/v1/","format":"event_id_only"}}
      """
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}" und Knoten "$.body.pushkey" der mit "\Q${CC_TLS_PUSHKEY}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"channels":[{"id":"zeta","status":"enabled"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${CC_TLS_PUSHKEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_TLS_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_TLS_POPP"
    Und ermittle den aktuellen Ready-Pod für das Deployment "${testdata.ccNotification.rsDeployment}" und speichere in der Variable "CC_TLS_NS_POD"
    Und ermittle aus den Pods im Namespace "${zetaDeploymentConfig.namespace}" den Wert aus der Spalte "IP" der Zeile mit "${CC_TLS_NS_POD}" und speichere in der Variable "CC_TLS_NS_IP"
    Und ermittle die konfigurierte Umgebungsvariable "PUSH_GATEWAY_RETRY_GIVE_UP_AFTER" des Containers "notification-service" im Pod "${CC_TLS_NS_POD}" und speichere in der Variable "CC_TLS_RETRY_BOUND"
    Und TGR prüfe Variable "CC_TLS_RETRY_BOUND" stimmt überein mit "PT20S"
    Und exportiere das TLS-Secret "${CC_TLS_PEER}" mit CA-Secret "${CC_TLS_PEER}-ca" in das Verzeichnis "target/notification-tls/${CC_TLS_PEER}"
    Und eine Kubernetes-verwaltete TlsTestTool-Sitzung für Gateway-Service "${CC_TLS_PEER}"
    Gegeben sei eine TlsTestTool-Server-Konfiguration mit Zertifikat "target/notification-tls/${CC_TLS_PEER}/tls.crt" und Schlüssel "target/notification-tls/${CC_TLS_PEER}/tls.key" und CA "target/notification-tls/${CC_TLS_PEER}/ca.crt":
      """
      host=0.0.0.0
      port=4433
      waitBeforeClose=1
      logLevel=low
      listenTimeout=25
      tlsVersion=(3,3)
      mode=server
      tlsLibrary=mbed TLS
      tlsCipherSuites=(0xC0,0x2B),(0xC0,0x2C)
      """
    Und verbinde den Gateway-Service "${CC_TLS_PEER}" mit dem reservierten TLS-Testserver
    Und TGR setze den default header "popp" auf den Wert "${CC_TLS_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_TLS_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_TLS_MARKER}-before"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "^/notifications$" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_TLS_MARKER}-before\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_TLS_NOTIFICATION_ID"
    Und merke den Abschlussnachweis im Container "notification-service" im Pod "${CC_TLS_NS_POD}" mit Muster "Notification ${CC_TLS_NOTIFICATION_ID} dispatch outcome: (failed|dispatched|partially_rejected)"
    Und werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und die TLS-Gegenstelle hat die IP-Adresse "${CC_TLS_NS_IP}"
    Und die ClientHello-TLS-Version ist 1.2
    Und die supported_versions-Erweiterung enthält ausschließlich TLS 1.2 oder 1.3 und speichert TLS-1.3-Unterstützung in "CC_TLS_OFFERS_13"
    Und ist der TLS-Handshake erfolgreich
    Und enthält das Protokoll des Containers "notification-service" im Pod "${CC_TLS_NS_POD}" innerhalb von 45 Sekunden das Muster "Notification ${CC_TLS_NOTIFICATION_ID} dispatch outcome: failed"
    Gegeben sei eine TlsTestTool-Server-Konfiguration mit Zertifikat "target/notification-tls/${CC_TLS_PEER}/tls.crt" und Schlüssel "target/notification-tls/${CC_TLS_PEER}/tls.key" und CA "target/notification-tls/${CC_TLS_PEER}/ca.crt":
      """
      host=0.0.0.0
      port=4433
      waitBeforeClose=1
      logLevel=low
      listenTimeout=25
      tlsVersion=(3,4)
      mode=server
      tlsLibrary=OpenSSL
      tlsCipherSuites=(0x13,0x01),(0x13,0x02)
      """
    Und verbinde den Gateway-Service "${CC_TLS_PEER}" mit dem reservierten TLS-Testserver
    Und TGR setze den default header "popp" auf den Wert "${CC_TLS_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_TLS_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_TLS_MARKER}-legacy"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "^/notifications$" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_TLS_MARKER}-legacy\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_TLS_NOTIFICATION_ID"
    Und merke den Abschlussnachweis im Container "notification-service" im Pod "${CC_TLS_NS_POD}" mit Muster "Notification ${CC_TLS_NOTIFICATION_ID} dispatch outcome: (failed|dispatched|partially_rejected)"
    Und werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und die TLS-Gegenstelle hat die IP-Adresse "${CC_TLS_NS_IP}"
    Und entspricht der TLS-1.3-Handshake der zuvor angebotenen Unterstützung "${CC_TLS_OFFERS_13}"
    Und enthält das Protokoll des Containers "notification-service" im Pod "${CC_TLS_NS_POD}" innerhalb von 45 Sekunden das Muster "Notification ${CC_TLS_NOTIFICATION_ID} dispatch outcome: failed"
    Gegeben sei eine TlsTestTool-Server-Konfiguration mit Zertifikat "target/notification-tls/${CC_TLS_PEER}/tls.crt" und Schlüssel "target/notification-tls/${CC_TLS_PEER}/tls.key" und CA "target/notification-tls/${CC_TLS_PEER}/ca.crt":
      """
      host=0.0.0.0
      port=4433
      waitBeforeClose=1
      logLevel=low
      listenTimeout=25
      tlsVersion=(3,3)
      mode=server
      tlsLibrary=mbed TLS
      tlsCipherSuites=(0xC0,0x2B),(0xC0,0x2C)
      """
    Und verbinde den Gateway-Service "${CC_TLS_PEER}" mit dem reservierten TLS-Testserver
    Und TGR setze den default header "popp" auf den Wert "${CC_TLS_POPP}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${CC_TLS_KVNR}","event_type":"registered_client_activity","correlation_id":"${CC_TLS_MARKER}-after"}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "^/notifications$" und Knoten "$.body.payload.correlation_id" der mit "\Q${CC_TLS_MARKER}-after\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR speichere Wert des Knotens "$.body.notification_id" der aktuellen Antwort in der Variable "CC_TLS_NOTIFICATION_ID"
    Und merke den Abschlussnachweis im Container "notification-service" im Pod "${CC_TLS_NS_POD}" mit Muster "Notification ${CC_TLS_NOTIFICATION_ID} dispatch outcome: (failed|dispatched|partially_rejected)"
    Und werden die Tls-Test-Tool-Protokolle abgerufen
    Und die TCP-IP-Verbindung wurde hergestellt
    Und die TLS-Gegenstelle hat die IP-Adresse "${CC_TLS_NS_IP}"
    Und die ClientHello-TLS-Version ist 1.2
    Und die supported_versions-Erweiterung enthält ausschließlich TLS 1.2 oder 1.3 und speichert TLS-1.3-Unterstützung in "CC_TLS_OFFERS_13"
    Und ist der TLS-Handshake erfolgreich
    Und enthält das Protokoll des Containers "notification-service" im Pod "${CC_TLS_NS_POD}" innerhalb von 45 Sekunden das Muster "Notification ${CC_TLS_NOTIFICATION_ID} dispatch outcome: failed"
