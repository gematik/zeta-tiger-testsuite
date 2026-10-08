# Docker-Images

Dieses Repo liefert zwei Docker-Images:

- `docker/frontend/Dockerfile` → Maven-im-Container (baut und führt die Testsuite aus). Tag
  `:frontend`.
- `docker/quality_gate/Dockerfile` → Runtime-only (JRE + gepackte Testsuite, ohne Maven) mit
  eigener `run-tests.sh`. Tag `:quality_gate`.

## CI-Build (GitLab)

CI baut beide Images via Buildx:

-
`docker buildx build --build-arg GITLAB_BASE_URL=${GITLAB_BASE_URL} --build-arg TESTPROXY_PROJECT_ID=${TESTPROXY_PROJECT_ID} -f docker/frontend/Dockerfile -t ${CI_REGISTRY_IMAGE}:frontend .`
-
`docker buildx build --build-arg GITLAB_BASE_URL=${GITLAB_BASE_URL} --build-arg TESTPROXY_PROJECT_ID=${TESTPROXY_PROJECT_ID} -f docker/quality_gate/Dockerfile -t ${CI_REGISTRY_IMAGE}:quality_gate .`

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
  -e CUCUMBER_TAGS="@blocker" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  testsuite:frontend

# Runtime-only (nutzt gepackte Artefakte + run-tests.sh)
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e CUCUMBER_TAGS="@blocker" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  testsuite:quality_gate
```

Ausführen mit gemounteten Report-Verzeichnissen:

```sh
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e CUCUMBER_TAGS="@blocker" \
  -v "$PWD/docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv:/app/docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv:ro" \
  -v "$PWD/target/site/serenity:/app/target/site/serenity" \
  -v "$PWD/target/failsafe-reports:/app/target/failsafe-reports" \
  -v "$PWD/target/allure-results:/app/target/allure-results" \
  testsuite:frontend
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e CUCUMBER_TAGS="@blocker" \
  -v "$PWD/docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv:/app/docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv:ro" \
  -v "$PWD/target/site/serenity:/app/target/site/serenity" \
  -v "$PWD/target/failsafe-reports:/app/target/failsafe-reports" \
  -v "$PWD/target/allure-results:/app/target/allure-results" \
  testsuite:quality_gate
