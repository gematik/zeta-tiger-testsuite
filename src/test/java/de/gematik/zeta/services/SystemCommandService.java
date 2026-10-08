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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Provides functionality to execute system commands in a separate process.
 */
@Slf4j
public class SystemCommandService implements AutoCloseable {

  public static final String WIN_PATH_USERS = "C:\\Users\\";
  public static final String WSL_PATH_USERS = "/mnt/c/Users/";
  public static final String WSL = "wsl";
  private static final String WSL_CHANGE_DIRECTORY_ARGUMENT = "--cd";
  private static final String WSL_EXEC_ARGUMENT = "--exec";
  public static final String PROCESS_TIMEOUT_MESSAGE_PREFIX = "Process exceeded timeout limit";

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
   * @throws AssertionError if errors occur during command execution
   */
  public CommandResult executeCommand(List<String> command) throws AssertionError {
    return executeCommand(command, true);
  }

  /**
   * Executes the given command in a new process and returns the result.
   *
   * @param command Full command to be passed to process
   * @param workDir target workdir where the command should be invoked
   * @return Execution result of given command
   * @throws AssertionError if errors occur during command execution
   */
  public CommandResult executeCommand(List<String> command, String workDir) throws AssertionError {
    return executeCommand(command, true, workDir, new HashMap<>());
  }

  /**
   * Executes the given command in a new process and returns the result.
   *
   * @param command Full command to be passed to process
   * @param workDir target workdir where the command should be invoked
   * @param environmentVariables set of environment variables for the command process
   * @return Execution result of given command
   * @throws AssertionError if errors occur during command execution
   */
  public CommandResult executeCommand(List<String> command, String workDir, Map<String, String> environmentVariables)
      throws AssertionError {
    return executeCommand(command, true, workDir, environmentVariables);
  }

  /**
   * Executes the given command in a new process and returns the result.

   * @param command Full command to be passed to process
   * @param verbose logging of response/error messages
   * @return Execution result of given command
   * @throws AssertionError if errors occur during command execution
   */
  public CommandResult executeCommand(List<String> command, boolean verbose) throws AssertionError {
    return executeCommand(command, verbose, "", new HashMap<>());
  }

  /**
   * Executes the given command in a new process and returns the result.
   *
   * @param command Full command to be passed to process
   * @param verbose logging of response/error messages
   * @param workDir target workdir where the command should be invoked
   * @param environmentVariables set of environment variables for the command process
   * @return Execution result of given command
   * @throws AssertionError if errors occur during command execution
   */
  public CommandResult executeCommand(List<String> command, boolean verbose, String workDir,
                                      Map<String, String> environmentVariables)
      throws AssertionError {
    String commandLine = String.join(" ", command);

    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    ProcessBuilder builder = new ProcessBuilder();
    if (environmentVariables != null) {
      builder.environment().putAll(environmentVariables);
    }

    boolean shouldChangeWorkDir = workDir != null && !workDir.isBlank();

    if (isWindows) {
      command = prepareWindowsCommand(command, workDir);
      commandLine = String.join(" ", command);
      if (shouldChangeWorkDir) {
        log.debug("Trying to execute following command in directory '{}': {}", workDir, commandLine);
      }

      // make sure to pass all vars to process in WSL via WSLENV
      builder.environment().putAll(prepareWslEnvVariable(environmentVariables));
    } else {
      if (shouldChangeWorkDir) {
        builder.directory(new File(workDir));
      }
    }

    builder.command(command);
    Process process;
    try {
      process = builder.start();
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
        throw new AssertionError(String.format(
            "%s of %d seconds", PROCESS_TIMEOUT_MESSAGE_PREFIX, processTimeoutSeconds));
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
   * Builds a WSL command that avoids Linux shell interpretation of the original arguments.
   *
   * <p>{@code wsl --exec} forwards the command as an argument vector. This is required for
   * arguments such as SQL statements and pipe characters.
   * {@code wsl --cd} replaces the previous shell-based working-directory workaround.</p>
   *
   * @param command command and arguments to execute inside WSL
   * @param workDir optional working directory
   * @return command suitable for a Windows {@link ProcessBuilder}
   */
  static List<String> prepareWindowsCommand(final List<String> command, final String workDir) {
    var wslCommand = new ArrayList<String>();
    wslCommand.add(WSL);
    if (workDir != null && !workDir.isBlank()) {
      wslCommand.add(WSL_CHANGE_DIRECTORY_ARGUMENT);
      wslCommand.add(adaptWindowsPathForWsl(workDir));
    }
    wslCommand.add(WSL_EXEC_ARGUMENT);
    command.stream()
        .map(SystemCommandService::adaptWindowsPathForWsl)
        .forEach(wslCommand::add);
    return List.copyOf(wslCommand);
  }

  /**
   * Converts a Windows user-directory path to its WSL mount path.
   *
   * @param value command argument or working directory
   * @return value with a WSL-compatible path when conversion is required
   */
  private static String adaptWindowsPathForWsl(final String value) {
    return value.contains(WIN_PATH_USERS)
        ? value.replace(WIN_PATH_USERS, WSL_PATH_USERS).replace("\\", "/")
        : value;
  }

  private Map<String, String> prepareWslEnvVariable(Map<String, String> targetEnvVars) {
    var m = new HashMap<String, String>();
    if (targetEnvVars == null || targetEnvVars.isEmpty()) {
      return m;
    }

    StringBuilder sb = new StringBuilder();
    for (var key : targetEnvVars.keySet()) {
      sb.append(key);
      sb.append(":");
    }
    var wslEnvStr = sb.toString();
    wslEnvStr = wslEnvStr.substring(0, wslEnvStr.length() - 1);
    m.put("WSLENV", wslEnvStr);
    return m;
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
