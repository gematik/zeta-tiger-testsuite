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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.model.tls.TlsCipherSuite;
import de.gematik.zeta.model.tls.TlsHashAlgorithm;
import de.gematik.zeta.model.tls.TlsLibrary;
import de.gematik.zeta.model.tls.TlsSignatureAlgorithm;
import de.gematik.zeta.model.tls.TlsVersion;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

/**
 * Builder for TLS test tool configuration snippets used by the TLS Cucumber steps.
 */
@Slf4j
public final class TlsTestToolConfigBuilder {

  /** Utility class. */
  private TlsTestToolConfigBuilder() {
  }

  /**
   * Builds a client-side TLS 1.2 test tool configuration.
   *
   * @param tlsClientHelloExtensions hex content for manipulated ClientHello extensions
   * @param tlsSupportedGroups hex content for the supported_groups extension
   * @param tlsCipherSuites tls-test-tool formatted cipher-suite configuration line
   * @param host host to be tested
   * @return tls-test-tool client configuration
   */
  public static @NonNull String buildTls12ClientConfig(
      String tlsClientHelloExtensions,
      String tlsSupportedGroups,
      String tlsCipherSuites,
      String host) {
    var basicConfiguration = """
        # TLS Test Tool configuration file
        mode=client
        tlsLibrary=mbed TLS
        waitBeforeClose=5
        logLevel=low
        tlsVersion=(3,3)
        handshakeType=normal
        tlsSecretFile=tlsSecretFile.txt
        """;
    var sniExtension = buildSniExtensionHex(host);
    var manipulateClientHelloExtensions = "manipulateClientHelloExtensions="
        + sniExtension + tlsClientHelloExtensions + tlsSupportedGroups + "\n";
    return basicConfiguration
        + tlsCipherSuites
        + manipulateClientHelloExtensions
        + "port=443\n"
        + "host=" + host + "\n";
  }

  /**
   * Builds a TLS-1.3 client-side TLS test tool configuration.
   *
   * @param tlsSignatureSchemes tls-test-tool formatted TLS-1.3 signature-schemes value
   * @param tlsSupportedGroups TLS-1.3 supported_groups value
   * @param tlsCipherSuites tls-test-tool formatted cipher-suite configuration line
   * @param host host to be tested
   * @return tls-test-tool client configuration
   */
  public static @NonNull String buildTls13ClientConfig(
      String tlsSignatureSchemes,
      String tlsSupportedGroups,
      String tlsCipherSuites,
      String host) {
    var basicConfiguration = """
        # TLS Test Tool configuration file
        mode=client
        tlsLibrary=OpenSSL
        waitBeforeClose=5
        logLevel=low
        tlsUseSni=true
        tlsVersion=(3,4)
        handshakeType=normal
        tlsSecretFile=tlsSecretFile.txt
        """;

    return basicConfiguration
        + "tlsCipherSuites=" + tlsCipherSuites + "\n"
        + "tlsSupportedGroups=" + tlsSupportedGroups + "\n"
        + "tlsSignatureSchemes=" + tlsSignatureSchemes + "\n"
        + "port=443\n"
        + "host=" + host + "\n";
  }

  /**
   * Builds a default TLS Test Tool server configuration with additional cipher-suite configuration.
   *
   * @param tlsCipherSuites tls-test-tool formatted cipher-suite configuration line
   * @return tls-test-tool server configuration
   */
  public static @NonNull String buildTls12ServerBaseConfig(String tlsCipherSuites) {
    return buildTls12ServerBaseConfig()
        + tlsCipherSuites;
  }

  /**
   * Builds a default TLS Test Tool server configuration.
   *
   * @return tls-test-tool server configuration for mbed TLS
   */
  public static @NonNull String buildTls12ServerBaseConfig() {
    return buildTls12ServerBaseConfig(TlsLibrary.MBED_TLS);
  }

