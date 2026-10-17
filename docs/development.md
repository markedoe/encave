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

## Continuous integration

| Workflow | What it does |
|----------|--------------|
| `ci.yml` | Secret scanning, public-safety and commit message checks |
| `apple.yml` | Model tests on Linux and macOS, builds both apps; on push, uploads to TestFlight |
| `security.yml` | Weekly full-history secret scan |

TestFlight uploads need these repository secrets: `APPLE_TEAM_ID`,
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY` (an App Store Connect API
key with the Admin role).
