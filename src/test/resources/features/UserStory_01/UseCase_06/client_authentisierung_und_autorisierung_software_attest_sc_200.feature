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

@UseCase_01_06
Funktionalität: Client Authentisierung und Autorisierung Software-Attest SC 200

  @A_29820
  @TA_A_29820_02
  @normal
  @smb
  Szenario: SMC-B Token Exchange übergibt das substanzielle Authentifizierungsniveau an die Policy Engine
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "CC_SMCB_CERTIFICATE"
    Und speichere die Telematik-ID aus SMC-B Zertifikat "${CC_SMCB_CERTIFICATE}" in Variable "CC_SMCB_TELEMATIK_ID"
    Und TGR prüfe Variable "CC_SMCB_TELEMATIK_ID" stimmt überein mit ".+"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.sub" überein mit "\Q${CC_SMCB_TELEMATIK_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.acr" überein mit "gematik-ehealth-loa-substantial"

  @Ignore
  @A_29993
  @TA_A_29993_02
  @normal
  @smb
  Szenario: Access Token verwendet den gematik-LoA-Wertebereich für acr
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.access_token.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "required": [
          "acr"
        ],
        "properties": {
          "acr": {
            "$ref": "classpath:schemas/v_2_0/schemas/access-token.yaml#/properties/payload/properties/acr"
          }
        },
        "additionalProperties": true
      }
      """

  @A_29951
  @TA_A_29951_01
  @normal
  @smb
  Szenariogrundriss: Token Exchange liefert eine schemakonforme Erfolgsantwort (<RequestedTokenType>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "issuedTypeRequestCondition" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    Und Setze im TigerProxy für die Nachricht "${issuedTypeRequestCondition}" die Regex-Manipulation auf Feld "$.body" mit Regex "requested_token_type=[^&]*" und Wert "requested_token_type=!{urlEncoded('<RequestedTokenType>')}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.requested_token_type" überein mit "!{urlEncoded('<RequestedTokenType>')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "allOf": [
          {
            "$ref": "classpath:schemas/v_2_0/schemas/token-response.yaml"
          },
          {
            "$ref": "classpath:schemas/v_2_0/schemas/token-response.yaml#/definitions/successResponse"
          }
        ],
        "properties": {
          "issued_token_type": {
            "$ref": "classpath:schemas/v_2_0/schemas/token-response.yaml#/definitions/successResponse/properties/issued_token_type"
          }
        },
        "additionalProperties": true
      }
      """

    Beispiele: Angeforderter Typ bei erfolgreichem Token Exchange
      | RequestedTokenType                          |
      | urn:ietf:params:oauth:token-type:access_token  |
      | urn:ietf:params:oauth:token-type:refresh_token |

  @A_26639
  @TA_A_26639_01
  @critical
  @no_proxy
  @websocket
  Szenario: Ingress unterstützt WebSocket Verbindung
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Dann wird die WebSocket Verbindung geschlossen

  @A_28432
  @TA_A_28432_01
  @require_kubectl
  Szenario: Ingress ist vorhanden und wird für den Client-Pfad verwendet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und ermittle aus Pods im Namespace "${zetaDeploymentConfig.namespace}" den Wert der Spalte "IP" für alle Zeilen mit "ingress" und speichere in der Variable "INGRESS_IPS"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body"
    Und TGR prüfe aktueller Request enthält Knoten "$.sender.domain"
    Und TGR speichere Wert des Knotens "$.sender.domain" der aktuellen Anfrage in der Variable "OBSERVED_IP"
    # exactly match quad-dotted IP in a string of comma separated IP addresses
    Und TGR prüfe Variable "INGRESS_IPS" stimmt überein mit "(?:[^,]+,\s*)*\Q${OBSERVED_IP}\E(?:,\s*[^,]+)*"

  @A_28144
  @TA_A_28144_01
  @TA_A_28144_02
  @TA_A_28144_03
  @MASVS-CRYPTO
  Szenariogrundriss: Nonce am Endpunkt GET /nonce (<testtyp>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "<request_url>"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "NONCE"
    Und decodiere Base64Url "${NONCE}" und prüfe, dass die Länge 128 bit ist

    @normal
    Beispiele: Integration
      | testtyp          | request_url               |
      | Integrationstest | ${paths.client.helloZeta} |

    @critical
    @no_proxy
    Beispiele: Komponententest
      | testtyp         | request_url                                            |
      | Komponententest | ${paths.guard.baseUrl}${paths.guard.nonceEndpointPath} |

  @A_28144
  @TA_A_28144_04
  @MASVS-CRYPTO
  Szenariogrundriss: Nonce ist zufällig (<testtyp>)
    # erste nonce
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "<request_url>"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "NONCE"
    # zweite nonce
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "<request_url>"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" nicht überein mit "${NONCE}"

    @normal
    Beispiele: Integration
      | testtyp          | request_url               |
      | Integrationstest | ${paths.client.helloZeta} |

    @critical
    @no_proxy
    Beispiele: Komponententest
      | testtyp         | request_url                                            |
      | Komponententest | ${paths.guard.baseUrl}${paths.guard.nonceEndpointPath} |


  @A_25660
  @A_25661-01
  @A_25760
  @A_26281-01
  @A_26661
  @A_26944
  @A_27007
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_25661-01_01
  @TA_A_25661-01_02
  @TA_A_25760_03
  @TA_A_26281-01_01
  @TA_A_26661_01
  @TA_A_26944_01
  @TA_A_27007_01
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @MASVS-CRYPTO
  Szenario: Die Komponente Authorization Server MUSS Access Token mit Attributen gemäß [access-token.yaml] und Refresh Token ausstellen
    Gegeben sei TGR sende eine leere "GET" Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "KEY_STORE"

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "JWT_TOKEN"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "REFRESH_TOKEN"
    Und decodiere und validiere "${JWT_TOKEN}" gegen Schema "schemas/v_2_0/schemas/access-token.yaml"
    Und verifiziere die ES256 Signatur des JWT "${JWT_TOKEN}" mit KeyStore "${KEY_STORE}"

    # TA_A_25660_04, TA_A_25760_03, TA_A_25661-01_02 - Refresh Token muss vorhanden sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und verifiziere die ES256 Signatur des JWT "${REFRESH_TOKEN}" mit KeyStore "${KEY_STORE}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_25739-02
  @TA_A_25739-02_04
  @normal
  @require_kubectl
  @no_proxy
  @MASVS-RESILIENCE
  Szenario: Gefordertes OPA Bundle Polling Intervall wird eingehalten
    Gegeben sei TGR setze lokale Variable "opaPoll" auf "${zetaDeploymentConfig.opa.pollingIntervalSeconds}"
    Dann prüfe, dass die aktiven und simulierten OPA Bundle Polling Intervalle "${opaPoll}" Sekunden betragen

  @A_25661-01
  @A_25739-02
  @A_26585-02
  @A_26586-01
  @A_27401
  @A_28527-01
  @A_28837
  @TA_A_25661-01_01
  @TA_A_25661-01_02
  @TA_A_25661-01_04
  @TA_A_25739-02_01
  @TA_A_26585-02_02
  @TA_A_26586-01_03
  @TA_A_26586-01_04
  @TA_A_27401_01
  @TA_A_28527-01_01
  @TA_A_28527-01_02
  @TA_A_28837_01
  @normal
  @MASVS-CODE
  @MASVS-RESILIENCE
  @MASVS-AUTH
  @smb
  Szenario: Policy Decision - Token Ausgabe mit korrekter Laufzeit gemäß OPA
    # TTL Werte als Variablen definieren
    Gegeben sei TGR setze lokale Variable "accessTokenTtl" auf "60"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "120"

    # OPA Response manipulieren: TTL Werte setzen
    # Die manipulierte OPA-Response bestimmt die tatsächliche Token-Gültigkeit im Authorization Server.
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CLIENT_ID"

    # TA_A_28837_01: Zum Token-Exchange-Request muss vor der Token-Ausgabe eine Policy-Engine-Anfrage erfolgen.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR speichere Wert des Knotens "$.body.scope" der aktuellen Anfrage in der Variable "TOKEN_REQUEST_SCOPES"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt erfolgt

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.input" der aktuellen Anfrage in der Variable "POLICY_ENGINE_INPUT"
    Und validiere "${POLICY_ENGINE_INPUT}" gegen Schema "schemas/v_2_0/schemas/policy-engine-input.yaml"

    # TA_A_26585-02_02: Client-Daten werden gemäß policy-engine-client-data.yaml an die Policy Engine weitergegeben
    Und TGR prüfe aktueller Request enthält Knoten "$.body.input.client_registration_data"
    Und TGR speichere Wert des Knotens "$.body.input.client_registration_data" der aktuellen Anfrage in der Variable "CLIENT_DATA"
    Und validiere "${CLIENT_DATA}" gegen Schema "schemas/v_2_0/schemas/policy-engine-client-data.yaml"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.input.client_registration_data.client_id"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.client_id" überein mit "${CLIENT_ID}"

    # TA_A_27401_01: OPA Response Schema-Validierung
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PDP_DECISION"
    Und validiere "${PDP_DECISION}" gegen Schema "schemas/v_2_0/schemas/pdp-decision.yaml"

    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.allow"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.ttl"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.ttl.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.ttl.refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.ttl.access_token" überein mit "${accessTokenTtl}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.ttl.refresh_token" überein mit "${refreshTokenTtl}"

    # TA_A_25739-02_01: Aktive OPA Instanz trifft Entscheidung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"

    # Token Response prüfen: Das ausgegebene Token-Set muss die zuvor gelieferte Policy-Engine-Decision berücksichtigen.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_25661-01_01: Access Token wird bei allow=true ausgegeben
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR speichere Wert des Knotens "$.body.access_token.body" der aktuellen Antwort in der Variable "ACCESS_TOKEN_CLAIMS"

    # TA_A_25661-01_02: Refresh Token wird bei allow=true ausgegeben
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body" der aktuellen Antwort in der Variable "REFRESH_TOKEN_CLAIMS"

    # TA_A_28527-01_01: Access Token Laufzeit gemäß Policy Engine
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.expires_in" überein mit "${accessTokenTtl}"
    # Tiger parst JWTs automatisch - prüfe TTL über exp - iat Differenz im JWT
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "accessExp"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.iat" der aktuellen Antwort in der Variable "accessIat"
    Und prüfe dass Token TTL zwischen exp="${accessExp}" und iat="${accessIat}" gleich "${accessTokenTtl}" Sekunden ist

    # TA_A_28527-01_02: Refresh Token Laufzeit gemäß Policy Engine
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_expires_in" überein mit "${refreshTokenTtl}"
    # Tiger parst JWTs automatisch - prüfe TTL über exp - iat Differenz im JWT
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "refreshExp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.iat" der aktuellen Antwort in der Variable "refreshIat"
    Und prüfe dass Token TTL zwischen exp="${refreshExp}" und iat="${refreshIat}" gleich "${refreshTokenTtl}" Sekunden ist

    # TA_A_25661-01_04: Access Token Claims passen zur Policy-Engine-Entscheidung.
    Dann prüfe Access-Token-Claims "${ACCESS_TOKEN_CLAIMS}" gegen Policy-Input "${POLICY_ENGINE_INPUT}", Entscheidung "${PDP_DECISION}" und Scopes aus Token-Request "${TOKEN_REQUEST_SCOPES}" mit soft assert

  @A_28440
  @TA_A_28440_01
  @normal
  Szenario: Client-IP Adresse wird aus Forwarded-Header übernommen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"

    Gegeben sei TGR setze lokale Variable "clientIp" auf "198.51.100.60"
    Und TGR setze lokale Variable "forwardedHeader" auf "for=${clientIp}"
    Und TGR setze lokale Variable "forwardedCondition" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    # Hinweis: A_28828: "Forwarded" ist bevorzugt, aber "X-Forwarded-For" und "X-Real-IP" sind ebenfalls zulässig, deshalb werden alle drei Header manipuliert
    Und Setze im TigerProxy für die Nachricht "${forwardedCondition}" die Manipulation auf Feld "${headers.forwarded.strict}" und Wert "${forwardedHeader}"
    Und Setze im TigerProxy für die Nachricht "${forwardedCondition}" die Manipulation auf Feld "${headers.xForwardedFor}" und Wert "${clientIp}"
    Und Setze im TigerProxy für die Nachricht "${forwardedCondition}" die Manipulation auf Feld "${headers.xRealIp}" und Wert "${clientIp}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.helloZetaPath}" übereinstimmt

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.input.authorization_request.ip_address"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.ip_address" überein mit "${clientIp}"


  @A_25649
  @A_25662
  @A_28808
  @TA_A_25649_01
  @TA_A_25662_03
  @TA_A_28808_05
  @MASVS-AUTH
  @MASVS-PRIVACY
  @smb
  Szenario: Neue Authentisierung nach Ablauf der Refresh Token Gültigkeitsdauer
    # TTL-Werte als Variablen definieren (in Sekunden)
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "30"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "100"
    # kleinen Puffer addieren, um Timing-Rennen zu vermeiden
    Und TGR setze lokale Variable "refreshTokenWait" auf "!{${refreshTokenTtl} + 2}"

    # OPA Decision manipulieren: Kurze TTL für Refresh Token setzen
    # Die OPA-Response bestimmt die tatsächliche Token-Gültigkeit im Authorization Server
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 3 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 3 Ausführungen

    # Client zurücksetzen und ersten Token holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Merke die Nonce für später
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "firstNonce"

    # Ersten Token Request validieren
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"

    # session_expiry = expiry vom ersten refresh_token
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"
    # sessionId speichern, um später neue Session nachzuweisen
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.sid"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "sessionId"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "${sessionId}"

    # Speichere attestationTimestamp und attestationChallenge zum späteren Vergleich
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.attestation_timestamp"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "attestationTimestamp1"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.attestation_challenge" der aktuellen Anfrage in der Variable "attestationChallenge1"

    # Warte bis das Refresh Token und damit session_expiry abgelaufen ist
    Und warte "${refreshTokenWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    # Nachrichten löschen für die finale Phase
    Und TGR lösche aufgezeichnete Nachrichten

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
    # Hinweis: grant_type ist URL-encoded, da der Request Body application/x-www-form-urlencoded ist
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_28808_05: Nach session_expiry wird eine neue Session verwendet
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "newSessionId"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "${newSessionId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" nicht überein mit "${sessionId}"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "newSessionExpiry"
    Und validiere, dass der Zeitstempel "${newSessionExpiry}" später als "${session_expiry}" liegt

    # TA_A_25649_01: Neue Session erfordert Client-Attestierung
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.attestation_timestamp"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "attestationTimestamp2"
    Und validiere, dass der Zeitstempel "${attestationTimestamp2}" später als "${attestationTimestamp1}" liegt
    Und TGR prüfe aktueller Request im Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge" stimmt nicht überein mit "\Q${attestationChallenge1}\E"

    # TA_A_28808_05: Die neue Authentisierung wird mit neuer Policy-Engine-Entscheidung ausgeführt
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.grant_type" überein mit "${oauth_parameters.grant_type.tokenExchange}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.input.user_info"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"

  @A_25663
  @A_25766
  @A_25767
  @TA_A_25663_01
  @TA_A_25766_02
  @TA_A_25767_02
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
  Szenario: DPoP Token Request - Client sendet DPoP Header und erhält DPoP-gebundenen Access Token
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "tokenNonce"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"

    # Token Response Validierung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.token_type" überein mit "DPoP"

    # DPoP JWT Validierung
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "dpopJwt"
    Und decodiere und validiere "${dpopJwt}" gegen Schema "schemas/v_2_0/schemas/dpop-token.yaml"
    Und verifiziere ES256 Signatur von DPoP JWT "${dpopJwt}"
    Und berechne JKT aus JWT Header JWK "${dpopJwt}" und speichere in Variable "dpopJwtJkt"
    # @TA_A_25663_01 - Token Binding: cnf.jkt muss mit DPoP Public Key Thumbprint übereinstimmen
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "${dpopJwtJkt}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" überein mit "${dpopJwtJkt}"

    # Subject Token bindet die Institution zusätzlich an den Client Instance Key.
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "clientAssertionJwt"
    Und berechne JKT aus JWT Header JWK "${clientAssertionJwt}" und speichere in Variable "clientAssertionJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.client_key.jkt" überein mit "${clientAssertionJkt}"

    # DPoP Header Validierung
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.typ}" überein mit "dpop+jwt"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.alg}" überein mit "ES256"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.header.jwk.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.header.jwk.kty}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.jwk.kty}" überein mit "EC"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.jwk.crv}" überein mit "P-256"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.jwk.x}" der aktuellen Anfrage in der Variable "dpopJwkX"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.jwk.y}" der aktuellen Anfrage in der Variable "dpopJwkY"
    Und decodiere Base64Url "${dpopJwkX}" und prüfe, dass die Länge 256 bit ist
    Und decodiere Base64Url "${dpopJwkY}" und prüfe, dass die Länge 256 bit ist
    Und TGR speichere Wert des Knotens "${headers.dpop.header.root}" der aktuellen Anfrage in der Variable "dpopHeader"
    Und prüfe dass jwk in "${dpopHeader}" keine privaten Key-Teile enthält

    # DPoP Payload Validierung
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.jti}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.htm}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.htu.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.iat}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htm}" überein mit "POST"
    Und TGR speichere Wert des Knotens "${headers.xForwardedProto}" der aktuellen Anfrage in der Variable "requestScheme"
    Und TGR speichere Wert des Knotens "${headers.xForwardedHost}" der aktuellen Anfrage in der Variable "requestHost"
    Und TGR ersetze ":443$" mit "" im Inhalt der Variable "requestHost"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "requestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "${requestScheme}://${requestHost}${requestPath}"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "tokenRequestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.path}" überein mit "${tokenRequestPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.body.iat}" der aktuellen Anfrage in der Variable "iat"
    Und validiere, dass der Zeitstempel "${iat}" in der Vergangenheit liegt
    # Der Client verwendet im DPoP Proof die vom /nonce Endpoint ausgegebene Nonce.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.nonce}" überein mit "${tokenNonce}"

  @A_25761
  @A_25766
  @A_25766
  @A_25767
  @A_26661
  @A_27007
  @A_27725-01
  @A_27802-03
  @TA_A_25761_02
  @TA_A_25766_01
  @TA_A_25767_01
  @TA_A_26661_01
  @TA_A_27007_01
  @TA_A_27725-01_01
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @normal
  @MASVS-CODE
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
  Szenario: Prüfe client-assertion-jwt im Token Exchange Request Body
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    # TA_A_26661_01, TA_A_27007_01: Authentifizierung mit Client Assertion JWT wird mit 200 OK abgeschlossen.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_id"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "CLIENT_ID"

    # TA_A_25761_02: Der Token Exchange Request enthält die private_key_jwt Client Assertion.
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_JWT"
    Und decodiere und validiere "${CLIENT_ASSERTION_JWT}" gegen Schema "schemas/v_2_0/schemas/client-assertion-jwt.yaml"

    ## Client Assertion Header
    # TA_A_27802-03_02: Der JWS-Algorithmus ist vorhanden und im Anwendungskontext akzeptabel.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.alg" überein mit "ES256"
    # TA_A_25767_01, TA_A_27802-03_01: Der JWT-Header weist die Client Assertion als JWT aus.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.typ" überein mit "(?i)jwt"

    ## Client Assertion JWT Key
    # TA_A_25766_01: Client-Credentials werden als kryptografischer Client Instance Key im JWT verwendet.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.jwk.use" überein mit "sig"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.jwk.kty" überein mit "EC"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.jwk.crv" überein mit "^(P-256|brainpoolP256r1)$"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.header.jwk.x" der aktuellen Anfrage in der Variable "clientAssertionJwkX"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.header.jwk.y" der aktuellen Anfrage in der Variable "clientAssertionJwkY"
    Und decodiere Base64Url "${clientAssertionJwkX}" und prüfe, dass die Länge 256 bit ist
    Und decodiere Base64Url "${clientAssertionJwkY}" und prüfe, dass die Länge 256 bit ist
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.client_assertion.header.jwk.d"
    # TA_A_25761_02: Die Client Assertion ist als signiertes JWT nachweisbar.
    Und verifiziere die ES256 Signatur des JWT "${CLIENT_ASSERTION_JWT}"

    ## Client Assertion Payload
    # TA_A_25767_01, TA_A_27802-03_01: Issuer und Subject binden die JWT Client Assertion an die Client-ID.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "${CLIENT_ID}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "${CLIENT_ID}"

    # TA_A_25767_01, TA_A_27802-03_01: Audience enthält den beabsichtigten Token-Endpunkt.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.aud.0" überein mit "https://${zeta_base_url}${paths.guard.tokenEndpointPath}"
    # TA_A_27802-03_01: Die Claims sind zeitlich gültig; das JWT darf nicht abgelaufen sein.
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.exp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_EXP"
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_EXP}" in der Zukunft liegt
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_TIMESTAMP"
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_EXP}" später als "${CLIENT_ASSERTION_TIMESTAMP}" liegt

    ## Token Exchange Request nach RFC 8693 mit JWT Client Authentication nach RFC 7523
    # TA_A_25761_02: Der Client nutzt OAuth2 Token Exchange am Token-Endpunkt.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "!{urlEncoded('${oauth_parameters.token_type.jwt}')}"
    # TA_A_25761_02: Die Client-Authentifizierung erfolgt als JWT Bearer Client Assertion.
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion_type" überein mit "!{urlEncoded('${oauth_parameters.client_assertion_type.jwtBearer}')}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    # TA_A_26661_01, TA_A_27007_01: Nach erfolgreicher Authentifizierung kann der Client mit der Operation fortfahren.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Telemetrie-Nachweis für TA_A_27725-01_01: erfolgreicher Token-Exchange ist per Jaeger auffindbar.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  |  operation                                  | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"200","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

  @A_27802-03
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @normal
  @MASVS-AUTH
  @MASVS-CODE
  @MASVS-CRYPTO
  Szenario: ZETA Guard akzeptiert ein gültiges Client Assertion JWT
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_JWT"
    Und verifiziere die ES256 Signatur des JWT "${CLIENT_ASSERTION_JWT}"

  @A_27802-03
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @normal
  @MASVS-AUTH
  @MASVS-CODE
  @MASVS-CRYPTO
  @smb
  Szenario: ZETA Guard akzeptiert eine Client Assertion mit unbekanntem nicht kritischem JOSE-Header
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "1"
    Und TGR setze lokale Variable "sessionTtl" auf "15"
    Und TGR setze lokale Variable "sessionExpiryWait" auf "!{${sessionTtl} + 2}"
    Und TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${sessionTtl}" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "access_token_expiry"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "tokenRequestCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    Und warte "${sessionExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${access_token_expiry}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "header.unknown_guard_parameter" auf Wert "unsupported" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${tokenRequestCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.unknown_guard_parameter" überein mit "unsupported"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.client_assertion.header.crit"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_JWT"
    Und verifiziere die ES256 Signatur des JWT "${CLIENT_ASSERTION_JWT}"
    Und verifiziere die ES256 Signatur des JWT "${CLIENT_ASSERTION_JWT}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CLIENT_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"

  @Ignore
  @A_25762-01
  @TA_A_25762-01_02
  @MASVS-AUTH
  @smb
  Szenario: Nutzerauthentifizierung mittels SM(C)-B signiertem Subject Token
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "tokenNonce"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_id"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "client_id"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "SUBJECT_TOKEN"
    # TA_A_25762-01_02: Schema-Validierung belegt die Pflichtstruktur, Signaturprüfung belegt das SM(C)-B signierte JWT.
    Und decodiere und validiere "${SUBJECT_TOKEN}" gegen Schema "schemas/v_2_0/schemas/subject-token-smb.yaml"
    Und verifiziere die ES256 Signatur des JWT "${SUBJECT_TOKEN}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"

    #[RFC 7523] - Werte auf Gültigkeit prüfen
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.alg" überein mit "ES256"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.typ" überein mit "JWT"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.exp" der aktuellen Anfrage in der Variable "subjectTokenExp"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.iat" der aktuellen Anfrage in der Variable "subjectTokenIat"
    Und validiere, dass der Zeitstempel "${subjectTokenExp}" später als "${subjectTokenIat}" liegt

    # Audience des Subject Tokens ist der Token Endpoint des Authorization Servers.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.aud.0" überein mit "https://${zeta_base_url}${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.nonce" überein mit "${tokenNonce}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.iss" überein mit "${client_id}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.sub" überein mit "${SMCB-INFO.telematikId}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.jti" der aktuellen Anfrage in der Variable "jti"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "dpopJwt"
    Und berechne JKT aus JWT Header JWK "${dpopJwt}" und speichere in Variable "dpopJwtJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" überein mit "${dpopJwtJkt}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "clientAssertionJwt"
    Und berechne JKT aus JWT Header JWK "${clientAssertionJwt}" und speichere in Variable "clientAssertionJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.client_key.jkt" überein mit "${clientAssertionJkt}"

    #[RFC7523] - Optionale Felder
    Und prüfe aktuelle Anfrage: der Knoten "$.body.subject_token.body.nbf" ist nicht vorhanden oder früher als jetzt

    ## Request Body
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "!{urlEncoded('${oauth_parameters.token_type.jwt}')}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_id"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "${client_id}"

  @Ignore
  @A_25645-01
  @A_26661
  @A_27802-03
  @TA_A_25645-01_01
  @TA_A_26661_01
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @MASVS-CRYPTO
  @smb
  Szenario: ZETA Guard akzeptiert Subject Token mit nicht spezifiziertem Payload typ Claim
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "SMCB_CERT"
    Und TGR setze lokale Variable "tokenExchangeCondition" auf ".*${paths.guard.tokenEndpointPath}"
    Und Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "body.typ" auf Wert "jwt" mit privatem Schlüssel "${SMCB_CERT.prv_pem}" für Pfad "${tokenExchangeCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR setze Anfrage Timeout auf 60 Sekunden
    Und TGR warte auf eine Nachricht, in der Knoten "$.body.subject_token.body.typ" mit "jwt" übereinstimmt
    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.body.typ" der mit "jwt" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.typ" überein mit "JWT"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "!{urlEncoded('${oauth_parameters.token_type.jwt}')}"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "SUBJECT_TOKEN"
    Und decodiere und validiere "${SUBJECT_TOKEN}" gegen Schema "schemas/v_2_0/schemas/subject-token-smb.yaml"
    Und verifiziere die ES256 Signatur des JWT "${SUBJECT_TOKEN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_27802-03
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @normal
  @MASVS-AUTH
  @MASVS-CODE
  @MASVS-CRYPTO
  @smb
  Szenario: ZETA Guard akzeptiert ein verschachteltes Client Assertion JWT mit cty JWT
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "1"
    Und TGR setze lokale Variable "sessionTtl" auf "15"
    Und TGR setze lokale Variable "sessionExpiryWait" auf "!{${sessionTtl} + 2}"
    Und TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${sessionTtl}" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.exp" der aktuellen Antwort in der Variable "access_token_expiry"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "tokenRequestCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    Und warte "${sessionExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${access_token_expiry}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "variant.name" auf Wert "nested_cty_jwt_valid_inner" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${tokenRequestCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.header.cty" überein mit "JWT"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "NESTED_CLIENT_ASSERTION"
    Und prüfe die JWT-Variante nested_cty_jwt_valid_inner in "${NESTED_CLIENT_ASSERTION}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante nested_cty_jwt_valid_inner in "${NESTED_CLIENT_ASSERTION}" hat lokal die erwartete Signaturintegrität
    Und prüfe die JWT-Variante nested_cty_jwt_valid_inner in "${NESTED_CLIENT_ASSERTION}" hat lokal mit öffentlichem Schlüssel aus privatem Schlüssel "${CLIENT_PRIVATE_KEY}" die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_25337
  @A_25338-01
  @A_25644-01
  @TA_A_25337_01
  @TA_A_25338-01_01
  @TA_A_25338-01_02
  @TA_A_25338-01_03
  @TA_A_25644-01_05
  @normal
  @MASVS-RESILIENCE
  @MASVS-AUTH
  @smb
  Szenario: Client Assertion JWT enthält Software Attestation für Linux
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # TA_A_25644-01_05: Client-ID und öffentlichen Schlüssel aus der erfolgreichen SDK-Registrierung erfassen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "SOFTWARE_CLIENT_ID"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "SOFTWARE_DCR_JWKS"
    # Positivkontrolle: Token Exchange derselben Client-Instanz einschließlich Schlüsselbindung und Policy-Ergebnis prüfen.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${SOFTWARE_CLIENT_ID}\E"
    Und prüfe die unabhängigen Nachweise des Software-Exchange zum registrierten JWKS "${SOFTWARE_DCR_JWKS}"
    Und prüfe das signierte Linux-Software-Statement ist schemakonform
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.public_key" der aktuellen Anfrage in der Variable "SOFTWARE_PUBLIC_KEY"
    Und prüfe Software-Schlüssel "${SOFTWARE_PUBLIC_KEY}" gehört zum registrierten JWKS "${SOFTWARE_DCR_JWKS}"

    # Die direkte Erfolgsantwort enthält Access- und Refresh-Token, aber kein Hardware-Attestierungstoken.
    Und prüfe aktuelle Antwort hat HTTP-Status 200 und nutze soft assert
    Und prüfe aktuelle Antwort im Knoten "$.body" stimmt als JSON_SCHEMA überein mit und nutze soft assert:
      """
      {
        "type": "object",
        "required": ["access_token", "refresh_token"],
        "properties": {
          "access_token": {"type": "string"},
          "refresh_token": {"type": "string"}
        },
        "not": {"required": ["zeta_attestation_token"]}
      }
      """

    # TA_A_25338-01_01 - Validierung gegen client-assertion-jwt.yaml
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_JWT"
    Und decodiere und validiere "${CLIENT_ASSERTION_JWT}" gegen Schema "schemas/v_2_0/schemas/client-assertion-jwt.yaml"
    Und verifiziere die ES256 Signatur des JWT "${CLIENT_ASSERTION_JWT}"
    # TA_A_25338-01_01 - Parameter "grant_type" gemäß gemSpec_ZETA#5.11.2.5.1,Step 6
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    # TA_A_25338-01_01 - Parameter "subject_token_type" gemäß gemSpec_ZETA#5.11.2.5.1,Step 6
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "!{urlEncoded('${oauth_parameters.token_type.jwt}')}"
    # TA_A_25338-01_01 - Parameter "client_assertion_type" gemäß gemSpec_ZETA#5.11.2.5.1,Step 6
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion_type"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion_type" überein mit "!{urlEncoded('${oauth_parameters.client_assertion_type.jwtBearer}')}"

    # Client statement enthält Product Id, welche unterschiedlich ist für Windows/Linux
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.platform" überein mit "linux"
    # TA_A_25644-01_05: Der Linux-Client verwendet ausdrücklich Software-Attestierung.
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture_type" überein mit "software"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement" der aktuellen Anfrage in der Variable "CLIENT_STATEMENT"
    Und validiere "${CLIENT_STATEMENT}" gegen Schema "schemas/v_2_0/schemas/client-statement.yaml"

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.attestation_timestamp"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.exp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_EXP"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_TIMESTAMP"
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_EXP}" später als "${CLIENT_ASSERTION_TIMESTAMP}" liegt
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_TIMESTAMP}" in der Vergangenheit liegt

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.public_key"
    # platform_product_id ist optional: Fehlen ist zulässig; ein vorhandener Wert muss dem Linux-Schema entsprechen.
    Und TGR prüfe aktueller Request im Knoten "$.body.client_assertion.body.client_statement.posture" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
          "platform_product_id": {
            "$ref": "classpath:schemas/v_2_0/schemas/product-id-linux.yaml"
          }
        }
      }
      """
    # TA_A_25337_01: product_id entspricht dem registrierten Produkt
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "PRODUCT_ID_INSTANCE_1"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "${testdata.product_id}"
    # TA_A_25338-01_02: product_id folgt dem erlaubten Format
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "${regex.product_id}"
    # TA_A_25338-01_03: product_version folgt dem erlaubten Format
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_version" überein mit "${regex.product_version}"

    ## client assertion enthält client statement mit attestation_data und client_id
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "CLIENT_ID"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.sub" überein mit "${CLIENT_ID}"

    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture" der aktuellen Anfrage in der Variable "POSTURE"
    Und validiere "${POSTURE}" gegen Schema "schemas/v_2_0/schemas/posture-software.yaml"

    # Policy-Daten mit den signierten Angaben dieser Client-Instanz vergleichen.
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "SOFTWARE_PRODUCT_VERSION"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.os" der aktuellen Anfrage in der Variable "SOFTWARE_OS"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.os_version" der aktuellen Anfrage in der Variable "SOFTWARE_OS_VERSION"
    Und wähle die einzige Policy-Transaktion des geprüften Software-Exchange
    Und prüfe aktuelle Antwort hat HTTP-Status 200 und nutze soft assert
    Und prüfe aktueller Request im Knoten "$.body.input.client_registration_data" stimmt als JSON_SCHEMA überein mit und nutze soft assert:
      """
      {
        "type": "object",
        "required": ["client_id", "platform", "posture_type", "product_id", "product_version", "device_info"],
        "properties": {
          "client_id": {"const": "${SOFTWARE_CLIENT_ID}"},
          "platform": {"const": "linux"},
          "posture_type": {"const": "software"},
          "product_id": {"const": "${PRODUCT_ID_INSTANCE_1}"},
          "product_version": {"const": "${SOFTWARE_PRODUCT_VERSION}"},
          "device_info": {
            "type": "object",
            "required": ["os", "os_version"],
            "properties": {
              "os": {"type": "string", "pattern": "(?i)^\\Q${SOFTWARE_OS}\\E$"},
              "os_version": {"const": "${SOFTWARE_OS_VERSION}"}
            }
          }
        }
      }
      """
    Und prüfe aktuelle Antwort im Knoten "$.body" stimmt als JSON_SCHEMA überein mit und nutze soft assert:
      """
      {
        "type": "object",
        "required": ["result"],
        "properties": {
          "result": {
            "type": "object",
            "required": ["allow"],
            "properties": {"allow": {"const": true}}
          }
        }
      }
      """

    # TA_A_25337_01: product_id wird in jeder Clientsystem-Instanz verwendet
    # Alten Verkehr entfernen, damit die zweite Instanz durch ihren eigenen Token Exchange nachgewiesen wird.
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "${PRODUCT_ID_INSTANCE_1}"

    # TA_A_25644-01_05: Für den Negativfall eine frische Instanz registrieren und deren Client-ID und JWKS erfassen.
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "INVALID_SOFTWARE_CLIENT_ID"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "INVALID_SOFTWARE_DCR_JWKS"
    # Den zu dieser Registrierung gehörenden privaten Schlüssel zum erneuten Signieren der Assertion verwenden.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "SOFTWARE_PRIVATE_KEY"
    # Nur das erforderliche os_version im ersten Exchange dieses Clients entfernen; Signatur und JWT-Header erhalten.
    Und TGR setze lokale Variable "softwareCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange' && message.body.client_id == '${INVALID_SOFTWARE_CLIENT_ID}'"
    Dann Entferne im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement.posture.os_version" mit privatem Schlüssel "${SOFTWARE_PRIVATE_KEY}" für Pfad "${softwareCondition}" und 1 Ausführungen und behalte den JWT-Header
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    # Den ersten manipulierten Exchange auswählen; spätere unmanipulierte SDK-Retries sind keine Negativnachweise.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${INVALID_SOFTWARE_CLIENT_ID}\E" übereinstimmt
    # Die isolierte Feldentfernung prüfen; alle Signaturen und Schlüsselbindungen bleiben gültig.
    Und prüfe die unabhängigen Nachweise des Software-Exchange zum registrierten JWKS "${INVALID_SOFTWARE_DCR_JWKS}"
    Und prüfe dem Linux-Software-Statement fehlt ausschließlich os_version bei ursprünglichem Wert "${SOFTWARE_OS_VERSION}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.public_key" der aktuellen Anfrage in der Variable "INVALID_SOFTWARE_PUBLIC_KEY"
    Und prüfe Software-Schlüssel "${INVALID_SOFTWARE_PUBLIC_KEY}" gehört zum registrierten JWKS "${INVALID_SOFTWARE_DCR_JWKS}"

    Und prüfe der Software-Exchange erzeugt keine Policy-Anfrage und nutze soft assert
    Und prüfe aktuelle Antwort hat HTTP-Status 400 und nutze soft assert
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "SOFTWARE_ERROR_RESPONSE"
    Und validiere "${SOFTWARE_ERROR_RESPONSE}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"
    Und prüfe aktuelle Antwort im Knoten "$.body" stimmt als JSON_SCHEMA überein mit und nutze soft assert:
      """
      {
        "type": "object",
        "not": {
          "anyOf": [
            {"required": ["access_token"]},
            {"required": ["refresh_token"]},
            {"required": ["id_token"]},
            {"required": ["zeta_attestation_token"]}
          ]
        }
      }
      """

  @Ignore
  @A_25644-01
  @TA_A_25644-01_03
  @normal
  @tpm_environment
  @MASVS-RESILIENCE
  @MASVS-AUTH
  Szenario: Client Assertion JWT enthält TPM Attestation für Linux
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_id"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "CLIENT_ID"

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_JWT"
    Und decodiere und validiere "${CLIENT_ASSERTION_JWT}" gegen Schema "schemas/v_2_0/schemas/client-assertion-jwt.yaml"
    Und verifiziere die ES256 Signatur des JWT "${CLIENT_ASSERTION_JWT}"

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.platform" überein mit "linux"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.sub" überein mit "${CLIENT_ID}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement" der aktuellen Anfrage in der Variable "CLIENT_STATEMENT"
    Und validiere "${CLIENT_STATEMENT}" gegen Schema "schemas/v_2_0/schemas/client-statement.yaml"

    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.attestation_timestamp"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.exp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_EXP"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.attestation_timestamp" der aktuellen Anfrage in der Variable "CLIENT_ASSERTION_TIMESTAMP"
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_EXP}" später als "${CLIENT_ASSERTION_TIMESTAMP}" liegt
    Und validiere, dass der Zeitstempel "${CLIENT_ASSERTION_TIMESTAMP}" in der Vergangenheit liegt
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture" der aktuellen Anfrage in der Variable "POSTURE"
    Und validiere "${POSTURE}" gegen Schema "schemas/v_2_0/schemas/posture-tpm.yaml"

  @A_25645-01
  @A_25650
  @TA_A_25645-01_01
  @TA_A_25650_02
  @normal
  @MASVS-AUTH
  @require_signing_key
  @smb
  Szenario: Die gespeicherte Telematik-ID bleibt bei unverändertem und manipuliertem Refresh gebunden
    # 1. Anmeldung: Client und Telematik-ID anhand des SMC-B-Zertifikats zuordnen.
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.[?(key =~ 'rt:.*')].body.client_id" der aktuellen Antwort in der Variable "clientId"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${clientId}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token_type" überein mit "!{urlEncoded('${oauth_parameters.token_type.jwt}')}"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "SUBJECT_TOKEN"
    Und decodiere und validiere "${SUBJECT_TOKEN}" gegen Schema "schemas/v_2_0/schemas/subject-token-smb.yaml"
    #TA_A_25645-01_01 SMCB Signaturprüfung
    Und verifiziere die ES256 Signatur des JWT "${SUBJECT_TOKEN}" mit Zertifikat aus x5c[0]
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"

    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.iss" überein mit "${clientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "${clientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "${clientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.sub" überein mit "${SMCB-INFO.telematikId}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.header.jwk.x" der aktuellen Anfrage in der Variable "BOUND_CLIENT_PUBLIC_X"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.registerEndpointPath}" und Knoten "$.body.jwks.keys.0.x" der mit "\Q${BOUND_CLIENT_PUBLIC_X}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.client_id" überein mit "\Q${clientId}\E"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # 2. Ausgangszustand: Identitätszuordnung und Signaturen der Tokens prüfen.
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${clientId}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${clientId}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${SMCB-INFO.telematikId}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "SMCB_STORED_SESSION_ID"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "SMCB_STORED_ACCESS_TOKEN"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sub" überein mit "\Q${SMCB-INFO.telematikId}\E"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "BOUND_INITIAL_REFRESH_TOKEN"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "SMCB_STORED_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${SMCB_STORED_ACCESS_TOKEN}" mit KeyStore "${SMCB_STORED_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${BOUND_INITIAL_REFRESH_TOKEN}" mit KeyStore "${SMCB_STORED_JWKS}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${clientId}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.identifier" überein mit "${SMCB-INFO.telematikId}"

    # 3. Kontrollanfrage: HTTP 401 löst einen unveränderten SDK-Refresh ohne Subject Token aus.
    # Genau eine Ressourcen-Antwort wird zu 401/invalid_token, damit das SDK trotz gültigem Access Token refresht.
    # In dieser Kontrollanfrage bleiben Refresh Token und AS-Antwort unverändert.
    Und TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}${paths.tigerProxy.modificationPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "name": "sdk-refresh-trigger",
        "condition": "isResponse && request.method == 'GET' && request.path == '${paths.guard.helloZetaPath}'",
        "targetElement": "$",
        "replaceWith": "HTTP/1.1 401 Unauthorized\r\nzeta-error-origin: pep\r\nWWW-Authenticate: DPoP error=\"invalid_token\"\r\nContent-Type: application/json\r\nContent-Length: 74\r\n\r\n{\"error\":\"invalid_token\",\"error_description\":\"Controlled refresh trigger\"}",
        "deleteAfterNExecutions": 1
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.tigerProxy.modificationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.refresh_token" der mit "\Q${BOUND_INITIAL_REFRESH_TOKEN}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${clientId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "\Q${BOUND_INITIAL_REFRESH_TOKEN}\E"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.subject_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${SMCB-INFO.telematikId}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${clientId}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" überein mit "\Q${SMCB_STORED_SESSION_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sub" überein mit "\Q${SMCB-INFO.telematikId}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.client_id" überein mit "\Q${clientId}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" überein mit "\Q${SMCB_STORED_SESSION_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token" nicht überein mit "\Q${BOUND_INITIAL_REFRESH_TOKEN}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "BOUND_CONTROL_ACCESS_TOKEN"
    # Das frisch rotierte Refresh Token für die zweite Probe verwenden, damit kein verbrauchtes Token getestet wird.
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "BOUND_CONTROL_REFRESH_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${BOUND_CONTROL_ACCESS_TOKEN}" mit KeyStore "${SMCB_STORED_JWKS}"
    Und verifiziere die ES256 Signatur des JWT "${BOUND_CONTROL_REFRESH_TOKEN}" mit KeyStore "${SMCB_STORED_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.header" der aktuellen Antwort in der Variable "BOUND_CONTROL_REFRESH_HEADER"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body" der aktuellen Antwort in der Variable "BOUND_EXPECTED_REFRESH_PAYLOAD"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.refresh_token.body.['iss'|'client_id'|'sid'|'jti'|'iat'|'exp'|'nbf'|'aud'|'scope'|'cnf']" stimmt nicht überein mit:
      """
      (?s).*\Q${SMCB-INFO.telematikId}\E.*
      """
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "BOUND_CONTROL_CLIENT_ASSERTION"
    Und verifiziere die ES256 Signatur des JWT "${BOUND_CONTROL_CLIENT_ASSERTION}"
    Und berechne JKT aus JWT Header JWK "${BOUND_CONTROL_CLIENT_ASSERTION}" und speichere in Variable "BOUND_CONTROL_CLIENT_JKT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.authorization_request.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.client_id" überein mit "\Q${clientId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.identifier" überein mit "\Q${SMCB-INFO.telematikId}\E"

    # 4. Negativanfrage: Alle TI-ID-Vorkommen im Refresh Token ersetzen und neu signieren, damit nur die Identität abweicht.
    Gegeben sei TGR setze lokale Variable "BOUND_FOREIGN_IDENTITY" auf "3-SMC-B-Testkarte-883110000129069"
    Und TGR setze lokale Variable "BOUND_SIGNING_KEY_FILE" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "BOUND_SIGNING_KEY" auf "!{file('${BOUND_SIGNING_KEY_FILE}')}"
    Und TGR prüfe Variable "BOUND_FOREIGN_IDENTITY" stimmt überein mit "(?!\Q${SMCB-INFO.telematikId}\E$)[A-Za-z0-9-]+"
    Und TGR ersetze "\Q${SMCB-INFO.telematikId}\E" mit "${BOUND_FOREIGN_IDENTITY}" im Inhalt der Variable "BOUND_EXPECTED_REFRESH_PAYLOAD"
    Und TGR prüfe Variable "BOUND_EXPECTED_REFRESH_PAYLOAD" stimmt überein mit "(?s)(?!.*\Q${SMCB-INFO.telematikId}\E).*"
    Und TGR prüfe Variable "BOUND_CONTROL_REFRESH_HEADER" stimmt überein mit "(?s)(?!.*\Q${SMCB-INFO.telematikId}\E).*"
    # Mit dem AS-Testschlüssel neu signieren, damit eine ungültige Signatur die Identitätsprüfung nicht verdeckt.
    Und signiere Payload "${BOUND_EXPECTED_REFRESH_PAYLOAD}" mit Header aus JWT "${BOUND_CONTROL_REFRESH_TOKEN}" und privatem Schlüssel "${BOUND_SIGNING_KEY}" und speichere in Variable "BOUND_FORGED_REFRESH_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${BOUND_FORGED_REFRESH_TOKEN}" mit KeyStore "${SMCB_STORED_JWKS}"
    Und TGR setze lokale Variable "BOUND_REFRESH_CONDITION" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    # Nur den refresh_token-Parameter ersetzen; die Regel gilt auch für SDK-Wiederholungen, maximal 20-mal.
    Und Setze im TigerProxy für die Nachricht "${BOUND_REFRESH_CONDITION}" die Regex-Manipulation auf Feld "$.body" mit Regex "(^|&)refresh_token=[^&]*" und Wert "$1refresh_token=${BOUND_FORGED_REFRESH_TOKEN}" und 20 Ausführungen
    # Erneut genau eine Ressourcen-Antwort auf 401/invalid_token setzen, um den zweiten SDK-Refresh auszulösen.
    # Dabei wird das manipulierte Refresh Token gesendet; die AS-Antwort bleibt unverändert.
    Und TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}${paths.tigerProxy.modificationPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "name": "sdk-refresh-trigger",
        "condition": "isResponse && request.method == 'GET' && request.path == '${paths.guard.helloZetaPath}'",
        "targetElement": "$",
        "replaceWith": "HTTP/1.1 401 Unauthorized\r\nzeta-error-origin: pep\r\nWWW-Authenticate: DPoP error=\"invalid_token\"\r\nContent-Type: application/json\r\nContent-Length: 74\r\n\r\n{\"error\":\"invalid_token\",\"error_description\":\"Controlled refresh trigger\"}",
        "deleteAfterNExecutions": 1
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.tigerProxy.modificationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt

    # 5. Manipulation prüfen: Tatsächlich gesendetes Token, Signaturen und Schlüsselbindung kontrollieren.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.refresh_token" der mit "\Q${BOUND_FORGED_REFRESH_TOKEN}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "${oauth_parameters.grant_type.refreshToken}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${clientId}\E"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.subject_token"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "\Q${BOUND_FORGED_REFRESH_TOKEN}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token.body.sub" überein mit "\Q${BOUND_FOREIGN_IDENTITY}\E"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Anfrage in der Variable "BOUND_REFRESH_EXP"
    Und validiere, dass der Zeitstempel "${BOUND_REFRESH_EXP}" in der Zukunft liegt
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Anfrage in der Variable "BOUND_OBSERVED_REFRESH_TOKEN"
    Und TGR prüfe aktueller Request im Knoten "$.body.refresh_token.header" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"const": ${BOUND_CONTROL_REFRESH_HEADER}}
      """
    Und TGR prüfe aktueller Request im Knoten "$.body.refresh_token.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {"const": ${BOUND_EXPECTED_REFRESH_PAYLOAD}}
      """
    Und verifiziere die ES256 Signatur des JWT "${BOUND_OBSERVED_REFRESH_TOKEN}" mit KeyStore "${SMCB_STORED_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "BOUND_REFRESH_CLIENT_ASSERTION"
    Und verifiziere die ES256 Signatur des JWT "${BOUND_REFRESH_CLIENT_ASSERTION}"
    Und berechne JKT aus JWT Header JWK "${BOUND_REFRESH_CLIENT_ASSERTION}" und speichere in Variable "BOUND_REFRESH_CLIENT_JKT"
    Und TGR prüfe Variable "BOUND_REFRESH_CLIENT_JKT" stimmt überein mit "\Q${BOUND_CONTROL_CLIENT_JKT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "\Q${clientId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${clientId}\E"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "BOUND_REFRESH_DPOP"
    Und verifiziere ES256 Signatur von DPoP JWT "${BOUND_REFRESH_DPOP}"
    Und berechne JKT aus JWT Header JWK "${BOUND_REFRESH_DPOP}" und speichere in Variable "BOUND_REFRESH_DPOP_JKT"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token.body.cnf.jkt" überein mit "\Q${BOUND_REFRESH_DPOP_JKT}\E"

    # 6. Bindung prüfen: Ablehnung ohne Tokens oder neue Tokens nur für die ursprüngliche Identität und Client-Session.
    Und TGR speichere Wert des Knotens "$.responseCode" der aktuellen Antwort in der Variable "BOUND_CHANGED_STATUS"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "BOUND_CHANGED_RESPONSE"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200|4[0-9]{2}"
    Und TGR setze lokale Variable "BOUND_RESPONSE_KIND" auf "!{'${BOUND_CHANGED_STATUS}' == '200' ? 'success' : 'rejection'}"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$ref": "#/$defs/${BOUND_RESPONSE_KIND}",
        "$defs": {
          "success": {
            "type": "object",
            "required": ["access_token", "refresh_token"],
            "properties": {
              "access_token": {"type": "string", "minLength": 1},
              "refresh_token": {"type": "string", "minLength": 1}
            }
          },
          "rejection": {
            "type": "object",
            "properties": {
              "error": {"not": {"enum": ["invalid_client", "invalid_dpop_proof", "use_dpop_nonce"]}}
            },
            "not": {"anyOf": [
              {"required": ["access_token"]},
              {"required": ["refresh_token"]},
              {"required": ["zeta_attestation_token"]}
            ]}
          }
        }
      }
      """
    # Die Claim-Prüfungen gelten bei HTTP 200; bei Ablehnung erzwingt das Schema oben eine Antwort ohne Tokens.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.[?('${BOUND_CHANGED_STATUS}' != '200' || $.body.access_token.body.sub == '${SMCB-INFO.telematikId}')]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.[?('${BOUND_CHANGED_STATUS}' != '200' || $.body.access_token.body.client_id == '${clientId}')]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.[?('${BOUND_CHANGED_STATUS}' != '200' || $.body.access_token.body.sid == '${SMCB_STORED_SESSION_ID}')]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.[?('${BOUND_CHANGED_STATUS}' != '200' || $.body.refresh_token.body.sub == '${SMCB-INFO.telematikId}')]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.[?('${BOUND_CHANGED_STATUS}' != '200' || $.body.refresh_token.body.client_id == '${clientId}')]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.[?('${BOUND_CHANGED_STATUS}' != '200' || $.body.refresh_token.body.sid == '${SMCB_STORED_SESSION_ID}')]"
    Und verifiziere die ES256-Signaturen der vorhandenen JWT-Felder "access_token,refresh_token" in JSON "${BOUND_CHANGED_RESPONSE}" mit KeyStore "${SMCB_STORED_JWKS}"
