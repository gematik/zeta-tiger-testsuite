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
  @MASVS-AUTH
  Szenario: PEP fügt alle ZETA-Header ein (User-Info, PoPP-Token-Content, Client-Data)
    # Access Token holen und User-Daten aus dem Access Token ermitteln
    Gegeben sei aktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}" der aktuellen Anfrage in der Variable "ACC_TOK"
    Und decodiere und validiere "${ACC_TOK}" gegen Schema "schemas/v_1_0/access-token.yaml"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "expectedIdentifier"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.profession_oid}" der aktuellen Anfrage in der Variable "expectedProfessionOid"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.common_name}" der aktuellen Anfrage in der Variable "expectedCommonName"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.organization_name}" der aktuellen Anfrage in der Variable "expectedOrganizationName"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.client_id}" der aktuellen Anfrage in der Variable "expectedClientId"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.product_id}" der aktuellen Anfrage in der Variable "expectedProductId"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.product_version}" der aktuellen Anfrage in der Variable "expectedProductVersion"

    # Nachrichten löschen und Resource Request mit zusätzlichem Header senden
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | x-ta-a-25669-01-07-forwarded | ta-a-25669-01-07-forwarded |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Prüfe Request VOR PEP - keine ZETA-Header vorhanden
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_OLD_HEADERS"

    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.userInfo.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.poppTokenContent.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.clientData.root}"

    # PoPP-Header (JWT) muss vorhanden sein
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.root}"

    # Prüfe Request NACH PEP
    # TA_A_26492-02_01: Für diesen Endpunkt ist die Weiterleitung der Client-Daten aktiviert
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^${paths.fachdienst.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_HEADERS"

    # TA_A_25669-01_07: Alle anderen Header wurden weitergeleitet
    Und prüfe Knoten "${ALL_HEADERS}" enthält mindestens alle Header aus "${ALL_OLD_HEADERS}" und nutze soft assert

    # TA_A_25669-01_01 / TA_A_26589-01_01: zeta-user-info wurde eingefügt und Access-Token-Claims wurden übernommen
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.root}" der aktuellen Anfrage in der Variable "USER_INFO"
    Und prüfe ob der Knoten "${USER_INFO}" MAX 250 Byte groß ist und nutze soft assert
    Und prüfe "${USER_INFO}" ist striktes Base64-URL Format
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.decoded.root}" der aktuellen Anfrage in der Variable "USER_INFO_decoded"
    Und validiere "${USER_INFO_decoded}" gegen Schema "schemas/v_1_0/zeta-user-info.yaml"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.identifier}" überein mit "${expectedIdentifier}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.professionOid}" überein mit "${expectedProfessionOid}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.commonName}" überein mit "${expectedCommonName}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.organizationName}" überein mit "${expectedOrganizationName}"

    # TA_A_25669-01_02: zeta-popp-token-content wurde eingefügt
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.poppTokenContent.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.poppTokenContent.root}" der aktuellen Anfrage in der Variable "POPP_CONTENT"
    Und prüfe ob der Knoten "${POPP_CONTENT}" MAX 450 Byte groß ist und nutze soft assert
    Und prüfe "${POPP_CONTENT}" ist striktes Base64-URL Format
    Und TGR speichere Wert des Knotens "${headers.zeta.poppTokenContent.decoded.root}" der aktuellen Anfrage in der Variable "POPP_CONTENT_decoded"
    # Vergleich: zeta-popp-token-content muss dem Payload des PoPP-JWT entsprechen
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.root}" der aktuellen Anfrage in der Variable "POPP_JWT_PAYLOAD"
    Und prüfe Knoten "${POPP_JWT_PAYLOAD}" enthält mindestens alle Kindknoten von "${POPP_CONTENT_decoded}"
    Und prüfe Knoten "${POPP_CONTENT_decoded}" enthält mindestens alle Kindknoten von "${POPP_JWT_PAYLOAD}"

    # TA_A_25669-01_03: zeta-client-data wurde eingefügt - zurück zum Request NACH PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.clientData.root}" der aktuellen Anfrage in der Variable "CLIENT_DATA"
    Und prüfe ob der Knoten "${CLIENT_DATA}" MAX 250 Byte groß ist und nutze soft assert
    Und prüfe "${CLIENT_DATA}" ist striktes Base64-URL Format
    Und TGR speichere Wert des Knotens "${headers.zeta.clientData.decoded.root}" der aktuellen Anfrage in der Variable "CLIENT_DATA_decoded"
    Und validiere "${CLIENT_DATA_decoded}" gegen Schema "schemas/v_1_0/client-data.yaml"
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
    Und deaktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment

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

    @TA_A_27260_05
    Beispiele: Policy Engine
      | component       | serviceName                       | specificBody |
      | Policy Engine   | ${telemetry.service.policyEngine} | *            |
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

  @A_25669-01
  @A_28439
  @TA_A_25669-01_04
  @TA_A_25669-01_05
  @TA_A_25669-01_06
  @TA_A_28439_01
  @deployment_modification
  @MASVS-AUTH
  Szenario: PEP überschreibt vom Client gesetzte zeta-Header und aktualisiert den Forwarded-Header
    Gegeben sei aktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    # Setze gefälschte zeta-Header per TigerProxy-Manipulation - diese werden vom Client mitgesendet
    Und TGR setze lokale Variable "fakeHeaderCondition" auf "isRequest && request.path =~ '.*${paths.client.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.userInfo.strict}" und Wert "FAKE_USER_INFO"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.poppTokenContent.strict}" und Wert "FAKE_POPP_CONTENT"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.clientData.strict}" und Wert "FAKE_CLIENT_DATA"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.forwarded.strict}" und Wert "for=client;proto=http"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.xForwardedFor}" und Wert "172.88.24.17"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.xRealIp}" und Wert "172.88.24.17"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - gefälschte Header wurden vom Client gesendet
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.helloZetaPath}" übereinstimmt
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.xForwardedFor}"
    Und TGR speichere Wert des Knotens "${headers.xForwardedFor}" der aktuellen Anfrage in der Variable "xforwardedBefore"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.root}" überein mit "FAKE_USER_INFO"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent.root}" überein mit "FAKE_POPP_CONTENT"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.root}" überein mit "FAKE_CLIENT_DATA"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.forwarded.root}"
    Und TGR speichere Wert des Knotens "${headers.forwarded.root}" der aktuellen Anfrage in der Variable "forwardedBefore"

    # Prüfe Request NACH PEP - Header wurden vom PEP überschrieben (nicht mehr FAKE_*)
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.forwarded.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.forwarded.root}" nicht überein mit "${forwardedBefore}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.xForwardedFor}" nicht überein mit "${xforwardedBefore}"
    Und TGR speichere Wert des Knotens "${headers.forwarded.root}" der aktuellen Anfrage in der Variable "forwardedAfter"
    # RFC 7239: bestehender Forwarded-Header bleibt erhalten und es wird ein weiteres gültiges Forwarded-Element (for|by|proto|host) angehängt
    Und TGR prüfe Variable "forwardedAfter" stimmt überein mit "^for=client;proto=http\s*,\s*(for|by|proto|host)=.+$"

    # Prüfe, dass alle drei zeta-Header vorhanden sind
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.poppTokenContent.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData.root}"

    # TA_A_25669-01_04: zeta-user-info wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.root}" nicht überein mit "FAKE_USER_INFO"

    # TA_A_25669-01_05: zeta-popp-token-content wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent.root}" nicht überein mit "FAKE_POPP_CONTENT"

    # TA_A_25669-01_06: zeta-client-data wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData.root}" nicht überein mit "FAKE_CLIENT_DATA"
    Und deaktiviere die Client-Daten-Weiterleitung für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
