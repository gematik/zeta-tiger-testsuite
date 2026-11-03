/*
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

package de.gematik.zeta.steps;

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.model.CertificateMaterial;
import de.gematik.zeta.services.BoundedObservation;
import de.gematik.zeta.services.RecordedMessages;
import io.cucumber.java.de.Dann;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.opentest4j.TestAbortedException;

/** Correlates SDK exchanges and certificate-bound OCSP traffic with observed token lifetimes. */
public class OcspCacheSteps {
  private static final int MARGIN_SECONDS = 10;
  private static final Duration BASELINE_ARRIVAL_WAIT = Duration.ofSeconds(10);
  private static final Duration MAX_RESPONSE_AGE_WITHOUT_NEXT_UPDATE = Duration.ofHours(24);
  private final OcspRequestSteps ocsp = new OcspRequestSteps();
  private long refreshSeconds;
  private Instant originalRefreshExpiry;
  private Instant exchangeStart;
  private Instant exchangeEnd;
  private Instant filled;
  private String initialRequestId;
  private CertificateID certificateId;
  private boolean hitVerified;

  /** Records the selected SDK exchange interval from its correlated RBEL messages. */
  @Dann("merke den aktuellen SMC-B-Exchange")
  public void rememberExchange() {
    var selected = RbelMessageRetriever.getInstance().getCurrentRequest();
    var messages = RecordedMessages.snapshot();
    var reply = new TimingGlue().findResponseForRequest(messages, selected);
    require(reply != null, "Missing successful exchange response");
    exchangeStart = time(selected);
    exchangeEnd = TimingGlue.responseReceivedAt(messages, reply);
  }

