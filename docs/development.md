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
- Sync (docs/SYNC.md) is ported in `android/core/.../core/sync` (merge, pairing,
  sessions, remote rounds, relay client; Bouncy Castle for X25519 and
  ChaCha20-Poly1305) and wired up in `android/app/.../sync` (NSD discovery, TCP
  framing, Keystore-encrypted identity, WorkManager relay rounds).
  `ProtocolVectorTests` must reproduce the published vectors. With a relay
  running (`PORT=8787 node relay/server.mjs`), `ENCAVE_RELAY_URL=http://localhost:8787
  ./gradlew :core:test` also syncs two devices through it, as CI does.
- Home screen widgets (Jetpack Glance) are in `android/app/.../widget`. They
  read `files/widget-snapshot.json`, a summary (`WidgetSnapshot` in core, as in
  CaveKit) that the app rewrites after each saved or synced change; the cellar
  is loaded only when it is missing or from last year. `WidgetContentTest` renders them with `glance-appwidget-testing`.
- `ScreenTourTest` opens every main screen with the sample cellar on a phone
  and a tablet, in English and French, saves screenshots to
  `android/app/build/screens` (never commit them) and checks the layout:
  centered `action.*` buttons, equal side-by-side buttons, racks that fit.

## Sync

The protocol is specified in [SYNC.md](SYNC.md); the Swift implementation is in
CaveKit (`SyncState.swift`, `SyncProtocol.swift`, `RemoteSync.swift`,
`RelayMailbox.swift`) and the app layer in `apple/Encave/Services/Sync/`.

- **Tests:** `swift test --package-path apple/Packages/CaveKit` covers merges,
  pairing, handshakes, remote sync and the published test vectors.
- **Relay:** `node --test relay/test/relay.test.mjs relay/test/worker.test.mjs`
  (Node server and Cloudflare Worker). To sync through a running
  relay, start `node relay/server.mjs` and run the CaveKit tests with
  `ENCAVE_RELAY_URL=http://localhost:8787`.
- **Two devices:** pairing needs two devices (or a device and a simulator) on
  the same network. The simulator cannot scan QR codes; type the code instead.

### Turning on iCloud sync (one-time setup)

The app can sync away from home through the user's own iCloud (CloudKit private
database, no server of ours). The option stays hidden until the build declares
a container:

1. In the Apple Developer account, create the iCloud container
   `iCloud.app.encave` and enable iCloud (CloudKit) for the `app.encave` App ID.
2. In `apple/project.yml`:
   - set `ENCAVE_CLOUDKIT_CONTAINER: iCloud.app.encave`;
   - add to the entitlements of both app targets:

     ```yaml
     com.apple.developer.icloud-container-identifiers: [iCloud.app.encave]
     com.apple.developer.icloud-services: [CloudKit]
     ```

     The iOS target needs an `entitlements:` block like the Mac one.
3. After the first sync from a development build, open the CloudKit Console and
   deploy the schema (record type `EncaveFile` with an Asset field `data`) to
   Production, so TestFlight and App Store builds can use it.

## Beta and store builds

Beta builds show the beta notices (Settings › About, the help) and offer the sample
cellar (on the empty Cellar and in Settings › Try Encave). A store build turns them
off without code changes:

- **Apple:** the `ENCAVE_CHANNEL` build setting in `apple/project.yml`, read from the
  `EncaveChannel` Info.plist key (`AppChannel.swift`). It is `beta` by default and for
  the TestFlight archives of `apple.yml`; build with `ENCAVE_CHANNEL=store` for the App
  Store.
- **Android:** `BuildConfig.BETA`, always true in debug builds; release builds follow
  the `ENCAVE_BETA` environment variable (true unless set to `false`).

## Continuous integration

| Workflow | What it does |
|----------|--------------|
| `ci.yml` | Secret scanning, public-safety and commit message checks (pull requests, pushes to `main` and `claude/**`) |
| `apple.yml` | CaveKit tests on Linux, French strings check, Mac launch test with screenshots, screen tour on iPhone, iPad and Mac (artifact `screens`; on main, pull requests and manual runs only), builds both apps; on a push to main (or a manual run), uploads to TestFlight and gives the build to the external testers |
| `relay.yml` | Relay tests (Node server and Cloudflare Worker), and two simulated devices syncing through a running relay |
| `android.yml` | Core and app unit tests, lint, debug APK (artifacts `encave-debug-apk`, `android-screens`) |
| `security.yml` | Weekly full-history secret and public-safety scan, CodeQL on the workflows |

TestFlight uploads need these repository secrets: `APPLE_TEAM_ID`,
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY` (an App Store Connect API
key with the Admin role).
