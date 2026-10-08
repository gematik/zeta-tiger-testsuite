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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * TLS-1.3 signature schemes with wire-format hex values and recommendation metadata.
 */
public enum TlsSignatureSchemes {
  RSA_PKCS1_MD5("rsa_pkcs1_md5", "(0x01,0x01)", Tls13Policy.FORBIDDEN),
  DSA_MD5("dsa_md5", "(0x01,0x02)", Tls13Policy.FORBIDDEN),
  ECDSA_MD5("ecdsa_md5", "(0x01,0x03)", Tls13Policy.FORBIDDEN),
  RSA_PKCS1_SHA1("rsa_pkcs1_sha1", "(0x02,0x01)", Tls13Policy.FORBIDDEN),
  DSA_SHA1("dsa_sha1", "(0x02,0x02)", Tls13Policy.FORBIDDEN),
  ECDSA_SHA1("ecdsa_sha1", "(0x02,0x03)", Tls13Policy.FORBIDDEN),
  RSA_PKCS1_SHA224("rsa_pkcs1_sha224", "(0x03,0x01)", Tls13Policy.FORBIDDEN),
  DSA_SHA224("dsa_sha224", "(0x03,0x02)", Tls13Policy.FORBIDDEN),
  ECDSA_SHA224("ecdsa_sha224", "(0x03,0x03)", Tls13Policy.FORBIDDEN),
  RSA_PKCS1_SHA256("rsa_pkcs1_sha256", "(0x04,0x01)", Tls13Policy.LEGACY_DISALLOWED),
  DSA_SHA256("dsa_sha256", "(0x04,0x02)", Tls13Policy.FORBIDDEN),
  RSA_PKCS1_SHA384("rsa_pkcs1_sha384", "(0x05,0x01)", Tls13Policy.LEGACY_DISALLOWED),
  DSA_SHA384("dsa_sha384", "(0x05,0x02)", Tls13Policy.FORBIDDEN),
  RSA_PKCS1_SHA512("rsa_pkcs1_sha512", "(0x06,0x01)", Tls13Policy.LEGACY_DISALLOWED),
  DSA_SHA512("dsa_sha512", "(0x06,0x02)", Tls13Policy.FORBIDDEN),
  RSA_PSS_RSAE_SHA256("rsa_pss_rsae_sha256", "(0x08,0x04)", Tls13Policy.CURVE_DISALLOWED),
  RSA_PSS_RSAE_SHA384("rsa_pss_rsae_sha384", "(0x08,0x05)", Tls13Policy.CURVE_DISALLOWED),
  RSA_PSS_RSAE_SHA512("rsa_pss_rsae_sha512", "(0x08,0x06)", Tls13Policy.CURVE_DISALLOWED),
  ED25519("ed25519", "(0x08,0x07)", Tls13Policy.CURVE_DISALLOWED),
  ED448("ed448", "(0x08,0x08)", Tls13Policy.CURVE_DISALLOWED),
  RSA_PSS_PSS_SHA256("rsa_pss_pss_sha256", "(0x08,0x09)", Tls13Policy.CURVE_DISALLOWED),
  RSA_PSS_PSS_SHA384("rsa_pss_pss_sha384", "(0x08,0x0A)", Tls13Policy.CURVE_DISALLOWED),
  RSA_PSS_PSS_SHA512("rsa_pss_pss_sha512", "(0x08,0x0B)", Tls13Policy.CURVE_DISALLOWED),
  ECDSA_SECP256R1_SHA256("ecdsa_secp256r1_sha256", "(0x04,0x03)", Tls13Policy.RECOMMENDED),
  ECDSA_SECP384R1_SHA384("ecdsa_secp384r1_sha384", "(0x05,0x03)", Tls13Policy.RECOMMENDED),
  ECDSA_SECP521R1_SHA512("ecdsa_secp521r1_sha512", "(0x06,0x03)", Tls13Policy.CURVE_DISALLOWED),
  ECDSA_BRAINPOOLP256R1TLS13_SHA256("ecdsa_brainpoolP256r1tls13_sha256", "(0x08,0x1A)", Tls13Policy.RECOMMENDED),
  ECDSA_BRAINPOOLP384R1TLS13_SHA384("ecdsa_brainpoolP384r1tls13_sha384", "(0x08,0x1B)", Tls13Policy.RECOMMENDED),
  ECDSA_BRAINPOOLP512R1TLS13_SHA512("ecdsa_brainpoolP512r1tls13_sha512", "(0x08,0x1C)", Tls13Policy.RECOMMENDED);