  /**
   * Builds a default TLS Test Tool server configuration.
   *
   * @param tlsLib TLS library used by tls-test-tool
   * @return tls-test-tool server configuration
   */
  public static @NonNull String buildTls12ServerBaseConfig(TlsLibrary tlsLib) {
    var tlsTestToolPort = TigerGlobalConfiguration.readStringOptional("tlsTestTool.port")
        .orElse("");

    if (tlsTestToolPort.isBlank()) {
      throw new AssertionError("TLS test tool configuration tlsTestTool.port could not be resolved.");
    }

    var basicConfiguration = """
        # TLS Test Tool configuration file
        host=0.0.0.0
        waitBeforeClose=5
        logLevel=low
        listenTimeout=60
        tlsVersion=(3,3)
        mode=server
        tlsSecretFile=tlsSecretFile.txt
        """;
    return basicConfiguration
        + "tlsLibrary=" + tlsLib.getDisplayName() + "\n"
        + "port=" + tlsTestToolPort + "\n";
  }

  /**
   * Builds a default TLS 1.3 TLS Test Tool server configuration.
   *
   * @param tlsLib TLS library used by tls-test-tool
   * @return tls-test-tool server configuration
   */
  public static @NonNull String buildTls13ServerBaseConfig(TlsLibrary tlsLib) {
    return buildTls12ServerBaseConfig(tlsLib)
        .replace("tlsVersion=(3,3)\n", "tlsVersion=(3,4)\n");
  }

  /**
   * Builds the default TLS 1.2 cipher-suite list used for broad positive handshake tests.
   *
   * @return tls-test-tool formatted cipher-suite configuration line
   */
  public static @NonNull String buildTls12ValidCipherSuites() {
    log.info(
        """
            The TLS 1.2 ClientHello offers the following supported TLS 1.2 cipher suites (also those in accordance with TR-02102-2, Chapter 3.3.1 Table 2):
            TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256
            TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
            TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
            TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
            TLS_ECDHE_ECDSA_WITH_AES_128_CCM
            TLS_ECDHE_ECDSA_WITH_AES_256_CCM
            TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256
            TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384
            TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            TLS_DHE_DSS_WITH_AES_128_CBC_SHA256
            TLS_DHE_DSS_WITH_AES_256_CBC_SHA256
            TLS_DHE_DSS_WITH_AES_128_GCM_SHA256
            TLS_DHE_DSS_WITH_AES_256_GCM_SHA384
            TLS_DHE_RSA_WITH_AES_128_CBC_SHA256
            TLS_DHE_RSA_WITH_AES_256_CBC_SHA256
            TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
            TLS_DHE_RSA_WITH_AES_256_GCM_SHA384
            TLS_DHE_RSA_WITH_AES_128_CCM
            TLS_DHE_RSA_WITH_AES_256_CCM""");

    return
        "tlsCipherSuites=(0xC0,0x23),(0xC0,0x24),(0xC0,0x2B),(0xC0,0x2C),(0xC0,0xAC),(0xC0,0xAD),"
            + "(0xC0,0x27),(0xC0,0x28),(0xC0,0x2F),(0xC0,0x30),(0x00,0x40),(0x00,0x6A),(0x00,0xA2),"
            + "(0x00,0xA3),(0x00,0x67),(0x00,0x6B),(0x00,0x9E),(0x00,0x9F),(0xC0,0x9E),(0xC0,0x9F)\n";
  }

