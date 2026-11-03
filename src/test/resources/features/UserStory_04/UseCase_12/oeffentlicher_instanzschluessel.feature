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

@UseCase_04_12
@require_kubectl
@oidc
@reset_testdriver
Funktionalität: Öffentlichen Instanzschlüssel der registrierten Client-Instanz als F2 führen

  @A_29907
  @TA_A_29907_01
  @Ignore
  @normal
  Szenario: Gespeicherter öffentlicher F2 gehört zum registrierten OIDC-Client
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "F2_KVNR"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "\s*\{\s*\}\s*"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/discover$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "${paths.guard.registerEndpointPath}" übereinstimmt
    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
    Und TGR finde die erste Anfrage mit Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.jwks.keys.0"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "F2_REGISTERED_JWKS"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/register$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.guard.registerEndpointPath}(/verify)?$"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "F2_CLIENT_ID"
    Und TGR prüfe Variable "F2_CLIENT_ID" stimmt überein mit ".+"
    Dann lies den gespeicherten F2 für Client "${F2_CLIENT_ID}" aus dem AS-Bestand in Variable "F2_AFTER_REGISTRATION"
    Und der gespeicherte F2 "${F2_AFTER_REGISTRATION}" ist öffentlich und entspricht dem Registrierungs-JWKS "${F2_REGISTERED_JWKS}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "authorization_code" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${F2_CLIENT_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "F2_USER_TOKEN"
    Und TGR prüfe Variable "F2_USER_TOKEN" stimmt überein mit "[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.client_id" der mit "\Q${F2_CLIENT_ID}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token" überein mit "\Q${F2_USER_TOKEN}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "\Q${F2_CLIENT_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${F2_CLIENT_ID}\E"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "F2_ASSERTION"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Dann lies den gespeicherten F2 für Client "${F2_CLIENT_ID}" aus dem AS-Bestand in Variable "F2_AS_JWKS"
    Und der gespeicherte F2 "${F2_AS_JWKS}" ist öffentlich und entspricht dem Registrierungs-JWKS "${F2_REGISTERED_JWKS}"
    Und der gespeicherte F2 "${F2_AS_JWKS}" ist öffentlich und entspricht dem Registrierungs-JWKS "${F2_AFTER_REGISTRATION}"
    Und die Besitzsignatur "${F2_ASSERTION}" ist mit dem gespeicherten F2 "${F2_AS_JWKS}" kryptografisch gültig
