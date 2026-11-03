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

@UseCase_04_04
@oidc
@additional_sdk_instance
Funktionalität: F1-Benachrichtigung nach tatsächlicher Client-Löschung

  @A_29935
  @TA_A_29935_01
  @normal
  @Ignore
  Szenario: Nach der Selbstlöschung von C2 wird das gebundene F1 benachrichtigt
    # 1. C1 einbuchen: Neue Identität mit verifiziertem F1 erstellen und ursprüngliches Access Token sichern.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_CLIENT_OWNER"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_CALLER_CLIENT"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_CLIENT_OWNER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_CALLER_CLIENT}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_CALLER_ACCESS"

    # 2. C2 einbuchen: Dieselbe Identität in einem getrennten SDK-Kontext mit eigener Clientregistrierung nutzen.
    Gegeben sei eine zusätzliche unabhängige SDK-Instanz für KVNR "${CC_CLIENT_OWNER}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${CC_SECOND_SDK_PATH}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^\Q${CC_SECOND_SDK_PATH}\E$" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_SECOND_SDK_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_TARGET_CLIENT"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.client_id" nicht überein mit "\Q${CC_CALLER_CLIENT}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_CLIENT_OWNER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_TARGET_CLIENT}\E"

    # 3. Ausgangsbestand prüfen: Die Identität besitzt ausschließlich C1 und C2.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.clients}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.clientsPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.clientsManagementPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) .+"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.client_id}" überein mit "\Q${CC_CALLER_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_CLIENT_OWNER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "array",
        "minItems": 2,
        "maxItems": 2,
        "items": {
          "type": "object",
          "required": ["client_id"],
          "properties": {
            "client_id": { "type": "string", "pattern": "^[A-Za-z0-9_.-]+$" }
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.[?(@.client_id == '${CC_CALLER_CLIENT}')]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.[?(@.client_id == '${CC_TARGET_CLIENT}')]"

    # 4. C1 vor der Löschung prüfen: Zugriff mit dem ursprünglichen Access Token bestätigen.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${CC_CALLER_ACCESS}\E"

    # 5. Mailbestand erfassen: Bereits vorhandene Nachrichten von der späteren Löschbenachrichtigung abgrenzen.
    Wenn der E-Mail-Inhalt von "${paths.mailcatcher.messages}" mit begrenzter Wartezeit und Anfragekennung in Variable "CC_MAIL_CAPTURE" abgerufen wird
    Dann TGR finde die erste Anfrage mit Pfad "${paths.mailcatcher.messagesPath}" und Knoten "$.header.X-Zeta-Mail-Capture" der mit "${CC_MAIL_CAPTURE}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_MAIL_BASELINE"
    Gegeben sei der E-Mail-Bestand "${CC_MAIL_BASELINE}" vor der Löschung von Client "${CC_TARGET_CLIENT}" ist für F1 "${CC_CLIENT_OWNER}@${oidcTestData.emailDomain}" erfasst

    # 6. C2 selbst löschen: DELETE für C2 mit dessen DPoP-Access-Token und erfolgreiche Guard-Antwort prüfen.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.client.loadRegistration}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadRegistrationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "204"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.clientsManagementPath}/${CC_TARGET_CLIENT}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "DELETE"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) .+"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.client_id}" überein mit "\Q${CC_TARGET_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_CLIENT_OWNER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "2[0-9][0-9]"

    # 7. Verbleibenden Client prüfen: C1 nutzt weiter sein ursprüngliches Token und ist der einzige Client.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${CC_CALLER_ACCESS}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.clients}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.clientsPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.clientsManagementPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) .+"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.client_id}" überein mit "\Q${CC_CALLER_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.body.sub}" überein mit "\Q${CC_CLIENT_OWNER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "type": "array",
        "minItems": 1,
        "maxItems": 1,
        "items": {
          "type": "object",
          "required": ["client_id"],
          "properties": {
            "client_id": { "type": "string", "pattern": "^[A-Za-z0-9_.-]+$" }
          }
        }
      }
      """
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.0.client_id" überein mit "\Q${CC_CALLER_CLIENT}\E"

    # 8. F1 benachrichtigen: Eine neue E-Mail muss die ausgeführte Client-Löschung bestätigen.
    Dann F1 erhält eine neue E-Mail über die ausgeführte Client-Löschung
