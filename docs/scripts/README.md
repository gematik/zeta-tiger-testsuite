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
- [serenity_readme.py](src/testsuite_docs/serenity_readme.py): erzeugt aus jedem `readme.adoc`
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
uv run --project docs/scripts traceability build --project-root .
```

Vor dem Generieren sollten aktuelle Testergebnisse vorliegen (z. B. via
`mvn test`), damit die Traceability-Artefakte Laufzeitdaten enthalten. Alternativ
erstellt `mvn -Pgenerate-documentation verify` die Testergebnisse,
Traceability-Artefakte sowie den Testplan in einem Durchlauf unter
`target/docs/html`.

Szenarien oder ganze Features, bei denen der Testaspekt *noch nicht* im Produkt umgesetzt ist,
werden mit `@product_not_impl` (bzw. `@produkt_not_impl`, `@canary`, `@not_impl`,
`@not_implemented`) getaggt.
Ohne Tag wird von einer Umsetzung im Produkt ausgegangen.
Die generierte Tabelle der User Stories/Use Cases weist dies in der Spalte „im Produkt umgesetzt“
aus, zeigt in „implementiert (Szenario vorhanden)“ die vorhandene Verknüpfung und listet den
„letzten erfolgreichen Testlauf“ separat.

Für die Lückenanalyse zwischen Produktumsetzung und Testsuite-Abdeckung kann optional eine CSV
unter `docs/asciidoc/tables/product_implementation.csv` hinterlegt werden (Spalten:
`Anforderung`, optional `Titel` (nur zur Orientierung), `umgesetzt` (ja/nein/teilweise),
optional `Hinweis`). Der Titel wird von der Generierung ignoriert.
Die Traceability-Generierung erzeugt daraus die Tabelle
`docs/asciidoc/tables/product_gap_table.adoc`, die pro Anforderung den gemeldeten
Umsetzungsstand, die Testsuite-Abdeckung (`ja`/`teilweise`/`nein`) sowie Hinweise zur Lücke zeigt.

Parameter:

- `--project-root`: Basisverzeichnis des Repositories.
- `--cucumber-json`: Pfad zu einer Cucumber JSON-Datei (Standard:
  `target/cucumber/traceability.json`).
- `--product-status-csv`: Pfad zu einer CSV mit Angaben zur Produktumsetzung von
  Anforderungen (Standard: `docs/asciidoc/tables/product_implementation.csv`).
- `--dry-run`: Generiert die Daten ohne Dateien zu schreiben.
- `--json`: Gibt die aggregierten Daten als JSON auf stdout aus.

Tabellen werden über `pytablewriter` als Asciidoc erzeugt. Wiederholte Werte in
gruppierten Spalten (z. B. Anforderungen oder User Stories) werden nur in der
ersten Zeile gezeigt; nachfolgende Zeilen bleiben leer, um Zusammengehörigkeiten
ohne Rowspan-Markup sichtbar zu machen.

### Skript: ZETA AFOs aus gemVZ XML erzeugen

```bash
uv run --project docs/scripts generate-zeta-afos --input-xml docs/gemVZ_Afo_ZETA_Guard_V_1.1.0_V1.0.0.xml --output-dir docs/asciidoc/afos/gemSpec_ZETA --force
```

Parameter:

- `--input-xml`: Pfad zur gemVZ ZETA Guard XML (Standard:
  `docs/gemVZ_Afo_ZETA_Guard_V_1.1.0_V1.0.0.xml`)
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

### Skript: [serenity_readme.py](src/testsuite_docs/serenity_readme.py)

Erstellt Markdown-Versionen aller `readme.adoc`-Dateien innerhalb der Feature-Struktur, damit
Serenity die Narratives direkt in den Reports anzeigen kann:

```bash
uv run --project docs/scripts serenity-sync-readme --project-root .
```

Parameter:

- `--project-root`: Repository-Wurzel (Standard: `.`).
- `--features-dir`: Pfad zu den Features (Standard:
  `src/test/resources/features` relativ zum Projektroot).
- `--dry-run`: Nur anzeigen, welche Markdown-Dateien geändert würden.
- `--verbose`: Jeden verarbeiteten Pfad in der Ausgabe zeigen.

Das Skript überschreibt vorhandene `readme.md`-Dateien mit der generierten Markdown-Version. Die
benötigten Downdoc-Binaries werden über die `pydowndoc[bin]`-Abhängigkeit eingebunden; nach
`uv sync --project docs/scripts` stehen sie plattformübergreifend bereit.

### Skript: [generate_cucumber_methods.py](src/testsuite_docs/generate_cucumber_methods.py)

Erzeugt eine Asciidoc-Liste der projektspezifischen Glue-Schritte aus `src/test/java` (inkl.
Javadoc-Kurzbeschreibung). Außerdem verweist die Datei auf die offizielle TGR-Doku
(`docs/Tiger-User-Manual.html`), ohne diese neu zu erzeugen.

```bash
uv run --project docs/scripts generate-cucumber-methods
```

Ergebnis: `docs/asciidoc/tables/cucumber_methods_table.adoc`
(nur Tabelle, inkl. Javadoc-Kurzbeschreibung).

Optional:

- `--glue-dir`: anderer Glue-Root (Standard: `src/test/java`).
- `--output`: eigener Zielpfad (Standard: `docs/asciidoc/tables/cucumber_methods_table.adoc`).