  @Getter
  private final String schemeName;
  @Getter
  private final String hexValue;
  @Getter
  private final Tls13Policy tls13Policy;

  TlsSignatureSchemes(String schemeName, String hexValue, Tls13Policy tls13Policy) {
    this.schemeName = schemeName;
    this.hexValue = hexValue;
    this.tls13Policy = tls13Policy;
  }

  /**
   * Returns the recommended TLS-1.3 signature scheme names expected in Gherkin tables.
   *
   * @return set of recommended TLS-1.3 signature scheme names
   */
  public static Set<String> recommendedSchemeNames() {
    return Arrays.stream(values())
        .filter(scheme -> scheme.getTls13Policy() == Tls13Policy.RECOMMENDED)
        .map(TlsSignatureSchemes::getSchemeName)
        .collect(Collectors.toCollection(HashSet::new));
  }

  /**
   * Returns the non-recommended TLS-1.3 signature scheme names expected in Gherkin tables.
   *
   * @return set of non-recommended TLS-1.3 signature scheme names
   */
  public static Set<String> nonRecommendedSchemeNames() {
    return Arrays.stream(values())
        .filter(scheme -> scheme.getTls13Policy() == Tls13Policy.LEGACY_DISALLOWED)
        .map(TlsSignatureSchemes::getSchemeName)
        .collect(Collectors.toCollection(HashSet::new));
  }

  /**
   * Returns all TLS-1.3 signature schemes that are not permitted by the current policy.
   *
   * @return list of unsupported TLS-1.3 signature schemes
   */
  public static List<TlsSignatureSchemes> unsupportedSchemes() {
    return Arrays.stream(values())
        .filter(scheme -> scheme.getTls13Policy() != Tls13Policy.RECOMMENDED)
        .filter(scheme -> scheme.getTls13Policy() != Tls13Policy.NA)
        .collect(Collectors.toList());
  }

  /**
   * Returns all TLS-1.3 RSA signature schemes that are not permitted by the current policy.
   *
   * @return list of unsupported TLS-1.3 RSA signature schemes
   */
  public static List<TlsSignatureSchemes> unsupportedRsaSchemes() {
    return Arrays.stream(values())
        .filter(scheme -> scheme.name().startsWith("RSA_"))
        .filter(scheme -> scheme.getTls13Policy() != Tls13Policy.RECOMMENDED)
        .filter(scheme -> scheme.getTls13Policy() != Tls13Policy.NA)
        .collect(Collectors.toList());
  }

  /**
   * Returns the tls-test-tool configuration value for the recommended TLS-1.3 signature schemes.
   *
   * @return comma-separated list in tls-test-tool tuple syntax
   */
  public static String recommendedSchemeHexValues() {
    return Arrays.stream(values())
        .filter(scheme -> scheme.getTls13Policy() == Tls13Policy.RECOMMENDED)
        .map(TlsSignatureSchemes::getHexValue)
        .collect(Collectors.joining(","));
  }

  /**
   * Returns the tls-test-tool configuration value for the given TLS-1.3 signature scheme names.
   *
   * @param schemeNames TLS-1.3 signature scheme names
   * @return comma-separated list in tls-test-tool tuple syntax
   */
  public static String schemeHexValuesForSchemeNames(Set<String> schemeNames) {
    if (schemeNames == null || schemeNames.isEmpty()) {
      throw new AssertionError("The TLS 1.3 signature scheme names are empty or null.");
    }

    return Arrays.stream(values())
        .filter(scheme -> schemeNames.contains(scheme.getSchemeName()))
        .map(TlsSignatureSchemes::getHexValue)
        .collect(Collectors.joining(","));
  }

  /**
   * Returns the recommended TLS-1.3 signature scheme names for the given hash algorithms.
   *
   * @param hashAlgorithms supported hash algorithms
   * @return set of recommended TLS-1.3 signature scheme names matching the provided hashes
   */
  public static Set<String> recommendedSchemeNamesForHashes(Set<TlsHashAlgorithm> hashAlgorithms) {
    return Arrays.stream(values())
        .filter(scheme -> scheme.getTls13Policy() == Tls13Policy.RECOMMENDED)
        .filter(scheme -> scheme.matchesAnyHash(hashAlgorithms))
        .map(TlsSignatureSchemes::getSchemeName)
        .collect(Collectors.toCollection(HashSet::new));
  }

