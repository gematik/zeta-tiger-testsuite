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

package de.gematik.zeta.testdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.CoroutineSingletons;
import org.junit.jupiter.api.Test;

class SdkReflectionTest {
  public static class Operations {
    final CountDownLatch interrupted = new CountDownLatch(1);
    volatile Continuation<Object> pending;

    public Object immediate(String value, Continuation<Object> continuation) {
      return value;
    }

    public Object absent(Continuation<Object> continuation) {
      return null;
    }

    public Object checked(Continuation<Object> continuation) throws IOException {
      throw new IOException("original");
    }

    public Object resumed(Continuation<Object> continuation) {
      continuation.resumeWith("resumed");
      return CoroutineSingletons.COROUTINE_SUSPENDED;
    }

    public Object failed(Continuation<Object> continuation) {
      continuation.resumeWith(ResultKt.createFailure(new IOException("original")));
      return CoroutineSingletons.COROUTINE_SUSPENDED;
    }

    public Object waiting(Continuation<Object> continuation) {
      pending = continuation;
      return CoroutineSingletons.COROUTINE_SUSPENDED;
    }

    public Object blocking(Continuation<Object> continuation) throws InterruptedException {
      try {
        new CountDownLatch(1).await();
        return null;
      } catch (InterruptedException failure) {
        interrupted.countDown();
        throw failure;
      }
    }

    public Object overloaded(String value) {
      return value;
    }

    public Object overloaded(Integer value) {
      return value;
    }
  }

  @Test
  void preservesSynchronousAndSuspendedResultsIncludingNull() throws Exception {
    var operations = new Operations();
    assertEquals("immediate", SdkReflection.suspend(operations, "immediate", "immediate"));
    assertEquals("resumed", SdkReflection.suspend(operations, "resumed"));
    assertNull(SdkReflection.suspend(operations, "absent"));
  }

  @Test
  void propagatesOriginalExceptionsSoOnlyAuthenticationFailuresCanBeClassified() {
    var operations = new Operations();
    assertEquals(
        "original",
        assertThrows(IOException.class, () -> SdkReflection.suspend(operations, "checked"))
            .getMessage());
    assertEquals(
        "original",
        assertThrows(IOException.class, () -> SdkReflection.suspend(operations, "failed"))
            .getMessage());
  }

  @Test
  void boundsSynchronousEntryAndInterruptsOwnedWorker() throws Exception {
    var operations = new Operations();
    assertThrows(
        TimeoutException.class, () -> SdkReflection.suspendWithin(100, operations, "blocking"));
    assertTrue(operations.interrupted.await(2, TimeUnit.SECONDS));
  }

  @Test
  void neverAcceptsLateSuspendedResultAfterDeadline() {
    var operations = new Operations();
    assertThrows(
        TimeoutException.class, () -> SdkReflection.suspendWithin(100, operations, "waiting"));
    operations.pending.resumeWith("too-late");
  }

  @Test
  void rejectsAmbiguousCallsAndInvalidDeadlines() {
    var operations = new Operations();
    assertThrows(
        AssertionError.class, () -> SdkReflection.call(operations, "overloaded", (Object) null));
    assertThrows(AssertionError.class, () -> SdkReflection.suspendWithin(0, operations, "resumed"));
    assertThrows(
        AssertionError.class, () -> SdkReflection.suspendWithin(25001, operations, "resumed"));
  }

  @Test
  void zeroArgumentClockAndCallbackObjectsDoNotExposeCapturedData() throws Exception {
    var clock =
        SdkReflection.function(
            0,
            args -> {
              assertEquals(0, args.length);
              return 123L;
            });
    assertEquals(123L, SdkReflection.call(clock, "invoke"));
    assertEquals("ControlledSdkCallback", clock.toString());
    assertEquals(System.identityHashCode(clock), clock.hashCode());
    assertTrue(clock.equals(clock));
  }
}
