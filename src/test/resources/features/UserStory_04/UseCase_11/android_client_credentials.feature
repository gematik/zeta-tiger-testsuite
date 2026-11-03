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
@UseCase_04_11
Funktionalität: Android-Client-Credentials mit Key and ID Attestation bestätigen

  @A_25434
  @TA_A_25434_01
  @normal
  @android
  @android_attestation_registration
  @MASVS-RESILIENCE
  Szenario: Android-Client übermittelt eine an seine Client-Credentials gebundene Attestierung
    Wenn TGR sende eine leere GET Anfrage an "${paths.androidRegistration.triggerUrl}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.androidRegistration.triggerPath}"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.guard.registerEndpointPath}" und Knoten "$.body.attestation_type" der mit "android" übereinstimmt erfolgt
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.registerEndpointPath}" und Knoten "$.body.attestation_type" der mit "android" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Host']" überein mit "\Q${paths.androidRegistration.guardHost}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.attestation_type" überein mit "android"
    Und TGR speichere Wert des Knotens "$.body.jwks.keys.0" der aktuellen Anfrage in der Variable "ANDROID_CLIENT_JWK"
    Und TGR speichere Wert des Knotens "$.body.android_key_attestation_certificate_chain" der aktuellen Anfrage in der Variable "ANDROID_AK_CHAIN"
    Und TGR speichere Wert des Knotens "$.body.signed_hash_puk_client_sig" der aktuellen Anfrage in der Variable "ANDROID_CLIENT_POP"
    Dann verifiziere die Android-Attestierungskette "${ANDROID_AK_CHAIN}" gegen Wurzeln "${paths.androidRegistration.trustAnchors}"
    Und verifiziere die Bindung der Android-Attestierungskette "${ANDROID_AK_CHAIN}" an Clientschlüssel "${ANDROID_CLIENT_JWK}"
    Und verifiziere die Client-Besitzsignatur "${ANDROID_CLIENT_POP}" mit Clientschlüssel "${ANDROID_CLIENT_JWK}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id"

  @A_25434
  @A_29781
  @TA_A_25434_01
  @TA_A_29781_01
  @normal
  @android
  @android_attestation_without_play_integrity
  @MASVS-RESILIENCE
  @MASVS-CRYPTO
  Szenario: Android-Client übermittelt eine an seine Client-Credentials gebundene Attestierung ohne Play Integrity
    Wenn TGR sende eine leere GET Anfrage an "${paths.androidRegistration.triggerUrl}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.androidRegistration.triggerPath}"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.guard.registerEndpointPath}" und Knoten "$.body.attestation_type" der mit "android" übereinstimmt erfolgt
    Dann TGR finde die nächste Anfrage mit Pfad "${paths.guard.registerEndpointPath}" und Knoten "$.body.attestation_type" der mit "android" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Host']" überein mit "\Q${paths.androidRegistration.guardHost}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.attestation_type" überein mit "android"
    Und TGR prüfe aktueller Request enthält nicht Knoten "$.body.play_integrity_token"
    Und TGR speichere Wert des Knotens "$.body.jwks.keys.0" der aktuellen Anfrage in der Variable "ANDROID_CLIENT_JWK"
    Und TGR speichere Wert des Knotens "$.body.android_key_attestation_certificate_chain" der aktuellen Anfrage in der Variable "ANDROID_AK_CHAIN"
    Und TGR speichere Wert des Knotens "$.body.signed_hash_puk_client_sig" der aktuellen Anfrage in der Variable "ANDROID_CLIENT_POP"
    Dann verifiziere die Android-Attestierungskette "${ANDROID_AK_CHAIN}" gegen Wurzeln "${paths.androidRegistration.trustAnchors}"
    Und verifiziere die Bindung der Android-Attestierungskette "${ANDROID_AK_CHAIN}" an Clientschlüssel "${ANDROID_CLIENT_JWK}"
    Und verifiziere die Client-Besitzsignatur "${ANDROID_CLIENT_POP}" mit Clientschlüssel "${ANDROID_CLIENT_JWK}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id"
