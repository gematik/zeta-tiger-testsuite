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

/**
 * Contains information required to modify configuration values to enable the
 * Additional Security Layer in a ZETA Guard deployment.
 *
 * @param nginxAslRegex Regex to match the ASL section in the nginx config
 * @param nginxAslAnchorRegex Regex to match the context of the ASL section in the nginx config
 * @param nginxAslConfig Value to be set in the nginx config to enable ASL
 * @param wellKnownAslRegex Regex to match the ASL section in the well-known config
 * @param wellKnownAslEnabledValue Value to be set in the well-known config to enable ASL
 */
public record ZetaEnableAslRequest(String nginxAslRegex,
                                  String nginxAslAnchorRegex,
                                  String nginxAslConfig,
                                  String wellKnownAslRegex,
                                  String wellKnownAslEnabledValue) {
}
