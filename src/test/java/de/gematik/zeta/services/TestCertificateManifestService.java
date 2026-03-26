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
import de.gematik.zeta.perf.FileUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves and reads certificate assets from the sibling {@code zeta-test-certificates} repository.
 */
@Slf4j
public class TestCertificateManifestService {

  private static final Path DEFAULT_MANIFEST_RELATIVE_PATH = Path.of("manifest/cert-manifest.tsv");

  /**
   * One row from the certificate manifest, enriched with the repo root for path resolution.
   */
  public record TestCertificateEntry(
      String stem,
      Path repoRoot,
      String crtRelativePath,
      String prvRelativePath,
      String pubRelativePath,
      String keystoreB64RelativePath,
      String keystorePassword,
      String keystoreAlias,
      String storeType
  ) {

    /**
     * Resolve the certificate file path.
     */
    public Path crtPath() {
      return resolveRelative(crtRelativePath);
    }

    /**
     * Resolve the private key file path.
     */
    public Path prvPath() {
      return resolveRelative(prvRelativePath);
    }

    /**
     * Resolve the public key file path.
     */
    public Path pubPath() {
      return resolveRelative(pubRelativePath);
    }

    /**
     * Resolve the Base64 keystore file path.
     */
    public Path keystoreB64Path() {
      return resolveRelative(keystoreB64RelativePath);
    }

    /**
     * Read the certificate file and return it as standard Base64 text.
     */
    public String crtBase64() throws IOException {
      return Base64.getEncoder().encodeToString(Files.readAllBytes(crtPath()));
    }

    /**
     * Read the private key file and return it as standard Base64 text.
     */
    public String prvBase64() throws IOException {
      return Base64.getEncoder().encodeToString(Files.readAllBytes(prvPath()));
    }

    /**
     * Read the public key file and return it as standard Base64 text.
     */
    public String pubBase64() throws IOException {
      return Base64.getEncoder().encodeToString(Files.readAllBytes(pubPath()));
    }

    /**
     * Read the keystore Base64 payload as trimmed UTF-8 text.
     */
    public String keystoreB64Content() throws IOException {
      return Files.readString(keystoreB64Path(), StandardCharsets.UTF_8).trim();
    }

    /**
     * Resolve one manifest-relative path against the certificate repository root.
     *
     * @param relativePath manifest-relative path
     * @return normalized absolute path
     */
    private Path resolveRelative(String relativePath) {
      var path = Path.of(relativePath);
      if (path.isAbsolute()) {
        return path.toAbsolutePath().normalize();
      }
      return repoRoot.resolve(relativePath).toAbsolutePath().normalize();
    }
  }

  /**
   * Resolve the manifest path from an explicit value, the configured repository root, or the
   * sibling repo.
   *
   * @param explicitManifestPath optional explicit path
   * @return absolute manifest path
   * @throws IOException if the manifest cannot be found
   */
  public Path resolveManifestPath(String explicitManifestPath) throws IOException {
    if (explicitManifestPath != null && !explicitManifestPath.isBlank()) {
      return FileUtils.resolveExisting(explicitManifestPath);
    }

    var configuredRepoRoot = firstNonBlank(
        TigerGlobalConfiguration.readStringOptional("testCertificates.dir").orElse(null),
        System.getProperty("testCertificates.dir"),
        System.getenv("ZETA_TEST_CERTIFICATES_DIR"));
    if (configuredRepoRoot != null) {
      var repoRootPath = FileUtils.resolveExisting(configuredRepoRoot);
      var manifestPath = repoRootPath.resolve(DEFAULT_MANIFEST_RELATIVE_PATH);
      FileUtils.requireFileExists(manifestPath);
      return manifestPath.toAbsolutePath().normalize();
    }

    var discovered = discoverSiblingManifestPath();
    log.debug("Using discovered test certificate manifest: {}", discovered);
    return discovered;
  }

