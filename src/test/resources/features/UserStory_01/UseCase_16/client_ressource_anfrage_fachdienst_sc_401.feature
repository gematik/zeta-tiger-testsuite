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

@UseCase_01_16
Funktionalität: Client Ressource Anfrage Fachdienst SC 401
  @A_25663
  @A_28963
  @TA_A_25663_01
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  @require_signing_key
  Szenario: Ressourcenanfrage wird abgelehnt, wenn Access Token an manipulierten DPoP Key gebunden ist
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Dann TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "previousSessionDpopJwt"
    Und berechne JKT aus JWT Header JWK "${previousSessionDpopJwt}" und speichere in Variable "previousSessionDpopJkt"

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "tokenDpopJwt"
    Und berechne JKT aus JWT Header JWK "${tokenDpopJwt}" und speichere in Variable "tokenDpopJkt"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" überein mit "${tokenDpopJkt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.cnf.jkt" nicht überein mit "${previousSessionDpopJkt}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "currentDpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"
    Dann Setze im TigerProxy für Access Token das Feld "body.cnf.jkt" auf Wert "${previousSessionDpopJkt}" mit Access Token Key "${signingKey}" und DPoP Key "${currentDpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "resourceDpopJwt"
    Und berechne JKT aus JWT Header JWK "${resourceDpopJwt}" und speichere in Variable "resourceDpopJkt"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.dpop.body.ath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" überein mit "${previousSessionDpopJkt}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.cnf.jkt}" nicht überein mit "${resourceDpopJkt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

  @A_25767
  @A_28963
  @A_26661
  @A_27007
  @A_27725-01
  @TA_A_25767_02
  @TA_A_28963_01
  @TA_A_26661_19
  @TA_A_27007_19
  @TA_A_27725-01_31
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  @MASVS-RESILIENCE
  Szenario: DPoP Resource Request - Guard lehnt fremdes JWK (Binding) ab
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"

    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "header.typ" auf Wert "dpop+jwt" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    # zweites reset, damit der Client beim nächsten Aufruf ein neues DPoP Keypair verwendet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR lösche aufgezeichnete Nachrichten
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                        | operation                       | start           | end           | limit | tags                                                                                               |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"401","http.target":"${paths.guard.helloZetaPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

  @A_25767
  @A_28963
  @TA_A_25767_02
  @TA_A_28963_01
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenario: DPoP Resource Request - Wiederverwendung desselben jti wird abgewiesen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und erzeuge eindeutige DPoP jti und speichere in Variable "resourceReplayJti"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.jti" auf Wert "${resourceReplayJti}" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 2 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten

    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.jti}" überein mit "${resourceReplayJti}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.jti}" überein mit "${resourceReplayJti}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @A_25767
  @A_28963
  @TA_A_25767_02
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  @MASVS-AUTH
  Szenariogrundriss: DPoP JWT Manipulation Test - Resource Anfrage (<JwtField>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Und TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Vorladen der Nachrichten, damit die nachfolgende Suche nach dem manipulierten Wert schneller durchläuft
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.dpop.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Beispiele: Manipulationen
      | JwtField   | NeuerWert                  |
      | header.typ | JWT                        |
      | header.alg | HS256                      |
      | header.alg | RS999                      |
      | body.iat   | 1600000000                 |
      | body.ath   | wronghash                  |
      | body.htm   | POST                       |
      | body.htu   | https://wrong.url/resource |

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft einen DPoP Proof mit Header-Algorithmus none beim Ressourcenzugriff
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "variant.name" auf Wert "alg_none" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.dpop.header.alg}" der mit "none" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante alg_none in "${manipulatedDpopJwt}" ist lokal strukturell angewendet
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard verwirft einen DPoP Proof ohne Pflicht-Claim <Claim> beim Ressourcenzugriff
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"

    Dann Entferne im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.<Claim>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.dpop.body.<Claim>}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und verifiziere ES256 Signatur von DPoP JWT "${manipulatedDpopJwt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Beispiele: Fehlende Pflicht-Claims
      | Claim |
      | jti   |
      | htm   |
      | htu   |
      | iat   |
      | ath   |

  @A_28963
  @TA_A_28963_01
  @normal
  @MASVS-CRYPTO
  Szenariogrundriss: ZETA Guard verwirft einen fehlerhaften oder nicht akzeptierten DPoP Proof beim Ressourcenzugriff (<Variante>)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "variant.name" auf Wert "<Variante>" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedDpopJwt}" hat lokal die erwartete Signaturintegrität
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

    Beispiele: manipulierte JWT-Varianten
      | Variante                           |
      | two_segments                       |
      | invalid_header_base64url           |
      | invalid_payload_base64url          |
      | invalid_signature_base64url        |
      | invalid_header_json                |
      | invalid_header_json_unquoted_keys  |
      | invalid_payload_json               |
      | invalid_payload_json_unquoted_keys |
      | unknown_header_parameter           |
      | unsupported_crit                   |
      | unsupported_alg                    |
      | missing_alg                        |
      | duplicate_alg_headers              |
      | invalid_signature                  |
      | jwe_like_five_segments             |
      | nested_cty_jwt_invalid_inner       |

  @A_28963
  @TA_A_28963_01
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft doppelte DPoP Header beim Ressourcenzugriff
    Wenn TGR setze lokale Variable "pathCondition" auf "isRequest && request.path =~ '.*${paths.guard.helloZetaPath}'"
    Und Dupliziere im TigerProxy für die Nachricht "${pathCondition}" den Header "DPoP"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und prüfe aktuelle Anfrage enthält den Header "DPoP" 2 mal
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @A_28963
  @TA_A_28963_01
  @MASVS-CRYPTO
  Szenario: ZETA Guard verwirft einen DPoP Proof mit privatem JWK Anteil beim Ressourcenzugriff
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "header.jwk.d" auf Wert "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" mit privatem Schlüssel "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.dpop.header.jwk.d}" der mit "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @require_signing_key
  Szenariogrundriss: ZETA Guard Integrationstest, JWT Prüfung (<JwtField>)
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
    Und TGR setze lokale Variable "wrongSigningKey" auf "!{file('src/test/resources/keys/popp-token-server_ecKey.pem')}"

    # Der unveränderte Aufruf belegt, dass Route und übrige Akzeptanzbedingungen grundsätzlich gültig sind.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Der Client-DPoP-Key wird benötigt, damit der Proxy ath und die DPoP-Signatur konsistent aktualisiert.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"

    # Die Manipulation gilt genau einmal und nur für den geprüften Guard-Pfad.
    Dann Setze im TigerProxy für Access Token das Feld "<JwtField>" auf Wert "<NeuerWert>" mit Access Token Key "<AccessTokenKey>" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen

    # Alte Nachrichten werden entfernt, damit alle folgenden Nachweise zum manipulierten Aufruf gehören.
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Pfad und geänderter Feldwert identifizieren den tatsächlich beim Guard eingegangenen Request.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt

    # Die lokale Prüfung gegen den Live-JWKS schließt eine unbeabsichtigt falsche Access-Token-Signatur aus.
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "authorizationHeader"
    Und extrahiere DPoP Access Token aus Authorization Header "${authorizationHeader}" und speichere in Variable "manipulatedAccessToken"
    Und prüfe die JWT-Variante <Signaturvariante> in "${manipulatedAccessToken}" hat lokal mit KeyStore "${KEY_STORE}" die erwartete Signaturintegritaet

    # ath und DPoP-Signatur belegen die Bindung des DPoP-Proofs an genau diesen manipulierten Access Token.
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und berechne SHA256 Hash von "${manipulatedAccessToken}" und speichere in Variable "expectedDpopAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${expectedDpopAth}"
    Und verifiziere ES256 Signatur von DPoP JWT "${manipulatedDpopJwt}"

    # Erst nach den Vorbedingungen wird die fachlich erwartete Guard-Antwort geprüft.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    @A_27802-02
    @TA_A_27802-02_01
    @TA_A_27802-02_02
    @MASVS-CRYPTO
    Beispiele: Manipulationen (JWT)
      | JwtField   | NeuerWert        | ResponseCode | AccessTokenKey     | Signaturvariante  |
      | header.alg | ES256            | 200          | ${signingKey}      | resign_correct_key |
      | header.alg | ES256            | 401          | ${wrongSigningKey} | invalid_signature  |
      | header.alg | RS1              | 401          | ${signingKey}      | resign_correct_key |
      | header.typ | dpop             | 401          | ${signingKey}      | resign_correct_key |
      | body.exp   | 1758719276       | 401          | ${signingKey}      | resign_correct_key |
      | body.iss   | someone          | 401          | ${signingKey}      | resign_correct_key |

    @A_28525-01
    @TA_A_28525-01_02
    Beispiele: Step-up-Bedingung bei nicht passender Audience
      | JwtField | NeuerWert                                                    | ResponseCode | AccessTokenKey | Signaturvariante  |
      | body.aud | unknown                                                      | 401          | ${signingKey}  | resign_correct_key |
      | body.aud | ${paths.guard.baseUrl}${paths.guard.helloZetaProxyErrorPath} | 401          | ${signingKey}  | resign_correct_key |

  @A_28525-01
  @TA_A_28525-01_01
  @deployment_modification
  @require_signing_key
  Szenario: Fehlender Scope löst HTTP 401 aus
    # Deployment-Modification: Der gültige Access Token enthält den erforderlichen Scope "email".
    Gegeben sei setze die erforderlichen Scopes "email" für die Route "${paths.guard.helloZetaPath}" im ZETA Deployment

    # Der aktuelle JWKS ist die Referenz für die Signatur des tatsächlich beim Guard empfangenen Access Tokens.
    Gegeben sei TGR sende eine leere "GET" Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "KEY_STORE"

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Gültigen Ausgangszustand herstellen und mit HTTP 200 bestätigen.
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # DPoP-Key holen, damit der manipulierte Access Token weiterhin korrekt gebunden bleibt.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"

    # Scope entfernen, alle übrigen Token- und DPoP-Eigenschaften unverändert lassen.
    Dann Setze im TigerProxy für Access Token das Feld "body.scope" auf Wert "missing_required_scope" mit Access Token Key "${signingKey}" und DPoP Key "${dpopKey}" für Pfad ".*${paths.guard.helloZetaPath}" und 1 Ausführungen

    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Der Request enthält den manipulierten Scope; die lokale Prüfung schließt eine falsche Access-Token-Signatur als Ablehnungsgrund aus.
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.authorization.dpopToken.root}.body.scope" der mit "missing_required_scope" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "authorizationHeader"
    Und extrahiere DPoP Access Token aus Authorization Header "${authorizationHeader}" und speichere in Variable "manipulatedAccessToken"
    Und prüfe die JWT-Variante resign_correct_key in "${manipulatedAccessToken}" hat lokal mit KeyStore "${KEY_STORE}" die erwartete Signaturintegritaet

    # Nachweis: Die Anfrage mit gültiger Signatur und fehlendem Scope wird mit HTTP 401 abgelehnt.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

  @A_27802-02
  @TA_A_27802-02_01
  @TA_A_27802-02_02
  @MASVS-CRYPTO
  @require_signing_key
  Szenariogrundriss: ZETA Guard verwirft fehlerhafte oder nicht akzeptierte Access Token JWT Varianten (<Variante>)
    Gegeben sei TGR sende eine leere "GET" Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "KEY_STORE"

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"
    Und TGR setze lokale Variable "ecKeyFilePath" auf "${paths.guard.ecKeyFile}"
    Und TGR setze lokale Variable "signingKey" auf "!{file('${ecKeyFilePath}')}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "dpopKey"

    Dann Setze im TigerProxy für Access Token das Feld "variant.name" auf Wert "<Variante>" mit Access Token Key "${signingKey}" und DPoP Key "${dpopKey}" für Pfad "${pathCondition}" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "authorizationHeader"
    Und extrahiere DPoP Access Token aus Authorization Header "${authorizationHeader}" und speichere in Variable "manipulatedAccessToken"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "manipulatedDpopJwt"
    Und prüfe die JWT-Variante <Variante> in "${manipulatedAccessToken}" ist lokal strukturell angewendet
    Und prüfe die JWT-Variante <Variante> in "${manipulatedAccessToken}" hat lokal mit KeyStore "${KEY_STORE}" die erwartete Signaturintegritaet
    Und berechne SHA256 Hash von "${manipulatedAccessToken}" und speichere in Variable "expectedDpopAth"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "${expectedDpopAth}"
    Und verifiziere ES256 Signatur von DPoP JWT "${manipulatedDpopJwt}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"

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
    Beispiele: invalid_signature
      | Variante                           |
      | invalid_signature                  |

    @normal
    Beispiele: jwe_like_five_segments
      | Variante                           |
      | jwe_like_five_segments             |

    @normal
    Beispiele: nested_cty_jwt_invalid_inner
      | Variante                           |
      | nested_cty_jwt_invalid_inner       |
