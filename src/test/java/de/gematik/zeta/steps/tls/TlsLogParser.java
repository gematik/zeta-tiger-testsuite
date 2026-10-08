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

import de.gematik.zeta.model.tls.TlsCipherSuite;
import de.gematik.zeta.model.tls.TlsEndpointRole;
import de.gematik.zeta.model.tls.TlsHashAlgorithm;
import de.gematik.zeta.model.tls.TlsSignatureAlgorithm;
import de.gematik.zeta.model.tls.TlsSignatureSchemes;
import de.gematik.zeta.model.tls.TlsSupportedGroup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.assertj.core.api.Assertions;

/**
 * Parser and formatter for the textual logs emitted by the TLS test tool.
 *
 * <p>The tls-test-tool logs are the only protocol transcript available to the Cucumber steps.
 * Keeping the parsing code here keeps {@code TlsTestToolSteps} focused on scenario orchestration and assertion intent.</p>
 */
public final class TlsLogParser {

  /**
   * TLS extension type: supported_groups.
   */
  public static final int TLS_EXTENSION_SUPPORTED_GROUPS = 0x000A;

  /**
   * TLS extension type: signature_algorithms.
   */
  public static final int TLS_EXTENSION_SIGNATURE_ALGORITHMS = 0x000D;

  /**
   * TLS extension type: supported_versions.
   */
  public static final int TLS_EXTENSION_SUPPORTED_VERSIONS = 0x002B;

  /**
   * TLS extension type: key_share.
   */
  public static final int TLS_EXTENSION_KEY_SHARE = 0x0033;

  /**
   * TLS extension type: renegotiation_info.
   */
  public static final int TLS_EXTENSION_RENEGOTIATION_INFO = 0xFF01;

  private static final String RENEG_MARKER = "Performing renegotiation.";
  private static final String FINISHED_MARKER = "Valid Finished message received.";
  private static final Pattern ALERT_LEVEL_PATTERN = Pattern.compile("Alert\\.level=([0-9a-fA-F]+)");
  private static final Pattern ALERT_DESCRIPTION_PATTERN = Pattern.compile("Alert\\.description=([0-9a-fA-F]+)");
  private static final Pattern TLS_HANDSHAKE_FAILED_PATTERN = Pattern.compile("TLS handshake failed:.*");
  private static final Pattern TLS_CIPHER_SUITE_PATTERN = Pattern.compile("Cipher suite:\\s*(.+)");
  private static final Pattern TLS_HASH_ALGORITHM_PATTERN = Pattern.compile("Server used HashAlgorithm\\s+(\\d+)");
  private static final Pattern TLS_SIGNATURE_ALGORITHM_PATTERN = Pattern.compile("Server used SignatureAlgorithm\\s+(\\d+)");
  private static final Pattern TLS_CERTIFICATE_VERIFY_ALGORITHM_PATTERN =
      Pattern.compile("CertificateVerify\\.algorithm=([0-9a-fA-F]{2})\\s+([0-9a-fA-F]{2})");
  private static final Pattern TLS_SERVER_HELLO_CIPHER_SUITE_PATTERN =
      Pattern.compile("ServerHello\\.cipher_suite=([0-9a-fA-F]{2})\\s+([0-9a-fA-F]{2})");
  private static final Pattern TLS_SERVER_HELLO_KEY_SHARE_GROUP_PATTERN =
      Pattern.compile("ServerHello\\.extensions=.*\\b00\\s+33\\s+[0-9a-fA-F]{2}\\s+[0-9a-fA-F]{2}\\s+([0-9a-fA-F]{2})\\s+([0-9a-fA-F]{2})");
  private static final Pattern TLS_RENEGOTIATION_PHASE_PATTERN =
      Pattern.compile("=>\\s*renegotiate|Performing renegotiation\\.");
  private static final Pattern TLS_CLIENT_HELLO_SENT_PATTERN = Pattern.compile("ClientHello message transmitted\\.");
  private static final Pattern TLS_CLIENT_HELLO_WRITE_PATTERN = Pattern.compile("=>\\s*write client hello");
  private static final Pattern TCP_IP_CONNECTION_FAILED_PATTERN =
      Pattern.compile("(?m)\\bTCP/IP connection to .+ failed:.*");
  private static final Pattern SERVER_HELLO_EXTENSIONS_PATTERN =
      Pattern.compile("ServerHello\\.extensions\\s*=\\s*([0-9a-fA-F]{2}(?:\\s+[0-9a-fA-F]{2})*)?");
  private static final Pattern CLIENT_HELLO_EXTENSIONS_OPTIONAL_PATTERN =
      Pattern.compile("(?m)^.*ClientHello\\.extensions\\s*=\\s*([0-9a-fA-F]{2}(?:[ \\t]+[0-9a-fA-F]{2})*)?[ \\t]*$");
  private static final Pattern CLIENT_HELLO_EXTENSIONS_LINE_PATTERN =
      Pattern.compile("ClientHello\\.extensions\\s*=\\s*([0-9a-fA-F]{2}(?:[ \\t]+[0-9a-fA-F]{2})*)?[ \\t]*$");
  private static final Pattern CLIENT_HELLO_CIPHER_SUITES_PATTERN =
      Pattern.compile("(?m)^.*ClientHello\\.cipher_suites=([0-9a-fA-F]{2}(?:[ \\t]+[0-9a-fA-F]{2})*)[ \\t]*$");

