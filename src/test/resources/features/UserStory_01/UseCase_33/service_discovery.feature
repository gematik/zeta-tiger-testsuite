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

@UseCase_01_33
Funktionalität: Endpunkte des ZETA Guard durch Service Discovery ermitteln

  @A_29374
  @TA_A_29374_01
  Szenario: Service Discovery übernimmt die nacheinander ermittelten Resource- und AS-Metadaten
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR setze lokale Variable "tiger.lib.validation.messageSortOrder" auf "TIMESTAMP"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.discover}"
    Dann TGR finde die erste Anfrage mit Pfad ".*${paths.guard.wellKnownOAuthProtectedResourcePath}$"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${zeta_base_url}\E(:[0-9]+)?"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.resource" der aktuellen Antwort in der Variable "discoveryResource"
    Und TGR speichere Wert des Knotens "$.body.authorization_servers.0" der aktuellen Antwort in der Variable "discoveryAs"
    Und TGR setze lokale Feature Variable "discoveryAsHost" auf "${discoveryAs}"
    Und TGR ersetze "^https?://" mit "" im Inhalt der Variable "discoveryAsHost"
    Und TGR ersetze "/.*$" mit "" im Inhalt der Variable "discoveryAsHost"
    Und TGR finde die nächste Nachricht mit Knoten "$.body.authorization_servers.0" der mit "\Q${discoveryAs}\E" übereinstimmt
    Und TGR finde die nächste Nachricht mit Knoten "$.path" der mit ".*${paths.guard.wellKnownOAuthServerPath}$" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.host}" überein mit "\Q${discoveryAsHost}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body.issuer" der aktuellen Antwort in der Variable "discoveryIssuer"
    Und TGR speichere Wert des Knotens "$.body.token_endpoint" der aktuellen Antwort in der Variable "discoveryTokenEndpoint"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.*.data.resource" überein mit "\Q${discoveryResource}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.*.data.authorization_servers.0" überein mit "\Q${discoveryAs}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.*.data.issuer" überein mit "\Q${discoveryIssuer}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.*.data.token_endpoint" überein mit "\Q${discoveryTokenEndpoint}\E"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.body.*.resource" überein mit "\Q${discoveryAsHost}\E"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Dann TGR finde die letzte Anfrage mit dem Pfad ".*/testdriver-api/reset$"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.*.data.resource"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.*.data.issuer"
    Und TGR prüfe aktuelle Antwort enthält nicht Knoten "$.body.*.resource"
