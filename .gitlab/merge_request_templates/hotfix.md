## Zusammenfassung

<!-- Behobenes Problem in Produktion/Staging und unmittelbare Auswirkungen -->

## Vorfall / Anlass

- Referenz (Ticket/Alarm): `...`
- Betroffener Bereich: `...`
- Fehlerursache: `...`

## Risikobewertung

- Warum ein kurzfristiger Merge vertretbar ist: `...`
- Bekannte Einschränkungen: `...`

## Verifikation

<!-- <existing-tag-expression> durch Tags aus den betroffenen Feature-Dateien ersetzen.
Nur tatsächlich ausgeführte Befehle stehen lassen. -->

Ausgeführt:

- `mvn verify -Pproxy "-Dcucumber.filter.tags=<existing-tag-expression>"`
- Zusätzliche gezielte Prüfungen: `...`

Ergebnis:

- `bestanden/fehlgeschlagen`
- Anzahl ausgeführter Szenarien/Beispiele (bestanden/fehlgeschlagen/übersprungen): `...` (null ausgeführte Szenarien sind kein bestandener Nachweis)
- Relevante Logs/Artefakte: `...`

## Rücksetzplan

- Zurückzunehmende Commits: `...`
- Konfigurationsschalter für die Rückfalllösung (falls vorhanden): `...`

## Checkliste

- [ ] Korrekturumfang ist minimal und abgegrenzt
- [ ] Kein sachfremdes Refactoring enthalten
- [ ] Keine Zugangsdaten/Secrets hinzugefügt
- [ ] Rücksetzplan ist klar
