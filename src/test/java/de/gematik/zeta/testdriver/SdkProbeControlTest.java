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

package de.gematik.zeta.testdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SdkProbeControlTest {
  @TempDir Path directory;

  @Test
  @Timeout(10)
  void commandsRetainContextButNeverRunSubsequentActionsImplicitly() throws Exception {
    var operations = new ArrayList<String>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var process =
          executor.submit(
              () -> {
                SdkProbeControl.serve(
                    directory,
                    command -> {
                      operations.add(command);
                      return Map.of("count", Integer.toString(operations.size()), "last", command);
                    });
                return null;
              });
      try {
        assertEquals("ready", await("ready"));
        assertTrue(operations.isEmpty(), "Server startup must not invoke an SDK operation");
        for (int i = 0; i < 3; i++) {
          var command = java.util.List.of("create", "authenticate", "logout").get(i);
          SdkProbeControl.publish(directory.resolve("request-" + (i + 1)), command);
          var response = await("response-" + (i + 1));
          assertTrue(response.startsWith("OK\n"));
          assertTrue(response.contains("count=" + (i + 1) + "\n"));
          assertTrue(response.contains("last=" + command + "\n"));
        }
      } finally {
        Files.writeString(directory.resolve("stop"), "");
      }
      process.get(2, TimeUnit.SECONDS);
      assertEquals(java.util.List.of("create", "authenticate", "logout"), operations);
    }
  }

  @Test
  @Timeout(10)
  void operationFailureDoesNotExposeCredentialsOrPreventShutdown() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      var process =
          executor.submit(
              () -> {
                SdkProbeControl.serve(
                    directory,
                    command -> {
                      throw new IllegalArgumentException("secret-refresh-token");
                    });
                return null;
              });
      try {
        await("ready");
        SdkProbeControl.publish(directory.resolve("request-1"), "authenticate");
        var response = await("response-1");
        assertEquals("ERROR\nIllegalArgumentException", response);
        assertFalse(response.contains("secret-refresh-token"));
      } finally {
        Files.writeString(directory.resolve("stop"), "");
      }
      process.get(2, TimeUnit.SECONDS);
    }
  }

  private String await(String file) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    var path = directory.resolve(file);
    while (!Files.exists(path) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(path), "Missing SDK control file: " + file);
    return Files.readString(path);
  }
}
