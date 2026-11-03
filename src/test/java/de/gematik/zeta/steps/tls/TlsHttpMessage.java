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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Strict HTTP framing shared by TLS application-evidence readers. */
final class TlsHttpMessage {
  private TlsHttpMessage() {}

  static String readHeaders(InputStream input) throws Exception {
    var bytes = new ByteArrayOutputStream();
    int suffix = 0;
    while (bytes.size() < 16384) {
      int next = input.read();
      if (next < 0) {
        throw new java.io.EOFException("Incomplete HTTP headers");
      }
      bytes.write(next);
      suffix = (suffix << 8) | next;
      if (suffix == 0x0d0a0d0a) {
        return bytes.toString(StandardCharsets.US_ASCII);
      }
    }
    throw new IllegalArgumentException("Oversized HTTP headers");
  }

  static String readBody(InputStream input, String headers) throws Exception {
    var length = Pattern.compile("(?im)^Content-Length: *([0-9]+)\\r?$").matcher(headers);
    var transfer = Pattern.compile("(?im)^Transfer-Encoding: *([^\\r\\n]+)\\r?$").matcher(headers);
    boolean chunked = transfer.find();
    if (chunked && (!"chunked".equalsIgnoreCase(transfer.group(1)) || transfer.find())) {
      throw new IllegalArgumentException("Unsupported or ambiguous transfer encoding");
    }
    var output = new ByteArrayOutputStream();
    if (length.find()) {
      int size = Integer.parseInt(length.group(1));
      if (size > 65536 || length.find() || chunked) {
        throw new IllegalArgumentException("Ambiguous/oversized HTTP body");
      }
      var bytes = input.readNBytes(size);
      if (bytes.length != size) {
        throw new java.io.EOFException("Incomplete HTTP body");
      }
      output.write(bytes);
    } else if (chunked) {
      while (true) {
        String line = readLine(input);
        int size = Integer.parseInt(line.split(";", 2)[0], 16);
        if (size < 0 || size > 65536 - output.size()) {
          throw new IllegalArgumentException("Oversized chunked body");
        }
        if (size == 0) {
          int trailers = 0;
          while (!readLine(input).isEmpty()) {
            if (++trailers > 16) {
              throw new IllegalArgumentException("Oversized HTTP trailers");
            }
          }
          break;
        }
        var bytes = input.readNBytes(size);
        if (bytes.length != size || !readLine(input).isEmpty()) {
          throw new java.io.EOFException("Incomplete chunk");
        }
        output.write(bytes);
      }
    } else {
      throw new IllegalArgumentException("Missing HTTP body framing");
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private static String readLine(InputStream input) throws Exception {
    var bytes = new ByteArrayOutputStream();
    while (bytes.size() < 1024) {
      int value = input.read();
      if (value < 0) {
        throw new java.io.EOFException("Incomplete HTTP line");
      }
      bytes.write(value);
      if (value == '\n') {
        var line = bytes.toString(StandardCharsets.US_ASCII);
        if (!line.endsWith("\r\n")) {
          throw new IllegalArgumentException("Invalid HTTP line ending");
        }
        return line.substring(0, line.length() - 2);
      }
    }
    throw new IllegalArgumentException("Oversized HTTP line");
  }
}