  /**
   * Utility class.
   */
  private TlsLogParser() {
  }

  /**
   * Checks whether a TLS log phase contains the given alert description paired with a fatal alert level.
   *
   * @param logPhase      TLS log phase to inspect
   * @param descriptionId expected alert description id
   * @return {@code true} if the description appears after {@code Alert.level=02}
   */
  public static boolean hasFatalAlertDescription(String logPhase, String descriptionId) {
    return hasAlertWithLevelAndDescription(logPhase, "02", descriptionId);
  }

  /**
   * Checks whether a TLS log phase contains the given alert description paired with the given alert level.
   *
   * @param logPhase      TLS log phase to inspect
   * @param levelId       expected alert level id
   * @param descriptionId expected alert description id
   * @return {@code true} if the description appears after the expected alert level
   */
  public static boolean hasAlertWithLevelAndDescription(String logPhase, String levelId, String descriptionId) {
    var currentAlertLevel = (String) null;
    for (var line : logPhase.split("\\R")) {
      var levelMatcher = ALERT_LEVEL_PATTERN.matcher(line);
      if (levelMatcher.find()) {
        currentAlertLevel = levelMatcher.group(1);
      }
      var descriptionMatcher = ALERT_DESCRIPTION_PATTERN.matcher(line);
      if (descriptionMatcher.find() && descriptionId.equalsIgnoreCase(descriptionMatcher.group(1))) {
        if (levelId.equalsIgnoreCase(currentAlertLevel)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Checks whether a TLS log phase contains the given alert level.
   *
   * @param logPhase TLS log phase to inspect
   * @param levelId  expected alert level id
   * @return {@code true} if the level is present
   */
  public static boolean hasAlertLevel(String logPhase, String levelId) {
    return ALERT_LEVEL_PATTERN.matcher(logPhase).results()
        .anyMatch(match -> levelId.equalsIgnoreCase(match.group(1)));
  }

  /**
   * Checks whether a TLS log phase contains the given alert description.
   *
   * @param logPhase      TLS log phase to inspect
   * @param descriptionId expected alert description id
   * @return {@code true} if the description is present
   */
  public static boolean hasAlertDescription(String logPhase, String descriptionId) {
    return ALERT_DESCRIPTION_PATTERN.matcher(logPhase).results()
        .anyMatch(match -> descriptionId.equalsIgnoreCase(match.group(1)));
  }

  /**
   * Validates that a successful renegotiation binds the new handshake to the previous Finished verify_data values as required by RFC 5746.
   *
   * @param fullLog complete TLS log output
   */
  public static void assertSecureRenegotiationBinding(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    int renegotiationIndex = fullLog.indexOf(RENEG_MARKER);
    if (renegotiationIndex < 0) {
      throw new AssertionError("No TLS renegotiation phase was found in the TLS logs.");
    }

    var initialHandshakePhase = fullLog.substring(0, renegotiationIndex);
    var renegotiationPhase = fullLog.substring(renegotiationIndex);
    var tlsTestToolRole = determineTlsTestToolRole(fullLog);

    var previousClientFinished = extractFinishedVerifyData(initialHandshakePhase, tlsTestToolRole, TlsEndpointRole.CLIENT);
    var previousServerFinished = extractFinishedVerifyData(initialHandshakePhase, tlsTestToolRole, TlsEndpointRole.SERVER);

    var renegotiatedClientHelloExtensions = extractHelloExtensions(renegotiationPhase, TlsEndpointRole.CLIENT);
    var renegotiatedServerHelloExtensions = extractHelloExtensions(renegotiationPhase, TlsEndpointRole.SERVER);

    var actualClientRenegotiationInfo = findExtensionData(renegotiatedClientHelloExtensions, TLS_EXTENSION_RENEGOTIATION_INFO);
    var expectedClientRenegotiationInfo = buildRenegotiationInfoPayload(previousClientFinished);
    Assertions
        .assertThat(actualClientRenegotiationInfo)
        .withFailMessage(
            "The renegotiated ClientHello renegotiation_info is missing or malformed. Expected %s but found %s.",
            toHex(expectedClientRenegotiationInfo),
            toHex(actualClientRenegotiationInfo))
        .isEqualTo(expectedClientRenegotiationInfo);

    var actualServerRenegotiationInfo = findExtensionData(renegotiatedServerHelloExtensions, TLS_EXTENSION_RENEGOTIATION_INFO);
    var expectedServerRenegotiationInfo =
        buildRenegotiationInfoPayload(previousClientFinished, previousServerFinished);
    Assertions
        .assertThat(actualServerRenegotiationInfo)
        .withFailMessage(
            "The renegotiated ServerHello renegotiation_info is missing or malformed. Expected %s but found %s.",
            toHex(expectedServerRenegotiationInfo),
            toHex(actualServerRenegotiationInfo))
        .isEqualTo(expectedServerRenegotiationInfo);
  }

  /**
   * Extracts a compact TCP/IP connection diagnostic from raw TLS tool logs.
   *
   * @param fullLog complete TLS log output
   * @return connection diagnostic for assertion messages
   */
  public static String extractTcpIpConnectionSummary(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      return "TLS logs are empty.";
    }
    var failedConnectionMatcher = TCP_IP_CONNECTION_FAILED_PATTERN.matcher(fullLog);
    if (failedConnectionMatcher.find()) {
      return "connection_failure=" + failedConnectionMatcher.group(0).trim();
    }
    return "No TCP/IP connection status line was found.";
  }

  /**
   * Extracts a compact alert summary from raw TLS tool logs to improve failure readability.
   *
   * @param fullLog complete TLS log output
   * @return compact summary with alert details and relevant handshake metadata
   */
  public static String extractAlertSummary(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      return "TLS logs are empty.";
    }
    var lines = Arrays.stream(fullLog.split("\\R")).toList();
    var relevantPhase = determineRelevantLogPhase(lines);
    var level = (String) null;
    var description = (String) null;
    var handshakeFailure = (String) null;
    var selectedCipherSuite = (String) null;
    var selectedHash = (TlsHashAlgorithm) null;
    var selectedSignature = (TlsSignatureAlgorithm) null;
    var selectedTls13SignatureScheme = (String) null;
    var selectedServerKeyShare = (String) null;
    var handshakeSuccessful = false;
    var lastLine = (String) null;

    for (var line : lines.subList(relevantPhase.startInclusive(), relevantPhase.endExclusive())) {
      var trimmedLine = line.trim();
      if (!trimmedLine.isEmpty()) {
        lastLine = trimmedLine;
        if (trimmedLine.contains("Handshake successful.")) {
          handshakeSuccessful = true;
        }
      }
      var levelMatcher = ALERT_LEVEL_PATTERN.matcher(line);
      if (levelMatcher.find()) {
        level = levelMatcher.group(1);
      }
      var descriptionMatcher = ALERT_DESCRIPTION_PATTERN.matcher(line);
      if (descriptionMatcher.find()) {
        description = descriptionMatcher.group(1);
      }
      var handshakeFailureMatcher = TLS_HANDSHAKE_FAILED_PATTERN.matcher(line);
      if (handshakeFailureMatcher.find()) {
        handshakeFailure = handshakeFailureMatcher.group(0);
      }
      var selectedCipherSuiteMatcher = TLS_CIPHER_SUITE_PATTERN.matcher(line);
      if (selectedCipherSuiteMatcher.find()) {
        selectedCipherSuite = selectedCipherSuiteMatcher.group(1).trim();
      }
      var serverHelloCipherSuiteMatcher = TLS_SERVER_HELLO_CIPHER_SUITE_PATTERN.matcher(line);
      if (serverHelloCipherSuiteMatcher.find()) {
        selectedCipherSuite =
            formatTlsCipherSuite(serverHelloCipherSuiteMatcher.group(1), serverHelloCipherSuiteMatcher.group(2));
      }
      var selectedHashMatcher = TLS_HASH_ALGORITHM_PATTERN.matcher(line);
      if (selectedHashMatcher.find()) {
        selectedHash = TlsHashAlgorithm.fromValue(Integer.parseInt(selectedHashMatcher.group(1)));
      }
      var selectedSignatureMatcher = TLS_SIGNATURE_ALGORITHM_PATTERN.matcher(line);
      if (selectedSignatureMatcher.find()) {
        selectedSignature = TlsSignatureAlgorithm.fromValue(Integer.parseInt(selectedSignatureMatcher.group(1)));
      }
      var certificateVerifyMatcher = TLS_CERTIFICATE_VERIFY_ALGORITHM_PATTERN.matcher(line);
      if (certificateVerifyMatcher.find()) {
        selectedTls13SignatureScheme =
            formatTls13SignatureScheme(certificateVerifyMatcher.group(1), certificateVerifyMatcher.group(2));
      }
      var serverKeyShareMatcher = TLS_SERVER_HELLO_KEY_SHARE_GROUP_PATTERN.matcher(line);
      if (serverKeyShareMatcher.find()) {
        selectedServerKeyShare =
            formatTlsSupportedGroup(serverKeyShareMatcher.group(1), serverKeyShareMatcher.group(2));
      }
    }

    var summary = new StringBuilder();
    summary.append("Alert summary:");
    summary.append(" level=").append(level != null ? level : "n/a");
    summary.append(", description=").append(description != null ? description : "n/a");
    if (handshakeFailure != null) {
      summary.append(", ").append(handshakeFailure);
    } else if (lastLine != null) {
      summary.append(", last_log_line=").append(lastLine);
    }
    if (handshakeSuccessful) {
      summary.append(", handshake_successful=true");
    }
    if (selectedCipherSuite != null) {
      summary.append(", selected_cipher_suite=").append(selectedCipherSuite);
    }
    if (selectedTls13SignatureScheme != null) {
      summary.append(", selected_tls13_signature_scheme=").append(selectedTls13SignatureScheme);
    }
    if (selectedServerKeyShare != null) {
      summary.append(", selected_server_key_share=").append(selectedServerKeyShare);
    }
    if (selectedHash != null) {
      summary.append(", selected_hash=")
          .append(selectedHash.name())
          .append("(")
          .append(selectedHash.getValue())
          .append("/")
          .append(selectedHash.getHexValue())
          .append(")");
    }
    if (selectedSignature != null) {
      summary.append(", selected_signature=")
          .append(selectedSignature.name())
          .append("(")
          .append(selectedSignature.getValue())
          .append("/")
          .append(selectedSignature.getHexValue())
          .append(")");
    }
    return summary.toString();
  }

  /**
   * Extracts the most relevant handshake phase from the TLS logs as a single string.
   *
   * @param fullLog complete TLS log output
   * @return TLS log excerpt containing the current or failing handshake phase
   */
  public static String extractRelevantLogPhase(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }
    var lines = Arrays.stream(fullLog.split("\\R")).toList();
    var relevantPhase = determineRelevantLogPhase(lines);
    return String.join(System.lineSeparator(), lines.subList(relevantPhase.startInclusive(), relevantPhase.endExclusive()));
  }

  /**
   * Extracts the hash algorithm selected by the server in TLS 1.2 ServerKeyExchange logs.
   *
   * @param fullLog complete TLS log output
   * @return selected hash algorithm, or {@code null} if not present in the logs
   */
  public static TlsHashAlgorithm extractSelectedTls12HashAlgorithm(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      return null;
    }
    var matcher = TLS_HASH_ALGORITHM_PATTERN.matcher(fullLog);
    if (!matcher.find()) {
      return null;
    }
    var id = Integer.parseInt(matcher.group(1));
    return TlsHashAlgorithm.fromValue(id);
  }

