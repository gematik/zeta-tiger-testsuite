# Repository Guidelines

## Priorities & Focus
- Always follow these priorities: (1) AGENTS rules, (2) prefer existing TGR steps over custom code, (3) Gherkin is German with 2-space indentation and snake_case filenames.
- Bold keywords in test aspects define the focus (e.g., **Ausgabe AccessToken** ⇒ no Refresh checks). Read the TA text and linked RFC section.
- Use Tiger User Manual (`docs/Tiger-User-Manual.html`) and ZETA specification (`docs/gemSpec_ZETA_V1.2.0_CC.html`) in `docs` for context.

## Project Structure & Module Organization
- `pom.xml`: Maven build, profiles, and plugin configuration.
- `src/test/resources/features/`: Gherkin features (German `#language:de`) grouped by `userstories/<UserStory>/<UseCase>/` and `smoke/`.
- `docs/`: Asciidoc sources, aliases, architecture notes, and `package.json` for Mermaid/Puppeteer.
- No wrapper scripts are needed; Mermaid CLI is invoked directly by Maven (local) and by the CI Asciidoctor image.
- `tiger.yaml` plus configs under `tiger/` (`defaults.yaml`, `paths.yaml`, `tiger-*.yaml`, `eRezeptTestData.yaml`): Test environment/configuration inputs for the Tiger test framework.

## Gherkin & Implementation Rules
- Use existing TGR steps (e.g., “Hole JWT…”, “TGR prüfe…”, “decodiere und validiere …”). Only add new step definitions if the test cannot be covered with the steps documented in `docs/Tiger-User-Manual.html` or `docs/asciidoc/tables/cucumber_methods_table.adoc`.
- Scenarios must respect UseCase preconditions (e.g., use existing tokens, do not reset if not allowed).
- Update UseCase readmes (include::…feature[]) when adding or renaming feature files.

## Build, Test, and Development Commands
- `mvn clean verify`: Build and run the test suite (Surefire/Failsafe via Tiger libraries).
- `mvn test`: Run tests without packaging.
- `mvn -Pgenerate-documentation generate-resources`: Generate docs (HTML) into `target/docs/html`. Uses Node/Yarn via the frontend plugin and Mermaid for diagrams (inkl. `uv sync`).
- `mvn -Pgenerate-documentation -DskipTests package`: Package and generate docs in one go.

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
