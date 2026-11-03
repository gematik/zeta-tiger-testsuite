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
  @smb
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
  @A_26477-01
  @deployment_modification
  @TA_A_26452_02
  @TA_A_26477-01_02 # Gültigkeitsdauer seit Ausstellung
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
  Szenario: PoPP Token Gültigkeitsdauer seit Ausstellung wird validiert
    Gegeben sei setze die PoPP Token Gültigkeit im ZETA Deployment auf "300s"
    Und aktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und aktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaProxyErrorPath}" im ZETA Deployment
    Und setze die PoPP Token Gültigkeit "30s" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment mit Uhrtoleranz 0 Sekunden
    Und setze die PoPP Token Gültigkeit "120s" für die Route "${paths.guard.helloZetaProxyErrorPath}" im ZETA Deployment mit Uhrtoleranz 0 Sekunden
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "CURRENT_IAT"
    Und TGR setze lokale Variable "CURRENT_IAT" auf "!{${CURRENT_IAT} - 2}"
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" auf Wert "${CURRENT_IAT}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR setze lokale Variable "poppTokenMaxAgeSeconds" auf "30"

    Wenn TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iat}" der mit "${CURRENT_IAT}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "PoPP_TOKEN_IAT"
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_IAT}" höchstens "${poppTokenMaxAgeSeconds}" Sekunden alt ist
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "POPP_TIME_CONTROL"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "POPP_TIME_AUTHORIZATION"
    Und verifiziere die ES256 Signatur des JWT "${POPP_TIME_CONTROL}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZetaProxyError}" mit folgenden Headern:
      | PoPP | ${POPP_TIME_CONTROL} |
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaProxyErrorPath}" und Knoten "${headers.popp.root}" der mit "\Q${POPP_TIME_CONTROL}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    Wenn speichere den aktuellen Unix-Zeitstempel in der Variable "POPP_TIME_NOW"
    Und TGR setze lokale Variable "POPP_TIME_OLD_IAT" auf "!{${POPP_TIME_NOW} - 60}"
    Und Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" auf Wert "${POPP_TIME_OLD_IAT}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${POPP_TIME_CONTROL} |
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iat}" der mit "${POPP_TIME_OLD_IAT}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "\Q${POPP_TIME_AUTHORIZATION}\E"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "POPP_TIME_OLD"
    Und verifiziere die ES256 Signatur des JWT "${POPP_TIME_OLD}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und die JWTs "${POPP_TIME_CONTROL}" und "${POPP_TIME_OLD}" unterscheiden sich ausschließlich im Claim "iat"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZetaProxyError}" mit folgenden Headern:
      | PoPP | ${POPP_TIME_OLD} |
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaProxyErrorPath}" und Knoten "${headers.popp.root}" der mit "\Q${POPP_TIME_OLD}\E" übereinstimmt
    Und validiere, dass der Zeitstempel "${POPP_TIME_OLD_IAT}" höchstens "120" Sekunden alt ist
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    Wenn speichere den aktuellen Unix-Zeitstempel in der Variable "POPP_TIME_NOW"
    Und TGR setze lokale Variable "CURRENT_IAT" auf "!{${POPP_TIME_NOW} - 2}"
    Und Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" auf Wert "${CURRENT_IAT}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${POPP_TIME_CONTROL} |
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iat}" der mit "${CURRENT_IAT}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und validiere, dass der Zeitstempel "${CURRENT_IAT}" höchstens "30" Sekunden alt ist
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "POPP_TIME_CONTROL"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "POPP_TIME_AUTHORIZATION"
    Und verifiziere die ES256 Signatur des JWT "${POPP_TIME_CONTROL}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"

    Wenn Entferne im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und behalte den JWT-Header
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${POPP_TIME_CONTROL} |
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.root}" der mit "(?s)(?!.*\x22iat\x22\s*:).*" übereinstimmt
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.body.iat}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "\Q${POPP_TIME_AUTHORIZATION}\E"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "POPP_TIME_MISSING_IAT"
    Und verifiziere die ES256 Signatur des JWT "${POPP_TIME_MISSING_IAT}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und die JWTs "${POPP_TIME_CONTROL}" und "${POPP_TIME_MISSING_IAT}" unterscheiden sich ausschließlich im Claim "iat"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26477-01
  @deployment_modification
  # TA_A_26477-01_03 verlangt zusätzlich getrennte Endpunkt-Konfiguration;
  # der bestehende positive Quartalsfall bleibt als Teilnachweis erhalten.
  @MASVS-AUTH
  @smb
  Szenario: PoPP Token Ausstellungszeitpunkt liegt im Quartal des Prüfzeitpunkts
    Gegeben sei setze die PoPP Token Gültigkeit im ZETA Deployment auf "quarter"
    Und aktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Die SDK-Fixture kann ein älteres PoPP-Token enthalten; für positive Prüfungen frisch ausstellen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMCB-INFO.telematikId}" und Profession "${SMCB-INFO.professionId}" und speichere in der Variable "FRESH_POPP_TOKEN"

    # Sende Resource Anfrage
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Prüfzeitpunkt ist der aktuelle Validierungszeitpunkt.
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "PoPP_TOKEN_IAT"
    Und validiere, dass der Zeitstempel "${PoPP_TOKEN_IAT}" im aktuellen Quartal liegt

  @A_25669-01
  @A_26477-01
  @TA_A_25669-01_05
  @TA_A_26477-01_05 # Signatur muss mathematisch gültig sein
  @TA_A_26477-01_08 # popp-token.actorId == access_token.sub
  @TA_A_26477-01_12 # gültiges vorhandenes JWKS oder erneuter Download
  @deployment_modification
  @MASVS-CODE
  @MASVS-AUTH
  @smb
  Szenario: PoPP-Signaturprüfung mit gültigem vorhandenem oder erneut geladenem JWKS
    Gegeben sei skaliere das Deployment "${zetaDeploymentConfig.pep.podName}" auf 1 Replikas
    Und der PEP verwendet für die Cache-Beobachtung genau einen Worker
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR setze lokale Variable "poppSignedJwksPath" auf "${paths.popp.signedJwks}"
    Und TGR setze lokale Variable "poppSignedJwksResponseCondition" auf "isResponse && request.path =~ '.*${poppSignedJwksPath}'"
    Und TGR setze lokale Variable "poppDiscoveryCacheControl" auf "public, max-age=300"
    # Der Mock kann ohne Cache-Control antworten: strikter Pfad legt den Header dann an.
    Und Setze im TigerProxy für die Nachricht "${poppSignedJwksResponseCondition}" die Manipulation auf Feld "$.header.['Cache-Control']" und Wert "${poppDiscoveryCacheControl}" und 1000 Ausführungen
    Und setze die PoPP Token Gültigkeit im ZETA Deployment auf "300s"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Die SDK-Fixture kann ein älteres PoPP-Token enthalten; für positive Prüfungen frisch ausstellen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMCB-INFO.telematikId}" und Profession "${SMCB-INFO.professionId}" und speichere in der Variable "FRESH_POPP_TOKEN"

    Gegeben sei aktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    # Vorbereitung: erster Abruf stellt sicher, dass ein gültiges JWKS vorliegt.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # "PoPP Token werden im Request Header PoPP übertragen".
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

     # TA_A_26477-01_05 - PEP HTTP Proxy - PoPP Token Validierung - Signatur mathematisch gültig
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"

    # Ein frischer Cache ist eine beobachtete Vorbedingung, kein Schluss aus HTTP 200 allein.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *= *300.*"
    Und merke den Empfangszeitpunkt der aktuellen Antwort als Cache-Beginn

    # Die Quelle erlaubt vorhandene gültige Schlüssel ODER erneuten Download.
    # Beide Wege bleiben zulässig; der frühere 500-Zwang mit Download-Verbot war unbegründet.
    Und Setze im TigerProxy für die Nachricht "$.path =~ '.*${paths.guard.helloZetaPath}$' && $.method == 'GET'" die Manipulation auf Feld "${headers.zeta.poppTokenContent.strict}" und Wert "CC_FORGED_POPP_CONTENT" und 1 Ausführungen
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.poppTokenContent.root}" überein mit "CC_FORGED_POPP_CONTENT"
    Und prüfe, dass die aktuelle Anfrage innerhalb der gemerkten Cache-Gültigkeit von 300 Sekunden liegt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    # TA_A_26477-01_08 - PEP HTTP Proxy - PoPP Token Validierung - Übereinstimmung von claim
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_SUB"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "${ACCESS_TOKEN_SUB}"

    # Den bereits getaggten Header-Überschreibungsaspekt am korrelierten Backend-Hop nachweisen.
    Und TGR speichere Wert des Knotens "${headers.popp.body.root}" der aktuellen Anfrage in der Variable "POPP_CONTROL_PAYLOAD"
    Dann TGR finde die nächste Anfrage mit Pfad "^${paths.fachdienst.helloZetaPath}" und Knoten "${headers.popp.root}" der mit "\Q${PoPP_TOKEN}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request im Knoten "${headers.zeta.poppTokenContent.root}" stimmt nicht überein mit "CC_FORGED_POPP_CONTENT"
    Und TGR speichere Wert des Knotens "${headers.zeta.poppTokenContent.root}" der aktuellen Anfrage in der Variable "POPP_FORWARDED_CONTENT"
    Und prüfe "${POPP_FORWARDED_CONTENT}" ist striktes Base64-URL Format
    Und TGR speichere Wert des Knotens "${headers.zeta.poppTokenContent.decoded.root}" der aktuellen Anfrage in der Variable "POPP_FORWARDED_PAYLOAD"
    Und prüfe Knoten "${POPP_FORWARDED_PAYLOAD}" enthält mindestens alle Kindknoten von "${POPP_CONTROL_PAYLOAD}"
    Und prüfe Knoten "${POPP_CONTROL_PAYLOAD}" enthält mindestens alle Kindknoten von "${POPP_FORWARDED_PAYLOAD}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # Gegenkontrolle gegen übersprungene Signaturprüfung: identische Claims und kid,
    # mathematisch korrekte Signatur eines fremden Schlüssels. Nur die erste manipulierte Anfrage zählt.
    Und TGR speichere Wert des Knotens "${headers.popp.root}.header.kid" der aktuellen Anfrage in der Variable "POPP_CONTROL_KID"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "POPP_CONTROL_IAT"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iss}" der aktuellen Anfrage in der Variable "POPP_CONTROL_ISS"
    Und TGR setze lokale Variable "POPP_FOREIGN_KEY" auf "!{file('src/test/resources/keys/popp-token-foreign_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.actorId" auf Wert "${ACCESS_TOKEN_SUB}" mit privatem Schlüssel "${POPP_FOREIGN_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.root}.header.kid" überein mit "\Q${POPP_CONTROL_KID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "\Q${ACCESS_TOKEN_SUB}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iat}" überein mit "${POPP_CONTROL_IAT}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iss}" überein mit "\Q${POPP_CONTROL_ISS}\E"
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" höchstens "300" Sekunden alt ist
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "POPP_NEGATIVE_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${POPP_NEGATIVE_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${POPP_FOREIGN_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    # Gegenkontrolle für actorId: richtige Signatur und unveränderte Zeit-/Issuer-/kid-Bedingungen.
    Und TGR setze lokale Variable "POPP_WRONG_ACTOR" auf "urn:telematik:cc-unrelated-actor"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.actorId" auf Wert "${POPP_WRONG_ACTOR}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.root}.header.kid" überein mit "\Q${POPP_CONTROL_KID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iat}" überein mit "${POPP_CONTROL_IAT}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iss}" überein mit "\Q${POPP_CONTROL_ISS}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${ACCESS_TOKEN_SUB}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "\Q${POPP_WRONG_ACTOR}\E"
    Und TGR prüfe aktueller Request im Knoten "${headers.popp.body.actorId}" stimmt nicht überein mit "\Q${ACCESS_TOKEN_SUB}\E"
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" höchstens "300" Sekunden alt ist
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "POPP_WRONG_ACTOR_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${POPP_WRONG_ACTOR_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26477-01
  @TA_A_26477-01_15
  @smb
  @deployment_modification
  @popp_deployment_toggle
  @MASVS-AUTH
  Szenariogrundriss: PoPP Token Validierung ist pro Endpunkt (<GuardEndpointPfad>) konfigurierbar
    Gegeben sei skaliere das Deployment "${zetaDeploymentConfig.pep.podName}" auf 1 Replikas
    Und der PEP verwendet für die Cache-Beobachtung genau einen Worker
    Und TGR setze lokale Variable "INVALID_POPP_ACTOR_ID" auf "urn:telematik:invalid-actor"
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.pepRoutePrefix}" im ZETA Deployment
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaProxyErrorPath}" im ZETA Deployment
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.erezept.rest.pepPath}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Gegeben sei aktiviere die PoPP Token Verifikation für die Route "<GuardEndpointPfad>" im ZETA Deployment
    Und hole frisches PoPP Token aus dem Generator für Akteur "${INVALID_POPP_ACTOR_ID}" und Profession "${testdata.popp.actorProfessionOid}" und speichere in der Variable "PoPP_TOKEN"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "poppHeaderCondition" auf "isRequest && request.path =~ '.*<GuardEndpointPfad>'"
    Und Setze im TigerProxy für die Nachricht "${poppHeaderCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "${headers.popp.lineRegex}" und Wert "" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "<ClientEndpoint>"
    Dann TGR finde die erste Anfrage mit Pfad "<GuardEndpointPfad>"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<ClientEndpoint>"
    Dann TGR finde die erste Anfrage mit Pfad "<GuardEndpointPfad>"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<AntwortKonfigurierteRoute>"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "^<FachdienstPfad>$" und Knoten "$.method" der mit "GET" übereinstimmt erfolgt
    Dann TGR finde die erste Anfrage mit Pfad "^<FachdienstPfad>$"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.responseCode"

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
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "^<NichtKonfigurierterFachdienstPfad1>$" und Knoten "$.method" der mit "GET" übereinstimmt erfolgt
    Dann TGR finde die erste Anfrage mit Pfad "^<NichtKonfigurierterFachdienstPfad1>$"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.responseCode"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "<NichtKonfigurierterClientEndpoint2>" mit folgenden Headern:
      | PoPP | ${PoPP_TOKEN} |
    Dann TGR finde die erste Anfrage mit Pfad "<NichtKonfigurierterGuardEndpointPfad2>" und Knoten "${headers.popp.body.actorId}" der mit "${INVALID_POPP_ACTOR_ID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<AntwortNichtKonfigurierteRoute2>"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "^<NichtKonfigurierterFachdienstPfad2>$" und Knoten "$.method" der mit "GET" übereinstimmt erfolgt
    Dann TGR finde die erste Anfrage mit Pfad "^<NichtKonfigurierterFachdienstPfad2>$"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.responseCode"

    Beispiele: Konfiguration der PoPP Token Validierung pro Endpunkt
      | ClientEndpoint | GuardEndpointPfad | FachdienstPfad | AntwortKonfigurierteRoute | NichtKonfigurierterClientEndpoint1 | NichtKonfigurierterGuardEndpointPfad1 | NichtKonfigurierterFachdienstPfad1 | AntwortNichtKonfigurierteRoute1 | NichtKonfigurierterClientEndpoint2 | NichtKonfigurierterGuardEndpointPfad2 | NichtKonfigurierterFachdienstPfad2 | AntwortNichtKonfigurierteRoute2 |
      | ${paths.client.helloZeta} | ${paths.guard.helloZetaPath} | ${paths.fachdienst.helloZetaPath} | 200 | ${paths.client.helloZetaProxyError} | ${paths.guard.helloZetaProxyErrorPath} | ${paths.fachdienst.helloZetaProxyErrorPath} | 500 | ${paths.client.baseUrl}${paths.erezept.rest.proxyPath} | ${paths.erezept.rest.pepPath} | ${paths.erezept.rest.rsPath} | 200 |
      | ${paths.client.helloZetaProxyError} | ${paths.guard.helloZetaProxyErrorPath} | ${paths.fachdienst.helloZetaProxyErrorPath} | 500 | ${paths.client.helloZeta} | ${paths.guard.helloZetaPath} | ${paths.fachdienst.helloZetaPath} | 200 | ${paths.client.baseUrl}${paths.erezept.rest.proxyPath} | ${paths.erezept.rest.pepPath} | ${paths.erezept.rest.rsPath} | 200 |
      | ${paths.client.baseUrl}${paths.erezept.rest.proxyPath} | ${paths.erezept.rest.pepPath} | ${paths.erezept.rest.rsPath} | 200 | ${paths.client.helloZeta} | ${paths.guard.helloZetaPath} | ${paths.fachdienst.helloZetaPath} | 200 | ${paths.client.helloZetaProxyError} | ${paths.guard.helloZetaProxyErrorPath} | ${paths.fachdienst.helloZetaProxyErrorPath} | 500 |

  @A_26477-01
  @TA_A_26477-01_07
  @deployment_modification
  @longrunning
  @MASVS-AUTH
  @smb
  Szenario: PoPP JWKS wird vor Ablauf aktualisiert
    Gegeben sei TGR setze lokale Variable "poppSignedJwksPath" auf "${paths.popp.signedJwks}"
    Und TGR setze Anfrage Timeout auf 180 Sekunden
    Und skaliere das Deployment "${zetaDeploymentConfig.pep.podName}" auf 1 Replikas
    Und der PEP verwendet für die Cache-Beobachtung genau einen Worker
    Und deaktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMCB-INFO.telematikId}" und Profession "${SMCB-INFO.professionId}" und speichere in der Variable "FRESH_POPP_TOKEN"

    Gegeben sei bereite ein korrekt signiertes PoPP-JWKS auf Pfad "${poppSignedJwksPath}" mit 180 Sekunden JWT-Gültigkeit in Variable "POPP_JWKS" vor
    Und Setze im TigerProxy für die Nachricht "${POPP_JWKS.condition}" die Manipulation auf Feld "$.body" und Wert "${POPP_JWKS.body}" und 1000 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${POPP_JWKS.condition}" die Manipulation auf Feld "$.header.['Cache-Control']" und Wert "public, max-age=180" und 1000 Ausführungen
    Und aktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *= *180.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "\Q${POPP_JWKS.body}\E"
    Und merke die aktuelle JWKS-Antwort nach vollständigem Empfang vor Ablauf der signierten Gültigkeit
    Und validiere, dass der Zeitstempel "${POPP_JWKS.expires}" in der Zukunft liegt

    Dann TGR finde die nächste Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und prüfe, dass der aktuelle Download mit Pfad "${poppSignedJwksPath}" vor Ablauf von 180 Sekunden seit Empfang der vorherigen Antwort mit diesem Pfad vollständig abgeschlossen wurde
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "\Q${POPP_JWKS.body}\E"
    Und die aktuelle JWKS-Antwort ist eine neue vollständig vor Ablauf der gemerkten signierten Gültigkeit empfangene Antwort
