/*-
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
package de.gematik.zeta.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for common file operations across the performance testing framework.
 */
@Slf4j
public final class FileUtils {

  private FileUtils() {
  }

  /**
   * Resolves a file path to an existing file.
   *
   * @param pathString input path (relative or absolute)
   * @return absolute, normalized existing path
   * @throws IOException if the file cannot be found
   */
  public static Path resolveExisting(String pathString) throws IOException {
    if (pathString == null || pathString.trim().isEmpty()) {
      throw new IllegalArgumentException("Path cannot be null or empty");
    }

    Path originalPath = Path.of(pathString.trim());

    // Try original path first
    if (Files.exists(originalPath)) {
      return originalPath.toAbsolutePath().normalize();
    }

    // Try with normalized separators (important for Windows backslashes)
    Path normalizedPath = Path.of(pathString.replace('\\', '/'));
    if (Files.exists(normalizedPath)) {
      return normalizedPath.toAbsolutePath().normalize();
    }

    // Search upward from current working directory
    Path workingDir = Path.of("").toAbsolutePath();
    for (Path current = workingDir; current != null; current = current.getParent()) {
      Path candidate = current.resolve(originalPath.toString());
      if (Files.exists(candidate)) {
        log.debug("Resolved '{}' to: {}", pathString, candidate);
        return candidate.toAbsolutePath().normalize();
      }
    }

    throw new IOException(String.format(
        "File not found: '%s' (searched from working directory: %s)",
        pathString, workingDir));
  }

  /**
   * Asserts that a file exists.
   *
   * @param path path to check
   * @throws AssertionError if the file does not exist
   */
  public static void requireFileExists(Path path) {
    if (!Files.exists(path)) {
      throw new AssertionError("Expected file not found: " + path.toAbsolutePath());
    }
  }

  /**
   * Ensures parent directories for a file path exist.
   *
   * @param filePath file path whose parents should exist
   * @throws IOException if directory creation fails
   */
  public static void ensureParentDirectories(Path filePath) throws IOException {
    Path parent = filePath.getParent();
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent);
      log.debug("Created directories: {}", parent);
    }
  }

  /**
   * Returns the filename without extension.
   *
   * @param path file path
   * @return base name without extension
   */
  public static String getNameWithoutExtension(Path path) {
    String filename = path.getFileName().toString();
    int lastDot = filename.lastIndexOf('.');
    return lastDot > 0 ? filename.substring(0, lastDot) : filename;
  }
}