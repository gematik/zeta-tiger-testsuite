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

import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local TLS tool ownership, with Kubernetes route recovery for deployment-changing callers. */
public final class TlsTestToolSession implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ZetaDeploymentModificationService kubernetes;
  private final String namespace;
  private final TlsTestToolService api;
  private final Runnable verification;
  private final PerformanceRoutingJournal journal;
  private final TlsTestToolLocalLock localLock;
  private ObjectNode snapshot;
  private String recoveryIdentity;
  private boolean owned;

  /** Creates an HTTP-only session without Kubernetes configuration or access. */
  public TlsTestToolSession(TlsTestToolService api) {
    this(null, null, api, () -> {});
  }

  TlsTestToolSession(ZetaDeploymentModificationService kubernetes, String namespace,
      TlsTestToolService api, Runnable verification) {
    this.kubernetes = kubernetes;
    this.namespace = namespace;
    this.api = api;
    this.verification = verification;
    journal = kubernetes == null ? null : new PerformanceRoutingJournal(kubernetes, "testsuite-tls-tool");
    localLock = new TlsTestToolLocalLock(TlsTestToolServiceFactory.serviceUrl());
  }

  /** Claims the tool locally and recovers journaled routes for Kubernetes-managed sessions. */
  public void start(String pod, String scenario) {
    localLock.acquire(journal == null ? null : journal.recoveryIdentity(namespace));
    if (journal != null) {
      journal.recover(namespace, saved -> {
        localLock.markRoutesPending(journal.recoveryIdentity(namespace));
        restoreRoutes(saved);
        api.reset();
      });
      snapshot = JSON.createObjectNode().put("version", 1).put("namespace", namespace)
          .put("pod", pod).put("scenario", scenario);
      snapshot.putArray("routes");
      journal.begin(namespace, snapshot);
      recoveryIdentity = journal.recoveryIdentity(namespace);
      if (recoveryIdentity == null) {
        throw new AssertionError("Owned TLS routing journal is missing");
      }
      localLock.acquire(recoveryIdentity);
    }
    owned = true;
  }

  /** Persists restoration before attaching a gateway route. */
  void reserveRoute(JsonNode original, JsonNode selected) {
    assertOwned();
    if (journal == null) {
      throw new AssertionError("Gateway routes require a Kubernetes-managed TLS session");
    }
    localLock.markRoutesPending(recoveryIdentity);
    var saved = snapshot.withArray("routes").addObject();
    saved.put("name", original.at("/metadata/name").asString());
    saved.set("uid", original.at("/metadata/uid"));
    saved.set("original", original.at("/spec/selector"));
    saved.set("expected", selected);
    var recovery = DeploymentStateRecovery.currentFor(namespace);
    if (recovery != null && recovery.ownsCreated("service/" + saved.path("name").asString(), original)) {
      saved.set("fixtureOwner", original.path("metadata").path("annotations")
          .path(DeploymentStateRecovery.RESOURCE_OWNER));
    }
    journal.update(namespace, snapshot);
  }

  /** Verifies local ownership and, for Kubernetes-managed sessions, journal ownership. */
  public void assertOwned() {
    if (!owned) {
      throw new AssertionError("TLS tool session does not own the runtime");
    }
    localLock.assertOwned();
    if (journal != null) {
      journal.assertOwned(namespace);
    }
  }

  /** Detaches and observes all recorded routes before resetting the shared runtime. */
  public void reset() {
    assertOwned();
    if (journal != null) {
      restoreRoutes(snapshot);
    }
    api.reset();
  }

  /** Returns the service only while this session owns the shared tool. */
  public TlsTestToolService api() {
    assertOwned();
    return api;
  }

  private void restoreRoutes(JsonNode saved) {
    if (saved.path("version").asInt() != 1
        || !namespace.equals(saved.path("namespace").asString()) || !saved.path("routes").isArray()) {
      throw new AssertionError("Invalid shared TLS tool recovery snapshot");
    }
    var failures = new ArrayList<Throwable>();
    for (var route : saved.path("routes")) {
      try {
        NotificationGatewayTlsRoute.restore(kubernetes, namespace, route);
      } catch (RuntimeException | AssertionError exception) {
        failures.add(exception);
      }
    }
    if (!failures.isEmpty()) {
      var failure = new AssertionError("TLS gateway routes could not be detached; reset and ownership release refused");
      failures.forEach(failure::addSuppressed);
      throw failure;
    }
  }

  /** Holds ownership through verification and reset; failed cleanup retains the durable backup. */
  @Override
  public void close() {
    try (localLock) {
      if (owned) {
        localLock.acquire(recoveryIdentity);
        assertOwned();
        if (journal != null) {
          restoreRoutes(snapshot);
        }
        verification.run();
        api.reset();
        if (journal != null) {
          journal.complete(namespace);
          localLock.clearPendingRoutes(recoveryIdentity);
        }
        owned = false;
      }
    } finally {
      if (journal != null) {
        journal.release();
      }
    }
  }
}
