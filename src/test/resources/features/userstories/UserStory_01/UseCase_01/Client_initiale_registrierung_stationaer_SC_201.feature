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

@UseCase_01_01
Funktionalität: Client_initiale_registrierung_stationaer_SC_201

  Grundlage:
    Gegeben sei TGR zeige Banner "Funktionalität: Client_initiale_regListrierung_stationaer_SC_201"
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze Anfrage Timeout auf 20 Sekunden

  @A_27266
  @A_27798
  @TA_A_27266_01
  @TA_A_27266_02
  @TA_A_27798_01
  @TA_A_27798_03
  Szenario: well-known zu oauth-protected-resource
    Wenn TGR sende eine leere GET Anfrage an "http://zetaGuard/achelos_testfachdienst/.well-known/oauth-protected-resource"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/.well-known/oauth-protected-resource$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "OPR_WELL_KNOWN"
    Und validiere "${OPR_WELL_KNOWN}" gegen Schema "schemas/v_1_0/opr-well-known.yaml"

  @A_27798
  @TA_A_27798_02
  @TA_A_27798_04
  Szenario: well-known zu oauth-authorization-server
      #Wenn TGR sende eine leere GET Anfrage an "http://zetaGuard/.well-known/oauth-authorization-server"
    Wenn TGR sende eine leere GET Anfrage an "http://zetaGuard/auth/realms/master/.well-known/oauth-authorization-server"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/.well-known/oauth-authorization-server$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "AS_WELL_KNOWN"
    Und validiere "${AS_WELL_KNOWN}" gegen Schema "schemas/v_1_0/as-well-known.yaml"
