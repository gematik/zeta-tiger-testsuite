# Mechanical source and document checks

The checker reads original source bytes and current documents. It does not verify
semantic correctness, complete test coverage or runtime conformity.

```bash
uv run --project docs/scripts generate-doc-includes
uv run --project docs/scripts traceability --project-root .
uv run --project docs/scripts generate-cucumber-methods
uv run --project docs/scripts check-zeta-design
uv run --project docs/scripts check-zeta-design --rendered target/docs/html/Testplan_ZETA.html
```

The existing command name is retained for build compatibility. The checker reads
`gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xlsx` from the CC source package directly.
The requirements and UseCases in the configured check scope are selected by
`REQUIREMENT_SCOPE` and `USECASE_SCOPE` in `design_checks.py`.
TestAspects and current assignments are discovered within that scope.
The active AFO documents under `docs/asciidoc/afos` are compared directly with the
workbook. `SUPPORTING_REQUIREMENT_SCOPE` selects additional AFO documents for source
checks without expanding quotation or UseCase structure checks. Adding files does
not automatically expand either scope. Missing selected AFOs fail, including
requirements outside the generator's product-test filter. Missing or empty source
input fails rather than producing an empty successful run.
There is no manifest or `--config` option. The scope constants contain identifiers
only, with no expected assignments, judgments or acceptance decisions.

Checks cover active AFO documents against the workbook, exact quotations, explicit omissions,
markup, UseCase table structure, reference consistency, includes, anchors and
optional rendered content. Source versions and changes are tracked in Git;
this checker does not maintain a separate expected-hash inventory.
Within the UseCase structure scope, TestAspect IDs and references belong only in
the `Abgedeckte Testaspekte` field. Occurrences elsewhere in the document produce
`reference-placement`; unknown and duplicate coverage references are still
rejected. Product flows do not need repeated TestAspect references.
Highlight gaps, overlaps and current assignments are observations.
No stored explanation approves them. Schema/table interpretation and coverage
require fresh review of the actual source. The separate runtime schema-drift
checker and its upstream pins are unchanged.

The report defaults to `target/design-verification.json`. Every result and CLI
summary states that independent validation was not performed and semantic
correctness, coverage completeness and source authority remain unverified.
Runtime compliance is not tested. Exit code zero means only the mechanical checks
passed. The report lists the actual checked IDs; omitted IDs are not verified.
`checked_afo_ids` lists the AFO documents compared with the workbook;
`checked_requirement_ids` lists the requirements selected for quotation checks.

For semantic verification, derive obligations afresh from the applicable
specification revision, including referenced figures, schemas and tables. Inspect
current TestAspects, UseCases, assertions and implementation against those
obligations and counterexamples. Report the source revision, code revision and direct evidence in the current
conversation. Do not add agent judgments, semantic assessments, review
conclusions or their rationale to version-controlled repository content. Local
Git-ignored task notes must not be committed or used as input to later reviews. Do not load prior agent reviews, analyses or
conclusions into the verification context, consult them as hints or assumptions,
or reproduce them in the current review. They must not influence the assessment
in any form. Inspect source material, current documents/code and direct machine
evidence afresh.
