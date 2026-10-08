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

package de.gematik.zeta.steps.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.model.tls.TlsCipherSuite;
import de.gematik.zeta.model.tls.TlsHandshakeExpectation;
import de.gematik.zeta.model.tls.TlsLibrary;
import de.gematik.zeta.model.tls.TlsSignatureAlgorithm;
import de.gematik.zeta.model.tls.TlsSupportedGroup;
import de.gematik.zeta.model.tls.TlsVersion;
import de.gematik.zeta.steps.TlsTestToolSteps;
import de.gematik.zeta.steps.tls.TlsLogParser;
import de.gematik.zeta.steps.tls.TlsTestToolConfigBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TLS diagnostics in {@link TlsTestToolSteps} and {@link TlsLogParser}.
 */
class TlsTestToolStepsTest {

  /**
   * Injects TLS log content into the step class under test via reflection.
   *
   * @param tlsSteps target step instance
   * @param value    TLS log content
   */
  private static void setTlsLogs(TlsTestToolSteps tlsSteps, String value) {
    try {
      var field = getTlsLogsField();
      field.set(tlsSteps, value);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Unable to set tlsLogs for test setup", e);
    }
  }

  /**
   * Invokes {@code extractAlertSummary()} via reflection.
   *
   * @param tlsSteps target step instance
   * @return extracted alert summary
   */
  private static String invokeExtractAlertSummary(TlsTestToolSteps tlsSteps) {
    try {
      var method = getExtractAlertSummaryMethod();
      return (String) method.invoke(tlsSteps);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("extractAlertSummary invocation failed", e.getCause());
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Unable to invoke extractAlertSummary", e);
    }
  }

  /**
   * Returns reflective access to the private {@code tlsLogs} field.
   *
   * @return reflective field handle
   */
  private static Field getTlsLogsField() {
    try {
      var field = TlsTestToolSteps.class.getDeclaredField("tlsLogs");
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException e) {
      throw new RuntimeException("Test setup failed, tlsLogs field not found", e);
    }
  }

  /**
   * Returns reflective access to the private {@code extractAlertSummary()} method.
   *
   * @return reflective method handle
   */
  private static Method getExtractAlertSummaryMethod() {
    try {
      var method = TlsTestToolSteps.class.getDeclaredMethod("extractAlertSummary");
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Test setup failed, extractAlertSummary method not found", e);
    }
  }

  /**
   * Extracts {@code supported_groups} via the parser API.
   *
   * @param fullLog TLS log content
   * @return extracted supported groups
   */
  private static List<TlsSupportedGroup> invokeExtractSupportedGroupsHex(String fullLog) {
    return TlsLogParser.extractSupportedGroupsHex(fullLog);
  }

  /**
   * Extracts {@code key_share} groups via the parser API.
   *
   * @param fullLog TLS log content
   * @return extracted key_share groups
   */
  private static List<TlsSupportedGroup> invokeExtractClientKeyShareGroups(String fullLog) {
    return TlsLogParser.extractClientKeyShareGroups(fullLog);
  }

  /**
   * Extracts TLS-1.3 signature schemes via the parser API.
   *
   * @param fullLog TLS log content
   * @return extracted TLS-1.3 signature schemes
   */
  private static List<Enum<?>> invokeExtractTls13SignatureSchemes(String fullLog) {
    return List.copyOf(TlsLogParser.extractTls13SignatureSchemes(fullLog));
  }

  /**
   * Builds the supported TLS-1.3 server configuration through the config builder API.
   *
   * @return generated TLS-1.3 server configuration
   */
  private static String invokeBuildTls13ServerConfigForSupportedCipherSuites() {
    return TlsTestToolConfigBuilder.buildTls13ServerConfigForSupportedCipherSuites();
  }

  /**
   * Builds a TLS-1.2 server configuration through the config builder API.
   *
   * @param cipherSuites     cipher suites in tls-test-tool tuple syntax
   * @param extraConfigLines optional additional config lines
   * @return generated TLS-1.2 server configuration
   */
  private static String invokeBuildTls12ServerConfig(String cipherSuites, String... extraConfigLines) {
    return TlsTestToolConfigBuilder.buildTls12ServerConfig(cipherSuites, extraConfigLines);
  }

  /**
   * Extracts ClientHello cipher suites via the parser API.
   *
   * @param fullLog TLS log content
   * @return extracted cipher suite pairs
   */
  private static List<String> invokeExtractClientHelloCipherSuitesAsPairs(String fullLog) {
    return TlsLogParser.extractClientHelloCipherSuitesAsPairs(fullLog);
  }

  /**
   * Builds a minimal successful renegotiation log from the TLS client perspective.
   *
   * @return TLS log excerpt with RFC 5746 compliant renegotiation_info values
   */
  private static String buildSuccessfulSecureRenegotiationLogForClient() {
    return """
        ClientHello message transmitted.
        tlsHandshakeMessage type =0x14 data =7d 8a 23 8c 74 9a 8b b1 31 f9 fb fa
        Finished message transmitted.
        tlsHandshakeMessage type =0x14 data =e9 14 51 5d f7 86 f4 37 01 b3 76 13
        Valid Finished message received.
        Handshake successful.
        Performing renegotiation.
        ClientHello.extensions=ff 01 00 0d 0c 7d 8a 23 8c 74 9a 8b b1 31 f9 fb fa
        ServerHello.extensions=ff 01 00 19 18 7d 8a 23 8c 74 9a 8b b1 31 f9 fb fa e9 14 51 5d f7 86 f4 37 01 b3 76 13
        Valid Finished message received.
        """;
  }

