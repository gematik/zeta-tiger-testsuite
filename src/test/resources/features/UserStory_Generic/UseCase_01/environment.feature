#
# #%L
# ZETA Testsuite
# %%
# (C) achelos GmbH, 2025, licensed for gematik GmbH
# %%
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# *******
#
# For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
# #L%
#

#language:de

@UseCase_GENERIC_01
Funktionalität: Szenarien mit Bezug zur Laufzeitumgebung des ZETA-Guard

  @A_28798
  @TA_A_28798_01
  @no_proxy
  @require_kubectl
  Szenariogrundriss: Erwartete ZETA-Guard-Komponente <pod-name.match> wird als Applikation in Kubernetes betrieben
    Wenn ermittle aus den Pods im Namespace "${zetaDeploymentConfig.namespace}" den Wert aus der Spalte "NAME" der Zeile mit "<pod-name.match>" und speichere in der Variable "<pod-name.match>-var"
    Dann TGR prüfe Variable "<pod-name.match>-var" stimmt überein mit "^<pod-name.match>.*$"
    Und TGR prüfe Variable "<pod-name.match>-var" stimmt überein mit "^(?!<pod-name.dont-match>).*$"

    Beispiele:
      | pod-name.match        | pod-name.dont-match        |
      | authserver            | DELIBERATELY_INVALID       |
      | keycloak-db           | DELIBERATELY_INVALID       |
      | opa                   | opa-simulation             |
      | opa-simulation        | DELIBERATELY_INVALID       |
      | pep-deployment        | DELIBERATELY_INVALID       |
