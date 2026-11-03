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

@oidc
@require_kubectl
@UseCase_02_01
Funktionalität: Push-Konfigurationen von ZETA Clients verwalten

  Hintergrund:
    # Reserviert eine dedizierte Pool-Identität und bereinigt Reste vor der Wiederverwendung.
    # Der vollständige OIDC-Registrierungsnachweis verwendet weiterhin eine frische Zufalls-KVNR.
    Gegeben sei eine freie KVNR aus dem Notification-Pool

  @A_25652
  @TA_A_25652_04
  @TA_A_25652_05
  @normal
  Szenario: Notification Service verwaltet die Push-Konfiguration eines ZETA Clients
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-management-proof&appId=de.gematik.zeta.testsuite.management-proof"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Anlegen: Der Notification Service speichert die vom Client übermittelte Push-Konfiguration.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "pushkey": "zeta-testsuite-management-proof",
        "appId": "de.gematik.zeta.testsuite.management-proof",
        "appDisplayName": "ZETA Testsuite",
        "deviceDisplayName": "ZETA Verwaltungsnachweis",
        "profileTag": "verwaltungsnachweis",
        "lang": "de",
        "data": {
          "url": "${testdata.notificationService.pusher.url}",
          "format": "event_id_only"
        }
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_display_name" überein mit "ZETA Testsuite"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.device_display_name" überein mit "ZETA Verwaltungsnachweis"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.profile_tag" überein mit "verwaltungsnachweis"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.lang" überein mit "de"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.url" überein mit "${testdata.notificationService.pusher.url}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.format" überein mit "event_id_only"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.method" überein mit "aes-hmac-sha256"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.time_iss_created" überein mit "[0-9]{4}-[0-9]{2}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.iss" überein mit "[0-9a-f]{64}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.key_identifier" überein mit "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].kind" überein mit "http"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].app_display_name" überein mit "ZETA Testsuite"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].device_display_name" überein mit "ZETA Verwaltungsnachweis"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].profile_tag" überein mit "verwaltungsnachweis"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].lang" überein mit "de"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].data.url" überein mit "${testdata.notificationService.pusher.url}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].data.format" überein mit "event_id_only"

    # Aktualisieren: Derselbe Pusher bleibt adressiert und seine geänderten Werte werden gespeichert.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "pushkey": "zeta-testsuite-management-proof",
        "appId": "de.gematik.zeta.testsuite.management-proof",
        "appDisplayName": "ZETA Testsuite geändert",
        "deviceDisplayName": "ZETA Verwaltungsnachweis geändert",
        "profileTag": "verwaltungsnachweis-geaendert",
        "lang": "en",
        "data": {
          "url": "${testdata.notificationService.pusher.url}",
          "format": "event_id_only"
        }
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_display_name" überein mit "ZETA Testsuite geändert"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.device_display_name" überein mit "ZETA Verwaltungsnachweis geändert"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.profile_tag" überein mit "verwaltungsnachweis-geaendert"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.lang" überein mit "en"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.url" überein mit "${testdata.notificationService.pusher.url}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.format" überein mit "event_id_only"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].app_display_name" überein mit "ZETA Testsuite geändert"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].device_display_name" überein mit "ZETA Verwaltungsnachweis geändert"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].profile_tag" überein mit "verwaltungsnachweis-geaendert"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].lang" überein mit "en"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')].data.format" überein mit "event_id_only"

    # Löschen: Der Notification Service entfernt genau die zuvor verwaltete Push-Konfiguration.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-management-proof&appId=de.gematik.zeta.testsuite.management-proof"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-management-proof"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.testsuite.management-proof"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.[?(@.pushkey == 'zeta-testsuite-management-proof' && @.app_id == 'de.gematik.zeta.testsuite.management-proof')]"

  @A_29977
  @TA_A_29977_01
  @TA_A_29977_02
  @TA_A_29977_03
  @normal
  Szenario: Notification Service akzeptiert nur den zum Zugriffstoken gebundenen DPoP-Schlüssel
    # Referenzzugriff: Ein akzeptierter Aufruf liefert Token und DPoP-Proof für den Binding-Nachweis.
    # F01: Isoliert die Referenzanfrage.
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    # F02: Sendet den gültigen Pusher-Aufruf.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    # F03: Wählt die Client-Antwort.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    # F04: Prüft die gültige Referenzantwort.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Binding-Nachweis: Der JKT des DPoP-JWK muss dem cnf.jkt des Access Tokens entsprechen.
    # F05: Wählt die geschützte Anfrage.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    # F06: Prüft den DPoP-Proof.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    # F07: Speichert den Referenz-Proof.
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "a29977BoundDpopJwt"
    # F08: Berechnet den Referenz-JKT.
    Und berechne JKT aus JWT Header JWK "${a29977BoundDpopJwt}" und speichere in Variable "a29977BoundDpopJkt"
    # F09: Prüft die JKT-Bindung (TA_A_29977_01).
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" überein mit "${a29977BoundDpopJkt}"

    # Manipulationsvorbereitung: Ein fremder, gültig signierbarer Schlüssel isoliert den Binding-Fehler.
    # F10: Lädt den fremden DPoP-Schlüssel.
    Und TGR setze lokale Variable "a29977ForeignDpopKey" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    # F11: Beschränkt die Manipulation auf den Pusher-Aufruf.
    Und TGR setze lokale Variable "a29977NotificationPusherPathCondition" auf "isRequest && request.path =~ '^${paths.guard.notificationPushersPath}$'"
    # F12: Ersetzt den DPoP-JWK durch den fremden Schlüssel.
    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "header.typ" auf Wert "dpop+jwt" mit privatem Schlüssel "${a29977ForeignDpopKey}" für Pfad "${a29977NotificationPusherPathCondition}" und 1 Ausführungen und ersetze JWK

    # Negativaufruf: Der Zugriff wird mit fremdem Proof und einem Access Token mit Referenz-JKT gesendet.
    # F13: Isoliert die manipulierte Anfrage.
    Und TGR lösche aufgezeichnete Nachrichten
    # F14: Sendet den manipulierten Pusher-Aufruf.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    # F15: Wählt die manipulierte Anfrage.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationPushersPath}"

    # Negativnachweis: Der Proof bleibt gültig, aber der JKT-Mismatch muss zur Ablehnung führen.
    # F16: Speichert den manipulierten Proof.
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "a29977ManipulatedDpopJwt"
    # F17: Prüft die Signatur des fremden Proofs.
    Und verifiziere ES256 Signatur von DPoP JWT "${a29977ManipulatedDpopJwt}"
    # F18: Berechnet den fremden JKT.
    Und berechne JKT aus JWT Header JWK "${a29977ManipulatedDpopJwt}" und speichere in Variable "a29977ManipulatedDpopJkt"
    # F19: Prüft den Referenz-JKT im Access Token.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" überein mit "${a29977BoundDpopJkt}"
    # F20: Prüft den JKT-Mismatch (TA_A_29977_02).
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" stimmt nicht überein mit "${a29977ManipulatedDpopJkt}"
    # F21: Prüft die Ablehnung (TA_A_29977_02).
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401|403"

    # Fehlender Proof: Der DPoP-Header wird nur aus dem folgenden Pusher-Aufruf entfernt.
    # F22: Entfernt den DPoP-Header für TA_A_29977_03.
    Und Setze im TigerProxy für die Nachricht "${a29977NotificationPusherPathCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "${headers.dpop.lineRegex}" und Wert "" und 1 Ausführungen
    # F23: Isoliert den Aufruf ohne Proof für TA_A_29977_03.
    Und TGR lösche aufgezeichnete Nachrichten
    # F24: Sendet den Aufruf ohne DPoP-Proof für TA_A_29977_03.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"

    # Fehlender-Proof-Nachweis: Der Request ohne DPoP-Header muss abgelehnt werden.
    # F25: Wählt den Request für den Nachweis von TA_A_29977_03.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationPushersPath}"
    # F26: Prüft den fehlenden DPoP-Proof (TA_A_29977_03).
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.dpop.root}"
    # F27: Prüft die Ablehnung (TA_A_29977_03).
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401|403"


  @A_29978
  @TA_A_29978_01
  @normal
  @notification_pusher_ownership
  Szenario: Notification Service ordnet Pusher beim Nutzerwechsel gemäß append-Default neu zu
    # Maßgeblich ist definitions/pusher_post_put_delete.yaml: Ohne append gilt false.
    # Derselbe Pushkey und dieselbe App-ID werden für Nutzer 2 registriert und bei Nutzer 1 entfernt.
    # TA_A_29978_04 passt daher nicht zu dieser erlaubten Registrierung.
    Gegeben sei eine eindeutige Test-ID mit Präfix "zeta-lifecycle-ownership" in Variable "notificationPusherPushkey"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${notificationPusherPushkey}"
    Und TGR setze lokale Variable "notificationOwnerId" auf "${oidcTestData.kvnr}"
    Und die nutzergebundene Bereinigung für Pusher "${notificationPusherPushkey}", Anwendung "${testdata.notificationService.pusher.appId}" und Nutzer "${notificationOwnerId}"
    Und TGR setze lokale Variable "schemaValidation.soft" auf "true"

    # Nutzer 1 registriert den Pusher; seine Identität kommt unabhängig aus der Testvorbereitung.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.scope}" überein mit "(^|.* )notification[.]pusher[.]write( .*|$)"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationPusherPushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.append"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.encryption.iss"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.encryption.key_identifier"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.encryption.iss" der aktuellen Anfrage in der Variable "notificationOwnerEncryptionIss"
    Und TGR speichere Wert des Knotens "$.body.encryption.key_identifier" der aktuellen Anfrage in der Variable "notificationOwnerEncryptionKeyIdentifier"

    # Die gespeicherte Konfiguration muss unter Nutzer 1 vollständig lesbar sein.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      !{file('src/test/resources/testdata/push/pusher_lifecycle_response.schema.json')}
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationOwnerPushersResponse"
    Und validiere "${notificationOwnerPushersResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

    Und TGR speichere Wert des Knotens "$.body.pushers.[?(@.pushkey == '${notificationPusherPushkey}')]" der aktuellen Antwort in der Variable "notificationOriginalPusher"

    # Unabhängiger Kontroll-Pusher bleibt bei Nutzer 1 auch während des Transfers bestehen.
    Und erzeuge eindeutige Test-ID mit Präfix "zeta-lifecycle-owner-control" und speichere in Variable "notificationOwnerControlKey"
    Und die nutzergebundene Bereinigung für Pusher "${notificationOwnerControlKey}", Anwendung "${testdata.notificationService.pusher.appId}" und Nutzer "${notificationOwnerId}"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${notificationOwnerControlKey}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.resetPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"kvnr":"${notificationOwnerId}","email":"${notificationOwnerId}@${oidcTestData.emailDomain}"}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      !{file('src/test/resources/testdata/push/pusher_lifecycle_response.schema.json')}
      """
    Und TGR speichere Wert des Knotens "$.body.pushers.[?(@.pushkey == '${notificationOwnerControlKey}')]" der aktuellen Antwort in der Variable "notificationOwnerControlPusher"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type":"object", "required":["pushers"],
        "properties":{"pushers":{
          "type":"array", "minItems":2, "maxItems":2,
          "allOf":[
            {"contains":{"const":${notificationOriginalPusher}}},
            {"contains":{"const":${notificationOwnerControlPusher}}}
          ]
        }}
      }
      """

    # Vor seiner Registrierung darf Nutzer 2 den Pusher von Nutzer 1 nicht lesen.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "NOTIFICATION_SECOND_KVNR"
    Und die nutzergebundene Bereinigung für Pusher "${notificationPusherPushkey}", Anwendung "${testdata.notificationService.pusher.appId}" und Nutzer "${NOTIFICATION_SECOND_KVNR}"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${NOTIFICATION_SECOND_KVNR}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["pushers"],"properties":{"pushers":{"type":"array","maxItems":0}}}
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "emptyPushersResponse"
    Und validiere "${emptyPushersResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.body.sub}" stimmt nicht überein mit "${notificationOwnerId}"

    # Auch Nutzer 2 besitzt vor dem Transfer einen unabhängigen Kontroll-Pusher.
    Und erzeuge eindeutige Test-ID mit Präfix "zeta-lifecycle-other-control" und speichere in Variable "notificationOtherControlKey"
    Und die nutzergebundene Bereinigung für Pusher "${notificationOtherControlKey}", Anwendung "${testdata.notificationService.pusher.appId}" und Nutzer "${NOTIFICATION_SECOND_KVNR}"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${notificationOtherControlKey}"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${NOTIFICATION_SECOND_KVNR}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${NOTIFICATION_SECOND_KVNR}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      !{file('src/test/resources/testdata/push/pusher_lifecycle_response.schema.json')}
      """
    Und TGR speichere Wert des Knotens "$.body.pushers.[?(@.pushkey == '${notificationOtherControlKey}')]" der aktuellen Antwort in der Variable "notificationOtherControlPusher"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type":"object", "required":["pushers"],
        "properties":{"pushers":{
          "type":"array", "minItems":1, "maxItems":1,
          "allOf":[
            {"contains":{"const":${notificationOtherControlPusher}}}
          ]
        }}
      }
      """

    # Beide Nutzer haben jetzt Geräte: Nutzer 1 darf Nutzer 2s Registrierung nicht sehen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.resetPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"kvnr":"${notificationOwnerId}","email":"${notificationOwnerId}@${oidcTestData.emailDomain}"}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type":"object", "required":["pushers"],
        "properties":{"pushers":{
          "type":"array", "minItems":2, "maxItems":2,
          "allOf":[
            {"contains":{"const":${notificationOriginalPusher}}},
            {"contains":{"const":${notificationOwnerControlPusher}}}
          ]
        }}
      }
      """

    # Zurück zu Nutzer 2, ohne die Kontroll-Pusher neu zu schreiben.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.resetPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"kvnr":"${NOTIFICATION_SECOND_KVNR}","email":"${NOTIFICATION_SECOND_KVNR}@${oidcTestData.emailDomain}"}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${notificationPusherPushkey}"

    # Ohne append muss die erfolgreiche Registrierung die bisherige Nutzerzuordnung ersetzen.
    Und TGR setze lokale Variable "testdata.notificationService.pusher.appDisplayName" auf "ZETA Testsuite Nutzer 2"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.deviceDisplayName" auf "Gerät von Nutzer 2"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.profileTag" auf "nutzerwechsel"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${NOTIFICATION_SECOND_KVNR}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.scope}" überein mit "(^|.* )notification[.]pusher[.]write( .*|$)"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationPusherPushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.append"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.encryption.iss"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.encryption.key_identifier"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request im Knoten "$.body.encryption.iss" stimmt nicht überein mit "\Q${notificationOwnerEncryptionIss}\E"
    Und TGR prüfe aktueller Request im Knoten "$.body.encryption.key_identifier" stimmt nicht überein mit "\Q${notificationOwnerEncryptionKeyIdentifier}\E"

    # Ein erfolgreicher Status genügt nicht: Nutzer 2 muss genau seine neuen Werte lesen können.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${NOTIFICATION_SECOND_KVNR}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      !{file('src/test/resources/testdata/push/pusher_lifecycle_response.schema.json')}
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationOtherPushersResponse"
    Und validiere "${notificationOtherPushersResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type":"object", "required":["pushers"],
        "properties":{"pushers":{
          "type":"array", "minItems":2, "maxItems":2,
          "allOf":[
            {"contains":{"const":${notificationOtherControlPusher}}}
          ]
        }}
      }
      """

    # Nutzer 1 erneut authentisieren, ohne seinen Pusher erneut anzulegen oder zu löschen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/oidc/user_kvnr_email.json')}
      """
    # Nur der übertragene Pusher verschwindet; Nutzer 1s Kontroll-Pusher bleibt unverändert.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type":"object", "required":["pushers"],
        "properties":{"pushers":{
          "type":"array", "minItems":1, "maxItems":1,
          "allOf":[
            {"contains":{"const":${notificationOwnerControlPusher}}}
          ]
        }}
      }
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationOwnerControlResponse"
    Und validiere "${notificationOwnerControlResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

  @A_29978
  @TA_A_29978_03
  @TA_A_29978_04
  @normal
  @reset_notification_pushers
  Szenario: Notification Service schützt Channel-Konfigurationen vor Fremdzugriffen
    # Ablaufübersicht:
    # 1. Referenzzustand des Eigentümers herstellen (F01–F25).
    # 2. Nutzer 2 registrieren und seinen Channel auf disabled setzen und zurücklesen (F26–F38).
    # 3. Fremdlesen versuchen und Datenherausgabe ausschließen (F39–F54).
    # 4. Fremde Deaktivierung versuchen (F55–F71).
    # 5. Bei weiterhin bestehendem Pusher von Nutzer 2 den Eigentümer-Channel zurücklesen (F77–F93).
    # 6. Pusher des Eigentümers entfernen (F94–F96).
    # 7. Als Nutzer 2 zurückkehren und dessen temporären Pusher entfernen (F96a–F96b, F72–F76).
    # 8. Beide Fremdzugriffe abschließend auf 403 prüfen (F97).
    # Die gespeicherten Fremdzugriffsstatus werden erst abschließend auf 403 geprüft,
    # damit auch bei einem falschen Status die Zustandsprüfung und Bereinigung ausgeführt werden.

    # Kapitel 1: Referenzzustand des Eigentümers herstellen.
    # F01: Einen kollisionsfreien Pusher für den Eigentumsnachweis erzeugen.
    Gegeben sei eine eindeutige Test-ID mit Präfix "notification-ownership" in Variable "notificationPusherPushkey"
    # F02: Die Testdaten für den folgenden Zugriff vorbereiten.
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${notificationPusherPushkey}"

    # F03: Manipulationen auf die Notification-Channel-Schnittstelle begrenzen.
    Und TGR setze lokale Variable "notificationChannelRequestCondition" auf "isRequest && request.path =~ '.*${paths.guard.notificationChannelsPathPrefix}?.*'"
    # F04: Den Pusher des Eigentümers als gemeinsames Zugriffsziel festhalten.
    Und TGR setze lokale Variable "notificationChannelTarget" auf "${paths.guard.notificationChannelsPathPrefix}${notificationPusherPushkey}"

    # Nutzer 1 erzeugt das Pusher-Objekt, das anschließend gezielt durch Nutzer 2 adressiert wird.
    # F05: Die nächste Operation von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F06: Die vorgesehene Client-Operation zur Beobachtung am Service auslösen.
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    # F07: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    # F08: Den Zugriff oder Rückgabewert dem gespeicherten Pusher zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationPusherPushkey}"
    # F09: Die authentisierte Identität für spätere Vergleiche festhalten.
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "notificationOwnerId"
    # F10: Den erfolgreichen Zugriff als Voraussetzung der fachlichen Prüfung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Eigentümer setzt den Channel einmalig auf den Referenzzustand enabled.
    # F11: Die nächste Operation von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F12: Die Testdaten für den folgenden Zugriff vorbereiten.
    Und TGR setze lokale Variable "testdata.notificationService.notificationChannel.status" auf "enabled"
    # F13: Den regulären SDK-Schreibaufruf mit gültigem Channel-Inhalt auslösen.
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/notification_channels.json')}
      """
    # F14: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${notificationChannelTarget}"
    # F15: Die beabsichtigte Schreibmethode am empfangenen Request nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    # F16: TA_A_29978_03: Die Operation der erwarteten Nutzeridentität zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    # F17: Den Änderungsversuch an der konkreten Channel-ID nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.[?(@.id == '${testdata.notificationService.notificationChannel.id}')].status" überein mit "enabled"
    # F18: Den erfolgreichen Zugriff als Voraussetzung der fachlichen Prüfung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Eigentümer liest den aktivierten Channel über den regulären SDK-Aufruf zurück.
    # F19: Die nächste Operation von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F20: Den Channel des lokal registrierten Eigentümer-Pushers lesen.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    # F21: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${notificationChannelTarget}"
    # F22: Den Lesezugriff am tatsächlich adressierten Pusher nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    # F23: TA_A_29978_03: Die Operation der erwarteten Nutzeridentität zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    # F24: Den erfolgreichen Zugriff als Voraussetzung der fachlichen Prüfung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F25: TA_A_29978_03: Den aktivierten Zustand des konkreten Channels nachweisen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == '${testdata.notificationService.notificationChannel.id}')].status" überein mit "enabled"

    # Kapitel 2: Nutzer 2 und seinen temporären Pusher vorbereiten.
    # F26: Eine unabhängige authentisierte Nutzeridentität herstellen.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "NOTIFICATION_SECOND_KVNR"

    # F27: Einen separaten Pusher für den zweiten Nutzer erzeugen.
    Gegeben sei eine eindeutige Test-ID mit Präfix "notification-ownership-other" in Variable "notificationOtherPusherPushkey"
    # F28: Die Registrierung ausschließlich auf den neuen Pusher beziehen.
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${notificationOtherPusherPushkey}"
    # F29: Die neue Registrierung isoliert aufzeichnen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F30: Den zweiten Nutzer für reguläre lokale Channel-Aufrufe vorbereiten.
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    # F31: Die Registrierung am Notification Service auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    # F32: Die vom Eigentümer verschiedene Nutzeridentität für die Fremdzugriffe nachweisen.
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.body.sub}" stimmt nicht überein mit "${notificationOwnerId}"
    # F33: Die authentisierte Identität für spätere Vergleiche festhalten.
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "notificationOtherUserId"
    # F34: Den eigenen Pusher des zweiten Nutzers nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationOtherPusherPushkey}"
    # F35: Die gültige Registrierung als Schreibvoraussetzung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Nutzer 2 speichert für denselben Channel einen vom Eigentümer abweichenden Zustand.
    # F35a: Den eigenen Channel-Endpunkt des zweiten Nutzers festhalten.
    Und TGR setze lokale Variable "notificationOtherChannelTarget" auf "${paths.guard.notificationChannelsPathPrefix}${notificationOtherPusherPushkey}"
    # F35b: Den Schreibzugriff von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F35c: Den abweichenden Zustand für Nutzer 2 vorbereiten.
    Und TGR setze lokale Variable "testdata.notificationService.notificationChannel.status" auf "disabled"
    # F35d: Den Channel des lokal registrierten zweiten Pushers deaktivieren.
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/notification_channels.json')}
      """
    # F35e: Den Schreibzugriff auf den Pusher von Nutzer 2 auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${notificationOtherChannelTarget}"
    # F35f: Die Schreibmethode am adressierten Pusher nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    # F35g: TA_A_29978_03: Den Schreibzugriff der zweiten Nutzeridentität zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOtherUserId}"
    # F35h: Den abweichenden Schreibwert für denselben Channel am Service nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.[?(@.id == '${testdata.notificationService.notificationChannel.id}')].status" überein mit "disabled"
    # F35i: Die erfolgreiche Konfiguration des zweiten Pushers bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Nutzer 2 liest seine gespeicherte Konfiguration vor den Fremdzugriffen zurück.
    # F35j: Den Lesezugriff von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F35k: Die eigene Channel-Konfiguration von Nutzer 2 abrufen.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    # F35l: Den Lesezugriff auf denselben Pusher auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${notificationOtherChannelTarget}"
    # F35m: Die Lesemethode am adressierten Pusher nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    # F35n: TA_A_29978_03: Den Lesezugriff der zweiten Nutzeridentität zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOtherUserId}"
    # F35o: Den erfolgreichen Lesezugriff als Prüfvorbedingung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F35p: TA_A_29978_03: Den gespeicherten Zustand disabled für Nutzer 2 nachweisen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == '${testdata.notificationService.notificationChannel.id}')].status" überein mit "disabled"

    # F36: Den aktuellen Client-Schlüssel aus dem Testdriver verfügbar machen.
    Und TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    # F37: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    # F38: Den aktuellen Client-Schlüssel für gültige DPoP-Proofs übernehmen.
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "notificationDpopKey"

    # Kapitel 3: Fremdlesen versuchen und Datenherausgabe ausschließen.
    # F39: Die nächste Operation von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F40: Den gespeicherten Pusher unabhängig vom lokalen SDK-Zustand adressieren.
    Dann Setze im TigerProxy für die Nachricht "${notificationChannelRequestCondition}" die Manipulation auf Feld "$.path" und Wert "${notificationChannelTarget}"
    # F41: Den DPoP-Proof an den tatsächlich adressierten Pusher binden.
    Und Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.htu" auf Wert "https://${zeta_base_url}${notificationChannelTarget}" mit privatem Schlüssel "${notificationDpopKey}" für Pfad ".*${paths.guard.notificationChannelsPathPrefix}?.*" und 1 Ausführungen
    # F42: Einen frisch authentisierten Notification-Aufruf für das konkrete Zugriffsziel erzeugen.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channels}"
    # F43: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${notificationChannelTarget}"
    # F44: Den Lesezugriff am tatsächlich adressierten Pusher nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    # F45: Die signierte Methode demselben Lesezugriff zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htm}" überein mit "GET"
    # F46: Die URL-Bindung des Proofs am empfangenen Request nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "https://${zeta_base_url}${notificationChannelTarget}"
    # F47: Den empfangenen Proof für zusammengehörige Signatur- und Schlüsselprüfungen speichern.
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "notificationProof"
    # F48: Eine kryptografisch ungültige Anfrage als Ablehnungsursache ausschließen.
    Und verifiziere ES256 Signatur von DPoP JWT "${notificationProof}"
    # F49: Den öffentlichen Proof-Schlüssel für den Token-Bindungsnachweis bestimmen.
    Und berechne JKT aus JWT Header JWK "${notificationProof}" und speichere in Variable "notificationProofJkt"
    # F50: Die Bindung des verwendeten Tokens an den signierenden Client prüfen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" überein mit "${notificationProofJkt}"
    # F51: Die erforderliche Channel-Berechtigung als Vorbedingung prüfen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.scope}" überein mit "(^|.* )notification.channel.read( .*|$)"
    # F52: TA_A_29978_03: Die Operation der erwarteten Nutzeridentität zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOtherUserId}"
    # F53: Die Antwort des Fremdlesezugriffs für die abschließende 403-Prüfung festhalten.
    Und TGR speichere Wert des Knotens "$.responseCode" der aktuellen Antwort in der Variable "notificationForeignReadStatus"
    # F54: TA_A_29978_04: Die Herausgabe der fremden Channel-Konfiguration ausschließen.
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.channels"

    # Kapitel 4: Fremde Deaktivierung versuchen.
    # F55: Die nächste Operation von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F56: Die Testdaten für den folgenden Zugriff vorbereiten.
    Und TGR setze lokale Variable "testdata.notificationService.notificationChannel.status" auf "disabled"
    # F57: Den gespeicherten Pusher unabhängig vom lokalen SDK-Zustand adressieren.
    Dann Setze im TigerProxy für die Nachricht "${notificationChannelRequestCondition}" die Manipulation auf Feld "$.path" und Wert "${notificationChannelTarget}"
    # F58: Den DPoP-Proof an den tatsächlich adressierten Pusher binden.
    Und Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.htu" auf Wert "https://${zeta_base_url}${notificationChannelTarget}" mit privatem Schlüssel "${notificationDpopKey}" für Pfad ".*${paths.guard.notificationChannelsPathPrefix}?.*" und 1 Ausführungen
    # F59: Den regulären SDK-Schreibaufruf mit gültigem Channel-Inhalt auslösen.
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/notification_channels.json')}
      """
    # F60: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${notificationChannelTarget}"
    # F61: Die URL-Bindung des Proofs am empfangenen Request nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "https://${zeta_base_url}${notificationChannelTarget}"
    # F62: Den empfangenen Proof für zusammengehörige Signatur- und Schlüsselprüfungen speichern.
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "notificationProof"
    # F63: Eine kryptografisch ungültige Anfrage als Ablehnungsursache ausschließen.
    Und verifiziere ES256 Signatur von DPoP JWT "${notificationProof}"
    # F64: Den öffentlichen Proof-Schlüssel für den Token-Bindungsnachweis bestimmen.
    Und berechne JKT aus JWT Header JWK "${notificationProof}" und speichere in Variable "notificationProofJkt"
    # F65: Die Bindung des verwendeten Tokens an den signierenden Client prüfen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" überein mit "${notificationProofJkt}"
    # F66: Die erforderliche Channel-Berechtigung als Vorbedingung prüfen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.scope}" überein mit "(^|.* )notification.channel.write( .*|$)"
    # F67: Die beabsichtigte Schreibmethode am empfangenen Request nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    # F68: Die beabsichtigte Schreibmethode am empfangenen Request nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htm}" überein mit "POST"
    # F69: TA_A_29978_03: Die Operation der erwarteten Nutzeridentität zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOtherUserId}"
    # F70: Den Änderungsversuch an der konkreten Channel-ID nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.[?(@.id == '${testdata.notificationService.notificationChannel.id}')].status" überein mit "disabled"
    # F71: Die Antwort des Fremdschreibzugriffs für die abschließende 403-Prüfung festhalten.
    Und TGR speichere Wert des Knotens "$.responseCode" der aktuellen Antwort in der Variable "notificationForeignWriteStatus"

    # Kapitel 5: Temporären Pusher von Nutzer 2 entfernen.
    # Der Pusher des Eigentümers und dessen Channel bleiben unangetastet.
    # F72: Die Bereinigung des eigenen Pushers isoliert aufzeichnen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F73: Nur den neu erzeugten Pusher des zweiten Nutzers löschen.
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${notificationOtherPusherPushkey}&appId=${testdata.notificationService.pusher.appId}"
    # F74: Die Bereinigung am Notification Service auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    # F75: Die Bereinigung auf den zusätzlichen Pusher begrenzen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationOtherPusherPushkey}"
    # F76: Die erfolgreiche Bereinigung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Kapitel 6: Als Eigentümer den unveränderten Channel nachweisen.
    # Nutzer 1 authentisiert sich erneut; ein lesender Aufruf stellt den frischen DPoP-Schlüssel bereit.
    # F77: Für die Rückkehr zum Eigentümer nur den Client-Sitzungszustand verwerfen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    # F78: Die ursprüngliche Eigentümeridentität ohne Pusher-Neuregistrierung wiederherstellen.
    Und TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/oidc/user_kvnr_email.json')}
      """
    # F79: Die nächste Operation von vorherigem Verkehr abgrenzen.
    Und TGR lösche aufgezeichnete Nachrichten
    # F80: Die Eigentümersitzung ohne Änderung des gespeicherten Pushers authentisieren.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"

    # F81: Den aktuellen Client-Schlüssel aus dem Testdriver verfügbar machen.
    Und TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    # F82: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    # F83: Den aktuellen Client-Schlüssel für gültige DPoP-Proofs übernehmen.
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "notificationDpopKey"

    # TA_A_29978_03 und TA_A_29978_04: Die Channel-Konfiguration bleibt ohne erneutes Schreiben erhalten.
    # F84: Die nächste Operation von vorherigem Verkehr abgrenzen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # Der SDK-Reset hat die bei der Registrierung gespeicherte lokale Pusher-Zuordnung gelöscht.
    # KVNR/E-Mail stellen nur die Eigentümeridentität wieder her, nicht den Pushkey für channels/local.
    # Deshalb wird der allgemeine Channel-Aufruf auf den bestehenden Pusher umgeleitet, ohne ihn neu zu registrieren.
    # Die geänderte Ziel-URL muss auch im DPoP-Proof als htu stehen und neu signiert werden; das Zugriffstoken bleibt unverändert.
    # F85: Den gespeicherten Pusher unabhängig vom lokalen SDK-Zustand adressieren.
    Dann Setze im TigerProxy für die Nachricht "${notificationChannelRequestCondition}" die Manipulation auf Feld "$.path" und Wert "${notificationChannelTarget}"
    # F86: Den DPoP-Proof an den tatsächlich adressierten Pusher binden.
    Und Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.htu" auf Wert "https://${zeta_base_url}${notificationChannelTarget}" mit privatem Schlüssel "${notificationDpopKey}" für Pfad ".*${paths.guard.notificationChannelsPathPrefix}?.*" und 1 Ausführungen
    # F87: Einen frisch authentisierten Notification-Aufruf für das konkrete Zugriffsziel erzeugen.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channels}"
    # F88: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${notificationChannelTarget}"
    # F89: Den Lesezugriff am tatsächlich adressierten Pusher nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    # F90: Die URL-Bindung des Proofs am empfangenen Request nachweisen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "https://${zeta_base_url}${notificationChannelTarget}"
    # F91: TA_A_29978_03: Die Operation der erwarteten Nutzeridentität zuordnen.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationOwnerId}"
    # F92: Den erfolgreichen Zugriff als Voraussetzung der fachlichen Prüfung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F93: TA_A_29978_03: Trotz disabled bei Nutzer 2 den weiterhin aktivierten Eigentümer-Channel nachweisen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == '${testdata.notificationService.notificationChannel.id}')].status" überein mit "enabled"

    # Kapitel 6: Pusher des Eigentümers entfernen.
    # F94: Den Zugriff oder Rückgabewert dem gespeicherten Pusher zuordnen.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${notificationPusherPushkey}&appId=${testdata.notificationService.pusher.appId}"
    # F95: Die zusammengehörige Anfrage und Antwort für die folgenden Prüfungen auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    # F96: Den erfolgreichen Zugriff als Voraussetzung der fachlichen Prüfung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Kapitel 7: Als Nutzer 2 zurückkehren und dessen temporären Pusher entfernen.
    # F96a: Den Client-Sitzungszustand für die Rückkehr zu Nutzer 2 verwerfen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    # F96b: Die gespeicherte zweite Identität ohne Pusher-Neuregistrierung wiederherstellen.
    Und TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "kvnr": "${NOTIFICATION_SECOND_KVNR}",
        "email": "${NOTIFICATION_SECOND_KVNR}@${oidcTestData.emailDomain}"
      }
      """

    # F72: Die Bereinigung des eigenen Pushers isoliert aufzeichnen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    # F73: Nur den neu erzeugten Pusher des zweiten Nutzers löschen.
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${notificationOtherPusherPushkey}&appId=${testdata.notificationService.pusher.appId}"
    # F74: Die Bereinigung am Notification Service auswählen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    # F75: Die Bereinigung auf den zusätzlichen Pusher begrenzen.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationOtherPusherPushkey}"
    # F76: Die erfolgreiche Bereinigung bestätigen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Kapitel 8: Beide Fremdzugriffe abschließend auf 403 prüfen.
    # Die Prüfung erfolgt erst jetzt, damit falsche Statuscodes Zustandsprüfung und Bereinigung nicht verhindern.
    # F97: TA_A_29978_04: Für Fremdlesen und Fremdschreiben jeweils exakt 403 nachweisen.
    Und TGR prüfe das "GET=${notificationForeignReadStatus};POST=${notificationForeignWriteStatus}" mit "GET=403;POST=403" überein stimmt

  @A_25735-01
  @normal
  @MASVS-PRIVACY
  Szenariogrundriss: ZETA Client aktiviert, ändert und deaktiviert Push-Benachrichtigungen für <description>
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    # Beide bekannten Test-Pusher werden entfernt, damit ein Abbruch eines vorherigen Beispiels
    # keinen weiterhin aktiven Pusher für denselben Nutzer hinterlässt.
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-ta25735-activity&appId=de.gematik.zeta.ta25735.activity"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-ta25735-registration&appId=de.gematik.zeta.ta25735.registration"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Ein eindeutig adressierter Test-Pusher bindet die spätere Zustellung an dasselbe Testgerät.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "pushkey": "zeta-testsuite-ta25735-<proofId>",
        "appId": "de.gematik.zeta.ta25735.<proofId>",
        "appDisplayName": "TA A_25735-01 <proofId>",
        "deviceDisplayName": "ZETA Testgerät <proofId>",
        "profileTag": "ta25735-<proofId>",
        "lang": "de-DE",
        "data": {
          "url": "${testdata.notificationService.pusher.url}",
          "format": "event_id_only"
        }
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "de.gematik.zeta.ta25735.<proofId>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "notificationUserId"

    # Aktivieren: Die Einstellung wird an den Notification Service übertragen und zurückgelesen.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "enabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "enabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "enabled"

    # Ändern: Der Client aktualisiert die bereits gespeicherte Channel-Konfiguration für den Pusher von enabled auf disabled und liest den neuen Zustand zurück.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "disabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "disabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "disabled"

    # Für den getrennten Deaktivierungsnachweis stellt der Client zunächst wieder einen aktiven Zustand her.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "enabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "enabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "enabled"

    # Ein korreliertes Ereignis der vom TestAspect geforderten Kategorie wird jetzt zugestellt.
    Und erzeuge eindeutige Test-ID mit Präfix "TA25735-<proofId>-ENABLED" und speichere in Variable "notificationEnabledCorrelationId"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "user_id": "${notificationUserId}",
        "event_type": "<eventType>",
        "correlation_id": "${notificationEnabledCorrelationId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notificationTestEventsPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_id" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.event_type" überein mit "<eventType>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.correlation_id" überein mit "${notificationEnabledCorrelationId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.notification_id"

    # Deaktivieren: Der Nutzer deaktiviert die zuvor wieder aktivierte Einstellung endgültig.
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "channels": [
          {
            "id": "zeta",
            "status": "disabled"
          }
        ]
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-ta25735-<proofId>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.id" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "disabled"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == 'zeta')].status" überein mit "disabled"

    # Dasselbe Ereignis wird nach der Deaktivierung vom Notification Service nicht mehr angenommen.
    Und erzeuge eindeutige Test-ID mit Präfix "TA25735-<proofId>-DISABLED" und speichere in Variable "notificationDisabledCorrelationId"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "user_id": "${notificationUserId}",
        "event_type": "<eventType>",
        "correlation_id": "${notificationDisabledCorrelationId}"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notificationTestEventsPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.event_type" überein mit "<eventType>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.correlation_id" überein mit "${notificationDisabledCorrelationId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "no_active_channel"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channel" überein mit "zeta"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-ta25735-<proofId>&appId=de.gematik.zeta.ta25735.<proofId>"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    @TA_A_25735-01_01
    Beispiele: Aktivitäten über registrierte Clients
      | description                         | proofId  | eventType                  |
      | Aktivitäten über registrierte Clients | activity | registered_client_activity |

    @TA_A_25735-01_02
    Beispiele: Neuregistrierungen für diesen Nutzer
      | description                          | proofId     | eventType               |
      | Neuregistrierungen für diesen Nutzer | registration | new_client_registration |

  @A_29976
  Szenariogrundriss: Notification Service weist ein Access Token nach fehlgeschlagener <Prüfung> zurück
    # F02: Ruft den aktuellen JWKS des ausstellenden Authorization Servers ab.
    Gegeben sei TGR sende eine leere "GET" Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    # F03: Korreliert die Antwort mit dem soeben ausgeführten JWKS-Aufruf.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    # F04: Stellt sicher, dass ein verwendbarer Live-JWKS vorliegt.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F05: Speichert den Live-JWKS für die Signaturprüfung des Access Tokens.
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "KEY_STORE"

    # F10: Beschafft über den OIDC-Ressourcenaufruf das Token für A_29976 und die isolierten TA-Prüfungen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    # F13: Wählt die zugehörige Anfrage am geschützten Notification-Service-Endpunkt aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    # F14: Belegt, dass der Notification Service das unmodifizierte Ausgangstoken akzeptiert.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F18: Speichert den Authorization-Header genau dieser Ausgangsanfrage.
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "authorizationHeader"
    # F19: Extrahiert das vom Notification Service empfangene Ausgangstoken.
    Und extrahiere DPoP Access Token aus Authorization Header "${authorizationHeader}" und speichere in Variable "accessToken"

    # Gültige Eingangsdaten schließen andere Ablehnungsgründe für die jeweilige TA-Prüfung aus.
    # F15: Belegt den erwarteten Authorization Server als Aussteller des Ausgangstokens.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.iss" überein mit "${paths.guard.issuerUrl}"
    # F16: Belegt die dienstspezifische Audience des Ausgangstokens.
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${paths.guard.notificationServiceUrl}",["${paths.guard.notificationServiceUrl}"]]}}}
      """
    # F17: Belegt den operationsbezogenen Lese-Scope als skalaren, leerzeichengetrennten Claim.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.scope" überein mit "${regex.scope.notificationPusherRead}"
    # F20: Belegt die gültige Authorization-Server-Signatur des Ausgangstokens.
    Und verifiziere die ES256 Signatur des JWT "${accessToken}" mit KeyStore "${KEY_STORE}"
    # F21: Speichert `exp` aus derselben Anfrage für die zeitliche Vorbedingung.
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body.exp" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_EXP"
    # F22: Belegt, dass das Ausgangstoken noch nicht abgelaufen ist.
    Und validiere, dass der Zeitstempel "${ACCESS_TOKEN_EXP}" in der Zukunft liegt
    # F23: Speichert `iat` aus derselben Anfrage für die zeitliche Vorbedingung.
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body.iat" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_IAT"
    # F24: Belegt, dass das Ausgangstoken bereits ausgestellt wurde.
    Und validiere, dass der Zeitstempel "${ACCESS_TOKEN_IAT}" in der Vergangenheit liegt

    # Die Schlüsselvorbereitung verwendet das beobachtete Szenario-Token und erhält die OIDC-Identität.
    # F06: Stellt für korrekt zu signierende Varianten den zum Ausgangstoken passenden Schlüssel bereit.
    Dann stelle für JWT-Variante <Signaturvariante> den Signaturschlüssel anhand des Access Tokens "${accessToken}" bereit
    # F07: Wählt für den jeweiligen TA den vorgesehenen Signaturschlüssel aus.
    Und TGR setze lokale Variable "ecKeyFilePath" auf "<AccessTokenKeyFile>"
    # F08: Lädt für den jeweiligen TA den gültigen oder gezielt fremden Signaturschlüssel.
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"

    # F25: Liest den aktiven DPoP-Schlüssel des OIDC-Testdrivers aus.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    # F26: Korreliert die Storage-Antwort mit dem soeben ausgeführten Aufruf.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    # F27: Speichert den DPoP-Schlüssel für die Aktualisierung von `ath` und Proof-Signatur.
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"

    # Genau eine Eigenschaft des Ausgangstokens wird für den ausgewählten Prüfzweig verändert.
    # F28: Manipuliert das Access Token und aktualisiert den zugehörigen DPoP-Proof konsistent.
    Dann Setze im TigerProxy für Access Token das Feld "body.<JwtField>" auf Wert "<NeuerWert>" mit Typ "<Werttyp>" mit Access Token Key "${signingKey}" und DPoP Key "${dpopKey}" für Pfad ".*${paths.guard.notificationPushersPath}$" und 1 Ausführungen
    # F29: Trennt die Evidenz des manipulierten Aufrufs von der Ausgangsanfrage.
    Und TGR lösche aufgezeichnete Nachrichten
    # F30: Sendet den manipulierten Aufruf an den Notification Service.
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"

    # Die folgenden Prüfungen binden Manipulation, Signaturzustand, DPoP und Ablehnung an dieselbe Anfrage.
    # F31: Wählt genau die am Ziel empfangene Anfrage mit dem manipulierten Wert aus.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationPushersPath}" und Knoten "${headers.authorization.dpopToken.root}.body.<JwtField>" der mit "\Q<NeuerWert>\E" übereinstimmt
    # F31a: Schließt für den jeweiligen A_29976-Testaspekt einen falschen JSON-Typ des manipulierten Feldes als Ablehnungsgrund aus.
    Und TGR prüfe aktuelle Anfrage im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "properties": {
          "<JwtField>": {"type": "<Werttyp>"}
        }
      }
      """
    # F32: Speichert den Authorization-Header der ausgewählten manipulierten Anfrage.
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "authorizationHeader"
    # F33: Extrahiert genau das vom Notification Service empfangene manipulierte Access Token.
    Und extrahiere DPoP Access Token aus Authorization Header "${authorizationHeader}" und speichere in Variable "manipulatedAccessToken"
    # F34: Belegt den für den Prüfzweig erwarteten Signaturzustand gegen den Live-JWKS.
    Und prüfe die JWT-Variante <Signaturvariante> in "${manipulatedAccessToken}" hat lokal mit KeyStore "${KEY_STORE}" die erwartete Signaturintegritaet
    # F35: Speichert den DPoP-Proof aus derselben manipulierten Anfrage.
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    # F36: Berechnet den erwarteten Token-Hash aus genau dem empfangenen Access Token.
    Und berechne SHA256 Hash von "${manipulatedAccessToken}" und speichere in Variable "expectedDpopAth"
    # F37: Belegt die `ath`-Bindung des DPoP-Proofs an das manipulierte Token.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${expectedDpopAth}"
    # F38: Schließt eine ungültige DPoP-Signatur als Ablehnungsgrund aus.
    Und verifiziere ES256 Signatur von DPoP JWT "${manipulatedDpopJwt}"
    # F39: Belegt die von A_29976 geforderte Ablehnung direkt am Notification Service.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401|403"

    @TA_A_29976_01
    Beispiele: ungültige Access-Token-Signatur
      | Prüfung  | JwtField | NeuerWert                | AccessTokenKeyFile      | Signaturvariante  | Werttyp |
      | Signatur | jti      | a29976-invalid-signature | ${paths.popp.ecKeyFile} | invalid_signature | string  |

    @TA_A_29976_02
    Beispiele: abgelaufenes Access Token
      | Prüfung | JwtField | NeuerWert  | AccessTokenKeyFile       | Signaturvariante   | Werttyp |
      | exp     | exp      | 1701432000 | ${paths.guard.ecKeyFile} | resign_correct_key | number  |

    @TA_A_29976_07
    Beispiele: zukünftiger Ausstellungszeitpunkt
      | Prüfung | JwtField | NeuerWert  | AccessTokenKeyFile       | Signaturvariante   | Werttyp |
      | iat     | iat      | 4102444800 | ${paths.guard.ecKeyFile} | resign_correct_key | number  |

    @TA_A_29976_03
    Beispiele: nicht vertrauenswürdiger Aussteller
      | Prüfung | JwtField | NeuerWert                          | AccessTokenKeyFile       | Signaturvariante   | Werttyp |
      | iss     | iss      | https://other.example.invalid/auth | ${paths.guard.ecKeyFile} | resign_correct_key | string  |

    @TA_A_29976_04
    Beispiele: unpassende Zielgruppe
      | Prüfung | JwtField | NeuerWert                                          | AccessTokenKeyFile       | Signaturvariante   | Werttyp |
      | aud     | aud      | https://other.example.invalid/notification-service | ${paths.guard.ecKeyFile} | resign_correct_key | string  |

    @TA_A_29976_05
    Beispiele: fehlender und unzureichender operationsbezogener Scope
      | Prüfung | JwtField   | NeuerWert                 | AccessTokenKeyFile       | Signaturvariante   | Werttyp |
      | Scope   | scope      | notification.pusher.write | ${paths.guard.ecKeyFile} | resign_correct_key | string  |

    @TA_A_29976_06
    Beispiele: zukünftiger Gültigkeitsbeginn
      | Prüfung | JwtField | NeuerWert  | AccessTokenKeyFile       | Signaturvariante   | Werttyp |
      | nbf     | nbf      | 4102444800 | ${paths.guard.ecKeyFile} | resign_correct_key | number  |

  @A_29985
  @TA_A_29985_02
  @normal
  Szenario: Notification Service lehnt einen dem Fachdienst nicht zugeordneten Channel mit 403 ab
    Gegeben sei ermittle den aktuellen Ready-Pod für das Deployment "testfachdienst" und speichere in der Variable "notificationFachdienstPod"
    Und ermittle die konfigurierte Umgebungsvariable "NOTIFICATION_SERVICE_RS_BASE_URL" des Containers "testfachdienst" im Pod "${notificationFachdienstPod}" und speichere in der Variable "notificationRsUpstream"
    Und TGR prüfe Variable "notificationRsUpstream" stimmt überein mit "\Q${paths.fachdienst.routingUpstream}/notification-service-rs\E"

    Gegeben sei ermittle den aktuellen Ready-Pod für das Deployment "notification-service-rs" und speichere in der Variable "notificationRsPod"
    Und ermittle die konfigurierte Umgebungsvariable "NOTIFICATION_CHANNELS_ALLOWED" des Containers "notification-service" im Pod "${notificationRsPod}" und speichere in der Variable "notificationAllowedChannels"
    Und TGR prüfe Variable "notificationAllowedChannels" stimmt überein mit "^(?:[^,]*,)*\s*\Qzeta\E\s*(?:,[^,]*)*$"
    Und TGR prüfe Variable "notificationAllowedChannels" stimmt überein mit "^(?!(?:[^,]*,)*\s*\Q${testdata.notificationService.notAllowedChannel}\E\s*(?:,|$))[^\r\n]+$"

    Gegeben sei erzeuge eindeutige Test-ID mit Präfix "notification-pusher" und speichere in Variable "notificationPushkey"
    Und TGR setze lokale Variable "testdata.notificationService.pusher.pushkey" auf "${notificationPushkey}"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationPushkey}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "notificationUserId"

    Wenn erzeuge eindeutige Test-ID mit Präfix "notification-allowed-channel" und speichere in Variable "notificationCorrelationId"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${notificationUserId}","event_type":"registered_client_activity","correlation_id":"${notificationCorrelationId}"}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die erste Anfrage mit Pfad "^${paths.guard.notificationServiceRsPath}$" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "\Q${notificationUserId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.id_type" überein mit "kvnr"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channel" überein mit "zeta"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.payload.event_type" überein mit "registered_client_activity"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "notificationReferenceRequest"
    Und TGR erstellt ein neues Rbel-Objekt "notificationRequest" mit Inhalt "${notificationReferenceRequest}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"

    Wenn TGR setzt Rbel-Objekt "notificationRequest" an Stelle "$.channel" auf Wert "${testdata.notificationService.notAllowedChannel}"
    Und erzeuge eindeutige Test-ID mit Präfix "notification-not-allowed-channel" und speichere in Variable "notificationCorrelationId"
    Und TGR setzt Rbel-Objekt "notificationRequest" an Stelle "$.payload.correlation_id" auf Wert "${notificationCorrelationId}"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und TGR setze lokale Variable "notificationChannelCondition" auf "isRequest && request.path == '/notification-service-rs/notifications' && request.bodyAsString =~ '(?s).*${notificationCorrelationId}.*'"
    Und Setze im TigerProxy für die Nachricht "${notificationChannelCondition}" die Manipulation auf Feld "$.body.channel" und Wert "${testdata.notificationService.notAllowedChannel}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${notificationUserId}","event_type":"registered_client_activity","correlation_id":"${notificationCorrelationId}"}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die erste Anfrage mit Pfad "^${paths.guard.notificationServiceRsPath}$" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage im Knoten "$.body" stimmt als "JSON" überein mit:
      """
      !{rbelObject:serialize('notificationRequest')}
      """
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${notificationPushkey}&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${notificationPushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_29975
  @normal
  Szenariogrundriss: Notification Service lehnt <Leseoperation> ohne Zugriffstoken ab

    # Referenz: Dieselbe Leseoperation ist mit Zugriffstoken erreichbar.
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<SdkUrl>"
    Dann TGR finde die letzte Anfrage mit dem Pfad "<SdkPfad>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "<GuardPfad>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) [^ ]+"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Die Header-Entfernung erfasst den initialen Versuch und den einmaligen Retry nach HTTP 401.
    Wenn Setze im TigerProxy für die Nachricht "isRequest && request.path == '<GuardPfad>'" die Regex-Manipulation auf Feld "$.header" mit Regex "(?im)^Authorization:[\t ]*[^\r\n]+(?:\r?\n|$)" und Wert "" und 2 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<SdkUrl>"
    Dann TGR finde die letzte Anfrage mit dem Pfad "<SdkPfad>"
    Dann TGR finde die erste Anfrage mit Pfad "<GuardPfad>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    # Der letzte Versuch darf die zuvor nachgewiesene Ablehnung nicht umgehen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "<GuardPfad>"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Dann Alle Manipulationen im TigerProxy werden gestoppt

    @TA_A_29975_02
    Beispiele: Pusher-Abfrage
      | Leseoperation | SdkUrl | SdkPfad | GuardPfad |
      | das Lesen der Pusher | ${paths.client.notifications.pushers} | ${paths.client.notifications.pushersPath} | ${paths.guard.notificationPushersPath} |

    @TA_A_29975_04
    Beispiele: Verfügbare Channels
      | Leseoperation | SdkUrl | SdkPfad | GuardPfad |
      | das Lesen der verfügbaren Channels | ${paths.client.notifications.channels} | ${paths.client.notifications.channelsPath} | ${paths.guard.notificationChannelsPath} |

  @A_29975
  @TA_A_29975_03
  @normal
  Szenario: Notification Service lehnt die Pusher-Registrierung mit Schlüsselmaterial ohne Zugriffstoken ab

    # Referenz: Ein neuer Pusher mit SDK-generiertem Schlüsselmaterial wird zunächst akzeptiert.
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.iss" überein mit "[0-9a-f]{64}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.key_identifier" überein mit "[0-9a-f-]{36}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) [^ ]+"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Die Referenzregistrierung wird entfernt, damit das SDK erneut eine Registrierung sendet.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["pushkey", "app_id"],
              "properties": {
                "pushkey": {"type": "string"},
                "app_id": {"type": "string"}
              }
            },
            "contains": {
              "type": "object",
              "required": [
                "pushkey",
                "app_id"
              ],
              "properties": {
                "pushkey": {
                  "const": "zeta-testsuite-notification-auth"
                },
                "app_id": {
                  "const": "${testdata.notificationService.pusher.appId}"
                }
              }
            },
            "minContains": 0,
            "maxContains": 0
          }
        }
      }
      """

    # Die Header-Entfernung erfasst den initialen Versuch und den einmaligen Retry nach HTTP 401.
    Wenn Setze im TigerProxy für die Nachricht "isRequest && request.path == '${paths.guard.notificationPushersSetPath}'" die Regex-Manipulation auf Feld "$.header" mit Regex "(?im)^Authorization:[\t ]*[^\r\n]+(?:\r?\n|$)" und Wert "" und 2 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.iss" überein mit "[0-9a-f]{64}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.encryption.key_identifier" überein mit "[0-9a-f-]{36}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    # Der letzte Versuch darf die zuvor nachgewiesene Ablehnung nicht umgehen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Dann Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["pushkey", "app_id"],
              "properties": {
                "pushkey": {"type": "string"},
                "app_id": {"type": "string"}
              }
            },
            "contains": {
              "type": "object",
              "required": [
                "pushkey",
                "app_id"
              ],
              "properties": {
                "pushkey": {
                  "const": "zeta-testsuite-notification-auth"
                },
                "app_id": {
                  "const": "${testdata.notificationService.pusher.appId}"
                }
              }
            },
            "minContains": 0,
            "maxContains": 0
          }
        }
      }
      """

    # Nur der eigene Test-Pusher wird entfernt.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_29975
  @TA_A_29975_04
  @normal
  Szenario: Notification Service lehnt das Lesen der Geräte-Channels ohne Zugriffstoken ab

    # Jeder Fall besitzt einen eigenen registrierten Pusher für Gerätebezug und Zustandskontrolle.
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Referenz: Das SDK liest die Channels des registrierten Testgeräts.
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) [^ ]+"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Die Header-Entfernung erfasst den initialen Versuch und den einmaligen Retry nach HTTP 401.
    Wenn Setze im TigerProxy für die Nachricht "isRequest && request.path == '${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth'" die Regex-Manipulation auf Feld "$.header" mit Regex "(?im)^Authorization:[\t ]*[^\r\n]+(?:\r?\n|$)" und Wert "" und 2 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    # Der letzte Versuch darf die zuvor nachgewiesene Ablehnung nicht umgehen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Dann Alle Manipulationen im TigerProxy werden gestoppt

    # Nur der eigene Test-Pusher wird entfernt.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_29975
  @TA_A_29975_03
  @normal
  Szenario: Notification Service lehnt das Löschen des Pushers ohne Zugriffstoken ab

    # Jeder Fall besitzt einen eigenen registrierten Pusher für Gerätebezug und Zustandskontrolle.
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Referenz: Die Deregistrierung des vorhandenen Pushers wird akzeptiert.
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) [^ ]+"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Pusher wird für den abgelehnten Löschversuch wiederhergestellt.
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["pushkey", "app_id"],
              "properties": {
                "pushkey": {"type": "string"},
                "app_id": {"type": "string"}
              }
            },
            "contains": {
              "type": "object",
              "required": [
                "pushkey",
                "app_id"
              ],
              "properties": {
                "pushkey": {
                  "const": "zeta-testsuite-notification-auth"
                },
                "app_id": {
                  "const": "${testdata.notificationService.pusher.appId}"
                }
              }
            },
            "minContains": 1,
            "maxContains": 1
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.pushers.[?(@.pushkey == 'zeta-testsuite-notification-auth' && @.app_id == '${testdata.notificationService.pusher.appId}')]" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type": "object"}
      """
    Und TGR speichere Wert des Knotens "$.body.pushers.[?(@.pushkey == 'zeta-testsuite-notification-auth' && @.app_id == '${testdata.notificationService.pusher.appId}')]" der aktuellen Antwort in der Variable "notificationStateBefore"

    # Die Header-Entfernung erfasst den initialen Versuch und den einmaligen Retry nach HTTP 401.
    Wenn Setze im TigerProxy für die Nachricht "isRequest && request.path == '${paths.guard.notificationPushersSetPath}'" die Regex-Manipulation auf Feld "$.header" mit Regex "(?im)^Authorization:[\t ]*[^\r\n]+(?:\r?\n|$)" und Wert "" und 2 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "${testdata.notificationService.pusher.appId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    # Der letzte Versuch darf die zuvor nachgewiesene Ablehnung nicht umgehen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Dann Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["pushkey", "app_id"],
              "properties": {
                "pushkey": {"type": "string"},
                "app_id": {"type": "string"}
              }
            },
            "contains": {
              "type": "object",
              "required": [
                "pushkey",
                "app_id"
              ],
              "properties": {
                "pushkey": {
                  "const": "zeta-testsuite-notification-auth"
                },
                "app_id": {
                  "const": "${testdata.notificationService.pusher.appId}"
                }
              }
            },
            "minContains": 1,
            "maxContains": 1
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.pushers.[?(@.pushkey == 'zeta-testsuite-notification-auth' && @.app_id == '${testdata.notificationService.pusher.appId}')]" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"const": ${notificationStateBefore}}
      """

    # Nur der eigene Test-Pusher wird entfernt.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_29975
  @TA_A_29975_03
  @normal
  Szenario: Notification Service lehnt das Aktualisieren des Pushers ohne Zugriffstoken ab

    # Jeder Fall besitzt einen eigenen registrierten Pusher für Gerätebezug und Zustandskontrolle.
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Referenz: Die Änderung wird mit gültigen Fachparametern akzeptiert.
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_geaendert.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_display_name" überein mit "Notification-Test geändert"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) [^ ]+"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Ausgangswert wird wiederhergestellt und vor dem Negativversuch zurückgelesen.
    Wenn TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "items": {"type":"object","required":["pushkey","app_id"],"properties":{"pushkey":{"type":"string"},"app_id":{"type":"string"}}},
            "contains": {"type":"object","required":["pushkey","app_id"],"properties":{"pushkey":{"const":"zeta-testsuite-notification-auth"},"app_id":{"const":"${testdata.notificationService.pusher.appId}"}}},
            "minContains": 1,
            "maxContains": 1
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.pushers.[?(@.pushkey == 'zeta-testsuite-notification-auth' && @.app_id == '${testdata.notificationService.pusher.appId}')]" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type": "object"}
      """
    Und TGR speichere Wert des Knotens "$.body.pushers.[?(@.pushkey == 'zeta-testsuite-notification-auth' && @.app_id == '${testdata.notificationService.pusher.appId}')]" der aktuellen Antwort in der Variable "notificationStateBefore"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.pushers.[?(@.pushkey == 'zeta-testsuite-notification-auth' && @.app_id == '${testdata.notificationService.pusher.appId}')].app_display_name" überein mit "Notification-Test Ausgangszustand"

    # Die Header-Entfernung erfasst den initialen Versuch und den einmaligen Retry nach HTTP 401.
    Wenn Setze im TigerProxy für die Nachricht "isRequest && request.path == '${paths.guard.notificationPushersSetPath}'" die Regex-Manipulation auf Feld "$.header" mit Regex "(?im)^Authorization:[\t ]*[^\r\n]+(?:\r?\n|$)" und Wert "" und 2 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_geaendert.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_display_name" überein mit "Notification-Test geändert"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    # Der letzte Versuch darf die zuvor nachgewiesene Ablehnung nicht umgehen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Dann Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "items": {"type":"object","required":["pushkey","app_id"],"properties":{"pushkey":{"type":"string"},"app_id":{"type":"string"}}},
            "contains": {"type":"object","required":["pushkey","app_id"],"properties":{"pushkey":{"const":"zeta-testsuite-notification-auth"},"app_id":{"const":"${testdata.notificationService.pusher.appId}"}}},
            "minContains": 1,
            "maxContains": 1
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.pushers.[?(@.pushkey == 'zeta-testsuite-notification-auth' && @.app_id == '${testdata.notificationService.pusher.appId}')]" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"const": ${notificationStateBefore}}
      """

    # Nur der eigene Test-Pusher wird entfernt.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_29975
  @TA_A_29975_05
  @normal
  Szenario: Notification Service lehnt das Ändern der Geräte-Channels ohne Zugriffstoken ab

    # Jeder Fall besitzt einen eigenen registrierten Pusher für Gerätebezug und Zustandskontrolle.
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_pusher_ausgang.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Referenz: Die Änderung wird mit gültigen Fachparametern akzeptiert.
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_channels_aktiv.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "enabled"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) [^ ]+"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Ausgangswert wird wiederhergestellt und vor dem Negativversuch zurückgelesen.
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_channels_inaktiv.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["channels"],
        "properties": {
          "channels": {
            "type": "array",
            "items": {"type":"object","required":["id"],"properties":{"id":{"type":"string"}}},
            "contains": {"type":"object","required":["id"],"properties":{"id":{"const":"zeta"}}},
            "minContains": 1,
            "maxContains": 1
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.channels.[?(@.id == 'zeta')]" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type": "object"}
      """
    Und TGR speichere Wert des Knotens "$.body.channels.[?(@.id == 'zeta')]" der aktuellen Antwort in der Variable "notificationStateBefore"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == 'zeta')].status" überein mit "disabled"

    # Die Header-Entfernung erfasst den initialen Versuch und den einmaligen Retry nach HTTP 401.
    Wenn Setze im TigerProxy für die Nachricht "isRequest && request.path == '${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth'" die Regex-Manipulation auf Feld "$.header" mit Regex "(?im)^Authorization:[\t ]*[^\r\n]+(?:\r?\n|$)" und Wert "" und 2 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/notification_channels_aktiv.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.channelsLocalPath}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channels.0.status" überein mit "enabled"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    # Der letzte Versuch darf die zuvor nachgewiesene Ablehnung nicht umgehen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Dann Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.channelsLocal}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPath}/zeta-testsuite-notification-auth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${oidcTestData.kvnr}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["channels"],
        "properties": {
          "channels": {
            "type": "array",
            "items": {"type":"object","required":["id"],"properties":{"id":{"type":"string"}}},
            "contains": {"type":"object","required":["id"],"properties":{"id":{"const":"zeta"}}},
            "minContains": 1,
            "maxContains": 1
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.channels.[?(@.id == 'zeta')]" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"const": ${notificationStateBefore}}
      """

    # Nur der eigene Test-Pusher wird entfernt.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=zeta-testsuite-notification-auth&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_29972
  @A_29979
  @A_29989
  @TA_A_29972_01
  @TA_A_29972_02
  @TA_A_29979_01
  @TA_A_29979_02
  @TA_A_29989_01
  @normal
  @notification_pusher_lifecycle
  Szenario: Notification Service registriert und deregistriert einen Pusher mit dienstspezifischem Token
    Gegeben sei erzeuge eindeutige Test-ID mit Präfix "zeta-lifecycle" und speichere in Variable "testdata.notificationService.pusher.pushkey"
    Und die gezielte Bereinigung für Pusher "${testdata.notificationService.pusher.pushkey}", Anwendung "${testdata.notificationService.pusher.appId}" und Nutzer "${oidcTestData.kvnr}"
    Und TGR setze lokale Variable "schemaValidation.soft" auf "true"
    Und der History-Featurestatus des laufenden Notification Service

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.notificationProtectedResourcePath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationProtectedResourcePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.resource" überein mit "[a-zA-Z][a-zA-Z0-9+.-]*:[^#\s]+"
    Und TGR speichere Wert des Knotens "$.body.resource" der aktuellen Antwort in der Variable "notificationLifecycleResource"

    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"$ref": "classpath:schemas/v_2_0/schemas/opr-well-known.yaml"}
      """
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.resource" überein mit "\Q${paths.guard.notificationServiceUrl}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.authorization_servers" überein mit '\[\s*(?:"[^"\\]*"\s*,\s*)*"\Q${paths.guard.notificationAuthorizationServerUri}\E"(?:\s*,\s*"[^"\\]*")*\s*\]'
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.dpop_bound_access_tokens_required" überein mit "true"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.scopes_supported" überein mit '\[\s*(?:"[^"\\]*"\s*,\s*)*"\Qnotification.pusher.read\E"(?:\s*,\s*"[^"\\]*")*\s*\]'
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.scopes_supported" überein mit '\[\s*(?:"[^"\\]*"\s*,\s*)*"\Qnotification.pusher.write\E"(?:\s*,\s*"[^"\\]*")*\s*\]'
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.scopes_supported" überein mit '\[\s*(?:"[^"\\]*"\s*,\s*)*"\Qnotification.channel.read\E"(?:\s*,\s*"[^"\\]*")*\s*\]'
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.scopes_supported" überein mit '\[\s*(?:"[^"\\]*"\s*,\s*)*"\Qnotification.channel.write\E"(?:\s*,\s*"[^"\\]*")*\s*\]'

    Und TGR prüfe aktuelle Antwort im Knoten "$.body.scopes_supported" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "array",
        "contains": {"const": "notification.history.read"},
        "minContains": ${notificationLifecycleHistoryScopeCount},
        "maxContains": ${notificationLifecycleHistoryScopeCount}
      }
      """

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${testdata.notificationService.pusher.pushkey}&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationLifecycleResource}",["${notificationLifecycleResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${testdata.notificationService.pusher.pushkey}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "\Q${testdata.notificationService.pusher.appId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationLifecycleResource}",["${notificationLifecycleResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${testdata.notificationService.pusher.pushkey}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "\Q${testdata.notificationService.pusher.appId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.device_display_name" überein mit "\Q${testdata.notificationService.pusher.deviceDisplayName}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.url" überein mit "\Q${testdata.notificationService.pusher.url}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${oidcTestData.kvnr}\E"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "notificationLifecycleSubject"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationLifecycleResource}",["${notificationLifecycleResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${notificationLifecycleSubject}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      !{file('src/test/resources/testdata/push/pusher_lifecycle_response.schema.json')}
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationLifecyclePusherResponse"
    Und validiere "${notificationLifecyclePusherResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

    Und TGR setze lokale Variable "testdata.notificationService.pusher.deviceDisplayName" auf "${testdata.notificationService.pusher.deviceDisplayName} aktualisiert"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine PUT Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationLifecycleResource}",["${notificationLifecycleResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${testdata.notificationService.pusher.pushkey}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "\Q${testdata.notificationService.pusher.appId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "http"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.device_display_name" überein mit "\Q${testdata.notificationService.pusher.deviceDisplayName}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.data.url" überein mit "\Q${testdata.notificationService.pusher.url}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${notificationLifecycleSubject}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationLifecycleResource}",["${notificationLifecycleResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${notificationLifecycleSubject}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      !{file('src/test/resources/testdata/push/pusher_lifecycle_response.schema.json')}
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationLifecyclePusherResponse"
    Und validiere "${notificationLifecyclePusherResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${testdata.notificationService.pusher.pushkey}&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationLifecycleResource}",["${notificationLifecycleResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "\Q${testdata.notificationService.pusher.pushkey}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.app_id" überein mit "\Q${testdata.notificationService.pusher.appId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kind" überein mit "null"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${notificationLifecycleSubject}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationLifecycleResource}",["${notificationLifecycleResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${notificationLifecycleSubject}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "required": ["pushers"],
        "properties": {
          "pushers": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["pushkey", "app_id"],
              "properties": {
                "pushkey": {"type": "string"},
                "app_id": {"type": "string"}
              }
            },
            "contains": {
              "type": "object",
              "required": ["pushkey", "app_id"],
              "properties": {
                "pushkey": {"const": "${testdata.notificationService.pusher.pushkey}"},
                "app_id": {"const": "${testdata.notificationService.pusher.appId}"}
              }
            },
            "minContains": 0,
            "maxContains": 0
          }
        }
      }
      """
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationLifecyclePusherResponse"
    Und validiere "${notificationLifecyclePusherResponse}" gegen OpenAPI-Schema "schemas/push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema"

  @A_29990
  Szenariogrundriss: Notification-Protokolle schützen den Identifikator bei Nutzerauflösung und Notification-Ablehnung (<id_type>)
    Gegeben sei ermittle den aktuellen Ready-Pod für das Deployment "testfachdienst" und speichere in der Variable "notificationFachdienstPod"
    Und ermittle die konfigurierte Umgebungsvariable "NOTIFICATION_SERVICE_RS_BASE_URL" des Containers "testfachdienst" im Pod "${notificationFachdienstPod}" und speichere in der Variable "notificationRsUpstream"
    Und TGR prüfe Variable "notificationRsUpstream" stimmt überein mit "\Q${paths.fachdienst.routingUpstream}/notification-service-rs\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Gegeben sei ein neuer Notification-Testidentifikator vom Typ "<id_type>" in Variable "notificationUserId"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "notificationLogStart"

    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}/users/${notificationUserId}/channels/query" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"id_type":"<id_type>"}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.guard.notificationUsersPathPrefix}${notificationUserId}/channels.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.errorCode" überein mit "USER_NOT_FOUND"

    Und erzeuge eindeutige Test-ID mit Präfix "notification-unknown-user" und speichere in Variable "notificationCorrelationId"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und TGR setze lokale Variable "notificationIdentifierCondition" auf "isRequest && request.path == '/notification-service-rs/notifications' && request.bodyAsString =~ '(?s).*${notificationCorrelationId}.*'"
    Und Setze im TigerProxy für die Nachricht "${notificationIdentifierCondition}" die Manipulation auf Feld "$.body.user.id_type" und Wert "<id_type>"
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${notificationUserId}","event_type":"registered_client_activity","correlation_id":"${notificationCorrelationId}"}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die erste Anfrage mit Pfad "^${paths.guard.notificationServiceRsPath}$" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.id_type" überein mit "<id_type>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.errorCode" überein mit "USER_NOT_FOUND"

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "notificationLogEnd"
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                              | size  | track_total_hits |
      | (resource.service.name:"${telemetry.service.notificationService}" OR resource.k8s.container.name:"${telemetry.containerName.notificationService}") AND @timestamp:[${notificationLogStart}000 TO ${notificationLogEnd}000] | 10000 | true             |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.timed_out" überein mit "false"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body._shards.failed" überein mit "0"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.relation" überein mit "eq"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.value" überein mit "([1-9][0-9]{0,3}|10000)"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits" überein mit "(?s)(?!.*\Q${notificationUserId}\E).*"

    @TA_A_29990_01
    @TA_A_29990_04
    Beispiele: KVNR
      | id_type |
      | kvnr    |

    @TA_A_29990_02
    @TA_A_29990_05
    Beispiele: Telematik-ID
      | id_type      |
      | telematik-id |

  @A_29990
  @TA_A_29990_01
  @TA_A_29990_04
  Szenario: Notification-Protokolle schützen die KVNR bei Registrierung und Notification-Versand
    Gegeben sei ermittle den aktuellen Ready-Pod für das Deployment "testfachdienst" und speichere in der Variable "notificationFachdienstPod"
    Und ermittle die konfigurierte Umgebungsvariable "NOTIFICATION_SERVICE_RS_BASE_URL" des Containers "testfachdienst" im Pod "${notificationFachdienstPod}" und speichere in der Variable "notificationRsUpstream"
    Und TGR prüfe Variable "notificationRsUpstream" stimmt überein mit "\Q${paths.fachdienst.routingUpstream}/notification-service-rs\E"
    Gegeben sei TGR setze lokale Variable "notificationUserId" auf "${oidcTestData.kvnr}"
    Und erzeuge eindeutige Test-ID mit Präfix "notification-privacy" und speichere in Variable "testdata.notificationService.pusher.pushkey"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "notificationLogStart"

    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.pushers}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/pusher.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersSetPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.pushkey" überein mit "${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine POST Anfrage an "${paths.client.notifications.channelsLocal}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/testdata/push/notification_channels.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationChannelsPathPrefix}${testdata.notificationService.pusher.pushkey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "${notificationUserId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}/users/${notificationUserId}/channels/query" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"id_type":"kvnr"}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.guard.notificationUsersPathPrefix}${notificationUserId}/channels.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.user.value" überein mit "${notificationUserId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.channels.[?(@.id == 'zeta')].status" überein mit "enabled"

    Und erzeuge eindeutige Test-ID mit Präfix "notification-privacy" und speichere in Variable "notificationCorrelationId"
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Wenn TGR sende eine POST Anfrage an "${paths.client.notificationTestEvents}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"user_id":"${notificationUserId}","event_type":"registered_client_activity","correlation_id":"${notificationCorrelationId}"}
      """
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Dann TGR finde die erste Anfrage mit Pfad "^${paths.guard.notificationServiceRsPath}$" und Knoten "$.body.payload.correlation_id" der mit "${notificationCorrelationId}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.value" überein mit "${notificationUserId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user.id_type" überein mit "kvnr"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.channel" überein mit "${testdata.notificationService.notificationChannel.id}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.status" überein mit "accepted|queued"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.notification_id"

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden

    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.notifications.pushers}?pushkey=${testdata.notificationService.pusher.pushkey}&appId=${testdata.notificationService.pusher.appId}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.notifications.pushersPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "notificationLogEnd"
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                              | size  | track_total_hits |
      | (resource.service.name:"${telemetry.service.notificationService}" OR resource.k8s.container.name:"${telemetry.containerName.notificationService}") AND @timestamp:[${notificationLogStart}000 TO ${notificationLogEnd}000] | 10000 | true             |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.timed_out" überein mit "false"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body._shards.failed" überein mit "0"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.relation" überein mit "eq"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.value" überein mit "([1-9][0-9]{0,3}|10000)"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits" überein mit "(?s)(?!.*\Q${notificationUserId}\E).*"
