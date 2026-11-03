#
# #%L
# ZETA Testsuite
# %%
# (C) achelos GmbH, 2025, licensed for gematik GmbH
# %%
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# *******
#
# For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
# #L%
#

"""Inject independent defects into temporary documents; never mutate the repository."""
from __future__ import annotations

from contextlib import redirect_stdout
import base64
import html
import io
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import yaml

from testsuite_docs import design_checks as checks


ROOT = Path(__file__).resolve().parents[3]
TA = "TA_A_29951_01"
UC = "UC_01_01"
BODY = "Der Authorization Server MUSS die Token Response nach dem Schema [token-response.yaml] erstellen."


class QuotationTest(unittest.TestCase):
  def test_source_end_marker_is_structural_without_hiding_wording_changes(self):
    source = checks.source_wording('A B.\n[<=]')
    self.assertEqual(source, 'A B.')
    self.assertEqual(checks.source_wording('A [<=] B.'), 'A [<=] B.')
    for body in ['"**A** B. [<=]"', '"**A** B."']:
      marked, _ = checks.quotation('Heading\n\n' + body)
      self.assertEqual(checks.quote_spans(marked, source), ([(0, 4)], [(0, 1)]))
    with self.assertRaises(ValueError):
      checks.quote_spans('A C.', source)

  def test_bold_offsets_point_into_source(self):
    quoted, bold = checks.quote_spans("Eins **zwei** drei.", "Eins zwei drei.")
    self.assertEqual(quoted, [(0, 15)])
    self.assertEqual(bold, [(5, 9)])

  def test_literal_source_ellipsis_is_preserved(self):
    self.assertEqual(checks.quote_spans("**A ... B**", "A ... B"), ([(0, 7)], [(0, 7)]))

  def test_explicit_omissions_are_measurable_and_can_be_combined(self):
    left, _ = checks.quote_spans("**A** ...", "A B C.")
    right, _ = checks.quote_spans("... **B C.**", "A B C.")
    self.assertEqual(checks.uncovered("A B C.", left, False), ["B C."])
    self.assertEqual(checks.uncovered("A B C.", left + right, False), [])

  def test_missing_punctuation_is_an_omission(self):
    spans, _ = checks.quote_spans("A ...", "A.")
    self.assertEqual(checks.uncovered("A.", spans, False), ["."])

  def test_changed_reordered_ambiguous_and_unmarked_omissions_fail(self):
    for marked, source in [("**A C**", "A B"), ("B ... A", "A B"),
                           ("... A ...", "A A"), ("A", "A."),
                           ("**A ... B", "A x B")]:
      with self.subTest(marked=marked), self.assertRaises(ValueError):
        checks.quote_spans(marked, source)

  def test_legacy_quotation_layouts_preserve_source_wording(self):
    for body in ['"**A** B. **[<=]**"', '"**A** B.[<=]"',
                 '"**A** B.**[<=]"**', '**A** B.', '**A** B. **[<=]**']:
      with self.subTest(body=body):
        marked, notes = checks.quotation('[#TA_A_1_01]\n===== Example\n\n' + body)
        self.assertEqual(checks.quote_spans(marked, 'A B.')[0], [(0, 4)])
        self.assertEqual(notes, '')

  def test_asciidoc_table_and_explicit_omission_preserve_order(self):
    text = '[#TA_A_1_01]\n===== Example\n\n"+++A+++\n\n|===\n| **B** | C\n| [...] | D\n|===\n[<=]"'
    marked, notes = checks.quotation(text)
    self.assertEqual(checks.quote_spans(marked, 'A B C omitted D')[0], [(0, 5), (14, 15)])
    with self.assertRaises(ValueError):
      checks.quote_spans(marked, 'A C B omitted D')

  def test_asciidoc_list_preserves_wording_order_and_highlights(self):
    text = 'Heading\n\nA:\n\n* **B**\n* C\n\n**[<=]**'
    marked, notes = checks.quotation(text)
    self.assertEqual(checks.quote_spans(marked, 'A: B C'), ([(0, 6)], [(3, 4)]))
    self.assertEqual(notes, '')
    for source in ['A: B D', 'A: C B', 'A: B C D']:
      with self.subTest(source=source), self.assertRaises(ValueError):
        checks.quote_spans(marked, source)

  def test_bracketed_omission_after_period_preserves_punctuation(self):
    marked, _ = checks.quotation('Heading\n\n"**A**.[...]**[<=]"**')
    self.assertEqual(checks.quote_spans(marked, 'A. B.'), ([(0, 2)], [(0, 1)]))

  def test_italic_source_terms_preserve_bold_offsets(self):
    marked, _ = checks.quotation('Heading\n\n"The **claim *actorId*** matches *sub*. [<=]"')
    self.assertEqual(checks.quote_spans(marked, 'The claim actorId matches sub.'),
                     ([(0, 30)], [(4, 17)]))

  def test_endpoint_wildcard_is_not_italic_markup(self):
    for body in ['GET /history/* requires **scope**.', '**GET /history/*** requires scope.']:
      with self.subTest(body=body):
        marked, _ = checks.quotation('Heading\n\n"' + body + ' [<=]"')
        self.assertEqual(checks.quote_spans(marked, 'GET /history/* requires scope.')[0], [(0, 30)])

  def test_closed_quote_without_marker_keeps_notes_separate(self):
    marked, notes = checks.quotation('Heading\n\n"[...] **B.** [...]"\n\nExplanation.')
    self.assertEqual(checks.quote_spans(marked, 'A. B. C.')[0], [(3, 5)])
    self.assertEqual(notes, 'Explanation.')

  def test_unterminated_quoted_block_is_rejected(self):
    with self.assertRaisesRegex(ValueError, 'no closing marker'):
      checks.quotation('[#TA_A_1_01]\n===== Example\n\n"**A** B.')


class DesignMutationTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    cls.workbook = (ROOT / checks.DEFAULT_XLSX).read_bytes()
    cls.template = (ROOT / checks.UC_ROOT / "Use_Case_Template.adoc").read_bytes()

  def setUp(self):
    requirement_scope = patch.object(checks, 'REQUIREMENT_SCOPE', frozenset({'A_29951'}))
    requirement_scope.start()
    self.addCleanup(requirement_scope.stop)
    supporting_scope = patch.object(checks, 'SUPPORTING_REQUIREMENT_SCOPE', frozenset())
    supporting_scope.start()
    self.addCleanup(supporting_scope.stop)
    scope = patch.object(checks, 'USECASE_SCOPE', frozenset({UC, 'UC_01_02'}))
    scope.start()
    self.addCleanup(scope.stop)
    self.tmp = tempfile.TemporaryDirectory()
    self.addCleanup(self.tmp.cleanup)
    self.root = Path(self.tmp.name)
    self.ta = checks.TA_ROOT / "gemSpec_ZETA/A_29951" / (TA + ".adoc")
    self.uc = checks.UC_ROOT / "UserStory_01/UseCase_01/readme.adoc"
    self.catalog = checks.DEFAULT_XLSX
    self.afo = checks.AFO_ROOT / "gemSpec_ZETA/A_29951.adoc"
    self.write(self.catalog, self.workbook)
    self.write(checks.UC_ROOT / "Use_Case_Template.adoc", self.template)
    afo = "[#A_29951]\n==== A_29951 - PDP Authorization Server, Token Ausstellung\n\n" + BODY + " [<=]\n"
    self.write(self.afo, afo)
    self.write(self.ta, f'[#'+TA+f']\n===== {TA} - Schema\n\n"**{BODY}**\n\n**[<=]**"\n\n'
               'Pflichtfeld **token_type**: string, DPoP. Optionales **refresh_token**: string. '
               'Pflichtfeld **service.name**: ZETA Guard PEP HTTP proxy.\n')
    self.write(self.uc, f'''[#{UC}]
== UseCase_01_01: Schema

[cols="1,1a",options="autowidth"]
|===
| *Titel:* | Schema
| Akteur: | Testsuite
| Auslöser: | Request
| Kurzbeschreibung: | Prüfen
| Vorbedingung: | Gültiger Request
| Nebenbedingung: | Keine
| Ablauf: |
. Token prüfen.
| Nachbedingung: | Korrekte Antwort
| Abgedeckte Testaspekte: |
* <<{TA}>>
|===

[mermaid,uc-01-01,svg]
....
sequenceDiagram
    A->>B: Token
....
''')
    self.write(Path("docs/asciidoc/Testplan_ZETA.adoc"),
               'include::afos/gemSpec_ZETA/A_29951.adoc[]\n'
               f'include::testaspekte/gemSpec_ZETA/A_29951/{TA}.adoc[]\n'
               'include::../../src/test/resources/features/UserStory_01/UseCase_01/readme.adoc[]\n')

  def write(self, relative, value):
    path = self.root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(value if isinstance(value, bytes) else value.encode("utf-8"))

  def replace(self, relative, old, new):
    text = (self.root / relative).read_text(encoding="utf-8")
    self.assertIn(old, text)
    self.write(relative, text.replace(old, new))

  def run_check(self):
    return checks.check(self.root)

  def assert_finding(self, code):
    self.assertIn(code, {f["code"] for f in self.run_check()["findings"]})

  def test_valid_fixture_is_deterministic_and_does_not_write_sources(self):
    first = self.run_check()
    before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
    second = self.run_check()
    after = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
    self.assertEqual(first["findings"], [])
    self.assertEqual(first, second)
    self.assertEqual(before, after)

  def test_active_afo_needs_no_duplicate_source_directory(self):
    self.assertFalse((self.root / checks.DEFAULT_XLSX.parent / 'afos').exists())
    report = self.run_check()
    self.assertEqual(report['findings'], [])
    self.assertEqual(report['checked_afo_ids'], ['A_29951'])

  def test_afo_whitespace_normalization_preserves_wording_checks(self):
    self.replace(self.afo, 'Server MUSS die Token Response', 'Server\nMUSS die Token\u00a0Response')
    self.assertEqual(self.run_check()['findings'], [])
    self.replace(self.afo, 'MUSS', 'KANN')
    self.assert_finding('source-afo')

  def test_changed_afo_anchor_and_title_are_rejected(self):
    original = (self.root / self.afo).read_text(encoding='utf-8')
    for before, after in (('[#A_29951]', '[#A_29952]'), ('Token Ausstellung', 'Token Pruefung')):
      with self.subTest(before=before):
        self.write(self.afo, original.replace(before, after))
        self.assert_finding('source-afo')

  def test_afo_in_wrong_source_directory_is_rejected(self):
    active = self.root / self.afo
    misplaced = self.root / checks.AFO_ROOT / 'gemSpec_Perf' / active.name
    misplaced.parent.mkdir(parents=True)
    active.rename(misplaced)
    self.assert_finding('source-folder')
    self.assert_finding('source-missing')

  def test_altered_quote_is_rejected(self):
    self.replace(self.ta, "MUSS", "KANN")
    self.assert_finding("quotation")

  def test_unhighlighted_text_remains_unapproved(self):
    self.replace(self.ta, f'"**{BODY}**', f'"{BODY}')
    report = self.run_check()
    self.assertEqual(report['coverage']['A_29951']['unhighlighted'], [BODY])
    self.assertEqual(report['coverage']['A_29951']['semantic_review'], 'required_against_current_sources')

  def test_overlaps_remain_unapproved(self):
    tid = "TA_A_29951_02"
    self.write(self.ta.with_name(tid + ".adoc"), (self.root / self.ta).read_text(encoding="utf-8").replace(TA, tid))
    report = self.run_check()
    self.assertTrue(report['coverage']['A_29951']['overlaps'])
    self.assertEqual(report['assessment']['semantic_correctness'], 'not_verified')

  def test_explicit_ellipsis_cannot_hide_collective_omission(self):
    self.replace(self.ta, f"**{BODY}**", "**Der Authorization Server MUSS** ...")
    self.assert_finding("quotation-omission")

  def test_missing_aspect_for_maintained_requirement_is_detected(self):
    (self.root / self.ta).unlink()
    self.assert_finding("missing-aspects")

  def test_missing_active_afo_is_detected(self):
    (self.root / self.afo).unlink()
    self.assert_finding('source-missing')

  def test_missing_single_source_cannot_hide_an_invalid_aspect(self):
    aid = 'A_29853'
    self.add_selected_requirement(aid)
    with patch.object(checks, 'REQUIREMENT_SCOPE', frozenset({'A_29951', aid})):
      self.assertEqual(self.run_check()['findings'], [])
      self.replace(self.ta, 'MUSS', 'KANN')
      self.assert_finding('quotation')
      (self.root / checks.AFO_ROOT / 'gemSpec_ZETA' / (aid + '.adoc')).unlink()
      with redirect_stdout(io.StringIO()):
        code = checks.main(['--project-root', str(self.root)])
      report = json.loads((self.root / 'target/design-verification.json').read_text(encoding='utf-8'))
      self.assertEqual(code, 1)
      self.assertTrue(any(f['code'] == 'source-missing' and f['target'] == aid
                          for f in report['findings']))
      self.assertIn('A_29951', report['checked_requirement_ids'])
      self.assertIn(TA, report['checked_aspect_ids'])
      self.assertTrue(any(f['code'] == 'quotation' and f['target'] == TA
                          for f in report['findings']))
      self.assertNotIn(aid, report['checked_requirement_ids'])

  def test_requirements_outside_product_test_filter_must_remain_present(self):
    from testsuite_docs.zeta_afos import DEFAULT_TEST_PROCEDURE

    catalog = ET.fromstring(checks.render_catalog(self.workbook))
    excluded = {'A_25732'}
    for aid in excluded:
      rows = [r for r in catalog.findall('.//requirement') if r.attrib['id'] == aid]
      self.assertTrue(rows)
      self.assertFalse(any(DEFAULT_TEST_PROCEDURE in r.findtext('testProcedure', '') for r in rows))
      self.add_selected_requirement(aid)
    with patch.object(checks, 'REQUIREMENT_SCOPE', frozenset({'A_29951'} | excluded)):
      self.assertEqual(self.run_check()['findings'], [])
      for aid in excluded:
        (self.root / checks.AFO_ROOT / 'gemSpec_ZETA' / f'{aid}.adoc').unlink()
      report = self.run_check()
      for aid in excluded:
        self.assertTrue(any(f['code'] == 'source-missing' and f['target'] == aid
                            for f in report['findings']))
        self.assertNotIn(aid, report['checked_afo_ids'])
        self.assertNotIn(aid, report['checked_requirement_ids'])
      self.assertIn('A_29951', report['checked_requirement_ids'])

  def test_missing_supporting_requirement_is_detected(self):
    aid = 'A_26988'
    with patch.object(checks, 'SUPPORTING_REQUIREMENT_SCOPE', frozenset({aid})):
      report = self.run_check()
      self.assertTrue(any(f['code'] == 'source-missing' and f['target'] == aid
                          for f in report['findings']))
      self.assertIn('A_29951', report['checked_requirement_ids'])

  def test_title_body_boundary_is_checked_independently_of_whitespace(self):
    self.replace(self.afo,
                 'Token Ausstellung\n\nDer', 'Token\n\nAusstellung Der')
    self.assert_finding('source-afo')

  def test_fields_and_diagram_must_be_in_standard_positions(self):
    self.replace(self.uc, '| Akteur: | Testsuite\n| Auslöser: | Request',
                 '| Auslöser: | Request\n| Akteur: | Testsuite')
    self.replace(self.uc, '[mermaid,uc-01-01,svg]\n', '')
    self.replace(self.uc, '| Nebenbedingung:', '[mermaid,uc-01-01,svg]\n| Nebenbedingung:')
    report = self.run_check()
    codes = {f['code'] for f in report['findings']}
    self.assertIn('usecase-field-order', codes)
    self.assertIn('diagram-placement', codes)

  def test_extra_aspect_is_discovered(self):
    self.write(self.ta.with_name("TA_A_29951_99.adoc"), (self.root / self.ta).read_bytes())
    self.assert_finding("ta-anchor")

  def test_missing_standard_field_is_rejected(self):
    self.replace(self.uc, "| Vorbedingung: | Gültiger Request", "| Vorbedingungen: | Gültiger Request")
    self.assert_finding("usecase-fields")

  def test_duplicate_field_is_rejected(self):
    self.replace(self.uc, "| Nachbedingung: |", "| Vorbedingung: |")
    self.assert_finding("usecase-fields")

  def test_appended_section_is_rejected(self):
    self.write(self.uc, (self.root / self.uc).read_text(encoding="utf-8") + '\n=== Geplante Ergänzungen\n')
    self.assert_finding("usecase-section")

  def test_aspect_ids_and_references_outside_coverage_field_are_rejected(self):
    original = (self.root / self.uc).read_text(encoding="utf-8")
    for field, value in [('Ablauf', '. Token prüfen.'),
                         ('Vorbedingung', 'Gültiger Request'),
                         ('Nebenbedingung', 'Keine'),
                         ('Nachbedingung', 'Korrekte Antwort')]:
      for reference in [TA, f'<<{TA}>>', f'<<{TA},Schema>>']:
        with self.subTest(field=field, reference=reference):
          self.write(self.uc, original.replace(value, value + ' ' + reference))
          self.assertIn({'code': 'reference-placement', 'target': UC,
                         'detail': {field: [TA]}}, self.run_check()['findings'])

  def test_aspect_ids_outside_usecase_table_are_rejected(self):
    original = (self.root / self.uc).read_text(encoding='utf-8')
    for section, text in [('before-table', TA + '\n' + original),
                          ('after-table', original + '\n<<' + TA + '>>\n')]:
      with self.subTest(section=section):
        self.write(self.uc, text)
        self.assertIn({'code': 'reference-placement', 'target': UC,
                       'detail': {section: [TA]}}, self.run_check()['findings'])

  def test_aspect_ids_before_first_usecase_field_are_rejected(self):
    original = (self.root / self.uc).read_text(encoding='utf-8')
    for reference in [TA, f'<<{TA}>>', f'<<{TA},Schema>>']:
      with self.subTest(reference=reference):
        self.write(self.uc, original.replace('|===\n| *Titel:*',
                                            '|===\n' + reference + '\n| *Titel:*'))
        self.assertIn({'code': 'reference-placement', 'target': UC,
                       'detail': {'table-preamble': [TA]}}, self.run_check()['findings'])

  def test_coverage_references_with_labels_preserve_assignments(self):
    self.replace(self.uc, f'* <<{TA}>>', f'* <<{TA},Schema>>')
    report = self.run_check()
    self.assertEqual(report['findings'], [])
    self.assertEqual(report['observed_assignments'][TA], [UC])

  def test_empty_coverage_field_is_rejected(self):
    self.replace(self.uc, f'* <<{TA}>>', '')
    self.assert_finding('usecase-fields')

  def test_duplicate_assignment_is_rejected(self):
    self.replace(self.uc, f'* <<{TA}>>', f'* <<{TA}>>\n* <<{TA}>>')
    self.assert_finding("duplicate-assignment")

  def test_unlisted_procedure_reference_in_another_usecase_is_detected(self):
    other = checks.UC_ROOT / 'UserStory_01/UseCase_02/readme.adoc'
    text = (self.root / self.uc).read_text(encoding='utf-8').replace(UC, 'UC_01_02')
    text = text.replace(f'* <<{TA}>>', 'Keine')
    text = text.replace('. Token prüfen.', f'. Token prüfen. (<<{TA}>>)')
    self.write(other, text)
    self.assert_finding('reference-placement')

  def test_selected_aspect_in_unselected_usecase_flow_is_rejected(self):
    other = checks.UC_ROOT / 'UserStory_99/UseCase_99/readme.adoc'
    text = (self.root / self.uc).read_text(encoding='utf-8').replace(UC, 'UC_99_99')
    self.write(other, text)
    self.assertEqual(self.run_check()['findings'], [])
    self.replace(other, '. Token prüfen.', f'. Token prüfen. {TA}')
    self.assert_finding('reference-placement')

  def test_unknown_reference_is_rejected(self):
    self.replace(self.uc, f'* <<{TA}>>', f'* <<{TA}>>\n* <<TA_GS-A_9999_01>>')
    self.assert_finding("unknown-reference")

  def test_missing_include_is_rejected(self):
    self.replace(Path("docs/asciidoc/Testplan_ZETA.adoc"), 'include::afos/', 'include::absent/')
    self.assert_finding("include-missing")

  def test_duplicate_include_is_rejected(self):
    entry = Path("docs/asciidoc/Testplan_ZETA.adoc")
    text = (self.root / entry).read_text(encoding="utf-8")
    self.write(entry, text + text.splitlines()[1] + '\n')
    self.assert_finding("assembled-anchor")

  def test_changed_requirement_text_is_rejected(self):
    self.replace(self.afo, 'MUSS', 'KANN')
    self.replace(self.ta, 'MUSS', 'KANN')
    self.assert_finding('source-afo')

  def test_cli_writes_report_and_returns_failure(self):
    self.replace(self.ta, 'MUSS', 'KANN')
    with redirect_stdout(io.StringIO()):
      code = checks.main(['--project-root', str(self.root)])
    self.assertEqual(code, 1)
    self.assertTrue((self.root / 'target/design-verification.json').is_file())

  def rendered_fixture(self):
    svg = base64.b64encode(b'<svg xmlns="http://www.w3.org/2000/svg"><text>Token</text></svg>').decode()
    expected = [BODY, '[⇐]', 'token_type', 'refresh_token', 'service.name']
    text = '<html><head><meta charset="UTF-8"></head><body><h2 id="A_29951">Source</h2><section><h3 id="'+TA+'">Aspect</h3>'
    text += ''.join('<strong>'+html.escape(s)+'</strong>' for s in expected)
    text += '</section><section><h2 id="'+UC+'">UseCase</h2>'
    text += '<img alt="uc 01 01" src="data:image/svg+xml;base64,'+svg+'"></section></body></html>'
    self.write(Path('rendered.html'), text)

  def test_existing_optional_fields_and_unnamed_diagram_are_supported(self):
    self.rendered_fixture()
    self.replace(self.uc, '| Nachbedingung: |',
                 '| weitere Akteure: | Client\n| Hinweis: | Existing note\n| Nachbedingung: |')
    self.replace(self.uc, '[mermaid,uc-01-01,svg]', '[mermaid]')
    self.replace(Path('rendered.html'), 'alt="uc 01 01"', 'alt="diagram"')
    self.assertEqual(checks.check(self.root, rendered=Path('rendered.html'))['findings'], [])
    self.replace(Path('rendered.html'), '<img ', '<span ')
    report = checks.check(self.root, rendered=Path('rendered.html'))
    self.assertIn('rendered-diagram', {f['code'] for f in report['findings']})

  def test_rendered_italic_terms_inside_bold_focus(self):
    self.rendered_fixture()
    self.replace(self.ta, 'Optionales **refresh_token**', 'Optionales **refresh *token***')
    self.replace(Path('rendered.html'), '<strong>refresh_token</strong>',
                 '<strong>refresh <em>token</em></strong>')
    self.assertEqual(checks.check(self.root, rendered=Path('rendered.html'))['findings'], [])

  def test_rendered_focus_and_diagram_are_checked(self):
    self.rendered_fixture()
    self.assertEqual(checks.check(self.root, rendered=Path('rendered.html'))['findings'], [])
    self.replace(Path('rendered.html'), '<strong>token_type</strong>', '<span>token_type</span>')
    self.replace(Path('rendered.html'), 'alt="uc 01 01"', 'alt="different"')
    codes = {f['code'] for f in checks.check(self.root, rendered=Path('rendered.html'))['findings']}
    self.assertIn('rendered-focus', codes)
    self.assertIn('rendered-diagram', codes)

  def test_rendered_end_marker_inside_quoted_focus_preserves_wording_checks(self):
    original = (self.root / self.ta).read_text(encoding='utf-8')
    for marker, focus in (('**[<=]**"', '[⇐]'), ('**[<=]"**', '[⇐]"'),
                          ('**[<=]**', '[⇐]')):
      with self.subTest(marker=marker):
        self.write(self.ta, original.replace('**[<=]**"', marker))
        self.rendered_fixture()
        self.replace(Path('rendered.html'), '<strong>[⇐]</strong>',
                     '<strong>' + html.escape(focus) + '</strong>')
        self.assertEqual(checks.check(self.root, rendered=Path('rendered.html'))['findings'], [])
        self.replace(Path('rendered.html'), html.escape(focus) + '</strong>',
                     html.escape(focus + ' changed') + '</strong>')
        codes = {f['code'] for f in checks.check(self.root, rendered=Path('rendered.html'))['findings']}
        self.assertIn('rendered-focus', codes)

  def test_rendered_passthrough_preserves_literal_source_text(self):
    self.rendered_fixture()
    with (self.root / self.ta).open('a', encoding='utf-8') as stream:
      stream.write('\nFachlicher Akteur: **+++SM(C)-B+++**.\n')
    self.replace(Path('rendered.html'), '</section><section>',
                 '<strong>SM(C)-B</strong></section><section>')
    self.assertEqual(checks.check(self.root, rendered=Path('rendered.html'))['findings'], [])
    self.replace(Path('rendered.html'), '<strong>SM(C)-B</strong>', '<strong>SM©-B</strong>')
    report = checks.check(self.root, rendered=Path('rendered.html'))
    self.assertIn('rendered-focus', {f['code'] for f in report['findings']})

  def test_rendered_duplicate_anchor_fails(self):
    self.rendered_fixture()
    self.replace(Path('rendered.html'), '<body>', '<body><div id="'+TA+'"></div>')
    report = checks.check(self.root, rendered=Path('rendered.html'))
    self.assertIn('rendered-anchor', {f['code'] for f in report['findings']})

  def test_duplicate_diagram_is_rejected(self):
    self.replace(self.uc, '[mermaid,uc-01-01,svg]', '[mermaid,uc-01-01,svg]\n[mermaid,duplicate,svg]')
    self.assert_finding('diagram-count')

  def add_selected_requirement(self, aid):
    catalog = ET.fromstring(checks.render_catalog(self.workbook))
    requirement = next(r for r in catalog.findall('.//requirement') if r.attrib['id'] == aid)
    source = requirement.findtext('sourceDocumentId')
    body = checks.normalize(requirement.findtext('description'))
    afo = f'[{chr(35)}{aid}]\n==== {aid} - {requirement.findtext("title")}\n\n{body}\n'
    self.write(checks.AFO_ROOT / source / (aid + '.adoc'), afo)
    tid = 'TA_' + aid + '_01'
    aspect = checks.TA_ROOT / source / aid / (tid + '.adoc')
    quoted_body = checks.source_wording(body)
    self.write(aspect, f'[{chr(35)}{tid}]\n===== {tid}\n\n"**{quoted_body}**\n\n**[<=]**"\n')
    entry = Path('docs/asciidoc/Testplan_ZETA.adoc')
    self.write(entry, (self.root / entry).read_text(encoding='utf-8')
               + f'include::afos/{source}/{aid}.adoc[]\n'
               + f'include::testaspekte/{source}/{aid}/{tid}.adoc[]\n')
    return aspect

  def test_selected_requirement_is_discovered_without_manifest(self):
    aid = 'A_29853'
    aspect = self.add_selected_requirement(aid)
    with patch.object(checks, 'REQUIREMENT_SCOPE', frozenset({'A_29951', aid})):
      report = self.run_check()
      self.assertEqual(report['findings'], [])
      self.assertIn(aid, report['checked_requirement_ids'])
      self.assertIn(aspect.stem, report['checked_aspect_ids'])
      self.replace(aspect, 'MUSS', 'KANN')
      self.assert_finding('quotation')

  def test_unselected_afo_does_not_expand_source_scope(self):
    self.write(checks.AFO_ROOT / 'gemSpec_ZETA/A_26988.adoc', 'Incorrect source text.')
    report = self.run_check()
    self.assertEqual(report['findings'], [])
    self.assertNotIn('A_26988', report['checked_afo_ids'])

  def test_supporting_requirement_stays_outside_aspect_scope(self):
    catalog = ET.fromstring(checks.render_catalog(self.workbook))
    aid = 'A_26988'
    requirement = next(r for r in catalog.findall('.//requirement') if r.attrib['id'] == aid)
    source = requirement.findtext('sourceDocumentId')
    body = checks.normalize(requirement.findtext('description'))
    afo = f'[{chr(35)}{aid}]\n==== {aid} - {requirement.findtext("title")}\n\n{body}\n'
    active = checks.AFO_ROOT / source / (aid + '.adoc')
    self.write(active, afo)
    self.write(checks.TA_ROOT / source / aid / ('TA_' + aid + '_01.adoc'),
               'Not a valid quotation or aspect.\n')
    with patch.object(checks, 'SUPPORTING_REQUIREMENT_SCOPE', frozenset({aid})):
      report = self.run_check()
      self.assertEqual(report['findings'], [])
      self.assertIn(aid, report['checked_afo_ids'])
      self.assertNotIn(aid, report['checked_requirement_ids'])
      self.replace(active, body, 'Incorrect source text.')
      self.assert_finding('source-afo')

  def test_unselected_usecase_does_not_expand_structure_scope(self):
    other = checks.UC_ROOT / 'UserStory_99/UseCase_99/readme.adoc'
    self.write(other, '[#UC_99_99]\n== Unselected\n\n<<' + TA + '>>\n')
    report = self.run_check()
    self.assertEqual(report['findings'], [])
    self.assertNotIn('UC_99_99', report['checked_usecase_ids'])

  def test_obsolete_config_option_is_rejected(self):
    from contextlib import redirect_stderr
    with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
      checks.main(['--project-root', str(self.root), '--config', 'obsolete.json'])
    self.assertEqual(error.exception.code, 2)

  def test_no_manifest_is_required(self):
    self.assertFalse((self.root / checks.DEFAULT_XLSX.parent / 'verification').exists())
    self.assertEqual(self.run_check()['findings'], [])

  def test_current_assignments_are_discovered_without_expected_mappings(self):
    self.replace(self.uc, UC, 'UC_01_02')
    report = self.run_check()
    self.assertEqual(report['findings'], [])
    self.assertEqual(report['observed_assignments'][TA], ['UC_01_02'])
    self.assertEqual(report['assessment']['independent_validation'], 'not_performed')
    self.assertEqual(report['assessment']['coverage_completeness'], 'not_verified')

  def test_cli_success_and_invalid_input_always_state_validation_limits(self):
    for invalid in [False, True]:
      with self.subTest(invalid=invalid):
        self.write(self.catalog, b'invalid workbook' if invalid else self.workbook)
        output = io.StringIO()
        with redirect_stdout(output):
          code = checks.main(['--project-root', str(self.root)])
        report = json.loads((self.root / 'target/design-verification.json').read_text())
        self.assertEqual(code, int(invalid))
        self.assertEqual(report['assessment']['independent_validation'], 'not_performed')
        self.assertEqual(json.loads(output.getvalue())['assessment'], report['assessment'])

  def test_missing_workbook_cli_reports_failure_and_validation_limits(self):
    (self.root / self.catalog).unlink()
    with redirect_stdout(io.StringIO()):
      code = checks.main(['--project-root', str(self.root)])
    report = json.loads((self.root / 'target/design-verification.json').read_text())
    self.assertEqual(code, 1)
    self.assertEqual(report['findings'][0]['code'], 'invalid-input')
    self.assertEqual(report['assessment']['independent_validation'], 'not_performed')


