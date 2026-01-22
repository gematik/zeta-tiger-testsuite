#
# #%L
# ZETA Testsuite
# %%
# (C) 2025 achelos GmbH, licensed for gematik GmbH
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
Funktionalität: Client_ressource_anfrage_fachdienst_SC_403

  Grundlage:
    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und Alle Manipulationen im TigerProxy werden gestoppt

  @A_25660
  @A_26477
  @A_26661
  @A_27007
  @TA_A_25660_03
  @TA_A_26477_02
  @TA_A_26477_08
  @TA_A_26661_01
  @TA_A_27007_26
  Szenariogrundriss: PoPP Token Manipulation Test - Token Request
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Und TGR setze lokale Variable "PoPP_PRIVATE_KEY" auf "MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgChSTcLLu6By9RINWnfQdtCqkm8WlOcje4oDnLV5KpmigCgYIKoZIzj0DAQehRANCAARwLyN6z4jOFORwcx0yMnrJ/2XGUR7b/Vcbo5W02kT7b9rKjub8r2tuBEJ/AIEupjjZ3kYSCPKoUS6v1SNOg8Th"

    Und TGR setze lokale Variable "pathCondition" auf ".*${paths.guard.helloZetaPath}"

    Dann Setze im TigerProxy für JWT in "<JwtLocation>" das Feld "<JwtField>" auf Wert "<NeuerWert>" mit privatem Schlüssel "${PoPP_PRIVATE_KEY}" für Pfad "${pathCondition}" und 1 Ausführungen und ersetze JWK

    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"

    Und TGR finde die letzte Anfrage mit dem Pfad "${paths.guard.helloZetaPath}"

    # Finde den manipulierten Request anhand des geänderten Wertes
    Dann TGR finde die letzte Anfrage mit Pfad "${paths.guard.helloZetaPath}" und Knoten "<JwtLocation>.<JwtField>" der mit "<NeuerWert>" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "<ResponseCode>"

    # 2. Manipuliere die Signatur des PoPP Token
    # 3. Gültigkeitsdauer gesamt
    # 4. Gültigkeitsdauer nach Ausstellungszeitpunkt (iat = 2023-12-01 12:00)
    # 5. Gültigkeitsdauer nach Prüfzeitpunkt (patientProofTime = 2023-12-01 12:00)
    # 6. Manipulierte "actorId"
    Beispiele: Manipulationen
      | JwtLocation                | JwtField                 | NeuerWert             | ResponseCode |
      | $.header.popp              | body.iat                 | 1701432000            | 403          |
      | $.header.popp              | body.patientProofTime    | 1701432000            | 403          |
      | $.header.popp              | body.actorId             | evil_client           | 403          |

  @dev
  @A_26988
  @TA_A_26988_01
  Szenariogrundriss: Telemetrie-Daten Service - Fehlermeldungen
    Wenn TGR sende eine GET Anfrage an "${paths.openSearch.baseUrl}${paths.openSearch.openTelemetryLogsSearchPath}" mit folgenden Daten:
      | q                                                                                                                                                                      | size |
      | resource.k8s.namespace.name:zeta-local AND resource.k8s.container.name:<containerName> AND attributes.log.file.path:/var/log/pods/* AND attributes.log.iostream:stderr | 1    |
    Dann TGR finde die letzte Anfrage mit dem Pfad "${paths.openSearch.openTelemetryLogsSearchPathPattern}"
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    # Die Existenz von hits.hits.0 bedeutet, dass es mindestens einen Open Telemetry Log-Eintrag für genau diesen Container gibt.
    Und TGR prüfe aktuelle Antwort enthält Knoten "$.body.hits.hits.0"

    Beispiele:
      | containerName           |

      # TA_A_26988_01, Ingress
      | controller              |

      # TODO: TA_A_26988_02, Egress               <- Container/Gateway fehlt noch
      #| controller-out          |

      # TA_A_26988_03, HTTP Proxy
      | nginx                   |

      # TA_A_26988_04, PEP Datenbank              <- Container fehlt noch
      #| database-pep            |

      # TA_A_26988_05, Authorization Server
      | keycloak                |

      # TA_A_26988_06, PDP Datenbank
      | postgresql              |

      # TA_A_26988_07, Policy Engine
      | opa                     |

      # TODO: TA_A_26988_08, Notification Service <- Container fehlt noch
      #| notification-service    |

      # TODO: TA_A_26988_09, Management Service   <- Container wird wahrscheinlich nicht benötigt
      #| management-service      |

      # TA_A_26988_10, Telemetrie Daten Service
      | opentelemetry-collector |

      # Zulieferer für den Telemetrie Daten Service
      | log-collector           |

      # TA_A_26988_11, Resource Server
      | testfachdienst          |
