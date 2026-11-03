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

@UseCase_04_07
Funktionalität: Refresh Token widerrufen und Logout abschließen

  @A_29996
  @TA_A_29996_02
  @normal
  @Ignore
  Szenario: Authorization Server veröffentlicht den Revocation-Endpunkt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    # Die öffentliche Testdriver-Operation löst die Discovery im echten SDK aus.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*${paths.guard.wellKnownOAuthServerPath}$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # RFC 7009 verlangt zusätzlich zum URI-Schema eine HTTPS-URL ohne Fragment.
    Und TGR prüfe aktuelle Antwort im Knoten "$.body" stimmt als "JSON_SCHEMA" überein mit:
      """
      {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "required": [
          "revocation_endpoint"
        ],
        "properties": {
          "revocation_endpoint": {
            "allOf": [
              {"$ref": "classpath:schemas/v_2_0/schemas/as-well-known.yaml#/properties/revocation_endpoint"},
              {"pattern": "^[Hh][Tt][Tt][Pp][Ss]://[^/?#\\s]+(?:[/?][^#\\s]*)?$"}
            ]
          }
        },
        "additionalProperties": true
      }
      """
