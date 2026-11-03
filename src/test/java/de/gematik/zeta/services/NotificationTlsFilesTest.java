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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class NotificationTlsFilesTest {
  @TempDir
  Path temporary;
  private MockedStatic<TigerGlobalConfiguration> configuration;

  @BeforeEach
  void configureInstalledSecrets() {
    configuration = mockStatic(TigerGlobalConfiguration.class);
    configuration.when(() -> TigerGlobalConfiguration.resolvePlaceholders(anyString()))
        .thenAnswer(call -> call.getArgument(0));
    configureSecret("gateway.clientSecrets.good", "ns-tls-client-good");
    configureSecret("gateway.clientSecrets.foreign", "ns-tls-client-foreign");
    configureSecret("rs.serverSecret", "ns-tls-rs-server");
  }

  private void configureSecret(String key, String secret) {
    configuration.when(() -> TigerGlobalConfiguration.readStringOptional("testdata.notificationTls." + key))
        .thenReturn(Optional.of(secret));
  }

  @AfterEach
  void releaseConfiguration() {
    configuration.close();
  }

  @Test
  void exportsTheConfiguredNsIdentitiesWhenDefaultSecretsAreAbsent() throws Exception {
    configureSecret("gateway.clientSecrets.good", "custom-good");
    configureSecret("gateway.clientSecrets.foreign", "custom-foreign");
    configureSecret("rs.serverSecret", "custom-rs-server");
    var requested = new HashSet<String>();
    java.util.function.BiFunction<String, String, byte[]> secrets = (secret, key) -> {
      if (java.util.Set.of("ns-tls-client-good", "ns-tls-client-foreign", "ns-tls-rs-server").contains(secret)) {
        throw new AssertionError("Default Secret is not installed");
      }
      requested.add(secret + "/" + key);
      return (secret + "/" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    };
    NotificationTlsFiles.exportGatewayFixtures(temporary, secrets);
    assertEquals("custom-good/tls.crt", Files.readString(temporary.resolve("ns-good-cert.pem")));
    assertEquals("custom-foreign/tls.crt", Files.readString(temporary.resolve("ns-foreign-cert.pem")));
    NotificationTlsFiles.exportRsFixtures(temporary, secrets);
    assertEquals("custom-rs-server/tls.crt", Files.readString(temporary.resolve("server-cert.pem")));
    org.junit.jupiter.api.Assertions.assertFalse(requested.contains("custom-good/tls.key"));
    org.junit.jupiter.api.Assertions.assertFalse(requested.contains("custom-foreign/tls.key"));
    org.junit.jupiter.api.Assertions.assertFalse(requested.contains("custom-rs-server/tls.key"));
  }

  @Test
  void exportsPeerMaterialWithoutAnyNsPrivateKey() throws Exception {
    var requested = new HashSet<String>();
    NotificationTlsFiles.exportGatewayFixtures(temporary, (secret, key) -> {
      requested.add(secret + "/" + key);
      return (secret + "/" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    });
    assertEquals(9, requested.size());
    try (var files = Files.list(temporary)) {
      assertEquals(9, files.count());
    }
    assertEquals("ns-tls-client-ca/tls.crt", Files.readString(temporary.resolve("ca.pem")));
    assertEquals("ns-tls-server-good/tls.key",
        Files.readString(temporary.resolve("server-good-key.pem")));
    org.junit.jupiter.api.Assertions.assertTrue(requested.stream()
        .filter(s -> s.startsWith("ns-tls-client-")).allMatch(s -> s.endsWith("/tls.crt")));
  }

  @Test
  void exportsOnlyTheRsClientsPrivateKeyAndPublicServerIdentity() throws Exception {
    var requested = new HashSet<String>();
    NotificationTlsFiles.exportRsFixtures(temporary, (secret, key) -> {
      requested.add(secret + "/" + key);
      return new byte[] {1};
    });
    assertEquals(java.util.Set.of("ns-tls-rs-client/tls.crt", "ns-tls-rs-client/tls.key",
        "ns-tls-server-ca/tls.crt", "ns-tls-rs-server/tls.crt"), requested);
    try (var files = Files.list(temporary)) {
      assertEquals(4, files.count());
    }
  }

  @Test
  void failedOrEmptySecretFetchPreservesPreviousMaterial() throws Exception {
    Files.writeString(temporary.resolve("ca.pem"), "previous certificate");
    assertThrows(AssertionError.class,
        () -> NotificationTlsFiles.exportGatewayFixtures(temporary, (secret, key) -> {
          if ("ns-tls-server-foreign".equals(secret)) {
            throw new AssertionError("secret unavailable");
          }
          return new byte[] {1};
        }));
    assertEquals("previous certificate", Files.readString(temporary.resolve("ca.pem")));
    assertThrows(AssertionError.class,
        () -> NotificationTlsFiles.exportGatewayFixtures(temporary, (secret, key) -> new byte[0]));
    assertEquals("previous certificate", Files.readString(temporary.resolve("ca.pem")));
    try (var files = Files.list(temporary)) {
      assertEquals(1, files.count());
    }
  }

  private X509Certificate certificate(
      String issuer, KeyPair signing, String subject, KeyPair identity, boolean ca)
      throws Exception {
    var builder =
        new JcaX509v3CertificateBuilder(
            new X500Name(issuer),
            BigInteger.ONE,
            new Date(0),
            new Date(2000000000000L),
            new X500Name(subject),
            identity.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (!ca) {
      builder.addExtension(
          Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
    }
    return new JcaX509CertificateConverter()
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withECDSA").build(signing.getPrivate())));
  }

  @Test
  void sameIssuerNameWithForeignKeyIsSelectableButNotTrusted() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    var trustedKey = generator.generateKeyPair();
    var foreignKey = generator.generateKeyPair();
    var clientKey = generator.generateKeyPair();
    String issuer = "CN=Gateway client CA";
    var trusted = certificate(issuer, trustedKey, issuer, trustedKey, true);
    var leaf = certificate(issuer, foreignKey, "CN=NS foreign", clientKey, false);
    assertDoesNotThrow(
        () ->
            NotificationTlsFiles.requireSelectableUntrustedClient(
                leaf.getEncoded(), trusted.getEncoded()));
    assertThrows(java.security.SignatureException.class, () -> leaf.verify(trusted.getPublicKey()));

    var store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    var foreign = certificate(issuer, foreignKey, issuer, foreignKey, true);
    store.setKeyEntry(
        "foreign", clientKey.getPrivate(), new char[0], new X509Certificate[] {leaf, foreign});
    var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store, new char[0]);
    var manager = (X509ExtendedKeyManager) factory.getKeyManagers()[0];
    assertNotNull(
        manager.chooseClientAlias(
            new String[] {"EC"},
            new java.security.Principal[] {trusted.getSubjectX500Principal()},
            null));
    assertNull(
        manager.chooseClientAlias(
            new String[] {"EC"},
            new java.security.Principal[] {
              new javax.security.auth.x500.X500Principal("CN=Other CA")
            },
            null));

    var valid = certificate(issuer, trustedKey, "CN=NS valid", clientKey, false);
    assertThrows(
        AssertionError.class,
        () ->
            NotificationTlsFiles.requireSelectableUntrustedClient(
                valid.getEncoded(), trusted.getEncoded()));
    var otherIssuer = certificate("CN=Other CA", foreignKey, "CN=NS foreign", clientKey, false);
    assertThrows(
        AssertionError.class,
        () ->
            NotificationTlsFiles.requireSelectableUntrustedClient(
                otherIssuer.getEncoded(), trusted.getEncoded()));
  }
}
