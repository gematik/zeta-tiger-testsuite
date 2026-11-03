# Schema packages and test rules

Runtime contracts are stored locally so test execution does not depend on network access.
Each upstream package records its own source and version:

| Directory | Content | Provenance |
| --- | --- | --- |
| `v_2_0/` | ZETA CC OpenAPI definitions and JSON Schemas from one upstream revision | [UPSTREAM.md](v_2_0/UPSTREAM.md) |
| `push_notifications_openapi/` | Push Notification Client–FD and Gateway APIs | [UPSTREAM.md](push_notifications_openapi/UPSTREAM.md) |

`sources.json` is the machine-readable import inventory used by `check-zeta-schemas --all`
and the blocking CI drift check. It records each independent pin and, for selective
imports, the exact upstream-to-local file mapping. Package-local `UPSTREAM.md` files
explain the provenance; they are the only files excluded from byte comparison.

`v_2_0/openapi/` and `v_2_0/schemas/` preserve the upstream sibling layout and share
one pin. Relative references resolve within this package without rewriting imported files.
Schema-validation steps take an explicit resource path, for example
`schemas/v_2_0/schemas/access-token.yaml`, to select the intended contract.

The pinned schema files are authoritative when specification prose contradicts their
field names, types or value domains. Imported contracts must not be locally rewritten
or replaced to accommodate those contradictions.

Focused TestAspect checks live directly in the scenarios as inline TGR `JSON_SCHEMA`
assertions. They declare the checked object and required field there and reuse its
unchanged upstream definition via a `classpath:` `$ref` with a JSON Pointer. Field
names, types and value domains are not duplicated in separate local CC schemas.
The observation remains the complete recorded object so JSON types are preserved.
For example, `acr` references `v_2_0/schemas/access-token.yaml#/properties/payload/properties/acr`.
TGR assertions fail immediately on violations; they do not use the custom validator's
`schemaValidation.soft` setting. Explicit `ver` presence is limited to scenarios that
deliberately select a token version, not imposed on every legacy token.

`mock/` contains test fixtures. `openid-federation-entity-statement.yaml` is a
test-specific structural check with its normative source recorded in the file.
Focused checks and fixtures are not substitutes for complete upstream validation.

The `validiere ... gegen OpenAPI-Schema ...` step takes an owning OpenAPI document plus
a JSON Pointer to its Schema Object. For example, the Push API response is selected via
`push_notifications_openapi/fd_openapi.yaml#/paths/~1pushers/get/responses/200/content/application~1json/schema`.
The loader selects OpenAPI 3.0 or 3.1 from `openapi`; 3.1 also honors `jsonSchemaDialect`
and explicit `$schema` declarations. Relative references retain their original base location.
Unsupported versions/dialects and missing fragments fail instead of silently using Draft 7.

OpenAPI contracts are evaluated without the optional JSON-Schema strict-mode additions.
The existing `schemaValidation.soft` setting controls how payload errors are collected;
invalid schema references/configuration always fail immediately. Standalone JSON Schemas
keep the existing step and its Draft-7 fallback when `$schema` is absent.
Scenario-specific required fields, values and state checks remain in features or testdata.

The networknt 3.0.7 OpenAPI-3.0 dialect requires a focused compatibility correction:
`nullable: true` extends only an explicit `type` in the same Schema Object, without
widening `enum` or inheriting nullability into composed schemas. `OpenApi30Dialect`
implements these two keyword corrections; imported files remain unchanged. Regression
tests cover this boundary and should be retained when upgrading the dependency.

For checking and updating imports, see the [tool documentation](../../../../docs/scripts/README.md#skript-zeta-schemas-prüfen-und-aktualisieren).