  /**
   * Extracts the raw TLS extensions block for a ClientHello or ServerHello in the given phase.
   *
   * @param handshakePhase TLS log excerpt for one handshake phase
   * @param helloRole      selects ClientHello or ServerHello
   * @return parsed extension block bytes
   */
  public static byte[] extractHelloExtensions(String handshakePhase, TlsEndpointRole helloRole) {
    var pattern =
        helloRole == TlsEndpointRole.CLIENT
            ? CLIENT_HELLO_EXTENSIONS_OPTIONAL_PATTERN
            : SERVER_HELLO_EXTENSIONS_PATTERN;
    var matcher = pattern.matcher(handshakePhase);
    if (!matcher.find() || matcher.group(1) == null || matcher.group(1).isBlank()) {
      throw new AssertionError("No " + helloRole.getDisplayName() + " hello extensions were found in the TLS logs.");
    }
    return parseHexBytes(matcher.group(1));
  }

  /**
   * Extracts the raw TLS extensions block matched by the given role.
   *
   * @param fullLog       complete TLS test tool log output
   * @param role          hello role whose extension block should be returned
   * @param failIfMissing whether a missing extensions line should raise an assertion
   * @return parsed extension bytes, or an empty array if the matched block is blank or malformed
   */
  public static byte[] extractHelloExtensions(String fullLog, TlsEndpointRole role, boolean failIfMissing) {
    var pattern = role == TlsEndpointRole.SERVER
        ? SERVER_HELLO_EXTENSIONS_PATTERN
        : CLIENT_HELLO_EXTENSIONS_OPTIONAL_PATTERN;
    return extractHelloExtensionsWithPattern(fullLog, pattern, failIfMissing);
  }

