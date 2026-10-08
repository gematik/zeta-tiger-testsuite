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

import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Minimal client for the load dispenser's gRPC control API. */
public final class LoadDispenserClient implements AutoCloseable {

  private static final String SERVICE = "load_dispenser.LoadDispenser";
  private static final int RPC_TIMEOUT_SECONDS = 15;
  private static final Set<String> VALID_STATUSES =
      Set.of("STOPPED", "SETUP", "RUNNING", "ABORTED", "FAILED");

  private static final MethodDescriptor<Empty, Empty> START = unary("Start", Empty.getDefaultInstance(),
      Empty.getDefaultInstance());
  private static final MethodDescriptor<Empty, Empty> ABORT = unary("Abort", Empty.getDefaultInstance(),
      Empty.getDefaultInstance());
  // The load dispenser exposes StatusReply.status as the single string field #1. Until generated
  // stubs are introduced, StringValue is used as the minimal compatible response type and the
  // decoded value is validated against the published status contract below.
  private static final MethodDescriptor<Empty, StringValue> STATUS = unary("Status", Empty.getDefaultInstance(),
      StringValue.getDefaultInstance());
  private static final MethodDescriptor<Struct, Struct> SET_CONFIG = unary("SetConfig", Struct.getDefaultInstance(),
      Struct.getDefaultInstance());

  private final ManagedChannel channel;

  /**
   * Opens a plaintext gRPC channel.
   *
   * @param endpoint load-dispenser address in {@code host:port} form
   */
  public LoadDispenserClient(String endpoint) {
    this.channel = ManagedChannelBuilder.forTarget(endpoint).usePlaintext().build();
  }

  /**
   * Replaces the previous per-run overrides.
   *
   * @param config complete configuration override for the next run
   * @return effective configuration including dispenser defaults
   */
  public Struct setConfig(Struct config) {
    return call(SET_CONFIG, config);
  }

  /** Starts a run asynchronously. */
  public void start() {
    call(START, Empty.getDefaultInstance());
  }

  /** Stops the active run, if any. */
  public void abort() {
    call(ABORT, Empty.getDefaultInstance());
  }

  /**
   * Returns the current dispenser status.
   *
   * @return {@code STOPPED}, {@code SETUP}, {@code RUNNING}, {@code ABORTED}, or {@code FAILED}
   */
  public String status() {
    var status = call(STATUS, Empty.getDefaultInstance()).getValue();
    if (!VALID_STATUSES.contains(status)) {
      throw new IllegalStateException("Unexpected load dispenser status: " + status);
    }
    return status;
  }

  @Override
  public void close() {
    channel.shutdown();
    try {
      if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
        channel.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      channel.shutdownNow();
    }
  }

  /**
   * Executes one blocking unary RPC with the common control deadline.
   *
   * @param method gRPC method descriptor
   * @param request protobuf request message
   * @param <T> request message type
   * @param <R> response message type
   * @return decoded response message
   */
  private <T, R> R call(MethodDescriptor<T, R> method, T request) {
    var options = CallOptions.DEFAULT.withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return ClientCalls.blockingUnaryCall(channel, method, options, request);
  }

  /**
   * Builds a protobuf unary method descriptor without generated service stubs.
   *
   * @param method method name within the load-dispenser service
   * @param requestDefault default request instance used by the protobuf marshaller
   * @param responseDefault default response instance used by the protobuf marshaller
   * @param <T> request message type
   * @param <R> response message type
   * @return reusable unary method descriptor
   */
  private static <T extends Message, R extends Message> MethodDescriptor<T, R> unary(
      String method, T requestDefault, R responseDefault) {
    return MethodDescriptor.<T, R>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, method))
        .setRequestMarshaller(ProtoUtils.marshaller(requestDefault))
        .setResponseMarshaller(ProtoUtils.marshaller(responseDefault))
        .build();
  }
}
