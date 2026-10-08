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
import lombok.Getter;

/**
 * Certificate and key files shipped with the TLS test tool fixture.
 */
public enum TlsServerCertificates {
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_PRIVATE_KEY("zeta_tls_test_tool_server_ecdsa_private_key", "ecdsa/zeta-tls-test-tool-server.privkey.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CN_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_cn_certificate",
      "ecdsa/zeta-tls-test-tool-server_evil_cn.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CN_SAN_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_cn_san_certificate",
      "ecdsa/zeta-tls-test-tool-server_evil_cn_san.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_SAN_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_san_certificate",
      "ecdsa/zeta-tls-test-tool-server_evil_san.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_GOOD_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_good_certificate",
      "ecdsa/zeta-tls-test-tool-server_good.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_EXPIRED_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_expired_certificate",
      "ecdsa/zeta-tls-test-tool-server_expired.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_NOT_YET_VALID_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_not_yet_valid_certificate",
      "ecdsa/zeta-tls-test-tool-server_not_yet_valid.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_CRL_ONLY_CERTIFICATE(
      "zeta_tls_test_tool_server_ecdsa_crl_only_certificate",
      "ecdsa/zeta-tls-test-tool-server_crl_only.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_SUBCA_CHAIN_CERTIFICATE(
      "zeta_tls_test_tool_server_ecdsa_subca_chain_certificate",
      "ecdsa/zeta-tls-test-tool-server_subca_chain.pem"),
  ZETA_TLS_TEST_TOOL_SERVER_ECDSA_DIFFERENT_CA_CERTIFICATE("zeta_tls_test_tool_server_ecdsa_different_ca_certificate",
      "ecdsa/zeta-tls-test-tool-server_no_chain.pem");

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