  /**
   * Find one entry by one-based index in the manifest.
   *
   * @param manifestPath path to the manifest
   * @param oneBasedIndex one-based row index excluding the header
   * @return manifest entry
   * @throws IOException on I/O errors
   */
  public TestCertificateEntry findByIndex(Path manifestPath, int oneBasedIndex) throws IOException {
    if (oneBasedIndex < 1) {
      throw new IllegalArgumentException("Manifest index must be >= 1");
    }

    try (Stream<String> lines = Files.lines(manifestPath, StandardCharsets.UTF_8)) {
      var iterator = lines.iterator();
      var header = readHeader(manifestPath, iterator);
      var current = 0;

      while (iterator.hasNext()) {
        var line = iterator.next();
        if (line.isBlank()) {
          continue;
        }
        current++;
        if (current == oneBasedIndex) {
          return parseEntry(line, header);
        }
      }
    }

    throw new IllegalArgumentException(
        "Manifest index out of range: " + oneBasedIndex + " in " + manifestPath);
  }

  /**
   * Find one entry by stem.
   *
   * @param manifestPath path to the manifest
   * @param stem stem to match
   * @return manifest entry
   * @throws IOException on I/O errors
   */
  public TestCertificateEntry findByStem(Path manifestPath, String stem) throws IOException {
    if (stem == null || stem.isBlank()) {
      throw new IllegalArgumentException("Stem must not be blank");
    }

    try (Stream<String> lines = Files.lines(manifestPath, StandardCharsets.UTF_8)) {
      var iterator = lines.iterator();
      var header = readHeader(manifestPath, iterator);

      while (iterator.hasNext()) {
        var line = iterator.next();
        if (line.isBlank()) {
          continue;
        }
        var values = splitValues(line, header);
        if (stem.equals(ManifestColumn.STEM.read(values, header.indexByColumn()))) {
          return parseEntry(values, header);
        }
      }
    }

    throw new IllegalArgumentException("Stem not found in manifest: " + stem);
  }

  /**
   * Find a one-based slice of manifest entries.
   *
   * @param manifestPath path to the manifest
   * @param startOneBased one-based start index excluding the header
   * @param count number of entries to read
   * @return manifest entries
   * @throws IOException on I/O errors
   */
  public List<TestCertificateEntry> findRange(Path manifestPath, int startOneBased, int count)
      throws IOException {
    if (startOneBased < 1) {
      throw new IllegalArgumentException("Start index must be >= 1");
    }
    if (count < 1) {
      throw new IllegalArgumentException("Count must be >= 1");
    }

    var entries = new ArrayList<TestCertificateEntry>(count);

    try (Stream<String> lines = Files.lines(manifestPath, StandardCharsets.UTF_8)) {
      var iterator = lines.iterator();
      var header = readHeader(manifestPath, iterator);
      var current = 0;

      while (iterator.hasNext()) {
        var line = iterator.next();
        if (line.isBlank()) {
          continue;
        }
        current++;
        if (current < startOneBased) {
          continue;
        }
        if (entries.size() >= count) {
          break;
        }

        entries.add(parseEntry(line, header));
      }
    }

    if (entries.size() < count) {
      throw new IllegalArgumentException(
          "Requested " + count + " entries from index " + startOneBased
              + ", but only " + entries.size() + " entries were available");
    }

    return List.copyOf(entries);
  }

  /**
   * Export a one-based slice of manifest entries as a TSV file for perf tooling such as JMeter.
   *
   * @param manifestPath path to the source manifest
   * @param startOneBased one-based start index excluding the header
   * @param count number of entries to export
   * @param outputPath output file path
   * @return written output path
   * @throws IOException on I/O errors
   */
  public Path exportTsvSlice(Path manifestPath, int startOneBased, int count, Path outputPath)
      throws IOException {
    FileUtils.ensureParentDirectories(outputPath);
    var entries = findRange(manifestPath, startOneBased, count);

    try (var writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
      writer.write(
          "stem\tcrt_path\tprv_path\tpub_path\tkeystore_b64_path\tkeystore_b64\tkeystore_password\tkeystore_alias\tstore_type");
      writer.newLine();

      for (var entry : entries) {
        writer.write(String.join("\t",
            entry.stem(),
            entry.crtPath().toString(),
            entry.prvPath().toString(),
            entry.pubPath().toString(),
            entry.keystoreB64Path().toString(),
            entry.keystoreB64Content(),
            entry.keystorePassword(),
            entry.keystoreAlias(),
            entry.storeType()));
        writer.newLine();
      }
    }

    return outputPath.toAbsolutePath().normalize();
  }

