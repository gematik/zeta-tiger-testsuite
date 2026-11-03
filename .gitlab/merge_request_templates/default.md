## Zusammenfassung

<!-- Welches Problem wird gelöst und warum jetzt? -->

## Umfang

- UserStory/UseCase: `...`
- TA / Anforderung / Spezifikationsverweis: `...` (z. B. `TA_A_xxxxx_xx`, RFC, gemSpec-Abschnitt)

## Gherkin / Testentwurf (falls zutreffend)

- Feature-Dateien: `src/test/resources/features/...`
- Sprach-/Formatprüfung: deutsches Gherkin, Einrückung mit 2 Leerzeichen, Dateinamen in snake_case
- Vorhandene TGR-Schritte wiederverwendet: `ja/nein`
- Neue Schrittdefinitionen ergänzt: `ja/nein`
- Falls ja, warum vorhandene Schritte nicht ausreichten: `...`

## Auswirkungen auf die Konfiguration

- Änderungen an `tiger.yaml` / Overlays (`tiger-proxy.yaml` usw.): `keine/...`
- Änderungen an Testdaten (`tiger/*.yaml`): `keine/...`
- Secrets betroffen: `nein` (bestätigen, dass keine Zugangsdaten committet wurden)

## Verifikation

<!-- <existing-tag-expression> durch Tags aus den betroffenen Feature-Dateien ersetzen.
Nur tatsächlich ausgeführte Befehle stehen lassen. -->

Ausgeführt:

- `mvn verify -Pproxy "-Dcucumber.filter.tags=<existing-tag-expression>"`
- `mvn test -Dskip.unit.tests=false` (falls ausgeführt)
- `mvn -Pgenerate-documentation generate-resources` (bei geänderter Dokumentation oder geänderten Schritten)
- `uv run --project docs/scripts generate-cucumber-methods` (bei geänderten Schrittdefinitionen)

Ergebnis:

- `bestanden/fehlgeschlagen`
- Anzahl ausgeführter Szenarien/Beispiele (bestanden/fehlgeschlagen/übersprungen): `...` (null ausgeführte Szenarien sind kein bestandener Nachweis)
- Relevante Logs/Screenshots/Artefakte: `...`

## Bekannte Produkt- oder Konformitätsfehler

<!-- Alle bekannten Produkt- oder Konformitätsfehler auflisten, die Szenarien fehlschlagen lassen, einschließlich der zugehörigen Tickets.
Pro Fehler eine Zeile ergänzen und alle betroffenen Szenarien nennen.
-->

| Betroffene Szenarien / Feature-Dateien | TA / Anforderung | Beobachteter Produkt- oder Konformitätsfehler | Ticket-Link |
| --- | --- | --- | --- |
| Nicht zutreffend | Nicht zutreffend | Nicht zutreffend | Nicht zutreffend |

## Aktualisierte Dokumentation

- UseCase-README mit `include::...feature[]` aktualisiert: `ja/nein/nicht zutreffend`
- Weitere Dokumentation aktualisiert (`docs/...`): `...`

## Risiken und offene Punkte

- Funktionales Risiko: `gering/mittel/hoch` mit Begründung
- Auswirkungen auf die Kompatibilität: `...`
- Folgeaufgaben (falls vorhanden): `...`

## Checkliste

- [ ] Änderungen sind auf den beabsichtigten Umfang begrenzt
- [ ] Vorhandene TGR-Schritte wurden eigenem Code vorgezogen
- [ ] Feature-Pfade und Benennung entsprechen den Projektkonventionen
- [ ] Keine Zugangsdaten/Secrets hinzugefügt
- [ ] Tests und Dokumentation bei Bedarf aktualisiert
- [ ] Alle oben genannten betroffenen Szenarien sind in `runtime_failure_annotations.internal.csv` ihren zugehörigen Tickets zugeordnet (falls zutreffend)
