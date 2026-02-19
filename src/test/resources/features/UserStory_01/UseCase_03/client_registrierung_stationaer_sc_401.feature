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

@UseCase_01_03
Funktionalität: client_registrierung_stationaer_sc_401

  @dev
  @TA_A_26661_12
  Szenario: Hinweis zu Fehlercode 401 am /register
    Gegeben sei TGR setze lokale Variable "Kommentar" auf "Am Endpunkt /register für die Clientregistrierung wird keine Authentifizierung des Clients vorgenommen.  Daher kann hier auch der Fehlercode 401 Unauthorized nicht erwartet werden. Fehlerhafte Clientdaten bzw. Authentifizierungsdaten hätten einen Fehlercode 400 Bad Request zur Folge."
    Dann warte "1" Sekunden