```

Umgebungsvariablen (beide Images, außer angegeben):

| Variable                               | Pflicht | Default                                                                              | Beschreibung                                                                                                                                                               |
|----------------------------------------|---------|--------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CUCUMBER_TAGS`                        | nein    | (leer)                                                                               | Tag-Filter für die Szenario-Auswahl. Ein leerer Wert startet im Docker-/CI-Pfad keinen festen Tag-Filter.                                                                  |
| `ZETA_BASE_URL`                        | nein    | `zeta-kind.local`                                                                    | Ziel-Base-URL für Guard/Client (Host ohne Scheme).                                                                                                                         |
| `ZETA_PROXY_URL`                       | nein    | `${zeta_base_url}:9999`                                                              | Proxy-URL (Host:Port, ohne Scheme).                                                                                                                                        |
| `ZETA_K8S_NAMESPACE`                   | nein    | `zeta-local`                                                                         | Kubernetes-Namespace für Telemetrie-Log-Abfragen (`resource.k8s.namespace.name`) sowie kubectl-/Deployment-bezogene Prüfungen (als `-Dzeta_k8s_namespace` weitergereicht). |
| `ALLOW_DEPLOYMENT_MODIFICATION`        | nein    | (leer)                                                                               | Setzt `-Dallow_deployment_modification=true\|false` für Szenarien mit `@deployment_modification`.                                                                          |
| `ALLOW_PERFORMANCE_TESTS`              | nein    | (leer)                                                                               | Setzt `-Dallow_performance_tests=true\|false` für Szenarien mit `@performance`. Ohne gesetzten Wert werden diese Szenarien als `skipped` markiert.                         |
| `ALLOW_LONGRUNNING_TESTS`              | nein    | (leer)                                                                               | Setzt `-Dallow_longrunning_tests=true\|false` für Szenarien mit `@longrunning`. Ohne gesetzten Wert werden diese Szenarien als `skipped` markiert.                         |
| `TPM_ENVIRONMENT`                      | nein    | (leer)                                                                               | Setzt `-Dtpm_environment=true\|false` für Szenarien mit `@tpm_environment`. Ohne gesetzten Wert werden diese Szenarien als `skipped` markiert.                             |
| `OPENSEARCH_URL`                       | nein    | `${zeta_base_url}:9200`                                                              | OpenSearch-URL (Telemetry-Logs), ohne Scheme.                                                                                                                              |
| `PROMETHEUS_URL`                       | nein    | `${zeta_base_url}:9090`                                                              | Prometheus-Host (Telemtry Metriken), ohne Scheme.                                                                                                                          |
| `ZETA_TLS_TEST_TOOL_SERVICE_URL`       | nein    | `${zeta_base_url}:9012`                                                              | Service-URL des TLS-Test-Tools, ohne Scheme; z. B. `zeta-tls-test-tool-service.zeta-staging.svc:9012`.                                                                     |
| `ZETA_TEST_CERTIFICATES_DIR`           | nein    | `/app/zeta-test-certificates` im Quality-Gate-Image, sonst aus `tiger/defaults.yaml` | Verzeichnis des `zeta-test-certificates` Checkouts; wird als `-DtestCertificates.dir=...` weitergereicht.                                                                  |
| `SMB_KEYSTORE_FILE_B64`                | nein    | aus `tiger/defaults.yaml`                                                            | Pfad zur Base64-codierten SMC-B PKCS#12-Datei; wird als `-Dzeta_k8s_smb_keystore_file=...` weitergereicht.                                                                 |
| `SMB_KEYSTORE_PW_FILE`                 | nein    | aus `tiger/defaults.yaml`                                                            | Pfad zur Passwortdatei der SMC-B PKCS#12-Datei; wird als `-Dzeta_k8s_smb_keystore_password_file=...` weitergereicht.                                                       |
| `PROFILE`                              | nein    | (leer)                                                                               | Optionales Tiger-Profil (z. B. `proxy`).                                                                                                                                   |
| `SERENITY_EXPORT_DIR`                  | nein    | (leer)                                                                               | Optionaler Ausgabeordner für Serenity-Reports.                                                                                                                             |
| `CUCUMBER_EXPORT_DIR`                  | nein    | (leer)                                                                               | Optionaler Ausgabeordner für Cucumber-JSON.                                                                                                                                |
| `ALLURE_RESULTS_DIR`                   | nein    | (leer)                                                                               | Optionaler Ausgabeordner für Allure-Ergebnisse (Quality-Gate-Image; Maven nutzt `/app/target/allure-results` oder `MAVEN_OPTS=-Dallure.results.directory=...`).            |
| `ZETA_RUNTIME_FAILURE_ANNOTATIONS_CSV` | nein    | `docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv`               | Optionaler Pfad zu einer internen CSV mit Tickets und Erklärungen für rote Laufzeit-Traceability-Zeilen.                                                                   |

Wichtig:

- `ZETA_BASE_URL`/`ZETA_PROXY_URL`/`OPENSEARCH_URL`/`ZETA_TLS_TEST_TOOL_SERVICE_URL` werden nur dann als `-D...`
  durchgereicht, wenn sie nicht leer sind.
- `ZETA_TEST_CERTIFICATES_DIR` wird, falls gesetzt, als `-DtestCertificates.dir=...`
  weitergereicht.
- `ZETA_K8S_NAMESPACE` wird, falls gesetzt, als `-Dzeta_k8s_namespace=...` weitergereicht.
- `SMB_KEYSTORE_FILE_B64` und `SMB_KEYSTORE_PW_FILE` werden, falls gesetzt,
  als `-Dzeta_k8s_smb_keystore_file=...` und `-Dzeta_k8s_smb_keystore_password_file=...` weitergereicht.
- Sind die beiden Variablen nicht gesetzt, gelten die Defaults aus `tiger/defaults.yaml`.
  Diese Defaults erwarten lokal `../zeta-test-certificates/smcb/smcb-certificates.p12.b64` und `../zeta-test-certificates/smcb/smcb-pw`.
- `ZETA_RUNTIME_FAILURE_ANNOTATIONS_CSV` wird direkt vom Runtime-Coverage-Plugin gelesen.
  Wird die Variable nicht gesetzt, nutzt das Plugin den relativen Standardpfad unter `/app`.
  Für Container-Runs kann die Datei entweder an diesen Standardpfad gemountet oder die Variable auf einen gemounteten Alternativpfad gesetzt
  werden.
