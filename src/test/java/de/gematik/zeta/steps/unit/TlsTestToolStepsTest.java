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
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.steps.TlsTestToolSteps;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for alert diagnostics in {@link TlsTestToolSteps}.
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

  private static Method getExtractSupportedGroupsHexMethod() {
    try {
      var method = TlsTestToolSteps.class.getDeclaredMethod("extractSupportedGroupsHex", String.class);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Test setup failed, extractSupportedGroupsHex method not found", e);
    }
  }

  private static Method getExtractClientHelloCipherSuitesAsPairsMethod() {
    try {
      var method = TlsTestToolSteps.class.getDeclaredMethod("extractClientHelloCipherSuitesAsPairs", String.class);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Test setup failed, extractClientHelloCipherSuitesAsPairs method not found", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<TlsTestToolSteps.TlsSupportedGroup> invokeExtractSupportedGroupsHex(String fullLog) {
    try {
      return (List<TlsTestToolSteps.TlsSupportedGroup>) getExtractSupportedGroupsHexMethod().invoke(null, fullLog);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("extractSupportedGroupsHex invocation failed", e.getCause());
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Unable to invoke extractSupportedGroupsHex", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> invokeExtractClientHelloCipherSuitesAsPairs(String fullLog) {
    try {
      return (List<String>) getExtractClientHelloCipherSuitesAsPairsMethod().invoke(null, fullLog);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("extractClientHelloCipherSuitesAsPairs invocation failed", e.getCause());
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Unable to invoke extractClientHelloCipherSuitesAsPairs", e);
    }
  }

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

  @Test
  void tlsSignatureAlgorithmProvidesHexValueAndUnknownFallback() {
    var ecdsa = TlsTestToolSteps.TlsSignatureAlgorithm.fromValue(3);
    var unknown = TlsTestToolSteps.TlsSignatureAlgorithm.fromValue(999);

    assertEquals(TlsTestToolSteps.TlsSignatureAlgorithm.ECDSA, ecdsa);
    assertEquals("0x03", ecdsa.getHexValue());
    assertEquals(TlsTestToolSteps.TlsSignatureAlgorithm.UNKNOWN, unknown);
    assertEquals("n/a", unknown.getHexValue());
  }

  @Test
  void extractSupportedGroupsHexHandlesTimestampedNextLogLine() {
    var supportedGroups = invokeExtractSupportedGroupsHex("""
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.extensions=00 0a 00 16 00 14 00 1d 00 17 00 18 00 19 00 1e 01 00 01 01 01 02 01 03 01 04 
        2026-03-17T08:26:38.499Z\tHIGH\tmbedTLS(ssl_srv.c:1836)\tselected ciphersuite: TLS-ECDHE-ECDSA-WITH-AES-128-GCM-SHA256
        """);

    assertEquals(
        List.of(
            TlsTestToolSteps.TlsSupportedGroup.X25519,
            TlsTestToolSteps.TlsSupportedGroup.SECP256R1,
            TlsTestToolSteps.TlsSupportedGroup.SECP384R1,
            TlsTestToolSteps.TlsSupportedGroup.SECP521R1,
            TlsTestToolSteps.TlsSupportedGroup.X448,
            TlsTestToolSteps.TlsSupportedGroup.FFDHE2048,
            TlsTestToolSteps.TlsSupportedGroup.FFDHE3072,
            TlsTestToolSteps.TlsSupportedGroup.FFDHE4096,
            TlsTestToolSteps.TlsSupportedGroup.FFDHE6144,
            TlsTestToolSteps.TlsSupportedGroup.FFDHE8192),
        supportedGroups);
  }

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

  @Test
  void tlsCipherSuiteProvidesTlsVersionForTls13CipherSuites() {
    var tls13CipherSuite = TlsTestToolSteps.TlsCipherSuite.AES_128_GCM_SHA256;
    assertEquals(TlsTestToolSteps.TlsVersion.TLS_1_3, tls13CipherSuite.getTlsVersion());
    assertFalse(tls13CipherSuite.getIsMandatory());
    var tls13Ccm8CipherSuite = TlsTestToolSteps.TlsCipherSuite.AES_128_CCM_8_SHA256;
    assertEquals("(0x13,0x05)", tls13Ccm8CipherSuite.getTlsTestToolCipherSuiteValue());
    assertEquals(TlsTestToolSteps.TlsVersion.TLS_1_3, tls13Ccm8CipherSuite.getTlsVersion());
    assertFalse(tls13Ccm8CipherSuite.getIsMandatory());
    var tls12CipherSuite = TlsTestToolSteps.TlsCipherSuite.ECDHE_RSA_AES_128_GCM_SHA256;
    assertEquals(TlsTestToolSteps.TlsVersion.TLS_1_2, tls12CipherSuite.getTlsVersion());
    assertTrue(tls12CipherSuite.getIsMandatory());
  }

  @Test
  void optionalTls13CipherSuitesContainsOnlyTls13NonMandatorySuites() {
    var cipherSuites = TlsTestToolSteps.TlsCipherSuite.optionalTls13CipherSuites();

    assertTrue(cipherSuites.contains(TlsTestToolSteps.TlsCipherSuite.AES_128_GCM_SHA256));
    assertTrue(cipherSuites.contains(TlsTestToolSteps.TlsCipherSuite.AES_128_CCM_8_SHA256));
    assertFalse(cipherSuites.contains(TlsTestToolSteps.TlsCipherSuite.ECDHE_RSA_AES_128_GCM_SHA256));
    assertTrue(cipherSuites.stream().map(TlsTestToolSteps.TlsCipherSuite::getTlsVersion)
        .allMatch(version -> version == TlsTestToolSteps.TlsVersion.TLS_1_3));
    assertEquals(
        Set.of(
            TlsTestToolSteps.TlsCipherSuite.AES_128_GCM_SHA256,
            TlsTestToolSteps.TlsCipherSuite.AES_256_GCM_SHA384,
            TlsTestToolSteps.TlsCipherSuite.CHACHA20_POLY1305_SHA256,
            TlsTestToolSteps.TlsCipherSuite.AES_128_CCM_SHA256,
            TlsTestToolSteps.TlsCipherSuite.AES_128_CCM_8_SHA256),
        cipherSuites);
  }

  @Test
  void onlySupportedCipherSuitesArePresentAcceptsTls13CipherSuites() {
    var tlsSteps = new TlsTestToolSteps();
    setTlsLogs(tlsSteps, """
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.cipher_suites=13 01 13 02 13 03 13 04 13 05
        2026-03-17T08:26:38.499Z\tHIGH\tTLS(TlsLogFilter.cpp:337)\tClientHello.compression_methods=00
        """);

    tlsSteps.onlySupportedCipherSuitesArePresent();
  }
}
