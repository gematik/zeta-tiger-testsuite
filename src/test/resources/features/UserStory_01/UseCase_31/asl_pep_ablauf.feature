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

# language: de
@UseCase_01_31 @require_kubectl @oidc
Funktionalität: SDK-ASL-Verbindung mit Terminierung und Antwortverschlüsselung am PEP

  @TA_A_29380_01
  @A_29380 @asl_pep @normal
  Szenario: SDK-Handshake, PEP-Entschlüsselung und unveränderte Antwort gehören zu demselben ASL-Aufruf
    Angenommen eine eindeutige, randomisierte KVNR in der Variablen "CC_ASL_KVNR"
    Wenn der SDK für KVNR "${CC_ASL_KVNR}" eine korrelierte ASL-Ressource mit Nutzdaten aufruft
    Dann enthält genau ein erfolgreicher ASL-Transport die ursprüngliche Ressource und Nutzdaten
    Und ist der korrelierte Fach-Client-Aufruf an jedem beobachteten Hop erfolgreich und unverändert
    Und passt der innere ASL-DPoP-Proof vollständig zum ursprünglichen SDK-Ressourcenaufruf
    Und stammt das innere ASL-Access-Token aus genau einem erfolgreichen ursprünglichen SDK-Grant
    Und gehört der vollständige ASL-Handshake zum Tunnel dieses SDK-Aufrufs
    Und trägt dieser ASL-Tunnel die inneren Nachweise ausschließlich verschlüsselt
    Und leitet der PEP genau einen korrelierten entschlüsselten Ressourcenaufruf weiter
    Und verschlüsselt der PEP genau die beobachtete Ressourcenantwort
    Und erhält der Fach-Client die vom SDK entschlüsselte Ressourcenantwort unverändert
    Und TGR finde die erste Anfrage mit Pfad "^\Q${ASL_TRANSPORT_PATH}\E$" und Knoten "$.body.decrypted.header.[~'X-Zeta-Test-Correlation']" der mit "^\Q${ASL_CORRELATION}\E$" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.decrypted.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.decrypted.path" überein mit "^\Q${paths.guard.helloZetaPath}\E$"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.decrypted.header.[~'Authorization']" überein mit "(?i)^DPoP .+"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.decrypted.header.[~'DPoP']"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.decrypted.responseCode" überein mit "200"
