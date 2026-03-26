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

import static java.nio.charset.StandardCharsets.UTF_8;

import de.gematik.zeta.services.model.CommandResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Provides functionality to execute system commands in a separate process.
 */
@Slf4j
public class SystemCommandService implements AutoCloseable {

  public static final String WIN_PATH_USERS = "C:\\Users\\";
  public static final String WSL_PATH_USERS = "/mnt/c/Users/";
  public static final String WSL = "wsl";

  private final ThreadPoolTaskExecutor executor = setupExecutor();
  private final int processTimeoutSeconds;

  /**
   * Constructor for SystemCommandService.
   *
   * @param processTimeoutSeconds max timeout for system commands
   */
  public SystemCommandService(int processTimeoutSeconds) {
    this.processTimeoutSeconds = processTimeoutSeconds;
  }

  /**
   * Executes the given command in a new process and returns the result.
   *
   * @param command Full command to be passed to process
   * @return Execution result of given command
   */
  public CommandResult executeCommand(List<String> command) throws AssertionError {
    return executeCommand(command, true);
  }

  /**
   * Executes the given command in a new process and returns the result.
   *
   * @param command Full command to be passed to process
   * @param verbose logging of response/error messages
   * @return Execution result of given command
   */
  public CommandResult executeCommand(List<String> command, boolean verbose) {
    String commandLine = String.join(" ", command);

    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    if (isWindows) {
      command.add(0, WSL);

      // adapt path in command
      command = command.stream()
          .map(s -> s.contains(WIN_PATH_USERS)
              ? s.replace(WIN_PATH_USERS, WSL_PATH_USERS).replace("\\", "/")
              : s)
          .toList();

      // adapt path in logging string
      commandLine = String.join(" ", command);
    }
    log.debug("Trying to execute: {}", commandLine);

    Process process;
    try {
      process = new ProcessBuilder(command).start();
    } catch (IOException e) {
      throw new AssertionError("Failed to start command: " + commandLine, e);
    }

    Future<String> stdoutFuture;
    Future<String> stderrFuture;
    int exitCode;

    try {
      stdoutFuture = executor.submit(() -> readStream(process.getInputStream()));
      stderrFuture = executor.submit(() -> readStream(process.getErrorStream()));

      boolean completed = process.waitFor(processTimeoutSeconds, TimeUnit.SECONDS);
      if (completed) {
        exitCode = process.exitValue();
      } else {
        process.destroy();
        if (process.isAlive()) {
          process.destroyForcibly();
        }
        throw new AssertionError(String.format("Process exceeded timeout limit of %d seconds", processTimeoutSeconds));
      }
    } catch (AssertionError e) {
      // rethrow AssertionError explicitly to propagate process wait imeout event
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new AssertionError(String.format("Command interrupted: %s", commandLine), e);
    } catch (Exception e) {
      throw new AssertionError(String.format("Unexpected error executing command: %s", commandLine), e);
    }

    String stdout = getFutureValue(stdoutFuture, "stdout", commandLine);
    String stderr = getFutureValue(stderrFuture, "stderr", commandLine);

    log.debug("Command exit code: {}", exitCode);
    if (!stdout.isBlank() && verbose) {
      log.trace("Command stdout:\n{}", stdout);
    }
    if (!stderr.isBlank() && verbose) {
      if (exitCode == 0) {
        log.warn("Command stderr:\n{}", stderr);
      } else {
        log.error("Command stderr:\n{}", stderr);
      }
    }

    return new CommandResult(List.copyOf(command), exitCode, stdout, stderr);
  }

  /**
   * Ensures proper shutdown of executor.
   * */
  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.getThreadPoolExecutor().awaitTermination(processTimeoutSeconds, TimeUnit.SECONDS)) {
        executor.getThreadPoolExecutor().shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.getThreadPoolExecutor().shutdownNow();
    }
  }

  /**
   * Configure executor for command processes.
   *
   * @return Configured ThreadPoolTaskExecutor
   */
  private ThreadPoolTaskExecutor setupExecutor() {
    var e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(2);
    e.setMaxPoolSize(2);
    e.setQueueCapacity(25);
    e.setThreadNamePrefix("SystemCommandProcess-");
    e.setWaitForTasksToCompleteOnShutdown(true);
    e.setAwaitTerminationSeconds(120);
    e.initialize();
    return e;
  }

  /**
   * Get the actual value from a Future object.
   *
   * @param future Target Future object
   * @param streamName Name of the Stream for context reference
   * @param commandLine Original command for context reference
   * @return Value of the given Future object
   */
  private String getFutureValue(Future<String> future, String streamName, String commandLine) throws AssertionError {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Command " + streamName + " read interrupted: " + commandLine, e);
    } catch (ExecutionException e) {
      throw new AssertionError("Command " + streamName + " read failed: " + commandLine, e);
    }
  }

  /**
   * Reads the content of given InputStream.
   *
   * @param inputStream Target InputStream
   * @return String content of given InputStream
   * @throws IOException if InputStream could not be read
   */
  private String readStream(InputStream inputStream) throws IOException {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(inputStream, UTF_8))) {
      StringBuilder content = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (!content.isEmpty()) {
          content.append('\n');
        }
        content.append(line);
      }
      return content.toString();
    }
  }
}
