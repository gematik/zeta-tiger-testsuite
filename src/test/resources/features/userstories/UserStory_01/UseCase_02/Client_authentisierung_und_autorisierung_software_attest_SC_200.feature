#
# #%L
# ZETA Testsuite
# %%
# (C) 2025 achelos GmbH, licensed for gematik GmbH
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

@UseCase_01_02
Funktionalität: Client_authentisierung_und_autorisierung_software_attest_SC_200

  Grundlage:
    Gegeben sei TGR zeige Banner "Funktionalität: Client_authentisierung_und_autorisierung_software_attest_SC_200"
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze Anfrage Timeout auf 20 Sekunden

  @TA_A_28144_01
  @TA_A_28144_02
  @TA_A_28144_03
  Szenario: Nonce am Endpunkt GET /nonce
    Wenn TGR sende eine leere GET Anfrage an "http://zetaGuard/nonce"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/nonce"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "NONCE"
    Und decodiere Base64Url "${NONCE}" und prüfe das die Länge 128 bit ist

  @TA_A_28144_04
  Szenario: Nonce ist zufällig
    Wenn TGR sende eine leere GET Anfrage an "http://zetaGuard/nonce"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/nonce"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "NONCE"
    Und TGR sende eine leere GET Anfrage an "http://zetaGuard/nonce"
    Und TGR finde die letzte Anfrage mit dem Pfad "/nonce"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" nicht überein mit "${NONCE}"

  @TA_A_27007_07
  @TA_A_25661_01
  @TA_A_26944_01
  Szenario: Die Komponente Authorization Server MUSS Access Token mit Attributen gemäß [access-token.yaml] ausstellen.
    Wenn Hole JWT von "http://zetaGuard/auth/realms/zeta-guard/protocol/openid-connect/token" und speichere in der Variable "jwtToken"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und validiere "${jwtToken}" gegen Schema "schemas/v_1_0/access-token.yaml"
