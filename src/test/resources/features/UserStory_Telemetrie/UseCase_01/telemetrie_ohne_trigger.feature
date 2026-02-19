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

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt

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
      | containerName |
      | controller    |

    # TODO: TA_A_26988_02, Egress                <- Dedizierter Container/Gateway fehlt noch
    @TA_A_26988_02
    Beispiele: Egress
      | containerName |
      | controller    |

    @TA_A_26988_03
    Beispiele: HTTP Proxy
      | containerName |
      | nginx         |

    # TODO: TA_A_26988_04, PEP Datenbank         <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_26988_04
    # Beispiele: PEP Datenbank
    #   | containerName |
    #   | database-pep  |

    @TA_A_26988_05
    Beispiele: Authorization Server
      | containerName |
      | keycloak      |

    @TA_A_26988_06
    Beispiele: PDP Datenbank
      | containerName |
      | postgresql    |

    @TA_A_26988_07
    Beispiele: Policy Engine
      | containerName |
      | opa           |

    # TODO: TA_A_26988_08, Notification Service  <- Container fehlt noch
    # @TA_A_26988_08
    # Beispiele: Notification Service
    #   | containerName        |
    #   | notification-service |

    # TODO: TA_A_26988_09 Management Service   <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_26988_09
    # Beispiele: Management Service
    #   | containerName     |
    #   |management-service |

    @TA_A_26988_10
    Beispiele: Telemetrie Daten Service
      | containerName           |
      | opentelemetry-collector |

    Beispiele: Zulieferer für Telemetrie Daten Service, Log Collector
      | containerName  |
      | log-collector  |

    Beispiele: Zulieferer für Telemetrie Daten Service, Exporteur zu gematik
      | containerName     |
      | telemetry-gateway |

    @TA_A_26988_11
    Beispiele: Resource Server
      | containerName  |
      | testfachdienst |

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
      | containerName |
      | controller    |

    # TODO: TA_A_27264_20, Egress               <- Dedizierter Container/Gateway fehlt noch
    @TA_A_27264_20
    Beispiele: Egress
      | containerName |
      | controller    |

    @TA_A_27264_21
    Beispiele: HTTP Proxy
      | containerName |
      | nginx         |

    @TA_A_27264_22
    Beispiele: Authorization Server
      | containerName |
      | keycloak      |

    @TA_A_27264_23
    Beispiele: Policy Engine
      | containerName |
      | opa           |

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
      | containerName           |
      | opentelemetry-collector |

    Beispiele: Zulieferer für Telemetrie Daten Service, Log Collector
      | containerName |
      | log-collector |

    Beispiele: Zulieferer für Telemetrie Daten Service, Exporteur zu gematik
      | containerName     |
      | telemetry-gateway |

    @TA_A_27264_27
    Beispiele: Resource Server
      | containerName  |
      | testfachdienst |

  @dev
  @A_27264
  Szenariogrundriss: OpenTelemetry Traces für ZETA Guard Komponenten (ohne Datenbanken)
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service       | lookback | limit |
      | <serviceName> | 1h       | 1     |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von traceID bedeutet, dass es mindestens einen Open Telemetry Trace für genau diesen Container/Service gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"

    @TA_A_27264_01
    Beispiele: Ingress
      | serviceName |
      | controller    |

    # TODO: TA_A_27264_02, Egress                <- Dedizierter Container/Gateway fehlt noch
    # @TA_A_27264_02
    # Beispiele: Egress
    #   | serviceName |
    #   | controller  |

    @TA_A_27264_03 # TODO: Service-Namen prüfen, warum wird Präfix unknown_service: verwendet
    Beispiele: HTTP Proxy
      | serviceName           |
      | unknown_service:nginx |

    @TA_A_27264_04
    Beispiele: Authorization Server
      | serviceName |
      | keycloak      |

    @TA_A_27264_05
    Beispiele: Policy Engine
      | serviceName |
      | opa         |

    # TODO: TA_A_27264_06, Notification Service  <- Container fehlt noch
    # @TA_A_27264_06
    # Beispiele: Notification Service
    #   | serviceName         |
    #   | notification-service  |

    # TODO: TA_A_27264_07, Management Service    <- Container wird wahrscheinlich nicht benötigt
    # @TA_A_27264_07
    # Beispiele: Management Service
    #   | serviceName        |
    #   | management-service   |

    @TA_A_27264_08
    Beispiele: Telemetrie Daten Service
      | serviceName           |
      | opentelemetry-collector |

    Beispiele: Zulieferer für Telemetrie Daten Service, Log Collector
      | serviceName |
      | log-collector |

    Beispiele: Zulieferer für Telemetrie Daten Service, Exporteur zu gematik
      | serviceName     |
      | telemetry-gateway |

    @TA_A_27264_09
    Beispiele: Resource Server
      | serviceName  |
      | testfachdienst |

  @dev
  @A_27492-01
  Szenariogrundriss: OpenTelemetry Unterstützung von HTTP Proxy, Authorization Server, Policy Engine und Notification Service
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                              | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:<containerName> AND attributes.log.file.path:/var/log/pods/* | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    Beispiele: HTTP Proxy
      | containerName |
      | nginx         |

    @TA_A_27492-01_03
    Beispiele: Authorization Server
      | containerName |
      | keycloak      |

    Beispiele: Policy Engine
      | containerName |
      | opa           |

    # Beispiele: Notification Service # TODO: Container fehlt noch im Deployment
    #   | containerName        |
    #   | notification-service |

  @A_25796
  Szenariogrundriss: Health Check Antworten der ZETA Guard Komponenten
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                                                                                                                         | size |
      | resource.k8s.namespace.name:${zeta_k8s_namespace} AND resource.k8s.container.name:<containerName> AND body:"\\\'msg\\\':\\\'Sent response.\\\'" AND body:"\\\'req_method\\\':\\\'GET\\\'"  AND body:"\\\'resp_status\\\':\\\'200\\\'" AND body:"\\\'req_path\\\':\\\'\\\/health\\\'" | 1    |

    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0._source.resource['k8s.pod.start_time']"

    @TA_A_25796_01
    Beispiele: Http Proxy
      | containerName |
      | nginx         |

    @TA_A_25796_30
    Beispiele: Open Policy Agent
      | containerName |
      | opa         |
