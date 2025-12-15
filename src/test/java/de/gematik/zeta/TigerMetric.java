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

package de.gematik.zeta;

import lombok.Getter;

/**
 * Tiger trace metrics as written by analysis CSV.
 *
 * <p>Columns present in the CSV: trace_id, path, e2e_ms, service_ms, middleware_overhead_ms,
 * ingress_request_ms, ingress_response_ms, egress_request_ms, egress_response_ms, forward_ms,
 * return_ms
 *
 * <p>Notes on calculations: forward_ms  = egress_request_ms  - ingress_request_ms service_ms  =
 * egress_response_ms - egress_request_ms return_ms   = ingress_response_ms - egress_response_ms
 * e2e_ms      = ingress_response_ms - ingress_request_ms middleware_overhead_ms = e2e_ms -
 * (forward_ms + service_ms + return_ms)
 */
@Getter
public enum TigerMetric {

  /**
   * End-to-end latency in milliseconds seen at ingress: from the moment the request arrived at
   * ingress until the response left ingress. Calculation: ingress_response_ms - ingress_request_ms
   */
  E2E_MS("e2e_ms"),

  /**
   * Time in milliseconds to forward the request from ingress to the backend egress. Calculation:
   * egress_request_ms - ingress_request_ms
   */
  FORWARD_MS("forward_ms"),

  /**
   * Pure backend/service processing time in milliseconds: from the moment the backend received the
   * request until it produced the response. Calculation: egress_response_ms - egress_request_ms
   */
  SERVICE_MS("service_ms"),

  /**
   * Time in milliseconds to return the response from backend egress back to ingress/client.
   * Calculation: ingress_response_ms - egress_response_ms
   */
  RETURN_MS("return_ms"),

  /**
   * Middleware overhead in milliseconds not accounted for by forward/service/return, e.g., proxy
   * internal processing, queuing, encoding/decoding, etc. Calculation: e2e_ms - (forward_ms +
   * service_ms + return_ms)
   */
  MIDDLEWARE_OVERHEAD_MS("middleware_overhead_ms");

  /**
   * -- GETTER -- Returns the exact CSV column name.
   */
  private final String columnName;

  TigerMetric(String columnName) {
    this.columnName = columnName;
  }

}
