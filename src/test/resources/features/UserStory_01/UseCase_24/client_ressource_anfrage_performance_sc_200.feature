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

@UseCase_01_24
@no_proxy
@performance
@require_kubectl
@deployment_modification
@performance_direct_routing
Funktionalität: Client Ressource Anfrage Performance SC 200

  # threads konfiguriert Worker, instances den Client-Pool; setup_semaphore steuert die Vorbereitung.
  # Die gesamte Szenariodauer enthält zusätzlich zur Lastdauer auch Vorbereitung, Wartezeit und Auswertung.
  # Prometheus wird nach der Telemetrie-Wartezeit zum aktuellen Zeitpunkt abgefragt; das Fenster enthält Puffer.
  # Der Ratendivisor bleibt die Lastdauer; nur die zweiphasige PDP-Rate verwendet combinedDurationS.
  # WebSocket-Szenarien prüfen 301 offene Verbindungen nach STOMP-Aufbau und erneut nach dem Lastlauf.



  @Ignore
  @A_26486-01
  @A_26488
  @TA_A_26486-01_01
  @TA_A_26488_01
  @websocket
  Szenario: PEP HTTP Proxy - Performance und Last ohne ASL
    # Vor Wiederaufnahme muss der eingesetzte Load Dispenser den Workload hellozeta_without_asl unterstützen.
    Angenommen TGR setze lokale Variable "loadDurationS" auf "300"
    Wenn 320 WebSocket Verbindungen mit STOMP zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta_without_asl |
      | threads            | 50                    |
      | instances          | 300                   |
      | registered_full    | 1.0                   |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                     |
      | target_rps         | 310                   |
      | ramp_s             | 10                    |
      | runtime_s          | ${loadDurationS}      |
      | loss_rate_access   | 0.0                   |
      | loss_rate_refresh  | 0.0                   |
      | control_timeout_s  | 900                   |
    Und sind mindestens 301 WebSocket Verbindungen offen
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pep_ohne_asl" mit Titelpräfix "PEP ohne ASL" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.pep}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die Rate >= "300" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.pepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist

  @A_26486-01
  @A_26488
  @TA_A_26486-01_01
  @TA_A_26488_01
  @websocket
  Szenario: PEP HTTP Proxy - Performance mit ASL
    # ASL muss im Zieldeployment aktiviert sein; der WebSocket-/STOMP-Aufbau schaltet ASL nicht ein.
    Angenommen TGR setze lokale Variable "loadDurationS" auf "60"
    Wenn 320 WebSocket Verbindungen mit STOMP zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta        |
      | threads            | 50               |
      | instances          | 300              |
      | registered_full    | 1.0              |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                |
      | target_rps         | 310              |
      | ramp_s             | 10               |
      | runtime_s          | ${loadDurationS} |
      | loss_rate_access   | 0.0              |
      | loss_rate_refresh  | 0.0              |
      | control_timeout_s  | 600              |
    Und sind mindestens 301 WebSocket Verbindungen offen
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pep_asl" mit Titelpräfix "PEP mit ASL" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die Rate >= "300" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                        | span                                  | avg | p90 | p95 | p99  |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.asl}       | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep}       | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.wellKnown} | 7.5 | 10  | 15  | 100  |

  @A_26489-02
  @A_26491
  @TA_A_26489-02_01
  @TA_A_26491_01
  Szenario: PDP Authorization Server - Last und Performance
    # loss_rate_refresh=1.0 fordert wiederholte vollständige Token-Austausche an.
    # target_rps bezeichnet die Workload-Zielrate; die PDP-Prüfung addiert Nonce- und Token-Spanzähler.
    Angenommen TGR setze lokale Variable "loadDurationS" auf "300"
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | tokens           |
      | threads            | 50               |
      | instances          | 160              |
      | registered_full    | 1.0              |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                |
      | target_rps         | 160              |
      | ramp_s             | 10               |
      | runtime_s          | ${loadDurationS} |
      | loss_rate_access   | 0.0              |
      | loss_rate_refresh  | 1.0              |
      | control_timeout_s  | 600              |
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp" mit Titelpräfix "PDP" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die kombinierte Rate >= "300" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                        | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce} | 33  | 50  | 75  | 500  |

  @A_26489-02
  @TA_A_26489-02_01
  Szenario: PDP Authorization Server - Token-Latenz mit Refresh Token
    # Access-Token-Verlust bei erhaltenem Refresh Token fordert den Refresh-Flow an.
    # Die Latenzprüfung verlangt TokenEndpoint.processGrantRequest mit zeta_test_oauth_grant="refresh".
    Angenommen TGR setze lokale Variable "loadDurationS" auf "60"
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | tokens           |
      | threads            | 50               |
      | instances          | 180              |
      | registered_full    | 1.0              |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                |
      | target_rps         | 180              |
      | ramp_s             | 10               |
      | runtime_s          | ${loadDurationS} |
      | loss_rate_access   | 1.0              |
      | loss_rate_refresh  | 0.0              |
      | control_timeout_s  | 600              |
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp" mit Titelpräfix "PDP Refresh-Token-Latenz" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "60" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Label "zeta_test_oauth_grant"="refresh", Fenster "${prometheusWindowS}" Sekunden Samples für folgende Latenzgrenzen in ms vorhanden sind
      | service                                  | span                              | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | TokenEndpoint.processGrantRequest | 75  | 100 | 150 | 1000 |

  @Ignore
  @A_26489-02
  @TA_A_26489-02_01
  Szenario: PDP Authorization Server - Revocation-Latenz unter Last
    Angenommen TGR setze lokale Variable "loadDurationS" auf "60"
    Und TGR setze lokale Variable "revokeTargetRps" auf "180"
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | revoke                |
      | threads            | 50                    |
      | instances          | 180                   |
      | registered_full    | 1.0                   |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                     |
      | target_rps         | ${revokeTargetRps}    |
      | ramp_s             | 10                    |
      | runtime_s          | ${loadDurationS}      |
      | loss_rate_access   | 0.0                   |
      | loss_rate_refresh  | 0.0                   |
      | control_timeout_s  | 600                   |
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp_revoke" mit Titelpräfix "PDP Revocation-Latenz" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "60" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Span "${telemetry.span.authorizationServer.revoke}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die Rate >= "${revokeTargetRps}" pro Sekunde ist
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                         | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.revoke} | 75  | 100 | 150 | 1000 |

  @deployment_modification
  @A_26487
  @TA_A_26487_01
  Szenariogrundriss: PEP HTTP Proxy - horizontale Skalierbarkeit mit mindestens 75 Prozent zusätzlicher Leistung pro Pod
    # Zielrate und Client-Pool: Basis 310 plus aufgerundete 75 Prozent der Basis je zusätzlichem Pod.
    # pepConcurrency wird als instances übergeben; die Workerzahl bleibt bei 50.
    Angenommen TGR setze lokale Variable "loadDurationS" auf "60"
    Und berechne und setze lokale Variable "targetRps" aus Basis "310" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und berechne und setze lokale Variable "pepConcurrency" aus Basis "310" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und skaliere das Deployment "${zetaDeploymentConfig.pep.podName}" auf <replicas> Replikas
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta         |
      | threads            | 50                |
      | instances          | ${pepConcurrency} |
      | registered_full    | 1.0               |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                 |
      | target_rps         | ${targetRps}      |
      | ramp_s             | 10                |
      | runtime_s          | ${loadDurationS}  |
      | loss_rate_access   | 0.0               |
      | loss_rate_refresh  | 0.0               |
      | control_timeout_s  | 900               |
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pep_asl" mit Titelpräfix "PEP Skalierung <replicas> Pods" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die Rate >= "${targetRps}" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                        | span                                  | avg | p90 | p95 | p99  |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.asl}       | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep}       | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.wellKnown} | 7.5 | 10  | 15  | 100  |

    Beispiele:
      | replicas |
      | 1        |
      | 2        |
      | 3        |
      | 4        |
      | 5        |

  @deployment_modification
  @A_26490
  @TA_A_26490_01
  Szenariogrundriss: PDP Authorization Server - horizontale Skalierbarkeit mit mindestens 75 Prozent zusätzlicher Leistung pro Pod
    # Workload-Zielrate und Client-Pool skalieren ab Basis 160, die kombinierte PDP-Mindestrate ab Basis 300.
    # Je zusätzlichem Pod werden aufgerundete 75 Prozent der jeweiligen Basis addiert; es bleiben 100 Worker.
    Angenommen TGR setze lokale Variable "loadDurationS" auf "60"
    Und berechne und setze lokale Variable "targetRps" aus Basis "160" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und berechne und setze lokale Variable "expectedCombinedRps" aus Basis "300" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und berechne und setze lokale Variable "pdpConcurrency" aus Basis "160" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und skaliere das Deployment "${zetaDeploymentConfig.authserver.podName}" auf <replicas> Replikas
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | tokens            |
      | threads            | 100               |
      | instances          | ${pdpConcurrency} |
      | registered_full    | 1.0               |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                 |
      | target_rps         | ${targetRps}      |
      | ramp_s             | 10                |
      | runtime_s          | ${loadDurationS}  |
      | loss_rate_access   | 0.0               |
      | loss_rate_refresh  | 1.0               |
      | control_timeout_s  | 900               |
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp" mit Titelpräfix "PDP Skalierung <replicas> Pods" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die kombinierte Rate >= "${expectedCombinedRps}" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                        | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce} | 33  | 50  | 75  | 500  |

    Beispiele:
      | replicas |
      | 1        |
      | 2        |
      | 3        |
      | 4        |
      | 5        |

  @A_26486-01
  @A_26488
  @A_26489-02
  @A_26491
  @TA_A_26486-01_01
  @TA_A_26488_01
  @TA_A_26489-02_01
  @TA_A_26491_01
  @websocket
  Szenario: PDP und PEP kombiniert - Realistischer Langzeittest mit Client-Rotation
    # 70000 Instanzen im Client-Pool und 50 Worker; registered_full=0.0 fordert keine vollständige Vorregistrierung an.
    # Die Poolgröße allein belegt nicht, dass innerhalb der Laufzeit alle Instanzen verwendet wurden.
    # Die kombinierte PDP-Rate umfasst hier Nonce-, Registrierungs- und Token-Spanzähler.
    Angenommen TGR setze lokale Variable "loadDurationS" auf "300"
    Wenn 320 WebSocket Verbindungen mit STOMP zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta        |
      | threads            | 50               |
      | instances          | 70000            |
      | registered_full    | 0.0              |
      | setup_semaphore    | ${load_dispenser_setup_semaphore} |
      | wait_after_setup_s | 0                |
      | target_rps         | 310              |
      | ramp_s             | 10               |
      | runtime_s          | ${loadDurationS} |
      | loss_rate_access   | 0.0              |
      | loss_rate_refresh  | 0.0              |
      | control_timeout_s  | 1800             |
    Und sind mindestens 301 WebSocket Verbindungen offen
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${loadDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp_kalte_clients,pep_asl" mit Titelpräfix "PDP und PEP kombiniert" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die kombinierte Rate >= "300" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die Rate >= "300" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                                     | avg | p90 | p95 | p99  |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.asl}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.pep}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.wellKnown}                    | 7.5 | 10  | 15  | 100  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce}              | 33  | 50  | 75  | 500  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.clientRegistration} | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token}              | 75  | 100 | 150 | 1000 |
    # Im kombinierten Lauf gelten diese zusätzlichen Refresh-Grenzen nur bei vorhandenen passenden Samples.
    Und falls in Prometheus für Label "zeta_test_oauth_grant"="refresh", Fenster "${prometheusWindowS}" Sekunden Samples vorhanden sind, gelten folgende Latenzgrenzen in ms
      | service                                  | span                              | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | TokenEndpoint.processGrantRequest | 75  | 100 | 150 | 1000 |

  @A_26486-01
  @A_26488
  @A_26489-02
  @A_26491
  @TA_A_26486-01_01
  @TA_A_26488_01
  @TA_A_26489-02_01
  @TA_A_26491_01
  @websocket
  Szenario: PDP und PEP kombiniert - Zweiphasiger Langzeittest mit Client-Rotation
    # Die Setup-Phase des Load Dispensers registriert den konfigurierten Anteil der Instanzen.
    # Im Lastlauf erzeugen vorbereitete Instanzen Token- und Resource-Last; die übrigen Instanzen
    # durchlaufen beim ersten fachlichen Zugriff den vollständigen Register-, Token- und Resource-Flow.
    # registered_full=0.95 fordert die Vorbereitung von 66500 der 70000 Instanzen an; es werden 50 Worker verwendet.
    # phase1DurationS stammt aus dem beobachteten Übergang nach RUNNING; keine lokale Vorbelegung setzen.
    # Das gemeinsame Prometheus-Fenster umfasst Vorbereitungsdauer, Lastdauer, Telemetrie-Wartezeit und Puffer.
    Angenommen TGR setze lokale Variable "loadDurationS" auf "300"
    Wenn 320 WebSocket Verbindungen mit STOMP zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test                       | hellozeta        |
      | threads                    | 50               |
      | instances                  | 70000            |
      | registered_full            | 0.95             |
      | setup_semaphore            | 64               |
      | wait_after_setup_s         | 0                |
      | target_rps                 | 310              |
      | ramp_s                     | 10               |
      | runtime_s                  | ${loadDurationS} |
      | loss_rate_access           | 0.0              |
      | loss_rate_refresh          | 0.0              |
      | control_timeout_s          | 3600             |
      | setup_duration_target_var  | phase1DurationS  |
      | run_start_epoch_target_var | PHASE2_START     |
      | run_end_epoch_target_var   | PHASE2_END       |

    # Jaeger verwendet die beobachteten Phase-2-Grenzen; Prometheus wertet mit dem größeren gemeinsamen Fenster aus.
    Und TGR setze lokale Variable "PHASE2_START_MICROS" auf "!{${PHASE2_START} * 1000000}"
    Und TGR setze lokale Variable "PHASE2_END_MICROS" auf "!{(${PHASE2_END} + 1) * 1000000 - 1}"
    Und TGR setze lokale Variable "combinedDurationS" auf "!{${phase1DurationS} + ${loadDurationS}}"
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${combinedDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und sind mindestens 301 WebSocket Verbindungen offen
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Wenn Jaeger-Traces mit folgenden Daten abgefragt werden:
      | service                        | start                  | end                  | limit | tags             |
      | ${telemetry.service.httpProxy} | ${PHASE2_START_MICROS} | ${PHASE2_END_MICROS} | 20    | {"error":"true"} |
    Wenn Jaeger-Traces mit folgenden Daten abgefragt werden:
      | service                                  | start                  | end                  | limit | tags             |
      | ${telemetry.service.authorizationServer} | ${PHASE2_START_MICROS} | ${PHASE2_END_MICROS} | 20    | {"error":"true"} |
    Wenn Jaeger-Traces mit folgenden Daten abgefragt werden:
      | service                        | operation                       | start                  | end                  | limit | minDuration |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.asl} | ${PHASE2_START_MICROS} | ${PHASE2_END_MICROS} | 20    | 1s          |
    Wenn Jaeger-Traces mit folgenden Daten abgefragt werden:
      | service                        | operation                       | start                  | end                  | limit | minDuration |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep} | ${PHASE2_START_MICROS} | ${PHASE2_END_MICROS} | 20    | 1s          |
    Wenn Jaeger-Traces mit folgenden Daten abgefragt werden:
      | service                                  | operation                                                | start                  | end                  | limit | minDuration |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.clientRegistration} | ${PHASE2_START_MICROS} | ${PHASE2_END_MICROS} | 20    | 1s          |
    Wenn Jaeger-Traces mit folgenden Daten abgefragt werden:
      | service                                  | operation                                   | start                  | end                  | limit | minDuration |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${PHASE2_START_MICROS} | ${PHASE2_END_MICROS} | 20    | 1s          |
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp_kalte_clients,pep_asl" mit Titelpräfix "PDP und PEP kombiniert zweiphasig" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${combinedDurationS}" Sekunden die kombinierte Rate >= "300" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${loadDurationS}" Sekunden die Rate >= "300" pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                                     | avg | p90 | p95 | p99  |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.asl}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.pep}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.wellKnown}                    | 7.5 | 10  | 15  | 100  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce}              | 33  | 50  | 75  | 500  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.clientRegistration} | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token}              | 75  | 100 | 150 | 1000 |
    # Im kombinierten Lauf gelten diese zusätzlichen Refresh-Grenzen nur bei vorhandenen passenden Samples.
    Und falls in Prometheus für Label "zeta_test_oauth_grant"="refresh", Fenster "${prometheusWindowS}" Sekunden Samples vorhanden sind, gelten folgende Latenzgrenzen in ms
      | service                                  | span                              | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | TokenEndpoint.processGrantRequest | 75  | 100 | 150 | 1000 |
