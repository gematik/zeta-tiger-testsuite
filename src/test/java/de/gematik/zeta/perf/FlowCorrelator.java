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

import de.gematik.zeta.perf.TigerTraceAnalyzer.FlowTiming;
import de.gematik.zeta.perf.TrafficMessageParser.TrafficMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Correlates ingress and egress traffic messages into end-to-end flow timings.
 */
@Slf4j
public class FlowCorrelator {

  /**
   * Correlates ingress and egress messages into flow timings and builds summary stats.
   *
   * @param ingressMessages parsed ingress messages
   * @param egressMessages  parsed egress messages
   * @return correlation result with flow timings and statistics
   */
  public CorrelationResult correlate(List<TrafficMessage> ingressMessages,
      List<TrafficMessage> egressMessages) {

    // Use atomic integers for lambda compatibility
    AtomicInteger ingressJMeterReq = new AtomicInteger();
    AtomicInteger ingressBackgroundReq = new AtomicInteger();
    AtomicInteger egressJMeterReq = new AtomicInteger();
    AtomicInteger egressBackgroundReq = new AtomicInteger();
    AtomicInteger inMissingResp = new AtomicInteger();
    AtomicInteger inRespBeforeReq = new AtomicInteger();
    AtomicInteger inDuplicateResp = new AtomicInteger();
    AtomicInteger egMissingResp = new AtomicInteger();
    AtomicInteger egRespBeforeReq = new AtomicInteger();
    AtomicInteger egDuplicateResp = new AtomicInteger();
    AtomicInteger nonMonotonicPairsSkipped = new AtomicInteger();

    // Create ingress pairs
    Map<String, GatePair> ingressPairs = createIngressPairs(
        ingressMessages,
        stats -> {
          ingressJMeterReq.set(stats.jmeterRequests);
          ingressBackgroundReq.set(stats.backgroundRequests);
          inMissingResp.set(stats.missingResponses);
          inRespBeforeReq.set(stats.responsesBeforeRequests);
          inDuplicateResp.set(stats.duplicateResponses);
        });

    // Create egress pairs
    Map<String, GatePair> egressPairs = createEgressPairs(
        egressMessages,
        stats -> {
          egressJMeterReq.set(stats.jmeterRequests);
          egressBackgroundReq.set(stats.backgroundRequests);
          egMissingResp.set(stats.missingResponses);
          egRespBeforeReq.set(stats.responsesBeforeRequests);
          egDuplicateResp.set(stats.duplicateResponses);
        });

    // Cross-correlate by Trace IDs
    Set<String> commonTraceIds = new HashSet<>(ingressPairs.keySet());
    commonTraceIds.retainAll(egressPairs.keySet());

    log.info("TraceId correlation: {} common pairs from {} ingress, {} egress",
        commonTraceIds.size(), ingressPairs.size(), egressPairs.size());

    // Create flow timings
    List<FlowTiming> flowTimings = new ArrayList<>();
    for (String traceId : commonTraceIds) {
      GatePair ingressPair = ingressPairs.get(traceId);
      GatePair egressPair = egressPairs.get(traceId);

      if (ingressPair == null || egressPair == null) {
        continue;
      }

      long t1 = ingressPair.request().timestampMs();
      long t2 = egressPair.request().timestampMs();
      long t3 = egressPair.response().timestampMs();
      long t4 = ingressPair.response().timestampMs();

      // Validate monotonic timing: t1 <= t2 <= t3 <= t4
      if (t2 < t1 || t3 < t2 || t4 < t3) {
        nonMonotonicPairsSkipped.incrementAndGet();
        log.debug("Non-monotonic timing for TraceId {}: t1={} t2={} t3={} t4={}",
            traceId, t1, t2, t3, t4);
        continue;
      }

      String path = !ingressPair.getPath().isEmpty() ?
          ingressPair.getPath() : egressPair.getPath();

      flowTimings.add(new FlowTiming(traceId, path, t1, t4, t2, t3));
    }

    // Create final statistics using atomic values
    CorrelationStats stats = new CorrelationStats(
        ingressJMeterReq.get(), ingressBackgroundReq.get(),
        egressJMeterReq.get(), egressBackgroundReq.get(),
        inMissingResp.get(), inRespBeforeReq.get(), inDuplicateResp.get(),
        egMissingResp.get(), egRespBeforeReq.get(), egDuplicateResp.get(),
        nonMonotonicPairsSkipped.get()
    );

    log.info("Correlation complete: {} flows created", flowTimings.size());
    return new CorrelationResult(flowTimings, stats);
  }

