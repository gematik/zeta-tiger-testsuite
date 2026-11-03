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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded bridge to the SDK installed in the testdriver, without a second SDK dependency. */
public final class SdkReflection {
  private SdkReflection() {}

  static Class<?> type(String name) throws ClassNotFoundException {
    return Class.forName(name);
  }

  static Object construct(String name, Object... args) throws Exception {
    var candidates =
        Arrays.stream(type(name).getConstructors())
            .filter(c -> matches(c.getParameterTypes(), args))
            .toList();
    require(candidates.size() == 1, "Ambiguous SDK constructor");
    return candidates.getFirst().newInstance(args);
  }

  static Object call(Object target, String name, Object... args) throws Exception {
    var owner = target instanceof Class<?> c ? c : target.getClass();
    var candidates =
        Arrays.stream(owner.getMethods())
            .filter(
                m ->
                    m.getName().equals(name)
                        && !m.isBridge()
                        && matches(m.getParameterTypes(), args))
            .toList();
    require(candidates.size() == 1, "Missing or ambiguous SDK method: " + name);
    return invoke(candidates.getFirst(), target instanceof Class<?> ? null : target, args);
  }

  private static boolean matches(Class<?>[] parameters, Object[] arguments) {
    if (parameters.length != arguments.length) {
      return false;
    }
    for (int i = 0; i < parameters.length; i++) {
      var expected = parameters[i] == long.class ? Long.class : parameters[i];
      expected = expected == boolean.class ? Boolean.class : expected;
      expected = expected == int.class ? Integer.class : expected;
      if (arguments[i] == null ? parameters[i].isPrimitive() : !expected.isInstance(arguments[i])) {
        return false;
      }
    }
    return true;
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Exception {
    try {
      require(method.trySetAccessible(), "SDK public operation is inaccessible");
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      return rethrow(failure.getCause());
    }
  }

  /** Reads configuration of this adapter's own SDK; never modifies product fields. */
  static Object ownField(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    var value = field.get(target);
    require(value != null, "Own SDK field not initialized: " + name);
    return value;
  }

  @FunctionalInterface
  interface Callback {
    Object invoke(Object[] arguments) throws Exception;
  }

  static Object function(int arity, Callback callback) throws Exception {
    return Proxy.newProxyInstance(
        SdkReflection.class.getClassLoader(),
        new Class<?>[] {type("kotlin.jvm.functions.Function" + arity)},
        (proxy, method, args) -> {
          if (method.getName().equals("invoke")) {
            return callback.invoke(args == null ? new Object[0] : args);
          }
          return objectMethod(proxy, method.getName(), args);
        });
  }

  static Object suspend(Object target, String name, Object... args) throws Exception {
    return suspendWithin(25000, target, name, args);
  }

  static Object suspendWithin(long milliseconds, Object target, String name, Object... args)
      throws Exception {
    require(milliseconds > 0 && milliseconds <= 25000, "Invalid SDK operation deadline");
    var pending = new CompletableFuture<Object>();
    var continuation =
        Proxy.newProxyInstance(
            SdkReflection.class.getClassLoader(),
            new Class<?>[] {type("kotlin.coroutines.Continuation")},
            (proxy, method, values) -> {
              if (method.getName().equals("getContext")) {
                return type("kotlin.coroutines.EmptyCoroutineContext")
                    .getField("INSTANCE")
                    .get(null);
              }
              if (method.getName().equals("resumeWith")) {
                try {
                  call(type("kotlin.ResultKt"), "throwOnFailure", values[0]);
                  pending.complete(values[0]);
                } catch (Exception | AssertionError failure) {
                  pending.completeExceptionally(failure);
                }
                return null;
              }
              return objectMethod(proxy, method.getName(), values);
            });
    var arguments = Arrays.copyOf(args, args.length + 1);
    arguments[args.length] = continuation;
    // Bounds both synchronous entry and a suspended result. The owner also bounds the entire JVM.
    var executor =
        Executors.newSingleThreadExecutor(
            r -> {
              var thread = new Thread(r, "bounded-sdk-operation");
              thread.setDaemon(true);
              return thread;
            });
    var invocation =
        executor.submit(
            () -> {
              try {
                var value = call(target, name, arguments);
                if (value
                    != type("kotlin.coroutines.intrinsics.CoroutineSingletons")
                        .getField("COROUTINE_SUSPENDED")
                        .get(null)) {
                  pending.complete(value);
                }
              } catch (Exception | AssertionError failure) {
                pending.completeExceptionally(failure);
              }
            });
    try {
      return pending.get(milliseconds, TimeUnit.MILLISECONDS);
    } catch (ExecutionException failure) {
      return rethrow(failure.getCause());
    } finally {
      invocation.cancel(true);
      executor.shutdownNow();
    }
  }

  private static Object rethrow(Throwable failure) throws Exception {
    if (failure instanceof Exception exception) {
      throw exception;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    throw new AssertionError("Unexpected SDK failure", failure);
  }

  private static Object objectMethod(Object proxy, String name, Object[] args) {
    return switch (name) {
      case "toString" -> "ControlledSdkCallback";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> throw new AssertionError("Unexpected SDK callback operation");
    };
  }

  static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
