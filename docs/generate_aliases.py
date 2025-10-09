import os


def make_alias_name(filename, parent_folder):
  """
  Erzeugt einen sprechenden Alias-Namen.
  Falls der Dateiname generisch ist (z.B. readme.adoc),
  wird der übergeordnete Ordner als Präfix genutzt.
  """
  name = filename.replace(".adoc", "")
  if name.lower() == "readme":
    return f"{parent_folder}_{name}"
  return name


def main():
  # Skript liegt in docs → eine Ebene hoch, um Projekt-Root zu bekommen
  script_dir = os.path.dirname(os.path.abspath(__file__))
  base_dir = os.path.abspath(os.path.join(script_dir, ".."))

  output_file = os.path.join(script_dir, "aliases.adoc")

  with open(output_file, "w", encoding="utf-8") as out:
 	out.write("include::../docs/afos/readme_aliases.adoc[]\n")
    out.write(
      "// Automatisch generierte Aliase zu allen .adoc-Dateien in src/ und docs/\n")
   
    out.write("// Dieses File wird vom Skript generate_aliases.py erzeugt.\n\n")

    for root, _, files in os.walk(base_dir):
      rel_root = os.path.relpath(root, base_dir)

      # Nur docs/ und src/ zulassen, target/ komplett ausschließen
      if not (rel_root.startswith("docs") or rel_root.startswith("src")):
        continue
      if "target" in rel_root.split(os.sep):
        continue

      for filename in sorted(files):
        if filename.endswith(".adoc") and filename != "aliases.adoc":
          full_path = os.path.join(root, filename)
          rel_path = os.path.relpath(full_path, os.path.dirname(output_file))
          parent_folder = os.path.basename(os.path.dirname(full_path))
          alias_name = make_alias_name(filename, parent_folder)

          # Alias für xref-Links
          out.write(f":{alias_name}: xref:{rel_path}[{alias_name}]\n")

          # Alias für include-Nutzung
          out.write(f":{alias_name}-include: {rel_path}\n")

  print(f"aliases.adoc wurde erstellt unter {output_file}")


if __name__ == "__main__":
  main()
