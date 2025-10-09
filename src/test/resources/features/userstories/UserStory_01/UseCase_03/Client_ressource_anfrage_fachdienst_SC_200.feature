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

@UseCase_01_03
Funktionalität: Client_ressource_anfrage_fachdienst_SC_200

  Grundlage:
    Gegeben sei TGR zeige Banner "Funktionalität: Client_ressource_anfrage_fachdienst_SC_200"
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze Anfrage Timeout auf 20 Sekunden

  @TA_A_25761_02
  @TA_A_25767_01
  @TA_A_25767_02
  Szenario: ZETA Guard, Resource Abfrage mit gültigem access token
    Wenn Hole JWT von "http://zetaGuard/auth/realms/zeta-guard/protocol/openid-connect/token" und speichere in der Variable "jwtToken"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/auth/realms/zeta-guard/protocol/openid-connect/token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und validiere "${jwtToken}" gegen Schema "schemas/v_1_0/access-token.yaml"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "JWT_BEARER_TOKEN"
    Und TGR sende eine leere GET Anfrage an "http://zetaGuard/pep/achelos_testfachdienst/hellozeta" mit folgenden Headern:
      | Authorization | Bearer ${JWT_BEARER_TOKEN} |
    Und TGR finde die letzte Anfrage mit dem Pfad "/pep/achelos_testfachdienst/hellozeta"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @TA_A_27802_01
  @TA_A_27802_02
  @TA_A_27802_03
  @TA_A_27802_04
  @TA_A_27802_05
  @TA_A_27802_10
  @TA_A_27802_11
  Szenariogrundriss:  ZETA Guard, JWT Prüfung
    Wenn Hole JWT von "http://zetaGuard/auth/realms/zeta-guard/protocol/openid-connect/token" und speichere in der Variable "jwtToken"
    Dann TGR finde die letzte Anfrage mit dem Pfad "/auth/realms/zeta-guard/protocol/openid-connect/token"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "JWT_BEARER_TOKEN"
    Und Setze "<Feld>" im JWT-Token "${JWT_BEARER_TOKEN}" auf den Wert "<NeuerWert>" und signiere mit dem Key "mocks/jwt-sign-key.pem" und speichere in der Variable "JWT_ERROR_TOKEN"
    Und TGR sende eine leere GET Anfrage an "http://zetaGuard/pep/achelos_testfachdienst/hellozeta" mit folgenden Headern:
      | Authorization | Bearer ${JWT_ERROR_TOKEN} |
    Und TGR finde die letzte Anfrage mit dem Pfad "/pep/achelos_testfachdienst/hellozeta"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    Beispiele: Manipulationen
      | Feld          | NeuerWert        | ResponseCode |
      | header.alg    | RS1              | 401          |
      | payload.exp   | 1758719276       | 401          |
      | payload.aud   | unknown          | 401          |
      | payload.iss   | someone          | 401          |
      | hash          |                  | 401          |
      | header.typ    | JWT              | 401          |
      | payload.nonce | 1234567890123456 | 401          |