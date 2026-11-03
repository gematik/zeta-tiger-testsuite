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

package de.gematik.zeta.services.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gematik.zeta.services.DeploymentStateRecovery;
import de.gematik.zeta.services.NotificationTlsPeer;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Verifies generated TLS identities and owned fixture cleanup against the Kubernetes emulator. */
class NotificationTlsPeerTest {

  /** The leaf chains to the generated CA and binds exactly the temporary Service DNS names. */
  @Test
  void createsTrustedServiceIdentityAndRemovesAllFixtures() throws Exception {
    var cluster = new DeploymentStateRecoveryTest.Cluster();
    var recovery = new DeploymentStateRecovery(cluster, "test");
    recovery.attach();
    try {
      NotificationTlsPeer.create("test", "ns-peer");
      var server = certificate(cluster.resources.get("secret/ns-peer"), "tls.crt");
      var ca = certificate(cluster.resources.get("secret/ns-peer-ca"), "ca.crt");
      server.verify(ca.getPublicKey());
      ca.verify(ca.getPublicKey());
      server.checkValidity();
      assertEquals(-1, server.getBasicConstraints());
      assertTrue(ca.getBasicConstraints() >= 0);
      assertEquals("EC", server.getPublicKey().getAlgorithm());
      assertTrue(server.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
      assertTrue(server.getSubjectAlternativeNames().stream().anyMatch(san -> "ns-peer".equals(san.get(1))));
      assertFalse(server.getSubjectAlternativeNames().stream().anyMatch(san -> "ns-tls-baseline".equals(san.get(1))));
      var service = cluster.resources.get("service/ns-peer");
      assertEquals(443, service.at("/spec/ports/0/port").asInt());
      assertEquals(4433, service.at("/spec/ports/0/targetPort").asInt());
      assertEquals("ns-peer", service.at("/spec/selector/testsuite.gematik.de~1idle-tls-peer").asString());
      var snapshot = cluster.journal().at("/data/snapshot").asString();
      assertFalse(snapshot.contains("tls.key"));
      assertFalse(snapshot.contains("tls.crt"));
    } finally {
      recovery.finish();
    }
    assertFalse(cluster.resources.containsKey("service/ns-peer"));
    assertFalse(cluster.resources.containsKey("secret/ns-peer"));
    assertFalse(cluster.resources.containsKey("secret/ns-peer-ca"));
  }

  /**
   * Parses a base64-encoded PEM certificate from Secret data.
   *
   * @param secret Secret manifest
   * @param key certificate data key
   * @return parsed X.509 certificate
   * @throws Exception if decoding fails
   */
  private X509Certificate certificate(JsonNode secret, String key) throws Exception {
    var bytes = Base64.getDecoder().decode(secret.path("data").path(key).asString());
    return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bytes));
  }
}
