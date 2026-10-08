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
- [masvs_tags.py](src/testsuite_docs/masvs_tags.py): ergänzt und prüft `@MASVS-*`-Tags in
  Feature-Szenarien anhand einer AFO/MASVS-CSV.
- [zeta_schemas.py](src/testsuite_docs/zeta_schemas.py): prüft und aktualisiert die lokal vendorten
  ZETA-Schemas aus `src/schemas` eines auswählbaren Upstream-Refs.
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

Der Produktumsetzungsstand wird ausschließlich über die Produktstatus-CSV gepflegt.
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

Die Abdeckungsmetriken werden konsistent aus den Testaspekt-Katalogen und Feature-Tags berechnet.
Ein Testaspekt gilt als `implementiert`, sobald mindestens ein Szenario den entsprechenden Tag referenziert.
Szenarien ohne Testaspekt-Tag werden dabei für die Metriken ignoriert; ebenso werden Anforderungs-Tags nicht in die Abdeckungsberechnung
einbezogen.
Die Abdeckung zählt eindeutige Testaspekte und nicht die Anzahl einzelner Szenarioausführungen.
Der Status einer Anforderung (`vollständig`, `teilweise`, `nicht abgedeckt`, `keine Testaspekte`) ergibt sich aus dem Verhältnis
implementierter zu insgesamt vorhandenen Testaspekten.
Das Diagramm `traceability-coverage-requirements-tested.mmd` fasst zusätzlich zusammen, wie viele Anforderungen mindestens einen
implementierten Testaspekt besitzen; Anforderungen ohne Testaspekte werden dort als „kein Testaspekt implementiert“ geführt.
Die Produktumsetzung in der Lückenanalyse basiert ausschließlich auf den CSV-Angaben.

Für die MASVS-Abdeckung liest die Traceability-Generierung zusätzlich `docs/masvs_mapping.csv` und die `@MASVS-*`-Tags aus den
Feature-Dateien.
Sie erzeugt `docs/asciidoc/tables/generated/masvs_coverage_by_category.adoc` mit MASVS-Kategorie, Anforderung, Use Case und Szenario.
Zusätzlich erzeugt sie `docs/asciidoc/tables/generated/masvs_coverage_by_requirement.adoc` mit der MASVS-Abdeckung je Anforderung.
Die MASVS-Tag-Konsistenz wird in der CI vor der Traceability-Generierung mit `tag-masvs-scenarios --check` geprüft.
Diese MASVS-Sicht ist die risikobasierte Sicherheitsprojektion in der Traceability-Generierung.
Die risikobasierte Sicherheitsabdeckung wird damit aus der AFO/MASVS-Zuordnung des Sicherheitstestplans und aus nachgewiesenen Szenario-Tags
abgeleitet.

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

### Interne Laufzeit-Fehlerannotationen

Der Cucumber-Plugin `de.gematik.zeta.traceability.RuntimeCoveragePlugin` erzeugt nach Testläufen die Laufzeit-CSV-Dateien
`runtime_coverage.csv`, `runtime_coverage_testaspects.csv` und `runtime_coverage_meta.csv`.
Zusätzlich kann er eine interne CSV mit Tickets und Verantwortlichen für erwartete rote oder übersprungene Testergebnisse einlesen.
Standardpfad ist `docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv`.
Der Pfad kann mit `-Dzeta.runtime.failure.annotations.csv=...` oder `ZETA_RUNTIME_FAILURE_ANNOTATIONS_CSV` überschrieben werden.
Die Datei ist in `.githubignore` eingetragen und soll nicht in öffentliche Artefakte übernommen werden.

Empfohlenes CSV-Format:

```csv
Zuordnung,Feature,Szenario,Tickets,Verantwortlich
Szenario,src/test/resources/features/UserStory_01/UseCase_22/client_ressource_anfrage_fachdienst_sc_200.feature,"DPoP Token Binding für Access und Refresh Token",https://jira.example/ZETA-1234,Team ZETA
```

Die CSV-Datei ist UTF-8; Umlaute wie `ä`, `ö`, `ü` und `ß` sind erlaubt.
Für neue Dateien sollten die deutschen Spaltennamen oben verwendet werden.

