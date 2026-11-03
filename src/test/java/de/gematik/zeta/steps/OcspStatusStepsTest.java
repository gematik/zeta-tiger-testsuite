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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import de.gematik.test.tiger.common.config.ConfigurationValuePrecedence;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Tests signed OCSP status isolation and restoration after interrupted scenarios. */
class OcspStatusStepsTest {
  private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

  /**
   * Rejects other statuses, unrelated certificates, invalid signatures and invalid time intervals.
   *
   * @param variant response mutation
   * @throws Exception if fixture generation fails
   */
  @ParameterizedTest
  @ValueSource(strings = {"good", "revoked", "unknown", "wrong_certificate", "bad_signature",
      "unauthorized_signer", "expired", "future", "future_revocation"})
  void validatesOnlyTheExpectedSignedStatus(String variant) throws Exception {
    var key = KeyPairGenerator.getInstance("EC").generateKeyPair();
    var certificate = certificate(key, BigInteger.ONE);
    var otherKey = KeyPairGenerator.getInstance("EC").generateKeyPair();
    var signer = variant.equals("unauthorized_signer") ? certificate(otherKey, BigInteger.TWO)
        : certificate;
    var requested = variant.equals("wrong_certificate") ? certificate(key, BigInteger.TWO)
        : certificate;
    CertificateStatus status = switch (variant) {
      case "revoked" -> new RevokedStatus(Date.from(NOW.minusSeconds(60)), 1);
      case "future_revocation" -> new RevokedStatus(Date.from(NOW.plusSeconds(60)), 1);
      case "unknown" -> new UnknownStatus();
      default -> null;
    };
    var builder = new JcaBasicOCSPRespBuilder(signer.getSubjectX500Principal());
    builder.addResponse(OcspRequestSteps.buildCertificateId(requested, certificate), status,
        Date.from(NOW.plusSeconds(variant.equals("future") ? 10 : -10)),
        Date.from(NOW.plusSeconds(variant.equals("expired") ? -1 : 60)), null);
    var signingKey = variant.equals("bad_signature") || variant.equals("unauthorized_signer")
        ? otherKey : key;
    var basic = builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
        .build(signingKey.getPrivate()),
        new org.bouncycastle.cert.X509CertificateHolder[] {
            new org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(signer)}, Date.from(NOW));
    var encoded = new OCSPRespBuilder().build(0, basic).getEncoded();
    var expected = variant.equals("revoked") || variant.equals("future_revocation")
        ? "revoked" : "good";
    if (variant.equals("good") || variant.equals("revoked")) {
      assertDoesNotThrow(() -> OcspRequestSteps.verifySignedStatus(
          encoded, certificate, certificate, expected, NOW));
      assertThrows(AssertionError.class, () -> OcspRequestSteps.verifySignedStatus(
          encoded, certificate, certificate, expected.equals("good") ? "revoked" : "good", NOW));
    } else {
      assertThrows(AssertionError.class, () -> OcspRequestSteps.verifySignedStatus(
          encoded, certificate, certificate, expected, NOW));
    }
  }

  /**
   * Restores the original serial list and clears cached status after partial preparation failure.
   *
   * @param conflict whether another actor changes the mock before restoration
   * @param contextPath configured mock context path
   * @param preparationFails whether the first configuration update fails
   * @throws Exception if the local service cannot start or JSON parsing fails
   */
  @ParameterizedTest
  @CsvSource({"false, '', true", "true, '', true", "false, '', false",
      "false, /mock, true", "true, /mock, true", "false, /mock, false",
      "true, /mock, false", "false, /nested/mock/, false"})
  void cleanupPreservesOriginalOrConcurrentConfiguration(
      boolean conflict, String contextPath, boolean preparationFails) throws Exception {
    var json = new ObjectMapper();
    var original = json.readTree("""
        {"mode":"always_good","goodProbability":0.9,"revokedProbability":0.1,
        "thisUpdateOffsetSeconds":-10,"nextUpdateOffsetSeconds":1800,
        "includeNextUpdate":true,"responseVariant":"normal","ocspAvailable":true,
        "crlAvailable":true,"revokedSerialNumbers":["123"]}
        """);
    var current = new AtomicReference<JsonNode>(original.deepCopy());
    var updates = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var prefix = contextPath.replaceAll("/+$", "");
    server.createContext(prefix + "/admin/config", exchange -> {
      var body = json.writeValueAsBytes(current.get());
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext(prefix + "/admin/mode", exchange -> {
      if (exchange.getRequestMethod().equals("POST")) {
        current.set(json.readTree(exchange.getRequestBody()));
      }
      var body = json.writeValueAsBytes(current.get());
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      var statusCode = exchange.getRequestMethod().equals("POST")
          && updates.incrementAndGet() == 1 && preparationFails ? 500 : 200;
      exchange.sendResponseHeaders(statusCode, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      TigerGlobalConfiguration.putValue("zeta_cert_validation_mock_url",
          "127.0.0.1:" + server.getAddress().getPort() + contextPath,
          ConfigurationValuePrecedence.TEST_CONTEXT);
      var cleared = new AtomicInteger();
      var steps = new OcspRequestSteps(cleared::incrementAndGet);
      steps.enableSmcbStatusRestoration();
      if (preparationFails) {
        assertThrows(AssertionError.class,
            () -> steps.certificateValidationMockUsesMode("always_revoked"));
      } else {
        steps.certificateValidationMockUsesMode("always_revoked");
      }
      if (conflict) {
        current.set(json.readTree("{\"mode\":\"always_unknown\"}"));
        assertThrows(AssertionError.class, steps::restoreSmcbStatusConfiguration);
        assertEquals("always_unknown", current.get().path("mode").asText());
      } else {
        steps.restoreSmcbStatusConfiguration();
        assertEquals(original, current.get());
      }
      assertEquals(1, cleared.get());
    } finally {
      server.stop(0);
      TigerGlobalConfiguration.clearTestVariables();
    }
  }

  /** Does not change the deployment if setup never reached the first mock mutation. */
  @Test
  void cleanupBeforePreparationDoesNothing() throws Exception {
    var cleared = new AtomicInteger();
    new OcspRequestSteps(cleared::incrementAndGet).restoreSmcbStatusConfiguration();
    assertEquals(0, cleared.get());
  }

  /**
   * Generates a certificate whose issuer is its own key.
   *
   * @param key signing and subject key
   * @param serial certificate serial number
   * @return generated certificate
   * @throws Exception if certificate creation fails
   */
  private static X509Certificate certificate(KeyPair key, BigInteger serial) throws Exception {
    var name = new X500Name("CN=OCSP status fixture " + serial);
    var builder = new JcaX509v3CertificateBuilder(name, serial,
        Date.from(NOW.minusSeconds(3600)), Date.from(NOW.plusSeconds(3600)), name, key.getPublic());
    return new JcaX509CertificateConverter().getCertificate(builder.build(
        new JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate())));
  }
}