  /**
   * Builds a minimal successful renegotiation log from the TLS server perspective.
   *
   * @return TLS log excerpt with RFC 5746 compliant renegotiation_info values
   */
  private static String buildSuccessfulSecureRenegotiationLogForServer() {
    return """
        Valid ClientHello message received.
        tlsHandshakeMessage type =0x14 data =5f 95 57 de 7f 4b dc 01 ab 85 4e 6a
        Valid Finished message received.
        tlsHandshakeMessage type =0x14 data =b1 2d e5 1f f2 19 8f b4 8a cb 37 21
        Finished message transmitted.
        Handshake successful.
        Performing renegotiation.
        ClientHello.extensions=ff 01 00 0d 0c 5f 95 57 de 7f 4b dc 01 ab 85 4e 6a
        ServerHello.extensions=ff 01 00 19 18 5f 95 57 de 7f 4b dc 01 ab 85 4e 6a b1 2d e5 1f f2 19 8f b4 8a cb 37 21
        Valid Finished message received.
        """;
  }

  /**
   * Verifies that alert summaries include the negotiated hash/signature details when present.
   */
  @Test
  void extractAlertSummaryContainsSelectedHashAndSignatureWithHexValues() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Alert.level=02
        Alert.description=28
        Cipher suite: TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
        Server used SignatureAlgorithm 3
        Server used HashAlgorithm 4
        TLS handshake failed: Received fatal alert
        """);

    var summary = invokeExtractAlertSummary(tlsSteps);

    assertTrue(summary.contains("level=02"));
    assertTrue(summary.contains("description=28"));
    assertTrue(summary.contains("selected_hash=SHA256(4/0x04)"));
    assertTrue(summary.contains("selected_signature=ECDSA(3/0x03)"));
    assertTrue(summary.contains("selected_cipher_suite=TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
  }

  /**
   * Verifies that alert summaries fall back to generic markers when no alert is present.
   */
  @Test
  void extractAlertSummaryFallsBackToLastLogLineAndHandshakeMarker() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        unrelated line
        Handshake successful.
        trailing line
        """);

    var summary = invokeExtractAlertSummary(tlsSteps);

    assertTrue(summary.contains("last_log_line=trailing line"));
    assertTrue(summary.contains("handshake_successful=true"));
  }

  /**
   * Verifies that the TCP/IP connection step accepts the TLS test tool success log line.
   */
  @Test
  void checkTcpIpConnectionIsEstablishedAcceptsEstablishedLogLine() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-05-11T20:43:08.299Z       HIGH       Tool(TlsTestTool.cpp:377)       TLS Test Tool version 1.0.1
        2026-05-11T20:43:08.404Z       HIGH       Network(TlsTestTool.cpp:411)       TCP/IP connection to 20.4.56.79:443 established.
        2026-05-11T20:43:08.404Z       HIGH       TLS(TlsSession.cpp:712)       Using patched mbed TLS 2.2.1.
        """);

    tlsSteps.checkTcpIpConnectionIsEstablished();
  }

  /**
   * Verifies that the TCP/IP connection step accepts the TLS test tool server-side received log line.
   */
  @Test
  void checkTcpIpConnectionIsEstablishedAcceptsReceivedLogLine() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-05-11T21:20:23.412Z       HIGH       Tool(TlsTestTool.cpp:377)       TLS Test Tool version 1.0.1
        2026-05-11T21:20:23.412Z       HIGH       Network(TlsTestTool.cpp:442)       Waiting for TCP/IP connection on port 4433.
        2026-05-11T21:20:23.412Z       HIGH       TLS(TlsSession.cpp:712)       Using patched mbed TLS 2.2.1.
        2026-05-11T21:20:23.412Z       HIGH       TLS(TlsSession.cpp:581)       Not using SNI.
        2026-05-11T21:20:23.713Z       HIGH       Network(TlsTestTool.cpp:325)       TCP/IP connection from 10.30.16.119:36830 received.
        2026-05-11T21:20:23.713Z       HIGH       Manipulation(ManipulateHelloVersion.cpp:24)       Setting version for Hello message to (3, 2).
        """);

    tlsSteps.checkTcpIpConnectionIsEstablished();
  }

  /**
   * Verifies that the TCP/IP connection step rejects the TLS test tool failure log line.
   */
  @Test
  void checkTcpIpConnectionIsEstablishedRejectsFailedLogLine() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-05-11T20:43:13.975Z       HIGH       Tool(TlsTestTool.cpp:377)       TLS Test Tool version 1.0.1
        2026-05-11T20:45:24.714Z       HIGH       Network(TlsTestTool.cpp:415)       TCP/IP connection to authserver:443 failed: connect: Connection timed out
        """);

    var error = assertThrows(AssertionError.class, tlsSteps::checkTcpIpConnectionIsEstablished);

    assertTrue(error.getMessage().contains("TCP/IP connection was not established"));
    assertTrue(error.getMessage().contains("Connection timed out"));
  }

  /**
   * Verifies that alert summaries include TLS-1.3 ServerHello and CertificateVerify metadata.
   */
  @Test
  void extractAlertSummaryContainsTls13HandshakeMetadata() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        ClientHello message transmitted.
        HelloRetryRequest.cipher_suite=13 02
        Valid ServerHello message received.
        ClientHello message transmitted.
        ServerHello.cipher_suite=13 01
        ServerHello.extensions=00 2b 00 02 03 04 00 33 00 24 00 1d 00 20 fe 48 db
        CertificateVerify.algorithm=04 03
        Handshake successful.
        TLS Test Tool exiting.
        """);

    var summary = invokeExtractAlertSummary(tlsSteps);

    assertTrue(summary.contains("level=n/a"));
    assertTrue(summary.contains("description=n/a"));
    assertTrue(summary.contains("handshake_successful=true"));
    assertTrue(summary.contains("last_log_line=TLS Test Tool exiting."));
    assertTrue(summary.contains("selected_cipher_suite=TLS_AES_128_GCM_SHA256"));
    assertTrue(summary.contains(
        "selected_tls13_signature_scheme=ECDSA_SECP256R1_SHA256(ecdsa_secp256r1_sha256/(0x04,0x03))"));
    assertTrue(summary.contains("selected_server_key_share=x25519(0x001D)"));
    assertFalse(summary.contains("TLS_AES_256_GCM_SHA384"));
  }

  /**
   * Verifies that alert summaries focus on the failing renegotiation phase only.
   */
  @Test
  void extractAlertSummaryUsesFailingHandshakePhaseOnly() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        ClientHello message transmitted.
        Handshake successful.
        Server used SignatureAlgorithm 3
        Server used HashAlgorithm 4
        Cipher suite: TLS-ECDHE-ECDSA-WITH-AES-128-GCM-SHA256
        => renegotiate
        Alert.level=01
        Alert.description=64
        TLS handshake failed: mbedtls_ssl_renegotiate failed
        """);

    var summary = invokeExtractAlertSummary(tlsSteps);

    assertTrue(summary.contains("level=01"));
    assertTrue(summary.contains("description=64"));
    assertTrue(summary.contains("TLS handshake failed: mbedtls_ssl_renegotiate failed"));
    assertFalse(summary.contains("selected_hash="));
    assertFalse(summary.contains("selected_signature="));
    assertFalse(summary.contains("selected_cipher_suite="));
    assertFalse(summary.contains("handshake_successful=true"));
  }

  /**
   * Verifies hex rendering and unknown fallback behavior for signature algorithm identifiers.
   */
  @Test
  void tlsSignatureAlgorithmProvidesHexValueAndUnknownFallback() {
    var ecdsa = TlsSignatureAlgorithm.fromValue(3);
    var unknown = TlsSignatureAlgorithm.fromValue(999);

    assertEquals(TlsSignatureAlgorithm.ECDSA, ecdsa);
    assertEquals("0x03", ecdsa.getHexValue());
    assertEquals(TlsSignatureAlgorithm.UNKNOWN, unknown);
    assertEquals("n/a", unknown.getHexValue());
  }

  /**
   * Verifies supported group extraction when the next log line begins with a timestamp.
   */
  @Test
  void extractSupportedGroupsHexHandlesTimestampedNextLogLine() {
    var supportedGroups = invokeExtractSupportedGroupsHex("""
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0a 00 16 00 14 00 1d 00 17 00 18 00 19 00 1e 01 00 01 01 01 02 01 03 01 04 
        2026-03-17T08:26:38.499Z\tHIGH\tmbedTLS(ssl_srv.c:1836)\tselected ciphersuite: TLS-ECDHE-ECDSA-WITH-AES-128-GCM-SHA256
        """);

    assertEquals(
        List.of(
            TlsSupportedGroup.X25519,
            TlsSupportedGroup.SECP256R1,
            TlsSupportedGroup.SECP384R1,
            TlsSupportedGroup.SECP521R1,
            TlsSupportedGroup.X448,
            TlsSupportedGroup.FFDHE2048,
            TlsSupportedGroup.FFDHE3072,
            TlsSupportedGroup.FFDHE4096,
            TlsSupportedGroup.FFDHE6144,
            TlsSupportedGroup.FFDHE8192),
        supportedGroups);
  }

  /**
   * Verifies that ClientHello key_share extraction stops at the declared extension length.
   */
  @Test
  void extractClientKeyShareGroupsStopsAtDeclaredExtensionLength() {
    var keyShareGroups = invokeExtractClientKeyShareGroups("""
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 33 00 47 00 45 00 17 00 41 04 6d b8 72 79 6e 22 e7 1c 9e 88 6e 1a c0 7f 54 7b 85 a0 84 cb 5a 7d 9f e9 43 1e 4e 34 7d 8f 1a 9a c6 66 8d 59 f4 b4 0c f8 22 17 3b 8d dd e0 0a 27 1f 39 c2 1a 27 0e d1 5e 84 12 66 2b 00 18 00 01 42
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.signature_algorithms=08 04 08 05 08 06
        """);

    assertEquals(
        List.of(
            TlsSupportedGroup.SECP256R1),
        keyShareGroups);
  }

  /**
   * Verifies that a following TLS padding extension is not misread as a ClientHello key_share group.
   */
  @Test
  void extractClientKeyShareGroupsIgnoresFollowingPaddingExtension() {
    var keyShareGroups = invokeExtractClientKeyShareGroups("""
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 33 00 06 00 04 00 17 00 00 00 15 00 00
        """);

    assertEquals(
        List.of(
            TlsSupportedGroup.SECP256R1),
        keyShareGroups);
  }

  /**
   * Verifies that TLS-1.3 signature-scheme extraction stops at the current log line boundary.
   */
  @Test
  void extractTls13SignatureSchemesHandlesTimestampedNextLogLine() {
    var signatureSchemes = invokeExtractTls13SignatureSchemes("""
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0d 00 12 00 10 08 04 08 05 08 06 08 09 08 0a 08 0b 04 03 05 03
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.supported_versions=03 04 03 03
        """);

    assertEquals(
        List.of(
            "RSA_PSS_RSAE_SHA256",
            "RSA_PSS_RSAE_SHA384",
            "RSA_PSS_RSAE_SHA512",
            "RSA_PSS_PSS_SHA256",
            "RSA_PSS_PSS_SHA384",
            "RSA_PSS_PSS_SHA512",
            "ECDSA_SECP256R1_SHA256",
            "ECDSA_SECP384R1_SHA384"),
        signatureSchemes.stream().map(Enum::name).toList());
  }

  /**
   * Verifies that ClientHello cipher suite extraction stops at the current log line boundary.
   */
  @Test
  void extractClientHelloCipherSuitesAsPairsStopsAtEndOfLogLine() {
    var cipherSuites = invokeExtractClientHelloCipherSuitesAsPairs("""
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.cipher_suites=13 01 13 02 13 03 c0 2b c0 2f c0 2c c0 30 cc a9 cc a8 c0 13 c0 14 
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.compression_methods=00 
        """);

    assertEquals(
        List.of(
            "(0x13,0x01)",
            "(0x13,0x02)",
            "(0x13,0x03)",
            "(0xC0,0x2B)",
            "(0xC0,0x2F)",
            "(0xC0,0x2C)",
            "(0xC0,0x30)",
            "(0xCC,0xA9)",
            "(0xCC,0xA8)",
            "(0xC0,0x13)",
            "(0xC0,0x14)"),
        cipherSuites);
  }

  /**
   * Verifies TLS version metadata for representative TLS 1.2 and TLS 1.3 cipher suites.
   */
  @Test
  void tlsCipherSuiteProvidesTlsVersionForTls13CipherSuites() {
    var tls13CipherSuite = TlsCipherSuite.AES_128_GCM_SHA256;
    assertEquals(TlsVersion.TLS_1_3, tls13CipherSuite.getTlsVersion());
    assertTrue(tls13CipherSuite.getIsMandatory());
    var tls13Ccm8CipherSuite = TlsCipherSuite.AES_128_CCM_8_SHA256;
    assertEquals("(0x13,0x05)", tls13Ccm8CipherSuite.getTlsTestToolCipherSuiteValue());
    assertEquals(TlsVersion.TLS_1_3, tls13Ccm8CipherSuite.getTlsVersion());
    assertFalse(tls13Ccm8CipherSuite.getIsMandatory());
    var tls12CipherSuite = TlsCipherSuite.ECDHE_ECDSA_AES_128_GCM_SHA256;
    assertEquals(TlsVersion.TLS_1_2, tls12CipherSuite.getTlsVersion());
    assertTrue(tls12CipherSuite.getIsMandatory());
  }

  /**
   * Verifies that the optional TLS 1.3 cipher suite helper contains only non-mandatory TLS 1.3 entries.
   */
  @Test
  void optionalTls13CipherSuitesContainsOnlyTls13NonMandatorySuites() {
    var cipherSuites = TlsCipherSuite.optionalTls13CipherSuites();

    assertFalse(cipherSuites.contains(TlsCipherSuite.AES_128_GCM_SHA256));
    assertTrue(cipherSuites.contains(TlsCipherSuite.AES_128_CCM_8_SHA256));
    assertFalse(cipherSuites.contains(TlsCipherSuite.ECDHE_ECDSA_AES_128_GCM_SHA256));
    assertTrue(cipherSuites.stream().map(TlsCipherSuite::getTlsVersion)
        .allMatch(version -> version == TlsVersion.TLS_1_3));
    assertEquals(
        Set.of(
            TlsCipherSuite.CHACHA20_POLY1305_SHA256,
            TlsCipherSuite.AES_128_CCM_SHA256,
            TlsCipherSuite.AES_128_CCM_8_SHA256),
        cipherSuites);
  }

  /**
   * Verifies that the mandatory TLS 1.3 cipher suite helper contains only mandatory TLS 1.3 entries.
   */
  @Test
  void mandatoryTls13CipherSuitesContainsOnlyMandatoryTls13Suites() {
    var cipherSuites = TlsCipherSuite.mandatoryTls13CipherSuites();

    assertEquals(
        Set.of(
            TlsCipherSuite.AES_128_GCM_SHA256,
            TlsCipherSuite.AES_256_GCM_SHA384),
        cipherSuites);
  }

  /**
   * Verifies that the default TLS-1.3 supported-groups profile advertises recommended and optional groups.
   */
  @Test
  void tls13SupportedGroupsAdvertiseRecommendedAndOptionalGroups() {
    var supportedGroups = TlsSupportedGroup.tls13SupportedGroups();

    assertEquals(
        "secp256r1,secp384r1,brainpoolP256r1,brainpoolP384r1,brainpoolP512r1",
        supportedGroups);
  }

  /**
   * Verifies that TLS-1.3 brainpool display names and NamedGroup IDs follow their policy buckets.
   */
  @Test
  void brainpoolTls13SupportedGroupsUsePolicySpecificEntries() {

    assertEquals(
        TlsSupportedGroup.BRAINPOOLP256R1,
        TlsSupportedGroup.fromDisplayName("brainpoolP256r1"));
    assertEquals(
        TlsSupportedGroup.BRAINPOOLP256R1TLS13,
        TlsSupportedGroup.fromDisplayName("brainpoolP256r1tls13"));
    assertEquals(0x0016, TlsSupportedGroup.SECP256K1.getValue());
    assertEquals(0x001A, TlsSupportedGroup.BRAINPOOLP256R1.getValue());
    assertEquals(0x001F, TlsSupportedGroup.BRAINPOOLP256R1TLS13.getValue());
    assertEquals(0x0020, TlsSupportedGroup.BRAINPOOLP384R1TLS13.getValue());
    assertEquals(0x0021, TlsSupportedGroup.BRAINPOOLP512R1TLS13.getValue());
    assertEquals(
        TlsSupportedGroup.Tls13Policy.OPTIONAL,
        TlsSupportedGroup.BRAINPOOLP256R1.getTls13Policy());
    assertEquals(
        TlsSupportedGroup.Tls13Policy.FORBIDDEN,
        TlsSupportedGroup.BRAINPOOLP256R1TLS13.getTls13Policy());
    assertEquals(
        TlsSupportedGroup.Tls12Policy.FORBIDDEN,
        TlsSupportedGroup.BRAINPOOLP256R1TLS13.getTls12Policy());

    var allowedGroups = TlsSupportedGroup.allowedGroups();
    assertTrue(allowedGroups.contains(TlsSupportedGroup.BRAINPOOLP256R1));
    assertFalse(allowedGroups.contains(TlsSupportedGroup.BRAINPOOLP256R1TLS13));
  }

  /**
   * Verifies that TLS-1.3 forbidden supported groups are selected from the TLS-1.3 policy bucket.
   */
  @Test
  void forbiddenGroupsForTls13ContainsOnlyTls13ForbiddenGroups() {
    var forbiddenGroups = TlsSupportedGroup.forbiddenGroupsForTls13();

    assertEquals(
        List.of(
            TlsSupportedGroup.BRAINPOOLP256R1TLS13,
            TlsSupportedGroup.BRAINPOOLP384R1TLS13,
            TlsSupportedGroup.BRAINPOOLP512R1TLS13,
            TlsSupportedGroup.SECP192R1,
            TlsSupportedGroup.SECP224R1,
            TlsSupportedGroup.SECP521R1,
            TlsSupportedGroup.SECP256K1,
            TlsSupportedGroup.X25519,
            TlsSupportedGroup.X448,
            TlsSupportedGroup.FFDHE2048,
            TlsSupportedGroup.FFDHE3072,
            TlsSupportedGroup.FFDHE4096,
            TlsSupportedGroup.FFDHE6144,
            TlsSupportedGroup.FFDHE8192),
        forbiddenGroups);
  }

  /**
   * Verifies that the TLS-1.3 offerable-forbidden set excludes the groups OpenSSL cannot advertise
   * (secp192r1/secp224r1/secp256k1) while keeping every other TLS-1.3-forbidden group.
   */
  @Test
  void forbiddenGroupsOfferableInTls13ExcludesNonOfferableGroups() {
    var offerable = TlsSupportedGroup.forbiddenGroupsOfferableInTls13();

    assertEquals(
        List.of(
            TlsSupportedGroup.BRAINPOOLP256R1TLS13,
            TlsSupportedGroup.BRAINPOOLP384R1TLS13,
            TlsSupportedGroup.BRAINPOOLP512R1TLS13,
            TlsSupportedGroup.SECP521R1,
            TlsSupportedGroup.X25519,
            TlsSupportedGroup.X448,
            TlsSupportedGroup.FFDHE2048,
            TlsSupportedGroup.FFDHE3072,
            TlsSupportedGroup.FFDHE4096,
            TlsSupportedGroup.FFDHE6144,
            TlsSupportedGroup.FFDHE8192),
        offerable);
  }

  /**
   * Verifies that TLS-1.3 advertised supported groups are not classified as TLS-1.2 forbidden.
   */
  @Test
  void tls13AdvertisedSupportedGroupsAreNotTls12Forbidden() {
    var advertisedForbiddenGroups = java.util.Arrays.stream(TlsSupportedGroup.values())
        .filter(group -> group.getTls13Policy() == TlsSupportedGroup.Tls13Policy.RECOMMENDED
            || group.getTls13Policy() == TlsSupportedGroup.Tls13Policy.OPTIONAL)
        .filter(group -> group.getTls12Policy() == TlsSupportedGroup.Tls12Policy.FORBIDDEN)
        .toList();

    assertEquals(List.of(), advertisedForbiddenGroups);
  }

  /**
   * Verifies that transmitted Certificate messages are accepted in TLS logs.
   */
  @Test
  void certificateWasTransmittedAcceptsCertificateMessageLogEntry() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-05-08T04:44:16.432Z\tHIGH\tTLS(TlsLogFilter.cpp:194)\tCertificate message transmitted.
        """);

    tlsSteps.checkCertificateWasTransmitted();
  }

  /**
   * Verifies that missing Certificate messages are reported as a broken handshake precondition.
   */
  @Test
  void certificateWasTransmittedReportsBrokenHandshakeWhenCertificateMessageIsMissing() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-05-08T04:44:16.432Z\tHIGH\tmbedTLS(ssl_srv.c:3805)\tserver state: 4
        2026-05-08T04:44:16.432Z\tHIGH\tTLS(TlsLogFilter.cpp:194)\tClientHello message transmitted.
        2026-05-08T04:44:16.432Z\tHIGH\tTLS(TlsLogFilter.cpp:194)\tAlert message received
        2026-05-08T04:44:16.432Z\tHIGH\tTLS(TlsLogFilter.cpp:194)\tAlert.level=02
        2026-05-08T04:44:16.432Z\tHIGH\tTLS(TlsLogFilter.cpp:194)\tAlert.description=2a
        """);

    var error = assertThrows(AssertionError.class, tlsSteps::checkCertificateWasTransmitted);

    assertTrue(error.getMessage().contains("not caused by the test scenario assertion"));
    assertTrue(error.getMessage().contains("the TLS handshake broke earlier for another reason"));
    assertTrue(error.getMessage().contains("Alert summary"));
  }

  /**
   * Verifies that GS-A_5542 alert validation accepts fatal handshake_failure alerts.
   */
  @Test
  void endpointSendsFatalTlsErrorAlertAcceptsHandshakeFailure() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Alert message received.
        Alert.level=02
        Alert.description=28
        TLS handshake failed: error:0A000410:SSL routines::sslv3 alert handshake failure
        """);

    tlsSteps.endpointSendsFatalTlsErrorAlert();
  }

  /**
   * Verifies that GS-A_5542 alert validation rejects warning alerts.
   */
  @Test
  void endpointSendsFatalTlsErrorAlertRejectsWarningAlert() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Alert message received.
        Alert.level=01
        Alert.description=28
        TLS handshake failed: error:0A000410:SSL routines::sslv3 alert handshake failure
        """);

    var error = assertThrows(AssertionError.class, tlsSteps::endpointSendsFatalTlsErrorAlert);

    assertTrue(error.getMessage().contains("Alert.level=02"));
  }

  /**
   * Verifies that GS-A_5542 alert validation rejects non-error alert descriptions.
   */
  @Test
  void endpointSendsFatalTlsErrorAlertRejectsNonErrorAlertDescription() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Alert message received.
        Alert.level=02
        Alert.description=00
        TLS handshake failed: error:0A000410:SSL routines::sslv3 alert handshake failure
        """);

    var error = assertThrows(AssertionError.class, tlsSteps::endpointSendsFatalTlsErrorAlert);

    assertTrue(error.getMessage().contains("No fatal TLS error alert description"));
  }

  /**
   * Verifies that fatal TLS alert validation keeps scanning after a same-description warning alert.
   */
  @Test
  void endpointSendsFatalTlsErrorAlertAcceptsLaterFatalAlertWithSameDescription() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Alert message received.
        Alert.level=01
        Alert.description=28
        Alert message received.
        Alert.level=02
        Alert.description=28
        TLS handshake failed: error:0A000410:SSL routines::sslv3 alert handshake failure
        """);

    tlsSteps.endpointSendsFatalTlsErrorAlert();
  }

  /**
   * Verifies that secp521r1 is not part of the recommended TLS-1.3 signature scheme set.
   */
  @Test
  void recommendedTls13SignatureSchemesDoNotContainSecp521r1() {
    var signatureSchemes = invokeExtractTls13SignatureSchemes("""
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0d 00 04 00 02 06 03
        """);

    assertEquals(List.of("ECDSA_SECP521R1_SHA512"), signatureSchemes.stream().map(Enum::name).toList());

    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0d 00 04 00 02 06 03
        """);

    var error = assertThrows(AssertionError.class, tlsSteps::checkClientHelloForSupportedTls13SignatureSchemes);

    assertTrue(error.getMessage().contains("ECDSA_SECP521R1_SHA512"));
  }

  /**
   * Verifies that the non-recommended TLS-1.3 signature-scheme bucket remains limited to legacy pkcs1 schemes.
   */
  @Test
  void nonRecommendedTls13SignatureSchemesRemainLimitedToPkcs1Schemes() throws Exception {
    var enumClass = Class.forName("de.gematik.zeta.model.tls.TlsSignatureSchemes");
    var method = enumClass.getDeclaredMethod("nonRecommendedSchemeNames");
    method.setAccessible(true);

    @SuppressWarnings("unchecked")
    var schemes = (Set<String>) method.invoke(null);

    assertEquals(Set.of(
        "rsa_pkcs1_sha256",
        "rsa_pkcs1_sha384",
        "rsa_pkcs1_sha512"), schemes);
  }

  /**
   * Verifies that the TLS-1.3 server config for supported cipher suites contains all supported suites.
   */
  @Test
  void buildTls13ServerConfigForSupportedCipherSuitesContainsAllSupportedTls13Suites() {
    var tlsConfig = invokeBuildTls13ServerConfigForSupportedCipherSuites();

    assertTrue(tlsConfig.contains("tlsCipherSuites=(0x13,0x01),(0x13,0x02),(0x13,0x03),(0x13,0x04),(0x13,0x05)"));
  }

  /**
   * Verifies that the TLS-1.3 server config intentionally does not force certificate usage.
   */
  @Test
  void buildTls13ServerConfigForSupportedCipherSuitesDoesNotForceCertificateUsage() {
    var tlsConfig = invokeBuildTls13ServerConfigForSupportedCipherSuites();

    assertFalse(tlsConfig.contains("manipulateForceCertificateUsage="));
  }

  /**
   * Verifies that the TLS-1.2 server config keeps using the historical default TLS library.
   */
  @Test
  void buildTls12ServerConfigUsesMbedTlsByDefault() {
    var tlsConfig = invokeBuildTls12ServerConfig("(0xC0,0x2B)");

    assertTrue(tlsConfig.contains("tlsLibrary=mbed TLS"));
    assertFalse(tlsConfig.contains("tlsLibrary=OpenSSL"));
  }

  /** Verifies that TLS 1.2 server configuration can select OpenSSL for OCSP stapling. */
  @Test
  void buildTls12ServerConfigCanUseOpenSsl() {
    var tlsConfig = TlsTestToolConfigBuilder.buildTls12ServerConfig(
        TlsLibrary.OPENSSL, "(0xC0,0x2B)");

    assertTrue(tlsConfig.contains("tlsLibrary=OpenSSL"));
    assertFalse(tlsConfig.contains("tlsLibrary=mbed TLS"));
  }

  /** Verifies ClientHello OCSP request evidence in an OpenSSL trace. */
  @Test
  void recognizesOcspStatusRequestInOpenSslClientHelloTrace() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(
        tlsSteps,
        """
        Received TLS Record
          ClientHello, Length=210
            extension_type=status_request(5), length=5
        """);

    tlsSteps.checkIfClientRequestsOcspStapling();
  }

  /** Verifies stapled CertificateStatus evidence in an OpenSSL server trace. */
  @Test
  void recognizesOcspStaplingResponseInOpenSslServerTrace() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(
        tlsSteps,
        """
        Sent TLS Record
          ServerHello, Length=89
            extension_type=status_request(5), length=0
        Sent TLS Record
          CertificateStatus, Length=471
        """);

    tlsSteps.checkIfServerProvidesOcspStaplingResponse();
  }

  /** Verifies successful-handshake evidence emitted by OpenSSL s_server. */
  @Test
  void recognizesSuccessfulOpenSslServerHandshake() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(
        tlsSteps,
        """
        SSL_accept:SSLv3/TLS read finished
        SSL_accept:SSLv3/TLS write finished
        Received TLS Record
          Content Type = ApplicationData (23)
        """);

    tlsSteps.checkIfTlsHandshakeMatchesExpectation(TlsHandshakeExpectation.ERFOLGREICH);
  }

  /** Verifies rejected-handshake evidence emitted when an OpenSSL peer sends a fatal alert. */
  @Test
  void recognizesRejectedOpenSslServerHandshake() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(
        tlsSteps,
        """
        SSL_accept:SSLv3/TLS write server done
        SSL3 alert read:fatal:bad certificate status response
        SSL_accept:error in error
        """);

    tlsSteps.checkIfTlsHandshakeMatchesExpectation(TlsHandshakeExpectation.NICHT_ERFOLGREICH);
  }

  /**
   * Verifies that TLS-1.2 client cipher-suite configuration rejects TLS-1.3 profiles.
   */
  @Test
  void tls12ClientCipherSuiteStepRejectsTls13Profiles() {
    var tlsSteps = new TlsTestToolSteps();

    var error = assertThrows(
        AssertionError.class,
        () -> tlsSteps.setValidTlsTestToolConfigForCipherSuiteProfile(
            "zeta-kind.local",
            TlsCipherSuite.AES_128_GCM_SHA256));

    assertEquals("The cipher suite profile is not a TLS 1.2 profile.", error.getMessage());
  }

  /**
   * Verifies that TLS-1.2 server cipher-suite configuration rejects TLS-1.3 profiles.
   */
  @Test
  void tls12ServerCipherSuiteStepRejectsTls13Profiles() {
    var tlsSteps = new TlsTestToolSteps();

    var error = assertThrows(
        AssertionError.class,
        () -> tlsSteps.setValidTls12TlsTestToolServerConfigForCipherSuiteProfile(
            TlsCipherSuite.AES_128_GCM_SHA256));

    assertEquals("The cipher suite profile is not a TLS 1.2 profile.", error.getMessage());
  }

  /**
   * Verifies that TLS 1.3 cipher suites do not fail the supported-cipher-suite assertion.
   */
  @Test
  void onlySupportedCipherSuitesArePresentAcceptsTls13CipherSuites() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.cipher_suites=13 01 13 02 13 03 13 04 13 05
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.compression_methods=00
        """);

    tlsSteps.onlySupportedCipherSuitesArePresent();
  }

  /**
   * Verifies that ClientHello key_share accepts only allowed supported groups.
   */
  @Test
  void clientKeyShareAcceptsOnlyAllowedSupportedGroups() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 33 00 47 00 45 00 17 00 41 04 6d b8 72 79 6e 22 e7 1c 9e 88 6e 1a c0 7f 54 7b 85 a0 84 cb 5a 7d 9f e9 43 1e 4e 34 7d 8f 1a 9a c6 66 8d 59 f4 b4 0c f8 22 17 3b 8d dd e0 0a 27 1f 39 c2 1a 27 0e d1 5e 84 12 66 2b 00 18 00 01 42
        """);

    tlsSteps.checkClientKeyShareForSupportedCurvesOnly();
  }

  /**
   * Verifies that ClientHello key_share rejects unsupported groups.
   */
  @Test
  void clientKeyShareRejectsUnsupportedGroups() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 33 00 26 00 24 00 1d 00 20 35 80 72 d6 36 58 80 d1 ae ea 32 9a df 91 21 38 38 51 ed 21 a2 8e 3b 75 e9 65 d0 d2 cd 16 62 54
        """);

    var error = assertThrows(AssertionError.class, tlsSteps::checkClientKeyShareForSupportedCurvesOnly);

    assertTrue(error.getMessage().contains("X25519"));
  }

  /**
   * Verifies that TLS-1.3 ClientHello signature-scheme validation accepts only supported schemes.
   */
  @Test
  void clientHelloAcceptsOnlySupportedTls13SignatureSchemes() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0d 00 0c 00 0a 04 03 05 03 08 1a 08 1b 08 1c
        """);

    tlsSteps.checkClientHelloForSupportedTls13SignatureSchemes();
  }

  /**
   * Verifies that TLS-1.3 ClientHello signature-scheme validation accepts no unsupported schemes.
   */
  @Test
  void clientHelloAcceptsNoUnsupportedTls13SignatureSchemes() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0d 00 0c 00 0a 04 03 05 03 08 1a 08 1b 08 1c
        """);

    tlsSteps.checkClientHelloForNoUnsupportedTls13SignatureSchemes();
  }

  /**
   * Verifies that TLS-1.3 ClientHello signature-scheme validation rejects unsupported schemes in a full extensions block.
   */
  @Test
  void clientHelloRejectsUnsupportedTls13SignatureSchemesFromFullExtensionsBlock() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(
        tlsSteps,
        "2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions="
            + "00 05 00 05 01 00 00 00 00 00 0a 00 08 00 06 00 1d 00 17 00 18 "
            + "00 0b 00 02 01 00 00 10 00 0e 00 0c 02 68 32 08 68 74 74 70 2f 31 2e 31 "
            + "00 11 00 09 00 07 02 00 04 00 00 00 00 00 17 00 00 00 23 00 00 "
            + "00 0d 00 26 00 24 04 03 05 03 06 03 08 07 08 08 08 04 08 05 08 06 08 09 "
            + "08 0a 08 0b 04 01 05 01 06 01 04 02 03 03 03 01 03 02 00 2b 00 05 04 03 04 03 03 "
            + "00 2d 00 02 01 01 00 32 00 2c 00 2a 04 03 05 03 06 03 08 07 08 08 08 04 08 05 "
            + "08 06 08 09 08 0a 08 0b 04 01 05 01 06 01 04 02 03 03 03 01 03 02 02 03 02 01 02 02 "
            + "00 33 00 6b 00 69 00 1d 00 20 c5 04 93 e3 af da d0 4f 78 ed ad 02 a9 0e c4 97 "
            + "ef bd 55 0d 30 05 64 3b 95 57 18 e6 b5 87 58 63 00 17 00 41 04 2c c8 9b 4f 65 "
            + "03 73 5d fe 4a e2 08 2e 30 f6 5f 36 f4 10 0c 69 d0 76 f6 7e ad 81 71 02 fa 40 "
            + "12 9b 13 67 40 63 36 cc 99 b2 93 c5 63 43 2f e5 cf a0 4c fa 64 ca 4d 7e 2f e5 "
            + "02 03 8f 3b ed d5 66 ff 01 00 01 00");

    var error = assertThrows(AssertionError.class, tlsSteps::checkClientHelloForNoUnsupportedTls13SignatureSchemes);

    assertTrue(error.getMessage().contains("ECDSA_SECP521R1_SHA512"));
    assertTrue(error.getMessage().contains("ED25519"));
    assertTrue(error.getMessage().contains("RSA_PKCS1_SHA256"));
    assertTrue(error.getMessage().contains("DSA_SHA256"));
  }

  /**
   * Verifies that TLS-1.3 ClientHello signature-scheme validation rejects unsupported schemes.
   */
  @Test
  void clientHelloRejectsUnsupportedTls13SignatureSchemes() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0d 00 08 00 06 04 01 08 04 02 03
        """);

    var error = assertThrows(AssertionError.class, tlsSteps::checkClientHelloForSupportedTls13SignatureSchemes);

    assertTrue(error.getMessage().contains("RSA_PKCS1_SHA256"));
    assertTrue(error.getMessage().contains("ECDSA_SHA1"));
  }

  /**
   * Verifies RFC 5746 success-path validation for renegotiation from the TLS client perspective.
   */
  @Test
  void rfc5746CompliantRenegotiationAcceptsBoundRenegotiationInfoForClientLogs() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, buildSuccessfulSecureRenegotiationLogForClient());

    tlsSteps.checkIfTlsRenegotiationIsRfc5746Compliant();
  }

  /**
   * Verifies RFC 5746 success-path validation for renegotiation from the TLS server perspective.
   */
  @Test
  void rfc5746CompliantRenegotiationAcceptsBoundRenegotiationInfoForServerLogs() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, buildSuccessfulSecureRenegotiationLogForServer());

    tlsSteps.checkIfTlsRenegotiationIsRfc5746Compliant();
  }

  /**
   * Verifies RFC 5746 validation accepts no_renegotiation as a warning alert.
   */
  @Test
  void rfc5746CompliantRenegotiationAcceptsWarningNoRenegotiationAlert() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Handshake successful.
        => renegotiate
        Alert.level=01
        Alert.description=64
        TLS Test Tool exiting.
        """);

    tlsSteps.checkIfTlsRenegotiationIsRfc5746Compliant();
  }

  /**
   * Verifies RFC 5746 validation accepts handshake_failure as a fatal alert.
   */
  @Test
  void rfc5746CompliantRenegotiationAcceptsFatalHandshakeFailureAlert() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Handshake successful.
        => renegotiate
        Alert.level=02
        Alert.description=28
        TLS Test Tool exiting.
        """);

    tlsSteps.checkIfTlsRenegotiationIsRfc5746Compliant();
  }

  /**
   * Verifies RFC 5746 validation only accepts handshake_failure as a fatal alert.
   */
  @Test
  void rfc5746CompliantRenegotiationRejectsWarningHandshakeFailureAlert() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Handshake successful.
        => renegotiate
        Alert.level=01
        Alert.description=28
        TLS Test Tool exiting.
        """);

    var error =
        assertThrows(AssertionError.class, tlsSteps::checkIfTlsRenegotiationIsRfc5746Compliant);

    assertTrue(error.getMessage().contains("fatal Alert.description=28"));
  }

  /**
   * Verifies RFC 5746 validation rejects no_renegotiation as a fatal alert.
   */
  @Test
  void rfc5746CompliantRenegotiationRejectsFatalNoRenegotiationAlert() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Handshake successful.
        => renegotiate
        Alert.level=02
        Alert.description=64
        TLS Test Tool exiting.
        """);

    var error =
        assertThrows(AssertionError.class, tlsSteps::checkIfTlsRenegotiationIsRfc5746Compliant);

    assertTrue(error.getMessage().contains("warning Alert.description=64"));
  }

  /**
   * Verifies RFC 5746 validation rejects illegal_parameter as an unsupported fatal alert.
   */
  @Test
  void rfc5746CompliantRenegotiationRejectsIllegalParameterAlert() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        Handshake successful.
        => renegotiate
        Alert.level=02
        Alert.description=2f
        TLS Test Tool exiting.
        """);

    var error =
        assertThrows(AssertionError.class, tlsSteps::checkIfTlsRenegotiationIsRfc5746Compliant);

    assertTrue(error.getMessage().contains("fatal Alert.description=28"));
  }

  /**
   * Verifies that malformed ClientHello renegotiation_info values fail the RFC 5746 success path.
   */
  @Test
  void rfc5746CompliantRenegotiationRejectsMalformedClientHelloBinding() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(
        tlsSteps,
        buildSuccessfulSecureRenegotiationLogForClient()
            .replace(
                "ClientHello.extensions=ff 01 00 0d 0c 7d 8a 23 8c 74 9a 8b b1 31 f9 fb fa",
                "ClientHello.extensions=ff 01 00 0d 0c 7d 8a 23 8c 74 9a 8b b1 31 f9 fb 00"));

    var error =
        assertThrows(AssertionError.class, tlsSteps::checkIfTlsRenegotiationIsRfc5746Compliant);

    assertTrue(error.getMessage().contains("ClientHello renegotiation_info"));
  }

  /**
   * Verifies that malformed ServerHello renegotiation_info values fail the RFC 5746 success path.
   */
  @Test
  void rfc5746CompliantRenegotiationRejectsMalformedServerHelloBinding() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(
        tlsSteps,
        buildSuccessfulSecureRenegotiationLogForServer()
            .replace(
                "ServerHello.extensions=ff 01 00 19 18 5f 95 57 de 7f 4b dc 01 ab 85 4e 6a b1 2d e5 1f f2 19 8f b4 8a cb 37 21",
                "ServerHello.extensions=ff 01 00 19 18 5f 95 57 de 7f 4b dc 01 ab 85 4e 6a b1 2d e5 1f f2 19 8f b4 8a cb 37 00"));

    var error =
        assertThrows(AssertionError.class, tlsSteps::checkIfTlsRenegotiationIsRfc5746Compliant);

    assertTrue(error.getMessage().contains("ServerHello renegotiation_info"));
  }
}