`Zuordnung` ist für die interne Laufzeitfehlerliste `Szenario`.
Szenario-Zuordnungen werden über Feature-Pfad und Szenarioname gegen die konkrete Fehlersituation gematcht.
Der Szenarioname ist die fachliche Zuordnung; AFO- und Testaspekt-Spalten werden für Szenario-Zuordnungen nicht benötigt.
Die generierte `runtime_coverage.csv` enthält dafür zusätzlich `Tickets` und `Verantwortlich` sowie `Scenarios` mit den ausgeführten
Szenarionamen pro AFO.
Die generierte `runtime_coverage_testaspects.csv` enthält zusätzlich die Spalten `Fehler Testrun`, `Tickets`, `Verantwortlich` und
`Scenarios`.
Wenn eine AFO- oder Testaspekt-Zeile mindestens ein bestandenes und mindestens ein fehlgeschlagenes Szenario enthält, wird der
`Laufzeit-Status` als `teilweise fehlgeschlagen` ausgegeben.

Spalten:

- `Zuordnung`: Granularität der Zuordnung.
  Für die interne Liste wird `Szenario` verwendet.
- `Feature`: Feature-Pfad für `Szenario`-Zuordnungen.
  Relative Pfade wie `src/test/resources/features/.../example.feature` sind ausreichend.
  Der Pfad ist optional, sollte aber gesetzt werden, wenn gleiche Szenarionamen in mehreren Features vorkommen können.
- `Szenario`: Exakter Szenarioname aus der `.feature`-Datei oder dem Cucumber-Report.
  Scenario-Outline-Platzhalter wie `<client_name>` werden als variable Segmente erkannt.
- `Tickets`: Ticket-IDs oder URLs, mehrere Einträge mit Semikolon trennen, z. B. `ZETA-1234;ZETA-5678`.
- `Verantwortlich`: Verantwortliche Partei für Nachverfolgung oder Klärung, z. B. Teamname, Hersteller, Komponente oder Ansprechpartner.
  Diese Spalte wird in die Laufzeit-CSV-Ausgabe übernommen.

### Skript: ZETA AFOs aus gemVZ XML erzeugen

```bash
uv run --project docs/scripts generate-zeta-afos --input-xml docs/gemVZ_Afo_ZETA_Guard_V_1.3.0-0_V1.0.0.xml --output-dir docs/asciidoc/afos --force
```

Parameter:

- `--input-xml`: Pfad zur eingecheckten gemVZ ZETA Guard XML-Datei (Standard:
  `docs/gemVZ_Afo_ZETA_Guard_V_1.3.0-0_V1.0.0.xml`).
- `--output-dir`: Zielverzeichnis für die generierten Anforderungen (Standard:
  `docs/asciidoc/afos`)
- `--test-procedure`: Filter auf `<testProcedure>` (Standard:
  `Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"`); leer lassen, um
  alle Anforderungen zu erzeugen
- `--[no-]readme`: Optional `readme.adoc` in `docs/asciidoc/afos` aktualisieren (Standard: aktiv)
- `--force`: Bestehendes Zielverzeichnis vorab löschen

Das Skript legt pro Anforderung eine `A_*.adoc` mit Anker und Titel an und kann optional ein `readme.adoc` mit den Quell-Dokumenten und
allen Includes erstellen.
Dabei werden auch die Quellenliste in `docs/asciidoc/afos/readme.adoc` sowie die Referenzen in `docs/asciidoc/chapters/02_referenzen.adoc`
und `docs/asciidoc/chapters/03_einleitung.adoc` aus der XML-Snapshot-Datei aktualisiert.
Neue gemVZ-Quellstände werden zuerst in eine versionierte XML-Snapshot-Datei überführt und anschließend über dieses Skript verarbeitet.

### Skript: MASVS-Tags aus AFO/MASVS-CSV anwenden und prüfen

Das Skript erwartet eine CSV mit den Spalten `AFO` und `MASVS`.
Die CSV kann aus der AFO/MASVS-Zuordnung des Sicherheitstestplans erstellt werden.
`MASVS` darf entweder als Kategorie (`AUTH`) oder als Tag (`MASVS-AUTH`) angegeben werden.

```bash
uv run --project docs/scripts tag-masvs-scenarios --mapping-csv docs/masvs_mapping.csv
```

Zur formalen Prüfung ohne Schreibzugriff:

```bash
uv run --project docs/scripts tag-masvs-scenarios --mapping-csv docs/masvs_mapping.csv --check
```

