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

@UseCase_01_27
Funktionalität: Client Authentisierung und Autorisierung Refresh Token without Attest SC 400

  @A_25662
  @A_25782
  @A_26662
  @A_27725-01
  @TA_A_25662_02
  @TA_A_25782_05
  @TA_A_26662_01
  @TA_A_27725-01_15
  @MASVS-AUTH
  @MASVS-RESILIENCE
  Szenario: Refresh Token Reuse wird vom Authorization Server abgelehnt (Negativtest)
    Gegeben sei TGR setze lokale Variable "accessTokenTtl" auf "5"
    # expires_in Manipulation aktivieren BEVOR der erste HelloZeta Request
    # (3 Ausführungen: Initial Token Exchange + 1. Refresh + 2. Refresh, alle mit manipuliertem expires_in)
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 3 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Refresh Token aus dem Token Exchange speichern
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "usedRefreshToken"

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # Warte bis Access Token abgelaufen ist
    Und warte "${accessTokenTtl}" Sekunden

    # Ersten Refresh durchführen (verwendet usedRefreshToken)
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Verifiziere dass der Refresh mit dem gespeicherten Token erfolgte (jetzt einziger Token Request)
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${usedRefreshToken}"

    # Versuche das bereits verwendete Refresh Token nochmal zu verwenden
    # Manipuliere den Request, um das alte Refresh Token zurückzuspielen
    # Hinweis: Token Request Body ist application/x-www-form-urlencoded (Form-Data), nicht JSON
    # TigerProxy hat keinen RbelHttpFormDataWriter, daher muss Regex auf $.body verwendet werden
    Wenn TGR setze lokale Variable "replayCondition" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    Dann Setze im TigerProxy für die Nachricht "${replayCondition}" die Regex-Manipulation auf Feld "$.body" mit Regex "refresh_token=[^&]*" und Wert "refresh_token=${usedRefreshToken}"

    Und TGR lösche aufgezeichnete Nachrichten

    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    # Zweiter Refresh-Versuch sollte fehlschlagen
    Und warte "${accessTokenTtl}" Sekunden
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"

    # Finde den Refresh Token Request (nicht den Token Exchange der danach folgt)
    # Wir suchen explizit nach dem Request mit grant_type=refresh_token
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.refresh_token" überein mit "${usedRefreshToken}"

    # Erwarte invalid_grant
    # Grund: Refresh Token wurde bereits verwendet (Rotation-Verletzung)
    # Die Response zu diesem Request sollte 400 mit invalid_grant sein
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.error"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "invalid_grant"
    # Zusätzliche Schema-Validierung
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "errorBody"
    Und validiere "${errorBody}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"400","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

  @A_27802-02
  @TA_A_27802-02_01
  @TA_A_27802-02_02
  @MASVS-CRYPTO
  @require_signing_key
  Szenariogrundriss: ZETA Guard verwirft fehlerhafte Refresh Token JWT Varianten (<Variante>)
    Gegeben sei TGR sende eine leere "GET" Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "KEY_STORE"

    Gegeben sei TGR setze lokale Variable "accessTokenTtl" auf "3"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"
    Und TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "30" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "refreshRequestCondition" auf ".*${paths.guard.tokenEndpointPath}"
    Und Setze im TigerProxy für JWT in "$.body.refresh_token" das Feld "variant.name" auf Wert "<Variante>" mit privatem Schlüssel "${signingKey}" für Pfad "${refreshRequestCondition}" und 1 Ausführungen

    Wenn warte "${accessTokenTtl}" Sekunden
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "${oauth_parameters.grant_type.refreshToken}" übereinstimmt
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Anfrage in der Variable "malformedRefreshToken"
    Und prüfe die JWT-Variante <Variante> in "${malformedRefreshToken}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${malformedRefreshToken}" hat lokal mit KeyStore "${KEY_STORE}" die erwartete Signaturintegritaet
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

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
    Beispiele: invalid_payload_json
      | Variante                           |
      | invalid_payload_json               |

    @normal
    Beispiele: invalid_signature
      | Variante                           |
      | invalid_signature                  |

    @normal
    Beispiele: jwe_like_five_segments
      | Variante                           |
      | jwe_like_five_segments             |

    @normal
    Beispiele: invalid_header_json_unquoted_keys
      | Variante                           |
      | invalid_header_json_unquoted_keys  |

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
    Beispiele: nested_cty_jwt_invalid_inner
      | Variante                           |
      | nested_cty_jwt_invalid_inner       |
