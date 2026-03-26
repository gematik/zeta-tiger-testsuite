# Docker-Images

Dieses Repo liefert zwei Docker-Images:

- `docker/frontend/Dockerfile` → Maven-im-Container (baut und führt die Testsuite aus). Tag
  `:frontend`.
- `docker/quality_gate/Dockerfile` → Runtime-only (JRE + gepackte Testsuite, ohne Maven) mit
  eigener `run-tests.sh`. Tag `:quality_gate`.

## CI-Build (GitLab)

CI baut beide Images via Buildx:

- `docker buildx build --build-arg GITLAB_BASE_URL=${GITLAB_BASE_URL} --build-arg TESTPROXY_PROJECT_ID=${TESTPROXY_PROJECT_ID} -f docker/frontend/Dockerfile -t ${CI_REGISTRY_IMAGE}:frontend .`
- `docker buildx build --build-arg GITLAB_BASE_URL=${GITLAB_BASE_URL} --build-arg TESTPROXY_PROJECT_ID=${TESTPROXY_PROJECT_ID} -f docker/quality_gate/Dockerfile -t ${CI_REGISTRY_IMAGE}:quality_gate .`

## Lokal bauen und ausführen

Maven-basiertes Image bauen:

```sh
docker build \
  --build-arg GITLAB_BASE_URL="${GITLAB_BASE_URL:-https://gitlab.com}" \
  --build-arg TESTPROXY_PROJECT_ID="${TESTPROXY_PROJECT_ID:-75362233}" \
  -f docker/frontend/Dockerfile \
  -t testsuite:frontend .
```

Runtime-only Image bauen:

```sh
docker build \
  --build-arg GITLAB_BASE_URL="${GITLAB_BASE_URL:-https://gitlab.com}" \
  --build-arg TESTPROXY_PROJECT_ID="${TESTPROXY_PROJECT_ID:-75362233}" \
  -f docker/quality_gate/Dockerfile \
  -t testsuite:quality_gate .
```

Ausführen (EntryPoint ruft `/app/run-tests.sh` auf):

```sh
# Maven-basiert (führt mvn verify im Container aus)
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -v "$HOME/dev/zeta-test-certificates:/cert-repo:ro" \
  -e CUCUMBER_TAGS="@smoke" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_TEST_CERTIFICATES_DIR="/cert-repo" \
  testsuite:frontend

# Runtime-only (nutzt gepackte Artefakte + run-tests.sh)
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -v "$HOME/dev/zeta-test-certificates:/cert-repo:ro" \
  -e CUCUMBER_TAGS="@smoke" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_TEST_CERTIFICATES_DIR="/cert-repo" \
  testsuite:quality_gate
```

Ausführen mit gemounteten Report-Verzeichnissen:

```sh
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e CUCUMBER_TAGS="@smoke" \
  -v "$PWD/target/site/serenity:/app/target/site/serenity" \
  -v "$PWD/target/failsafe-reports:/app/target/failsafe-reports" \
  -v "$PWD/target/allure-results:/app/target/allure-results" \
  testsuite:frontend
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e CUCUMBER_TAGS="@smoke" \
  -v "$PWD/target/site/serenity:/app/target/site/serenity" \
  -v "$PWD/target/failsafe-reports:/app/target/failsafe-reports" \
  -v "$PWD/target/allure-results:/app/target/allure-results" \
  testsuite:quality_gate
```

Umgebungsvariablen (beide Images, außer angegeben):

