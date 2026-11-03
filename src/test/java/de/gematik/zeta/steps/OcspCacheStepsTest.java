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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;
import org.springframework.test.util.ReflectionTestUtils;

/** Negative controls for certificate-bound OCSP requests and expiry observation windows. */
class OcspCacheStepsTest {
  private static final long NOW = 1800000000000L;

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 10, 11})
  void baselineMustBeObservedWithinTheReferenceExchange(int offset) throws Exception {
    var start = java.time.Instant.ofEpochMilli(NOW);
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var request = recordedRequest(id);
    request.addOrReplaceFacet(new de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet(
        start.plusSeconds(offset).atZone(java.time.ZoneOffset.UTC)));
    if (offset >= 0 && offset <= 10) {
      assertDoesNotThrow(() -> OcspCacheSteps.requireBaselineRequest(
          () -> List.of(request), "/ocsp", id, start, start.plusSeconds(10),
          System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos()));
    } else {
      assertThrows(TestAbortedException.class, () -> OcspCacheSteps.requireBaselineRequest(
          () -> List.of(request), "/ocsp", id, start, start.plusSeconds(10),
          System.nanoTime() + java.time.Duration.ofMillis(1).toNanos()));
    }
  }

  @Test
  void missingOrForeignOcspRequestDoesNotEstablishAMeasurementBaseline() throws Exception {
    var start = java.time.Instant.ofEpochMilli(NOW);
    var cert = fixtureCertificate("secp256r1");
    var other = fixtureCertificate("brainpoolP256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var foreign = recordedRequest(OcspRequestSteps.buildCertificateId(other, other));
    for (var messages : List.of(List.<de.gematik.rbellogger.data.RbelElement>of(), List.of(foreign))) {
      var failure = assertThrows(TestAbortedException.class,
          () -> OcspCacheSteps.requireBaselineRequest(() -> messages, "/ocsp", id, start,
              start.plusSeconds(10), System.nanoTime() + java.time.Duration.ofMillis(1).toNanos()));
      org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage()
          .contains("A testdriver reset does not establish an empty AS cache"));
    }
    assertThrows(AssertionError.class, () -> OcspCacheSteps.requireBaselineRequest(
        List::of, "/ocsp", id, start.plusSeconds(10), start, System.nanoTime()));
  }

  @Test
  void delayedBaselineIsAcceptedOnlyForTheOriginalCertificateAndExchange() throws Exception {
    var start = java.time.Instant.ofEpochMilli(NOW);
    var cert = fixtureCertificate("secp256r1");
    var other = fixtureCertificate("brainpoolP256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var foreign = recordedRequest(OcspRequestSteps.buildCertificateId(other, other));
    var outside = recordedRequest(id);
    var matching = recordedRequest(id);
    foreign.addOrReplaceFacet(new de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet(
        start.plusSeconds(5).atZone(java.time.ZoneOffset.UTC)));
    outside.addOrReplaceFacet(new de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet(
        start.plusSeconds(11).atZone(java.time.ZoneOffset.UTC)));
    matching.addOrReplaceFacet(new de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet(
        start.plusSeconds(5).atZone(java.time.ZoneOffset.UTC)));
    var reads = new AtomicInteger();
    OcspCacheSteps.requireBaselineRequest(() -> switch (reads.incrementAndGet()) {
      case 1 -> List.of(foreign);
      case 2 -> List.of(outside);
      default -> List.of(foreign, outside, matching);
    }, "/ocsp", id, start, start.plusSeconds(10),
        System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos());
    assertEquals(3, reads.get());
  }

  @Test
  void expiredBaselineDeadlineDoesNotRestartTheObservationBudget() throws Exception {
    var start = java.time.Instant.ofEpochMilli(NOW);
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var reads = new AtomicInteger();
    assertThrows(TestAbortedException.class, () -> OcspCacheSteps.requireBaselineRequest(() -> {
      reads.incrementAndGet();
      return List.of();
    }, "/ocsp", id, start, start.plusSeconds(10), System.nanoTime() - 1));
    assertEquals(0, reads.get());
  }

  @Test
  void baselineReadFailureIsNotReportedAsMissingTraffic() throws Exception {
    var start = java.time.Instant.ofEpochMilli(NOW);
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var failure = new AssertionError("Malformed captured OCSP request");
    assertSame(failure, assertThrows(AssertionError.class,
        () -> OcspCacheSteps.requireBaselineRequest(() -> {
          throw failure;
        }, "/ocsp", id, start, start.plusSeconds(10),
            System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos())));
  }

  @Test
  void interruptedBaselineWaitIsNotReportedAsMissingTraffic() throws Exception {
    var start = java.time.Instant.ofEpochMilli(NOW);
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    try {
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class, () -> OcspCacheSteps.requireBaselineRequest(
          List::of, "/ocsp", id, start, start.plusSeconds(10),
          System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos()));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void cacheHitMustCompleteNearExpiry() {
    var fill = java.time.Instant.ofEpochMilli(NOW);
    assertDoesNotThrow(() -> OcspCacheSteps.verifyHitWindow(fill, 30,
        fill.plusSeconds(21), fill.plusSeconds(29)));
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyHitWindow(fill, 30,
        fill.plusSeconds(1), fill.plusSeconds(2)));
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyHitWindow(fill, 30,
        fill.plusSeconds(29), fill.plusSeconds(30)));
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyHitWindow(fill, 30,
        fill.plusSeconds(25), fill.plusSeconds(24)));
  }

  @Test
  void passedPostExpiryTargetRetainsOnlyTheOriginalObservationBudget() {
    var now = java.time.Instant.now();
    var steps = new OcspCacheSteps();
    ReflectionTestUtils.setField(steps, "refreshSeconds", 30L);
    ReflectionTestUtils.setField(steps, "filled", now.minusSeconds(35));
    ReflectionTestUtils.setField(steps, "originalRefreshExpiry", now.minusSeconds(5));
    assertDoesNotThrow(() -> steps.waitForBoundary(2));

    ReflectionTestUtils.setField(steps, "filled", now.minusSeconds(41));
    ReflectionTestUtils.setField(steps, "originalRefreshExpiry", now.minusSeconds(11));
    assertThrows(AssertionError.class, () -> steps.waitForBoundary(2));

    ReflectionTestUtils.setField(steps, "filled", now.minusSeconds(25));
    ReflectionTestUtils.setField(steps, "originalRefreshExpiry", now.plusSeconds(5));
    assertThrows(AssertionError.class, () -> steps.waitForBoundary(-10));
  }

  @Test
  void renewalMustFollowBothExpirationsAndBelongToTheExchange() {
    var fill = java.time.Instant.ofEpochMilli(NOW);
    var expiry = fill.plusSeconds(30);
    assertDoesNotThrow(() -> OcspCacheSteps.verifyRenewalWindow(fill, 30, expiry,
        fill.plusSeconds(32), fill.plusSeconds(35), fill.plusSeconds(33), fill.plusSeconds(34)));
    for (int[] times : List.of(new int[] {29, 35, 33, 34}, new int[] {32, 35, 31, 34},
        new int[] {32, 35, 33, 36}, new int[] {32, 41, 33, 34},
        new int[] {32, 35, 34, 33})) {
      assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyRenewalWindow(fill, 30, expiry,
          fill.plusSeconds(times[0]), fill.plusSeconds(times[1]),
          fill.plusSeconds(times[2]), fill.plusSeconds(times[3])));
    }
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyRenewalWindow(fill, 30,
        fill.plusSeconds(34), fill.plusSeconds(32), fill.plusSeconds(35),
        fill.plusSeconds(33), fill.plusSeconds(34)));
  }

  @Test
  void additionalUnansweredOcspRequestsCannotLookLikeCacheHits() {
    var first = new de.gematik.rbellogger.data.RbelElement(new byte[0], null);
    var extra = new de.gematik.rbellogger.data.RbelElement(new byte[0], null);
    assertDoesNotThrow(() -> OcspCacheSteps.verifyRequestCount(List.of(first), first.getUuid(), 1));
    assertThrows(AssertionError.class,
        () -> OcspCacheSteps.verifyRequestCount(List.of(first, extra), first.getUuid(), 1));
    assertThrows(AssertionError.class,
        () -> OcspCacheSteps.verifyRequestCount(List.of(extra), first.getUuid(), 1));
    assertThrows(AssertionError.class,
        () -> OcspCacheSteps.verifyRequestCount(List.of(first), first.getUuid(), 2));
    assertDoesNotThrow(() -> OcspCacheSteps.verifyRequestCount(
        List.of(first, extra), first.getUuid(), 2));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 60000})
  void ocspResponseMustStayGoodAndValidBeyondTheWholeMeasurement(long nextUpdateOffset)
      throws Exception {
    var fill = java.time.Instant.ofEpochMilli(NOW);
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var nextUpdate = nextUpdateOffset == 0 ? 0 : NOW + nextUpdateOffset;
    assertDoesNotThrow(() -> OcspCacheSteps.verifyResponse(
        ocspResponse(id, null, NOW - 1000, nextUpdate), id, fill, fill.plusSeconds(40)));
    for (long[] validity : List.of(new long[] {NOW + 1000, nextUpdate},
        new long[] {NOW - 1000, NOW + 40000}, new long[] {NOW - 1000, NOW - 1000})) {
      assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyResponse(
          ocspResponse(id, null, validity[0], validity[1]), id, fill, fill.plusSeconds(40)));
    }
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyResponse(
        ocspResponse(id, new org.bouncycastle.cert.ocsp.UnknownStatus(), NOW - 1000, nextUpdate),
        id, fill, fill.plusSeconds(40)));
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyResponse(
        ocspResponse(id, new org.bouncycastle.cert.ocsp.RevokedStatus(new Date(NOW - 1000), 0),
            NOW - 1000, nextUpdate), id, fill, fill.plusSeconds(40)));
    var foreign = new org.bouncycastle.cert.ocsp.CertificateID(
        new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().build()
            .get(org.bouncycastle.cert.ocsp.CertificateID.HASH_SHA1),
        new org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(cert), BigInteger.TWO);
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyResponse(
        ocspResponse(foreign, null, NOW - 1000, nextUpdate), id, fill, fill.plusSeconds(40)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 40, 70})
  void missingNextUpdateUsesThisUpdateAndMustOutliveTheMeasurement(int windowSeconds)
      throws Exception {
    var received = java.time.Instant.ofEpochMilli(NOW);
    var validThrough = received.plusSeconds(windowSeconds);
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var boundary = validThrough.toEpochMilli() - 86400000;
    assertDoesNotThrow(() -> OcspCacheSteps.verifyResponse(
        ocspResponse(id, null, boundary + 1000, 0), id, received, validThrough));
    for (long thisUpdate : new long[] {boundary, boundary - 1000, NOW - 86400000}) {
      assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyResponse(
          ocspResponse(id, null, thisUpdate, 0), id, received, validThrough));
    }
  }

  @Test
  void requestsForAnotherIssuerCannotSatisfyTheCacheObservation() throws Exception {
    var cert = fixtureCertificate("secp256r1");
    var other = fixtureCertificate("brainpoolP256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var foreignId = OcspRequestSteps.buildCertificateId(other, other);
    var original = recordedRequest(id);
    var foreign = recordedRequest(foreignId);
    assertEquals(List.of(original), OcspCacheSteps.matchingRequests(
        List.of(original, foreign), "/ocsp", id));
    assertThrows(AssertionError.class, () -> OcspCacheSteps.verifyRequestCount(
        OcspCacheSteps.matchingRequests(List.of(original, foreign), "/ocsp", id),
        original.getUuid(), 2));
    var additional = recordedRequest(id);
    assertEquals(2, OcspCacheSteps.matchingRequests(
        List.of(original, additional, foreign), "/ocsp", id).size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/ocsp/", "/ecc-ocsp/", "/ocsp/health!"})
  void requestsWithoutExtractablePayloadDoNotAbortCacheObservation(String path) throws Exception {
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var unrelated = recordedHttpRequest("GET " + path + " HTTP/1.1\r\nHost: ocsp.test\r\n\r\n");
    var original = recordedRequest(id);
    var start = java.time.Instant.ofEpochMilli(NOW);
    original.addOrReplaceFacet(new de.gematik.rbellogger.facets.timing.RbelMessageTimingFacet(
        start.plusSeconds(5).atZone(java.time.ZoneOffset.UTC)));
    var pattern = "(?:/ecc-ocsp|/ocsp)(?:/.*)?";
    OcspCacheSteps.requireBaselineRequest(() -> List.of(unrelated, original), pattern, id,
        start, start.plusSeconds(10), System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos());
    var matching = OcspCacheSteps.matchingRequests(List.of(unrelated, original), pattern, id);
    assertEquals(List.of(original), matching);
    OcspCacheSteps.verifyRequestCount(matching, original.getUuid(), 1);
    var renewed = recordedRequest(id);
    OcspCacheSteps.verifyRequestCount(OcspCacheSteps.matchingRequests(
        List.of(unrelated, original, renewed), pattern, id), original.getUuid(), 2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST /ocsp HTTP/1.1\r\nHost: ocsp.test\r\n"
        + "Content-Length: 3\r\n\r\nbad", "GET /ocsp/tls HTTP/1.1\r\nHost: ocsp.test\r\n\r\n"})
  void malformedPayloadStillFailsCacheObservation(String request) throws Exception {
    var cert = fixtureCertificate("secp256r1");
    var id = OcspRequestSteps.buildCertificateId(cert, cert);
    var malformed = recordedHttpRequest(request);
    var failure = assertThrows(AssertionError.class, () -> OcspCacheSteps.matchingRequests(
        List.of(malformed), "(?:/ecc-ocsp|/ocsp)(?:/.*)?", id));
    assertEquals("Failed to parse OCSP request.", failure.getMessage());
  }

  private static de.gematik.rbellogger.data.RbelElement recordedHttpRequest(String request) {
    return de.gematik.rbellogger.RbelLogger.build().getRbelConverter().parseMessage(
        request.getBytes(StandardCharsets.US_ASCII),
        new de.gematik.rbellogger.data.RbelMessageMetadata());
  }

  private static de.gematik.rbellogger.data.RbelElement recordedRequest(
      org.bouncycastle.cert.ocsp.CertificateID id) throws Exception {
    var body = new org.bouncycastle.cert.ocsp.OCSPReqBuilder().addRequest(id).build().getEncoded();
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write(("POST /ocsp HTTP/1.1\r\nHost: ocsp.test\r\nContent-Type: application/ocsp-request\r\n"
        + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    bytes.write(body);
    return de.gematik.rbellogger.RbelLogger.build().getRbelConverter().parseMessage(
        bytes.toByteArray(), new de.gematik.rbellogger.data.RbelMessageMetadata());
  }

  private static byte[] ocspResponse(org.bouncycastle.cert.ocsp.CertificateID id,
      org.bouncycastle.cert.ocsp.CertificateStatus status, long thisUpdate, long nextUpdate)
      throws Exception {
    var provider = new BouncyCastleProvider();
    var generator = KeyPairGenerator.getInstance("EC", provider);
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var key = generator.generateKeyPair();
    var builder = new org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder(
        new org.bouncycastle.cert.ocsp.RespID(new X500Name("CN=Synthetic OCSP unit test")));
    builder.addResponse(id, status, new Date(thisUpdate),
        nextUpdate == 0 ? null : new Date(nextUpdate), null);
    var signed = builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
        .setProvider(provider).build(key.getPrivate()), null, new Date(NOW));
    return new org.bouncycastle.cert.ocsp.OCSPRespBuilder().build(0, signed).getEncoded();
  }

  private static java.security.cert.X509Certificate fixtureCertificate(String curve)
      throws Exception {
    var provider = new BouncyCastleProvider();
    var generator = KeyPairGenerator.getInstance("EC", provider);
    generator.initialize(new ECGenParameterSpec(curve));
    var key = generator.generateKeyPair();
    var name = new X500Name("CN=Synthetic SMCB unit test");
    var cert = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
        new Date(NOW - 1000), new Date(NOW + 86400000), name, key.getPublic())
        .build(new JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(provider).build(key.getPrivate()));
    return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
        .setProvider(provider).getCertificate(cert);
  }
}
