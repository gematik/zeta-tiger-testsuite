/*-
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
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;

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
   * Supported Groups required for the TLS handshake (GS-A_4384-03 ).
   * secp256r1
   * secp384r1
   * brainpoolP256r1
   * brainpoolP384r1
   */
  private static final String SUPPORTED_GROUPS = "000a001800160019001c0018001b00170016001a0015001400130012";

  /**
   * String for storing the TLS logs.
   */
  private String tlsLogs;

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
   * Configures and runs the TLS test tool for TLS 1.1.
   *
   * @param host Host to be tested
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit nur TLS 1.1 wurde erstellt")
  @Given("the TlsTestTool configuration data for the host {tigerResolvedString} with only TLS 1.1 is created")
  public void setTlsTestToolConfigforTls1_1(String host) {
    checkHost(host);

    String basicConfiguration = "# TLS Test Tool configuration file\n"
        + "mode=client\n"
        + "tlsLibrary=mbed TLS\n"
        + "waitBeforeClose=5\n"
        + "logLevel=low\n"
        + "tlsVersion=(3,2)\n"
        + "tlsUseSni=true\n"
        + "handshakeType=normal\n";
    String port = "port=443\n";
    String tlsTestToolConfigBuffer = basicConfiguration
        + port
        + "host=" + host + "\n";

    log.info("The Client only offers a TLS 1.1 connection.");

    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.3 with unsupported RSA Signature Schemes.
   *
   * @param host Host to be tested
   * @param signatureSchemes Signature Schemes that must not be supported
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit den folgenden TLS 1.3 Signature-Schemes wurden festgelegt:")
  @Given("The TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.3 signature schemes:")
  public void setTlsTestToolConfigForUnsupportedSignatureSchemes(String host, DataTable signatureSchemes) {
    checkHost(host);

    // Check if all the supported/mandatory SignatureSchemes are present
    HashSet<String>  signatureSchemeHashSet = new HashSet<String>();
    for (List<String> row : signatureSchemes.asLists()) {
      if (row == null || row.isEmpty()) {
        continue;
      }
      String signatureScheme = row.getFirst() != null ? row.getFirst().trim() : "";
      if (signatureScheme.isEmpty()) {
        continue;
      }
      signatureSchemeHashSet.add(signatureScheme);
    }

    HashSet<String> supportedSignatureSchemes = new HashSet<String>();
    for (SignatureSchemes s : SignatureSchemes.values()) {
      supportedSignatureSchemes.add(s.toString());
    }

    if (!supportedSignatureSchemes.equals(signatureSchemeHashSet)) {
      throw new AssertionError("For this test, the signature hash algorithms cannot be changed."
          + "They must be:\n"
          + "- rsa_pkcs1_sha256\n"
          + "- rsa_pkcs1_sha384\n"
          + "- rsa_pkcs1_sha512");
    }

    log.info("The TLS 1.3 ClientHello offers the following TLS 1.3 cipher suites in accordance with TR-02102-2:");
    log.info("TLS_AES_128_GCM_SHA256");
    log.info("TLS_AES_256_GCM_SHA384");
    log.info("TLS_AES_128_CCM_SHA256");
    String tlsCipherSuites = "tlsCipherSuites=(0x13,0x01),(0x13,0x02),(0x13,0x04)\n";

    String tlsSignatureSchemes = "tlsSignatureSchemes=(0x04,0x01),(0x05,0x01),(0x06,0x01)\n";
    log.info("ClientHello only offers the following TLS 1.3 signature schemes:");
    for (String signatureScheme : signatureSchemeHashSet) {
      log.info(signatureScheme);
    }

    String basicConfiguration = "# TLS Test Tool configuration file\n"
        + "mode=client\n"
        + "tlsLibrary=OpenSSL\n"
        + "waitBeforeClose=5\n"
        + "logLevel=low\n"
        + "tlsUseSni=true\n"
        + "tlsVersion=(3,4)\n"
        + "handshakeType=normal\n";
    String port = "port=443\n";
    String tlsTestToolConfigBuffer = basicConfiguration
        + tlsCipherSuites
        + tlsSignatureSchemes
        + port
        + "host=" + host + "\n";

    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Configures and runs the TLS test tool for TLS 1.2 with unsupported RSA Signature and hash algorithms.
   *
   * @param host Host to be tested
   * @param signatureHashAlgorithms Signature and hash algorithms that must not be supported
   */
  @Gegebensei("die TlsTestTool-Konfigurationsdaten für den Host {tigerResolvedString} mit den folgenden TLS 1.2 Signatur-Hash-Algorithmen wurden festgelegt:")
  @Given("The TlsTestTool configuration data for the host {tigerResolvedString} has been set for the following TLS 1.2 signature hash algorithms:")
  public void setTlsTestToolConfigForUnsupportedSignatureHashAlgorithms(String host, DataTable signatureHashAlgorithms) {

    checkHost(host);

    // Check if all the supported/mandatory SignatureAndHashAlgorithms are present
    HashSet<String>  signatureAlgorithmHashSet = new HashSet<String>();
    for (List<String> row : signatureHashAlgorithms.asLists()) {
      if (row == null || row.isEmpty()) {
        continue;
      }
      String signatureHashAlgo = row.getFirst() != null ? row.getFirst().trim() : "";
      if (signatureHashAlgo.isEmpty()) {
        continue;
      }
      signatureAlgorithmHashSet.add(signatureHashAlgo);
    }

    HashSet<String> supportedSignatureAndHashAlgorithms = new HashSet<String>();
    for (SignatureAndHashAlgorithms s : SignatureAndHashAlgorithms.values()) {
      supportedSignatureAndHashAlgorithms.add(s.toString());

    }

    if (!supportedSignatureAndHashAlgorithms.equals(signatureAlgorithmHashSet)) {
      throw new AssertionError("For this test, the signature hash algorithms cannot be changed."
          + "They must be:\n"
          + "- RSA_MD5\n"
          + "- RSA_SHA1\n"
          + "- RSA_SHA224\n"
          + "- RSA_SHA256\n"
          + "- RSA_SHA384\n"
          + "- RSA_SHA512");
    }

    log.info("The TLS 1.2 ClientHello offers the following TLS 1.2 RSA cipher suites in accordance with TR-02102-2:\n"
        + "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256\n"
        + "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384\n"
        + "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256\n"
        + "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384\n"
        + "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256\n"
        + "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256\n"
        + "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256\n"
        + "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384\n"
        + "TLS_DHE_RSA_WITH_AES_128_CCM\n"
        + "TLS_DHE_RSA_WITH_AES_256_CCM\n"
        + "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256\n"
        + "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384\n"
        + "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256\n"
        + "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384\n"
        + "TLS_DH_RSA_WITH_AES_128_CBC_SHA256\n"
        + "TLS_DH_RSA_WITH_AES_256_CBC_SHA256\n"
        + "TLS_DH_RSA_WITH_AES_128_GCM_SHA256\n"
        + "TLS_DH_RSA_WITH_AES_256_GCM_SHA384");

    String tlsCipherSuites = "tlsCipherSuites=(0xC0,0x27),(0xC0,0x28),(0xC0,0x2F),(0xC0,0x30),(0x00,0x67),"
        + "(0x00,0x6B),(0x00,0x9E),(0x00,0x9F),(0xC0,0x9E),(0xC0,0x9F),(0xC0,0x29),(0xC0,0x2A),(0xC0,0x31),"
        + "(0xC0,0x32),(0x00,0x3F),(0x00,0x69),(0x00,0xA0),(0x00,0xA1)\n";
    log.info("ClientHello only offers the following TLS 1.2 signature hash algorithms:");
    for (String signatureHashAlgo : supportedSignatureAndHashAlgorithms) {
      log.info(signatureHashAlgo);
    }

    String basicConfiguration = "# TLS Test Tool configuration file\n"
        + "mode=client\n"
        + "tlsLibrary=mbed TLS\n"
        + "waitBeforeClose=5\n"
        + "logLevel=low\n"
        + "tlsUseSni=true\n"
        + "tlsVersion=(3,3)\n"
        + "handshakeType=normal\n";
    String manipulateClientHelloExtensions = "manipulateClientHelloExtensions="
        + "000d000e000C010102010301040105010601" + SUPPORTED_GROUPS + "\n";
    String port = "port=443\n";
    String tlsTestToolConfigBuffer = basicConfiguration
        + tlsCipherSuites
        + manipulateClientHelloExtensions
        + port
        + "host=" + host + "\n";

    runTlsTestTool(tlsTestToolConfigBuffer);
  }

  /**
   * Checks whether a TLS “alert” has been received.
   */
  @Dann("Akzeptiert der Zeta Guard Endpunkt das ClientHello nicht und sendet eine Alert Nachricht mit Description Id {string}.")
  @Then("The Zeta Guard endpoint does not accept the ClientHello and sends an alert message with the description id {string}.")
  public void zetaGuardSendsAnAlert(String descriptionId) {
    // Check the logs for the "Alert message received" and "Alert.level=02" alert messages
    Assertions
        .assertThat(tlsLogs.contains("Alert message received"))
        .withFailMessage("'Alert' message not received from endpoint.")
        .isTrue();
    Assertions
        .assertThat(tlsLogs.contains("Alert.level=02"))
        .withFailMessage("'Alert.level=02' not found in the TLS test tool logs.")
        .isTrue();
    Assertions
        .assertThat(tlsLogs.contains("Alert.description=" + descriptionId))
        .withFailMessage("Alert.description={} not found in the TLS test tool logs.", descriptionId)
        .isTrue();
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
    String tlsTestToolLocation = System.getProperty("user.dir") + TLSTESTTOOLPATH;

    // Select the correct TLS Test Tool binary
    boolean useWsl = false;
    if (isWindowsPath(tlsTestToolLocation)) {
      useWsl = true;
      log.info("WSL will be used");
    }

    if (useWsl) {
      tlsTestToolLocation += "-wsl";
    } else {
      tlsTestToolLocation += "-alpine";
    }

    // Save the TLS Test Tool configuration
    String configFileLocation = createTempConfigFile(tlsTestToolConfigBuffer);

    // Check if the TLS Test Tool exists
    if (!Files.exists(Path.of(tlsTestToolLocation))) {
      throw new AssertionError("The TLS test tool could not be found at: " + tlsTestToolLocation);
    }

    // Set the permissions so that the TLS Test tool can execute
    if (!useWsl) {
      try {
        new ProcessBuilder("chmod", "+x", tlsTestToolLocation)
            .inheritIO()
            .start()
            .waitFor();
      } catch (java.io.IOException | InterruptedException e) {
        throw new AssertionError("Error setting permissions for the TLS test tools", e);
      }
    }

    // Convert the path if running on wsl
    if (useWsl) {
      tlsTestToolLocation = toWslPath(tlsTestToolLocation);
      configFileLocation = toWslPath(configFileLocation);
    }

    log.debug("TLS Test Tool path = {}", tlsTestToolLocation);
    log.debug("TLS Test Tool configuration file path: {}", configFileLocation);

    // Clear the logs
    tlsLogs = "";
    try {
      // Execute the TLS test tool
      List<String> command = new ArrayList<>();
      if (useWsl) {
        command.add("wsl");
      }
      command.add(tlsTestToolLocation);
      command.add("--configFile=" + configFileLocation);
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);  // merge stderr into stdout
      Process process = pb.start();
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream())
      );
      // Extract the logs
      tlsLogs = reader.lines().collect(Collectors.joining("\n"));
      log.info("TLS Test Tool Logs:");
      log.info(tlsLogs);
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
   * Converts a Windows path to an equivalent WSL path.
   */
  public static String toWslPath(String winPath) {
    // Normalize separators
    String p = winPath.replace("\\", "/");

    // Extract drive letter
    if (p.length() >= 2 && p.charAt(1) == ':') {
      char drive = Character.toLowerCase(p.charAt(0));
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
}
