## Zusammenfassung

<!-- Welche Dokumentation wurde aktualisiert und warum? -->

## Art des Inhalts

- [ ] Benutzerdokumentation
- [ ] Zuordnung von TestAspekten zur Spezifikation
- [ ] Includes in UseCase-READMEs
- [ ] Cucumber-Methodentabelle
- [ ] Sonstiges: `...`

## Generierung / Verifikation

Ausgeführt:

- `mvn -Pgenerate-documentation generate-resources` (falls zutreffend)
- `uv run --project docs/scripts generate-cucumber-methods` (bei geänderter Schrittdokumentation)

Ergebnis:

- `bestanden/fehlgeschlagen`
- Anmerkungen/Screenshots/Artefakte: `...`

## Funktionale Auswirkungen

- Laufzeitverhalten geändert: `nein/ja`
- Falls ja, erläutern: `...`

## Checkliste

- [ ] Dokumentation stimmt mit der aktuellen Implementierung überein
- [ ] Links/Includes werden korrekt dargestellt
- [ ] Keine Zugangsdaten/Secrets hinzugefügt