  /**
   * Search upwards from the current working directory for a direct or sibling manifest.
   *
   * @return discovered manifest path
   * @throws IOException if no manifest can be found
   */
  private Path discoverSiblingManifestPath() throws IOException {
    var workingDir = Path.of("").toAbsolutePath().normalize();

    for (var current = workingDir; current != null; current = current.getParent()) {
      var directCandidate = current.resolve(DEFAULT_MANIFEST_RELATIVE_PATH);
      if (Files.exists(directCandidate)) {
        return directCandidate.toAbsolutePath().normalize();
      }

      var siblingCandidate = current.resolve("zeta-test-certificates")
          .resolve(DEFAULT_MANIFEST_RELATIVE_PATH);
      if (Files.exists(siblingCandidate)) {
        return siblingCandidate.toAbsolutePath().normalize();
      }
    }

    throw new IOException(
        "Could not locate " + DEFAULT_MANIFEST_RELATIVE_PATH
            + ". Set testCertificates.dir or ZETA_TEST_CERTIFICATES_DIR.");
  }

  /**
   * Parse the header row and validate the required manifest columns.
   *
   * @param manifestPath manifest path for error messages
   * @param iterator line iterator positioned at the start of the file
   * @return parsed header metadata
   */
  private ManifestHeader readHeader(Path manifestPath, java.util.Iterator<String> iterator) {
    if (!iterator.hasNext()) {
      throw new IllegalArgumentException("Manifest is empty: " + manifestPath);
    }

    var headerLine = iterator.next();
    var headers = headerLine.split("\t", -1);
    var indexByColumn = new java.util.EnumMap<ManifestColumn, Integer>(ManifestColumn.class);
    for (var i = 0; i < headers.length; i++) {
      var column = ManifestColumn.fromHeader(headers[i]);
      if (column != null) {
        indexByColumn.put(column, i);
      }
    }

    for (var requiredColumn : ManifestColumn.values()) {
      if (!indexByColumn.containsKey(requiredColumn)) {
        throw new IllegalArgumentException(
            "Manifest " + manifestPath + " is missing required column: "
                + requiredColumn.headerName);
      }
    }

    return new ManifestHeader(manifestPath.toAbsolutePath().normalize(), indexByColumn);
  }

  /**
   * Parse one manifest data row into a typed entry.
   *
   * @param line raw TSV line
   * @param header parsed manifest header
   * @return typed manifest entry
   */
  private TestCertificateEntry parseEntry(String line, ManifestHeader header) throws IOException {
    return parseEntry(splitValues(line, header), header);
  }

  /**
   * Parse one manifest data row into a typed entry.
   *
   * @param values parsed TSV columns
   * @param header parsed manifest header
   * @return typed manifest entry
   * @throws IOException on I/O errors while resolving the repository root
   */
  private TestCertificateEntry parseEntry(String[] values, ManifestHeader header) throws IOException {
    var crtRelativePath = ManifestColumn.CRT.read(values, header.indexByColumn());
    var prvRelativePath = ManifestColumn.PRV.read(values, header.indexByColumn());
    var pubRelativePath = ManifestColumn.PUB.read(values, header.indexByColumn());
    var keystoreB64RelativePath = ManifestColumn.KEYSTORE_B64.read(values, header.indexByColumn());
    var repoRoot = resolveRepoRoot(header.manifestPath(), crtRelativePath, prvRelativePath,
        pubRelativePath, keystoreB64RelativePath);

    return new TestCertificateEntry(
        ManifestColumn.STEM.read(values, header.indexByColumn()),
        repoRoot,
        crtRelativePath,
        prvRelativePath,
        pubRelativePath,
        keystoreB64RelativePath,
        ManifestColumn.KEYSTORE_PASSWORD.read(values, header.indexByColumn()),
        ManifestColumn.KEYSTORE_ALIAS.read(values, header.indexByColumn()),
        ManifestColumn.STORE_TYPE.read(values, header.indexByColumn())
    );
  }

