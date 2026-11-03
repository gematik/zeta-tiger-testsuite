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
@require_kubectl
@oidc
@additional_sdk_instance
Funktionalität: Ein eingebuchter Client löscht einen anderen Client derselben Identität

  @A_29934
  @TA_A_29934_01
  @normal
  @Ignore
  Szenario: C1 entfernt C2 und bleibt selbst eingebucht
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
    Und TGR speichere Wert des Knotens "$.body.registration_access_token" der aktuellen Antwort in der Variable "CC_CALLER_RAT"
    Und TGR speichere Wert des Knotens "$.body.registration_client_uri" der aktuellen Antwort in der Variable "CC_CALLER_URI"
    Und TGR prüfe Variable "CC_CALLER_URI" stimmt überein mit "https://\Q${zeta_base_url}\E(?::[0-9]+)?/[^?#]+"
    Und TGR setze lokale Feature Variable "CC_CALLER_PATH" auf "${CC_CALLER_URI}"
    Und TGR ersetze "^https://[^/]+" mit "" im Inhalt der Variable "CC_CALLER_PATH"
    Und TGR prüfe Variable "CC_CALLER_PATH" stimmt überein mit "/[^?#]+/\Q${CC_CALLER_CLIENT}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_CLIENT_OWNER}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_CALLER_CLIENT}\E"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_CALLER_ACCESS"

    # Die Load-API legt C2 mit eigenem Speicher/Schlüssel an; C1 wird nicht zurückgesetzt.
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
    Und die Clients "${CC_CALLER_CLIENT}" und "${CC_TARGET_CLIENT}" gehören allein zur Identität mit F1 "${CC_CLIENT_OWNER}@${oidcTestData.emailDomain}"

    # Positive Kontrollen: C1 nutzt noch seinen ursprünglichen Grant; sein RAT ist gültig.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${CC_CALLER_ACCESS}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${CC_CALLER_PATH}" mit folgenden Headern:
      | Authorization | Bearer ${CC_CALLER_RAT} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_CALLER_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_CALLER_CLIENT}\E"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_CALLER_METADATA"
    Und übernimm ein rotiertes Registration Access Token aus "${CC_CALLER_METADATA}" in Variable "CC_CALLER_RAT", falls vorhanden

    # Das verfügbare SDK/Testdriver-API bietet keinen Client-Verwaltungs-DELETE.
    # Nur dieser Serveraufruf erfolgt unabhängig, mit C1-RAT und dem tatsächlichen C2-Ziel.
    # Die CC-Baseline verlangt RAT; Client-Assertions aus späteren Entwürfen ersetzen es nicht.
    Wenn TGR sende eine leere DELETE Anfrage an "${paths.guard.baseUrl}${paths.guard.clientsManagementPath}/${CC_TARGET_CLIENT}" mit folgenden Headern:
      | Authorization | Bearer ${CC_CALLER_RAT} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.clientsManagementPath}/${CC_TARGET_CLIENT}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "DELETE"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "Bearer \Q${CC_CALLER_RAT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "2[0-9][0-9]"
    Und nur der andere Client wurde gelöscht und der aufrufende Client bleibt derselben Identität zugeordnet
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i:DPoP) \Q${CC_CALLER_ACCESS}\E"
