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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Sequential commands for one isolated SDK JVM; contexts remain in memory between commands.
 */
public final class SdkProbeControl {
  private SdkProbeControl() {}

  /**
   * One public adapter operation, with only sanitized evidence returned to the caller.
   */
  @FunctionalInterface
  public interface Operation {
    /**
     * Executes one named operation and returns sanitized metadata without credentials.
     */
    Map<String, String> execute(String command) throws Exception;
  }

  /**
   * Serves ordered commands until stopped; the launching runtime enforces the process deadline.
   */
  public static void serve(Path directory, Operation operation) throws Exception {
    Files.writeString(directory.resolve("ready"), "ready", StandardCharsets.UTF_8);
    int sequence = 1;
    while (!Files.exists(directory.resolve("stop"))) {
      var request = directory.resolve("request-" + sequence);
      if (!Files.exists(request)) {
        Thread.sleep(50);
        continue;
      }
      String response;
      try {
        if (Files.size(request) > 128) {
          throw new AssertionError("Oversized SDK operation");
        }
        var command = Files.readString(request, StandardCharsets.UTF_8);
        if (!command.matches("[a-z][a-z_]{0,63}")) {
          throw new AssertionError("Invalid SDK operation");
        }
        response = "OK\n" + evidence(operation.execute(command));
      } catch (Exception | AssertionError failure) {
        // SDK exceptions may contain credentials. Publish only the exception class.
        response = "ERROR\n" + failure.getClass().getSimpleName();
      }
      publish(directory.resolve("response-" + sequence), response);
      Files.delete(request);
      sequence++;
    }
  }

  /**
   * Encodes only bounded, non-secret adapter metadata; probe-specific parsers check field names.
   */
  public static String evidence(Map<String, String> fields) {
    var result = new StringBuilder();
    fields.forEach(
        (key, value) -> {
          if (!key.matches("[a-z_0-9]+") || value == null || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new AssertionError("Unsafe SDK adapter evidence");
          }
          result.append(key).append('=').append(value).append('\n');
        });
    if (result.length() > 65536) {
      throw new AssertionError("Oversized SDK adapter evidence");
    }
    return result.toString();
  }

  /**
   * Publishes a complete command or response atomically inside the private probe directory.
   */
  public static void publish(Path target, String value) throws Exception {
    var temporary = Files.createTempFile(target.getParent(), "message-", ".tmp");
    try {
      Files.writeString(temporary, value, StandardCharsets.UTF_8);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
