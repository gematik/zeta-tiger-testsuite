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

@normal
@require_kubectl
@deployment_modification
@raw_otlp_capture
@UseCase_Telemetrie_03
Funktionalität: PEP-Traceparameter am rohen gematik-Export mit HTTP-Operationen abgleichen

  @A_29020
  @TA_A_29020_01
  @TA_A_29020_02
  @TA_A_29020_05
  @TA_A_29020_06
  @TA_A_29020_07
  @TA_A_29020_08
  @TA_A_29020_09
  @TA_A_29020_10
  @TA_A_29020_11
  @TA_A_29020_12
  @TA_A_29020_13
  @oidc
  @reset_testdriver
  @Ignore
  Szenario: Erfolgreiche und abgewiesene OIDC-Operationen exportieren passende HTTP-Traceparameter ohne Profession-OID
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_TELEMETRY_KVNR"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR speichere Wert des Knotens "${body.tokenResponse.accessToken}" der aktuellen Antwort in der Variable "CC_TELEMETRY_ACCESS_TOKEN"
    Und TGR speichere Wert des Knotens "${body.tokenRequest.clientAssertion.body.clientStatement.posture.productId}" der aktuellen Anfrage in der Variable "CC_TELEMETRY_PRODUCT_ID"
    Und TGR speichere Wert des Knotens "${body.tokenRequest.clientAssertion.body.clientStatement.posture.productVersion}" der aktuellen Anfrage in der Variable "CC_TELEMETRY_PRODUCT_VERSION"
    Und TGR prüfe Variable "CC_TELEMETRY_PRODUCT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "CC_TELEMETRY_PRODUCT_VERSION" stimmt überein mit ".+"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${body.tokenRequest.subjectToken.header.x5c.root}"

    Dann prüfe deaktiviertes Sampling im Auslieferungsstandard "/chart/values/zeta-guard/telemetry-gateway/config" für Pipeline "${telemetry.gematikTracePipeline}"
    Und Sampling ist auch in allen laufenden Collectors derselben Pipeline deaktiviert
    Gegeben sei eine unveränderte OTLP-Rohdatenerfassung am gematik-Testempfänger
    Und TGR setze lokale Variable "CC_TELEMETRY_TRACE" auf "!{randomHex(32)}"
    Und TGR setze lokale Variable "CC_TELEMETRY_GET_PARENT" auf "!{randomHex(16)}"
    Und TGR setze lokale Variable "CC_TELEMETRY_POST_PARENT" auf "!{randomHex(16)}"
    Und TGR setze lokale Variable "CC_TELEMETRY_NOT_FOUND_PARENT" auf "!{randomHex(16)}"
    Und TGR setze lokale Variable "CC_TELEMETRY_REJECTED_TRACE" auf "!{randomHex(32)}"

    Wenn Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "${headers.traceparent.strict}" und Wert "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_GET_PARENT}-01"
    Und merke den Beginn des PEP-Operationsfensters "GET200"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.traceparent.root}" der mit "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_GET_PARENT}-01" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${CC_TELEMETRY_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${zeta_base_url}\E"
    Und merke das Ende des PEP-Operationsfensters "GET200"
    Und merke für den OTLP-Rohexport den Tracekontext "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_GET_PARENT}-01" mit Methode GET, Ziel "${paths.guard.helloZetaPath}" und Status 200
    Und ordne dem letzten OTLP-Operationsnachweis das Zeitfenster "GET200" zu
    Und erwarte für den letzten OTLP-Operationsnachweis die Route "${testdata.ccTelemetry.noPoppRoute}" und den Zielserver "${zeta_base_url}"
    Und erwarte für den letzten OTLP-Operationsnachweis die Produkt-ID "${CC_TELEMETRY_PRODUCT_ID}" und Produktversion "${CC_TELEMETRY_PRODUCT_VERSION}"
    Und erwarte für den letzten OTLP-Operationsnachweis keine Profession-OID

    Wenn hole frisches PoPP Token aus dem Generator für Akteur "${CC_TELEMETRY_KVNR}" und Profession "1.2.276.0.76.4.49" und speichere in der Variable "CC_TELEMETRY_POPP"
    Und TGR setze lokale Variable "CC_TELEMETRY_PUSHKEY" auf "!{randomHex(32)}"
    Und TGR setze den default header "popp" auf den Wert "${CC_TELEMETRY_POPP}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'POST' && message.path =~ '^${testdata.ccTelemetry.noOpPostPepPath}$'" die Manipulation auf Feld "${headers.traceparent.strict}" und Wert "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_POST_PARENT}-01"
    Und merke den Beginn des PEP-Operationsfensters "POST200"
    Und TGR sende eine POST Anfrage an "${paths.client.baseUrl}${testdata.ccTelemetry.noOpPostPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      ${testdata.ccTelemetry.noOpPusherJson}
      """
    Und TGR lösche default header "popp"
    Dann TGR finde die letzte Anfrage mit Pfad "${testdata.ccTelemetry.noOpPostPepPath}" und Knoten "${headers.traceparent.root}" der mit "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_POST_PARENT}-01" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${CC_TELEMETRY_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${zeta_base_url}\E"
    Und merke das Ende des PEP-Operationsfensters "POST200"
    Und merke für den OTLP-Rohexport den Tracekontext "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_POST_PARENT}-01" mit Methode POST, Ziel "${testdata.ccTelemetry.noOpPostPepPath}" und Status 200
    Und ordne dem letzten OTLP-Operationsnachweis das Zeitfenster "POST200" zu
    Und erwarte für den letzten OTLP-Operationsnachweis die Route "${testdata.ccTelemetry.postRoute}" und den Zielserver "${zeta_base_url}"
    Und erwarte für den letzten OTLP-Operationsnachweis die Produkt-ID "${CC_TELEMETRY_PRODUCT_ID}" und Produktversion "${CC_TELEMETRY_PRODUCT_VERSION}"
    Und erwarte für den letzten OTLP-Operationsnachweis keine Profession-OID

    Wenn Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}/telemetry-not-found$'" die Manipulation auf Feld "${headers.traceparent.strict}" und Wert "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_NOT_FOUND_PARENT}-01"
    Und merke den Beginn des PEP-Operationsfensters "GET404"
    Und TGR sende eine leere GET Anfrage an "${paths.client.baseUrl}${testdata.ccTelemetry.notFoundPath}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}/telemetry-not-found" und Knoten "${headers.traceparent.root}" der mit "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_NOT_FOUND_PARENT}-01" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${CC_TELEMETRY_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "404"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${zeta_base_url}\E"
    Und merke das Ende des PEP-Operationsfensters "GET404"
    Und merke für den OTLP-Rohexport den Tracekontext "00-${CC_TELEMETRY_TRACE}-${CC_TELEMETRY_NOT_FOUND_PARENT}-01" mit Methode GET, Ziel "${paths.guard.helloZetaPath}/telemetry-not-found" und Status 404
    Und ordne dem letzten OTLP-Operationsnachweis das Zeitfenster "GET404" zu
    Und erwarte für den letzten OTLP-Operationsnachweis die Route "${testdata.ccTelemetry.noPoppRoute}" und den Zielserver "${zeta_base_url}"
    Und erwarte für den letzten OTLP-Operationsnachweis die Produkt-ID "${CC_TELEMETRY_PRODUCT_ID}" und Produktversion "${CC_TELEMETRY_PRODUCT_VERSION}"
    Und erwarte für den letzten OTLP-Operationsnachweis keine Profession-OID

    Wenn merke den Beginn des PEP-Operationsfensters "GET401"
    Und TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}${paths.tigerProxy.modificationPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "name":"cc-telemetry-rejected-context-${CC_TELEMETRY_REJECTED_TRACE}",
        "condition":"isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'",
        "targetElement":"${headers.traceparent.strict}",
        "replaceWith":"00-${CC_TELEMETRY_REJECTED_TRACE}-\u0021{randomHex(16)}-01"
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.tigerProxy.modificationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Wenn TGR sende eine PUT Anfrage an "${paths.tigerProxy.baseUrl}${paths.tigerProxy.modificationPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      {
        "name":"cc-telemetry-rejected-authorization-${CC_TELEMETRY_REJECTED_TRACE}",
        "condition":"isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'",
        "targetElement":"${headers.root}",
        "regexFilter":"${headers.authorization.lineRegex}",
        "replaceWith":""
      }
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.tigerProxy.modificationPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${body.responseCode}"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.traceparent.root}" der mit "00-${CC_TELEMETRY_REJECTED_TRACE}-[0-9a-f]{16}-01" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "GET"
    Und TGR prüfe aktueller Request enthält nicht Knoten "${headers.authorization.root}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "401"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${zeta_base_url}\E"
    Und merke für den OTLP-Rohexport alle PEP-Versuche der Trace-ID "${CC_TELEMETRY_REJECTED_TRACE}" mit Methode GET, Ziel "${paths.guard.helloZetaPath}" und Status 401 im Zeitfenster "GET401"
    Und merke das Ende des PEP-Operationsfensters "GET401"
    Und ordne dem letzten OTLP-Operationsnachweis das Zeitfenster "GET401" zu
    Und erwarte für den letzten OTLP-Operationsnachweis die Route "${testdata.ccTelemetry.noPoppRoute}" und den Zielserver "${zeta_base_url}"
    Und erwarte für den letzten OTLP-Operationsnachweis keine Profession-OID

    Dann entsprechen alle markierten PEP-Operationen im OTLP-Rohexport ihren beobachteten Zeitfenstern und Traceparametern

  @A_29020
  @TA_A_29020_01
  @TA_A_29020_02
  @TA_A_29020_03
  @TA_A_29020_05
  @TA_A_29020_06
  @TA_A_29020_07
  @TA_A_29020_08
  @TA_A_29020_09
  @TA_A_29020_10
  @TA_A_29020_11
  @TA_A_29020_12
  @smb
  Szenario: Der PEP exportiert Produktdaten und Profession aus der verwendeten SMC-B-Session
    Gegeben sei lade SMC-B Keystore des ZETA Deployments in Variablen mit Präfix "CC_TELEMETRY_SMCB_CERT"
    Und schreibe Daten aus dem SMC-B Zertifikat "${CC_TELEMETRY_SMCB_CERT.crt_b64}" in die Variable "CC-TELEMETRY-SMCB-EXPECTED"
    Und TGR prüfe Variable "CC-TELEMETRY-SMCB-EXPECTED.professionId" stimmt überein mit "[0-9]+(\.[0-9]+)+"
    Und hole frisches PoPP Token aus dem Generator für Akteur "${CC-TELEMETRY-SMCB-EXPECTED.telematikId}" und Profession "${CC-TELEMETRY-SMCB-EXPECTED.professionId}" und speichere in der Variable "CC_TELEMETRY_SMCB_POPP"
    Und TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten

    Dann prüfe deaktiviertes Sampling im Auslieferungsstandard "/chart/values/zeta-guard/telemetry-gateway/config" für Pipeline "${telemetry.gematikTracePipeline}"
    Und Sampling ist auch in allen laufenden Collectors derselben Pipeline deaktiviert
    Gegeben sei eine unveränderte OTLP-Rohdatenerfassung am gematik-Testempfänger
    Und TGR setze lokale Variable "CC_TELEMETRY_SMCB_TRACE" auf "!{randomHex(32)}"
    Und TGR setze lokale Variable "CC_TELEMETRY_SMCB_RESOURCE_PARENT" auf "!{randomHex(16)}"
    Und Setze im TigerProxy für die Nachricht "isRequest && message.method == 'GET' && message.path =~ '^${paths.guard.helloZetaPath}$'" die Manipulation auf Feld "${headers.traceparent.strict}" und Wert "00-${CC_TELEMETRY_SMCB_TRACE}-${CC_TELEMETRY_SMCB_RESOURCE_PARENT}-01"
    Und merke den Beginn des PEP-Operationsfensters "SMCB_GET200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" mit folgenden Headern:
      | PoPP | ${CC_TELEMETRY_SMCB_POPP} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.message}" überein mit "Hello ZETA!"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${body.oidc.tokenRequest.grantType}" der mit "!{urlEncoded('${oauth_parameters.grant_type.tokenExchange}')}" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.tokenRequest.subjectToken.header.x5c.first}" überein mit "\Q${CC_TELEMETRY_SMCB_CERT.crt_b64}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.tokenRequest.subjectToken.body.sub}" überein mit "\Q${CC-TELEMETRY-SMCB-EXPECTED.telematikId}\E"
    Und TGR speichere Wert des Knotens "${body.tokenResponse.accessToken}" der aktuellen Antwort in der Variable "CC_TELEMETRY_SMCB_ACCESS_TOKEN"
    Und TGR speichere Wert des Knotens "${body.tokenRequest.clientAssertion.body.clientStatement.posture.productId}" der aktuellen Anfrage in der Variable "CC_TELEMETRY_SMCB_PRODUCT_ID"
    Und TGR speichere Wert des Knotens "${body.tokenRequest.clientAssertion.body.clientStatement.posture.productVersion}" der aktuellen Anfrage in der Variable "CC_TELEMETRY_SMCB_PRODUCT_VERSION"
    Und TGR prüfe Variable "CC_TELEMETRY_SMCB_PRODUCT_ID" stimmt überein mit ".+"
    Und TGR prüfe Variable "CC_TELEMETRY_SMCB_PRODUCT_VERSION" stimmt überein mit ".+"
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.traceparent.root}" der mit "00-${CC_TELEMETRY_SMCB_TRACE}-${CC_TELEMETRY_SMCB_RESOURCE_PARENT}-01" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${body.method}" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${zeta_base_url}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "(?i)^DPoP \Q${CC_TELEMETRY_SMCB_ACCESS_TOKEN}\E$"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.root}" überein mit "\Q${CC_TELEMETRY_SMCB_POPP}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorId}" überein mit "\Q${CC-TELEMETRY-SMCB-EXPECTED.telematikId}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.popp.body.actorProfessionOid}" überein mit "\Q${CC-TELEMETRY-SMCB-EXPECTED.professionId}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.responseCode}" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${body.message}" überein mit "Hello ZETA!"
    Und merke das Ende des PEP-Operationsfensters "SMCB_GET200"
    Und merke für den OTLP-Rohexport den Tracekontext "00-${CC_TELEMETRY_SMCB_TRACE}-${CC_TELEMETRY_SMCB_RESOURCE_PARENT}-01" mit Methode GET, Ziel "${paths.guard.helloZetaPath}" und Status 200
    Und ordne dem letzten OTLP-Operationsnachweis das Zeitfenster "SMCB_GET200" zu
    Und erwarte für den letzten OTLP-Operationsnachweis die Route "${testdata.ccTelemetry.postRoute}" und den Zielserver "${zeta_base_url}"
    Und erwarte für den letzten OTLP-Operationsnachweis die Produkt-ID "${CC_TELEMETRY_SMCB_PRODUCT_ID}" und Produktversion "${CC_TELEMETRY_SMCB_PRODUCT_VERSION}"
    Und erwarte für den letzten OTLP-Operationsnachweis die Profession-OID "${CC-TELEMETRY-SMCB-EXPECTED.professionId}"
    Dann entsprechen alle markierten PEP-Operationen im OTLP-Rohexport ihren beobachteten Zeitfenstern und Traceparametern
