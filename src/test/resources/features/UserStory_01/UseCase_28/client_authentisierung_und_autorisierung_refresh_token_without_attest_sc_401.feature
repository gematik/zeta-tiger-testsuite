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

@UseCase_01_28
Funktionalität: Client Authentisierung und Autorisierung Refresh Token without Attest SC 401

  @A_25663
  @A_27725-01
  @TA_A_25663_02
  @TA_A_27725-01_16
  @normal
  @MASVS-AUTH
  @MASVS-RESILIENCE
  Szenario: Refresh Token Binding - Refresh mit anderem DPoP Key scheitert (Negativtest)
    # TA_A_25663_02: Dieser Test verifiziert die DPoP Refresh Token Binding Anforderung
    # Testszenario: Angreifer stiehlt Refresh Token, hat aber eigenen (anderen) DPoP Key
    # Erwartetes Verhalten: Token-Endpoint MUSS mit 401 Unauthorized antworten

    # SCHRITT 1: Erste Session erstellen (Attacker-Session) und DPoP-Key speichern
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Attacker-Key aus Storage holen
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "attackerDpopKey"

    # expires_in auf 5 Sekunden setzen um Refresh zu erzwingen (nur 1 Ausführung für Victim-Session)
    Gegeben sei TGR setze lokale Variable "accessTokenTtl" auf "5"
    Wenn TGR setze lokale Variable "opaCondition" auf "isResponse && request.path =~ '.*${paths.opa.decisionPath}'"
    Dann Setze im TigerProxy für die Nachricht "${opaCondition}" die Manipulation auf Feld "$.body.result.ttl.access_token" und Wert "${accessTokenTtl}" und 1 Ausführungen

    # SCHRITT 2: Neue Session erstellen (Victim-Session mit neuem DPoP Key)
    # Der Refresh Token dieser Session ist an den NEUEN DPoP Key gebunden (via jkt Claim)
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Warten dass HelloZeta-Response vollständig geparst ist
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

    # Nachrichten löschen damit wir nach dem Refresh nur den Refresh-Request finden
    Und TGR lösche aufgezeichnete Nachrichten

    # SCHRITT 3: Manipulation aktivieren - DPoP-Proof mit ATTACKER-Key signieren und JWK ersetzen
    # RFC 9449: "such a client MUST present a DPoP proof for the same key that was used to
    #           obtain the refresh token each time that refresh token is used"
    # Dieser Test verletzt diese Anforderung absichtlich mit einem anderen Key
    Wenn TGR setze lokale Variable "attackerJti" auf "attacker-jti"
    Wenn TGR setze lokale Variable "dpopCondition" auf ".*${paths.guard.tokenEndpointPath}"
    Dann Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.jti" auf Wert "${attackerJti}" mit privatem Schlüssel "${attackerDpopKey}" für Pfad "${dpopCondition}" und 1 Ausführungen und ersetze JWK

    Und TGR lösche aufgezeichnete Nachrichten

    Und speichere den aktuellen Unix-Zeitstempel in der Variable "START"
    Und TGR setze lokale Variable "START_MICROS" auf "!{${START} * 1000000}"

    # Warte auf Token Expiry und Trigger Refresh
    Und warte "${accessTokenTtl}" Sekunden
    Und TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    # Vorladen der Nachrichten, damit die nachfolgende Suche nach dem manipulierten Wert schneller durchläuft
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.tokenEndpointPath}"

    # VERIFIZIERUNG: Token-Endpoint MUSS Refresh mit falschem DPoP-Key ablehnen
    # Erwarteter Fehler: 401 Unauthorized (DPoP Key Thumbprint != jkt im gebundenen Refresh Token)
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.tokenEndpointPath}" und Knoten "${headers.dpop.body.jti}" der mit "${attackerJti}" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "$.body.grant_type" überein mit "refresh_token"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "401"
    Und warte "${testdata.telemetry_wait_seconds}" Sekunden
    Und speichere den aktuellen Unix-Zeitstempel in der Variable "END"
    Und TGR setze lokale Variable "END_MICROS" auf "!{${END} * 1000000}"
    Wenn TGR sende eine GET Anfrage an "${paths.jaeger.baseUrl}${paths.jaeger.jaegerTracesSearchPath}" mit folgenden Daten:
      | service                                  | operation                                   | start           | end           | limit | tags                                                                                                              |
      | ${telemetry.service.authorizationServer} | ${telemetry.span.authorizationServer.token} | ${START_MICROS} | ${END_MICROS} | 1     | {"http.response.status_code":"401","url.path":"${paths.guard.tokenEndpointPath}"} |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.jaeger.jaegerTracesSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.data.0.traceID"
