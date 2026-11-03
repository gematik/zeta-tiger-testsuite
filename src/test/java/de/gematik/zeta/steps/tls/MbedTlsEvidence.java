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

package de.gematik.zeta.steps.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Protocol and application evidence from one TLS 1.2 connection of the bundled mbed TLS tool. */
public final class MbedTlsEvidence {
  private static final ObjectMapper JSON = new ObjectMapper();

  private MbedTlsEvidence() {}

  /** Extracts actual TCP peer, certificate messages and fully framed received HTTP data. */
  public static ObjectNode parse(String log, boolean server) throws Exception {
    require(log.contains("mbedTLS("), "Missing mbed TLS evidence");
    var connection =
        Pattern.compile(
                server
                    ? "TCP/IP connection from ([0-9.]+):[0-9]+ received\\."
                    : "TCP/IP connection to ([0-9.]+):[0-9]+ established\\.")
            .matcher(log);
    require(connection.find(), "Missing actual TCP peer");
    var row = JSON.createObjectNode().put("peer", connection.group(1));
    require(!connection.find(), "Expected exactly one TLS connection");
    boolean handshake = log.contains("Handshake successful.");
    if (handshake) {
      require(
          (server
                  ? log.contains("server hello, chosen version: [3:3]")
                      && log.contains("ServerHello message transmitted.")
                  : log.contains("ServerHello.server_version=03 03"))
              && !log.contains("ServerHello.server_version=03 04")
              && !log.contains("server hello, chosen version: [3:4]"),
          "Expected TLS 1.2");
      require(
          log.contains("ServerHello.cipher_suite=c0 2b")
              && log.lines()
                  .filter(line -> line.contains("ServerHello.cipher_suite="))
                  .allMatch(line -> line.endsWith("ServerHello.cipher_suite=c0 2b")),
          "Expected TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
    }
    String received = receivedCertificate(log);
    String sent = sentCertificate(log);
    row.put("handshake", handshake).put("tls_version", handshake ? "TLSv1.2" : "");
    row.put("cipher_suite", handshake ? "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" : "");
    row.put("client_sha", server ? received : sent);
    row.put("server_sha", server ? (handshake ? sent : "") : received);
    row.put("offered_server_sha", server ? sent : received);
    // Only local certificate-validation errors identify what this peer rejected.
    // A received fatal alert, cipher mismatch or transport error does not identify the cause.
    String reason = !handshake && server ? clientCertificateRejection(log) : "";
    row.put("failure", reason.isEmpty() ? "" : "TLS_AUTHENTICATION_FAILED");
    row.put("failure_reason", reason);
    row.put("failure_source", reason.isEmpty() ? "" : "gateway");
    row.put("request_base64", "").put("body_base64", "").put("status", 0);
    var data = applicationData(log);
    require(handshake || data.length == 0, "Application data without a completed TLS handshake");
    if (data.length != 0) {
      var input = new ByteArrayInputStream(data);
      var headers = TlsHttpMessage.readHeaders(input);
      var body = TlsHttpMessage.readBody(input, headers);
      require(input.available() == 0, "Extra bytes after the captured HTTP message");
      String line = headers.split("\r\n", 2)[0];
      row.put("request_base64", encode(line));
      row.put("body_base64", encode(body));
      if (!server) {
        require(line.matches("HTTP/1\\.[01] [0-9]{3} .*"), "Invalid captured HTTP response");
        row.put("status", Integer.parseInt(line.split(" ")[1]));
      }
    }
    return row;
  }

  private static String clientCertificateRejection(String log) {
    for (String line : log.lines().toList()) {
      if (line.contains("mbedtls_ssl_handshake_step failed: SSL - No client certificate received")
          || line.contains(
              "mbedtls_ssl_handshake_step failed: SSL - No client certification received")) {
        return "CLIENT_CERTIFICATE_MISSING";
      }
      if (line.contains("mbedtls_ssl_handshake_step failed: X509 - Certificate verification failed")) {
        return "CLIENT_CERTIFICATE_REJECTED";
      }
    }
    return "";
  }

  /** Reassembles every decrypted receive fragment; rejects malformed hex and oversized captures. */
  public static byte[] applicationData(String log) {
    var output = new ByteArrayOutputStream();
    for (String line : log.lines().toList()) {
      int start = line.indexOf("Application data received: ");
      if (start >= 0) {
        byte[] bytes = hex(line.substring(start + "Application data received: ".length()));
        require(output.size() + bytes.length <= 131072, "Oversized TLS application evidence");
        output.writeBytes(bytes);
      }
    }
    return output.toByteArray();
  }

  /** Matches the request against the plaintext output dump and the completed mbed TLS write. */
  public static void verifySentRequest(String log, String request) {
    var start = log.indexOf("Send TLS application data message 1 of 1.");
    var end = start < 0 ? -1 : log.indexOf("<= write", start);
    require(start >= 0 && end > start, "Missing completed TLS application write");
    var lines = log.substring(start, end).lines().toList();
    var payload = new ByteArrayOutputStream();
    for (int i = 0; i < lines.size(); i++) {
      var dumpHeader =
          Pattern.compile("dumping 'before encrypt: output payload' \\(([0-9]+) bytes\\)")
              .matcher(lines.get(i));
      if (!dumpHeader.find()) {
        continue;
      }
      int size = Integer.parseInt(dumpHeader.group(1));
      require(size <= 16384, "Oversized TLS application fragment");
      int offset = 0;
      while (offset < size && i + 1 < lines.size()) {
        var dump =
            Pattern.compile("mbedTLS\\([^)]*\\)\\s+([0-9a-f]{4}): {2}(.*)$").matcher(lines.get(++i));
        require(
            dump.find() && Integer.parseInt(dump.group(1), 16) == offset,
            "Incomplete transmitted application dump");
        int count = Math.min(16, size - offset);
        require(dump.group(2).length() >= count * 3 - 1, "Truncated transmitted application dump");
        payload.writeBytes(hex(dump.group(2).substring(0, count * 3 - 1)));
        offset += count;
      }
      require(offset == size, "Truncated transmitted application payload");
    }
    require(
        Arrays.equals(request.getBytes(StandardCharsets.UTF_8), payload.toByteArray()),
        "Actual TLS application request differs from the intended RS operation");
  }

  private static String receivedCertificate(String log) throws Exception {
    var matcher =
        Pattern.compile("(?m)^.*Certificate\\.certificate_list\\[0]=([^\\r\\n]*)$").matcher(log);
    if (!matcher.find()) {
      return "";
    }
    var result = fingerprint(hex(matcher.group(1)));
    require(!matcher.find(), "Multiple peer certificate messages");
    return result;
  }

  private static String sentCertificate(String log) throws Exception {
    var lines = log.lines().toList();
    String found = "";
    boolean certificateRecord = false;
    for (int i = 0; i < lines.size(); i++) {
      var header =
          Pattern.compile("dumping 'output record sent to network' \\(([0-9]+) bytes\\)")
              .matcher(lines.get(i));
      if (!header.find()) {
        continue;
      }
      int size = Integer.parseInt(header.group(1));
      require(size <= 18432, "Oversized TLS record");
      var record = new ByteArrayOutputStream();
      while (i + 1 < lines.size()) {
        var dump =
            Pattern.compile("mbedTLS\\([^)]*\\)\\s+([0-9a-f]{4}): {2}(.*)$")
                .matcher(lines.get(i + 1));
        if (!dump.find()) {
          break;
        }
        i++;
        require(Integer.parseInt(dump.group(1), 16) == record.size(), "Incomplete TLS record dump");
        int count = Math.min(16, size - record.size());
        require(count > 0 && dump.group(2).length() >= count * 3 - 1, "Invalid TLS record dump");
        record.writeBytes(hex(dump.group(2).substring(0, count * 3 - 1)));
      }
      byte[] bytes = record.toByteArray();
      require(bytes.length == size, "Truncated TLS record dump");
      if (size < 12 || bytes[0] != 22 || bytes[5] != 11) {
        continue;
      }
      require(
          !certificateRecord
              && bytes[1] == 3
              && bytes[2] == 3
              && ((bytes[3] & 255) * 256 + (bytes[4] & 255)) == size - 5
              && uint24(bytes, 6) == size - 9
              && uint24(bytes, 9) == size - 12,
          "Ambiguous or incomplete transmitted TLS certificate");
      certificateRecord = true;
      if (size > 12) {
        int leafSize = uint24(bytes, 12);
        require(leafSize > 0 && leafSize <= size - 15, "Invalid transmitted leaf certificate");
        found = fingerprint(Arrays.copyOfRange(bytes, 15, 15 + leafSize));
      }
    }
    require(
        !certificateRecord || log.contains("Certificate message transmitted."),
        "Certificate was constructed but not transmitted");
    return found;
  }

  private static int uint24(byte[] bytes, int at) {
    require(at + 3 <= bytes.length, "Incomplete TLS length");
    return (bytes[at] & 255) * 65536 + (bytes[at + 1] & 255) * 256 + (bytes[at + 2] & 255);
  }

  private static byte[] hex(String value) {
    var text = value.strip();
    require(text.matches("[0-9a-fA-F]{2}(?: [0-9a-fA-F]{2})*"), "Malformed TLS hex evidence");
    return HexFormat.of().parseHex(text.replace(" ", ""));
  }

  private static String fingerprint(byte[] der) throws Exception {
    java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(der));
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
  }

  private static String encode(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
