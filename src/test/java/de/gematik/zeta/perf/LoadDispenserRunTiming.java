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

package de.gematik.zeta.perf;

import java.time.Duration;
import java.time.Instant;

/**
 * Timing boundaries observed through the load dispenser status API.
 *
 * @param setupDuration elapsed time before the first {@code RUNNING} status
 * @param runStart time at which {@code RUNNING} was first observed
 * @param runEnd time at which {@code STOPPED} was observed
 */
public record LoadDispenserRunTiming(
    Duration setupDuration,
    Instant runStart,
    Instant runEnd) {
}
