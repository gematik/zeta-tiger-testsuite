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

@UseCase_01_23
Funktionalität: Client Ressource Anfrage Fachdienst Client-Daten Header SC 200

  @A_25669-01
  @A_26492-02
  @A_26589-01
  @A_26590-02
  @A_26661
  @A_27007
  @TA_A_25669-01_01
  @TA_A_25669-01_02
  @TA_A_25669-01_03
  @TA_A_25669-01_07
  @TA_A_26492-02_01
  @TA_A_26589-01_01
  @TA_A_26590-02_01
  @TA_A_26661_15
  @TA_A_27007_15
  @deployment_modification
  @popp_deployment_toggle
  @MASVS-CODE
  @MASVS-PRIVACY
  @MASVS-AUTH
  Szenario: PEP fügt alle ZETA-Header ein (User-Info, PoPP-Token-Content, Client-Data)
    # Vorbereitung: Access Token holen und die erwarteten Claims für die späteren Header-Prüfungen sichern.
    # Vorbedingung für zeta-popp-token-content: Die PoPP-Verifikation ist für die Route aktiviert.
    Gegeben sei aktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    # Vorbedingung für TA_A_25669-01_03, TA_A_26492-02_01 und TA_A_26590-02_01:
    # Die Client-Daten-Weiterleitung ist für den geprüften Endpunkt aktiviert.
    Und aktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}" der aktuellen Anfrage in der Variable "ACC_TOK"
    Und decodiere und validiere "${ACC_TOK}" gegen Schema "schemas/v_2_0/schemas/access-token.yaml"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "expectedIdentifier"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.profession_oid}" der aktuellen Anfrage in der Variable "expectedProfessionOid"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.common_name}" der aktuellen Anfrage in der Variable "expectedCommonName"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.organization_name}" der aktuellen Anfrage in der Variable "expectedOrganizationName"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.client_id}" der aktuellen Anfrage in der Variable "expectedClientId"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.product_id}" der aktuellen Anfrage in der Variable "expectedProductId"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.product_version}" der aktuellen Anfrage in der Variable "expectedProductVersion"

    # Die Nachrichten werden gelöscht, damit die nachfolgende Request-Kette eindeutig ausgewählt werden kann.
    # TA_A_25669-01_07: Der zusätzliche Test-Header dient als Nachweis für die Weiterleitung aller ursprünglichen Header.
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | x-ta-a-25669-01-07-forwarded | ta-a-25669-01-07-forwarded |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    # TA_A_27007_15: Der ZETA Client erhält für die erfolgreich weitergeleitete Anfrage HTTP 200.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Request VOR PEP: Auswahl der zur Client-Anfrage gehörenden PEP-Eingangsanfrage.
    Und TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    # TA_A_26661_15: Der ZETA Guard beantwortet die erfolgreiche HTTP-Proxy-Anfrage mit HTTP 200.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Vorbedingungen für TA_A_25669-01_01, TA_A_25669-01_02 und TA_A_25669-01_03:
    # Die einzusetzenden ZETA-Header sind am PEP-Eingang noch nicht vorhanden.
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_OLD_HEADERS"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.userInfo.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.poppTokenContent.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.clientData.root}"

    # Vorbedingung für TA_A_25669-01_02: Der eingehende Request enthält ein PoPP-JWT,
    # sodass zeta-popp-token-content als optionaler Header eingesetzt werden muss.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.root}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.root}" der aktuellen Anfrage in der Variable "POPP_JWT_PAYLOAD"

    # Request NACH PEP: Auswahl der ersten Fachdienst-Anfrage nach dem ausgewählten PEP-Eingang.
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^${paths.fachdienst.helloZetaPath}" übereinstimmt
    Und TGR finde die nächste Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_HEADERS"

    # TA_A_25669-01_07: Alle anderen Header wurden weitergeleitet
    Und prüfe Knoten "${ALL_HEADERS}" enthält mindestens alle Header aus "${ALL_OLD_HEADERS}" und nutze soft assert

    # TA_A_25669-01_01 / TA_A_26589-01_01: zeta-user-info wurde als neuer Header eingesetzt
    # und die Nutzer-Daten aus den Access-Token-Claims wurden übernommen.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.root}" der aktuellen Anfrage in der Variable "USER_INFO"
    Und prüfe ob der Knoten "${USER_INFO}" MAX 250 Byte groß ist und nutze soft assert
    Und prüfe "${USER_INFO}" ist striktes Base64-URL Format
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.decoded.root}" der aktuellen Anfrage in der Variable "USER_INFO_decoded"
    Und validiere "${USER_INFO_decoded}" gegen Schema "schemas/v_2_0/schemas/zeta-user-info.yaml"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.identifier}" überein mit "${expectedIdentifier}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.professionOid}" überein mit "${expectedProfessionOid}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.commonName}" überein mit "${expectedCommonName}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.organizationName}" überein mit "${expectedOrganizationName}"

    # TA_A_25669-01_02: zeta-popp-token-content wurde aus dem PoPP-Payload eingesetzt.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.poppTokenContent.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.poppTokenContent.root}" der aktuellen Anfrage in der Variable "POPP_CONTENT"
    Und prüfe ob der Knoten "${POPP_CONTENT}" MAX 450 Byte groß ist und nutze soft assert
    Und prüfe "${POPP_CONTENT}" ist striktes Base64-URL Format
    Und TGR speichere Wert des Knotens "${headers.zeta.poppTokenContent.decoded.root}" der aktuellen Anfrage in der Variable "POPP_CONTENT_decoded"
    # Der gespeicherte PEP-Eingang bleibt der Vergleichsanker; dadurch werden keine unabhängigen Nachrichten kombiniert.
    Und prüfe Knoten "${POPP_JWT_PAYLOAD}" enthält mindestens alle Kindknoten von "${POPP_CONTENT_decoded}"
    Und prüfe Knoten "${POPP_CONTENT_decoded}" enthält mindestens alle Kindknoten von "${POPP_JWT_PAYLOAD}"

    # TA_A_25669-01_03 / TA_A_26492-02_01 / TA_A_26590-02_01:
    # zeta-client-data wurde bei aktivierter Endpunkt-Konfiguration aus den Access-Token-Claims eingesetzt.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.clientData.root}" der aktuellen Anfrage in der Variable "CLIENT_DATA"
    Und prüfe ob der Knoten "${CLIENT_DATA}" MAX 250 Byte groß ist und nutze soft assert
    Und prüfe "${CLIENT_DATA}" ist striktes Base64-URL Format
    Und TGR speichere Wert des Knotens "${headers.zeta.clientData.decoded.root}" der aktuellen Anfrage in der Variable "CLIENT_DATA_decoded"
    Und validiere "${CLIENT_DATA_decoded}" gegen Schema "schemas/v_2_0/schemas/client-data.yaml"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.decoded.clientId}" überein mit "${expectedClientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.decoded.product_id}" überein mit "${expectedProductId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.decoded.product_version}" überein mit "${expectedProductVersion}"

  @A_27260
  @deployment_modification
  @MASVS-PRIVACY
  Szenariogrundriss: Telemetrie-Daten Service liefert Logs ohne Profilbildung (<component>)
    Gegeben sei aktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Synchronisiert auf den tatsächlich weitergeleiteten Resource-Request (stabilisiert gegen Timing-Rennen)
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^${paths.fachdienst.helloZetaPath}" übereinstimmt

    # Profiling-relevante Werte aus dem Request nach PEP sichern
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.decoded.identifier}" der aktuellen Anfrage in der Variable "USER_IDENTIFIER"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.clientData.decoded.clientId}" der aktuellen Anfrage in der Variable "CLIENT_ID"

    # Warte auf Telemetrie-Ingestion
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und TGR setze lokale Variable "logQueryBase" auf "resource.service.name:<serviceName> AND body:<specificBody>"

    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q               | size |
      | ${logQueryBase} | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    # Negativprüfung: keiner der profilbildenden Werte darf in den Logs auffindbar sein
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                      | size |
      | ${logQueryBase} AND (body:*${USER_IDENTIFIER}* OR body:*${CLIENT_ID}*) | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.hits.hits.0"

    @TA_A_27260_01
    Beispiele: Ingress
      | component | serviceName                  | specificBody |
      | Ingress   | ${telemetry.service.ingress} | *HelloZeta*  |

    @TA_A_27260_02
    Beispiele: Egress
      | component | serviceName                 | specificBody |
      | Egress    | ${telemetry.service.egress} | *HelloZeta*  |

    @TA_A_27260_03
    Beispiele: HTTP Proxy
      | component  | serviceName                    | specificBody |
      | HTTP Proxy | ${telemetry.service.httpProxy} | *HelloZeta*  |

    @TA_A_27260_04
    Beispiele: Authorization Server
      | component             | serviceName                               | specificBody |
      | Authorization Server  | ${telemetry.service.authorizationServer}  | *            |
    # TODO: Z341 findet einen passenden Knoten (mit Daten, die Profilbildung ermöglichen)

    @TA_A_27260_06
    Beispiele: Notification Service
      | component            | serviceName                              | specificBody |
      | Notification Service | ${telemetry.service.notificationService} | *            |
    # TODO: Z332 findet keinen passenden Knoten

    @TA_A_27260_07
    Beispiele: Resource Server
      | component       | serviceName                         | specificBody |
      | Resource Server | ${telemetry.service.resourceServer} | *HelloZeta*  |

  @A_27260
  @TA_A_27260_05
  @deployment_modification
  @MASVS-PRIVACY
  Szenario: Telemetrie-Daten Service liefert Logs ohne Profilbildung (Policy Engine)
    Gegeben sei aktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "PRIVACY_POLICY_CLIENT_ID"
    Und TGR prüfe Variable "PRIVACY_POLICY_CLIENT_ID" stimmt überein mit ".+"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${PRIVACY_POLICY_CLIENT_ID}\E" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.client_id" überein mit "\Q${PRIVACY_POLICY_CLIENT_ID}\E"
    Und TGR speichere Wert des Knotens "$.body.decision_id" der aktuellen Antwort in der Variable "PRIVACY_POLICY_DECISION_ID"
    Und TGR prüfe Variable "PRIVACY_POLICY_DECISION_ID" stimmt überein mit ".+"
    Und merke ab der aktuellen Antwort die Telemetriefrist "PRIVACY_POLICY_LOG" von "${testdata.telemetry_policy_decision_timeout_seconds}" Sekunden

    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.decoded.identifier}" der aktuellen Anfrage in der Variable "PRIVACY_POLICY_USER_IDENTIFIER"
    Und TGR prüfe Variable "PRIVACY_POLICY_USER_IDENTIFIER" stimmt überein mit ".+"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.decoded.clientId}" überein mit "\Q${PRIVACY_POLICY_CLIENT_ID}\E"

    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                         | size  | track_total_hits |
      | resource.service.name:"${telemetry.service.policyEngine}" AND attributes.opa.event_type:"decision_log" AND attributes.opa.decision_log.decision_id:"${PRIVACY_POLICY_DECISION_ID}" | 10000 | true             |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und wiederhole die aktuelle OpenSearch-GET-Abfrage innerhalb der Telemetriefrist "PRIVACY_POLICY_LOG" alle 5 Sekunden solange das Ergebnis leer ist
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.timed_out" überein mit "false"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body._shards.failed" überein mit "0"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.relation" überein mit "eq"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.value" überein mit "([1-9][0-9]{0,3}|10000)"
    Und TGR speichere Wert des Knotens "$.body.hits.total.value" der aktuellen Antwort in der Variable "PRIVACY_POLICY_LOG_COUNT"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body.hits.hits" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "array",
        "minItems": ${PRIVACY_POLICY_LOG_COUNT},
        "maxItems": ${PRIVACY_POLICY_LOG_COUNT},
        "items": {"type": "object", "required": ["_source"]}
      }
      """
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits" nicht überein mit "(?s).*\Q${PRIVACY_POLICY_USER_IDENTIFIER}\E.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits" nicht überein mit "(?s).*\Q${PRIVACY_POLICY_CLIENT_ID}\E.*"

  @A_25669-01
  @A_28439
  @A_26492-02
  @A_26661
  @A_27007
  @TA_A_25669-01_04
  @TA_A_25669-01_05
  @TA_A_25669-01_06
  @TA_A_28439_01
  @TA_A_26492-02_01
  @TA_A_26661_15
  @TA_A_27007_15
  @deployment_modification
  @popp_deployment_toggle
  @MASVS-CODE
  @MASVS-PRIVACY
  Szenario: PEP überschreibt vom Client gesetzte zeta-Header und aktualisiert den Forwarded-Header
    # Die TA-Prüfungen beziehen sich auf die Anfrage vom PEP zum Fachdienst.
    # Die Suchschritte davor bauen dafür die Nachrichtenkette Request vor PEP -> PEP-Eingang -> Request nach PEP auf.
    # Vorbedingung für zeta-popp-token-content: Die PoPP-Verifikation ist für die Route aktiviert.
    Gegeben sei aktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    # TA_A_26492-02_01: Die Client-Daten-Weiterleitung wird für den geprüften Endpunkt aktiviert.
    Und aktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    # Setze gefälschte zeta-Header per TigerProxy-Manipulation - diese werden vom Client mitgesendet
    Und TGR setze lokale Variable "fakeHeaderCondition" auf "isRequest && request.path =~ '.*${paths.client.helloZetaPath}'"
    # Der Ingress entfernt eingehende Forwarding-Header zum Schutz vor IP-Spoofing:
    # Forwarded, X-Forwarded-For und X-Real-IP.
    # Die zeta-* Header bleiben vor dem Ingress, damit ihr Überschreiben durch den PEP
    # aus einem tatsächlich eingehenden Client-Request geprüft wird.
    # Für die TA_A_28439_01-Prüfung wird Forwarded daher erst hinter dem Ingress gesetzt.
    Und TGR setze lokale Variable "forwardedHeaderCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.userInfo.strict}" und Wert "FAKE_USER_INFO"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.poppTokenContent.strict}" und Wert "FAKE_POPP_CONTENT"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.clientData.strict}" und Wert "FAKE_CLIENT_DATA"
    Und Setze im TigerProxy für die Nachricht "${forwardedHeaderCondition}" die Manipulation auf Feld "${headers.forwarded.strict}" und Wert "for=client;proto=http"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.xForwardedFor}" und Wert "172.88.24.17"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.xRealIp}" und Wert "172.88.24.17"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Synchronisation auf den PEP-Eingang; dieser Warteschritt verändert currentRequest/currentResponse nicht.
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.helloZetaPath}" übereinstimmt
    # Wählt den Request VOR PEP aus, also die vom Client gesendete Anfrage mit den Testwerten.
    # Die folgenden Assertions prüfen hier nur die Testvoraussetzung; sie decken keinen der Ziel-TAs ab.
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    # TA_A_27007_15: Der ZETA Client erhält für die erfolgreiche Proxy-Anfrage HTTP 200.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Hilfsprüfung für die spätere Negativprüfung; kein eigener TA.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.xForwardedFor}"
    Und TGR speichere Wert des Knotens "${headers.xForwardedFor}" der aktuellen Anfrage in der Variable "xforwardedBefore"

    # Wählt den PEP-Eingang aus: die nächste Guard-Anfrage nach dem ausgewählten Client-Request
    # mit dem per Manipulation gesetzten Forwarded-Header.
    # Dieser Request ist der Nachrichtenanker für die nachfolgende Suche, aber noch nicht der TA-Prüffokus.
    Und TGR finde die nächste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.forwarded.root}" der mit "^for=client;proto=http$" übereinstimmt
    # TA_A_26661_15: Der ZETA Guard beantwortet die erfolgreiche HTTP-Proxy-Anfrage mit HTTP 200.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Testvoraussetzungen für TA_A_25669-01_04 bis TA_A_25669-01_06:
    # Die gleichnamigen Header sind am PEP-Eingang mit den bekannten Testwerten vorhanden.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.root}" überein mit "FAKE_USER_INFO"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent.root}" überein mit "FAKE_POPP_CONTENT"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.root}" überein mit "FAKE_CLIENT_DATA"
    # Testvoraussetzung für TA_A_28439_01; die Auswahl oben prüft den Wert bereits mit.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.forwarded.root}"
    Und TGR speichere Wert des Knotens "${headers.forwarded.root}" der aktuellen Anfrage in der Variable "forwardedBefore"

    # Prüfe Request NACH PEP - Header wurden vom PEP überschrieben (nicht mehr FAKE_*)
    # Wählt den ersten passenden Fachdienst-Request NACH dem oben ausgewählten PEP-Eingang.
    # In diesem Szenario gibt es nach diesem Eingang genau einen passenden Fachdienst-Request;
    # damit ist dies die Anfrage PEP -> Resource Backend und der Prüffokus für
    # TA_A_25669-01_04, TA_A_25669-01_05, TA_A_25669-01_06 und TA_A_28439_01.
    Dann TGR finde die nächste Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    # TA_A_28439_01: Der Forwarded-Header ist in genau diesem Request vorhanden.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.forwarded.root}"
    # TA_A_28439_01: Das bestehende Forwarded-Element bleibt erhalten und ein weiteres gültiges Element wird ergänzt.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.forwarded.root}" überein mit "^${forwardedBefore}\s*,\s*(for|by|proto|host)=.+$"
    # Ergänzende Negativprüfung gegen die vom Client gesetzte X-Forwarded-For-Adresse; kein eigener TA.
    Und TGR prüfe aktueller Request im Knoten "${headers.xForwardedFor}" stimmt nicht überein mit "\Q${xforwardedBefore}\E"
    # RFC 7239: bestehendes Forwarded-Element bleibt erhalten und mindestens ein weiteres gültiges Element wird angehängt.

    # Ab hier wird keine neue Nachricht gesucht; alle Assertions beziehen sich weiterhin auf denselben Request PEP -> Resource Backend.
    # TA_A_25669-01_04: zeta-user-info ist im Request an das Resource Backend vorhanden.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    # TA_A_25669-01_05: zeta-popp-token-content ist im Request an das Resource Backend vorhanden.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.poppTokenContent.root}"
    # TA_A_25669-01_06 und TA_A_26492-02_01: zeta-client-data wird bei aktivierter
    # Endpunkt-Konfiguration in den Request an das Resource Backend eingesetzt.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData.root}"

    # TA_A_25669-01_04: zeta-user-info wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request im Knoten "${headers.zeta.userInfo.root}" stimmt nicht überein mit "\QFAKE_USER_INFO\E"

    # TA_A_25669-01_05: zeta-popp-token-content wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request im Knoten "${headers.zeta.poppTokenContent.root}" stimmt nicht überein mit "\QFAKE_POPP_CONTENT\E"

    # TA_A_25669-01_06: zeta-client-data wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request im Knoten "${headers.zeta.clientData.root}" stimmt nicht überein mit "\QFAKE_CLIENT_DATA\E"
