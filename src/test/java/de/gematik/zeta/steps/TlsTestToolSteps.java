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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.TlsTestToolServiceFactory;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.ParameterType;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.assertj.core.api.Assertions;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1ParsingException;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.jspecify.annotations.NonNull;


/**
 * Cucumber step definitions for TLS Test Tool operations.
 *
 * <p>This class provides step definitions for the TLS Tests.</p>
 */
@Slf4j
public class TlsTestToolSteps {

  /**
   * Supported Groups required for the TLS handshake (GS-A_4384-03). secp256r1 secp384r1 brainpoolP256r1 brainpoolP384r1
   */
  private static final String VALID_SUPPORTED_GROUPS = "000a000a000800170018001a001b";

  /**
   * RSA based SignatureHash Algorithms RSA_MD5, RSA_SHA1, RSA_SHA224, RSA_SHA256, RSA_SHA384 and RSA_SHA512.
   */
  private static final String RSA_HASH_VARIANTS = "000d000e000C010102010301040105010601";

  /**
   * SignatureHash Algorithms RSA_SHA256, RSA_SHA384, RSA_SHA512, DSA_SHA256, DSA_SHA384, DSA_SHA512, ECDSA_SHA256, ECDSA_SHA384,
   * ECDSA_SHA512.
   */
  private static final String SUPPORTED_SIGNATURE_HASH_ALGOS = "000d00140012040105010601040205020602040305030603";

  /**
   * Unsupported SignatureHash Algorithms RSA_MD5, RSA_SHA1, RSA_SHA224, DSA_MD5, DSA_SHA1, DSA_SHA224, ECDSA_MD5, ECDSA_SHA1,
   * ECDSA_SHA224.
   */
  private static final String UNSUPPORTED_SIGNATURE_HASH_ALGOS = "000d00140012010102010301010202020302010302030303";

  /**
   * The OID of the sha256withRSA signature algorithm.
   */
  private static final String OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11";

  /**
   * The OID of the ecdsa-with-SHA256 signature algorithm.
   */
  private static final String OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2";

  /**
   * The OID of the brainpoolP256r1 curve.
   */
  private static final String OID_BRAINPOOL256R1 = "1.3.36.3.3.2.8.1.1.7";

  /**
   * The OID of the P-256 / secp256r1 / prime256v1 curve.
   */
  private static final String OID_P256 = "1.2.840.10045.3.1.7";

  /**
   * The marker to indicate that renegotiation has started.
   */
  private static final String RENEG_MARKER = "Performing renegotiation.";

  /**
   * The marker to indicate that the handshake is complete.
   */
  private static final String FINISHED_MARKER = "Valid Finished message received.";

  /**
   * The minimum allowed RSA public key length.
   */
  private static final int MIN_ALLOWED_RSA_KEY_LENGTH = 3000;

  /**
   * Precompiled regex patterns.
   */
  private static final Pattern ALERT_LEVEL_PATTERN = Pattern.compile("Alert\\.level=([0-9a-fA-F]+)");
  private static final Pattern ALERT_DESCRIPTION_PATTERN = Pattern.compile("Alert\\.description=([0-9a-fA-F]+)");
  private static final Pattern TLS_HANDSHAKE_FAILED_PATTERN = Pattern.compile("TLS handshake failed:.*");
  private static final Pattern TLS_CIPHER_SUITE_PATTERN = Pattern.compile("Cipher suite:\\s*(.+)");
  private static final Pattern TLS_HASH_ALGORITHM_PATTERN = Pattern.compile("Server used HashAlgorithm\\s+(\\d+)");
  private static final Pattern TLS_SIGNATURE_ALGORITHM_PATTERN = Pattern.compile("Server used SignatureAlgorithm\\s+(\\d+)");
  private static final Pattern TLS_RENEGOTIATION_PHASE_PATTERN = Pattern.compile("=>\\s*renegotiate");
  private static final Pattern TLS_CLIENT_HELLO_SENT_PATTERN = Pattern.compile("ClientHello message transmitted\\.");
  private static final Pattern TLS_CLIENT_HELLO_WRITE_PATTERN = Pattern.compile("=>\\s*write client hello");
  private static final Pattern CERTIFICATE_LIST_PATTERN = Pattern.compile("Certificate\\.certificate_list\\[0\\]=([0-9a-fA-F \\t]+)");
  private static final Pattern HEX_BYTE_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{2}\\b");
  private static final Pattern SERVER_HELLO_EXTENSIONS_PATTERN =
      Pattern.compile("ServerHello\\.extensions\\s*=\\s*([0-9a-fA-F]{2}(?:\\s+[0-9a-fA-F]{2})*)?");
  private static final Pattern CLIENT_HELLO_EXTENSIONS_OPTIONAL_PATTERN =
      Pattern.compile("(?m)^.*ClientHello\\.extensions\\s*=\\s*([0-9a-fA-F]{2}(?:[ \\t]+[0-9a-fA-F]{2})*)?[ \\t]*$");
  private static final Pattern CLIENT_HELLO_EXTENSIONS_PATTERN =
      Pattern.compile("(?m)^.*ClientHello\\.extensions=([0-9a-fA-F]{2}(?:[ \\t]+[0-9a-fA-F]{2})*)[ \\t]*$");
  private static final Pattern CLIENT_HELLO_CIPHER_SUITES_PATTERN =
      Pattern.compile("(?m)^.*ClientHello\\.cipher_suites=([0-9a-fA-F]{2}(?:[ \\t]+[0-9a-fA-F]{2})*)[ \\t]*$");
  private static final String CONFIG_LINE_PATTERN_TEMPLATE = "(?m)^%s=(.+)$";

  /**
   * String for storing the TLS logs.
   */
  private String tlsLogs;

  /**
   * The TLS Test Tool process.
   */
  private CompletableFuture<String> tlsLogsFuture;

  /**
   * Stores the TLS 1.2 hash algorithms that were intentionally offered in the previous setup step. This allows failure messages to explain
   * what was tested versus what the server selected.
   */
  private LinkedHashSet<TlsHashAlgorithm> lastOfferedTls12HashAlgorithms = new LinkedHashSet<>();

  /**
   * Build a complete SNI (server_name) extension body for a given host. Format: extension_type(2) + extension_length(2) +
   * server_name_list_length(2) + name_type(1) + host_name_length(2) + host_name.
   *
   * @param host raw host value from the feature file (host, host:port or URL)
   * @return hex encoded SNI extension including type and length fields
   */
  private static @NonNull String buildSniExtensionHex(String host) {
    var sniHost = normalizeHostForSni(host);
    var hostBytes = sniHost.getBytes(StandardCharsets.US_ASCII);
    var serverNameLen = 1 + 2 + hostBytes.length;
    var extensionDataLen = 2 + serverNameLen;

    var sb = new StringBuilder();
    sb.append("0000"); // server_name extension type
    sb.append(String.format("%04x", extensionDataLen));
    sb.append(String.format("%04x", serverNameLen));
    sb.append("00"); // host_name
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
  private static @NonNull String normalizeHostForSni(String host) {
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
      // Plain IPv6 literal without scheme/port.
      return candidate;
    }
    if (candidate.contains(":") && !candidate.startsWith("[")) {
      return candidate.substring(0, candidate.indexOf(':'));
    }
    return candidate;
  }

  /**
   * Builds a client-side TLS test tool configuration.
   *
   * @param tlsClientHelloExtensions hex content for manipulated ClientHello extensions
   * @param tlsSupportedGroups hex content for the supported_groups extension
   * @param tlsCipherSuites tls-test-tool formatted cipher-suite configuration line
   * @param host host to be tested
   * @return tls-test-tool client configuration
   */
  private static @NonNull String getTlsTestToolConfigBuffer(String tlsClientHelloExtensions, String tlsSupportedGroups,
      String tlsCipherSuites, String host) {
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
    // We manipulate extensions intentionally for several tests. Keep SNI present explicitly,
    // because replacing the extension block can otherwise remove it and cause generic
    // protocol-version alerts from ingress/frontends before crypto checks are reached.
    var sniExtension = buildSniExtensionHex(host);
    var manipulateClientHelloExtensions = "manipulateClientHelloExtensions="
        + sniExtension + tlsClientHelloExtensions + tlsSupportedGroups + "\n";
    var port = "port=443\n";
    return basicConfiguration
        + tlsCipherSuites
        + manipulateClientHelloExtensions
        + port
        + "host=" + host + "\n";
  }

  /**
   * Builds a default TLS Test Tool server configuration with additional cipher-suite configuration.
   *
   * @param tlsCipherSuites tls-test-tool formatted cipher-suite configuration line
   * @return tls-test-tool server configuration
   */
  private static @NonNull String getTlsTestToolServerBaseConfig(String tlsCipherSuites) {
    return getTlsTestToolServerBaseConfig()
        + tlsCipherSuites;
  }

  /**
   * Builds a default TLS Test Tool server configuration.
   *
   * @return tls-test-tool server configuration for Mbed TLS
   */
  private static @NonNull String getTlsTestToolServerBaseConfig() {
    return getTlsTestToolServerBaseConfig(TlsLibrary.MBED_TLS);
  }

  /**
   * Builds a default TLS Test Tool server configuration.
   *
   * @param tlsLib            TLS library used by tls-test-tool
   * @return tls-test-tool server configuration
   */
  private static @NonNull String getTlsTestToolServerBaseConfig(TlsLibrary tlsLib) {

    // Get the TLS Test Tool Server Port from the deployment configuration
    String tlsTestToolPort = TigerGlobalConfiguration.readStringOptional("tlsTestTool.port")
        .orElse("");

    if (tlsTestToolPort.isBlank()) {
      throw new AssertionError("TLS test tool configuration tlsTestTool.port could not be resolved.");
    }

    String basicConfiguration = """
        # TLS Test Tool configuration file
        host=0.0.0.0
        waitBeforeClose=5
        logLevel=low
        listenTimeout=60
        tlsVersion=(3,3)
        mode=server
        tlsSecretFile=tlsSecretFile.txt
        """;
    String library = "tlsLibrary=" + tlsLib.getDisplayName() + "\n";
    String port = "port=" + tlsTestToolPort + "\n";
    return basicConfiguration
        + library
        + port;
  }

