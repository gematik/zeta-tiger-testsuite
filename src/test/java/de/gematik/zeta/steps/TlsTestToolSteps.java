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

import de.gematik.zeta.model.tls.SignatureAndHashAlgorithms;
import de.gematik.zeta.model.tls.TlsCipherSuite;
import de.gematik.zeta.model.tls.TlsEndpointRole;
import de.gematik.zeta.model.tls.TlsHandshakeExpectation;
import de.gematik.zeta.model.tls.TlsHashAlgorithm;
import de.gematik.zeta.model.tls.TlsLibrary;
import de.gematik.zeta.model.tls.TlsServerCertificates;
import de.gematik.zeta.model.tls.TlsSignatureAlgorithm;
import de.gematik.zeta.model.tls.TlsSignatureSchemes;
import de.gematik.zeta.model.tls.TlsSupportedGroup;
import de.gematik.zeta.model.tls.TlsVersion;
import de.gematik.zeta.steps.tls.TlsCertificateLogInspector;
import de.gematik.zeta.steps.tls.TlsLogParser;
import de.gematik.zeta.steps.tls.TlsTestToolConfigBuilder;
import de.gematik.zeta.steps.tls.TlsTestToolRunContext;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.ParameterType;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.opentest4j.TestAbortedException;

/**
 * Cucumber step definitions for TLS Test Tool operations.
 *
 * <p>This class provides step definitions for the TLS Tests.</p>
 */
@Slf4j
public class TlsTestToolSteps {

  /**
   * Supported groups required for the TLS handshake (A_28868). P-256 / P-384 and optional brainpool curves.
   */
  private static final String VALID_SUPPORTED_GROUPS = "000a000a000800170018001a001b";

  /**
   * RFC 6066 status_request extension for requesting one OCSP response during TLS 1.2 handshakes.
   */
  private static final String OCSP_STATUS_REQUEST_EXTENSION = "000500050100000000";
  private static final Set<String> TLS_ERROR_ALERT_DESCRIPTION_IDS =
      Set.of(
          "0a", "14", "16", "28", "2a", "2b", "2c", "2d", "2e", "2f", "30", "31", "32", "33", "46", "47", "50", "56",
          "6d", "6e", "70", "71", "73", "74", "78");

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

  private static final Pattern TLS_HASH_ALGORITHM_PATTERN = Pattern.compile("Server used HashAlgorithm\\s+(\\d+)");
  private static final Pattern TLS_CERTIFICATE_VERIFY_ALGORITHM_PATTERN =
      Pattern.compile("CertificateVerify\\.algorithm=([0-9a-fA-F]{2})\\s+([0-9a-fA-F]{2})");
  private static final Pattern TLS_SERVER_KEY_EXCHANGE_NAMED_CURVE_PATTERN =
      Pattern.compile("ServerKeyExchange\\.params\\.curve_params\\.namedcurve=(\\d+)");
  private static final Pattern TLS_RENEGOTIATION_PHASE_PATTERN =
      Pattern.compile("=>\\s*renegotiate|Performing renegotiation\\.");
  private static final Pattern TCP_IP_CONNECTION_ESTABLISHED_PATTERN =
      Pattern.compile("(?m)\\bTCP/IP connection (?:to \\S+ established|from \\S+ received)\\.");
  private static final Pattern OPENSSL_CLIENT_STATUS_REQUEST_PATTERN =
      Pattern.compile(
          "Received(?: TLS)? Record(?:(?!Sent(?: TLS)? Record).)*ClientHello"
              + "(?:(?!Sent(?: TLS)? Record).)*"
              + "extension_type=status_request\\s*\\(5\\)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern OPENSSL_SERVER_STATUS_REQUEST_PATTERN =
      Pattern.compile(
          "Sent(?: TLS)? Record(?:(?!Received(?: TLS)? Record).)*ServerHello"
              + "(?:(?!Received(?: TLS)? Record).)*"
              + "extension_type=status_request\\s*\\(5\\)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern OPENSSL_CERTIFICATE_STATUS_PATTERN =
      Pattern.compile(
          "Sent(?: TLS)? Record(?:(?!Received(?: TLS)? Record).)*CertificateStatus",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);


  /**
   * Scenario-local TLS test tool lifecycle context.
   */
  private final TlsTestToolRunContext tlsTestToolRunContext = new TlsTestToolRunContext();

  /**
   * String for storing the TLS logs.
   */
  private String tlsLogs;

  /**
   * Stores the TLS 1.2 hash algorithms that were intentionally offered in the previous setup step. This allows failure messages to explain
   * what was tested versus what the server selected.
   */
  private LinkedHashSet<TlsHashAlgorithm> lastOfferedTlsHashAlgorithms = new LinkedHashSet<>();

  /**
   * Verifies that a resolved cipher-suite profile belongs to the expected TLS version before using it in a version-specific step
   * definition.
   *
   * @param profile         resolved cipher-suite profile token from the feature file
   * @param expectedVersion TLS version required by the calling step
   */
  private static void requireCipherSuiteProfileVersion(TlsCipherSuite profile, TlsVersion expectedVersion) {
    if (profile == null) {
      throw new AssertionError("The cipher suite profile is null.");
    }
    if (profile.getTlsVersion() != expectedVersion) {
      throw new AssertionError("The cipher suite profile is not a " + expectedVersion.getDisplayName() + " profile.");
    }
  }

  /**
   * Parses non-empty first-column values from a Cucumber data table.
   *
   * @param table      data table containing values in the first column
   * @param setFactory target set factory
   * @param <S>        target set type
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
   * Asserts that a parsed TLS structure is non-empty and contains only allowed values.
   *
   * @param actualValues    parsed values
   * @param isAllowed       predicate that defines whether a value is policy-compliant
   * @param emptyMessage    failure message for an empty value set
   * @param mismatchMessage failure message template for unsupported values
   * @param <T>             parsed value type
   */
  private static <T> void assertOnlyAllowedValues(
      List<T> actualValues,
      Predicate<T> isAllowed,
      String emptyMessage,
      String mismatchMessage) {
    Assertions
        .assertThat(actualValues.isEmpty())
        .withFailMessage(emptyMessage)
        .isFalse();

    var mismatches = actualValues.stream()
        .filter(isAllowed.negate())
        .distinct()
        .toList();

    Assertions
        .assertThat(mismatches.isEmpty())
        .withFailMessage(mismatchMessage, mismatches)
        .isTrue();
  }

  /**
   * Configures and runs the TLS test tool (server) for TLS 1.1.
   *
   */
  @Gegebensei("eine TlsTestTool-Server-Konfiguration nur für TLS 1.1")
  @Given("the TlsTestTool server configuration data with only TLS 1.1 is created")
  public void setTls12TlsTestToolServerConfigForTls1_1() {

    var tlsTestToolConfigBuffer = TlsTestToolConfigBuilder.buildTls12ServerBaseConfig()
        + "manipulateHelloVersion=(0x03,0x02)\n";

    log.info("The Server only offers a TLS 1.1 connection.");

    runTlsTestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.1.
   *
   * @param host Host to be tested
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} nur für TLS 1.1")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} with only TLS 1.1 is created")
  public void setTls12TlsTestToolConfigForTls1_1(String host) {
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

    runTlsTestToolClient(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.3 with non-recommended signature schemes.
   *
   * @param host             host to be tested
   * @param signatureSchemes signature schemes that are expected to be non-recommended for TLS 1.3
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Konfiguration für den Host {tigerResolvedString} mit den folgenden nicht empfohlenen TLS-1.3-Signature-Schemes:")
  @Given("the TLS 1.3 TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following non recommended TLS 1.3 signature schemes:")
  public void setTls13TlsTestToolConfigForNonRecommendedSignatureSchemes(String host,
      DataTable signatureSchemes) {
    checkHost(host);
    if (signatureSchemes == null) {
      throw new AssertionError("The signature schemes table is null.");
    }

    // Check if all non-recommended TLS 1.3 signature schemes are present
    var signatureSchemeHashSet = parseNonEmptyFirstColumn(signatureSchemes, HashSet::new);

    var nonRecommendedSignatureSchemes = TlsSignatureSchemes.nonRecommendedSchemeNames();

    if (!nonRecommendedSignatureSchemes.equals(signatureSchemeHashSet)) {
      throw new AssertionError("""
          For this test, the signature hash algorithms cannot be changed.
          They must be:
          - rsa_pkcs1_sha256
          - rsa_pkcs1_sha384
          - rsa_pkcs1_sha512""");
    }

    log.info(
        "The TLS 1.3 ClientHello offers the following TLS 1.3 cipher suites in accordance with A_28868");
    log.info("TLS_AES_128_GCM_SHA256");
    log.info("TLS_AES_256_GCM_SHA384");

    var mandatoryCipherSuites = TlsCipherSuite.mandatoryTls13CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.joining(","));

    runTls13ClientScenario(
        host,
        mandatoryCipherSuites,
        nonRecommendedSignatureSchemes,
        TlsSignatureSchemes.nonRecommendedSchemeHexValues(),
        TlsSupportedGroup.tls13SupportedGroups());
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.3 with the signature schemes from the scenario.
   *
   * <p>This scenario currently expects the recommended TLS-1.3 signature-scheme set.</p>
   *
   * @param host             host to be tested
   * @param signatureSchemes TLS-1.3 signature schemes provided by the scenario
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Konfiguration für den Host {tigerResolvedString} mit den folgenden TLS-1.3-Signature-Schemes:")
  @Given("the TLS 1.3 TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.3 signature schemes:")
  public void setTls13TlsTestToolConfigSignatureSchemes(String host,
      DataTable signatureSchemes) {
    checkHost(host);
    if (signatureSchemes == null) {
      throw new AssertionError("The signature schemes table is null.");
    }

    log.info(
        "The TLS 1.3 ClientHello offers the following TLS 1.3 cipher suites in accordance with A_28868");
    log.info("TLS_AES_128_GCM_SHA256");
    log.info("TLS_AES_256_GCM_SHA384");

    var mandatoryCipherSuites = TlsCipherSuite.mandatoryTls13CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.joining(","));

    // Convert the scenario-provided TLS 1.3 signature schemes to tls-test-tool wire values.
    var signatureSchemeHashSet = parseNonEmptyFirstColumn(signatureSchemes, HashSet::new);
    var signatureSchemeHexValues = TlsSignatureSchemes.schemeHexValuesForSchemeNames(signatureSchemeHashSet);
    runTls13ClientScenario(
        host,
        mandatoryCipherSuites,
        signatureSchemeHashSet,
        signatureSchemeHexValues,
        TlsSupportedGroup.tls13SupportedGroups());
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2 with unsupported RSA Signature and hash algorithms.
   *
   * @param host                    Host to be tested
   * @param signatureHashAlgorithms Signature and hash algorithms that must not be supported
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} mit den folgenden TLS-1.2-Signatur-Hash-Algorithmen:")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.2 signature hash algorithms:")
  public void setTls12TlsTestToolConfigForUnsupportedSignatureHashAlgorithms(String host,
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
        TlsTestToolConfigBuilder.buildTls12ValidCipherSuites(),
        RSA_HASH_VARIANTS,
        VALID_SUPPORTED_GROUPS,
        supportedSignatureAndHashAlgorithms,
        false);
  }

