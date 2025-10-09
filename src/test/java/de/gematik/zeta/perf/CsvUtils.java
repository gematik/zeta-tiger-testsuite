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

import de.gematik.zeta.perf.TigerTraceAnalyzer.FlowTiming;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/**
 * Unified CSV utility for reading, parsing, and analyzing CSV files.
 */
@Slf4j
public final class CsvUtils {

  private CsvUtils() {
  }

  // Legacy column mappings for Tiger metrics
  private static final Map<String, String> TIGER_LEGACY_COLUMNS = Map.of(
      "pep_forward_ms", "forward_ms",
      "pep_return_ms", "return_ms",
      "pep_processing_ms", "middleware_overhead_ms",
      "backend_ms", "service_ms",
      "end_to_end_ms", "e2e_ms"
  );

  /**
   * Reads a CSV file (with header) into {@link CsvData}.
   *
   * @param csvFile path to CSV
   * @return parsed CSV data
   * @throws IOException if file is missing or unreadable
   */
  public static CsvData readCsv(Path csvFile) throws IOException {
    if (!Files.exists(csvFile)) {
      throw new IOException("CSV file not found: " + csvFile.toAbsolutePath());
    }

    try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8);
        CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader)) {

      List<String> headers = new ArrayList<>(parser.getHeaderNames());
      List<CsvRow> rows = new ArrayList<>();

      for (CSVRecord record : parser) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
          values.add(record.size() > i ? record.get(i) : "");
        }
        rows.add(new CsvRow(headers, values));
      }

      log.debug("Read CSV: {} headers, {} rows from {}", headers.size(), rows.size(),
          csvFile.getFileName());
      return new CsvData(headers, rows);
    }
  }

  /**
   * Reads specific numeric columns as arrays of doubles. Applies legacy column name resolution.
   *
   * @param csvFile     CSV path
   * @param columnNames requested columns
   * @return map of requested (original) column name to values
   * @throws IOException on read/parse errors
   */
  public static Map<String, double[]> readNumericColumns(Path csvFile, String... columnNames)
      throws IOException {
    CsvData csv = readCsv(csvFile);
    Map<String, double[]> result = new HashMap<>();

    for (String columnName : columnNames) {
      String effectiveColumn = resolveColumn(columnName, csv.headers());
      double[] values = csv.getColumnAsDoubles(effectiveColumn);
      result.put(columnName, values);
    }

    return result;
  }

  /**
   * Computes a linear-interpolated percentile in [0..1].
   *
   * @param values     sample values
   * @param percentile percentile as fraction (e.g., 0.95)
   * @return percentile value or NaN if no data
   */
  public static double percentile(double[] values, double percentile) {
    if (values == null || values.length == 0) {
      return Double.NaN;
    }

    double[] sorted = Arrays.copyOf(values, values.length);
    Arrays.sort(sorted);

    double rank = percentile * (sorted.length - 1);
    int lower = (int) Math.floor(rank);
    int upper = (int) Math.ceil(rank);

    if (lower == upper) {
      return sorted[lower];
    }

    double weight = rank - lower;
    return sorted[lower] * (1 - weight) + sorted[upper] * weight;
  }

  /**
   * Resolves a column name using legacy Tiger mappings if needed.
   *
   * @param requested requested column
   * @param headers   available headers
   * @return effective column name to use
   */
  public static String resolveColumn(String requested, List<String> headers) {
    if (headers.contains(requested)) {
      return requested;
    }

    String mapped = TIGER_LEGACY_COLUMNS.get(requested);
    if (mapped != null && headers.contains(mapped)) {
      return mapped;
    }

    return requested; // Return original if no mapping found
  }

  /**
   * Resolves a JMeter summary label via exact/ci/stripped/startsWith/contains matching.
   *
   * @param availableLabels labels from CSV
   * @param requested       requested label
   * @return matching label or null if none/ambiguous
   */
  public static String resolveSummaryLabel(Collection<String> availableLabels, String requested) {
    if (availableLabels == null || availableLabels.isEmpty() || requested == null) {
      return null;
    }

    String req = requested.trim();
    if (req.isEmpty()) {
      return null;
    }

    // 1. Exact match
    if (availableLabels.contains(req)) {
      return req;
    }

    // 2. Case-insensitive match
    for (String label : availableLabels) {
      if (label != null && label.equalsIgnoreCase(req)) {
        return label;
      }
    }

    // 3. Strip parentheses and match
    Pattern stripParens = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
    String reqNoParens = stripParens.matcher(req).replaceAll("");

    List<String> candidates = availableLabels.stream()
        .filter(Objects::nonNull)
        .filter(label -> {
          String labelNoParens = stripParens.matcher(label).replaceAll("");
          return labelNoParens.equalsIgnoreCase(reqNoParens);
        })
        .collect(Collectors.toList());

    if (candidates.size() == 1) {
      return candidates.get(0);
    }

    // 4. Starts with (case-insensitive)
    candidates = availableLabels.stream()
        .filter(Objects::nonNull)
        .filter(label -> label.toLowerCase().startsWith(req.toLowerCase()))
        .collect(Collectors.toList());

    if (candidates.size() == 1) {
      return candidates.get(0);
    }

    // 5. Contains (case-insensitive)
    candidates = availableLabels.stream()
        .filter(Objects::nonNull)
        .filter(label -> label.toLowerCase().contains(reqNoParens.toLowerCase()))
        .collect(Collectors.toList());

    if (candidates.size() == 1) {
      return candidates.get(0);
    }

    return null;
  }

  /**
   * Writes a list of flow timings to CSV with a fixed header.
   *
   * @param timings    flow timing records
   * @param outputFile CSV target path
   * @throws IOException on write errors
   */
  public static void writeFlowTimings(List<FlowTiming> timings, Path outputFile)
      throws IOException {
    FileUtils.ensureParentDirectories(outputFile);

    try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8);
        CSVPrinter printer = CSVFormat.DEFAULT.withHeader(
            "trace_id", "path", "e2e_ms", "service_ms", "middleware_overhead_ms",
            "ingress_request_ms", "ingress_response_ms", "egress_request_ms", "egress_response_ms",
            "forward_ms", "return_ms"
        ).print(writer)) {

      for (FlowTiming timing : timings) {
        printer.printRecord(
            timing.traceId(),
            timing.path(),
            String.format(Locale.ROOT, "%.3f", timing.getEndToEndMs()),
            String.format(Locale.ROOT, "%.3f", timing.getServiceMs()),
            String.format(Locale.ROOT, "%.3f", timing.getMiddlewareOverheadMs()),
            timing.ingressRequestMs(),
            timing.ingressResponseMs(),
            timing.egressRequestMs(),
            timing.egressResponseMs(),
            String.format(Locale.ROOT, "%.3f", timing.getForwardMs()),
            String.format(Locale.ROOT, "%.3f", timing.getReturnMs())
        );
      }

      log.debug("Wrote {} flow timings to {}", timings.size(), outputFile.getFileName());
    }
  }

  /**
   * Parses a string as double; returns NaN for null/empty/invalid values.
   *
   * @param value text to parse
   * @return parsed double or NaN
   */
  private static double parseDoubleOrNaN(String value) {
    if (value == null || value.trim().isEmpty()) {
      return Double.NaN;
    }

    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /**
     * Represents a CSV dataset with headers and rows.
     */
    public record CsvData(List<String> headers, List<CsvRow> rows) {

      /**
       * Returns all values from a column as strings.
       *
       * @param columnName column to read
       * @return list of values (missing cells as null filtered out)
       */
      public List<String> getColumn(String columnName) {
        return rows.stream()
            .map(row -> row.get(columnName))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
      }

      /**
       * Returns a column parsed as doubles, excluding NaN.
       *
       * @param columnName column to read
       * @return array of doubles
       */
      public double[] getColumnAsDoubles(String columnName) {
        return rows.stream()
            .map(row -> row.get(columnName))
            .filter(Objects::nonNull)
            .mapToDouble(CsvUtils::parseDoubleOrNaN)
            .filter(d -> !Double.isNaN(d))
            .toArray();
      }

      /**
       * Finds the first row where {@code columnName} equals {@code value}.
       *
       * @param columnName column to compare
       * @param value      expected value
       * @return matching row or null
       */
      public CsvRow findRow(String columnName, String value) {
        return rows.stream()
            .filter(row -> Objects.equals(row.get(columnName), value))
            .findFirst()
            .orElse(null);
      }

    }

  /**
     * Represents a single CSV row.
     */
    public record CsvRow(List<String> headers, List<String> values) {

      /**
       * Returns the cell by column name.
       *
       * @param columnName column to read
       * @return cell value or null
       */
      public String get(String columnName) {
        int index = headers.indexOf(columnName);
        return get(index);
      }

      /**
       * Returns the cell by index.
       *
       * @param index zero-based index
       * @return cell value or null
       */
      public String get(int index) {

        return (index >= 0 && index < values.size()) ? values.get(index) : null;
      }
    }
}