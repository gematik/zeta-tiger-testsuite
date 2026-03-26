## Summary
<!-- Production/staging issue fixed and immediate impact -->

## Incident / Reason
- Reference (ticket/alert): `...`
- Affected area: `...`
- Root cause: `...`


## Risk Assessment
- Why this is safe for fast merge: `...`
- Known limitations: `...`

## Validation
Executed:
- `PROFILE=proxy mvn -Dcucumber.filter.tags='@staging' clean verify`
- Additional targeted check(s): `...`

Result:
- `pass/fail`
- Relevant logs/artifacts: `...`

## Rollback Plan
- Revert commit(s): `...`
- Fallback config toggle (if any): `...`

## Checklist
- [ ] Fix scope is minimal and isolated
- [ ] No unrelated refactoring included
- [ ] No credentials/secrets added
- [ ] Rollback plan is clear
