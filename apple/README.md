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

The workflow uses two macOS jobs per run on main, pull requests and manual
runs, and one on pushes to other branches:

- **Build, Mac launch test, TestFlight upload**: on main (pushes and manual
  runs), archives and uploads both apps, then launches the archived Mac app
  (twice empty, then each section with the sample cellar) and reports crashes.
  On other branches (manual runs included: they never upload) and pull
  requests, builds the Mac app in Release for the same launch test, and the
  iPhone and iPad app for the simulator while the Mac app runs. Every build
  checks the privacy manifest. On main, pull requests and manual runs, the same
  Mac app then makes the Mac screenshots for the App Store
  (`tools/mac-screenshots.sh`, artifact `mac-screens`).
- **Screen tour (iPhone, iPad, Mac)** (main, pull requests and manual runs):
  builds the UI tests once, runs the model tests on macOS, starts the newest
  iPhone and iPad simulators and runs the tour on both side by side, English
  then French (with the large text checks on the iPhone), then the Mac tour
  (artifact `screens`, one folder per device). On GitHub's runners an iPhone
  and an iPad run together as fast as one; two iPads, two iPhones or three
  simulators time out, and each extra job would spend about 20 minutes starting
  its simulators. The external testers get a build of main once the tour has
  passed. Runs on main are never cancelled by a later push.

The same split runs locally, for example the French iPhone tour only:

```bash
xcodebuild build-for-testing -project Encave.xcodeproj -scheme Encave \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO
xcodebuild test-without-building -xctestrun build/Build/Products/*.xctestrun \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:EncaveUITests/ScreenTourTests/testTourInFrench
```
