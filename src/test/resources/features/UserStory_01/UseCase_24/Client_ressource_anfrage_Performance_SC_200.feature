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
Funktionalität: Client_ressource_anfrage_Performance_SC_200

  @no_proxy
  @dev
  @perf
  @A_26486-01
  @A_26488
  @TA_A_26486-01_01
  @TA_A_26488_01
  @websocket
#  @deployment_modification
  Szenario: PEP HTTP Proxy - Performance und Last ohne ASL
    # Voraussetzung derzeit: ASL ist vor dem Lauf manuell deaktiviert.
    # Gemessen wird HTTP-Last auf /hellozeta; die 300 WebSocket-Verbindungen bleiben parallel offen.
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "jdurationS" auf "300"
    Und TGR setze lokale Variable "jtargetRps" auf "310"
    Und TGR setze lokale Variable "prometheusCollectorDelayS" auf "90"
    Und TGR setze lokale Variable "prometheusInitBufferS" auf "180"
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${jdurationS} + 2*${prometheusCollectorDelayS} + ${prometheusInitBufferS}}"
#    Und deaktiviere den Additional Security Layer im Zeta Deployment
    Wenn 301 WebSocket Verbindungen zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    Dann sind mindestens 301 WebSocket Verbindungen offen
    Wenn ich JMeter mit dem Plan "perf/jmeter/parameterized-http-test.jmx" starte
      | -JLOAD_DRIVER_BASE_URL         | https://${zeta_base_url}                               |
      | -JLOAD_PATH_TEMPLATE           | /load/{id}{proxyPath}                                  |
      | -JLOAD_INIT_PATH_TEMPLATE      | /loaddriver-api/{id}{proxyPath}                        |
      | -JLOAD_PROXY_PATH              | /hellozeta                                             |
      | -JLOAD_INSTANCE_COUNT          | 300                                                    |
      | -JLOAD_CREATE_BATCH_SIZE       | 100                                                    |
      | -JLOAD_ONE_INSTANCE_PER_THREAD | true                                                   |
      | -JLOAD_AUTO_INIT               | true                                                   |
      | -JLOAD_INIT_FLOW               | authenticate                                           |
      | -JLOAD_WAIT_READY              | true                                                   |
      | -JLOAD_READY_TIMEOUT_S         | 180                                                    |
      | -JLOAD_PRECHECK_ENABLED        | false                                                  |
      | -JLOAD_DELETE_BEFORE_CREATE    | true                                                   |
      | -JLOAD_DELETE_AFTER_TEST       | true                                                   |
      | -JLOAD_CREATE_BODY_INLINE      | true                                                   |
      | -JLOAD_FACHDIENST_URL          | https://${zeta_base_url}/pep/achelos_testfachdienst/   |
      | -JLOAD_SMCB_KEYSTORE_MANIFEST  | ${testCertificates.dir}/manifest/keystore-manifest.tsv |
      | -JHTTP_METHOD                  | GET                                                    |
      | -JEXPECTED_STATUS              | 200                                                    |
      | -JDURATION_S                   | ${jdurationS}                                          |
      | -JRAMP_S                       | 10                                                     |
      | -JTHREADS                      | 300                                                    |
      | -JTARGET_RPS                   | ${jtargetRps}                                          |
      | -JJITTER_RANGE_MS              | 30                                                     |
      | -JWARMUP_S                     | 0                                                      |
      | -JWARMUP_THREADS               | 0                                                      |
      | -l                             | out/pep-performance-load-driver.jtl                    |
      | -f                             |                                                        |

    Dann erstelle die JMeter-Zusammenfassung aus "out/pep-performance-load-driver.jtl" nach "out/pep-performance-load-driver-summary.csv"
    Dann stelle sicher, dass im JMeter-Summary "out/pep-performance-load-driver-summary.csv" das Label "ZetaGuard Request" rps > ${jtargetRps}
    Und warte "${prometheusCollectorDelayS}" Sekunden
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden und Divisor "${jdurationS}" Sekunden die Rate >= 300 pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden die Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 7.5 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 10 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 15 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 100 ms ist
    Und sind mindestens 301 WebSocket Verbindungen offen
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
#    Und aktiviere den Additional Security Layer im Zeta Deployment

  @no_proxy
  @perf
  @A_26486
  @A_26488
  @TA_A_26486-01_01
  @TA_A_26488_01
  @websocket
  Szenario: PEP HTTP Proxy - Performance mit ASL
    # Voraussetzung derzeit: ASL ist vor dem Lauf manuell aktiviert.
    # Der fachliche Lastpfad bleibt /hellozeta; zusätzlich werden interne /ASL-Spans auf Latenz geprüft.
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "jdurationS" auf "60"
    Und TGR setze lokale Variable "jtargetRps" auf "310"
    Und TGR setze lokale Variable "prometheusCollectorDelayS" auf "90"
    Und TGR setze lokale Variable "prometheusInitBufferS" auf "180"
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${jdurationS} + 2*${prometheusCollectorDelayS} + ${prometheusInitBufferS}}"
    Wenn 301 WebSocket Verbindungen zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    Dann sind mindestens 301 WebSocket Verbindungen offen
    Wenn ich JMeter mit dem Plan "perf/jmeter/parameterized-http-test.jmx" starte
      | -JLOAD_DRIVER_BASE_URL         | https://${zeta_base_url}                               |
      | -JLOAD_PATH_TEMPLATE           | /load/{id}{proxyPath}                                  |
      | -JLOAD_INIT_PATH_TEMPLATE      | /loaddriver-api/{id}{proxyPath}                        |
      | -JLOAD_PROXY_PATH              | /hellozeta                                             |
      | -JLOAD_INSTANCE_COUNT          | 300                                                    |
      | -JLOAD_CREATE_BATCH_SIZE       | 100                                                    |
      | -JLOAD_ONE_INSTANCE_PER_THREAD | true                                                   |
      | -JLOAD_AUTO_INIT               | true                                                   |
      | -JLOAD_INIT_FLOW               | authenticate                                           |
      | -JLOAD_WAIT_READY              | true                                                   |
      | -JLOAD_READY_TIMEOUT_S         | 180                                                    |
      | -JLOAD_PRECHECK_ENABLED        | false                                                  |
      | -JLOAD_DELETE_BEFORE_CREATE    | true                                                   |
      | -JLOAD_DELETE_AFTER_TEST       | true                                                   |
      | -JLOAD_CREATE_BODY_INLINE      | true                                                   |
      | -JLOAD_FACHDIENST_URL          | https://${zeta_base_url}/pep/achelos_testfachdienst/   |
      | -JLOAD_SMCB_KEYSTORE_MANIFEST  | ${testCertificates.dir}/manifest/keystore-manifest.tsv |
      | -JHTTP_METHOD                  | GET                                                    |
      | -JEXPECTED_STATUS              | 200                                                    |
      | -JDURATION_S                   | ${jdurationS}                                          |
      | -JRAMP_S                       | 10                                                     |
      | -JTHREADS                      | 300                                                    |
      | -JTARGET_RPS                   | ${jtargetRps}                                          |
      | -JJITTER_RANGE_MS              | 30                                                     |
      | -JWARMUP_S                     | 0                                                      |
      | -JWARMUP_THREADS               | 0                                                      |
      | -l                             | out/pep-performance-asl-load-driver.jtl                |
      | -f                             |                                                        |

    Dann erstelle die JMeter-Zusammenfassung aus "out/pep-performance-asl-load-driver.jtl" nach "out/pep-performance-asl-load-driver-summary.csv"
    Dann stelle sicher, dass im JMeter-Summary "out/pep-performance-asl-load-driver-summary.csv" das Label "ZetaGuard Request" rps > ${jtargetRps}
    Und warte "${prometheusCollectorDelayS}" Sekunden
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden und Divisor "${jdurationS}" Sekunden die Rate >= 300 pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden die Fehlerrate <= "1.0" Prozent ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 7.5 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 10 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 15 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 100 ms ist
    # /ASL wird hier nur auf Latenz geprüft; der fachliche Durchsatz wird weiterhin über /pep/ bewertet.
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/ASL/", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/ASL/", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/ASL/", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/ASL/", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist

    Und sind mindestens 301 WebSocket Verbindungen offen
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen

  @no_proxy
  @perf
  @A_26489-01
  @A_26491
  @TA_A_26489-01_01
  @TA_A_26491_01
  Szenario: PDP Authorization Server - Last und Performance
    # 150 Instanzen, je einem Thread fest zugeordnet (ONE_INSTANCE_PER_THREAD=true).
    # AUTO_INIT bereitet die Instanzen im Load Driver einmalig vor; zusätzlich ruft der konfigurierte
    # Init-Flow /authenticate pro Instanz vor dem Messlauf genau einmal auf.
    # Load-Loop: authenticate → removeAuth → authenticate → removeAuth → ...
    # removeAuth entfernt nur den Token, kein Keycloak-Reset → nächstes authenticate = nonce + token.
    # TARGET_RPS=150 bezieht sich auf den JMeter-Step "PDP Authenticate"; A_26491 wird daher über
    # die kombinierte Prometheus-Rate nonce + token und nicht über die rohe JMeter-Requestzahl bewertet.
    # AFO A_26491: Summe nonce + token >= 300 req/s über alle PDP-Endpunkte.
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "jdurationS" auf "60"
    Und TGR setze lokale Variable "jtargetRps" auf "160"
    Und TGR setze lokale Variable "prometheusCollectorDelayS" auf "90"
    Und TGR setze lokale Variable "prometheusInitBufferS" auf "180"
    Wenn ich JMeter mit dem Plan "perf/jmeter/pdp-auth-cycle-test.jmx" starte
      | -JLOAD_DRIVER_BASE_URL         | https://${zeta_base_url}                               |
      | -JLOAD_PATH_TEMPLATE           | /loaddriver-api/{id}{proxyPath}                        |
      | -JLOAD_INIT_PATH_TEMPLATE      | /loaddriver-api/{id}{proxyPath}                        |
      | -JLOAD_PROXY_PATH              | /authenticate                                          |
      | -JLOAD_INSTANCE_COUNT          | 160                                                    |
      | -JLOAD_CREATE_BATCH_SIZE       | 100                                                    |
      | -JLOAD_ONE_INSTANCE_PER_THREAD | true                                                   |
      | -JLOAD_AUTO_INIT               | true                                                   |
      | -JLOAD_INIT_FLOW               | authenticate                                           |
      | -JLOAD_WAIT_READY              | true                                                   |
      | -JLOAD_READY_TIMEOUT_S         | 180                                                    |
      | -JLOAD_PRECHECK_ENABLED        | false                                                  |
      | -JLOAD_DELETE_BEFORE_CREATE    | true                                                   |
      | -JLOAD_DELETE_AFTER_TEST       | true                                                   |
      | -JLOAD_CREATE_BODY_INLINE      | true                                                   |
      | -JLOAD_FACHDIENST_URL          | https://${zeta_base_url}/pep/achelos_testfachdienst/   |
      | -JLOAD_SMCB_KEYSTORE_MANIFEST  | ${testCertificates.dir}/manifest/keystore-manifest.tsv |
      | -JDURATION_S                   | ${jdurationS}                                          |
      | -JRAMP_S                       | 10                                                     |
      | -JTHREADS                      | 160                                                    |
      | -JTARGET_RPS                   | ${jtargetRps}                                          |
      | -JJITTER_RANGE_MS              | 30                                                     |
      | -l                             | out/pdp-performance-load-driver.jtl                    |
      | -f                             |                                                        |

    Dann erstelle die JMeter-Zusammenfassung aus "out/pdp-performance-load-driver.jtl" nach "out/pdp-performance-load-driver-summary.csv"
    Dann stelle sicher, dass im JMeter-Summary "out/pdp-performance-load-driver-summary.csv" das Label "PDP Authenticate" rps > ${jtargetRps}
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${jdurationS} + 2*${prometheusCollectorDelayS} + ${prometheusInitBufferS}}"
    Und warte "${prometheusCollectorDelayS}" Sekunden
    # AFO A_26491: nach dem Lastlauf festen Collector-Delay abwarten; das größere Fenster enthält
    # auch verzögert exportierte Span-Metriken, die Rate bleibt aber auf die eigentliche Lastdauer normiert
    Und stelle sicher, dass in Prometheus für Service "keycloak", Spans "NonceProvider.createNonce,POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden und Divisor "${jdurationS}" Sekunden die kombinierte Rate >= 300 pro Sekunde ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden die Fehlerrate <= "1.0" Prozent ist
    # PDP /token: interne Keycloak-Latenz für Token-Exchange
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist
    # PDP /nonce: interne Keycloak-Latenz für Nonce-Erzeugung
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist

  @no_proxy
  @dev
  @perf
  @websocket
  Szenario: PDP und PEP kombiniert - Realistischer Langzeittest mit Client-Rotation
    # Kombiniertes PEP+PDP-Lastszenario mit kontinuierlichem Background-Reset.
    # 8000 Instanzen werden einmalig angelegt; AUTO_INIT=false, damit Discovery/Registrierung nicht
    # schon vor dem Messlauf verbraucht werden.
    # Während JMeter läuft, setzt der Load Driver die Instanzen im Hintergrund zurück
    # (SDK-State geleert, Instanzen bleiben erhalten).
    # Das nächste /hellozeta auf einer zurückgesetzten Instanz löst erneut Discovery-, Nonce-,
    # Register- und Token-Schritte aus und erzeugt dadurch zusätzliche PDP- und Well-known-Last.
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "jdurationS" auf "60"
    Und TGR setze lokale Variable "jtargetRps" auf "310"
    Und TGR setze lokale Variable "prometheusCollectorDelayS" auf "90"
    Und TGR setze lokale Variable "prometheusInitBufferS" auf "180"
    Und TGR setze lokale Variable "prometheusWindowS" auf "!{${jdurationS} + 2*${prometheusCollectorDelayS} + ${prometheusInitBufferS}}"
    Wenn 300 WebSocket Verbindungen zu "${paths.client.websocketBaseUrl}" aufgebaut werden
    Dann sind mindestens 300 WebSocket Verbindungen offen
    Wenn ich JMeter mit dem Plan "perf/jmeter/parameterized-http-test.jmx" starte
      | -JLOAD_DRIVER_BASE_URL         | https://${zeta_base_url}                               |
      | -JLOAD_PATH_TEMPLATE           | /load/{id}{proxyPath}                                  |
      | -JLOAD_INIT_PATH_TEMPLATE      | /loaddriver-api/{id}{proxyPath}                        |
      | -JLOAD_PROXY_PATH              | /hellozeta                                             |
      | -JLOAD_INSTANCE_COUNT          | 8000                                                   |
      | -JLOAD_CREATE_BATCH_SIZE       | 100                                                    |
      | -JLOAD_ONE_INSTANCE_PER_THREAD | false                                                  |
      | -JLOAD_AUTO_INIT               | false                                                  |
      | -JLOAD_WAIT_READY              | false                                                  |
      | -JLOAD_BACKGROUND_RESET        | true                                                   |
      | -JLOAD_PRECHECK_ENABLED        | false                                                  |
      | -JLOAD_DELETE_BEFORE_CREATE    | true                                                   |
      | -JLOAD_DELETE_AFTER_TEST       | true                                                   |
      | -JLOAD_CREATE_BODY_INLINE      | true                                                   |
      | -JLOAD_FACHDIENST_URL          | https://${zeta_base_url}/pep/achelos_testfachdienst/   |
      | -JLOAD_SMCB_KEYSTORE_MANIFEST  | ${testCertificates.dir}/manifest/keystore-manifest.tsv |
      | -JHTTP_METHOD                  | GET                                                    |
      | -JEXPECTED_STATUS              | 200                                                    |
      | -JDURATION_S                   | ${jdurationS}                                          |
      | -JRAMP_S                       | 10                                                     |
      | -JTHREADS                      | 300                                                    |
      | -JTARGET_RPS                   | ${jtargetRps}                                          |
      | -JJITTER_RANGE_MS              | 30                                                     |
      | -JWARMUP_S                     | 0                                                      |
      | -JWARMUP_THREADS               | 0                                                      |
      | -l                             | out/pep-performance-rotation-load-driver.jtl           |
      | -f                             |                                                        |

    Dann erstelle die JMeter-Zusammenfassung aus "out/pep-performance-rotation-load-driver.jtl" nach "out/pep-performance-rotation-load-driver-summary.csv"
    Dann stelle sicher, dass im JMeter-Summary "out/pep-performance-rotation-load-driver-summary.csv" das Label "ZetaGuard Request" errorRate <= 1
    Dann stelle sicher, dass im JMeter-Summary "out/pep-performance-rotation-load-driver-summary.csv" das Label "ZetaGuard Request" rps > ${jtargetRps}
    Dann stelle sicher, dass im JMeter-Summary "out/pep-performance-rotation-load-driver-summary.csv" das Label "ZetaGuard Request" p99_ms <= 1000
    Und warte "${prometheusCollectorDelayS}" Sekunden
    # AFO A_26491: kombinierte PDP-Rate nonce + token >= 300/s (Background-Reset erzwingt erneute PDP-Last bei jeder zurückgesetzten Instanz)
    Und stelle sicher, dass in Prometheus für Service "keycloak", Spans "NonceProvider.createNonce,POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden und Divisor "${jdurationS}" Sekunden die kombinierte Rate >= 300 pro Sekunde ist
    # PEP: interne nginx-Latenz
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/pep/", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist
    # Well-known: Discovery-Requests werden im Rotationsszenario durch re-initialisierte Clients erneut ausgelöst
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 7.5 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 10 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 15 ms ist
    Und stelle sicher, dass in Prometheus für Service "unknown_service:nginx", Span "/.well-known/", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 100 ms ist
    # PDP /nonce: interne Keycloak-Latenz für Nonce-Erzeugung
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "NonceProvider.createNonce", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist
    # PDP /register: Latenz für Client-Registrierung in Keycloak
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/clients-registrations/{provider}", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/clients-registrations/{provider}", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/clients-registrations/{provider}", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/clients-registrations/{provider}", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist
    # PDP /token: Latenz für Token-Exchange in Keycloak
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der avg-Wert <= 75 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der p90-Wert <= 100 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der p95-Wert <= 150 ms ist
    Und stelle sicher, dass in Prometheus für Service "keycloak", Span "POST /realms/{realm}/protocol/{protocol}/token", Fenster "${prometheusWindowS}" Sekunden der p99-Wert <= 1000 ms ist
    Und sind mindestens 300 WebSocket Verbindungen offen
    Und werden alle aufgebauten WebSocket Verbindungen geschlossen
    
