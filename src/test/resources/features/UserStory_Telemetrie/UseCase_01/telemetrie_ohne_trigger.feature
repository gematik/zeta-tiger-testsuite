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

@UseCase_TELEMETRIE_01
Funktionalität: Telemetrie-Tests ohne dedizierten Trigger

  @deployment_modification
  @internal
  @no_proxy
  Szenario: PEP Deployment Image aktualisieren
    Und ermittle den Image-Pfad für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_image_path"
    Und setze das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}"
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist
    Und warte "30" Sekunden
    Und rolle das Deployment "${zetaDeploymentConfig.pep.podName}" zurück
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionUpdate}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist

  @A_27261
  @TA_A_27261_01
  @no_proxy
  @require_kubectl
  @MASVS-RESILIENCE
  Szenario: Telemetriedaten-Service gibt Telemetriedaten per OTLP gRPC oder HTTP JSON weiter
    Dann prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Telemetriedaten per erlaubtem OTLP Protokoll weitergibt

  @A_27727-01
  @TA_A_27727-01_01
  @TA_A_27727-01_02
  @no_proxy
  @require_kubectl
  @MASVS-RESILIENCE
  Szenario: Telemetriedaten-Service liefert Traces asynchron per Batch-Export
    Dann prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Traces per Batch exportiert

  @A_27264
  @normal
  @MASVS-RESILIENCE
  Szenariogrundriss: OpenTelemetry Logs für ZETA Guard Komponenten (<serviceName>) (ohne Datenbanken)
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                   | size |
      | resource.service.name:<serviceName> | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Service gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    @TA_A_27264_19
    Beispiele: Ingress
      | serviceName                  |
      | ${telemetry.service.ingress} |

    @TA_A_27264_20
    Beispiele: Egress
      | serviceName                 |
      | ${telemetry.service.egress} |

    @TA_A_27264_21
    Beispiele: HTTP Proxy
      | serviceName                    |
      | ${telemetry.service.httpProxy} |

    Beispiele: HTTP Proxy helper for metrics
      | serviceName                        |
      | ${telemetry.service.helperMetrics} |

    @TA_A_27264_22
    Beispiele: Authorization Server
      | serviceName                              |
      | ${telemetry.service.authorizationServer} |

    @TA_A_27264_23
    Beispiele: Policy Engine
      | serviceName                       |
      | ${telemetry.service.policyEngine} |

    # TODO: TA_A_27264_24, Notification Service  <- Service fehlt noch
    # @TA_A_27264_24
    # Beispiele: Notification Service
    #   | serviceName                              |
    #   | ${telemetry.service.notificationService} |

    # TA_A_27264_25, Management Service ist optional, wird nicht unterstützt und wird daher hier nicht geprüft.
    # @TA_A_27264_25
    # Beispiele: Management Service
    #   | serviceName                            |
    #   | ${telemetry.service.managementService} |

    @TA_A_27264_26
    Beispiele: Telemetrie Daten Service
      | serviceName                               |
      | ${telemetry.service.telemetryDataService} |

    @TA_A_27264_27
    Beispiele: Resource Server
      | serviceName                         |
      | ${telemetry.service.resourceServer} |

  @A_27264
  @normal
  @MASVS-RESILIENCE
  Szenariogrundriss: OpenTelemetry Traces für ZETA Guard Komponenten (<service>) (ohne Datenbanken)
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service   | lookback | limit |
      | <service> | 1h       | 1     |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von traceID bedeutet, dass es mindestens einen Open Telemetry Trace für genau diesen Container/Service gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

#    @TA_A_27264_01
#    Beispiele: Ingress
#      | service                      |
#      | ${telemetry.service.ingress} |
#
#
#    @TA_A_27264_02
#    Beispiele: Egress
#      | service                     |
#      | ${telemetry.service.egress} |

    @TA_A_27264_03
    Beispiele: HTTP Proxy
      | service                        |
      | ${telemetry.service.httpProxy} |

