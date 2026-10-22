# Encave for iPhone, iPad and Mac

SwiftUI multiplatform app. The cellar logic lives in the `CaveKit` Swift
package, which has no UI code and is tested on Linux and macOS.

```bash
brew install xcodegen
cd apple
xcodegen generate          # creates Encave.xcodeproj (not committed)
open Encave.xcodeproj

swift test --package-path Packages/CaveKit   # model tests, any platform
```

Colors and the app icon are generated from `design/`:

```bash
python3 design/generate_apple_assets.py
python3 design/generate_app_icon.py
```

## Builds

`.github/workflows/apple.yml` builds both apps on every change under
`apple/`, and uploads them to TestFlight from `main`, for the internal testers.
Once the full screen tour of that run has passed, the build also goes to every
external TestFlight group of the app, with the latest commits as "What to
Test", and is submitted to Beta App Review (`tools/testflight_testers.py`).
A manual run on `main` with "external testers" ticked does the same. To give a
build already in TestFlight to the external testers, run "TestFlight external
build" with its number. Apple reviews one build per version at a time: a build
added while another is in review waits in the group until it is submitted
again. It needs these repository secrets:
`APPLE_TEAM_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY` (App
Store Connect API key with the Admin role).