- `ALLOW_DEPLOYMENT_MODIFICATION` wird, falls gesetzt, als
  `-Dallow_deployment_modification=...` weitergereicht.
- `ALLOW_PERFORMANCE_TESTS` wird, falls gesetzt, als
  `-Dallow_performance_tests=...` weitergereicht.
- `ALLOW_LONGRUNNING_TESTS` wird, falls gesetzt, als
  `-Dallow_longrunning_tests=...` weitergereicht.
- `TPM_ENVIRONMENT` wird, falls gesetzt, als
  `-Dtpm_environment=...` weitergereicht.
- `CUCUMBER_TAGS` wird bei leerem Wert bewusst leer weitergereicht.
  Dadurch wird im Docker-/CI-Pfad nicht implizit auf `@blocker` oder `@critical` eingeschränkt.
- Szenarien mit `@performance`, `@longrunning` oder `@tpm_environment` werden ohne die jeweiligen Opt-in-Flags weiterhin entdeckt,
  aber im Report als `skipped` markiert.
- Leere oder nicht gesetzte Werte fallen auf die Defaults aus `tiger/defaults.yaml` zurück
  (typisch `zeta-local`), was in Staging-Umgebungen zu falschen Telemetrie-Ergebnissen führen kann.
- `OPENSEARCH_URL` steuert Telemetrie-Log-Abfragen.
- Telemetrie-Metrik-Abfragen laufen über `https://${ZETA_BASE_URL}/prometheus`.
- `ZETA_TLS_TEST_TOOL_SERVICE_URL` überschreibt `zeta_tls_test_tool_service_url`, z. B. mit
  `zeta-tls-test-tool-service.zeta-staging.svc:9012`.
- Für Cluster-Zugriff im Container `-v "$HOME/.kube:/home/zeta/.kube:ro"` setzen (Windows analog:
  `-v %USERPROFILE%\\.kube:/home/zeta/.kube:ro`).
- Für schreibbare Bind-Mounts die Images mit passender Host-UID/GID bauen, z. B.
  `docker build --build-arg ZETA_UID=$(id -u) --build-arg ZETA_GID=$(id -g) ...`.
  Beispiele:

Frontend-Image mit Proxy und @critical:

```bash
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e PROFILE=proxy \
  -e CUCUMBER_TAGS="@critical" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_PROXY_URL="zeta-kind.local:9999" \
  testsuite:frontend
```

Quality-Gate-Image mit Proxy und @critical:

```bash
docker run --rm \
  --network=host \
  --add-host zeta-kind.local:<host-ip> \
  -v "$HOME/.kube:/home/zeta/.kube:ro" \
  -e PROFILE=proxy \
  -e CUCUMBER_TAGS="@critical" \
  -e ZETA_BASE_URL="zeta-kind.local" \
  -e ZETA_PROXY_URL="zeta-kind.local:9999" \
  testsuite:quality_gate
```

`<host-ip>` ist die IP des Hosts, unter der `zeta-kind.local` aus dem Container erreichbar ist.

Die Images laufen zur Laufzeit als User `zeta`.
Für schreibbare Host-Mounts sollte der Container mit derselben UID/GID wie der Host-Benutzer gebaut werden, damit Report-Verzeichnisse ohne
Root-Rechte beschrieben werden können.

## GitLab CI Nutzung für das Quality-Gate

Das Quality-Gate-Image kann in einem GitLab-CI-Job so genutzt werden:

```yaml
quality-gate:
  stage: test
  image: "${CI_REGISTRY_IMAGE}:quality_gate"
  script:
    - /app/run-tests.sh
  variables:
    CUCUMBER_TAGS: ""
    # PROFILE: "proxy"  # nur bei Bedarf für Proxy-Erfassung setzen
    # ZETA_RUNTIME_FAILURE_ANNOTATIONS_CSV: "docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv"  # optional für interne rote Testrun-Erklärungen
    # OPENSEARCH_URL: "zeta-kind.local:9200"  # optional für Telemetrie-Log-Abfragen
    # PROMETHEUS_URL: "zeta-kind.local:9090"  # optional für Telemetry-Metrik Abfragen
    # ZETA_TLS_TEST_TOOL_SERVICE_URL: "zeta-tls-test-tool-service.zeta-staging.svc:9012"  # optional für das TLS-Test-Tool
  artifacts:
    when: always
    paths:
      - target/site/serenity
      - target/cucumber-parallel
    reports:
      junit: target/cucumber-parallel/cucumber.xml
```

