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

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Creates owned DNS and ECDSA certificate fixtures for a temporary TLS peer. */
public final class NotificationTlsPeer {
  private static final ObjectMapper JSON = new ObjectMapper();

  private NotificationTlsPeer() {}

  /**
   * Creates an idle TCP 443 to 4433 Service, a server Secret and a public CA Secret.
   *
   * @param namespace namespace for the fixtures
   * @param name unique DNS label, also used as server Secret name; CA Secret adds -ca
   * @throws Exception if certificate generation or Kubernetes creation fails
   */
  public static void create(String namespace, String name) throws Exception {
    if (!name.matches("[a-z0-9](?:[-a-z0-9]{0,58}[a-z0-9])?")) {
      throw new AssertionError("TLS peer name must leave room for the CA Secret suffix");
    }
    var recovery = DeploymentStateRecovery.currentFor(namespace);
    if (recovery == null) {
      throw new AssertionError("TLS peer creation requires an active recovery session");
    }
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var caKey = generator.generateKeyPair();
    var serverKey = generator.generateKeyPair();
    var ca = certificate(name + " CA", name + " CA", caKey, caKey, true, List.of());
    var server = certificate(name, name + " CA", serverKey, caKey, false,
        List.of(name, name + "." + namespace, name + "." + namespace + ".svc",
            name + "." + namespace + ".svc.cluster.local"));
    server.verify(ca.getPublicKey());
    var caSecret = resource("Secret", name + "-ca");
    caSecret.put("type", "Opaque").putObject("data").put("ca.crt", encoded(pem(ca)));
    recovery.create(caSecret);
    var serverSecret = resource("Secret", name);
    serverSecret.put("type", "kubernetes.io/tls").putObject("data")
        .put("tls.crt", encoded(pem(server))).put("tls.key", encoded(pem(serverKey.getPrivate())));
    recovery.create(serverSecret);
    var service = resource("Service", name);
    service.withObject("/metadata/annotations").put("testsuite.gematik.de/tls-peer", "true");
    var spec = service.putObject("spec").put("type", "ClusterIP");
    spec.putObject("selector").put("testsuite.gematik.de/idle-tls-peer", name);
    spec.putArray("ports").addObject().put("name", "tls").put("protocol", "TCP")
        .put("port", 443).put("targetPort", 4433);
    recovery.create(service);
  }

  /**
   * Adds the peer URL and its mounted CA to the existing Notification Service trust configuration.
   *
   * @param service Kubernetes executor
   * @param namespace target namespace
   * @param deployment Notification Service deployment
   * @param name peer Service and certificate name
   */
  public static void trust(ZetaDeploymentModificationService service, String namespace,
      String deployment, String name) {
    if (!name.matches("[a-z0-9](?:[-a-z0-9]{0,58}[a-z0-9])?")) {
      throw new AssertionError("Invalid TLS peer DNS label");
    }
    DeploymentEnvironment.change(service, namespace, deployment, "notification-service", template -> {
      var spec = (ObjectNode) template.path("spec");
      var container = DeploymentEnvironment.container(template, "notification-service");
      var volumeName = "cc-" + name;
      var mountPath = "/certs/" + volumeName;
      var volumes = spec.withArray("/volumes");
      var mounts = container.withArray("/volumeMounts");
      if (volumes.valueStream().anyMatch(volume -> volumeName.equals(volume.path("name").asString()))
          || mounts.valueStream().anyMatch(mount -> mountPath.equals(mount.path("mountPath").asString()))) {
        throw new AssertionError("TLS fixture mount conflicts with existing configuration");
      }
      volumes.addObject().put("name", volumeName).putObject("secret").put("secretName", name + "-ca");
      mounts.addObject().put("name", volumeName).put("mountPath", mountPath).put("readOnly", true);
      DeploymentEnvironment.append(container, "PUSH_GATEWAY_TRUSTED_CA_PATHS", mountPath + "/ca.crt");
      DeploymentEnvironment.append(container, "PUSH_GATEWAY_ALLOWED_BASE_URLS", "https://" + name + "/push/v1/");
      return template;
    });
  }

  /**
   * Constructs an unsigned Kubernetes fixture document.
   *
   * @param kind core resource kind
   * @param name resource name
   * @return new manifest
   */
  private static ObjectNode resource(String kind, String name) {
    var resource = JSON.createObjectNode().put("apiVersion", "v1").put("kind", kind);
    resource.putObject("metadata").put("name", name);
    return resource;
  }

  /**
   * Encodes Secret material without line wrapping.
   *
   * @param material UTF-8 PEM
   * @return Kubernetes Secret data value
   */
  private static String encoded(String material) {
    return Base64.getEncoder().encodeToString(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Serializes a key or certificate to PEM.
   *
   * @param material key or certificate
   * @return PEM text
   * @throws Exception if serialization fails
   */
  private static String pem(Object material) throws Exception {
    var output = new StringWriter();
    try (var writer = new JcaPEMWriter(output)) {
      writer.writeObject(material);
    }
    return output.toString();
  }

  /**
   * Issues a short-lived P-256 CA or server certificate with explicit constraints and usage.
   *
   * @param subject certificate subject CN
   * @param issuer issuer CN
   * @param key subject key pair
   * @param signer issuer key pair
   * @param ca whether this is a CA certificate
   * @param hosts DNS subject alternative names
   * @return signed certificate
   * @throws Exception if certificate construction fails
   */
  private static X509Certificate certificate(String subject, String issuer, KeyPair key,
      KeyPair signer, boolean ca, List<String> hosts) throws Exception {
    var now = Instant.now();
    var builder = new JcaX509v3CertificateBuilder(new X500Name("CN=" + issuer),
        new BigInteger(159, new SecureRandom()).add(BigInteger.ONE), Date.from(now.minusSeconds(300)),
        Date.from(now.plusSeconds(86400)), new X500Name("CN=" + subject), key.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    builder.addExtension(Extension.keyUsage, true,
        new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
    if (!ca) {
      builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
      builder.addExtension(Extension.subjectAlternativeName, false,
          new GeneralNames(hosts.stream().map(host -> new GeneralName(GeneralName.dNSName, host)).toArray(GeneralName[]::new)));
    }
    return new JcaX509CertificateConverter().getCertificate(
        builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate())));
  }
}
