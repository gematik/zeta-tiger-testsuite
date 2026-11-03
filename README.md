<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# ZETA Tiger Testsuite Repository

TIGER/Cucumber-basierte Testsuite für ZETA Guard und die angebundenen Dienste.

## Voraussetzungen

- Java 21 und Maven im `PATH`.
- Ein erreichbares ZETA-Testdeployment mit dem passenden SDK-Testdriver und Testfachdienst.
- Ein erreichbarer Standalone-Tiger-Proxy für die Profile `proxy` und `oidc-proxy`.
- Testidentitäten und Zertifikate passend zur Zielumgebung.
- Für Kubernetes-Zugriffe: `kubectl` und eine gültige `kubeconfig` mit den benötigten
  Berechtigungen im Ziel-Namespace. Unter Windows benötigen die entsprechenden Schritte
  WSL mit dort eingerichtetem `kubectl` und `kubeconfig`.

Alle folgenden Maven-Befehle werden im Repository-Wurzelverzeichnis ausgeführt.

## Konfiguration

Die Hauptkonfiguration liegt in [tiger.yaml](tiger.yaml), die Umgebungswerte in
[tiger/defaults.yaml](tiger/defaults.yaml). Werte lassen sich für einzelne Läufe mit
`-D<config-key>=<wert>` überschreiben.

| Einstellung | Maven-Parameter | Umgebungsvariable |
| --- | --- | --- |
| ZETA-Host ohne Scheme | `-Dzeta_base_url=zeta-kind.local` | `ZETA_BASE_URL` |
| Kubernetes-Namespace | `-Dzeta_k8s_namespace=zeta-local` | `ZETA_K8S_NAMESPACE` |
| Host für lokale Port-Forwards | `-Dzeta_port_forward_host=localhost` | `ZETA_PORT_FORWARD_HOST` |
| Zertifikatsverzeichnis | `-DtestCertificates.dir=../zeta-test-certificates` | `ZETA_TEST_CERTIFICATES_DIR` |

Weitere Einstellungen:

- [tiger/testdata.yaml](tiger/testdata.yaml): Testdaten und Notification-Konfiguration.
- [tiger/zeta-deployment-config.yaml](tiger/zeta-deployment-config.yaml): Deployment-,
  Helm- und Keycloak-Konfiguration.
- [tiger/tls-test-tool-config.yaml](tiger/tls-test-tool-config.yaml): TLS-Test-Tool.
- `popp_token_generator_url` beziehungsweise `POPP_TOKEN_GENERATOR_URL`: vollständige
  URL des PoPP-Token-Generators.

### Infrastruktur und Port-Forwards

Die Infrastruktur-APIs verwenden standardmäßig `localhost` mit den folgenden Ports.
Die URL-Einstellungen erwarten `host:port` ohne Scheme.

| Dienst | Einstellung | Kubernetes-Service | Lokaler Port → Service-Port |
| --- | --- | --- | --- |
| Tiger Proxy | `zeta_proxy_url` | `tiger-proxy` | `9999:9999` |
| OpenSearch | `opensearch_url` | `opensearch` | `9200:9200` |
| Prometheus | `prometheus_url` | `prometheus` | `9090:9090` |
| Jaeger Query-API | `jaeger_url` | `jaeger` | `16686:16686` |
| TLS-Test-Tool | `zeta_tls_test_tool_service_url` | `zeta-tls-test-tool-service` | `9012:80` |
| Zertifikatsvalidierungs-Mock | `zeta_cert_validation_mock_url` | `zeta-cert-validation-mock` | `9013:80` |
| Load Dispenser | `load_dispenser_url` | `load-dispenser` | `50051:50051` |

Starten Sie die benötigten Port-Forwards jeweils in einem eigenen Terminal, zum Beispiel:

```sh
kubectl -n zeta-local port-forward svc/tiger-proxy 9999:9999
```

Passen Sie Namespace und Service-Namen an das Deployment an. Warten Sie vor dem Teststart
auf `Forwarding from`. Nach einem Podwechsel muss der Port-Forward erneut gestartet werden.
Für Docker-Netzwerke gelten die Hinweise in [docker/README.md](docker/README.md).

### Zertifikate und Signing-Key

Legen Sie das Repository `zeta-test-certificates` standardmäßig neben diesem Repository ab
oder setzen Sie `testCertificates.dir`. Der Pfad muss auf dessen Wurzelverzeichnis zeigen;
`manifest/cert-manifest.tsv` und die darin referenzierten Dateien müssen vorhanden sein.

Für die Deployment-SMC-B-Identität werden standardmäßig diese Dateien verwendet:

- `../zeta-test-certificates/smcb/smcb-certificates.p12.b64`
- `../zeta-test-certificates/smcb/smcb-pw`

Abweichende Pfade lassen sich über `zeta_k8s_smb_keystore_file` und
`zeta_k8s_smb_keystore_password_file` setzen.

Access-Token-Manipulationen benötigen den privaten ES256-Signing-Key der Testumgebung unter
`src/test/resources/keys/${zeta_base_url}-ecKey.pem`. Bei fehlendem oder unpassendem Key kann
die Suite ihn mit entsprechendem Kubernetes-Zugriff aus Keycloak oder dem HSM-Simulator
beschaffen; die Quellen werden in `tiger/zeta-deployment-config.yaml` konfiguriert.
Verwenden Sie ausschließlich Testschlüssel und committen Sie keine lokal ersetzten Keys.

## Tests ausführen

SMC-B-Authentisierung mit Tiger Proxy:

```sh
mvn verify -Pproxy "-Dzeta_base_url=zeta-kind.local" "-Dallow_deployment_modification=false"
```

Das aktive Profil setzt den nativen Cucumber-Tagfilter für die passenden Szenarien:
`@smb` läuft nur mit SMC-B, `@oidc` nur mit OIDC. Szenarien ohne Authentisierungstag laufen mit
beiden Profilen. `@Ignore` bleibt ausgeschlossen. Für einen vollständigen Profillauf sind
keine zusätzlichen Cucumber-Tags erforderlich. Maven verwendet diese Auswahl
als Vorgabe für `cucumber.filter.tags`; ein explizites `-Dcucumber.filter.tags`
ersetzt sie vollständig. Beim JAR-Start wird ein expliziter Filter mit der
Profilauswahl verknüpft. Im Quality-Gate-Image reicht deshalb beispielsweise
`CUCUMBER_TAGS=@blocker`: `not @Ignore` und die Authentisierungsabgrenzung sind bereits
im Basisfilter enthalten und müssen im Zusatzfilter nicht wiederholt werden.
Die jeweiligen Laufzeitvoraussetzungen und Freigaben gelten weiterhin.

Behalten Sie für gezielte Maven-Läufe die Profilauswahl im Filter bei:

```sh
mvn verify -Pproxy "-Dcucumber.filter.tags=not @Ignore and not @oidc and @blocker"
mvn verify -Pproxy "-Dcucumber.filter.tags=not @Ignore and not @oidc and @TA_A_25761_02"
mvn verify -Pproxy "-Dcucumber.filter.name=^ZETA Guard Integrationstest, JWT Prüfung.*$"
```

| Maven-Profil | Authentisierung | Tiger Proxy |
| --- | --- | --- |
| `-Pproxy` | SMC-B | Ja |
| `-Poidc-proxy` | OIDC | Ja |
| `-Poidc` | OIDC | Nein |

Szenarien ohne `@no_proxy` benötigen eine konfigurierte `tiger.tigerProxy.proxyId` und
werden sonst übersprungen. Die Proxy-Profile setzen diese Konfiguration.

Für CI-Läufe ohne Workflow-UI und Browser:

```sh
mvn -B -ntp verify -Pproxy "-Djava.awt.headless=true" "-Dtiger.lib.activateWorkflowUi=false" "-Dtiger.lib.startBrowser=false" "-Dtiger.lib.runTestsOnStart=true"
```

Unit-Tests sind standardmäßig deaktiviert. Separat ausführen:

```sh
mvn test "-Dskip.unit.tests=false"
```

### Zusätzliche Laufzeitvoraussetzungen

- **Deployment-Änderungen:** Mit `-Dallow_deployment_modification=true` aktivieren und die
  erforderlichen Kubernetes-Rechte bereitstellen. Ohne Deployment-Rechte den Wert auf `false`
  belassen. Läufe mit Änderungen an gemeinsam genutzten Ressourcen müssen exklusiv erfolgen.
  Temporäre TLS-Gegenstellen des Notification Service benötigen zusätzlich Rechte zum Erstellen,
  Lesen und Löschen testlaufeigener Services und Secrets sowie zum Erstellen und Aktualisieren
  der Sicherungs-ConfigMaps.
- **TLS:** Einen TLS-Test-Tool-Service mit Unterstützung für `POST /reset` unter
  `tlsTestTool.serviceUrl` bereitstellen. Gewöhnliche TLS-Aufrufe benötigen nur die HTTP-API;
  NS-TLS-Läufe zusätzlich das konfigurierte Kubernetes-/Gateway-Profil und Schreibrechte.
  Dieselbe Tool-Instanz auch über Hosts und Container hinweg nur exklusiv verwenden.
  Nach fehlgeschlagener Routenbereinigung die Wiederherstellung mit Kubernetes-Zugriff
  abschließen, bevor das Tool erneut genutzt wird.