Die Prüfung beweist die Postbedingung, dass jedes geparste Szenario oder Beispiel mit gemapptem AFO-Tag alle zugehörigen `@MASVS-*`-Tags
trägt.
Diese Prüfung läuft auch im GitLab-Job `traceability`, bevor die Testplan-Tabellen generiert werden.
Die generierte MASVS-Abdeckung im Testplan wird anschließend durch das Skript `traceability` aus der CSV und den Feature-Tags abgeleitet.

### Skript: ZETA-Schemas prüfen und aktualisieren

Die Testsuite hält die ZETA-Schemas lokal unter `src/test/resources/schemas/v_1_0`.
Die lokale Kopie bleibt bewusst versioniert, damit Szenarioausführungen nicht vom Netzwerk oder einem beweglichen Upstream-Branch abhängen.
Der relevante Upstream-Ref kann per `--ref` oder `ZETA_SCHEMA_REF` gesetzt werden.
Für den aktuellen Release-Stand ist `v1.3.1-2` voreingestellt.
Alternativ kann die GitHub-Tree-URL direkt als `--repo-url` angegeben werden.

Zur Prüfung auf Drift:

```bash
uv run --project docs/scripts check-zeta-schemas --ref v1.3.1-2
uv run --project docs/scripts check-zeta-schemas --repo-url https://github.com/gematik/zeta/tree/v1.3.1-2/src/schemas
```

Das Skript lädt nur `src/schemas` aus dem Upstream-Archiv.
Bei Abweichungen beendet es sich mit Exit-Code `1` und schreibt den Unified Diff nach `target/schema-drift.diff`.
Lokale Mock-Schemas unter `src/test/resources/schemas/mock` werden nicht verglichen.

Zur Aktualisierung der lokalen Kopie:

```bash
uv run --project docs/scripts update-zeta-schemas --ref v1.3.1-2
uv run --project docs/scripts update-zeta-schemas --repo-url https://github.com/gematik/zeta/tree/v1.3.1-2/src/schemas
```

Das Update ersetzt `src/test/resources/schemas/v_1_0` durch die Dateien aus dem gewählten Upstream-Ref.
Zusätzlich wird `src/test/resources/schemas/UPSTREAM.md` mit Repository, Ref, Upstream-Pfad und lokalem Zielpfad geschrieben.

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

Für lange Läufe sind diese Optionen hilfreich:

```bash
uv run --project docs/scripts gitlab-issue-sync --issue-state all --verbose --progress --skip-feature-comments
```

Für eine detaillierte Dry-Run-Auswertung der geplanten Änderungen:

```bash
uv run --project docs/scripts gitlab-issue-sync --issue-state all --skip-feature-comments --report-changes
```

Hinweise:

- Standardmäßig Dry-Run; Änderungen erst mit `--apply`.
- `--verbose` schreibt Fortschritt nach `stderr`, `--log-every` steuert das Intervall, Standard ist `50`.
- `--progress` zeigt bei TTY-Ausgabe einen Fortschrittsbalken; in CI/Nicht-TTY greift weiterhin die normale Log-Ausgabe.
- `--workers` parallelisiert nur lesende GitLab-API-Aufrufe konservativ; Standard ist `8`, Schreibzugriffe bleiben seriell.
- `--report-changes` ergänzt die JSON-Ausgabe um eine Liste der konkret geplanten Änderungen.
- `--skip-feature-comments` überspringt das teure Lesen/Schreiben von `Feature:`-Notizen, wenn nur Zustände synchronisiert werden sollen.
- Die JSON-Ausgabe ist UTF-8-freundlich und enthält `duration_seconds` für die Wall-Clock-Laufzeit.
- Titeländerungen werden zusätzlich als `cosmetic` oder `substantive` klassifiziert.
- Szenario-Tags werden immer verarbeitet (TA-Tag vorhanden → schließen, entfernt → wieder öffnen).
- Issues mit Label `entfällt` werden nicht wieder geöffnet; das Skript versucht in GitLab zusätzlich den Status `Won't do` zu setzen und
  fällt sonst auf `closed` zurück.
- GitLab.com unterstützt das Statusfeld „In progress/Done“ nicht per API-Update; das Skript nutzt open/close.
- Tokenquelle: `/tmp/gitlab_token`, `GITLAB_TOKEN` oder `CI_JOB_TOKEN`.
