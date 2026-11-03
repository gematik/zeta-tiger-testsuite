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

@UseCase_03_03
@require_kubectl
@oidc
@flow_afo_continuation
@reset_testdriver
Funktionalität: Mobilen Software-Client mit E-Mail-OTP registrieren

  @A_29658
  @TA_A_29658_03
  @MASVS-AUTH
  Szenario: Mobile Software-Registrierung bestätigt die Transaktion mit dem zugestellten OTP
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "DCR_KVNR"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.email" der aktuellen Anfrage in der Variable "DCR_EMAIL"
    Und TGR prüfe Variable "DCR_EMAIL" stimmt überein mit "\Q${DCR_KVNR}@${oidcTestData.emailDomain}\E"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "tiger.lib.validation.messageSortOrder" auf "TIMESTAMP"
    Und TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.*.*.client_id"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.registerEndpointPath}" übereinstimmt
    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.user_email" überein mit "\Q${DCR_EMAIL}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_name" überein mit ".+"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.jwks.keys.0"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_types.[?(@.content == 'urn:ietf:params:oauth:grant-type:token-exchange')]"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_types.[?(@.content == 'refresh_token')]"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_types.[?(@.content == 'urn:ietf:params:oauth:grant-type:jwt-bearer')]"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.token_endpoint_auth_method" überein mit "private_key_jwt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.redirect_uris.[?(@.content == '${oidcTestData.pdp.entityStatement.redirectUris.oidcRedirectUri}')]" überein mit "https://[^/]+/cb/[^/]+/oidc"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.redirect_uris.[?(@.content == '${oidcTestData.pdp.entityStatement.redirectUris.redirectUri}')]" überein mit "https://[^/]+/cb/[^/]+/app"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "202"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message_id" überein mit "otp_verification"
    Und TGR speichere Wert des Knotens "$.body.transaction_id" der aktuellen Antwort in der Variable "DCR_TRANSACTION"
    Und TGR prüfe Variable "DCR_TRANSACTION" stimmt überein mit ".+"
    Wenn TGR sende eine leere GET Anfrage an "${paths.mailcatcher.messages}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.[?(@.recipients.0 == '<${DCR_EMAIL}>')].id" der aktuellen Antwort in der Variable "DCR_MAIL_ID"
    Wenn TGR sende eine leere GET Anfrage an "${paths.mailcatcher.messages}/${DCR_MAIL_ID}.plain"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}/${DCR_MAIL_ID}.plain"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "DCR_CODE"
    Und TGR ersetze "(?s)^.*?\b([0-9]{6})\b.*$" mit "$1" im Inhalt der Variable "DCR_CODE"
    Und TGR prüfe Variable "DCR_CODE" stimmt überein mit "[0-9]{6}"
    Und TGR setze lokale Variable "tiger.rbel.request.timeout" auf "35"
    Dann TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.registerVerifyEndpointPath}" übereinstimmt
    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.registerEndpointPath}"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.transaction_id" der mit "\Q${DCR_TRANSACTION}\E" übereinstimmt
    Und TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.registerVerifyEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.transaction_id" überein mit "\Q${DCR_TRANSACTION}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.code" überein mit "\Q${DCR_CODE}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "DCR_CLIENT_ID"
    Und TGR prüfe Variable "DCR_CLIENT_ID" stimmt überein mit ".+"
    Dann TGR warte auf eine Nachricht, in der Knoten "$.path" mit ".*/testdriver-api/register$" übereinstimmt
    Und TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/register$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.*.*.client_id" überein mit "\Q${DCR_CLIENT_ID}\E"