  /**
   * Extracts ClientHello.cipher_suite.
   *
   * @param tlsLog TLS log content
   * @return cipher suites pairs ["(0xC0,0x2C)", "(0xC0,0x30)", ...]
   */
  public static List<String> extractClientHelloCipherSuitesAsPairs(String tlsLog) {
    if (tlsLog == null || tlsLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }
    Matcher m = CLIENT_HELLO_CIPHER_SUITES_PATTERN.matcher(tlsLog);
    if (!m.find()) {
      return List.of();
    }

    String[] bytes = m.group(1).trim().split("\\s+");
    List<String> pairs = new ArrayList<>(bytes.length / 2);
    for (int i = 0; i + 1 < bytes.length; i += 2) {
      pairs.add("(0x" + bytes[i].toUpperCase(Locale.ROOT) + ",0x" + bytes[i + 1].toUpperCase(Locale.ROOT) + ")");
    }
    return pairs;
  }

  /**
   * Extracts the {@code supported_groups} extension list from a TLS ClientHello.
   *
   * @param fullLog complete TLS test tool log output
   * @return list of extracted supported groups in ClientHello order
   */
  public static List<TlsSupportedGroup> extractSupportedGroupsHex(String fullLog) {
    byte[] sg = findClientHelloExtensionData(fullLog, TLS_EXTENSION_SUPPORTED_GROUPS);
    if (sg == null || sg.length < 2) {
      return List.of();
    }

    int listLen = u16(sg, 0);
    if (2 + listLen > sg.length) {
      return List.of();
    }

    List<TlsSupportedGroup> groups = new ArrayList<>();
    for (int i = 2; i + 1 < 2 + listLen; i += 2) {
      groups.add(TlsSupportedGroup.fromHex(String.format("%04x", u16(sg, i))));
    }
    return groups;
  }

