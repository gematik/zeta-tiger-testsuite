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
Funktionalität: Subject-Nonce im SDK-Token-Exchange isoliert validieren

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
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.opa.decisionPath}" haben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_CONTROL_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten

  @A_29376
  @TA_A_29376_16
  @smcb_subject_nonce_candidate
  @normal
  Szenariogrundriss: Korrekt signiertes SDK-Subject mit <Variante> Nonce
    Gegeben sei lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "CC_NONCE_SMCB"
    # Ausschließlich Subject-Nonce ändern; DPoP behält die echte aktuelle AS-Nonce.
    Wenn Setze im TigerProxy für JWT in "$.body.subject_token" das Feld "body.nonce" auf Wert "<Wert>" mit privatem Schlüssel "${CC_NONCE_SMCB.prv_pem}" für Pfad ".*${paths.guard.tokenEndpointPath}" und 20 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_NONCE_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_NONCE_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_NONCE_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.header.x5c.0" überein mit "\Q${CC_NONCE_SMCB.crt_b64}\E"
    Und der SMC-B-Exchange enthält ausschließlich den Fehler <Fehler> bei registriertem JWKS "${CC_NONCE_JWKS}"
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat
    Und alle aufgezeichneten SMC-B-Exchanges für Client "${CC_NONCE_CLIENT}" mit JWKS "${CC_NONCE_JWKS}" und isoliertem Fehler <Fehler> liefern Status 400 und Fehler invalid_grant ohne Tokens

    Beispiele: Isolierte Subject-Nonce
      | Variante   | Wert                       | Fehler                |
      | unbekannter | cc-unissued-subject-nonce | subject_nonce_unknown |
      | leerer      |                          | subject_nonce_empty   |
