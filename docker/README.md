# Docker images

This repo ships two Docker images side by side:

- `docker/frontend/Dockerfile` → Maven-in-container image (builds and runs the testsuite). Pushed as `:latest`.
- `docker/quality_gate/Dockerfile` → Runtime-only image (JRE + packaged testsuite, no Maven) with its own `run-tests.sh`. Pushed as `:qualitygate`.

## CI build (GitLab)

CI builds both images via Buildx:

- `docker buildx build -f docker/frontend/Dockerfile -t ${CI_REGISTRY_IMAGE}:latest .`
- `docker buildx build -f docker/quality_gate/Dockerfile -t ${CI_REGISTRY_IMAGE}:qualitygate .`

## Local build & run

Build the Maven-based image:

```sh
docker build -f docker/frontend/Dockerfile -t testsuite:latest .
```

Build the runtime-only image:

```sh
docker build -f docker/quality_gate/Dockerfile -t testsuite:qualitygate .
```

Run (shared defaults):

```sh
# Maven-based (runs mvn verify inside)
docker run --rm -e CUCUMBER_TAGS="@smoke" testsuite:latest

# Runtime-only (uses packaged artefacts + run-tests.sh)
docker run --rm -e CUCUMBER_TAGS="@smoke" testsuite:qualitygate
```

Common env vars (shared by both images):

- `CUCUMBER_TAGS` - tag filter (default `@smoke`)
- `ZETA_PROXY` - proxy mode (`no-proxy` by default)
- `ZETA_PROXY_URL` - proxy URL
- `ZETA_BASE_URL` - target base URL
- `TIGER_ENVIRONMENT` - Tiger environment (default `cloud`)
- `SERENITY_EXPORT_DIR` - optional output path for Serenity reports
- `CUCUMBER_EXPORT_DIR` - optional Cucumber JSON output path

## GitLab CI usage for the quality gate

To run the quality gate image inside a GitLab CI job (pulling the prebuilt image):

```yaml
quality-gate:
  stage: test
  image: "${CI_REGISTRY_IMAGE}:qualitygate"
  script:
    - /app/run-tests.sh
  variables:
    CUCUMBER_TAGS: "@smoke"
    ZETA_PROXY: "no-proxy"
    TIGER_ENVIRONMENT: "cloud"
  artifacts:
    when: always
    paths:
      - target/site/serenity
      - target/cucumber-parallel
    reports:
      junit: target/cucumber-parallel/cucumber.xml
```

Notes:
- Adjust `CUCUMBER_TAGS`/`ZETA_BASE_URL`/`ZETA_PROXY_URL` as needed.
- Mount extra outputs by setting `SERENITY_EXPORT_DIR` / `CUCUMBER_EXPORT_DIR`.