| Variable                          | Pflicht | Default                   | Beschreibung                                                                                                                                                               |
|-----------------------------------|---------|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CUCUMBER_TAGS`                   | nein    | `@smoke`                  | Tag-Filter für die Szenario-Auswahl.                                                                                                                                       |
| `ZETA_BASE_URL`                   | nein    | `zeta-kind.local`         | Ziel-Base-URL für Guard/Client (Host ohne Scheme).                                                                                                                         |
| `ZETA_PROXY_URL`                  | nein    | `${zeta_base_url}:9999`   | Proxy-URL (Host:Port, ohne Scheme).                                                                                                                                        |
| `ZETA_K8S_NAMESPACE`              | nein    | `zeta-local`              | Kubernetes-Namespace für Telemetrie-Log-Abfragen (`resource.k8s.namespace.name`) sowie kubectl-/Deployment-bezogene Prüfungen (als `-Dzeta_k8s_namespace` weitergereicht). |
| `ALLOW_DEPLOYMENT_MODIFICATION`   | nein    | (leer)                    | Setzt `-Dallow_deployment_modification=true                                                                                                                                |false` für Szenarien mit `@deployment_modification`. |
| `OPENSEARCH_URL`                  | nein    | `${zeta_base_url}:9200`   | OpenSearch-URL (Telemetry-Logs), ohne Scheme.                                                                                                                              |
| `PROMETHEUS_URL`                  | nein    | `${zeta_base_url}:9090`   | Prometheus-URL (Telemetry-Metriken), ohne Scheme.                                                                                                                          |
| `ZETA_TEST_CERTIFICATES_DIR`      | nein    | aus `tiger/defaults.yaml` | Verzeichnis des `zeta-test-certificates` Checkouts; wird als `-DtestCertificates.dir=...` weitergereicht.                                                                  |
| `PROFILE`                         | nein    | (leer)                    | Optionales Tiger-Profil (z. B. `proxy`).                                                                                                                                   |
| `SERENITY_EXPORT_DIR`             | nein    | (leer)                    | Optionaler Ausgabeordner für Serenity-Reports.                                                                                                                             |
| `CUCUMBER_EXPORT_DIR`             | nein    | (leer)                    | Optionaler Ausgabeordner für Cucumber-JSON.                                                                                                                                |
| `ALLURE_RESULTS_DIR`              | nein    | (leer)                    | Optionaler Ausgabeordner für Allure-Ergebnisse (Quality-Gate-Image; Maven nutzt `/app/target/allure-results` oder `MAVEN_OPTS=-Dallure.results.directory=...`).            |

Wichtig:

- `ZETA_BASE_URL`/`ZETA_PROXY_URL`/`OPENSEARCH_URL`/`PROMETHEUS_URL` werden nur dann als `-D...`
  durchgereicht, wenn sie nicht leer sind.
- `ZETA_TEST_CERTIFICATES_DIR` wird, falls gesetzt, als `-DtestCertificates.dir=...`
  weitergereicht.
- `ZETA_K8S_NAMESPACE` wird, falls gesetzt, als `-Dzeta_k8s_namespace=...` weitergereicht.
- `ALLOW_DEPLOYMENT_MODIFICATION` wird, falls gesetzt, als
  `-Dallow_deployment_modification=...` weitergereicht.
- Leere oder nicht gesetzte Werte fallen auf die Defaults aus `tiger/defaults.yaml` zurück
  (typisch `zeta-local`), was in Staging-Umgebungen zu falschen Telemetrie-Ergebnissen führen kann.
- `OPENSEARCH_URL` steuert Telemetrie-Log-Abfragen, `PROMETHEUS_URL` steuert Telemetrie-Metrik-Abfragen.
- Für Cluster-Zugriff im Container `-v "$HOME/.kube:/home/zeta/.kube:ro"` setzen (Windows analog:
  `-v %USERPROFILE%\\.kube:/home/zeta/.kube:ro`).
- Für schreibbare Bind-Mounts die Images mit passender Host-UID/GID bauen, z. B.
  `docker build --build-arg ZETA_UID=$(id -u) --build-arg ZETA_GID=$(id -g) ...`.
Beispiele:

Frontend-Image mit Proxy und @staging:

```bash
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e PROFILE=proxy \
  -e CUCUMBER_TAGS="@staging" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_PROXY_URL="zeta-kind.local:9999" \
  testsuite:frontend
