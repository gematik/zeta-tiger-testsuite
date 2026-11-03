# ZETA V2.0.0 CC source package and derived XML

The implementation source is gemSpec ZETA V2.0.0 CC, revision `1658346`, dated `09.07.2026`.
Its publication status is **zur Abstimmung freigegeben**, classification **öffentlich_Entwurf**.
The HTML was copied unchanged from the `spec` repository at commit `8e0498c3614b8fbfb34cea865318bec91d13ff00`, directory `data/versions/2.0.0-cc/source-package/` (HTML in `Draft_ZETA_26_2/`).
The XLSX originated from the same source package and is locally supplemented with
the requirement-end markers `[<=]` present in that pinned HTML specification.
The markers are included in both description fields for 310 matching requirement
IDs (371 rows, including repeated test procedures). All other cell values,
worksheet structure and formatting are preserved. The 96 rows belonging to other
source documents are unchanged because those documents are not contained in this
HTML specification. This supplement does not change requirement wording or source
versions and does not constitute an officially republished catalog.

- [Draft specification](gemSpec_ZETA_V2.0.0_CC.html)
- [Source requirement catalog](gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xlsx)
- [Locally derived XML](derived/gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xml)

## Deterministic conversion

Run from the repository root:

```bash
uv run --project docs/scripts generate-zeta-catalog-xml
uv run --project docs/scripts generate-zeta-catalog-xml --check
```

The first command regenerates only the derived XML.
The second checks exact bytes without writing and exits nonzero if the artifact is missing or differs.
Both commands validate the generated document against the pinned local XSD before accepting it; a schema error prevents writing output.
The converter is also available as `python -m testsuite_docs.zeta_catalog_xml` with `docs/scripts/src` on `PYTHONPATH` and the project dependencies installed.
Identical XLSX bytes produce identical UTF-8 XML bytes with LF line endings, fixed element order, and no timestamps or machine-specific paths.
The XML includes the source workbook's SHA-256 digest.
Re-saving the XLSX can change that digest even when its visible values remain unchanged.

The conversion preserves all 467 requirement rows in worksheet order, covering 399 distinct IDs and five test procedures.
The 68 repeated IDs have different test procedures; they are retained as separate rows, as in the historical XML format.
The product-test procedure selects 256 requirement IDs.
The six source documents, literal text and HTML descriptions, severity, source references, configuration elements, chapter information, parent relationships, and further references come exclusively from the XLSX.
HTML descriptions are XML-escaped text rather than CDATA; parsing recovers the original HTML string.
Newline-separated configuration elements and parent IDs become ordered child elements.
An empty configuration cell omits the optional `configElements` element.
The workbook's explanatory notes are preserved as XML comments prefixed with `Workbook note:`.
No `workbookNotes` element or other catalog extension is added.
Notes containing `--` are rejected because that sequence cannot occur in an XML comment.
The empty `Spezifische Merkmale` sheet becomes an empty `specificAttributes` element.
Changed sheet layouts, formulas, unknown source or parent IDs, conflicting repeated definitions, duplicate ID/procedure pairs, and populated specific attributes are rejected rather than guessed or silently discarded.

## Status and limitations

This XML is a local, reproducible conversion of the draft XLSX, **not a published or authoritative gematik XML catalog**.
It is compatible with the current `generate-zeta-afos` requirement reader, including its product-test filter.
It validates against [the local schema](schemas/afoListeSteckbrief_v2_local.xsd), which changes only the requirement attributes `type` and `lastUpdate` from required to optional.
Their original value types (`xs:string` and `xs:date`) remain unchanged, as do all other schema rules.
It does not validate against the unmodified official schema because those two attributes are absent.
The XLSX does not supply requirement `type` or `lastUpdate` fields; those attributes are omitted.
No values are copied from the historical XML, inferred from old IDs, or presented as official metadata.
The source document version `2.0.0_CC` is preserved exactly as supplied by the workbook.

## Pinned schemas

The [upstream schema snapshot](schemas/upstream/afoListeSteckbrief_v2.xsd) was downloaded from [gematik](https://gemspec.gematik.de/schemas/afoListeSteckbrief_v2.xsd) on `2026-09-15`.
It is retained byte-for-byte, including its CRLF line endings, with SHA-256 `cf38270fdc31d15afd55a4e23aebf7f67a5881d9822ccfb60f413c53e72296e0`.
The `.gitattributes` exception prevents Git from rewriting this snapshot's bytes.
The local variant uses LF line endings and applies only the two `use="required"` to `use="optional"` changes through `render_local_schema` in `testsuite_docs.zeta_catalog_xml`.
Tests verify both its reproducibility from the snapshot and that the schema trees differ only in those two attribute requirements.
Validation uses `lxml` and the checked-in local XSD without network access.
No schema URL is used to fetch changing rules at build time.

To regenerate the local schema from the pinned snapshot, with the project dependencies installed:

```bash
PYTHONPATH=docs/scripts/src uv run --project docs/scripts python -c 'from testsuite_docs.zeta_catalog_xml import UPSTREAM_SCHEMA, LOCAL_SCHEMA, render_local_schema; LOCAL_SCHEMA.write_bytes(render_local_schema(UPSTREAM_SCHEMA.read_bytes()))'
```

## Catalog selection

The AFO generator uses the locally derived V2.0.0 CC XML by default.
Catalog generation alone does not change the implementation status of historical scenarios.
The maintained README and reference entries identify the local CC draft by name and version.
The AFO generator does not preserve these local source links; it constructs public gematik URLs from
XML metadata, which do not establish publication of the draft or its derived catalog.
Reconciliation with a subsequently published specification and corresponding official XML remains pending.

## Selected TestAspect and UseCase designs

TestAspects and UseCase procedures are maintained in the regular documentation sources.
Their semantic coverage must be reviewed afresh against the applicable specification and current implementation/evidence. Exclude earlier agent reviews, analyses and judgments from the review context entirely; do not consult, consider or reproduce them.
AFO documents are maintained in the [test plan](../../asciidoc/afos) and checked directly
against the pinned source workbook. `--force` fully replaces the affected output folders
with requirements selected from the input XML.
The separate `generate-doc-includes` command builds common indexes from the existing AFO files without
changing their definitions.
Requirements retained outside the selected catalog remain visible without implying completed scenario migration.
The existing editorial corrections to A_26486-01, A_26668-02 and A_26988 from commit `5cfb1757` are also maintained there unchanged.
The repository consistency tests reject retired requirement definitions, tags, includes and
cross-references in the maintained catalog, UseCases and scenarios. Literal mentions in unchanged
normative source quotations remain permitted.

## Verification

The selected TestAspect/UseCase designs also have [maintained offline verification](verification/README.md).
Run `uv run --project docs/scripts check-zeta-design` for mechanical source, quotation,
document structure and current reference consistency. Stored design expectations
and historical review conclusions are not inputs. A passing result is not
independent design validation; semantic correctness and runtime conformity remain unverified.

```bash
uv run --project docs/scripts pytest docs/scripts/tests/test_zeta_catalog_xml.py
```

The tests compare every requirement field with the XLSX, preserve duplicate procedures and parent relations, check documents and reference tables, verify exact reproducibility and read-only drift detection, reject ambiguous source data, and exercise the existing AFO generator in a temporary output directory.
Schema tests also reject missing required attributes and elements, invalid severity and date values, incorrect element order, and unexpected elements or attributes.
An invalid generated document cannot overwrite an existing output file.
An in-memory fixture supplying synthetic `type` and `lastUpdate` values passes the unmodified official schema, confirming that all remaining official rules are satisfied; those synthetic values are never written into the catalog.
