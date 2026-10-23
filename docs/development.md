# Development

## Apple app (iPhone, iPad, Mac)

Requirements: macOS with the latest Xcode, and [XcodeGen](https://github.com/yonaskolb/XcodeGen).
The app runs on iOS and iPadOS 18 and macOS 15 or later; **Ask your cellar**
and **Suggest Details** need iOS, iPadOS or macOS 26 with Apple Intelligence.

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

The French is written with plain spaces; the scripts put a narrow no-break space
before ; ! ? and inside « », and a no-break space before :. Both `--check`
commands (this one and Android's `strings.py --check`, run in CI) also fail when
a help text is left in English, and when a **bold** word of the help (a button,
setting or screen, each step of "Settings › Your data") is no longer a text the
app shows, in English or in French. Place names in the help, such as
"Shelf 2, place 3", must match a place pattern of the app; services, file names
and examples go in `HELP_NAMES`.

Avoid building strings with `? "a" : "b"` inside `Text`: write two `Text`
views so both strings are extracted and translated.

### Licences and privacy manifest

Settings › About › Licences lists the libraries in each app with their licence
(see `licences/README.md`). After changing a dependency, run
`python3 apple/tools/licences.py` or `python3 android/tools/licences.py`; CI
fails with `--check` while the list is out of date. The Apple privacy manifest
is `apple/Encave/Resources/PrivacyInfo.xcprivacy`: declare there any new
required-reason API the code starts using (UserDefaults, file timestamps, disk
space, system boot time, active keyboards).

### AI label recognition (optional)

Off by default. In Settings, choose a cloud provider with your own API key
(ChatGPT, Claude, Gemini, Grok, Mistral, DeepSeek, Qwen, Kimi, GLM, MiniMax,
OpenRouter) or a server on your network (Ollama, vLLM, LM Studio or any
OpenAI-compatible server, no key needed), with a model that reads images. The key is stored in the Keychain; only the label
photo is sent. Never put a key in the code, the scheme or a test.

## Android app (phones, tablets, foldables)

Requirements: JDK 17 or newer and the Android SDK (Android Studio, or the
command-line tools with `platforms;android-37.0`). The app runs on Android 15
(API 35) or later. Point Gradle at the SDK
with `ANDROID_HOME` or an untracked `android/local.properties`.

```bash
cd android
./gradlew :core:test                 # cellar model (pure Kotlin, JDK only)
./gradlew :app:testDebugUnitTest     # tokens, repository and Robolectric smoke tests
./gradlew :app:lintDebug :app:assembleDebug
```

- `android/core` is the Kotlin port of CaveKit. Keep both in step: the JSON
  backup is shared, and the Kotlin tests read fixtures written by CaveKit:
  `fixtures/interop` and `fixtures/search` (shared by both) and
  `android/core/src/test/resources`. After a model change, regenerate them with
  `ENCAVE_WRITE_FIXTURES=1 swift test --package-path apple/Packages/CaveKit`,
  then run the Kotlin tests.
- Colors: `ui/theme/DesignTokens.kt` mirrors `design/tokens.json`;
  `DesignTokensTest` fails if they differ. Never hardcode colors elsewhere.
- Strings: edit the English/French table in `android/tools/strings.py`, then
  run `python3 android/tools/strings.py` to write both `strings.xml` files.
- AI label recognition works as on Apple; the key is encrypted with the
  Android Keystore and excluded from backups.
- Sync (docs/SYNC.md) is ported in `android/core/.../core/sync` (merge, pairing,
  sessions, remote rounds, relay and WebDAV clients; the system's X25519
  (XDH) and ChaCha20-Poly1305, Conscrypt on Android and the JDK in tests, see
  [ENCRYPTION.md](ENCRYPTION.md)) and wired up in `android/app/.../sync` (NSD discovery, TCP
  framing, Keystore-encrypted identity, WorkManager relay rounds).
  `ProtocolVectorTests` must reproduce the published vectors. With a relay
  running (`PORT=8787 node relay/server.mjs`), `ENCAVE_RELAY_URL=http://localhost:8787
  ./gradlew :core:test` also syncs two devices through it, as CI does.
- Home screen widgets (Jetpack Glance) are in `android/app/.../widget`. They
  read `files/widget-snapshot.json`, a summary (`WidgetSnapshot` in core; the Apple
  app has no widgets yet) that the app rewrites after each saved or synced change; the cellar
  is loaded only when it is missing or from last year. `WidgetContentTest` renders them with `glance-appwidget-testing`.
- `ScreenTourTest` opens every main screen with the sample cellar on a phone
  and a tablet, in English and French, and on a phone in dark mode, saves screenshots to
  `android/app/build/screens` (never commit them) and checks the layout:
  centered `action.*` buttons, equal side-by-side buttons, racks that fit.

### Android release

The release build is shrunk and obfuscated with R8 (keep rules in
`android/app/proguard-rules.pro`) and must pass `:app:lintRelease`.

- **Version:** `versionName` is read from `MARKETING_VERSION` in
  `apple/project.yml`, so both apps share one version. `versionCode` comes from
  the `BUILD_NUMBER` environment variable (1 when absent); CI sets it as
  `apple.yml` does: `1000 + run number * 10 + attempt`.
- **Signing:** only from the environment: `ENCAVE_KEYSTORE_PATH`,
  `ENCAVE_KEYSTORE_PASSWORD`, `ENCAVE_KEY_ALIAS` and `ENCAVE_KEY_PASSWORD`.
  Without all four the release is unsigned (never signed with the debug key).
- **CI:** the `release` job of `android.yml` (pushes to `main` and manual runs)
  runs `:app:lintRelease :app:bundleRelease` and uploads the bundle
  (`encave-release-aab`) and the R8 mapping (`encave-release-mapping`, needed
  to read crash stack traces). It signs when these repository secrets are set:
  `ENCAVE_KEYSTORE_BASE64`, `ENCAVE_KEYSTORE_PASSWORD`, `ENCAVE_KEY_ALIAS` and
  `ENCAVE_KEY_PASSWORD`. Nothing is uploaded to Google Play.

Create the upload keystore once, outside the repository, and keep it and its
passwords in a password manager (never commit it):

```bash
keytool -genkeypair -v -keystore encave-upload.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 encave-upload.jks   # value of ENCAVE_KEYSTORE_BASE64 (macOS: base64 -i encave-upload.jks)
```

With Play App Signing (the default for new apps), Google keeps the app signing
key and this keystore is only the upload key: if it is lost, the upload key can
be reset from the Play Console. Build a signed bundle locally with:

```bash
cd android
BUILD_NUMBER=2 ENCAVE_KEYSTORE_PATH=path/to/encave-upload.jks ENCAVE_KEY_ALIAS=upload \
  ENCAVE_KEYSTORE_PASSWORD=... ENCAVE_KEY_PASSWORD=... ./gradlew :app:lintRelease :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab and app/build/outputs/mapping/release/mapping.txt
```

## Sync

The protocol is specified in [SYNC.md](SYNC.md); the Swift implementation is in
CaveKit (`SyncState.swift`, `SyncProtocol.swift`, `RemoteSync.swift`,
`RelayMailbox.swift`, `WebDAVMailbox.swift`) and the app layer in
`apple/Encave/Services/Sync/` (with `CloudKitMailboxStore.swift` for iCloud).

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
| `apple.yml` | Every run: CaveKit tests on Linux and the French strings check. A macOS job builds both apps, checks they bundle the privacy manifest and runs the Mac launch test; a branch push runs only this macOS job. On main, pull requests and manual runs (on any branch), the same job also makes the Mac screenshots for the App Store, 2880x1800 in English and French, light and dark (artifact `mac-screens`, made by `apple/tools/mac-screenshots.sh`), and another job runs the model tests on macOS and the screen tour on iPhone and iPad, then on the Mac (artifact `screens`; see `apple/README.md`). Uploads to TestFlight only from main (a push or a manual run), when the App Store Connect secrets are set (never on forks); branch pushes, manual runs on a branch and pull requests never upload. An uploaded build reaches the internal testers; the `testers` job then gives it to the external testers and submits it to Beta App Review once the screen tour of the same run has passed: for every push to main, and for a manual run on main with "external testers" ticked |
| `relay.yml` | Relay tests (Node server and Cloudflare Worker), and two simulated devices syncing through a running relay |
| `android.yml` | Core and app unit tests, lint, debug APK (artifacts `encave-debug-apk`, `android-screens`); on a push to main (or a manual run), release lint and bundle, signed when the secrets are set (artifacts `encave-release-aab`, `encave-release-mapping`). Nothing is uploaded to Google Play |
| `security.yml` | Weekly full-history secret and public-safety scan, CodeQL on the workflows |
| `play-status.yml` | Manual, read-only, on `main`: checks that the repository reaches Google Play (Workload Identity Federation, variables `PLAY_WIF_PROVIDER` and `PLAY_SERVICE_ACCOUNT`) and lists the testing tracks, their releases and the store listing languages |
| `testflight-status.yml` | Manual, read-only: the latest TestFlight builds, which external groups have them and where Beta App Review stands |
| `testflight-external.yml` | Manual: gives a build already in TestFlight (its build number) to every external group and submits it to Beta App Review, with an optional "What to Test"; builds nothing |
| `appstore-assets.yml` | Manual "App Store header image": `inspect` reads App Store Connect; `upload` places `docs/store/header-*.png` as the product page header and the search results asset of the version being prepared, in French and English (see [STORE.md](STORE.md)) |
| `appstore-info.yml` | Manual "App Store listing": `inspect` reads the listing; `apply` sets what `docs/store/appstore.json` gives (privacy policy URL, age rating, names, subtitles and texts per language) and attaches its `build` to the iOS version being prepared |

None of these workflows submits anything for App Store review.

TestFlight uploads need these repository secrets: `APPLE_TEAM_ID`,
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_PRIVATE_KEY` (an App Store Connect API
key with the Admin role).
