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
Funktionalität: SDK-Logout über den veröffentlichten Revocation-Endpunkt

  @A_30001
  @TA_A_30001_03
  @TA_A_30001_04
  @TA_A_30001_05
  @normal
  @Ignore
  Szenario: Der echte SDK-Logout sendet sein Refresh Token per POST an den Revocation-Endpunkt
    # Die HTTP-API des Testdrivers bietet keinen Logout. Der isolierte Adapter ruft daher
    # die öffentlichen Funktionen der unveränderten installierten SDK-JARs auf.
    # Keine unabhängige HTTP-Anfrage erzeugt den zu prüfenden Revocation-Request.
    Gegeben sei ein steuerbarer SDK-Logout-Kontext mit echter OIDC-Konfiguration
    Wenn der isolierte SDK-Logout-Kontext die Operation create ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation discover ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation register ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation authenticate ausführt
    Wenn der isolierte SDK-Logout-Kontext die Operation logout ausführt
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^\Q${paths.guard.tokenEndpointPath}\E$" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "CC_SDK_ACCESS"
    Und TGR speichere Wert des Knotens "$.body.refresh_token" der aktuellen Antwort in der Variable "CC_SDK_REFRESH"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.sub" der aktuellen Anfrage in der Variable "CC_SDK_CLIENT"
    Und der SDK-Logout gehört zu Access Token "${CC_SDK_ACCESS}", Refresh Token "${CC_SDK_REFRESH}" und Client "${CC_SDK_CLIENT}"

    # Die Schlüsselbindung im Access Token ist nur mit gültiger AS-Signatur beweiskräftig.
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_SDK_LOGOUT_JWKS"
    Und verifiziere die ES256 Signatur des JWT "${CC_SDK_ACCESS}" mit KeyStore "${CC_SDK_LOGOUT_JWKS}"

    # Die Soll-URL stammt aus der tatsächlichen AS-Discovery, nicht aus einem geratenen /revoke-Pfad.
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthServerPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.wellKnownOAuthServerPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.revocation_endpoint" der aktuellen Antwort in der Variable "CC_SDK_REVOCATION_ENDPOINT"
    Und verwende den veröffentlichten SDK-Revocation-Endpunkt "${CC_SDK_REVOCATION_ENDPOINT}"

    # Bereits abgewickelter SDK-Aufruf: Warten dient nur der vollständigen Tiger-Aufzeichnung.
    Und TGR warte auf eine Nachricht, in der Knoten "$.path" mit "^\Q${CC_SDK_REVOCATION_PATH}\E$" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${CC_SDK_REVOCATION_PATH}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.token" überein mit "\Q${CC_SDK_REFRESH}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "\Q${CC_SDK_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.token_type_hint" überein mit "refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und der aufgezeichnete SDK-Widerruf für Access Token "${CC_SDK_ACCESS}", Refresh Token "${CC_SDK_REFRESH}" und Client "${CC_SDK_CLIENT}" an "${CC_SDK_REVOCATION_ENDPOINT}" enthält gültige frische Client- und DPoP-Nachweise ohne Attestierung oder Nonce