```

Quality-Gate-Image mit Proxy und @staging:

```bash
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e PROFILE=proxy \
  -e CUCUMBER_TAGS="@staging" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_PROXY_URL="zeta-kind.local:9999" \
  testsuite:quality_gate
```

`<host-ip>` ist die IP des Hosts, unter der `zeta-kind.local` aus dem Container erreichbar ist.

Die Images laufen zur Laufzeit als User `zeta`.
Für schreibbare Host-Mounts sollte der Container mit derselben UID/GID wie der Host-Benutzer gebaut werden, damit Report-Verzeichnisse ohne Root-Rechte beschrieben werden können.

## GitLab CI Nutzung für das Quality-Gate

Das Quality-Gate-Image kann in einem GitLab-CI-Job so genutzt werden:

```yaml
quality-gate:
  stage: test
  image: "${CI_REGISTRY_IMAGE}:quality_gate"
  script:
    - /app/run-tests.sh
  variables:
    CUCUMBER_TAGS: "@smoke"
    # PROFILE: "proxy"  # nur bei Bedarf für Proxy-Erfassung setzen
    # OPENSEARCH_URL: "zeta-kind.local:9200"  # optional für Telemetrie-Log-Abfragen
    # PROMETHEUS_URL: "zeta-kind.local:9090"  # optional für Telemetrie-Metrik-Abfragen
  artifacts:
    when: always
    paths:
      - target/site/serenity
      - target/cucumber-parallel
    reports:
      junit: target/cucumber-parallel/cucumber.xml
```

Hinweise:

- `CUCUMBER_TAGS`/`ZETA_BASE_URL`/`ZETA_PROXY_URL`/`OPENSEARCH_URL`/`PROMETHEUS_URL` nach Bedarf setzen.
- Wenn Zertifikatsschritte verwendet werden, muss das Repo `zeta-test-certificates` im Container erreichbar sein.
- Lokal geht das am einfachsten per Read-only-Mount plus `ZETA_TEST_CERTIFICATES_DIR=/cert-repo`.
- Zusätzliche Ausgabeordner per `SERENITY_EXPORT_DIR` / `CUCUMBER_EXPORT_DIR` mounten.
- `ALLURE_RESULTS_DIR` wird von beiden Images ausgewertet (das Maven-Image symlinkt
  `/app/target/allure-results` auf den Export-Ordner).
- Die Docker-Images enthalten nur das Alpine-Binary des TLS-Tools (
  `tools/tls-test-tool-1.0.1/TlsTestTool-alpine`).

Beispiel für einen GitLab-Stage mit zusätzlichem Zertifikats-Checkout:

```yaml
quality-gate-with-certs:
  stage: test
  image: "${CI_REGISTRY_IMAGE}:quality_gate"
  variables:
    CUCUMBER_TAGS: "@smoke"
    CERT_REPO_DIR: "${CI_PROJECT_DIR}/.cache/zeta-test-certificates"
    ZETA_TEST_CERTIFICATES_DIR: "${CI_PROJECT_DIR}/.cache/zeta-test-certificates"
  before_script:
    - apk add --no-cache git
    - git clone --depth 1 git@<git-host>:<group>/zeta-test-certificates.git "${CERT_REPO_DIR}"
  script:
    - /app/run-tests.sh
  artifacts:
    when: always
    paths:
      - target/site/serenity
      - target/cucumber-parallel
```

Wichtig:

- Bei `image:`-Jobs gibt es kein `docker run -v ...`; der zusätzliche Inhalt muss im Job selbst nach `${CI_PROJECT_DIR}` geholt oder aus einem Artefakt bereitgestellt werden.
- `/app/run-tests.sh` startet die Testsuite aus dem Image, kann aber problemlos auf einen absoluten Pfad unter `${CI_PROJECT_DIR}` zugreifen, wenn `ZETA_TEST_CERTIFICATES_DIR` darauf zeigt.

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
