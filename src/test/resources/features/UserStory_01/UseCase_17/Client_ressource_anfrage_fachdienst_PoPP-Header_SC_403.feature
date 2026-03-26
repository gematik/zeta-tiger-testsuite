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

@UseCase_01_17
Funktionalität: Client_ressource_anfrage_fachdienst_PoPP-Header_SC_403

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR sende eine leere GET Anfrage an "${paths.tigerProxy.baseUrl}/resetMessages"

  @A_25660
  @A_26477
  @A_26661
  @TA_A_25660_03
  @TA_A_26477_08
  @TA_A_26661_20
  Szenariogrundriss: PoPP Token Manipulation Test - Token Request
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgChSTcLLu6By9RINWnfQdtCqkm8WlOcje4oDnLV5KpmigCgYIKoZIzj0DAQehRANCAARwLyN6z4jOFORwcx0yMnrJ/2XGUR7b/Vcbo5W02kT7b9rKjub8r2tuBEJ/AIEupjjZ3kYSCPKoUS6v1SNOg8Th"

    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "${headers.popp.root}" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.root}.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    # 2. Manipuliere die Signatur des PoPP Token
    # 3. Gültigkeitsdauer gesamt
    # 4. Gültigkeitsdauer nach Ausstellungszeitpunkt (iat = 2023-12-01 12:00)
    # 5. Gültigkeitsdauer nach Prüfzeitpunkt (patientProofTime = 2023-12-01 12:00)
    # 6. Manipulierte "actorId"
    Beispiele: Manipulationen
      | JwtField                 | NeuerWert   |
      | body.iat                 | 1701432000  |
      | body.patientProofTime    | 1701432000  |
      | body.actorId             | evil_client |

  @dev
  @A_26477
  @A_26661
  @TA_A_26477_09
  @TA_A_26661_20
  Szenario: PoPP Token mit ungültiger Signatur wird abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Erster erfolgreicher Flow - Client erhält gültiges PoPP-Token
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.insurerId}" der aktuellen Anfrage in der Variable "PoPP_INSURER_ID"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-foreign_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    # PoPP-Token mit fremdem Key neu signieren, aber JWK NICHT ersetzen
    # Feldwahl ist absichtlich "harmlos", damit die Ablehnung nur auf die Signatur zurückzuführen ist
    Dann Setze im TigerProxy für JWT in "${headers.popp.root}" das Feld "body.insurerId" auf Wert "${PoPP_INSURER_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe, dass die Manipulation angewendet wurde
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.insurerId}" der mit "${PoPP_INSURER_ID}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @dev
  @A_26477
  @A_26661
  @TA_A_26477_06
  @TA_A_26661_20
  Szenario: PoPP Token mit fremdem Schlüssel signiert wird abgelehnt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Erster erfolgreicher Flow - Client erhält gültiges PoPP-Token
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR speichere Wert des Knotens "${headers.popp.body.insurerId}" der aktuellen Anfrage in der Variable "PoPP_INSURER_ID"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "!{file('src/test/resources/keys/popp-token-foreign_ecKey.pem')}"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    # PoPP-Token mit fremdem Key neu signieren UND JWK ersetzen
    # Signatur ist mathematisch gültig, aber Key ist nicht im JWKS des PoPP Servers
    Dann Setze im TigerProxy für JWT in "${headers.popp.root}" das Feld "body.insurerId" auf Wert "${PoPP_INSURER_ID}" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Prüfe, dass die Manipulation angewendet wurde und der Token weiterhin schema-konform ist
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.popp.body.insurerId}" der mit "${PoPP_INSURER_ID}" übereinstimmt
    Und TGR speichere Wert des Knotens "${headers.popp.root}" der aktuellen Anfrage in der Variable "PoPP_TOKEN"
    Und verifiziere die ES256 Signatur des JWT "${PoPP_TOKEN}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

  @A_26493
  @A_27007
  @A_26661
  @TA_A_26493_01
  @TA_A_26661_20
  @longrunning
  Szenario: PoPP JWKS wird nach einem zweiten Ablauf nicht weiterverwendet, wenn kein neues JWKS ladbar ist
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "poppJwksPath" auf "${paths.popp.jwks}"
    Und TGR setze lokale Variable "poppJwksResponseCondition" auf "isResponse && request.path =~ '.*${poppJwksPath}'"
    # 24h ist ein JWKS Abruf gültig
    Und TGR setze lokale Variable "poppJwksExpiryWait" auf "86400"
    # Alle 5 Minuten erfolgt ein JWKS Abruf
    Und TGR setze lokale Variable "poppJwksRequestWait" auf "300"

    # Erster Ressource Abruf triggert JWKS-Download mit kurzer Cache-Dauer
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # JWKS ablaufen lassen und erneuten Download für 24h fehlschlagen lassen
    Und Setze im TigerProxy für die Nachricht "${poppJwksResponseCondition}" die Manipulation auf Feld "$.responseCode" und Wert "500"
    Und warte "${poppJwksRequestWait}" Sekunden
    # Prüfen das JWKS beim ersten Interval nicht abgerufen werden konnte
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Prüfen das JWKS nach 24h nicht abgefragt werden konnte
    Und warte "${poppJwksExpiryWait}" Sekunden
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Erneute Ressource Anfrage - bestehendes JWKS wird weiter genutzt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Erneuter Testlauf bei dem nach weiteren 24h JWKS nicht mehr verwendet werden darf
    # JWKS ablaufen lassen und erneuten Download für 24h fehlschlagen lassen
    Und Setze im TigerProxy für die Nachricht "${poppJwksResponseCondition}" die Manipulation auf Feld "$.responseCode" und Wert "500"
    Und warte "${poppJwksRequestWait}" Sekunden
    # Prüfen das JWKS beim nächsten Interval nicht abgerufen werden konnte
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Prüfen das JWKS nach 24h nicht abgefragt werden konnte
    Und warte "${poppJwksExpiryWait}" Sekunden
    Dann TGR finde die letzte Anfrage mit dem Pfad "${poppJwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "500"

    # Erneute Ressource Anfrage - bestehendes JWKS wird weiter genutzt
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
