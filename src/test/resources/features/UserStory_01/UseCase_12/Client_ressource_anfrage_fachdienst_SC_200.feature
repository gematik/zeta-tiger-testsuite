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

@UseCase_01_12
Funktionalität: Client_ressource_anfrage_fachdienst_SC_200_integrationstest

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR sende eine leere GET Anfrage an "${paths.tigerProxy.baseUrl}/resetMessages"

  @A_26639
  @A_26195
  @TA_A_26639_01
  @TA_A_26195_01
  @websocket
  Szenario: Ingress und PEP HTTP Proxy unterstützt WebSocket Verbindung
    # Ein WebSocket-Roundtrip sollte hier reichen, allerdings könnte man auch noch die Verbindung
      # Ingress <-> PEP HTTP Proxy über den Standalone Tiger Proxy testen.
    Wenn eine WebSocket Verbindung zu "${paths.client.websocketBaseUrl}" geöffnet wird
    Dann wird die WebSocket Verbindung geschlossen

  @A_27802
  @TA_A_27802_01
  @TA_A_27802_02
  @TA_A_27802_03
  @TA_A_27802_04
  @TA_A_27802_05
  @TA_A_27802_10
  @TA_A_27802_11
  Szenariogrundriss:  ZETA Guard Integrationstest, JWT Prüfung
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Holen der Keys: Guard Key (signingKey) für Access Token, Client Key (dpopKey) für DPoP
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"
    Und TGR setze lokale Variable "wrongSigningKey" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Client-DPoP-Key vom Storage holen (für ath-Aktualisierung)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"

    # JWT-Manipulation setzen: Access Token manipulieren UND DPoP ath automatisch aktualisieren
    Dann Setze im TigerProxy für Access Token das Feld "<JwtField>" auf Wert "<NeuerWert>" mit Access Token Key "<AccessTokenKey>" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen

    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    Beispiele: Manipulationen (JWT)
      | JwtField   | NeuerWert        | ResponseCode | AccessTokenKey     |
      | header.alg | ES256            | 200          | ${signingKey}      |
      | header.alg | ES256            | 401          | ${wrongSigningKey} |
      | header.alg | RS1              | 401          | ${signingKey}      |
      | header.typ | dpop             | 401          | ${signingKey}      |
      | body.exp   | 1758719276       | 401          | ${signingKey}      |
      | body.aud   | unknown          | 401          | ${signingKey}      |
      | body.iss   | someone          | 401          | ${signingKey}      |
      | body.nonce | 1234567890123456 | 401          | ${signingKey}      |

  @dev
  @A_25660
  @A_25762
  @A_25767
  @A_26492-01
  @A_27802
  @A_27853
  @A_25766
  @TA_A_25660_01
  @TA_A_25660_04
  @TA_A_25762_04
  @TA_A_25767_02
  @TA_A_26492-01_02
  @TA_A_27802_10
  @TA_A_27802_11
  @TA_A_27853_01
  @TA_A_25766_02
  Szenario: DPoP Resource Request - Client sendet DPoP Proof mit Access Token für geschützte Ressource
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # save the acces token from /token response for later ath check
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "accessToken"

    # TA_A_25660_04 - Refresh Token muss vorhanden sein
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"

    # Resource Request Validierung
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.authorization.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "^DPoP .*"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.clientData}"

    # DPoP JWT Validierung
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "resourceDpopJwt"
    Und decodiere und validiere "${resourceDpopJwt}" gegen Schema "schemas/v_1_0/dpop-token.yaml"
    Und verifiziere ES256 Signatur von DPoP JWT "${resourceDpopJwt}"

    # DPoP Header Validierung
    # @TA_A_27802_10 - typ muss "dpop+jwt" sein
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.typ}" überein mit "dpop+jwt"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.alg}" überein mit "ES256"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.header.jwk.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.header.jwk.kty}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.jwk.kty}" überein mit "EC"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.jwk.crv}" überein mit "P-256"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.jwk.x}" der aktuellen Anfrage in der Variable "resourceDpopJwkX"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.jwk.y}" der aktuellen Anfrage in der Variable "resourceDpopJwkY"
    Und decodiere Base64Url "${resourceDpopJwkX}" und prüfe das die Länge 256 bit ist
    Und decodiere Base64Url "${resourceDpopJwkY}" und prüfe das die Länge 256 bit ist
    Und TGR speichere Wert des Knotens "${headers.dpop.header.root}" der aktuellen Anfrage in der Variable "resourceDpopHeader"
    Und prüfe dass jwk in "${resourceDpopHeader}" keine privaten Key-Teile enthält

    # DPoP Payload Validierung
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.jti}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.htm}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.htu.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.iat}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htm}" überein mit "GET"
    Und TGR speichere Wert des Knotens "${headers.forwarded.xForwardedProto}" der aktuellen Anfrage in der Variable "requestScheme"
    Und TGR speichere Wert des Knotens "${headers.forwarded.xForwardedHost}" der aktuellen Anfrage in der Variable "requestHost"
    Und TGR ersetze ":443$" mit "" im Inhalt der Variable "requestHost"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "requestPath"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "${requestScheme}://${requestHost}${requestPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.body.iat}" der aktuellen Anfrage in der Variable "resourceIat"
    Und validiere, dass der Zeitstempel "${resourceIat}" in der Vergangenheit liegt

    # Access Token Hash (ath) Validierung - nur bei Resource Requests
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.ath}"
    Und berechne SHA256 Hash von "${accessToken}" und speichere in Variable "expectedAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${expectedAth}"

    # Nonce wird bei Resource Requests nicht mitgeschickt

    # TA_A_27853_01 - ZETA-API-Version Header muss vorhanden sein und SemVer entsprechen (Regex: ^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$; Beispiel: 1.2.3-rc.1+build.5)
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.zeta.apiVersion}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.apiVersion}" überein mit "${regex.zetaApiVersion}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.apiVersion}" überein mit "${testdata.semVer}"

  @TA_A_26561_01
  Szenario: PEP HTTP Proxy nutzt gecachte Response-Inhalte
    Gegeben sei Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR setze lokale Variable "cachedMessage" auf "Hello ZETA (cached)"
    Und TGR setze lokale Variable "cacheCondition" auf "isResponse && request.path =~ '^${paths.fachdienst.helloZetaPath}'"
    Dann Setze im TigerProxy für die Nachricht "${cacheCondition}" die Manipulation auf Feld "$.body.message" und Wert "${cachedMessage}" und 1 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "${cachedMessage}"

    Und Alle Manipulationen im TigerProxy werden gestoppt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "${cachedMessage}"

  @dev
  @A_25762
  @A_25767
  @A_27007
  @A_26661
  @TA_A_25762_04
  @TA_A_25767_02
  @TA_A_27007_19
  @TA_A_26661_19
  Szenario: DPoP Resource Request - Guard lehnt fremdes JWK (Binding) ab
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"

    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.root}" das Feld "header.typ" auf Wert "dpop+jwt" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    # zweites reset, damit der Client beim nächsten Aufruf ein neues DPoP Keypair verwendet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @dev
  @A_25762
  @A_25767
  @TA_A_25762_04
  @TA_A_25767_02
  Szenario: DPoP Resource Request - Wiederverwendung desselben jti wird abgewiesen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.root}" das Feld "body.jti" auf Wert "replay-jti" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 2 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @dev
  @A_25767
  @A_27802
  @TA_A_25767_02
  @TA_A_27802_10
  Szenariogrundriss: DPoP JWT Manipulation Test - Resource Anfrage
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.root}" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Vorladen der Nachrichten, damit die nachfolgende Suche nach dem manipulierten Wert schneller durchläuft
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.dpop.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Beispiele: Manipulationen
      | JwtField   | NeuerWert                  |
      | header.typ | JWT                        |
      | header.alg | RS999                      |
      | body.iat   | 1600000000                 |
      | body.ath   | wronghash                  |
      | body.htm   | POST                       |
      | body.htu   | https://wrong.url/resource |

  @A_25669
  @TA_A_25669_08
  @deployment_modification
  @popp_deployment_toggle
  Szenario: PEP fügt keinen ZETA-PoPP-Token-Content ein, wenn kein PoPP-Header mitgeschickt wurde, und entfernt ursprünglich gleichnamige Header
    Gegeben sei deaktiviere die PoPP Token Verifikation für die Route "/pep/" im ZETA Deployment

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppHeaderCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    # popp Header löschen
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "${headers.popp.lineRegex}" und Wert ""
    # ZETA-PoPP-Token-Content Header hinzufügen
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Manipulation auf Feld "${headers.zeta.poppTokenContent}" und Wert "FAKE_POPP_CONTENT"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - PoPP-Header ohne Token-Inhalt
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent}" überein mit "FAKE_POPP_CONTENT"

    # Prüfe Request NACH PEP - ZETA-PoPP-Token-Content darf nicht gesetzt sein
    Dann TGR finde die nächste Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.poppTokenContent}"

    Und aktiviere die PoPP Token Verifikation für die Route "/pep/" im ZETA Deployment

  @dev
  @A_27260
  Szenariogrundriss: Telemetrie-Daten Service liefert Logs ohne Profilbildung (<component>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Synchronisiert auf den tatsächlich weitergeleiteten Resource-Request (stabilisiert gegen Timing-Rennen)
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^${paths.fachdienst.helloZetaPath}" übereinstimmt

    # Profiling-relevante Werte aus dem Request nach PEP sichern
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.decoded.identifier}" der aktuellen Anfrage in der Variable "USER_IDENTIFIER"
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.decoded.professionOid}" der aktuellen Anfrage in der Variable "USER_PROFESSION_OID"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData}"
    Und TGR speichere Wert des Knotens "${headers.zeta.clientDataDecodedClientId}" der aktuellen Anfrage in der Variable "CLIENT_ID"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s)
    Und warte 70 Sekunden
    Und TGR setze lokale Variable "logQueryBase" auf "resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:<containerName> AND attributes.log.file.path:/var/log/pods/* AND body:<specificBody>"

    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q               | size |
      | ${logQueryBase} | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    # Negativprüfung: keiner der profilbildenden Werte darf in den Logs auffindbar sein
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                       | size |
      | ${logQueryBase} AND (body:*${USER_IDENTIFIER}* OR body:*${USER_PROFESSION_OID}* OR body:*${CLIENT_ID}*) | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.hits.hits.0"

    @TA_A_27260_01
    Beispiele: Ingress
      | component | containerName                      | specificBody |
      | Ingress   | ${telemetry.containerName.ingress} | *HelloZeta*  |

    @TA_A_27260_02
    Beispiele: Egress
      | component | containerName                     | specificBody |
      | Egress    | ${telemetry.containerName.egress} | *HelloZeta*  |

    @TA_A_27260_03
    Beispiele: HTTP Proxy
      | component  | containerName                        | specificBody |
      | HTTP Proxy | ${telemetry.containerName.httpProxy} | *HelloZeta*  |

    @TA_A_27260_07
    Beispiele: Resource Server
      | component       | containerName                             | specificBody |
      | Resource Server | ${telemetry.containerName.resourceServer} | *HelloZeta*  |

  @dev
  @A_25669
  @TA_A_25669_04
  @TA_A_25669_05
  @TA_A_25669_06
  @TA_A_28439_01
  Szenario: PEP überschreibt vom Client gesetzte ZETA-Header und aktualisiert den Forwarded-Header
    # Setze gefälschte ZETA-Header per TigerProxy-Manipulation - diese werden vom Client mitgesendet
    Gegeben sei TGR setze lokale Variable "fakeHeaderCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.userInfo.root}" und Wert "FAKE_USER_INFO"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.poppTokenContent}" und Wert "FAKE_POPP_CONTENT"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.zeta.clientData}" und Wert "FAKE_CLIENT_DATA"
    Und Setze im TigerProxy für die Nachricht "${fakeHeaderCondition}" die Manipulation auf Feld "${headers.forwarded.root}" und Wert "for=client;proto=http"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - gefälschte Header wurden vom Client gesendet
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.helloZetaPath}" übereinstimmt
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.root}" überein mit "FAKE_USER_INFO"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent}" überein mit "FAKE_POPP_CONTENT"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData}" überein mit "FAKE_CLIENT_DATA"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.forwarded.root}"
    Und TGR speichere Wert des Knotens "${headers.forwarded.root}" der aktuellen Anfrage in der Variable "forwardedBefore"

    # Prüfe Request NACH PEP - Header wurden vom PEP überschrieben (nicht mehr FAKE_*)
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.forwarded.root}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.forwarded.root}" nicht überein mit "${forwardedBefore}"
    Und TGR speichere Wert des Knotens "${headers.forwarded.root}" der aktuellen Anfrage in der Variable "forwardedAfter"
    # RFC 7239: bestehender Forwarded-Header bleibt erhalten und es wird ein weiteres gültiges Forwarded-Element (for|by|proto|host) angehängt
    Und TGR prüfe Variable "forwardedAfter" stimmt überein mit "^for=client;proto=http\\s*,\\s*(for|by|proto|host)=.+$"

    # Prüfe, dass alle drei ZETA-Header vorhanden sind
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.poppTokenContent}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData}"

    # TA_A_25669_04: ZETA-User-Info wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.root}" nicht überein mit "FAKE_USER_INFO"

    # TA_A_25669_05: ZETA-PoPP-Token-Content wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent}" nicht überein mit "FAKE_POPP_CONTENT"

    # TA_A_25669_06: ZETA-Client-Data wurde überschrieben (nicht mehr FAKE-Wert)
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientData}" nicht überein mit "FAKE_CLIENT_DATA"

  @dev
  @A_27725
  @TA_A_27725_02
  Szenario: Telemetrie-Daten enthalten bei erfolgreicher Operation den HTTP-Statuscode 200
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^${paths.fachdienst.helloZetaPath}" übereinstimmt
    Und TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.path" der aktuellen Anfrage in der Variable "resourcePath"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s)
    Und warte 70 Sekunden

    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                   | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:${telemetry.containerName.httpProxy} AND attributes.log.file.path:/var/log/pods/* AND body:\"${resourcePath}\" AND http.status:200 AND @timestamp:[now-3m TO now] | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source['http.status']" überein mit "200"

  @dev
  @A_27399
  @TA_A_27399_05
  Szenario: Sessiondaten nach session_expiry nicht mehr verwendbar
    # TTL-Werte als Variablen definieren (in Sekunden)
    Wenn TGR setze lokale Variable "accessTokenTtl" auf "30"
    Und TGR setze lokale Variable "refreshTokenTtl" auf "100"

    # OPA Decision manipulieren: Kurze TTL für Refresh Token setzen
    # Die OPA-Response bestimmt die tatsächliche Token-Gültigkeit im Authorization Server
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "${refreshTokenTtl}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen

    # Client zurücksetzen und ersten Token holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token.body.sid" der aktuellen Antwort in der Variable "oldSessionId"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "session_expiry"

    # Warte bis session_expiry erreicht ist
    Und warte "${refreshTokenTtl}" Sekunden
    Und validiere, dass der Zeitstempel "${session_expiry}" in der Vergangenheit liegt

    # Nachrichten löschen, damit nur der nächste Resource-Request geprüft wird
    Und TGR lösche aufgezeichnete Nachrichten

    # DPoP-Key holen und Access Token manipulieren (jti alt)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.dpop_private_key" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für Access Token das Feld "body.sid" auf Wert "${oldSessionId}" mit Access Token Key "${signingKey}" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" nicht überein mit "200"

    # Überprüfe zusätzlich, dass eine neue Session verwendet wird
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sid" nicht überein mit "${oldSessionId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.refresh_token.body.sid" nicht überein mit "${oldSessionId}"
