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

package de.gematik.zeta.model.tls;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * Mandatory and optional TLS cipher suites.
 */
public enum TlsCipherSuite {
  // Mandatory
  ECDHE_ECDSA_AES_128_GCM_SHA256("ecdhe_ecdsa_aes_128_gcm_sha256", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "(0xC0,0x2B)",
      TlsVersion.TLS_1_2, true),
  ECDHE_ECDSA_AES_256_GCM_SHA384("ecdhe_ecdsa_aes_256_gcm_sha384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "(0xC0,0x2C)",
      TlsVersion.TLS_1_2, true),
  // Optional (TR-02102-2, Abschnitt 3.3.1.1, Tabelle 1)
  ECDHE_ECDSA_AES_128_CBC_SHA256("ecdhe_ecdsa_aes_128_cbc_sha256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "(0xC0,0x23)",
      TlsVersion.TLS_1_2, false),
  ECDHE_ECDSA_AES_256_CBC_SHA384("ecdhe_ecdsa_aes_256_cbc_sha384", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", "(0xC0,0x24)",
      TlsVersion.TLS_1_2, false),
  ECDHE_ECDSA_AES_128_CCM("ecdhe_ecdsa_aes_128_ccm", "TLS_ECDHE_ECDSA_WITH_AES_128_CCM", "(0xC0,0xAC)", TlsVersion.TLS_1_2, false),
  ECDHE_ECDSA_AES_256_CCM("ecdhe_ecdsa_aes_256_ccm", "TLS_ECDHE_ECDSA_WITH_AES_256_CCM", "(0xC0,0xAD)", TlsVersion.TLS_1_2, false),
  ECDHE_RSA_AES_128_GCM_SHA256("ecdhe_rsa_aes_128_gcm_sha256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "(0xC0,0x2F)", TlsVersion.TLS_1_2,
      false),
  ECDHE_RSA_AES_256_GCM_SHA384("ecdhe_rsa_aes_256_gcm_sha384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "(0xC0,0x30)", TlsVersion.TLS_1_2,
      false),
  ECDHE_RSA_AES_128_CBC_SHA256("ecdhe_rsa_aes_128_cbc_sha256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "(0xC0,0x27)", TlsVersion.TLS_1_2,
      false),
  ECDHE_RSA_AES_256_CBC_SHA384("ecdhe_rsa_aes_256_cbc_sha384", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "(0xC0,0x28)", TlsVersion.TLS_1_2,
      false),
  DHE_DSS_AES_128_CBC_SHA256("dhe_dss_aes_128_cbc_sha256", "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", "(0x00,0x40)", TlsVersion.TLS_1_2, false),
  DHE_DSS_AES_256_CBC_SHA256("dhe_dss_aes_256_cbc_sha256", "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", "(0x00,0x6A)", TlsVersion.TLS_1_2, false),
  DHE_DSS_AES_128_GCM_SHA256("dhe_dss_aes_128_gcm_sha256", "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256", "(0x00,0xA2)", TlsVersion.TLS_1_2, false),
  DHE_DSS_AES_256_GCM_SHA384("dhe_dss_aes_256_gcm_sha384", "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384", "(0x00,0xA3)", TlsVersion.TLS_1_2, false),
  DHE_RSA_AES_128_CBC_SHA256("dhe_rsa_aes_128_cbc_sha256", "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", "(0x00,0x67)", TlsVersion.TLS_1_2, false),
  DHE_RSA_AES_256_CBC_SHA256("dhe_rsa_aes_256_cbc_sha256", "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256", "(0x00,0x6B)", TlsVersion.TLS_1_2, false),
  DHE_RSA_AES_128_GCM_SHA256("dhe_rsa_aes_128_gcm_sha256", "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", "(0x00,0x9E)", TlsVersion.TLS_1_2, false),
  DHE_RSA_AES_256_GCM_SHA384("dhe_rsa_aes_256_gcm_sha384", "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "(0x00,0x9F)", TlsVersion.TLS_1_2, false),
  DHE_RSA_AES_128_CCM("dhe_rsa_aes_128_ccm", "TLS_DHE_RSA_WITH_AES_128_CCM", "(0xC0,0x9E)", TlsVersion.TLS_1_2, false),
  DHE_RSA_AES_256_CCM("dhe_rsa_aes_256_ccm", "TLS_DHE_RSA_WITH_AES_256_CCM", "(0xC0,0x9F)", TlsVersion.TLS_1_2, false),
  // Mandatory
  AES_128_GCM_SHA256("aes_128_gcm_sha256", "TLS_AES_128_GCM_SHA256", "(0x13,0x01)", TlsVersion.TLS_1_3, true),
  AES_256_GCM_SHA384("aes_256_gcm_sha384", "TLS_AES_256_GCM_SHA384", "(0x13,0x02)", TlsVersion.TLS_1_3, true),
  // Optional (TLS 1.3)
  CHACHA20_POLY1305_SHA256("chacha20_poly1305_sha256", "TLS_CHACHA20_POLY1305_SHA256", "(0x13,0x03)", TlsVersion.TLS_1_3, false),
  AES_128_CCM_SHA256("aes_128_ccm_sha256", "TLS_AES_128_CCM_SHA256", "(0x13,0x04)", TlsVersion.TLS_1_3, false),
  AES_128_CCM_8_SHA256("aes_128_ccm_8_sha256", "TLS_AES_128_CCM_8_SHA256", "(0x13,0x05)", TlsVersion.TLS_1_3, false),
  // The empty renegotiation cipher suite is added automatically by clients that support secure renegotiation
  EMPTY_RENEGOTIATION_INFO_SCSV("empty_renegotiation_info_scsv", "TLS_EMPTY_RENEGOTIATION_INFO_SCSV", "(0x00,0xFF)", TlsVersion.TLS_1_2,
      false);

  @Getter
  private final String cipherSuiteId;
  @Getter
  private final String cipherSuiteName;
  @Getter
  private final String tlsTestToolCipherSuiteValue;
  @Getter
  private final TlsVersion tlsVersion;
  @Getter
  private final Boolean isMandatory;

  TlsCipherSuite(String cipherSuiteId, String cipherSuiteName, String tlsTestToolCipherSuiteValue, TlsVersion tlsVersion,
      Boolean isMandatory) {
    this.cipherSuiteId = cipherSuiteId;
    this.cipherSuiteName = cipherSuiteName;
    this.tlsTestToolCipherSuiteValue = tlsTestToolCipherSuiteValue;
    this.tlsVersion = tlsVersion;
    this.isMandatory = isMandatory;
  }

  /**
   * Resolve enum by readable cipher suite profile token used in feature files.
   *
   * @param cipherSuiteId profile token
   * @return matching profile
   */
  public static TlsCipherSuite fromCipherSuiteId(String cipherSuiteId) {
    return Arrays.stream(values())
        .filter(profile -> profile.cipherSuiteId.equals(cipherSuiteId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Unsupported ciphersuite : " + cipherSuiteId));
  }

  /**
   * Return all supported TLS 1.2 Cipher Suites.
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> supportedTls12CipherSuites() {
    return Arrays.stream(values())
        .filter(cs -> cs != EMPTY_RENEGOTIATION_INFO_SCSV)
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_2)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return all supported TLS 1.2 Cipher Suites without optional from TR-02102-2, Abschnitt 3.3.1 .
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> supportedTls12CipherSuitesWithoutOptional() {
    return Arrays.stream(values())
        .filter(cs -> cs != EMPTY_RENEGOTIATION_INFO_SCSV)
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_2)
        .filter(cs -> Boolean.TRUE.equals(cs.getIsMandatory()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return all supported TLS 1.3 Cipher Suites.
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> supportedTls13CipherSuites() {
    return Arrays.stream(values())
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_3)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return all supported TLS 1.3 Cipher Suites without optional from TR-02102-2, Abschnitt 3.3.1 .     *
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> supportedTls13CipherSuitesWithoutOptional() {
    return Arrays.stream(values())
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_3)
        .filter(cs -> Boolean.TRUE.equals(cs.getIsMandatory()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return optional TLS 1.2 Cipher Suites.
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> optionalTls12CipherSuites() {
    return Arrays.stream(values())
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_2)
        .filter(cs -> !Boolean.TRUE.equals(cs.getIsMandatory()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return optional TLS 1.3 Cipher Suites.
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> optionalTls13CipherSuites() {
    return Arrays.stream(values())
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_3)
        .filter(cs -> !Boolean.TRUE.equals(cs.getIsMandatory()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return mandatory TLS 1.2 Cipher Suites.
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> mandatoryTls12CipherSuites() {
    return Arrays.stream(values())
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_2)
        .filter(TlsCipherSuite::getIsMandatory)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return mandatory TLS 1.3 Cipher Suites.
   *
   * @return insertion-ordered set of policy-supported cipher suites
   */
  public static LinkedHashSet<TlsCipherSuite> mandatoryTls13CipherSuites() {
    return Arrays.stream(values())
        .filter(cs -> cs.getTlsVersion() == TlsVersion.TLS_1_3)
        .filter(TlsCipherSuite::getIsMandatory)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

}
