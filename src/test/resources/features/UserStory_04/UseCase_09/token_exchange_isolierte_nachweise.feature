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

@UseCase_04_09
@require_kubectl
@smb
Funktionalität: Signaturen und Schlüsselbindungen im SDK-Token-Exchange getrennt validieren

  Grundlage:
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_CONTROL_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_CONTROL_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_CONTROL_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${CC_CONTROL_JWKS}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_CONTROL_CLIENT}\E"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "CC_CONTROL_ASSERTION"
    Und berechne JKT aus JWT Header JWK "${CC_CONTROL_ASSERTION}" und speichere in Variable "CC_CONTROL_CLIENT_JKT"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_CONTROL_DPOP"
    Und berechne JKT aus JWT Header JWK "${CC_CONTROL_DPOP}" und speichere in Variable "CC_CONTROL_DPOP_JKT"
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.opa.decisionPath}" haben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_CONTROL_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten

  # Pflichtclaim-Teilnachweise für TA_A_29376_09; weitere JOSE-Varianten bleiben offen.
  @A_25661-01
  @A_29376
  @TA_A_25661-01_10
  @TA_A_29376_09
  @smcb_assertion_candidate
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  Szenariogrundriss: Korrekt signierte SDK-Client-Assertion ohne Pflichtclaim <Claim>
    # Neue Registrierung nach der Kontrolle; danach bleibt derselbe SDK-Schlüssel erhalten.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_ASSERTION_INITIAL_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_ASSERTION_INITIAL_CLIENT"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CC_ASSERTION_PRIVATE_KEY"
    # Genau ein Pflichtfeld entfernen und mit dem unveränderten registrierten Schlüssel signieren.
    Wenn Entferne im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.<Claim>" mit privatem Schlüssel "${CC_ASSERTION_PRIVATE_KEY}" für Pfad ".*${paths.guard.tokenEndpointPath}" und 20 Ausführungen und ersetze JWK
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    # 401 kann eine SDK-Neuregistrierung auslösen; jede Registrierung bleibt separat nachweisbar.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NEGATIVE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NEGATIVE_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_NEGATIVE_CLIENT}\E"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler assertion_missing_<Claim> bei registriertem JWKS "${CC_NEGATIVE_JWKS}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.zeta_attestation_token"
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat
    Und alle SMC-B-Exchanges derselben SDK-Schlüsselinstanz ab Client "${CC_ASSERTION_INITIAL_CLIENT}" mit JWKS "${CC_ASSERTION_INITIAL_JWKS}" und isoliertem Fehler assertion_missing_<Claim> liefern Status 401 und Fehler invalid_client ohne Tokens

    Beispiele: Fehlende Assertion-Pflichtclaims
      | Claim |
      | iss   |
      | sub   |
      | aud   |
      | exp   |
      | jti   |

  # Weitere Teilnachweise für TA_A_29376_09; vollständige Assertion-Validierung bleibt offen.
  @smcb_assertion_candidate
  @normal
  Szenariogrundriss: Korrekt signierte SDK-Client-Assertion mit isoliertem Fehler <Fehler>
    # Neue Registrierung nach der Kontrolle; danach bleibt derselbe SDK-Schlüssel erhalten.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_ASSERTION_INITIAL_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_ASSERTION_INITIAL_CLIENT"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "CC_ASSERTION_PRIVATE_KEY"
    # Nur ein Feld ändern und korrekt signieren; die unabhängige Prüfung bindet den JWK an DCR.
    Wenn Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "<Feld>" auf Wert "<Wert>" mit privatem Schlüssel "${CC_ASSERTION_PRIVATE_KEY}" für Pfad ".*${paths.guard.tokenEndpointPath}" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    # 401 kann eine SDK-Neuregistrierung auslösen; jede Registrierung bleibt separat nachweisbar.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NEGATIVE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NEGATIVE_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_NEGATIVE_CLIENT}\E"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler <Fehler> bei registriertem JWKS "${CC_NEGATIVE_JWKS}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.zeta_attestation_token"
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat
    Und alle SMC-B-Exchanges derselben SDK-Schlüsselinstanz ab Client "${CC_ASSERTION_INITIAL_CLIENT}" mit JWKS "${CC_ASSERTION_INITIAL_JWKS}" und isoliertem Fehler <Fehler> liefern Status 401 und Fehler invalid_client ohne Tokens

    @A_25661-01
    @A_29376
    @TA_A_25661-01_10
    @TA_A_29376_09
    @MASVS-AUTH
    @MASVS-CODE
    Beispiele: Unabhängige Assertion-Claims
      | Fehler            | Feld       | Wert                          |
      | assertion_typ     | header.typ | cc-invalid-assertion          |
      | assertion_iss     | body.iss   | cc-invalid-issuer             |
      | assertion_sub     | body.sub   | cc-invalid-subject            |
      | assertion_aud     | body.aud.0 | https://invalid.example/token |
      | assertion_expired | body.exp   | 1700000000                    |

  @normal
  Szenariogrundriss: Nur die <Nachweis>-Signatur des frischen SDK-Requests ist ungültig
    # Tiger verändert nur jti ohne Neusignierung; automatische Wiederholungen bleiben manipuliert.
    Wenn Setze im TigerProxy für JWT in "<JwtPath>" das Feld "body.jti" auf Wert "<Marker>" für Pfad ".*${paths.guard.tokenEndpointPath}" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NEGATIVE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NEGATIVE_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_NEGATIVE_CLIENT}\E"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler <Fehler> bei registriertem JWKS "${CC_NEGATIVE_JWKS}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.zeta_attestation_token"
    # Drei Sekunden zusätzliches Recorder-Fenster; anschließend wird der gesamte Verlauf geprüft.
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat
    Und alle aufgezeichneten SMC-B-Exchanges für Client "${CC_NEGATIVE_CLIENT}" mit JWKS "${CC_NEGATIVE_JWKS}" und isoliertem Fehler <Fehler> liefern Status 400 und Fehler <ErrorCode> ohne Tokens

    @A_25661-01
    @A_29376
    @TA_A_25661-01_09
    @TA_A_29376_10
    @smcb_dpop_candidate
    @MASVS-AUTH
    @MASVS-CODE
    Beispiele: DPoP-Signatur
      | Nachweis | JwtPath                | Marker          | Fehler         | ErrorCode          |
      | DPoP     | ${headers.dpop.strict} | cc-invalid-dpop | dpop_signature | invalid_dpop_proof |

    @A_29376
    @TA_A_29376_11
    @smcb_subject_candidate
    Beispiele: SMC-B-Signatur
      | Nachweis      | JwtPath              | Marker             | Fehler            | ErrorCode     |
      | Subject Token | $.body.subject_token | cc-invalid-subject | subject_signature | invalid_grant |

  @A_29376
  @normal
  Szenariogrundriss: Gültig signiertes Subject Token bindet allein den falschen <Bindung>
    # Der SDK-Request bleibt die Stimulation. Der kontrollierte Test-SMC-B-Schlüssel erlaubt
    # die Neusignierung genau einer Bindung; dies prüft die AS-Validierung, keine SDK-Erzeugung.
    Gegeben sei lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "SMCB_CERT"
    Wenn Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "body.<Fehler>.jkt" auf Wert "<FremderJkt>" mit privatem Schlüssel "${SMCB_CERT.prv_pem}" für Pfad ".*${paths.guard.tokenEndpointPath}" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NEGATIVE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NEGATIVE_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_NEGATIVE_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.<Fehler>.jkt" überein mit "\Q<FremderJkt>\E"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler <Fehler> bei registriertem JWKS "${CC_NEGATIVE_JWKS}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.zeta_attestation_token"
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat
    Und alle aufgezeichneten SMC-B-Exchanges für Client "${CC_NEGATIVE_CLIENT}" mit JWKS "${CC_NEGATIVE_JWKS}" und isoliertem Fehler <Fehler> liefern Status 400 und Fehler invalid_grant ohne Tokens

    @TA_A_29376_03
    Beispiele: DCR-Bindung
      | Bindung           | Fehler     | FremderJkt                |
      | DCR-Client-Key    | client_key | ${CC_CONTROL_CLIENT_JKT}  |

    @TA_A_29376_15
    Beispiele: DPoP-Bindung
      | Bindung           | Fehler   | FremderJkt              |
      | DPoP-Signatur-Key | dpop_key | ${CC_CONTROL_DPOP_JKT}  |

  @A_25661-01
  @TA_A_25661-01_12
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  Szenario: Leerer Pflichtparameter subject_token_type im ansonsten gültigen SDK-Exchange
    # RFC 6749 behandelt leere Parameterwerte wie ausgelassene Parameter. Der vollständige
    # positive SDK-Exchange der Grundlage beweist die erforderlichen gültigen Credentials.
    Wenn Setze im TigerProxy für die Nachricht "$.path =~ '.*${paths.guard.tokenEndpointPath}$' && $.method == 'POST'" die Regex-Manipulation auf Feld "$.body" mit Regex "(?<=&)subject_token_type=[^&]*|^subject_token_type=[^&]*" und Wert "subject_token_type=" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NEGATIVE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NEGATIVE_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und der SMC-B-Exchange enthält ausschließlich den Fehler empty_subject_type bei registriertem JWKS "${CC_NEGATIVE_JWKS}"
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat
    Und alle aufgezeichneten SMC-B-Exchanges für Client "${CC_NEGATIVE_CLIENT}" mit JWKS "${CC_NEGATIVE_JWKS}" und isoliertem Fehler empty_subject_type liefern Status 400 und Fehler invalid_request ohne Tokens

  @A_25661-01
  @TA_A_25661-01_13
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  Szenariogrundriss: Unzulässiges Zeichen <Zeichen> im ansonsten gültigen SDK-Scope
    # RFC 6749 Abschnitt 3.3 schließt beide Zeichen in scope-token ausdrücklich aus.
    # Tiger hängt genau ein Zeichen an den ursprünglichen Scope an; alle Credentials bleiben erhalten.
    Wenn Setze im TigerProxy für die Nachricht "$.path =~ '.*${paths.guard.tokenEndpointPath}$' && $.method == 'POST'" die Regex-Manipulation auf Feld "$.body" mit Regex "(?<=&)scope=([^&]*)|^scope=([^&]*)" und Wert "scope=$1$2<Encoded>" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NEGATIVE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NEGATIVE_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und der SMC-B-Exchange enthält ausschließlich den Fehler invalid_scope bei registriertem JWKS "${CC_NEGATIVE_JWKS}"
    Und warte "3" Sekunden
    # A_25661-01 fordert hier den Fehlervertrag, keine bestimmte interne Policy-Aufrufreihenfolge.
    Und alle aufgezeichneten SMC-B-Exchanges für Client "${CC_NEGATIVE_CLIENT}" mit JWKS "${CC_NEGATIVE_JWKS}" und isoliertem Fehler invalid_scope liefern Status 400 und Fehler invalid_scope ohne Tokens

    Beispiele:
      | Zeichen                | Encoded |
      | Anführungszeichen      | %22     |
      | Rückwärts-Schrägstrich | %5C     |

  @A_25661-01
  @TA_A_25661-01_14
  @normal
  @MASVS-CODE
  @MASVS-AUTH
  Szenario: Resource-Fragment im ansonsten gültigen SDK-Exchange wird als invalid_target abgelehnt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthProtectedResourcePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.resource" der aktuellen Antwort in der Variable "CC_FRAGMENT_RESOURCE"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_FRAGMENT_CONTROL_CLIENT"
    Und TGR setze lokale Variable "ccFragmentControlPolicy" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}' && request.body.getFirst('input').get().getFirst('client_registration_data').get().getFirst('client_id').get().getFirst('content').get().rawStringContent == '${CC_FRAGMENT_CONTROL_CLIENT}' && request.body.getFirst('input').get().getFirst('authorization_request').get().getFirst('grant_type').get().getFirst('content').get().rawStringContent == '${oauth_parameters.grant_type.tokenExchange}'"
    Und Setze im TigerProxy für die Nachricht "${ccFragmentControlPolicy}" die Manipulation auf Feld "$.body.result" und Wert '{"allow":true,"ttl":{"access_token":120,"refresh_token":240},"aud":"urn:gematik:cc:resource-fragment-control"}' und 20 Ausführungen
    Wenn Setze im TigerProxy für die Nachricht "$.path =~ '.*${paths.guard.tokenEndpointPath}$' && $.method == 'POST'" die Regex-Manipulation auf Feld "$.body" mit Regex "(?<=&)audience=([^&]+)|^audience=([^&]+)" und Wert "resource=$1$2" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "RESOURCE_CONTROL_JWKS"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.resource" überein mit "\Q!{urlEncoded('${CC_FRAGMENT_RESOURCE}')}\E"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.audience"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_FRAGMENT_CONTROL_CLIENT}\E"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${RESOURCE_CONTROL_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.scope" der aktuellen Anfrage in der Variable "RESOURCE_CONTROL_SCOPE_ENCODED"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_FRAGMENT_CONTROL_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.aud" überein mit "urn:gematik:cc:resource-fragment-control"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthProtectedResourcePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.resource" überein mit "\Q${CC_FRAGMENT_RESOURCE}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NEGATIVE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NEGATIVE_CLIENT"
    Und TGR setze lokale Variable "ccFragmentNegativePolicy" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}' && request.body.getFirst('input').get().getFirst('client_registration_data').get().getFirst('client_id').get().getFirst('content').get().rawStringContent == '${CC_NEGATIVE_CLIENT}' && request.body.getFirst('input').get().getFirst('authorization_request').get().getFirst('grant_type').get().getFirst('content').get().rawStringContent == '${oauth_parameters.grant_type.tokenExchange}'"
    Und Setze im TigerProxy für die Nachricht "${ccFragmentNegativePolicy}" die Manipulation auf Feld "$.body.result" und Wert '{"allow":true,"ttl":{"access_token":120,"refresh_token":240},"aud":"urn:gematik:cc:resource-fragment-control"}' und 20 Ausführungen

    Wenn Setze im TigerProxy für die Nachricht "$.path =~ '.*${paths.guard.tokenEndpointPath}$' && $.method == 'POST'" die Regex-Manipulation auf Feld "$.body" mit Regex "(?<=&)audience=([^&]+)|^audience=([^&]+)" und Wert "resource=$1$2%23cc-invalid-target" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_NEGATIVE_CLIENT}\E"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler resource_fragment bei registriertem JWKS "${CC_NEGATIVE_JWKS}"
    Und warte "3" Sekunden
    Und alle aufgezeichneten SMC-B-Exchanges verwenden ausschließlich Resource "${CC_FRAGMENT_RESOURCE}" mit Fragment cc-invalid-target und Scope "!{new('org.apache.commons.codec.net.URLCodec').decode('${RESOURCE_CONTROL_SCOPE_ENCODED}')}"
    Und alle aufgezeichneten SMC-B-Exchanges für Client "${CC_NEGATIVE_CLIENT}" mit JWKS "${CC_NEGATIVE_JWKS}" und isoliertem Fehler resource_fragment liefern Status 400 und Fehler invalid_target ohne Tokens
