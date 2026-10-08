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

@UseCase_01_09
Funktionalität: Client Authentisierung und Autorisierung Software-Attest SC 403

  @A_25661
  @A_26661
  @A_26662
  @A_26988
  @A_27401
  @A_27725-01
  @TA_A_25661_03
  @TA_A_26661_04
  @TA_A_26662_01
  @TA_A_26988_05
  @TA_A_26988_07
  @TA_A_27401_01
  @TA_A_27725-01_04
  @normal
  @MASVS-AUTH
  @MASVS-RESILIENCE
  Szenario: Policy Decision - Zugriffsverweigerung bei allow=false liefert HTTP 403
    # TA_A_25661_03, TA_A_26661_04: OPA-Decision gezielt auf allow=false setzen.
    Gegeben sei TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 1 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.authorization_request.grant_type" der mit "${oauth_parameters.grant_type.tokenExchange}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_27401_01: PDP Decision entspricht pdp-decision.yaml.
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PDP_DECISION"
    Und validiere "${PDP_DECISION}" gegen Schema "schemas/v_1_0/pdp-decision.yaml"

    # TA_A_25661_03: Manipulierte Policy Decision verweigert den Zugriff.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "false"

    # TA_A_25661_03, TA_A_26661_04: Authorization Server quittiert allow=false am Token-Endpunkt mit 403 Forbidden.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    # TA_A_26662_01: Fehlerantwort enthält ein JSON-Objekt gemäß zeta-error.yaml.
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"

    # TA_A_25661_03, TA_A_26661_04: Rückgabe an den Client ist 403 Forbidden.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s)
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"

    # TA_A_26988_05 - Authorization Server: Fehlermeldung wird vom Telemetriedaten Service gesammelt und ist in Jaeger auffindbar.
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"403","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_27725-01_04 - Der 403-Token-Request ist mit Statuscode und URL in Jaeger auffindbar.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

    # TA_A_26988_07 - Policy Engine: Fehlermeldung wird vom Telemetriedaten Service gesammelt und ist in Jaeger auffindbar.
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                           | start           | end           | limit | tags                                                                                       |
      | ${telemetry.service.policyEngine} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"200","url.path":"${paths.opa.decisionPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

  @A_27496
  @TA_A_27496_01
  @TA_A_27496_02
  @TA_A_27496_03
  @normal
  @MASVS-RESILIENCE
  Szenario: product_id, product_version und professionOID werden bei jedem Aufruf korrekt verarbeitet und protokolliert
    # Gutfall ohne Manipulation, aber mit Prüfung der drei Werte im Token- und OPA-Request
    # Für den Aufruf wird allow=false erzwungen, damit ein vollständiger Policy-Request erfolgt
    Gegeben sei TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    # Erster Aufruf
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "PRODUCT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "PRODUCT_VERSION"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_id" überein mit "${PRODUCT_ID}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_version" überein mit "${PRODUCT_VERSION}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "${SMCB-INFO.professionId}"

    # Telemetrie-/Monitoring-Nachweis: der Token-Lauf ist eindeutig korreliert und protokolliert
    # Jaeger stellt product_id, product_version und professionOID hier nicht als suchbare Span-Tags bereit.
    # Die wertgenaue Prüfung erfolgt daher im Token- und OPA-Request oben.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"403","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

    # @TA_A_27496_01
    # @TA_A_27496_02
    # @TA_A_27496_03
    # Protokollierung von product_id, product_version und professionOID
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                          | size |
      | resource.service.name:"${telemetry.service.httpProxy}" AND body:professionOID AND body:${SMCB-INFO.professionId} AND body:product_id AND body:${PRODUCT_ID} AND body:product_version AND body:${PRODUCT_VERSION} AND @timestamp:[${START}000 TO ${END}000] | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

  @A_27496
  @TA_A_27496_01
  @TA_A_27496_02
  @TA_A_27496_03
  @normal
  @MASVS-RESILIENCE
  Szenariogrundriss: product_id, product_version und professionOID werden korrekt verarbeitet und protokolliert (<ManipuliertesFeld>)
    # Test für manipulierte product_id und product_version
    # Für beide Aufrufe wird allow=false erzwungen, damit jeweils ein vollständiger Policy-Request erfolgt
    Gegeben sei TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 2 Ausführungen

    Und TGR setze lokale Variable "tokenRequestCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    # Erster Aufruf:
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.<ManipuliertesFeld>" der aktuellen Anfrage in der Variable "ORIGINAL_VALUE"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.<ManipuliertesFeld>" überein mit "${ORIGINAL_VALUE}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "${SMCB-INFO.professionId}"

    # Hole einen Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "privateKey"

    # Zweiter Aufruf: <ManipuliertesFeld> manipuliert
    Und TGR lösche aufgezeichnete Nachrichten
    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${privateKey}" für Pfad "${tokenRequestCondition}" und 1 Ausführungen und ersetze JWK
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificateSecond"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificateSecond}" in die Variable "SMCB-INFO-SECOND"
    # Stelle sicher, dass die Manipulation funktioniert hat
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.<ManipuliertesFeld>" überein mit "<NeuerWert>"
    # Prüfe, dass die gleichen Daten an OPA übergeben werden
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.<ManipuliertesFeld>" überein mit "<NeuerWert>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "${SMCB-INFO-SECOND.professionId}"

    # Telemetrie-/Monitoring-Nachweis: beide Token-Läufe sind eindeutig korreliert und protokolliert
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 2     | {"http.response.status_code":"403","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.1.traceID"
    Und TGR speichere Wert des Knotens "$.body.data.0.traceID" der aktuellen Antwort in der Variable "firstTraceId"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.1.traceID" nicht überein mit "${firstTraceId}"

    # @TA_A_27496_01
    # @TA_A_27496_02
    # @TA_A_27496_03
    # Erste Anfrage: Protokollierung von product_id, product_version und professionOID
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                  | size |
      | resource.service.name:"${telemetry.service.httpProxy}" AND body:professionOID AND body:${SMCB-INFO.professionId} AND body:<ManipuliertesFeld> AND body:${ORIGINAL_VALUE} AND @timestamp:[${START}000 TO ${END}000] | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    # Zweite Anfrage: Protokollierung von product_id, product_version und professionOID
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                            | size |
      | resource.service.name:"${telemetry.service.httpProxy}" AND body:professionOID AND body:${SMCB-INFO-SECOND.professionId} AND body:<ManipuliertesFeld> AND body:<NeuerWert> AND @timestamp:[${START}000 TO ${END}000] | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    Beispiele:
      | ManipuliertesFeld | JwtField                                      | NeuerWert         |
      | product_id        | body.client_statement.posture.product_id      | test-proxy-second |
      | product_version   | body.client_statement.posture.product_version | 9.9.9             |

  @A_25645-01
  @A_25661
  @A_26662
  @TA_A_25645-01_01
  @TA_A_25661_03
  @TA_A_26662_01
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenariogrundriss: ZETA Guard verweigert Token Exchange bei falschem <Binding> Binding im Subject Token
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "SMCB_CERT"
    Und TGR setze lokale Variable "tokenExchangeCondition" auf ".*${paths.guard.tokenEndpointPath}"
    Und Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "<JwtField>" auf Wert "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" mit privatem Schlüssel "${SMCB_CERT.prv_pem}" für Pfad "${tokenExchangeCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.<JwtField>" der mit "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "dpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${dpopJwt}"
    Und berechne JKT aus JWT Header JWK "${dpopJwt}" und speichere in Variable "dpopJkt"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "clientAssertionJwt"
    Und verifiziere die ES256 Signatur des JWT "${clientAssertionJwt}"
    Und berechne JKT aus JWT Header JWK "${clientAssertionJwt}" und speichere in Variable "clientAssertionJkt"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "manipulatedSubjectToken"
    Und verifiziere die ES256 Signatur des JWT "${manipulatedSubjectToken}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.<JwtField>" nicht überein mit "<ErwarteterJkt>"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.<UnveraendertesBindingField>" überein mit "<UnveraenderterJkt>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

    Beispiele: subject_token_bindings
      | Binding    | JwtField            | ErwarteterJkt          | UnveraendertesBindingField | UnveraenderterJkt       |
      | DPoP Key   | body.dpop_key.jkt   | ${dpopJkt}             | body.client_key.jkt        | ${clientAssertionJkt}   |
      | Client Key | body.client_key.jkt | ${clientAssertionJkt}  | body.dpop_key.jkt          | ${dpopJkt}              |

  @A_25645-01
  @A_25661
  @A_26662
  @TA_A_25645-01_01
  @TA_A_25661_03
  @TA_A_26662_01
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
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

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.body.jti}" der mit "attacker-session-token-jti" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "dpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${dpopJwt}"
    Und berechne JKT aus JWT Header JWK "${dpopJwt}" und speichere in Variable "attackerDpopJkt"
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "subjectToken"
    Und verifiziere die ES256 Signatur des JWT "${subjectToken}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.dpop_key.jkt" nicht überein mit "${attackerDpopJkt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_25644-01
  @TA_A_25644-01_05
  @normal
  @MASVS-AUTH
  Szenario: Software Attestation (Linux) - ungültige attestation_challenge wird abgelehnt (Negativtest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "pathCondition" auf "message.path =~ '.*${paths.guard.tokenEndpointPath}' && message.body.grant_type =~ '.*token-exchange'"

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement.posture.attestation_challenge" auf Wert "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" mit privatem Schlüssel "${CLIENT_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_assertion.body.client_statement.posture.attestation_challenge" der mit "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