  /**
   * Awaits a certificate-bound OCSP request transmitted within the reference token exchange.
   *
   * @param certificate observed SMC-B certificate as Base64 DER or PEM
   * @param issuer issuer certificate as Base64 DER or PEM
   * @throws Exception if certificate decoding or identifier creation fails, or waiting is interrupted
   * @throws TestAbortedException if no matching request is observed by the fixed arrival deadline
   */
  @Dann("prüfe die OCSP-Messbasis für Zertifikat {tigerResolvedString}"
      + " mit Ausstellerzertifikat {tigerResolvedString}")
  public void requireBaseline(String certificate, String issuer) throws Exception {
    require(exchangeStart != null && filled == null, "A fresh reference exchange is required");
    var leaf = CertificateMaterial.parseCertificate(certificate, "SMC-B certificate");
    var expected = OcspRequestSteps.buildCertificateId(
        leaf, CertificateMaterial.parseCertificate(issuer, "SMC-B issuer certificate"));
    TigerGlobalConfiguration.putValue("CC_OCSP_SERIAL",
        leaf.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT),
        de.gematik.test.tiger.common.config.ConfigurationValuePrecedence.TEST_CONTEXT);
    try {
      requireBaselineRequest(RecordedMessages::snapshot, config("testdata.ccOcsp.pathPattern"),
          expected, exchangeStart, exchangeEnd,
          System.nanoTime() + Duration.between(Instant.now(),
              exchangeEnd.plus(BASELINE_ARRIVAL_WAIT)).toNanos());
    } catch (TestAbortedException e) {
      ReportAttachments.addText("OCSP measurement prerequisite", e.getMessage());
      throw e;
    }
  }

  /**
   * Records the selected, certificate-bound successful OCSP exchange as the initial cache fill.
   *
   * @param lifetime observed refresh-token validity duration in seconds
   * @param expiry expiration claim of the original refresh token, as Unix seconds
   * @throws Exception if the values or recorded OCSP response cannot be decoded
   */
  @Dann("merke die aktuelle OCSP-Antwort als Cache-Beginn mit Refresh-Token-Laufzeit"
      + " {tigerResolvedString} Sekunden und Ablauf {tigerResolvedString}")
  public void rememberFill(String lifetime, String expiry) throws Exception {
    require(exchangeStart != null && filled == null, "A fresh reference exchange is required");
    refreshSeconds = Long.parseLong(lifetime);
    originalRefreshExpiry = Instant.ofEpochSecond(Long.parseLong(expiry));
    var selected = RbelMessageRetriever.getInstance().getCurrentRequest();
    var ids = OcspRequestSteps.extractCertificateIds(ocsp.extractOcspRequestBytes(selected).orElseThrow());
    require(ids.size() == 1, "Expected one certificate in the initial OCSP request");
    certificateId = ids.getFirst();
    initialRequestId = selected.getUuid();
    var messages = RecordedMessages.snapshot();
    var reply = new TimingGlue().findResponseForRequest(messages, selected);
    require(reply != null, "Initial OCSP request has no response");
    filled = TimingGlue.responseReceivedAt(messages, reply);
    require(!time(selected).isBefore(exchangeStart) && !filled.isAfter(exchangeEnd),
        "Initial OCSP exchange is outside the reference token exchange");
    verifyResponse(reply.findElement("$.body").orElseThrow().getRawContent(), certificateId, filled,
        filled.plusSeconds(refreshSeconds + MARGIN_SECONDS));
    require(matchingRequests(messages).size() == 1,
        "Expected one observed initial OCSP request for the SMC-B certificate");
  }

  /**
   * Waits against the original observed fill; positive offsets also include original RT expiry.
   *
   * @param offsetSeconds signed offset from the expected cache expiry, in seconds
   * @throws InterruptedException if waiting is interrupted
   */
  @Dann("warte bis {int} Sekunden relativ zum gemerkten OCSP-Cache-Ablauf")
  public void waitForBoundary(int offsetSeconds) throws InterruptedException {
    require(filled != null && (offsetSeconds == -MARGIN_SECONDS || offsetSeconds == 2),
        "Use the defined before-expiry or after-expiry boundary");
    var target = filled.plusSeconds(refreshSeconds + offsetSeconds);
    if (offsetSeconds > 0 && originalRefreshExpiry.plusSeconds(offsetSeconds).isAfter(target)) {
      target = originalRefreshExpiry.plusSeconds(offsetSeconds);
    }
    if (offsetSeconds < 0) {
      require(Instant.now().isBefore(target), "OCSP observation boundary was already missed");
    }
    var boundary = target;
    var deadline = offsetSeconds > 0
        ? filled.plusSeconds(refreshSeconds + MARGIN_SECONDS) : target.plusSeconds(2);
    BoundedObservation.awaitUntil(Instant::now, observed -> !observed.isBefore(boundary),
        System.nanoTime() + Duration.between(Instant.now(), deadline).toNanos(),
        Duration.ofMillis(250), "OCSP observation boundary was missed");
  }

  /** Requires a completed fresh exchange near expiry without any further matching OCSP request. */
  @Dann("wurde der SMC-B-OCSP-Cache kurz vor Ablauf ohne erneute OCSP-Anfrage verwendet")
  public void verifyHit() {
    require(filled != null, "Observe an initial OCSP cache fill first");
    verifyHitWindow(filled, refreshSeconds, exchangeStart, exchangeEnd);
    verifyRequestCount(matchingRequests(RecordedMessages.snapshot()), initialRequestId, 1);
    hitVerified = true;
  }

  /**
   * Requires the selected fresh OCSP exchange after the original refresh token and cache expired.
   *
   * @throws Exception if the recorded OCSP response cannot be decoded
   */
  @Dann("wurde der SMC-B-OCSP-Cache nach Refresh-Token-Ablauf durch die aktuelle OCSP-Anfrage erneuert")
  public void verifyRenewal() throws Exception {
    require(hitVerified, "Prove the pre-expiry cache hit first");
    var selected = RbelMessageRetriever.getInstance().getCurrentRequest();
    var messages = RecordedMessages.snapshot();
    var matching = matchingRequests(messages);
    verifyRequestCount(matching, initialRequestId, 2);
    var reply = new TimingGlue().findResponseForRequest(messages, selected);
    require(reply != null, "Renewed OCSP request has no response");
    var sent = time(selected);
    var received = TimingGlue.responseReceivedAt(messages, reply);
    verifyRenewalWindow(filled, refreshSeconds, originalRefreshExpiry,
        exchangeStart, exchangeEnd, sent, received);
    verifyResponse(reply.findElement("$.body").orElseThrow().getRawContent(), certificateId, received, received);
    require(matching.stream().anyMatch(message -> selected.getUuid().equals(message.getUuid())),
        "Selected renewal does not query the original SMC-B certificate");
  }

  /**
   * Awaits matching OCSP traffic whose transmission time is in the inclusive exchange interval.
   *
   * @param messages fresh captured-message snapshots
   * @param pathPattern regular expression selecting OCSP paths
   * @param expected certificate identifier to match
   * @param start original exchange start
   * @param end original exchange completion
   * @param deadline fixed observation deadline on the {@link System#nanoTime()} clock
   * @throws InterruptedException if polling is interrupted
   * @throws TestAbortedException if no matching request is observed before the deadline
   */
  static void requireBaselineRequest(Supplier<List<RbelElement>> messages, String pathPattern,
      CertificateID expected, Instant start, Instant end, long deadline) throws InterruptedException {
    require(!end.isBefore(start), "Invalid reference exchange interval");
    var missing = "Missing OCSP measurement prerequisite: no recorded request for"
        + " SMC-B certificate serial " + expected.getSerialNumber().toString(16)
        + " within the reference exchange [" + start + ", " + end + "]"
        + " before the capture-arrival deadline."
        + " The cache lifetime cannot be measured. A testdriver reset does not establish an empty"
        + " AS cache; the cause of the missing request is undetermined.";
    try {
      BoundedObservation.awaitUntil(messages,
          observed -> matchingRequests(observed, pathPattern, expected).stream()
              .anyMatch(message -> !time(message).isBefore(start) && !time(message).isAfter(end)),
          deadline, Duration.ofMillis(250), missing);
    } catch (BoundedObservation.ObservationTimeoutException e) {
      throw new TestAbortedException(missing, e);
    }
  }

  private List<RbelElement> matchingRequests(List<RbelElement> messages) {
    return matchingRequests(messages, config("testdata.ccOcsp.pathPattern"), certificateId);
  }

  static List<RbelElement> matchingRequests(List<RbelElement> messages, String pathPattern,
      CertificateID expected) {
    var parser = new OcspRequestSteps();
    return messages.stream().filter(message -> message.findRbelPathMembers("$.path").stream()
        .anyMatch(path -> path.getRawStringContent().matches(pathPattern)))
        .filter(message -> parser.extractOcspRequestBytes(message)
            .map(OcspRequestSteps::extractCertificateIds)
            .stream().flatMap(List::stream)
            .anyMatch(id -> OcspRequestSteps.certificateIdsMatch(id, expected)))
        .toList();
  }

  static void verifyRequestCount(List<RbelElement> matching, String initialId, int count) {
    require(matching.size() == count
        && matching.stream().anyMatch(message -> initialId.equals(message.getUuid())),
        "Unexpected OCSP request count for the SMC-B certificate: " + matching.size());
  }

  static void verifyHitWindow(Instant fill, long seconds, Instant sent, Instant received) {
    var expiry = fill.plusSeconds(seconds);
    require(!sent.isBefore(expiry.minusSeconds(MARGIN_SECONDS)) && sent.isBefore(expiry)
        && !received.isBefore(sent) && received.isBefore(expiry),
        "Cache-hit exchange must complete in the last ten seconds before expected expiry");
  }

  static void verifyRenewalWindow(Instant fill, long seconds, Instant refreshExpiry,
      Instant sent, Instant received, Instant ocspSent, Instant ocspReceived) {
    var expiry = fill.plusSeconds(seconds);
    require(!sent.isBefore(expiry) && !sent.isBefore(refreshExpiry)
        && !ocspSent.isBefore(sent) && !ocspReceived.isBefore(ocspSent)
        && !received.isBefore(ocspReceived)
        && !received.isAfter(expiry.plusSeconds(MARGIN_SECONDS)),
        "OCSP renewal must belong to a fresh exchange after expiry within ten seconds");
  }

  /**
   * Checks certificate-bound GOOD status and validity through the supplied instant.
   * Missing {@code nextUpdate} uses the reference AS maximum age of 24 hours from {@code thisUpdate}.
   *
   * @param bytes encoded OCSP response
   * @param expected expected certificate identifier
   * @param received response receipt time
   * @param validThrough last instant that the response must remain valid beyond
   * @throws Exception if the response cannot be decoded
   */
  static void verifyResponse(byte[] bytes, CertificateID expected, Instant received,
      Instant validThrough) throws Exception {
    var outer = new OCSPResp(bytes);
    require(outer.getStatus() == 0 && outer.getResponseObject() instanceof BasicOCSPResp,
        "Missing successful basic OCSP response");
    var response = (BasicOCSPResp) outer.getResponseObject();
    var matching = java.util.Arrays.stream(response.getResponses())
        .filter(item -> OcspRequestSteps.certificateIdsMatch(item.getCertID(), expected)).toList();
    require(matching.size() == 1, "Missing or ambiguous SMC-B OCSP response entry");
    var item = matching.getFirst();
    require(item.getCertStatus() == null && item.getThisUpdate() != null
        && !item.getThisUpdate().toInstant().isAfter(received),
        "OCSP response must be GOOD with thisUpdate no later than receipt");
    var expires = item.getNextUpdate() == null
        ? item.getThisUpdate().toInstant().plus(MAX_RESPONSE_AGE_WITHOUT_NEXT_UPDATE)
        : item.getNextUpdate().toInstant();
    require(expires.isAfter(validThrough),
        "OCSP response validity must extend beyond the observation window");
  }

  private static Instant time(RbelElement message) {
    return message.getFacet(RbelMessageTimingFacet.class).orElseThrow(
        () -> new AssertionError("Missing OCSP/exchange timestamp")).getTransmissionTime().toInstant();
  }

  private static String config(String key) {
    return TigerGlobalConfiguration.resolvePlaceholders(
        TigerGlobalConfiguration.readStringOptional(key).orElseThrow());
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