#    @TA_A_27264_04
#    Beispiele: Authorization Server
#      | service                                  |
#      | ${telemetry.service.authorizationServer} |
#
#    @TA_A_27264_05
#    Beispiele: Policy Engine
#      | service                           |
#      | ${telemetry.service.policyEngine} |
#
#    # TODO: TA_A_27264_06, Notification Service  <- Container fehlt noch
#    # @TA_A_27264_06
#    # Beispiele: Notification Service
#    #   | service                                  |
#    #   | ${telemetry.service.notificationService} |
#
#    # TA_A_27264_07, Management Service ist optional, wird nicht unterstützt und wird daher hier nicht geprüft.
#    # @TA_A_27264_07
#    # Beispiele: Management Service
#    #   | service                                |
#    #   | ${telemetry.service.managementService} |
#
#    @TA_A_27264_08
#    Beispiele: Telemetrie Daten Service
#      | service                                   |
#      | ${telemetry.service.telemetryDataService} |
#
#    @TA_A_27264_09
#    Beispiele: Resource Server
#      | service                             |
#      | ${telemetry.service.resourceServer} |

  @A_27264
  @normal
  @MASVS-RESILIENCE
  Szenariogrundriss: OpenTelemetry Metrics für ZETA Guard Komponenten (<container>) (ohne Datenbanken)
    Wenn TGR sende eine GET Anfrage an "${paths.prometheus.baseUrl}${paths.prometheus.prometheusMetricsSearchPath}" mit folgenden Daten:
      | query                                                 |
      | container_start_time_seconds{container="<container>"} |

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.prometheus.prometheusMetricsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Knotenexistenz bedeutet, dass es mindestens einen Open Telemetry Metrik-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.result.0.value.1"

    @TA_A_27264_10
    Beispiele: Ingress
      | container                      |
      | ${telemetry.container.ingress} |

    @TA_A_27264_11
    Beispiele: Egress
      | container                    |
      |${telemetry.container.egress} |

    @TA_A_27264_12
    Beispiele: HTTP Proxy
      | container                        |
      | ${telemetry.container.httpProxy} |

    Beispiele: HTTP Proxy helper for metrics
      | container                             |
      |  ${telemetry.container.helperMetrics} |

    @TA_A_27264_13
    Beispiele: Authorization Server
      | container                                   |
      |  ${telemetry.container.authorizationServer} |

    @TA_A_27264_14
    Beispiele: Policy Engine
      | container                            |
      |  ${telemetry.container.policyEngine} |

    # TODO: TA_A_27264_15, Notification Service <- Container fehlt noch
    # @TA_A_27264_15
    # Beispiele: Notification Service
    #   | container                                   |
    #   |  ${telemetry.container.notificationService} |

    # TA_A_27264_16, Management Service ist optional, wird nicht unterstützt und wird daher hier nicht geprüft.
    # @TA_A_27264_16
    # Beispiele: Management Service
    #   | container                                 |
    #   |  ${telemetry.container.managementService} |

    @TA_A_27264_17
    Beispiele: Telemetrie Daten Service
      | container                                    |
      |  ${telemetry.container.telemetryDataService} |

    @TA_A_27264_18
    Beispiele: Resource Server
      | container                              |
      |  ${telemetry.container.resourceServer} |

  @A_27492-02
  @normal
  @MASVS-RESILIENCE
  Szenariogrundriss: OpenTelemetry Unterstützung von HTTP Proxy, Authorization Server, Policy Engine und Notification Service

    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service       | operation   | lookback | limit | tags   |
      | <serviceName> | <operation> | 1h       | 1     | <tags> |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von traceID bedeutet, dass es mindestens einen Open Telemetry Trace für genau diese Operations-Suchparameter gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

    @TA_A_27492-02_01
    Beispiele: HTTP Proxy - oauth-protected-resource
      | serviceName                    | operation                             | tags                                                                 |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.wellKnown} | {"http.target":"${paths.guard.wellKnownOAuthProtectedResourcePath}"} |

    @TA_A_27492-02_02
    Beispiele: HTTP Proxy - resource
      | serviceName                    | operation                       | tags                                            |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | {"http.target":"${paths.guard.helloZetaPath}"} |

    @TA_A_27492-02_03
    Beispiele: Authorization Server - oauth-authorization-server
      | serviceName                              | operation                               | tags                                                                              |
      | ${telemetry.service.authorizationServer} | GET /realms/{realm}/.well-known/{alias} | {"url.path":"${paths.guard.wellKnownAuthServerPath}"} |

    @TA_A_27492-02_04
    Beispiele: Authorization Server - nonce
      | serviceName                              | operation                                    | tags                                               |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce2} | {"url.path":"${paths.guard.nonceEndpointPath}"} |

    @TA_A_27492-02_05
    Beispiele: Authorization Server - register
      | serviceName                              | operation                                                | tags                                                    |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.clientRegistration} | {"url.path":"${paths.guard.registerEndpointPath}"} |

    @TA_A_27492-02_06
    Beispiele: Authorization Server - token
      | serviceName                              | operation                                   | tags                                               |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | {"url.path":"${paths.guard.tokenEndpointPath}"} |

    @TA_A_27492-02_07
    Beispiele: Policy Engine - /v1/data/authz
      | serviceName                       | operation                             | tags                                             |
      | ${telemetry.service.policyEngine} | ${telemetry.span.policyEngine.data}   | {"url.path":"${paths.opa.decisionPath}"} |

    # Beispiele: Notification Service # TODO: Container fehlt noch im Deployment
    #   | serviceName                              | operation | tags |
    #   | ${telemetry.service.notificationService} |          |      |
