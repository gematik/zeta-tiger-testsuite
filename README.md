<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# ZETA Tiger Testsuite Repository

> **Zweck**  
> Dieses Repository enthält die TIGER/Cucumber-basierte Testsuite für ZETA (PEP / ZETA Guard /
> Testfachdienst).  
> Ziel ist: wiederholbare, dokumentierte End-2-End Tests (Userstories / UseCases) mit möglichst
> wenigen Custom-Glue-Klassen — stattdessen sollen die TGR-Hilfssteps (Tiger Glue / TGR) verwendet
> werden.

---

## Inhaltsverzeichnis

- [Projektstruktur](#projektstruktur)
- [Voraussetzungen](#voraussetzungen)
- [Schnellstart](#schnellstart)
- [Tiger-Konfigurationen](#tiger-konfigurationen)
- [TGR Methoden](#tgr-methoden)
- [Troubleshooting & Tipps](#troubleshooting--tipps)
- [Wo TGR-Methoden dauerhaft ablegen](#wo-tgr-methoden-dauerhaft-ablegen)
- [Dokumentation (AsciiDoc/Mermaid)](#dokumentation-asciidocmermaid)
- [License](#license)

---

## Projektstruktur

Dieses Repo enthält die Cucumber-Features, Tiger-Konfigurationen und optional kleine
Glue/Hook-Klassen für die Testausführung.

---

## Voraussetzungen

- Java 21
- Maven
- IntelliJ mit Cucumber-Plugin
- Apache JMeter 5.6.3 (abgelegt unter `tools/apache-jmeter-5.6.3`)
- TLS Test Tool 1.0.1 (abgelegt unter `tools/tls-test-tool-1.0.1`)

Zum Ausführen des Features ```Client_ressource_anfrage_fachdienst_SC_200``` ist die Beschaffung des
Keykloak-Signaturschlüssels für die jeweilige Umgebung und die Ablage unter 
```src/test/resources/keys/zeta-achelos.westeurope.cloudapp.azure.com-ecKey.pem```
```src/test/resources/keys/zeta-cd.westeurope.cloudapp.azure.com-ecKey.pem```
```src/test/resources/keys/zeta-dev.westeurope.cloudapp.azure.com-ecKey.pem```
```src/test/resources/keys/zeta-local.westeurope.cloudapp.azure.com-ecKey.pem```
```src/test/resources/keys/zeta-staging.westeurope.cloudapp.azure.com-ecKey.pem```
```src/test/resources/mocks/jwt-sign-key.pem```
notwendig.

---

## Schnellstart

```bash
# Ausführen aller Scenarien der Testsuite
mvn verify

# Ausführen von getaggten Scenarien
# Optionen:
# Smoke Tests         @smoke
# Status ok           @staging
# Status fail         @dev
# Performance         @perf
# AFO Aspects         @TA_A_xxxx
mvn verify "-Dcucumber.filter.tags=@TA_A_25761_02 or @TA_A_27802_01"

# Ausführen gegen eine bestimmte Stage (Cloud)
# 1. Setzen Sie den gewünschten Host (ohne Scheme) via ENV oder Maven:
#    export ZETA_BASE_URL=zeta-dev.example
#    # oder
#    mvn verify -Dzeta_base_url=zeta-dev.example
# 2. Nutzen Sie das Cloud-Profil (environment=cloud bleibt unverändert):
mvn verify -Denvironment=cloud
```

Cucumber-Features (unter `src/test/resources/features`) werden von der JUnit Platform / Cucumber
Engine ausgeführt.

### Ausführen über Docker

Der Multi-Stage-Dockerbuild (`Dockerfile`) funktioniert identisch lokal wie in CI. Typischer Ablauf:

```bash
# 1) Image bauen (die Build-Stage cached alle Maven-Dependencies).
docker build -t testsuite:latest .

# 2) Container ausführen (Volume mount nur für Reports nötig).
docker run --rm \
  -e ZETA_BASE_URL=https://zeta-dev.example \
  -v "$PWD/target/site/serenity:/app/target/site/serenity" \
  -v "$PWD/target/failsafe-reports:/app/target/failsafe-reports" \
  testsuite:latest
```

> **Wichtig**: `ZETA_BASE_URL` wird unverändert an Maven (`-Dzeta_base_url`)
> durchgereicht. Ohne diesen Wert greifen die Tests lediglich auf symbolische Hostnamen wie
`zetaClient`, wodurch die Läufe erwartungsgemäß fehlschlagen.

| Variable              | Default  | Wirkung                                                                                                          |
|-----------------------|----------|------------------------------------------------------------------------------------------------------------------|
| `ZETA_BASE_URL`       | (leer)   | Ziel-Host für Cloud-/Stage-Tests; Pflicht sobald externe Services angesprochen werden sollen.                    |
| `TIGER_ENVIRONMENT`   | `cloud`  | Wählt die Tiger-Konfiguration (`tiger-*.yaml`).                                                                  |
| `CUCUMBER_TAGS`       | `@smoke` | Szenario-Auswahl analog zu `-Dcucumber.filter.tags`.                                                             |
| `SERENITY_EXPORT_DIR` | (leer)   | Optionales Ziel (z. B. `/builds/.../target/site/serenity`); wird mit `/app/target/site/serenity` verlinkt.       |
| `FAILSAFE_EXPORT_DIR` | (leer)   | Optionales Ziel (z. B. `/builds/.../target/failsafe-reports`); wird mit `/app/target/failsafe-reports` verlinkt. |

Der Container führt grundsätzlich `mvn verify` aus.

Alle Varianten laufen headless, da der Container standardmäßig
`-Dtiger.lib.activateWorkflowUi=false` usw. setzt.
Zusätzlich installiert das Image den Wrapper `/usr/local/bin/run-tests.sh`,
der exakt dieselbe Maven-Kommandokette wie der Docker-`CMD` startet
und so in GitLab-Jobs (oder lokalen Shell-Skripten) ohne Copy & Paste genutzt werden kann.
Setzen Sie `SERENITY_EXPORT_DIR` bzw. `FAILSAFE_EXPORT_DIR`,
werden `/app/target/site/serenity` und `/app/target/failsafe-reports` zur Laufzeit
auf die angegebenen Pfade verlinkt (z. B. `$CI_PROJECT_DIR/target/site/serenity` bzw.
`$CI_PROJECT_DIR/target/failsafe-reports`),
sodass GitLab-Artefakte direkt aus der Pipeline-Workspace-Struktur kommen.

#### GitLab CI Docker Build

Der Job `docker-image` (Stage `container` in [.gitlab-ci.yml](.gitlab-ci.yml)) baut exakt dieses
Image und pusht es ins GitLab Container Registry. Er läuft standardmäßig nur auf dem
Default-Branch und verwendet dieselbe Multi-Stage-Konfiguration inklusive offline Maven-Cache. Der
Build nutzt `docker buildx build` mit `oci-mediatypes=false`, damit die Registry einen Docker
Schema-v2-Manifest mit Configuration-Digest erhält; die Inline-Kommentare beschreiben Login und
Push-Schritte.

Beispieljob zur Nutzung des Images in einer zweiten Pipeline:

```yaml
testsuite-smoke:
  image: registry.gitlab.com/<gruppe>/testsuite:latest
  script:
    - /usr/local/bin/run-tests.sh
  variables:
    ZETA_BASE_URL: https://zeta-dev.example
    TIGER_ENVIRONMENT: cloud
    CUCUMBER_TAGS: "@smoke and not @perf"
    SERENITY_EXPORT_DIR: "$CI_PROJECT_DIR/target/site/serenity"
    FAILSAFE_EXPORT_DIR: "$CI_PROJECT_DIR/target/failsafe-reports"
```

Damit entfällt das manuelle Wiederholen des kompletten Maven-Kommandos; Anpassungen erfolgen
ausschließlich über Variablen.

#### Preflight-Checks & `.gitattributes`

Die GitLab-Pipeline besitzt eine zusätzliche Stage `preflight`, in der der Job `utf8_posix_check`
alle versionierten Dateien auf POSIX-kompatible Zeilenenden (LF) und gültiges UTF-8 prüft (binäre
Assets sowie `tools/` werden ausgenommen). Dadurch schlagen Merge-Requests früh fehl, wenn irgendwo
versehentlich CRLF oder ISO-8859-1 eingecheckt würde.

Die Datei [.gitattributes](.gitattributes) erzwingt dieselben Regeln lokal: Git liefert sämtliche
Quelltexte als UTF-8 + LF aus und konvertiert nur Windows-Launcher (`*.bat`, `*.cmd`, `*.ps1`)
zurück
auf CRLF. Verlassen Sie sich daher auf `.gitattributes` anstatt `core.autocrlf`, besonders auf
Windows. Falls der Preflight-Job Probleme meldet, führen Sie einmal `dos2unix <file>` (bzw. `git
checkout -- <file>`) aus oder normalisieren Sie alles mit `git add --renormalize .`.

---

## Tiger-Konfigurationen

* **[tiger.yaml](tiger.yaml)**: Hauptkonfiguration.
* **[tiger-local.yaml](tiger/tiger-local.yaml)**: lokale Variante.
* **[tiger-cloud.yaml](tiger/tiger-cloud.yaml)**: wenn Services extern bereitgestellt werden
  (einheitliches Cloud-Profil).
* **[tiger-proxy-overlay.yaml](tiger/tiger-proxy-overlay.yaml)**: standardmäßig aktiviertes Overlay,
  das
  den per Port-Forward bereitgestellten Tiger-Proxy (`http://localhost:9999`) nutzt und als separate
  Datei verbleibt, damit Sie alternative Proxy-Setups ohne Änderungen an [tiger.yaml](tiger.yaml)
  einbinden können.

Konfigurieren Sie den Cloud-Host zentral über `zeta_base_url` in der `defaults.yaml`.
Alternativ können Sie beim Start `ZETA_BASE_URL` oder einen Maven-Parameter wie
`-Dzeta_base_url=https://zeta-dev.example` setzen. Belassen Sie `environment` auf `cloud`. Der
Proxy-Overlay ist bereits eingebunden und nutzt den via Port-Forward erreichbaren Admin-Port
(`http://localhost:9999`). Stellen Sie sicher, dass vor dem Teststart ein entsprechender
Port-Forward aktiv ist (z. B. `kubectl port-forward svc/tiger-proxy 9999:9999`).
Falls Sie den Proxy für einen Lauf deaktivieren möchten, entfernen Sie den Eintrag im Abschnitt
`additionalConfigurationFiles` oder überschreiben Sie ihn per Umgebungsvariable/Maven-Property.

### Proxy-Tags

- Szenarien, die ohne Standalone-Tiger-Proxy laufen, mit `@no_proxy` taggen.
- Alle anderen Szenarien setzen einen konfigurierten Proxy voraus. Ist `zeta_proxy` ≠ `proxy`
  (z. B. via `-Dzeta_proxy=no-proxy`), werden nicht getaggte Szenarien automatisch übersprungen.

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

---

## Cucumber Methoden

Die zentrale Referenz liegt in [cucumber_methods.adoc](docs/cucumber_methods.adoc) (AsciiDoc im
Ordner `docs/`).
Dort werden deutsche ↔ englische Cucumber Methoden und Best-Practices dokumentiert.
Die Tabelle der projektspezifischen Glue-Steps wird automatisch
aus [cucumber_methods_table.adoc](docs/asciidoc/tables/cucumber_methods_table.adoc) eingebunden,
wobei diese per
`uv run --project docs/scripts generate-cucumber-methods` erzeugt wird.


---

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

---

## Wo TGR-Methoden dauerhaft ablegen

* `docs/tgr_methods.adoc` — kanonische Referenz (Pflicht).
* Die Tabelle unter `docs/asciidoc/tables/cucumber_methods_table.adoc` wird per
  `uv run --project docs/scripts generate-cucumber-methods` generiert.
* PR-Policy: Änderungen an TGR-Docs müssen im PR-Text begründet werden.

---

## Dokumentation (AsciiDoc/Mermaid)

- Build (lokal):
    - `mvn --batch-mode -Pgenerate-documentation -DskipTests=true generate-resources`
    - Artefakte: `target/docs/html/Testplan_ZETA.html`, `target/docs/epub/Testplan_ZETA.epub`
    - Die UV-Umgebung wird automatisch mit `uv sync` aktualisiert; mit
      `-Dtraceability.sync.skip=true`
      lässt sich der Schritt überspringen.
- Inhaltliche Attribute wie `:toc:`, `:sectids:` etc. werden im `docs/asciidoc/Testplan_ZETA.adoc`
  gepflegt (nicht im POM duplizieren).
- Diagramme:
    - Asciidoctor Diagram + Mermaid CLI via Node/Yarn (installiert in `target/node_modules`).
    - Gemeinsamer Diagramm-Cache: `target/docs/diagram-cache` (verhindert Doppel-Rendering für
      HTML/EPUB).
    - Mermaid-Branding: `docs/asciidoc/mermaid-config.json` (einheitliche Farben/Fonts für
      HTML/EPUB).
- GitLab CI:
    - Job `docs` erzeugt die HTML/EPUB-Dokumente mit Maven (kein separater Asciidoctor-Container
      nötig).
    - Pages veröffentlichen ausschließlich Serenity-Reports; Docs werden als Artefakte beigefügt.

Tipps:

- Falls HTML nur „diagram“ statt Bilder zeigt, prüfe, ob die generierten Diagrammdateien im gleichen
  Ordner wie die HTML-Ausgabe liegen (`target/docs/html`).
- Unter Windows wird `mmdc.cmd` verwendet; unter Linux/CI `mmdc`. Der POM kümmert sich um die
  korrekten Pfade.

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