  /**
   * Builds request/response pairs for the ingress gate and reports stats.
   *
   * @param messages ingress messages
   * @param callback stats consumer
   * @return map of TraceId to paired request/response
   */
  private Map<String, GatePair> createIngressPairs(List<TrafficMessage> messages,
      StatsCallback callback) {
    return createGatePairs(messages, "Ingress", callback);
  }

  /**
   * Builds request/response pairs for the egress gate and reports stats.
   *
   * @param messages egress messages
   * @param callback stats consumer
   * @return map of TraceId to paired request/response
   */
  private Map<String, GatePair> createEgressPairs(List<TrafficMessage> messages,
      StatsCallback callback) {
    return createGatePairs(messages, "Egress", callback);
  }

  /**
   * Pairs requests with responses for a gate, collecting basic quality stats.
   *
   * @param messages gate messages (mixed request/response)
   * @param gateName name used in logs
   * @param callback stats consumer
   * @return map of TraceId to paired request/response
   */
  private Map<String, GatePair> createGatePairs(List<TrafficMessage> messages,
      String gateName,
      StatsCallback callback) {
    List<TrafficMessage> jmeterRequests = messages.stream()
        .filter(m -> m.isRequest() && m.isJMeterTraffic())
        .toList();

    List<TrafficMessage> backgroundRequests = messages.stream()
        .filter(m -> m.isRequest() && !m.isJMeterTraffic())
        .toList();

    int missingResponses = 0;
    int responsesBeforeRequests = 0;
    int duplicateResponses = 0;

    Map<String, GatePair> pairs = new LinkedHashMap<>();

    for (TrafficMessage request : jmeterRequests) {
      if (request.uuid() == null) {
        continue;
      }

      List<TrafficMessage> responses = messages.stream()
          .filter(m -> !m.isRequest() && request.uuid().equals(m.pairedRequestsUuid()))
          .toList();

      if (responses.isEmpty()) {
        missingResponses++;
        log.debug("{} missing response for request {} (TraceId: {})",
            gateName, request.uuid(), request.traceId());
        continue;
      }

      if (responses.size() > 1) {
        duplicateResponses++;
        log.debug("{} multiple responses ({}) for request {}",
            gateName, responses.size(), request.uuid());
      }

      TrafficMessage response = responses.get(0);
      if (response.timestampMs() < request.timestampMs()) {
        responsesBeforeRequests++;
        log.debug("{} response before request: {}", gateName, request.uuid());
        continue;
      }

      pairs.put(request.traceId(), new GatePair(request, response));
    }

    // Report statistics via callback
    callback.accept(new PairStats(jmeterRequests.size(), backgroundRequests.size(),
        missingResponses, responsesBeforeRequests, duplicateResponses));

    return pairs;
  }

  @FunctionalInterface
  private interface StatsCallback {

    void accept(PairStats stats);
  }

  private record PairStats(int jmeterRequests, int backgroundRequests, int missingResponses,
                           int responsesBeforeRequests, int duplicateResponses) {

  }

  private record GatePair(TrafficMessage request, TrafficMessage response) {

      /**
       * Returns the HTTP path of the paired request.
       *
       * @return request path (never null)
       */
      public String getPath() {
        return request.path();
      }
    }

  public record CorrelationResult(List<FlowTiming> flowTimings, CorrelationStats stats) {

  }
}