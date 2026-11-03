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

@UseCase_04_05
@require_kubectl
@oidc
Funktionalität: Erhaltene Identität und F1 nach letzter Client-Löschung durch Folgeregistrierung nachweisen

  @A_29938
  @TA_A_29938_01
  @TA_A_29938_02
  @TA_A_29938_03
  @normal
  @Ignore
  Szenario: Nach der letzten Client-Löschung erkennt der Guard die Identität und ihr bestehendes F1 wieder
    # 1. Erstanmeldung: Eine frische Identität erhält einen Client und ein bestätigtes F1.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_RETAINED_KVNR"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_RETAINED_CLIENT"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_RETAINED_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_RETAINED_CLIENT}\E"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "authorization_code" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_RETAINED_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.binding_mode" überein mit "collect_email"

    # 2. Ausgangsbestand: Genau ein Client stellt sicher, dass die folgende Löschung den letzten trifft.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.clients}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.clientsPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.clientsManagementPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) .+"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.client_id}" überein mit "\Q${CC_RETAINED_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_RETAINED_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"array","minItems":1,"maxItems":1}
      """
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.0.client_id" überein mit "\Q${CC_RETAINED_CLIENT}\E"

    Wenn der E-Mail-Inhalt von "${paths.mailcatcher.messages}" mit begrenzter Wartezeit und Anfragekennung in Variable "CC_MAIL_CAPTURE" abgerufen wird
    Dann TGR finde die erste Anfrage mit Pfad "${paths.mailcatcher.messagesPath}" und Knoten "$.header.X-Zeta-Mail-Capture" der mit "${CC_MAIL_CAPTURE}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.[?(@.recipients.0 == '<${CC_RETAINED_KVNR}@${oidcTestData.emailDomain}>' && @.subject == 'Your email verification code')].id" der aktuellen Antwort in der Variable "CC_INITIAL_OTP_MAIL_ID"

    # 3. Selbstlöschung über das SDK: Nur eine abgeschlossene Löschung erfüllt die Vorbedingung.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.registration}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.registrationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "204"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.clientsManagementPath}/${CC_RETAINED_CLIENT}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "DELETE"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) .+"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.client_id}" überein mit "\Q${CC_RETAINED_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_RETAINED_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200|204"

    # 4. Neue Clientinstanz, gleiche Identität: Reset verhindert die Wiederverwendung der alten Registrierung.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.resetPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine POST Anfrage an "${paths.client.oidc.kvnrEmail}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"kvnr":"${CC_RETAINED_KVNR}","email":"${CC_RETAINED_KVNR}@${oidcTestData.emailDomain}"}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_FOLLOWUP_CLIENT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.client_id" nicht überein mit "\Q${CC_RETAINED_CLIENT}\E"
    # TA_A_29938_02: verify_otp statt collect_email weist den Folgeregistrierungspfad des Guards nach.
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "authorization_code" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_FOLLOWUP_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.binding_mode" überein mit "verify_otp"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_RETAINED_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_FOLLOWUP_CLIENT}\E"

    # 5. TA_A_29938_01/_03: Bestehendes F1 erhält einen neuen OTP, den der neue Client erfolgreich bestätigt.
    Wenn der E-Mail-Inhalt von "${paths.mailcatcher.messages}" mit begrenzter Wartezeit und Anfragekennung in Variable "CC_MAIL_CAPTURE" abgerufen wird
    Dann TGR finde die erste Anfrage mit Pfad "${paths.mailcatcher.messagesPath}" und Knoten "$.header.X-Zeta-Mail-Capture" der mit "${CC_MAIL_CAPTURE}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.[?(@.recipients.0 == '<${CC_RETAINED_KVNR}@${oidcTestData.emailDomain}>' && @.subject == 'Your email verification code' && @.id != ${CC_INITIAL_OTP_MAIL_ID})].id" der aktuellen Antwort in der Variable "CC_FOLLOWUP_OTP_MAIL_ID"
    Und TGR prüfe Variable "CC_FOLLOWUP_OTP_MAIL_ID" stimmt überein mit "[0-9]+"
    Wenn der E-Mail-Inhalt von "${paths.mailcatcher.messages}/${CC_FOLLOWUP_OTP_MAIL_ID}.plain" mit begrenzter Wartezeit und Anfragekennung in Variable "CC_MAIL_CAPTURE" abgerufen wird
    Dann TGR finde die erste Anfrage mit Pfad "${paths.mailcatcher.messagesPath}/${CC_FOLLOWUP_OTP_MAIL_ID}.plain" und Knoten "$.header.X-Zeta-Mail-Capture" der mit "${CC_MAIL_CAPTURE}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_FOLLOWUP_OTP_CODE"
    Und TGR ersetze "(?s).*Your verification code is: ([0-9]{6}).*" mit "$1" im Inhalt der Variable "CC_FOLLOWUP_OTP_CODE"
    Und TGR prüfe Variable "CC_FOLLOWUP_OTP_CODE" stimmt überein mit "[0-9]{6}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.client_id}" überein mit "\Q${CC_FOLLOWUP_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.emailVerificationRequest.code}" überein mit "\Q${CC_FOLLOWUP_OTP_CODE}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # 6. Endbestand: Nur der neue Client gehört zur erhaltenen Identität; der gelöschte bleibt entfernt.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.clients}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.clientsPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.clientsManagementPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.client_id}" überein mit "\Q${CC_FOLLOWUP_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_RETAINED_KVNR}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"type":"array","minItems":1,"maxItems":1}
      """
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.0.client_id" überein mit "\Q${CC_FOLLOWUP_CLIENT}\E"