- **Performance:** Ohne Proxy-Profil starten und `-Dallow_performance_tests=true` sowie
  `-Dallow_deployment_modification=true` setzen. Benötigt werden der Load Dispenser und
  Kubernetes-Rechte für Lesen, Patch, Exec, Rollout sowie Create/Update der Sicherungs-ConfigMaps.
  Performance-Läufe und andere Tests dürfen dieselbe Umgebung nicht gleichzeitig verwenden.

Der Android-Push-Smoke-Test (`@smoke_android_push`) benötigt zusätzlich einen laufenden
Android Driver mit Firebase-Konfiguration und `PUSH_GATEWAY_URL`, einen Push Gateway mit
passenden FCM-Zugangsdaten sowie den Notification Service mit aktivierter Verschlüsselung
und dem Channel `zeta`.
`android_push_firebase_project_id` muss dem Firebase-Projekt des Android Drivers und des
Push Gateways entsprechen; lokal ist `zeta-4899c` vorbelegt. Die Firebase-App-ID,
Sender-ID und der API-Key des Drivers müssen aus der zugehörigen `google-services.json`
stammen; das Gateway benötigt den Service-Account dieses Projekts oder eine dafür
berechtigte projektübergreifende Identität.
Der Treiber muss Registrierung, Channel-Abonnement und Empfang unter
`/androiddriver-api/notifications/` unterstützen.
`paths.androidClient.baseUrl` kann für einen separat bereitgestellten Android Driver
überschrieben werden; standardmäßig wird derselbe Ingress wie für `zetaClient` verwendet.
Der Test setzt den Android-SDK-Kontext zurück und darf deshalb nicht parallel zu anderen
Tests auf derselben Android-Driver-Instanz laufen.
Der Versand zum Push Gateway muss über den angebundenen Tiger Proxy aufgezeichnet werden.
Der Zielpfad ist unter `paths.pushGateway.encryptedDispatchPath` konfigurierbar.

### ASL konfigurieren

Für einen Lauf gegen die bestehende ASL-Konfiguration beide Schalter
`zeta_k8s_enable_asl_globally` und `zeta_k8s_disable_asl_globally` auf `false` belassen.

Für eine globale ASL-Änderung werden ein lokaler Klon von `zeta-guard-helm`, das Kubernetes-Secret
`asl-identity`, `make`, `helm`, `kubectl` und Deployment-/Helm-Rollback-Rechte benötigt.
Setzen Sie `zeta_k8s_helm_deployment_directory`, die SMC-B-Dateipfade und
`allow_deployment_modification=true`. Aktivieren Sie genau einen der beiden ASL-Schalter.
`zetaDeploymentConfig.helmReleaseName` muss zum Release des gewählten Chart-Makefile-Ziels passen.
Weitere Deployment-Voraussetzungen stehen in der Dokumentation von `zeta-guard-helm`.

Die ASL-Ressourcenläufe verwenden `-Poidc-proxy` und benötigen freigegebenes non-PU-Tracing
zur Entschlüsselung des SDK-Transports. Für RS-Terminierung werden zusätzlich ein ASL-fähiger RS,
die Aufzeichnung seiner Ressourcenoperation und `asl.rs.upstreamHost` für den PEP→RS-Hop benötigt.

### Lokales Notification-Aufbewahrungsprofil

`UserStory_02/UseCase_09/statusdaten_aufbewahrungsfrist.feature` benötigt `oidc-proxy`,
Kubernetes-Zugriff und `allow_deployment_modification=true`. Das SDK registriert den Pusher;
die gezielte Bereinigung wird vor der Registrierung erfasst. Zwei Ereignisse werden über die
generische SDK-Proxy-Operation am Testfachdienst ausgelöst. Tiger zeichnet SDK- und
Fachdienst-Interaktionen auf. Der interne Fachdienst-zu-NS-Hop dieses Deployments ist nicht
im Proxy sichtbar; der unabhängige NS-Verarbeitungslognachweis verwendet deshalb die vom
SDK empfangene Notification-ID gemeinsam mit dem korrelierten tatsächlichen Speicherbestand.

