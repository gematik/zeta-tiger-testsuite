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
  Szenario: Telemetriedaten-Service gibt Telemetriedaten per OTLP gRPC oder HTTP JSON weiter
    Dann prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Telemetriedaten per erlaubtem OTLP Protokoll weitergibt

  @A_27727-02
  @TA_A_27727-02_01
  @TA_A_27727-02_02
  @no_proxy
  @require_kubectl
  Szenario: Telemetriedaten-Service liefert Traces asynchron per Batch-Export
    Dann prüfe, dass die Telemetrie-Gateway Collector-Konfiguration Traces aus Pipeline "${telemetry.gematikTracePipeline}" per Batch über OTLP exportiert

  @A_27492-02
  @normal
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