  /**
   * Checks whether a TLS “alert” has been received.
   */
  @Dann("lehnt der ZETA Guard Endpunkt das ClientHello ab und sendet eine Alert-Nachricht mit Description-ID {string}")
  @Dann("lehnt der ZETA Client das ServerHello ab und sendet eine Alert-Nachricht mit Description-ID {string}")
  @Dann("der ZETA Client hat eine Alert-Nachricht mit Description-ID {string} gesendet")
  @Then("the Zeta Guard endpoint does not accept the ClientHello and sends an alert message with the description id {string}")
  @Then("the Zeta Client does not accept the ServerHello and sends an alert message with the description id {string}")
  @Then("the Zeta Client sends an alert message with the description id {string}")
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
   * Checks whether a TLS endpoint sent a fatal alert with a TLS error alert description.
   */
  @Dann("der ZETA Client hat eine fatale Alert-Nachricht mit einer TLS-Fehlermeldung gesendet")
  @Then("the Zeta Client sends a fatal alert message with a TLS error description")
  public void endpointSendsFatalTlsErrorAlert() {
    requireTlsLogs();

    Assertions
        .assertThat(tlsLogs.contains("Alert message received"))
        .withFailMessage(
            "'Alert' message not received from endpoint. %s %s",
            extractAlertSummary(),
            buildHashNegotiationSummary())
        .isTrue();

    Assertions
        .assertThat(TlsLogParser.hasAlertLevel(tlsLogs, "02"))
        .withFailMessage(
            "'Alert.level=02' not found in TLS logs. %s %s",
            extractAlertSummary(),
            buildHashNegotiationSummary())
        .isTrue();

    var hasListedFatalErrorAlert = TLS_ERROR_ALERT_DESCRIPTION_IDS.stream()
        .anyMatch(descriptionId -> TlsLogParser.hasAlertWithLevelAndDescription(tlsLogs, "02", descriptionId));

    Assertions
        .assertThat(hasListedFatalErrorAlert)
        .withFailMessage(
            "No fatal TLS error alert description found in TLS logs. %s %s",
            extractAlertSummary(),
            buildHashNegotiationSummary())
        .isTrue();
  }

  /**
   * /** Configures and runs the TLS test tool for TLS 1.2.
   *
   * @param host Host to be tested
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString}")
  public void setValidTlsTestToolConfig(String host) {
    var tlsCipherSuites = TlsTestToolConfigBuilder.buildTls12ValidCipherSuites();
    runTls12TestTool(host, tlsCipherSuites);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2 with an OCSP status_request ClientHello extension.
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TLS 1.2 TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit OCSP Status Request")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} with OCSP status request")
  public void setValidTlsTestToolConfigWithOcspStatusRequest(String host) {
    runTls12ClientScenario(
        host,
        TlsTestToolConfigBuilder.buildTls12ValidCipherSuites(),
        SUPPORTED_SIGNATURE_HASH_ALGOS + OCSP_STATUS_REQUEST_EXTENSION,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.supportedByPolicy(),
        false);
  }

  /**
   * Checks whether a ServerHello record was not received.
   */
  @Dann("wurde kein ServerHello-Record empfangen")
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
   * Resolves a Server-Key-Exchange expectation token from a feature file.
   *
   * @param expectation textual expectation token
   * @return {@code true} if the Server-Key-Exchange record is expected, otherwise {@code false}
   */
  @ParameterType("gesendet|nicht gesendet|sent|not sent")
  public boolean tlsServerKeyExchangeExpectation(String expectation) {
    return switch (expectation) {
      case "gesendet", "sent" -> true;
      case "nicht gesendet", "not sent" -> false;
      default -> throw new AssertionError("Unsupported Server-Key-Exchange expectation: " + expectation);
    };
  }

  /**
   * Checks whether the Server-Key-Exchange record matches the expected result.
   *
   * @param expectedSent expected Server-Key-Exchange result
   */
  @Dann("wird der Server-Key-Exchange-Datensatz {tlsServerKeyExchangeExpectation}")
  @Then("the server key exchange record is {tlsServerKeyExchangeExpectation}")
  public void checkIfTheServerKeyExchangeMatchesExpectation(boolean expectedSent) {
    requireTlsLogs();
    if (expectedSent) {
      Assertions
          .assertThat(tlsLogs.contains("ServerKeyExchange.params.curve_params.namedcurve"))
          .withFailMessage("ServerKeyExchange message not received.")
          .isTrue();
    } else {
      Assertions
          .assertThat(!(tlsLogs.contains("ServerKeyExchange.params.curve_params.namedcurve")
              || tlsLogs.contains("Bad ServerKeyExchange message received")))
          .withFailMessage("ServerKeyExchange message received.")
          .isTrue();
    }
  }

  /**
   * Skips TLS 1.3 scenarios when the endpoint does not support TLS 1.3.
   *
   * <p>If the log indicates protocol-version rejection, the scenario is aborted as skipped. Other failures are
   * left to subsequent assertions so real conformance bugs still fail the scenario.</p>
   */
  @Dann("wird TLS 1.3 unterstützt, andernfalls wird das Szenario übersprungen")
  @Then("TLS 1.3 is supported or the scenario is skipped")
  public void skipScenarioIfTls13IsNotSupportedByTheServer() {
    requireTlsLogs();

    if (tlsLogs.contains("Alert.description=46")
        || tlsLogs.toLowerCase(Locale.ROOT).contains("protocol version")) {
      throw new TestAbortedException("TLS 1.3 is not supported by the tested endpoint.");
    }
  }

