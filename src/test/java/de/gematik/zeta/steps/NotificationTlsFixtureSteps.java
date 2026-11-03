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

package de.gematik.zeta.steps;

import de.gematik.zeta.services.NotificationTlsPeer;
import de.gematik.zeta.services.ZetaDeploymentConfiguration;
import io.cucumber.java.de.Gegebensei;

/** Steps for owned TLS peer fixtures and additional Notification Service trust. */
public class NotificationTlsFixtureSteps {

  /**
   * Creates a temporary DNS Service and its server and CA certificate Secrets.
   *
   * @param name unique Service and server Secret name
   * @throws Exception if certificate generation or resource creation fails
   */
  @Gegebensei("erstelle die temporäre TLS-Gegenstelle {tigerResolvedString} mit ECDSA-Serverzertifikat")
  public void createPeer(String name) throws Exception {
    requireModification();
    NotificationTlsPeer.create(ZetaDeploymentConfiguration.getNamespace(), name);
  }

  /**
   * Adds the peer URL and CA to the named Notification Service deployment.
   *
   * @param name peer Service name
   * @param deployment Notification Service deployment
   */
  @Gegebensei("ergänze die TLS-Gegenstelle {tigerResolvedString} als Push-Gateway-Ziel im Deployment {tigerResolvedString}")
  public void trustPeer(String name, String deployment) {
    requireModification();
    NotificationTlsPeer.trust(ZetaDeploymentConfiguration.getServiceInstance(),
        ZetaDeploymentConfiguration.getNamespace(), deployment, name);
  }

  /** Requires deployment modification to be enabled. */
  private static void requireModification() {
    if (!ZetaDeploymentConfiguration.isDeploymentModificationAllowed()) {
      throw new AssertionError("Deployment modification is disabled");
    }
  }
}