  /**
   * Returns the tls-test-tool configuration value for the recommended TLS-1.3 signature schemes matching the given hash algorithms.
   *
   * @param hashAlgorithms supported hash algorithms
   * @return comma-separated list in tls-test-tool tuple syntax
   */
  public static String recommendedSchemeHexValuesForHashes(Set<TlsHashAlgorithm> hashAlgorithms) {
    return Arrays.stream(values())
        .filter(scheme -> scheme.getTls13Policy() == Tls13Policy.RECOMMENDED)
        .filter(scheme -> scheme.matchesAnyHash(hashAlgorithms))
        .map(TlsSignatureSchemes::getHexValue)
        .collect(Collectors.joining(","));
  }

  /**
   * Returns the tls-test-tool configuration value for the non-recommended TLS-1.3 signature schemes.
   *
   * @return comma-separated list in tls-test-tool tuple syntax
   */
  public static String nonRecommendedSchemeHexValues() {
    return Arrays.stream(values())
        .filter(scheme -> scheme.getTls13Policy() == Tls13Policy.LEGACY_DISALLOWED)
        .map(TlsSignatureSchemes::getHexValue)
        .collect(Collectors.joining(","));
  }

  /**
   * Resolves a TLS-1.3 signature scheme from the two-byte algorithm value logged in CertificateVerify.
   *
   * @param firstByte  first logged hex byte
   * @param secondByte second logged hex byte
   * @return matching TLS-1.3 signature scheme, or throws if unknown
   */
  public static TlsSignatureSchemes fromAlgorithmBytes(String firstByte, String secondByte) {
    var tuple = "(0x%s,0x%s)".formatted(firstByte.toUpperCase(Locale.ROOT), secondByte.toUpperCase(Locale.ROOT));
    return Arrays.stream(values())
        .filter(scheme -> scheme.getHexValue().equalsIgnoreCase(tuple))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Unsupported TLS 1.3 signature scheme: " + tuple));
  }

  /**
   * Returns the hash algorithm associated with this TLS-1.3 signature scheme.
   *
   * @return associated TLS hash algorithm, or {@link TlsHashAlgorithm#UNKNOWN} if it cannot be derived
   */
  public TlsHashAlgorithm getAssociatedHashAlgorithm() {
    var normalizedName = schemeName.toLowerCase(Locale.ROOT);
    if (normalizedName.endsWith("_sha1")) {
      return TlsHashAlgorithm.SHA1;
    }
    if (normalizedName.endsWith("_sha224")) {
      return TlsHashAlgorithm.SHA224;
    }
    if (normalizedName.endsWith("_sha256")) {
      return TlsHashAlgorithm.SHA256;
    }
    if (normalizedName.endsWith("_sha384")) {
      return TlsHashAlgorithm.SHA384;
    }
    if (normalizedName.endsWith("_sha512")) {
      return TlsHashAlgorithm.SHA512;
    }
    return TlsHashAlgorithm.UNKNOWN;
  }

  /**
   * Checks whether this TLS-1.3 signature scheme uses one of the provided hash algorithms.
   *
   * @param hashAlgorithms supported hash algorithms
   * @return {@code true} if the scheme name matches any provided hash algorithm
   */
  private boolean matchesAnyHash(Set<TlsHashAlgorithm> hashAlgorithms) {
    if (hashAlgorithms == null || hashAlgorithms.isEmpty()) {
      return false;
    }
    return hashAlgorithms.stream().anyMatch(this::matchesHash);
  }

  /**
   * Checks whether this TLS-1.3 signature scheme uses the provided hash algorithm.
   *
   * @param hashAlgorithm supported hash algorithm
   * @return {@code true} if the scheme name ends with the hash suffix
   */
  private boolean matchesHash(TlsHashAlgorithm hashAlgorithm) {
    if (hashAlgorithm == null || hashAlgorithm == TlsHashAlgorithm.UNKNOWN || hashAlgorithm == TlsHashAlgorithm.SUPPORTED_MIX) {
      return false;
    }
    return getAssociatedHashAlgorithm() == hashAlgorithm;
  }

  /**
   * TLS-1.3-specific recommendation classification for signature schemes.
   */
  public enum Tls13Policy {
    RECOMMENDED,
    LEGACY_DISALLOWED,
    CURVE_DISALLOWED,
    FORBIDDEN,
    NA
  }
}