  /**
   * Builds the default TLS 1.2 cipher-suite list used for broad positive handshake tests.
   *
   * @return tls-test-tool formatted cipher-suite configuration line
   */
  private static @NonNull String getTlsTestValidCipherSuites() {
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
   * Builds the TLS 1.2 ECDHE-only cipher-suite list for curve-focused tests.
   *
   * @return tls-test-tool formatted cipher-suite configuration line
   */
  private static @NonNull String getTlsTestValidEcdheCipherSuites() {
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
   * Parses DER-encoded certificate bytes as an {@link X509Certificate}.
   *
   * @param der certificate bytes in DER format
   * @return parsed {@link X509Certificate}
   * @throws CertificateException if parsing fails
   */
  private static X509Certificate parseAsX509(byte[] der) throws CertificateException {
    var cf = CertificateFactory.getInstance("X.509");
    try (InputStream in = new ByteArrayInputStream(der)) {
      var c = cf.generateCertificate(in);
      if (!(c instanceof X509Certificate x509)) {
        throw new AssertionError(
            "Parsed certificate is not an X509Certificate (type=" + c.getType() + ")");
      }
      return x509;
    } catch (IOException ioe) {
      // Should not happen with ByteArrayInputStream, but keep it clean
      throw new CertificateException("I/O error while parsing certificate", ioe);
    }
  }

  /**
   * Converts a Windows path to an equivalent WSL path.
   *
   * @param winPath Windows path to convert
   * @return converted WSL path
   */
  private static String toWslPath(String winPath) {
    if (winPath == null || winPath.isBlank()) {
      throw new AssertionError("The Windows path is empty or null.");
    }

    // Normalize separators
    var p = winPath.replace("\\", "/");

    // Extract drive letter
    if (p.length() >= 2 && p.charAt(1) == ':') {
      var drive = Character.toLowerCase(p.charAt(0));
      p = "/mnt/" + drive + p.substring(2);
    }
    return p;
  }

  /**
   * Checks whether the path has the Windows path format.
   *
   * @param path path to check
   * @return {@code true} if the path is a Windows path
   */
  private static boolean isWindowsPath(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    return path.matches("^[a-zA-Z]:[/\\\\].*") || path.startsWith("\\\\");
  }

  /**
   * Checks whether the process runs inside WSL.
   */
  private static boolean isWslEnvironment() {
    return System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null;
  }

  /**
   * Extracts the certificate bytes from the log line with Certificate.certificate_list[0]=...
   *
   * @param fullLog full TLS log content
   * @return DER certificate bytes or {@code null} if not present
   */
  private static byte[] extractCertificateFromLog(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      return null;
    }

    var m = CERTIFICATE_LIST_PATTERN.matcher(fullLog);
    if (!m.find()) {
      return null;
    }

    var raw = m.group(1);
    var b = HEX_BYTE_PATTERN.matcher(raw);
    var byteCount = 0;
    while (b.find()) {
      byteCount++;
    }
    if (byteCount == 0) {
      return null;
    }
    var normalized = new StringBuilder(byteCount * 2);
    b.reset();
    while (b.find()) {
      normalized.append(b.group());
    }
    try {
      return Hex.decodeHex(normalized.toString());
    } catch (DecoderException e) {
      return null;
    }
  }

  /**
   * Extracts the named curve OID from the certificate.
   *
   * @param cert X509 certificate
   * @return named curve OID (e.g. "1.2.840.10045.3.1.7") or null if not EC / not named curve
   */
  private static String getNamedCurveOid(X509Certificate cert) {

    if (cert == null) {
      return null;
    }

    var pk = cert.getPublicKey();

    if (!(pk instanceof ECPublicKey)) {
      return null;
    }
    try {

      var spki = SubjectPublicKeyInfo.getInstance(pk.getEncoded());

      // AlgorithmIdentifier parameters for id-ecPublicKey are typically the namedCurve OID
      if (!spki.getAlgorithm().getAlgorithm().equals(X9ObjectIdentifiers.id_ecPublicKey)) {
        return null;
      }

      var params = spki.getAlgorithm().getParameters();
      if (params == null) {
        return null;
      }

      var curveOid = ASN1ObjectIdentifier.getInstance(params);
      return curveOid.getId();

    } catch (NullPointerException | IllegalArgumentException | ASN1ParsingException e) {
      return null;
    }
  }

  /**
   * Resolve the certificate path.
   *
   * @param certificate certificate or key file name
   * @return resolved file path, converted to WSL format if required
   */
  private static String resolveCertificateOrKeyPath(String certificate) {

    if (certificate == null || certificate.isBlank()) {
      throw new AssertionError("The certificate is empty or null.");
    }

    var configuredPath = TigerGlobalConfiguration.readStringOptional("tlsTestTool.certificateDirectoryPath")
        .orElseThrow(() -> new AssertionError(
            "The config key 'tlsTestTool.certificateDirectoryPath' could not be resolved."));
    var baseDirectory = Path.of(System.getProperty("user.dir"), configuredPath).normalize();
    var certificateLocation = baseDirectory.resolve(certificate).normalize();
    if (!certificateLocation.startsWith(baseDirectory)) {
      throw new AssertionError("The certificate path is invalid.");
    }

    return certificateLocation.toString();
  }

  /**
   * Configures and runs the TLS test tool (server) for TLS 1.1.
   *
   */
  @Gegebensei("die TlsTestTool-Server-Konfigurationsdaten wurden nur für TLS 1.1 erstellt")
  @Given("the TlsTestTool server configuration data with only TLS 1.1 is created")
  public void setTlsTestToolServerConfigForTls1_1() {

    String tlsTestToolConfigBuffer = getTlsTestToolServerBaseConfig()
        + "manipulateHelloVersion=(0x03,0x02)\n";

    log.info("The Server only offers a TLS 1.1 connection.");

    runTlsTestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.1.
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} wurden nur für TLS 1.1 erstellt")
  @Given("the TlsTestTool configuration data for the host {tigerResolvedString} with only TLS 1.1 is created")
  public void setTlsTestToolConfigForTls1_1(String host) {
    checkHost(host);

    var basicConfiguration = """
        # TLS Test Tool configuration file
        mode=client
        tlsLibrary=mbed TLS
        waitBeforeClose=5
        logLevel=low
        tlsVersion=(3,2)
        tlsUseSni=true
        handshakeType=normal
        tlsSecretFile=tlsSecretFile.txt
        """;
    var port = "port=443\n";
    var tlsTestToolConfigBuffer = basicConfiguration
        + port
        + "host=" + host + "\n";

    log.info("The Client only offers a TLS 1.1 connection.");

    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.3 with unsupported RSA Signature Schemes.
   *
   * @param host             Host to be tested
   * @param signatureSchemes Signature Schemes that must not be supported
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit den folgenden TLS 1.3 Signature-Schemes wurden festgelegt:")
  @Given("the TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.3 signature schemes:")
  public void setTlsTestToolConfigForUnsupportedSignatureSchemes(String host,
      DataTable signatureSchemes) {
    checkHost(host);
    if (signatureSchemes == null) {
      throw new AssertionError("The signature schemes table is null.");
    }

    // Check if all the supported/mandatory SignatureSchemes are present
    var signatureSchemeHashSet = parseNonEmptyFirstColumn(signatureSchemes, HashSet::new);

    var supportedSignatureSchemes = Arrays.stream(SignatureSchemes.values())
        .map(Enum::toString)
        .collect(Collectors.toCollection(HashSet::new));

    if (!supportedSignatureSchemes.equals(signatureSchemeHashSet)) {
      throw new AssertionError("""
          For this test, the signature hash algorithms cannot be changed.
          They must be:
          - rsa_pkcs1_sha256
          - rsa_pkcs1_sha384
          - rsa_pkcs1_sha512""");
    }

    log.info(
        "The TLS 1.3 ClientHello offers the following TLS 1.3 cipher suites in accordance with TR-02102-2:");
    log.info("TLS_AES_128_GCM_SHA256");
    log.info("TLS_AES_256_GCM_SHA384");
    log.info("TLS_AES_128_CCM_SHA256");
    var tlsCipherSuites = "tlsCipherSuites=(0x13,0x01),(0x13,0x02),(0x13,0x04)\n";

    var tlsSignatureSchemes = "tlsSignatureSchemes=(0x04,0x01),(0x05,0x01),(0x06,0x01)\n";
    log.info("ClientHello only offers the following TLS 1.3 signature schemes:");
    signatureSchemeHashSet.forEach(log::info);

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
    var port = "port=443\n";
    var tlsTestToolConfigBuffer = basicConfiguration
        + tlsCipherSuites
        + tlsSignatureSchemes
        + port
        + "host=" + host + "\n";

    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2 with unsupported RSA Signature and hash algorithms.
   *
   * @param host                    Host to be tested
   * @param signatureHashAlgorithms Signature and hash algorithms that must not be supported
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit den folgenden TLS 1.2 Signatur-Hash-Algorithmen wurden festgelegt:")
  @Given("the TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.2 signature hash algorithms:")
  public void setTlsTestToolConfigForUnsupportedSignatureHashAlgorithms(String host,
      DataTable signatureHashAlgorithms) {

    checkHost(host);
    if (signatureHashAlgorithms == null) {
      throw new AssertionError("The signature hash algorithms table is null.");
    }

    // Check if all the supported/mandatory SignatureAndHashAlgorithms are present
    var signatureAlgorithmHashSet = parseNonEmptyFirstColumn(signatureHashAlgorithms, HashSet::new);

    var supportedSignatureAndHashAlgorithms = Arrays.stream(SignatureAndHashAlgorithms.values())
        .map(Enum::toString)
        .collect(Collectors.toCollection(HashSet::new));

    if (!supportedSignatureAndHashAlgorithms.equals(signatureAlgorithmHashSet)) {
      throw new AssertionError("""
          For this test, the signature hash algorithms cannot be changed.
          They must be:
          - RSA_MD5
          - RSA_SHA1
          - RSA_SHA224
          - RSA_SHA256
          - RSA_SHA384
          - RSA_SHA512""");
    }

    runTls12ClientScenario(
        host,
        getTlsTestValidCipherSuites(),
        RSA_HASH_VARIANTS,
        VALID_SUPPORTED_GROUPS,
        supportedSignatureAndHashAlgorithms,
        false);
  }

  /**
   * Checks whether a TLS “alert” has been received.
   */
  @Dann("akzeptiert der ZETA Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id {string}")
  @Dann("akzeptiert der ZETA Client das ServerHello nicht und sendet eine Alert Nachricht mit Description Id {string}")
  @Then("the Zeta Guard endpoint does not accept the ClientHello and sends an alert message with the description id {string}")
  @Then("the Zeta Client does not accept the ServerHello and sends an alert message with the description id {string}")
  public void endpointSendsAlertWithDescription(String descriptionId) {
    requireTlsLogs();

    // Check the logs for the "Alert message received" and "Alert.level=02" alert messages
    Assertions
        .assertThat(tlsLogs.contains("Alert message received"))
        .withFailMessage(
            "'Alert' message not received from endpoint. %s %s",
            extractAlertSummary(),
            buildHashNegotiationSummary())
        .isTrue();
    Assertions
        // Alert.level=02 indicates a fatal alert
        .assertThat(tlsLogs.contains("Alert.level=02"))
        .withFailMessage(
            "'Alert.level=02' not found in TLS logs. %s %s",
            extractAlertSummary(),
            buildHashNegotiationSummary())
        .isTrue();
    Assertions
        .assertThat(tlsLogs.contains("Alert.description=" + descriptionId))
        .withFailMessage(
            "Alert.description=%s not found in TLS logs. %s %s",
            descriptionId,
            extractAlertSummary(),
            buildHashNegotiationSummary())
        .isTrue();
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2.
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString}")
  public void setValidTlsTestToolConfig(String host) {
    var tlsCipherSuites = getTlsTestValidCipherSuites();
    runTls12TestTool(host, tlsCipherSuites);
  }

  /**
   * Checks whether a ServerHello record was not received.
   */
  @Dann("wird der ServerHello-Record nicht empfangen")
  @Then("the ServerHello record is not received")
  public void checkIfTheServerHelloIsNotReceived() {
    requireTlsLogs();

    var p = Pattern.compile(
        "ServerHello\\.cipher_suite\\s*=\\s*([0-9a-fA-F]{2})\\s+([0-9a-fA-F]{2})");
    var m = p.matcher(tlsLogs);

    var hi = 0;
    var lo = 0;
    if (m.find()) {
      hi = Integer.parseInt(m.group(1), 16);
      lo = Integer.parseInt(m.group(2), 16);
    }

    Assertions
        .assertThat(!(tlsLogs.contains("ServerHello.cipher_suite")))
        .withFailMessage("ServerHello message received where not expected. "
            + "Server selected cipher suite (0x%x,0x%x).", hi, lo)
        .isTrue();
  }

  /**
   * Checks whether no Server Key Exchange record is sent.
   */
  @Dann("wird der Server-Key-Exchange-Datensatz nicht gesendet")
  @Then("the server key exchange record is not sent")
  public void checkIfTheServerKeyExchangeIsNotSent() {
    requireTlsLogs();
    Assertions
        .assertThat(!(tlsLogs.contains("ServerKeyExchange.params.curve_params.namedcurve")
            || tlsLogs.contains("Bad ServerKeyExchange message received")))
        .withFailMessage("ServerKeyExchange message received.")
        .isTrue();
  }

  /**
   * Checks whether a Server Key exchange record is sent.
   */
  @Dann("wird der Server-Key-Exchange-Datensatz gesendet")
  @Then("the server key exchange record is sent")
  public void checkIfTheServerKeyExchangeIsSent() {
    requireTlsLogs();
    Assertions
        .assertThat(tlsLogs.contains("ServerKeyExchange.params.curve_params.namedcurve"))
        .withFailMessage("ServerKeyExchange message not received.")
        .isTrue();
  }

  /**
   * Configures and runs TLS 1.2 with the provided cipher-suite list.
   *
   * @param host                Host to be tested
   * @param cipherSuiteHexValue Hex value of the cipher suite(s) to be tested
   */
  private void configureTls12ForCipherSuites(String host, String cipherSuiteHexValue) {
    if (cipherSuiteHexValue == null || cipherSuiteHexValue.isBlank()) {
      throw new AssertionError("The Cipher Suite value is empty or null.");
    }

    var tlsCipherSuites = "tlsCipherSuites=" + cipherSuiteHexValue + "\n";
    log.info("The TLS 1.2 ClientHello offers the %s TLS 1.2 cipher suites.".formatted(cipherSuiteHexValue));
    runTls12TestTool(host, tlsCipherSuites);
  }

  /**
   * Configures and runs TLS 1.2 with the provided cipher-suite.
   *
   * @param cipherSuiteHexValue Hex value of the cipher suite(s) to be tested
   */
  private void configureAndRunTls12ServerForCipherSuites(String cipherSuiteHexValue) {
    if (cipherSuiteHexValue == null || cipherSuiteHexValue.isBlank()) {
      throw new AssertionError("The Cipher Suite value is empty or null.");
    }

    var tlsCipherSuites = "tlsCipherSuites=" + cipherSuiteHexValue + "\n";
    log.info("The TLS 1.2 server offers the %s TLS 1.2 cipher suite.".formatted(cipherSuiteHexValue));
    runTls12TestToolServer(tlsCipherSuites);
  }

  /**
   * Resolves a readable cipher suite profile token from a feature file.
   *
   * @param cipherSuiteId cipher suite unique id (e.g. {@code ecdhe_rsa_aes_128_gcm_sha256})
   * @return matching cipher suite
   */
  @ParameterType("ecdhe_rsa_aes_128_gcm_sha256|ecdhe_rsa_aes_256_gcm_sha384")
  public TlsCipherSuite tlsCipherSuiteProfile(String cipherSuiteId) {
    return TlsCipherSuite.fromCipherSuiteId(cipherSuiteId);
  }

  /**
   * Resolves a readable server certificate token from a feature file.
   *
   * @param certificateId certificate unique id (e.g. {@code zeta_tls_test_tool_server_ecdsa_good_certificate})
   * @return matching certificate descriptor
   */
  @ParameterType(
      "zeta_tls_test_tool_server_ecdsa_private_key|zeta_tls_test_tool_server_ecdsa_different_cn_certificate|"
          + "zeta_tls_test_tool_server_ecdsa_different_san_certificate|zeta_tls_test_tool_server_ecdsa_good_certificate|"
          + "zeta_tls_test_tool_server_ecdsa_expired_certificate|zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate|"
          + "zeta_tls_test_tool_server_ecdsa_different_ca_certificate|zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate")
  public TlsServerCertificates tlsServerCertificate(String certificateId) {
    return TlsServerCertificates.fromCertificateId(certificateId);
  }

  /**
   * Configures and runs TLS 1.2 for a readable cipher suite profile.
   *
   * @param host    Host to be tested
   * @param profile cipher suite profile mapped to tls-test-tool tuple syntax
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für das Cipher-Suite-Profil {tlsCipherSuiteProfile}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the cipher suite profile {tlsCipherSuiteProfile}")
  public void setValidTlsTestToolConfigForCipherSuiteProfile(String host, TlsCipherSuite profile) {
    configureTls12ForCipherSuites(host, profile.getTlsTestToolCipherSuiteValue());
  }

  /**
   * Configures and runs TLS 1.2 for a specific group.
   *
   * @param supportedGroup The supported group
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützte Gruppe {string}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the Supported group {string}")
  public void setValidTlsTestToolServerConfigForSupportedGroup(String supportedGroup) {

    if (supportedGroup == null || supportedGroup.isBlank()) {
      throw new AssertionError("The Supported group value is empty or null.");
    }

    if (TlsSupportedGroup.fromDisplayName(supportedGroup) == TlsSupportedGroup.UNKNOWN) {
      throw new AssertionError("The Supported Group value is unknown.");
    }

    var tlsTestToolConfigBuffer = getTlsTestToolServerBaseConfig()
        + "manipulateEllipticCurveGroup=" + supportedGroup + "\n";

    runTls12TestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs TLS 1.2 for a specific hash.
   *
   * @param hashAlgo The hash algorithm
   */
  @Gegebensei("die TlsTestTool-Server-Konfigurationsdaten für den Hash-Algorithmus {string}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the Hash-Algo {string}")
  public void setValidTlsTestToolServerConfigForHashAlgo(String hashAlgo) {

    if (hashAlgo == null || hashAlgo.isBlank()) {
      throw new AssertionError("The Hash algorithm value is empty or null.");
    }

    var hash = TlsHashAlgorithm.fromDisplayName(hashAlgo);
    if (hash == TlsHashAlgorithm.UNKNOWN) {
      throw new AssertionError("The Hash algorithm value is unknown.");
    }

    LinkedHashSet<TlsHashAlgorithm> listOfHashes;
    if (hash == TlsHashAlgorithm.SUPPORTED_MIX) {
      listOfHashes = TlsHashAlgorithm.supportedByPolicy();
    } else {
      listOfHashes = new LinkedHashSet<>();
      listOfHashes.add(hash);
    }

    // Create the Signature Algorithm - Hash algorithm OpenSSL configuration pairs
    var supportedSignatureAlgos = TlsSignatureAlgorithm.getSupportedSignatureAlgorithms();
    String commaSeparatedSignatureHashPairs =
        listOfHashes.stream()
            .flatMap(hashAlgorithm -> supportedSignatureAlgos.stream()
                .map(sig -> "(" + sig.getValue() + "," + hashAlgorithm.getValue() + ")"))
            .collect(Collectors.joining(","));

    var tlsTestToolConfigBuffer = getTlsTestToolServerBaseConfig(TlsLibrary.OPENSSL)
        + "tlsSignatureAlgorithms=" + commaSeparatedSignatureHashPairs + "\n";

    runTlsTestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs TLS 1.2 for a readable cipher suite profile.
   *
   * @param profile cipher suite profile mapped to tls-test-tool tuple syntax
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für das Cipher-Suite-Profil {tlsCipherSuiteProfile}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the cipher suite profile {tlsCipherSuiteProfile}")
  public void setValidTlsTestToolServerConfigForCipherSuiteProfile(TlsCipherSuite profile) {
    configureAndRunTls12ServerForCipherSuites(profile.getTlsTestToolCipherSuiteValue());
  }

  /**
   * Configures and runs TLS 1.2 for a supported cipher suite and a HelloRequest message.
   *
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten mit HelloRequest für eine der unterstützten Cipher-Suiten")
  @Given("the TLS 1.2 TlsTestTool server configuration data with HelloRequest support for a supported ciphersuite")
  public void setValidTlsTestToolServerConfigHelloRequestForASupportedCipherSuite() {
    var supportedCipherSuites = TlsCipherSuite.supportedTls12CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.joining(","));
    var tlsTestToolConfigBuffer = getTlsTestToolServerBaseConfig();
    tlsTestToolConfigBuffer += "tlsCipherSuites=" + supportedCipherSuites + "\n";
    tlsTestToolConfigBuffer += "manipulateRenegotiate=\n";
    runTlsTestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs TLS 1.2 for all supported cipher suites.
   *
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützten Cipher-Suiten")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the supported ciphersuites")
  public void setValidTlsTestToolServerConfigForAllSupportedCipherSuite() {
    var supportedCipherSuites = TlsCipherSuite.supportedTls12CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.joining(","));
    configureAndRunTls12ServerForCipherSuites(supportedCipherSuites);
  }

  /**
   * Configures and runs TLS 1.2 for all supported cipher suite profiles for a specific certificate.
   *
   * @param tlsServerCertificate certificate descriptor used for the server configuration
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Server-Konfigurationsdaten für die unterstützten Cipher-Suiten mit {tlsServerCertificate}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the supported ciphersuites for {tlsServerCertificate}")
  public void setValidTlsTestToolServerConfigForACertificate(TlsServerCertificates tlsServerCertificate) {
    var supportedCipherSuites = TlsCipherSuite.supportedTls12CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.joining(","));

    var tlsTestToolConfigBuffer = getTlsTestToolServerBaseConfig();
    tlsTestToolConfigBuffer += "tlsCipherSuites=" + supportedCipherSuites + "\n";
    tlsTestToolConfigBuffer += "manipulateForceCertificateUsage=";
    runTlsTestToolServer(tlsTestToolConfigBuffer, tlsServerCertificate);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2.
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für die nicht unterstützten Cipher-Suiten")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the unsupported ciphersuites")
  public void setTlsTestToolConfigForInvalidCipherSuite(String host) {
    // Cipher suites besides TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    // and those from [TR-02102-2], Chapter 3.3.1 Table 2
    var tlsCipherSuites = "tlsCipherSuites=(0x00,0x01),(0x00,0x02),(0x00,0x03),(0x00,0x04),"
        + "(0x00,0x05),(0x00,0x06),(0x00,0x17),(0x00,0x18),(0x00,0x20),(0x00,0x24),(0x00,0x27),"
        + "(0x00,0x28),(0x00,0x2a),(0x00,0x2b),(0x00,0x2c),(0x00,0x2d),(0x00,0x2e),(0x00,0x2f),"
        + "(0x00,0x30),(0x00,0x31),(0x00,0x32),(0x00,0x33),(0x00,0x34),(0x00,0x35),(0x00,0x36),"
        + "(0x00,0x37),(0x00,0x38),(0x00,0x39),(0x00,0x3a),(0x00,0x3b),(0x00,0x3c),(0x00,0x3d),"
        + "(0x00,0x3e),(0x00,0x41),(0x00,0x42),(0x00,0x43),(0x00,0x44),(0x00,0x45),(0x00,0x46),"
        + "(0x00,0x68),(0x00,0x6c),(0x00,0x6d),(0x00,0x84),(0x00,0x85),(0x00,0x86),(0x00,0x87),"
        + "(0x00,0x88),(0x00,0x89),(0x00,0x8a),(0x00,0x8c),(0x00,0x8d),(0x00,0x8e),(0x00,0x90),"
        + "(0x00,0x91),(0x00,0x92),(0x00,0x94),(0x00,0x95),(0x00,0x96),(0x00,0x97),(0x00,0x98),"
        + "(0x00,0x99),(0x00,0x9a),(0x00,0x9b),(0x00,0x9c),(0x00,0x9d),(0x00,0xa4),(0x00,0xa5),"
        + "(0x00,0xa9),(0x00,0xaa),(0x00,0xab),(0x00,0xac),(0x00,0xad),(0x00,0xae),(0x00,0xaf),"
        + "(0x00,0xb0),(0x00,0xb1),(0x00,0xb2),(0x00,0xb3),(0x00,0xb4),(0x00,0xb5),(0x00,0xb6),"
        + "(0x00,0xb7),(0x00,0xb8),(0x00,0xb9),(0x00,0xba),(0x00,0xbb),(0x00,0xbc),(0x00,0xbd),"
        + "(0x00,0xbe),(0x00,0xbf),(0x00,0xc0),(0x00,0xc1),(0x00,0xc2),(0x00,0xc3),(0x00,0xc4),"
        + "(0x00,0xc5),(0x00,0xff),(0xc0,0x01),(0xc0,0x02),(0xc0,0x04),(0xc0,0x05),(0xc0,0x22),"
        + "(0xc0,0x06),(0xc0,0x07),(0xc0,0x09),(0xc0,0x0a),(0xc0,0x0b),(0xc0,0x0c),(0xc0,0x0e),"
        + "(0xc0,0x0f),(0xc0,0x10),(0xc0,0x11),(0xc0,0x13),(0xc0,0x14),(0xc0,0x15),(0xc0,0x16),"
        + "(0xc0,0x18),(0xc0,0x19),(0xc0,0x1d),(0xc0,0x1e),(0xc0,0x1f),(0xc0,0x20),(0xc0,0x21),"
        + "(0xc0,0x2d),(0xc0,0x2e),(0xc0,0x33),(0xc0,0x35),(0xc0,0x36),(0xc0,0x37),(0xc0,0x38),"
        + "(0xc0,0x39),(0xc0,0x3a),(0xc0,0x3b),(0xc0,0x3c),(0xc0,0x3d),(0xc0,0x3e),(0xc0,0x3f),"
        + "(0xc0,0x40),(0xc0,0x41),(0xc0,0x42),(0xc0,0x43),(0xc0,0x44),(0xc0,0x45),(0xc0,0x46),"
        + "(0xc0,0x47),(0xc0,0x48),(0xc0,0x49),(0xc0,0x4a),(0xc0,0x4b),(0xc0,0x4c),(0xc0,0x4d),"
        + "(0xc0,0x4e),(0xc0,0x4f),(0xc0,0x50),(0xc0,0x51),(0xc0,0x52),(0xc0,0x53),(0xc0,0x54),"
        + "(0xc0,0x55),(0xc0,0x56),(0xc0,0x57),(0xc0,0x58),(0xc0,0x59),(0xc0,0x5a),(0xc0,0x5b),"
        + "(0xc0,0x5c),(0xc0,0x5d),(0xc0,0x5e),(0xc0,0x5f),(0xc0,0x60),(0xc0,0x61),(0xc0,0x62),"
        + "(0xc0,0x63),(0xc0,0x64),(0xc0,0x65),(0xc0,0x66),(0xc0,0x67),(0xc0,0x68),(0xc0,0x69),"
        + "(0xc0,0x6a),(0xc0,0x6b),(0xc0,0x6c),(0xc0,0x6d),(0xc0,0x6e),(0xc0,0x6f),(0xc0,0x70),"
        + "(0xc0,0x71),(0xc0,0x72),(0xc0,0x73),(0xc0,0x74),(0xc0,0x75),(0xc0,0x76),(0xc0,0x77),"
        + "(0xc0,0x78),(0xc0,0x79),(0xc0,0x7a),(0xc0,0x7b),(0xc0,0x7c),(0xc0,0x7d),(0xc0,0x7e),"
        + "(0xc0,0x7f),(0xc0,0x80),(0xc0,0x81),(0xc0,0x82),(0xc0,0x83),(0xc0,0x84),(0xc0,0x85),"
        + "(0xc0,0x86),(0xc0,0x87),(0xc0,0x88),(0xc0,0x89),(0xc0,0x8a),(0xc0,0x8b),(0xc0,0x8c),"
        + "(0xc0,0x8d),(0xc0,0x8e),(0xc0,0x8f),(0xc0,0x90),(0xc0,0x91),(0xc0,0x92),(0xc0,0x93),"
        + "(0xc0,0x94),(0xc0,0x95),(0xc0,0x96),(0xc0,0x97),(0xc0,0x98),(0xc0,0x99),(0xc0,0x9a),"
        + "(0xc0,0x9b),(0xc0,0x9c),(0xc0,0x9d),(0xc0,0xa0),(0xc0,0xa1),(0xc0,0xa2),(0xc0,0xa3),"
        + "(0xc0,0xa4),(0xc0,0xa5),(0xc0,0xa6),(0xc0,0xa7),(0xc0,0xa8),(0xc0,0xa9),(0xc0,0xaa),"
        + "(0xc0,0xab),(0xc0,0xae),(0xc0,0xaf),(0xcc,0xa8),(0xcc,0xa9),(0xc0,0x25),(0xc0,0x26),"
        + "(0xcc,0xaa),(0xcc,0xab),(0xcc,0xac),(0xcc,0xad),(0xcc,0xae),(0x00,0xa6),(0x00,0xa7),"
        + "(0x00,0xa8)\n";
    runTls12TestTool(host, tlsCipherSuites);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2.
   *
   * @param host            Host to be tested
   * @param supportedGroups Hex value of the supported groups extension to be tested
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für die unterstützte Gruppe {string}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the supported groups {string}")
  public void setValidTlsTestToolConfigForSupportedGroups(String host, String supportedGroups) {
    if (supportedGroups == null || supportedGroups.isBlank()) {
      throw new AssertionError("The Supported Groups value is empty or null.");
    }
    runTls12SupportedGroupsScenario(host, supportedGroups);
  }

  /**
   * Configures and runs TLS 1.2 for a readable supported-groups profile.
   *
   * @param host    Host to be tested
   * @param supportedGroup supported-group
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für das unterstützte-Gruppen-Profil {string}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the supported-group profile {string}")
  public void setValidTlsTestToolConfigForSupportedGroupProfile(String host, String supportedGroup) {

    if (supportedGroup == null || supportedGroup.isBlank()) {
      throw new AssertionError("The Supported group value is empty or null.");
    }

    var group = TlsSupportedGroup.fromDisplayName(supportedGroup);

    if (group == TlsSupportedGroup.UNKNOWN) {
      throw new AssertionError("The Supported Group value is unknown.");
    }

    List<TlsSupportedGroup> listOfSupportedGroups;
    if (group == TlsSupportedGroup.UNSUPPORTED_MIX) {
      listOfSupportedGroups = TlsSupportedGroup.forbiddenGroups();
    } else {
      listOfSupportedGroups = List.of(group);
    }

    runTls12SupportedGroupsScenario(host, TlsSupportedGroup.buildSupportedGroupsExtension(listOfSupportedGroups));
  }

  /**
   * Configures and runs TLS 1.2 for explicit {@code supported_groups} extension content.
   *
   * @param host            Host to be tested
   * @param supportedGroups Hex value of the supported groups extension to be tested
   */
  private void runTls12SupportedGroupsScenario(String host, String supportedGroups) {
    runTls12ClientScenario(
        host,
        getTlsTestValidEcdheCipherSuites(),
        SUPPORTED_SIGNATURE_HASH_ALGOS,
        supportedGroups,
        TlsHashAlgorithm.supportedByPolicy(),
        false);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2. The TLS_EMPTY_RENEGOTIATION_INFO_SCSV (0x00ff) Cipher Suite is automatically added
   * by the TLS Test tool
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für TLS Renegotiation")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for TLS Renegotiation")
  public void setTlsTestToolConfigForRenegotiation(String host) {
    runTls12ClientScenario(
        host,
        getTlsTestValidCipherSuites(),
        SUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.supportedByPolicy(),
        true);
  }

  /**
   * Checks whether a Server Key exchange uses one of the supported hash functions.
   */
  @Dann("verwendet der Server-Schlüsselaustausch eine der unterstützten Hashfunktionen")
  @Then("the server key exchange uses one of the supported hash functions")
  public void checkIfTheServerKeyExchangeUsesOneOfTheSupportedHashFunctions() {
    requireTlsLogs();
    var matcher = TLS_HASH_ALGORITHM_PATTERN.matcher(tlsLogs);

    if (!matcher.find()) {
      if (tlsLogs != null && tlsLogs.contains("TLS handshake failed")) {
        throw new AssertionError(
            "The hash algorithm used for the Server Key exchange could not be found because the TLS handshake already failed. "
                + extractAlertSummary() + " " + buildHashNegotiationSummary());
      }
      throw new AssertionError("The hash algorithm used for the Server Key exchange could not be found. "
          + extractAlertSummary() + " " + buildHashNegotiationSummary());
    }
    var hashAlgorithmUsed = Integer.parseInt(matcher.group(1));
    var selectedHashAlgorithm = TlsHashAlgorithm.fromValue(hashAlgorithmUsed);
    if (selectedHashAlgorithm.isSupportedByPolicy()) {
      log.info("The {} hash algorithm was used for the Server Key exchange.", selectedHashAlgorithm);
      return;
    }
    throw new AssertionError("An unsupported hash algorithm with value " + hashAlgorithmUsed
        + " (" + selectedHashAlgorithm + ") was used for the Server Key exchange.");
  }

  /**
   * Configures and runs the TLS test tool for hash functions < SHA-256.
   *
   * @param host          Host to be tested
   * @param hashFunctions Hash algorithms that must not be supported
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit den folgenden nicht unterstützten Hashfunktionen wurden festgelegt:")
  @Given("the TlsTestTool configuration data for the host {tigerResolvedString} has been set using the following unsupported hash functions:")
  public void setTlsTestToolConfigForInvalidHash(String host, DataTable hashFunctions) {
    checkHost(host);
    if (hashFunctions == null) {
      throw new AssertionError("The hash functions table is null.");
    }

    // Check if all the supported/mandatory SignatureAndHashAlgorithms are present
    var hashFunctionsHashSet = parseNonEmptyFirstColumn(hashFunctions, LinkedHashSet::new);

    var unsupportedHashFunctions = TlsHashAlgorithm.unsupportedByPolicyNames();

    if (!unsupportedHashFunctions.equals(hashFunctionsHashSet)) {
      throw new AssertionError("""
          For this test, the signature hash algorithms cannot be changed.
          They must be:
          - MD5
          - SHA1
          - SHA224""");
    }
    lastOfferedTls12HashAlgorithms = mapNamesToHashAlgorithms(hashFunctionsHashSet);

    runTls12ClientScenario(
        host,
        getTlsTestValidCipherSuites(),
        UNSUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.unsupportedByPolicy(),
        false);

  }

  /**
   * Configures and runs the TLS test tool for supported hash algorithms.
   *
   * @param host          Host to be tested
   * @param hashFunctions Hash algorithms that are supported
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit den folgenden unterstützten Hashfunktionen wurden festgelegt:")
  @Given("the TlsTestTool configuration data for the host {tigerResolvedString} has been set with the following supported hash functions:")
  public void setTlsTestToolConfigForValidHash(String host, DataTable hashFunctions) {
    checkHost(host);
    if (hashFunctions == null) {
      throw new AssertionError("The hash functions table is null.");
    }

    // Check if all the supported/mandatory SignatureAndHashAlgorithms are present
    var hashFunctionsHashSet = parseNonEmptyFirstColumn(hashFunctions, LinkedHashSet::new);

    var supportedHashFunctions = TlsHashAlgorithm.supportedByPolicyNames();

    if (!supportedHashFunctions.equals(hashFunctionsHashSet)) {
      throw new AssertionError("""
          For this test, the signature hash algorithms cannot be changed.
          They must be:
          - SHA256
          - SHA384
          - SHA512""");
    }
    lastOfferedTls12HashAlgorithms = mapNamesToHashAlgorithms(hashFunctionsHashSet);

    runTls12ClientScenario(
        host,
        getTlsTestValidCipherSuites(),
        SUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.supportedByPolicy(),
        false);

  }

  /**
   * Resolves a readable handshake expectation token from a feature file.
   *
   * @param expectation textual expectation token
   * @return matching handshake expectation enum
   */
  @ParameterType("erfolgreich|nicht erfolgreich|successful|not successful")
  public TlsHandshakeExpectation tlsHandshakeExpectation(String expectation) {
    return TlsHandshakeExpectation.fromValue(expectation);
  }

  /**
   * Checks whether the TLS handshake matches the expected result.
   */
  @Dann("ist der TLS-Handshake {tlsHandshakeExpectation}")
  @Then("the TLS handshake is {tlsHandshakeExpectation}")
  public void checkIfTlsHandshakeMatchesExpectation(TlsHandshakeExpectation expectation) {
    if (expectation == null) {
      throw new AssertionError("The TLS handshake expectation is empty or null.");
    }
    switch (expectation) {
      case ERFOLGREICH -> checkForMessageInTlsLogs("Handshake successful");
      case NICHT_ERFOLGREICH -> checkForMessageInTlsLogs("TLS handshake failed", "Handshake aborted");
      default -> throw new AssertionError("Unsupported TLS handshake expectation: " + expectation);
    }
  }

  /**
   * Checks whether the ClientHello only offers the supported curves.
   */
  @Dann("der ClientHello bietet nur die unterstützten Kurven an")
  @Then("the client hello only offers the supported curves")
  public void checkClientHelloForSupportedCurves() {
    List<TlsSupportedGroup> supportedGroupsInClientHello = extractSupportedGroupsHex(tlsLogs);
    Assertions
        .assertThat(supportedGroupsInClientHello.isEmpty())
        .withFailMessage("No supported_groups extension entries found in ClientHello logs.")
        .isFalse();

    Set<TlsSupportedGroup> allowedSet = new HashSet<>(TlsSupportedGroup.allowedGroups());

    // Collect mismatches (groups offered by client but not allowed)
    List<TlsSupportedGroup> mismatches = supportedGroupsInClientHello.stream()
        .filter(g -> !allowedSet.contains(g))
        .toList();

    Assertions
        .assertThat(mismatches.isEmpty())
        .withFailMessage("The following un-supported Supported Groups were advertised by the Client. %s", mismatches)
        .isTrue();
  }

  /**
   * Checks whether the ClientHello does not offer any unsupported signature algorithms.
   */
  @Dann("der ClientHello bietet keine nicht unterstützten Signaturalgorithmen an")
  @Then("the client hello does not offer any unsupported signature algorithms")
  public void checkClientHelloForNoUnsupportedSignatureAlgorithms() {
    List<TlsSignatureAlgorithm> signatureAlgorithmsInClientHello = extractSignatureAlgorithmsHex(tlsLogs);
    Assertions
        .assertThat(signatureAlgorithmsInClientHello.isEmpty())
        .withFailMessage("No signature_algorithms entries found in ClientHello logs.")
        .isFalse();

    Set<TlsSignatureAlgorithm> unsupportedSet =
        new HashSet<>(TlsSignatureAlgorithm.getUnsupportedSignatureAlgorithms());

    List<TlsSignatureAlgorithm> mismatches = signatureAlgorithmsInClientHello.stream()
        .filter(unsupportedSet::contains)
        .distinct()
        .toList();

    Assertions
        .assertThat(mismatches.isEmpty())
        .withFailMessage(
            "The following unsupported Signature Algorithms were advertised by the Client. %s",
            mismatches)
        .isTrue();
  }

  /**
   * Checks whether the handshake renegotiation is initiated.
   */
  @Dann("wird die TLS-Handshake-renegotiation gestartet")
  @Then("the TLS handshake renegotiation is triggered")
  public void checkIfTlsRenegotiationIsTriggered() {
    checkForMessageInTlsLogs("Performing renegotiation");
  }

  /**
   * Checks whether the handshake renegotiation was successful.
   */
  @Dann("ist die TLS-Handshake-renegotiation erfolgreich")
  @Then("the TLS handshake renegotiation is successful")
  public void checkIfTlsRenegotiationIsSuccessful() {
    checkForMessageInTlsLogs("<= handshake");

    // If a renegotiation is successful, then the "<= renegotiate" message is logged
    checkForMessageInTlsLogs("<= renegotiate");
  }

  /**
   * Checks whether the server initiated renegotiation was successful.
   */
  @Dann("ist die TLS-Server initiierte renegotiation erfolgreich")
  @Then("the TLS Server initiated renegotiation is successful")
  public void checkIfTlsServerInitiatedRenegotiationIsSuccessful() {
    Assertions
        .assertThat(hasFinishedAfterRenegotiation(tlsLogs))
        .withFailMessage("Server-initiated renegotiation was not completed successfully.")
        .isTrue();
  }

  /**
   * Checks whether the specified message is present in the TLS logs.
   *
   * @param message message to search for in TLS logs
   * @param optionalMessages optional alternative messages; if present, at least one expected message must appear
   */
  private void checkForMessageInTlsLogs(String message, String... optionalMessages) {
    requireTlsLogs();

    var expectedMessages = new ArrayList<String>();
    if (message != null && !message.isBlank()) {
      expectedMessages.add(message);
    }
    if (optionalMessages != null) {
      for (var optionalMessage : optionalMessages) {
        if (optionalMessage != null && !optionalMessage.isBlank()) {
          expectedMessages.add(optionalMessage);
        }
      }
    }
    if (expectedMessages.isEmpty()) {
      throw new AssertionError("No valid message to search for in TLS logs.");
    }
    var containsAnyExpectedMessage = expectedMessages.stream().anyMatch(tlsLogs::contains);

    Assertions
        .assertThat(containsAnyExpectedMessage)
        .withFailMessage(
            "None of the expected messages %s were found in TLS logs. %s %s",
            expectedMessages,
            extractAlertSummary(),
            buildHashNegotiationSummary())
        .isTrue();
  }

  /**
   * Extract a compact alert summary from raw TLS tool logs to improve failure readability.
   *
   * @return compact summary with alert details and relevant handshake metadata
   */
  private String extractAlertSummary() {
    if (tlsLogs == null || tlsLogs.isBlank()) {
      return "TLS logs are empty.";
    }
    var lines = Arrays.stream(tlsLogs.split("\\R")).toList();
    var relevantPhase = determineRelevantLogPhase(lines);
    var level = (String) null;
    var description = (String) null;
    var handshakeFailure = (String) null;
    var selectedCipherSuite = (String) null;
    var selectedHash = (TlsHashAlgorithm) null;
    var selectedSignature = (TlsSignatureAlgorithm) null;
    var handshakeSuccessful = false;
    var lastLine = (String) null;

    for (var line : lines.subList(relevantPhase.startInclusive(), relevantPhase.endExclusive())) {
      var trimmedLine = line.trim();
      if (!trimmedLine.isEmpty()) {
        lastLine = trimmedLine;
        if (trimmedLine.contains("Handshake successful.")) {
          handshakeSuccessful = true;
        }
      }
      var levelMatcher = ALERT_LEVEL_PATTERN.matcher(line);
      if (levelMatcher.find()) {
        level = levelMatcher.group(1);
      }
      var descriptionMatcher = ALERT_DESCRIPTION_PATTERN.matcher(line);
      if (descriptionMatcher.find()) {
        description = descriptionMatcher.group(1);
      }
      var handshakeFailureMatcher = TLS_HANDSHAKE_FAILED_PATTERN.matcher(line);
      if (handshakeFailureMatcher.find()) {
        handshakeFailure = handshakeFailureMatcher.group(0);
      }
      var selectedCipherSuiteMatcher = TLS_CIPHER_SUITE_PATTERN.matcher(line);
      if (selectedCipherSuiteMatcher.find()) {
        selectedCipherSuite = selectedCipherSuiteMatcher.group(1).trim();
      }
      var selectedHashMatcher = TLS_HASH_ALGORITHM_PATTERN.matcher(line);
      if (selectedHashMatcher.find()) {
        selectedHash = TlsHashAlgorithm.fromValue(Integer.parseInt(selectedHashMatcher.group(1)));
      }
      var selectedSignatureMatcher = TLS_SIGNATURE_ALGORITHM_PATTERN.matcher(line);
      if (selectedSignatureMatcher.find()) {
        selectedSignature = TlsSignatureAlgorithm.fromValue(Integer.parseInt(selectedSignatureMatcher.group(1)));
      }
    }

    var summary = new StringBuilder();
    summary.append("Alert summary:");
    summary.append(" level=").append(level != null ? level : "n/a");
    summary.append(", description=").append(description != null ? description : "n/a");
    if (handshakeFailure != null) {
      summary.append(", ").append(handshakeFailure);
    } else if (lastLine != null) {
      summary.append(", last_log_line=").append(lastLine);
    }
    if (handshakeSuccessful) {
      summary.append(", handshake_successful=true");
    }
    if (selectedCipherSuite != null) {
      summary.append(", selected_cipher_suite=").append(selectedCipherSuite);
    }
    if (selectedHash != null) {
      summary.append(", selected_hash=")
          .append(selectedHash.name())
          .append("(")
          .append(selectedHash.getValue())
          .append("/")
          .append(selectedHash.getHexValue())
          .append(")");
    }
    if (selectedSignature != null) {
      summary.append(", selected_signature=")
          .append(selectedSignature.name())
          .append("(")
          .append(selectedSignature.getValue())
          .append("/")
          .append(selectedSignature.getHexValue())
          .append(")");
    }
    return summary.toString();
  }

  /**
   * Determine the most relevant handshake phase in the TLS logs.
   *
   * <p>If a handshake failure is present, the phase ending at the last failure line is selected.
   * Otherwise, the latest observed handshake phase is used.</p>
   *
   * @param lines TLS log lines
   * @return selected handshake phase boundaries
   */
  private TlsLogPhase determineRelevantLogPhase(List<String> lines) {
    var failureIndex = -1;
    for (var i = 0; i < lines.size(); i++) {
      if (TLS_HANDSHAKE_FAILED_PATTERN.matcher(lines.get(i)).find()) {
        failureIndex = i;
      }
    }
    var endExclusive = failureIndex >= 0 ? failureIndex + 1 : lines.size();
    var startInclusive = 0;
    for (var i = 0; i < endExclusive; i++) {
      if (isHandshakePhaseBoundary(lines.get(i))) {
        startInclusive = i + 1;
      }
    }
    return new TlsLogPhase(startInclusive, endExclusive);
  }

  /**
   * Checks whether a log line marks a new handshake phase boundary.
   *
   * @param line log line
   * @return {@code true} if the line indicates start of a new handshake phase
   */
  private boolean isHandshakePhaseBoundary(String line) {
    return TLS_RENEGOTIATION_PHASE_PATTERN.matcher(line).find()
        || TLS_CLIENT_HELLO_SENT_PATTERN.matcher(line).find()
        || TLS_CLIENT_HELLO_WRITE_PATTERN.matcher(line).find();
  }

  /**
   * Build a short summary that explains which TLS1.2 hash algorithms were offered and whether the server selected a concrete hash algorithm
   * in the observed logs.
   *
   * @return hash negotiation summary
   */
  private String buildHashNegotiationSummary() {
    if (lastOfferedTls12HashAlgorithms.isEmpty()) {
      return "Hash negotiation: no explicit TLS1.2 hash offer context captured.";
    }
    var selected = extractSelectedTls12HashAlgorithm();
    return (selected == null)
        ? ("Hash negotiation: offered="
        + lastOfferedTls12HashAlgorithms
        + ", selected=n/a (handshake likely aborted before ServerKeyExchange hash selection).")
        : ("Hash negotiation: offered="
            + lastOfferedTls12HashAlgorithms
            + ", selected="
            + selected
            + ", selected_offered="
            + lastOfferedTls12HashAlgorithms.contains(selected)
            + ".");
  }

  /**
   * Extract the hash algorithm selected by the server in TLS 1.2 ServerKeyExchange logs.
   *
   * @return selected hash algorithm, or {@code null} if not present in the logs
   */
  private TlsHashAlgorithm extractSelectedTls12HashAlgorithm() {
    if (tlsLogs == null || tlsLogs.isBlank()) {
      return null;
    }
    var matcher = TLS_HASH_ALGORITHM_PATTERN.matcher(tlsLogs);
    if (!matcher.find()) {
      return null;
    }
    var id = Integer.parseInt(matcher.group(1));
    return TlsHashAlgorithm.fromValue(id);
  }

  /**
   * Map textual hash names from feature tables to canonical TLS hash enum values.
   *
   * @param names hash names such as {@code SHA256} or {@code RSA_SHA256}
   * @return insertion-ordered set of mapped hash algorithms
   */
  private LinkedHashSet<TlsHashAlgorithm> mapNamesToHashAlgorithms(Set<String> names) {
    return names.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .map(TlsHashAlgorithm::fromDisplayName)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Parses non-empty first-column values from a Cucumber data table.
   *
   * @param table data table containing values in the first column
   * @param setFactory target set factory
   * @param <S> target set type
   * @return set of normalized first-column values
   */
  private static <S extends Set<String>> S parseNonEmptyFirstColumn(DataTable table, Supplier<S> setFactory) {
    return table
        .asLists()
        .stream()
        .filter(row -> row != null && !row.isEmpty())
        .map(row -> row.getFirst() != null ? row.getFirst().trim() : "")
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toCollection(setFactory));
  }

  /**
   * Ensures TLS logs are available for assertions.
   */
  private void requireTlsLogs() {
    if (tlsLogs == null || tlsLogs.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }
  }

  /**
   * Creates and runs a TLS 1.2 client scenario with configurable hash and group extensions.
   *
   * @param host host to be tested
   * @param tlsCipherSuites tls-test-tool formatted cipher-suite configuration line
   * @param tlsSignatureHashAlgos tls-test-tool formatted signature hash algorithms extension
   * @param tlsSupportedGroups tls-test-tool formatted supported-groups extension
   * @param offeredHashAlgorithms hash algorithms to log for traceability
   * @param enableRenegotiation whether to append renegotiation trigger to the config
   */
  private void runTls12ClientScenario(
      String host,
      String tlsCipherSuites,
      String tlsSignatureHashAlgos,
      String tlsSupportedGroups,
      Iterable<?> offeredHashAlgorithms,
      boolean enableRenegotiation) {
    checkHost(host);

    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    for (var hashAlgorithm : offeredHashAlgorithms) {
      log.info("{}", hashAlgorithm);
    }

    var tlsTestToolConfigBuffer = getTlsTestToolConfigBuffer(
        tlsSignatureHashAlgos, tlsSupportedGroups, tlsCipherSuites, host);
    if (enableRenegotiation) {
      // Add configuration to initiate a TLS handshake renegotiation.
      tlsTestToolConfigBuffer += "manipulateRenegotiate=\n";
    }
    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Checks whether a X509 Certificate is received.
   */
  @Dann("erhält der Client ein X.509-Zertifikat gemäß [gemSpec_Krypt#GS-A_4359-*] vom Server")
  @Then("the Client receives a X.509-Certificate that conforms to [gemSpec_Krypt#GS-A_4359-*] from the server")
  public void x509CertificateReceivedAccordingToSpecification() {

    // Extract the certificate
    var der = extractCertificateFromLog(tlsLogs);
    if (der == null || der.length == 0) {
      throw new AssertionError("No certificate hex dump found in the log.");
    }

    // Parse as a X509 certificate
    X509Certificate cert;
    try {
      cert = parseAsX509(der);
    } catch (CertificateException e) {
      throw new AssertionError("Error Parsing the certificate", e);
    }

    log.debug("Parsed as X.509 certificate");
    log.debug("Type: {}", cert.getType());
    log.debug("Version: {}", cert.getVersion());

    // Determine the type of public key
    var pk = cert.getPublicKey();
    if (pk instanceof RSAPublicKey rsaPk) {
      log.debug("Public Key used: RSA");
      log.debug("RSA Key size: {}", rsaPk.getModulus().bitLength());
      log.debug("Signature Algo: {}", cert.getSigAlgOID());

      // Check if the key length is >= the minimum RSA key length
      Assertions
          .assertThat(rsaPk.getModulus().bitLength())
          .withFailMessage("Certificate public key length (%d) is less than minimum allowed %d", rsaPk.getModulus().bitLength(),
              MIN_ALLOWED_RSA_KEY_LENGTH)
          .isGreaterThanOrEqualTo(MIN_ALLOWED_RSA_KEY_LENGTH);

      // Check if the correct signature algorithm is used in the certificate
      Assertions
          .assertThat(cert.getSigAlgOID())
          .withFailMessage("Incorrect certificate signature algorithm (OID:%s) used. Expected OID:%s.", cert.getSigAlgOID(),
              OID_SHA256_WITH_RSA)
          .isEqualTo(OID_SHA256_WITH_RSA);

    } else if (pk instanceof ECPublicKey) {
      log.debug("Public Key used: EC");

      var curveOid = getNamedCurveOid(cert);

      if (curveOid == null) {
        throw new AssertionError("The X.509 certificate EC curve is missing or can not be determined.");
      }

      log.debug("Curve OID: {}", curveOid);
      log.debug("Signature Algo: {}", cert.getSigAlgOID());

      // Check if the brainpoolP256r1 or the P-256 curve is used
      Assertions
          .assertThat(curveOid)
          .withFailMessage("Incorrect curve found (OID:%s) ", curveOid)
          .isIn(OID_BRAINPOOL256R1, OID_P256);

      // Check if the ecdsa-with-SHA256 signature algorithm is used
      Assertions
          .assertThat(cert.getSigAlgOID())
          .withFailMessage("Incorrect certificate signature algorithm (OID:%s) used. Expected OID:%s.", cert.getSigAlgOID(),
              OID_ECDSA_WITH_SHA256)
          .isEqualTo(OID_ECDSA_WITH_SHA256);
    } else {
      throw new AssertionError("The X.509 certificate public key is not RSA nor EC.");
    }
  }

  /**
   * Creates a TLS 1.2 test tool configuration and executes the tool.
   *
   * @param host            Host to be tested
   * @param tlsCipherSuites Cipher suites configuration string for the test tool
   */
  private void runTls12TestTool(String host, String tlsCipherSuites) {
    runTls12ClientScenario(
        host,
        tlsCipherSuites,
        SUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.supportedByPolicy(),
        false);
  }

  /**
   * Creates a TLS 1.2 server configuration and starts the service-managed TLS test tool server.
   *
   * @param tlsCipherSuites Cipher suites configuration string for the test tool
   */
  private void runTls12TestToolServer(String tlsCipherSuites) {

    var tlsTestToolConfigBuffer = getTlsTestToolServerBaseConfig(tlsCipherSuites);
    runTlsTestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Checks whether current OS is Windows.
   *
   * @return {@code true} for Windows OS
   */
  private static boolean isWindowsOs() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }   

  /**
   * Resolves the platform-specific TLS test tool binary location.
   *
   * @param isWindows whether current OS is Windows
   * @param isWsl whether runtime is WSL
   * @return absolute tool location
   */
  private static String resolveTlsToolLocation(boolean isWindows, boolean isWsl) {
    var configuredPath = TigerGlobalConfiguration.readStringOptional("tlsTestTool.binaryBasePath")
        .orElseThrow(() -> new AssertionError("The config key 'tlsTestTool.binaryBasePath' could not be resolved."));
    var baseLocation = Path.of(System.getProperty("user.dir"), configuredPath).normalize();
    return baseLocation + ((isWindows || isWsl) ? "-wsl" : "-alpine");
  }

  /**
   * Builds the process command line for launching the TLS test tool.
   *
   * @param tlsTestToolLocation executable path
   * @param configFileLocation configuration file path
   * @param isWindows whether current OS is Windows
   * @return launch command
   */
  private static List<String> buildTlsToolCommand(String tlsTestToolLocation, String configFileLocation, boolean isWindows) {
    List<String> command = new ArrayList<>();
    if (isWindows) {
      command.add("wsl");
    }
    command.add(tlsTestToolLocation);
    command.add("--configFile=" + configFileLocation);
    return command;
  }

  /**
   * Starts the TLS test tool process and captures merged stdout/stderr asynchronously.
   *
   * @param command process command line
   * @return future with complete process logs
   * @throws IOException if process startup fails
   */
  private static CompletableFuture<String> startTlsToolAndCollectLogs(List<String> command) throws IOException {
    var processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);  // merge stderr into stdout

    var process = processBuilder.start();
    return CompletableFuture.supplyAsync(() -> {
      try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        return reader.lines().collect(Collectors.joining("\n"));
      } catch (IOException e) {
        throw new CompletionException(e);
      }
    }).thenCombine(process.onExit(), (logs, exitedProcess) -> {
      if (exitedProcess.exitValue() != 0) {
        throw new CompletionException(new RuntimeException("Exit=" + exitedProcess.exitValue()));
      }
      return logs;
    });
  }

  /**
   * Waits for process completion and stores logs when running in synchronous mode.
   *
   * @param mode execution mode
   * @throws InterruptedException if waiting is interrupted
   * @throws ExecutionException if process completed exceptionally
   */
  private void completeSynchronouslyIfNeeded(ExecutionMode mode) throws InterruptedException, ExecutionException {
    if (mode == ExecutionMode.SYNCHRONOUS) {
      tlsLogsFuture.get();
      saveTlsLogsToSerenityReport();
    }
  }

  /**
   * Clears the local process-backed log collector before a new run starts.
   */
  private void clearTlsLogCollectors() {
    tlsLogsFuture = null;
  }

  /**
   * Check if the host is empty or null.
   *
   * @param host host value to validate
   */
  void checkHost(String host) {
    if (host == null || host.isBlank()) {
      throw new AssertionError("The host is empty or null.");
    }
    log.info("Performing the TLS-Test for host: {}", host);
  }

  /**
   * Executes the TLS test tool.
   *
   * @param tlsTestToolConfigBuffer TLS test tool configuration content
   */
  void runTlsTestTool(String tlsTestToolConfigBuffer) {
    runTlsTestTool(tlsTestToolConfigBuffer, ExecutionMode.DEFAULT);
  }

  /**
   * Executes the TLS test tool.
   *
   * @param tlsTestToolConfigBuffer TLS test tool configuration content
   * @param mode execution mode
   */
  void runTlsTestTool(String tlsTestToolConfigBuffer, ExecutionMode mode) {
    var isWindows = isWindowsOs();
    var isWsl = isWslEnvironment();
    var tlsTestToolLocation = resolveTlsToolLocation(isWindows, isWsl);

    // Save the TLS Test Tool configuration
    var configFileLocation = createTempConfigFile(tlsTestToolConfigBuffer);

    // Check if the TLS Test Tool exists
    if (!Files.exists(Path.of(tlsTestToolLocation))) {
      throw new AssertionError("The TLS test tool could not be found at: " + tlsTestToolLocation);
    }

    // Convert the path if running via WSL from Windows
    if (isWindows) {
      tlsTestToolLocation = toWslPath(tlsTestToolLocation);
      configFileLocation = toWslPath(configFileLocation);
    }

    log.debug("TLS Test Tool path = {}", tlsTestToolLocation);
    log.debug("TLS Test Tool configuration file path: {}", configFileLocation);

    // Clear the logs
    tlsLogs = "";
    clearTlsLogCollectors();
    try {
      List<String> command = buildTlsToolCommand(tlsTestToolLocation, configFileLocation, isWindows);
      tlsLogsFuture = startTlsToolAndCollectLogs(command)
          .thenApply(logs -> {
            tlsLogs = logs;
            return logs;
          });
      completeSynchronouslyIfNeeded(mode);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Error when running the TLS test tool", e);
    } catch (java.io.IOException | ExecutionException e) {
      throw new AssertionError("Error when running the TLS test tool", e);
    }
  }

  /**
   * Starts the TLS test tool server through the TLS test tool service using the default certificate.
   *
   * @param tlsTestToolConfigBuffer TLS test tool server configuration content
   */
  void runTlsTestToolServer(String tlsTestToolConfigBuffer) {
    runTlsTestToolServer(tlsTestToolConfigBuffer, TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE);
  }

  /**
   * Executes the TLS test tool in server mode via the TLS test tool service.
   *
   * <p>The previous service-managed process is stopped first and the retained remote logs are cleared
   * before the generated config file, certificate, and private key are uploaded. The new server
   * instance is then started through the service endpoint.</p>
   *
   * @param tlsTestToolConfigBuffer TLS test tool server configuration content
   * @param serverCertificate certificate descriptor to upload; defaults to the standard certificate when {@code null}
   */
  void runTlsTestToolServer(String tlsTestToolConfigBuffer, TlsServerCertificates serverCertificate) {
    var effectiveServerCertificate = serverCertificate == null ? TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE : serverCertificate;
    var configFileLocation = Path.of(createTempConfigFile(tlsTestToolConfigBuffer));
    var certificateFile = Path.of(resolveCertificateOrKeyPath(effectiveServerCertificate.relativePath));
    var privateKeyFile = Path.of(
        resolveCertificateOrKeyPath(TlsServerCertificates.getPrivateKeyForCertificate(effectiveServerCertificate).relativePath));

    log.debug("TLS Test Tool service configuration file path: {}", configFileLocation);
    log.debug("TLS Test Tool service certificate file path: {}", certificateFile);
    log.debug("TLS Test Tool service private key file path: {}", privateKeyFile);

    tlsLogs = "";
    clearTlsLogCollectors();
    try {
      var tlsTestToolService = TlsTestToolServiceFactory.getInstance();
      tlsTestToolService.stop();
      tlsTestToolService.clearLogs();
      tlsTestToolService.updateConfig(configFileLocation);
      tlsTestToolService.updateCertificate(certificateFile, privateKeyFile);
      tlsTestToolService.start();
    } catch (AssertionError e) {
      throw new AssertionError("Error when running the TLS test tool", e);
    }
  }

  /**
   * Saves the logs to the Serenity Report.
   */
  private void saveTlsLogsToSerenityReport() {
    requireTlsLogs();
    log.debug("TLS Test Tool Logs:");
    log.debug(tlsLogs);

    Serenity.recordReportData()
        .withTitle("TLS Test Tool Logs:")
        .andContents(tlsLogs);
  }

  /**
   * Retrieves the TLS test tool server logs from the TLS test tool service and stores them in the report.
   */
  @Dann("die Tls-Test-Tool-Server-Protokolle abrufen")
  @Then("the TLS test tool server logs are retrieved")
  public void getTheTlsTestToolServerLogs() {
    try {
      tlsLogs = TlsTestToolServiceFactory.getInstance().getLogs();
      log.debug("TLS Test Tool Logs:");
      log.debug(tlsLogs);

      Serenity.recordReportData()
          .withTitle("TLS Test Tool Logs:")
          .andContents(tlsLogs);
    } catch (AssertionError e) {
      throw new AssertionError("Error when retrieving TLS test tool server logs", e);
    }
  }

  /**
   * Waits for a local TLS test tool execution to complete and stores the collected logs in the report.
   */
  @Dann("die Tls-Test-Tool-Protokolle abrufen")
  @Then("the TLS test tool logs are retrieved")
  public void getTheTlsTestToolLogs() {
    if (tlsLogsFuture == null) {
      throw new AssertionError("TLS test tool process has not been started.");
    }
    try {
      tlsLogsFuture.get();
      saveTlsLogsToSerenityReport();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Error when running the TLS test tool", e);
    } catch (ExecutionException e) {
      throw new AssertionError("Error when running the TLS test tool", e);
    }
  }

  /**
   * Creates a temporary configuration file for the TLS test tool.
   *
   * @param contents configuration content to write
   * @return created temp file path
   */
  private String createTempConfigFile(String contents) {

    if (contents == null || contents.isBlank()) {
      throw new AssertionError("The content is empty or null.");
    }

    Path tempFile;
    try {
      tempFile = Files.createTempFile("tls-test-tool", ".conf");
    } catch (java.io.IOException e) {
      throw new AssertionError("Error creating file: tls-test-tool.conf", e);
    }

    try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      writer.write(contents);
    } catch (java.io.IOException e) {
      throw new AssertionError("Error writing to file: " + tempFile, e);
    }

    log.info("Created temporary TLS test tool configuration file: {}", tempFile);
    log.debug(contents);
    return tempFile.toString();
  }

  /**
   * Extracts a filesystem path value from a generated TLS test tool configuration buffer.
   *
   * @param tlsTestToolConfigBuffer generated configuration content
   * @param key config key whose path value should be extracted
   * @return extracted path value
   */
  private static Path extractConfigPath(String tlsTestToolConfigBuffer, String key) {
    var matcher = Pattern.compile(CONFIG_LINE_PATTERN_TEMPLATE.formatted(Pattern.quote(key))).matcher(tlsTestToolConfigBuffer);
    if (!matcher.find()) {
      throw new AssertionError("TLS test tool configuration does not contain required key: " + key);
    }
    return Path.of(matcher.group(1).trim());
  }

  /**
   * Checks whether the server sends a renegotiation_info extension in the ServerHello record.
   */
  @Dann("ist die Erweiterung renegotiation_info im ServerHello vorhanden")
  @Then("the renegotiation_info extension is present in the ServerHello")
  public void checkIfRenegotiationInfoExtensionIsPresentInServerHello() {
    Assertions
        .assertThat(helloHasEmptyRenegotiationInfo(TlsEndpointRole.SERVER))
        .withFailMessage("The renegotiation_info could not be found in the TLS ServerHello.")
        .isTrue();
  }

  /**
   * Checks whether the client sends a renegotiation_info extension in the ClientHello record.
   *
   * @return true if renegotiation_info (0xff01) is present with length == 0
   */
  private boolean clientHelloHasEmptyRenegotiationInfo() {
    return helloHasEmptyRenegotiationInfo(TlsEndpointRole.CLIENT);
  }

  /**
   * Checks whether the renegotiation_info extension is present.
   *
   * @param role TLS endpoint role
   * @return true if renegotiation_info (0xff01) is present with length == 0
   */
  private boolean helloHasEmptyRenegotiationInfo(TlsEndpointRole role) {
    requireTlsLogs();

    // Extract hex bytes after "ServerHello.extensions=" or "ClientHello.extensions=" depending on the role
    var extensionsPattern = role == TlsEndpointRole.SERVER
        ? SERVER_HELLO_EXTENSIONS_PATTERN
        : CLIENT_HELLO_EXTENSIONS_OPTIONAL_PATTERN;
    var m = extensionsPattern.matcher(tlsLogs);
    if (!m.find()) {
      return false;
    }

    var extensionBytes = m.group(1);
    if (extensionBytes == null || extensionBytes.isBlank()) {
      return false;
    }
    var hexBytes = extensionBytes.trim().split("\\s+");
    var bytes = new byte[hexBytes.length];
    for (var i = 0; i < hexBytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hexBytes[i], 16);
    }

    // Parse TLS extensions: type(2) | length(2) | data(length)
    var offset = 0;
    while (offset + 4 <= bytes.length) {
      var type = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
      var len = ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
      offset += 4;

      if (offset + len > bytes.length) {
        return false; // malformed
      }

      // renegotiation_info = 0xff01
      if (type == 0xFF01) {
        // For initial handshake, renegotiated_connection_length must be 0
        return len == 1 && bytes[offset] == 0x00;
      }

      offset += len;
    }

    return false;
  }

  /**
   * Extracts ClientHello.cipher_suite.
   *
   * @param tlsLog TLS log content
   * @return cipher suites pairs ["(0xC0,0x2C)", "(0xC0,0x30)", ...]
   * @throws AssertionError if {@code tlsLog} is {@code null} or blank
   */
  private static List<String> extractClientHelloCipherSuitesAsPairs(String tlsLog) {
    if (tlsLog == null || tlsLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }
    Matcher m = CLIENT_HELLO_CIPHER_SUITES_PATTERN.matcher(tlsLog);
    if (!m.find()) {
      return List.of();
    }

    String[] bytes = m.group(1).trim().split("\\s+");
    List<String> pairs = new ArrayList<>(bytes.length / 2);
    for (int i = 0; i + 1 < bytes.length; i += 2) {
      pairs.add("(0x" + bytes[i].toUpperCase(Locale.ROOT) + ",0x" + bytes[i + 1].toUpperCase(Locale.ROOT) + ")");
    }
    return pairs;
  }

  /**
   * Checks whether the ClientHello TLS version is 1.2.
   */
  @Dann("die ClientHello-TLS-Version ist 1.2")
  @Then("the ClientHello TLS version is 1.2")
  public void checkIfClientHelloTlsVersionIs1_2() {
    requireTlsLogs();

    // Checks if the client supports TLS 1.2
    Assertions
        .assertThat(tlsLogs.contains("ClientHello.client_version=03 03"))
        .withFailMessage("TLS 1.2 is not supported by the client.")
        .isTrue();
  }

  /**
   * Checks whether the client hello only contains cipher suites specified in TR-02102-2, Abschnitt 3.3.1 Tabelle 1.
   * TLS 1.3 cipher suites are also accepted and do not cause this test step to fail if they are present.
   */
  @Dann("der ClientHello-Record enthält nur Cipher-Suiten aus TR-02102-2, Abschnitt 3.3.1 Tabelle 1")
  @Then("the ClientHello record contains only cipher suites from TR-02102-2, section 3.3.1 table 1")
  public void onlySupportedCipherSuitesArePresent() {
    requireTlsLogs();

    List<String> offered = extractClientHelloCipherSuitesAsPairs(tlsLogs);
    Assertions
        .assertThat(offered.isEmpty())
        .withFailMessage("No ClientHello.cipher_suites found in log.")
        .isFalse();

    // HashSet for fast membership checks
    HashSet<String> supportedCipherSuites = TlsCipherSuite.supportedTls12CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.toCollection(HashSet::new));
    supportedCipherSuites.addAll(TlsCipherSuite.optionalTls13CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.toSet()));

