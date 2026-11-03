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

@require_kubectl
@oidc
@flow_afo_continuation
@reset_testdriver
Funktionalität: Software-Schlüssel und Client-Statement ohne Hardwareattestierung erstellen

  Szenariogrundriss: Software-Fallback auf <Plattform> erzeugt Schlüssel und Statement nach dem Nonce-Bezug
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "FLOW_KVNR"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "tiger.lib.validation.messageSortOrder" auf "TIMESTAMP"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.client_private_key"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.client_public_key"
    Und TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "FLOW_CLIENT"
    Und TGR speichere Wert des Knotens "$.body.jwks" der aktuellen Anfrage in der Variable "FLOW_JWKS"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "FALLBACK_PRIVATE"
    Und TGR speichere Wert des Knotens "$.body.client_public_key" der aktuellen Antwort in der Variable "FALLBACK_PUBLIC"
    Und prüfe Software-Schlüssel "${FALLBACK_PUBLIC}" gehört zum registrierten JWKS "${FLOW_JWKS}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "FALLBACK_NONCE"
    Und TGR prüfe Variable "FALLBACK_NONCE" stimmt überein mit ".+"
    Und TGR finde die nächste Nachricht mit Knoten "$.body" der mit "\Q${FALLBACK_NONCE}\E" übereinstimmt
    Und TGR finde die nächste Nachricht mit Knoten "$.path" der mit "\Q${testdata.ccPar.outerPath}\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.sub" überein mit "\Q${FLOW_CLIENT}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture_type" überein mit "software"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.os"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.os_version"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.product_id"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion.body.client_statement.posture.product_version"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.public_key" der aktuellen Anfrage in der Variable "FALLBACK_STATEMENT_KEY"
    Und prüfe Software-Schlüssel "${FALLBACK_STATEMENT_KEY}" gehört zum registrierten JWKS "${FLOW_JWKS}"
    Und TGR speichere Wert des Knotens "$.body.client_assertion" der aktuellen Anfrage in der Variable "FALLBACK_ASSERTION"
    Und verifiziere die ES256 Signatur des JWT "${FALLBACK_ASSERTION}" mit öffentlichem Schlüssel aus privatem Schlüssel "${FALLBACK_PRIVATE}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.platform" überein mit "<Plattformwert>"

    @software_fallback_portable
    Beispiele: Softwarepfad auf Linux
      | Plattform | Plattformwert |
      | Linux     | linux         |

    @UseCase_04_13
    @A_29809
    @TA_A_29809_03
    @MASVS-RESILIENCE
    Beispiele: Android ohne Hardwareattestierung
      | Plattform | Plattformwert |
      | Android   | android       |

    @UseCase_04_01
    @UseCase_04_14
    @A_29782
    @A_29810
    @TA_A_29782_04
    @TA_A_29810_02
    @MASVS-CRYPTO
    @MASVS-RESILIENCE
    Beispiele: Apple ohne Secure Enclave oder App Attest
      | Plattform | Plattformwert |
      | Apple     | apple         |

