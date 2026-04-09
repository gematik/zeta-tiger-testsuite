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

@UseCase_01_01
Funktionalität: Client_initiale_registrierung_stationaer_SC_201

  @A_26640
  @A_26641
  @A_27266
  @A_27798
  @A_28422
  @A_28464
  @TA_A_27266_01
  @TA_A_27266_02
  @TA_A_27798_01
  @TA_A_27798_03
  @TA_A_28422_01
  @TA_A_28422_03
  @TA_A_28464_01
  @TA_A_26641_01
  Szenario: well-known zu oauth-protected-resource
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "OPR_WELL_KNOWN"
    Und validiere "${OPR_WELL_KNOWN}" gegen Schema "schemas/v_1_0/opr-well-known.yaml"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.cacheControl}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.authorization_servers.0"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.authorization_servers.1"

  @no_proxy
  @component
  @TA_A_26668-01_06
  @TA_A_26640_01
  Szenario: well-known zu oauth-protected-resource (Komponententest)
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthProtectedResourcePath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "OPR_WELL_KNOWN"
    Und validiere "${OPR_WELL_KNOWN}" gegen Schema "schemas/v_1_0/opr-well-known.yaml"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.cacheControl}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.authorization_servers.0"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.authorization_servers.1"

  @A_27798
  @A_28422
  @dev
  @TA_A_27798_02
  @TA_A_27798_04
  @TA_A_28422_01
  @TA_A_28422_03
  @TA_A_28422_06
  Szenario: well-known zu oauth-authorization-server
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "AS_WELL_KNOWN"
    Und validiere "${AS_WELL_KNOWN}" gegen Schema "schemas/v_1_0/as-well-known.yaml"
    Und TGR speichere Wert des Knotens "$.body.jwks_uri" der aktuellen Antwort in der Variable "jwksUri"
    Und TGR assert variable "jwksUri" matches "^https?://[^/]+/.*$"
    Und TGR setze lokale Variable "jwksPath" auf "${jwksUri}"
    Und TGR ersetze "^https?://[^/]+" mit "" im Inhalt der Variable "jwksPath"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${jwksPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${jwksPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.cacheControl}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"

  @no_proxy
  @component
  Szenario: well-known zu oauth-authorization-server (Komponententest)
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthServerPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "AS_WELL_KNOWN"
    Und validiere "${AS_WELL_KNOWN}" gegen Schema "schemas/v_1_0/as-well-known.yaml"
    Und TGR speichere Wert des Knotens "$.body.jwks_uri" der aktuellen Antwort in der Variable "jwksUri"
    Und TGR assert variable "jwksUri" matches "^https?://[^/]+/.*$"
    Und TGR setze lokale Variable "jwksPath" auf "${jwksUri}"
    Und TGR ersetze "^https?://[^/]+" mit "" im Inhalt der Variable "jwksPath"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${jwksPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${jwksPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.cacheControl}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"


  @A_27266
  @A_28420-01
  @A_28421-01
  @A_28425-01
  @A_27798
  @TA_A_28420-01_01
  @TA_A_28420-01_02
  @TA_A_28421-01_01
  @TA_A_28421-01_02
  @TA_A_28421-01_03
  @TA_A_28421-01_04
  @TA_A_28421-01_05
  Szenariogrundriss: etag wird bei der Abfrage der well-known Dokumente verwendet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Erste Resourceabfrage: finde aktuellen etag heraus
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Dann TGR finde die nächste Anfrage mit dem Pfad ".*<expected_path>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "WELL_KNOWN"
    Und validiere "${WELL_KNOWN}" gegen Schema "<schema_path>"
    # TA_A_28420-01_01, TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.eTag}"
    Und TGR speichere Wert des Knotens "${headers.eTag}" der aktuellen Antwort in der Variable "ETAG"

    # Manipuliere die nächste Response: responseCode = 404 => Folgende Resourceanfrage muss Service Discovery enthalten (A_28426)
    Und TGR setze lokale Variable "notFoundCondition" auf "isResponse && request.path =^ '${paths.fachdienst.helloZetaPath}'"
    Und Setze im TigerProxy für die Nachricht "${notFoundCondition}" die Manipulation auf Feld "$.responseCode" und Wert "404"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
     # Zweite Resourceabfrage: Fehler bei Resourceanfrage provoziert erneute Service Discovery Anfrage
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "404"
    Und Alle Manipulationen im TigerProxy werden gestoppt

    # Dritte Resourceabfrage: "if-none-match" Header mit etag => Response = 304 und leerer body
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Dann TGR finde die nächste Anfrage mit dem Pfad ".*<expected_path>"
    # TA_A_28421-01_01
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "304"
    # TA_A_28425-01_01 und TA_A_28425-01_02
    Und TGR prüfe aktueller Request enthält Knoten "${headers.ifNoneMatch}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch}" überein mit "${ETAG}"
    # TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag}" überein mit "${ETAG}"
    # TA_A_28421-01_02
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "RESPONSEBODY"
    Und TGR prüfe Variable "RESPONSEBODY" stimmt überein mit ""

    # Manipuliere "if-none-match" Header: ungültiger etag
    Gegeben sei TGR setze lokale Variable "ifNoneMatchCondition" auf "isRequest && request.path =~ '.*<expected_path>.*'"
    Dann TGR setze lokale Variable "INVALID_ETAG" auf "invalid-etag"
    Dann Setze im TigerProxy für die Nachricht "${ifNoneMatchCondition}" die Manipulation auf Feld "${headers.ifNoneMatch}" und Wert "${INVALID_ETAG}"

    # Reset client => Service Discovery muss bei der nächsten Resourceabfrage ausgeführt werden
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Vierte Resourceabfrage: "if-none-match" Header mit ungültigem etag => Response = 200 und vollständiges well-known Dokument
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Dann TGR finde die nächste Anfrage mit dem Pfad ".*<expected_path>"
    # Überprüfe die Manipulation
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch}" überein mit "${INVALID_ETAG}"
    # TA_A_28421-01_03
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_28421-01_05
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag}" überein mit "${ETAG}"
    # TA_A_28421-01_04
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "${WELL_KNOWN}"

    @TA_A_28425-01_01
    @TA_A_27266_02
    @TA_A_27798_03
    Beispiele: "/.well-known/oauth-protected-resource"
      | expected_path                                      | schema_path                       |
      | ${paths.guard.wellKnownOAuthProtectedResourcePath} | schemas/v_1_0/opr-well-known.yaml |

    @TA_A_28425-01_02
    @TA_A_27798_04
    Beispiele: "/.well-known/oauth-authorization-server"
      | expected_path                           | schema_path                      |
      | ${paths.guard.wellKnownOAuthServerPath} | schemas/v_1_0/as-well-known.yaml |


  @component
  @A_27266
  @A_27798
  @A_28420-01
  @A_28421-01
  @A_28425-01
  @TA_A_28420-01_01
  @TA_A_28420-01_02
  @TA_A_28421-01_01
  @TA_A_28421-01_02
  @TA_A_28421-01_03
  @TA_A_28421-01_04
  @TA_A_28421-01_05
  Szenariogrundriss: etag wird bei der Abfrage der well-known Dokumente vom ZETA Guard bereitgestellt (Komponententest)
    # Erste Anfrage: Finde aktuellen etag heraus
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}<expected_path>"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*<expected_path>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "WELL_KNOWN"
    Und validiere "${WELL_KNOWN}" gegen Schema "<schema_path>"
    # TA_A_28420-01_01, TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.eTag}"
    Und TGR speichere Wert des Knotens "${headers.eTag}" der aktuellen Antwort in der Variable "ETAG"

    # Zweite Anfrage: Erzwinge "if-none-match" Header für die well-known Abfrage. Server antwortet "304 Not Modified"
    Gegeben sei TGR setze lokale Variable "ifNoneMatchCondition" auf "isRequest && request.path =~ '.*<expected_path>.*'"
    Dann Setze im TigerProxy für die Nachricht "${ifNoneMatchCondition}" die Manipulation auf Feld "${headers.ifNoneMatch}" und Wert "${ETAG}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}<expected_path>"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*<expected_path>"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.ifNoneMatch}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch}" überein mit "${ETAG}"
    # TA_A_28421-01_01
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "304"
    # TA_A_28421-01_02
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "RESPONSEBODY"
    Und TGR prüfe Variable "RESPONSEBODY" stimmt überein mit ""
    # TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag}" überein mit "${ETAG}"

    # Dritte Anfrage: Sende "if-none-match" Header mit falschem etag. Server antwortet 200 und sendet das aktuelle well-known JSON Dokument.
    Gegeben sei TGR setze lokale Variable "ifNoneMatchCondition" auf "isRequest && request.path =~ '.*<expected_path>.*'"
    Und TGR setze lokale Variable "INVALID_ETAG" auf "invalid-etag"
    Dann Setze im TigerProxy für die Nachricht "${ifNoneMatchCondition}" die Manipulation auf Feld "${headers.ifNoneMatch}" und Wert "${INVALID_ETAG}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}<expected_path>"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*<expected_path>"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch}" überein mit "${INVALID_ETAG}"
    # TA_A_28421-01_03
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_28421-01_04
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "${WELL_KNOWN}"
    # TA_A_28421-01_05 und TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag}" überein mit "${ETAG}"

    @TA_A_27266_02
    @TA_A_27798_03
    Beispiele: "/.well-known/oauth-protected-resource"
      | expected_path                                      | schema_path                       |
      | ${paths.guard.wellKnownOAuthProtectedResourcePath} | schemas/v_1_0/opr-well-known.yaml |

    @TA_A_27798_04
    Beispiele: "/.well-known/oauth-authorization-server"
      | expected_path                           | schema_path                      |
      | ${paths.guard.wellKnownOAuthServerPath} | schemas/v_1_0/as-well-known.yaml |


  @A_26668-01
  @TA_A_26668-01_06
  @TA_A_26668-01_07
  @TA_A_26668-01_08
  @TA_A_26668-01_10
  @TA_A_26668-01_11
  @TA_A_26668-01_12
  @TA_A_26668-01_14
  @TA_A_26668-01_15
  @TA_A_26668-01_16
  @TA_A_26668-01_30
  @TA_A_26668-01_31
  @TA_A_26668-01_32
  @TA_A_26668-01_34
  @TA_A_26668-01_35
  @TA_A_26668-01_36
  @TA_A_26668-01_42
  @TA_A_26668-01_43
  @TA_A_26668-01_44
  Szenario: Rate-Limit Header an relevanten Endpunkten vorhanden
    # Voraussetzung: Rate-Limit ist auf jedem geprüften Endpunkt konfiguriert.
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.limit}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.remaining}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.reset}"

    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.limit}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.remaining}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.reset}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.limit}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.remaining}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.reset}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.nonceEndpointPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.limit}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.remaining}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.reset}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.limit}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.remaining}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.reset}"

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.limit}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.remaining}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.reset}"

  @dev
  @A_26661
  @A_28465
  @TA_A_26661_10
  @TA_A_28465_02
  Szenario: Client erfolgreich registrieren (Integrationstest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    # TA_A_26661_10 - ZETA Guard - HTTP Statuscodes - Clientregistrierung - 201 Created
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id_issued_at"

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.token_endpoint_auth_method" überein mit "private_key_jwt"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.grant_types"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.jwks"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.redirect_uris"

    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.registration_client_uri"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.registration_access_token"

    # --- Request validation ---
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.contentType}" überein mit "application/json"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_name" überein mit "sdk-client"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.token_endpoint_auth_method"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_types"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.jwks"

  @A_25738
  @TA_A_25738_08
  Szenario: Telemetrie protokolliert Status/Ergebnis der Client-Registrierung (Platzhalter)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"

    Und warte "2" Sekunden
    # Authentication Server loggt das Ergebnis noch nicht, daher schlägt der Test fehl.
    # TODO: Query anpassen, wenn Log-Eintrag vom Authentication Server generiert wird.
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                  | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:${telemetry.containerName.authorizationServer} AND body:clientregistrationresult | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für obiges Query gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

  @no_proxy
  @component
  @dev
  @A_26661
  @TA_A_26661_10
  Szenario: Client erfolgreich registrieren (Komponententest)
    Wenn TGR sende eine POST Anfrage an "${paths.guard.baseUrl}${paths.guard.registerEndpointPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/register-request.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    # TA_A_26661_10
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id_issued_at"

    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.token_endpoint_auth_method" überein mit "private_key_jwt"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.grant_types"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.jwks"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.redirect_uris"

    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.registration_client_uri"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.registration_access_token"

    # --- Request validation ---
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "POST"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.contentType}" überein mit "application/json"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_name" überein mit "sdk-client"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.token_endpoint_auth_method"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_types"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.jwks"
