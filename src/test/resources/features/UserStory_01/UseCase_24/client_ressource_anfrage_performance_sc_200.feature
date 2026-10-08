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
Funktionalität: Client Ressource Anfrage Performance SC 200

# Laufzeit: Der vollständige ausführbare Performance-Lauf dauert in der Achelos-Umgebung ungefähr 1 h 40 min.
# Die Laufzeit hängt von Cluster, Setup und Telemetrieverzögerung ab; das ignorierte Ohne-ASL-Szenario ist nicht enthalten.

  # Alle hellozeta-Workloads des Load Dispensers senden ein gültiges PoPP-Token.
  # Damit bleibt PoPP-Last auch nach der Ablösung des JMeter-Drivers Bestandteil
  # der PEP- und kombinierten Performance-Szenarien.

  # Dieses Szenario bleibt als fachliche Abdeckung dokumentiert, ist aber derzeit bewusst ignoriert.
  # Der aktuell veröffentlichte Load Dispenser unterstützt nur test=hellozeta (mit ASL) und test=tokens.
  # Für die Wiederaufnahme muss der Load Dispenser zuerst einen Plain-HTTP-Workload ohne ASL anbieten,
  # z. B. test=hellozeta_without_asl. Erst danach können die kubectl-Voraussetzung (ASL deaktiviert),
  # die 310-RPS-Last und die Prometheus-Prüfungen wieder ausgeführt werden.
  @Ignore
  @A_26486-01
  @A_26488
  @TA_A_26486-01_01
  @TA_A_26488_01
  @websocket
  Szenario: PEP HTTP Proxy - Performance und Last ohne ASL
    # TA_A_26488_01 prüft mehr als 300 WebSocket-Verbindungen und mehr als 300 Requests/s am selben
    # PEP-Pod. Die WebSockets werden vor dem HTTP-Lastlauf aufgebaut und bleiben währenddessen offen.
    # Lastaufbau: 320 Verbindungen anfordern, um die Kapazität von mehr als 300 zu testen.
    Wenn 320 WebSocket Verbindungen zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    # Testaspekt TA_A_26488_01: Mindestanzahl direkt nach dem Aufbau verifizieren.
    Dann sind mindestens 301 WebSocket Verbindungen offen
    # Lastaufbau: nachgelagerter Plain-HTTP-Workload ohne ASL mit 310 Requests/s.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta_without_asl |
      | threads            | 50                    |
      | instances          | 300                   |
      | registered_full    | 1.0                   |
      | setup_semaphore    | 128                   |
      | wait_after_setup_s | 0                     |
      | target_rps         | 310                   |
      | ramp_s             | 10                    |
      | runtime_s          | 300                   |
      | loss_rate_access   | 0.0                   |
      | loss_rate_refresh  | 0.0                   |
      | control_timeout_s  | 900                   |
    # Testaspekt TA_A_26488_01: sicherstellen, dass die Verbindungen den HTTP-Lastlauf überstanden haben.
    Und sind mindestens 301 WebSocket Verbindungen offen
    # Cleanup: WebSocket-Ressourcen unmittelbar nach dem Lastlauf freigeben.
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    # Auswertung: Prometheus-Fenster für Lastlauf, Collector-Verzögerung und Puffer berechnen.
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{300 + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    # Auswertung: auf den Export der Telemetrie warten.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Reporting: Diagrammprofil für den PEP ohne ASL anhängen.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pep_ohne_asl" mit Titelpräfix "PEP ohne ASL" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26486-01_01 und TA_A_26488_01: PEP-Durchsatzrate unter kombinierter HTTP-/WebSocket-Last prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.pep}", Fenster "${prometheusWindowS}" Sekunden und Divisor "300" Sekunden die Rate >= "300" pro Sekunde ist
    # Testaspekt TA_A_26486-01_01: kombinierte PEP-/Well-known-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.pepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist

  @A_26486-01
  @A_26488
  @TA_A_26486-01_01
  @TA_A_26488_01
  @websocket
  Szenario: PEP HTTP Proxy - Performance mit ASL
    # Voraussetzung derzeit: ASL ist vor dem Lauf manuell aktiviert.
    # Der fachliche Lastpfad bleibt /hellozeta; zusätzlich werden interne /ASL-Spans auf Latenz geprüft.
    # TA_A_26488_01 prüft mehr als 300 WebSocket-Verbindungen und mehr als 300 Requests/s am selben
    # PEP-Pod. Die WebSockets werden deshalb vor dem HTTP-Lastlauf aufgebaut und bleiben währenddessen offen.
    # Lastaufbau: 320 Verbindungen anfordern, um die Kapazität von mehr als 300 zu testen.
    Wenn 320 WebSocket Verbindungen zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    # Testaspekt TA_A_26488_01: Mindestanzahl direkt nach dem Aufbau verifizieren.
    Dann sind mindestens 301 WebSocket Verbindungen offen
    # Lastaufbau: gültiger hellozeta-Workload mit 310 Requests/s und ASL.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta        |
      | threads            | 50               |
      | instances          | 300              |
      | registered_full    | 1.0              |
      | setup_semaphore    | 128              |
      | wait_after_setup_s | 0                |
      | target_rps         | 310              |
      | ramp_s             | 10               |
      | runtime_s          | 60               |
      | loss_rate_access   | 0.0              |
      | loss_rate_refresh  | 0.0              |
      | control_timeout_s  | 600              |
    # Testaspekt TA_A_26488_01: sicherstellen, dass die Verbindungen den HTTP-Lastlauf überstanden haben.
    Und sind mindestens 301 WebSocket Verbindungen offen
    # Cleanup: WebSocket-Ressourcen unmittelbar nach dem Lastlauf freigeben.
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    # Auswertung: Prometheus-Fenster für den 60-sekündigen ASL-Lastlauf berechnen.
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{60 + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    # Auswertung: auf die verzögerte Telemetrie-Übertragung warten.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Reporting: ASL-, PEP- und Well-known-Metriken als Performance-Diagramm dokumentieren.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pep_asl" mit Titelpräfix "PEP mit ASL" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26486-01_01 und TA_A_26488_01: ASL-Durchsatz unter kombinierter HTTP-/WebSocket-Last prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "60" Sekunden die Rate >= "300" pro Sekunde ist
    # Testaspekt TA_A_26486-01_01: kombinierte ASL-/PEP-/Well-known-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekt TA_A_26486-01_01: Latenzgrenzen für ASL, PEP und Well-known prüfen.
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                        | span                                  | avg | p90 | p95 | p99  |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.asl}       | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.pep}       | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy} | ${telemetry.span.httpProxy.wellKnown} | 7.5 | 10  | 15  | 100  |

  @A_26489-01
  @A_26491
  @TA_A_26489-01_01
  @TA_A_26491_01
  Szenario: PDP Authorization Server - Last und Performance
    # threads = parallele Dispenser-Worker; instances = Pool registrierter Identitäten.
    # Die Worker rotieren im Round-Robin über den Instanz-Pool, sodass jede Instanz
    # im Lauf mehrfach bedient wird (siehe Werte in der Tabelle unten).
    # Der Load Dispenser registriert und authentisiert die Instanzen vor dem Messlauf einmalig.
    # Im Lastlauf wird vor jedem Turn das Refresh Token verworfen (loss_rate_refresh = 1.0),
    # sodass der nächste Turn wieder den vollständigen nonce- + token-Flow auslöst.
    # TARGET_RPS bezieht sich auf einen Dispenser-Turn; TA_A_26491_01 wird daher über
    # die kombinierte Prometheus-Rate nonce + token und nicht über die rohe Anzahl Dispenser-Turns bewertet.
    # Testaspekt TA_A_26491_01: Summe nonce + token >= 300 req/s über alle PDP-Endpunkte.
    # Lastaufbau: verworfene Refresh-Tokens erzwingen wiederholte nonce- und token-Flows.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | tokens           |
      | threads            | 50               |
      | instances          | 160              |
      | registered_full    | 1.0              |
      | setup_semaphore    | 128              |
      | wait_after_setup_s | 0                |
      | target_rps         | 160              |
      | ramp_s             | 10               |
      | runtime_s          | 300              |
      | loss_rate_access   | 0.0              |
      | loss_rate_refresh  | 1.0              |
      | control_timeout_s  | 600              |
    # Auswertung: Prometheus-Fenster für den 300-sekündigen PDP-Lastlauf berechnen.
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{300 + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    # Auswertung: auf den Export der PDP-Telemetrie warten.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Testaspekt TA_A_26491_01: nach dem Lastlauf festen Collector-Delay abwarten; das größere Fenster enthält
    # auch verzögert exportierte Span-Metriken, die Rate bleibt aber auf die eigentliche Lastdauer normiert
    # Reporting: Nonce- und Token-Metriken als PDP-Performance-Diagramm dokumentieren.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp" mit Titelpräfix "PDP" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26491_01: kombinierte Nonce- und Token-Rate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "300" Sekunden die kombinierte Rate >= "300" pro Sekunde ist
    # Testaspekt TA_A_26491_01: PDP-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekt TA_A_26489-01_01: Token- und Nonce-Latenzgrenzen prüfen.
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                        | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce} | 33  | 50  | 75  | 500  |

  @A_26489-01
  @TA_A_26489-01_01
  Szenario: PDP Authorization Server - Refresh-Token-Latenz
    # Der Dispenser initialisiert alle Clients und verwirft im Messlauf vor jedem Turn das Access Token
    # (loss_rate_access = 1.0). Der Refresh Token bleibt erhalten, sodass jeder Turn nach dem ersten
    # vollständigen Token-Austausch gezielt einen Refresh-Token-Flow (grant_type=refresh_token) auslöst.
    # instances bilden den Pool registrierter Identitäten, über den die threads im Round-Robin
    # rotieren: jede Instanz wird im Lauf viele Male bedient, sodass ab der zweiten Iteration
    # überwiegend Refresh-Last statt initialer voller Token-Austausche entsteht.
    # Lastaufbau: Access-Token-Verlust erzwingt Refresh-Token-Verwendung.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | tokens           |
      | threads            | 50               |
      | instances          | 180              |
      | registered_full    | 1.0              |
      | setup_semaphore    | 128              |
      | wait_after_setup_s | 0                |
      | target_rps         | 180              |
      | ramp_s             | 10               |
      | runtime_s          | 60               |
      | loss_rate_access   | 1.0              |
      | loss_rate_refresh  | 0.0              |
      | control_timeout_s  | 600              |
    # Auswertung: Zeitfenster für den Refresh-Token-Lastlauf berechnen.
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{60 + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    # Auswertung: auf den Export der Refresh-Token-Spans warten.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Reporting: Refresh-Token-Latenzdiagramm anhängen.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp" mit Titelpräfix "PDP Refresh-Token-Latenz" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "60" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26489-01_01: Refresh-Token-Latenzgrenzen prüfen.
    Und stelle sicher, dass in Prometheus für Label "zeta_test_oauth_grant"="refresh", Fenster "${prometheusWindowS}" Sekunden Samples für folgende Latenzgrenzen in ms vorhanden sind
      | service                                  | span                              | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | TokenEndpoint.processGrantRequest | 75  | 100 | 150 | 1000 |

  @deployment_modification
  @A_26487
  @TA_A_26487_01
  Szenariogrundriss: PEP HTTP Proxy - horizontale Skalierbarkeit mit mindestens 75 Prozent zusätzlicher Leistung pro Pod
    # Feste Basis: 300 RPS mit einem Pod.
    # Für jede zusätzliche Replica werden mindestens weitere 225 RPS gefordert.
    # Skalierung: Zielrate und Parallelität gemäß der Replica-Anzahl berechnen.
    Und berechne und setze lokale Variable "targetRps" aus Basis "310" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und berechne und setze lokale Variable "pepConcurrency" aus Basis "310" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    # Skalierung: PEP auf die aktuelle Replica-Anzahl bringen.
    Und skaliere das Deployment "${zetaDeploymentConfig.pep.podName}" auf <replicas> Replikas
    # Lastaufbau: skalierte PEP-Instanz mit der berechneten Zielrate belasten.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta         |
      | threads            | 50                |
      | instances          | ${pepConcurrency} |
      | registered_full    | 1.0               |
      | setup_semaphore    | 128               |
      | wait_after_setup_s | 0                 |
      | target_rps         | ${targetRps}      |
      | ramp_s             | 10                |
      | runtime_s          | 60                |
      | loss_rate_access   | 0.0               |
      | loss_rate_refresh  | 0.0               |
      | control_timeout_s  | 900               |
    # Auswertung: Prometheus-Fenster berechnen und auf Telemetrie warten.
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{60 + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Reporting: PEP-Metriken je Replica-Anzahl dokumentieren.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pep_asl" mit Titelpräfix "PEP Skalierung <replicas> Pods" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26487_01: mindestens 75 Prozent zusätzliche PEP-Leistung je Pod prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "60" Sekunden die Rate >= "${targetRps}" pro Sekunde ist
    # Testaspekt TA_A_26487_01: skalierte PEP-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekt TA_A_26486-01_01: skalierte PEP-Latenzgrenzen prüfen.
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
    # Feste Basis: kombinierte PDP-Rate nonce + token >= 300/s mit einem Pod.
    # Für jede zusätzliche Replica werden mindestens weitere 225/s gefordert.
    # Skalierung: Zielrate, kombinierte Erwartungsrate und Parallelität berechnen.
    Und berechne und setze lokale Variable "targetRps" aus Basis "160" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und berechne und setze lokale Variable "expectedCombinedRps" aus Basis "300" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    Und berechne und setze lokale Variable "pdpConcurrency" aus Basis "160" und <replicas> Replikas mit 75 Prozent Zusatzleistung pro Pod
    # Skalierung: Authorization Server auf die aktuelle Replica-Anzahl bringen.
    Und skaliere das Deployment "${zetaDeploymentConfig.authserver.podName}" auf <replicas> Replikas
    # Lastaufbau: skalierte PDP-Instanz mit erzwungenen nonce- und token-Flows belasten.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | tokens            |
      | threads            | 50                |
      | instances          | ${pdpConcurrency} |
      | registered_full    | 1.0               |
      | setup_semaphore    | 128               |
      | wait_after_setup_s | 0                 |
      | target_rps         | ${targetRps}      |
      | ramp_s             | 10                |
      | runtime_s          | 60                |
      | loss_rate_access   | 0.0               |
      | loss_rate_refresh  | 1.0               |
      | control_timeout_s  | 900               |
    # Auswertung: Prometheus-Fenster berechnen und auf Telemetrie warten.
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{60 + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Reporting: PDP-Metriken je Replica-Anzahl dokumentieren.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp" mit Titelpräfix "PDP Skalierung <replicas> Pods" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26490_01: kombinierte PDP-Leistung je zusätzlichem Pod prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "60" Sekunden die kombinierte Rate >= "${expectedCombinedRps}" pro Sekunde ist
    # Testaspekt TA_A_26490_01: skalierte PDP-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekt TA_A_26489-01_01: skalierte PDP-Latenzgrenzen prüfen.
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
  @A_26489-01
  @A_26491
  @TA_A_26486-01_01
  @TA_A_26488_01
  @TA_A_26489-01_01
  @TA_A_26491_01
  @websocket
  Szenario: PDP und PEP kombiniert - Realistischer Langzeittest mit Client-Rotation
    # Kombiniertes PEP+PDP-Lastszenario mit Client-Rotation.
    # 70000 Instanzen werden angelegt; 180 Threads rotieren im Round-Robin reihum über alle
    # 70000 Instanzen, sodass die Instanzen im Laufe des Tests nach und nach bedient werden.
    # Das erste /hellozeta auf einer noch nicht initialisierten Instanz löst Discovery-, Nonce-,
    # Register- und Token-Schritte aus und erzeugt dadurch zusätzliche PDP- und Well-known-Last.
    # TA_A_26488_01 prüft mehr als 300 WebSocket-Verbindungen und mehr als 300 Requests/s am selben
    # PEP-Pod. Die WebSockets werden vor dem HTTP-Lastlauf aufgebaut und bleiben währenddessen offen.
    # Lastaufbau: 320 Verbindungen anfordern, um die Kapazität von mehr als 300 zu testen.
    Wenn 320 WebSocket Verbindungen zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    # Testaspekt TA_A_26488_01: Mindestanzahl direkt nach dem Aufbau verifizieren.
    Dann sind mindestens 301 WebSocket Verbindungen offen
    # Lastaufbau: kalte Clients mit Rotation über 70000 Instanzen und 310 Requests/s.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test               | hellozeta        |
      | threads            | 50               |
      | instances          | 70000            |
      | registered_full    | 0.0              |
      | setup_semaphore    | 128              |
      | wait_after_setup_s | 0                |
      | target_rps         | 310              |
      | ramp_s             | 10               |
      | runtime_s          | 300              |
      | loss_rate_access   | 0.0              |
      | loss_rate_refresh  | 0.0              |
      | control_timeout_s  | 1800             |
    # Testaspekt TA_A_26488_01: sicherstellen, dass die Verbindungen den HTTP-Lastlauf überstanden haben.
    Und sind mindestens 301 WebSocket Verbindungen offen
    # Cleanup: WebSocket-Ressourcen unmittelbar nach dem Lastlauf freigeben.
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    # Auswertung: Fenster für die 120-sekündige kombinierte Last berechnen.
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{120 + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    # Auswertung: auf den Export der kombinierten Telemetrie warten.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Reporting: kombinierte PDP-/PEP-Metriken für kalte Clients dokumentieren.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp_kalte_clients,pep_asl" mit Titelpräfix "PDP und PEP kombiniert" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26491_01: kombinierte PDP-Rate nonce + register + token >= 300/s über alle kalten Clients
    # Testaspekt TA_A_26491_01: kombinierte PDP-Rate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "120" Sekunden die kombinierte Rate >= "300" pro Sekunde ist
    # Testaspekt TA_A_26486-01_01: PEP-Rate im kombinierten Client-Rotationslauf prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "120" Sekunden die Rate >= "300" pro Sekunde ist
    # Testaspekt TA_A_26486-01_01: kombinierte PEP-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekt TA_A_26491_01: kombinierte PDP-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekte TA_A_26486-01_01 und TA_A_26489-01_01: PEP-, PDP- und Well-known-Latenzen prüfen.
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                                     | avg | p90 | p95 | p99  |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.asl}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.pep}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.wellKnown}                    | 7.5 | 10  | 15  | 100  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce}              | 33  | 50  | 75  | 500  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.clientRegistration} | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token}              | 75  | 100 | 150 | 1000 |
    # PDP /refresh: zeta_test_oauth_grant wird auf TokenEndpoint.processGrantRequest gesetzt (kc.details.grant_type ist dort als spanevent)
    Und falls in Prometheus für Label "zeta_test_oauth_grant"="refresh", Fenster "${prometheusWindowS}" Sekunden Samples vorhanden sind, gelten folgende Latenzgrenzen in ms
      | service                                  | span                              | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | TokenEndpoint.processGrantRequest | 75  | 100 | 150 | 1000 |

  @A_26486-01
  @A_26488
  @A_26489-01
  @A_26491
  @TA_A_26486-01_01
  @TA_A_26488_01
  @TA_A_26489-01_01
  @TA_A_26491_01
  @websocket
  Szenario: PDP und PEP kombiniert - Zweiphasiger Langzeittest mit Client-Rotation
    # Die Setup-Phase des Load Dispensers registriert den konfigurierten Anteil der Instanzen.
    # Im Lastlauf erzeugen vorbereitete Instanzen Token- und Resource-Last; die übrigen Instanzen
    # durchlaufen beim ersten fachlichen Zugriff den vollständigen Register-, Token- und Resource-Flow.
    # TA_A_26488_01 prüft mehr als 300 WebSocket-Verbindungen und mehr als 300 Requests/s am selben
    # PEP-Pod. Die WebSockets werden vor dem HTTP-Lastlauf aufgebaut und bleiben über beide Phasen offen.
    # Phasenaufbau: Dauer der Setup-Phase und Zeitgrenzen der Messphase speichern.
    Und TGR setze lokale Variable "phase1DurationS" auf "1"
    # Lastaufbau: 320 Verbindungen anfordern, um die Kapazität von mehr als 300 zu testen.
    Wenn 320 WebSocket Verbindungen zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    # Testaspekt TA_A_26488_01: Mindestanzahl direkt nach dem Aufbau verifizieren.
    Dann sind mindestens 301 WebSocket Verbindungen offen
    # Lastaufbau: zweiphasigen Kaltstart-/Betriebslauf mit 70000 Client-Instanzen ausführen.
    Wenn der Load Dispenser mit folgender Konfiguration ausgeführt wird
      | test                       | hellozeta        |
      | threads                    | 50               |
      | instances                  | 70000            |
      | registered_full            | 0.95             |
      | setup_semaphore            | 128              |
      | wait_after_setup_s         | 0                |
      | target_rps                 | 310              |
      | ramp_s                     | 10               |
      | runtime_s                  | 300              |
      | loss_rate_access           | 0.0              |
      | loss_rate_refresh          | 0.0              |
      | control_timeout_s          | 3600             |
      | setup_duration_target_var  | phase1DurationS  |
      | run_start_epoch_target_var | PHASE2_START     |
      | run_end_epoch_target_var   | PHASE2_END       |

    # Auswertung: die Phase-2-Zeitgrenzen und das gemeinsame Prometheus-Fenster berechnen.
    Und TGR setze lokale Variable "PHASE2_START_MICROS" auf "!{${PHASE2_START} * 1000000}"
    Und TGR setze lokale Variable "PHASE2_END_MICROS" auf "!{(${PHASE2_END} + 1) * 1000000 - 1}"
    Und TGR setze lokale Variable "combinedDurationS" auf "!{${phase1DurationS} + 120}"
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${combinedDurationS} + ${testdata.telemetry_wait_seconds} + ${testdata.prometheus_window_buffer_seconds}}"
    # Testaspekt TA_A_26488_01: sicherstellen, dass die Verbindungen beide Lastphasen überstanden haben.
    Und sind mindestens 301 WebSocket Verbindungen offen
    # Cleanup: WebSocket-Ressourcen unmittelbar nach dem Lastlauf freigeben.
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    # Auswertung: auf den Export der beiden Lastphasen warten.
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    # Testaspekte TA_A_26486-01_01 und TA_A_26489-01_01: Fehler- und Langläufer-Traces je Phase aus Jaeger abfragen.
    # Direkter Jaeger-Client (nicht über den Tiger-Proxy): der Proxy sendet beim Binary-Forwarding
    # unter Tiger 4.4 die Ziel-IP statt des Hostnamens als TLS-SNI, was der Achelos-Ingress mit
    # unrecognized_name(112) ablehnt und die Anfrage ohne Timeout hängen lässt.
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
    # Reporting: kombinierte PDP-/PEP-Metriken und zweiphasige Traces dokumentieren.
    Und hänge Prometheus-Diagrammprofil "performance" für Ziele "pdp_kalte_clients,pep_asl" mit Titelpräfix "PDP und PEP kombiniert zweiphasig" aus YAML "tiger/prometheusCharts.yaml", Fenster "${prometheusWindowS}" Sekunden, Rollup "120" Sekunden und Schritt "5" Sekunden an
    # Testaspekt TA_A_26491_01: kombinierte PDP-Rate nonce + register + token über Registrierungs- und Betriebsphase
    # Testaspekt TA_A_26491_01: kombinierte PDP-Rate über beide Phasen prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden und Divisor "${combinedDurationS}" Sekunden die kombinierte Rate >= "300" pro Sekunde ist
    # Testaspekt TA_A_26486-01_01: PEP-Rate in der zweiphasigen Messung prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Span "${telemetry.span.httpProxy.asl}", Fenster "${prometheusWindowS}" Sekunden und Divisor "120" Sekunden die Rate >= "300" pro Sekunde ist
    # Testaspekt TA_A_26486-01_01: kombinierte PEP-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.httpProxy}", Spans "${telemetry.spanGroup.httpProxy.aslPepAndWellKnown}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekt TA_A_26491_01: kombinierte PDP-Fehlerrate prüfen.
    Und stelle sicher, dass in Prometheus für Service "${telemetry.service.authorizationServer}", Spans "${telemetry.spanGroup.authorizationServer.nonceRegisterAndToken}", Fenster "${prometheusWindowS}" Sekunden die kombinierte Fehlerrate <= "1.0" Prozent ist
    # Testaspekte TA_A_26486-01_01 und TA_A_26489-01_01: Latenzgrenzen über beide Phasen prüfen.
    Und stelle sicher, dass in Prometheus im Fenster "${prometheusWindowS}" Sekunden folgende Latenzgrenzen in ms eingehalten werden
      | service                                  | span                                                     | avg | p90 | p95 | p99  |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.asl}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.pep}                          | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.httpProxy}           | ${telemetry.span.httpProxy.wellKnown}                    | 7.5 | 10  | 15  | 100  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.nonce}              | 33  | 50  | 75  | 500  |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.clientRegistration} | 75  | 100 | 150 | 1000 |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token}              | 75  | 100 | 150 | 1000 |
    # PDP /refresh: zeta_test_oauth_grant wird auf TokenEndpoint.processGrantRequest gesetzt (kc.details.grant_type ist dort als spanevent)
    Und falls in Prometheus für Label "zeta_test_oauth_grant"="refresh", Fenster "${prometheusWindowS}" Sekunden Samples vorhanden sind, gelten folgende Latenzgrenzen in ms
      | service                                  | span                              | avg | p90 | p95 | p99  |
      | ${telemetry.service.authorizationServer} | TokenEndpoint.processGrantRequest | 75  | 100 | 150 | 1000 |
