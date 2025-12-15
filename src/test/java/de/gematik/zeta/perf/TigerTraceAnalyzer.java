/*-
 * #%L
 * ZETA Testsuite
 * %%
 * (C) achelos GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.perf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Tiger Trace Analyzer.
 *
 * <p>Analyzes Tiger traffic dumps and correlates ingress/egress flows using UUID-based pairing and
 * X-Trace-Id correlation.
 */
@Slf4j
public class TigerTraceAnalyzer {

  private final TrafficMessageParser parser;
  private final FlowCorrelator correlator;

  /**
   * Constructor which sets both, the trafficMessageParser and the FlowCorrelator.
   */
  public TigerTraceAnalyzer() {
    this.parser = new TrafficMessageParser();
    this.correlator = new FlowCorrelator();
  }

  /**
   * Analyzes ingress/egress traffic and returns correlated flows with stats.
   *
   * @param ingressFile ingress .tgr
   * @param egressFile  egress .tgr
   * @return analysis result with counts, flows, and issues
   * @throws IOException on parse errors
   */
  public AnalysisResult analyze(Path ingressFile, Path egressFile) throws IOException {
    log.info("Starting Tiger trace analysis: ingress={}, egress={}", ingressFile, egressFile);

    var ingressMessages = parser.parseFile(ingressFile, "ingress");
    var egressMessages = parser.parseFile(egressFile, "egress");

    var correlation = correlator.correlate(ingressMessages, egressMessages);

    return new AnalysisResult(
        ingressMessages.size(),
        egressMessages.size(),
        correlation.flowTimings(),
        correlation.stats()
    );
  }

  /**
   * Runs analysis and writes the result as CSV.
   *
   * @param ingressFile ingress .tgr
   * @param egressFile  egress .tgr
   * @param outputFile  target CSV
   * @throws IOException on I/O errors
   */
  public void analyzeAndWriteCsv(Path ingressFile, Path egressFile, Path outputFile)
      throws IOException {
    var result = analyze(ingressFile, egressFile);
    CsvUtils.writeFlowTimings(result.flowTimings(), outputFile);
    logAnalysisSummary(result);
  }

  /**
   * Analyzes end-to-end timing within a single ingress file. Pairs requests with responses based on
   * TraceId within the same file.
   *
   * @param ingressFile ingress .tgr file
   * @param outputFile  target CSV
   * @throws IOException on I/O errors
   */
  public void analyzeIngressE2EAndWriteCsv(Path ingressFile, Path outputFile) throws IOException {
    log.info("Starting ingress E2E analysis: {}", ingressFile);

    var messages = parser.parseFile(ingressFile, "ingress");
    var flowTimings = createIngressE2EFlowTimings(messages);

    CsvUtils.writeFlowTimings(flowTimings, outputFile);

    log.info("Ingress E2E analysis complete: {} flows from {} messages",
        flowTimings.size(), messages.size());
  }

  /**
   * Creates E2E flow timings from request/response pairs within ingress messages.
   */
  private List<FlowTiming> createIngressE2EFlowTimings(
      List<TrafficMessageParser.TrafficMessage> messages) {
    var flows = new ArrayList<FlowTiming>();
    // TODO: Convert comments to english.

    // 1) Kandidaten-Requests: nur echte JMeter-Requests (mit TraceId)
    var jmeterRequests = messages.stream()
        .filter(TrafficMessageParser.TrafficMessage::isJMeterTraffic) // isRequest && has traceId
        .toList();
    if (jmeterRequests.isEmpty()) {
      return flows;
    }

    // 2) Indexe über die GESAMTE Liste (nicht gefiltert), damit Responses gefunden werden
    var byTraceId = messages.stream()
        .filter(m -> m.traceId() != null && !m.traceId().isBlank())
        .collect(Collectors.groupingBy(TrafficMessageParser.TrafficMessage::traceId));

    // optional, falls Responses keine TraceId tragen: via pairedMessageUuid koppeln
    var byUuid = messages.stream()
        .collect(
            Collectors.toMap(TrafficMessageParser.TrafficMessage::uuid, m -> m, (a, b) -> a));

    for (var req : jmeterRequests) {
      TrafficMessageParser.TrafficMessage resp = null;

      // 2a) Primär: gleiche TraceId, erste Response NACH dem Request
      var sameTrace = byTraceId.get(req.traceId());
      if (sameTrace != null) {
        resp = sameTrace.stream()
            .filter(m -> !m.isRequest())
            .filter(m -> m.timestampMs() >= req.timestampMs())
            .findFirst()
            .orElse(null);
      }

      // 2b) Fallback: Response, deren pairedMessageUuid == req.uuid
      if (resp == null) {
        var requestUuid = req.uuid();
        resp = messages.stream()
            .filter(m -> !m.isRequest())
            .filter(m -> requestUuid.equals(m.pairedRequestsUuid()))
            .findFirst()
            .orElse(null);
      }

      if (resp != null) {
        long timestampReq = req.timestampMs();
        long timestampRes = resp.timestampMs();
        flows.add(new FlowTiming(
            req.traceId(),           // trace_id
            req.path(),              // path
            timestampReq, timestampRes,                 // e2e: request_ms -> response_ms
            timestampReq, timestampRes                  // ingress==egress in Ein-Datei-Analyse
        ));
      }
    }
    return flows;
  }

  // private helper
  private void logAnalysisSummary(AnalysisResult result) {
    log.info("Analysis complete: {} ingress, {} egress messages -> {} correlated flows",
        result.ingressMessageCount(),
        result.egressMessageCount(),
        result.flowTimings().size());

    if (result.stats().hasIssues()) {
      log.warn("Analysis issues detected: {}", result.stats().getSummary());
    }
  }

  /**
   * TODO: add javadoc.
   *
   * @param ingressMessageCount TODO.
   * @param egressMessageCount  TODO.
   * @param flowTimings         TODO.
   * @param stats               TODO.
   */
  public record AnalysisResult(int ingressMessageCount, int egressMessageCount,
                               List<FlowTiming> flowTimings, CorrelationStats stats) {

  }

  /**
   * TODO: add javadoc.
   *
   * @param traceId           TODO.
   * @param path              TODO.
   * @param ingressRequestMs  TODO.
   * @param ingressResponseMs TODO.
   * @param egressRequestMs   TODO.
   * @param egressResponseMs  TODO.
   */
  public record FlowTiming(String traceId, String path, long ingressRequestMs,
                           long ingressResponseMs, long egressRequestMs, long egressResponseMs) {

    /**
     * Returns end-to-end latency in ms.
     */
    public double getEndToEndMs() {
      return ingressResponseMs - ingressRequestMs;
    }

    /**
     * Returns backend service time in ms.
     */
    public double getServiceMs() {
      return egressResponseMs - egressRequestMs;
    }

    /**
     * Returns middleware overhead in ms.
     */
    public double getMiddlewareOverheadMs() {
      return getEndToEndMs() - getServiceMs();
    }

    /**
     * Returns forward time (ingress -> egress) in ms.
     */
    public double getForwardMs() {
      return egressRequestMs - ingressRequestMs;
    }

    /**
     * Returns return time (egress -> ingress) in ms.
     */
    public double getReturnMs() {
      return ingressResponseMs - egressResponseMs;
    }
  }
}
