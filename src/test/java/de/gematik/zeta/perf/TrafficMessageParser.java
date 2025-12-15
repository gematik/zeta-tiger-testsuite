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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses Tiger traffic files (.tgr) into structured TrafficMessage objects.
 */
@Slf4j
public class TrafficMessageParser {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String H_TRACE_ID = "x-trace-id";

  private static final Pattern REQUEST_LINE = Pattern.compile(
      "^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE|CONNECT)\\s+([^\\s]+)\\s+HTTP/\\d\\.\\d\\r?$",
      Pattern.MULTILINE);

  private static final Pattern HEADER_LINE = Pattern.compile(
      "^([!#$%&'*+\\-.^_`|~A-Za-z0-9]+):\\s*(.*)\\r?$",
      Pattern.MULTILINE);

  /**
   * Parses a .tgr file into a list of {@link TrafficMessage}.
   *
   * @param path     input file
   * @param gateRole "ingress" or "egress"
   * @return parsed messages (errors logged and skipped)
   * @throws IOException on I/O errors
   */
  public List<TrafficMessage> parseFile(Path path, String gateRole) throws IOException {
    var messages = new ArrayList<TrafficMessage>();
    var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    int parseErrors = 0;

    log.debug("Parsing {} lines from {}", lines.size(), path.getFileName());

    for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
      String raw = lines.get(lineNo);
      if (raw.trim().isEmpty()) {
        continue;
      }

      try {
        var message = parseMessage(raw.trim(), gateRole);
        messages.add(message);
      } catch (Exception ex) {
        parseErrors++;
        if (parseErrors <= 10) {
          log.warn("Parse error at {}:{} -> {}", path.getFileName(), lineNo + 1, ex.getMessage());
        }
      }
    }