Das Profil erzeugt einen temporären NS-Pod aus dem beobachteten Image-Digest mit derselben
Testdatenbank. Es setzt `NOTIFICATION_PERSISTENCE_RETENTION=PT2M`,
`NOTIFICATION_PERSISTENCE_PURGE_INTERVAL=1s` und `PUSH_GATEWAY_RETRY_GIVE_UP_AFTER=PT1M`.
Historie und Persistenzversiegelung müssen im bestehenden lokalen Profil bereits deaktiviert sein.
Der RS-Service wird ausschließlich auf diesen Pod geroutet. Die Bereinigung stellt den ursprünglichen
Service-Selector bedingt wieder her und entfernt den eigenen Pod auch nach fehlgeschlagenen Assertions.
Originaldeployment, ursprünglicher Pod und dessen Payloadschutz werden nicht verändert.

## Ergebnisse

- Serenity-Berichte: `target/site/serenity/`
- Maven-Integrationstestergebnisse: `target/failsafe-reports/`
- Allure-Ergebnisse: `target/allure-results/`
- Server-Logs: `target/serverLogs/`

## Docker und Dokumentation

Build, Start, CI und Container-Variablen: [docker/README.md](docker/README.md).

Den Testplan mit installiertem `uv` erzeugen:

```sh
mvn -Pgenerate-documentation generate-resources
```

Die HTML-Ausgabe liegt unter `target/docs/html/Testplan_ZETA.html`.
Dokumentationsstruktur, Windows-Hinweise und Hilfsskripte:
[docs/README.md](docs/README.md) und [docs/scripts/README.md](docs/scripts/README.md).

## Repository-Pflege

- Features und UseCases: `src/test/resources/features/`
- Java-Schrittdefinitionen und Hilfsklassen: `src/test/java/`
- Textkodierung und Zeilenenden: [.gitattributes](.gitattributes)

Third-Party-Lizenzen ohne Integrationstestlauf prüfen:

```sh
mvn verify -Pdistribution -DskipTests=true
```

Ergebnis: `target/generated-sources/license/THIRD-PARTY.txt`.
Lizenzausnahmen in `src/license/override-THIRD-PARTY.properties` erfordern Legal-Freigabe;
Begründung, Reviewer und Datum gehören in die MR-Beschreibung.

## BSI TLS Test Tool Hinweis

Die ZETA-Tiger-Testsuite verwendet eine angepasste Version vom BSI TLS Test Tool (siehe https://github.com/BSI-Bund/TaSK/pull/34) und dazugehörige Testartefakte (`BSI-Bund/TaSK`, Unterverzeichnis `tlstesttool`).

Sie stehen nicht unter der Apache-2.0-Lizenz dieses Repositorys.
Für diese Dateien gelten stattdessen die Lizenzbedingungen des Upstream-Projekts.
Das Upstream-README beschreibt das TLS Test Tool als `EUPL-1.2-or-later` lizenziert.

Bitte prüfen Sie bei Weitergabe, Spiegelung oder Austausch der Tool-Artefakte zusätzlich die
Lizenz- und Hinweisdateien des Upstream-Projekts:

- Upstream-Repository: <https://github.com/BSI-Bund/TaSK/tree/master/tlstesttool>
- Upstream-README: <https://raw.githubusercontent.com/BSI-Bund/TaSK/master/tlstesttool/README.md>
- EUPL-1.2 Text: <https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12>

## License

(C) achelos GmbH, 2025, licensed for gematik GmbH

Apache License, Version 2.0

See the [LICENSE](./LICENSE) for the specific language governing permissions and limitations under
the License.

## Additional Notes and Disclaimer from gematik GmbH

1. Copyright notice: Each published work result is accompanied by an explicit statement of the license conditions for use. These are regularly typical conditions in connection with open source or free software. Programs described/provided/linked here are free software, unless otherwise stated.
2. Permission notice: Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
    1. The copyright notice (Item 1) and the permission notice (Item 2) shall be included in all copies or substantial portions of the Software.
    2. The software is provided "as is" without warranty of any kind, either express or implied, including, but not limited to, the warranties of fitness for a particular purpose, merchantability, and/or non-infringement. The authors or copyright holders shall not be liable in any manner whatsoever for any damages or other claims arising from, out of or in connection with the software or the use or other dealings with the software, whether in an action of contract, tort, or otherwise.
    3. The software is the result of research and development activities, therefore not necessarily quality assured and without the character of a liable product. For this reason, gematik does not provide any support or other user assistance (unless otherwise stated in individual cases and without justification of a legal obligation). Furthermore, there is no claim to further development and adaptation of the results to a more current state of the art.
3. Gematik may remove published results temporarily or permanently from the place of publication at any time without prior notice or justification.
4. Please note: Parts of this code may have been generated using AI-supported technology. Please take this into account, especially when troubleshooting, for security analyses and possible adjustments.
