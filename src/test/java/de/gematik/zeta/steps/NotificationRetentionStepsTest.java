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

package de.gematik.zeta.steps;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import de.gematik.zeta.services.NotificationDispatchProfile;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class NotificationRetentionStepsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long NOW = 1790000000000L;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cleanupPreservesProfileFailuresAndSupportsRetry(boolean profileFails) {
    var profileFailure = new AssertionError("profile cleanup");
    var profile = mock(NotificationDispatchProfile.class);
    if (profileFails) {
      doThrow(profileFailure).doNothing().when(profile).close();
    }
    var steps = new NotificationRetentionSteps();
    ReflectionTestUtils.setField(steps, "profile", profile);
    if (profileFails) {
      assertSame(profileFailure, assertThrows(AssertionError.class, steps::cleanup));
    } else {
      assertDoesNotThrow(steps::cleanup);
    }
    Mockito.verify(profile).close();
    assertDoesNotThrow(steps::cleanup);
    Mockito.verify(profile, times(profileFails ? 2 : 1)).close();
    assertDoesNotThrow(steps::cleanup);
    verifyNoMoreInteractions(profile);
  }

  @Test
  void processingEvidenceMustContainTheExactAcceptedNotification() {
    var id = "f1815c13-4165-4429-852c-94131c87fa78";
    var line = "INFO Notification " + id + " via https://gateway.invalid/: status=FAILED";
    assertDoesNotThrow(() -> NotificationRetentionSteps.verifyProcessingLog(line, id));
    for (var invalid : new String[] {"", line.replace(id, "other-id"),
        line.replace(id, id + "-other"), line.replace("Notification ", "Incoming request ")}) {
      assertThrows(AssertionError.class,
          () -> NotificationRetentionSteps.verifyProcessingLog(invalid, id));
    }
  }

  @Test
  void roundingAllowanceNeverExtendsTheDeclaredMaximumStorage() {
    for (long lifetime : new long[] {120000, 121000, 122000}) {
      var snapshot = JSON.createObjectNode();
      snapshot.putArray("records").addObject()
          .put("accepted_at", Instant.ofEpochMilli(NOW).toString())
          .put("expires_at", NOW + lifetime);
      long deadline = NotificationRetentionSteps.physicalDeadline(snapshot);
      assertEquals(NOW + 125000, deadline);
      var absent = JSON.createObjectNode().put("observed_at_ms", deadline);
      absent.putArray("records");
      assertDoesNotThrow(() -> NotificationRetentionSteps.verifyPurged(absent, deadline));
      absent.put("observed_at_ms", deadline + 1);
      assertThrows(AssertionError.class,
          () -> NotificationRetentionSteps.verifyPurged(absent, deadline));
      absent.put("observed_at_ms", NOW + 126000);
      assertThrows(AssertionError.class,
          () -> NotificationRetentionSteps.verifyPurged(absent, deadline));
      absent.put("observed_at_ms", deadline).putArray("records").addObject();
      assertThrows(AssertionError.class,
          () -> NotificationRetentionSteps.verifyPurged(absent, deadline));
    }
  }

  @Test
  void rejectsMissingForeignExpiredOrUnboundedProcessingData() {
    var good = JSON.createObjectNode().put("observed_at_ms", NOW);
    var row =
        good.putArray("records")
            .addObject()
            .put("notification_id", "own-id")
            .put("correlation", "own-event")
            .put("status", "accepted")
            .put("accepted_at", Instant.ofEpochMilli(NOW).toString())
            .put("expires_at", NOW + 120000);
    assertDoesNotThrow(() -> verify(good));
    row.put("status", "queued");
    assertDoesNotThrow(() -> verify(good));
    row.putNull("status");
    assertDoesNotThrow(() -> verify(good));
    for (String mutation :
        new String[] {
          "missing",
          "foreign",
          "wrong-marker",
          "wrong-status",
          "unbounded",
          "wrong-ttl",
          "clock",
          "stale"
        }) {
      var copy = good.deepCopy();
      var changed = (ObjectNode) copy.path("records").get(0);
      switch (mutation) {
        case "missing" -> copy.putArray("records");
        case "foreign" -> changed.put("notification_id", "someone-else");
        case "wrong-marker" -> changed.put("correlation", "someone-else");
        case "wrong-status" -> changed.put("status", "unknown");
        case "unbounded" -> changed.putNull("expires_at");
        case "wrong-ttl" -> changed.put("expires_at", NOW + 86400000);
        case "clock" -> copy.put("observed_at_ms", NOW - 20000);
        case "stale" -> changed.put("accepted_at", Instant.ofEpochMilli(NOW - 20000).toString());
        default -> throw new AssertionError();
      }
      assertThrows(AssertionError.class, () -> verify(copy), mutation);
    }
  }

  private static void verify(ObjectNode value) {
    NotificationRetentionSteps.verifyAccepted(value, "own-id", "own-event", NOW, NOW);
  }
}
