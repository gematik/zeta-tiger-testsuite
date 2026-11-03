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

@oidc
@UseCase_04_01
Funktionalität: Apple-Client-Credentials mit App Attest bestätigen

  @A_25768
  @TA_A_25768_01
  @normal
  @ios
  @apple_attestation_registration
  @MASVS-CRYPTO
  @MASVS-RESILIENCE
  Szenario: Apple-Client übermittelt eine an seine Client-Credentials gebundene Attestierung
    Wenn TGR sende eine leere GET Anfrage an "${paths.appleRegistration.triggerUrl}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.appleRegistration.triggerPath}"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.guard.registerEndpointPath}" und Knoten "$.body.attestation_type" der mit "apple" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.registerEndpointPath}" haben
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Host']" überein mit "\Q${paths.appleRegistration.guardHost}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.attestation_type" überein mit "apple"
    Und TGR speichere Wert des Knotens "$.body.jwks.keys.0" der aktuellen Anfrage in der Variable "APPLE_CLIENT_JWK"
    Und TGR speichere Wert des Knotens "$.body.apple_attestation_object" der aktuellen Anfrage in der Variable "APPLE_ATTESTATION_OBJECT"
    Und TGR speichere Wert des Knotens "$.body.signed_hash_puk_client_sig" der aktuellen Anfrage in der Variable "APPLE_CLIENT_POP"
    Dann verifiziere das Apple-Attestierungsobjekt "${APPLE_ATTESTATION_OBJECT}" gegen Wurzeln "${paths.appleRegistration.trustAnchors}"
    Und verifiziere die Bindung des Apple-Attestierungsobjekts "${APPLE_ATTESTATION_OBJECT}" an Clientschlüssel "${APPLE_CLIENT_JWK}"
    Und verifiziere die Client-Besitzsignatur "${APPLE_CLIENT_POP}" mit Clientschlüssel "${APPLE_CLIENT_JWK}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id"
