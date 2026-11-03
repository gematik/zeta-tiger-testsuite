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

## Tests

```bash
uv run --project docs/scripts generate-doc-includes
uv run --project docs/scripts traceability --project-root .
uv run --project docs/scripts generate-cucumber-methods
uv run --project docs/scripts pytest docs/scripts/tests
```

pytest ist als Entwicklungsabhängigkeit hinterlegt und wird von `uv run` automatisch installiert.
Für eine separate Installation einschließlich Tests `uv sync --project docs/scripts --locked` ohne `--no-dev` verwenden.
Die Repository-Entwurfsprüfung liest auch generierte Tabellen; deshalb gehören die drei Vorbereitungsschritte zum Testlauf aus einem frischen Checkout.
Das Maven-Profil `generate-documentation` und der CI-Job `traceability` erzeugen
diese Artefakte vor denselben pytest-Tests.

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
  Schema- und OpenAPI-Pakete anhand ihrer jeweils eigenen Upstream-Refs.
- [gitlab_issue_sync.py](src/testsuite_docs/gitlab_issue_sync.py): erstellt/aktualisiert GitLab-Issues
  für AFOs/Testaspekte, schließt Testaspekte mit @TA_-Szenario-Tags und synchronisiert AFO-Issues
  (open/closed).

## Verwendung

### Quelltreue und Dokumentstruktur prüfen

```bash
uv run --project docs/scripts generate-doc-includes
uv run --project docs/scripts traceability --project-root .
uv run --project docs/scripts generate-cucumber-methods
uv run --project docs/scripts check-zeta-design
```

Der bestehende Befehl prüft Quelltreue, Zitate, Includes, UseCase-Struktur und
Referenzkonsistenz. TestAspekte und Zuordnungen werden aus aktuellen Dateien ermittelt.
Die ausgewählten AFO-Dokumente unter `docs/asciidoc/afos` werden direkt mit dem
festgeschriebenen CC-XLSX-Katalog verglichen. Fehlende AFOs bleiben Fehler, auch wenn
sie vom Produkttestfilter des Generators nicht ausgewählt werden.
TestAspekt-IDs und -Verweise sind ausschließlich unter „Abgedeckte Testaspekte“ zulässig;
der Produktablauf benötigt keine wiederholten Referenzen.
Gespeicherte Entwurfserwartungen, Reviewfreigaben und Schema-/Tabellenzuordnungen
werden nicht als Eingaben akzeptiert. Ein grüner Lauf bestätigt keine fachliche
Korrektheit oder Vollständigkeit. Diese erfordern neue Analyse gegen die Spezifikation.
Details: [Prüfanleitung](../specifications/2.0.0-cc/verification/README.md).

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
Die Anforderungsliste kommt aus der Produkttest-Auswahl des lokal abgeleiteten CC-Prüfverzeichnisses
`docs/specifications/2.0.0-cc/derived/gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xml`.
Es gilt derselbe Prüfverfahrensfilter wie beim AFO-Generator. Zusätzliche gepflegte AFOs bleiben enthalten.
Fehlt die AFO-Dokumentation zu einer ausgewählten Kataloganforderung, bricht die Testplangenerierung mit einer Fehlermeldung ab.
Das Maven-Profil `generate-documentation` erzeugt die Artefakte im Schritt
`generate-resources`, z. B. mit `mvn -Pgenerate-documentation generate-resources`.
Die Anforderungs-, Testaspekt- und UseCase-Zuordnungen werden in den Tabellen des
Testplans dargestellt. Die Serenity-Szenarioberichte erhalten keinen zusätzlichen
Traceability-Anhang. `--json` gibt die aggregierten Daten ausschließlich auf stdout aus;
die Generierung legt keine zusätzliche JSON-Datei ab.

Die generierte Tabelle der User Stories/Use Cases zeigt in
der Spalte „getestet“ die vorhandene Verknüpfung.

Die Tabelle `docs/asciidoc/tables/generated/requirements_coverage_table.adoc` zeigt
alle Anforderungen mit laufender Nummer, Szenarioabdeckung und dem Prozentwert
getesteter Testaspekte einschließlich der absoluten Anzahl (z. B. `67% (2/3)`).

