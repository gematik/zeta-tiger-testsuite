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

# language: de
@UseCase_01_12
Funktionalität: Zuordnung der Monitoring-Clientdaten zu unabhängigen SMC-B Sessions

  @A_27496
  @additional_sdk_instance
  @monitoring_client_variant
  @normal
  @smb
  Szenariogrundriss: PDP und PEP unterscheiden <feld> und professionOID beim Wechsel A B A
    Gegeben sei lade Zertifikat mit Stem "80276883110001000000-C_SMCB91_AUT_E256_X509" aus dem Testzertifikat-Manifest in Variablen mit Präfix "MON_OTHER_CERT"
    Und schreibe Daten aus dem SMC-B Zertifikat "${MON_OTHER_CERT.crt_b64}" in die Variable "MON-OTHER-SMCB"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${MON-OTHER-SMCB.telematikId}" und Profession "${MON-OTHER-SMCB.professionId}" und speichere in der Variable "MON_OTHER_POPP_TOKEN"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.loadListInstances}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadListInstancesPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "MON_PREVIOUS_SDK_INSTANCES"
    Wenn TGR sende eine POST Anfrage an "${paths.client.loadCreateInstances}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {"count":1,"autoInit":false,"logLevel":null,"instances":[{"authMode":"SMB","fachdienstUrl":"${testdata.ccSdkInstances.fachdienstUrl}","disableTlsVerification":${testdata.ccSdkInstances.disableTlsVerification},"smbKeystoreB64":"${MON_OTHER_CERT.keystore_b64}","smbKeystoreAlias":"${MON_OTHER_CERT.keystore_alias}","smbKeystorePassword":"${MON_OTHER_CERT.keystore_password}","poppToken":"${MON_OTHER_POPP_TOKEN}"}]}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadCreateInstancesPath}"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "MON_CREATED_SDK_INSTANCE"
    Und merke die zusätzliche SDK-Instanz aus Antwort "${MON_CREATED_SDK_INSTANCE}" mit vorherigem Bestand "${MON_PREVIOUS_SDK_INSTANCES}" zur Bereinigung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.created" überein mit "1"
    Und TGR speichere Wert des Knotens "$.body.ids.0" der aktuellen Antwort in der Variable "MON_OTHER_SDK_ID"
    Und TGR setze lokale Variable "MON_OTHER_PATH" auf "${paths.client.loadResourcePathPrefix}/${MON_OTHER_SDK_ID}${paths.fachdienst.helloZetaPath}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.loadListInstances}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.loadListInstancesPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.[?(@.id == ${MON_OTHER_SDK_ID})].state" überein mit "CREATED"
    Und merke den primären SDK-Kontext zur Bereinigung
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "MON_BASE_CLIENT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "MON_BASE_PRODUCT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "MON_BASE_PRODUCT_VERSION"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "MON_BASE_SMCB_CERTIFICATE"
    Und schreibe Daten aus dem SMC-B Zertifikat "${MON_BASE_SMCB_CERTIFICATE}" in die Variable "MON-BASE-SMCB"
    Und TGR prüfe Variable "MON-OTHER-SMCB.professionId" stimmt überein mit "(?s)^(?!\Q${MON-BASE-SMCB.professionId}\E$).+"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.register}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "MON_PREPARED_CLIENT_ID"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "MON_CLIENT_PRIVATE_KEY"
    Und TGR setze lokale Variable "MON_EXPECTED_ID" auf "<product_id>"
    Und TGR setze lokale Variable "MON_EXPECTED_VERSION" auf "<product_version>"
    Und TGR prüfe Variable "<originalvariable>" stimmt überein mit "(?s)^(?!\Q<wert>\E$).+"
    Und Setze im TigerProxy für JWT in "$.body.client_assertion" das Feld "body.client_statement.posture.<feld>" auf Wert "<wert>" mit privatem Schlüssel "${MON_CLIENT_PRIVATE_KEY}" für Pfad "${paths.guard.tokenEndpointPath}" und 1 Ausführungen und ersetze JWK

    Und TGR setze lokale Variable "MON_PERMIT_POLICY_CONDITION" auf "isResponse && request.path =~ '^${paths.opa.decisionPath}$' && request.body.getFirst('input').get().getFirst('client_registration_data').get().getFirst('client_id').get().getFirst('content').get().rawStringContent == '${MON_PREPARED_CLIENT_ID}' && request.body.getFirst('input').get().getFirst('authorization_request').get().getFirst('grant_type').get().getFirst('content').get().rawStringContent == '${oauth_parameters.grant_type.tokenExchange}'"
    Und Setze im TigerProxy für die Nachricht "${MON_PERMIT_POLICY_CONDITION}" die Manipulation auf Feld "$.body.result" und Wert '{"allow":true,"ttl":{"access_token":300,"refresh_token":86400}}' und 1 Ausführungen

    Und TGR setze lokale Variable "MON_A_TRACE_ID" auf "!{randomHex(32)}"
    Und TGR setze lokale Variable "MON_A_TOKEN_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'POST' && message.path =~ '^${paths.guard.tokenEndpointPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_A_TRACE_ID}-${MON_A_TOKEN_PARENT}-01" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "MON_A_RESOURCE_1_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_A_TRACE_ID}-${MON_A_RESOURCE_1_PARENT}-01" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_A_TRACE_ID}-${MON_A_TOKEN_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "MON_A_ACCESS_TOKEN"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "MON_A_CLIENT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "MON_A_PRODUCT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "MON_A_PRODUCT_VERSION"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "MON_A_SMCB_CERTIFICATE"
    Und schreibe Daten aus dem SMC-B Zertifikat "${MON_A_SMCB_CERTIFICATE}" in die Variable "MON-SMCB"
    Und TGR setze lokale Variable "MON_A_PROFESSION_OID" auf "${MON-SMCB.professionId}"
    Und TGR prüfe Variable "MON_A_CLIENT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_A_PRODUCT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_A_PRODUCT_VERSION" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_A_PROFESSION_OID" stimmt überein mit "[0-9]+(\.[0-9]+)+"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${MON_A_CLIENT_ID}\E" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.ttl.access_token" überein mit "300"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.ttl.refresh_token" überein mit "86400"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.result.reasons"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_id" überein mit "\Q${MON_A_PRODUCT_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_version" überein mit "\Q${MON_A_PRODUCT_VERSION}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "\Q${MON_A_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.decision_id" der aktuellen Antwort in der Variable "MON_A_POLICY_DECISION_ID"
    Und TGR prüfe Variable "MON_A_POLICY_DECISION_ID" stimmt überein mit ".+"
    Und merke ab der aktuellen Antwort die Telemetriefrist "MON_A_POLICY_LOG" von "${testdata.telemetry_policy_decision_timeout_seconds}" Sekunden
    Und TGR speichere Wert des Knotens "$.header.traceparent" der aktuellen Anfrage in der Variable "MON_A_AS_POLICY_SPAN_ID"
    Und TGR prüfe Variable "MON_A_AS_POLICY_SPAN_ID" stimmt überein mit "00-${MON_A_TRACE_ID}-[0-9a-f]{16}-0[01]"
    Und TGR ersetze "^00-[0-9a-f]{32}-([0-9a-f]{16})-[0-9a-f]{2}$" mit "$1" im Inhalt der Variable "MON_A_AS_POLICY_SPAN_ID"

    Und TGR prüfe Variable "MON_A_CLIENT_ID" stimmt überein mit "\Q${MON_PREPARED_CLIENT_ID}\E"
    Und TGR prüfe Variable "MON_A_CLIENT_ID" stimmt überein mit "(?s)^(?!\Q${MON_BASE_CLIENT_ID}\E$).+"
    Und TGR prüfe Variable "MON_A_PRODUCT_ID" stimmt überein mit "\Q${MON_EXPECTED_ID}\E"
    Und TGR prüfe Variable "MON_A_PRODUCT_VERSION" stimmt überein mit "\Q${MON_EXPECTED_VERSION}\E"
    Und TGR prüfe Variable "MON_A_SMCB_CERTIFICATE" stimmt überein mit "\Q${MON_BASE_SMCB_CERTIFICATE}\E"
    Und TGR prüfe Variable "MON_A_PROFESSION_OID" stimmt überein mit "\Q${MON-BASE-SMCB.professionId}\E"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_A_TRACE_ID}-${MON_A_RESOURCE_1_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${MON_A_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}" und Knoten "$.header.traceparent" der mit "00-${MON_A_TRACE_ID}-[0-9a-f]{16}-0[01]" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.fachdienst.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    Und TGR setze lokale Variable "MON_B_TRACE_ID" auf "!{randomHex(32)}"
    Und TGR setze lokale Variable "MON_B_TOKEN_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'POST' && message.path =~ '^${paths.guard.tokenEndpointPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_B_TRACE_ID}-${MON_B_TOKEN_PARENT}-01" und 1 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "MON_B_RESOURCE_1_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_B_TRACE_ID}-${MON_B_RESOURCE_1_PARENT}-01" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${MON_OTHER_PATH}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${MON_OTHER_PATH}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_B_TRACE_ID}-${MON_B_TOKEN_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.access_token" der aktuellen Antwort in der Variable "MON_B_ACCESS_TOKEN"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Anfrage in der Variable "MON_B_CLIENT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "MON_B_PRODUCT_ID"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "MON_B_PRODUCT_VERSION"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "MON_B_SMCB_CERTIFICATE"
    Und schreibe Daten aus dem SMC-B Zertifikat "${MON_B_SMCB_CERTIFICATE}" in die Variable "MON-SMCB"
    Und TGR setze lokale Variable "MON_B_PROFESSION_OID" auf "${MON-SMCB.professionId}"
    Und TGR prüfe Variable "MON_B_CLIENT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_B_PRODUCT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_B_PRODUCT_VERSION" stimmt überein mit ".+"
    Und TGR prüfe Variable "MON_B_PROFESSION_OID" stimmt überein mit "[0-9]+(\.[0-9]+)+"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.opa.decisionPath}" und Knoten "$.body.input.client_registration_data.client_id" der mit "\Q${MON_B_CLIENT_ID}\E" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "true"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_id" überein mit "\Q${MON_B_PRODUCT_ID}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.product_version" überein mit "\Q${MON_B_PRODUCT_VERSION}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "\Q${MON_B_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.decision_id" der aktuellen Antwort in der Variable "MON_B_POLICY_DECISION_ID"
    Und TGR prüfe Variable "MON_B_POLICY_DECISION_ID" stimmt überein mit ".+"
    Und merke ab der aktuellen Antwort die Telemetriefrist "MON_B_POLICY_LOG" von "${testdata.telemetry_policy_decision_timeout_seconds}" Sekunden
    Und TGR speichere Wert des Knotens "$.header.traceparent" der aktuellen Anfrage in der Variable "MON_B_AS_POLICY_SPAN_ID"
    Und TGR prüfe Variable "MON_B_AS_POLICY_SPAN_ID" stimmt überein mit "00-${MON_B_TRACE_ID}-[0-9a-f]{16}-0[01]"
    Und TGR ersetze "^00-[0-9a-f]{32}-([0-9a-f]{16})-[0-9a-f]{2}$" mit "$1" im Inhalt der Variable "MON_B_AS_POLICY_SPAN_ID"

    Und TGR prüfe Variable "MON_B_CLIENT_ID" stimmt überein mit "(?s)^(?!\Q${MON_A_CLIENT_ID}\E$).+"
    Und TGR prüfe Variable "MON_B_PRODUCT_ID" stimmt überein mit "\Q${MON_BASE_PRODUCT_ID}\E"
    Und TGR prüfe Variable "MON_B_PRODUCT_VERSION" stimmt überein mit "\Q${MON_BASE_PRODUCT_VERSION}\E"
    Und TGR prüfe Variable "MON_B_SMCB_CERTIFICATE" stimmt überein mit "\Q${MON_OTHER_CERT.crt_b64}\E"
    Und TGR prüfe Variable "MON_B_PROFESSION_OID" stimmt überein mit "\Q${MON-OTHER-SMCB.professionId}\E"
    Und TGR prüfe Variable "MON_B_PROFESSION_OID" stimmt überein mit "(?s)^(?!\Q${MON_A_PROFESSION_OID}\E$).+"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_B_TRACE_ID}-${MON_B_RESOURCE_1_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${MON_B_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}" und Knoten "$.header.traceparent" der mit "00-${MON_B_TRACE_ID}-[0-9a-f]{16}-0[01]" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.fachdienst.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.tokenEndpointPath}" haben

    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "MON_A_RESOURCE_2_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "$.header.traceparent" und Wert "00-${MON_A_TRACE_ID}-${MON_A_RESOURCE_2_PARENT}-01" und 1 Ausführungen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.traceparent" überein mit "00-${MON_A_TRACE_ID}-${MON_A_RESOURCE_2_PARENT}-01"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${MON_A_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass zwischen aktueller Anfrage und aktueller Antwort eine Anfrage mit Pfad "${paths.fachdienst.helloZetaPath}" und Knoten "$.header.traceparent" der mit "00-${MON_A_TRACE_ID}-[0-9a-f]{16}-0[01]" übereinstimmt erfolgt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.fachdienst.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.message" überein mit "Hello ZETA!"
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.guard.helloZetaPath}" haben
    Und prüfe, dass genau 1 aufgezeichnete Anfragen den Pfad "${paths.fachdienst.helloZetaPath}" haben
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.guard.tokenEndpointPath}" hat

    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.opa.decisionPath}" hat

    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                           | size | track_total_hits |
      | resource.service.name:"${telemetry.service.policyEngine}" AND traceId:"${MON_A_TRACE_ID}" AND attributes.opa.decision_log.decision_id:"${MON_A_POLICY_DECISION_ID}" AND attributes.gematik.zeta.kind:"siem" | 2    | true             |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und wiederhole die aktuelle OpenSearch-GET-Abfrage innerhalb der Telemetriefrist "MON_A_POLICY_LOG" alle 5 Sekunden solange das Ergebnis leer ist
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.timed_out" überein mit "false"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body._shards.failed" überein mit "0"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.relation" überein mit "eq"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.value" überein mit "1"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.hits.hits.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.traceId" überein mit "${MON_A_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.resource['service.name']" überein mit "${telemetry.service.policyEngine}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['opa.event_type']" überein mit "decision_log"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['opa.decision_log.decision_id']" überein mit "${MON_A_POLICY_DECISION_ID}"
    Und TGR speichere Wert des Knotens "$.body.hits.hits.0._source.spanId" der aktuellen Antwort in der Variable "MON_A_POLICY_LOG_SPAN_ID"
    Und TGR speichere Wert des Knotens "$.body.hits.hits.0._source.body" der aktuellen Antwort in der Variable "MON_A_POLICY_LOG_BODY"
    Und TGR erstellt ein neues Rbel-Objekt "MON_A_POLICY_INPUT" mit Inhalt "${MON_A_POLICY_LOG_BODY}"
    Und TGR prüft, dass Rbel-Objekt "MON_A_POLICY_INPUT" an Stelle "$.decision_id" gleich "${MON_A_POLICY_DECISION_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_A_POLICY_INPUT" an Stelle "$.trace_id" gleich "${MON_A_TRACE_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_A_POLICY_INPUT" an Stelle "$.span_id" gleich "${MON_A_POLICY_LOG_SPAN_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_A_POLICY_INPUT" an Stelle "$.input.client_registration_data.product_id" gleich "${MON_A_PRODUCT_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_A_POLICY_INPUT" an Stelle "$.input.client_registration_data.product_version" gleich "${MON_A_PRODUCT_VERSION}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_A_POLICY_INPUT" an Stelle "$.input.user_info.professionOID" gleich "${MON_A_PROFESSION_OID}" ist

    Wenn TGR sende eine leere GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}/${MON_A_TRACE_ID}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPath}/${MON_A_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.traceID" überein mit "${MON_A_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.data.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_A_TOKEN_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].tags.[?(@.key == 'url.path')].value" überein mit "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_A_POLICY_LOG_SPAN_ID}')].traceID" überein mit "${MON_A_TRACE_ID}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.spanID == '${MON_A_POLICY_LOG_SPAN_ID}')].processID" der aktuellen Antwort in der Variable "MON_A_POLICY_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_A_POLICY_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_A_POLICY_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.policyEngine}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_A_POLICY_LOG_SPAN_ID}')].operationName" überein mit "${telemetry.span.policyEngine.data}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_A_POLICY_LOG_SPAN_ID}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_A_AS_POLICY_SPAN_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_A_AS_POLICY_SPAN_ID}')].traceID" überein mit "${MON_A_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_A_AS_POLICY_SPAN_ID}')].tags.[?(@.key == 'span.kind')].value" überein mit "client"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.spanID == '${MON_A_AS_POLICY_SPAN_ID}')].processID" der aktuellen Antwort in der Variable "MON_A_AS_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_A_AS_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_A_AS_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.authorizationServer}"

    Und TGR prüfe aktuelle Antwort im Knoten "$.body.data.0.spans" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "array",
        "items": {
          "if": {"required": ["operationName"], "properties": {"operationName": {"const": "${telemetry.span.httpProxy.pep}"}}},
          "then": {
            "type": "object",
            "required": ["traceID", "spanID", "processID", "references", "tags"],
            "properties": {
              "traceID": {"const": "${MON_A_TRACE_ID}"},
              "spanID": {"type": "string", "pattern": "^[0-9a-f]{16}$"},
              "processID": {"type": "string", "pattern": "^p[0-9]+$"},
              "references": {
                "type": "array",
                "contains": {
                  "type": "object",
                  "required": ["refType", "spanID", "traceID"],
                  "properties": {"refType": {"const": "CHILD_OF"}, "spanID": {"enum": ["${MON_A_RESOURCE_1_PARENT}", "${MON_A_RESOURCE_2_PARENT}"]}, "traceID": {"const": "${MON_A_TRACE_ID}"}}
                }
              },
              "tags": {
                "type": "array",
                "allOf": [
                  {"contains": {"type": "object", "required": ["key", "value"], "properties": {"key": {"const": "product.id"}, "value": {"const": "${MON_A_PRODUCT_ID}"}}}},
                  {"items": {"if": {"required": ["key"], "properties": {"key": {"const": "product.id"}}}, "then": {"required": ["value"], "properties": {"value": {"const": "${MON_A_PRODUCT_ID}"}}}}},
                  {"contains": {"type": "object", "required": ["key", "value"], "properties": {"key": {"const": "product.version"}, "value": {"const": "${MON_A_PRODUCT_VERSION}"}}}},
                  {"items": {"if": {"required": ["key"], "properties": {"key": {"const": "product.version"}}}, "then": {"required": ["value"], "properties": {"value": {"const": "${MON_A_PRODUCT_VERSION}"}}}}},
                  {"contains": {"type": "object", "required": ["key", "value"], "properties": {"key": {"const": "profession.oid"}, "value": {"const": "${MON_A_PROFESSION_OID}"}}}},
                  {"items": {"if": {"required": ["key"], "properties": {"key": {"const": "profession.oid"}}}, "then": {"required": ["value"], "properties": {"value": {"const": "${MON_A_PROFESSION_OID}"}}}}}
                ]
              }
            }
          }
        }
      }
      """

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].traceID" überein mit "${MON_A_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_A_RESOURCE_1_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.target')].value" überein mit "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.method')].value" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].tags.[?(@.key == 'product.id')].value" überein mit "\Q${MON_A_PRODUCT_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].tags.[?(@.key == 'product.version')].value" überein mit "\Q${MON_A_PRODUCT_VERSION}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].tags.[?(@.key == 'profession.oid')].value" überein mit "\Q${MON_A_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].spanID" der aktuellen Antwort in der Variable "MON_A_PEP_1_SPAN_ID"
    Und TGR ersetze "^([0-9a-f]{16}).*$" mit "$1" im Inhalt der Variable "MON_A_PEP_1_SPAN_ID"
    Und TGR prüfe Variable "MON_A_PEP_1_SPAN_ID" stimmt überein mit "[0-9a-f]{16}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_1_PARENT}')].processID" der aktuellen Antwort in der Variable "MON_A_PEP_1_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_A_PEP_1_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_A_PEP_1_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.httpProxy}"

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].traceID" überein mit "${MON_A_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_A_RESOURCE_2_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].tags.[?(@.key == 'http.target')].value" überein mit "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].tags.[?(@.key == 'http.method')].value" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].tags.[?(@.key == 'product.id')].value" überein mit "\Q${MON_A_PRODUCT_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].tags.[?(@.key == 'product.version')].value" überein mit "\Q${MON_A_PRODUCT_VERSION}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].tags.[?(@.key == 'profession.oid')].value" überein mit "\Q${MON_A_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].spanID" der aktuellen Antwort in der Variable "MON_A_PEP_2_SPAN_ID"
    Und TGR ersetze "^([0-9a-f]{16}).*$" mit "$1" im Inhalt der Variable "MON_A_PEP_2_SPAN_ID"
    Und TGR prüfe Variable "MON_A_PEP_2_SPAN_ID" stimmt überein mit "[0-9a-f]{16}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_A_RESOURCE_2_PARENT}')].processID" der aktuellen Antwort in der Variable "MON_A_PEP_2_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_A_PEP_2_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_A_PEP_2_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.httpProxy}"

    Und TGR prüfe Variable "MON_A_PEP_2_SPAN_ID" stimmt überein mit "(?s)^(?!\Q${MON_A_PEP_1_SPAN_ID}\E$).+"

    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                           | size | track_total_hits |
      | resource.service.name:"${telemetry.service.policyEngine}" AND traceId:"${MON_B_TRACE_ID}" AND attributes.opa.decision_log.decision_id:"${MON_B_POLICY_DECISION_ID}" AND attributes.gematik.zeta.kind:"siem" | 2    | true             |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und wiederhole die aktuelle OpenSearch-GET-Abfrage innerhalb der Telemetriefrist "MON_B_POLICY_LOG" alle 5 Sekunden solange das Ergebnis leer ist
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.timed_out" überein mit "false"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body._shards.failed" überein mit "0"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.relation" überein mit "eq"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.total.value" überein mit "1"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.hits.hits.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.traceId" überein mit "${MON_B_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.resource['service.name']" überein mit "${telemetry.service.policyEngine}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['opa.event_type']" überein mit "decision_log"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['opa.decision_log.decision_id']" überein mit "${MON_B_POLICY_DECISION_ID}"
    Und TGR speichere Wert des Knotens "$.body.hits.hits.0._source.spanId" der aktuellen Antwort in der Variable "MON_B_POLICY_LOG_SPAN_ID"
    Und TGR speichere Wert des Knotens "$.body.hits.hits.0._source.body" der aktuellen Antwort in der Variable "MON_B_POLICY_LOG_BODY"
    Und TGR erstellt ein neues Rbel-Objekt "MON_B_POLICY_INPUT" mit Inhalt "${MON_B_POLICY_LOG_BODY}"
    Und TGR prüft, dass Rbel-Objekt "MON_B_POLICY_INPUT" an Stelle "$.decision_id" gleich "${MON_B_POLICY_DECISION_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_B_POLICY_INPUT" an Stelle "$.trace_id" gleich "${MON_B_TRACE_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_B_POLICY_INPUT" an Stelle "$.span_id" gleich "${MON_B_POLICY_LOG_SPAN_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_B_POLICY_INPUT" an Stelle "$.input.client_registration_data.product_id" gleich "${MON_B_PRODUCT_ID}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_B_POLICY_INPUT" an Stelle "$.input.client_registration_data.product_version" gleich "${MON_B_PRODUCT_VERSION}" ist
    Und TGR prüft, dass Rbel-Objekt "MON_B_POLICY_INPUT" an Stelle "$.input.user_info.professionOID" gleich "${MON_B_PROFESSION_OID}" ist

    Wenn TGR sende eine leere GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}/${MON_B_TRACE_ID}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPath}/${MON_B_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.traceID" überein mit "${MON_B_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.data.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_B_TOKEN_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].tags.[?(@.key == 'url.path')].value" überein mit "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.authorizationServer.token}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_B_POLICY_LOG_SPAN_ID}')].traceID" überein mit "${MON_B_TRACE_ID}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.spanID == '${MON_B_POLICY_LOG_SPAN_ID}')].processID" der aktuellen Antwort in der Variable "MON_B_POLICY_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_B_POLICY_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_B_POLICY_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.policyEngine}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_B_POLICY_LOG_SPAN_ID}')].operationName" überein mit "${telemetry.span.policyEngine.data}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_B_POLICY_LOG_SPAN_ID}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_B_AS_POLICY_SPAN_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_B_AS_POLICY_SPAN_ID}')].traceID" überein mit "${MON_B_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.spanID == '${MON_B_AS_POLICY_SPAN_ID}')].tags.[?(@.key == 'span.kind')].value" überein mit "client"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.spanID == '${MON_B_AS_POLICY_SPAN_ID}')].processID" der aktuellen Antwort in der Variable "MON_B_AS_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_B_AS_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_B_AS_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.authorizationServer}"

    Und TGR prüfe aktuelle Antwort im Knoten "$.body.data.0.spans" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "array",
        "items": {
          "if": {"required": ["operationName"], "properties": {"operationName": {"const": "${telemetry.span.httpProxy.pep}"}}},
          "then": {
            "type": "object",
            "required": ["traceID", "spanID", "processID", "references", "tags"],
            "properties": {
              "traceID": {"const": "${MON_B_TRACE_ID}"},
              "spanID": {"type": "string", "pattern": "^[0-9a-f]{16}$"},
              "processID": {"type": "string", "pattern": "^p[0-9]+$"},
              "references": {
                "type": "array",
                "contains": {"type": "object", "required": ["refType", "spanID", "traceID"], "properties": {"refType": {"const": "CHILD_OF"}, "spanID": {"enum": ["${MON_B_RESOURCE_1_PARENT}"]}, "traceID": {"const": "${MON_B_TRACE_ID}"}}}
              },
              "tags": {
                "type": "array",
                "allOf": [
                  {"contains": {"type": "object", "required": ["key", "value"], "properties": {"key": {"const": "product.id"}, "value": {"const": "${MON_B_PRODUCT_ID}"}}}},
                  {"items": {"if": {"required": ["key"], "properties": {"key": {"const": "product.id"}}}, "then": {"required": ["value"], "properties": {"value": {"const": "${MON_B_PRODUCT_ID}"}}}}},
                  {"contains": {"type": "object", "required": ["key", "value"], "properties": {"key": {"const": "product.version"}, "value": {"const": "${MON_B_PRODUCT_VERSION}"}}}},
                  {"items": {"if": {"required": ["key"], "properties": {"key": {"const": "product.version"}}}, "then": {"required": ["value"], "properties": {"value": {"const": "${MON_B_PRODUCT_VERSION}"}}}}},
                  {"contains": {"type": "object", "required": ["key", "value"], "properties": {"key": {"const": "profession.oid"}, "value": {"const": "${MON_B_PROFESSION_OID}"}}}},
                  {"items": {"if": {"required": ["key"], "properties": {"key": {"const": "profession.oid"}}}, "then": {"required": ["value"], "properties": {"value": {"const": "${MON_B_PROFESSION_OID}"}}}}}
                ]
              }
            }
          }
        }
      }
      """

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].traceID" überein mit "${MON_B_TRACE_ID}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].references.[?(@.refType == 'CHILD_OF')].spanID" überein mit "${MON_B_RESOURCE_1_PARENT}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.target')].value" überein mit "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.method')].value" überein mit "GET"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].tags.[?(@.key == 'http.response.status_code')].value" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].tags.[?(@.key == 'product.id')].value" überein mit "\Q${MON_B_PRODUCT_ID}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].tags.[?(@.key == 'product.version')].value" überein mit "\Q${MON_B_PRODUCT_VERSION}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].tags.[?(@.key == 'profession.oid')].value" überein mit "\Q${MON_B_PROFESSION_OID}\E"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].spanID" der aktuellen Antwort in der Variable "MON_B_PEP_1_SPAN_ID"
    Und TGR ersetze "^([0-9a-f]{16}).*$" mit "$1" im Inhalt der Variable "MON_B_PEP_1_SPAN_ID"
    Und TGR prüfe Variable "MON_B_PEP_1_SPAN_ID" stimmt überein mit "[0-9a-f]{16}"
    Und TGR speichere Wert des Knotens "$.body.data.0.spans.[?(@.operationName == '${telemetry.span.httpProxy.pep}' && @.references.0.spanID == '${MON_B_RESOURCE_1_PARENT}')].processID" der aktuellen Antwort in der Variable "MON_B_PEP_1_PROCESS_IDS"
    Und TGR ersetze "^(p[0-9]+).*$" mit "$1" im Inhalt der Variable "MON_B_PEP_1_PROCESS_IDS"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.data.0.processes.${MON_B_PEP_1_PROCESS_IDS}.serviceName" überein mit "${telemetry.service.httpProxy}"

    @TA_A_27496_01 @TA_A_27496_03 @TA_A_27496_04 @TA_A_27496_06
    Beispiele: Isolierte Produkt-ID und andere Berufsgruppe
      | feld            | wert                  | originalvariable         | product_id              | product_version             |
      | product_id      | test-proxy-monitoring | MON_BASE_PRODUCT_ID      | test-proxy-monitoring   | ${MON_BASE_PRODUCT_VERSION} |

    @TA_A_27496_02 @TA_A_27496_03 @TA_A_27496_05 @TA_A_27496_06
    Beispiele: Isolierte Produktversion und andere Berufsgruppe
      | feld            | wert                  | originalvariable         | product_id              | product_version             |
      | product_version | 9.9.9                 | MON_BASE_PRODUCT_VERSION | ${MON_BASE_PRODUCT_ID}  | 9.9.9                       |
