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
Once the full screen tour of that run (or of an earlier run of the same files)
and the Mac launch test have passed, the `testers` job gives the build to every
external TestFlight group of the app, with the latest commits as "What to
Test", and submits it to Beta App Review (`tools/testflight_testers.py`).
A manual run on `main` with "external testers" ticked does the same. To give a
build already in TestFlight to the external testers, run "TestFlight external
build" with its number: it checks that the tour of the run that made the build
passed, unless "force" is ticked. Apple reviews one build per version at a time: a build
added while another is in review waits in the group until it is submitted
again. It needs these repository secrets:
`APPLE_TEAM_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY` (App
Store Connect API key with the Admin role).

Jobs of the workflow:

- **CaveKit tests (Linux)** and **French translations**: every run, first.
- **Build, Mac launch test, TestFlight upload** (`apps`, macOS): on main
  (pushes and manual runs), archives and uploads both apps, then launches the
  archived Mac app (twice empty, then each section with the sample cellar) and
  reports crashes. On other branches (manual runs included: they never upload)
  and pull requests, builds the Mac app in Release for the same launch test,
  and the iPhone and iPad app for the simulator while the Mac app runs. Every
  build checks the privacy manifest. On main, pull requests and manual runs,
  the same Mac app then makes the Mac screenshots for the App Store
  (`tools/mac-screenshots.sh`, artifact `mac-screens`). Pushes to other
  branches run only this macOS job.
- **Tour already passed on this commit?** (`tour-check`): on main, looks for
  an earlier run whose head commit has the same git tree (a pull request
  merged as it was toured) and whose tour passed; the tour is then skipped and
  the log names that run.
- **Screen tour** (main, pull requests and manual runs), four parts, one
  simulator each: iPad English (with the model tests on macOS), iPad French,
  iPhone English (with the large text checks) and iPhone French (with the
  studio pictures). Three run at a time, so that the `apps` job always finds a
  macOS runner; the shortest part runs last. Each part builds the UI tests,
  boots the newest iPhone or iPad simulator and runs its tests; a failed build
  stops the part at once. On GitHub's runners two simulators on one runner slow
  each other and three fail to launch the app. Measured: 28 to 43 minutes a
  part. "Re-run failed jobs" re-runs only the parts that failed, and
  **Screenshots in one artifact** merges the four into `screens` (one folder
  per device). The Mac UI tests do not run in CI (GitHub's Mac runners show
  no windows to them): the launch test and the Mac screenshots cover the Mac.
- **TestFlight external testers** (`testers`): see above. Also when one upload
  failed: the other platform's build still goes, and the run stays red.

Runs on main are never cancelled by a later push.

The same split runs locally, for example the French iPhone tour only:

```bash
xcodebuild build-for-testing -project Encave.xcodeproj -scheme Encave \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO
xcodebuild test-without-building -xctestrun build/Build/Products/*.xctestrun \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:EncaveUITests/ScreenTourTests/testTourInFrench
```
