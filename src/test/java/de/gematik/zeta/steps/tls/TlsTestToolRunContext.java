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

import de.gematik.zeta.model.tls.TlsServerCertificates;
import de.gematik.zeta.services.TlsTestToolServiceFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;

/**
 * Scenario-local lifecycle context for service-backed TLS test tool runs.
 */
@Slf4j
public class TlsTestToolRunContext {

  /**
   * Cached TLS test tool logs for the current scenario.
   */
  private String tlsLogs = "";

  /**
   * Tracks whether a service-backed TLS test tool run was started in the current scenario.
   */
  private boolean tlsTestToolStarted;

  /**
   * Executes the TLS test tool in client mode via the TLS test tool service.
   *
   * <p>The previous service-managed process is stopped first and the retained remote logs are cleared before the generated config file and CA
   * certificate are uploaded. The new client run is then started through the service endpoint.</p>
   *
   * @param tlsTestToolConfigBuffer TLS test tool configuration content
   */
  public void runClient(String tlsTestToolConfigBuffer) {
    var configFileLocation = Path.of(createTempConfigFile(tlsTestToolConfigBuffer));
    var caCertificateFile = resolveCaCertificatePath();
    log.debug("TLS Test Tool service configuration file path: {}", configFileLocation);
    log.debug("TLS Test Tool service CA certificate file path: {}", caCertificateFile);

    clear();
    var tlsTestToolService = TlsTestToolServiceFactory.getInstance();
    tlsTestToolService.stop();
    tlsTestToolService.clearLogs();
    tlsTestToolService.updateConfig(configFileLocation);
    tlsTestToolService.updateCaCertificate(caCertificateFile);
    tlsTestToolService.startAsTlsClient();
    tlsTestToolStarted = true;
  }

  /**
   * Starts the TLS test tool server through the TLS test tool service using the default certificate.
   *
   * @param tlsTestToolConfigBuffer TLS test tool server configuration content
   */
  public void runServer(String tlsTestToolConfigBuffer) {
    runServer(tlsTestToolConfigBuffer, TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE);
  }

  /**
   * Executes the TLS test tool in server mode via the TLS test tool service.
   *
   * <p>The previous service-managed process is stopped first and the retained remote logs are cleared before the generated config file,
   * certificate, and private key are uploaded. The new server instance is then started through the service endpoint.</p>
   *
   * @param tlsTestToolConfigBuffer TLS test tool server configuration content
   * @param serverCertificate       certificate descriptor to upload; defaults to the standard certificate when {@code null}
   */
  public void runServer(String tlsTestToolConfigBuffer, TlsServerCertificates serverCertificate) {
    var effectiveServerCertificate =
        serverCertificate == null ? TlsServerCertificates.ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE : serverCertificate;
    var configFileLocation = Path.of(createTempConfigFile(tlsTestToolConfigBuffer));
    var certificateFile = TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(effectiveServerCertificate.getRelativePath());
    var privateKeyFile = TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(
        TlsServerCertificates.getPrivateKeyForCertificate(effectiveServerCertificate).getRelativePath());
    var caCertificateFile = resolveCaCertificatePath();

    log.debug("TLS Test Tool service configuration file path: {}", configFileLocation);
    log.debug("TLS Test Tool service certificate file path: {}", certificateFile);
    log.debug("TLS Test Tool service private key file path: {}", privateKeyFile);
    log.debug("TLS Test Tool service CA certificate file path: {}", caCertificateFile);

    clear();
    var tlsTestToolService = TlsTestToolServiceFactory.getInstance();
    tlsTestToolService.stop();
    tlsTestToolService.clearLogs();
    tlsTestToolService.updateConfig(configFileLocation);
    tlsTestToolService.updateCertificate(certificateFile, privateKeyFile);
    tlsTestToolService.updateCaCertificate(caCertificateFile);
    tlsTestToolService.startAsTlsServer();
    tlsTestToolStarted = true;
  }

  /**
   * Retrieves logs for a previously started TLS test tool run and stores them in the Serenity report.
   *
   * @return retrieved TLS test tool logs
   */
  public String retrieveLogs() {
    if (!tlsTestToolStarted) {
      throw new AssertionError("TLS test tool has not been started.");
    }
    tlsLogs = TlsTestToolServiceFactory.getInstance().getLogs();
    saveLogsToSerenityReport();
    return tlsLogs;
  }

  /**
   * Returns the currently cached TLS test tool logs.
   *
   * @return cached TLS logs
   */
  public String getLogs() {
    return tlsLogs;
  }

  /**
   * Ensures TLS logs are available for assertions.
   */
  public void requireLogs() {
    if (tlsLogs == null || tlsLogs.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }
  }

  /**
   * Clears cached logs and service-backed run lifecycle markers before a new run starts.
   */
  private void clear() {
    tlsLogs = "";
    tlsTestToolStarted = false;
  }

  /**
   * Saves the currently cached TLS test tool logs to the Serenity report.
   */
  private void saveLogsToSerenityReport() {
    requireLogs();
    log.debug("TLS Test Tool Logs:");
    log.debug(tlsLogs);

    Serenity.recordReportData()
        .withTitle("TLS Test Tool Logs:")
        .andContents(tlsLogs);
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
   * Resolves the CA certificate path used by the TLS test tool service.
   *
   * @return resolved CA certificate file path
   */
  private static Path resolveCaCertificatePath() {
    return TlsTestToolCertificateFixtures.resolveCaCertificatePath();
  }
}
