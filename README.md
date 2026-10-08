<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# ZETA Tiger Testsuite Repository

> **Zweck**  
> Dieses Repository enthält die TIGER/Cucumber-basierte Testsuite für ZETA (PEP / ZETA Guard,
> Testfachdienst indirekt über den Guard).  
> Ziel ist: wiederholbare, dokumentierte End-2-End Tests (Userstories / UseCases) mit möglichst
> wenigen Custom-Glue-Klassen — stattdessen sollen die TGR-Hilfssteps (Tiger Glue / TGR) verwendet
> werden.

---

## Inhaltsverzeichnis

- [Projektstruktur](#projektstruktur)
- [Voraussetzungen](#voraussetzungen)
- [Schnellstart](#schnellstart)
- [Docker](#docker)
- [Preflight-Checks und `.gitattributes`](#preflight-checks-und-gitattributes)
- [Testzertifikate](#testzertifikate)
- [Tiger-Konfigurationen](#tiger-konfigurationen)
- [Troubleshooting & Tipps](#troubleshooting--tipps)
- [Dokumentation (AsciiDoc/Mermaid)](#dokumentation-asciidocmermaid)
- [Lizenz-Compliance (Third-Party)](#lizenz-compliance-third-party)
- [License](#license)

---

## Projektstruktur

Dieses Repository enthält die Cucumber-Features, Tiger-Konfigurationen und ergänzende
Testhilfen für die Testausführung.

- `src/test/resources/features`: Gherkin-Features nach User Story und Use Case.
- `src/test/java`: projektspezifische Step-Definitionen, Hooks und Hilfsklassen.
- `tiger.yaml` plus `tiger/*.yaml`: zentrale Tiger-Konfigurationen.
- `docs/`: Testplan, AsciiDoc-Quellen und generierte Referenztabellen.
- `docker/`: Container-Builds und Laufzeitdokumentation.

---

## Voraussetzungen

- Java 21
- Maven
- IntelliJ mit Cucumber-Plugin
- TLS Test Tool Service für die TLS-UseCases.
  Die zugehörigen Zertifikatsfixtures liegen unter `src/test/resources/tls-test-tool/certificates`.

### Signing-Key für Access-Token-Manipulationen

Tests, die Access Tokens neu signieren, benötigen den privaten ES256-Signing-Key der jeweiligen
Zielumgebung.
Der Key muss am konfigurierten Pfad
`src/test/resources/keys/${zeta_base_url}-ecKey.pem` liegen.
Wird die Datei für den aktuellen Host ersetzt, ist weder ein zusätzlicher Mount noch der Parameter
`paths.guard.ecKeyFile` notwendig.
Private Keys dürfen ausschließlich aus dafür vorgesehenen Testumgebungen bezogen werden.
Eine lokal durch einen Umgebungs-Key ersetzte Datei darf nicht committed werden.

Für das lokale KIND-Deployment wird der Access-Token-Key von der `ecdsa-generated`-Komponente des
Keycloak-Realms `zeta-guard` verwendet.
Der über `HSM_PROXY_KEY_ID` konfigurierte Key ist lediglich der TLS-Key des Authservers.
Der `@require_signing_key`-Hook prüft den abgelegten Key zunächst gegen einen frisch ausgestellten
Access Token.
Wenn der Key fehlt oder nicht passt, fragt der Hook die aktiven und aktivierten
`ecdsa-generated`-Keys direkt über den vorhandenen Java-Kubernetes-Zugriff aus Keycloak ab,
ermittelt den passenden privaten Key kryptografisch und legt ihn am konfigurierten Pfad ab.
Nur wenn auch diese Abfrage nicht möglich ist oder keinen eindeutigen Treffer liefert, werden die
betroffenen Szenarien übersprungen.
Die deploymentabhängigen Keycloak-Werte für diese Abfrage stehen in
`tiger/zeta-deployment-config.yaml` unter `keycloakSigningKey`.

Nach einer Neuerzeugung des Realm-Keys oder einem Neuaufsetzen der Keycloak-Datenbank muss die
lokale Kopie beim nächsten `@require_signing_key`-Szenario automatisch erneuert werden.
Der JWT-Szenariogrundriss kann anschließend wie folgt ausgeführt werden:

```bash
mvn clean verify -Pproxy \
  '-Dcucumber.filter.name=^ZETA Guard Integrationstest, JWT Prüfung.*$' \
  '-Dzeta_base_url=zeta-kind.local' \
  '-Dtiger.lib.activateWorkflowUi=false'
```

---

## Schnellstart

```bash
# Ausführen aller Scenarien der Testsuite
mvn verify

# Ausführen von getaggten Scenarien mit Standalone Tiger Proxy via profile proxy
# Optionen:
# Smoke Tests         @blocker
# Status ok           @critical
# Performance         @performance
# AFO Aspects         @TA_A_xxxx
mvn verify -Pproxy "-Dcucumber.filter.tags=@TA_A_25761_02 or @TA_A_27802_01"

# Ausführen gegen eine bestimmte Stage (Cloud)
# 1. Setzen Sie den gewünschten Host (ohne Scheme) via ENV oder Maven:
#    export ZETA_BASE_URL=zeta-kind.local
#    # oder
#    mvn verify -Dzeta_base_url=zeta-kind.local
# 2. Nutzen Sie das Cloud-Profil (environment=cloud bleibt unverändert):
mvn verify -Denvironment=cloud
```

Cucumber-Features (unter `src/test/resources/features`) werden von der JUnit Platform / Cucumber
Engine ausgeführt.

Hinweis: Die Tests laufen mit der JVM-Einstellung `java.net.preferIPv4Stack=true`, damit lokale
Proxy-/WebSocket-Verbindungen in WSL nicht an einer IPv6-only `localhost`-Auflösung scheitern.
Das Verhalten ist in `pom.xml` fest verdrahtet und gilt für alle Maven-Runs. Unter Windows hat
die Einstellung in typischen IPv4/IPv6-Setups keinen negativen Einfluss.

## Docker

Alle Docker-Details zu Build, Run, CI und Variablen stehen in [docker/README.md](docker/README.md).
Für Container-Läufe mit internen Laufzeit-Fehlerannotationen ist dort auch `ZETA_RUNTIME_FAILURE_ANNOTATIONS_CSV` dokumentiert.

## Preflight-Checks und `.gitattributes`

Die GitLab-Pipeline besitzt eine zusätzliche Stage `preflight`, in der der Job `utf8_posix_check`
alle versionierten Dateien auf POSIX-kompatible Zeilenenden (LF) und gültiges UTF-8 prüft.
Binäre Assets sowie `tools/` sind davon ausgenommen.
Dadurch schlagen Merge Requests früh fehl, wenn versehentlich CRLF oder ISO-8859-1 eingecheckt würde.

Die Datei [.gitattributes](.gitattributes) erzwingt dieselben Regeln lokal.
Git liefert sämtliche Quelltexte als UTF-8 + LF aus und konvertiert nur Windows-Launcher
(`*.bat`, `*.cmd`, `*.ps1`) zurück auf CRLF.
Verlassen Sie sich daher auf `.gitattributes` anstatt auf `core.autocrlf`, besonders unter Windows.
Falls der Preflight-Job Probleme meldet, führen Sie einmal `dos2unix <file>` aus, normalisieren Sie
mit `git add --renormalize .` oder stellen Sie die Datei aus Git erneut her.

## Testzertifikate

Für Performance- und Setup-Szenarien kann die Testsuite Zertifikate aus dem separaten Repository `zeta-test-certificates` laden.
Primär wird dafür `testCertificates.dir` in [tiger/defaults.yaml](tiger/defaults.yaml) verwendet.
Der Default zeigt auf das Schwesterverzeichnis `../zeta-test-certificates`.
Wenn sich der Ort des Zertifikats-Repositories ändert, können Sie `testCertificates.dir` anpassen.
Für den Build des `quality_gate`-Docker-Images wird das Zertifikats-Repository zusätzlich unter `.cache/zeta-test-certificates` im Build-Kontext unterstützt.
Dabei wird für das Image nur die Teilmenge `manifest/keystore-manifest.tsv` plus `keystores/` übernommen.
In GitLab CI erfolgt der Checkout dafür per Sparse-Checkout, damit nur `manifest/` und `keystores/` aus dem Zertifikats-Repository geladen werden.
Lokal können Sie dafür das vorhandene Checkout nach `.cache/zeta-test-certificates` klonen oder kopieren.
Die Deployment-SMC-B-Defaults aus [tiger/defaults.yaml](tiger/defaults.yaml) nutzen dagegen die lokale Repository-Struktur unter `../zeta-test-certificates/smcb/`.
Dieser Pfad ist der Default-Fallback, wenn keine expliziten SMC-B-Dateipfade gesetzt werden.
Für CI oder abweichende Workspaces können Sie stattdessen explizit setzen:

```bash
export ZETA_TEST_CERTIFICATES_DIR=/path/to/zeta-test-certificates
```

Erwartetes Repository-Layout:

```text
zeta-test-certificates/
├── asl/
│   ├── zeta-guard.asl.issuer.pem
│   ├── zeta-guard.asl.signer.key.pem
│   └── zeta-guard.asl.signer.pem
├── certs/
│   ├── block-000000-009999/
│   ├── block-010000-019999/
│   └── ...
├── incoming/
├── manifest/
│   ├── cert-manifest.tsv
│   ├── keystore-manifest.tsv
│   └── truststore-manifest.tsv
├── keystores/
│   ├── block-000000-009999/
│   └── README.md
├── smcb/
│   ├── smcb-certificates.p12
│   ├── smcb-certificates.p12.b64
│   └── smcb-pw
├── pyproject.toml
├── src/
│   └── zeta_certificates/
│       └── cli.py
├── truststores/
│   ├── README.md
│   └── truststores.yaml
├── Makefile
└── README.md
```

Wichtig für die Testsuite:

- `testCertificates.dir` beziehungsweise `ZETA_TEST_CERTIFICATES_DIR` muss auf das Repository-Wurzelverzeichnis zeigen.
- Das Zertifikat-Manifest wird fest unter `manifest/cert-manifest.tsv` erwartet.
- Die im Manifest referenzierten Zertifikats- und Keystore-Dateien müssen relativ zu diesem Repository-Wurzelverzeichnis auflösbar sein.
- Die Default-Pfade für die Deployment-SMC-B-Testidentität sind `../zeta-test-certificates/smcb/smcb-certificates.p12.b64` und `../zeta-test-certificates/smcb/smcb-pw`.
- CI- und Docker-Läufe sollten diese Pfade bei Bedarf explizit über die jeweilige Laufzeitkonfiguration setzen.

Verfügbare Glue-Methoden:

- Einzelnen Zertifikatseintrag in Tiger-Variablen laden:
  `Dann lade Zertifikat-Eintrag Nummer 1 aus dem Testzertifikat-Manifest in Variablen mit Präfix "perf.client"`
- Mehrere Zertifikatseinträge mit freier Anzahl in Tiger-Variablen laden:
  `Dann lade 100 Zertifikat-Einträge ab Nummer 1 aus dem Testzertifikat-Manifest in Variablen mit Präfix "perf.clients"`
- Einzelnen Eintrag per Stem laden:
  `Dann lade Zertifikat mit Stem "80276883110001000001-C_SMCB57_AUT_E256_X509" aus dem Testzertifikat-Manifest in Variablen mit Präfix "perf.client"`
- Slice für externe Werkzeuge als TSV exportieren:
  `Dann exportiere 100 Zertifikat-Einträge ab Nummer 1 aus dem Testzertifikat-Manifest nach "target/test-certificates/perf-slice.tsv"`

Nach dem Laden eines Eintrags stehen u. a. folgende Variablen zur Verfügung:

- `${perf.client.stem}`
- `${perf.client.crt_path}`
- `${perf.client.prv_path}`
- `${perf.client.pub_path}`
- `${perf.client.crt_b64}`
- `${perf.client.prv_b64}`
- `${perf.client.pub_b64}`
- `${perf.client.keystore_b64_path}`
- `${perf.client.keystore_b64}`
- `${perf.client.keystore_password}`
- `${perf.client.keystore_alias}`
- `${perf.client.store_type}`

Beim Laden mehrerer Einträge werden zusätzlich `${perf.clients.count}`, `${perf.clients.start_index}` sowie `${perf.clients.1.*}` bis `${perf.clients.N.*}` gesetzt.

Der TSV-Export ist für externe Werkzeuge gedacht.
Er enthält absolute Dateipfade für Zertifikat, Private Key und Public Key sowie den inline aufgelösten Base64-Keystore.
Die Inline-Werte der geladenen Zertifikate werden ebenfalls Base64-kodiert bereitgestellt, damit binäre DER-Dateien nicht als UTF-8 fehlinterpretiert werden.

## Additional Security Layer (ASL)

Die Testsuite kann so konfiguriert werden, dass das ZETA Guard Deployment vor der Testausführung explizit mit
oder ohne ASL neu deployed wird. Das Deployment mit entsprechenden Parametern geschieht automatisch vor der Ausführung
jeglicher Szenarien und nach der Ausführung wird das Deployment wiederhergestellt, i.S.v. ohne zusätzliche Anpassungen
mit dem aktuellen Stand des lokalen `zeta-guard-helm` Repositories deployed.

### Voraussetzungen
- lokaler Klon des `zeta-guard-helm` Repository sowie dessen Voraussetzungen für ein Deployment (Zertifikate, Passwörter etc.)
- ASL Zertifikate müssen als `asl-identity` Kubernetes Secret im Cluster vorhanden sein
- folgende Tools müssen über die `$PATH` Umgebungsvariable verfügbar sein:
    - `make`
    - `helm`
    - `kubectl`
- `config` Datei für `kubectl` mit den Berechtigungen, ein komplettes  Deployment auszuführen

[Details zur Konfiguration finden sich im Abschnitt Konfiguration](#additional-security-layer-asl-konfigurieren)

## Tiger-Konfigurationen

Hinweis: Die Cucumber-Driver-Klassen werden durch das Tiger Maven Plugin mit dem Template
`config/tiger-driver-template.jtmpl` erzeugt, damit Allure-Ergebnisse standardmäßig mitlaufen.

* **[tiger.yaml](tiger.yaml)**: Hauptkonfiguration.

Konfigurieren Sie den Cloud-Host zentral über `zeta_base_url` in der `defaults.yaml`.
Alternativ können Sie beim Start `ZETA_BASE_URL` oder einen Maven-Parameter wie
`-Dzeta_base_url=zeta-kind.local` setzen.

Der Kubernetes Namespace (`zeta_k8s_namespace`) wird in Telemetrie-Log-Abfragen
(`resource.k8s.namespace.name`) sowie in kubectl-/Deployment-bezogenen Prüfungen verwendet.
Setzen Sie ihn in der `defaults.yaml` oder alternativ über die Umgebungsvariable
`ZETA_K8S_NAMESPACE` bzw. den Maven-Parameter `-Dzeta_k8s_namespace=...`.
Die Ausführung dieser Gruppe von Testfälle kann über den Schalter `allow_deployment_modification` in der `defaults.yaml`
oder über den Maven-Parameter `-Dallow_deployment_modification=true` gesteuert werden.

Für OpenTelemetry-Log-Abfragen wird `opensearch_url` verwendet (OpenSearch-Host ohne Scheme).
Sie können `OPENSEARCH_URL` setzen oder `-Dopensearch_url=zeta-kind.local:9200` verwenden.
Wenn kein Wert gesetzt ist, greift der Default `${zeta_base_url}:9200`.

Für OpenTelemetry-Metrik-Abfragen wird `prometheus_url` verwendet (Prometheus-Host ohne Scheme).
Sie können `PROMETHEUS_URL` setzen oder `-Dprometheus_url=zeta-kind.local:9090` verwenden.
Wenn kein Wert gesetzt ist, greift der Default `${zeta_base_url}:9090`.

Für die funktionalen PoPP-Token-Schritte wird `popp_token_generator_url` verwendet.
Sie können `POPP_TOKEN_GENERATOR_URL` setzen oder
`-Dpopp_token_generator_url=https://.../popp/test/api/v1/token-generator` verwenden.
Die Load-Dispenser-Workloads `test=hellozeta` erzeugen gültige PoPP-Token intern und benötigen
diese URL nicht.

Für das TLS-Test-Tool wird `zeta_tls_test_tool_service_url` verwendet (Host ohne Scheme).
Sie können `ZETA_TLS_TEST_TOOL_SERVICE_URL` setzen oder `-Dzeta_tls_test_tool_service_url=zeta-tls-test-tool-service.zeta-staging.svc:9012` verwenden.
Wenn kein Wert gesetzt ist, greift der Default `${zeta_base_url}:9012`.

Für die Proxy-Erfassung stehen Profile zur Verfügung:
`PROFILE=proxy` aktiviert die Tiger-Proxy-Erfassung und verwendet standardmäßig
`zeta_proxy_url=${zeta_base_url}:9999`.
Wenn stattdessen ein lokaler Port-Forward genutzt wird, setzen Sie explizit
`ZETA_PROXY_URL=localhost:9999` (oder `-Dzeta_proxy_url=localhost:9999`) und starten zuvor z. B.
`kubectl port-forward svc/tiger-proxy 9999:9999`.
Ohne Angabe wird kein Proxy-Profil geladen.

### Proxy-Tags

- Szenarien, die ohne Standalone-Tiger-Proxy laufen, mit `@no_proxy` taggen.
- Alle anderen Szenarien setzen einen konfigurierten Proxy voraus. Ist `PROFILE` nicht `proxy`,
  werden nicht getaggte Szenarien automatisch übersprungen.

### `kubectl`-Abfragen

Zu Beginn jedes Testlaufs erfasst die Testsuite mit einem lesenden `kubectl get pods` automatisch die getesteten Container-Images im konfigurierten Namespace.
Hierfür ist `allow_deployment_modification` nicht erforderlich.
Wenn `kubectl`, Clusterzugriff oder die Berechtigung zum Auflisten der Pods fehlen, läuft die Testsuite ohne Versionsinformationen weiter.
Serenity gruppiert die Ergebnisse in `build-info.html` nach Namespace und Pod und zeigt je Container Image-Tag und Digest getrennt durch ` | ` an.
Allure erhält dieselben Laufzeitinformationen als `deployment.version.*` in `target/allure-results/environment.properties`.

- Szenarien, die `kubectl` und Zugriff auf den konfigurierten Kubernetes-Namespace benötigen,
  aber keine Modifikationen am Deployment vornehmen, mit `@require_kubectl` taggen.
  Diese Szenarien werden automatisch übersprungen, wenn `kubectl` nicht verfügbar ist oder
  kein Zugriff auf den Namespace (`zeta_k8s_namespace`) besteht.

### ZETA Guard Deployment Modifikation

- grundsätzlich steuert der Schalter `allow_deployment_modification` ob die Testsuite überhaupt
  Modifikationen am ZETA Guard Deployment vornehmen darf
- Szenarien, welche direkt Werte im Deployment des ZETA Guard verändern, müssen mit
  `@deployment_modification` getaggt werden
- Voraussetzungen:
    - das Tool [`kubectl`](https://kubernetes.io/docs/reference/kubectl/) muss in der `PATH` Umgebungsvariable
      des Systems vorhanden sein und ausführbar sein
    - `kubectl` muss Zugriff auf eine gültige `kubeconfig` für den gewünschten
      Namespace (`zeta_k8s_namespace`) haben

> **Wichtiger Windows-Hinweis (WSL/Kubectl):**  
> Für Deployment-Manipulationsschritte und bestimmte `kubectl`-basierte Checks wird `kubectl` innerhalb von WSL genutzt.  
> Unter Windows funktionieren diese Teile der Testsuite daher nur, wenn WSL eingerichtet ist und dort ein funktionsfähiges `kubectl` inklusive `kubeconfig` verfügbar ist.  
> Ein ausschließlich unter Windows installiertes `kubectl.exe` (ohne WSL-Setup) ist für diese Checks nicht ausreichend.

### Additional Security Layer (ASL) konfigurieren

Für die explizite De-/Aktivierung des ASL müssen einige Konfigurationsschritte vorgenommen werden. Die Werte können 
jeweils in der `tiger/defaults.yaml` Datei gesetzt werden oder beim Aufruf über `-D<config-key>=<config-value>` 
gesetzt bzw. überschrieben werden.

- Pfad zum `zeta-guard-helm` Repository festlegen: <br>&nbsp;&nbsp; `zeta_k8s_helm_deployment_directory: /path/to/repository`
- Deployment Modifikationen allgemein erlauben: <br>&nbsp;&nbsp; `allow_deployment_modification: true`
- ASL aktivieren: <br>&nbsp;&nbsp; `zeta_k8s_enable_asl_globally: true`
- ASL deaktivieren: <br>&nbsp;&nbsp; `zeta_k8s_disable_asl_globally: true`
- Datei mit dem SMCB Keystore für den PDP : <br>&nbsp;&nbsp; `zeta_k8s_smb_keystore_file`
- Datei mit dem Password für den SMCB Keystore für den PDP : <br>&nbsp;&nbsp; `zeta_k8s_smb_keystore_password_file`

**Anmerkung:** Da die Konfiguration über die erwähnten Schalter grundsätzlich ungültige Zustände definieren kann, wird 
die Konfiguration zur Laufzeit geprüft und die Ausführung bei ungültiger Konfiguration mit einer Fehlermeldung abgebrochen.

Das Deployment aus dem `zeta-guard-helm` Repository heraus kann jeweils weitere Anforderungen haben, z.B. bezüglich 
Umgebungsvariablen für sensible Werte. Details zu diesen Voraussetzungen befinden sich in der Dokumentation
des `zeta-guard-helm` Repositories.


### Tiger Optionen

In der Datei [tiger.yaml](tiger.yaml) können unter dem Schlüssel `lib:` verschiedene Optionen
gesetzt werden,
um das Verhalten der Tiger-Laufzeit und der Workflow-UI zu steuern.

**Hinweise:**

* Für CI/CD-Umgebungen sollten `activateWorkflowUi` und `startBrowser` stets `false` sein.
* Die Tests lassen sich dann headless zum Beispiel mit

  ```bash
  mvn -B -ntp -Djava.awt.headless=true \
      -Dtiger.lib.activateWorkflowUi=false \
      -Dtiger.lib.startBrowser=false \
      -Dtiger.lib.runTestsOnStart=true \
      verify
  ```

  ausführen.
* Eine vollständige Beschreibung aller Optionen befindet sich in der
  [Tiger-User-Manual-Dokumentation](https://gematik.github.io/app-Tiger/Tiger-User-Manual.html).

## Troubleshooting & Tipps

* **Server not found**: Prüfen Sie `tiger.yaml` auf exakte Server-Keys und das Working Directory
  beim Start.
* **Port conflicts / Windows locks**: Nutzen Sie `active: false` plus dynamische Ports oder Docker.
* **Actuator Health fail**: Achten Sie auf `spring-boot-starter-actuator` sowie
  `management.endpoints.web.exposure.include=health`.
* **Cucumber findet keine Features**: Stellen Sie sicher, dass Features unter
  `src/test/resources/features` liegen
  und die Cucumber Engine als Test-Dependency verfügbar ist.
* **Logs**: Tiger schreibt Server-Logs in `target/serverLogs/` (oder `build/`) — prüfen Sie diese
  regelmäßig.

## Dokumentation (AsciiDoc/Mermaid)

Die Testplan-Dokumentation wird über das Maven-Profil `generate-documentation` erzeugt.

- Lokale Generierung: `mvn -Pgenerate-documentation generate-resources`
- Kompletter Build inklusive Paketierung: `mvn -Pgenerate-documentation -DskipTests package`
- Artefakte: `target/docs/html/Testplan_ZETA.html` und `target/docs/epub/Testplan_ZETA.epub`

Weiterführende Details liegen bewusst in den dedizierten Dokumentationen:

- Überblick zur Doku-Struktur: [docs/README.md](docs/README.md)
- UV-verwaltete Hilfsskripte für die Dokumentation: [docs/scripts/README.md](docs/scripts/README.md)

## Lizenz-Compliance (Third-Party)

Die Testsuite prüft Third-Party-Lizenzen in `verify` automatisch über
`org.codehaus.mojo:license-maven-plugin`.

```bash
mvn -DskipTests=true verify
```

Ergebnisdatei:

- `target/generated-sources/license/THIRD-PARTY.txt`

Konfiguration:

- Denylist: `pom.xml` Property `license.denied.list`
- Build-Abbruch bei fehlender oder verbotener Lizenz: `failOnMissing=true`, `failOnBlacklist=true`

Ausnahmen sind nur nach Legal-Freigabe zulässig:

- Datei: `src/license/override-THIRD-PARTY.properties`
- Format: `<groupId>--<artifactId>--<version>=<Lizenzbezeichner>`
- Für interne Freigaben SPDX-kompatibel als `LicenseRef-...` dokumentieren, zum Beispiel
  `LicenseRef-Approved-Dependency-Exception`.
- Pro neuer Ausnahme müssen Begründung, Reviewer und Datum in der MR-Beschreibung enthalten sein.

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
