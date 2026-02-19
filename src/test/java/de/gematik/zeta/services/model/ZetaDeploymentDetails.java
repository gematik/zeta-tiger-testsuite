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
 * Contains information about the ZETA Guard deployment.
 *
 * @param namespace Namespace target deployment is running in
 * @param pepPodName Name of the PEP pod
 * @param nginxConfigMapName Name of the ConfigMap for the nginx settings of the PEP service
 * @param nginxConfigMapKeySegments Segmented key of nginx config in ConfigMap
 * @param wellKnownConfigMapName Name of the ConfigMap for the well-known settings of the PEP service
 * @param wellKnownConfigMapKeySegments Segmented key of well-known config in ConfigMap
 */
public record ZetaDeploymentDetails(String namespace,
                                    String pepPodName,
                                    String nginxConfigMapName,
                                    String[] nginxConfigMapKeySegments,
                                    String wellKnownConfigMapName,
                                    String[] wellKnownConfigMapKeySegments) {
}
