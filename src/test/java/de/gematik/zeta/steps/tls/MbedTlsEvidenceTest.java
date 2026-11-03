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

package de.gematik.zeta.steps.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MbedTlsEvidenceTest {

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "client-good-good-32",
        "server-good-good-32",
        "server-missing-good-32-control",
        "server-foreign-good-32-control",
        "server-good-missing-32-control",
        "server-good-foreign-32-control",
        "server-good-wrong-host-32-control",
        "server-good-good-32-control"
      })
  void parsesRecordedBundledBinaryCertificateAndHttpMessages(String name) throws Exception {
    var log = fixture(name + ".log");
    boolean server = name.startsWith("server");
    var row = MbedTlsEvidence.parse(log, server);
    var good = certificateHash("good");
    boolean positive = name.startsWith("client-good-good") || name.startsWith("server-good-good");
    assertEquals(positive, row.path("handshake").asBoolean());
    if (positive) {
      assertEquals(good, row.path("client_sha").asString());
      assertEquals(good, row.path("server_sha").asString());
      assertEquals(
          "BEGIN-" + "x".repeat(32) + "-END",
          new String(
              Base64.getDecoder().decode(row.path("body_base64").asString()),
              StandardCharsets.UTF_8));
      if (!server) {
        var request =
            "POST /push/v1/notify/batch HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 42\r\nConnection: close\r\n\r\nBEGIN-"
                + "x".repeat(32)
                + "-END";
        MbedTlsEvidence.verifySentRequest(log, request);
        assertThrows(
            AssertionError.class,
            () ->
                MbedTlsEvidence.verifySentRequest(
                    log, request.replace("notify/batch", "wrong-operation")));
      }
    } else {
      assertEquals("", row.path("request_base64").asString());
      assertEquals("", row.path("body_base64").asString());
      String offered =
          name.contains("-missing-32")
              ? ""
              : name.contains("-foreign-32")
                  ? certificateHash("foreign")
                  : name.contains("-wrong-host-32") ? certificateHash("wrong-host") : good;
      assertEquals(offered, row.path("offered_server_sha").asString());
      assertEquals(
          name.startsWith("server-foreign-good") ? certificateHash("foreign") : "",
          row.path("client_sha").asString());
      assertEquals(
          name.contains("good-foreign") || name.contains("good-wrong-host") || name.contains("good-missing")
              ? ""
              : "TLS_AUTHENTICATION_FAILED",
          row.path("failure").asString());
      assertEquals(
          name.startsWith("server-missing-good") ? "CLIENT_CERTIFICATE_MISSING"
              : name.startsWith("server-foreign-good") ? "CLIENT_CERTIFICATE_REJECTED" : "",
          row.path("failure_reason").asString());
    }
  }

  private static String fixture(String name) throws Exception {
    try (var stream =
        MbedTlsEvidenceTest.class.getResourceAsStream(
            "/tls-test-tool/evidence/mbedtls12/" + name)) {
      return new String(
          java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String certificateHash(String name) throws Exception {
    var certificate =
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(
                new java.io.ByteArrayInputStream(
                    fixture(name + "-cert.pem").getBytes(StandardCharsets.UTF_8)));
    assertEquals("EC", certificate.getPublicKey().getAlgorithm());
    return HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
  }

  private static final String CONNECTION = """
      mbedTLS(test:1)
      TCP/IP connection to 10.0.0.1:8443 established.
      """;
  private static final String SUCCESS =
      CONNECTION
          + "ServerHello.server_version=03 03\nServerHello.cipher_suite=c0 2b\n"
          + "Handshake successful.\n";

  @Test
  void reassemblesFragmentedLargeResponsesWithoutPaddingOrTruncation() throws Exception {
    var body = "x".repeat(30000);
    var message = "HTTP/1.1 200 OK\r\nContent-Length: 30000\r\n\r\n" + body;
    var log =
        SUCCESS
            + received(message.substring(0, 89))
            + received(message.substring(89, 18000))
            + received(message.substring(18000));
    assertArrayEquals(
        message.getBytes(StandardCharsets.UTF_8), MbedTlsEvidence.applicationData(log));
    var result = MbedTlsEvidence.parse(log, false);
    assertEquals(200, result.path("status").asInt());
    assertEquals(
        body,
        new String(
            Base64.getDecoder().decode(result.path("body_base64").asString()),
            StandardCharsets.UTF_8));
  }

  @Test
  void rejectsIncompleteBodiesAmbiguousLengthsAndTrailingBytes() {
    for (String message :
        java.util.List.of(
            "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}extra")) {
      assertThrows(
          Throwable.class, () -> MbedTlsEvidence.parse(SUCCESS + received(message), false));
    }
  }

  @Test
  void preservesChunkedResponseAndRejectsAnIncompleteChunk() throws Exception {
    String framed = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n";
    var result = MbedTlsEvidence.parse(SUCCESS + received(framed), false);
    assertEquals("e30=", result.path("body_base64").asString());
    assertThrows(
        Exception.class,
        () ->
            MbedTlsEvidence.parse(
                SUCCESS + received(framed.substring(0, framed.length() - 2)), false));
  }

  @Test
  void genericTlsFailuresDoNotProveCertificateRejection() throws Exception {
    for (String cause :
        java.util.List.of(
            "NET - Connection was reset by peer",
            "SSL - The operation timed out",
            "Network read failed",
            "SSL - A fatal alert message was received from our peer",
            "SSL - None of the common ciphersuites is usable (eg, no suitable certificate)",
            "SSL - Processing of the Certificate handshake message failed")) {
      for (boolean server : java.util.List.of(true, false)) {
        var connection = server
            ? "mbedTLS(test:1)\nTCP/IP connection from 10.0.0.1:4433 received.\n" : CONNECTION;
        var row = MbedTlsEvidence.parse(connection + "mbedtls_ssl_handshake_step failed: " + cause, server);
        assertEquals("", row.path("failure").asString(), cause);
        assertEquals("", row.path("failure_reason").asString(), cause);
      }
    }
    var row =
        MbedTlsEvidence.parse(
            """
            mbedTLS(test:1)
            TCP/IP connection from 10.0.0.1:4433 received.
            mbedtls_ssl_handshake_step failed: SSL - No client certificate received from the peer\
            """,
            true);
    assertEquals("TLS_AUTHENTICATION_FAILED", row.path("failure").asString());
    assertEquals("CLIENT_CERTIFICATE_MISSING", row.path("failure_reason").asString());
    assertEquals("gateway", row.path("failure_source").asString());
  }

  @Test
  void rejectsWrongVersionMultipleConnectionsAndUnauthenticatedHttp() {
    assertThrows(
        AssertionError.class,
        () -> MbedTlsEvidence.parse(SUCCESS.replace("03 03", "03 04"), false));
    assertThrows(
        AssertionError.class,
        () -> MbedTlsEvidence.parse(SUCCESS.replace("c0 2b", "c0 2f"), false));
    assertThrows(AssertionError.class, () -> MbedTlsEvidence.parse(SUCCESS + CONNECTION, false));
    assertThrows(
        AssertionError.class,
        () ->
            MbedTlsEvidence.parse(
                CONNECTION + received("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"), false));
  }

  @Test
  void rejectsMalformedHexInsteadOfSilentlyDroppingBytes() {
    assertThrows(
        AssertionError.class,
        () -> MbedTlsEvidence.applicationData("Application data received: 30 zz"));
    assertThrows(
        AssertionError.class,
        () -> MbedTlsEvidence.applicationData("Application data received: 3"));
    assertThrows(
        AssertionError.class, () -> MbedTlsEvidence.verifySentRequest(SUCCESS, "GET / HTTP/1.1"));
  }

  @Test
  void onlyTls12AndMbedTlsAreConfiguredAndReceiveWindowIsNonzero() {
    var config =
        TlsTestToolConfigBuilder.buildTls12ApplicationConfig(
            false, "ns.test", 8443, "GET /users/test/channels HTTP/1.1\r\n\r\n");
    assertTrue(config.contains("tlsLibrary=mbed TLS\ntlsVersion=(3,3)\ntlsVerifyPeer=true"));
    assertTrue(config.contains("tlsCipherSuites=(0xC0,0x2B)\n"));
    assertFalse(config.contains("0x2F"));
    assertTrue(config.contains("sessionLifetime=3"));
    assertTrue(config.contains("manipulateSendTlsApplicationData=1,47 45 54"));
  }

  private static String received(String message) {
    return "Application data received: "
        + HexFormat.ofDelimiter(" ").formatHex(message.getBytes(StandardCharsets.UTF_8))
        + "\n";
  }
}