  /**
   * Extracts the {@code key_share} group list from a TLS ClientHello.
   *
   * @param fullLog complete TLS test tool log output
   * @return list of extracted key_share groups in ClientHello order
   */
  public static List<TlsSupportedGroup> extractClientKeyShareGroups(String fullLog) {
    byte[] keyShare = findClientHelloExtensionData(fullLog, TLS_EXTENSION_KEY_SHARE);
    if (keyShare == null || keyShare.length < 2) {
      return List.of();
    }

    int clientSharesLength = u16(keyShare, 0);
    if (2 + clientSharesLength > keyShare.length) {
      return List.of();
    }

    List<TlsSupportedGroup> groups = new ArrayList<>();
    int offset = 2;
    int end = 2 + clientSharesLength;
    while (offset + 4 <= end) {
      int groupId = u16(keyShare, offset);
      int keyExchangeLength = u16(keyShare, offset + 2);
      int nextOffset = offset + 4 + keyExchangeLength;
      if (nextOffset > end) {
        return List.of();
      }

      groups.add(TlsSupportedGroup.fromValue(groupId));
      offset = nextOffset;
    }

    if (offset != end) {
      return List.of();
    }
    return groups;
  }

  /**
   * Extracts TLS-1.3 {@code signature_algorithms} extension values from a TLS ClientHello.
   *
   * @param fullLog complete TLS test tool log output
   * @return list of extracted TLS-1.3 signature schemes in ClientHello order
   */
  public static List<TlsSignatureSchemes> extractTls13SignatureSchemes(String fullLog) {
    byte[] signatureAlgorithms = findClientHelloExtensionData(fullLog, TLS_EXTENSION_SIGNATURE_ALGORITHMS);
    if (signatureAlgorithms == null || signatureAlgorithms.length < 2) {
      return List.of();
    }

    int listLen = u16(signatureAlgorithms, 0);
    int listEnd = Math.min(2 + listLen, signatureAlgorithms.length);

    List<TlsSignatureSchemes> schemes = new ArrayList<>();
    for (int i = 2; i + 1 < listEnd; i += 2) {
      var firstByte = String.format("%02X", signatureAlgorithms[i] & 0xFF);
      var secondByte = String.format("%02X", signatureAlgorithms[i + 1] & 0xFF);
      schemes.add(TlsSignatureSchemes.fromAlgorithmBytes(firstByte, secondByte));
    }
    return schemes;
  }

  /**
   * Extracts the {@code signature_algorithms} list from a TLS ClientHello.
   *
   * @param fullLog complete TLS test tool log output
   * @return list of extracted signature algorithms in ClientHello order
   */
  public static List<TlsSignatureAlgorithm> extractSignatureAlgorithmsHex(String fullLog) {
    byte[] signatureAlgorithms = findClientHelloExtensionData(fullLog, TLS_EXTENSION_SIGNATURE_ALGORITHMS);
    if (signatureAlgorithms == null || signatureAlgorithms.length < 2) {
      throw new AssertionError("signature_algorithms not present in the logs.");
    }

    int listLen = u16(signatureAlgorithms, 0);
    int listEnd = Math.min(2 + listLen, signatureAlgorithms.length);

    List<TlsSignatureAlgorithm> algorithms = new ArrayList<>();
    for (int i = 2; i + 1 < listEnd; i += 2) {
      int signatureAlgorithmId = signatureAlgorithms[i + 1] & 0xFF;
      algorithms.add(TlsSignatureAlgorithm.fromValue(signatureAlgorithmId));
    }
    return algorithms;
  }

