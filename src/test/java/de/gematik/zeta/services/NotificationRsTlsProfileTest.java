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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationRsTlsProfileTest {
  @Test
  void configuresExistingNsBeforeSelectingReadyPodAndStartingPeer() throws Exception {
    try (var targets = mockConstruction(DeployedNotificationService.class,
            (target, context) -> when(target.address()).thenReturn("10.0.0.9"));
        var files = mockStatic(NotificationTlsFiles.class);
        var peers = mockConstruction(TlsTestToolEndpoint.class);
        var configuration = mockStatic(NotificationTlsDeployment.class)) {
      files.when(() -> NotificationTlsFiles.configuration(anyString())).thenAnswer(
          call -> switch ((String) call.getArgument(0)) {
            case "testdata.notificationTls.rs.port" -> "9443";
            case "testdata.notificationTls.rs.host" -> "installed-ns.test";
            default -> "CN=installed-rs";
          });
      Path directory = Path.of("fixtures");
      files.when(() -> NotificationTlsFiles.rsDirectory(null, "test")).thenReturn(directory);
      for (var name : new String[] {"client-cert.pem", "client-key.pem", "ca.pem", "server-cert.pem"}) {
        files.when(() -> NotificationTlsFiles.read(directory, name)).thenReturn(new byte[] {1});
      }
      try (var profile = new NotificationRsTlsProfile(null, "test", "installed-rs", "tool")) {
        profile.start();
        configuration.verify(() -> NotificationTlsDeployment.rs(null, "test", "installed-rs", 9443));
        configuration.verify(() -> NotificationTlsDeployment.exposeRs("test", "installed-ns.test", 9443));
        verify(peers.constructed().getFirst()).start(
            org.mockito.ArgumentMatchers.eq(Map.of("installed-ns.test", "10.0.0.9")),
            org.mockito.ArgumentMatchers.startsWith("cc-rs-tls-"));
        var peer = peers.constructed().getFirst();
        when(peer.serviceAddress("installed-ns.test")).thenReturn("10.96.0.9");
        assertEquals("10.96.0.9", profile.address());
      }
      var target = targets.constructed().getFirst();
      verify(target).address();
      verify(target).verify();
      verifyNoMoreInteractions(target);
    }
  }

  @Test
  void missingCredentialsFailBeforeReconfigurationAndPeerCreation() {
    try (var targets = mockConstruction(DeployedNotificationService.class);
        var files = mockStatic(NotificationTlsFiles.class);
        var peers = mockConstruction(TlsTestToolEndpoint.class)) {
      files.when(() -> NotificationTlsFiles.configuration(anyString()))
          .thenThrow(new AssertionError("missing installed credentials"));
      var profile = new NotificationRsTlsProfile(null, "test", "installed-rs", "tool");
      assertThrows(AssertionError.class, profile::start);
      profile.close();
      org.junit.jupiter.api.Assertions.assertTrue(targets.constructed().isEmpty());
      org.junit.jupiter.api.Assertions.assertTrue(peers.constructed().isEmpty());
    }
  }

}