Hinweise:

- `CUCUMBER_TAGS`/`ZETA_BASE_URL`/`ZETA_PROXY_URL`/`OPENSEARCH_URL`/`PROMETHEUS_URL`/`ZETA_TLS_TEST_TOOL_SERVICE_URL` nach Bedarf setzen.
- Ein leerer `CUCUMBER_TAGS`-Wert bedeutet im Docker-/CI-Pfad: keine feste Tag-Vorauswahl,
  also breiter Lauf mit den zusätzlichen Skip-Guards aus `Hooks`.
- `ALLOW_PERFORMANCE_TESTS=true` und `ALLOW_LONGRUNNING_TESTS=true` nur dann setzen,
  wenn diese Szenarien in CI wirklich ausgeführt werden sollen.
- Das Quality-Gate-Image erwartet beim Build ein Checkout unter `.cache/zeta-test-certificates`.
- Lokal lässt sich das einfach aus `~/IdeaProjects/zeta-test-certificates` nach `.cache/zeta-test-certificates` klonen.
- Nach dem Build sind im Quality-Gate-Image standardmäßig die Manifestdateien und `keystores/` unter
  `/app/zeta-test-certificates` enthalten.
- Die Deployment-SMC-B-Testidentität wird nicht in das Image eingebaut.
  Stellen Sie die Pfade zur Base64-codierten PKCS#12-Datei und zur Passwortdatei über `SMB_KEYSTORE_FILE_B64`/`SMB_KEYSTORE_PW_FILE` bereit.
  In GitLab CI sollten diese Variablen beim Testsuite-Aufruf aus den gleichnamigen GitLab-Variablen gesetzt werden.
- Zusätzliche Ausgabeordner per `SERENITY_EXPORT_DIR` / `CUCUMBER_EXPORT_DIR` mounten.
- Zu Beginn jedes Testlaufs fragt die Testsuite die Container-Versionen per `kubectl` aus dem konfigurierten Namespace ab.
  Wenn `kubectl`, Clusterzugriff oder Berechtigungen fehlen, wird der Testlauf ohne Versionsinventar fortgesetzt.
  Die Serenity-Systeminformationen werden als
  `target/site/serenity/buildInfo.json` persistiert.
  Die Serenity-Umgebungsdetails gruppieren die Container nach Namespace und Pod.
  Die zweite Spalte zeigt Image-Tag und Digest getrennt durch ` | `.
  Jeder Eintrag wird außerdem als `deployment.version.*` in den
  Allure-Umgebungsinformationen angezeigt.
  Die Serenity-Umgebungsdetails sind über das Informationssymbol im Kopf des Hauptreports
  (`index.html`) erreichbar und werden auf `build-info.html` dargestellt.
- Das Quality-Gate erzeugt `index.html`, `serenity-summary.html` und `serenity-summary.json` selbst und behandelt fehlende Reports als Fehler.
- Interne Fehlerannotationen können per `ZETA_RUNTIME_FAILURE_ANNOTATIONS_CSV` auf eine gemountete CSV gesetzt werden.
  Das CSV-Format ist in `docs/scripts/README.md` beschrieben.
  Die Spalte `Verantwortlich` kann für die verantwortliche Partei gepflegt werden.
- `ALLURE_RESULTS_DIR` wird von beiden Images ausgewertet (das Maven-Image symlinkt
  `/app/target/allure-results` auf den Export-Ordner).
- Die Docker-Images enthalten die TLS-Test-Tool-Zertifikatsfixtures aus `src/test/resources/tls-test-tool/certificates`.
  Die TLS-Testausführung erfolgt über den TLS Test Tool Service.

Der Container-Build-Job für `quality_gate` in `.gitlab-ci.yml` holt das Zertifikats-Repository vor `docker build` per Sparse-Checkout in den
Build-Kontext.
Der Job nutzt zusätzlich einen GitLab-Cache für das Bare-Repository unter `.cache/git/`, damit Folgepipelines nur noch die Änderungen aus GitLab
nachladen müssen.
Empfohlen ist dafür in GitLab CI/CD:

- standardmäßig wird `${CI_PROJECT_NAMESPACE}/zeta-test-certificates` auf derselben GitLab-Instanz verwendet
- optional `CERT_REPO_URL` als vollständige Clone-URL für abweichende Hosts oder Strukturen
- optional `CERT_REPO_REF`, Default `main`

Beispiel:

