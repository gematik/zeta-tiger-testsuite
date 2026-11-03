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

@UseCase_04_07
@require_kubectl
@oidc
Funktionalität: Der Nutzerlogout beendet den ursprünglichen Server-Grant

  @A_30002
  @TA_A_30002_01
  @normal
  @MASVS-PRIVACY
  @MASVS-STORAGE
  @MASVS-AUTH
  @Ignore
  Szenario: Das ursprüngliche Refresh Token ist nach dem echten SDK-Logout unbrauchbar
    # Die Testdriver-HTTP-API hat keinen Logout. Der Adapter ruft den öffentlichen Logout
    # der unveränderten installierten SDK-JARs mit eigener frischer OIDC-Testidentität auf.
    # Eine getrennte Kopie ihrer Software-Testschlüssel ermöglicht die spätere unabhängige
    # Grant-Prüfung trotz SDK-Bereinigung. Sie wird weder in SDK-Storage zurückgeschrieben
    # noch in Tiger-Variablen abgelegt; die temporäre private RAM-Datei wird entfernt.
    Gegeben sei ein steuerbarer SDK-Logout-Kontext mit echter OIDC-Konfiguration und unabhängigem Grant-Prüfclient
    Wenn der isolierte SDK-Logout-Kontext die Operation create ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation discover ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation register ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation authenticate ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation logout ausführt
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^\Q${paths.guard.tokenEndpointPath}\E$" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Anfrage in der Variable "CC_LOGOUT_TOKEN_REQUEST"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_LOGOUT_TOKEN_RESPONSE"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_LOGOUT_ACCESS"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_LOGOUT_REFRESH"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.sub" der aktuellen Anfrage in der Variable "CC_LOGOUT_CLIENT"
    Und der SDK-Logout gehört zu Access Token "${CC_LOGOUT_ACCESS}", Refresh Token "${CC_LOGOUT_REFRESH}" und Client "${CC_LOGOUT_CLIENT}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_LOGOUT_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${CC_LOGOUT_ACCESS}" mit KeyStore "${CC_LOGOUT_JWKS}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthServerPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthServerPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_LOGOUT_DISCOVERY"

    # Nur der tatsächliche SDK-POST mit seinem ursprünglichen RT zählt als Widerruf.
    # Der unabhängige Prüfclient erzeugt ausschließlich einen frischen Refresh mit demselben
    # RT, gebundenen Schlüsseln, neuer AS-Nonce und passenden Proofs. Er darf keine Token erhalten.
    # Zusätzlich muss die ursprüngliche Session in der AS-Verwaltung beendet sein.
    # Rohe DB-Zeilenzahlen sind Diagnosewerte; physisches Löschen ist hier keine Anforderung.
    Gegeben sei ein unabhängiger Logout-Grant-Prüfclient mit Discovery "${CC_LOGOUT_DISCOVERY}", Token-Request "${CC_LOGOUT_TOKEN_REQUEST}" und Token-Antwort "${CC_LOGOUT_TOKEN_RESPONSE}"
    Wenn der unabhängige Logout-Prüfclient das ursprüngliche Refresh Token mit frischen Proofs verwendet
    Dann beendet der tatsächliche SDK-Logout den ursprünglichen Grant nachweislich auf dem Server
