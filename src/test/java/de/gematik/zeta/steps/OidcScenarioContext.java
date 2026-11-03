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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.NotificationKvnrPool;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Keeps all pool reservations until the final scenario hook and protects the shared OIDC driver.
 * The operating-system lock also covers other local JVMs/worktrees and is released on process exit.
 */
public final class OidcScenarioContext {

  private static final ThreadLocal<OidcScenarioContext> CURRENT = ThreadLocal.withInitial(OidcScenarioContext::new);
  private static final Map<String, Semaphore> LOCAL_DRIVERS = new ConcurrentHashMap<>();
  private Semaphore localDriver;
  private final List<OwnedIdentity> identities = new ArrayList<>();
  private FileChannel driverChannel;
  private FileLock driverLock;

  private OidcScenarioContext() {}

  /**
   * Acquires exclusive local ownership before changing the shared testdriver identity.
   */
  public static void acquireDriver() {
    var context = CURRENT.get();
    if (context.driverLock != null) {
      return;
    }
    if (TigerGlobalConfiguration.readBoolean("cucumber.execution.parallel.enabled", false)) {
      throw new AssertionError("OIDC scenarios require sequential execution with the shared testdriver and TigerProxy.");
    }
    try {
      var target = TigerGlobalConfiguration.resolvePlaceholders("${zeta_base_url}");
      var semaphore = LOCAL_DRIVERS.computeIfAbsent(target, unused -> new Semaphore(1));
      if (!semaphore.tryAcquire()) {
        throw new AssertionError("Another scenario owns the OIDC testdriver for " + target + ".");
      }
      context.localDriver = semaphore;
      var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(target.getBytes(StandardCharsets.UTF_8)));
      var directory = Path.of(System.getProperty("java.io.tmpdir"), "zeta-testsuite-oidc-locks");
      Files.createDirectories(directory);
      context.driverChannel = FileChannel.open(directory.resolve(hash + ".lock"),
          StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      context.driverLock = context.driverChannel.tryLock();
      if (context.driverLock == null) {
        throw new AssertionError("Another local test run owns the OIDC testdriver for " + target + ".");
      }
    } catch (IOException | NoSuchAlgorithmException | OverlappingFileLockException e) {
      throw new AssertionError("Could not exclusively reserve the OIDC testdriver.", e);
    }
  }

  /**
   * Records ownership before configuring the identity or making a protected request.
   *
   * @param pool originating pool
   * @param reservation reserved KVNR and binding email
   */
  public static void register(NotificationKvnrPool pool, NotificationKvnrPool.Reservation reservation) {
    CURRENT.get().identities.add(new OwnedIdentity(pool, reservation));
  }

  /**
   * Returns every identity used by this scenario, including failed setup attempts.
   *
   * @return snapshot of owned identities
   */
  public static List<OwnedIdentity> identities() {
    return List.copyOf(CURRENT.get().identities);
  }

  /**
   * Quarantines any unfinished reservations and releases the local driver lock after all hooks.
   */
  public static void clear() {
    var context = CURRENT.get();
    try {
      for (var identity : context.identities) {
        identity.finish(false);
      }
    } finally {
      try {
        if (context.driverChannel != null) {
          context.driverChannel.close();
        }
      } catch (IOException e) {
        throw new AssertionError("Could not release the OIDC testdriver lock.", e);
      } finally {
        if (context.localDriver != null) {
          context.localDriver.release();
        }
        CURRENT.remove();
      }
    }
  }

  /**
   * A pool identity that can only be released once.
   */
  public static final class OwnedIdentity {
    private final NotificationKvnrPool pool;
    private final NotificationKvnrPool.Reservation reservation;
    private boolean finished;

    private OwnedIdentity(NotificationKvnrPool pool, NotificationKvnrPool.Reservation reservation) {
      this.pool = pool;
      this.reservation = reservation;
    }

    /**
     * Returns immutable identity data for reauthentication during cleanup.
     *
     * @return reservation data
     */
    public NotificationKvnrPool.Reservation reservation() {
      return reservation;
    }

    /**
     * Records successful cleanup or quarantines this identity; repeated calls have no effect.
     *
     * @param clean whether the server-side pusher list was verified empty
     */
    public void finish(boolean clean) {
      if (!finished) {
        pool.finish(reservation, clean);
        finished = true;
      }
    }
  }
}
