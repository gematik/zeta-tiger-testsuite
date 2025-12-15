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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts JMeter JTL files to summary CSV format.
 */
@Slf4j
public class JtlSummarizer {

  private static final String[] REQUIRED_HEADERS = {"timeStamp", "elapsed", "label", "success"};

  /**
   * Summarizes a JTL file into a compact per-label CSV.
   *
   * @param jtlFile     source JTL file
   * @param summaryFile target CSV (created/overwritten)
   * @throws IOException if reading or writing fails
   */
  public void summarize(Path jtlFile, Path summaryFile) throws IOException {
    FileUtils.requireFileExists(jtlFile);

    List<JtlRecord> records = parseJtlFile(jtlFile);
    if (records.isEmpty()) {
      log.warn("No valid records found in JTL file: {}", jtlFile);
      return;
    }

    Map<String, List<JtlRecord>> byLabel = records.stream()
        .collect(Collectors.groupingBy(JtlRecord::label));

    writeSummaryFile(byLabel, summaryFile);

    log.info("JTL summary created: {} -> {} ({} labels, {} total records)",
        jtlFile.getFileName(), summaryFile.getFileName(), byLabel.size(), records.size());
  }

  /**
   * Parses a JTL file (CSV/TSV) into records.
   *
   * @param jtlFile JTL input path
   * @return parsed records (may be empty)
   * @throws IOException if file read fails
   */
  private List<JtlRecord> parseJtlFile(Path jtlFile) throws IOException {
    List<String> lines = Files.readAllLines(jtlFile, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      return Collections.emptyList();
    }

    String separator = detectSeparator(lines.get(0));
    String[] headers = lines.get(0).split(separator, -1);

    int[] columnIndices = findRequiredColumns(headers);

    List<JtlRecord> records = new ArrayList<>();
    int parseErrors = 0;

    for (int i = 1; i < lines.size(); i++) {
      try {
        String[] values = lines.get(i).split(separator, -1);
        if (values.length <= Arrays.stream(columnIndices).max().orElse(-1)) {
          continue;
        }

        long timestamp = Long.parseLong(values[columnIndices[0]].trim());
        long elapsed = Long.parseLong(values[columnIndices[1]].trim());
        String label = values[columnIndices[2]].trim();
        boolean success = "true".equalsIgnoreCase(values[columnIndices[3]].trim());

        records.add(new JtlRecord(timestamp, elapsed, label, success));

      } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
        parseErrors++;
        if (parseErrors <= 5) { // Log only first 5 errors
          log.debug("Skipping malformed JTL line {}: {}", i + 1, e.getMessage());
        }
      }
    }

    if (parseErrors > 0) {
      log.info("Parsed {} valid records from JTL file ({} parsing errors)", records.size(),
          parseErrors);
    }

