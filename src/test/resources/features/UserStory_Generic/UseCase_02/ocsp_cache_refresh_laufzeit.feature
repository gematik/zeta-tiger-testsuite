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
Funktionalität: OCSP-Cache-Dauer entspricht der wirksamen SMC-B-Refresh-Token-Laufzeit

  @A_29046
  @TA_A_29046_06
  @normal
  @additional_sdk_instance
  @smb
  Szenariogrundriss: OCSP-Cache passt zur ausgegebenen Refresh-Token-Laufzeit bei Policy-Wert <Laufzeit> Sekunden
    Gegeben sei wähle einen zufälligen Zertifikat-Stem mit Muster "${testdata.ccOcsp.certificatePattern}" aus dem Testzertifikat-Manifest in Variable "CC_OCSP_STEM"
    Und lade Zertifikat mit Stem "${CC_OCSP_STEM}" aus dem Testzertifikat-Manifest in Variablen mit Präfix "CC_OCSP_ID"
    Und lade X.509-Zertifikat aus Datei "${testdata.ccOcsp.issuerCertificate}" in Variable "CC_OCSP_ISSUER"
    Und schreibe Daten aus dem SMC-B Zertifikat "${CC_OCSP_ID.crt_b64}" in die Variable "CC_OCSP_ACTOR"
    Und speichere die Telematik-ID aus SMC-B Zertifikat "${CC_OCSP_ID.crt_b64}" in Variable "CC_OCSP_TELEMATIK_ID"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC_OCSP_ACTOR.telematikId}" und Profession "${CC_OCSP_ACTOR.professionId}" und speichere in der Variable "CC_OCSP_POPP"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.loadListInstances}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadListInstancesPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_OCSP_PREVIOUS_INSTANCES"
    Wenn TGR sende eine POST Anfrage an "${paths.client.loadCreateInstances}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"count":1,"autoInit":false,"logLevel":null,"instances":[{"authMode":"SMB","fachdienstUrl":"${testdata.ccSdkInstances.fachdienstUrl}","disableTlsVerification":${testdata.ccSdkInstances.disableTlsVerification},"smbKeystoreB64":"${CC_OCSP_ID.keystore_b64}","smbKeystoreAlias":"${CC_OCSP_ID.keystore_alias}","smbKeystorePassword":"${CC_OCSP_ID.keystore_password}","poppToken":"${CC_OCSP_POPP}"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadCreateInstancesPath}"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_OCSP_CREATED_INSTANCE"
    Und merke die zusätzliche SDK-Instanz aus Antwort "${CC_OCSP_CREATED_INSTANCE}" mit vorherigem Bestand "${CC_OCSP_PREVIOUS_INSTANCES}" zur Bereinigung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.created" überein mit "1"
    Und TGR speichere Wert des Knotens "$.body.ids.0" der aktuellen Antwort in der Variable "CC_OCSP_SDK_ID"
    Und TGR setze lokale Variable "CC_OCSP_RESOURCE" auf "${paths.client.baseUrl}${paths.client.loadResourcePathPrefix}/${CC_OCSP_SDK_ID}${paths.fachdienst.helloZetaPath}"
    Und TGR setze lokale Variable "CC_OCSP_RESET" auf "${paths.client.baseUrl}${paths.client.loadDriverPathPrefix}/${CC_OCSP_SDK_ID}/reset"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "ccOcspPolicy" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}' && request.body.getFirst('input').get().getFirst('authorization_request').get().getFirst('grant_type').get().getFirst('content').get().rawStringContent == '${oauth_parameters.grant_type.tokenExchange}' && request.body.getFirst('input').get().getFirst('user_info').get().getFirst('identifier').get().getFirst('content').get().rawStringContent == '${CC_OCSP_TELEMATIK_ID}'"
    Und Setze im TigerProxy für die Nachricht "${ccOcspPolicy}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "<Laufzeit>" und 3 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${CC_OCSP_RESOURCE}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${CC_OCSP_ID.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und merke den aktuellen SMC-B-Exchange
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "CC_OCSP_EXP"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.iat" der aktuellen Antwort in der Variable "CC_OCSP_IAT"
    Und TGR setze lokale Variable "CC_OCSP_LIFETIME" auf "!{${CC_OCSP_EXP} - ${CC_OCSP_IAT}}"
    Und TGR prüfe Variable "CC_OCSP_LIFETIME" stimmt überein mit "<Laufzeitbereich>"
    Und prüfe die OCSP-Messbasis für Zertifikat "${CC_OCSP_ID.crt_b64}" mit Ausstellerzertifikat "${CC_OCSP_ISSUER}"
    Und TGR finde die erste Anfrage mit Pfad "${testdata.ccOcsp.pathPattern}" und Knoten "$.body.requests.0.serialNumber" der mit "${CC_OCSP_SERIAL}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat "${CC_OCSP_ID.crt_b64}" mit Ausstellerzertifikat "${CC_OCSP_ISSUER}" ab
    Und merke die aktuelle OCSP-Antwort als Cache-Beginn mit Refresh-Token-Laufzeit "${CC_OCSP_LIFETIME}" Sekunden und Ablauf "${CC_OCSP_EXP}"

    Gegeben sei TGR sende eine leere GET Anfrage an "${CC_OCSP_RESET}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadDriverPathPrefix}/${CC_OCSP_SDK_ID}/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und warte bis -10 Sekunden relativ zum gemerkten OCSP-Cache-Ablauf
    Wenn TGR sende eine leere GET Anfrage an "${CC_OCSP_RESOURCE}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${CC_OCSP_ID.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und merke den aktuellen SMC-B-Exchange
    Dann wurde der SMC-B-OCSP-Cache kurz vor Ablauf ohne erneute OCSP-Anfrage verwendet

    Gegeben sei TGR sende eine leere GET Anfrage an "${CC_OCSP_RESET}"
    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadDriverPathPrefix}/${CC_OCSP_SDK_ID}/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und warte bis 2 Sekunden relativ zum gemerkten OCSP-Cache-Ablauf
    Wenn TGR sende eine leere GET Anfrage an "${CC_OCSP_RESOURCE}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${CC_OCSP_ID.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und merke den aktuellen SMC-B-Exchange
    Dann TGR finde die letzte Anfrage mit Pfad "${testdata.ccOcsp.pathPattern}" und Knoten "$.body.requests.0.serialNumber" der mit "${CC_OCSP_SERIAL}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat "${CC_OCSP_ID.crt_b64}" mit Ausstellerzertifikat "${CC_OCSP_ISSUER}" ab
    Und wurde der SMC-B-OCSP-Cache nach Refresh-Token-Ablauf durch die aktuelle OCSP-Anfrage erneuert

    Beispiele:
      | Laufzeit | Laufzeitbereich |
      | 30       | 3[0-9]          |
      | 60       | 6[0-9]          |

  @A_29046
  @TA_A_29046_06
  @normal
  @deployment_modification
  @smb
  Szenariogrundriss: OCSP-Cache passt nach AS-Neustart zur ausgegebenen Refresh-Token-Laufzeit bei Policy-Wert <Laufzeit> Sekunden
    Gegeben sei lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "CC_OCSP_DEPLOYMENT"
    Und speichere die Telematik-ID aus SMC-B Zertifikat "${CC_OCSP_DEPLOYMENT.crt_b64}" in Variable "CC_OCSP_TELEMATIK_ID"
    Und starte das Deployment "${zetaDeploymentConfig.authserver.podName}" neu und warte auf Bereitschaft
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "ccOcspPolicy" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}' && request.body.getFirst('input').get().getFirst('authorization_request').get().getFirst('grant_type').get().getFirst('content').get().rawStringContent == '${oauth_parameters.grant_type.tokenExchange}' && request.body.getFirst('input').get().getFirst('user_info').get().getFirst('identifier').get().getFirst('content').get().rawStringContent == '${CC_OCSP_TELEMATIK_ID}'"
    Und Setze im TigerProxy für die Nachricht "${ccOcspPolicy}" die Manipulation auf Feld "$.body.result.ttl.refresh_token" und Wert "<Laufzeit>" und 3 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${CC_OCSP_DEPLOYMENT.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und merke den aktuellen SMC-B-Exchange
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.exp" der aktuellen Antwort in der Variable "CC_OCSP_EXP"
    Und TGR speichere Wert des Knotens "$.body.refresh_token.body.iat" der aktuellen Antwort in der Variable "CC_OCSP_IAT"
    Und TGR setze lokale Variable "CC_OCSP_LIFETIME" auf "!{${CC_OCSP_EXP} - ${CC_OCSP_IAT}}"
    Und TGR prüfe Variable "CC_OCSP_LIFETIME" stimmt überein mit "<Laufzeitbereich>"
    Und prüfe die OCSP-Messbasis für Zertifikat "${CC_OCSP_DEPLOYMENT.crt_b64}" mit Ausstellerzertifikat "${CC_OCSP_DEPLOYMENT.issuer_crt_b64}"
    Und TGR finde die erste Anfrage mit Pfad "${testdata.ccOcsp.pathPattern}" und Knoten "$.body.requests.0.serialNumber" der mit "${CC_OCSP_SERIAL}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat "${CC_OCSP_DEPLOYMENT.crt_b64}" mit Ausstellerzertifikat "${CC_OCSP_DEPLOYMENT.issuer_crt_b64}" ab
    Und merke die aktuelle OCSP-Antwort als Cache-Beginn mit Refresh-Token-Laufzeit "${CC_OCSP_LIFETIME}" Sekunden und Ablauf "${CC_OCSP_EXP}"

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und warte bis -10 Sekunden relativ zum gemerkten OCSP-Cache-Ablauf
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${CC_OCSP_DEPLOYMENT.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und merke den aktuellen SMC-B-Exchange
    Dann wurde der SMC-B-OCSP-Cache kurz vor Ablauf ohne erneute OCSP-Anfrage verwendet

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/reset"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und warte bis 2 Sekunden relativ zum gemerkten OCSP-Cache-Ablauf
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "$.body.subject_token.header.x5c.0" der mit "${CC_OCSP_DEPLOYMENT.crt_b64}" übereinstimmt
    Und TGR prüfe aktuelle Anfrage stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und merke den aktuellen SMC-B-Exchange
    Dann TGR finde die letzte Anfrage mit Pfad "${testdata.ccOcsp.pathPattern}" und Knoten "$.body.requests.0.serialNumber" der mit "${CC_OCSP_SERIAL}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und die aktuelle OCSP-Anfrage fragt das Zertifikat "${CC_OCSP_DEPLOYMENT.crt_b64}" mit Ausstellerzertifikat "${CC_OCSP_DEPLOYMENT.issuer_crt_b64}" ab
    Und wurde der SMC-B-OCSP-Cache nach Refresh-Token-Ablauf durch die aktuelle OCSP-Anfrage erneuert

    Beispiele:
      | Laufzeit | Laufzeitbereich |
      | 30       | 3[0-9]          |
      | 60       | 6[0-9]          |
