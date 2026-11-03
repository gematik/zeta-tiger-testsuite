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

package de.gematik.zeta.services;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SignatureException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiFunction;

/** Fixtures from the installed Helm profile or supplied files; no generated NS identity. */
final class NotificationTlsFiles {
  private NotificationTlsFiles() {}

  static String configuration(String key) {
    return TigerGlobalConfiguration.readStringOptional(key)
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .filter(v -> !v.isBlank())
        .filter(v -> !v.contains("${"))
        .orElseThrow(
            () -> new AssertionError("Missing installed Helm TLS test prerequisite: " + key));
  }

  static Path directory(String key) {
    var path = Path.of(configuration(key));
    if (!Files.isDirectory(path)) {
      throw new AssertionError("Missing TLS fixture directory configured by " + key);
    }
    return path;
  }

  /** Reads only peer material from the installed Kind PKI; NS client private keys stay in Kubernetes. */
  static Path gatewayDirectory(ZetaDeploymentModificationService service, String namespace)
      throws java.io.IOException {
    return installedDirectory(service, namespace, "gateway");
  }

  static Path rsDirectory(ZetaDeploymentModificationService service, String namespace)
      throws java.io.IOException {
    return installedDirectory(service, namespace, "rs");
  }

  private static Path installedDirectory(
      ZetaDeploymentModificationService service, String namespace, String role)
      throws java.io.IOException {
    String prefix = "testdata.notificationTls." + role;
    String source = TigerGlobalConfiguration.readStringOptional(prefix + ".materialSource")
        .orElse("files");
    if ("files".equals(source)) {
      return directory(prefix + ".materialDirectory");
    }
    if (!"kubernetes".equals(source)) {
      throw new AssertionError("Unknown " + role + " TLS material source: " + source);
    }
    Path path = Path.of(configuration(prefix + ".materialDirectory"));
    BiFunction<String, String, byte[]> secrets = (secret, key) -> {
      var result = service.executeKubectlCommand(false, List.of("-n", namespace, "get", "secret",
          secret, "-o", "jsonpath={.data." + key.replace(".", "\\.") + "}"));
      if (result.exitCode() != 0 || result.stdout().isBlank()) {
        throw new AssertionError("Missing installed TLS material: " + secret + "/" + key);
      }
      try {
        return Base64.getDecoder().decode(result.stdout().strip());
      } catch (IllegalArgumentException ex) {
        throw new AssertionError("Invalid encoded TLS material: " + secret + "/" + key);
      }
    };
    if ("rs".equals(role)) {
      exportRsFixtures(path, secrets);
    } else {
      exportGatewayFixtures(path, secrets);
    }
    return path;
  }

  static void exportGatewayFixtures(Path directory, BiFunction<String, String, byte[]> secrets)
      throws java.io.IOException {
    var material = new LinkedHashMap<String, byte[]>();
    material.put("ca.pem", secrets.apply("ns-tls-client-ca", "tls.crt"));
    material.put("ns-good-cert.pem", secrets.apply(
        configuration("testdata.notificationTls.gateway.clientSecrets.good"), "tls.crt"));
    material.put("ns-foreign-cert.pem", secrets.apply(
        configuration("testdata.notificationTls.gateway.clientSecrets.foreign"), "tls.crt"));
    for (String variant : List.of("good", "foreign", "wrong-host")) {
      String secret = "ns-tls-server-" + variant;
      material.put("server-" + variant + "-cert.pem", secrets.apply(secret, "tls.crt"));
      material.put("server-" + variant + "-key.pem", secrets.apply(secret, "tls.key"));
    }
    writeFixtures(directory, material);
  }

  static void exportRsFixtures(Path directory, BiFunction<String, String, byte[]> secrets)
      throws java.io.IOException {
    var material = new LinkedHashMap<String, byte[]>();
    material.put("client-cert.pem", secrets.apply("ns-tls-rs-client", "tls.crt"));
    material.put("client-key.pem", secrets.apply("ns-tls-rs-client", "tls.key"));
    material.put("ca.pem", secrets.apply("ns-tls-server-ca", "tls.crt"));
    material.put("server-cert.pem", secrets.apply(
        configuration("testdata.notificationTls.rs.serverSecret"), "tls.crt"));
    writeFixtures(directory, material);
  }

  private static void writeFixtures(Path directory, java.util.Map<String, byte[]> material)
      throws java.io.IOException {
    if (material.values().stream().anyMatch(value -> value == null || value.length == 0)) {
      throw new AssertionError("Installed TLS fixture material is empty");
    }
    Files.createDirectories(directory);
    for (var entry : material.entrySet()) {
      Path temporary = Files.createTempFile(directory, ".fixture-", ".pem");
      try {
        Files.write(temporary, entry.getValue());
        Files.move(temporary, directory.resolve(entry.getKey()), StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
  }

  static byte[] read(Path directory, String name) throws java.io.IOException {
    return Files.readAllBytes(directory.resolve(name));
  }

  static String fingerprint(byte[] pem) throws Exception {
    var certificate =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem));
    certificate.checkValidity();
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
  }

  /**
   * Ensures issuer-name selection permits transmission without making the foreign signer trusted.
   */
  static void requireSelectableUntrustedClient(byte[] pem, byte[] trustedPem) throws Exception {
    var factory = CertificateFactory.getInstance("X.509");
    var client = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(pem));
    client.checkValidity();
    var usage = client.getExtendedKeyUsage();
    if (client.getBasicConstraints() >= 0
        || (usage != null && !usage.contains("1.3.6.1.5.5.7.3.2"))) {
      throw new AssertionError("Foreign NS fixture must be a valid client-authentication leaf");
    }
    boolean selectable = false;
    for (var certificate : factory.generateCertificates(new ByteArrayInputStream(trustedPem))) {
      var ca = (X509Certificate) certificate;
      if (client.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) {
        selectable = true;
        try {
          client.verify(ca.getPublicKey());
          throw new AssertionError("Foreign NS client fixture is signed by a trusted CA");
        } catch (SignatureException expected) {
          // Same issuer DN, different signing key: selection succeeds, authentication must fail.
        }
      }
    }
    if (!selectable) {
      throw new AssertionError(
          "Foreign NS client issuer must match an advertised CA name while using an untrusted"
              + " signing key");
    }
  }
}
