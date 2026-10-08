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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * TLS 1.2 hash algorithm IDs used in signature_algorithms and ServerKeyExchange metadata.
 */
public enum TlsHashAlgorithm {
  MD5(1, "0x01", "md5", false),
  SHA1(2, "0x02", "sha1", false),
  SHA224(3, "0x03", "sha224", false),
  SHA256(4, "0x04", "sha256", true),
  SHA384(5, "0x05", "sha384", true),
  SHA512(6, "0x06", "sha512", true),
  UNKNOWN(-1, "n/a", "unknown", false),
  SUPPORTED_MIX(-1, "n/a", "supported_mix", false);

  @Getter
  private final int value;
  @Getter
  private final String hexValue;
  @Getter
  private final String displayName;
  @Getter
  private final boolean supportedByPolicy;

  TlsHashAlgorithm(int value, String hexValue, String displayName, boolean supportedByPolicy) {
    this.value = value;
    this.hexValue = hexValue;
    this.displayName = displayName;
    this.supportedByPolicy = supportedByPolicy;
  }

  /**
   * Resolve enum by TLS 1.2 hash id.
   *
   * @param value TLS hash id from protocol metadata
   * @return matching enum or {@link #UNKNOWN}
   */
  public static TlsHashAlgorithm fromValue(int value) {
    return Arrays.stream(values())
        .filter(algorithm -> algorithm.value == value)
        .findFirst()
        .orElse(UNKNOWN);
  }

  /**
   * Resolve enum by textual hash name used in feature tables and logs.
   *
   * @param displayName the human-readable hash name to resolve
   * @return matching hash {@link TlsHashAlgorithm}, or {@link #UNKNOWN} if no match is found
   */
  public static TlsHashAlgorithm fromDisplayName(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      return UNKNOWN;
    }

    String needle = displayName.trim();
    return java.util.Arrays.stream(values())
        .filter(g -> g.displayName.equalsIgnoreCase(needle))
        .findFirst()
        .orElse(UNKNOWN);
  }

  /**
   * Return all hash algorithms currently allowed by policy for TLS 1.2 signature usage.
   *
   * @return insertion-ordered set of policy-supported hash algorithms
   */
  public static LinkedHashSet<TlsHashAlgorithm> supportedByPolicy() {
    return Arrays.stream(values())
        .filter(TlsHashAlgorithm::isSupportedByPolicy)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return all hash algorithms currently disallowed by policy for TLS 1.2 signature usage.
   *
   * @return insertion-ordered set of policy-disallowed hash algorithms
   */
  public static LinkedHashSet<TlsHashAlgorithm> unsupportedByPolicy() {
    return Arrays.stream(values())
        .filter(algorithm -> algorithm != UNKNOWN && algorithm != SUPPORTED_MIX && !algorithm.isSupportedByPolicy())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Return policy-supported hash names as expected in Gherkin data tables.
   *
   * @return set of enum names for policy-supported hash algorithms
   */
  public static Set<String> supportedByPolicyNames() {
    return supportedByPolicy().stream().map(Enum::name).collect(Collectors.toSet());
  }

  /**
   * Return policy-disallowed hash names as expected in Gherkin data tables.
   *
   * @return set of enum names for policy-disallowed hash algorithms
   */
  public static Set<String> unsupportedByPolicyNames() {
    return unsupportedByPolicy().stream().map(Enum::name).collect(Collectors.toSet());
  }
}
