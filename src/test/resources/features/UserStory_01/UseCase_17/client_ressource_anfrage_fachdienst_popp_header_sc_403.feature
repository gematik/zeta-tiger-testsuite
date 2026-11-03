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

  @A_29849
  @TA_A_29849_01
  @TA_A_29849_02
  @smb
  Szenariogrundriss: Client authentifiziert nach terminierter Session infolge 403 des PEP HTTP Proxy erneut (<terminationError>)
    # Initiale Client-Sitzung für die nachfolgende PEP-Ablehnung vorbereiten.
    # F01: Das Zurücksetzen entfernt einen zuvor gespeicherten Client-Session-Kontext.
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    # F02: Der erste Ressourcenzugriff erzeugt die initiale authentifizierte Client-Session.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # F03: Die Client-Antwort bestätigt die erfolgreiche initiale Sitzung.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    # F04: Nur eine erfolgreiche Ressourcenanfrage liefert die gültige Ausgangssitzung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F05: Die initiale Token-Antwort liefert den zu schützenden Session-Kontext.
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    # F06: Der Subject Token ist der Vergleichsanker für eine neue Nutzer-Authentifizierung.
    Und TGR speichere Wert des Knotens "$.body.subject_token" der aktuellen Anfrage in der Variable "terminatedSessionSubjectToken"

    # Nur die vom PEP ausgelöste Reaktion und ihre Folgeanfragen aufzeichnen.
    # F07: Das Leeren isoliert die PEP-Ablehnung und die darauf folgende Authentifizierung.
    Und TGR lösche aufgezeichnete Nachrichten
    # F08: Die Bedingung bindet die Terminierungsantwort an den PEP HTTP Proxy.
    Und TGR setze lokale Variable "pepTerminationCondition" auf "isResponse && request.path =~ '.*${paths.guard.helloZetaPath}'"
    # F09: Der PEP HTTP Proxy liefert die endgültige Ablehnung.
    Und Setze im TigerProxy für die Nachricht "${pepTerminationCondition}" die Manipulation auf Feld "$.responseCode" und Wert "403" und 1 Ausführungen
    # F10: Der Fehlerkörper kennzeichnet die terminierte Sitzung an der PEP-Schnittstelle.
    Und Setze im TigerProxy für die Nachricht "${pepTerminationCondition}" die Manipulation auf Feld "$.body" und Wert "!{file('src/test/resources/mocks/<errorBody>')}" und 1 Ausführungen
    # F11: Der Herkunftsheader kennzeichnet die Terminierungsantwort als PEP-Fehler.
    Und Setze im TigerProxy für die Nachricht "${pepTerminationCondition}" die Manipulation auf Feld "$.header.zeta-error-origin" und Wert "pep" und 1 Ausführungen

    # Die terminierte Sitzung muss zur vollständigen Neu-Authentifizierung führen.
    # F12: Der Ressourcenzugriff empfängt die vom PEP HTTP Proxy ausgelöste Ablehnung.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # F13: Der erste PEP-Aufruf belegt den konkreten Statuscode.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    # F14: Das Access Token der abgelehnten PEP-Anfrage dient als Vergleichswert.
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}" der aktuellen Anfrage in der Variable "terminatedSessionAccessToken"
    # F15: Die PEP-Antwort bestätigt die endgültige Ablehnung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    # F16: Der Fehlerwert kennzeichnet die terminierte Sitzung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error" überein mit "<terminationError>"
    # F17: Die Terminierungsantwort enthält die für die interne Client-Fehlerbehandlung erforderliche PEP-Kennzeichnung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.[~'zeta-error-origin']" überein mit "pep"
    # F18: Der Token Exchange nach der Ablehnung leitet die erneute Authentifizierung ein.
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    # F19: Der neue Subject Token im Exchange belegt TA_A_29849_01.
    Und TGR prüfe aktueller Request im Knoten "$.body.subject_token" stimmt nicht überein mit "^\Q${terminatedSessionSubjectToken}\E$"
    # F20: Die erfolgreiche Antwort bestätigt die erneute Authentifizierung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # F21: Das ausgegebene Access Token korreliert den Exchange mit dem folgenden PEP-Zugriff.
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "renewedSessionAccessToken"
    # F22: Der einzige Token Exchange schließt für TA_A_29849_02 einen zusätzlichen Refresh im Aufzeichnungsfenster aus.
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    # Das neu ausgegebene Access Token am wiederholten PEP-Zugriff nachweisen.
    # F23: Der nächste PEP-Aufruf ist der Wiederholungsversuch nach der erneuten Authentifizierung.
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    # F24: Der wiederholte PEP-Zugriff verwendet genau das nach der Ablehnung ausgegebene Access Token.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}" überein mit "^\Q${renewedSessionAccessToken}\E$"
    # F25: Der wiederholte PEP-Zugriff verwendet nicht das Access Token der terminierten Sitzung.
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}" stimmt nicht überein mit "^\Q${terminatedSessionAccessToken}\E$"

    Beispiele:
      | terminationError      | errorBody                                 |
      | session_terminated    | zeta-error-403-session-terminated.json    |
      | refresh_token_revoked | zeta-error-403-refresh-token-revoked.json |

  @A_26477-01
  @A_26452
  @A_26661
  @A_27725-01
  @TA_A_26477-01_08
  @TA_A_26452_03
  @TA_A_26661_20
  @TA_A_27725-01_32
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
  Szenario: PoPP Token mit abweichender actorId wird mit HTTP 403 abgelehnt und in der Telemetrie erfasst
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMCB-INFO.telematikId}" und Profession "${SMCB-INFO.professionId}" und speichere in der Variable "FRESH_POPP_TOKEN"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_SUB"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "^\Q${ACCESS_TOKEN_SUB}\E$"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR speichere Wert des Knotens "${headers.popp.header.kid}" der aktuellen Anfrage in der Variable "POPP_CONTROL_KID"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iss}" der aktuellen Anfrage in der Variable "POPP_CONTROL_ISS"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "POPP_CONTROL_IAT"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '^${paths.guard.helloZetaPath}$'"
    Und TGR setze lokale Variable "WRONG_ACTOR_ID" auf "evil_client"
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.actorId" auf Wert "${WRONG_ACTOR_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Und Alle Manipulationen im TigerProxy werden gestoppt

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "^\Q${WRONG_ACTOR_ID}\E$"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "^\Q${ACCESS_TOKEN_SUB}\E$"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "POPP_NEGATIVE_SUB"
    Und TGR prüfe aktueller Request im Knoten "${headers.popp.body.actorId}" stimmt nicht überein mit "^\Q${POPP_NEGATIVE_SUB}\E$"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.header.kid}" überein mit "^\Q${POPP_CONTROL_KID}\E$"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iss}" überein mit "^\Q${POPP_CONTROL_ISS}\E$"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iat}" überein mit "${POPP_CONTROL_IAT}"
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" höchstens "300" Sekunden alt ist
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
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

  @A_26452
  @TA_A_26452_03
  @normal
  @MASVS-CRYPTO
  @smb
  Szenario: PoPP Token ohne actorId wird mit HTTP 403 abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMCB-INFO.telematikId}" und Profession "${SMCB-INFO.professionId}" und speichere in der Variable "FRESH_POPP_TOKEN"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "ACCESS_TOKEN_SUB"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "^\Q${ACCESS_TOKEN_SUB}\E$"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und TGR setze lokale Variable "POPP_CONTROL_TOKEN" auf "${PoPP_TOKEN}"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "POPP_CONTROL_IAT"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '^${paths.guard.helloZetaPath}$'"
    Dann Entferne im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.actorId" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und behalte den JWT-Header
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Und Alle Manipulationen im TigerProxy werden gestoppt

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.popp.body.actorId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "^\Q${ACCESS_TOKEN_SUB}\E$"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iat}" überein mit "${POPP_CONTROL_IAT}"
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${POPP_CONTROL_IAT}" höchstens "300" Sekunden alt ist
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und die JWTs "${POPP_CONTROL_TOKEN}" und "${PoPP_TOKEN}" unterscheiden sich ausschließlich im Claim "actorId"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26450
  @A_26477-01
  @A_26493-01
  @TA_A_26450_03
  @TA_A_26477-01_12
  @TA_A_26493-01_01
  @normal
  @MASVS-AUTH
  @MASVS-CRYPTO
  @deployment_modification
  @smb
  Szenario: PoPP JWKS wird bei unbekanntem PoPP kid heruntergeladen
    Gegeben sei aktiviere die PoPP Token Verifikation für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und setze die PoPP Token Gültigkeit im ZETA Deployment auf "300s"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppSignedJwksPath" auf "${paths.popp.signedJwks}"
    Und TGR setze lokale Variable "poppSignedJwksResponseCondition" auf "isResponse && request.path =~ '.*${poppSignedJwksPath}'"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "UNKNOWN_POPP_KID" auf "unknown_popp_kid"

    # Die SDK-Fixture kann ein älteres PoPP-Token enthalten; für die isolierte kid-Prüfung frisch ausstellen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMCB-INFO.telematikId}" und Profession "${SMCB-INFO.professionId}" und speichere in der Variable "FRESH_POPP_TOKEN"

    # Erfolgreicher Kontrollzugriff mit dem frisch ausgestellten, unveränderten PoPP-Token.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request im Knoten "${headers.popp.root}.header.kid" stimmt nicht überein mit "\Q${UNKNOWN_POPP_KID}\E"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "POPP_KID_CONTROL_IAT"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iss}" der aktuellen Anfrage in der Variable "POPP_KID_CONTROL_ISS"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "POPP_KID_CONTROL_SUB"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "\Q${POPP_KID_CONTROL_SUB}\E"

    # Ein unbekanntes kid macht ein vorhandenes JWKS für diese Signaturprüfung unzureichend.
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "header.kid" auf Wert "${UNKNOWN_POPP_KID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    # Die Ablehnung darf auch bei langsamen Läufen nicht durch Alter, Issuer oder Akteur erklärt sein.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iat}" überein mit "${POPP_KID_CONTROL_IAT}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iss}" überein mit "\Q${POPP_KID_CONTROL_ISS}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "\Q${POPP_KID_CONTROL_SUB}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${POPP_KID_CONTROL_SUB}\E"
    Und validiere, dass der Zeitstempel "${POPP_KID_CONTROL_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${POPP_KID_CONTROL_IAT}" höchstens "300" Sekunden alt ist
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${poppSignedJwksPath}" und Knoten "$.method" der mit "GET" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200|304"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "header.kid" auf Wert "${UNKNOWN_POPP_KID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${poppSignedJwksResponseCondition}" die Manipulation auf Feld "$.responseCode" und Wert "500" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${FRESH_POPP_TOKEN} |

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    # Die Ablehnung darf auch bei langsamen Läufen nicht durch Alter, Issuer oder Akteur erklärt sein.
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iat}" überein mit "${POPP_KID_CONTROL_IAT}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.iss}" überein mit "\Q${POPP_KID_CONTROL_ISS}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "\Q${POPP_KID_CONTROL_SUB}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${POPP_KID_CONTROL_SUB}\E"
    Und validiere, dass der Zeitstempel "${POPP_KID_CONTROL_IAT}" in der Vergangenheit liegt
    Und validiere, dass der Zeitstempel "${POPP_KID_CONTROL_IAT}" höchstens "300" Sekunden alt ist
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${poppSignedJwksPath}" und Knoten "$.method" der mit "GET" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppSignedJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.header.kid" der mit "${UNKNOWN_POPP_KID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26450
  @TA_A_26450_02
  @MASVS-CRYPTO
  @smb
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

  @A_26477-01
  @TA_A_26477-01_10
  @MASVS-AUTH
  @smb
  Szenariogrundriss: ZETA Guard verwirft nicht akzeptierte PoPP Token JWT Parsing- oder Header-Varianten (<Variante>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"

    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" die Variante <Variante> mit Parameter '<Parameter>' mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "manipulatedPoPPToken"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedPoPPToken}" mit Parameter '<Parameter>' ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedPoPPToken}" hat lokal mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}" die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    @normal
    Beispiele: two_segments
      | Variante     | Parameter |
      | two_segments |           |

    @normal
    Beispiele: invalid_header_base64url
      | Variante                 | Parameter |
      | invalid_header_base64url |           |

    @normal
    Beispiele: invalid_payload_base64url
      | Variante                  | Parameter |
      | invalid_payload_base64url |           |

    @normal
    Beispiele: invalid_signature_base64url
      | Variante                    | Parameter |
      | invalid_signature_base64url |           |

    @normal
    Beispiele: invalid_header_json
      | Variante            | Parameter |
      | invalid_header_json |           |

    @normal
    Beispiele: invalid_header_json_unquoted_keys
      | Variante                          | Parameter |
      | invalid_header_json_unquoted_keys |           |

    @normal
    Beispiele: invalid_payload_json
      | Variante             | Parameter |
      | invalid_payload_json |           |

    @normal
    Beispiele: invalid_payload_json_unquoted_keys
      | Variante                           | Parameter |
      | invalid_payload_json_unquoted_keys |           |

    @normal
    Beispiele: unsupported_crit
      | Variante         | Parameter |
      | unsupported_crit |           |

    @normal
    Beispiele: unsupported_alg
      | Variante        | Parameter |
      | unsupported_alg |           |

    @normal
    Beispiele: missing_alg
      | Variante    | Parameter |
      | missing_alg |           |

    @normal
    Beispiele: duplicate_alg_headers
      | Variante              | Parameter         |
      | duplicate_alg_headers | ["ES256","RS999"] |

    @normal
    Beispiele: jwe_like_five_segments
      | Variante               | Parameter |
      | jwe_like_five_segments |           |

    @normal
    Beispiele: nested_cty_jwt_invalid_inner
      | Variante                     | Parameter |
      | nested_cty_jwt_invalid_inner |           |

  @A_26477-01
  @A_29850
  @TA_A_26477-01_09
  @TA_A_29850_02
  @normal
  @MASVS-AUTH
  @smb
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
    # Der Antwortheader weist die von TA_A_29850_02 geforderte Kennzeichnung des PEP-Fehlers nach
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.zeta.errorOrigin.root}" überein mit "pep"

  @A_26452
  @TA_A_26452_01
  @MASVS-CRYPTO
  @smb
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

  @A_26477-01
  @A_26661
  @deployment_modification
  @TA_A_26477-01_10
  @TA_A_26661_20
  @MASVS-AUTH
  @smb
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
  @A_26477-01
  @A_26661
  @deployment_modification
  @TA_A_26452_02
  # TA_A_26477-01_03 verlangt getrennte Endpunkt-Konfiguration.
  # Dieser globale Quartalsfall bleibt mit allen Kontrollen als Teilnachweis erhalten.
  @TA_A_26477-01_10
  @TA_A_26661_20
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
  Szenario: PoPP Token mit Ausstellungszeitpunkt außerhalb des Prüfzeitpunkt-Quartals wird abgelehnt
    Gegeben sei setze die PoPP Token Gültigkeit im ZETA Deployment auf "quarter"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    # Die unveränderte SDK-Fixture muss im aktuellen Quartal akzeptiert werden,
    # bevor allein iat sicher außerhalb des Quartals und der Kulanz gesetzt wird.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.popp.body.iat}" der aktuellen Anfrage in der Variable "POPP_QUARTER_CONTROL_IAT"
    Und validiere, dass der Zeitstempel "${POPP_QUARTER_CONTROL_IAT}" im aktuellen Quartal liegt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "POPP_QUARTER_CONTROL_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${POPP_QUARTER_CONTROL_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"

    Und speichere einen Zeitstempel aus dem vorletzten Quartal in der Variable "IAT_OUTSIDE_QUARTER"

    # Für die Quartalsprüfung wird das PoPP-Token weiter mit dem echten PoPP-Server-Key signiert.
    Dann Setze im TigerProxy für JWT in "${headers.popp.strict}" das Feld "body.iat" auf Wert "${IAT_OUTSIDE_QUARTER}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.iat}" der mit "${IAT_OUTSIDE_QUARTER}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}" mit öffentlichem Schlüssel aus privatem Schlüssel "${PoPP_PRIVATE_KEY}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26450
  @A_26477-01
  @A_26661
  @TA_A_26450_01
  @TA_A_26477-01_09
  @TA_A_26661_20
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  @smb
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

  @A_26477-01
  @A_26661
  @TA_A_26477-01_06
  @TA_A_26661_20
  @normal
  @MASVS-AUTH
  @smb
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

  @A_29853
  @TA_A_29853_02
  @normal
  Szenario: Client reicht einen Fachdienst-Fehler mit HTTP 403 ohne PEP-Fehler-Header transparent durch
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Ausgangslage: erfolgreicher Zugriff, damit ein gueltiges Access Token vorliegt.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Beide Kontroll-Hops müssen im Recorder abgeschlossen sein, bevor die Negativphase beginnt.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR lösche aufgezeichnete Nachrichten

    # Der Fehler entsteht im Fachdienst (hinter dem PEP) und wird vom PEP lediglich durchgereicht.
    Und TGR setze lokale Variable "fachdienstErrorCondition" auf "isResponse && request.path =~ '.*${paths.fachdienst.helloZetaPath}'"
    Und TGR setze lokale Variable "fachdienstErrorBody" auf "fachdienst-fehlerbody-403"
    # Derselbe eindeutige Testmarker ermöglicht den korrelierten Nachweis der unveränderten Weitergabe für TA_A_29853_02.
    Und TGR setze lokale Variable "fachdienstErrorMarker" auf "fachdienst-fehler-403"
    Und Setze im TigerProxy für die Nachricht "${fachdienstErrorCondition}" die Manipulation auf Feld "$.responseCode" und Wert "403" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${fachdienstErrorCondition}" die Manipulation auf Feld "$.body" und Wert "${fachdienstErrorBody}" und 1 Ausführungen
    Und Setze im TigerProxy für die Nachricht "${fachdienstErrorCondition}" die Manipulation auf Feld "$.header.['x-zeta-test-error']" und Wert "${fachdienstErrorMarker}" und 1 Ausführungen

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "${fachdienstErrorBody}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.[~'x-zeta-test-error']" überein mit "${fachdienstErrorMarker}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "${fachdienstErrorBody}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.[~'x-zeta-test-error']" überein mit "${fachdienstErrorMarker}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.zeta.errorOrigin.root}"

    # TA_A_29853_02 - Statuscode, Body und Marker-Header erreichen den aufrufenden Fach-Client unverändert.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "${fachdienstErrorBody}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.header.[~'x-zeta-test-error']" überein mit "${fachdienstErrorMarker}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.zeta.errorOrigin.root}"

    # Ohne PEP-Fehler-Header leitet der Client weder Token-Erneuerung noch Ressourcen-Retry ein.
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.guard.tokenEndpointPath}" hat

    Und Alle Manipulationen im TigerProxy werden gestoppt


  @Ignore
  @MASVS-AUTH
  @A_29676
  @TA_A_29676_01
  @TA_A_29676_02
  @deployment_modification
  @require_signing_key
  Szenariogrundriss: CC Strict Audience-Matching für Tokenversion <Version>
    # Der aktuelle JWKS ist die Referenz für die Signatur des tatsächlich beim Guard empfangenen Access Tokens.
    Gegeben sei TGR sende eine leere "GET" Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "KEY_STORE"

    # Reset, Zielpfad und Signing-Keys stellen für jedes Beispiel einen definierten Ausgangszustand her.
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"

    # Der unveränderte Aufruf belegt, dass Route und übrige Akzeptanzbedingungen grundsätzlich gültig sind.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "VALID_AUDIENCE_CLAIMS"
    Und setze die erwartete Audience aus Access Token Claims "${VALID_AUDIENCE_CLAIMS}" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Client-DPoP-Key wird benötigt, damit der Proxy ath und die DPoP-Signatur konsistent aktualisiert.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"

    # Beide Tokenverträge benötigen eine eigene positive Kontrolle. Tiger setzt nur ver,
    # signiert das echte SDK-Token erneut und aktualisiert den gebundenen DPoP-Proof.
    Dann Setze im TigerProxy für Access Token das numerische Feld "body.ver" auf Wert <Version> mit Access Token Key "${signingKey}" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Die positive Kontrolle belegt auch, dass der überwachte Fachdienst-Pfad tatsächlich sichtbar ist.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.body.ver" der mit "<Version>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "VERSIONED_AUDIENCE_CLAIMS"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "required": [
          "ver"
        ],
        "properties": {
          "ver": {
            "$ref": "classpath:schemas/v_2_0/schemas/access-token.yaml#/properties/payload/properties/ver"
          }
        },
        "additionalProperties": true
      }
      """
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.aud" überein mit "(?!.*cc_unrelated_service).*"

    # Nur die logische Audience der Route wechselt. Token-aud, Schlüssel, Scope und acr
    # bleiben gegenüber der positiven Kontrolle unverändert; die Version bleibt explizit.
    Und setze die erwartete Audience aus Access Token Claims '{"aud":"cc_unrelated_service"}' für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment
    Dann Setze im TigerProxy für Access Token das numerische Feld "body.ver" auf Wert <Version> mit Access Token Key "${signingKey}" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen

    # Alte Nachrichten werden entfernt, damit alle folgenden Nachweise zum manipulierten Aufruf gehören.
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Die tatsächlich eingegangene Tokenversion muss numerisch und unverändert sein.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.body.ver" der mit "<Version>" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body" der aktuellen Anfrage in der Variable "REJECTED_AUDIENCE_CLAIMS"
    Und TGR prüfe aktueller Request im Knoten "${headers.authorization.dpopToken.root}.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "required": [
          "ver"
        ],
        "properties": {
          "ver": {
            "$ref": "classpath:schemas/v_2_0/schemas/access-token.yaml#/properties/payload/properties/ver"
          }
        },
        "additionalProperties": true
      }
      """
    Und TGR prüfe Variable "REJECTED_AUDIENCE_CLAIMS" stimmt überein mit "\Q${VERSIONED_AUDIENCE_CLAIMS}\E"

    # Die lokale Prüfung gegen den Live-JWKS schließt eine unbeabsichtigt falsche Access-Token-Signatur aus.
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "authorizationHeader"
    Und extrahiere DPoP Access Token aus Authorization Header "${authorizationHeader}" und speichere in Variable "manipulatedAccessToken"
    Und prüfe die JWT-Variante resign_correct_key in "${manipulatedAccessToken}" hat lokal mit KeyStore "${KEY_STORE}" die erwartete Signaturintegritaet

    # ath und DPoP-Signatur belegen die Bindung des DPoP-Proofs an genau diesen manipulierten Access Token.
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und berechne SHA256 Hash von "${manipulatedAccessToken}" und speichere in Variable "expectedDpopAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${expectedDpopAth}"
    Und verifiziere ES256 Signatur von DPoP JWT "${manipulatedDpopJwt}"

    # Der Verlauf seit dem Löschen umfasst auch verspätet aufgezeichnete Weiterleitungen und SDK-Retries.
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.fachdienst.helloZetaPath}" hat
    # Der eigenständige Statusvertrag bleibt auch bei erfolgreicher Nichtweiterleitung verbindlich.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Beispiele: Nicht zur Route passende Audience bei gültiger Signatur
      | Version |
      | 1       |
      | 2       |
