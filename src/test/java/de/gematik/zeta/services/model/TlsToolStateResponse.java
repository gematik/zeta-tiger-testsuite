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

package de.gematik.zeta.services.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Response payload for the current TLS test tool process state.
 *
 * @param running current process running state
 * @param lastExitCode exit code from the previous run, if any
 * @param startedAt timestamp when the current or last process was started
 * @param stoppedAt timestamp when the previous process was stopped
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TlsToolStateResponse(
    boolean running,
    Integer lastExitCode,
    Instant startedAt,
    Instant stoppedAt) {
}