  /**
   * Checks whether the ClientHello contains the supported_versions extension with TLS 1.3 (0x0304).
   *
   * @param fullLog complete TLS test tool log output
   * @return {@code true} if TLS 1.3 is advertised in supported_versions, otherwise {@code false}
   */
  public static boolean clientHelloSignalsTls13Support(String fullLog) {
    byte[] supportedVersions = findClientHelloExtensionData(fullLog, TLS_EXTENSION_SUPPORTED_VERSIONS);
    if (supportedVersions == null || supportedVersions.length < 3) {
      return false;
    }

    int listLen = supportedVersions[0] & 0xFF;
    if (1 + listLen > supportedVersions.length) {
      return false;
    }

    for (int i = 1; i + 1 < 1 + listLen; i += 2) {
      if (u16(supportedVersions, i) == 0x0304) {
        return true;
      }
    }
    return false;
  }

  /**
   * Finds a specific extension payload in the logged ClientHello extension blocks.
   *
   * @param fullLog    complete TLS test tool log output
   * @param wantedType extension type to locate
   * @return extension payload, or {@code null} if the extension is absent or malformed
   */
  public static byte[] findClientHelloExtensionData(String fullLog, int wantedType) {
    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    var clientHelloExtensionsFound = false;
    for (var line : fullLog.split("\\R")) {
      var extensionBytes = extractClientHelloExtensionsHexFromLine(line);
      if (extensionBytes == null) {
        continue;
      }
      clientHelloExtensionsFound = true;
      var extensionData = findExtensionData(parseHexBytesSafely(extensionBytes), wantedType);
      if (extensionData != null) {
        return extensionData;
      }
    }

    if (!clientHelloExtensionsFound) {
      throw new AssertionError("Client hello extension not present in the logs.");
    }
    return null;
  }

  /**
   * Finds and returns the payload of a specific TLS extension.
   *
   * @param extensions raw TLS extensions block
   * @param wantedType extension type to locate
   * @return extension payload, or {@code null} if absent or malformed
   */
  public static byte[] findExtensionData(byte[] extensions, int wantedType) {
    int i = 0;
    while (i + 4 <= extensions.length) {
      int type = u16(extensions, i);
      int len = u16(extensions, i + 2);
      int dataStart = i + 4;
      int dataEnd = dataStart + len;

      if (type == wantedType) {
        if (dataEnd > extensions.length) {
          return null;
        }
        return Arrays.copyOfRange(extensions, dataStart, dataEnd);
      }
      if (dataEnd > extensions.length) {
        return null;
      }

      i = dataEnd;
    }
    return null;
  }

  /**
   * Parses a whitespace-separated hex string into a byte array.
   *
   * @param hexWithSpaces whitespace-separated hex bytes
   * @return parsed bytes in the same order as provided
   */
  public static byte[] parseHexBytes(String hexWithSpaces) {
    var text = hexWithSpaces.trim();
    if (text.isEmpty()) {
      return new byte[0];
    }
    String normalized = text.replaceAll("\\s+", "");
    try {
      return Hex.decodeHex(normalized);
    } catch (DecoderException e) {
      NumberFormatException ex = new NumberFormatException("Invalid hex token");
      ex.initCause(e);
      throw ex;
    }
  }

  /**
   * Reads an unsigned 16-bit big-endian value from the provided byte array.
   *
   * @param b   source byte array
   * @param off start offset of the 2-byte value
   * @return decoded value in range 0..65535
   */
  public static int u16(byte[] b, int off) {
    return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
  }

  /**
   * Checks whether the TLS log contains a successful handshake completion message after renegotiation.
   *
   * @param fullLog complete TLS log output
   * @return {@code true} if a Finished message appears after renegotiation
   */
  public static boolean hasFinishedAfterRenegotiation(String fullLog) {
    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    int renegIdx = fullLog.indexOf(RENEG_MARKER);
    if (renegIdx < 0) {
      return false;
    }

    int finishedIdx = fullLog.indexOf(FINISHED_MARKER, renegIdx + RENEG_MARKER.length());
    return finishedIdx >= 0;
  }

  /**
   * Extracts the first extension block matched by the provided pattern.
   *
   * @param fullLog           complete TLS test tool log output
   * @param extensionsPattern pattern whose first group contains hex-encoded extensions
   * @param failIfMissing     whether a missing extensions line should raise an assertion
   * @return parsed extension bytes, or an empty array for absent or malformed optional data
   */
  private static byte[] extractHelloExtensionsWithPattern(
      String fullLog,
      Pattern extensionsPattern,
      boolean failIfMissing) {
    if (fullLog == null || fullLog.isBlank()) {
      throw new AssertionError("The TLS log is empty or null.");
    }

    Matcher matcher = extensionsPattern.matcher(fullLog);
    if (!matcher.find()) {
      if (failIfMissing) {
        throw new AssertionError("Client hello extension not present in the logs.");
      }
      return new byte[0];
    }

    String extensionBytes = matcher.group(1);
    if (extensionBytes == null || extensionBytes.isBlank()) {
      return new byte[0];
    }

    try {
      return parseHexBytes(extensionBytes);
    } catch (NumberFormatException e) {
      return new byte[0];
    }
  }