    log.info("Parsed {} messages from {} ({} errors)", messages.size(), gateRole, parseErrors);
    return messages;
  }

  /**
   * Parses a single JSON line into a {@link TrafficMessage}.
   *
   * @param jsonLine raw JSON of one message
   * @param gateRole "ingress" or "egress"
   * @return populated TrafficMessage
   * @throws Exception if JSON or HTTP extraction fails
   */
  private TrafficMessage parseMessage(String jsonLine, String gateRole) throws Exception {
    var node = JSON.readTree(jsonLine);

    String uuid = getStringValue(node, "uuid");
    String pairedUuid = getStringValue(node, "pairedMessageUuid");
    long timestampMs = parseTimestampMillis(node);

    String httpRaw = getStringValue(node, "rawMessageContent");
    String httpContent = decodeIfBase64ElseRaw(httpRaw);

    boolean isRequest = isHttpRequest(httpContent);
    String requestPath = isRequest ? parseHttpPath(httpContent) : "";
    var headers = parseHttpHeaders(httpContent);
    String traceId = isRequest ? getHeaderIgnoreCase(headers, H_TRACE_ID) : null;

    return new TrafficMessage(gateRole, uuid, pairedUuid, isRequest,
        timestampMs, requestPath, headers, traceId);
  }

  /**
   * Heuristically checks if raw HTTP text looks like a request line.
   *
   * @param http raw HTTP text
   * @return true if it matches a request pattern
   */
  private boolean isHttpRequest(String http) {
    if (http == null || http.trim().isEmpty()) {
      return false;
    }
    return REQUEST_LINE.matcher(http).find()
        || http.trim().startsWith("GET ")
        || http.trim().startsWith("POST ");
  }

  /**
   * Extracts the HTTP request path from the request line.
   *
   * @param http raw HTTP text
   * @return path or empty string if not found
   */
  private String parseHttpPath(String http) {
    if (http == null) {
      return "";
    }
    Matcher matcher = REQUEST_LINE.matcher(http);
    return matcher.find() ? matcher.group(2) : "";
  }

  /**
   * Parses HTTP headers into a case-preserving map (first occurrence wins).
   *
   * @param http raw HTTP text
   * @return ordered map of headers
   */
  private Map<String, String> parseHttpHeaders(String http) {
    var headers = new LinkedHashMap<String, String>();
    if (http == null) {
      return headers;
    }

    Matcher matcher = HEADER_LINE.matcher(http);
    while (matcher.find()) {
      String name = matcher.group(1);
      String value = matcher.group(2);
      if (name != null && value != null) {
        headers.put(name.trim(), value.trim());
      }
    }
    return headers;
  }

  /**
   * Finds a header value by name (case-insensitive).
   *
   * @param headers    parsed headers
   * @param headerName header to lookup (lower/upper ignored)
   * @return value or null if missing/blank
   */
  private String getHeaderIgnoreCase(Map<String, String> headers, String headerName) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }

    for (var entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(headerName)) {
        String value = entry.getValue();
        return (value == null || value.isBlank()) ? null : value.trim();
      }
    }
    return null;
  }

  /**
   * If content looks like Base64, try to decode and return decoded HTTP; otherwise return the
   * original content.
   *
   * @param content raw or Base64 HTTP blob
   * @return decoded HTTP if detected, else original
   */
  private String decodeIfBase64ElseRaw(String content) {
    if (content == null) {
      return "";
    }
    String trimmed = content.trim();

    // If it looks like HTTP, return as-is
    if (Stream.of("GET ", "POST ", "HTTP/").anyMatch(trimmed::startsWith)) {
      return content;
    }

    // Try Base64 decode if it matches pattern
    if (trimmed.matches("^[A-Za-z0-9+/=\\r\\n]+$")) {
      try {
        byte[] decoded = Base64.getMimeDecoder().decode(trimmed);
        String decodedStr = new String(decoded, StandardCharsets.UTF_8);
        if (Stream.of("HTTP/", "GET ", "POST ").anyMatch(decodedStr::contains)) {
          return decodedStr;
        }
      } catch (IllegalArgumentException ignore) {
        // Not valid Base64, return original
      }
    }
    return content;
  }

  /**
   * Parses the "timestamp" field into epoch millis, falling back to now on errors. Accepts
   * ISO-8601, optionally with a trailing timezone region in brackets.
   *
   * @param node JSON object containing "timestamp"
   * @return epoch milliseconds
   */
  private long parseTimestampMillis(JsonNode node) {
    var timestamp = node.get("timestamp");
    if (timestamp == null || timestamp.isNull()) {
      return System.currentTimeMillis();
    }

    try {
      String timestampStr = timestamp.asText().trim();
      // Remove timezone name if present: [Europe/Berlin]
      if (timestampStr.contains("[")) {
        timestampStr = timestampStr.substring(0, timestampStr.indexOf('['));
      }
      return Instant.parse(timestampStr).toEpochMilli();
    } catch (Exception ex) {
      log.warn("Failed to parse timestamp: {} -> {}", timestamp.asText(), ex.getMessage());
      return System.currentTimeMillis();
    }
  }

  /**
   * Safely reads a string field from a JSON node.
   *
   * @param node  JSON object
   * @param field field name
   * @return string value or null
   */
  private String getStringValue(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  /**
   * TODO: javadoc.
   *
   * @param gateRole           TODO.
   * @param uuid               TODO.
   * @param pairedRequestsUuid TODO.
   * @param isRequest          TODO.
   * @param timestampMs        TODO.
   * @param path               TODO.
   * @param headers            TODO.
   * @param traceId            TODO.
   */
  public record TrafficMessage(String gateRole, String uuid, String pairedRequestsUuid,
                               boolean isRequest, long timestampMs, String path,
                               Map<String, String> headers, String traceId) {

    /**
     * Constructs a message, normalizing optional fields.
     *
     * @param gateRole           "ingress" or "egress"
     * @param uuid               message uuid
     * @param pairedRequestsUuid previous/paired uuid (nullable)
     * @param isRequest          true if request, false if response
     * @param timestampMs        epoch millis
     * @param path               request path (may be empty)
     * @param headers            HTTP headers (may be empty)
     * @param traceId            x-trace-id (nullable)
     */
    public TrafficMessage(String gateRole, String uuid, String pairedRequestsUuid,
        boolean isRequest,
        long timestampMs, String path, Map<String, String> headers, String traceId) {
      this.gateRole = gateRole;
      this.uuid = uuid;
      this.pairedRequestsUuid = emptyToNull(pairedRequestsUuid);
      this.isRequest = isRequest;
      this.timestampMs = timestampMs;
      this.path = path == null ? "" : path;
      this.headers = headers == null ? Collections.emptyMap() : headers;
      this.traceId = traceId;
    }

    /**
     * Converts empty strings to null.
     *
     * @param s input string
     * @return null if blank, else s
     */
    private static String emptyToNull(String s) {
      return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * True if this is a request that carries an x-trace-id.
     *
     * @return whether message originates from JMeter traffic
     */
    public boolean isJMeterTraffic() {
      return isRequest && traceId != null && !traceId.isBlank();
    }
  }
}
