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
- Lokale Generierung: `mvn -Pasciidoc-enabled generate-resources`
- Kompletter Build: `mvn -Pasciidoc-enabled -DskipTests package`

