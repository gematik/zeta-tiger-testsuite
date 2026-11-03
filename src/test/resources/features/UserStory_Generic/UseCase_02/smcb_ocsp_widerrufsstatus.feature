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

@UseCase_Generic_02
Funktionalität: OCSP-Widerrufsprüfung beim SMC-B-Token-Exchange

  @normal
  @deployment_modification
  @smcb_ocsp_status
  @additional_sdk_instance
  @A_29046
  @TA_A_29046_03
  @smb
  Szenario: SMC-B-Token-Exchange akzeptiert gültiges und lehnt widerrufenes Zertifikat ab
    Gegeben sei wähle einen zufälligen Zertifikat-Stem mit Muster "${testdata.ccOcsp.certificatePattern}" aus dem Testzertifikat-Manifest in Variable "CC_OCSP_STEM"
    Und lade Zertifikat mit Stem "${CC_OCSP_STEM}" aus dem Testzertifikat-Manifest in Variablen mit Präfix "SMOKE_OCSP"
    Und lade X.509-Zertifikat aus Datei "${testdata.ccOcsp.issuerCertificate}" in Variable "SMOKE_OCSP_ISSUER"
    Und schreibe Daten aus dem SMC-B Zertifikat "${SMOKE_OCSP.crt_b64}" in die Variable "SMOKE_OCSP_ACTOR"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${SMOKE_OCSP_ACTOR.telematikId}" und Profession "${SMOKE_OCSP_ACTOR.professionId}" und speichere in der Variable "SMOKE_OCSP_POPP"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.loadListInstances}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadListInstancesPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "SMOKE_OCSP_PREVIOUS_INSTANCES"
    Wenn TGR sende eine POST Anfrage an "${paths.client.loadCreateInstances}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"count":1,"autoInit":false,"logLevel":null,"instances":[{"authMode":"SMB","fachdienstUrl":"${testdata.ccSdkInstances.fachdienstUrl}","disableTlsVerification":${testdata.ccSdkInstances.disableTlsVerification},"smbKeystoreB64":"${SMOKE_OCSP.keystore_b64}","smbKeystoreAlias":"${SMOKE_OCSP.keystore_alias}","smbKeystorePassword":"${SMOKE_OCSP.keystore_password}","poppToken":"${SMOKE_OCSP_POPP}"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadCreateInstancesPath}"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "SMOKE_OCSP_CREATED_INSTANCE"
    Und merke die zusätzliche SDK-Instanz aus Antwort "${SMOKE_OCSP_CREATED_INSTANCE}" mit vorherigem Bestand "${SMOKE_OCSP_PREVIOUS_INSTANCES}" zur Bereinigung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.created" überein mit "1"
    Und TGR speichere Wert des Knotens "$.body.ids.0" der aktuellen Antwort in der Variable "SMOKE_OCSP_SDK_ID"
    Und TGR setze lokale Variable "SMOKE_OCSP_RESOURCE" auf "${paths.client.baseUrl}${paths.client.loadResourcePathPrefix}/${SMOKE_OCSP_SDK_ID}${paths.fachdienst.helloZetaPath}"
    Und TGR setze lokale Variable "SMOKE_OCSP_RESET" auf "${paths.client.baseUrl}${paths.client.loadDriverPathPrefix}/${SMOKE_OCSP_SDK_ID}/reset"
    Und der Zertifikatsvalidierungs-Mock verwendet den Modus "always_good"
    Und starte das Deployment "${zetaDeploymentConfig.authserver.podName}" neu und warte auf Bereitschaft
    Und TGR sende eine leere GET Anfrage an "${SMOKE_OCSP_RESET}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadDriverPathPrefix}/${SMOKE_OCSP_SDK_ID}/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${SMOKE_OCSP_RESOURCE}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${SMOKE_OCSP.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.access_token"
    Und TGR finde die erste Anfrage mit Pfad "${testdata.ccOcsp.pathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat "${SMOKE_OCSP.crt_b64}" mit Ausstellerzertifikat "${SMOKE_OCSP_ISSUER}" ab
    Und die aktuelle OCSP-Antwort für Zertifikat "${SMOKE_OCSP.crt_b64}" mit Ausstellerzertifikat "${SMOKE_OCSP_ISSUER}" ist gültig und meldet "good"

    Gegeben sei der Zertifikatsvalidierungs-Mock meldet nur das Zertifikat "${SMOKE_OCSP.crt_b64}" als widerrufen
    Und starte das Deployment "${zetaDeploymentConfig.authserver.podName}" neu und warte auf Bereitschaft
    Und TGR sende eine leere GET Anfrage an "${SMOKE_OCSP_RESET}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadDriverPathPrefix}/${SMOKE_OCSP_SDK_ID}/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Wenn TGR sende eine leere GET Anfrage an "${SMOKE_OCSP_RESOURCE}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${SMOKE_OCSP.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "4[0-9]{2}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.error_description" überein mit "(?is).*certificate has been revoked.*"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"
    Und TGR finde die erste Anfrage mit Pfad "${testdata.ccOcsp.pathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat "${SMOKE_OCSP.crt_b64}" mit Ausstellerzertifikat "${SMOKE_OCSP_ISSUER}" ab
    Und die aktuelle OCSP-Antwort für Zertifikat "${SMOKE_OCSP.crt_b64}" mit Ausstellerzertifikat "${SMOKE_OCSP_ISSUER}" ist gültig und meldet "revoked"
