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
 * TLS 1.2 signature algorithm IDs used in signature_algorithms and ServerKeyExchange metadata.
 */
public enum TlsSignatureAlgorithm {
  RSA(1, "0x01", Policy.FORBIDDEN),
  DSA(2, "0x02", Policy.OPTIONAL),
  ECDSA(3, "0x03", Policy.OPTIONAL),
  UNKNOWN(-1, "n/a", Policy.NA);

  @Getter
  private final int value;
  @Getter
  private final String hexValue;
  @Getter
  private final Policy policy;

  TlsSignatureAlgorithm(int value, String hexValue, Policy policy) {
    this.value = value;
    this.hexValue = hexValue;
    this.policy = policy;
  }

  /**
   * Resolve enum by TLS 1.2 signature id.
   *
   * @param value TLS signature id from protocol metadata
   * @return matching enum or {@link #UNKNOWN}
   */
  public static TlsSignatureAlgorithm fromValue(int value) {
    return Arrays.stream(values())
        .filter(algorithm -> algorithm.value == value)
        .findFirst()
        .orElse(UNKNOWN);
  }

  /**
   * Returns all supported TLS 1.2 signature algorithms defined by {@link TlsSignatureAlgorithm}.
   *
   * @return an {@link List} of all supported {@link TlsSignatureAlgorithm} values
   */
  public static List<TlsSignatureAlgorithm> getSupportedSignatureAlgorithms() {
    return Arrays.stream(TlsSignatureAlgorithm.values())
        .filter(g -> g.getPolicy() == Policy.MANDATORY
            || g.getPolicy() == Policy.OPTIONAL)
        .collect(Collectors.toList());
  }

  /**
   * Returns all unsupported / forbidden TLS 1.2 signature algorithms defined by {@link TlsSignatureAlgorithm}.
   *
   * @return an {@link List} of all forbidden {@link TlsSignatureAlgorithm} values
   */
  public static List<TlsSignatureAlgorithm> getUnsupportedSignatureAlgorithms() {
    return Arrays.stream(TlsSignatureAlgorithm.values())
        .filter(g -> g.getPolicy() == Policy.FORBIDDEN)
        .collect(Collectors.toList());
  }

  /**
   * Policy classification for TLS signature algorithms used when validating a ClientHello.
   *
   * <ul>
   *   <li>{@link #MANDATORY} – the signature algorithm must be offered/supported to comply with the policy.</li>
   *   <li>{@link #OPTIONAL} – the signature algorithm is permitted by policy but not required.</li>
   *   <li>{@link #FORBIDDEN} – the signature algorithm must not be offered; its presence is a policy violation.</li>
   *   <li>{@link #NA} – not applicable / unspecified (e.g., placeholder or unknown signature algorithm).</li>
   * </ul>
   */
  public enum Policy {
    MANDATORY,
    OPTIONAL,
    FORBIDDEN,
    NA
  }

}