    return records;
  }

  /**
   * Finds indices for required headers (timeStamp, elapsed, label, success).
   *
   * @param headers header row tokens
   * @return indices aligned to REQUIRED_HEADERS
   */
  private int[] findRequiredColumns(String[] headers) {
    int[] indices = new int[REQUIRED_HEADERS.length];

    for (int i = 0; i < REQUIRED_HEADERS.length; i++) {
      indices[i] = -1;
      for (int j = 0; j < headers.length; j++) {
        if (REQUIRED_HEADERS[i].equalsIgnoreCase(headers[j].trim())) {
          indices[i] = j;
          break;
        }
      }
      if (indices[i] == -1) {
        throw new IllegalArgumentException(
            "Missing required JTL column: " + REQUIRED_HEADERS[i]
                + ". Found: " + Arrays.toString(headers));
      }
    }

    return indices;
  }

  /**
   * Writes per-label summary rows to CSV.
   *
   * @param recordsByLabel map label -> records
   * @param summaryFile    output CSV
   * @throws IOException if write fails
   */
  private void writeSummaryFile(Map<String, List<JtlRecord>> recordsByLabel, Path summaryFile)
      throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add("label,count,errorRate,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,rps");

    for (Map.Entry<String, List<JtlRecord>> entry : recordsByLabel.entrySet()) {
      String label = entry.getKey();
      List<JtlRecord> records = new ArrayList<>(entry.getValue());
      records.sort(Comparator.comparingLong(JtlRecord::elapsed));

      SummaryStats stats = calculateStats(records);
      lines.add(formatSummaryLine(label, stats));
    }

    FileUtils.ensureParentDirectories(summaryFile);
    Files.write(summaryFile, lines, StandardCharsets.UTF_8);
  }

  /**
   * Formats one CSV summary line.
   *
   * @param label label name
   * @param stats summary stats
   * @return CSV row as string
   */
  private String formatSummaryLine(String label, SummaryStats stats) {
    return String.format(Locale.ROOT,
        "%s,%d,%.6f,%.0f,%d,%d,%d,%d,%.1f",
        escapeCsvValue(label),
        stats.count(),
        stats.errorRate(),
        stats.avgMs(),
        stats.p50Ms(),
        stats.p95Ms(),
        stats.p99Ms(),
        stats.maxMs(),
        stats.rps()
    );
  }

  /**
   * Computes summary statistics for a label.
   *
   * @param records label records
   * @return aggregated stats
   */
  private SummaryStats calculateStats(List<JtlRecord> records) {
    if (records.isEmpty()) {
      return new SummaryStats(0, 0.0, 0.0, 0, 0, 0, 0, 0.0);
    }

    int count = records.size();
    long errors = records.stream().mapToLong(r -> r.success() ? 0 : 1).sum();
    double errorRate = (double) errors / count;

    double avgMs = records.stream().mapToLong(JtlRecord::elapsed).average().orElse(0.0);

    long p50 = percentile(records, 0.50);
    long p95 = percentile(records, 0.95);
    long p99 = percentile(records, 0.99);

    long maxMs = records.stream().mapToLong(JtlRecord::elapsed).max().orElse(0L);

    double rps = calculateRps(records);

    return new SummaryStats(count, errorRate, avgMs, p50, p95, p99, maxMs, rps);
  }

  /**
   * Estimates requests-per-second over the record time span.
   *
   * @param records label records
   * @return RPS value
   */
  private double calculateRps(List<JtlRecord> records) {
    if (records.size() < 2) {
      return records.size();
    }

    long firstTimestamp = records.stream().mapToLong(JtlRecord::timestamp).min().orElse(0);
    long lastTimestamp = records.stream().mapToLong(JtlRecord::timestamp).max().orElse(0);

    return (lastTimestamp > firstTimestamp)
        ? (records.size() / ((lastTimestamp - firstTimestamp) / 1000.0)) : records.size();
  }

  /**
   * Returns the elapsed percentile (records must be sorted by elapsed asc).
   *
   * @param sortedRecords records sorted by elapsed
   * @param percentile    fraction (e.g., 0.95)
   * @return elapsed at percentile
   */
  private long percentile(List<JtlRecord> sortedRecords, double percentile) {
    if (sortedRecords.isEmpty()) {
      return 0;
    }

    int index = Math.min(
        (int) Math.ceil(percentile * sortedRecords.size()) - 1,
        sortedRecords.size() - 1);

    return sortedRecords.get(index).elapsed();
  }

  /**
   * Detects column separator from header line.
   *
   * @param headerLine first line of JTL
   * @return separator ("," ";" or "\t")
   */
  private String detectSeparator(String headerLine) {
    if (headerLine.contains(",")) {
      return ",";
    }
    if (headerLine.contains(";")) {
      return ";";
    }
    if (headerLine.contains("\t")) {
      return "\t";
    }
    return ",";
  }


  /**
   * Escapes label values for CSV output.
   *
   * @param value label value
   * @return safe CSV token
   */
  private String escapeCsvValue(String value) {
    return value == null ? "" : value.replace(",", " ").replace("\n", " ").replace("\r", " ");
  }

  private record JtlRecord(long timestamp, long elapsed, String label, boolean success) {

  }

  private record SummaryStats(int count, double errorRate, double avgMs, long p50Ms, long p95Ms,
                              long p99Ms, long maxMs, double rps) {

  }
}
