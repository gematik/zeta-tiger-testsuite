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

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Separate installed gateway destinations keep dispatch retries out of later TLS probes. */
record NotificationGatewayTlsTarget(URI url, String service) {
  static final List<String> NAMES =
      List.of("baseline", "missing", "foreign", "untrusted", "wrong-host", "control");
  private static final Set<String> USED = ConcurrentHashMap.newKeySet();

  static Map<String, NotificationGatewayTlsTarget> load(Function<String, String> configuration) {
    var targets = new LinkedHashMap<String, NotificationGatewayTlsTarget>();
    var hosts = new HashSet<String>();
    var services = new HashSet<String>();
    for (var name : NAMES) {
      String prefix = "testdata.notificationTls.gateway.targets." + name;
      var url = URI.create(configuration.apply(prefix + ".url"));
      String service = configuration.apply(prefix + ".service");
      require(
          "https".equals(url.getScheme())
              && url.getHost() != null
              && url.getUserInfo() == null
              && url.getQuery() == null
              && url.getFragment() == null
              && (url.getPort() == -1 || url.getPort() == 443)
              && url.getPath().endsWith("/"),
          "Expected an explicit HTTPS gateway on port 443: " + name);
      require(
          service.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?"),
          "Expected a dedicated gateway Service: " + name);
      require(
          hosts.add(url.getHost().toLowerCase(Locale.ROOT)) && services.add(service),
          "Gateway variants require distinct DNS hosts and Services; different paths do not isolate"
              + " TLS retries");
      targets.put(name, new NotificationGatewayTlsTarget(url, service));
    }
    return Map.copyOf(targets);
  }

  void reserve(String namespace) {
    // A failed handshake can leave dispatch work alive even after peer cleanup.
    require(
        USED.add(namespace + "/" + service),
        "Gateway TLS destination already used in this JVM; provide a fresh, idle fixture");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
