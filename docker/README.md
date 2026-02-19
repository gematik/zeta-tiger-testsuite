# Docker-Images

Dieses Repo liefert zwei Docker-Images:

- `docker/frontend/Dockerfile` → Maven-im-Container (baut und führt die Testsuite aus). Tag
  `:latest`.
- `docker/quality_gate/Dockerfile` → Runtime-only (JRE + gepackte Testsuite, ohne Maven) mit
  eigener `run-tests.sh`. Tag `:qualitygate`.

## CI-Build (GitLab)

CI baut beide Images via Buildx:

- `docker buildx build -f docker/frontend/Dockerfile -t ${CI_REGISTRY_IMAGE}:latest .`
- `docker buildx build -f docker/quality_gate/Dockerfile -t ${CI_REGISTRY_IMAGE}:qualitygate .`

## Lokal bauen und ausführen

Maven-basiertes Image bauen:

```sh
docker build -f docker/frontend/Dockerfile -t testsuite:latest .
```

Runtime-only Image bauen:

```sh
docker build -f docker/quality_gate/Dockerfile -t testsuite:qualitygate .
```

Ausführen (EntryPoint ruft `/app/run-tests.sh` auf):

```sh
# Maven-basiert (führt mvn verify im Container aus)
docker run --rm -e CUCUMBER_TAGS="@smoke" -e ZETA_BASE_URL="zeta-kind.local" testsuite:latest

# Runtime-only (nutzt gepackte Artefakte + run-tests.sh)
docker run --rm -e CUCUMBER_TAGS="@smoke" -e ZETA_BASE_URL="zeta-kind.local" testsuite:qualitygate
```

Ausführen mit gemounteten Report-Verzeichnissen:

```sh
docker run --rm \
  -e CUCUMBER_TAGS="@smoke" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -v "$PWD/target/site/serenity:/app/target/site/serenity" \
  -v "$PWD/target/failsafe-reports:/app/target/failsafe-reports" \
  -v "$PWD/target/allure-results:/app/target/allure-results" \
  testsuite:latest
docker run --rm \
  -e CUCUMBER_TAGS="@smoke" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -v "$PWD/target/site/serenity:/app/target/site/serenity" \
  -v "$PWD/target/failsafe-reports:/app/target/failsafe-reports" \
  -v "$PWD/target/allure-results:/app/target/allure-results" \
  testsuite:qualitygate
```

Umgebungsvariablen (beide Images, außer angegeben):

| Variable              | Pflicht                | Default    | Beschreibung                                                                                                                                                    |
|-----------------------|------------------------|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CUCUMBER_TAGS`       | nein                   | `@smoke`   | Tag-Filter für die Szenario-Auswahl.                                                                                                                            |
| `ZETA_PROXY_URL`      | nein                   | (leer)     | Proxy-URL.                                                                                                                                                      |
| `ZETA_BASE_URL`       | ja (für externe Ziele) | (leer)     | Ziel-Base-URL für Cloud-/Stage-Tests (leer -> `https://:443`).                                                                                                  |
| `OPENSEARCH_URL`      | nein                   | (leer)     | OpenSearch-URL (Telemetry-Logs), ohne Scheme, z. B. `opensearch:9200`.                                                                                           |
| `PROFILE`             | nein                   | (leer)     | Optionales Tiger-Profil (z. B. `proxy`).                                                                                                                        |
| `SERENITY_EXPORT_DIR` | nein                   | (leer)     | Optionaler Ausgabeordner für Serenity-Reports.                                                                                                                  |
| `CUCUMBER_EXPORT_DIR` | nein                   | (leer)     | Optionaler Ausgabeordner für Cucumber-JSON.                                                                                                                     |
| `ALLURE_RESULTS_DIR`  | nein                   | (leer)     | Optionaler Ausgabeordner für Allure-Ergebnisse (Quality-Gate-Image; Maven nutzt `/app/target/allure-results` oder `MAVEN_OPTS=-Dallure.results.directory=...`). |

Wichtig:

- `ZETA_BASE_URL` wird als `-Dzeta_base_url` durchgereicht. Ist der Wert leer, greifen die Tests
  auf symbolische Hosts wie `zetaClient` zu und schlagen erwartungsgemäß fehl.
- `OPENSEARCH_URL` wird als `-Dopensearch_url` durchgereicht und steuert die Telemetrie-Log-Abfragen.

Beispiele:

Frontend-Image mit Proxy und @staging:

```bash
docker run --rm \
  -e PROFILE=proxy \
  -e CUCUMBER_TAGS="@staging" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_PROXY_URL="zeta-kind.local:9999" \
  testsuite-frontend
```

Quality-Gate-Image mit Proxy und @staging:

```bash
docker run --rm \
  -e PROFILE=proxy \
  -e CUCUMBER_TAGS="@staging" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_PROXY_URL="zeta-kind.local:9999" \
  testsuite-qualitygate
```

## GitLab CI Nutzung für das Quality-Gate

Das Quality-Gate-Image kann in einem GitLab-CI-Job so genutzt werden:

```yaml
quality-gate:
  stage: test
  image: "${CI_REGISTRY_IMAGE}:qualitygate"
  script:
    - /app/run-tests.sh
  variables:
    CUCUMBER_TAGS: "@smoke"
    # PROFILE: "proxy"  # nur bei Bedarf für Proxy-Erfassung setzen
    # OPENSEARCH_URL: "localhost:9200"  # optional für Telemetrie-Log-Abfragen
  artifacts:
    when: always
    paths:
      - target/site/serenity
      - target/cucumber-parallel
    reports:
      junit: target/cucumber-parallel/cucumber.xml
```

Hinweise:

- `CUCUMBER_TAGS`/`ZETA_BASE_URL`/`ZETA_PROXY_URL`/`OPENSEARCH_URL` nach Bedarf setzen.
- Zusätzliche Ausgabeordner per `SERENITY_EXPORT_DIR` / `CUCUMBER_EXPORT_DIR` mounten.
- `ALLURE_RESULTS_DIR` wird von beiden Images ausgewertet (das Maven-Image symlinkt
  `/app/target/allure-results` auf den Export-Ordner).
- Die Docker-Images enthalten nur das Alpine-Binary des TLS-Tools (
  `tools/tls-test-tool-1.0.1/TlsTestTool-alpine`).

## TLS-Handshake Troubleshooting

Konkrete Optionen:

1. Truststores im Thin-Image angleichen.
   CA-Bundle installieren und interne CA in den Java-Truststore importieren.
   Beispiel (in `docker/quality_gate/Dockerfile` Runtime-Stage):

   ```dockerfile
   RUN apk add --no-cache ca-certificates && update-ca-certificates
   # Wenn eine interne CA vorhanden ist:
   # COPY ci/certs/internal-ca.crt /usr/local/share/ca-certificates/internal-ca.crt
   # RUN update-ca-certificates \
   # && keytool -importcert -noprompt -alias internal-ca \
   # -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit \
   # -file /usr/local/share/ca-certificates/internal-ca.crt
   ```
2. Proxy-CA verwenden, die bereits vertraut wird.
   `tigerProxy.tls.serverRootCa` in `tiger/*.yaml` konfigurieren, damit der Proxy Zertifikate mit
   einer bekannten CA ausstellt.
3. Sicherstellen, dass der Proxy sich nicht selbst proxyt.
   Wenn `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` zwischen Jobs abweichen, kann die Verbindung durch
   den lokalen Proxy schleifen und TLS-Fehler auslösen. Einheitliche Umgebungen (insb. `NO_PROXY`)
   vermeiden das.
