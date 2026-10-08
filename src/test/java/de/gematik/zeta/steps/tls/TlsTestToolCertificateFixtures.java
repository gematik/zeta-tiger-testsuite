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
import java.nio.file.Path;

/**
 * Resolves TLS test tool certificate fixture paths from Tiger configuration.
 */
public final class TlsTestToolCertificateFixtures {

  private static final String TLS_TEST_TOOL_CERTIFICATE_DIRECTORY_PATH_CONFIG_KEY = "tlsTestTool.certificateDirectoryPath";
  private static final String TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY = "tlsTestTool.caCertificatePath";

  /**
   * Utility class.
   */
  private TlsTestToolCertificateFixtures() {
  }

  /**
   * Resolves a certificate or private-key path below the configured fixture directory.
   *
   * @param certificate certificate or key path relative to the fixture directory
   * @return resolved file path
   */
  public static Path resolveCertificateOrKeyPath(String certificate) {
    if (certificate == null || certificate.isBlank()) {
      throw new AssertionError("The certificate is empty or null.");
    }

    var baseDirectory = resolveCertificateDirectory();
    var certificateLocation = baseDirectory.resolve(certificate).normalize();
    if (!certificateLocation.startsWith(baseDirectory)) {
      throw new AssertionError("The certificate path is invalid.");
    }

    return certificateLocation;
  }

  /**
   * Resolves the configured TLS test tool CA certificate path.
   *
   * @return resolved CA certificate file path
   */
  public static Path resolveCaCertificatePath() {
    return resolveConfiguredPath(TLS_TEST_TOOL_CA_CERTIFICATE_PATH_CONFIG_KEY);
  }

  /**
   * Resolves the configured TLS test tool certificate fixture directory.
   *
   * @return resolved certificate fixture directory
   */
  public static Path resolveCertificateDirectory() {
    return resolveConfiguredPath(TLS_TEST_TOOL_CERTIFICATE_DIRECTORY_PATH_CONFIG_KEY);
  }

  /**
   * Resolves a configured path from Tiger configuration.
   *
   * @param tigerConfigKey full Tiger configuration key
   * @return resolved path
   */
  private static Path resolveConfiguredPath(String tigerConfigKey) {
    var configuredPath = TigerGlobalConfiguration.readStringOptional(tigerConfigKey)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .orElseThrow(() -> new AssertionError("The config key '" + tigerConfigKey + "' could not be resolved."));
    var path = Path.of(configuredPath);
    if (!path.isAbsolute()) {
      path = Path.of(System.getProperty("user.dir")).resolve(path);
    }
    return path.normalize();
  }
}
