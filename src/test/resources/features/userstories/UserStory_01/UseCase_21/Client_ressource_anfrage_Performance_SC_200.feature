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

@UseCase_01_21
Funktionalität: Client_ressource_anfrage_Performance_SC_200

  Grundlage:
    Gegeben sei TGR zeige Banner "Funktionalität: Client_ressource_anfrage_Performance_SC_200"
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und TGR setze Anfrage Timeout auf 20 Sekunden

  @A_26486 @TA_A_26486_01
  @A_26488 @TA_A_26488_01
#  Szenario: PEP HTTP Proxy - Performance und Last
#    #TODO: BASE_URL needs to be set correctly as soon as it's known
#    #TODO: 300 Websockets needs to be connected. This is missing.
#    Wenn ich JMeter mit dem Plan "perf/jmeter/parameterized-http-test.jmx" starte
#      | -JBASE_URL   | http://localhost:9080/achelos_testfachdienst/hellozeta |
#      | -JDURATION_S | 30                                                     |
#      | -JRAMP_S     | 10                                                     |
#      | -JTHREADS    | 150                                                    |
#      | -JTARGET_RPS | 350                                                    |
#      | -JTHINK_MS   | 0                                                      |
#      | -l           | out/pep-performance.jtl                                |
#      | -f           |                                                        |
#
#    # JMeter-Ergebnisse
#    Dann erstelle die JMeter-Zusammenfassung aus "out/pep-performance.jtl" nach "out/pep-performance-summary.csv"
#
#    # Tiger Traffic Analyse (Ingress ↔ Egress correlation via X-Trace-Id)
#    Und analysiere Tiger Traffic aus "target/gates/ingress.tgr" und "target/gates/egress.tgr" nach "out/tiger-trace-analysis-pep.csv"
#
#    # A_26486, TA_A_26486_01 (PEP – geprüft & weitergeleitet ≤ 100 ms)
#    Und stelle sicher, dass in "out/tiger-trace-analysis-pep.csv" für die Spalte forward_ms der max-Wert <= 100 ms ist
#    # A_26488, TA_A_26488_01 (PEP – Last)
#    Dann stelle sicher, dass im JMeter-Summary "out/pep-performance-summary.csv" das Label "ZetaGuard Request" rps >= 300


  @A_26489 @TA_A_26489_01
  @A_26491 @TA_A_26491_01
  Szenario: PDP HTTP Proxy - Last und Performance
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    #TODO: BASE_URL for PDP needs to be set correctly as soon as it's known
    Wenn ich JMeter mit dem Plan "perf/jmeter/parameterized-http-test.jmx" starte
#      | -JBASE_URL        | http://localhost:9080/auth/realms/zeta-guard/protocol/openid-connect/token |
      | -JBASE_URL        | https://zeta.example.org/auth/realms/zeta-guard/protocol/openid-connect/token |
      | -JHTTP_METHOD     | POST                                                                           |
      | -JREQUEST_BODY    | src/test/resources/mocks/token_request                                         |
      | -JEXPECTED_STATUS | 200                                                                            |
      | -JCONTENT_TYPE    | application/x-www-form-urlencoded                                              |
      | -JHEADER1_NAME    | Host                                                                           |
      | -JHEADER1_VALUE   | zeta.example.org                                                              |
      | -JDURATION_S      | 60                                                                             |
      | -JRAMP_S          | 15                                                                             |
      | -JTHREADS         | 100                                                                            |
      | -JTARGET_RPS      | 300                                                                            |
      | -JWARMUP_S        | 10                                                                             |
      | -JWARMUP_THREADS  | 10                                                                             |
      | -l                | out/pdp-performance.jtl                                                        |
      | -f                |                                                                                |

    # JMeter-Ergebnisse
    Dann erstelle die JMeter-Zusammenfassung aus "out/pdp-performance.jtl" nach "out/pdp-performance-summary.csv"

    # Tiger Traffic Analyse
#    Und analysiere Tiger E2E aus "target/gates/ingress.tgr" nach "out/tiger-e2e-analysis.csv"

    # A_26489, TA_A_26489_01 (PDP – bearbeitet & beantwortet ≤ 200 ms)
#    Und stelle sicher, dass in "out/tiger-e2e-analysis.csv" für die Spalte e2e_ms der max-Wert <= 200 ms ist
    Dann stelle sicher, dass im JMeter-Summary "out/pdp-performance-summary.csv" das Label "ZetaGuard Request" max_ms <= 200
    # A_26491, TA_A_26491_01 (PDP – Last)
    Dann stelle sicher, dass im JMeter-Summary "out/pdp-performance-summary.csv" das Label "ZetaGuard Request" rps >= 300