  /**
   * Builds the default TLS 1.3 cipher-suite list used for broad positive handshake tests.
   *
   * @return tls-test-tool formatted cipher-suite configuration line
   */
  public static @NonNull String buildTls13ValidCipherSuites() {
    var mandatoryTls13CipherSuites = TlsCipherSuite.mandatoryTls13CipherSuites();

    log.info("The TLS 1.3 ClientHello offers the following mandatory TLS 1.3 cipher suites in accordance with A_28868:");
    mandatoryTls13CipherSuites.stream()
        .map(TlsCipherSuite::getCipherSuiteName)
        .forEach(log::info);

    return mandatoryTls13CipherSuites.stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.joining(","));
  }

  /**
   * Builds the TLS 1.2 ECDHE-only cipher-suite list for curve-focused tests.
   *
   * @return tls-test-tool formatted cipher-suite configuration line
   */
  public static @NonNull String buildTls12ValidEcdheCipherSuites() {
    log.info(
        """
            The TLS 1.2 ClientHello offers the following supported ECDHE TLS 1.2 cipher suites (also those in accordance with TR-02102-2, Chapter 3.3.1 Table 2):
            TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256
            TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
            TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
            TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
            TLS_ECDHE_ECDSA_WITH_AES_128_CCM
            TLS_ECDHE_ECDSA_WITH_AES_256_CCM
            TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256
            TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384
            TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384""");

    return "tlsCipherSuites=(0xC0,0x23),(0xC0,0x24),(0xC0,0x2B),(0xC0,0x2C),(0xC0,0xAC),(0xC0,0xAD),"
        + "(0xC0,0x27),(0xC0,0x28),(0xC0,0x2F),(0xC0,0x30)\n";
  }

  /**
   * Builds a tls-test-tool signature/hash configuration value from the provided TLS 1.2 hashes.
   *
   * @param hashes insertion-ordered set of TLS 1.2 hash algorithms to advertise
   * @return comma-separated tuple list suitable for {@code tlsSignatureAlgorithms=...}
   */
  public static @NonNull String buildTls12SupportedSignatureHashPairs(LinkedHashSet<TlsHashAlgorithm> hashes) {
    if (hashes == null || hashes.isEmpty()) {
      throw new AssertionError("At least one TLS 1.2 hash algorithm is required.");
    }

    var supportedSignatureAlgos = TlsSignatureAlgorithm.getSupportedSignatureAlgorithms();
    return hashes.stream()
        .flatMap(hashAlgorithm -> supportedSignatureAlgos.stream()
            .map(signatureAlgorithm -> "(" + signatureAlgorithm.getValue() + "," + hashAlgorithm.getValue() + ")"))
        .collect(Collectors.joining(","));
  }

  /**
   * Builds a TLS-1.3 server configuration for all policy-supported cipher suites.
   *
   * @return generated TLS test tool server configuration
   */
  public static @NonNull String buildTls13ServerConfigForSupportedCipherSuites() {
    return buildTls13ServerConfig(buildSupportedCipherSuitesValue(TlsVersion.TLS_1_3));
  }

  /**
   * Builds a TLS-1.2 server configuration for the provided cipher suites and optional extra config lines.
   *
   * @param cipherSuites cipher suites in tls-test-tool tuple syntax
   * @param extraConfigLines additional config lines without trailing newline
   * @return generated TLS test tool server configuration
   */
  public static @NonNull String buildTls12ServerConfig(String cipherSuites, String... extraConfigLines) {
    return buildTlsServerConfig(buildTls12ServerBaseConfig(), cipherSuites, extraConfigLines);
  }

  /**
   * Builds a TLS-1.2 server configuration for the selected TLS library.
   *
   * @param tlsLibrary TLS implementation used by the test server
   * @param cipherSuites cipher suites in tls-test-tool tuple syntax
   * @param extraConfigLines additional config lines without trailing newline
   * @return generated TLS test tool server configuration
   */
  public static @NonNull String buildTls12ServerConfig(
      TlsLibrary tlsLibrary, String cipherSuites, String... extraConfigLines) {
    return buildTlsServerConfig(
        buildTls12ServerBaseConfig(tlsLibrary), cipherSuites, extraConfigLines);
  }

  /**
   * Builds a TLS-1.3 server configuration for the provided cipher suites and optional extra config lines.
   *
   * @param cipherSuites cipher suites in tls-test-tool tuple syntax
   * @param extraConfigLines additional config lines without trailing newline
   * @return generated TLS test tool server configuration
   */
  public static @NonNull String buildTls13ServerConfig(String cipherSuites, String... extraConfigLines) {
    return buildTlsServerConfig(buildTls13ServerBaseConfig(TlsLibrary.OPENSSL), cipherSuites, extraConfigLines);
  }

  /**
   * Returns all supported cipher suites for the given TLS version in tls-test-tool tuple syntax.
   *
   * @param tlsVersion TLS version whose supported cipher suites should be returned
   * @return comma-separated supported cipher suites
   */
  public static @NonNull String buildSupportedCipherSuitesValue(TlsVersion tlsVersion) {
    if (tlsVersion == null) {
      throw new AssertionError("The TLS version is null.");
    }

    var cipherSuites = switch (tlsVersion) {
      case TLS_1_2 -> TlsCipherSuite.supportedTls12CipherSuites().stream();
      case TLS_1_3 -> TlsCipherSuite.supportedTls13CipherSuites().stream();
    };

    return cipherSuites
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.joining(","));
  }

  /**
   * Build a complete SNI extension body for a given host.
   *
   * @param host raw host value from the feature file
   * @return hex encoded SNI extension including type and length fields
   */
  static @NonNull String buildSniExtensionHex(String host) {
    var sniHost = normalizeHostForSni(host);
    var hostBytes = sniHost.getBytes(StandardCharsets.US_ASCII);
    var serverNameLen = 1 + 2 + hostBytes.length;
    var extensionDataLen = 2 + serverNameLen;

    var sb = new StringBuilder();
    sb.append("0000");
    sb.append(String.format("%04x", extensionDataLen));
    sb.append(String.format("%04x", serverNameLen));
    sb.append("00");
    sb.append(String.format("%04x", hostBytes.length));
    for (var b : hostBytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * Resolve host-only value for SNI. Accept plain host, host:port or URL.
   *
   * @param host raw host value from the scenario
   * @return normalized host without scheme or port
   */
  static @NonNull String normalizeHostForSni(String host) {
    var candidate = host.trim();
    if (candidate.startsWith("[")) {
      var closingBracket = candidate.indexOf(']');
      if (closingBracket > 1) {
        return candidate.substring(1, closingBracket);
      }
    }
    if (candidate.contains("://")) {
      try {
        var uri = new URI(candidate);
        if (uri.getHost() != null && !uri.getHost().isBlank()) {
          return uri.getHost();
        }
      } catch (URISyntaxException ignored) {
        // Fallback below.
      }
    }
    var colonCount = candidate.chars().filter(ch -> ch == ':').count();
    if (colonCount > 1) {
      return candidate;
    }
    if (candidate.contains(":") && !candidate.startsWith("[")) {
      return candidate.substring(0, candidate.indexOf(':'));
    }
    return candidate;
  }

  /**
   * Builds a TLS server configuration from a base config, cipher suites, and optional extra config lines.
   *
   * @param baseConfig TLS server base configuration
   * @param cipherSuites cipher suites in tls-test-tool tuple syntax
   * @param extraConfigLines additional config lines without trailing newline
   * @return generated TLS test tool server configuration
   */
  private static @NonNull String buildTlsServerConfig(String baseConfig, String cipherSuites, String... extraConfigLines) {
    if (cipherSuites == null || cipherSuites.isBlank()) {
      throw new AssertionError("The Cipher Suite value is empty or null.");
    }

    var configBuilder = new StringBuilder(baseConfig)
        .append("tlsCipherSuites=")
        .append(cipherSuites)
        .append("\n");

    if (extraConfigLines != null) {
      for (var extraConfigLine : extraConfigLines) {
        if (extraConfigLine != null && !extraConfigLine.isBlank()) {
          configBuilder.append(extraConfigLine).append("\n");
        }
      }
    }
    return configBuilder.toString();
  }
}
