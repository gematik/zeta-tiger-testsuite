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
Funktionalität: Client initiale Registrierung stationär SC 201

  @A_26640
  @A_26641
  @A_27266
  @A_27798
  @A_28464
  @TA_A_26640_01
  @TA_A_26641_01
  @TA_A_27266_01
  @TA_A_27266_02
  @TA_A_27798_01
  @TA_A_27798_03
  @TA_A_28464_01
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
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.authorization_servers.0"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.authorization_servers.1"

  @A_26640
  @TA_A_26640_01
  @component
  @no_proxy
  Szenario: well-known zu oauth-protected-resource (Komponententest)
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthProtectedResourcePath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "OPR_WELL_KNOWN"
    Und validiere "${OPR_WELL_KNOWN}" gegen Schema "schemas/v_1_0/opr-well-known.yaml"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.authorization_servers.0"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.authorization_servers.1"

  @A_27798
  @TA_A_27798_02
  @TA_A_27798_04
  Szenario: well-known zu oauth-authorization-server
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "AS_WELL_KNOWN"
    Und validiere "${AS_WELL_KNOWN}" gegen Schema "schemas/v_1_0/as-well-known.yaml"
    Und TGR speichere Wert des Knotens "$.body.jwks_uri" der aktuellen Antwort in der Variable "jwksUri"
    Und TGR assert variable "jwksUri" matches "^https?://[^/]+/.*$"
    Und TGR setze lokale Feature Variable "jwksPath" auf "${jwksUri}"
    Und TGR ersetze "^https?://[^/]+" mit "" im Inhalt der Variable "jwksPath"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${jwksPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${jwksPath}"

  @A_27798
  @TA_A_27798_02
  @TA_A_27798_04
  @component
  @no_proxy
  Szenario: well-known zu oauth-authorization-server (Komponententest)
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthServerPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.host}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "${zeta_base_url}(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "AS_WELL_KNOWN"
    Und validiere "${AS_WELL_KNOWN}" gegen Schema "schemas/v_1_0/as-well-known.yaml"
    Und TGR speichere Wert des Knotens "$.body.jwks_uri" der aktuellen Antwort in der Variable "jwksUri"
    Und TGR assert variable "jwksUri" matches "^https?://[^/]+/.*$"
    Und TGR setze lokale Feature Variable "jwksPath" auf "${jwksUri}"
    Und TGR ersetze "^https?://[^/]+" mit "" im Inhalt der Variable "jwksPath"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${jwksPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${jwksPath}"

  @A_28422
  @TA_A_28422_01
  @TA_A_28422_03
  @component
  @no_proxy
  Szenariogrundriss: Cache-Control Direktiven der Well-Known JSON-Dokumente werden gesetzt
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}<well_known_path>"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*<well_known_path>$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.cacheControl}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"

    Beispiele: "Well-Known JSON-Dokumente"
      | well_known_path                                    |
      | ${paths.guard.wellKnownOAuthProtectedResourcePath} |
      | ${paths.guard.wellKnownOAuthServerPath}            |

  @A_28422
  @TA_A_28422_04
  @TA_A_28422_06
  @component
  @no_proxy
  Szenario: Cache-Control Direktiven der JWKS JSON-Dokumente werden gesetzt
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthServerPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.jwks_uri" der aktuellen Antwort in der Variable "jwksUri"
    Und TGR assert variable "jwksUri" matches "^https?://[^/]+/.*$"
    Und TGR setze lokale Feature Variable "jwksPath" auf "${jwksUri}"
    Und TGR ersetze "^https?://[^/]+" mit "" im Inhalt der Variable "jwksPath"
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${jwksPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${jwksPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.cacheControl}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*max-age *=[ ]*[0-9]+.*"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.cacheControl}" überein mit "(?i).*public.*"

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
  Szenariogrundriss: etag wird bei der Abfrage des well-known <well_known_name> verwendet
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
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.eTag.lenient}"
    Und TGR speichere Wert des Knotens "${headers.eTag.lenient}" der aktuellen Antwort in der Variable "ETAG"

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
    Und TGR prüfe aktueller Request enthält Knoten "${headers.ifNoneMatch.lenient}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch.lenient}" überein mit "${ETAG}"
    # TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag.lenient}" überein mit "${ETAG}"
    # TA_A_28421-01_02
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "RESPONSEBODY"
    Und TGR prüfe Variable "RESPONSEBODY" stimmt überein mit ""

    # Manipuliere "if-none-match" Header: ungültiger etag
    Gegeben sei TGR setze lokale Variable "ifNoneMatchCondition" auf "isRequest && request.path =~ '.*<expected_path>.*'"
    Dann TGR setze lokale Variable "INVALID_ETAG" auf "${testdata.invalidEtag}"
    Dann Setze im TigerProxy für die Nachricht "${ifNoneMatchCondition}" die Manipulation auf Feld "${headers.ifNoneMatch.strict}" und Wert "${INVALID_ETAG}"

    # Reset client => Service Discovery muss bei der nächsten Resourceabfrage ausgeführt werden
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # Vierte Resourceabfrage: "if-none-match" Header mit ungültigem etag => Response = 200 und vollständiges well-known Dokument
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Dann TGR finde die nächste Anfrage mit dem Pfad ".*<expected_path>"
    # Überprüfe die Manipulation
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch.strict}" überein mit "${INVALID_ETAG}"
    # TA_A_28421-01_03
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_28421-01_04
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "${WELL_KNOWN}"
    # TA_A_28421-01_05
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag.lenient}" überein mit "${ETAG}"

    @TA_A_27266_02
    @TA_A_27798_03
    @TA_A_28425-01_01
    Beispiele: "/.well-known/oauth-protected-resource"
      | well_known_name          | expected_path                                      | schema_path                       |
      | oauth-protected-resource | ${paths.guard.wellKnownOAuthProtectedResourcePath} | schemas/v_1_0/opr-well-known.yaml |

    @TA_A_27798_04
    @TA_A_28425-01_02
    Beispiele: "/.well-known/oauth-authorization-server"
      | well_known_name            | expected_path                           | schema_path                      |
      | oauth-authorization-server | ${paths.guard.wellKnownOAuthServerPath} | schemas/v_1_0/as-well-known.yaml |


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
  @component
  Szenariogrundriss: etag wird bei der Abfrage des well-known <well_known_name> vom ZETA Guard bereitgestellt (Komponententest)
    # Erste Anfrage: Finde aktuellen etag heraus
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}<expected_path>"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*<expected_path>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "WELL_KNOWN"
    Und validiere "${WELL_KNOWN}" gegen Schema "<schema_path>"
    # TA_A_28420-01_01, TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.eTag.lenient}"
    Und TGR speichere Wert des Knotens "${headers.eTag.lenient}" der aktuellen Antwort in der Variable "ETAG"

    # Zweite Anfrage: Erzwinge "if-none-match" Header für die well-known Abfrage. Server antwortet "304 Not Modified"
    Gegeben sei TGR setze lokale Variable "ifNoneMatchCondition" auf "isRequest && request.path =~ '.*<expected_path>.*'"
    Dann Setze im TigerProxy für die Nachricht "${ifNoneMatchCondition}" die Manipulation auf Feld "${headers.ifNoneMatch.strict}" und Wert "${ETAG}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}<expected_path>"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*<expected_path>"
    Und TGR prüfe aktueller Request enthält Knoten "${headers.ifNoneMatch.lenient}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch.lenient}" überein mit "${ETAG}"
    # TA_A_28421-01_01
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "304"
    # TA_A_28421-01_02
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "RESPONSEBODY"
    Und TGR prüfe Variable "RESPONSEBODY" stimmt überein mit ""
    # TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag.lenient}" überein mit "${ETAG}"

    # Dritte Anfrage: Sende "if-none-match" Header mit falschem etag. Server antwortet 200 und sendet das aktuelle well-known JSON Dokument.
    Gegeben sei TGR setze lokale Variable "ifNoneMatchCondition" auf "isRequest && request.path =~ '.*<expected_path>.*'"
    Und TGR setze lokale Variable "INVALID_ETAG" auf "${testdata.invalidEtag}"
    Dann Setze im TigerProxy für die Nachricht "${ifNoneMatchCondition}" die Manipulation auf Feld "${headers.ifNoneMatch.strict}" und Wert "${INVALID_ETAG}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}<expected_path>"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*<expected_path>"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.ifNoneMatch.lenient}" überein mit "${INVALID_ETAG}"
    # TA_A_28421-01_03
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # TA_A_28421-01_04
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body" überein mit "${WELL_KNOWN}"
    # TA_A_28421-01_05 und TA_A_28420-01_02
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "${headers.eTag.lenient}" überein mit "${ETAG}"

    @TA_A_27266_02
    @TA_A_27798_03
    Beispiele: "/.well-known/oauth-protected-resource"
      | well_known_name          | expected_path                                      | schema_path                       |
      | oauth-protected-resource | ${paths.guard.wellKnownOAuthProtectedResourcePath} | schemas/v_1_0/opr-well-known.yaml |

    @TA_A_27798_04
    Beispiele: "/.well-known/oauth-authorization-server"
      | well_known_name            | expected_path                           | schema_path                      |
      | oauth-authorization-server | ${paths.guard.wellKnownOAuthServerPath} | schemas/v_1_0/as-well-known.yaml |


  @A_26668-02
  @require_kubectl
  Szenariogrundriss: Rate-Limit Header an relevantem Endpunkt <endpoint_name> vorhanden
    Gegeben sei im Kubernetes-Deployment eine Rate-Limit-Konfiguration "<rate_limit_config>" für den Endpunkt "<endpoint_path>"
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "<request_path>"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.limit}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.remaining}"
    Und TGR prüfe aktuelle Antwort enthält Knoten "${headers.rateLimit.reset}"

    @TA_A_26668-02_05
    @TA_A_26668-02_06
    @TA_A_26668-02_07
    @TA_A_26668-02_08
    Beispiele: "/.well-known/oauth-protected-resource"
      | endpoint_name            | endpoint_path                                      | request_path                                          | rate_limit_config |
      | oauth-protected-resource | ${paths.guard.wellKnownOAuthProtectedResourcePath} | .*${paths.guard.wellKnownOAuthProtectedResourcePath}$ | rate-limit        |

    @TA_A_26668-02_09
    @TA_A_26668-02_10
    @TA_A_26668-02_11
    @TA_A_26668-02_12
    Beispiele: geschützte Ressource
      | endpoint_name        | endpoint_path                | request_path                 | rate_limit_config |
      | geschützte Ressource | ${paths.guard.helloZetaPath} | ${paths.guard.helloZetaPath} | rate-limit        |

    @TA_A_26668-02_13
    @TA_A_26668-02_14
    @TA_A_26668-02_15
    @TA_A_26668-02_16
    Beispiele: "/.well-known/oauth-authorization-server"
      | endpoint_name              | endpoint_path                           | request_path                               | rate_limit_config |
      | oauth-authorization-server | ${paths.guard.wellKnownOAuthServerPath} | .*${paths.guard.wellKnownOAuthServerPath}$ | rate-limit        |

    @TA_A_26668-02_29
    @TA_A_26668-02_30
    @TA_A_26668-02_31
    @TA_A_26668-02_32
    Beispiele: "/nonce"
      | endpoint_name | endpoint_path                    | request_path                     | rate_limit_config |
      | nonce         | ${paths.guard.nonceEndpointPath} | ${paths.guard.nonceEndpointPath} | rate-limit        |

    @TA_A_26668-02_33
    @TA_A_26668-02_34
    @TA_A_26668-02_35
    @TA_A_26668-02_36
    Beispiele: "/register"
      | endpoint_name | endpoint_path                       | request_path                        | rate_limit_config |
      | register      | ${paths.guard.registerEndpointPath} | ${paths.guard.registerEndpointPath} | rate-limit        |

    @TA_A_26668-02_41
    @TA_A_26668-02_42
    @TA_A_26668-02_43
    @TA_A_26668-02_44
    Beispiele: "/token"
      | endpoint_name | endpoint_path                    | request_path                     | rate_limit_config |
      | token         | ${paths.guard.tokenEndpointPath} | ${paths.guard.tokenEndpointPath} | rate-limit        |

  @A_28426
  @TA_A_28426_01
  @longrunning
  Szenario: Well-known JSON-Dokumente ohne Cache-Control-Header werden alle 24h neu geladen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR setze lokale Variable "wellKnownRefreshWait" auf "86400"
    Und TGR setze lokale Variable "oprWellKnownResponseCondition" auf "isResponse && request.path =~ '.*${paths.guard.wellKnownOAuthProtectedResourcePath}$'"
    Und TGR setze lokale Variable "asWellKnownResponseCondition" auf "isResponse && request.path =~ '.*${paths.guard.wellKnownOAuthServerPath}$'"
    Und Setze im TigerProxy für die Nachricht "${oprWellKnownResponseCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "(?mi)^cache-control:\\s*[^\\n]*\\n?" und Wert ""
    Und Setze im TigerProxy für die Nachricht "${asWellKnownResponseCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "(?mi)^cache-control:\\s*[^\\n]*\\n?" und Wert ""

    # Initiale Service Discovery
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.cacheControl}"
    Und TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.cacheControl}"

    # Aufzeichnungen löschen damit nur die erneute Service Discovery geprüft wird
    Und TGR lösche aufgezeichnete Nachrichten
    Und warte "${wellKnownRefreshWait}" Sekunden
    Und Setze im TigerProxy für die Nachricht "${oprWellKnownResponseCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "(?mi)^cache-control:\\s*[^\\n]*\\n?" und Wert ""
    Und Setze im TigerProxy für die Nachricht "${asWellKnownResponseCondition}" die Regex-Manipulation auf Feld "$.header" mit Regex "(?mi)^cache-control:\\s*[^\\n]*\\n?" und Wert ""

    # Service Discovery nach 24h erneut anstoßen
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.cacheControl}"
    Und TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "${headers.cacheControl}"

  @A_26587-01
  @A_26661
  @A_28465
  @TA_A_26587-01_01
  @TA_A_26661_10
  @TA_A_28465_02
  @critical
  @MASVS-AUTH
  Szenario: Client erfolgreich registrieren (Integrationstest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    # TA_A_26661_10 - ZETA Guard - HTTP Statuscodes - Clientregistrierung - 201 Created
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.client_id_issued_at"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "registeredClientId"

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

    # TA_A_26587-01_01: PDP Datenbank - Kompatibilität zum Authorization Server über registrierte Client-ID im Token Request
    Dann TGR finde die nächste Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.client_assertion"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.iss" überein mit "${registeredClientId}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_assertion.body.sub" überein mit "${registeredClientId}"

  @A_25738
  @TA_A_25738_01
  @TA_A_25738_03
  @TA_A_25738_07
  @A_27725-01
  @TA_A_27725-01_20
  @MASVS-RESILIENCE
  Szenario: Telemetrie protokolliert Parameter, Zeitstempel und Ergebnis der Client-Registrierung
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    # Registrierung Teil 1: Die DCR legt zunächst einen Client-Platzhalter an und liefert dessen Client-ID.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "201"
    Und TGR speichere Wert des Knotens "$.body.client_id" der aktuellen Antwort in der Variable "registeredClientId"

    # Registrierung Teil 2: Die erste Ressourcenanfrage führt den Token-Exchange mit Client-Attestierung aus
    # und finalisiert dadurch die Registrierung. Der korrelierte OPA-Request enthält die final gespeicherten Clientdaten.
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.opa.decisionPath}"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.input.client_registration_data.client_id" überein mit "${registeredClientId}"
    Und TGR speichere Wert des Knotens "$.body.input.client_registration_data.platform" der aktuellen Anfrage in der Variable "CLIENT_PLATFORM"
    Und TGR speichere Wert des Knotens "$.body.input.client_registration_data.registration_timestamp" der aktuellen Anfrage in der Variable "REGISTRATION_TIMESTAMP"

    # Warte auf Telemetrie-Ingestion (Lieferintervall standardmäßig 60s)
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"

    # Der bei der Finalisierung erzeugte Security-Event-Logeintrag wird über die Client-ID eindeutig mit Teil 1 verbunden.
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                      | size |
      | resource.service.name:"${telemetry.service.authorizationServer}" AND attributes.auth.client_id:"${registeredClientId}" AND attributes.client_registration.result:* AND @timestamp:[${START}000 TO ${END}000] | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['auth.client_id']" überein mit "${registeredClientId}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.body" überein mit "authn_token_created:${registeredClientId}"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.hits.hits.0._source.attributes['auth.user_id']"

    # TA_A_25738_01 - Der protokollierte Betriebssystemparameter entspricht der Plattform aus der Clienteattestierung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['client_registration.client.os.name']" überein mit "(?i)^\Q${CLIENT_PLATFORM}\E$"

    # TA_A_25738_03 - Der protokollierte Registrierungszeitpunkt entspricht dem in Teil 2 verwendeten Registrierungszeitpunkt.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['client_registration.datetime']" überein mit "${REGISTRATION_TIMESTAMP}"

    # TA_A_25738_07 - VALID belegt das erfolgreiche Ergebnis der finalisierten Client-Registrierung.
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.hits.hits.0._source.attributes['client_registration.result']" überein mit "VALID"

    # TA_A_27725-01_20 bleibt als unabhängiger Trace-Nachweis für http.response.status_code=201 erhalten.
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                                    | start           | end           | limit | tags                                                                                 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.clientRegistration}     | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"201","url.path":"${paths.guard.registerEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.spans.*.tags.[?(@.key == 'http.response.status_code' && @.value == 201)]"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.spans.*.tags.[?(@.key == 'kc.clientId' && @.value == '${registeredClientId}')]"

  @A_26661
  @TA_A_26661_10
  @component
  @normal
  @no_proxy
  @MASVS-AUTH
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
