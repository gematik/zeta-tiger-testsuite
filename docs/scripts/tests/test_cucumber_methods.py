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

"""Regression checks for real glue annotation shapes used by the migrated scenarios."""
from pathlib import Path

from testsuite_docs.generate_cucumber_methods import extract_project_glue
from testsuite_docs.generate_cucumber_methods import decode_java_string


def test_single_line_javadoc_does_not_hide_this_or_following_steps(tmp_path: Path):
  (tmp_path / 'Glue.java').write_text('''
public class Glue {
  /** First assertion. Additional detail. */
  @Dann("prüfe den ursprünglichen Request")
  public void first() {}
  /** Second assertion. */
  @Dann("prüfe auch Wiederholungen")
  public void second() {}
}
''', encoding='utf-8')
  entries = extract_project_glue(tmp_path)
  assert [(e.german, e.description) for e in entries] == [
      ('prüfe den ursprünglichen Request', 'First assertion.'),
      ('prüfe auch Wiederholungen', 'Second assertion.')]


def test_concatenated_annotations_preserve_complete_phrase_and_escaped_quotes(tmp_path: Path):
  (tmp_path / 'Glue.java').write_text(r'''
public class Glue {
  /** Checks the whole request history. */
  @Dann("alle Requests (einschließlich Wiederholungen) an "
      + "{tigerResolvedString} haben Fehler \"ungültig\"")
  @Then(
      "all requests to "
      + "{tigerResolvedString} fail")
  public void check(String path)
      throws Exception {}
  @Override
  public String toString() { return "not a step"; }
}
''', encoding='utf-8')
  entries = extract_project_glue(tmp_path)
  assert len(entries) == 1
  assert entries[0].german == ('alle Requests (einschließlich Wiederholungen) an '
                                '{tigerResolvedString} haben Fehler "ungültig"')
  assert entries[0].english == 'all requests to {tigerResolvedString} fail'


def test_nonliteral_annotation_is_not_reported_as_a_partial_step(tmp_path: Path):
  (tmp_path / 'Glue.java').write_text('''
public class Glue {
  @Dann("incomplete " + CONSTANT)
  public void unknown() {}
  @Given("independent complete step")
  public void known() {}
}
''', encoding='utf-8')
  entries = extract_project_glue(tmp_path)
  assert len(entries) == 1
  assert entries[0].english == 'independent complete step'


def test_java_escapes_do_not_corrupt_regex_backslashes_or_unicode():
  assert decode_java_string(r'"regex \\s+ and \\Qliteral\\E"') == r'regex \s+ and \Qliteral\E'
  assert decode_java_string(r'"Unicode: \u00fc, space:\s, quote:\""') == 'Unicode: ü, space: , quote:"'
  assert decode_java_string(r'"apostrophe:\\s\'"') == "apostrophe:\\s'"
