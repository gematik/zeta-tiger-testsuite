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
Funktionalität: Stationärer SMC-B-Client benötigt keine zusätzliche TOFU-Faktorverwaltung

  @A_29893
  @TA_A_29893_02
  @normal
  @Ignore
  @smb
  Szenario: Frisch registrierter stationärer SDK-Client erhält SMC-B-Token ohne TOFU-E-Mail
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "CC_SMB_JWKS"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "CC_SMB_CLIENT"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.grant_type" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_id" überein mit "\Q${CC_SMB_CLIENT}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und der SMC-B-Exchange verwendet gültige unabhängige Nachweise und eine gültige Client-Assertion zum registrierten JWKS "${CC_SMB_JWKS}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.client_id" überein mit "\Q${CC_SMB_CLIENT}\E"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "CC_SMB_CERTIFICATE"
    Und speichere die Telematik-ID aus SMC-B Zertifikat "${CC_SMB_CERTIFICATE}" in Variable "CC_SMB_IDENTITY"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.subject_token.body.sub" überein mit "\Q${CC_SMB_IDENTITY}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.access_token.body.sub" überein mit "\Q${CC_SMB_IDENTITY}\E"
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad ".*/zeta/identity/.*" hat
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad ".*/(?:par|auth|authorize)" hat
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${testdata.ccPar.outerPath}" hat
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${testdata.ccPar.innerPath}" hat
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Dann prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.opa.decisionPath}" haben
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${CC_SMB_CLIENT}\E" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
