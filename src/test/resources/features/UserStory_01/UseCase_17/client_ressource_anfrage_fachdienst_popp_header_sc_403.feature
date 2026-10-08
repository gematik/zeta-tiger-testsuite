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

@UseCase_01_17
Funktionalität: Client Ressource Anfrage Fachdienst PoPP-Header SC 403

  # Gemäß Fachaustausch vom 02.07.2026 prüft der PoPP-Verifier des ZETA Guard
  # ausschließlich die Claims iss, iat und actorId. Alle weiteren Claims prüft der Fachdienst.
  # Quelle: https://wiki.gematik.de/spaces/P114ZT/pages/764657578/2026-07-02+Fachaustausch

  @A_26477
  @A_26452
  @A_26661
  @A_27725-01
  @TA_A_26477_08
  @TA_A_26452_03
  @TA_A_26661_20
  @TA_A_27725-01_32
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  @MASVS-RESILIENCE
  Szenario: PoPP Token mit abweichender actorId wird abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_SUB"

    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "WRONG_ACTOR_ID" auf "evil_client"

    # Für die Claim-Prüfung wird das PoPP-Token weiter mit dem echten PoPP-Server-Key signiert.
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.actorId" auf Wert "${WRONG_ACTOR_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.actorId}" der mit "${WRONG_ACTOR_ID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" nicht überein mit "${ACCESS_TOKEN_SUB}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                        | operation                       | start           | end           | limit | tags                                                                                               |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"403","http.target":"${paths.guard.helloZetaPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

  @A_26450
  @A_26477
  @A_26493-01
  @TA_A_26450_03
  @TA_A_26477_13
  @TA_A_26493-01_01
  @normal
  @MASVS-AUTH
  @MASVS-CRYPTO
  Szenario: PoPP JWKS wird bei unbekanntem PoPP kid heruntergeladen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppSignedJwksPath" auf "${paths.popp.signedJwks}"
    Und TGR setze lokale Variable "poppSignedJwksResponseCondition" auf "isResponse && request.path =~ '.*${poppSignedJwksPath}'"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "UNKNOWN_POPP_KID" auf "unknown_popp_kid"

    # Ein unbekanntes kid macht ein vorhandenes JWKS für diese Signaturprüfung unzureichend.
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "header.kid" auf Wert "${UNKNOWN_POPP_KID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200|304"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "header.kid" auf Wert "${UNKNOWN_POPP_KID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${poppSignedJwksResponseCondition}" die Manipulation auf Feld "$.responseCode" und Wert "500" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26450
  @TA_A_26450_02
  @MASVS-CRYPTO
  Szenariogrundriss: PoPP Token mit fehlerhaftem Header-Attribut wird abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Beispiele: Fehlerhafte Header-Attribute
      | JwtField   | NeuerWert                   |
      | header.typ | JWT                         |
      | header.alg | RS999                       |
      | header.kid | invalid_popp_kid_for_header |

  @A_26477
  @TA_A_26477_10
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenariogrundriss: ZETA Guard verwirft nicht akzeptierte PoPP Token JWT Parsing- oder Header-Varianten (<Variante>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"

    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "variant.name" auf Wert "<Variante>" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "manipulatedPoPPToken"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedPoPPToken}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedPoPPToken}" hat lokal mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}" die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    @normal
    Beispiele: two_segments
      | Variante                           |
      | two_segments                       |

    @normal
    Beispiele: invalid_header_base64url
      | Variante                           |
      | invalid_header_base64url           |

    @normal
    Beispiele: invalid_payload_base64url
      | Variante                           |
      | invalid_payload_base64url          |

    @normal
    Beispiele: invalid_signature_base64url
      | Variante                           |
      | invalid_signature_base64url        |

    @normal
    Beispiele: invalid_header_json
      | Variante                           |
      | invalid_header_json                |

    @normal
    Beispiele: invalid_header_json_unquoted_keys
      | Variante                           |
      | invalid_header_json_unquoted_keys  |

    @normal
    Beispiele: invalid_payload_json
      | Variante                           |
      | invalid_payload_json               |

    @normal
    Beispiele: invalid_payload_json_unquoted_keys
      | Variante                           |
      | invalid_payload_json_unquoted_keys |

    @normal
    Beispiele: unknown_header_parameter
      | Variante                           |
      | unknown_header_parameter           |

    @normal
    Beispiele: unsupported_crit
      | Variante                           |
      | unsupported_crit                   |

    @normal
    Beispiele: unsupported_alg
      | Variante                           |
      | unsupported_alg                    |

    @normal
    Beispiele: missing_alg
      | Variante                           |
      | missing_alg                        |

    @normal
    Beispiele: duplicate_alg_headers
      | Variante                           |
      | duplicate_alg_headers              |

    @normal
    Beispiele: jwe_like_five_segments
      | Variante                           |
      | jwe_like_five_segments             |

    @normal
    Beispiele: nested_cty_jwt_invalid_inner
      | Variante                           |
      | nested_cty_jwt_invalid_inner       |

  @A_26477
  @TA_A_26477_09
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: ZETA Guard verwirft ein PoPP Token mit ungültiger Signatur
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"

    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "variant.name" auf Wert "invalid_signature" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "manipulatedPoPPToken"
    Und prüfe die JWT-Variante invalid_signature in "${manipulatedPoPPToken}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante invalid_signature in "${manipulatedPoPPToken}" hat lokal mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}" die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_25660
  @A_26477
  @A_26661
  @TA_A_25660_03
  @TA_A_26477_08
  @TA_A_26661_20
  @normal
  @MASVS-AUTH
  Szenario: PoPP Token Manipulation Test - actorId Claim
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "WRONG_ACTOR_ID" auf "evil_client"

    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.actorId" auf Wert "${WRONG_ACTOR_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.actorId}" der mit "${WRONG_ACTOR_ID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26452
  @TA_A_26452_01
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: PoPP Token mit abweichendem iss Claim wird abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "WRONG_POPP_ISSUER" auf "https://evil.example.com"

    # Für die Issuer-Prüfung wird das PoPP-Token weiter mit dem echten PoPP-Server-Key signiert.
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iss" auf Wert "${WRONG_POPP_ISSUER}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iss}" der mit "${WRONG_POPP_ISSUER}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.strict}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26477
  @A_26661
  @deployment_modification
  @TA_A_26477_02
  @TA_A_26477_10
  @TA_A_26661_20
  @MASVS-AUTH
  Szenario: PoPP Token mit zu altem iat wird abgelehnt
    Gegeben sei setze die PoPP Token Gültigkeit im ZETA Deployment auf "300s"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMCB-INFO.telematikId}" und Profession "${SMCB-INFO.professionId}" und speichere in der Variable "PoPP_TOKEN"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "OLD_IAT" auf "1701432000"

    # Für die Parameter-Prüfung wird das PoPP-Token weiter mit dem echten PoPP-Server-Key signiert.
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" auf Wert "${OLD_IAT}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${PoPP_TOKEN} |

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iat}" der mit "${OLD_IAT}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26452
  @A_26477
  @A_26661
  @deployment_modification
  @TA_A_26452_02
  @TA_A_26477_03
  @TA_A_26477_10
  @TA_A_26661_20
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: PoPP Token mit Ausstellungszeitpunkt außerhalb des Prüfzeitpunkt-Quartals wird abgelehnt
    Gegeben sei setze die PoPP Token Gültigkeit im ZETA Deployment auf "quarter"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und speichere einen Zeitstempel aus dem vorherigen Quartal in der Variable "IAT_OUTSIDE_QUARTER"

    # Für die Quartalsprüfung wird das PoPP-Token weiter mit dem echten PoPP-Server-Key signiert.
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" auf Wert "${IAT_OUTSIDE_QUARTER}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iat}" der mit "${IAT_OUTSIDE_QUARTER}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26450
  @A_26477
  @A_26661
  @TA_A_26450_01
  @TA_A_26477_09
  @TA_A_26661_20
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: PoPP Token mit ungültiger Signatur wird abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Erster erfolgreicher Flow - Client erhält gültiges PoPP-Token
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.insurerId}" der aktuellen Anfrage in der Variable "PoPP_INSURER_ID"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-foreign_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    # Belastbarer Nachweis für TA_A_26450_01:
    # Der bestehende TGR-JWT-Manipulationsschritt signiert den PoPP-Token mit einem fremden Key neu,
    # ohne das JWK zu ersetzen; zusammen mit dem 403-Nachweis prüft das die RFC7515-Signaturverifikation.
    # Feldwahl ist absichtlich "harmlos", damit die Ablehnung nur auf die Signatur zurückzuführen ist
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.insurerId" auf Wert "${PoPP_INSURER_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe die erste manipulierte Anfrage in dieser Phase und ihre gepaarte Antwort
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.insurerId}" der mit "${PoPP_INSURER_ID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26477
  @A_26661
  @TA_A_26477_06
  @TA_A_26661_20
  @normal
  @MASVS-AUTH
  Szenario: PoPP Token mit fremdem Schlüssel signiert wird abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Erster erfolgreicher Flow - Client erhält gültiges PoPP-Token
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.insurerId}" der aktuellen Anfrage in der Variable "PoPP_INSURER_ID"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-foreign_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    # PoPP-Token mit fremdem Key neu signieren UND JWK ersetzen
    # Signatur ist mathematisch gültig, aber Key ist nicht im JWKS des PoPP Servers
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.insurerId" auf Wert "${PoPP_INSURER_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe die erste manipulierte Anfrage in dieser Phase und ihre gepaarte Antwort
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.insurerId}" der mit "${PoPP_INSURER_ID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
