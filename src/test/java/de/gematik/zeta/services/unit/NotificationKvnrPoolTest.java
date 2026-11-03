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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.gematik.zeta.services.NotificationKvnrPool;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies exclusive reservations, reuse and quarantine independently of the environment. */
class NotificationKvnrPoolTest {

  @Test
  void reservesDistinctIdentitiesAndFailsWhenExhausted() {
    var pool = new NotificationKvnrPool(List.of("D198916288", "R379696303"));
    var first = pool.reserve("example.test");
    var second = pool.reserve("example.test");
    assertThat(first.kvnr()).isNotEqualTo(second.kvnr());
    assertThat(first.email()).isEqualTo(first.kvnr() + "@example.test");
    assertThatThrownBy(() -> pool.reserve("example.test")).isInstanceOf(AssertionError.class);
    pool.finish(first, true);
    var reused = pool.reserve("example.test");
    assertThat(reused.kvnr()).isEqualTo(first.kvnr());
    assertThat(reused.id()).isNotEqualTo(first.id());
    assertThatThrownBy(() -> pool.finish(first, true)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> pool.reserve("example.test")).isInstanceOf(AssertionError.class);
  }

  @Test
  void failedCleanupCannotReturnIdentityToPool() {
    var pool = new NotificationKvnrPool(List.of("D198916288", "R379696303"));
    var failed = pool.reserve("example.test");
    pool.finish(failed, false);
    var usable = pool.reserve("example.test");
    assertThat(usable.kvnr()).isNotEqualTo(failed.kvnr());
    pool.finish(usable, true);
    assertThat(pool.reserve("example.test").kvnr()).isEqualTo(usable.kvnr());
    assertThatThrownBy(() -> pool.reserve("example.test")).hasMessageContaining("1 quarantined");
  }

  @Test
  void rejectsEmptyDuplicateAndInvalidPools() {
    assertThatThrownBy(() -> new NotificationKvnrPool(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NotificationKvnrPool(List.of("D198916288", "D198916288")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NotificationKvnrPool(List.of("D198916289"))).isInstanceOf(IllegalArgumentException.class);
  }
}
