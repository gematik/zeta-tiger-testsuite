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

package de.gematik.zeta.model;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Helpers for certificate material used by step definitions.
 */
public final class CertificateMaterial {

  /**
   * Prevent instantiation of this utility class.
   */
  private CertificateMaterial() {
  }

  /**
   * Parses a Base64 DER or PEM encoded X.509 certificate.
   *
   * @param certificate Base64 DER or PEM encoded certificate
   * @param description human-readable certificate description for assertion messages
   * @return parsed X.509 certificate
   */
  public static X509Certificate parseCertificate(String certificate, String description) {
    if (certificate == null || certificate.isBlank()) {
      throw new AssertionError("certificate must not be blank");
    }

    var normalizedCertificate = certificate.trim()
        .replace("-----BEGIN CERTIFICATE-----", "")
        .replace("-----END CERTIFICATE-----", "")
        .replaceAll("\\s", "");

    byte[] decodedCertMaterial;

    try {
      decodedCertMaterial = Base64.getDecoder().decode(normalizedCertificate);
    } catch (IllegalArgumentException e) {
      throw new AssertionError("Failed to Base64-decode certificate.", e);
    }

    try {
      var certificateFactory = CertificateFactory.getInstance("X.509");
      return (X509Certificate) certificateFactory.generateCertificate(
          new ByteArrayInputStream(decodedCertMaterial));
    } catch (CertificateException e) {
      throw new AssertionError("Failed to parse " + description + ": " + e.getMessage(), e);
    }
  }
}
