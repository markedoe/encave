# Development

## Apple app (iPhone, iPad, Mac)

Requirements: macOS with the latest Xcode, and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
brew install xcodegen
cd apple
xcodegen generate        # creates Encave.xcodeproj (not committed)
open Encave.xcodeproj
```

Schemes: `Encave` (iPhone and iPad) and `EncaveMac` (macOS).

### Model tests

The cellar logic lives in `apple/Packages/CaveKit`, which has no UI code and
runs anywhere Swift 6 runs (macOS or Linux):

```bash
swift test --package-path apple/Packages/CaveKit
```

### Colors and icon

Colors come from `design/tokens.json`. After editing tokens or the icon:

```bash
python3 design/generate_apple_assets.py
python3 design/generate_app_icon.py     # needs Pillow
```

Never hardcode colors in Swift; use the asset catalog names.

### Localization

English is the source language; French translations live in
`apple/tools/l10n.py`, which writes the String Catalogs:

```bash
python3 apple/tools/l10n.py           # regenerate Localizable and InfoPlist catalogs
python3 apple/tools/l10n.py --check   # fails if a string has no French (run in CI)
```

Avoid building strings with `? "a" : "b"` inside `Text`: write two `Text`
views so both strings are extracted and translated.

### AI label recognition (optional)

Off by default. In Settings, choose Claude (Anthropic API key) or an
OpenAI-compatible server (OpenAI, or OpenWebUI/Ollama on your network) with a
model that accepts images. The key is stored in the Keychain; only the label
photo is sent. Never put a key in the code, the scheme or a test.

## Android app (phones, tablets, foldables)

Requirements: JDK 17 or newer and the Android SDK (Android Studio, or the
command-line tools with `platforms;android-37.0`). Point Gradle at the SDK
with `ANDROID_HOME` or an untracked `android/local.properties`.

```bash
cd android
./gradlew :core:test                 # cellar model (pure Kotlin, JDK only)
./gradlew :app:testDebugUnitTest     # tokens, repository and Robolectric smoke tests
./gradlew :app:lintDebug :app:assembleDebug
```

- `android/core` is the Kotlin port of CaveKit. Keep both in step: the JSON
  backup is shared, and `InteropTest` reads fixtures exported by CaveKit
  (`android/core/src/test/resources`).
- Colors: `ui/theme/DesignTokens.kt` mirrors `design/tokens.json`;
  `DesignTokensTest` fails if they differ. Never hardcode colors elsewhere.
- Strings: edit the English/French table in `android/tools/strings.py`, then
  run `python3 android/tools/strings.py` to write both `strings.xml` files.
- AI label recognition works as on Apple; the key is encrypted with the
  Android Keystore and excluded from backups.
- `ScreenTourTest` opens every main screen with the sample cellar on a phone
  and a tablet, in English and French, saves screenshots to
  `android/app/build/screens` (never commit them) and checks the layout:
  centered `action.*` buttons, equal side-by-side buttons, racks that fit.

## Continuous integration

| Workflow | What it does |
|----------|--------------|
| `ci.yml` | Secret scanning, public-safety and commit message checks |
| `apple.yml` | Model tests on Linux and macOS, builds both apps; on push, uploads to TestFlight |
| `android.yml` | Core and app unit tests, lint, debug APK (artifacts `encave-debug-apk`, `android-screens`) |
| `security.yml` | Weekly full-history secret scan |

TestFlight uploads need these repository secrets: `APPLE_TEAM_ID`,
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY` (an App Store Connect API
key with the Admin role).
