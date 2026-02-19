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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;
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
   * Relative path of the TLS test tool.
   */
  private static final String TLSTESTTOOLPATH = "/tools/tls-test-tool-1.0.1/TlsTestTool";

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
   * The minimum allowed RSA public key length.
   */
  private static final int MIN_ALLOWED_RSA_KEY_LENGTH = 3000;
  private static final Pattern ALERT_LEVEL_PATTERN = Pattern.compile("Alert\\.level=([0-9a-fA-F]+)");
  private static final Pattern ALERT_DESCRIPTION_PATTERN = Pattern.compile("Alert\\.description=([0-9a-fA-F]+)");
  private static final Pattern TLS_HANDSHAKE_FAILED_PATTERN = Pattern.compile("TLS handshake failed:.*");
  private static final Pattern TLS_CIPHER_SUITE_PATTERN = Pattern.compile("Cipher suite:\\s*(.+)");
  private static final Pattern TLS_HASH_ALGORITHM_PATTERN = Pattern.compile("Server used HashAlgorithm\\s+(\\d+)");
  private static final Pattern TLS_SIGNATURE_ALGORITHM_PATTERN = Pattern.compile("Server used SignatureAlgorithm\\s+(\\d+)");
  private static final Pattern TLS_RENEGOTIATION_PHASE_PATTERN = Pattern.compile("=>\\s*renegotiate");
  private static final Pattern TLS_CLIENT_HELLO_SENT_PATTERN = Pattern.compile("ClientHello message transmitted\\.");
  private static final Pattern TLS_CLIENT_HELLO_WRITE_PATTERN = Pattern.compile("=>\\s*write client hello");
  /**
   * String for storing the TLS logs.
   */
  private String tlsLogs;
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
   * Parses the DER as an X.509 certificate, throws if not possible.
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
   */
  public static String toWslPath(String winPath) {
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
   */
  public static boolean isWindowsPath(String path) {
    return path.matches("^[a-zA-Z]:[/\\\\].*") || path.startsWith("\\\\");
  }

  /**
   * Checks whether the process runs inside WSL.
   */
  public static boolean isWslEnvironment() {
    return System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null;
  }

  /**
   * Extracts the certificate bytes from the log line with Certificate.certificate_list[0]=...
   */
  private static byte[] extractCertificateFromLog(String log) {

    // Extract the hex bytes after the log marker
    final var certList = Pattern.compile(
        "Certificate\\.certificate_list\\[0\\]=([0-9a-fA-F \\t]+)");

    // Matches individual hex bytes
    final var hexByte = Pattern.compile("\\b[0-9a-fA-F]{2}\\b");
    var m = certList.matcher(log);
    if (!m.find()) {
      return null;
    }

    var raw = m.group(1);

    // Re-tokenize bytes to be robust to tabs / double spaces etc.
    var bytes = new ArrayList<String>();
    var b = hexByte.matcher(raw);
    while (b.find()) {
      bytes.add(b.group());
    }

    if (bytes.isEmpty()) {
      return null;
    }

    var out = new byte[bytes.size()];
    for (var i = 0; i < bytes.size(); i++) {
      out[i] = (byte) Integer.parseInt(bytes.get(i), 16);
    }
    return out;
  }

  /**
   * Extracts the named curve OID from the certificate.
   *
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
   * Configures and runs the TLS test tool for TLS 1.1.
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit nur TLS 1.1 wurde erstellt")
  @Given("the TlsTestTool configuration data for the host {tigerResolvedString} with only TLS 1.1 is created")
  public void setTlsTestToolConfigforTls1_1(String host) {
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
  @Given("The TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.3 signature schemes:")
  public void setTlsTestToolConfigForUnsupportedSignatureSchemes(String host,
      DataTable signatureSchemes) {
    checkHost(host);

    // Check if all the supported/mandatory SignatureSchemes are present
    var signatureSchemeHashSet = signatureSchemes
        .asLists()
        .stream()
        .filter(row -> row != null && !row.isEmpty())
        .map(row -> row.getFirst() != null ? row.getFirst().trim() : "")
        .filter(signatureScheme -> !signatureScheme.isEmpty())
        .collect(Collectors.toCollection(HashSet::new));

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
  @Given("The TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.2 signature hash algorithms:")
  public void setTlsTestToolConfigForUnsupportedSignatureHashAlgorithms(String host,
      DataTable signatureHashAlgorithms) {

    checkHost(host);

    // Check if all the supported/mandatory SignatureAndHashAlgorithms are present
    var signatureAlgorithmHashSet = signatureHashAlgorithms
        .asLists()
        .stream()
        .filter(row -> row != null && !row.isEmpty())
        .map(row -> row.getFirst() != null ? row.getFirst().trim() : "")
        .filter(signatureHashAlgo -> !signatureHashAlgo.isEmpty())
        .collect(Collectors.toCollection(HashSet::new));

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

    var tlsCipherSuites = getTlsTestValidCipherSuites();
    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    supportedSignatureAndHashAlgorithms.forEach(log::info);

    var tlsTestToolConfigBuffer = getTlsTestToolConfigBuffer(
        RSA_HASH_VARIANTS, VALID_SUPPORTED_GROUPS,
        tlsCipherSuites, host);

    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Checks whether a TLS “alert” has been received.
   */
  @Dann("akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id {string}")
  @Then("the Zeta Guard endpoint does not accept the ClientHello and sends an alert message with the description id {string}")
  public void zetaGuardSendsAnAlert(String descriptionId) {
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
  @Given("The TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString}")
  public void setValidTlsTestToolConfig(String host) {
    var tlsCipherSuites = getTlsTestValidCipherSuites();
    runTls12TestTool(host, tlsCipherSuites);
  }

  /**
   * Checks whether a Server Hello record was not received.
   */
  @Dann("wird der Server-Hello Record nicht empfangen")
  @Then("the server hello record is not received")
  public void checkIfTheServerHelloIsNotReceived() {

    if (tlsLogs == null || tlsLogs.trim().isEmpty()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

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
   * Checks whether a Server Key exchange uses one of the curves.
   */
  @Dann("wird der Server-Key-Exchange-Datensatz nicht gesendet")
  @Then("the server key exchange record is not sent")
  public void checkIfTheServerKeyExchangeIsReceived() {

    if (tlsLogs == null || tlsLogs.trim().isEmpty()) {
      throw new AssertionError("The TLS log is empty or null.");
    }
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

    if (tlsLogs == null || tlsLogs.trim().isEmpty()) {
      throw new AssertionError("The TLS log is empty or null.");
    }
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
    if (cipherSuiteHexValue == null || cipherSuiteHexValue.trim().isEmpty()) {
      throw new AssertionError("The Cipher Suite value is empty or null.");
    }

    var tlsCipherSuites = "tlsCipherSuites=" + cipherSuiteHexValue + "\n";
    log.info("The TLS 1.2 ClientHello offers the %s TLS 1.2 cipher suites.".formatted(cipherSuiteHexValue));
    runTls12TestTool(host, tlsCipherSuites);
  }

  /**
   * Resolves a readable ciphersuite profile token from a feature file.
   *
   * @param profileName profile token (e.g. {@code ecdhe_rsa_aes_128_gcm_sha256})
   * @return matching ciphersuite profile
   */
  @ParameterType("ecdhe_rsa_aes_128_gcm_sha256|ecdhe_rsa_aes_256_gcm_sha384")
  public TlsCipherSuiteProfile tlsCipherSuiteProfile(String profileName) {
    return TlsCipherSuiteProfile.fromProfileName(profileName);
  }

  /**
   * Configures and runs TLS 1.2 for a readable ciphersuite profile.
   *
   * @param host    Host to be tested
   * @param profile ciphersuite profile mapped to tls-test-tool tuple syntax
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für das Ciphersuite-Profil {tlsCipherSuiteProfile}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the ciphersuite profile {tlsCipherSuiteProfile}")
  public void setValidTlsTestToolConfigForCipherSuiteProfile(String host, TlsCipherSuiteProfile profile) {
    configureTls12ForCipherSuites(host, profile.getTlsTestToolCipherSuiteValue());
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2.
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für die nicht unterstützten Ciphersuiten")
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
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für die Unterstützte Gruppe {string}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the supported groups {string}")
  public void setValidTlsTestToolConfigForSupportedGroups(String host, String supportedGroups) {
    if (supportedGroups == null || supportedGroups.trim().isEmpty()) {
      throw new AssertionError("The Supported Groups value is empty or null.");
    }
    runTls12SupportedGroupsScenario(host, supportedGroups);
  }

  /**
   * Resolves a readable supported-groups profile token from a feature file.
   *
   * @param profileName profile token (e.g. {@code p256_only})
   * @return matching supported-group profile
   */
  @ParameterType("p256_only|p384_only|unsupported_mix")
  public TlsSupportedGroupProfile tlsSupportedGroupProfile(String profileName) {
    return TlsSupportedGroupProfile.fromProfileName(profileName);
  }

  /**
   * Configures and runs TLS 1.2 for a readable supported-groups profile.
   *
   * @param host    Host to be tested
   * @param profile supported-groups profile mapped to extension hex value
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} für das Unterstützte-Gruppen-Profil {tlsSupportedGroupProfile}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the supported-group profile {tlsSupportedGroupProfile}")
  public void setValidTlsTestToolConfigForSupportedGroupProfile(String host, TlsSupportedGroupProfile profile) {
    runTls12SupportedGroupsScenario(host, profile.getSupportedGroupsExtensionHex());
  }

  /**
   * Configures and runs TLS 1.2 for explicit {@code supported_groups} extension content.
   *
   * @param host            Host to be tested
   * @param supportedGroups Hex value of the supported groups extension to be tested
   */
  private void runTls12SupportedGroupsScenario(String host, String supportedGroups) {
    checkHost(host);

    var tlsCipherSuites = getTlsTestValidEcdheCipherSuites();

    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    TlsHashAlgorithm.supportedByPolicy().stream().map(Enum::toString).forEach(log::info);

    var tlsTestToolConfigBuffer = getTlsTestToolConfigBuffer(
        SUPPORTED_SIGNATURE_HASH_ALGOS, supportedGroups, tlsCipherSuites, host);
    runTlsTestTool(tlsTestToolConfigBuffer);
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

    checkHost(host);

    var tlsCipherSuites = getTlsTestValidCipherSuites();

    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    for (var s : TlsHashAlgorithm.supportedByPolicy()) {
      log.info(s.toString());
    }

    var tlsTestToolConfigBuffer = getTlsTestToolConfigBuffer(
        SUPPORTED_SIGNATURE_HASH_ALGOS, VALID_SUPPORTED_GROUPS, tlsCipherSuites, host);

    // Add configuration to initiate a TLS handshake renegotiation
    tlsTestToolConfigBuffer += "manipulateRenegotiate=\n";
    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Checks whether a Server Key exchange uses one of the supported hash functions.
   */
  @Dann("verwendet der Server-Schlüsselaustausch eine der unterstützten Hashfunktionen")
  @Then("the server key exchange uses one of the supported hash functions")
  public void checkIfTheServerKeyExchangeUsesOneOfTheSupportedHashFunctions() {
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

    // Check if all the supported/mandatory SignatureAndHashAlgorithms are present
    var hashFunctionsHashSet = hashFunctions
        .asLists()
        .stream()
        .filter(row -> row != null && !row.isEmpty())
        .map(row -> row.getFirst() != null ? row.getFirst().trim() : "")
        .filter(hashFunction -> !hashFunction.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));

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

    var tlsCipherSuites = getTlsTestValidCipherSuites();
    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    TlsHashAlgorithm.unsupportedByPolicy()
        .forEach(signatureHashAlgo -> log.info("{}", signatureHashAlgo));

    var tlsTestToolConfigBuffer = getTlsTestToolConfigBuffer(UNSUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS, tlsCipherSuites, host);
    runTlsTestTool(tlsTestToolConfigBuffer);

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

    // Check if all the supported/mandatory SignatureAndHashAlgorithms are present
    var hashFunctionsHashSet = hashFunctions
        .asLists()
        .stream()
        .filter(row -> row != null && !row.isEmpty())
        .map(row -> row.getFirst() != null ? row.getFirst().trim() : "")
        .filter(hashFunction -> !hashFunction.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));

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

    var tlsCipherSuites = getTlsTestValidCipherSuites();
    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    for (var signatureHashAlgo : TlsHashAlgorithm.supportedByPolicy()) {
      log.info("{}", signatureHashAlgo);
    }

    var tlsTestToolConfigBuffer = getTlsTestToolConfigBuffer(SUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS, tlsCipherSuites, host);
    runTlsTestTool(tlsTestToolConfigBuffer);

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
    switch (expectation) {
      case ERFOLGREICH -> checkForMessageInTlsLogs("Handshake successful");
      case NICHT_ERFOLGREICH -> checkForMessageInTlsLogs("TLS handshake failed");
      default -> throw new AssertionError("Unsupported TLS handshake expectation: " + expectation);
    }
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
   * Checks whether the specified message is present in the TLS logs.
   */
  public void checkForMessageInTlsLogs(String message) {

    // Check if the logs are present
    if (tlsLogs == null || tlsLogs.trim().isEmpty()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    // Check if the message is present
    if (message == null || message.trim().isEmpty()) {
      throw new AssertionError("The message to be found is empty or null.");
    }

    Assertions
        .assertThat(tlsLogs.contains(message))
        .withFailMessage(
            "The '%s' message could not be found in TLS logs. %s %s",
            message,
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
        .map(TlsHashAlgorithm::fromName)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
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
    checkHost(host);

    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    for (var s : TlsHashAlgorithm.supportedByPolicy()) {
      log.info(s.toString());
    }

    var tlsTestToolConfigBuffer = getTlsTestToolConfigBuffer(
        SUPPORTED_SIGNATURE_HASH_ALGOS, VALID_SUPPORTED_GROUPS, tlsCipherSuites, host);
    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Check if the host is empty or null.
   */
  void checkHost(String host) {
    if (host == null || host.trim().isEmpty()) {
      throw new AssertionError("The host is empty or null.");
    }
    log.info("Performing the TLS-Test for host: {}", host);
  }

  /**
   * Executes the TLS test tool.
   */
  void runTlsTestTool(String tlsTestToolConfigBuffer) {

    // Execute the TLS test tool
    var tlsTestToolLocation = System.getProperty("user.dir") + TLSTESTTOOLPATH;

    // Select the correct TLS Test Tool binary
    var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    var isWsl = isWslEnvironment();
    var useWslBinary = isWindows || isWsl;

    if (useWslBinary) {
      tlsTestToolLocation += "-wsl";
    } else {
      tlsTestToolLocation += "-alpine";
    }

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

    // Set the permissions so that the TLS Test tool can execute
    try {
      if (isWindows) {
        new ProcessBuilder("wsl", "chmod", "+x", tlsTestToolLocation)
            .inheritIO()
            .start()
            .waitFor();
      } else {
        new ProcessBuilder("chmod", "+x", tlsTestToolLocation)
            .inheritIO()
            .start()
            .waitFor();
      }
    } catch (java.io.IOException | InterruptedException e) {
      throw new AssertionError("Error setting permissions for the TLS test tools", e);
    }

    log.debug("TLS Test Tool path = {}", tlsTestToolLocation);
    log.debug("TLS Test Tool configuration file path: {}", configFileLocation);

    // Clear the logs
    tlsLogs = "";
    try {
      // Execute the TLS test tool
      List<String> command = new ArrayList<>();
      if (isWindows) {
        command.add("wsl");
      }
      command.add(tlsTestToolLocation);
      command.add("--configFile=" + configFileLocation);
      var pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);  // merge stderr into stdout
      var process = pb.start();
      var reader = new BufferedReader(
          new InputStreamReader(process.getInputStream())
      );
      // Extract the logs
      tlsLogs = reader.lines().collect(Collectors.joining("\n"));
      log.debug("TLS Test Tool Logs:");
      log.debug(tlsLogs);
      Serenity.recordReportData()
          .withTitle("TLS Test Tool Logs:")
          .andContents(tlsLogs);
    } catch (java.io.IOException e) {
      throw new AssertionError("Error when running the TLS test tool", e);
    }
  }

  /**
   * Creates a temporary configuration file for the TLS test tool.
   */
  private String createTempConfigFile(String contents) {

    if (contents == null || contents.trim().isEmpty()) {
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
   * Checks whether the sever sends a renegotiation_info extension in the server hello record.
   */
  @Dann("ist die Erweiterung renegotiation_info im Server-Hello vorhanden")
  @Then("the renegotiation_info extension is present in the Server Hello")
  public void istDieErweiterungImServerHelloVorhanden() {
    Assertions
        .assertThat(serverHelloHasEmptyRenegotiationInfo())
        .withFailMessage("The renegotiation_info could not be found in the TLS Server Hello.")
        .isTrue();
  }

  /**
   * Checks whether the sever sends a renegotiation_info extension in the server hello record.
   *
   * @return true if renegotiation_info (0xff01) is present with length == 0
   */
  private boolean serverHelloHasEmptyRenegotiationInfo() {

    // Check if the logs are present
    if (tlsLogs == null || tlsLogs.trim().isEmpty()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    // Extract hex bytes after "ServerHello.extensions="
    var p = Pattern.compile(
        "ServerHello\\.extensions\\s*=\\s*([0-9a-fA-F]{2}(?:\\s+[0-9a-fA-F]{2})*)");
    var m = p.matcher(tlsLogs);
    if (!m.find()) {
      return false;
    }

    var hexBytes = m.group(1).trim().split("\\s+");
    var bytes = new byte[hexBytes.length];
    for (var i = 0; i < hexBytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hexBytes[i], 16);
    }

    // Parse TLS extensions: type(2) | length(2) | data(length)
    var i = 0;
    while (i + 4 <= bytes.length) {
      var type = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
      var len = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
      i += 4;

      if (i + len > bytes.length) {
        return false; // malformed
      }

      // renegotiation_info = 0xff01
      if (type == 0xFF01) {
        // For initial handshake, renegotiated_connection_length must be 0
        return len == 1 && bytes[i] == 0x00;
      }

      i += len;
    }

    return false;
  }

  /**
   * Supported/required signature hash algorithms.
   */
  public enum SignatureAndHashAlgorithms {
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
  public enum SignatureSchemes {
    rsa_pkcs1_sha256,
    rsa_pkcs1_sha384,
    rsa_pkcs1_sha512,
  }

  /**
   * TLS 1.2 hash algorithm IDs used in signature_algorithms and ServerKeyExchange metadata.
   */
  public enum TlsHashAlgorithm {
    MD5(1, "0x01", false),
    SHA1(2, "0x02", false),
    SHA224(3, "0x03", false),
    SHA256(4, "0x04", true),
    SHA384(5, "0x05", true),
    SHA512(6, "0x06", true),
    UNKNOWN(-1, "n/a", false);

    @Getter
    private final int value;
    @Getter
    private final String hexValue;
    @Getter
    private final boolean supportedByPolicy;

    TlsHashAlgorithm(int value, String hexValue, boolean supportedByPolicy) {
      this.value = value;
      this.hexValue = hexValue;
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
     * @param name textual hash name, e.g. {@code SHA256} or {@code RSA_SHA256}
     * @return matching hash enum, or {@code null} when no supported name is recognized
     */
    public static TlsHashAlgorithm fromName(String name) {
      var normalized = name.trim().toUpperCase(Locale.ROOT);
      // Accept plain names like SHA256 and combined names like RSA_SHA256.
      if (normalized.contains("SHA512")) {
        return SHA512;
      }
      if (normalized.contains("SHA384")) {
        return SHA384;
      }
      if (normalized.contains("SHA256")) {
        return SHA256;
      }
      if (normalized.contains("SHA224")) {
        return SHA224;
      }
      if (normalized.contains("SHA1")) {
        return SHA1;
      }
      if (normalized.contains("MD5")) {
        return MD5;
      }
      return null;
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
          .filter(algorithm -> algorithm != UNKNOWN && !algorithm.isSupportedByPolicy())
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
    RSA(1, "0x01"),
    DSA(2, "0x02"),
    ECDSA(3, "0x03"),
    UNKNOWN(-1, "n/a");

    @Getter
    private final int value;
    @Getter
    private final String hexValue;

    TlsSignatureAlgorithm(int value, String hexValue) {
      this.value = value;
      this.hexValue = hexValue;
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
  }

  /**
   * Readable profile tokens for TLS supported-groups extension variants used in feature files.
   */
  public enum TlsSupportedGroupProfile {
    P256_ONLY("p256_only", "000a000400020017"),
    P384_ONLY("p384_only", "000a000400020018"),
    UNSUPPORTED_MIX("unsupported_mix", "000a001400120010001300150019001c001d001e01010102");

    @Getter
    private final String profileName;
    @Getter
    private final String supportedGroupsExtensionHex;

    TlsSupportedGroupProfile(String profileName, String supportedGroupsExtensionHex) {
      this.profileName = profileName;
      this.supportedGroupsExtensionHex = supportedGroupsExtensionHex;
    }

    /**
     * Resolve enum by readable supported-group profile token used in feature files.
     *
     * @param profileName profile token
     * @return matching profile
     */
    public static TlsSupportedGroupProfile fromProfileName(String profileName) {
      return Arrays.stream(values())
          .filter(profile -> profile.profileName.equals(profileName))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Unsupported supported-group profile: " + profileName));
    }
  }

  /**
   * Readable profile tokens for mandatory TLS cipher suites used in feature files.
   */
  public enum TlsCipherSuiteProfile {
    ECDHE_RSA_AES_128_GCM_SHA256("ecdhe_rsa_aes_128_gcm_sha256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "(0xC0,0x2F)"),
    ECDHE_RSA_AES_256_GCM_SHA384("ecdhe_rsa_aes_256_gcm_sha384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "(0xC0,0x30)");

    @Getter
    private final String profileName;
    @Getter
    private final String cipherSuiteName;
    @Getter
    private final String tlsTestToolCipherSuiteValue;

    TlsCipherSuiteProfile(String profileName, String cipherSuiteName, String tlsTestToolCipherSuiteValue) {
      this.profileName = profileName;
      this.cipherSuiteName = cipherSuiteName;
      this.tlsTestToolCipherSuiteValue = tlsTestToolCipherSuiteValue;
    }

    /**
     * Resolve enum by readable ciphersuite profile token used in feature files.
     *
     * @param profileName profile token
     * @return matching profile
     */
    public static TlsCipherSuiteProfile fromProfileName(String profileName) {
      return Arrays.stream(values())
          .filter(profile -> profile.profileName.equals(profileName))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Unsupported ciphersuite profile: " + profileName));
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
}
