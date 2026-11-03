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

@UseCase_04_02
@require_kubectl
@oidc
@require_oidc_decryption_key
Funktionalität: Bestehende Registrierung nicht allein durch IDP-Authentisierung wiederherstellen

  Grundlage:
    # C1 und C3 bestätigen E1; C2 hat nur dieselbe IDP-Identität, eigene Schlüssel und E2.
    Gegeben sei ein steuerbarer SDK-Adapter für getrennte IDP-Faktorkontexte
    Wenn der IDP-SDK-Kontext "owner" die Operation "create" ausführt
    Und der IDP-SDK-Kontext "owner" die Operation "discover" ausführt
    Und der IDP-SDK-Kontext "owner" die Operation "register" ausführt
    Und der IDP-SDK-Kontext "owner" die Operation "authenticate" ausführt
    Und der IDP-SDK-Kontext "owner" die Operation "otp" ausführt
    Wenn der IDP-SDK-Kontext "attacker" die Operation "create" ausführt
    Und der IDP-SDK-Kontext "attacker" die Operation "discover" ausführt
    Und der IDP-SDK-Kontext "attacker" die Operation "register" ausführt
    Und der IDP-SDK-Kontext "attacker" die Operation "authenticate" ausführt
    # Nur E1-Mailmetadaten beobachten; Original-OTP niemals lesen oder erfüllen.
    Und der IDP-SDK-Kontext "attacker" die Operation "withhold" ausführt
    Wenn der IDP-SDK-Kontext "control" die Operation "create" ausführt
    Und der IDP-SDK-Kontext "control" die Operation "discover" ausführt
    Und der IDP-SDK-Kontext "control" die Operation "register" ausführt
    Und der IDP-SDK-Kontext "control" die Operation "authenticate" ausführt
    Und der IDP-SDK-Kontext "control" die Operation "otp" ausführt
    Und der IDP-SDK-Kontext "attacker" die Operation "observe" ausführt
    Dann die drei IDP-SDK-Kontexte besitzen getrennte Schlüssel und den erwarteten Faktorbestand
    Und der IDP-SDK-Kontext "owner" hat einen vollständig gebundenen Faktor-Grant erhalten
    Und der IDP-SDK-Kontext "control" hat einen vollständig gebundenen Faktor-Grant erhalten
    Und der IDP-SDK-Kontext "attacker" besitzt nur ein IDP-Binding ohne bestätigten Faktor

    # Das SDK wartet auf OTP. Der unabhängige Prüfclient benötigt deshalb C2s eigene
    # Testschlüssel, um die AS-Autorisierung mit originalem Binding Token aktiv zu prüfen.
    Gegeben sei ein unabhängiger Prüfclient mit den eigenen Schlüsseln des wartenden IDP-Clients

  @A_29896
  @TA_A_29896_03
  @normal
  @Ignore
  Szenario: Folgeregistrierung benötigt einen ursprünglichen Faktor (Ohne Nachweis eines alten Faktors)
    Wenn der IDP-only-Prüfclient den Tokenaustausch ohne ursprünglichen Faktor versucht
    Dann der IDP-only-Abschluss wird abgelehnt und alle ursprünglichen Bindungen bleiben erhalten

  @A_29896
  @TA_A_29896_03
  @normal
  @Ignore
  Szenario: Folgeregistrierung benötigt einen ursprünglichen Faktor (Neue ungebundene E-Mail als Ersatz)
    Dann die ursprüngliche E-Mail-Bindung wurde tatsächlich vom ersten SDK-Kontext angelegt
    Wenn der IDP-only-Prüfclient die ursprüngliche Challenge als positive Proof-Kontrolle erneut anfordert
    Dann die positive IDP-only-Proof-Kontrolle hat genau eine ursprüngliche Challenge zugestellt
    Wenn der IDP-only-Prüfclient eine neue ungebundene E-Mail als Ersatz anbietet
    # Falls der AS erneut E1 verlangt, bleibt sie ungelesen. Nur eine echte E2-Challenge wird erfüllt.
    Und der IDP-only-Prüfclient ausschließlich eine angebotene Ersatz-E-Mail-Challenge bestätigt
    Und der IDP-only-Prüfclient den Tokenaustausch ohne ursprünglichen Faktor versucht
    Dann der IDP-only-Abschluss wird abgelehnt und alle ursprünglichen Bindungen bleiben erhalten
