# Testsuite Docs Tools

Das Verzeichnis docs/scripts enthält ein mit [uv](https://docs.astral.sh/uv/) verwaltetes
Python-Modul für Hilfsskripte rund um die Dokumentation.

Alle in diesem Dokument aufgeführten Skript-Aufrufe werden aus dem Projektverzeichnis der Testsuite
ausgeführt.

## Einrichtung

```bash
uv sync --project docs/scripts
```

Das legt eine isolierte Umgebung an und installiert die in [pyproject.toml](pyproject.toml)
deklarierten Abhängigkeiten.

## Skripte

- [update_testaspects.py](src/testsuite_docs/update_testaspects.py): erzeugt Asciidoc-Testaspekte
  aus einer Excel-Exportdatei.
- [traceability](src/testsuite_docs/traceability/__main__.py): wertet Anforderungen,
  Testaspekte und Feature-Tags aus und generiert Tabellen/Diagramme für den Testplan.
- [fetch_youtrack_testaspects.py](src/testsuite_docs/fetch_youtrack_testaspects.py): ruft UseCases
  und verknüpfte TestAspekte direkt aus YouTrack ab und gibt eine strukturierte Übersicht aus.
- [generate_cucumber_methods.py](src/testsuite_docs/generate_cucumber_methods.py): erzeugt eine
  generierte TGR/Cucumber-Step-Tabelle aus projektinternem Glue.
- [gitlab_issue_sync.py](src/testsuite_docs/gitlab_issue_sync.py): erstellt/aktualisiert GitLab-Issues
  für AFOs/Testaspekte, schließt Testaspekte mit @TA_-Szenario-Tags und synchronisiert AFO-Issues
  (open/closed).
  der Feature-Struktur eine Markdown-Datei für Serenity (setzt `pydowndoc[bin]` voraus, wird beim
  `uv sync` automatisch installiert).

## Verwendung

### Skript: [update_testaspects.py](src/testsuite_docs/update_testaspects.py)

Zur Erstellung der Eingabedatei
siehe [Issue-Export aus YouTrack](https://www.jetbrains.com/help/youtrack/server/export-issues-csv-excel-format.html).

```bash
uv run --project docs/scripts update-testaspects \
  --excel-path docs/Issues.xlsx \
  --output-dir docs/asciidoc/testaspekte
```

Parameter:

- `--excel-path`: Pfad zur Excel-Quelldatei (Standard: `docs/Issues.xlsx`)
- `--output-dir`: Zielordner fuer die generierten `.adoc`-Dateien (
  Standard: [docs/asciidoc/testaspekte](../asciidoc/testaspekte))

Die Ausgabestruktur wird dabei vollständig geleert und anschließend neu aufgebaut.

### Skript: [traceability](src/testsuite_docs/traceability/__main__.py)

```bash
uv run --project docs/scripts traceability --project-root .
```

Die Traceability-Auswertung stützt sich ausschließlich auf die modellierten
Testaspekte und die Tags in den Feature-Dateien; Laufzeit-Reports aus Serenity
oder Cucumber werden nicht ausgewertet.
Das Maven-Profil `generate-documentation` erzeugt die Artefakte im Schritt
`generate-resources`, z. B. mit `mvn -Pgenerate-documentation generate-resources`.

Szenarien oder ganze Features, bei denen der Testaspekt *noch nicht* im Produkt umgesetzt ist,
werden mit `@product_not_impl` (bzw. `@produkt_not_impl`, `@canary`, `@not_impl`,
`@not_implemented`) getaggt.
Ohne Tag wird von einer Umsetzung im Produkt ausgegangen.
Die generierte Tabelle der User Stories/Use Cases zeigt in
„implementiert (Szenario vorhanden)“ die vorhandene Verknüpfung.

Für die Lückenanalyse zwischen Produktumsetzung und Testsuite-Abdeckung kann optional eine CSV
unter `docs/asciidoc/tables/source/product_implementation.csv` hinterlegt werden (Spalten:
`Anforderung`, optional `Titel` (nur zur Orientierung), `umgesetzt` (ja/nein/teilweise),
optional `Hinweis`). Der Titel wird von der Generierung ignoriert.
Die Traceability-Generierung erzeugt daraus die Tabelle
`docs/asciidoc/tables/generated/product_gap_table.adoc`, die pro Anforderung den gemeldeten
Umsetzungsstand, die Testsuite-Abdeckung (`ja`/`teilweise`/`nein`), die prozentuale
Testabdeckung (implementierte/gesamte Testaspekte) sowie Hinweise zur Lücke zeigt.

Die Abdeckungsmetriken werden konsistent aus den Testaspekt-Katalogen und Feature-Tags berechnet. Ein Testaspekt gilt als `implementiert`, sobald mindestens ein Szenario den entsprechenden Tag referenziert. Szenarien ohne Testaspekt-Tag werden dabei für die Metriken ignoriert; ebenso werden `A_*`-Tags nicht in die Abdeckungsberechnung einbezogen. Die Abdeckung zählt eindeutige Testaspekte und nicht die Anzahl einzelner Szenarioausführungen. Der Status einer Anforderung (`vollständig`, `teilweise`, `nicht abgedeckt`, `keine Testaspekte`) ergibt sich aus dem Verhältnis implementierter zu insgesamt vorhandenen Testaspekten. Das Diagramm `traceability-coverage-requirements-tested.mmd` fasst zusätzlich zusammen, wie viele Anforderungen mindestens einen implementierten Testaspekt besitzen; Anforderungen ohne Testaspekte werden dort als „kein Testaspekt implementiert“ geführt. Die Produktumsetzung in der Lückenanalyse basiert ausschließlich auf den CSV-Angaben und berücksichtigt keine `@product_not_impl`-Tags.

Für Anforderungen mit erhöhter Priorität aus der Bedrohungsanalyse kann eine
zweite CSV unter `docs/asciidoc/tables/source/bedrohungsanalyse_requirements.csv`
hinterlegt werden (Spalten: `Anforderung`, optional `Priorität`, optional
`Begründung`).
Die Generierung erzeugt daraus zwei Tabellen:

- `docs/asciidoc/tables/source/bedrohungsanalyse_requirements.csv`
- `docs/asciidoc/tables/generated/bedrohungsanalyse_summary.adoc`:
  Anforderungsübersicht mit TA-Erfüllung als Bruch und Prozentwert
  (`erfüllt / gesamt`, z. B. `5/8 (63%)`).
- `docs/asciidoc/tables/generated/bedrohungsanalyse_gaps.adoc`:
  nur offene Testaspekte mit `Ist/Soll` als Bruch und Prozentwert.

Die Soll-Ist-Bewertung erfolgt pro Testaspekt, wobei die Priorität der Anforderung auf die zugehörigen Testaspekte heruntergebrochen wird. Als Zielgröße gilt je Testaspekt: `hoch` = 3 Szenarien (sofern möglich), `mittel` = 2 und `niedrig` = 1 eindeutiges Szenario. Der Ist-Wert wird als Anzahl eindeutiger Szenarien je Testaspekt ermittelt.

Parameter:

- `--project-root`: Basisverzeichnis des Repositories.
- `--product-status-csv`: Pfad zu einer CSV mit Angaben zur Produktumsetzung von
  Anforderungen (Standard: `docs/asciidoc/tables/source/product_implementation.csv`).
- `--dry-run`: Generiert die Daten ohne Dateien zu schreiben.
- `--json`: Gibt die aggregierten Daten als JSON auf stdout aus.

Tabellen werden über die gemeinsame Python-Utility `testsuite_docs.asciidoc_tables`
als Asciidoc erzeugt. Wiederholte Werte in gruppierten Spalten (z. B.
Anforderungen oder User Stories) werden nur in der ersten Zeile gezeigt;
nachfolgende Zeilen bleiben leer, um Zusammengehörigkeiten ohne Rowspan-Markup
sichtbar zu machen.

### Skript: ZETA AFOs aus gemVZ XML erzeugen

```bash
uv run --project docs/scripts generate-zeta-afos --input-xml docs/gemVZ_Afo_ZETA_Guard_V_1.2.0_V1.0.0.xml --output-dir docs/asciidoc/afos/gemSpec_ZETA --force
```

Parameter:

- `--input-xml`: Pfad zur gemVZ ZETA Guard XML (Standard:
  `docs/gemVZ_Afo_ZETA_Guard_V_1.2.0_V1.0.0.xml`)
- `--output-dir`: Zielverzeichnis für die generierten Anforderungen (Standard:
  `docs/asciidoc/afos/gemSpec_ZETA`)
- `--test-procedure`: Filter auf `<testProcedure>` (Standard:
  `Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"`); leer lassen, um
  alle Anforderungen zu erzeugen
- `--[no-]readme`: Optional `readme.adoc` in `docs/asciidoc/afos` aktualisieren (Standard: aktiv)
- `--force`: Bestehendes Zielverzeichnis vorab löschen

Das Skript legt pro Anforderung eine `A_*.adoc` mit Anker und Titel an und kann optional
ein `readme.adoc` mit den Quell-Dokumenten und allen Includes erstellen.

### Skript: [fetch_youtrack_testaspects.py](src/testsuite_docs/fetch_youtrack_testaspects.py)

```bash
uv run --project docs/scripts fetch-youtrack-testaspects --url https://youtrack.example.com
```

Parameter:

- `--url`: Basis-URL der YouTrack-Instanz (kann alternativ über die Umgebungsvariable `YOUTRACK_URL`
  gesetzt werden)
- `YOUTRACK_TOKEN`: Permanenter Access Token für die Authentifizierung (über Umgebungsvariable
  setzen)

#### YouTrack Access Token erstellen

Damit das Script [fetch_youtrack_testaspects.py](src/testsuite_docs/fetch_youtrack_testaspects.py)
auf YouTrack zugreifen kann, benötigst du einen permanenten Access Token.

1. **Anmelden** bei YouTrack und ins **Profil** wechseln.
2. Gehe zu **Access Management → Access Tokens**.
3. Klicke **Create new token** und gib ihm einen Namen, z. B. `testsuite_docs_token`.
4. Wähle Berechtigungen: mindestens **Read Issues**.
5. Token erstellen und **sofort kopieren** (wird nur einmal angezeigt).
6. Token als Umgebungsvariable setzen:
    - Linux/macOS:
      ```bash
      export YOUTRACK_TOKEN="<access-token>"
      ```
    - Windows PowerShell:
      ```powershell
      setx YOUTRACK_TOKEN "<access-token>"
      ```
7. Script ausführen:
   ```bash
   uv run --project docs/scripts fetch-youtrack-testaspects --url https://youtrack.example.com
   ```

### Skript: [generate_cucumber_methods.py](src/testsuite_docs/generate_cucumber_methods.py)

Erzeugt eine Asciidoc-Liste der projektspezifischen Glue-Schritte aus `src/test/java` (inkl.
Javadoc-Kurzbeschreibung). Außerdem verweist die Datei auf die offizielle TGR-Doku
(`docs/Tiger-User-Manual.html`), ohne diese neu zu erzeugen.

```bash
uv run --project docs/scripts generate-cucumber-methods
```

Ergebnis: `docs/asciidoc/tables/generated/cucumber_methods_table.adoc`
(nur Tabelle, inkl. Javadoc-Kurzbeschreibung).

Optional:

- `--glue-dir`: anderer Glue-Root (Standard: `src/test/java`).
- `--output`: eigener Zielpfad (Standard: `docs/asciidoc/tables/generated/cucumber_methods_table.adoc`).

### Skript: [gitlab_issue_sync.py](src/testsuite_docs/gitlab_issue_sync.py)

```bash
uv run --project docs/scripts gitlab-issue-sync --token-file /tmp/gitlab_token --issue-state all
```

Hinweise:

- Standardmäßig Dry-Run; Änderungen erst mit `--apply`.
- Szenario-Tags werden immer verarbeitet (TA-Tag vorhanden → schließen, entfernt → wieder öffnen).
- GitLab.com unterstützt das Statusfeld „In progress/Done“ nicht per API-Update; das Skript nutzt open/close.
- Tokenquelle: `/tmp/gitlab_token`, `GITLAB_TOKEN` oder `CI_JOB_TOKEN`.
