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

package de.gematik.zeta.services.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.gematik.zeta.services.ZetaDeploymentModificationService;
import de.gematik.zeta.services.model.CommandResult;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Regression checks for command time consuming the original rollout budget. */
public class DeploymentRolloutDeadlineTest {
  /** Even successful kubectl output cannot prove readiness after the deadline. */
  @Test
  public void latePodListDoesNotPass() {
    var service = new TimedService(
        new Reply(0, "1", 1), new Reply(0, "rolled out", 1),
        new Reply(0, "pep-new", 4));

    var result = service.waitForDeploymentRollout("ns", "pep", 5);

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("within 5 seconds"));
    assertEquals(3, service.commands);
    assertEquals(0, service.sleeps);
  }

  /** A timed-out rollout command cannot trigger a fresh pod observation or another attempt. */
  @Test
  public void commandTimeStopsFurtherPolling() {
    for (int status : List.of(0, 1)) {
      var service = new TimedService(new Reply(0, "1", 1), new Reply(status, "rollout", 5));

      var result = service.waitForDeploymentRollout("ns", "pep", 5);

      assertEquals(1, result.exitCode());
      assertEquals(2, service.commands);
      assertEquals(0, service.sleeps);
    }
  }

  /** The initial replica lookup is part of the same observation budget. */
  @Test
  public void slowReplicaLookupStopsBeforeRollout() {
    var service = new TimedService(new Reply(0, "1", 6));

    assertEquals(1, service.waitForDeploymentRollout("ns", "pep", 5).exitCode());
    assertEquals(1, service.commands);
  }

  /** A successful rollout still requires the complete expected replica count. */
  @Test
  public void timelyCompleteReplicaSetPasses() {
    var service = new TimedService(
        new Reply(0, "2", 1), new Reply(0, "rolled out", 1),
        new Reply(0, "pep-a\npep-b\nother-pod", 1));

    assertEquals(0, service.waitForDeploymentRollout("ns", "pep", 5).exitCode());
    assertEquals(3, service.commands);
  }

  /** Command time and polling intervals accumulate instead of resetting the deadline. */
  @Test
  public void incompleteReplicasCannotExtendTheBudget() {
    var service = new TimedService(
        new Reply(0, "2", 1), new Reply(0, "rolled out", 1),
        new Reply(0, "pep-a", 1));

    assertEquals(1, service.waitForDeploymentRollout("ns", "pep", 5).exitCode());
    assertEquals(3, service.commands);
    assertEquals(1, service.sleeps);
  }

  private record Reply(int status, String output, long seconds) {}

  private static final class TimedService extends ZetaDeploymentModificationService {
    private final Queue<Reply> replies;
    private long now;
    private int commands;
    private int sleeps;

    private TimedService(Reply... replies) {
      super(60, 5);
      this.replies = new ArrayDeque<>(List.of(replies));
    }

    @Override
    public CommandResult executeKubectlCommand(boolean verbose, List<String> arguments) {
      var reply = replies.remove();
      commands++;
      now += TimeUnit.SECONDS.toNanos(reply.seconds());
      return new CommandResult(arguments, reply.status(), reply.output(), "");
    }

    @Override
    protected long rolloutObservationTime() {
      return now;
    }

    @Override
    protected void sleepForPodStatusCheckInterval() {
      sleeps++;
      now += TimeUnit.SECONDS.toNanos(2);
    }
  }
}
