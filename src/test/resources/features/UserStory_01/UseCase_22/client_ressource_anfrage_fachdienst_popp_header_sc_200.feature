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

@UseCase_01_22
Funktionalität: Client Ressource Anfrage Fachdienst PoPP-Header SC 200

  # Gemäß Fachaustausch vom 02.07.2026 prüft der PoPP-Verifier des ZETA Guard
  # ausschließlich die Claims iss, iat und actorId. Alle weiteren Claims prüft der Fachdienst.
  # Quelle: https://wiki.gematik.de/spaces/P114ZT/pages/764657578/2026-07-02+Fachaustausch

  @A_26450
  @A_26452
  @TA_A_26450_02
  @TA_A_26452_01
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: PoPP Token Header und Claims werden bei gültigem Token akzeptiert
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_26450_02 - Header-Attribute typ, alg und kid sind vorhanden und fachlich korrekt.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.header.typ}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.header.alg}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.header.kid}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.header.typ}" überein mit "vnd[.]telematik[.]popp[+]jwt"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.header.alg}" überein mit "ES256"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.header.kid}" überein mit ".+"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.signature.isValid}" überein mit "true"

    # TA_A_26452_01 - iss ist vorhanden und enthält das Entity-Statement-sub des PoPP-Service.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.body.iss}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iss}" überein mit ".*${testdata.popp.entityStatementSub}.*"

    # Die folgenden Prüfungen weisen nur den Inhalt des am Guard eingegangenen PoPP-Tokens nach.
    # Aus dem Vorhandensein der Claims und der erfolgreichen Antwort wird keine PEP-seitige Validierung abgeleitet.
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.body.version}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.body.proofMethod}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.body.patientProofTime}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.body.patientId}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.body.insurerId}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.body.actorProfessionOid}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.version}" überein mit "1[.]0[.]0"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.proofMethod}" überein mit "${testdata.popp.proofMethod}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.patientId}" überein mit "${testdata.popp.patientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.insurerId}" überein mit "${testdata.popp.insurerId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorProfessionOid}" überein mit "${testdata.popp.actorProfessionOid}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.patientProofTime}" der aktuellen Anfrage in der Variable "PoPP_PATIENT_PROOF_TIME"
    Und validiere, dass der Zeitstempel "${PoPP_PATIENT_PROOF_TIME}" in der Vergangenheit liegt

  @A_26452
  @A_26477
  @deployment_modification
  @TA_A_26452_02
  @TA_A_26477_02 # Gültigkeitsdauer seit Ausstellung
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: PoPP Token Gültigkeitsdauer seit Ausstellung wird validiert
    Gegeben sei setze die PoPP Token Gültigkeit im ZETA Deployment auf "300s"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "CURRENT_IAT"
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" auf Wert "${CURRENT_IAT}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR setze lokale Variable "poppTokenMaxAgeSeconds" auf "300"

    # Sende Resource Anfrage
    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iat}" der mit "${CURRENT_IAT}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Zeitstempel "iat" ist nicht älter als konfiguriert
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "PoPP_TOKEN_IAT"
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_IAT}" höchstens "${poppTokenMaxAgeSeconds}" Sekunden alt ist

  @A_26477
  @deployment_modification
  @TA_A_26477_03 # Ausstellungszeitpunkt liegt im Quartal des Prüfzeitpunkts
  @MASVS-AUTH
  Szenario: PoPP Token Ausstellungszeitpunkt liegt im Quartal des Prüfzeitpunkts
    Gegeben sei setze die PoPP Token Gültigkeit im ZETA Deployment auf "quarter"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Sende Resource Anfrage
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Prüfzeitpunkt ist der aktuelle Validierungszeitpunkt.
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "PoPP_TOKEN_IAT"
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_IAT}" im aktuellen Quartal liegt

  @A_25669-01
  @A_26477
  @TA_A_25669-01_05
  @TA_A_26477_05 # Signatur muss mathematisch gültig sein
  @TA_A_26477_08 # popp-token.actorId == access_token.sub
  @TA_A_26477_12 # vorhandenes und noch gültiges JWKS wird verwendet
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: PoPP Token Validation - vorhandenes gültiges JWKS wird verwendet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppSignedJwksPath" auf "${paths.popp.signedJwks}"
    Und TGR setze lokale Variable "poppSignedJwksResponseCondition" auf "isResponse && request.path =~ '.*${poppSignedJwksPath}'"
    Und TGR setze lokale Variable "poppDiscoveryCacheControl" auf "public, max-age=5"
    Und Setze im TigerProxy für die Nachricht "${poppSignedJwksResponseCondition}" die Manipulation auf Feld "${headers.cacheControl}" und Wert "${poppDiscoveryCacheControl}"

    # Vorbereitung: erster Abruf stellt sicher, dass ein gültiges JWKS vorliegt.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # "PoPP Token werden im Request Header PoPP übertragen".
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

     # TA_A_26477_05 - PEP HTTP Proxy - PoPP Token Validierung - Signatur mathematisch gültig
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"

    # TA_A_26477_12 - vorhandenes und noch gültiges JWKS wird verwendet.
    # Ein erneuter Download wird hier absichtlich gestört; der Abruf muss dennoch mit dem
    # bereits geladenen gültigen JWKS erfolgreich bleiben.
    Und Setze im TigerProxy für die Nachricht "${poppSignedJwksResponseCondition}" die Manipulation auf Feld "$.responseCode" und Wert "500" und 1 Ausführungen
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${poppSignedJwksPath}" hat
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    # TA_A_26477_08 - PEP HTTP Proxy - PoPP Token Validierung - Übereinstimmung von claim
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_SUB"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "${ACCESS_TOKEN_SUB}"

  @A_26477
  @TA_A_26477_01
  @deployment_modification
  @popp_deployment_toggle
  @MASVS-AUTH
  Szenariogrundriss: PoPP Token Validierung ist pro Endpunkt (<GuardEndpointPfad>) konfigurierbar
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR setze lokale Variable "INVALID_POPP_ACTOR_ID" auf "urn:telematik:invalid-actor"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${INVALID_POPP_ACTOR_ID}" und Profession "${testdata.popp.actorProfessionOid}" und speichere in der Variable "PoPP_TOKEN"
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaProxyErrorPath}" im ZETA Deployment
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.erezept.rest.pepPath}" im ZETA Deployment
    Und aktiviere die PoPP Token Verifikation für die Route "<GuardEndpointPfad>" im ZETA Deployment

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "poppHeaderCondition" auf "isRequest && request.path =~ '.*<GuardEndpointPfad>'"
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "${headers.popp.lineRegex}" und Wert ""
    Wenn TGR sende eine leere GET Anfrage an "<ClientEndpoint>"
    Dann TGR finde die erste Anfrage mit Pfad "<GuardEndpointPfad>"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<ClientEndpoint>"
    Dann TGR finde die erste Anfrage mit Pfad "<GuardEndpointPfad>"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<AntwortKonfigurierteRoute>"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<ClientEndpoint>" mit folgenden Headern:
      | PoPP | ${PoPP_TOKEN} |
    Dann TGR finde die erste Anfrage mit Pfad "<GuardEndpointPfad>" und Knoten "${headers.popp.body.actorId}" der mit "${INVALID_POPP_ACTOR_ID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<NichtKonfigurierterClientEndpoint1>" mit folgenden Headern:
      | PoPP | ${PoPP_TOKEN} |
    Dann TGR finde die erste Anfrage mit Pfad "<NichtKonfigurierterGuardEndpointPfad1>" und Knoten "${headers.popp.body.actorId}" der mit "${INVALID_POPP_ACTOR_ID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<AntwortNichtKonfigurierteRoute1>"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<NichtKonfigurierterClientEndpoint2>" mit folgenden Headern:
      | PoPP | ${PoPP_TOKEN} |
    Dann TGR finde die erste Anfrage mit Pfad "<NichtKonfigurierterGuardEndpointPfad2>" und Knoten "${headers.popp.body.actorId}" der mit "${INVALID_POPP_ACTOR_ID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<AntwortNichtKonfigurierteRoute2>"

    Beispiele: Konfiguration der PoPP Token Validierung pro Endpunkt
      | ClientEndpoint                                         | GuardEndpointPfad                      | AntwortKonfigurierteRoute | NichtKonfigurierterClientEndpoint1                    | NichtKonfigurierterGuardEndpointPfad1 | AntwortNichtKonfigurierteRoute1 | NichtKonfigurierterClientEndpoint2                   | NichtKonfigurierterGuardEndpointPfad2 | AntwortNichtKonfigurierteRoute2 |
      | ${paths.client.helloZeta}                              | ${paths.guard.helloZetaPath}           | 200                       | ${paths.client.helloZetaProxyError}                   | ${paths.guard.helloZetaProxyErrorPath} | 500                             | ${paths.client.baseUrl}${paths.erezept.rest.proxyPath} | ${paths.erezept.rest.pepPath}         | 200                             |
      | ${paths.client.helloZetaProxyError}                    | ${paths.guard.helloZetaProxyErrorPath} | 500                       | ${paths.client.helloZeta}                             | ${paths.guard.helloZetaPath}           | 200                             | ${paths.client.baseUrl}${paths.erezept.rest.proxyPath} | ${paths.erezept.rest.pepPath}         | 200                             |
      | ${paths.client.baseUrl}${paths.erezept.rest.proxyPath} | ${paths.erezept.rest.pepPath}          | 200                       | ${paths.client.helloZeta}                             | ${paths.guard.helloZetaPath}           | 200                             | ${paths.client.helloZetaProxyError}                   | ${paths.guard.helloZetaProxyErrorPath} | 500                             |

  @A_26477
  @TA_A_26477_07
  @longrunning
  @MASVS-AUTH
  Szenario: PoPP JWKS wird vor Ablauf aktualisiert
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppSignedJwksPath" auf "${paths.popp.signedJwks}"
    Und TGR setze lokale Variable "poppSignedJwksResponseCondition" auf "isResponse && request.path =~ '.*${poppSignedJwksPath}'"
    Und TGR setze lokale Variable "poppJwksCacheControl" auf "public, max-age=5"
    Und TGR setze Anfrage Timeout auf 10 Sekunden
    Und Setze im TigerProxy für die Nachricht "${poppSignedJwksResponseCondition}" die Manipulation auf Feld "${headers.cacheControl}" und Wert "${poppJwksCacheControl}" und 2 Ausführungen

    # Erster Ressource Abruf triggert JWKS-Download mit kurzer Cache-Dauer
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *= *5.*"

    # TA_A_26477_07 - über kurze Cache-Control-Dauer wird ein JWKS-Update vor Ablauf erzwungen
    Dann TGR finde die nächste Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und prüfe, dass die aktuelle Anfrage mit Pfad "${poppSignedJwksPath}" vor Ablauf von 5 Sekunden seit der vorherigen Anfrage mit diesem Pfad gesendet wurde
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
