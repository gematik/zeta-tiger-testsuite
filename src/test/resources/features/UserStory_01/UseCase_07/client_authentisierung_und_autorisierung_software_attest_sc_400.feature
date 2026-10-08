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

@UseCase_01_07
Funktionalität: Client Authentisierung und Autorisierung Software-Attest SC 400

  @A_25767
  @A_26661
  @A_27007
  @A_28963
  @TA_A_25767_02
  @TA_A_26661_02
  @TA_A_27007_02
  @TA_A_28963_01
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenariogrundriss: DPoP JWT Manipulation Test - Token Request - 1 (<JwtField>)
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
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    # Ablauf der Session erzwingt eine neue vollständige Authentifizierung per token-exchange für ein Access Token.
    Und warte "${sessionExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    # Manipulation wird neu signiert, damit die geänderten Felder validiert werden.
    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und berechne JKT aus JWT Header JWK "${manipulatedDpopJwt}" und speichere in Variable "manipulatedDpopJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" überein mit "${manipulatedDpopJkt}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.nonce" der aktuellen Anfrage in der Variable "subjectTokenNonce"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.nonce}" <NonceVergleich> mit "${subjectTokenNonce}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

    # iat in der Vergangenheit (2023-12-01)
    # iat in der Zukunft (2050-01-01)
    Beispiele: Manipulationen
      | JwtField   | NeuerWert               | NonceVergleich   |
      | header.typ | JWT                     | überein           |
      | body.nonce | 1234567                 | nicht überein     |
      | header.alg | HS256                   | überein           |
      | header.alg | RS999                   | überein           |
      | body.iat   | 1701432000              | überein           |
      | body.iat   | 2524608000              | überein           |
      | body.htm   | GET                     | überein           |
      | body.htu   | https://wrong.url/token | überein           |

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft einen DPoP Proof mit Header-Algorithmus none am Token-Endpunkt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "variant.name" auf Wert "alg_none" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.header.alg}" der mit "none" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante alg_none in "${manipulatedDpopJwt}" ist lokal strukturell angewendet
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft einen DPoP Proof mit fremdem JWK und unveränderter Signatur am Token-Endpunkt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.jwk.x}" der aktuellen Anfrage in der Variable "foreignTokenRequestDpopJwkX"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.tokenEndpointPath}"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "header.jwk.x" auf Wert "${foreignTokenRequestDpopJwkX}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    # Nach reset verwendet der Client ein neues DPoP Keypair; der eingesetzte fremde JWK-Anteil passt damit nicht mehr zur Signatur.
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.header.jwk.x}" der mit "${foreignTokenRequestDpopJwkX}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedTokenRequestDpopJwt"
    Und prüfe die JWT-Variante invalid_signature in "${manipulatedTokenRequestDpopJwt}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard verwirft einen DPoP Proof ohne Pflicht-Claim <Claim> am Token-Endpunkt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"

    Dann Entferne im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.<Claim>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.dpop.body.<Claim>}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${manipulatedDpopJwt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

    Beispiele: Fehlende Pflicht-Claims
      | Claim |
      | jti   |
      | htm   |
      | htu   |
      | iat   |

  # TODO: Bei Bedarf später nach erwarteten ResponseCodes aufsplitten und in passende UseCases verteilen.
  @A_25767
  @A_26661
  @A_27007
  @A_28963
  @TA_A_25767_02
  @TA_A_26661_02
  @TA_A_27007_02
  @TA_A_28963_01
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenariogrundriss: DPoP JWT Manipulation Test - Token Request - 2 (<JwtField>)
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
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    # Hole einen Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    # Ablauf der Session erzwingt eine neue vollständige Authentifizierung per token-exchange für ein Access Token.
    Und warte "${sessionExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    # Manipulation mit JWK-Ersetzung und Signatur über den aktuellen DPoP Key.
    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und berechne JKT aus JWT Header JWK "${manipulatedDpopJwt}" und speichere in Variable "manipulatedDpopJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" überein mit "${manipulatedDpopJkt}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.nonce" der aktuellen Anfrage in der Variable "subjectTokenNonce"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.nonce}" <NonceVergleich> mit "${subjectTokenNonce}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

    # iat in der Vergangenheit (2023-12-01)
    # iat in der Zukunft (2050-01-01)
    Beispiele: Manipulationen
      | JwtField   | NeuerWert               | NonceVergleich   |
      | header.typ | JWD                     | überein           |
      | header.alg | RS256                   | überein           |
      | body.nonce | invalidNonceValue123    | nicht überein     |
      | body.iat   | 1701432000              | überein           |
      | body.iat   | 2524608000              | überein           |
      | body.htm   | DELETE                  | überein           |
      | body.htu   | https://wrong.url/token | überein           |

  @A_28963
  @TA_A_28963_01
  @minor
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard verwirft einen fehlerhaften oder nicht akzeptierten DPoP Proof am Token-Endpunkt (<Variante>)
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
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    # Ablauf der Session erzwingt eine neue vollständige Authentifizierung per token-exchange für ein Access Token.
    Und warte "${sessionExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "variant.name" auf Wert "<Variante>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

    Beispiele: manipulierte JWT-Varianten
      | Variante                           |
      | two_segments                       |
      | invalid_header_base64url           |
      | invalid_payload_base64url          |
      | invalid_signature_base64url        |
      | invalid_header_json                |
      | invalid_header_json_unquoted_keys  |
      | invalid_payload_json               |
      | invalid_payload_json_unquoted_keys |
      | unsupported_alg                    |
      | missing_alg                        |
      | duplicate_alg_headers              |
      | invalid_signature                  |
      | jwe_like_five_segments             |
      | nested_cty_jwt_invalid_inner       |

  @A_27802-02
  @TA_A_27802-02_01
  @TA_A_27802-02_02
  @A_28963
  @TA_A_28963_01
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard verwirft einen DPoP Proof mit nicht unterstütztem JOSE Header am Token-Endpunkt (<Variante>)
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
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    # Ablauf der Session erzwingt eine neue vollständige Authentifizierung per token-exchange für ein Access Token.
    Und warte "${sessionExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "variant.name" auf Wert "<Variante>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "<SuchKnoten>" der mit "<SuchWert>" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

    @normal
    Beispiele: unknown_header_parameter
      | Variante                 | SuchKnoten                                               | SuchWert          |
      | unknown_header_parameter | $.header.[~'DPoP'].header.unknown_guard_parameter       | unsupported       |

    @normal
    Beispiele: unsupported_crit
      | Variante                 | SuchKnoten                                               | SuchWert          |
      | unsupported_crit         | $.header.[~'DPoP'].header.unsupported_guard_parameter   | requested-by-crit |

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft doppelte DPoP Header am Token-Endpunkt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    Und Dupliziere im TigerProxy für die Nachricht "${pathCondition}" den Header "DPoP"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und prüfe aktuelle Anfrage enthält den Header "DPoP" 2 mal
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft einen DPoP Proof mit privatem JWK Anteil am Token-Endpunkt
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
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "initialDpopJwt"
    Und berechne JKT aus JWT Header JWK "${initialDpopJwt}" und speichere in Variable "initialDpopJkt"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    # Ablauf der Session erzwingt eine neue vollständige Authentifizierung per token-exchange für ein Access Token.
    Und warte "${sessionExpiryWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "header.jwk.d" auf Wert "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.header.jwk.d}" der mit "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und verifiziere die ES256 Signatur des JWT "${manipulatedDpopJwt}" mit öffentlichem Schlüssel aus privatem Schlüssel "${dpopKey}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'DPoP'].header.jwk.kid" überein mit "${initialDpopJkt}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" überein mit "${initialDpopJkt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft am Token-Endpunkt die Wiederverwendung desselben jti
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "1"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "15"
    Und TGR setze lokale Variable "refreshTokenWait" auf "!{${refreshTokenTtl} + 2}"
    Und TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 2 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 2 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und erzeuge eindeutige DPoP jti und speichere in Variable "tokenReplayJti"

    Und warte "${refreshTokenWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt
    Und TGR lösche aufgezeichnete Nachrichten

    Und TGR setze lokale Variable "tokenExchangeCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"
    Und Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.jti" auf Wert "${tokenReplayJti}" mit privatem Schlüssel "${dpopKey}" für Pfad "${tokenExchangeCondition}" und 1 Ausführungen und ersetze JWK

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.body.jti}" der mit "${tokenReplayJti}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.jti}" überein mit "${tokenReplayJti}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR setze lokale Variable "tokenEndpointCondition" auf ".*${paths.guard.tokenEndpointPath}"
    Und Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.jti" auf Wert "${tokenReplayJti}" mit privatem Schlüssel "${dpopKey}" für Pfad "${tokenEndpointCondition}" und 1 Ausführungen und ersetze JWK

    Und warte "2" Sekunden
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.body.jti}" der mit "${tokenReplayJti}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.jti}" überein mit "${tokenReplayJti}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  # TODO: Dieser Szenariogrundriss bündelt Varianten mit unterschiedlichen erwarteten Statuscodes.
  # Eine spätere Aufteilung nach Zielstatuscode und passendem UseCase sollte geprüft werden.
  @A_27802-02
  @TA_A_27802-02_01
  @TA_A_27802-02_02
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard verwirft fehlerhafte Client Assertion JWT Varianten (<Variante>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "tokenRequestCondition" auf ".*${paths.guard.tokenEndpointPath}"

    # RFC 7519, Abschnitt 7.2:
    # - Punkt-separierte JWT-Serialisierung
    # - base64url-decodierbarer und JSON-parsbarer Header
    # - JWS/JWE-Unterscheidung und verschachteltes JWT via cty=JWT
    # - base64url-decodierbare und interpretierbare Claims-Menge
    #
    # RFC 7515, Abschnitt 5.2:
    # - decodierbare JWS-Segmente
    # - doppelte / unbekannte / kritische Header-Parameter
    # - vorhandenes und akzeptables alg
    # - Signaturprüfung über den Signing Input
    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "variant.name" auf Wert "<Variante>" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${tokenRequestCondition}" und 1 Ausführungen und ersetze JWK

    Und TGR lösche aufgezeichnete Nachrichten
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "MANIPULATED_CLIENT_ASSERTION"
    Und prüfe die JWT-Variante <Variante> in "${MANIPULATED_CLIENT_ASSERTION}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${MANIPULATED_CLIENT_ASSERTION}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.error_description"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error_description" nicht überein mit ".*client_id parameter does not match sub claim.*"

    Beispiele: JWT und JWS Fehlervarianten
      | Variante                           | ResponseCode |
      | two_segments                       | 400          |
      | invalid_header_base64url           | 400          |
      | invalid_header_json                | 400          |
      | invalid_header_json_unquoted_keys  | 400          |
      | jwe_like_five_segments             | 400          |
      | nested_cty_jwt_invalid_inner       | 400          |
      | invalid_payload_base64url          | 400          |
      | invalid_payload_json               | 400          |
      | invalid_payload_json_unquoted_keys | 400          |
      | unknown_header_parameter           | 400          |
      | invalid_signature_base64url        | 400          |
      | unsupported_crit                   | 400          |
      | unsupported_alg                    | 400          |
      | duplicate_alg_headers              | 400          |
      | missing_alg                        | 400          |
      | invalid_signature                  | 401          |

  # TODO: Dieser Szenariogrundriss bündelt Varianten mit unterschiedlichen erwarteten Statuscodes.
  # Eine spätere Aufteilung nach Zielstatuscode und passendem UseCase sollte geprüft werden.
  @A_27725-01
  @A_27802-02
  @TA_A_27802-02_01
  @TA_A_27802-02_02
  @MASVS-CRYPTO
  @MASVS-RESILIENCE
  Szenariogrundriss: Client Assertion JWT Manipulation Test - Token Request (<JwtField>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    # Löse Generieren des Client Key aus
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"

    # Hole den Client Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.tokenEndpointPath}"

    Dann Setze im TigerProxy für JWT in "<JwtLocation>" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "<JwtLocation>.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"<ResponseCode>","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

    # A_27802-02 - ZETA Guard, JWT Prüfung
    # - Header:
    # -- Algorithmus (alg)
    # - Payload:
    # -- Ablaufdatum (exp) => 401 bei abgelaufenem JWT (gemSpec_ZETA#Tabelle_12)
    # -- Audience (aud)
    # -- Issuer (iss)
    # -- Integrität: Hash-Prüfung
    # -- Schlüsselvalidierung
    @TA_A_27725-01_02
    Beispiele: Manipulationen mit HTTP 400
      | JwtLocation             | JwtField   | NeuerWert               | ResponseCode |
      | $.body.client_assertion | header.typ | helloWorld              | 400          |
      | $.body.client_assertion | header.alg | RS999                   | 400          |
      | $.body.client_assertion | body.aud.0 | https://wrong.url/token | 400          |

    @TA_A_27725-01_03
    Beispiele: Manipulationen mit HTTP 401
      | JwtLocation             | JwtField | NeuerWert  | ResponseCode |
      | $.body.client_assertion | body.exp | 1763370594 | 401          |

  @A_25649
  @TA_A_25649_01
  @MASVS-AUTH
  Szenario: Neue Session ohne neue Attestation (Negativtest)
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


    # Ersten Token Request validieren
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"

    # session_expiry = expiry vom ersten refresh_token
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    # Entferne client_statement aus der Client Assertion für die nächste Token-Exchange-Anfrage
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"
    Dann Entferne im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    # Warte bis das Refresh Token und damit session_expiry abgelaufen ist
    Und warte "${refreshTokenWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    # Nachrichten löschen für die finale Phase
    Und TGR lösche aufgezeichnete Nachrichten

    # Zweite Anfrage sollte neue Authentisierung auslösen (weil RT abgelaufen)
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Bei abgelaufenem RT muss der Client eine neue Authentisierung durchführen (Token Exchange).
    # Der erwartete Spezifikationswert wird für die RBEL-Darstellung des Formular-Requests aufgelöst
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt

    # TA_A_25649_01: Die neue Session wird ohne die erforderliche Client-Attestierung abgelehnt.
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.client_assertion.body.client_statement"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_26661
  @TA_A_26661_02
  Szenario: Neue Session mit client_statement als String wird abgelehnt (Negativtest)
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

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Ablaufzeitpunkt der ersten Session speichern
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    # client_statement für den nächsten Token Exchange durch einen String ersetzen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"
    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement" auf Wert "{}" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    # Warte bis das Refresh Token und damit session_expiry abgelaufen ist
    Und warte "${refreshTokenWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Die fehlerhafte Client Assertion wird als ungültige Anfrage abgelehnt.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement" überein mit "{}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_25644-01
  @TA_A_25644-01_03
  @MASVS-AUTH
  @tpm_environment
  Szenario: TPM Attestation - ungültige Quote wird abgelehnt (Negativtest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.register}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement.posture.tpm_quote" auf Wert "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_assertion.body.client_statement.posture.tpm_quote" der mit "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"


  @A_25644-01
  @TA_A_25644-01_05
  @MASVS-AUTH
  Szenario: Plattformwechsel nach Ablauf des Refresh Tokens wird abgelehnt (Negativtest)
    # TTL-Werte als Variablen definieren (in Sekunden)
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "5"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "15"
    # kleinen Puffer addieren, um Timing-Rennen zu vermeiden
    Und TGR setze lokale Variable "refreshTokenWait" auf "!{${refreshTokenTtl} + 2}"

    # OPA Decision manipulieren: Kurze TTL für Refresh Token setzen
    # Die OPA-Response bestimmt die tatsächliche Token-Gültigkeit im Authorization Server
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen

    # Client zurücksetzen und ersten Token holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Ersten Token Request validieren
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.platform" überein mit "linux"

    # session_expiry = expiry vom ersten refresh_token
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    # Warte bis das Refresh Token und damit session_expiry abgelaufen ist
    Und warte "${refreshTokenWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    # Nachrichten löschen für die finale Phase
    Und TGR lösche aufgezeichnete Nachrichten

    # Hole den Client Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement.platform" auf Wert "windows" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    # Zweite Anfrage sollte neue Authentisierung auslösen (weil RT abgelaufen)
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_assertion.body.client_statement.platform" der mit "windows" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
