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

@UseCase_01_10
Funktionalität: Client_authentisierung_und_autorisierung_client_attest_SC_200

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt


  @dev
  @A_25762
  @TA_A_25762_03
  Szenario: Nutzerauthentifizierung mittels SMC-B (JWT)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "tokenNonce"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_registration_by_auth_server.*.client_id" der aktuellen Antwort in der Variable "client_id"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "SUBJECT_TOKEN"
    Und decodiere und validiere "${SUBJECT_TOKEN}" gegen Schema "schemas/v_1_0/smb-id-token-jwt.yaml"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"

    #[RFC7523] - JWT-Subject Token Pflichtfelder
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.header.typ"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.header.x5c"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.header.alg"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.body.iss"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.body.sub"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.body.aud"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.body.iat"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.body.exp"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.body.nonce"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.body.jti"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token.signature"

    #[RFC 7523] - Werte auf Gültigkeit prüfen
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.alg" überein mit "ES256"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.typ" überein mit "JWT"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.exp" der aktuellen Anfrage in der Variable "subjectTokenExp"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.iat" der aktuellen Anfrage in der Variable "subjectTokenIat"
    Und validiere, dass der Zeitstempel "${subjectTokenExp}" später als "${subjectTokenIat}" liegt

    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.aud.0" überein mit "${paths.guard.audPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.nonce" überein mit "${tokenNonce}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.iss" überein mit "${client_id}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.sub" überein mit "${SMCB-INFO.telematikId}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.jti" der aktuellen Anfrage in der Variable "jti"

    #[RFC7523] - Optionale Felder
    Und prüfe aktuelle Anfrage: der Knoten "$.body.subject_token.body.nbf" ist nicht vorhanden oder früher als jetzt

    ## Request Body
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_id"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "${client_id}"

    # Fehlerhafte Signatur muss abgelehnt werden
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    # JWT-Payload ändern ohne Neusignierung => Signatur ungültig
    Und Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "body.jti" auf Wert "changed-jti" für Pfad ".*${paths.guard.tokenEndpointPath}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.body.jti" der mit "changed-jti" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

  @A_25762
  @A_25766
  @TA_A_25762_04
  @TA_A_25766_02
  Szenario: Nutzerauthentifizierung - Client sendet DPoP Header und Access Token
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "tokenNonce"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"

    # Token Response Validierung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_25762_04 - Response muss DPoP-gebundenen Token zurückgeben
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.token_type" überein mit "DPoP"

    # DPoP JWT Validierung
    Und TGR speichere Wert des Knotens "$.header.dpop" der aktuellen Anfrage in der Variable "dpopJwt"
    Und decodiere und validiere "${dpopJwt}" gegen Schema "schemas/v_1_0/dpop-token.yaml"
    Und verifiziere ES256 Signatur von DPoP JWT "${dpopJwt}"
    Und berechne JKT aus DPoP JWT "${dpopJwt}" und speichere in Variable "dpopJwtJkt"
    # @TA_A_25663_01 - Token Binding: cnf.jkt muss mit DPoP Public Key Thumbprint übereinstimmen
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "${dpopJwtJkt}"

    # DPoP Header Validierung
    # @TA_A_27802_10 - typ muss "dpop+jwt" sein
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.header.typ" überein mit "dpop+jwt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.header.alg" überein mit "ES256"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.header.jwk"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.header.jwk.kty"
    Und TGR speichere Wert des Knotens "$.header.dpop.header" der aktuellen Anfrage in der Variable "dpopHeader"
    Und prüfe dass jwk in "${dpopHeader}" keine privaten Key-Teile enthält

    # DPoP Payload Validierung
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.jti"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.htm"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.htu"
    Und TGR prüfe aktueller Request enthält Knoten "$.header.dpop.body.iat"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.htm" überein mit "POST"
    Und TGR speichere Wert des Knotens "$.header.X-Forwarded-Proto" der aktuellen Anfrage in der Variable "requestScheme"
    Und TGR speichere Wert des Knotens "$.header.X-Forwarded-Host" der aktuellen Anfrage in der Variable "requestHost"
    Und TGR ersetze ":443$" mit "" im Inhalt der Variable "requestHost"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "requestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.htu" überein mit "${requestScheme}://${requestHost}${requestPath}"
    Und TGR speichere Wert des Knotens "$.header.dpop.body.iat" der aktuellen Anfrage in der Variable "iat"
    Und prüfe dass Timestamp "${iat}" in der Vergangenheit liegt
    # @TA_A_27802_11 - nonce Validierung
    # Guard MUSS prüfen, dass DPoP nonce mit vom /nonce Endpoint ausgegebener nonce übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.nonce" überein mit "${tokenNonce}"


  @dev
  @A_25762
  @TA_A_25762_04
  Szenariogrundriss: Nutzerauthentifizierung mittels SMC-B (DPoP)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Hole einen Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.tokenEndpointPath}"

    # Manipulation mit JWK-Ersetzung - Signatur ist gültig, aber JWK ist von anderem Key
    # Der ZETA Guard prüft typ/nonce bevor er das JWK-Binding validiert
    Dann Setze im TigerProxy für JWT in "<JwtLocation>" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Vorladen der Nachrichten, damit die nachfolgende Suche nach dem manipulierten Wert schneller durchläuft
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "<JwtLocation>.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    # iat in der Vergangenheit (2023-12-01)
    # iat in der Zukunft (2050-01-01)
    Beispiele: Manipulationen
      | JwtLocation   | JwtField   | NeuerWert               | ResponseCode |
      | $.header.dpop | header.typ | JWD                     | 400          |
      | $.header.dpop | header.alg | RS256                   | 400          |
      | $.header.dpop | body.nonce | invalidNonceValue123    | 400          |
      | $.header.dpop | body.iat   | 1701432000              | 400          |
      | $.header.dpop | body.iat   | 2524608000              | 400          |
      | $.header.dpop | body.htm   | DELETE                  | 400          |
      | $.header.dpop | body.htu   | https://wrong.url/token | 400          |
