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

Zum Ausführen des Features ```Client_ressource_anfrage_fachdienst_SC_200``` ist die Beschaffung des Keykloak-Signaturschlüssels und die Ablage unter ```src/test/resources/mocks/jwt-sign-key.pem``` notwendig.

---

## Schnellstart

```bash
mvn verify
```

Cucumber-Features (unter `src/test/resources/features`) werden von der JUnit Platform / Cucumber
Engine ausgeführt.

---

## Tiger-Konfigurationen

* **[tiger.yaml](tiger.yaml)**: Hauptkonfiguration.
* **[tiger-local.yaml](tiger-local.yaml)**: lokale Variante.
* **[tiger-cloud.yaml](tiger-cloud.yaml)**: wenn Services extern bereitgestellt werden.

---

## TGR Methoden

Die zentrale Referenz liegt in `docs/tgr_methods.md`.
Dort sind deutsche ↔ englische TGR-Steps,
Syntaxbeispiele und Best-Practices dokumentiert.

Kurz: wichtige Steps

* `TGR sende eine leere GET-Anfrage an "url"`
* `TGR sende eine POST-Anfrage an "url" mit Body:`
* `TGR finde die letzte Anfrage` / `TGR finde die letzte Anfrage mit Pfad "..."`
* `TGR prüfe aktuelle Antwort mit Attribut "$.responseCode" stimmt überein mit "200"`
* `TGR lösche aufgezeichnete Nachrichten`
* `TGR setze Anfrage Timeout auf X Sekunden`

Lege Änderungen an `docs/tgr_methods.md` zentral und versioniert ab.

---

<a name="troubleshooting--tipps"></a>

## Troubleshooting & Tipps

* **Server not found**: Prüfe `tiger.yaml` auf exakte Server-Keys und Working Directory beim Start.
* **Port conflicts / Windows locks**: Nutze `active: false` + dynamische Ports oder Docker.
* **Actuator Health fail**: Achte auf `spring-boot-starter-actuator` +
  `management.endpoints.web.exposure.include=health`.
* **Cucumber findet keine Features**: Features müssen unter `src/test/resources/features` liegen;
  Cucumber Engine als Test-Dependency vorhanden.
* **Logs**: Tiger schreibt Server-Logs in `target/serverLogs/` (oder `build/`) — immer prüfen.

---

## Wo TGR-Methoden dauerhaft ablegen

* `docs/tgr_methods.md` — kanonische Referenz (Pflicht).
* PR-Policy: Änderungen an TGR-Docs müssen im PR-Text begründet werden.

---

## Dokumentation (AsciiDoc/Mermaid)

- Build (lokal):
    - `mvn --batch-mode -Pasciidoc-enabled -DskipTests=true generate-resources`
    - Artefakte: `target/docs/html/Testplan_ZETA.html`, `target/docs/pdf/Testplan_ZETA.pdf`
- Inhaltliche Attribute wie `:toc:`, `:sectids:` etc. werden im `docs/asciidoc/Testplan_ZETA.adoc`
  gepflegt (nicht im POM duplizieren).
- Diagramme:
    - Asciidoctor Diagram + Mermaid CLI via Node/Yarn (installiert in `target/node_modules`).
    - Gemeinsamer Diagramm-Cache: `target/docs/diagram-cache` (verhindert Doppel-Rendering für
      HTML/PDF).
    - Mermaid-Branding: `docs/asciidoc/mermaid-config.json` (einheitliche Farben/Fonts für
      HTML/PDF).
- GitLab CI:
    - Job `docs` erzeugt die HTML/PDF-Dokumente mit Maven (kein separater Asciidoctor-Container
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
    3. We take open source license compliance very seriously. We are always striving to achieve compliance at all times and to improve our processes. If you find any issues or have any suggestions or comments, or if you see any other ways in which we can improve, please reach out to: ospo@gematik.de
3. Please note: Parts of this code may have been generated using AI-supported technology. Please take this into account, especially when troubleshooting, for security analyses and possible adjustments.
