# Licence texts

The standard texts of the licences of the apps' dependencies, used by the
scripts that write the acknowledgements shown in Settings › About › Licences:

- `python3 apple/tools/licences.py` writes `apple/Encave/Resources/Licences.json`
  from `apple/Packages/CaveKit/Package.resolved`.
- `python3 android/tools/licences.py` writes
  `android/app/src/main/res/raw/licences.json` from the release runtime
  classpath of the Android app.

Run them after changing a dependency; `--check` fails when a file is out of
date or a dependency has no licence entry. Encave's own licence is `LICENSE`
at the root of the repository.
