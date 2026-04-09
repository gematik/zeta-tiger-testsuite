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

  @internal
  @require_kubectl
  @deployment_modification
  @no_proxy
  Szenario: PEP Deployment Image aktualisieren
    Und ermittle den Image-Pfad für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_image_path"
    Und setze das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}"
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist
    Und warte "30" Sekunden
    Und rolle das Deployment "${zetaDeploymentConfig.pep.podName}" zurück
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionUpdate}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist

  @dev
  @A_26988
  Szenariogrundriss: Telemetrie-Daten Service - Fehlermeldungen
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                 | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:<containerName> AND attributes.log.file.path:/var/log/pods/* AND attributes.log.iostream:stderr | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    @TA_A_26988_01
    Beispiele: Ingress
      | containerName                      |
      | ${telemetry.containerName.ingress} |

    @TA_A_26988_02
    Beispiele: Egress
      | containerName                     |
      | ${telemetry.containerName.egress} |

    @TA_A_26988_03
    Beispiele: HTTP Proxy
      | containerName |
      | ${telemetry.containerName.httpProxy} |

    Beispiele: HTTP Proxy helper for metrics
      | containerName                            |
      | ${telemetry.containerName.helperMetrics} |

    # TODO: TA_A_26988_04, PEP Datenbank         <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_26988_04
    # Beispiele: PEP Datenbank
    #   | containerName                          |
    #   | ${telemetry.containerName.pepDatabase} |

    @TA_A_26988_05
    Beispiele: Authorization Server
      | containerName                                  |
      | ${telemetry.containerName.authorizationServer} |

    @TA_A_26988_06
    Beispiele: PDP Datenbank
      | containerName                          |
      | ${telemetry.containerName.pdpDatabase} |

    @TA_A_26988_07
    Beispiele: Policy Engine
      | containerName                           |
      | ${telemetry.containerName.policyEngine} |

    # TODO: TA_A_26988_08, Notification Service  <- Container fehlt noch
    # @TA_A_26988_08
    # Beispiele: Notification Service
    #   | containerName                                  |
    #   | ${telemetry.containerName.notificationService} |

    # TODO: TA_A_26988_09 Management Service   <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_26988_09
    # Beispiele: Management Service
    #   | containerName                                |
    #   | ${telemetry.containerName.managementService} |

    @TA_A_26988_10
    Beispiele: Telemetrie Daten Service
      | containerName                                   |
      | ${telemetry.containerName.telemetryDataService} |

    Beispiele: Zulieferer für Telemetrie Daten Service, Log Collector
      | containerName                                 |
      | ${telemetry.containerName.helperLogCollector} |

    @TA_A_26988_11
    Beispiele: Resource Server
      | containerName                             |
      | ${telemetry.containerName.resourceServer} |

  @dev
  @A_27264
  Szenariogrundriss: OpenTelemetry Logs für ZETA Guard Komponenten (ohne Datenbanken)
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                              | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:<containerName> AND attributes.log.file.path:/var/log/pods/* | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    @TA_A_27264_19
    Beispiele: Ingress
      | containerName                      |
      | ${telemetry.containerName.ingress} |

    @TA_A_27264_20
    Beispiele: Egress
      | containerName                     |
      | ${telemetry.containerName.egress} |

    @TA_A_27264_21
    Beispiele: HTTP Proxy
      | containerName                        |
      | ${telemetry.containerName.httpProxy} |

    Beispiele: HTTP Proxy helper for metrics
      | containerName                            |
      | ${telemetry.containerName.helperMetrics} |

    @TA_A_27264_22
    Beispiele: Authorization Server
      | containerName                                  |
      | ${telemetry.containerName.authorizationServer} |

    @TA_A_27264_23
    Beispiele: Policy Engine
      | containerName                           |
      | ${telemetry.containerName.policyEngine} |

    # TODO: TA_A_27264_24, Notification Service  <- Container fehlt noch
    # @TA_A_27264_24
    # Beispiele: Notification Service
    #   | containerName         |
    #   | notification-service  |

    # TODO: TA_A_27264_25, Management Service    <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_27264_25
    # Beispiele: Management Service
    #   | containerName        |
    #   | management-service   |

    @TA_A_27264_26
    Beispiele: Telemetrie Daten Service
      | containerName                                   |
      | ${telemetry.containerName.telemetryDataService} |

    Beispiele: Zulieferer für Telemetrie Daten Service, Log Collector
      | containerName                                 |
      | ${telemetry.containerName.helperLogCollector} |

    @TA_A_27264_27
    Beispiele: Resource Server
      | containerName                             |
      | ${telemetry.containerName.resourceServer} |

  @dev
  @A_27264
  Szenariogrundriss: OpenTelemetry Traces für ZETA Guard Komponenten (ohne Datenbanken)
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service   | lookback | limit |
      | <service> | 1h       | 1     |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von traceID bedeutet, dass es mindestens einen Open Telemetry Trace für genau diesen Container/Service gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

    @TA_A_27264_01
    Beispiele: Ingress
      | service                      |
      | ${telemetry.service.ingress} |


    @TA_A_27264_02
    Beispiele: Egress
      | service                     |
      | ${telemetry.service.egress} |

    @TA_A_27264_03
    Beispiele: HTTP Proxy
      | service                        |
      | ${telemetry.service.httpProxy} |

    @TA_A_27264_04
    Beispiele: Authorization Server
      | service                                  |
      | ${telemetry.service.authorizationServer} |

    @TA_A_27264_05
    Beispiele: Policy Engine
      | service                           |
      | ${telemetry.service.policyEngine} |

    # TODO: TA_A_27264_06, Notification Service  <- Container fehlt noch
    # @TA_A_27264_06
    # Beispiele: Notification Service
    #   | service                                  |
    #   | ${telemetry.service.notificationService} |

    # TODO: TA_A_27264_07, Management Service    <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_27264_07
    # Beispiele: Management Service
    #   | service                                |
    #   | ${telemetry.service.managementService} |

    @TA_A_27264_08
    Beispiele: Telemetrie Daten Service
      | service                                   |
      | ${telemetry.service.telemetryDataService} |

    Beispiele: Zulieferer für Telemetrie Daten Service, Log Collector
      | service                                 |
      | ${telemetry.service.helperLogCollector} |

    @TA_A_27264_09
    Beispiele: Resource Server
      | service                             |
      | ${telemetry.service.resourceServer} |

  @dev
  @A_27264
  Szenariogrundriss: OpenTelemetry Metrics für ZETA Guard Komponenten (ohne Datenbanken)
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

    # TODO: TA_A_27264_16, Management Service   <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_27264_16
    # Beispiele: Management Service
    #   | container                                 |
    #   |  ${telemetry.container.managementService} |

    @TA_A_27264_17
    Beispiele: Telemetrie Daten Service
      | container                                    |
      |  ${telemetry.container.telemetryDataService} |

    Beispiele: Zulieferer für Telemetrie Daten Service, Log Collector
      | container                                  |
      |  ${telemetry.container.helperLogCollector} |

    @TA_A_27264_18
    Beispiele: Resource Server
      | container                              |
      |  ${telemetry.container.resourceServer} |

  @dev
  @A_27492-02
  Szenariogrundriss: OpenTelemetry Unterstützung von HTTP Proxy, Authorization Server, Policy Engine und Notification Service
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                              | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:<containerName> AND attributes.log.file.path:/var/log/pods/* | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    Beispiele: HTTP Proxy
      | containerName                        |
      | ${telemetry.containerName.httpProxy} |

    @TA_A_27492-02_03
    Beispiele: Authorization Server
      | containerName                                  |
      | ${telemetry.containerName.authorizationServer} |

    Beispiele: Policy Engine
      | containerName                           |
      | ${telemetry.containerName.policyEngine} |

    # Beispiele: Notification Service # TODO: Container fehlt noch im Deployment
    #   | containerName                                  |
    #   | ${telemetry.containerName.notificationService} |


