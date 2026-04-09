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

@UseCase_01_21
Funktionalität: Client_ressource_anfrage_fachdienst_SC_200

  Grundlage:
    Und TGR setze Anfrage Timeout auf 120 Sekunden

  @A_25786
  @TA_A_25786_03
  @deployment_modification
  Szenario: Laufende Anfrage wird vor Abschluss des Versionswechsels beendet
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Und ermittle das vollständige Image für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_original_image"
    Und ermittle den Image-Pfad für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_image_path"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}/delay/10" ohne auf Antwort zu warten mit folgenden Headern:
      | X-Rollout-Trace-Id | complete-before-finalize-1 |

    Wenn setze das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}"
    Dann prüfe, dass die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/10" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "complete-before-finalize-1" übereinstimmt, beantwortet wird bevor das Deployment "${zetaDeploymentConfig.pep.podName}" finalisiert ist oder 60 Sekunden vergangen sind
    Und TGR finde die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/10" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "complete-before-finalize-1" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und rolle das Deployment "${zetaDeploymentConfig.pep.podName}" zurück und prüfe, dass das Image "${pep_original_image}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" innerhalb von 30 Sekunden aktiv ist
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_25784
  @TA_A_25784_01
  @deployment_modification
  Szenario: Aktualisierung wird im Hintergrund heruntergeladen, während eine Anfrage weiterläuft
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Und ermittle den aktuellen Ready-Pod für das Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_old_ready_pod"
    Und ermittle das vollständige Image für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_original_image"
    Und ermittle den Image-Pfad für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_image_path"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}/delay/60" ohne auf Antwort zu warten mit folgenden Headern:
      | X-Rollout-Trace-Id | background-download-1 |

    Wenn setze das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}"
    Dann prüfe, dass für das Deployment "${zetaDeploymentConfig.pep.podName}" innerhalb von 45 Sekunden ein Pod mit dem Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" sichtbar wird, bevor die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/60" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "background-download-1" übereinstimmt, beantwortet wird
    Wenn sende wiederholt eine leere GET Anfrage an "${paths.client.helloZeta}" und erwarte HTTP Status "200" bis das Deployment "${zetaDeploymentConfig.pep.podName}" ausgehend von Pod "${pep_old_ready_pod}" auf einen neuen Pod gewechselt ist oder 120 Sekunden vergangen sind
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist
    Und ermittle den aktuellen Ready-Pod für das Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_new_ready_pod"
    Und TGR assert variable "pep_new_ready_pod" matches "^(?!${pep_old_ready_pod}$).+"
    Und prüfe, dass für den Pod "${pep_new_ready_pod}" ein Image-Pull für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" stattgefunden hat
    Dann TGR finde die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/60" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "background-download-1" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/60" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "background-download-1" übereinstimmt, eine Antwortzeit von mindestens 50 Sekunden hat

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und rolle das Deployment "${zetaDeploymentConfig.pep.podName}" zurück und prüfe, dass das Image "${pep_original_image}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" innerhalb von 30 Sekunden aktiv ist
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_25785
  @TA_A_25785_01
  @deployment_modification
  Szenario: Folgeanfragen bleiben bis zur finalisierten Aktualisierung ohne Unterbrechung verfügbar
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Und ermittle den aktuellen Ready-Pod für das Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_old_ready_pod"
    Und ermittle das vollständige Image für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_original_image"
    Und ermittle den Image-Pfad für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_image_path"
    Wenn setze das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}"
    Dann sende wiederholt eine leere GET Anfrage an "${paths.client.helloZeta}" und erwarte HTTP Status "200" bis das Deployment "${zetaDeploymentConfig.pep.podName}" finalisiert ist oder 120 Sekunden vergangen sind
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist
    Und ermittle den aktuellen Ready-Pod für das Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_new_ready_pod"
    Und TGR assert variable "pep_new_ready_pod" matches "^(?!${pep_old_ready_pod}$).+"

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    Und rolle das Deployment "${zetaDeploymentConfig.pep.podName}" zurück und prüfe, dass das Image "${pep_original_image}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" innerhalb von 30 Sekunden aktiv ist
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"

  @A_25786
  @TA_A_25786_04
  @deployment_modification
  Szenario: Langlaufende Anfrage wird während des Versionswechsels ordnungsgemäß weitergeführt
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    Und ermittle den aktuellen Ready-Pod für das Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_old_ready_pod"
    Und ermittle das vollständige Image für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_original_image"
    Und ermittle den Image-Pfad für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_image_path"
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}/delay/60" ohne auf Antwort zu warten mit folgenden Headern:
      | X-Rollout-Trace-Id | longrun-update-1 |
    Und setze das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}"

    # TA_A_25786_04: Die zuerst gestartete Langläufer-Anfrage bleibt während des Verschwindens des
    # alten Ready-Pods offen und wird erst nach der ordnungsgemäßen Übernahme bei weiterhin
    # ausstehender Rollout-Finalisierung erfolgreich beantwortet.
    Dann prüfe, dass der Pod "${pep_old_ready_pod}" verschwindet, während die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/60" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "longrun-update-1" übereinstimmt, noch keine Antwort hat, und dass das Deployment "${zetaDeploymentConfig.pep.podName}" erst danach finalisiert wird oder 90 Sekunden vergangen sind
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionDowngrade}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" aktiv ist
    Und ermittle den aktuellen Ready-Pod für das Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_new_ready_pod"
    Und TGR assert variable "pep_new_ready_pod" matches "^(?!${pep_old_ready_pod}$).+"
    Dann TGR finde die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/60" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "longrun-update-1" übereinstimmt
    Und TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
    Und prüfe, dass die erste Anfrage mit Pfad "${paths.client.helloZetaPath}/delay/60" und Knoten "$.header.[~'x-rollout-trace-id']" der mit "longrun-update-1" übereinstimmt, eine Antwortzeit von mindestens 50 Sekunden hat

    Gegeben sei TGR lösche aufgezeichnete Nachrichten
    # Technisches Cleanup: Deployment wieder auf das ursprüngliche Image zurücksetzen.
    Und rolle das Deployment "${zetaDeploymentConfig.pep.podName}" zurück und prüfe, dass das Image "${pep_original_image}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" innerhalb von 30 Sekunden aktiv ist
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
  @A_25788
  @A_25789
  @TA_A_25788_01
  @TA_A_25789_01
  @deployment_modification
  Szenario: Rollback bei fehlgeschlagener Aktualisierung stellt stabile Vorversion wieder her
    Gegeben sei TGR sende eine leere GET Anfrage an "${paths.client.reset}"

    # TA_A_25788_01 + TA_A_25789_01: Fehlgeschlagene Aktualisierung wird nach nachgewiesenem Rollout-Fehler
    # automatisch und innerhalb eines festen Zeitbudgets auf die stabile Vorversion zurückgeführt.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}" ohne auf Antwort zu warten
    Und ermittle das vollständige Image für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_original_image"
    Und ermittle den Image-Pfad für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" und speichere in der Variable "pep_image_path"
    Und setze das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionInvalid}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}"
    Und prüfe, dass das Image "${pep_image_path}:${zetaDeploymentConfig.pep.image.versionInvalid}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" im Deployment "${zetaDeploymentConfig.pep.podName}" nicht aktiv wird und das Deployment innerhalb von 30 Sekunden nach fehlgeschlagenem Update automatisch auf das Image "${pep_original_image}" zurückkehrt
    Und bereinige verbleibende fehlgeschlagene Rollout-Pods des Deployments "${zetaDeploymentConfig.pep.podName}" für den Container "${zetaDeploymentConfig.pep.nginx.containerName}" mit anderem Image als "${pep_original_image}"

    # Nachweis stabile Vorversion: System antwortet nach Rollback weiterhin erfolgreich.
    Wenn TGR sende eine leere GET Anfrage an "${paths.client.helloZeta}"
    Dann TGR prüfe aktuelle Antwort stimmt im Knoten "$.responseCode" überein mit "200"
