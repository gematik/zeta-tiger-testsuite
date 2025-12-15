#
# #%L
# ZETA Testsuite
# %%
# (C) 2025 achelos GmbH, licensed for gematik GmbH
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

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten

  @dev
  @A_18464
  @TA_A_18464_01
  @no_proxy
  Szenariogrundriss: TLS 1.1 darf nicht unterstützt werden.
    Gegeben sei die TlsTestTool-Konfigurationsdaten für den Host "<host>" mit nur TLS 1.1 wurde erstellt
    # 46 in hexadezimal entspricht 70 in dezimal und ist der Alert Code für TLS Protocol Version Failure.
    Dann Akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id "46".
    Beispiele:
      | host |
      # Zeta Guard Ingress
      | ${zeta_base_url} |

  @staging
  @A_27266
  @A_27798
  @TA_A_27266_01
  @TA_A_27266_02
  @TA_A_27798_01
  @TA_A_27798_03
  Szenariogrundriss: well-known zu oauth-protected-resource
    Gegeben sei <reset_step>
    Wenn <request_step>
    Dann TGR finde die letzte Anfrage mit dem Pfad "<expected_path>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.httpVersion" überein mit "HTTP/1.1"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "OPR_WELL_KNOWN"
    Und validiere "${OPR_WELL_KNOWN}" gegen Schema "schemas/v_1_0/opr-well-known.yaml"

    @TA_A_26640_01
    Beispiele: Integrationstest
      | reset_step                                               | request_step                                           | expected_path                                          |
      | TGR sende eine leere GET Anfrage an "${paths.client.reset}" | TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" | .*${paths.guard.wellKnownOAuthProtectedResourcePath}$ |

    @no_proxy
    @component
    Beispiele: Komponententest
      | reset_step                             | request_step                                                                  | expected_path                                          |
      | TGR lösche aufgezeichnete Nachrichten | TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthProtectedResourcePath}" | .*${paths.guard.wellKnownOAuthProtectedResourcePath}$ |

  @dev
  @A_27798
  @TA_A_27798_02
  @TA_A_27798_04
  Szenariogrundriss: well-known zu oauth-authorization-server
    Gegeben sei <reset_step>
    Wenn <request_step>
    Dann TGR finde die letzte Anfrage mit dem Pfad "<expected_path>"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "AS_WELL_KNOWN"
    Und validiere "${AS_WELL_KNOWN}" gegen Schema "schemas/v_1_0/as-well-known.yaml"

    Beispiele: Integrationstest
      | reset_step                                               | request_step                                           | expected_path                                      |
      | TGR sende eine leere GET Anfrage an "${paths.client.reset}" | TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" | ${paths.guard.wellKnownOAuthServerPath} |

    @no_proxy
    @component
    Beispiele: Komponententest
      | reset_step                             | request_step                                                               | expected_path                                      |
      | TGR lösche aufgezeichnete Nachrichten | TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.wellKnownOAuthServerPath}" | .*${paths.guard.wellKnownOAuthServerPath}$ |

  @staging
  @dev
  @A_27799
  @A_27007
  @TA_A_27007_14
  Szenario: Client erfolgreich registrieren (Integrationstest)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    # TA_A_27007_14
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
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.[~'Content-Type']" überein mit "application/json"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_name" überein mit "sdk-client"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.token_endpoint_auth_method"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_types"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.jwks"

  @no_proxy
  @staging
  @component
  @dev
  @A_27799
  @A_27007
  @TA_A_27007_14
  Szenario: Client erfolgreich registrieren (Komponententest)
    Wenn TGR sende eine POST Anfrage an "${paths.guard.baseUrl}${paths.guard.registerEndpointPath}" mit ContentType "application/json" und folgenden mehrzeiligen Daten:
      """
      !{file('src/test/resources/mocks/register-request.json')}
      """
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.registerEndpointPath}"
    # TA_A_27007_14
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
    Und TGR prüfe aktueller Request stimmt im Knoten "$.header.Content-Type" überein mit "application/json"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.client_name" überein mit "sdk-client"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.token_endpoint_auth_method"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.grant_types"
    Und TGR prüfe aktueller Request enthält Knoten "$.body.jwks"
