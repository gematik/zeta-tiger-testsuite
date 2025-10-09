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
Funktionalität: Client_authentisierung_und_autorisierung_software attest_SC_400

  Grundlage:
    Gegeben sei TGR zeige Banner "Funktionalität: Client_authentisierung_und_autorisierung_software attest_SC_400"
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze Anfrage Timeout auf 20 Sekunden

  @TA_A_27007_11
  @TA_A_25661_03
  Szenario: Fehlerhafte Anfrage im Authorization Code Flow
    Wenn Hole JWT für Client "evil_client" von "http://zetaGuard/auth/realms/zeta-guard/protocol/openid-connect/token" und speichere in der Variable "jwtToken"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "400"

  @TA_A_27007_11
  @TA_A_25661_03
  @TA_A_26662_01
  Szenario: Der ZETA Guard muss bei HTTP Fehler 400 ein JSON Objekt gemäß [zeta-error.yaml] senden.
    Wenn Hole JWT für Client "evil_client" von "http://zetaGuard/auth/realms/zeta-guard/protocol/openid-connect/token" und speichere in der Variable "jwtToken"
    Und validiere "${jwtToken}" gegen Schema "schemas/v_1_0/zeta-error.yaml"

