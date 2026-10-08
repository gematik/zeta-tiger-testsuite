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

package de.gematik.zeta.model.tls;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * TLS "supported_groups" (extension 0x000a) NamedGroup IDs with policy classification.
 */
public enum TlsSupportedGroup {

  // Mandatory
  SECP256R1(0x0017, "secp256r1", Tls12Policy.MANDATORY, Tls13Policy.RECOMMENDED),
  SECP384R1(0x0018, "secp384r1", Tls12Policy.MANDATORY, Tls13Policy.RECOMMENDED),

  // Optional
  BRAINPOOLP256R1(0x001A, "brainpoolP256r1", Tls12Policy.OPTIONAL, Tls13Policy.OPTIONAL),
  BRAINPOOLP384R1(0x001B, "brainpoolP384r1", Tls12Policy.OPTIONAL, Tls13Policy.OPTIONAL),
  BRAINPOOLP512R1(0x001C, "brainpoolP512r1", Tls12Policy.OPTIONAL, Tls13Policy.OPTIONAL),

  // TLS 1.3 tool names for brainpool curves
  BRAINPOOLP256R1TLS13(0x001F, "brainpoolP256r1tls13", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  BRAINPOOLP384R1TLS13(0x0020, "brainpoolP384r1tls13", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  BRAINPOOLP512R1TLS13(0x0021, "brainpoolP512r1tls13", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),

  // Forbidden
  SECP192R1(0x0013, "secp192r1", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  SECP224R1(0x0015, "secp224r1", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  SECP521R1(0x0019, "secp521r1", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  SECP256K1(0x0016, "secp256k1", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),

  X25519(0x001D, "x25519", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  X448(0x001E, "x448", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),

  FFDHE2048(0x0100, "ffdhe2048", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  FFDHE3072(0x0101, "ffdhe3072", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  FFDHE4096(0x0102, "ffdhe4096", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  FFDHE6144(0x0103, "ffdhe6144", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),
  FFDHE8192(0x0104, "ffdhe8192", Tls12Policy.FORBIDDEN, Tls13Policy.FORBIDDEN),

  UNKNOWN(-1, "unknown", Tls12Policy.NA, Tls13Policy.NA),
  UNSUPPORTED_MIX(-1, "unsupported_mix", Tls12Policy.NA, Tls13Policy.NA),
  // Synthetic profile: both mandatory groups offered together (see mandatoryGroups()).
  MANDATORY_MIX(-1, "mandatory_mix", Tls12Policy.NA, Tls13Policy.NA);

  @Getter
  private final int value;
  @Getter
  private final String displayName;
  @Getter
  private final Tls12Policy tls12Policy;
  @Getter
  private final Tls13Policy tls13Policy;

  TlsSupportedGroup(int value, String displayName, Tls12Policy tls12Policy, Tls13Policy tls13Policy) {
    this.value = value;
    this.displayName = displayName;
    this.tls12Policy = tls12Policy;
    this.tls13Policy = tls13Policy;
  }

  /**
   * Resolves a {@link TlsSupportedGroup} from its numeric NamedGroup identifier.
   *
   * @param value numeric NamedGroup ID (typically an uint16 from protocol metadata)
   * @return matching {@link TlsSupportedGroup}, or {@link #UNKNOWN} if the value is not recognized
   */
  public static TlsSupportedGroup fromValue(int value) {
    return java.util.Arrays.stream(values())
        .filter(group -> group.value == value)
        .findFirst()
        .orElse(UNKNOWN);
  }

  /**
   * Resolves a {@link TlsSupportedGroup} from a hex-encoded NamedGroup identifier.
   *
   * @param hex hex-encoded NamedGroup ID (with or without {@code 0x} prefix)
   * @return matching {@link TlsSupportedGroup}, or {@link #UNKNOWN} for invalid/unknown inputs
   */
  public static TlsSupportedGroup fromHex(String hex) {
    if (hex == null || hex.isBlank()) {
      return UNKNOWN;
    }
    String s = hex.trim();
    if (s.startsWith("0x") || s.startsWith("0X")) {
      s = s.substring(2);
    }
    try {
      return fromValue(Integer.parseInt(s, 16));
    } catch (NumberFormatException e) {
      return UNKNOWN;
    }
  }

  /**
   * Resolves a {@link TlsSupportedGroup} by its human-readable display name.
   *
   * @param displayName the human-readable group name to resolve
   * @return matching {@link TlsSupportedGroup}, or {@link #UNKNOWN} if no match is found
   */
  public static TlsSupportedGroup fromDisplayName(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      return UNKNOWN;
    }

    String needle = displayName.trim();
    if ("p256".equalsIgnoreCase(needle)) {
      return SECP256R1;
    }
    if ("p384".equalsIgnoreCase(needle)) {
      return SECP384R1;
    }
    return java.util.Arrays.stream(values())
        .filter(g -> g.displayName.equalsIgnoreCase(needle))
        .findFirst()
        .orElse(UNKNOWN);
  }

  /**
   * Builds a TLS {@code supported_groups} extension (type {@code 0x000a}) from the provided list of {@link TlsSupportedGroup}.
   *
   * @param groups list of  {@link TlsSupportedGroup} groups to encode
   * @return {@code supported_groups} extension as a lowercase hex string (no whitespace)
   * @throws IllegalArgumentException if {@code groups} is {@code null} or empty
   */
  public static String buildSupportedGroupsExtension(List<TlsSupportedGroup> groups) {
    if (groups == null || groups.isEmpty()) {
      throw new IllegalArgumentException("groups must not be null/empty");
    }

    int listLenBytes = groups.size() * 2;      // each group is u16
    int extLenBytes = 2 + listLenBytes;       // u16 listLen + list

    StringBuilder sb = new StringBuilder();
    sb.append("000a");                         // extension type supported_groups
    sb.append(u16(extLenBytes));               // extension length
    sb.append(u16(listLenBytes));              // list length
    for (TlsSupportedGroup g : groups) {
      sb.append(u16(g.getValue()));            // group id
    }
    return sb.toString().toLowerCase();
  }

  /**
   * Formats an unsigned 16-bit value as a four-digit lowercase hex string.
   *
   * @param v value in range 0..65535
   * @return hex string representation (e.g. {@code 000a})
   */
  private static String u16(int v) {
    return String.format("%04x", v & 0xFFFF);
  }

  /**
   * Returns all groups that are forbidden by policy.
   *
   * @return a list of policy-forbidden groups, in enum order
   */
  public static List<TlsSupportedGroup> forbiddenGroups() {
    return Arrays.stream(TlsSupportedGroup.values())
        .filter(g -> g.getTls12Policy() == TlsSupportedGroup.Tls12Policy.FORBIDDEN)
        .collect(Collectors.toList());
  }

  /**
   * Returns the mandatory groups with secp384r1 FIRST, then secp256r1. Order is
   * significant: offering both keeps an ECDSA P-256 server certificate usable
   * (its curve is present in the ClientHello — TLS 1.2 ties supported_groups to
   * the cert curve, RFC 8422 §5.1), while a server honoring client group
   * preference then selects secp384r1 for the ephemeral ECDHE key. This proves
   * secp384r1 support without a p384-only ClientHello, which no ECDSA-P256-cert
   * server can satisfy.
   *
   * @return {@code [secp384r1, secp256r1]}
   */
  public static List<TlsSupportedGroup> mandatoryGroups() {
    return List.of(SECP384R1, SECP256R1);
  }

  /**
   * Returns all TLS-1.3 supported groups that are forbidden by the TLS-1.3 policy.
   *
   * @return a list of TLS-1.3-policy-forbidden groups, in enum order
   */
  public static List<TlsSupportedGroup> forbiddenGroupsForTls13() {
    return Arrays.stream(TlsSupportedGroup.values())
        .filter(g -> g.getTls13Policy() == Tls13Policy.FORBIDDEN)
        .collect(Collectors.toList());
  }

  /**
   * Groups that are forbidden by TLS-1.3 policy AND can actually be offered in a TLS-1.3
   * ClientHello. The tls-test-tool builds its TLS-1.3 supported_groups from these names via
   * OpenSSL's group list, which is all-or-nothing: a single unparseable token makes OpenSSL
   * discard the whole list and fall back to its defaults (which include allowed curves), so the
   * ClientHello would no longer be forbidden-only. OpenSSL does not register
   * secp192r1/secp224r1/secp256k1 as TLS-usable named groups (the first two are below TLS's
   * security floor; secp256k1 was never enabled for TLS group negotiation), so it cannot put them
   * on the wire and they are excluded here. They remain in {@link #forbiddenGroupsForTls13()} for
   * policy classification, and in the hex-encoded TLS-1.2 forbidden mix, which is name-independent.
   *
   * @return TLS-1.3-forbidden groups that OpenSSL can advertise, in enum order
   */
  public static List<TlsSupportedGroup> forbiddenGroupsOfferableInTls13() {
    var notOfferable = List.of(SECP192R1, SECP224R1, SECP256K1);
    return forbiddenGroupsForTls13().stream()
        .filter(g -> !notOfferable.contains(g))
        .collect(Collectors.toList());
  }

  /**
   * Returns all supported groups that are permitted by policy.
   *
   * @return a list of policy-allowed supported groups (mandatory + optional), in enum order
   */
  public static List<TlsSupportedGroup> allowedGroups() {
    return Arrays.stream(TlsSupportedGroup.values())
        .filter(g -> g.getTls12Policy() == TlsSupportedGroup.Tls12Policy.MANDATORY
            || g.getTls12Policy() == TlsSupportedGroup.Tls12Policy.OPTIONAL)
        .collect(Collectors.toList());
  }

  /**
   * Returns the TLS-1.3 supported_groups names in the required wire-name form and order.
   *
   * @return comma-separated TLS-1.3 supported_groups names
   */
  public static String tls13SupportedGroups() {
    return Arrays.stream(TlsSupportedGroup.values())
        .filter(g -> g.getTls13Policy() == Tls13Policy.RECOMMENDED
            || g.getTls13Policy() == Tls13Policy.OPTIONAL)
        .map(TlsSupportedGroup::getDisplayName)
        .collect(Collectors.joining(","));
  }

  /**
   * Returns the TLS-1.3 supported_groups value for the provided groups in wire-name form.
   *
   * @param groups groups to encode for tls-test-tool configuration
   * @return comma-separated TLS-1.3 supported_groups names
   */
  public static String tls13SupportedGroupsValue(List<TlsSupportedGroup> groups) {
    if (groups == null || groups.isEmpty()) {
      throw new IllegalArgumentException("groups must not be null/empty");
    }
    return groups.stream()
        .map(TlsSupportedGroup::getDisplayName)
        .collect(Collectors.joining(","));
  }

  /**
   * TLS-1.2 policy classification for supported groups (NamedGroup IDs) used when validating a ClientHello.
   *
   * <ul>
   *   <li>{@link #MANDATORY} – the group must be offered/supported to comply with the policy.</li>
   *   <li>{@link #OPTIONAL} – the group is permitted by policy but not required.</li>
   *   <li>{@link #FORBIDDEN} – the group must not be offered; its presence is a policy violation.</li>
   *   <li>{@link #NA} – not applicable / unspecified (e.g., placeholder or unknown group).</li>
   * </ul>
   */
  public enum Tls12Policy {
    MANDATORY,
    OPTIONAL,
    FORBIDDEN,
    NA
  }

  /**
   * TLS-1.3-specific recommendation classification for supported_groups entries.
   */
  public enum Tls13Policy {
    RECOMMENDED,
    FORBIDDEN,
    OPTIONAL,
    NA
  }

}
