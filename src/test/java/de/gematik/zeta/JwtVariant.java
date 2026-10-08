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

package de.gematik.zeta;

import java.util.Locale;

/**
 * Supported JWT manipulation variants used by the TigerProxy JWT manipulation endpoint.
 */
public enum JwtVariant {
  TWO_SEGMENTS,
  ALG_NONE,
  JWE_LIKE_FIVE_SEGMENTS,
  INVALID_HEADER_BASE64URL,
  INVALID_PAYLOAD_BASE64URL,
  INVALID_SIGNATURE_BASE64URL,
  INVALID_HEADER_JSON,
  INVALID_PAYLOAD_JSON,
  INVALID_HEADER_JSON_UNQUOTED_KEYS,
  INVALID_PAYLOAD_JSON_UNQUOTED_KEYS,
  UNKNOWN_HEADER_PARAMETER,
  UNSUPPORTED_CRIT,
  UNSUPPORTED_ALG,
  MISSING_ALG,
  DUPLICATE_ALG_HEADERS,
  RESIGN_CORRECT_KEY,
  INVALID_SIGNATURE,
  NESTED_CTY_JWT_VALID_INNER,
  NESTED_CTY_JWT_INVALID_INNER;

  /**
   * Pattern fragment matching all supported JWT variant tokens.
   */
  public static final String PATTERN =
      "two_segments|alg_none|jwe_like_five_segments|invalid_header_base64url|"
          + "invalid_payload_base64url|invalid_signature_base64url|invalid_header_json|"
          + "invalid_payload_json|invalid_header_json_unquoted_keys|"
          + "invalid_payload_json_unquoted_keys|unknown_header_parameter|unsupported_crit|"
          + "unsupported_alg|missing_alg|duplicate_alg_headers|resign_correct_key|"
          + "invalid_signature|nested_cty_jwt_valid_inner|nested_cty_jwt_invalid_inner";

  /**
   * Resolves a JWT variant token.
   *
   * @param token textual variant token from Gherkin
   * @return matching variant
   */
  public static JwtVariant fromToken(String token) {
    return JwtVariant.valueOf(token.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Returns true when this variant must preserve a valid local cryptographic signature.
   *
   * @return true for re-signed semantic/header variants
   */
  public boolean keepsSignature() {
    return switch (this) {
      case INVALID_HEADER_JSON_UNQUOTED_KEYS,
          INVALID_PAYLOAD_JSON_UNQUOTED_KEYS,
          UNKNOWN_HEADER_PARAMETER,
          UNSUPPORTED_CRIT,
          UNSUPPORTED_ALG,
          MISSING_ALG,
          DUPLICATE_ALG_HEADERS,
          NESTED_CTY_JWT_VALID_INNER,
          NESTED_CTY_JWT_INVALID_INNER,
          RESIGN_CORRECT_KEY -> true;
      default -> false;
    };
  }

  /**
   * Returns the Gherkin/TigerProxy token for this variant.
   *
   * @return lower-case underscore token
   */
  public String token() {
    return name().toLowerCase(Locale.ROOT);
  }
}
