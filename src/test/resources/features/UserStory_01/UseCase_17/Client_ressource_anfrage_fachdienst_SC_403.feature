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

@UseCase_01_17
Funktionalität: Client_ressource_anfrage_fachdienst_SC_403

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "proxy" auf "${paths.tigerProxy.baseUrl}"

  @A_25660
  @A_26477
  @A_26661
  @A_27007
  @TA_A_25660_03
  @TA_A_26661_01
  @TA_A_27007_26
  Szenariogrundriss: PoPP Token Manipulation Test - Token Request
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgChSTcLLu6By9RINWnfQdtCqkm8WlOcje4oDnLV5KpmigCgYIKoZIzj0DAQehRANCAARwLyN6z4jOFORwcx0yMnrJ/2XGUR7b/Vcbo5W02kT7b9rKjub8r2tuBEJ/AIEupjjZ3kYSCPKoUS6v1SNOg8Th"

    Und TGR setze lokale Variable "pathCondition" auf ".*/achelos-testfachdienst/hellozeta"

    Dann Setze im TigerProxy "${proxy}" für JWT in "<JwtLocation>" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR gebe aktuelle Request als Rbel-Tree aus
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    # 2. Manipuliere die Signatur des PoPP Token
    # 3. Gültigkeitsdauer gesamt
    # 4. Gültigkeitsdauer nach Ausstellungszeitpunkt (iat = 2023-12-01 12:00)
    # 5. Gültigkeitsdauer nach Prüfzeitpunkt (patientProofTime = 2023-12-01 12:00)
    # 6. Manipulierte "actorId"
    Beispiele: Manipulationen
      | JwtLocation                | JwtField                 | NeuerWert             | ResponseCode |
      | $.header.popp              | body.iat                 | 1701432000            | 403          |
      | $.header.popp              | body.patientProofTime    | 1701432000            | 403          |
      | $.header.popp              | body.actorId             | evil_client           | 403          |
