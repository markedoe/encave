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

Every user-facing string goes in `apple/Encave/Resources/Localizable.xcstrings`
with English and French.

## Continuous integration

| Workflow | What it does |
|----------|--------------|
| `ci.yml` | Secret scanning, public-safety and commit message checks |
| `apple.yml` | Model tests on Linux and macOS, builds both apps; on push, uploads to TestFlight |
| `security.yml` | Weekly full-history secret scan |

TestFlight uploads need these repository secrets: `APPLE_TEAM_ID`,
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY` (an App Store Connect API
key with the Admin role).
