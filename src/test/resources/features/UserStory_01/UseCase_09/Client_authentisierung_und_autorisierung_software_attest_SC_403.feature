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

@UseCase_01_09
Funktionalität: Client_authentisierung_und_autorisierung_software_attest_SC_403

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Und TGR sende eine leere GET Anfrage an "${paths.tigerProxy.baseUrl}/resetMessages"

  @dev
  @A_25661
  @A_27401
  @A_26661
  @TA_A_25661_03
  @TA_A_26661_04
  @TA_A_26662_01
  @TA_A_27401_01
  Szenario: Policy Decision - Zugriffsverweigerung bei allow=false liefert HTTP 403
    # OPA Response manipulieren: allow auf false setzen
    # Bei allow=false muss Authserver mit 403 antworten
    Gegeben sei Setze im TigerProxy für die Nachricht "isResponse" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 1 Ausführungen

    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # TA_A_27401_01: OPA Response Schema-Validierung
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "PDP_DECISION"
    # TA_A_27401_01 - PDP Policy Engine - Decision Eigenschaften - schemakonform
    Und validiere "${PDP_DECISION}" soft gegen Schema "schemas/v_1_0/pdp-decision.yaml"

    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.result.allow"
    # Manipulation verifizieren: allow sollte false sein
    # TA_A_25661_03 - PDP Authorization Server - Umsetzung der Policy Decision - Zugriffsverweigerung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.result.allow" überein mit "false"

    # TA_A_25661_03: Token Request muss mit 403 Forbidden fehlschlagen
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    # TA_A_25661_03 - PDP Authorization Server - Umsetzung der Policy Decision - Zugriffsverweigerung
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"

    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "body"
    # TA_A_26662_01 - ZETA Guard, HTTP Fehlerdetails
    Und validiere "${body}" gegen Schema "schemas/v_1_0/zeta-error.yaml"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.access_token"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.refresh_token"

  @A_27496
  @TA_A_27496_01
  @TA_A_27496_02
  @TA_A_27496_03
  Szenario: product_id, product_version und professionOID werden bei jedem Aufruf korrekt verarbeitet und protokolliert
    # Für beide Aufrufe wird allow=false erzwungen, damit jeweils ein vollständiger Policy-Request erfolgt
    Gegeben sei Setze im TigerProxy für die Nachricht "isResponse" die Manipulation auf Feld "$.body.result.allow" und Wert "false" und 2 Ausführungen
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Erster Aufruf
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "PRODUCT_ID_FIRST"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "PRODUCT_VERSION_FIRST"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificate"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificate}" in die Variable "SMCB-INFO"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_assertion.posture.product_id" überein mit "${PRODUCT_ID_FIRST}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_assertion.posture.product_version" überein mit "${PRODUCT_VERSION_FIRST}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "${SMCB-INFO.professionId}"

    # Zweiter Aufruf
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "secondProductId" auf "test-proxy-second"
    Und TGR setze lokale Variable "secondProductVersion" auf "9.9.9"
    Und TGR setze lokale Variable "secondTokenRequestCondition" auf "isRequest && request.path =~ '.*${paths.guard.tokenEndpointPath}'"
    # Hole einen Private Key über storage (wird für Signatur und JWK-Ersetzung verwendet)
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"

    Und TGR speichere Wert des Knotens "$.body.client_private_key" der aktuellen Antwort in der Variable "privateKey"
    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.tokenEndpointPath}"

    Dann Setze im TigerProxy für JWT in "$.body.client_assertion.body.client_statement.posture" das Feld "product_id" auf Wert "${secondProductId}" mit privatem Schlüssel "${privateKey}" für Pfad "${secondTokenRequestCondition}" und 1 Ausführungen und ersetze JWK
    Dann Setze im TigerProxy für JWT in "$.body.client_assertion.body.client_statement.posture" das Feld "product_version" auf Wert "${secondProductVersion}" mit privatem Schlüssel "${privateKey}" für Pfad "${secondTokenRequestCondition}" und 1 Ausführungen und ersetze JWK

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "403"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_id" der aktuellen Anfrage in der Variable "PRODUCT_ID_SECOND"
    Und TGR speichere Wert des Knotens "$.body.client_assertion.body.client_statement.posture.product_version" der aktuellen Anfrage in der Variable "PRODUCT_VERSION_SECOND"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_id" überein mit "${secondProductId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.client_statement.posture.product_version" überein mit "${secondProductVersion}"
    Und TGR speichere Wert des Knotens "$.body.subject_token.header.x5c.0" der aktuellen Anfrage in der Variable "smcbCertificateSecond"
    Und schreibe Daten aus dem SMC-B Zertifikat "${smcbCertificateSecond}" in die Variable "SMCB-INFO-SECOND"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_assertion.posture.product_id" überein mit "${PRODUCT_ID_SECOND}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_assertion.posture.product_version" überein mit "${PRODUCT_VERSION_SECOND}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.user_info.professionOID" überein mit "${SMCB-INFO-SECOND.professionId}"

    # Telemetrie-/Monitoring-Nachweis: beide Läufe eindeutig korreliert und protokolliert
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND body:professionOID AND body:${SMCB-INFO.professionId} AND body:product_id AND body:${PRODUCT_ID_FIRST} AND body:product_version AND body:${PRODUCT_VERSION_FIRST} AND body:${paths.guard.tokenEndpointPath} | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                       | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND body:professionOID AND body:${SMCB-INFO-SECOND.professionId} AND body:product_id AND body:${PRODUCT_ID_SECOND} AND body:product_version AND body:${PRODUCT_VERSION_SECOND} AND body:${paths.guard.tokenEndpointPath} | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"
