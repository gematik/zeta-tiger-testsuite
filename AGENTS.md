# Repository Guidelines

## Project Structure & Module Organization
- `pom.xml`: Maven build, profiles, and plugin configuration.
- `src/test/resources/features/`: Gherkin features (German `#language:de`) grouped by `userstories/<UserStory>/<UseCase>/` and `smoke/`.
- `docs/`: Asciidoc sources, aliases, architecture notes, and `package.json` for Mermaid/Puppeteer.
- No wrapper scripts are needed; Mermaid CLI is invoked directly by Maven (local) and by the CI Asciidoctor image.
- `tiger*.yaml`, `defaults.yaml`, `demoData.yaml`: Test environment/configuration inputs for the Tiger test framework.

## Build, Test, and Development Commands
- `mvn clean verify`: Build and run the test suite (Surefire/Failsafe via Tiger libraries).
- `mvn test`: Run tests without packaging.
- `mvn -Pasciidoc-enabled generate-resources`: Generate docs (HTML/PDF) into `target/docs/{html,pdf}`. Uses Node/Yarn via the frontend plugin and Mermaid for diagrams.
- `mvn -Pasciidoc-enabled -DskipTests package`: Package and generate docs in one go.

## Coding Style & Naming Conventions
- **Gherkin**: German keywords, 2-space indentation, descriptive scenario names. Place files under `src/test/resources/features/...` using lowercase snake_case file names.
- **Asciidoc**: Use concise headings, keep diagrams alongside source; images generated to the output directory by the build.
- **YAML**: Two-space indentation; no tabs. Keep secrets out of VCS.

## Testing Guidelines
- Framework: Tiger test libraries execute Gherkin features from `src/test/resources/features`.
- Add new `.feature` files under the appropriate `UserStory_xx/UseCase_xx/` folder. Keep steps reusable and data in YAML/adoc where applicable.
- Run locally with `mvn test` (or `mvn clean verify`). Check the `smoke/` features first for quick sanity.

## Commit & Pull Request Guidelines
- **Commits**: Imperative, present tense, concise (e.g., `add mermaid config`, `refactor pipeline`). Group related changes; avoid noisy churn.
- **PRs**: Provide a clear description, linked issues, and rationale. Include how to reproduce and test (commands, configs), and attach screenshots or logs for doc generation or test runs. Update docs when changing behavior.

## Security & Configuration Tips
- Do not commit credentials. Externalize secrets via environment variables or local, untracked overrides.
- Choose/adjust environment configs via the provided `tiger*.yaml` files; prefer `tiger-local.yaml` for local runs and keep cloud settings separate.