  /**
   * Determines whether the TLS test tool logged the transcript as client or server.
   *
   * @param fullLog complete TLS test tool log output
   * @return endpoint role used by the TLS test tool
   */
  private static TlsEndpointRole determineTlsTestToolRole(String fullLog) {
    int transmittedClientHelloIndex = fullLog.indexOf("ClientHello message transmitted.");
    int receivedClientHelloIndex = fullLog.indexOf("Valid ClientHello message received.");

    if (transmittedClientHelloIndex >= 0
        && (receivedClientHelloIndex < 0 || transmittedClientHelloIndex < receivedClientHelloIndex)) {
      return TlsEndpointRole.CLIENT;
    }
    if (receivedClientHelloIndex >= 0) {
      return TlsEndpointRole.SERVER;
    }

    throw new AssertionError("Unable to determine whether the TLS test tool acted as client or server.");
  }

  /**
   * Extracts the Finished verify_data sent by the requested endpoint before renegotiation.
   *
   * @param handshakePhase     TLS log excerpt before renegotiation starts
   * @param tlsTestToolRole    endpoint role of the TLS test tool
   * @param finishedSenderRole endpoint role whose Finished data should be extracted
   * @return Finished verify_data bytes
   */
  private static byte[] extractFinishedVerifyData(
      String handshakePhase,
      TlsEndpointRole tlsTestToolRole,
      TlsEndpointRole finishedSenderRole) {
    var finishedMarker =
        tlsTestToolRole == finishedSenderRole
            ? "Finished message transmitted."
            : "Valid Finished message received.";
    var finishedDataPattern =
        Pattern.compile("tlsHandshakeMessage type\\s*=\\s*0x14 data\\s*=\\s*([0-9a-fA-F ]+)");

    byte[] lastFinishedData = null;
    for (var line : handshakePhase.split("\\R")) {
      var matcher = finishedDataPattern.matcher(line);
      if (matcher.find()) {
        lastFinishedData = parseHexBytes(matcher.group(1));
      }

      if (line.contains(finishedMarker)) {
        if (lastFinishedData == null) {
          throw new AssertionError("Finished verify_data could not be extracted before '" + finishedMarker + "'.");
        }
        return lastFinishedData;
      }
    }

    throw new AssertionError("No '" + finishedMarker + "' entry was found before renegotiation.");
  }

  /**
   * Builds the RFC 5746 renegotiation_info extension payload from previous Finished values.
   *
   * @param verifyDataParts Finished verify_data values in protocol order
   * @return renegotiation_info payload including the one-byte length prefix
   */
  private static byte[] buildRenegotiationInfoPayload(byte[]... verifyDataParts) {
    int payloadLength = 0;
    for (var verifyDataPart : verifyDataParts) {
      if (verifyDataPart != null) {
        payloadLength += verifyDataPart.length;
      }
    }

    if (payloadLength > 0xFF) {
      throw new AssertionError("renegotiation_info payload exceeds the supported length encoding.");
    }

    var renegotiationInfo = new byte[payloadLength + 1];
    renegotiationInfo[0] = (byte) payloadLength;

    int offset = 1;
    for (var verifyDataPart : verifyDataParts) {
      if (verifyDataPart == null || verifyDataPart.length == 0) {
        continue;
      }
      System.arraycopy(verifyDataPart, 0, renegotiationInfo, offset, verifyDataPart.length);
      offset += verifyDataPart.length;
    }

    return renegotiationInfo;
  }

  /**
   * Formats raw bytes as space-separated lowercase hex for assertion messages.
   *
   * @param bytes bytes to format
   * @return hex representation, or {@code null} text for {@code null}
   */
  private static String toHex(byte[] bytes) {
    if (bytes == null) {
      return "null";
    }
    return Hex.encodeHexString(bytes).replaceAll("..(?!$)", "$0 ").trim();
  }

  /**
   * Formats a TLS cipher suite tuple with the known suite name when available.
   *
   * @param firstByte  first cipher suite byte
   * @param secondByte second cipher suite byte
   * @return known suite name or an UNKNOWN marker
   */
  private static String formatTlsCipherSuite(String firstByte, String secondByte) {
    var tuple = formatHexBytePair(firstByte, secondByte);
    return Arrays.stream(TlsCipherSuite.values())
        .filter(cipherSuite -> cipherSuite.getTlsTestToolCipherSuiteValue().equalsIgnoreCase(tuple))
        .findFirst()
        .map(TlsCipherSuite::getCipherSuiteName)
        .orElse("UNKNOWN(" + tuple + ")");
  }

