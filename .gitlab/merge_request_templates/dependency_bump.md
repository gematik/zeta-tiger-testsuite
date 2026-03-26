## Summary
<!-- Why was the dependency updated/added -->

## Dependency Changes
- `group/artifact` `old` -> `new`
- `group/artifact` `old` -> `new`

## Scope
- Build/plugin only: `yes/no`
- Runtime-impacting deps: `yes/no`
- Transitive impact to Tiger/ZETA execution: `none/possible` (details: `...`)

## Code Changes Required
- API migration needed due to bump: `yes/no`
- Changed files:
  - `...`

## Validation
Executed:
- `PROFILE=proxy mvn -Dcucumber.filter.tags='@staging' clean verify`
- `mvn test` (if run)

Result:
- `pass/fail`
- Relevant logs/artifacts: `...`

## Risks
- Known breaking-change risk: `low/medium/high`
- Areas to watch after merge: `...`

## Checklist
- [ ] Versions are intentionally selected (no accidental drift)
- [ ] Any required API migrations are included
- [ ] No credentials/secrets added
