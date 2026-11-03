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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reserves predefined notification identities until verified cleanup completes.
 * Failed cleanup quarantines an identity for the remainder of this JVM. A subsequent run must
 * clean the dedicated identity before using it, including leftovers from interrupted runs.
 */
public final class NotificationKvnrPool {

  private static final Map<String, NotificationKvnrPool> POOLS = new HashMap<>();
  private final List<String> kvnrs;
  private final Map<String, Reservation> active = new LinkedHashMap<>();
  private final Set<String> quarantined = new HashSet<>();
  private int next;

  /**
   * Creates a pool of distinct, valid test identities.
   *
   * @param kvnrs predefined KVNRs exclusively assigned to notification tests
   */
  public NotificationKvnrPool(List<String> kvnrs) {
    if (kvnrs.isEmpty() || new HashSet<>(kvnrs).size() != kvnrs.size()
        || kvnrs.stream().anyMatch(kvnr -> !KvnrGenerator.isValid(kvnr))) {
      throw new IllegalArgumentException("The notification KVNR pool must contain distinct valid KVNRs.");
    }
    this.kvnrs = List.copyOf(kvnrs);
  }

  /**
   * Loads the ten dedicated identities for the configured target environment.
   *
   * @return pool shared by the scenarios in this JVM
   */
  public static synchronized NotificationKvnrPool configured() {
    var kvnrs = TigerGlobalConfiguration.readList("testdata.oidc.kvnrPool").stream()
        .map(TigerGlobalConfiguration::resolvePlaceholders).toList();
    if (kvnrs.size() != 10) {
      throw new AssertionError("testdata.oidc.kvnrPool must contain exactly ten dedicated test KVNRs.");
    }
    var key = TigerGlobalConfiguration.resolvePlaceholders("${zeta_base_url}") + ":" + kvnrs;
    return POOLS.computeIfAbsent(key, unused -> new NotificationKvnrPool(kvnrs));
  }

  /**
   * Atomically reserves the next available identity without falling back to random values.
   *
   * @param emailDomain domain associated with the pool identities
   * @return owned reservation that must be retained even if setup fails
   */
  public synchronized Reservation reserve(String emailDomain) {
    if (emailDomain == null || emailDomain.isBlank()) {
      throw new IllegalArgumentException("The OIDC test email domain must not be blank.");
    }
    for (int offset = 0; offset < kvnrs.size(); offset++) {
      int index = (next + offset) % kvnrs.size();
      var kvnr = kvnrs.get(index);
      if (!active.containsKey(kvnr) && !quarantined.contains(kvnr)) {
        var reservation = new Reservation(UUID.randomUUID().toString(), kvnr, kvnr + "@" + emailDomain);
        active.put(kvnr, reservation);
        next = (index + 1) % kvnrs.size();
        return reservation;
      }
    }
    throw new AssertionError("No free notification KVNR: " + active.size() + " reserved, "
        + quarantined.size() + " quarantined after cleanup failure.");
  }

  /**
   * Releases only the matching reservation, quarantining identities with unverified cleanup.
   *
   * @param reservation reservation owned by the completed scenario
   * @param clean whether all required cleanup checks succeeded
   */
  public synchronized void finish(Reservation reservation, boolean clean) {
    if (!reservation.equals(active.get(reservation.kvnr()))) {
      throw new IllegalStateException("The notification KVNR reservation is no longer owned by this scenario.");
    }
    active.remove(reservation.kvnr());
    if (!clean) {
      quarantined.add(reservation.kvnr());
    }
  }

  /**
   * Identity and ownership token retained independently of mutable Tiger variables.
   *
   * @param id unique reservation identifier
   * @param kvnr reserved identity
   * @param email correlated OIDC binding address
   */
  public record Reservation(String id, String kvnr, String email) {

    /**
     * Generates a fresh F1 address when driver reset starts a new SDK registration.
     *
     * @return email correlated to the reserved KVNR and using its configured domain
     */
    public String newBindingEmail() {
      return kvnr + "-" + UUID.randomUUID() + email.substring(email.lastIndexOf('@'));
    }
  }
}
