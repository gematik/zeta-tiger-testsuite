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

@UseCase_01_17
@oidc
Funktionalität: Externe DPoP-Zieladresse vor der Weiterleitung prüfen

  @A_29676
  @TA_A_29676_04
  @MASVS-AUTH
  @normal
  @Ignore
  Szenariogrundriss: Gültiger SDK-Proof mit abweichendem <Teil> der Zieladresse
    # Frischer echter OIDC-SDK-Kontext; erfolgreicher Abruf belegt Route, Audience und Scope.
    Gegeben sei eine eindeutige, randomisierte KVNR in der Variablen "CC_HTU_KVNR"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}" ohne auf Antwort zu warten
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.guard.bindEmailVerifyPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.bindEmailVerifyPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR warte auf eine Nachricht, in der Knoten "${body.path}" mit "${paths.client.oidc.helloZetaPath}" übereinstimmt
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.oidc.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htu.root}" überein mit "\Qhttps://${zeta_base_url}${paths.guard.helloZetaPath}\E"
    Und TGR speichere Wert des Knotens "${headers.authorization.root}" der aktuellen Anfrage in der Variable "CC_HTU_AUTHORIZATION"
    Und extrahiere DPoP Access Token aus Authorization Header "${CC_HTU_AUTHORIZATION}" und speichere in Variable "CC_HTU_ACCESS"
    Und TGR speichere Wert des Knotens "${headers.dpop.body.jti}" der aktuellen Anfrage in der Variable "CC_HTU_CONTROL_JTI"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_HTU_CONTROL_PROOF"
    Und verifiziere ES256 Signatur von DPoP JWT "${CC_HTU_CONTROL_PROOF}"

    # Live-JWKS und ursprüngliches Token unabhängig prüfen, bevor die Zieladresse verändert wird.
    Wenn TGR sende eine leere GET Anfrage an "${paths.guard.baseUrl}${paths.guard.certsEndpointPath}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.certsEndpointPath}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und TGR speichere Wert des Knotens "$.body" der aktuellen Antwort in der Variable "CC_HTU_JWKS"
    Und prüfe die JWT-Variante resign_correct_key in "${CC_HTU_ACCESS}" hat lokal mit KeyStore "${CC_HTU_JWKS}" die erwartete Signaturintegritaet
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.storage}"
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.client.storagePath}"
    Und TGR speichere Wert des Knotens "${body.client.storage.dpop_private_key}" der aktuellen Antwort in der Variable "CC_HTU_PRIVATE_KEY"

    # Nur htu wird geändert. Der tatsächliche Ressourcenpfad bleibt unverändert.
    Wenn Setze im TigerProxy für JWT in "${headers.dpop.strict}" das Feld "body.htu" auf Wert "<Ziel>" mit privatem Schlüssel "${CC_HTU_PRIVATE_KEY}" für Pfad ".*${paths.guard.helloZetaPath}" und 20 Ausführungen
    Und TGR lösche aufgezeichnete Nachrichten
    Und TGR sende eine leere GET Anfrage an "${paths.client.oidc.helloZeta}"
    Und Alle Manipulationen im TigerProxy werden gestoppt
    Dann TGR finde die erste Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "${headers.dpop.body.htu.root}" der mit "\Q<Ziel>\E" übereinstimmt
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.root}" überein mit "\Q${CC_HTU_AUTHORIZATION}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "$.method" überein mit "GET"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.htm}" überein mit "GET"
    Und TGR prüfe aktueller Request im Knoten "${headers.dpop.body.jti}" stimmt nicht überein mit "\Q${CC_HTU_CONTROL_JTI}\E"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.jti}" überein mit ".+"
    Und TGR speichere Wert des Knotens "${headers.dpop.root}" der aktuellen Anfrage in der Variable "CC_HTU_PROOF"
    Und verifiziere ES256 Signatur von DPoP JWT "${CC_HTU_PROOF}"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.header.typ}" überein mit "dpop\+jwt"
    Und TGR speichere Wert des Knotens "${headers.dpop.header.root}" der aktuellen Anfrage in der Variable "CC_HTU_HEADER"
    Und prüfe dass jwk in "${CC_HTU_HEADER}" keine privaten Key-Teile enthält
    Und berechne JKT aus JWT Header JWK "${CC_HTU_PROOF}" und speichere in Variable "CC_HTU_JKT"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.authorization.dpopToken.root}.body.cnf.jkt" überein mit "\Q${CC_HTU_JKT}\E"
    Und berechne SHA256 Hash von "${CC_HTU_ACCESS}" und speichere in Variable "CC_HTU_ATH"
    Und TGR prüfe aktueller Request stimmt im Knoten "${headers.dpop.body.ath}" überein mit "\Q${CC_HTU_ATH}\E"
    Und TGR speichere Wert des Knotens "${headers.dpop.body.iat}" der aktuellen Anfrage in der Variable "CC_HTU_IAT"
    Und validiere, dass der Zeitstempel "${CC_HTU_IAT}" höchstens "60" Sekunden alt ist
    Und TGR speichere Wert des Knotens "${headers.authorization.dpopToken.root}.body.exp" der aktuellen Anfrage in der Variable "CC_HTU_EXP"
    Und validiere, dass der Zeitstempel "${CC_HTU_EXP}" in der Zukunft liegt
    # Das Beobachtungsfenster umfasst den ganzen Negativaufruf; ein weitergeleiteter Retry scheitert ebenfalls.
    Und warte "3" Sekunden
    Und prüfe, dass keine aufgezeichnete Anfrage den Pfad "${paths.fachdienst.helloZetaPath}" hat
    # SDK-Refresh/Wiederholungen sind erlaubt; jeder Ressourcenrequest bleibt isoliert falsch adressiert.
    Und alle SDK-Ressourcenaufrufe an "https://${zeta_base_url}${paths.guard.helloZetaPath}" mit allein abweichendem htu "<Ziel>", Kontrolltoken "${CC_HTU_ACCESS}" und JWKS "${CC_HTU_JWKS}" werden mit 403 abgelehnt

    Beispiele: Unabhängige Bestandteile der externen URL
      | Teil   | Ziel                                                                        |
      | Schema | http://${zeta_base_url}${paths.guard.helloZetaPath}                            |
      | Host   | https://cc-unrelated.example${paths.guard.helloZetaPath}                       |
      | Pfad   | https://${zeta_base_url}${paths.guard.helloZetaPath}/cc-unrelated               |