    // Find any non-supported cipher suites
    List<String> notSupported = offered.stream()
        .filter(cs -> !supportedCipherSuites.contains(cs))
        .toList();

    Assertions
        .assertThat(notSupported.isEmpty())
        .withFailMessage("The following un-supported Cipher Suites were advertised by the Client. %s", notSupported)
        .isTrue();
  }

  /**
   * Checks whether the client hello only contains the TLS_EMPTY_RENEGOTIATION_INFO_SCSV cipher suite or
   * an empty renegotiation_info extension.
   */
  @Dann("der ClientHello-Record enthält TLS_EMPTY_RENEGOTIATION_INFO_SCSV oder eine leere renegotiation_info extension")
  @Then("the ClientHello record contains TLS_EMPTY_RENEGOTIATION_INFO_SCSV or an empty renegotiation_info extension")
  public void scsvCipherSuiteOrRenegotiationInfoArePresent() {
    requireTlsLogs();

    List<String> offered = extractClientHelloCipherSuitesAsPairs(tlsLogs);
    Assertions
        .assertThat(offered.isEmpty())
        .withFailMessage("No ClientHello.cipher_suites found in log.")
        .isFalse();

    // Check if the EMPTY_RENEGOTIATION_INFO_SCSV cipher suite is present
    boolean hasEmptyRenegotiationInfoScsv =
        offered.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .anyMatch(s ->
                s.equalsIgnoreCase(TlsCipherSuite.EMPTY_RENEGOTIATION_INFO_SCSV.getTlsTestToolCipherSuiteValue())
            );

    if (!hasEmptyRenegotiationInfoScsv) {
      // Check if the  renegotiation_info extension is present and is empty
      Assertions
          .assertThat(clientHelloHasEmptyRenegotiationInfo())
          .withFailMessage("The EMPTY_RENEGOTIATION_INFO_SCSV or the correct renegotiation_info could not be found in the TLS Client Hello.")
          .isTrue();
    }
  }

  /**
   * Supported/required signature hash algorithms.
   */
  private enum SignatureAndHashAlgorithms {
    RSA_MD5,
    RSA_SHA1,
    RSA_SHA224,
    RSA_SHA256,
    RSA_SHA384,
    RSA_SHA512,
  }

  /**
   * Supported/required signature schemes.
   */
  private enum SignatureSchemes {
    rsa_pkcs1_sha256,
    rsa_pkcs1_sha384,
    rsa_pkcs1_sha512,
  }

  /**
   * TLS 1.2 hash algorithm IDs used in signature_algorithms and ServerKeyExchange metadata.
   */
  public enum TlsHashAlgorithm {
    MD5(1, "0x01", "md5", false),
    SHA1(2, "0x02", "sha1", false),
    SHA224(3, "0x03", "sha224", false),
    SHA256(4, "0x04", "sha256", true),
    SHA384(5, "0x05", "sha384", true),
    SHA512(6, "0x06", "sha512", true),
    UNKNOWN(-1, "n/a", "unknown", false),
    SUPPORTED_MIX(-1, "n/a", "supported_mix", false);

    @Getter
    private final int value;
    @Getter
    private final String hexValue;
    @Getter
    private final String displayName;
    @Getter
    private final boolean supportedByPolicy;

    TlsHashAlgorithm(int value, String hexValue, String displayName, boolean supportedByPolicy) {
      this.value = value;
      this.hexValue = hexValue;
      this.displayName = displayName;
      this.supportedByPolicy = supportedByPolicy;
    }

    /**
     * Resolve enum by TLS 1.2 hash id.
     *
     * @param value TLS hash id from protocol metadata
     * @return matching enum or {@link #UNKNOWN}
     */
    public static TlsHashAlgorithm fromValue(int value) {
      return Arrays.stream(values())
          .filter(algorithm -> algorithm.value == value)
          .findFirst()
          .orElse(UNKNOWN);
    }

    /**
     * Resolve enum by textual hash name used in feature tables and logs.
     *
     * @param displayName the human-readable hash name to resolve
     * @return matching hash {@link TlsHashAlgorithm}, or {@link #UNKNOWN} if no match is found
     */
    public static TlsHashAlgorithm fromDisplayName(String displayName) {
      if (displayName == null || displayName.isBlank()) {
        return UNKNOWN;
      }

      String needle = displayName.trim();
      return java.util.Arrays.stream(values())
          .filter(g -> g.displayName.equalsIgnoreCase(needle))
          .findFirst()
          .orElse(UNKNOWN);
    }

    /**
     * Return all hash algorithms currently allowed by policy for TLS 1.2 signature usage.
     *
     * @return insertion-ordered set of policy-supported hash algorithms
     */
    public static LinkedHashSet<TlsHashAlgorithm> supportedByPolicy() {
      return Arrays.stream(values())
          .filter(TlsHashAlgorithm::isSupportedByPolicy)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Return all hash algorithms currently disallowed by policy for TLS 1.2 signature usage.
     *
     * @return insertion-ordered set of policy-disallowed hash algorithms
     */
    public static LinkedHashSet<TlsHashAlgorithm> unsupportedByPolicy() {
      return Arrays.stream(values())
          .filter(algorithm -> algorithm != UNKNOWN && algorithm != SUPPORTED_MIX && !algorithm.isSupportedByPolicy())
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Return policy-supported hash names as expected in Gherkin data tables.
     *
     * @return set of enum names for policy-supported hash algorithms
     */
    public static Set<String> supportedByPolicyNames() {
      return supportedByPolicy().stream().map(Enum::name).collect(Collectors.toSet());
    }

    /**
     * Return policy-disallowed hash names as expected in Gherkin data tables.
     *
     * @return set of enum names for policy-disallowed hash algorithms
     */
    public static Set<String> unsupportedByPolicyNames() {
      return unsupportedByPolicy().stream().map(Enum::name).collect(Collectors.toSet());
    }
  }

  /**
   * TLS 1.2 signature algorithm IDs used in signature_algorithms and ServerKeyExchange metadata.
   */
  public enum TlsSignatureAlgorithm {
    RSA(1, "0x01", Policy.FORBIDDEN),
    DSA(2, "0x02", Policy.OPTIONAL),
    ECDSA(3, "0x03", Policy.OPTIONAL),
    UNKNOWN(-1, "n/a", Policy.NA);

    @lombok.Getter private final int value;
    @lombok.Getter private final String hexValue;
    @lombok.Getter private final Policy policy;

    TlsSignatureAlgorithm(int value, String hexValue, Policy policy) {
      this.value = value;
      this.hexValue = hexValue;
      this.policy = policy;
    }

    /**
     * Policy classification for TLS signature algorithms used when validating a ClientHello.
     *
     * <ul>
     *   <li>{@link #MANDATORY} – the signature algorithm must be offered/supported to comply with the policy.</li>
     *   <li>{@link #OPTIONAL} – the signature algorithm is permitted by policy but not required.</li>
     *   <li>{@link #FORBIDDEN} – the signature algorithm must not be offered; its presence is a policy violation.</li>
     *   <li>{@link #NA} – not applicable / unspecified (e.g., placeholder or unknown signature algorithm).</li>
     * </ul>
     */
    public enum Policy {
      MANDATORY,
      OPTIONAL,
      FORBIDDEN,
      NA
    }

    /**
     * Resolve enum by TLS 1.2 signature id.
     *
     * @param value TLS signature id from protocol metadata
     * @return matching enum or {@link #UNKNOWN}
     */
    public static TlsSignatureAlgorithm fromValue(int value) {
      return Arrays.stream(values())
          .filter(algorithm -> algorithm.value == value)
          .findFirst()
          .orElse(UNKNOWN);
    }

    /**
     * Returns all supported TLS 1.2 signature algorithms defined by {@link TlsSignatureAlgorithm}.
     *
     * @return an {@link List} of all supported {@link TlsSignatureAlgorithm} values
     */
    public static List<TlsSignatureAlgorithm> getSupportedSignatureAlgorithms() {
      return Arrays.stream(TlsSignatureAlgorithm.values())
          .filter(g -> g.getPolicy() == Policy.MANDATORY
              || g.getPolicy() == Policy.OPTIONAL)
          .collect(Collectors.toList());
    }

    /**
     * Returns all unsupported / forbidden TLS 1.2 signature algorithms defined by {@link TlsSignatureAlgorithm}.
     *
     * @return an {@link List} of all forbidden {@link TlsSignatureAlgorithm} values
     */
    public static List<TlsSignatureAlgorithm> getUnsupportedSignatureAlgorithms() {
      return Arrays.stream(TlsSignatureAlgorithm.values())
          .filter(g -> g.getPolicy() == Policy.FORBIDDEN)
          .collect(Collectors.toList());
    }

  }

  /**
   * TLS "supported_groups" (extension 0x000a) NamedGroup IDs with policy classification.
   */
  public enum TlsSupportedGroup {

    // Mandatory
    SECP256R1(0x0017, "secp256r1", GroupPolicy.MANDATORY),
    SECP384R1(0x0018, "secp384r1", GroupPolicy.MANDATORY),

    // Optional
    BRAINPOOLP256R1(0x001A, "brainpoolP256r1", GroupPolicy.OPTIONAL),
    BRAINPOOLP384R1(0x001B, "brainpoolP384r1", GroupPolicy.OPTIONAL),

    // Forbidden
    SECP192R1(0x0013, "secp192r1", GroupPolicy.FORBIDDEN),
    SECP224R1(0x0015, "secp224r1", GroupPolicy.FORBIDDEN),
    SECP521R1(0x0019, "secp521r1 (P-521)", GroupPolicy.FORBIDDEN),
    SECP256K1(0x0010, "secp256k1", GroupPolicy.FORBIDDEN),

    BRAINPOOLP512R1(0x001C, "brainpoolP512r1", GroupPolicy.FORBIDDEN),

    X25519(0x001D, "x25519", GroupPolicy.FORBIDDEN),
    X448(0x001E, "x448", GroupPolicy.FORBIDDEN),

    FFDHE2048(0x0100, "ffdhe2048", GroupPolicy.FORBIDDEN),
    FFDHE3072(0x0101, "ffdhe3072", GroupPolicy.FORBIDDEN),
    FFDHE4096(0x0102, "ffdhe4096", GroupPolicy.FORBIDDEN),
    FFDHE6144(0x0103, "ffdhe6144", GroupPolicy.FORBIDDEN),
    FFDHE8192(0x0104, "ffdhe8192", GroupPolicy.FORBIDDEN),

    UNKNOWN(-1, "unknown", GroupPolicy.NA),
    UNSUPPORTED_MIX(-1, "unsupported_mix", GroupPolicy.NA);

    /**
     * Policy classification for TLS supported groups (NamedGroup IDs) used when validating a ClientHello.
     *
     * <ul>
     *   <li>{@link #MANDATORY} – the group must be offered/supported to comply with the policy.</li>
     *   <li>{@link #OPTIONAL} – the group is permitted by policy but not required.</li>
     *   <li>{@link #FORBIDDEN} – the group must not be offered; its presence is a policy violation.</li>
     *   <li>{@link #NA} – not applicable / unspecified (e.g., placeholder or unknown group).</li>
     * </ul>
     */
    public enum GroupPolicy {
      MANDATORY,
      OPTIONAL,
      FORBIDDEN,
      NA
    }

    @lombok.Getter private final int value;
    @lombok.Getter private final String displayName;
    @lombok.Getter private final GroupPolicy policy;

    TlsSupportedGroup(int value, String displayName, GroupPolicy policy) {
      this.value = value;
      this.displayName = displayName;
      this.policy = policy;
    }

    /**
     * Resolves a {@link TlsSupportedGroup} from its numeric NamedGroup identifier.
     *
     * @param value numeric NamedGroup ID (typically an uint16 from protocol metadata)
     * @return matching {@link TlsSupportedGroup}, or {@link #UNKNOWN} if the value is not recognized
     */
    public static TlsSupportedGroup fromValue(int value) {
      return java.util.Arrays.stream(values())
          .filter(group -> group.value == value)
          .findFirst()
          .orElse(UNKNOWN);
    }

    /**
     * Resolves a {@link TlsSupportedGroup} from a hex-encoded NamedGroup identifier.
     *
     * @param hex hex-encoded NamedGroup ID (with or without {@code 0x} prefix)
     * @return matching {@link TlsSupportedGroup}, or {@link #UNKNOWN} for invalid/unknown inputs
     */
    public static TlsSupportedGroup fromHex(String hex) {
      if (hex == null || hex.isBlank()) {
        return UNKNOWN;
      }
      String s = hex.trim();
      if (s.startsWith("0x") || s.startsWith("0X")) {
        s = s.substring(2);
      }
      try {
        return fromValue(Integer.parseInt(s, 16));
      } catch (NumberFormatException e) {
        return UNKNOWN;
      }
    }

    /**
     * Resolves a {@link TlsSupportedGroup} by its human-readable display name.
     *
     * @param displayName the human-readable group name to resolve
     * @return matching {@link TlsSupportedGroup}, or {@link #UNKNOWN} if no match is found
     */
    public static TlsSupportedGroup fromDisplayName(String displayName) {
      if (displayName == null || displayName.isBlank()) {
        return UNKNOWN;
      }

      String needle = displayName.trim();
      return java.util.Arrays.stream(values())
          .filter(g -> g.displayName.equalsIgnoreCase(needle))
          .findFirst()
          .orElse(UNKNOWN);
    }

    /**
     * Builds a TLS {@code supported_groups} extension (type {@code 0x000a}) from the provided list of
     * {@link TlsSupportedGroup}.
     *
     * @param groups list of  {@link TlsSupportedGroup} groups to encode
     * @return {@code supported_groups} extension as a lowercase hex string (no whitespace)
     * @throws IllegalArgumentException if {@code groups} is {@code null} or empty
     */
    public static String buildSupportedGroupsExtension(List<TlsSupportedGroup> groups) {
      if (groups == null || groups.isEmpty()) {
        throw new IllegalArgumentException("groups must not be null/empty");
      }

      int listLenBytes = groups.size() * 2;      // each group is u16
      int extLenBytes  = 2 + listLenBytes;       // u16 listLen + list

      StringBuilder sb = new StringBuilder();
      sb.append("000a");                         // extension type supported_groups
      sb.append(u16(extLenBytes));               // extension length
      sb.append(u16(listLenBytes));              // list length
      for (TlsSupportedGroup g : groups) {
        sb.append(u16(g.getValue()));            // group id
      }
      return sb.toString().toLowerCase();
    }

    /**
     * Formats an unsigned 16-bit value as a four-digit lowercase hex string.
     *
     * @param v value in range 0..65535
     * @return hex string representation (e.g. {@code 000a})
     */
    private static String u16(int v) {
      return String.format("%04x", v & 0xFFFF);
    }

    /**
     * Returns all groups that are forbidden by policy.
     *
     * @return a list of policy-forbidden groups, in enum order
     */
    public static List<TlsSupportedGroup> forbiddenGroups() {
      return Arrays.stream(TlsSupportedGroup.values())
          .filter(g -> g.getPolicy() == TlsSupportedGroup.GroupPolicy.FORBIDDEN)
          .collect(Collectors.toList());
    }

    /**
     * Returns all supported groups that are permitted by policy.
     *
     * @return a list of policy-allowed supported groups (mandatory + optional), in enum order
     */
    public static List<TlsSupportedGroup> allowedGroups() {
      return Arrays.stream(TlsSupportedGroup.values())
          .filter(g -> g.getPolicy() == TlsSupportedGroup.GroupPolicy.MANDATORY
              || g.getPolicy() == TlsSupportedGroup.GroupPolicy.OPTIONAL)
          .collect(Collectors.toList());
    }

  }

  /**
   * TLS versions.
   */
  public enum TlsVersion {
    TLS_1_2("TLS 1.2"),
    TLS_1_3("TLS 1.3");

    @Getter
    private final String displayName;

    TlsVersion(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * Mandatory and optional TLS cipher suites.
   */
  public enum TlsCipherSuite {
    // Mandatory
    ECDHE_RSA_AES_128_GCM_SHA256("ecdhe_rsa_aes_128_gcm_sha256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "(0xC0,0x2F)", TlsVersion.TLS_1_2, true),
    ECDHE_RSA_AES_256_GCM_SHA384("ecdhe_rsa_aes_256_gcm_sha384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "(0xC0,0x30)", TlsVersion.TLS_1_2, true),
    // Optional (TR-02102-2, Abschnitt 3.3.1, Tabelle 1)
    ECDHE_ECDSA_AES_128_CBC_SHA256("ecdhe_ecdsa_aes_128_cbc_sha256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "(0xC0,0x23)", TlsVersion.TLS_1_2, false),
    ECDHE_ECDSA_AES_256_CBC_SHA384("ecdhe_ecdsa_aes_256_cbc_sha384", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", "(0xC0,0x24)", TlsVersion.TLS_1_2, false),
    ECDHE_ECDSA_AES_128_GCM_SHA256("ecdhe_ecdsa_aes_128_gcm_sha256", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "(0xC0,0x2B)", TlsVersion.TLS_1_2, false),
    ECDHE_ECDSA_AES_256_GCM_SHA384("ecdhe_ecdsa_aes_256_gcm_sha384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "(0xC0,0x2C)", TlsVersion.TLS_1_2, false),
    ECDHE_ECDSA_AES_128_CCM("ecdhe_ecdsa_aes_128_ccm", "TLS_ECDHE_ECDSA_WITH_AES_128_CCM", "(0xC0,0xAC)", TlsVersion.TLS_1_2, false),
    ECDHE_ECDSA_AES_256_CCM("ecdhe_ecdsa_aes_256_ccm", "TLS_ECDHE_ECDSA_WITH_AES_256_CCM", "(0xC0,0xAD)", TlsVersion.TLS_1_2, false),
    ECDHE_RSA_AES_128_CBC_SHA256("ecdhe_rsa_aes_128_cbc_sha256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "(0xC0,0x27)", TlsVersion.TLS_1_2, false),
    ECDHE_RSA_AES_256_CBC_SHA384("ecdhe_rsa_aes_256_cbc_sha384", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "(0xC0,0x28)", TlsVersion.TLS_1_2, false),
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
    // Optional (TLS 1.3)
    AES_128_GCM_SHA256("aes_128_gcm_sha256", "TLS_AES_128_GCM_SHA256", "(0x13,0x01)", TlsVersion.TLS_1_3, false),
    AES_256_GCM_SHA384("aes_256_gcm_sha384", "TLS_AES_256_GCM_SHA384", "(0x13,0x02)", TlsVersion.TLS_1_3, false),
    CHACHA20_POLY1305_SHA256("chacha20_poly1305_sha256", "TLS_CHACHA20_POLY1305_SHA256", "(0x13,0x03)", TlsVersion.TLS_1_3, false),
    AES_128_CCM_SHA256("aes_128_ccm_sha256", "TLS_AES_128_CCM_SHA256", "(0x13,0x04)", TlsVersion.TLS_1_3, false),
    AES_128_CCM_8_SHA256("aes_128_ccm_8_sha256", "TLS_AES_128_CCM_8_SHA256", "(0x13,0x05)", TlsVersion.TLS_1_3, false),
    // The empty renegotiation cipher suite is added automatically by clients that support secure renegotiation
    EMPTY_RENEGOTIATION_INFO_SCSV("empty_renegotiation_info_scsv", "TLS_EMPTY_RENEGOTIATION_INFO_SCSV", "(0x00,0xFF)", TlsVersion.TLS_1_2, false);

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

  }

  /**
   * Certificate and key files shipped with the TLS test tool fixture.
   */
  public enum TlsServerCertificates {
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_PRIVATE_KEY("zeta_tls_test_tool_server_ecdsa_private_key", "ecdsa/zeta-tls-test-tool-server.privkey.pem"),
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CN_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_cn_certificate", "ecdsa/zeta-tls-test-tool-server_evil_cn.pem"),
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CN_SAN_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate", "ecdsa/zeta-tls-test-tool-server_evil_cn_san.pem"),
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_SAN_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_san_certificate", "ecdsa/zeta-tls-test-tool-server_evil_san.pem"),
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_good_certificate", "ecdsa/zeta-tls-test-tool-server_good.pem"),
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_EXPIRED_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_expired_certificate", "ecdsa/zeta-tls-test-tool-server_expired.pem"),
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_NOT_YET_VALID_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate", "ecdsa/zeta-tls-test-tool-server_not_yet_valid.pem"),
    ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CA_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_ca_certificate", "ecdsa/zeta-tls-test-tool-server_no_chain.pem");

    @Getter
    private final String certificateId;
    @Getter
    private final String relativePath;

    TlsServerCertificates(String certificateId, String relativePath) {
      this.certificateId = certificateId;
      this.relativePath = relativePath;
    }

    /**
     * Resolve enum by readable certificate token used in feature files.
     *
     * @param certificateId profile token
     * @return matching profile
     */
    public static TlsServerCertificates fromCertificateId(String certificateId) {
      return Arrays.stream(values())
          .filter(profile -> profile.certificateId.equals(certificateId))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Unsupported server certificate: " + certificateId));
    }

    /**
     * Returns the private key enum entry for a given certificate enum entry.
     *
     * @param certificate certificate enum entry
     * @return private key enum entry matching the provided certificate
     */
    public static TlsServerCertificates getPrivateKeyForCertificate(TlsServerCertificates certificate) {
      if (certificate == null) {
        throw new AssertionError("The server certificate is empty or null.");
      }
      String enumName = certificate.name();
      if (enumName.contains("_ECDSA_") && enumName.endsWith("_CERTIFICATE")) {
        return ZETA_TLS_TEST_TOOL_SERVER_ECDSA_PRIVATE_KEY;
      }
      throw new AssertionError("Unsupported certificate for private key mapping: " + enumName);
    }
  }

  /**
   * Expected TLS handshake result tokens used in feature files.
   */
  public enum TlsHandshakeExpectation {
    ERFOLGREICH("erfolgreich", "successful"),
    NICHT_ERFOLGREICH("nicht erfolgreich", "not successful");

    private final String deValue;
    private final String enValue;

    TlsHandshakeExpectation(String deValue, String enValue) {
      this.deValue = deValue;
      this.enValue = enValue;
    }

    /**
     * Resolve enum by textual expectation used in feature files.
     *
     * @param value expectation token
     * @return matching handshake expectation
     */
    public static TlsHandshakeExpectation fromValue(String value) {
      return Arrays.stream(values())
          .filter(expectation -> expectation.deValue.equals(value) || expectation.enValue.equals(value))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Unsupported TLS handshake expectation: " + value));
    }
  }

  /**
   * Inclusive-exclusive line range that represents one selected handshake phase in TLS logs.
   */
  private record TlsLogPhase(int startInclusive, int endExclusive) {

  }

  /**
   * The sync and async execution modes for the TLS Test Tool.
   */
  private enum ExecutionMode {
    SYNCHRONOUS("Synchronous", Set.of("sync", "synchronous", "block", "blocking")),
    ASYNCHRONOUS("Asynchronous", Set.of("async", "asynchronous", "nonblock", "non-blocking", "nonblocking"));

    private final String text;
    private final Set<String> aliases;

    ExecutionMode(String text, Set<String> aliases) {
      this.text = text;
      this.aliases = aliases;
    }

    public static final ExecutionMode DEFAULT = SYNCHRONOUS;

    /**
     * Resolve execution mode enum from textual representation.
     *
     * @param value expectation token
     * @return matching execution mode
     */
    public static ExecutionMode fromString(String value) {
      if (value == null || value.isBlank()) {
        return DEFAULT;
      }

      String v = value.trim().toLowerCase(Locale.ROOT);
      for (ExecutionMode m : values()) {
        if (m.aliases.contains(v) || m.text.toLowerCase(Locale.ROOT).equals(v)) {
          return m;
        }
      }
      throw new IllegalArgumentException(
          "Unknown execution mode '" + value + "'. Allowed: sync|blocking|synchronous or async|nonblocking|asynchronous");
    }
  }

  /**
   * Extracts the {@code supported_groups} (extension {@code 0x000A}) list from a TLS ClientHello
   * contained in the given tool log and returns it as a list of {@link TlsSupportedGroup}.
   *
   * @param fullLog complete TLS test tool log output containing {@code ClientHello.extensions=...}
   * @return list of extracted supported groups in the order they appear in the ClientHello;
   *         empty list if not present or malformed
   * @throws AssertionError if {@code fullLog} is {@code null/blank} or if no {@code ClientHello.extensions}
   *                        section is present in the log
   */
  private static List<TlsSupportedGroup> extractSupportedGroupsHex(String fullLog) {

    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    // Extract the client hello extension
    Matcher m = CLIENT_HELLO_EXTENSIONS_PATTERN.matcher(fullLog);
    if (!m.find()) {
      throw new AssertionError("Client hello extension not present in the logs.");
    }
    var clientHelloExtensions = m.group(1).trim();
    if (clientHelloExtensions.isBlank()) {
      return List.of();
    }

    byte[] extensions;
    try {
      extensions = parseHexBytes(clientHelloExtensions);
    } catch (NumberFormatException e) {
      return List.of();
    }
    byte[] sg = findExtensionData(extensions, 0x000A); // supported_groups
    if (sg == null || sg.length < 2) {
      return List.of();
    }

    int listLen = u16(sg, 0);
    if (2 + listLen > sg.length) {
      return List.of();
    }

    List<TlsSupportedGroup> groups = new ArrayList<>();
    for (int i = 2; i + 1 < 2 + listLen; i += 2) {
      groups.add(TlsSupportedGroup.fromHex(String.format("%04x", u16(sg, i))));
    }
    return groups;
  }

  /**
   * Extracts the {@code signature_algorithms} (extension {@code 0x000D}) list from a TLS ClientHello
   * contained in the given tool log and returns signature algorithms as a list of {@link TlsSignatureAlgorithm}.
   *
   * @param fullLog complete TLS test tool log output containing {@code ClientHello.extensions=...}
   * @return list of extracted signature algorithms in the order they appear in the ClientHello;
   *         empty list if the extension payload is malformed
   * @throws AssertionError if {@code fullLog} is {@code null/blank} or if no {@code ClientHello.extensions}
   *                        section is present in the log, or if no {@code signature_algorithms}
   *                        extension is present in the extracted extensions block
   */
  private static List<TlsSignatureAlgorithm> extractSignatureAlgorithmsHex(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    Matcher m = CLIENT_HELLO_EXTENSIONS_PATTERN.matcher(fullLog);
    if (!m.find()) {
      throw new AssertionError("Client hello extension not present in the logs.");
    }
    var clientHelloExtensions = m.group(1).trim();
    if (clientHelloExtensions.isBlank()) {
      return List.of();
    }

    byte[] extensions;
    try {
      extensions = parseHexBytes(clientHelloExtensions);
    } catch (NumberFormatException e) {
      return List.of();
    }

    byte[] signatureAlgorithms = findExtensionData(extensions, 0x000D); // signature_algorithms
    if (signatureAlgorithms == null || signatureAlgorithms.length < 2) {
      throw new AssertionError("signature_algorithms not present in the logs.");
    }

    int listLen = u16(signatureAlgorithms, 0);
    if (2 + listLen > signatureAlgorithms.length) {
      return List.of();
    }

    List<TlsSignatureAlgorithm> algorithms = new ArrayList<>();
    for (int i = 2; i + 1 < 2 + listLen; i += 2) {
      int signatureAlgorithmId = signatureAlgorithms[i + 1] & 0xFF;
      algorithms.add(TlsSignatureAlgorithm.fromValue(signatureAlgorithmId));
    }
    return algorithms;
  }

  /**
   * Parses a whitespace-separated hex string into a byte array.
   *
   * @param hexWithSpaces whitespace-separated hex bytes (e.g., {@code "c0 2f 00 ff"})
   * @return parsed bytes in the same order as provided
   * @throws NullPointerException if {@code hexWithSpaces} is {@code null}
   * @throws NumberFormatException if any token is not valid hexadecimal
   */
  private static byte[] parseHexBytes(String hexWithSpaces) {
    var text = hexWithSpaces.trim();
    if (text.isEmpty()) {
      return new byte[0];
    }
    String normalized = text.replaceAll("\\s+", "");
    try {
      return Hex.decodeHex(normalized);
    } catch (DecoderException e) {
      NumberFormatException ex = new NumberFormatException("Invalid hex token");
      ex.initCause(e);
      throw ex;
    }
  }

  /**
   * Finds and returns the payload (data) of a specific TLS ClientHello extension.
   *
   * @param extensions the raw ClientHello extensions block (concatenated extensions)
   * @param wantedType the extension type to locate (e.g., {@code 0x000A} for supported_groups)
   * @return a new byte array containing the extension payload, or {@code null} if not found or malformed
   */
  private static byte[] findExtensionData(byte[] extensions, int wantedType) {
    int i = 0;
    while (i + 4 <= extensions.length) {
      int type = u16(extensions, i);
      int len  = u16(extensions, i + 2);
      int dataStart = i + 4;
      int dataEnd = dataStart + len;

      if (dataEnd > extensions.length) {
        return null; // malformed
      }
      if (type == wantedType) {
        return Arrays.copyOfRange(extensions, dataStart, dataEnd);
      }

      i = dataEnd;
    }
    return null;
  }

  /**
   * Reads an unsigned 16-bit big-endian value from the provided byte array.
   *
   * @param b source byte array
   * @param off start offset of the 2-byte value
   * @return decoded value in range 0..65535
   */
  private static int u16(byte[] b, int off) {
    return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
  }

  /**
   * Supported TLS stack / crypto provider implementations.
   */
  private enum TlsLibrary {

    /** The mbed TLS library. */
    MBED_TLS("mbed TLS"),

    /** The OpenSSL library. */
    OPENSSL("OpenSSL");

    private final String displayName;

    TlsLibrary(String displayName) {
      this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name of the TLS library.
     *
     * @return display name (e.g. {@code "mbed TLS"} or {@code "OpenSSL"})
     */
    public String getDisplayName() {
      return displayName;
    }

  }

  /**
   * Indicates the TLS endpoint role in a handshake/test scenario.
   *
   * <p>Use {@link #CLIENT} for the side initiating the TLS connection (sending the ClientHello),
   * and {@link #SERVER} for the side accepting the connection (responding with the ServerHello).
   */
  private enum TlsEndpointRole {

    /** Initiates the TLS connection and sends the ClientHello. */
    CLIENT("client"),

    /** Accepts the TLS connection and responds with the ServerHello. */
    SERVER("server");

    private final String displayName;

    TlsEndpointRole(String displayName) {
      this.displayName = displayName;
    }

    /**
     * Returns the human-readable role name.
     *
     * @return the display name (e.g. {@code "client"} or {@code "server"})
     */
    public String getDisplayName() {
      return displayName;
    }
  }

  /**
   * Checks whether the TLS log contains a successful handshake completion message
   * <em>after</em> a renegotiation has been initiated.
   *
   * @param fullLog the complete TLS log as a single string
   * @return {@code true} if {@code FINISHED_MARKER} appears after {@code RENEG_MARKER}; otherwise {@code false}
   */
  private static boolean hasFinishedAfterRenegotiation(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    int renegIdx = fullLog.indexOf(RENEG_MARKER);
    if (renegIdx < 0) {
      return false;
    }

    int finishedIdx = fullLog.indexOf(FINISHED_MARKER, renegIdx + RENEG_MARKER.length());
    return finishedIdx >= 0;
  }

}
