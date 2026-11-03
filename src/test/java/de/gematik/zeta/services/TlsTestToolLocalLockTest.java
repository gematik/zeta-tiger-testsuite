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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTestToolLocalLockTest {
  private static final String IDENTITY = "test/testsuite-tls-tool/journal-uid";
  @TempDir
  Path temporaryDirectory;

  @Test
  void refusedContenderCannotReleaseTheOwnersLock() {
    var path = temporaryDirectory.resolve("shared.lock");
    try (var owner = new TlsTestToolLocalLock(path);
        var contender = new TlsTestToolLocalLock(path)) {
      owner.acquire(null);
      assertThrows(AssertionError.class, () -> contender.acquire(null));
      contender.close();
      owner.assertOwned();
      assertThrows(AssertionError.class, () -> contender.acquire(IDENTITY));
      owner.close();
      contender.acquire(null);
      contender.assertOwned();
    }
  }

  @Test
  void distinctToolsCanRunIndependently() {
    try (var first = new TlsTestToolLocalLock(temporaryDirectory.resolve("first.lock"));
        var second = new TlsTestToolLocalLock(temporaryDirectory.resolve("second.lock"))) {
      first.acquire(null);
      second.acquire(null);
      first.assertOwned();
      second.assertOwned();
    }
  }

  @Test
  void anotherProcessCannotClaimALiveLockAndItsPendingRoutesSurviveExit() throws Exception {
    var path = temporaryDirectory.resolve("shared.lock");
    try (var owner = new TlsTestToolLocalLock(path)) {
      owner.acquire(null);
      try (var contender = new TlsTestToolLocalLock(path)) {
        assertThrows(AssertionError.class, () -> contender.acquire(null));
      }
      assertNotEquals(0, runProbe(path));
      assertEquals(0, Files.size(path));
      owner.assertOwned();
    }
    assertEquals(0, runProbe(path));
    assertTrue(Files.size(path) > 0);
    try (var ordinary = new TlsTestToolLocalLock(path)) {
      var failure = assertThrows(AssertionError.class, () -> ordinary.acquire(null));
      assertTrue(failure.getCause().getMessage().contains("route cleanup is pending"));
    }
    try (var recovery = new TlsTestToolLocalLock(path)) {
      recovery.acquire(IDENTITY);
      recovery.clearPendingRoutes(IDENTITY);
    }
    try (var ordinary = new TlsTestToolLocalLock(path)) {
      ordinary.acquire(null);
      ordinary.assertOwned();
      assertEquals(0, Files.size(path));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "other/testsuite-tls-tool/journal-uid", "test/testsuite-tls-tool/other-journal-uid"
  })
  void anotherRecoveryIdentityCannotAcquireReplaceOrClearPendingRoutes(String otherIdentity) throws Exception {
    var path = temporaryDirectory.resolve("shared.lock");
    try (var owner = new TlsTestToolLocalLock(path)) {
      owner.acquire(IDENTITY);
      owner.markRoutesPending(IDENTITY);
    }
    var pending = Files.readAllBytes(path);
    try (var other = new TlsTestToolLocalLock(path)) {
      assertThrows(AssertionError.class, () -> other.acquire(otherIdentity));
    }
    assertArrayEquals(pending, Files.readAllBytes(path));
    try (var recovery = new TlsTestToolLocalLock(path)) {
      recovery.acquire(IDENTITY);
      assertThrows(AssertionError.class, () -> recovery.acquire(otherIdentity));
      assertThrows(AssertionError.class, () -> recovery.markRoutesPending(otherIdentity));
      assertThrows(AssertionError.class, () -> recovery.clearPendingRoutes(otherIdentity));
    }
    assertArrayEquals(pending, Files.readAllBytes(path));
    try (var recovery = new TlsTestToolLocalLock(path)) {
      recovery.acquire(IDENTITY);
      recovery.clearPendingRoutes(IDENTITY);
    }
    try (var ordinary = new TlsTestToolLocalLock(path)) {
      ordinary.acquire(null);
      assertEquals(0, Files.size(path));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"\u0001", "tls-routes-v1:test/testsuite-tls-tool/"})
  void legacyOrIncompleteMarkerCannotBeClaimedEvenByAManagedSession(String marker) throws Exception {
    var path = temporaryDirectory.resolve("shared.lock");
    Files.writeString(path, marker);
    try (var recovery = new TlsTestToolLocalLock(path)) {
      assertThrows(AssertionError.class, () -> recovery.acquire(IDENTITY));
    }
    assertEquals(marker, Files.readString(path));
    try (var ordinary = new TlsTestToolLocalLock(path)) {
      assertThrows(AssertionError.class, () -> ordinary.acquire(null));
    }
    assertEquals(marker, Files.readString(path));
  }

  private int runProbe(Path path) throws Exception {
    var executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var classPath = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
        "-cp", classPath, Probe.class.getName(), path.toString())
        .redirectErrorStream(true).redirectOutput(temporaryDirectory.resolve("probe.log").toFile()).start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Local lock probe did not terminate");
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  static class Probe {
    public static void main(String[] args) {
      try (var lock = new TlsTestToolLocalLock(Path.of(args[0]))) {
        lock.acquire(IDENTITY);
        lock.markRoutesPending(IDENTITY);
      }
    }
  }
}
