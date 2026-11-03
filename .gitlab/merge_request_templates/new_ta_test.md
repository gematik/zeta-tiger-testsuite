## Zusammenfassung

<!-- Welcher TA wird implementiert oder erweitert und was wird geprüft? -->

## TA-Zuordnung

- TA-ID: `TA_A_xxxxx_xx`
- TA-Titel / Fokus-Schlüsselwörter: `...`
- Quelldokument: `docs/asciidoc/testaspekte/...`
- Verknüpfter Spezifikations-/RFC-Abschnitt: `...`

## Testentwurf

- UserStory-/UseCase-Pfad: `src/test/resources/features/userstories/...`
- Feature-Dateien: `...`
- Voraussetzungen gemäß UseCase eingehalten: `ja/nein`
- Vorhandene TGR-Schritte wiederverwendet: `ja/nein`
- Neue Schrittdefinitionen ergänzt: `ja/nein`
- Falls neue Schritte: warum die Schritte aus Tiger-Handbuch und Cucumber-Methodentabelle nicht ausreichen: `...`

## Implementierte Szenarien

- `...`
- `...`

## Aktualisierte Dokumentation

- UseCase-README mit `include::...feature[]` aktualisiert: `ja/nein`
- Weitere Dokumentation aktualisiert: `...`

## Verifikation

<!-- <existing-tag-expression> durch Tags aus den betroffenen Feature-Dateien ersetzen.
Nur tatsächlich ausgeführte Befehle stehen lassen. -->

Ausgeführt:

- `mvn verify -Pproxy "-Dcucumber.filter.tags=<existing-tag-expression>"`
- Gezielter TA-Testlauf/Filter: `...`
- `uv run --project docs/scripts generate-cucumber-methods` (bei geänderten Schrittdefinitionen)

Ergebnis:

- `bestanden/fehlgeschlagen`
- Anzahl ausgeführter Szenarien/Beispiele (bestanden/fehlgeschlagen/übersprungen): `...` (null ausgeführte Szenarien sind kein bestandener Nachweis)
- Relevante Logs/Artefakte: `...`

## Risiken und offene Punkte

- Risiko für das Verhalten: `gering/mittel/hoch`
- Folgeaufgaben / Prüfungen außerhalb des Umfangs: `...`

## Checkliste

- [ ] TA-Fokus entspricht der Bedeutung der fett hervorgehobenen Schlüsselwörter
- [ ] Deutsches Gherkin, Einrückung mit 2 Leerzeichen, Dateinamen in snake_case
- [ ] Vorhandene TGR-Schritte wurden eigenem Code vorgezogen
- [ ] Keine Zugangsdaten/Secrets hinzugefügt
