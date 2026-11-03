# ZETA V2 schema provenance

The `openapi/` and `schemas/` directories are unchanged copies of the corresponding
upstream ZETA trees from one revision. Their sibling layout preserves relative references.

- Upstream repository: [gematik/zeta](https://github.com/gematik/zeta)
- Source branch: `feature/zeta-stufe-2`
- Pinned upstream ref: `b94a63bbf6288d783350e2b23fb7dc2244c9d156`
- Upstream paths: `src/openapi`, `src/schemas`
- Local path: `src/test/resources/schemas/v_2_0`
- Retrieved: `2026-09-28`

## Import scope

The exact file inventory is maintained in [../sources.json](../sources.json). It includes
all files in both upstream directories at the pinned revision, including nested schema
files and the empty upstream placeholder `openapi/as_token_endpoint.yaml`.
The token endpoint definition is `openapi/as-token-endpoint.yaml`.
New upstream files must be included explicitly when updating this inventory.

This refresh advances from `0f29d8c824b1a525b0b4c20cd34c1448cdbd5eac` and changes
three files. `dcr-request.yaml` clarifies public-key canonicalization and hash-signature
encoding. `policy-engine-client-data.yaml` clarifies verified Android claims.
`posture-android.yaml` moves to schema version 2.0.0: `nonce` and
`signed_client_data_hash` are required, and the certificate-chain field becomes the
optional `android_key_attestation_certificate_chain`. All other imported files are
unchanged, including the previously added `schemas/as-entity-statement.yaml`.

Schema validation establishes payload structure only. The described hardware signature,
nonce consumption and Play Integrity binding need native Android execution evidence;
importing these definitions does not itself implement or verify those product behaviors.

The earlier package consolidation replaced the separate OpenAPI snapshot and its duplicated schema dependencies.
Compared with that snapshot, the two discovery OpenAPI documents include caching/ETag
metadata, and `authorization-backend-webhook.yaml` is included. That consolidation preserved
the V2 JSON Schema contents and moved their local paths to `v_2_0/schemas/`.
The former OpenAPI copy of `zeta-error.yaml` also differs in its version metadata and
documented error codes; its open string contract is unchanged. It now resolves to the
same V2 file as all other callers.

## Usage

The default `check-zeta-schemas` and `update-zeta-schemas` commands select this package
and the pinned commit. Moving the source branch does not change the test baseline.
`UPSTREAM.md` is local provenance metadata, excluded from the upstream byte comparison
and preserved when the imported files are replaced.

The source is the development snapshot selected for CC runtime validation. Its schema
definitions take precedence over conflicting specification prose. User-info and
policy-input validation use this complete package directly, including optional-field
constraints; no local replacement changes its field names or value domains.
Focused TestAspect checks reuse unchanged upstream field definitions directly in scenario
DocStrings; their placement and scope are documented in [../README.md](../README.md).
