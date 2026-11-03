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
@UseCase_02_01
@require_kubectl
@reset_testdriver
Funktionalität: Resource und Scopes der Notification-Service-Tokens prüfen

  Hintergrund:
    Gegeben sei eine eindeutige, randomisierte KVNR
    Und TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.notificationProtectedResourcePath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationProtectedResourcePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.resource" überein mit "\Q${paths.guard.notificationServiceUrl}\E"
    Und TGR speichere Wert des Knotens "$.body.resource" der aktuellen Antwort in der Variable "notificationTokenResource"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationTokenJwks"
    Und TGR lösche aufgezeichnete Nachrichten

  @A_29979
  @TA_A_29979_03
  @normal
  Szenario: SDK fordert das Notification-Service-Token mit dem Resource Indicator an
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.resource" überein mit "\Q!{urlEncoded('${notificationTokenResource}')}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "notificationResourceAccessToken"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.access_token.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationTokenResource}",["${notificationTokenResource}"]]}}}
      """
    Und verifiziere die ES256 Signatur des JWT "${notificationResourceAccessToken}" mit KeyStore "${notificationTokenJwks}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${notificationResourceAccessToken}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${oidcTestData.kvnr}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_29979
  @TA_A_29979_04
  @normal
  Szenario: AuthS begrenzt Notification-Token-Scopes auch bei einer unzulässigen Scope-Anforderung
    Gegeben sei der History-Featurestatus des laufenden Notification Service
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "notificationControlToken"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationControlResponse"
    Und die Tokenantwort "${notificationControlResponse}" bei HTTP "200" für Resource "${notificationTokenResource}" hält die Notification-Scope-Grenzen mit History-Status "${notificationLifecycleHistoryScopeCount}" und JWKS "${notificationTokenJwks}" ein
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${notificationControlToken}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Gegeben sei eine eindeutige, randomisierte KVNR
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "notificationExchangeCondition" auf "$.path == '${paths.guard.tokenEndpointPath}' && $.method == 'POST' && $.body.grant_type == '!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}'"
    Und Setze im TigerProxy für die Nachricht "${notificationExchangeCondition}" die Regex-Manipulation auf Feld "$.body" mit Regex "(^|&)scope=[^&]*" und Wert "" und 20 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${notificationExchangeCondition}" die Regex-Manipulation auf Feld "$.body" mit Regex "$" und Wert "&scope=notification.pusher.read%20profile" und 20 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.scope" überein mit "notification.pusher.read%20profile"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200|400|403"
    Und TGR speichere Wert des Knotens "$.responseCode" der aktuellen Antwort in der Variable "notificationScopeStatus"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "notificationScopeResponse"
    Und die Tokenantwort "${notificationScopeResponse}" bei HTTP "${notificationScopeStatus}" für Resource "${notificationTokenResource}" hält die Notification-Scope-Grenzen mit History-Status "${notificationLifecycleHistoryScopeCount}" und JWKS "${notificationTokenJwks}" ein

  @A_29979
  @TA_A_29979_07
  @normal
  Szenariogrundriss: Notification Service autorisiert kein Token mit zusätzlichem Scope <Scope>
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "notificationOriginalAuthorization"
    Und extrahiere DPoP Access Token aus Authorization Header "${notificationOriginalAuthorization}" und speichere in Variable "notificationOriginalToken"
    Und verifiziere die ES256 Signatur des JWT "${notificationOriginalToken}" mit KeyStore "${notificationTokenJwks}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.iss" überein mit "\Q${paths.guard.issuerUrl}\E"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"object","required":["aud"],"properties":{"aud":{"enum":["${notificationTokenResource}",["${notificationTokenResource}"]]}}}
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${oidcTestData.kvnr}\E"
    Und stelle für JWT-Variante resign_correct_key den Signaturschlüssel anhand des Access Tokens "${notificationOriginalToken}" bereit
    Und TGR setze lokale Variable "notificationSigningKeyPath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "notificationSigningKey" auf "!{file('${notificationSigningKeyPath}')}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "notificationDpopKey"
    Und Setze im TigerProxy für Access Token das Feld "body.scope" auf Wert "notification.pusher.read" mit Access Token Key "${notificationSigningKey}" und DPoP Key "${notificationDpopKey}" für Pfad ".*${paths.guard.notificationPushersPath}$" und 20 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.scope" überein mit "notification.pusher.read"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "notificationAllowedAuthorization"
    Und extrahiere DPoP Access Token aus Authorization Header "${notificationAllowedAuthorization}" und speichere in Variable "notificationAllowedToken"
    Und verifiziere die ES256 Signatur des JWT "${notificationAllowedToken}" mit KeyStore "${notificationTokenJwks}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn Alle Manipulationen im TigerProxy werden gestoppt
    Und Setze im TigerProxy für Access Token das Feld "body.scope" auf Wert "notification.pusher.read <Scope>" mit Access Token Key "${notificationSigningKey}" und DPoP Key "${notificationDpopKey}" für Pfad ".*${paths.guard.notificationPushersPath}$" und 20 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.notificationPushersPath}" und Knoten "${headers.authorization.dpopToken.root}.body.scope" der mit "notification.pusher.read <Scope>" übereinstimmt
    Und merke die aktuelle Anfrage als Proxy-Historienbeginn
    Und synchronisiere die Proxy-Historie seit dem gemerkten Beginn
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "notificationForbiddenAuthorization"
    Und extrahiere DPoP Access Token aus Authorization Header "${notificationForbiddenAuthorization}" und speichere in Variable "notificationForbiddenToken"
    Und die JWTs "${notificationAllowedToken}" und "${notificationForbiddenToken}" unterscheiden sich ausschließlich im Claim "scope"
    Und verifiziere die ES256 Signatur des JWT "${notificationForbiddenToken}" mit KeyStore "${notificationTokenJwks}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body.exp" der aktuellen Anfrage in der Variable "notificationTokenExpires"
    Und validiere, dass der Zeitstempel "${notificationTokenExpires}" in der Zukunft liegt
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body.iat" der aktuellen Anfrage in der Variable "notificationTokenIssued"
    Und validiere, dass der Zeitstempel "${notificationTokenIssued}" in der Vergangenheit liegt
    Und prüfe aktuelle Anfrage: der Knoten "${headers.authorization.dpopToken.root}.body.nbf" ist nicht vorhanden oder früher als jetzt
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "notificationForbiddenProof"
    Und berechne SHA256 Hash von "${notificationForbiddenToken}" und speichere in Variable "notificationExpectedAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${notificationExpectedAth}"
    Und verifiziere ES256 Signatur von DPoP JWT "${notificationForbiddenProof}"
    Und berechne JKT aus JWT Header JWK "${notificationForbiddenProof}" und speichere in Variable "notificationProofJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.cnf.jkt" überein mit "\Q${notificationProofJkt}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htm}" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "\Qhttps://${zeta_base_url}${paths.guard.notificationPushersPath}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401|403"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.pushers"
    Und prüfe, dass alle aufgezeichneten "GET" Anfragen mit Pfad "${paths.guard.notificationPushersPath}" mit HTTP "401|403" beantwortet wurden

    Wenn Alle Manipulationen im TigerProxy werden gestoppt
    Und Setze im TigerProxy für Access Token das Feld "body.scope" auf Wert "notification.pusher.read" mit Access Token Key "${notificationSigningKey}" und DPoP Key "${notificationDpopKey}" für Pfad ".*${paths.guard.notificationPushersPath}$" und 20 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.notifications.pushers}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.notificationPushersPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.scope" überein mit "notification.pusher.read"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Beispiele:
      | Scope                 |
      | profile               |
      | email                 |
      | notification.unlisted |
