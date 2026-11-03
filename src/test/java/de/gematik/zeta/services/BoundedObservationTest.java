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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedObservationTest {
  private static final Duration BOUND = Duration.ofMillis(10);
  private static final Duration INTERVAL = Duration.ofMillis(2);

  @Test
  void bufferedDecisionUploadCanArriveAfterTenMinutesWithinItsOriginalBudget() throws Exception {
    var clock = new AtomicLong();
    var reads = new AtomicLong();
    assertEquals(true, BoundedObservation.awaitUntil(
        () -> {
          reads.incrementAndGet();
          return clock.get() >= Duration.ofSeconds(605).toNanos();
        }, value -> value, Duration.ofSeconds(660).toNanos(), Duration.ofSeconds(5),
        "missing decision log", clock::get,
        millis -> clock.addAndGet(Duration.ofMillis(millis).toNanos())));
    assertEquals(122, reads.get());
    assertEquals(Duration.ofSeconds(605).toNanos(), clock.get());
  }

  @Test
  void subsequentObservationDoesNotRestartTheSharedBudget() throws Exception {
    var clock = new AtomicLong(Duration.ofMillis(8).toNanos());
    assertEquals(
        "first",
        BoundedObservation.awaitUntil(
            () -> "first", v -> true, BOUND.toNanos(), INTERVAL, "timeout", clock::get,
            millis -> fail("unexpected sleep")));
    assertThrows(
        BoundedObservation.ObservationTimeoutException.class,
        () -> BoundedObservation.awaitUntil(
            () -> {
              clock.addAndGet(Duration.ofMillis(3).toNanos());
              return true;
            }, v -> v, BOUND.toNanos(), INTERVAL, "timeout", clock::get,
            millis -> fail("unexpected sleep")));
  }

  @Test
  void expiredSharedBudgetDoesNotStartAnotherRead() {
    assertThrows(
        BoundedObservation.ObservationTimeoutException.class,
        () -> BoundedObservation.awaitUntil(
            () -> fail("read after deadline"), v -> true, BOUND.toNanos(), INTERVAL,
            "timeout", () -> BOUND.toNanos(), millis -> fail("unexpected sleep")));
  }

  @Test
  void delayedResultIsReturnedWithoutRestartingDeadline() throws Exception {
    var clock = new AtomicLong();
    var reads = new AtomicLong();
    assertEquals(
        3L,
        BoundedObservation.await(
            reads::incrementAndGet,
            n -> n == 3,
            BOUND,
            INTERVAL,
            "timeout",
            clock::get,
            millis -> clock.addAndGet(Duration.ofMillis(millis).toNanos())));
    assertEquals(Duration.ofMillis(4).toNanos(), clock.get());
  }

  @Test
  void successfulReadCompletingAfterDeadlineIsRejected() {
    var clock = new AtomicLong();
    assertThrows(
        BoundedObservation.ObservationTimeoutException.class,
        () ->
            BoundedObservation.await(
                () -> {
                  clock.set(BOUND.plusMillis(1).toNanos());
                  return true;
                },
                n -> n,
                BOUND,
                INTERVAL,
                "timeout",
                clock::get,
                millis -> fail("unexpected sleep")));
  }

  @Test
  void continuouslyIncompleteObservationExpires() {
    var clock = new AtomicLong();
    assertThrows(
        BoundedObservation.ObservationTimeoutException.class,
        () ->
            BoundedObservation.await(
                () -> false,
                n -> n,
                BOUND,
                INTERVAL,
                "timeout",
                clock::get,
                millis -> clock.addAndGet(Duration.ofMillis(millis).toNanos())));
    assertEquals(BOUND.toNanos(), clock.get());
  }

  @Test
  void readFailureAndInterruptionAreNotRetriedOrSwallowed() {
    var failure = new IllegalStateException("read failure");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                BoundedObservation.await(
                    () -> {
                      throw failure;
                    },
                    n -> false,
                    BOUND,
                    INTERVAL,
                    "timeout",
                    () -> 0,
                    millis -> fail("unexpected retry"))));
    assertThrows(
        InterruptedException.class,
        () ->
            BoundedObservation.await(
                () -> false,
                n -> n,
                BOUND,
                INTERVAL,
                "timeout",
                () -> 0,
                millis -> {
                  throw new InterruptedException();
                }));
  }
}
