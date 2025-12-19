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
  @TA_A_25661_02
  @TA_A_25760_03
  @TA_A_26944_01
  Szenario: Die Komponente Authorization Server MUSS Access Token mit Attributen gemäß [access-token.yaml] und Refresh Token ausstellen (Integrationstest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "JWT_TOKEN"
    Und decodiere und validiere "${JWT_TOKEN}" gegen Schema "schemas/v_1_0/access-token.yaml"
    # TA_A_25660_04, TA_A_25760_03, TA_A_25661_02 - Refresh Token muss vorhanden sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

  @dev
  @TA_A_25664_01
  @TA_A_25664_02
  @TA_A_25661_01
  @TA_A_25661_02
  @TA_A_27401_01
  @TA_A_25739_01
  Szenario: Policy Decision - Token Ausgabe mit korrekter Laufzeit gemäß OPA
    Gegeben sei Alle Manipulationen im TigerProxy "${proxy}" werden gestoppt

    # TTL Werte als Variablen definieren
    Und TGR setze lokale Variable "accessTokenTtl" auf "60"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "120"

    # OPA Response manipulieren: TTL Werte setzen
    # Diese Manipulation wirkt nur auf OPA-Responses (Pfad $.body.result.ttl existiert nur dort)
    Dann Setze im TigerProxy "${proxy}" für die Nachricht "isResponse" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy "${proxy}" für die Nachricht "isResponse" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_27401_01: OPA Response Schema-Validierung
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PDP_DECISION"
    Und validiere "${PDP_DECISION}" soft gegen Schema "schemas/v_1_0/pdp-decision.yaml"

    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.allow"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.ttl"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.ttl.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.ttl.refresh_token"

    # TA_A_25739_01: Aktive OPA Instanz trifft Entscheidung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"

    # Token Response prüfen
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25661_01: Access Token wird bei allow=true ausgegeben
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"

    # TA_A_25661_02: Refresh Token wird bei allow=true ausgegeben
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

    # TA_A_25664_01: Access Token Laufzeit gemäß Policy Engine
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.expires_in" überein mit "${accessTokenTtl}"
    # Tiger parst JWTs automatisch - prüfe TTL über exp - iat Differenz im JWT
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "accessExp"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.iat" der aktuellen Antwort in der Variable "accessIat"
    Und prüfe dass Token TTL zwischen exp="${accessExp}" und iat="${accessIat}" gleich "${accessTokenTtl}" Sekunden ist

    # TA_A_25664_02: Refresh Token Laufzeit gemäß Policy Engine
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_expires_in" überein mit "${refreshTokenTtl}"
    # Refresh Token ist intern JWT (A_26945: "opaque" = keine Struktur-Vorgabe für Client, aber Server kann JWT verwenden)
    # Tiger parst JWTs automatisch - prüfe TTL über exp - iat Differenz im JWT
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "refreshExp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.iat" der aktuellen Antwort in der Variable "refreshIat"
    Und prüfe dass Token TTL zwischen exp="${refreshExp}" und iat="${refreshIat}" gleich "${refreshTokenTtl}" Sekunden ist


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
    Und warte "${accessTokenTtl}" Sekunden

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
    Und warte "${refreshTokenTtl}" Sekunden

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
    Und prüfe dass Timestamp "${iat}" in der Vergangenheit liegt
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
  @A_25337
  @A_25338
  @A_25644
  @TA_A_25644_06
  @TA_A_25644_07
  @TA_A_25337_01
  @TA_A_25338_02
  @TA_A_25338_03
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
    # TA_A_25337_01
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "${testdata.product_id}"
    # TA_A_25338_02
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "${regex.product_id}"
    # TA_A_25338_03
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_version" überein mit "${regex.product_version}"

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

  @A_25650
  @A_25645
  @TA_A_25650_02
  @TA_A_25645_01
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
