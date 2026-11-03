# Asciidoc Struktur

Dieses Projekt generiert den Testplan über Asciidoctor (siehe `pom.xml`).

## Hauptdatei

- [Testplan_ZETA.adoc](asciidoc/Testplan_ZETA.adoc)

## UseCases pflegen

Nutze die [UseCase-Vorlage](../src/test/resources/features/Use_Case_Template.adoc) und
die [Regeln zur UseCase-Pflege](../AGENTS.md#usecases-pflegen). UseCases beschreiben
fachliche Produktinteraktionen und ihre Voraussetzungen, einschließlich der
Aktivierungsbedingungen spezifikationsseitig optionaler Funktionen. Frei wählbare
Implementierungs-, Optimierungs- und Umgebungsdetails gehören in die vorhandene
Konfigurations-, Betriebs- oder Testdokumentation.

## Bilder und Diagramme

- `docs/asciidoc/images/`: Quellen für Bilder
- `docs/asciidoc/diagrams/`: Mermaid-Quellen
- `docs/asciidoc/tables/`: Tabellen-Quellen
- Das lokal gerenderte HTML landet unter `target/docs/html/`.
- Generierte Includes, Tabellen und Diagrammquellen liegen in den jeweiligen Asciidoc-Quellverzeichnissen.

## Build

- Lokale Generierung: `mvn -Pgenerate-documentation generate-resources`
- Include-Indizes, Tabellen und Diagrammquellen werden dabei automatisch erzeugt.
- Kompletter Build: `mvn -Pgenerate-documentation -DskipTests package`
  - Währenddessen synchronisiert Maven automatisch die UV-Umgebung; mit
    `-Dtraceability.sync.skip=true` lässt sich das überspringen.

Für eine direkte Asciidoctor-Vorschau aus einem frischen Checkout vorher im Projektverzeichnis ausführen:

```bash
uv run --project docs/scripts generate-doc-includes
uv run --project docs/scripts traceability --project-root .
uv run --project docs/scripts generate-cucumber-methods
```

Maven übergibt [puppeteer-config.json](puppeteer-config.json) unter Linux als
`mermaid-puppeteer-config` mit absolutem Pfad an Asciidoctor Diagram.
Der CI-Job installiert Mermaid CLI und Puppeteer aus `package.json` mit dem
festgeschriebenen `pnpm-lock.yaml` und verwendet den Chromium-Browser der Alpine-Umgebung.
Die pnpm-Version übernimmt er aus `pom.xml`. Das CI-HTML liegt unter `docs/Testplan_ZETA.html`.

### Windows

Das Maven-Profil wählt unter Windows automatisch [scripts/mmdc.cmd](scripts/mmdc.cmd).
Der Launcher verwendet die von Maven unter `target` installierten Node.js-/Mermaid-Versionen
und [puppeteer-windows.json](puppeteer-windows.json). Seine Pfade werden vom Skriptverzeichnis
aus aufgelöst; Node.js muss nicht zusätzlich im `PATH` stehen. Chrome wird über eine Pipe
angesprochen, und Puppeteer verwaltet das temporäre Browserprofil.

Puppeteer verwendet standardmäßig den zu seiner installierten Version passenden Browser
aus seinem Cache. Falls er noch fehlt, kann er nach der Maven-Phase `initialize` installiert werden:

```powershell
mvn -Pgenerate-documentation initialize
& .\target\node\node.exe .\target\node_modules\puppeteer\install.mjs
mvn -Pgenerate-documentation generate-resources
```

Ein vorhandener Chrome-Browser lässt sich alternativ für den aktuellen Prozess konfigurieren:

```powershell
$env:PUPPETEER_EXECUTABLE_PATH = 'C:\Pfad zu Chrome\chrome.exe'
mvn -Pgenerate-documentation generate-resources
```

`PUPPETEER_CACHE_DIR` kann das Browser-Cache-Verzeichnis festlegen. Ein eigener Launcher kann
weiterhin mit `-Dmmdc.cmd=...` gewählt werden. Benutzernamen, Browser-Versionen und lokale
Cache-Pfade werden nicht in die Repository-Konfiguration eingetragen.
Unter Windows verwendet der Launcher stattdessen `puppeteer-windows.json`.

## Python-Hilfsskripte

- [scripts](scripts): uv-Modul mit Tools zur Pflege der Dokumentation (`uv sync`, dann
  `uv run update-testaspects`)
