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

@UseCase_04_12
@oidc
@additional_sdk_instance
@reset_testdriver
Funktionalität: F1 bleibt für jede KVNR auf Identitätsebene gebunden

  Grundlage: Zwei verschiedene KVNRs haben jeweils eine bestätigte E-Mail
    # Zufällige Domains verhindern, dass KVNR@Standarddomain als vermeintlich gespeicherter F1 genügt.
    Und TGR setze lokale Variable "F1_DEFAULT_EMAIL_DOMAIN" auf "${oidcTestData.emailDomain}"
    Gegeben sei erzeuge eindeutige Test-ID mit Präfix "f1-a" und speichere in Variable "F1_EMAIL_DOMAIN_1"
    Und TGR setze lokale Variable "oidcTestData.emailDomain" auf "${F1_EMAIL_DOMAIN_1}.example.test"
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "F1_KVNR_1"
    Und TGR setze lokale Variable "F1_EMAIL_1" auf "${F1_KVNR_1}@${oidcTestData.emailDomain}"
    Gegeben sei eine zusätzliche unabhängige SDK-Instanz für KVNR "${F1_KVNR_1}"
    Und TGR setze lokale Variable "F1_FIRST_SDK_PATH" auf "${CC_SECOND_SDK_PATH}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${F1_FIRST_SDK_PATH}"
    Und TGR warte auf eine Nachricht, in der Knoten "$.body.access_token.body.sub" mit "\Q${F1_KVNR_1}\E" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${F1_KVNR_1}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email" überein mit "(?i)\Q${F1_EMAIL_1}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email_verified" überein mit "true"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.client_id" der aktuellen Antwort in der Variable "F1_INITIAL_CLIENT_1"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.email" überein mit "(?i)\Q!{urlEncoded('${F1_EMAIL_1}')}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Beide Erstbindungen zuerst herstellen: Eine global gemerkte letzte E-Mail darf nicht genügen.
    Gegeben sei erzeuge eindeutige Test-ID mit Präfix "f1-b" und speichere in Variable "F1_EMAIL_DOMAIN_2"
    Und TGR setze lokale Variable "oidcTestData.emailDomain" auf "${F1_EMAIL_DOMAIN_2}.example.test"
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "F1_KVNR_2"
    Und TGR setze lokale Variable "F1_EMAIL_2" auf "${F1_KVNR_2}@${oidcTestData.emailDomain}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Und TGR warte auf eine Nachricht, in der Knoten "$.body.access_token.body.sub" mit "\Q${F1_KVNR_2}\E" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${F1_KVNR_2}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" nicht überein mit "\Q${F1_KVNR_1}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email" überein mit "(?i)\Q${F1_EMAIL_2}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email_verified" überein mit "true"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.client_id" der aktuellen Antwort in der Variable "F1_INITIAL_CLIENT_2"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" nicht überein mit "\Q${F1_INITIAL_CLIENT_1}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.email" überein mit "(?i)\Q!{urlEncoded('${F1_EMAIL_2}')}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.bindEmailPath}" haben
    Und TGR setze lokale Variable "F1_EMAIL_REQUEST_PATTERN" auf "(?is).*(?:\Q${F1_EMAIL_1}\E|\Q!{urlEncoded(getValue('F1_EMAIL_1'))}\E|\Q${F1_EMAIL_2}\E|\Q!{urlEncoded(getValue('F1_EMAIL_2'))}\E).*"
    Und TGR setze lokale Variable "oidcTestData.emailDomain" auf "${F1_DEFAULT_EMAIL_DOMAIN}"

  @A_29900
  @A_29901
  @TA_A_29900_01
  @TA_A_29900_03
  @TA_A_29901_01
  @normal
  Szenario: Folgeregistrierungen verwenden ohne erneute E-Mail-Angabe den F1 der jeweiligen KVNR
    # KVNR 1 lokal zurücksetzen und neu registrieren; vorhandene Guard-Clients bleiben bestehen.
    # Ganze Guard-Anfragen zusätzlich auf beide Adressen im Klartext und URL-kodiert prüfen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"kvnr":"${F1_KVNR_1}","email":"${F1_EMAIL_1}"}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Und TGR warte auf eine Nachricht, in der Knoten "$.body.client_id" mit "(?!\Q${F1_INITIAL_CLIENT_1}\E$)(?!\Q${F1_INITIAL_CLIENT_2}\E$).+" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "F1_FOLLOW_CLIENT_1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.client_id" nicht überein mit "\Q${F1_INITIAL_CLIENT_1}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    # Keine erneute E-Mail-Angabe: Der Guard fordert für den neuen Client direkt einen OTP an.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${F1_FOLLOW_CLIENT_1}\E" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.binding_mode" überein mit "verify_otp"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.scope" überein mit "zeta:email-verify"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.bindEmailVerifyPath}" und Knoten "${headers.authorization.dpopToken.root}.body.azp" der mit "\Q${F1_FOLLOW_CLIENT_1}\E" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.code" der aktuellen Anfrage in der Variable "F1_OTP_1"
    # Nach OTP-Bestätigung müssen die Token wieder zur ursprünglichen KVNR und E-Mail gehören.
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${F1_FOLLOW_CLIENT_1}\E" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${F1_KVNR_1}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${F1_FOLLOW_CLIENT_1}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email" überein mit "(?i)\Q${F1_EMAIL_1}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email_verified" überein mit "true"
    # Eingereichten OTP und Mailempfänger abgleichen: Zustellung an die ursprünglich gebundene Adresse.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}/[0-9]+[.]plain"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "(?s).*\b${F1_OTP_1}\b.*"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "F1_OTP_MAIL_PATH"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}!{getValue('F1_OTP_MAIL_PATH').replace('.plain', '.json')}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}/[0-9]+[.]json"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.recipients.0" überein mit "(?i)<\Q${F1_EMAIL_1}\E>"
    # Zwei Bindungsanfragen aus den Erstregistrierungen; die Folgeregistrierung darf keine weitere auslösen.
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.bindEmailPath}" haben

    # KVNR 2 ebenso prüfen, damit vertauschte oder global gespeicherte E-Mail-Adressen auffallen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"kvnr":"${F1_KVNR_2}","email":"${F1_EMAIL_2}"}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Und TGR warte auf eine Nachricht, in der Knoten "$.body.client_id" mit "(?!\Q${F1_INITIAL_CLIENT_1}\E$)(?!\Q${F1_INITIAL_CLIENT_2}\E$)(?!\Q${F1_FOLLOW_CLIENT_1}\E$).+" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "F1_FOLLOW_CLIENT_2"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.client_id" nicht überein mit "\Q${F1_INITIAL_CLIENT_2}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${F1_FOLLOW_CLIENT_2}\E" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.binding_mode" überein mit "verify_otp"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.scope" überein mit "zeta:email-verify"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.bindEmailVerifyPath}" und Knoten "${headers.authorization.dpopToken.root}.body.azp" der mit "\Q${F1_FOLLOW_CLIENT_2}\E" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.code" der aktuellen Anfrage in der Variable "F1_OTP_2"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${F1_FOLLOW_CLIENT_2}\E" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "$..email"
    Und TGR prüfe aktueller Request im Knoten "$" stimmt nicht überein mit "${F1_EMAIL_REQUEST_PATTERN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${F1_KVNR_2}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${F1_FOLLOW_CLIENT_2}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email" überein mit "(?i)\Q${F1_EMAIL_2}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.email_verified" überein mit "true"
    # Eingereichten OTP und Mailempfänger abgleichen: Zustellung an die ursprünglich gebundene Adresse.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}/[0-9]+[.]plain"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "(?s).*\b${F1_OTP_2}\b.*"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "F1_OTP_MAIL_PATH"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}!{getValue('F1_OTP_MAIL_PATH').replace('.plain', '.json')}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}/[0-9]+[.]json"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.recipients.0" überein mit "(?i)<\Q${F1_EMAIL_2}\E>"
    # Zwei Bindungsanfragen aus den Erstregistrierungen; die Folgeregistrierung darf keine weitere auslösen.
    Und prüfe, dass genau 2 aufgezeichnete Anfragen den Pfad "${paths.guard.bindEmailPath}" haben

    # Ursprünglichen Client weiter nutzen; insgesamt je zwei Erst- und Folgeregistrierungen prüfen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${F1_FIRST_SDK_PATH}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${F1_FIRST_SDK_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass genau 4 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
