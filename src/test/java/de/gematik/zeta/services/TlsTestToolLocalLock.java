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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process lock and persistent pending-route marker for callers sharing a local temporary directory. */
final class TlsTestToolLocalLock implements AutoCloseable {
  private static final Set<Path> ACTIVE = ConcurrentHashMap.newKeySet();
  private final Path path;
  private FileChannel channel;
  private FileLock lock;
  private boolean registered;

  TlsTestToolLocalLock(String serviceUrl) {
    this(Path.of(System.getProperty("java.io.tmpdir"), "zeta-tls-test-tool",
        UUID.nameUUIDFromBytes(serviceUrl.getBytes(StandardCharsets.UTF_8)) + ".lock"));
  }

  TlsTestToolLocalLock(Path path) {
    this.path = path.toAbsolutePath().normalize();
  }

  void acquire(String recoveryIdentity) {
    if (lock != null && lock.isValid()) {
      verifyPendingRoutes(recoveryIdentity);
      return;
    }
    if (!ACTIVE.add(path)) {
      throw new AssertionError("TLS tool is already reserved by another local test run: " + path);
    }
    registered = true;
    try {
      Files.createDirectories(path.getParent());
      channel = FileChannel.open(path, StandardOpenOption.CREATE,
          StandardOpenOption.READ, StandardOpenOption.WRITE);
      lock = channel.tryLock();
      if (lock == null) {
        throw new AssertionError("TLS tool is already reserved by another local test run");
      }
      verifyPendingRoutes(recoveryIdentity);
    } catch (IOException | OverlappingFileLockException | AssertionError exception) {
      try {
        close();
      } catch (AssertionError cleanup) {
        exception.addSuppressed(cleanup);
      }
      throw new AssertionError("Cannot reserve the TLS tool locally: " + path, exception);
    }
  }

  void assertOwned() {
    if (lock == null || !lock.isValid()) {
      throw new AssertionError("Local TLS tool reservation was lost");
    }
  }

  void markRoutesPending(String recoveryIdentity) {
    assertOwned();
    var marker = ByteBuffer.wrap(markerBytes(recoveryIdentity));
    verifyPendingRoutes(recoveryIdentity);
    try {
      if (channel.size() > 0) {
        return;
      }
      channel.position(0);
      while (marker.hasRemaining()) {
        channel.write(marker);
      }
      channel.force(true);
    } catch (IOException exception) {
      throw new AssertionError("Cannot persist pending TLS gateway route cleanup", exception);
    }
  }

  void clearPendingRoutes(String recoveryIdentity) {
    assertOwned();
    markerBytes(recoveryIdentity);
    verifyPendingRoutes(recoveryIdentity);
    try {
      channel.truncate(0);
      channel.force(true);
    } catch (IOException exception) {
      throw new AssertionError("Cannot clear pending TLS gateway route cleanup", exception);
    }
  }

  private void verifyPendingRoutes(String recoveryIdentity) {
    assertOwned();
    try {
      long size = channel.size();
      if (size == 0) {
        return;
      }
      if (recoveryIdentity == null) {
        throw new AssertionError("TLS gateway route cleanup is pending; recover the NS TLS session "
            + "with Kubernetes access before using the shared tool: " + path);
      }
      var expected = markerBytes(recoveryIdentity);
      if (size == expected.length) {
        var saved = ByteBuffer.allocate(expected.length);
        channel.position(0);
        while (saved.hasRemaining()) {
          if (channel.read(saved) == -1) {
            break;
          }
        }
        if (!saved.hasRemaining() && Arrays.equals(saved.array(), expected)) {
          return;
        }
      }
      throw new AssertionError("TLS gateway route cleanup is pending for a different or unidentified "
          + "Kubernetes journal; the marker cannot be replaced or cleared by " + recoveryIdentity + ": " + path);
    } catch (IOException exception) {
      throw new AssertionError("Cannot read pending TLS gateway route cleanup", exception);
    }
  }

  private static byte[] markerBytes(String recoveryIdentity) {
    if (recoveryIdentity == null || recoveryIdentity.length() > 1024
        || !recoveryIdentity.matches("[A-Za-z0-9._/-]+")) {
      throw new AssertionError("A Kubernetes journal identity is required for pending TLS gateway routes");
    }
    return ("tls-routes-v1:" + recoveryIdentity).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (IOException exception) {
      throw new AssertionError("Cannot release the local TLS tool reservation", exception);
    } finally {
      channel = null;
      lock = null;
      if (registered) {
        ACTIVE.remove(path);
        registered = false;
      }
    }
  }
}