Die Abdeckungsmetriken werden konsistent aus den Testaspekt-Katalogen und Feature-Tags berechnet.
Ein Testaspekt gilt in diesen statischen Übersichten als `getestet`, sobald mindestens ein nicht ignoriertes Szenario den entsprechenden Tag referenziert.
Szenarien mit `@ignore` (unabhängig von Groß-/Kleinschreibung, also auch `@Ignore`) werden für alle Abdeckungsmetriken einschließlich MASVS nicht gezählt.
Das gilt auch für geerbte Feature-Tags. Bei Szenariogrundrissen werden nur nicht ignorierte Beispiele-Blöcke berücksichtigt;
sind alle Beispiele ignoriert, tragen auch die Tags des Szenariogrundrisses nicht zur Abdeckung bei.
Für die Testaspekt-basierte Abdeckung werden Szenarien ohne Testaspekt-Tag ignoriert;
Anforderungs-Tags fließen ebenfalls nicht in diese Berechnung ein.
Die Abdeckung zählt eindeutige Testaspekte und nicht die Anzahl einzelner Szenarioausführungen.
Der Status einer Anforderung (`vollständig`, `teilweise`, `nicht abgedeckt`, `keine Testaspekte`) ergibt sich aus dem Verhältnis
getesteter zu insgesamt vorhandenen Testaspekten.
Das Diagramm `traceability-coverage-requirements-tested.mmd` fasst zusätzlich zusammen, wie viele Anforderungen mindestens einen
getesteten Testaspekt besitzen; Anforderungen ohne Testaspekte werden dort als „kein Testaspekt getestet“ geführt.

Für die MASVS-Abdeckung liest die Traceability-Generierung zusätzlich `docs/masvs_mapping.csv` und die `@MASVS-*`-Tags aus den
Feature-Dateien.
Diese Sicht ordnet Szenarien anhand ihrer Anforderungs- und MASVS-Tags zu und berücksichtigt sie auch ohne Testaspekt-Tag.
Sie erzeugt `docs/asciidoc/tables/generated/masvs_coverage_by_category.adoc` mit MASVS-Kategorie, Anforderung, Use Case und Szenario.
Zusätzlich erzeugt sie `docs/asciidoc/tables/generated/masvs_coverage_by_requirement.adoc` mit der MASVS-Abdeckung je Anforderung.
Die MASVS-Tag-Konsistenz wird in der CI vor der Traceability-Generierung mit `tag-masvs-scenarios --check` geprüft.
Diese MASVS-Sicht ist die risikobasierte Sicherheitsprojektion in der Traceability-Generierung.
Die risikobasierte Sicherheitsabdeckung wird damit aus der AFO/MASVS-Zuordnung des Sicherheitstestplans und aus nachgewiesenen Szenario-Tags
abgeleitet.

Parameter:

- `--project-root`: Basisverzeichnis des Repositories.
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
Die Anforderungsliste verwendet dieselbe XML-Produkttest-Auswahl und die zusätzlichen gepflegten AFOs wie der Testplan.
Die Laufzeit-CSV enthält Kataloganforderungen auch ohne AFO-Datei oder Szenarioergebnis;
Titel werden aus vorhandenen AFO-Dateien ergänzt und ansonsten dem Prüfverzeichnis entnommen.
Fehlt das Prüfverzeichnis oder ist es ungültig, wird ein Fehler gemeldet.
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
Die Spalte `Testaspekte mit Laufzeitergebnis` zählt Testaspekte mit mindestens einem erfassten Szenarioergebnis, einschließlich übersprungener Szenarien.
`Testaspekte bestanden` zählt davon ausschließlich Testaspekte mit erfolgreichem Laufzeitstatus.
Wenn eine AFO- oder Testaspekt-Zeile mindestens ein bestandenes und mindestens ein fehlgeschlagenes Szenario enthält, wird der
`Laufzeit-Status` als `teilweise fehlgeschlagen` ausgegeben.