class RepositoryDesignTest(unittest.TestCase):
  USECASES = [ROOT / checks.UC_ROOT / "UserStory_01" / f"UseCase_{number:02d}"
              for number in (4, 6, 7, 8, 9, 11, 12, 15, 16, 17, 22, 24, 27)] + [
                  ROOT / checks.UC_ROOT / "UserStory_03/UseCase_01"]

  @classmethod
  def setUpClass(cls):
    catalog = ET.fromstring(checks.render_catalog((ROOT / checks.DEFAULT_XLSX).read_bytes()))
    cls.requirements = {node.get("id"): node for node in catalog.findall(".//requirement")}

  def test_tagged_aspects_exist_under_their_current_requirement_and_usecase(self):
    catalog = {path.stem: path for path in (ROOT / checks.TA_ROOT).glob("*/*/TA_*.adoc")}
    for folder in self.USECASES:
      documented = set(re.findall(r"<<(TA_[\w-]+)", (folder / "readme.adoc").read_text(encoding="utf-8")))
      for feature in folder.glob("*.feature"):
        tags = re.findall(r"(?m)^\s*@(TA_[\w-]+)", feature.read_text(encoding="utf-8"))
        for tag in tags:
          with self.subTest(feature=feature, aspect=tag):
            self.assertIn(tag, catalog)
            self.assertIn(tag, documented)
            self.assertEqual(catalog[tag].parent.name, tag[3:].rsplit("_", 1)[0])

  def test_retired_ids_do_not_reappear_in_active_catalog_or_scenarios(self):
    retired = (
        "A_28782", "A_25340-01", "A_27867-01", "A_26489-01", "A_25661", "A_25752",
        "A_26477", "A_26972-02", "A_26973-01", "A_28525-01", "A_26586", "A_27802-02",
        "A_27264", "A_27379-01", "A_26561", "A_26560", "A_27727-01", "A_28527",
        "A_25665", "A_29851", "A_25748-02",
    )
    pattern = re.compile(r"(?<![\w-])(?:TA_)?(?:" + "|".join(map(re.escape, retired))
                         + r")(?:_\d+)?(?![\w-])")
    paths = list((ROOT / checks.AFO_ROOT).rglob("*.adoc"))
    paths += list((ROOT / checks.TA_ROOT).rglob("*.adoc"))
    paths += list((ROOT / checks.UC_ROOT).rglob("*.feature"))
    paths += list((ROOT / checks.UC_ROOT).rglob("*.adoc"))
    for path in paths:
      with self.subTest(path=path):
        text = path.read_text(encoding="utf-8")
        references = re.findall(
            r"(?m)^\[#[^\]]+\]|^[ \t]*@[^\n]+|^include::[^\n]+|<<[^>]+>>", text)
        active = "\n".join([path.relative_to(ROOT).as_posix(), *references])
        self.assertIsNone(pattern.search(active))

  def test_requirement_sources_match_the_cc_catalog(self):
    for aid in ("A_25661-01", "A_26477-01", "A_26489-02", "A_26586-01", "A_26972-03",
                "A_26973-02", "A_27802-03", "A_28525-02", "A_29676"):
      with self.subTest(afo=aid):
        requirement = self.requirements[aid]
        source = ROOT / checks.AFO_ROOT / requirement.findtext("sourceDocumentId") / f"{aid}.adoc"
        lines = source.read_text(encoding="utf-8").splitlines()
        self.assertGreaterEqual(len(lines), 4)
        self.assertEqual(lines[0], f"[#{aid}]")
        self.assertEqual(checks.normalize(lines[1]), checks.normalize(f"==== {aid} - {requirement.findtext('title')}"))
        self.assertEqual(lines[2].strip(), "")
        self.assertEqual(checks.normalize("\n".join(lines[3:])), checks.normalize(requirement.findtext("description")))

  def test_cc_runtime_schema_references_resolve_locally(self):
    resources = ROOT / "src/test/resources"
    seeds = {resources / match for folder in self.USECASES for path in folder.glob("*.feature")
             for match in re.findall(r'"(?:classpath:)?(schemas/v_2_0/[^"$#]+\.yaml)(?:#[^"]*)?"',
                                     path.read_text(encoding="utf-8"))}
    visited = set()

    def visit(path):
      path = path.resolve()
      if path in visited:
        return
      visited.add(path)
      self.assertTrue(path.is_relative_to(resources.resolve()), path)
      self.assertTrue(path.is_file(), path)

      def references(value):
        if isinstance(value, dict):
          for key, child in value.items():
            if key == "$ref" and not child.startswith("#"):
              self.assertNotIn("://", child, f"Runtime reference requires network: {child}")
              visit(path.parent / child.split("#")[0])
            else:
              references(child)
        elif isinstance(value, list):
          for child in value:
            references(child)
      references(yaml.safe_load(path.read_text(encoding="utf-8")))

    self.assertTrue(seeds)
    for seed in seeds:
      visit(seed)

  def test_error_and_routing_aspects_quote_their_cc_requirement(self):
    for aid in ("A_25661-01", "A_28525-02", "A_29676"):
      original = checks.source_wording(self.requirements[aid].findtext("description"))
      for aspect in (ROOT / checks.TA_ROOT / "gemSpec_ZETA" / aid).glob("TA_*.adoc"):
        with self.subTest(aspect=aspect):
          marked, _ = checks.quotation(aspect.read_text(encoding="utf-8"))
          self.assertIn("**", marked, "A TestAspect needs a visible test focus")
          self.assertEqual(checks.normalize(marked.replace("**", "")), original)

  def test_selected_repository_designs(self):
    report = checks.check(ROOT)
    self.assertEqual(report['findings'], [], json.dumps(report['findings'], ensure_ascii=False, indent=2))

  def test_monitoring_quotations_reject_changed_source_wording(self):
    read_text = Path.read_text
    for number in range(1, 7):
      tid = f'TA_A_27496_{number:02}'
      with self.subTest(aspect=tid):
        path = ROOT / checks.TA_ROOT / 'gemSpec_ZETA/A_27496' / f'{tid}.adoc'
        text = read_text(path, encoding='utf-8')
        self.assertIn('Informationen', text)
        malformed = text.replace('Informationen', 'Angaben', 1)

        def mutated_read_text(current, *args, **kwargs):
          return malformed if current == path else read_text(current, *args, **kwargs)

        with patch.object(Path, 'read_text', mutated_read_text):
          report = checks.check(ROOT)
        self.assertIn('A_27496', report['checked_requirement_ids'])
        self.assertIn(tid, report['checked_aspect_ids'])
        self.assertTrue(any(f['code'] == 'quotation' and f['target'] == tid
                            for f in report['findings']))

  def test_scoped_usecases_reject_missing_actor(self):
    read_text = Path.read_text
    for number in (12, 16, 17):
      with self.subTest(usecase=number):
        path = ROOT / checks.UC_ROOT / f'UserStory_01/UseCase_{number}/readme.adoc'
        text = read_text(path, encoding='utf-8')
        cells, _ = checks.fields(text)
        self.assertTrue(cells['Akteur'])
        malformed = text.replace(cells['Akteur'], '', 1)

        def mutated_read_text(current, *args, **kwargs):
          return malformed if current == path else read_text(current, *args, **kwargs)

        with patch.object(Path, 'read_text', mutated_read_text):
          report = checks.check(ROOT)
        self.assertTrue(any(f['code'] == 'usecase-fields'
                            and f['target'] == f'UC_01_{number}'
                            and 'Akteur' in f['detail']['missing']
                            for f in report['findings']))


if __name__ == '__main__':
  unittest.main()
