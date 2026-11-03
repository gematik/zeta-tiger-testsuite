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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationGatewayTlsTargetTest {
  private Map<String, String> configuration() {
    var result = new HashMap<String, String>();
    for (var name : NotificationGatewayTlsTarget.NAMES) {
      var prefix = "testdata.notificationTls.gateway.targets." + name;
      result.put(prefix + ".url", "https://" + name + ".test/push/v1/");
      result.put(prefix + ".service", name);
    }
    return result;
  }

  @Test
  void requiresDistinctTcpDestinationsRatherThanOnlyDifferentHttpPaths() {
    var configuration = configuration();
    assertEquals(6, NotificationGatewayTlsTarget.load(configuration::get).size());
    configuration.put(
        "testdata.notificationTls.gateway.targets.control.url",
        "https://BASELINE.test/another/path/");
    assertThrows(AssertionError.class, () -> NotificationGatewayTlsTarget.load(configuration::get));
    var duplicate = configuration();
    duplicate.put("testdata.notificationTls.gateway.targets.control.service", "baseline");
    assertThrows(AssertionError.class, () -> NotificationGatewayTlsTarget.load(duplicate::get));
  }

  @Test
  void rejectsUnusableUrlsAndReuseEvenAfterPeerCleanup() {
    for (var url :
        java.util.List.of(
            "http://control.test/",
            "https://control.test:8443/",
            "https://control.test/no-slash",
            "https://control.test/?query=true")) {
      var configuration = configuration();
      configuration.put("testdata.notificationTls.gateway.targets.control.url", url);
      assertThrows(
          AssertionError.class, () -> NotificationGatewayTlsTarget.load(configuration::get));
    }
    var targets = NotificationGatewayTlsTarget.load(configuration()::get);
    String namespace = UUID.randomUUID().toString();
    targets.get("baseline").reserve(namespace);
    targets.get("control").reserve(namespace);
    assertThrows(AssertionError.class, () -> targets.get("baseline").reserve(namespace));
  }
}