  /**
   * Checks whether the ClientHello advertises TLS 1.3 via the supported_versions extension and skips the scenario otherwise.
   */
  @Dann("das ClientHello signalisiert TLS-1.3-Unterstützung, andernfalls wird das Szenario übersprungen")
  @Then("the ClientHello record signals TLS 1.3 support or the scenario is skipped")
  public void skipScenarioIfClientHelloDoesNotSignalTls13Support() {
    requireTlsLogs();

    if (!TlsLogParser.clientHelloSignalsTls13Support(tlsLogs)) {
      throw new TestAbortedException(
          "ClientHello does not advertise TLS 1.3 support via supported_versions.");
    }
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
   * Configures and runs TLS 1.3 with the provided cipher-suite list.
   *
   * @param host                Host to be tested
   * @param cipherSuiteHexValue Hex value of the cipher suite(s) to be tested
   */
  private void configureTls13ForCipherSuites(String host, String cipherSuiteHexValue) {
    checkHost(host);
    if (cipherSuiteHexValue == null || cipherSuiteHexValue.isBlank()) {
      throw new AssertionError("The Cipher Suite value is empty or null.");
    }

    log.info("The TLS 1.3 ClientHello offers the %s TLS 1.3 cipher suites.".formatted(cipherSuiteHexValue));
    runTlsTestToolClient(
        TlsTestToolConfigBuilder.buildTls13ClientConfig(
            TlsSignatureSchemes.recommendedSchemeHexValues(),
            TlsSupportedGroup.tls13SupportedGroups(),
            cipherSuiteHexValue,
            host));
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
   * @param cipherSuiteId cipher suite unique id (e.g. {@code ecdhe_ecdsa_aes_128_gcm_sha256})
   * @return matching cipher suite
   */
  @ParameterType("ecdhe_ecdsa_aes_128_gcm_sha256|ecdhe_ecdsa_aes_256_gcm_sha384|aes_128_gcm_sha256|aes_256_gcm_sha384")
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
          + "zeta_tls_test_tool_server_ecdsa_crl_only_certificate|zeta_tls_test_tool_server_ecdsa_subca_chain_certificate|"
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
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für das Cipher-Suite-Profil {tlsCipherSuiteProfile}")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the cipher suite profile {tlsCipherSuiteProfile}")
  public void setValidTlsTestToolConfigForCipherSuiteProfile(String host, TlsCipherSuite profile) {
    requireCipherSuiteProfileVersion(profile, TlsVersion.TLS_1_2);
    configureTls12ForCipherSuites(host, profile.getTlsTestToolCipherSuiteValue());
  }

  /**
   * Configures and runs TLS 1.3 for a readable cipher suite profile.
   *
   * @param host    Host to be tested
   * @param profile cipher suite profile mapped to tls-test-tool tuple syntax
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für das Cipher-Suite-Profil {tlsCipherSuiteProfile}")
  @Given("the TLS 1.3 TlsTestTool configuration data for the host {tigerResolvedString} for the cipher suite profile {tlsCipherSuiteProfile}")
  public void setValidTlsTestToolConfigForTls13CipherSuiteProfile(String host, TlsCipherSuite profile) {
    requireCipherSuiteProfileVersion(profile, TlsVersion.TLS_1_3);
    configureTls13ForCipherSuites(host, profile.getTlsTestToolCipherSuiteValue());
  }

  /**
   * Configures and runs TLS 1.2 for a specific group.
   *
   * @param supportedGroup The supported group
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützte Gruppe {string}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the Supported group {string}")
  public void setValidTls12TlsTestToolServerConfigForSupportedGroup(String supportedGroup) {

    if (supportedGroup == null || supportedGroup.isBlank()) {
      throw new AssertionError("The Supported group value is empty or null.");
    }

    var group = TlsSupportedGroup.fromDisplayName(supportedGroup);
    if (group == TlsSupportedGroup.UNKNOWN) {
      throw new AssertionError("The Supported Group value is unknown.");
    }

    var tlsTestToolConfigBuffer = TlsTestToolConfigBuilder.buildTls12ServerBaseConfig()
        + "manipulateEllipticCurveGroup=" + group.getDisplayName() + "\n";

    runTls12TestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs TLS 1.2 for a specific hash.
   *
   * @param hashAlgo The hash algorithm
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Server-Konfiguration für den Hash-Algorithmus {string}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the Hash-Algo {string}")
  public void setValidTls12TlsTestToolServerConfigForHashAlgo(String hashAlgo) {

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

    var tlsTestToolConfigBuffer = TlsTestToolConfigBuilder.buildTls12ServerBaseConfig(TlsLibrary.OPENSSL)
        + "tlsSignatureAlgorithms=" + TlsTestToolConfigBuilder.buildTls12SupportedSignatureHashPairs(listOfHashes) + "\n";

    runTlsTestToolServer(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs TLS 1.2 for a readable cipher suite profile.
   *
   * @param profile cipher suite profile mapped to tls-test-tool tuple syntax
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Server-Konfiguration für das Cipher-Suite-Profil {tlsCipherSuiteProfile}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the cipher suite profile {tlsCipherSuiteProfile}")
  public void setValidTls12TlsTestToolServerConfigForCipherSuiteProfile(TlsCipherSuite profile) {
    requireCipherSuiteProfileVersion(profile, TlsVersion.TLS_1_2);
    configureAndRunTls12ServerForCipherSuites(profile.getTlsTestToolCipherSuiteValue());
  }

  /**
   * Configures and runs TLS 1.3 for a readable cipher suite profile.
   *
   * @param profile cipher suite profile mapped to tls-test-tool tuple syntax
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Server-Konfiguration für das Cipher-Suite-Profil {tlsCipherSuiteProfile}")
  @Given("the TLS 1.3 TlsTestTool server configuration data for the cipher suite profile {tlsCipherSuiteProfile}")
  public void setValidTls13TlsTestToolServerConfigForCipherSuiteProfile(TlsCipherSuite profile) {
    requireCipherSuiteProfileVersion(profile, TlsVersion.TLS_1_3);
    runTlsTestToolServer(TlsTestToolConfigBuilder.buildTls13ServerConfig(profile.getTlsTestToolCipherSuiteValue()));
  }

  /**
   * Configures and runs TLS 1.2 for a supported cipher suite and a HelloRequest message.
   *
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Server-Konfiguration mit HelloRequest für eine der unterstützten Cipher-Suiten")
  @Given("the TLS 1.2 TlsTestTool server configuration data with HelloRequest support for a supported ciphersuite")
  public void setValidTls12TlsTestToolServerConfigHelloRequestForASupportedCipherSuite() {
    runTlsTestToolServer(
        TlsTestToolConfigBuilder.buildTls12ServerConfig(
            TlsTestToolConfigBuilder.buildSupportedCipherSuitesValue(TlsVersion.TLS_1_2),
            "manipulateRenegotiate="));
  }

  /**
   * Configures and runs TLS 1.2 for all supported cipher suites.
   *
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the supported ciphersuites")
  public void setValidTls12TlsTestToolServerConfigForAllSupportedCipherSuite() {
    runTlsTestToolServer(
        TlsTestToolConfigBuilder.buildTls12ServerConfig(TlsTestToolConfigBuilder.buildSupportedCipherSuitesValue(TlsVersion.TLS_1_2)));
  }

  /**
   * Configures and runs TLS 1.3 for all supported cipher suites.
   *
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten")
  @Given("the TLS 1.3 TlsTestTool server configuration data for the supported ciphersuites")
  public void setValidTls13TlsTestToolServerConfigForAllSupportedCipherSuite() {
    runTlsTestToolServer(TlsTestToolConfigBuilder.buildTls13ServerConfigForSupportedCipherSuites());
  }

  /**
   * Configures and runs TLS 1.2 for all supported cipher suite profiles for a specific certificate.
   *
   * @param tlsServerCertificate certificate descriptor used for the server configuration
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit {tlsServerCertificate}")
  @Given("the TLS 1.2 TlsTestTool server configuration data for the supported ciphersuites for {tlsServerCertificate}")
  public void setValidTls12TlsTestToolServerConfigForACertificate(TlsServerCertificates tlsServerCertificate) {
    runTlsTestToolServer(
        TlsTestToolConfigBuilder.buildTls12ServerConfig(
            TlsTestToolConfigBuilder.buildSupportedCipherSuitesValue(TlsVersion.TLS_1_2),
            "manipulateForceCertificateUsage="),
        tlsServerCertificate);
  }

  /**
   * Configures the OpenSSL TLS 1.2 server required for OCSP stapling support.
   *
   * @param tlsServerCertificate certificate descriptor used for the server configuration
   */
  @Gegebensei(
      "eine TLS-1.2-OpenSSL-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit {tlsServerCertificate}")
  @Given(
      "the TLS 1.2 OpenSSL TlsTestTool server configuration data for the supported ciphersuites for {tlsServerCertificate}")
  public void setValidTls12OpenSslServerConfigForACertificate(
      TlsServerCertificates tlsServerCertificate) {
    runTlsTestToolServer(
        TlsTestToolConfigBuilder.buildTls12ServerConfig(
            TlsLibrary.OPENSSL,
            TlsTestToolConfigBuilder.buildSupportedCipherSuitesValue(TlsVersion.TLS_1_2),
            "manipulateForceCertificateUsage="),
        tlsServerCertificate);
  }

  /**
   * Configures and runs TLS 1.3 for all supported cipher suite profiles for a specific certificate.
   *
   * @param tlsServerCertificate certificate descriptor used for the server configuration
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Server-Konfiguration für die unterstützten Cipher-Suiten mit {tlsServerCertificate}")
  @Given("the TLS 1.3 TlsTestTool server configuration data for the supported ciphersuites for {tlsServerCertificate}")
  public void setValidTls13TlsTestToolServerConfigForACertificate(TlsServerCertificates tlsServerCertificate) {
    runTlsTestToolServer(TlsTestToolConfigBuilder.buildTls13ServerConfigForSupportedCipherSuites(), tlsServerCertificate);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2.
   *
   * @param host Host to be tested
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für die optional unterstützten Cipher-Suiten")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the optional ciphersuites")
  public void setTlsTestToolConfigForOptionalCipherSuite(String host) {
    // optional Cipher suites from [TR-02102-2], Chapter 3.3.1 Table 2
    // besides TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    var optionalTls12Ciphersuite = TlsCipherSuite.optionalTls12CipherSuites().stream().map(
        TlsCipherSuite::getTlsTestToolCipherSuiteValue).collect(Collectors.joining(","));
    var tlsCipherSuites = "tlsCipherSuites=" + optionalTls12Ciphersuite + "\n";
    runTls12TestTool(host, tlsCipherSuites);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2.
   *
   * @param host Host to be tested
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für die nicht unterstützten Cipher-Suiten")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for the unsupported ciphersuites")
  public void setTlsTestToolConfigForInvalidCipherSuite(String host) {
    // Cipher suites besides TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
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
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für die unterstützte Gruppe {string}")
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
   * @param host           Host to be tested
   * @param supportedGroup supported-group
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für das unterstützte-Gruppen-Profil {string}")
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
    } else if (group == TlsSupportedGroup.MANDATORY_MIX) {
      listOfSupportedGroups = TlsSupportedGroup.mandatoryGroups();
    } else {
      listOfSupportedGroups = List.of(group);
    }
    runTls12SupportedGroupsScenario(host, TlsSupportedGroup.buildSupportedGroupsExtension(listOfSupportedGroups));
  }

  /**
   * Configures and runs TLS 1.3 for a readable supported-groups profile.
   *
   * @param host           Host to be tested
   * @param supportedGroup supported-group profile token
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für das unterstützte-Gruppen-Profil {string}")
  @Given("the TLS 1.3 TlsTestTool configuration data for the host {tigerResolvedString} for the supported-group profile {string}")
  public void setValidTls13TlsTestToolConfigForSupportedGroupProfile(String host, String supportedGroup) {

    if (supportedGroup == null || supportedGroup.isBlank()) {
      throw new AssertionError("The Supported group value is empty or null.");
    }

    var group = TlsSupportedGroup.fromDisplayName(supportedGroup);

    if (group == TlsSupportedGroup.UNKNOWN) {
      throw new AssertionError("The Supported Group value is unknown.");
    }

    List<TlsSupportedGroup> listOfSupportedGroups;
    if (group == TlsSupportedGroup.UNSUPPORTED_MIX) {
      // only groups OpenSSL can actually put on the wire — a single unparseable
      // token would make it discard the whole list and fall back to defaults
      // (which include allowed curves), defeating the forbidden-only ClientHello.
      listOfSupportedGroups = TlsSupportedGroup.forbiddenGroupsOfferableInTls13();
    } else {
      listOfSupportedGroups = List.of(group);
    }

    runTls13ClientScenario(
        host,
        TlsTestToolConfigBuilder.buildTls13ValidCipherSuites(),
        TlsSignatureSchemes.recommendedSchemeNames(),
        TlsSignatureSchemes.recommendedSchemeHexValues(),
        TlsSupportedGroup.tls13SupportedGroupsValue(listOfSupportedGroups));
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
        TlsTestToolConfigBuilder.buildTls12ValidEcdheCipherSuites(),
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
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} für TLS-Renegotiation")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} for TLS Renegotiation")
  public void setTlsTestToolConfigForRenegotiation(String host) {
    runTls12ClientScenario(
        host,
        TlsTestToolConfigBuilder.buildTls12ValidCipherSuites(),
        SUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.supportedByPolicy(),
        true);
  }

  /**
   * Configures and runs TLS 1.2 with a semantically invalid RFC 5746 {@code renegotiation_info} extension in the initial ClientHello.
   *
   * <p>The extension is syntactically well-formed but advertises a non-empty
   * {@code renegotiated_connection} value during the initial handshake, which a compliant server must reject.</p>
   *
   * @param host host to be tested
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} mit einer fehlerhaften renegotiation_info-Erweiterung")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} with an invalid renegotiation_info extension")
  public void setTlsTestToolConfigWithInvalidRenegotiationInfoExtension(String host) {
    checkHost(host);

    var invalidRenegotiationInfoExtension = "ff0100020100";
    runTls12ClientScenario(
        host,
        TlsTestToolConfigBuilder.buildTls12ValidCipherSuites(),
        invalidRenegotiationInfoExtension + SUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.supportedByPolicy(),
        false);
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
   * Checks whether the CertificateVerify message uses one of the supported hash functions.
   */
  @Dann("verwendet der Certificate-Verify eine der unterstützten Hashfunktionen")
  @Then("the certificate verify uses one of the supported hash functions")
  public void checkIfTheCertificateVerifyUsesOneOfTheSupportedHashFunctions() {
    requireTlsLogs();
    var matcher = TLS_CERTIFICATE_VERIFY_ALGORITHM_PATTERN.matcher(tlsLogs);

    if (!matcher.find()) {
      if (tlsLogs.contains("TLS handshake failed")) {
        throw new AssertionError(
            "The signature scheme used for CertificateVerify could not be found because the TLS handshake already failed. "
                + extractAlertSummary() + " " + buildHashNegotiationSummary());
      }
      throw new AssertionError("The signature scheme used for CertificateVerify could not be found. "
          + extractAlertSummary() + " " + buildHashNegotiationSummary());
    }

    var selectedScheme = TlsSignatureSchemes.fromAlgorithmBytes(matcher.group(1), matcher.group(2));
    var selectedHashAlgorithm = selectedScheme.getAssociatedHashAlgorithm();

    if (selectedHashAlgorithm != TlsHashAlgorithm.UNKNOWN && selectedHashAlgorithm.isSupportedByPolicy()) {
      log.info("The {} signature scheme using {} was used for CertificateVerify.", selectedScheme.getSchemeName(), selectedHashAlgorithm);
      return;
    }

    throw new AssertionError("An unsupported CertificateVerify signature scheme was used: "
        + selectedScheme.getSchemeName()
        + " "
        + selectedScheme.getHexValue()
        + " (hash="
        + selectedHashAlgorithm
        + ").");
  }

  /**
   * Checks whether the ServerHello key_share uses the expected supported-group profile.
   *
   * @param supportedGroup expected supported-group profile token
   */
  @Dann("verwendet die Server-Key-Share das unterstützte-Gruppen-Profil {string}")
  @Then("the supported-group profile {string} is used in the server key share")
  public void checkIfTheServerKeyShareUsesSupportedGroupProfile(String supportedGroup) {
    requireTlsLogs();

    if (supportedGroup == null || supportedGroup.isBlank()) {
      throw new AssertionError("The Supported group value is empty or null.");
    }

    var expectedGroup = TlsSupportedGroup.fromDisplayName(supportedGroup);
    if (expectedGroup == TlsSupportedGroup.UNKNOWN || expectedGroup == TlsSupportedGroup.UNSUPPORTED_MIX) {
      throw new AssertionError("The Supported Group value is unknown or not singular.");
    }

    var serverHelloExtensions = TlsLogParser.extractHelloExtensions(tlsLogs, TlsEndpointRole.SERVER);
    var keyShare = TlsLogParser.findExtensionData(serverHelloExtensions, TlsLogParser.TLS_EXTENSION_KEY_SHARE);
    if (keyShare == null || keyShare.length < 2) {
      throw new AssertionError("The ServerHello key_share extension could not be found. " + extractAlertSummary());
    }

    var actualGroup = TlsSupportedGroup.fromValue(TlsLogParser.u16(keyShare, 0));
    Assertions.assertThat(actualGroup.getValue())
        .withFailMessage(
            "The ServerHello key_share used %s but expected %s for supported-group profile %s.",
            actualGroup.getDisplayName(),
            expectedGroup.getDisplayName(),
            supportedGroup)
        .isEqualTo(expectedGroup.getValue());
  }

  /**
   * Checks whether the server key exchange uses one of the supported curves.
   */
  @Dann("verwendet der Server-Schlüsselaustausch eine der unterstützten Kurven")
  @Then("the server key exchange uses one of the supported curves")
  public void checkIfTheServerKeyExchangeUsesOneOfTheSupportedCurves() {
    requireTlsLogs();
    var matcher = TLS_SERVER_KEY_EXCHANGE_NAMED_CURVE_PATTERN.matcher(tlsLogs);

    if (!matcher.find()) {
      throw new AssertionError(
          "The curve used for the Server Key exchange could not be found. " + extractAlertSummary());
    }

    var namedCurve = Integer.parseInt(matcher.group(1), 16);
    var selectedCurve = TlsSupportedGroup.fromValue(namedCurve);
    if (selectedCurve.getTls12Policy() == TlsSupportedGroup.Tls12Policy.MANDATORY
        || selectedCurve.getTls12Policy() == TlsSupportedGroup.Tls12Policy.OPTIONAL) {
      log.info("The {} curve was used for the Server Key exchange.", selectedCurve.getDisplayName());
      return;
    }

    throw new AssertionError("An unsupported curve with value " + namedCurve
        + " (" + selectedCurve.getDisplayName() + ") was used for the Server Key exchange.");
  }

  /**
   * Checks that the TLS 1.2 ServerKeyExchange selected exactly the given supported-group profile for the ephemeral
   * ECDHE key. Counterpart to {@link #checkIfTheServerKeyShareUsesSupportedGroupProfile(String)} (TLS 1.3 key_share).
   *
   * @param supportedGroup singular supported-group profile (e.g. {@code p384})
   */
  @Dann("verwendet der Server-Schlüsselaustausch das unterstützte-Gruppen-Profil {string}")
  @Then("the supported-group profile {string} is used in the server key exchange")
  public void checkIfTheServerKeyExchangeUsesSupportedGroupProfile(String supportedGroup) {
    requireTlsLogs();

    if (supportedGroup == null || supportedGroup.isBlank()) {
      throw new AssertionError("The Supported group value is empty or null.");
    }

    var expectedGroup = TlsSupportedGroup.fromDisplayName(supportedGroup);
    if (expectedGroup == TlsSupportedGroup.UNKNOWN
        || expectedGroup == TlsSupportedGroup.UNSUPPORTED_MIX
        || expectedGroup == TlsSupportedGroup.MANDATORY_MIX) {
      throw new AssertionError("The Supported Group value is unknown or not singular.");
    }

    var matcher = TLS_SERVER_KEY_EXCHANGE_NAMED_CURVE_PATTERN.matcher(tlsLogs);
    if (!matcher.find()) {
      throw new AssertionError(
          "The curve used for the Server Key exchange could not be found. " + extractAlertSummary());
    }

    var actualGroup = TlsSupportedGroup.fromValue(Integer.parseInt(matcher.group(1), 16));
    Assertions.assertThat(actualGroup.getValue())
        .withFailMessage(
            "The ServerKeyExchange used %s but expected %s for supported-group profile %s.",
            actualGroup.getDisplayName(),
            expectedGroup.getDisplayName(),
            supportedGroup)
        .isEqualTo(expectedGroup.getValue());
  }

  /**
   * Configures and runs the TLS test tool for hash functions < SHA-256.
   *
   * @param host          Host to be tested
   * @param hashFunctions Hash algorithms that must not be supported
   */
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} mit den folgenden nicht unterstützten Hashfunktionen:")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} has been set using the following unsupported hash functions:")
  public void setTls12TlsTestToolConfigForInvalidHash(String host, DataTable hashFunctions) {
    checkHost(host);
    if (hashFunctions == null) {
      throw new AssertionError("The hash functions table is null.");
    }

    // Check if all supported TLS 1.2 hash algorithms are present.
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
    lastOfferedTlsHashAlgorithms = mapNamesToHashAlgorithms(hashFunctionsHashSet);

    runTls12ClientScenario(
        host,
        TlsTestToolConfigBuilder.buildTls12ValidCipherSuites(),
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
  @Gegebensei("eine TLS-1.2-TlsTestTool-Konfiguration für den Host {tigerResolvedString} mit den folgenden unterstützten Hashfunktionen:")
  @Given("the TLS 1.2 TlsTestTool configuration data for the host {tigerResolvedString} has been set with the following supported hash functions:")
  public void setTls12TlsTestToolConfigForValidHash(String host, DataTable hashFunctions) {
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
    lastOfferedTlsHashAlgorithms = mapNamesToHashAlgorithms(hashFunctionsHashSet);

    runTls12ClientScenario(
        host,
        TlsTestToolConfigBuilder.buildTls12ValidCipherSuites(),
        SUPPORTED_SIGNATURE_HASH_ALGOS,
        VALID_SUPPORTED_GROUPS,
        TlsHashAlgorithm.supportedByPolicy(),
        false);

  }

  /**
   * Configures and runs the TLS test tool for supported hash algorithms.
   *
   * @param host          Host to be tested
   * @param hashFunctions Hash algorithms that are supported
   */
  @Gegebensei("eine TLS-1.3-TlsTestTool-Konfiguration für den Host {tigerResolvedString} mit den folgenden unterstützten Hashfunktionen:")
  @Given("the TLS 1.3 TlsTestTool configuration data for the host {tigerResolvedString} has been set with the following supported hash functions:")
  public void setTls13TlsTestToolConfigForValidHash(String host, DataTable hashFunctions) {
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
    lastOfferedTlsHashAlgorithms = mapNamesToHashAlgorithms(hashFunctionsHashSet);
    var supportedHashes = mapNamesToHashAlgorithms(hashFunctionsHashSet);

    runTls13ClientScenario(
        host,
        TlsTestToolConfigBuilder.buildTls13ValidCipherSuites(),
        TlsSignatureSchemes.recommendedSchemeNamesForHashes(supportedHashes),
        TlsSignatureSchemes.recommendedSchemeHexValuesForHashes(supportedHashes),
        TlsSupportedGroup.tls13SupportedGroups());

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
      case ERFOLGREICH ->
          checkForMessageInTlsLogs(
              "Handshake successful", "SSL_accept:SSLv3/TLS write finished");
      case NICHT_ERFOLGREICH ->
          checkForMessageInTlsLogs(
              "TLS handshake failed", "Handshake aborted", "SSL3 alert read:fatal:");
      default -> throw new AssertionError("Unsupported TLS handshake expectation: " + expectation);
    }
  }

  /**
   * Checks whether the TLS test tool established the TCP/IP connection before the TLS handshake.
   */
  @Dann("die TCP-IP-Verbindung wurde hergestellt")
  @Then("the TCP-IP connection is established")
  public void checkTcpIpConnectionIsEstablished() {
    requireTlsLogs();

    Assertions
        .assertThat(TCP_IP_CONNECTION_ESTABLISHED_PATTERN.matcher(tlsLogs).find())
        .withFailMessage(
            "The TCP/IP connection was not established. %s",
            TlsLogParser.extractTcpIpConnectionSummary(tlsLogs))
        .isTrue();
  }

  /**
   * Checks whether the ClientHello only offers the supported curves.
   */
  @Dann("das ClientHello bietet nur unterstützte Kurven an")
  @Then("the client hello only offers the supported curves")
  public void checkClientHelloForSupportedCurves() {
    var allowedSet = new HashSet<>(TlsSupportedGroup.allowedGroups());
    assertOnlyAllowedValues(
        TlsLogParser.extractSupportedGroupsHex(tlsLogs),
        allowedSet::contains,
        "No supported_groups extension entries found in ClientHello logs.",
        "The following un-supported Supported Groups were advertised by the Client. %s");
  }

  /**
   * Checks whether the ClientHello key_share only uses supported curves.
   */
  @Dann("verwendet die Client-Key-Share nur unterstützte Kurven")
  @Then("only the supported curves are used in the client key share")
  public void checkClientKeyShareForSupportedCurvesOnly() {
    requireTlsLogs();
    var allowedSet = new HashSet<>(TlsSupportedGroup.allowedGroups());
    assertOnlyAllowedValues(
        TlsLogParser.extractClientKeyShareGroups(tlsLogs),
        allowedSet::contains,
        "No key_share extension entries found in ClientHello logs.",
        "The following unsupported Supported Groups were used in ClientHello.key_share. %s");
  }

  /**
   * Checks whether the ClientHello only offers supported TLS-1.3 signature schemes.
   */
  @Dann("das ClientHello bietet nur unterstützte Signature-Schemes an")
  @Then("the client hello only offers the supported signature schemes")
  public void checkClientHelloForSupportedTls13SignatureSchemes() {
    requireTlsLogs();
    assertOnlyAllowedValues(
        TlsLogParser.extractTls13SignatureSchemes(tlsLogs),
        scheme -> scheme.getTls13Policy() == TlsSignatureSchemes.Tls13Policy.RECOMMENDED,
        "No TLS 1.3 signature schemes found in ClientHello logs.",
        "The following unsupported TLS 1.3 Signature Schemes were advertised by the Client. %s");
  }

  /**
   * Checks whether the ClientHello does not offer any unsupported RSA TLS 1.3 signature schemes.
   */
  @Dann("das ClientHello bietet keine nicht unterstützten RSA-TLS-1.3-Signature-Schemes an")
  @Then("the client hello does not offer any unsupported RSA TLS 1.3 signature schemes")
  public void checkClientHelloForNoUnsupportedRsaTls13SignatureSchemes() {
    requireTlsLogs();
    var unsupportedSet = new HashSet<>(TlsSignatureSchemes.unsupportedRsaSchemes());
    assertOnlyAllowedValues(
        TlsLogParser.extractTls13SignatureSchemes(tlsLogs),
        scheme -> !unsupportedSet.contains(scheme),
        "No TLS 1.3 RSA signature schemes found in ClientHello logs.",
        "The following unsupported TLS RSA 1.3 Signature Schemes were advertised by the Client. %s");
  }

  /**
   * Checks whether the ClientHello does not offer any unsupported TLS 1.3 signature schemes.
   */
  @Dann("das ClientHello bietet keine nicht unterstützten TLS-1.3-Signature-Schemes an")
  @Then("the client hello does not offer any unsupported TLS 1.3 signature schemes")
  public void checkClientHelloForNoUnsupportedTls13SignatureSchemes() {
    requireTlsLogs();
    var unsupportedSet = new HashSet<>(TlsSignatureSchemes.unsupportedSchemes());
    assertOnlyAllowedValues(
        TlsLogParser.extractTls13SignatureSchemes(tlsLogs),
        scheme -> !unsupportedSet.contains(scheme),
        "No TLS 1.3 signature schemes found in ClientHello logs.",
        "The following unsupported TLS 1.3 Signature Schemes were advertised by the Client. %s");
  }

  /**
   * Checks whether the ClientHello does not offer any unsupported signature algorithms.
   */
  @Dann("das ClientHello bietet keine nicht unterstützten Signaturalgorithmen an")
  @Then("the client hello does not offer any unsupported signature algorithms")
  public void checkClientHelloForNoUnsupportedSignatureAlgorithms() {
    Set<TlsSignatureAlgorithm> unsupportedSet = new HashSet<>(TlsSignatureAlgorithm.getUnsupportedSignatureAlgorithms());
    assertOnlyAllowedValues(
        TlsLogParser.extractSignatureAlgorithmsHex(tlsLogs),
        signatureAlgorithm -> !unsupportedSet.contains(signatureAlgorithm),
        "No signature_algorithms entries found in ClientHello logs.",
        "The following unsupported Signature Algorithms were advertised by the Client. %s");
  }

  /**
   * Checks whether the TLS test tool logs contain the transmitted Certificate message.
   */
  @Dann("wurde die TLS-Certificate-Nachricht übertragen")
  @Then("the TLS Certificate was transmitted")
  public void checkCertificateWasTransmitted() {
    requireTlsLogs();
    var expectedMessage = "Certificate message transmitted.";
    Assertions
        .assertThat(tlsLogs.contains(expectedMessage))
        .withFailMessage(
            "The TLS Certificate message was not transmitted. This failure is not caused by the test scenario assertion; "
                + "the TLS handshake broke earlier for another reason. %s",
            extractAlertSummary())
        .isTrue();
  }

  /**
   * Checks whether the handshake renegotiation is initiated.
   */
  @Dann("wurde die TLS-Handshake-Renegotiation gestartet")
  @Then("the TLS handshake renegotiation is triggered")
  public void checkIfTlsRenegotiationIsTriggered() {
    checkForMessageInTlsLogs("Performing renegotiation");
  }

  /**
   * Checks whether the handshake renegotiation was successful.
   */
  @Dann("war die TLS-Handshake-Renegotiation erfolgreich")
  @Then("the TLS handshake renegotiation is successful")
  public void checkIfTlsRenegotiationIsSuccessful() {
    checkForMessageInTlsLogs("<= handshake");

    // If a renegotiation is successful, then the "<= renegotiate" message is logged
    checkForMessageInTlsLogs("<= renegotiate");
  }

  /**
   * Checks whether the handshake renegotiation was explicitly refused with the expected TLS alert.
   *
   * @param descriptionId expected TLS alert description in hexadecimal form without {@code 0x} prefix
   */
  @Dann("die TLS-Handshake-Renegotiation wurde mit einer Alert-Nachricht mit Description-ID {string} abgelehnt")
  @Then("the TLS handshake renegotiation is rejected with an alert message having description id {string}")
  public void checkIfTlsRenegotiationIsRejectedWithAlertDescription(String descriptionId) {
    requireTlsLogs();

    Assertions
        .assertThat(TLS_RENEGOTIATION_PHASE_PATTERN.matcher(tlsLogs).find())
        .withFailMessage("No TLS renegotiation phase was found in the TLS logs.")
        .isTrue();

    var renegotiationPhase = extractRelevantLogPhase();

    Assertions
        .assertThat(renegotiationPhase)
        .withFailMessage(
            "Alert.description=%s not found in renegotiation phase. %s",
            descriptionId,
            extractAlertSummary())
        .contains("Alert.description=" + descriptionId);

    Assertions
        .assertThat(renegotiationPhase)
        .withFailMessage(
            "TLS renegotiation failure marker not found in renegotiation phase. %s",
            extractAlertSummary())
        .contains("TLS handshake failed");

    Assertions
        .assertThat(TlsLogParser.hasFinishedAfterRenegotiation(tlsLogs))
        .withFailMessage("Renegotiation completed successfully although rejection was expected.")
        .isFalse();
  }

  /**
   * Checks whether TLS renegotiation is handled in an RFC 5746 compliant way.
   *
   * <p>A peer is compliant if it either completes secure renegotiation successfully or refuses
   * renegotiation with a warning {@code no_renegotiation} alert (level id {@code 01}, description id {@code 64}) or a fatal
   * {@code handshake_failure} alert (level id {@code 02}, description id {@code 28}).</p>
   */
  @Dann("war die TLS-Handshake-Renegotiation RFC-5746-konform erfolgreich oder wurde mit no_renegotiation oder handshake_failure abgelehnt")
  @Then("the TLS handshake renegotiation is RFC 5746 compliant by succeeding or being rejected with no_renegotiation or handshake_failure")
  public void checkIfTlsRenegotiationIsRfc5746Compliant() {
    requireTlsLogs();

    if (TlsLogParser.hasFinishedAfterRenegotiation(tlsLogs)) {
      TlsLogParser.assertSecureRenegotiationBinding(tlsLogs);
      return;
    }

    var renegotiationPhase = extractRelevantLogPhase();

    if (renegotiationPhase.isBlank()) {
      throw new AssertionError("The TLS renegotiation log is empty or null.");
    }

    var refusedWithNoRenegotiationAlert =
        TlsLogParser.hasAlertWithLevelAndDescription(renegotiationPhase, "01", "64");
    var refusedWithHandshakeFailureAlert =
        TlsLogParser.hasAlertWithLevelAndDescription(renegotiationPhase, "02", "28");

    Assertions
        .assertThat(refusedWithNoRenegotiationAlert || refusedWithHandshakeFailureAlert)
        .withFailMessage(
            "Renegotiation neither completed successfully nor was it rejected with warning Alert.description=64 (no_renegotiation) "
                + "or fatal Alert.description=28 (handshake_failure). %s",
            extractAlertSummary())
        .isTrue();
  }

  /**
   * Checks whether the server initiated renegotiation was successful.
   */
  @Dann("war die vom TLS-Server initiierte Renegotiation erfolgreich")
  @Then("the TLS Server initiated renegotiation is successful")
  public void checkIfTlsServerInitiatedRenegotiationIsSuccessful() {
    Assertions
        .assertThat(TlsLogParser.hasFinishedAfterRenegotiation(tlsLogs))
        .withFailMessage("Server-initiated renegotiation was not completed successfully.")
        .isTrue();
  }

  /**
   * Checks whether the specified message is present in the TLS logs.
   *
   * @param message          message to search for in TLS logs
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
   * Extracts a compact alert summary from the current TLS tool logs.
   *
   * @return compact summary with alert details and relevant handshake metadata
   */
  private String extractAlertSummary() {
    return TlsLogParser.extractAlertSummary(tlsLogs);
  }

  /**
   * Extracts the most relevant handshake phase from the current TLS tool logs.
   *
   * @return TLS log excerpt containing the current or failing handshake phase
   */
  private String extractRelevantLogPhase() {
    return TlsLogParser.extractRelevantLogPhase(tlsLogs);
  }

  /**
   * Build a short summary that explains which TLS1.2 hash algorithms were offered and whether the server selected a concrete hash algorithm
   * in the observed logs.
   *
   * @return hash negotiation summary
   */
  private String buildHashNegotiationSummary() {
    if (lastOfferedTlsHashAlgorithms.isEmpty()) {
      return "Hash negotiation: no explicit TLS1.2 hash offer context captured.";
    }
    var selected = extractSelectedTls12HashAlgorithm();
    return (selected == null)
        ? ("Hash negotiation: offered="
           + lastOfferedTlsHashAlgorithms
           + ", selected=n/a (handshake likely aborted before ServerKeyExchange hash selection).")
        : ("Hash negotiation: offered="
           + lastOfferedTlsHashAlgorithms
           + ", selected="
           + selected
           + ", selected_offered="
           + lastOfferedTlsHashAlgorithms.contains(selected)
           + ".");
  }

  /**
   * Extract the hash algorithm selected by the server in TLS 1.2 ServerKeyExchange logs.
   *
   * @return selected hash algorithm, or {@code null} if not present in the logs
   */
  private TlsHashAlgorithm extractSelectedTls12HashAlgorithm() {
    return TlsLogParser.extractSelectedTls12HashAlgorithm(tlsLogs);
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
   * @param host                  host to be tested
   * @param tlsCipherSuites       tls-test-tool formatted cipher-suite configuration line
   * @param tlsSignatureHashAlgos tls-test-tool formatted signature hash algorithms extension
   * @param tlsSupportedGroups    tls-test-tool formatted supported-groups extension
   * @param offeredHashAlgorithms hash algorithms to log for traceability
   * @param enableRenegotiation   whether to append renegotiation trigger to the config
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

    var tlsTestToolConfigBuffer = TlsTestToolConfigBuilder.buildTls12ClientConfig(
        tlsSignatureHashAlgos, tlsSupportedGroups, tlsCipherSuites, host);
    if (enableRenegotiation) {
      // Add configuration to initiate a TLS handshake renegotiation.
      tlsTestToolConfigBuffer += "manipulateRenegotiate=\n";
    }
    runTlsTestToolClient(tlsTestToolConfigBuffer);
  }

  /**
   * Creates and runs a TLS 1.3 client scenario.
   *
   * @param host                     host to be tested
   * @param tlsCipherSuites          tls-test-tool formatted cipher-suite configuration line
   * @param supportedSchemeNames     TLS-1.3 signature-scheme names to log for traceability
   * @param supportedSchemeHexValues tls-test-tool formatted TLS-1.3 signature-schemes value
   * @param tlsSupportedGroups       TLS-1.3 supported_groups value
   */
  private void runTls13ClientScenario(
      String host,
      String tlsCipherSuites,
      Iterable<?> supportedSchemeNames,
      String supportedSchemeHexValues,
      String tlsSupportedGroups) {

    checkHost(host);
    Objects.requireNonNull(supportedSchemeHexValues, "supportedSchemeHexValues must not be null");
    Objects.requireNonNull(tlsSupportedGroups, "tlsSupportedGroups must not be null");

    log.info("ClientHello only offers the following TLS 1.3 signature schemes:");
    for (var supportedScheme : supportedSchemeNames) {
      log.info("{}", supportedScheme);
    }

    log.info("ClientHello only offers the following TLS 1.3 supported groups: {}", tlsSupportedGroups);

    var tlsTestToolConfigBuffer = TlsTestToolConfigBuilder.buildTls13ClientConfig(
        supportedSchemeHexValues, tlsSupportedGroups, tlsCipherSuites, host);

    runTlsTestToolClient(tlsTestToolConfigBuffer);
  }

  /**
   * Checks whether the server certificate uses TR-02102-2-recommended key lengths and domain parameters.
   */
  @Dann("verwendet der Server ein Zertifikat mit Schlüssellängen und Domainparameter nach [TR-02102-2]")
  @Then("the server uses a certificate with key lengths and domain parameters according to [TR-02102-2]")
  public void checkServerCertificateUsesRecommendedKeyLengthsAndDomainParameters() {
    requireTlsLogs();
    TlsCertificateLogInspector.assertRecommendedKeyLengthsAndDomainParameters(tlsLogs);
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

    var tlsTestToolConfigBuffer = TlsTestToolConfigBuilder.buildTls12ServerBaseConfig(tlsCipherSuites);
    runTlsTestToolServer(tlsTestToolConfigBuffer);
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
   * Executes the TLS test tool in client mode via the TLS test tool service.
   *
   * <p>The previous service-managed process is stopped first and the retained remote logs are cleared
   * before the generated config file and CA certificate are uploaded. The new client run is then started through the service endpoint.</p>
   *
   * @param tlsTestToolConfigBuffer TLS test tool configuration content
   */
  void runTlsTestToolClient(String tlsTestToolConfigBuffer) {
    tlsTestToolRunContext.runClient(tlsTestToolConfigBuffer);
    tlsLogs = tlsTestToolRunContext.getLogs();
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
   * before the generated config file, certificate, and private key are uploaded. The new server instance is then started through the
   * service endpoint.</p>
   *
   * @param tlsTestToolConfigBuffer TLS test tool server configuration content
   * @param serverCertificate       certificate descriptor to upload; defaults to the standard certificate when {@code null}
   */
  void runTlsTestToolServer(String tlsTestToolConfigBuffer, TlsServerCertificates serverCertificate) {
    tlsTestToolRunContext.runServer(tlsTestToolConfigBuffer, serverCertificate);
    tlsLogs = tlsTestToolRunContext.getLogs();
  }

  /**
   * Retrieves logs for a previously started TLS test tool run and stores them in the report.
   */
  @Dann("werden die Tls-Test-Tool-Protokolle abgerufen")
  @Then("the TLS test tool logs are retrieved")
  public void getTheTlsTestToolLogs() {
    tlsLogs = tlsTestToolRunContext.retrieveLogs();
  }

  /**
   * Checks whether the server sends a renegotiation_info-Erweiterung in the ServerHello record.
   */
  @Dann("das ServerHello enthält die Erweiterung renegotiation_info")
  @Then("the renegotiation_info-Erweiterung is present in the ServerHello")
  public void checkIfRenegotiationInfoExtensionIsPresentInServerHello() {
    Assertions
        .assertThat(helloHasEmptyRenegotiationInfo(TlsEndpointRole.SERVER))
        .withFailMessage("The renegotiation_info could not be found in the TLS ServerHello.")
        .isTrue();
  }

  /**
   * Checks whether the server answered the OCSP status_request with a TLS stapled OCSP response.
   */
  @Dann("liefert der Server eine OCSP-Stapling-Antwort")
  @Then("the server provides an OCSP stapling response")
  public void checkIfServerProvidesOcspStaplingResponse() {
    requireTlsLogs();

    if (OPENSSL_SERVER_STATUS_REQUEST_PATTERN.matcher(tlsLogs).find()) {
      Assertions
          .assertThat(OPENSSL_CERTIFICATE_STATUS_PATTERN.matcher(tlsLogs).find())
          .withFailMessage(
              "The OpenSSL TLS trace does not contain a sent stapled OCSP CertificateStatus message.")
          .isTrue();
      return;
    }

    var serverStatusRequest = TlsLogParser.findExtensionData(
        TlsLogParser.extractHelloExtensions(tlsLogs, TlsEndpointRole.SERVER, false),
        0x0005);

    Assertions
        .assertThat(serverStatusRequest)
        .withFailMessage("The TLS ServerHello did not acknowledge the OCSP status_request extension.")
        .isNotNull()
        .isEmpty();

    Assertions
        .assertThat(tlsLogs)
        .withFailMessage("The TLS handshake log does not contain a stapled OCSP CertificateStatus message.")
        .contains("CertificateStatus");
  }

  /** Checks whether the TLS client requested an OCSP stapling response in its ClientHello. */
  @Dann("enthält das ClientHello die OCSP-status_request-Erweiterung")
  @Then("the ClientHello contains the OCSP status_request extension")
  public void checkIfClientRequestsOcspStapling() {
    requireTlsLogs();
    if (OPENSSL_CLIENT_STATUS_REQUEST_PATTERN.matcher(tlsLogs).find()) {
      return;
    }
    var statusRequest = TlsLogParser.findExtensionData(
        TlsLogParser.extractHelloExtensions(tlsLogs, TlsEndpointRole.CLIENT, false),
        0x0005);
    Assertions.assertThat(statusRequest)
        .withFailMessage("The TLS ClientHello does not contain an OCSP status_request extension.")
        .isNotNull();
  }

  /**
   * Checks whether the renegotiation_info-Erweiterung is present.
   *
   * @param role TLS endpoint role
   * @return true if renegotiation_info (0xff01) is present with length == 0
   */
  private boolean helloHasEmptyRenegotiationInfo(TlsEndpointRole role) {
    requireTlsLogs();
    var renegotiationInfo = TlsLogParser.findExtensionData(
        TlsLogParser.extractHelloExtensions(tlsLogs, role, false),
        TlsLogParser.TLS_EXTENSION_RENEGOTIATION_INFO);
    return renegotiationInfo != null && renegotiationInfo.length == 1 && renegotiationInfo[0] == 0x00;
  }

  /**
   * Checks whether the ClientHello TLS version is 1.2.
   */
  @Dann("die ClientHello-TLS-Version ist 1.2")
  @Then("the ClientHello TLS version is 1.2")
  public void checkIfClientHelloTlsVersionIs1_2() {
    requireTlsLogs();

    Assertions
        .assertThat(tlsLogs.contains("ClientHello.client_version=03 03"))
        .withFailMessage("TLS 1.2 is not supported by the client.")
        .isTrue();
  }

  /**
   * Checks whether the client hello only contains cipher suites specified in TR-02102-2, Abschnitt 3.3.1 Tabelle 1. TLS 1.3 cipher suites
   * are also accepted and do not cause this test step to fail if they are present.
   */
  @Dann("das ClientHello enthält nur Cipher-Suiten aus TR-02102-2, Abschnitt 3.3.1 Tabelle 1")
  @Then("the ClientHello record contains only cipher suites from TR-02102-2, section 3.3.1 table 1")
  public void onlySupportedCipherSuitesArePresent() {
    requireTlsLogs();

    var offered = TlsLogParser.extractClientHelloCipherSuitesAsPairs(tlsLogs);
    Assertions
        .assertThat(offered.isEmpty())
        .withFailMessage("No ClientHello.cipher_suites found in log.")
        .isFalse();

    // HashSet for fast membership checks
    var supportedCipherSuites = TlsCipherSuite.supportedTls12CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.toCollection(HashSet::new));
    supportedCipherSuites.addAll(TlsCipherSuite.supportedTls13CipherSuites().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.toSet()));

    // Find any non-supported cipher suites
    var notSupported = offered.stream()
        .filter(cs -> !supportedCipherSuites.contains(cs))
        .toList();

    Assertions
        .assertThat(notSupported.isEmpty())
        .withFailMessage("The following un-supported Cipher Suites were advertised by the Client. %s", notSupported)
        .isTrue();
  }

  /**
   * Checks whether the client hello only contains cipher suites from  A_28868 and none specified in TR-02102-2, Abschnitt 3.3.1 Tabelle 1.
   * TLS 1.3 cipher suites are also accepted and do not cause this test step to fail if they are present.
   */
  @Dann("das ClientHello enthält keine optionalen Cipher-Suiten aus TR-02102-2, Abschnitt 3.3.1 Tabelle 1")
  @Then("the ClientHello record contains no optional cipher suites from TR-02102-2, section 3.3.1 table 1")
  public void onlySupportedCipherSuitesArePresentWithoutOptional() {
    requireTlsLogs();

    var offered = TlsLogParser.extractClientHelloCipherSuitesAsPairs(tlsLogs);
    Assertions
        .assertThat(offered.isEmpty())
        .withFailMessage("No ClientHello.cipher_suites found in log.")
        .isFalse();

    // HashSet for fast membership checks
    var supportedCipherSuites = TlsCipherSuite.supportedTls12CipherSuitesWithoutOptional().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.toCollection(HashSet::new));
    supportedCipherSuites.addAll(TlsCipherSuite.supportedTls13CipherSuitesWithoutOptional().stream()
        .map(TlsCipherSuite::getTlsTestToolCipherSuiteValue)
        .collect(Collectors.toSet()));

    // Find any non-supported cipher suites
    var notSupported = offered.stream()
        .filter(cs -> !supportedCipherSuites.contains(cs))
        .toList();

    Assertions
        .assertThat(notSupported.isEmpty())
        .withFailMessage("The following un-supported Cipher Suites were advertised by the Client. %s", notSupported)
        .isTrue();
  }

  /**
   * Checks whether the client hello only contains the TLS_EMPTY_RENEGOTIATION_INFO_SCSV cipher suite or an empty renegotiation_info
   * extension.
   */
  @Dann("das ClientHello enthält TLS_EMPTY_RENEGOTIATION_INFO_SCSV oder eine leere renegotiation_info-Erweiterung")
  @Then("the ClientHello record contains TLS_EMPTY_RENEGOTIATION_INFO_SCSV or an empty renegotiation_info-Erweiterung")
  public void scsvCipherSuiteOrRenegotiationInfoArePresent() {
    requireTlsLogs();

    // RFC 5746 is satisfied if either the empty renegotiation_info-Erweiterung is present
    // or TLS_EMPTY_RENEGOTIATION_INFO_SCSV is offered in the ClientHello.
    if (helloHasEmptyRenegotiationInfo(TlsEndpointRole.CLIENT)) {
      return;
    }

    var offered = TlsLogParser.extractClientHelloCipherSuitesAsPairs(tlsLogs);
    Assertions
        .assertThat(offered.isEmpty())
        .withFailMessage(
            "Neither an empty renegotiation_info-Erweiterung nor ClientHello.cipher_suites were found in log.")
        .isFalse();

    // Check if the EMPTY_RENEGOTIATION_INFO_SCSV cipher suite is present
    var hasEmptyRenegotiationInfoScsv =
        offered.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .anyMatch(s ->
                s.equalsIgnoreCase(TlsCipherSuite.EMPTY_RENEGOTIATION_INFO_SCSV.getTlsTestToolCipherSuiteValue())
            );

    Assertions
        .assertThat(hasEmptyRenegotiationInfoScsv)
        .withFailMessage(
            "The EMPTY_RENEGOTIATION_INFO_SCSV or the correct renegotiation_info could not be found in the TLS Client Hello.")
        .isTrue();
  }

}