  /**
   * Formats a TLS-1.3 signature scheme tuple with the known scheme name when available.
   *
   * @param firstByte  first signature scheme byte
   * @param secondByte second signature scheme byte
   * @return known scheme summary or an UNKNOWN marker
   */
  private static String formatTls13SignatureScheme(String firstByte, String secondByte) {
    var tuple = formatHexBytePair(firstByte, secondByte);
    return Arrays.stream(TlsSignatureSchemes.values())
        .filter(signatureScheme -> signatureScheme.getHexValue().equalsIgnoreCase(tuple))
        .findFirst()
        .map(signatureScheme -> signatureScheme.name()
            + "("
            + signatureScheme.getSchemeName()
            + "/"
            + signatureScheme.getHexValue()
            + ")")
        .orElse("UNKNOWN(" + tuple + ")");
  }

  /**
   * Formats a supported-group tuple with the known group name when available.
   *
   * @param firstByte  first supported-group byte
   * @param secondByte second supported-group byte
   * @return known group summary or an UNKNOWN marker
   */
  private static String formatTlsSupportedGroup(String firstByte, String secondByte) {
    var groupId = Integer.parseInt(firstByte + secondByte, 16);
    var group = TlsSupportedGroup.fromValue(groupId);
    var groupName = group == TlsSupportedGroup.UNKNOWN ? "UNKNOWN" : group.getDisplayName();
    return groupName + "(" + formatHexWord(groupId) + ")";
  }

  /**
   * Formats two hex bytes in tls-test-tool tuple notation.
   *
   * @param firstByte  first byte as two hex digits
   * @param secondByte second byte as two hex digits
   * @return tuple representation
   */
  private static String formatHexBytePair(String firstByte, String secondByte) {
    return "(0x%s,0x%s)".formatted(firstByte.toUpperCase(Locale.ROOT), secondByte.toUpperCase(Locale.ROOT));
  }

  /**
   * Formats an unsigned 16-bit value as uppercase hex.
   *
   * @param value value to format
   * @return four-digit hex word with {@code 0x} prefix
   */
  private static String formatHexWord(int value) {
    return "0x%04X".formatted(value & 0xFFFF);
  }

  /**
   * Determines the log phase that is most useful for diagnostics.
   *
   * @param lines complete TLS log split into lines
   * @return inclusive/exclusive line range for the relevant phase
   */
  private static TlsLogPhase determineRelevantLogPhase(List<String> lines) {
    var failureIndex = -1;
    for (var i = 0; i < lines.size(); i++) {
      if (TLS_HANDSHAKE_FAILED_PATTERN.matcher(lines.get(i)).find()) {
        failureIndex = i;
      }
    }
    var endExclusive = failureIndex >= 0 ? failureIndex + 1 : lines.size();
    var startInclusive = 0;
    for (var i = 0; i < endExclusive; i++) {
      if (isHandshakePhaseBoundary(lines.get(i))) {
        startInclusive = i + 1;
      }
    }
    return new TlsLogPhase(startInclusive, endExclusive);
  }

  /**
   * Checks whether a line starts a new handshake phase for diagnostic scoping.
   *
   * @param line TLS log line
   * @return {@code true} if the line marks a phase boundary
   */
  private static boolean isHandshakePhaseBoundary(String line) {
    return TLS_RENEGOTIATION_PHASE_PATTERN.matcher(line).find()
        || TLS_CLIENT_HELLO_SENT_PATTERN.matcher(line).find()
        || TLS_CLIENT_HELLO_WRITE_PATTERN.matcher(line).find();
  }

  /**
   * Extracts the ClientHello extensions hex payload from one log line.
   *
   * @param line TLS log line
   * @return hex payload, an empty string for an empty payload, or {@code null} if the line is unrelated
   */
  private static String extractClientHelloExtensionsHexFromLine(String line) {
    var matcher = CLIENT_HELLO_EXTENSIONS_LINE_PATTERN.matcher(line);
    if (!matcher.find()) {
      return null;
    }
    var extensionBytes = matcher.group(1);
    return extensionBytes == null ? "" : extensionBytes.trim();
  }

  /**
   * Parses hex bytes while treating malformed optional parser input as empty data.
   *
   * @param hexWithSpaces whitespace-separated hex bytes
   * @return parsed bytes, or an empty byte array
   */
  private static byte[] parseHexBytesSafely(String hexWithSpaces) {
    if (hexWithSpaces == null || hexWithSpaces.isBlank()) {
      return new byte[0];
    }
    try {
      return parseHexBytes(hexWithSpaces);
    } catch (NumberFormatException e) {
      return new byte[0];
    }
  }

  /**
   * Line range for one relevant TLS log phase.
   *
   * @param startInclusive first included line index
   * @param endExclusive   first excluded line index
   */
  private record TlsLogPhase(int startInclusive, int endExclusive) {

  }
}
