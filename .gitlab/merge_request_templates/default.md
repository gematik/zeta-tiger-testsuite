## Summary
<!-- What problem is solved and why now? -->

## Scope
- UserStory/UseCase: `...`
- TA / Requirement / Spec reference: `...` (e.g. `TA_A_xxxxx_xx`, RFC, gemSpec section)


## Gherkin / Test Design (if applicable)
- Feature files: `src/test/resources/features/...`
- Language/format check: German Gherkin, 2-space indentation, snake_case filenames
- Reused existing TGR steps: `yes/no`
- New step definitions added: `yes/no`
- If yes, why existing steps were insufficient: `...`

## Config Impact
- `tiger.yaml` / overlay changes (`tiger-proxy.yaml`, etc.): `none/...`
- Test data changes (`tiger/*.yaml`): `none/...`
- Secrets affected: `no` (confirm no credentials committed)

## Validation
Executed:
- `PROFILE=proxy mvn -Dcucumber.filter.tags='@staging' clean verify`
- `mvn test` (if run)
- `mvn -Pgenerate-documentation generate-resources` (if docs/steps changed)
- `uv run --project docs/scripts generate-cucumber-methods` (if step definitions changed)

Result:
- `pass/fail`
- Relevant logs/screenshots/artifacts: `...`

## Documentation Updates
- UseCase README `include::...feature[]` updated: `yes/no/n.a.`
- Other docs updated (`docs/...`): `...`

## Risks & Open Points
- Functional risk: `low/medium/high` + why
- Compatibility impact: `...`
- Follow-ups (if any): `...`

## Checklist
- [ ] Changes are limited to intended scope
- [ ] Existing TGR steps preferred over custom code
- [ ] Feature paths and naming follow project conventions
- [ ] No credentials/secrets added
- [ ] Tests/docs updated where required
