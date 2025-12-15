#
# #%L
# ZETA Testsuite
# %%
# (C) 2025 achelos GmbH, licensed for gematik GmbH
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

@UseCase_01_06
Funktionalität: Client_authentisierung_und_autorisierung_software_attest_SC_200

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "proxy" auf "${paths.tigerProxy.baseUrl}"

  @no_proxy
  @TA_A_26639_01
  Szenario: Ingress unterstützt WebSocket Verbindung
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Dann wird die WebSocket Verbindung geschlossen

  @staging
  @TA_A_28144_01
  @TA_A_28144_02
  @TA_A_28144_03
  Szenariogrundriss: Nonce am Endpunkt GET /nonce
    Gegeben sei <reset_step>
    Wenn <nonce_request_step>
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "NONCE"
    Und decodiere Base64Url "${NONCE}" und prüfe das die Länge 128 bit ist

    Beispiele: Integrationstest
      | reset_step                                                  | nonce_request_step                                              |
      | TGR sende eine leere GET Anfrage an "${paths.client.reset}" | TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" |

    @no_proxy
    Beispiele: Komponententest
      | reset_step                            | nonce_request_step                                                                           |
      | TGR lösche aufgezeichnete Nachrichten | TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.nonceEndpointPath}" |

  @staging
  @TA_A_28144_04
  Szenariogrundriss: Nonce ist zufällig
    # erste nonce
    Gegeben sei <first_reset>
    Wenn <first_nonce_request>
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "NONCE"
    # zweite nonce
    Gegeben sei <second_reset>
    Wenn <second_nonce_request>
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" nicht überein mit "${NONCE}"

    Beispiele: Integrationstest
      | first_reset                                                 | first_nonce_request                                             | second_reset                                                | second_nonce_request                                            |
      | TGR sende eine leere GET Anfrage an "${paths.client.reset}" | TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" | TGR sende eine leere GET Anfrage an "${paths.client.reset}" | TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" |

    @no_proxy
    Beispiele: Komponententest
      | first_reset                           | first_nonce_request                                                                          | second_reset                          | second_nonce_request                                                                         |
      | TGR lösche aufgezeichnete Nachrichten | TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.nonceEndpointPath}" | TGR lösche aufgezeichnete Nachrichten | TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.nonceEndpointPath}" |

  @dev
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_27007_07
  @TA_A_25661_01
  @TA_A_25760_03
  @TA_A_26944_01
  Szenario: Die Komponente Authorization Server MUSS Access Token mit Attributen gemäß [access-token.yaml] und Refresh Token ausstellen (Integrationstest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "JWT_TOKEN"
    Und decodiere und validiere "${JWT_TOKEN}" gegen Schema "schemas/v_1_0/access-token.yaml"
    # TA_A_25660_04, TA_A_25760_03 - Refresh Token muss vorhanden sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

  @no_proxy
  @component
  @dev
  @TA_A_25660_01
  @TA_A_27007_07
  @TA_A_25661_01
  @TA_A_26944_01
  Szenario: Die Komponente Authorization Server MUSS Access Token mit Attributen gemäß [access-token.yaml] ausstellen (Komponententest)
    Wenn Hole JWT von "${paths.guard.baseUrl}${paths.guard.tokenEndpointPath}" und speichere in der Variable "jwtToken"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" nicht überein mit "200"

  @dev
  @TA_A_25662_03
  Szenario: Neue Authentisierung nach Ablauf der Refresh Token Gültigkeitsdauer
    # Manipulationen stoppen (von vorherigen Tests)
    Gegeben sei Alle Manipulationen im TigerProxy "${proxy}" werden gestoppt

    # TTL-Werte als Variablen definieren (in Sekunden)
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "5"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "10"

    # OPA Decision manipulieren: Kurze TTL für Refresh Token setzen
    # Die OPA-Response bestimmt die tatsächliche Token-Gültigkeit im Authorization Server
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*/v1/data/zeta/authz/decision'"
    Dann Setze im TigerProxy "${proxy}" für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 3 Ausführungen
    Und Setze im TigerProxy "${proxy}" für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 3 Ausführungen

    # Client zurücksetzen und ersten Token holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "firstNonce"

    # Ersten Token Request validieren
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"

    # Nachrichten löschen um nur den nächsten Refresh-Request zu finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Warte bis Access Token abgelaufen ist (aber RT noch gültig)
    Und TGR warte "${accessTokenTtl}" Sekunden

    # Erste Anfrage sollte Refresh mit RT auslösen (RT noch gültig)
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Validiere dass Refresh durchgeführt wurde (nicht neue Authentisierung)
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "refresh_token" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Nachrichten löschen für die finale Phase
    Und TGR lösche aufgezeichnete Nachrichten

    # Warte bis auch das rotierte Refresh Token abgelaufen ist
    Und TGR warte "${refreshTokenTtl}" Sekunden

    # Zweite Anfrage sollte neue Authentisierung auslösen (weil RT abgelaufen)
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25662_03: Validiere dass neue Authentisierung erfolgt
    # Nachweis 1: Neuer Nonce Request wurde durchgeführt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Prüfe dass ein NEUER Nonce ausgegeben wurde
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" nicht überein mit "${firstNonce}"

    # TA_A_25662_03: Nachweis 2: Token Request verwendet grant_type=token-exchange (nicht refresh_token)
    # Bei abgelaufenem RT muss der Client eine neue Authentisierung durchführen (Token Exchange)
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Hinweis: grant_type ist URL-encoded, da der Request Body application/x-www-form-urlencoded ist
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"

  @dev
  @TA_A_25762_04
  @TA_A_25767_02
  @TA_A_25663_01
  @TA_A_27802_10
  @TA_A_27802_11
  Szenario: DPoP Token Request - Client sendet DPoP Header und erhält DPoP-gebundenen Access Token
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
    Und verifiziere Signatur von DPoP JWT "${dpopJwt}"
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
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "requestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.htu" überein mit "${requestScheme}://${requestHost}${requestPath}"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "tokenRequestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.htu.path" überein mit "${tokenRequestPath}"
    Und TGR speichere Wert des Knotens "$.header.dpop.body.iat" der aktuellen Anfrage in der Variable "iat"
    Und prüfe dass Timestamp "${iat}" innerhalb von 300 Sekunden liegt
    # @TA_A_27802_11 - nonce Validierung
    # Guard MUSS prüfen, dass DPoP nonce mit vom /nonce Endpoint ausgegebener nonce übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.dpop.body.nonce" überein mit "${tokenNonce}"

  @dev
  @TA_A_25762_04
  @TA_A_25767_02
  @TA_A_27802_10
  @TA_A_27802_11
  Szenariogrundriss: DPoP JWT Manipulation Test - Token Request
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Hole einen Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.tokenEndpointPath}"

    # Manipulation mit JWK-Ersetzung - Signatur ist gültig, aber JWK ist von anderem Key
    # Der ZETA Guard prüft typ/nonce bevor er das JWK-Binding validiert
    Dann Setze im TigerProxy "${proxy}" für JWT in "<JwtLocation>" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    Beispiele: Manipulationen
      | JwtLocation   | JwtField   | NeuerWert               | ResponseCode |
      | $.header.dpop | header.typ | JWT                     | 400          |
      | $.header.dpop | body.nonce | 1234567                 | 400          |
      | $.header.dpop | header.alg | RS999                   | 400          |
      | $.header.dpop | body.iat   | 1600000000              | 400          |
      | $.header.dpop | body.htm   | GET                     | 400          |
      | $.header.dpop | body.htu   | https://wrong.url/token | 400          |



  @dev
  @A_25644
  @A_27007
  @A_27802
  @TA_A_27007_01
  @TA_A_25644_06
  @TA_A_25762_05
  Szenario: Prüfe client-assertion-jwt im Token Exchange Request Body
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR gebe aktuelle Request als Rbel-Tree aus
    Und TGR gebe aktuelle Response als Rbel-Tree aus
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_id"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "CLIENT_ID"

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_JWT"
    # A_25762 - client assertion validiert gegen das Schema client-assertion-jwt.yaml
    Und decodiere und validiere "${CLIENT_ASSERTION_JWT}" gegen Schema "schemas/v_1_0/client-assertion-jwt.yaml" soft assert

    ## Client Assertion Header
    # A_27802 Algorithmus ist akzeptabel
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.alg" überein mit "ES256"
    # Client Assertion Typ: das Schema erfordert "jwt", allgemeine Convention ist "JWT"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.typ" überein mit "(?i)jwt"

    ## Client Assertion JWT Key
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.jwk.use" überein mit "sig"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.jwk.kty" überein mit "EC"
    Und verifiziere die Signatur des JWT "${CLIENT_ASSERTION_JWT}"

    ## Client Assertion Payload
    # A_27802 - issuer enthält den erwarteten Aussteller (== client_id)
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "${CLIENT_ID}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "${CLIENT_ID}"

    # A_27802: audience enthält den beabsichtigten Empfänger.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.aud.0" überein mit "https://${zeta_base_url}${paths.guard.tokenEndpointPath}"
    # A_27802: das token darf noch nicht abgelaufen sein.
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.exp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_EXP"
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_EXP}" in der Zukunft liegt
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_TIMESTAMP"
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_EXP}" später als "${CLIENT_ASSERTION_TIMESTAMP}" liegt


    ## Request Body
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion_type" überein mit "urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer"



  @dev
  @A_25644
  @TA_A_25644_06
  @TA_A_25644_07
  Szenario: Client Assertion JWT enthält Software Attestation für Windows/Linux
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_JWT"
    Und decodiere und validiere "${CLIENT_ASSERTION_JWT}" gegen Schema "client-assertion-jwt.yaml" soft assert

    # Client statement enthält Product Id, welche unterschiedlich ist für Windows/Linux
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.platform" überein mit "(windows|linux)"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement" der aktuellen Anfrage in der Variable "CLIENT_STATEMENT"
    Und validiere "${CLIENT_STATEMENT}" soft gegen Schema "schemas/v_1_0/client-statement.yaml"

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.attestation_timestamp"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.exp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_EXP"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_TIMESTAMP"
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_EXP}" später als "${CLIENT_ASSERTION_TIMESTAMP}" liegt
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_TIMESTAMP}" in der Vergangenheit liegt

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.public_key"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "[0-9a-zA-Z\-]{1,20}+"

    ## client assertion enthält client statement mit attestation_data und client_id
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "CLIENT_ID"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.sub" überein mit "${CLIENT_ID}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.urn:gematik:params:oauth:client-attestation:software"


  @dev
  @A_27802
  @TA_A_27802_01
  @TA_A_27802_02
  @TA_A_27802_03
  @TA_A_27802_04
  Szenariogrundriss: Client Assertion JWT Manipulation Test - Token Request
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Hole den Client Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR gebe aktuelle Response als Rbel-Tree aus

    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.tokenEndpointPath}"

    Dann Setze im TigerProxy "${proxy}" für JWT in "<JwtLocation>" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR gebe aktuelle Request als Rbel-Tree aus
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    # A_27802 - ZETA Guard, JWT Prüfung
    # - Header:
    # -- Algorithmus (alg)
    # - Payload:
    # -- Ablaufdatum (exp)
    # -- Audience (aud)
    # -- Issuer (iss)
    # -- Integrität: Hash-Prüfung
    # -- Schlüsselvalidierung
    Beispiele: Manipulationen
      | JwtLocation             | JwtField    | NeuerWert                             | ResponseCode |
      | $.body.client_assertion | header.typ  | dpop+jwt                              | 400          |
      | $.body.client_assertion | header.alg  | RS999                                 | 400          |
      | $.body.client_assertion | body.exp    | 1763370594                            | 400          |
      | $.body.client_assertion | body.aud.0  | https://wrong.url/token               | 400          |
      | $.body.client_assertion | body.iss    | evil_client                           | 400          |

  @TA_A_25650_02
  Szenario: PDP Client Registrierung - TI-Identität in Attestation - Bindung TelematikID
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und Alle Manipulationen im TigerProxy "${proxy}" werden gestoppt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_registration_by_auth_server.*.client_id" der aktuellen Antwort in der Variable "clientId"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe die Telematik ID aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "telematikId"

    # Prüfe die Client-ID und die Telematik ID in der Attestation
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.iss" überein mit "${clientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "${clientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "${clientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.sub" überein mit "${telematikId}"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.authorization.BearerToken.body.sub" überein mit "${telematikId}"
