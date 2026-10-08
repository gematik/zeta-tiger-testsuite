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

@UseCase_01_08
Funktionalität: Client Authentisierung und Autorisierung Software-Attest SC 401

  @A_25783-01
  @A_27007
  @TA_A_25783-01_02
  @TA_A_27007_03
  @normal
  @MASVS-AUTH
  Szenario: Erneute Authentifizierung nach 401 Unauthorized
    Gegeben sei TGR setze lokale Variable "unauthorizedCondition" auf "isResponse && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    Und Setze im TigerProxy für die Nachricht "${unauthorizedCondition}" die Manipulation auf Feld "$.responseCode" und Wert "401" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Erste Token-Anfrage mit 401 (manipuliert)
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    # TA_A_27007_03 - ZETA Client - HTTP Statuscodes - Authentifizierung mit Client Assertion JWT - 401 Unauthorized
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    # TA_A_25783-01_02 - Anweisung befolgen Re-Autorisierung - Authorization Server
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "firstSubjectToken"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "firstClientAssertion"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.attestation_challenge" der aktuellen Anfrage in der Variable "firstAttestationChallenge"

    # Nonce-Anfrage nach 401
    Und TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"

    # Zweiter Token-Request (Retry)
    Und TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    # TA_A_25783-01_02 - Anweisung befolgen Re-Autorisierung - Authorization Server
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token" nicht überein mit "${firstSubjectToken}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion" nicht überein mit "${firstClientAssertion}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge" nicht überein mit "${firstAttestationChallenge}"

  @A_26661
  @A_27802-02
  @TA_A_26661_03
  @TA_A_27802-02_01
  @TA_A_27802-02_02
  @normal
  @MASVS-CRYPTO
  Szenario: Client Assertion JWT Manipulation Test - Token Request mit fremdem issuer
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "5"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "15"
    Und TGR setze lokale Variable "refreshTokenWait" auf "!{${refreshTokenTtl} + 2}"
    Und TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token.body.exp"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    Und warte "${refreshTokenWait}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.iss" auf Wert "evil_client" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_assertion.body.iss" der mit "evil_client" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    #@TA_A_26661_03
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @A_26661
  @TA_A_26661_03
  @MASVS-AUTH
  Szenario: Nutzerauthentifizierung mittels SM(C)-B signiertem Subject Token mit manipulierter Signatur wird abgelehnt (Negativtest)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    # JWT-Payload ändern ohne Neusignierung => Signatur ungültig
    Und Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "body.jti" auf Wert "changed-jti" für Pfad ".*${paths.guard.tokenEndpointPath}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.body.jti" der mit "changed-jti" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @A_27802-02
  @TA_A_27802-02_01
  @TA_A_27802-02_02
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard verwirft fehlerhafte Subject Token JWT Varianten
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "SMCB_CERT"
    Und TGR setze lokale Variable "tokenExchangeCondition" auf ".*${paths.guard.tokenEndpointPath}"
    Und Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${SMCB_CERT.prv_pem}" für Pfad "${tokenExchangeCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.subject_token"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "malformedSubjectToken"
    Und prüfe die JWT-Variante <Variante> in "${malformedSubjectToken}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${malformedSubjectToken}" hat lokal die erwartete Signaturintegrität
    Und prüfe die JWT-Variante <Variante> in "${malformedSubjectToken}" hat lokal mit eingebettetem Zertifikat die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    @normal
    Beispiele: resign_correct_key
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | resign_correct_key                 | header.alg   | ES256                              | 200          |

    @normal
    Beispiele: two_segments
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | two_segments                       | variant.name | two_segments                       | 400          |

    @normal
    Beispiele: invalid_header_base64url
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_header_base64url           | variant.name | invalid_header_base64url           | 400          |

    @normal
    Beispiele: invalid_payload_base64url
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_payload_base64url          | variant.name | invalid_payload_base64url          | 400          |

    @normal
    Beispiele: invalid_signature_base64url
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_signature_base64url        | variant.name | invalid_signature_base64url        | 400          |

    @normal
    Beispiele: invalid_header_json
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_header_json                | variant.name | invalid_header_json                | 400          |

    @normal
    Beispiele: invalid_header_json_unquoted_keys
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_header_json_unquoted_keys  | variant.name | invalid_header_json_unquoted_keys  | 400          |

    @normal
    Beispiele: invalid_payload_json
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_payload_json               | variant.name | invalid_payload_json               | 400          |

    @normal
    Beispiele: invalid_payload_json_unquoted_keys
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_payload_json_unquoted_keys | variant.name | invalid_payload_json_unquoted_keys | 400          |

    @normal
    Beispiele: unknown_header_parameter
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | unknown_header_parameter           | variant.name | unknown_header_parameter           | 400          |

    @normal
    Beispiele: unsupported_crit
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | unsupported_crit                   | variant.name | unsupported_crit                   | 400          |

    @normal
    Beispiele: unsupported_alg
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | unsupported_alg                    | variant.name | unsupported_alg                    | 400          |

    @normal
    Beispiele: missing_alg
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | missing_alg                        | variant.name | missing_alg                        | 400          |

    @normal
    Beispiele: duplicate_alg_headers
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | duplicate_alg_headers              | variant.name | duplicate_alg_headers              | 400          |

    @normal
    Beispiele: invalid_signature
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | invalid_signature                  | variant.name | invalid_signature                  | 401          |

    @normal
    Beispiele: jwe_like_five_segments
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | jwe_like_five_segments             | variant.name | jwe_like_five_segments             | 400          |

    @normal
    Beispiele: nested_cty_jwt_invalid_inner
      | Variante                           | JwtField     | NeuerWert                          | ResponseCode |
      | nested_cty_jwt_invalid_inner       | variant.name | nested_cty_jwt_invalid_inner       | 400          |
