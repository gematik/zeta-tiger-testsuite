## Summary
<!-- Which TA is implemented/extended and what is validated -->

## TA Mapping
- TA ID: `TA_A_xxxxx_xx`
- TA title/focus keyword(s): `...`
- Source doc: `docs/asciidoc/testaspekte/...`
- Linked spec/RFC section: `...`

## Test Design
- UserStory/UseCase path: `src/test/resources/features/userstories/...`
- Feature file(s): `...`
- Preconditions respected per UseCase: `yes/no`
- Existing TGR steps reused: `yes/no`
- New step definitions added: `yes/no`
- If new steps: why required beyond Tiger User Manual/cucumber methods table: `...`

## Implemented Scenarios
- `...`
- `...`

## Documentation Updates
- UseCase README `include::...feature[]` updated: `yes/no`
- Additional docs updated: `...`

## Validation
Executed:
- `PROFILE=proxy mvn -Dcucumber.filter.tags='@staging' clean verify`
- Targeted TA run/filter: `...`
- `uv run --project docs/scripts generate-cucumber-methods` (if step defs changed)

Result:
- `pass/fail`
- Relevant logs/artifacts: `...`

## Risks & Open Points
- Behavioral risk: `low/medium/high`
- Follow-ups / out-of-scope checks: `...`

## Checklist
- [ ] TA focus matches bold keyword intent
- [ ] German Gherkin, 2-space indentation, snake_case filename(s)
- [ ] Existing TGR steps preferred over custom code
- [ ] No credentials/secrets added
