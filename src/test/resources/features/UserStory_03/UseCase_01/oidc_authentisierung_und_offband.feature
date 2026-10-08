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

@mobile
@UseCase_03_01
Funktionalität: Mobiler Client OIDC Authentisierung, Autorisierung und Offband-Nutzerverifikation

  @A_25656
  @TA_A_25656_01
  @component
  @no_proxy
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
  @A_26586
  @A_26973-01
  @A_27867-01
  @TA_A_25432-01_02
  @TA_A_25432-01_05
  @TA_A_25651_01
  @TA_A_25657_01
  @TA_A_25758_01
  @TA_A_25758_02
  @TA_A_26586_02
  @TA_A_26973-01_01
  @TA_A_27867-01_04
  @minor
  @MASVS-AUTH
  @require_kubectl
  @require_oidc_testdriver
  @require_oidc_decryption_key
  Szenario: OIDC-Authentisierung verarbeitet Nutzer-Daten und bestätigt die Registrierung
    # F01: Erzeugt die isolierte OIDC-Testidentität und stellt KVNR sowie E-Mail-Adresse
    # für die folgenden Prüfungen bereit.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "OIDC_KVNR"
    # F02: Wählt genau die Antwort des neuen KVNR/E-Mail-Setup-Aufrufs aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.kvnrEmailPath}"
    # F03: Stellt sicher, dass der Testdriver die neue Identität erfolgreich übernommen hat.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    # F04: Prüft, dass die konfigurierte KVNR der isolierten Testidentität entspricht.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.kvnr" überein mit "${OIDC_KVNR}"
    # F05: Belegt TA_A_25758_01: Die strukturell valide E-Mail-Adresse ist mit der
    # isolierten KVNR korreliert und wurde dem OIDC-Testclient bereitgestellt.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.email" überein mit "${OIDC_KVNR}@${oidcTestData.emailDomain}"
    # F06: Speichert genau die bereitgestellte E-Mail-Adresse für die spätere Korrelation.
    Und TGR speichere Wert des Knotens "$.body.email" der aktuellen Anfrage in der Variable "OIDC_EMAIL"

    # Der Aufruf /oidc/kvnr-email bereitet den nächsten authentifizierten Request nur vor.
    # Der folgende helloZeta-Aufruf startet den eigentlichen geschützten OIDC- und Ressourcenfluss.
    # F07: Startet den geschützten Zugriff; der Testdriver authentifiziert den nächsten Request mit F01.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten

    # F08: Wartet auf die Übertragung der als Nutzereingabe gesetzten E-Mail-Adresse an den Authorization Server.
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailPath}" übereinstimmt
    # F09: Belegt TA_A_25758_01 und TA_A_25758_02: Der Client verwendet genau die
    # bereitgestellte E-Mail-Adresse für die Offband-Verifikation.
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.bindEmailPath}" und Knoten "${body.oidc.bindEmailRequest.email}" der mit "!{urlEncoded('${OIDC_EMAIL}')}" übereinstimmt
    # F10: Belegt, dass der Authorization Server die E-Mail-Adresse angenommen hat.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "202"

    # F11: Gibt dem asynchronen Versand Zeit, die Nachricht im Mailcatcher abzulegen.
    Und warte "1" Sekunden
    # F12: Liest die vom Authorization Server versendeten Nachrichten aus dem Mailcatcher.
    Wenn TGR sende eine leere GET Anfrage an "${paths.mailcatcher.messages}"
    # F13: Wählt genau die Mailcatcher-Antwort für F12 aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}"
    # F14: Stellt sicher, dass der Mailcatcher die Nachrichten erfolgreich liefert.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    # F15: Belegt TA_A_25432-01_05, TA_A_25651_01 und TA_A_25758_02:
    # Die Nachricht wurde nach Übertragung an den Authorization Server an die aktuelle Adresse versendet.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.[?(@.recipients.0 == '<${OIDC_EMAIL}>')].id"
    # F16: Speichert die ID genau dieser Nachricht, damit OTP-Quelle und Verifikationsrequest korreliert bleiben.
    Und TGR speichere Wert des Knotens "$.body.[?(@.recipients.0 == '<${OIDC_EMAIL}>')].id" der aktuellen Antwort in der Variable "OIDC_VERIFICATION_MAIL_ID"

    # Der OIDC-Testdriver liest den OTP aus der für diese Adresse gefundenen
    # Mailcatcher-Nachricht und bestätigt ihn automatisch im laufenden Flow.
    # F17: Ruft den Klartext genau der zuvor korrelierten Nachricht ab.
    Wenn TGR sende eine leere GET Anfrage an "${paths.mailcatcher.messages}/${OIDC_VERIFICATION_MAIL_ID}.plain"
    # F18: Wählt die Antwort des korrelierten Klartextabrufs aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.mailcatcher.messagesPath}/${OIDC_VERIFICATION_MAIL_ID}.plain"
    # F19: Stellt sicher, dass Mailcatcher den Nachrichteninhalt erfolgreich geliefert hat.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    # F20: Übernimmt den Nachrichteninhalt als Quelle des tatsächlich versendeten OTP.
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "OIDC_VERIFICATION_CODE"
    # F21: Extrahiert ausschließlich den sechsstelligen OTP aus dem Text der korrelierten Nachricht.
    Und TGR ersetze "(?s).*Your verification code is: ([0-9]{6}).*" mit "$1" im Inhalt der Variable "OIDC_VERIFICATION_CODE"
    # F22: Belegt das erwartete Format des aus der Nachricht extrahierten OTP.
    Und TGR prüfe Variable "OIDC_VERIFICATION_CODE" stimmt überein mit "[0-9]{6}"

    # F23: Wartet auf die automatische OTP-Bestätigung am Guard.
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    # F24: Belegt, dass genau der aus der korrelierten Nachricht extrahierte OTP am verifyPath verwendet wurde.
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.bindEmailVerifyPath}" und Knoten "${body.oidc.emailVerificationRequest.code}" der mit "${OIDC_VERIFICATION_CODE}" übereinstimmt
    # F25: Stellt sicher, dass der Guard diesen OTP erfolgreich bestätigt hat.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"

    # F26: Wartet auf den asynchronen Folgeaufruf nach abgeschlossener Offband-Bestätigung.
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    # F27: Wählt den asynchronen Folgeaufruf für die Korrelation des weiteren Flows aus.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    # F28: Belegt, dass die Ressource-Anfrage mit HTTP-200 erfolgreich beantwortet wurde.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"

    # F29: Belegt TA_A_25432-01_02 und TA_A_25657_01 am beobachteten Authorization-Code-Tokenaustausch.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.sectoralIdp.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "authorization_code" übereinstimmt
    # F30: Stellt sicher, dass der sektorale IDP den Tokenaustausch erfolgreich beantwortet.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"

    # F31: Belegt TA_A_25657_01 und TA_A_26973-01_01: Die Tokenantwort enthält ein id_token.
    Und TGR prüfe aktuelle Antwort enthält Knoten "${body.oidc.tokenResponse.idToken.root}"
    # F32: Liest den KVNR-Claim aus genau diesem korrelierten und dynamisch entschlüsselten id_token.
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.id}" in Variable "ID_TOKEN_KVNR"
    # F33: Belegt TA_A_26973-01_01: Die id_token-KVNR entspricht der Testidentität aus F01.
    Und TGR prüfe Variable "ID_TOKEN_KVNR" stimmt überein mit "${OIDC_KVNR}"
    # F34: Liest den Profession-Claim aus demselben id_token als Quelle des OPA-Mappings.
    Und entschlüssle JWE "${body.oidc.tokenResponse.idToken.root}" der aktuellen Antwort und speichere Claim "${oidcTestData.idTokenClaims.profession}" in Variable "ID_TOKEN_PROFESSION"

    # F35: Wählt den zur OIDC-Autorisierung gehörenden Policy-Engine-Request aus.
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    # F36: Belegt den erfolgreichen Policy-Engine-Aufruf gemäß dem UseCase-Ablauf.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    # F37: Belegt, dass die Policy-Engine-Anfrage den Token-Exchange als Autorisierungsanlass trägt.
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.authorizationRequest.grantType}" überein mit "${oauth_parameters.grant_type.tokenExchange}"
    # F38: Belegt TA_A_26586_02: Der Request enthält die an die Policy Engine übergebenen Nutzer-Daten.
    Und TGR prüfe aktueller Request enthält Knoten "${body.oidc.opaRequest.userInfo.root}"
    # F39: Übernimmt genau diese Nutzer-Daten für die nachfolgende Schema- und Mappingprüfung.
    Und TGR speichere Wert des Knotens "${body.oidc.opaRequest.userInfo.root}" der aktuellen Anfrage in der Variable "OIDC_USER_INFO"
    # F40: Belegt TA_A_27867-01_04: Die Nutzer-Daten sind gemäß zeta-user-info schemakonform.
    Und validiere "${OIDC_USER_INFO}" gegen Schema "schemas/v_1_0/zeta-user-info.yaml"
    # F41: Belegt TA_A_26973-01_01: Der id-Claim wird als identifier gemappt.
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.userInfo.identifier}" überein mit "${ID_TOKEN_KVNR}"
    # F42: Belegt TA_A_26973-01_01: Derselbe id-Claim wird zusätzlich als commonName gemappt.
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.userInfo.commonName}" überein mit "${ID_TOKEN_KVNR}"
    # F43: Belegt TA_A_26973-01_01: Der profession-Claim wird als professionOID gemappt.
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.oidc.opaRequest.userInfo.professionOid}" überein mit "${ID_TOKEN_PROFESSION}"
