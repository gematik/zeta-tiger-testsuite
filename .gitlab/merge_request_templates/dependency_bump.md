## Zusammenfassung

<!-- Warum wurde die Abhängigkeit aktualisiert oder hinzugefügt? -->

## Geänderte Abhängigkeiten

- `group/artifact` `alt` -> `neu`
- `group/artifact` `alt` -> `neu`

## Umfang

- Nur Build/Plugin betroffen: `ja/nein`
- Abhängigkeiten mit Laufzeitauswirkungen: `ja/nein`
- Transitive Auswirkungen auf die Tiger-/ZETA-Ausführung: `keine/möglich` (Details: `...`)

## Erforderliche Codeänderungen

- API-Migration wegen der Versionsänderung nötig: `ja/nein`
- Geänderte Dateien: `...`

## Verifikation

<!-- <existing-tag-expression> durch Tags aus den betroffenen Feature-Dateien ersetzen.
Nur tatsächlich ausgeführte Befehle stehen lassen. -->

Ausgeführt:

- `mvn verify -Pproxy "-Dcucumber.filter.tags=<existing-tag-expression>"`
- `mvn test -Dskip.unit.tests=false` (falls ausgeführt)

Ergebnis:

- `bestanden/fehlgeschlagen`
- Anzahl ausgeführter Szenarien/Beispiele (bestanden/fehlgeschlagen/übersprungen): `...` (null ausgeführte Szenarien sind kein bestandener Nachweis)
- Relevante Logs/Artefakte: `...`

## Risiken

- Bekanntes Risiko inkompatibler Änderungen: `gering/mittel/hoch`
- Nach dem Merge zu beobachtende Bereiche: `...`

## Checkliste

- [ ] Versionen wurden bewusst gewählt (keine unbeabsichtigten Versionsänderungen)
- [ ] Erforderliche API-Migrationen sind enthalten
- [ ] Keine Zugangsdaten/Secrets hinzugefügt
