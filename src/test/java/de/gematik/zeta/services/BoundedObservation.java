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

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Polls read-only observations without accepting a result after the original deadline.
 */
public final class BoundedObservation {
  private BoundedObservation() {}

  /** Signals exhaustion of an observation deadline without converting failures of the read. */
  public static final class ObservationTimeoutException extends AssertionError {
    private static final long serialVersionUID = 1L;

    private ObservationTimeoutException(String message) {
      super(message);
    }
  }

  /**
   * Keeps transport failures visible and propagates interruption to the scenario.
   */
  public static <T> T await(
      Supplier<T> read, Predicate<T> complete, Duration bound, Duration interval, String failure)
      throws InterruptedException {
    return await(read, complete, bound, interval, failure, System::nanoTime, Thread::sleep);
  }

  static <T> T await(
      Supplier<T> read,
      Predicate<T> complete,
      Duration bound,
      Duration interval,
      String failure,
      LongSupplier clock,
      Sleeper sleep)
      throws InterruptedException {
    if (bound.isNegative() || bound.isZero() || interval.toMillis() < 1) {
      throw new IllegalArgumentException(
          "Positive observation bound and polling interval required");
    }
    return awaitUntil(
        read, complete, clock.getAsLong() + bound.toNanos(), interval, failure, clock, sleep);
  }

  /**
   * Shares an existing nanoTime deadline across consecutive observations of one operation.
   */
  public static <T> T awaitUntil(
      Supplier<T> read, Predicate<T> complete, long deadline, Duration interval, String failure)
      throws InterruptedException {
    return awaitUntil(read, complete, deadline, interval, failure, System::nanoTime, Thread::sleep);
  }

  static <T> T awaitUntil(
      Supplier<T> read,
      Predicate<T> complete,
      long deadline,
      Duration interval,
      String failure,
      LongSupplier clock,
      Sleeper sleep)
      throws InterruptedException {
    if (interval.toMillis() < 1) {
      throw new IllegalArgumentException("Positive polling interval required");
    }
    while (clock.getAsLong() < deadline) {
      var value = read.get();
      if (complete.test(value)) {
        if (clock.getAsLong() > deadline) {
          throw new ObservationTimeoutException(failure);
        }
        return value;
      }
      sleep.sleep(interval.toMillis());
    }
    throw new ObservationTimeoutException(failure);
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(long milliseconds) throws InterruptedException;
  }
}
