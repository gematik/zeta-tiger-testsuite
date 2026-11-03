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
  @A_28963
  @TA_A_25767_02
  @TA_A_28963_01
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
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
    Und TGR prüfe aktueller Request <NonceKnotenPruefung> "${headers.dpop.body.nonce}" <NonceVergleich> mit "\Q${subjectTokenNonce}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

    # iat in der Vergangenheit (2023-12-01)
    # iat in der Zukunft (2050-01-01)
    Beispiele: Manipulationen
      | JwtField   | NeuerWert               | NonceKnotenPruefung | NonceVergleich       |
      | header.typ | JWT                     | stimmt im Knoten    | überein              |
      | body.nonce | 1234567                 | im Knoten           | stimmt nicht überein |
      | header.alg | HS256                   | stimmt im Knoten    | überein              |
      | header.alg | RS999                   | stimmt im Knoten    | überein              |
      | body.iat   | 1701432000              | stimmt im Knoten    | überein              |
      | body.iat   | 2524608000              | stimmt im Knoten    | überein              |
      | body.htm   | GET                     | stimmt im Knoten    | überein              |
      | body.htu   | https://wrong.url/token | stimmt im Knoten    | überein              |

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-AUTH
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @MASVS-AUTH
  @MASVS-CRYPTO
  @smb
  Szenario: ZETA Guard verwirft einen DPoP Proof mit beschädigter Signatur und unverändertem JWK am Token-Endpunkt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "validTokenRequestDpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${validTokenRequestDpopJwt}"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "variant.name" auf Wert "signature_bit_flip" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedTokenRequestDpopJwt"
    Und prüfe die JWT-Variante invalid_signature in "${manipulatedTokenRequestDpopJwt}" hat lokal die erwartete Signaturintegrität
    Und berechne JKT aus JWT Header JWK "${manipulatedTokenRequestDpopJwt}" und speichere in Variable "manipulatedTokenRequestDpopJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" überein mit "${manipulatedTokenRequestDpopJkt}"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "signatureTestSubjectToken"
    Und verifiziere die ES256 Signatur des JWT "${signatureTestSubjectToken}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.body.nonce" der aktuellen Anfrage in der Variable "signatureTestSubjectNonce"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.nonce}" überein mit "\Q${signatureTestSubjectNonce}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-AUTH
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

    Beispiele: Fehlende Pflicht-Claims
      | Claim |
      | jti   |
      | htm   |
      | htu   |
      | iat   |

  # TODO: Bei Bedarf später nach erwarteten ResponseCodes aufsplitten und in passende UseCases verteilen.
  @A_25767
  @A_28963
  @TA_A_25767_02
  @TA_A_28963_01
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
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
    Und TGR prüfe aktueller Request <NonceKnotenPruefung> "${headers.dpop.body.nonce}" <NonceVergleich> mit "\Q${subjectTokenNonce}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

    # iat in der Vergangenheit (2023-12-01)
    # iat in der Zukunft (2050-01-01)
    Beispiele: Manipulationen
      | JwtField   | NeuerWert               | NonceKnotenPruefung | NonceVergleich       |
      | header.typ | JWD                     | stimmt im Knoten    | überein              |
      | header.alg | RS256                   | stimmt im Knoten    | überein              |
      | body.nonce | invalidNonceValue123    | im Knoten           | stimmt nicht überein |
      | body.iat   | 1701432000              | stimmt im Knoten    | überein              |
      | body.iat   | 2524608000              | stimmt im Knoten    | überein              |
      | body.htm   | DELETE                  | stimmt im Knoten    | überein              |
      | body.htu   | https://wrong.url/token | stimmt im Knoten    | überein              |

  @A_28963
  @TA_A_28963_01
  @minor
  @MASVS-AUTH
  @MASVS-CRYPTO
  @smb
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

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" die Variante <Variante> mit Parameter '<Parameter>' mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" mit Parameter '<Parameter>' ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

    Beispiele: manipulierte JWT-Varianten
      | Variante                           | Parameter         |
      | two_segments                       |                   |
      | invalid_header_base64url           |                   |
      | invalid_payload_base64url          |                   |
      | invalid_signature_base64url        |                   |
      | invalid_header_json                |                   |
      | invalid_header_json_unquoted_keys  |                   |
      | invalid_payload_json               |                   |
      | invalid_payload_json_unquoted_keys |                   |
      | unsupported_alg                    |                   |
      | missing_alg                        |                   |
      | duplicate_alg_headers              | ["ES256","RS999"] |
      | invalid_signature                  |                   |
      | jwe_like_five_segments             |                   |
      | nested_cty_jwt_invalid_inner       |                   |

  @A_27802-03
  @A_28963
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @TA_A_28963_01
  @MASVS-AUTH
  @MASVS-CODE
  @MASVS-CRYPTO
  @smb
  Szenariogrundriss: ZETA Guard prüft einen DPoP Proof mit unbekannter JOSE-Erweiterung am Token-Endpunkt (<Variante>)
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
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.header.root}.<HeaderParameter>" der mit "<HeaderWert>" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktueller Request <CritPruefung> Knoten "${headers.dpop.header.root}.crit"
    Und berechne JKT aus JWT Header JWK "${manipulatedDpopJwt}" und speichere in Variable "manipulatedDpopJkt"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" überein mit "${manipulatedDpopJkt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.<AntwortFeld>"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "if": { "required": ["error"] },
        "then": { "$ref": "classpath:schemas/v_2_0/schemas/zeta-error.yaml" }
      }
      """

    @normal
    Beispiele: unknown_header_parameter
      | Variante                 | HeaderParameter             | HeaderWert        | CritPruefung  | ResponseCode | AntwortFeld  |
      | unknown_header_parameter | unknown_guard_parameter     | unsupported       | enthält nicht | 200          | access_token |

    @normal
    Beispiele: unsupported_crit
      | Variante                 | HeaderParameter             | HeaderWert        | CritPruefung  | ResponseCode | AntwortFeld  |
      | unsupported_crit         | unsupported_guard_parameter | requested-by-crit | enthält       | 400          | error        |

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-AUTH
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-AUTH
  @MASVS-CRYPTO
  @smb
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-AUTH
  @MASVS-CRYPTO
  @smb
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  # TODO: Dieser Szenariogrundriss bündelt Varianten mit unterschiedlichen erwarteten Statuscodes.
  # Eine spätere Aufteilung nach Zielstatuscode und passendem UseCase sollte geprüft werden.
  @A_27802-03
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @MASVS-AUTH
  @MASVS-CODE
  @MASVS-CRYPTO
  @smb
  Szenariogrundriss: ZETA Guard verwirft fehlerhafte Client Assertion JWT Varianten (<Variante>)
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

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" die Variante <Variante> mit Parameter '<Parameter>' mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${tokenRequestCondition}" und 1 Ausführungen und ersetze JWK

    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "MANIPULATED_CLIENT_ASSERTION"
    Und prüfe die JWT-Variante <Variante> in "${MANIPULATED_CLIENT_ASSERTION}" mit Parameter '<Parameter>' ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${MANIPULATED_CLIENT_ASSERTION}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

    Beispiele: JWT und JWS Fehlervarianten
      | Variante                           | ResponseCode | Parameter         |
      | two_segments                       | 400          |                   |
      | invalid_header_base64url           | 400          |                   |
      | invalid_header_json                | 400          |                   |
      | invalid_header_json_unquoted_keys  | 400          |                   |
      | jwe_like_five_segments             | 400          |                   |
      | nested_cty_jwt_invalid_inner       | 400          |                   |
      | invalid_payload_base64url          | 400          |                   |
      | invalid_payload_json               | 400          |                   |
      | invalid_payload_json_unquoted_keys | 400          |                   |
      | invalid_signature_base64url        | 400          |                   |
      | unsupported_crit                   | 400          |                   |
      | unsupported_alg                    | 400          |                   |
      | duplicate_alg_headers              | 400          | ["ES256","RS999"] |
      | missing_alg                        | 400          |                   |
      | invalid_signature                  | 401          |                   |

  # TODO: Dieser Szenariogrundriss bündelt Varianten mit unterschiedlichen erwarteten Statuscodes.
  # Eine spätere Aufteilung nach Zielstatuscode und passendem UseCase sollte geprüft werden.
  @A_27725-01
  @A_27802-03
  @TA_A_27802-03_01
  @TA_A_27802-03_02
  @MASVS-AUTH
  @MASVS-CODE
  @MASVS-CRYPTO
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

    # A_27802-03 - ZETA Guard, JWT Prüfung
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
  @smb
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  @A_26662
  @TA_A_26662_01
  @smb
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  @A_25644-01
  @TA_A_25644-01_03
  @MASVS-RESILIENCE
  @MASVS-AUTH
  @tpm_environment
  @Ignore
  @smb
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"


  @A_25644-01
  @TA_A_25644-01_05
  @MASVS-RESILIENCE
  @MASVS-AUTH
  @smb
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
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"


  @A_25645-01
  @A_25661-01
  @A_26662
  @TA_A_25645-01_01
  @TA_A_25661-01_08
  @TA_A_26662_01
  @A_26661
  @TA_A_26661_02
  @A_27007
  @TA_A_27007_02
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @smb
  Szenariogrundriss: ZETA Guard verweigert Token Exchange bei falschem <Binding> Binding im Subject Token
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "SMCB_CERT"
    Und TGR setze lokale Variable "tokenExchangeCondition" auf ".*${paths.guard.tokenEndpointPath}"
    Und Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "<JwtField>" auf Wert "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" mit privatem Schlüssel "${SMCB_CERT.prv_pem}" für Pfad "${tokenExchangeCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.<JwtField>" der mit "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" übereinstimmt
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "SUBJECT_BINDING_CLIENT"
    Dann TGR finde die erste Nachricht mit Knoten "$.body.client_id" der mit "\Q${SUBJECT_BINDING_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.path" überein mit ".*\Q${paths.guard.registerEndpointPath}\E"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "LEGACY_SUBJECT_BINDING_JWKS"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.<JwtField>" der mit "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${SUBJECT_BINDING_CLIENT}\E"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "dpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${dpopJwt}"
    Und berechne JKT aus JWT Header JWK "${dpopJwt}" und speichere in Variable "dpopJkt"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "clientAssertionJwt"
    Und verifiziere die ES256 Signatur des JWT "${clientAssertionJwt}"
    Und berechne JKT aus JWT Header JWK "${clientAssertionJwt}" und speichere in Variable "clientAssertionJkt"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "manipulatedSubjectToken"
    Und verifiziere die ES256 Signatur des JWT "${manipulatedSubjectToken}"
    Und TGR prüfe aktueller Request im Knoten "$.body.subject_token.<JwtField>" stimmt nicht überein mit "\Q<ErwarteterJkt>\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.<UnveraendertesBindingField>" überein mit "<UnveraenderterJkt>"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler <IsolierterFehler> bei registriertem JWKS "${LEGACY_SUBJECT_BINDING_JWKS}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "invalid_grant"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

    Beispiele: subject_token_bindings
      | Binding    | JwtField            | ErwarteterJkt          | UnveraendertesBindingField | UnveraenderterJkt       | IsolierterFehler |
      | DPoP Key   | body.dpop_key.jkt   | ${dpopJkt}             | body.client_key.jkt        | ${clientAssertionJkt}   | dpop_key         |
      | Client Key | body.client_key.jkt | ${clientAssertionJkt}  | body.dpop_key.jkt          | ${dpopJkt}              | client_key       |

  @A_25645-01
  @A_25661-01
  @A_26662
  @TA_A_25645-01_01
  @TA_A_25661-01_08
  @TA_A_26662_01
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @smb
  Szenario: ZETA Guard verweigert Token Exchange mit DPoP Key aus vorheriger Client-Session
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "attackerDpopKey"

    Wenn TGR setze lokale Variable "tokenExchangeCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"
    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.jti" auf Wert "attacker-session-token-jti" mit privatem Schlüssel "${attackerDpopKey}" für Pfad "${tokenExchangeCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "LEGACY_BINDING_JWKS"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.body.jti}" der mit "attacker-session-token-jti" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "dpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${dpopJwt}"
    Und berechne JKT aus JWT Header JWK "${dpopJwt}" und speichere in Variable "attackerDpopJkt"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "subjectToken"
    Und verifiziere die ES256 Signatur des JWT "${subjectToken}"
    Und TGR prüfe aktueller Request im Knoten "$.body.subject_token.body.dpop_key.jkt" stimmt nicht überein mit "\Q${attackerDpopJkt}\E"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler dpop_key bei registriertem JWKS "${LEGACY_BINDING_JWKS}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "invalid_grant"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_2_0/schemas/zeta-error.yaml"

  @A_25661-01
  @TA_A_25661-01_15
  @require_kubectl
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  @smb
  Szenario: ZETA Guard fordert bei fehlender DPoP-Nonce eine neue Nonce an
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "5"
    Und TGR setze lokale Variable "sessionTtl" auf "15"
    Und TGR setze lokale Variable "sessionExpiryWait" auf "!{${sessionTtl} + 2}"
    Und TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${sessionTtl}" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_CHALLENGE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_CHALLENGE_CLIENT"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_CHALLENGE_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${CC_CHALLENGE_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "CC_CHALLENGE_SMCB_CERT"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "CC_CHALLENGE_DPOP_KEY"

    Und warte "${sessionExpiryWait}" Sekunden
    Und TGR setze lokale Variable "tokenExchangeCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"
    Und Entferne im TigerProxy für JWT in "${headers.dpop.root}" das Feld "body.nonce" mit privatem Schlüssel "${CC_CHALLENGE_DPOP_KEY}" für Pfad "${tokenExchangeCondition}" und behalte den JWT-Header
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.x5c.0" überein mit "\Q${CC_CHALLENGE_SMCB_CERT}\E"
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat
    Und alle aufgezeichneten SMC-B-Exchanges für Client "${CC_CHALLENGE_CLIENT}" mit JWKS "${CC_CHALLENGE_JWKS}" und isoliertem Fehler dpop_nonce_missing liefern Status 400 und Fehler use_dpop_nonce ohne Tokens
    Und Alle Manipulationen im TigerProxy werden gestoppt

  @A_25644-01
  @A_25661-01
  @TA_A_25661-01_12
  @TA_A_25644-01_05
  @normal
  @MASVS-CODE
  @MASVS-RESILIENCE
  @MASVS-AUTH
  @smb
  Szenario: Software Attestation (Linux) - fehlende nonce wird abgelehnt (Negativtest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    # Entfernt die Posture-Nonce auch dann, wenn zukünftige SDK-Versionen sie mitsenden.
    # Ist das Feld bereits abwesend, bleibt es abwesend; die Assertion wird neu signiert.
    Dann Entferne im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement.posture.nonce" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt

    # Die einmalige Manipulation trifft den ersten Token-Exchange; SDK-Retries nicht auswählen.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.client_assertion.body.client_statement.posture.nonce"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "MANIPULATED_CLIENT_ASSERTION"
    Und verifiziere die ES256 Signatur des JWT "${MANIPULATED_CLIENT_ASSERTION}" mit öffentlichem Schlüssel aus privatem Schlüssel "${CLIENT_PRIVATE_KEY}"

    # Software-Posture und Linux-Produkt-ID am tatsächlich manipulierten Token-Request nachweisen.
    Und TGR prüfe aktuelle Anfrage im Knoten "$.body.client_assertion.body.client_statement" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "object",
        "required": ["platform", "posture_type", "posture"],
        "properties": {
          "platform": { "const": "linux" },
          "posture_type": { "const": "software" },
          "posture": {
            "allOf": [
              { "$ref": "classpath:schemas/v_2_0/schemas/posture-software.yaml" }
            ],
            "required": ["platform_product_id"],
            "properties": {
              "platform_product_id": {
                "allOf": [
                  { "$ref": "classpath:schemas/v_2_0/schemas/product-id-linux.yaml" }
                ],
                "required": ["platform"],
                "properties": {
                  "platform": { "const": "linux" }
                }
              }
            }
          }
        }
      }
      """

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "allOf": [
          { "$ref": "classpath:schemas/v_2_0/schemas/zeta-error.yaml" }
        ],
        "properties": {
          "error": { "const": "invalid_request" },
          "access_token": false,
          "refresh_token": false,
          "id_token": false,
          "zeta_attestation_token": false
        }
      }
      """