  /**
   * Split one manifest data row and pad missing trailing columns.
   *
   * @param line raw TSV line
   * @param header parsed manifest header
   * @return parsed row values
   */
  private String[] splitValues(String line, ManifestHeader header) {
    var values = line.split("\t", -1);
    return Arrays.copyOf(values, Math.max(values.length, header.indexByColumn().size()));
  }

  /**
   * Resolve the repository root for one manifest row by walking upward from the manifest location
   * until all referenced assets exist.
   *
   * @param manifestPath resolved manifest path
   * @param assetRelativePaths manifest asset paths
   * @return repository root used for relative asset resolution
   * @throws IOException if no matching root can be determined
   */
  private Path resolveRepoRoot(Path manifestPath, String... assetRelativePaths) throws IOException {
    for (var current = manifestPath.toAbsolutePath().normalize().getParent();
         current != null;
         current = current.getParent()) {
      if (allAssetsExist(current, assetRelativePaths)) {
        return current;
      }
    }

    throw new IOException(
        "Could not determine certificate repository root for manifest " + manifestPath
            + ". The manifest must stay inside the certificate repository layout.");
  }

  /**
   * Check whether all non-blank asset paths resolve successfully under one candidate root.
   *
   * @param candidateRoot candidate repository root
   * @param assetRelativePaths manifest asset paths
   * @return {@code true} when every referenced asset exists
   */
  private boolean allAssetsExist(Path candidateRoot, String... assetRelativePaths) {
    return Stream.of(assetRelativePaths)
        .filter(TestCertificateManifestService::isNotBlank)
        .map(Path::of)
        .allMatch(assetPath -> assetPath.isAbsolute()
            ? Files.exists(assetPath)
            : Files.exists(candidateRoot.resolve(assetPath).normalize()));
  }

  /**
   * Return the first non-blank value from the provided candidates.
   *
   * @param values candidate values
   * @return first non-blank value or {@code null}
   */
  private static String firstNonBlank(String... values) {
    return Stream.of(values)
        .filter(TestCertificateManifestService::isNotBlank)
        .findFirst()
        .orElse(null);
  }

  /**
   * Check whether a string is non-null and not blank.
   *
   * @param value value to inspect
   * @return {@code true} when the value has visible characters
   */
  private static boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * Parsed manifest metadata shared across row parsing.
   *
   * @param manifestPath resolved manifest path
   * @param indexByColumn resolved header indices
   */
  private record ManifestHeader(Path manifestPath, Map<ManifestColumn, Integer> indexByColumn) {
  }

  /**
   * Supported certificate manifest columns.
   */
  private enum ManifestColumn {
    STEM("stem"),
    CRT("crt"),
    PRV("prv"),
    PUB("pub"),
    KEYSTORE_B64("keystore_b64"),
    KEYSTORE_PASSWORD("keystore_password"),
    KEYSTORE_ALIAS("keystore_alias"),
    STORE_TYPE("store_type");

    private final String headerName;

    /**
     * Create one enum constant for a specific manifest header name.
     *
     * @param headerName canonical TSV header name
     */
    ManifestColumn(String headerName) {
      this.headerName = headerName;
    }

    /**
     * Resolve this column from a parsed TSV row.
     *
     * @param values row values
     * @param indexByColumn header index map
     * @return matching value or an empty string when absent
     */
    public String read(String[] values, Map<ManifestColumn, Integer> indexByColumn) {
      return Optional.ofNullable(indexByColumn.get(this))
          .filter(index -> index < values.length)
          .map(index -> values[index])
          .orElse("");
    }

    /**
     * Resolve one enum constant from a manifest header name.
     *
     * @param headerName raw header name
     * @return matching column or {@code null} for unknown headers
     */
    public static ManifestColumn fromHeader(String headerName) {
      return switch (headerName) {
        case "stem" -> STEM;
        case "crt" -> CRT;
        case "prv" -> PRV;
        case "pub" -> PUB;
        case "keystore_b64" -> KEYSTORE_B64;
        case "keystore_password" -> KEYSTORE_PASSWORD;
        case "keystore_alias" -> KEYSTORE_ALIAS;
        case "store_type" -> STORE_TYPE;
        default -> null;
      };
    }
  }
}
