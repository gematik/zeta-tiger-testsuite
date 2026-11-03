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

@UseCase_03_01
Funktionalität: Mobiler Client OIDC Authentisierung, Autorisierung und Offband-Nutzerverifikation

  @A_25656
  @TA_A_25656_01
  @component
  @no_proxy
  @MASVS-AUTH
  Szenario: PDP-Entity-Statement weist erlaubte Redirect-URLs des OIDC-Clients aus
    # E01: Ruft das vom PDP Authorization Server veröffentlichte Entity Statement direkt ab.
    # Der direkte Komponentenzugriff trennt den Nachweis vom späteren asynchronen OIDC-Flow.
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownFederationPath}"
    # E02: Wählt genau die Entity-Statement-Antwort aus, die durch E01 ausgelöst wurde.
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownFederationPath}$"
    # E03: Prüft, dass der virtuelle Host des PDP verwendet wurde.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    # E04: Ein Entity Statement wird als signierter kompakter JWT ausgeliefert und muss deshalb
    # zunächst erfolgreich als Entity-Statement-Struktur dekodierbar sein. $.body speichert den
    # Roh-JWT; RBEL stellt dessen dekodierte Payload anschließend unter $.body.body bereit.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PDP_ENTITY_STATEMENT"
    Und decodiere und validiere "${PDP_ENTITY_STATEMENT}" gegen Schema "schemas/openid-federation-entity-statement.yaml"
    Und TGR speichere Wert des Knotens "$.body.body.jwks" der aktuellen Antwort in der Variable "PDP_ENTITY_STATEMENT_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${PDP_ENTITY_STATEMENT}" mit KeyStore "${PDP_ENTITY_STATEMENT_JWKS}"
    # E05: Belegt TA_A_25656_01: Die Relying-Party-Metadaten enthalten eine Redirect-URI-Liste.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.body.metadata.openid_relying_party.redirect_uris"
    # E06: Belegt TA_A_25656_01 für die Redirect-URLs des OIDC-Testclients.
    # RBEL legt primitive Array-Werte unter dem Knoten "content" ab; der Filter muss deshalb
    # den RBEL-Wert und nicht das JSON-Array-Element selbst vergleichen.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.body.metadata.openid_relying_party.redirect_uris.[?(@.content == '${oidcTestData.pdp.entityStatement.redirectUris.redirectUri}')]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.body.metadata.openid_relying_party.redirect_uris.[?(@.content == '${oidcTestData.pdp.entityStatement.redirectUris.oidcRedirectUri}')]"

  @A_25432-01
  @A_25651
  @A_25657
  @A_25758
  @A_26586-01
  @A_26973-02
  @TA_A_25432-01_02
  @TA_A_25432-01_05
  @TA_A_25651_01
  @TA_A_25657_01
  @TA_A_25758_01
  @TA_A_25758_02
  @TA_A_26586-01_02
  @TA_A_26973-02_01
  @minor
  @MASVS-CODE
  @MASVS-AUTH
  @require_kubectl
  @oidc
  @require_oidc_decryption_key
  @reset_testdriver
  Szenario: OIDC-Authentisierung verarbeitet Nutzer-Daten und bestätigt die Registrierung
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "OIDC_KVNR"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kvnr" überein mit "${OIDC_KVNR}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.email" überein mit "${OIDC_KVNR}@${oidcTestData.emailDomain}"
    Und TGR speichere Wert des Knotens "$.body.email" der aktuellen Anfrage in der Variable "OIDC_EMAIL"

    Und TGR setze lokale Variable "tiger.lib.validation.messageSortOrder" auf "TIMESTAMP"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten

    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.bindEmailPath}" und Knoten "${body.oidc.bindEmailRequest.email}" der mit "!{urlEncoded('${OIDC_EMAIL}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "202"

    Und warte "1" Sekunden
    Wenn TGR sende eine leere GET Anfrage an "${paths.mailcatcher.messages}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.[?(@.recipients.0 == '<${OIDC_EMAIL}>')].id"
    Und TGR speichere Wert des Knotens "$.body.[?(@.recipients.0 == '<${OIDC_EMAIL}>')].id" der aktuellen Antwort in der Variable "OIDC_VERIFICATION_MAIL_ID"

    Wenn TGR sende eine leere GET Anfrage an "${paths.mailcatcher.messages}/${OIDC_VERIFICATION_MAIL_ID}.plain"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}/${OIDC_VERIFICATION_MAIL_ID}.plain"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "OIDC_VERIFICATION_CODE"
    Und TGR ersetze "(?s)^.*?\b([0-9]{6})\b.*$" mit "$1" im Inhalt der Variable "OIDC_VERIFICATION_CODE"
    Und TGR prüfe Variable "OIDC_VERIFICATION_CODE" stimmt überein mit "[0-9]{6}"

    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.bindEmailVerifyPath}" und Knoten "${body.oidc.emailVerificationRequest.code}" der mit "${OIDC_VERIFICATION_CODE}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"

    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.sectoralIdp.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "authorization_code" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"

    Und TGR prüfe aktuelle Antwort enthält Knoten "${body.oidc.tokenResponse.idToken.root}"
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.id}" in Variable "ID_TOKEN_KVNR"
    Und TGR prüfe Variable "ID_TOKEN_KVNR" stimmt überein mit "${OIDC_KVNR}"
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.profession}" in Variable "ID_TOKEN_PROFESSION"
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.organization}" in Variable "ID_TOKEN_ORGANIZATION"

    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.authorizationRequest.grantType}" überein mit "${oauth_parameters.grant_type.tokenExchange}"
    Und TGR prüfe aktueller Request enthält Knoten "${body.oidc.opaRequest.userInfo.root}"
    Und TGR speichere Wert des Knotens "${body.oidc.opaRequest.userInfo.root}" der aktuellen Anfrage in der Variable "OIDC_USER_INFO"
    Und validiere "${OIDC_USER_INFO}" gegen Schema "schemas/v_2_0/schemas/zeta-user-info.yaml"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.userInfo.identifier}" überein mit "${ID_TOKEN_KVNR}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.userInfo.commonName}" überein mit "${ID_TOKEN_KVNR}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.userInfo.professionOid}" überein mit "${ID_TOKEN_PROFESSION}"

    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.userInfo.organizationName}" überein mit "${ID_TOKEN_ORGANIZATION}"