```yaml
variables:
  CERT_REPO_REF: "main"

docker-image-qualitygate:
  stage: container
  image: docker:29-cli
  variables:
    CERT_REPO_DIR: ".cache/zeta-test-certificates"
    CERT_REPO_GIT_CACHE_DIR: ".cache/git/zeta-test-certificates.git"
  cache:
    key: "docker-build-${CI_SERVER_HOST}-${CI_PROJECT_PATH_SLUG}"
    paths:
      - .cache/git/
    policy: pull-push
  script:
    - apk add --no-cache git
    - test -n "${CI_SERVER_URL}" && test -n "${CI_PROJECT_NAMESPACE}"
    - CERT_REPO_URL="https://gitlab-ci-token:${CI_JOB_TOKEN}@${CI_SERVER_URL#https://}/${CI_PROJECT_NAMESPACE}/zeta-test-certificates.git"
    - mkdir -p "$(dirname "${CERT_REPO_GIT_CACHE_DIR}")"
    - test -d "${CERT_REPO_GIT_CACHE_DIR}/objects" || git init --bare "${CERT_REPO_GIT_CACHE_DIR}"
    - git -C "${CERT_REPO_GIT_CACHE_DIR}" fetch --depth 1 "${CERT_REPO_URL}" "${CERT_REPO_REF}"
    - cert_repo_sha="$(git -C "${CERT_REPO_GIT_CACHE_DIR}" rev-parse FETCH_HEAD)"
    - git -C "${CERT_REPO_GIT_CACHE_DIR}" update-ref refs/heads/cache "${cert_repo_sha}"
    - git -C "${CERT_REPO_GIT_CACHE_DIR}" symbolic-ref HEAD refs/heads/cache
    - rm -f "${CERT_REPO_GIT_CACHE_DIR}/FETCH_HEAD"
    - rm -rf "${CERT_REPO_DIR}"
    - git clone --reference-if-able "${CERT_REPO_GIT_CACHE_DIR}" --no-checkout "${CERT_REPO_GIT_CACHE_DIR}" "${CERT_REPO_DIR}"
    - git -C "${CERT_REPO_DIR}" sparse-checkout init --cone
    - git -C "${CERT_REPO_DIR}" sparse-checkout set keystores manifest
    - git -C "${CERT_REPO_DIR}" -c advice.detachedHead=false checkout "${cert_repo_sha}"
    - test -f "${CERT_REPO_DIR}/manifest/cert-manifest.tsv"
    - test -f "${CERT_REPO_DIR}/manifest/truststore-manifest.tsv"
    - test -f "${CERT_REPO_DIR}/manifest/keystore-manifest.tsv"
    - rm -rf "${CERT_REPO_DIR}/.git"
    - docker build -f docker/quality_gate/Dockerfile -t "${CI_REGISTRY_IMAGE}:quality_gate" .
```

Wichtig:

- Der zusätzliche Inhalt wird vor dem Docker-Build per Sparse-Checkout nach `.cache/zeta-test-certificates` geholt und dann per `COPY` ins
  Image übernommen.
- Vor dem Docker-Build wird geprüft, dass `manifest/cert-manifest.tsv`,
  `manifest/truststore-manifest.tsv` und `manifest/keystore-manifest.tsv` vorhanden sind.
- SMC-B Deployment-Keymaterial muss zur Laufzeit als Datei verfügbar sein.
  Die Dateipfade werden per `SMB_KEYSTORE_FILE_B64`/`SMB_KEYSTORE_PW_FILE` übergeben.
  Der repo-nahe Default-Fallback ist im Zertifikats-Repository unter `smcb/smcb-certificates.p12.b64` und `smcb/smcb-pw` dokumentiert.
- Vor dem Docker-Build wird `.git/` aus dem Zertifikats-Checkout entfernt, damit keine Remote-URL mit eingebettetem Job-Token ins Image
  gelangt.
- Im Bare-Repository-Cache wird keine Remote-URL gespeichert.
  `FETCH_HEAD` wird nach dem Auflösen des Commits entfernt, damit der Job-Token nicht im GitLab-Cache landet.
- Für Mirrors auf derselben GitLab-Instanz reicht meist die Ableitung über `CI_PROJECT_NAMESPACE` zusammen mit `CI_JOB_TOKEN`.
- Für andere Hosts oder Zugangsdaten kann stattdessen `CERT_REPO_URL` verwendet werden.

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