Zusätzlich erzeugt das Plugin am Ende des vollständigen Laufs im Serenity-Ausgabeordner
`runtime_failure_annotation_check.csv` und `runtime_failure_annotation_check.status`.
Der Gegencheck arbeitet auf Szenarioebene und verwendet Feature-Pfad sowie Szenarioname;
bei Scenario Outlines werden die vorhandenen Platzhalterregeln verwendet.
Die Werte der Spalte `Typ` sind bewusst englische, maschinenlesbare Konstanten:
`FAILURE_AMBIGUOUS`, `FAILURE_WITHOUT_TICKET`, `FAILURE_WITH_TICKET`, `IGNORED_ANNOTATION`,
`INVALID_ANNOTATION`, `NOT_EXECUTED`, `ANNOTATION_AMBIGUOUS`, `ANNOTATION_ACTIVE`,
`EXECUTION_INCOMPLETE` und `TICKET_REMOVABLE`.
`FAILURE_WITHOUT_TICKET` bezeichnet ein fehlgeschlagenes Szenario ohne passende Ticketzeile;
`TICKET_REMOVABLE` bezeichnet ein im aktuellen Lauf erfolgreiches Szenario mit bisheriger Ticketzeile.
`NOT_EXECUTED` und `EXECUTION_INCOMPLETE` sind keine Löschfreigabe, weil aus einem
gefilterten oder übersprungenen Lauf kein erfolgreicher Fix abgeleitet werden kann.
Der Status enthält `PASS`, solange keine fehlende, ungültige oder mehrdeutige Ticketzuordnung
gefunden wurde, und sonst `FAIL`.
Dieser Status bewertet nur die Ticketabdeckung, verändert den Job-Exit-Code nicht und ersetzt nicht den eigentlichen Teststatus.

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

Für das V2.0.0-CC-Entwurfspaket erzeugt `uv run --project docs/scripts generate-zeta-catalog-xml` ein deterministisches lokales XML aus dem XLSX-Katalog.
`uv run --project docs/scripts generate-zeta-catalog-xml --check` prüft die eingecheckte Ableitung ohne Schreibzugriff.
Beide Befehle validieren vorab gegen das lokale XSD, das ausschließlich `type` und `lastUpdate` optional macht; das originale XSD bleibt unverändert versioniert.
Quellen, Feldzuordnung und Grenzen dieser Ableitung sind in [der Paketdokumentation](../specifications/2.0.0-cc/README.md) beschrieben.
Diese Ableitung ist kein veröffentlichtes gematik-XML. Der AFO-Generator verwendet sie standardmäßig.

```bash
uv run --project docs/scripts generate-zeta-afos --output-dir target/generated-cc-afos --no-readme
```

Parameter:

- `--input-xml`: Pfad zur eingecheckten gemVZ ZETA Guard XML-Datei (Standard:
  `docs/specifications/2.0.0-cc/derived/gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xml`).
- `--output-dir`: Zielverzeichnis für die generierten Anforderungen (Standard:
  `docs/asciidoc/afos`)
- `--test-procedure`: Filter auf `<testProcedure>` (Standard:
  `Festlegungen zur funktionalen Eignung "Produkttest/Produktübergreifender Test"`); leer lassen, um
  alle Anforderungen zu erzeugen
- `--[no-]readme`: Optional `readme.adoc` in `docs/asciidoc/afos` aktualisieren (Standard: aktiv)
- `--force`: Bestehende Ausgabeordner der im XML ausgewählten Spezifikationen vorab vollständig löschen

Das Skript legt pro Anforderung eine `A_*.adoc` mit Anker und Titel an und kann optional ein `readme.adoc` mit den Quell-Dokumenten und
allen Includes erstellen.
Dabei werden auch die Quellenliste in `docs/asciidoc/afos/readme.adoc` sowie die Referenzen in `docs/asciidoc/chapters/02_referenzen.adoc`
und `docs/asciidoc/chapters/03_einleitung.adoc` aus der XML-Snapshot-Datei aktualisiert.
Neue gemVZ-Quellstände werden zuerst in eine versionierte XML-Snapshot-Datei überführt und anschließend über dieses Skript verarbeitet.

Bei `--force` ersetzt der gewählte Katalogstand zusätzliche oder bearbeitete AFOs in den
betroffenen Ausgabeordnern des Testplans. Die Entwurfskennzeichnung
und lokalen Quellenverweise des gepflegten Testplans werden separat gepflegt; der Generator
konstruiert seine Quellenlinks aus den XML-Metadaten.
Für eine getrennte CC-Generierung `--output-dir` zusammen mit `--no-readme` verwenden.
`--output-dir` allein leitet die oben
genannten README- und Referenzaktualisierungen nicht um.

### Automatische Include-Verzeichnisse

```bash
uv run --project docs/scripts generate-doc-includes
```

