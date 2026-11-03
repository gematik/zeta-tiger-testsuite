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

package de.gematik.zeta.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.data.core.TracingMessagePairFacet;
import io.restassured.builder.ResponseBuilder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DriverControlClientTest {
  @Test
  void retainsServiceRejectionsSeparatelyFromDriverSuccess() {
    var reply = parse("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\n"
        + "Date: Sun, 04 Oct 2026 10:00:00 GMT\r\n\r\n"
        + "{\"error\":\"refresh_token_revoked\"}");
    var downstream = DriverControlClient.downstream(reply);
    assertEquals(403, downstream.statusCode());
    assertEquals("{\"error\":\"refresh_token_revoked\"}", downstream.asString());
    assertEquals("Sun, 04 Oct 2026 10:00:00 GMT", downstream.getHeader("Date"));
  }

  @Test
  void neverTreatsDriverFailuresAsProductRejections() {
    for (int status : new int[] {400, 409, 500}) {
      var reply =
          new ResponseBuilder()
              .setStatusCode(status)
              .setBody("{\"status\":403,\"body\":\"expected error\"}")
              .build();
      assertThrows(AssertionError.class, () -> DriverControlClient.decode(reply));
    }
    assertThrows(
        AssertionError.class,
        () ->
            DriverControlClient.decode(
                new ResponseBuilder()
                    .setStatusCode(200)
                    .setBody("{\"adapterError\":\"IOException\"}")
                    .build()));
    for (var body :
        new String[] {
          "{}",
          "{\"status\":200}",
          "{\"status\":\"200\",\"body\":\"ok\"}",
          "{\"status\":700,\"body\":\"ok\"}", "{\"completed\":false}"
        }) {
      assertThrows(AssertionError.class, () -> DriverControlClient.decode(
          new ResponseBuilder().setStatusCode(200).setBody(body).build()));
    }
  }

  @Test
  void selectsOnlyFreshRequestsAndTheirCorrelatedResponses() {
    var stale = request();
    var current = request();
    var unrelated = parse("HTTP/1.1 200 OK\r\n\r\nwrong response");
    var rejected = parse("HTTP/1.1 403 Forbidden\r\n\r\nactual response");
    pair(stale, unrelated);
    assertNull(DriverControlClient.selectResponse(
        List.of(stale, unrelated, current), Set.of(stale.getUuid()), "POST", "/token"));
    pair(current, rejected);
    assertEquals(rejected, DriverControlClient.selectResponse(
        List.of(stale, unrelated, current, rejected),
        Set.of(stale.getUuid(), unrelated.getUuid()), "POST", "/token"));
    assertThrows(AssertionError.class, () -> DriverControlClient.selectResponse(
        List.of(current, rejected, request()), Set.of(), "POST", "/token"));
    assertThrows(AssertionError.class, () -> DriverControlClient.selectResponse(
        List.of(current, rejected), Set.of(rejected.getUuid()), "POST", "/token"));
  }

  @Test
  void preservesDuplicateDateHeadersForClockValidation() {
    var reply = parse("HTTP/1.1 200 OK\r\n"
        + "Date: Sun, 04 Oct 2026 10:00:00 GMT\r\n"
        + "Date: Sun, 04 Oct 2026 10:00:01 GMT\r\n\r\n{}");
    assertEquals(2, DriverControlClient.downstream(reply).getHeaders().getValues("Date").size());
  }

  private static RbelElement request() {
    return parse("POST /token HTTP/1.1\r\nHost: as.example\r\n"
        + "Content-Type: application/x-www-form-urlencoded\r\n\r\ngrant_type=refresh_token");
  }

  private static RbelElement parse(String message) {
    var parts = message.split("\r\n\r\n", 2);
    message = parts[0] + "\r\nContent-Length: "
        + parts[1].getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + parts[1];
    return RbelLogger.build().getRbelConverter().parseMessage(
        message.getBytes(StandardCharsets.UTF_8), new RbelMessageMetadata());
  }

  private static void pair(RbelElement request, RbelElement response) {
    var pair = TracingMessagePairFacet.builder().request(request)
        .responses(List.of(response)).build();
    request.addFacet(pair);
    response.addFacet(pair);
  }
}
