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

@UseCase_Telemetrie_02
Funktionalität: Sampling im Auslieferungsstandard des ZETA Guards prüfen

  @A_28988
  @TA_A_28988_01
  @normal
  @no_proxy
  @require_kubectl
  Szenario: Sampling ist im ausgelieferten Standard und der laufenden gematik-Pipeline deaktiviert
    # Chart-Defaults des tatsächlich installierten Bundles, getrennt von dessen Benutzer-Overrides.
    # Das Profil verfolgt den direkten OTLP-Eingang der gematik-Betriebsdatenpipeline.
    Dann prüfe deaktiviertes Sampling im Auslieferungsstandard "/chart/values/zeta-guard/telemetry-gateway/config" für Pipeline "${telemetry.gematikTracePipeline}"
    Und Sampling ist auch in allen laufenden Collectors derselben Pipeline deaktiviert