Der Befehl erzeugt die ignorierten AFO- und TestAspect-Verzeichnisse aus den vorhandenen Quelldateien.
Er verändert weder AFO- noch TestAspect-Definitionen. Maven und CI führen ihn vor den Dokumentationsprüfungen aus.
Auch vor einem direkten `check-zeta-design` oder einer Asciidoctor-Vorschau ist er erforderlich.

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
Das Maven-Profil `generate-documentation` führt dieselbe Prüfung vor der Traceability-Generierung aus.
Die generierte MASVS-Abdeckung im Testplan wird anschließend durch das Skript `traceability` aus der CSV und den Feature-Tags abgeleitet.

### Skript: ZETA-Schemas prüfen und aktualisieren

Die Testsuite hält die Upstream-Pakete unter `src/test/resources/schemas` lokal und versioniert,
damit Szenarioausführungen nicht vom Netzwerk oder einem beweglichen Upstream-Branch abhängen.
[sources.json](../../src/test/resources/schemas/sources.json) enthält für jedes Paket das
Repository, den Ref und entweder das vollständige Quellverzeichnis oder die genaue Dateiauswahl.
Die Herkunft und Verwendung beschreibt jeweils das `UPSTREAM.md` im Paketverzeichnis.

Zur Prüfung auf Drift:

```bash
uv run --project docs/scripts check-zeta-schemas
uv run --project docs/scripts check-zeta-schemas --local-dir src/test/resources/schemas/push_notifications_openapi
uv run --project docs/scripts check-zeta-schemas --all
```

Ohne weitere Auswahl wird `v_2_0` geprüft. `--local-dir` wählt ein anderes Paket mitsamt
seinem eigenen Ref. `--all` prüft alle registrierten Pakete ausschließlich anhand des Manifests;
dies ist auch die verpflichtende CI-Prüfung. Einzelprüfungen erlauben abweichende Quellen
über `--ref`/`ZETA_SCHEMA_REF`, `--repo-url`/`ZETA_SCHEMA_REPO_URL` und `--upstream-dir`.
Eine GitHub-Tree-URL ist ebenfalls als `--repo-url` zulässig.
Einzelprüfungen und Updates verwenden ohne expliziten Ref den Paket-Pin aus `sources.json`.
Fehlt dieser, ist ein Ref über `--ref`, `ZETA_SCHEMA_REF` oder eine GitHub-Tree-URL erforderlich;
andernfalls bricht das Werkzeug vor dem Download ab. Einen festen Commit-Fallback im Code gibt es nicht.
Das Skript lädt das Upstream-Archiv und extrahiert die im Manifest festgelegten Dateien.
Nur das paketlokale `UPSTREAM.md` wird vom Bytevergleich ausgenommen; zusätzliche Dateien
und Änderungen an importierten Dateien gelten als Drift.
Bei Abweichungen beendet es sich mit Exit-Code `1` und schreibt den Unified Diff nach `target/schema-drift.diff`.
Lokale Mock-Schemas unter `src/test/resources/schemas/mock` werden nicht verglichen.

Zur Aktualisierung der lokalen Kopie:

```bash
uv run --project docs/scripts update-zeta-schemas
uv run --project docs/scripts update-zeta-schemas --ref <geprüfter-commit>
```

Ein Update ersetzt das ausgewählte Paket und aktualisiert dessen Eintrag in `sources.json`.
Die genaue Dateiauswahl selektiv importierter Pakete bleibt erhalten, solange nicht explizit
ein vollständiges `--upstream-dir` gewählt wird. Neue Upstream-Dateien müssen bewusst in die
Dateiauswahl aufgenommen werden. Für reproduzierbare Updates einen geprüften Commit verwenden.
Das paketlokale `UPSTREAM.md` wird mit Repository, Ref, Quell- und Zielpfad aktualisiert;
ergänzende Abschnitte ab der ersten Überschrift zweiter Ebene bleiben erhalten.
`v_2_0` enthält `openapi/` und `schemas/` aus demselben Ref; die vollständige Dateiauswahl
dieser beiden Quellverzeichnisse ist gemeinsam im Manifest hinterlegt. Relative Referenzen
bleiben dadurch unverändert.
Lokale Profile und Adapter außerhalb der registrierten Pakete werden nicht überschrieben.

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
