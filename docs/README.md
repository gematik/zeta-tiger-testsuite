# Asciidoc Struktur

Dieses Projekt generiert den Testplan über Asciidoctor (siehe `pom.xml`).

## Hauptdatei

- [Testplan_ZETA.adoc](asciidoc/Testplan_ZETA.adoc)

## Bilder und Diagramme

- `docs/asciidoc/images/`: Quellen für Bilder
- `docs/asciidoc/diagrams/`: Mermaid-Quellen
- `docs/asciidoc/tables/`: Tabellen-Quellen
- Generierte Ausgaben landen im Build unter `target/docs/…`.

## Build

- Lokale Generierung: `mvn -Pgenerate-documentation generate-resources`
- Kompletter Build: `mvn -Pgenerate-documentation -DskipTests package`
  - Währenddessen synchronisiert Maven automatisch die UV-Umgebung; mit
    `-Dtraceability.sync.skip=true` lässt sich das überspringen.

## Python-Hilfsskripte

- [scripts](scripts): uv-Modul mit Tools zur Pflege der Dokumentation (`uv sync`, dann
  `uv run update-testaspects`)
