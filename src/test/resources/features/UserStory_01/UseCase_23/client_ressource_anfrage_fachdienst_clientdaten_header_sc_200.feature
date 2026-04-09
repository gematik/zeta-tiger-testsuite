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

@UseCase_01_23
Funktionalität: client_ressource_anfrage_fachdienst_clientdaten_header_sc_200_integrationstest

  @dev
  @A_25669-01
  @A_26492-02
  @A_26589-01
  @A_26590-02
  @A_26661
  @A_27007
  @TA_A_25669-01_01
  @TA_A_25669-01_02
  @TA_A_25669-01_03
  @TA_A_25669-01_07
  @TA_A_26492-02_01
  @TA_A_26589-01_01
  @TA_A_26590-02_01
  @TA_A_26661_15
  @TA_A_27007_15
  Szenario: PEP fügt alle ZETA-Header ein (User-Info, PoPP-Token-Content, Client-Data)
    # Access Token holen und User-Daten aus dem Access Token ermitteln
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}" der aktuellen Anfrage in der Variable "ACC_TOK"
    Und TGR setze lokale Variable "ACC_TOK_decoded" auf "!{base64Decode(${ACC_TOK})}"
    Und validiere "${ACC_TOK_decoded}" gegen Schema "schemas/v_1_0/access-token.yaml"
    ## Und decodiere und validiere "${ACC_TOK}" gegen Schema "schemas/v_1_0/access-token.yaml"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.sub}" der aktuellen Anfrage in der Variable "expectedIdentifier"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.profession_oid}" der aktuellen Anfrage in der Variable "expectedProfessionOid"
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.body.client_id}" der aktuellen Anfrage in der Variable "expectedClientId"

    # Nachrichten löschen und Resource Request mit manipulierten PDP-DB-Daten senden
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe Request VOR PEP - keine ZETA-Header vorhanden
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_OLD_HEADERS"

    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.userInfo.root}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.poppTokenContent}"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.zeta.clientData}"

    # PoPP-Header (JWT) muss vorhanden sein
    Und TGR prüfe aktueller Request enthält Knoten "${headers.popp.root}"

    # Prüfe Request NACH PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR speichere Wert des Knotens "$.header" der aktuellen Anfrage in der Variable "ALL_HEADERS"

    # TA_A_25669-01_01: zeta-user-info wurde eingefügt
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.userInfo.root}"
    Und TGR speichere Wert des Knotens "${headers.zeta.userInfo.root}" der aktuellen Anfrage in der Variable "USER_INFO"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.identifier}" überein mit "${expectedIdentifier}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.userInfo.decoded.professionOid}" überein mit "${expectedProfessionOid}"
    Und prüfe ob der Knoten "${USER_INFO}" MAX 250 Byte groß ist und nutze soft assert
    Und prüfe "${USER_INFO}" ist striktes Base64-URL Format
    Und TGR setze lokale Variable "USER_INFO_decoded" auf "!{base64Decode(${USER_INFO})}"
    Und validiere "${USER_INFO_decoded}" gegen Schema "schemas/v_1_0/zeta-user-info.yaml"

    # TA_A_25669-01_02: zeta-popp-token-content wurde eingefügt
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.poppTokenContent}"
    Und TGR speichere Wert des Knotens "${headers.zeta.poppTokenContent}" der aktuellen Anfrage in der Variable "POPP_CONTENT"
    Und prüfe ob der Knoten "${POPP_CONTENT}" MAX 450 Byte groß ist und nutze soft assert
    Und prüfe "${POPP_CONTENT}" ist striktes Base64-URL Format
    Und TGR setze lokale Variable "POPP_CONTENT_decoded" auf "!{base64Decode(${POPP_CONTENT})}"
    # Vergleich: zeta-popp-token-content muss dem Payload des PoPP-JWT entsprechen
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.root}" der aktuellen Anfrage in der Variable "POPP_JWT_PAYLOAD"
    Und prüfe Knoten "${POPP_JWT_PAYLOAD}" enthält mindestens alle Kindknoten von "${POPP_CONTENT_decoded}"
    Und prüfe Knoten "${POPP_CONTENT_decoded}" enthält mindestens alle Kindknoten von "${POPP_JWT_PAYLOAD}"

    # TA_A_25669-01_03: zeta-client-data wurde eingefügt - zurück zum Request NACH PEP
    Dann TGR finde die letzte Anfrage mit dem Pfad "^${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.zeta.clientData}"
    Und TGR speichere Wert des Knotens "${headers.zeta.clientData}" der aktuellen Anfrage in der Variable "CLIENT_DATA"
    Und prüfe ob der Knoten "${CLIENT_DATA}" MAX 250 Byte groß ist und nutze soft assert
    Und prüfe "${CLIENT_DATA}" ist striktes Base64-URL Format
    Und TGR setze lokale Variable "CLIENT_DATA_decoded" auf "!{base64Decode(${CLIENT_DATA})}"
    Und validiere "${CLIENT_DATA_decoded}" soft gegen Schema "schemas/v_1_0/client-data.yaml"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.zeta.clientDataDecodedClientId}" überein mit "${expectedClientId}"

    # TA_A_25669-01_07: Alle anderen Header wurden weitergeleitet
    Und prüfe Knoten "${ALL_HEADERS}" enthält mindestens alle Header aus "${ALL_OLD_HEADERS}" und nutze soft assert
