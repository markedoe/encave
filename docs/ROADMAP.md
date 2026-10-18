# Roadmap

Encave is a set of **local-first native apps**:
your cellar lives on your devices, works offline (cellars rarely have signal),
and syncs end-to-end encrypted between your devices and the people you share
it with.

## Principles

1. **Your data stays yours.** It is stored on your devices; anything that
   leaves them is end-to-end encrypted. No server can read your cellar.
2. **Native on every platform.** SwiftUI for iPhone, iPad and Mac;
   Jetpack Compose for Android. Each app follows its platform's conventions.
3. **Fast core actions.** Adding a bottle takes under 10 seconds; finding or
   drinking one takes two taps.
4. **Accessible and bilingual** from day one (VoiceOver/TalkBack,
   Dynamic Type, full light and dark mode, French and English).

## Platforms

| Platform | Technology | Notes |
|----------|------------|-------|
| iPhone, iPad | SwiftUI | One multiplatform Xcode project |
| **Mac** | SwiftUI (native macOS target, not Catalyst) | Menu bar commands, keyboard shortcuts, multiple windows, sortable tables |
| Android phones, tablets, foldables | Kotlin + Jetpack Compose, Material 3 | Adaptive layouts, dynamic color |

## Architecture

```
 iPhone / iPad / Mac (SwiftUI)              Android (Compose)
   CaveKit (Swift package)                  core (Kotlin module)
   model, merge, sync protocol      ⇄       same model; protocol port in
                                            progress, checked with shared
                                            test vectors
            │  same Wi-Fi: Bonjour, direct, encrypted  │
            └──────────────────────┬───────────────────┘
                                   │  away from home: encrypted files only
                          ┌────────▼────────┐
                          │  Storage         │  relay (self-hosted, Node or
                          │  (never has keys)│  Cloudflare); iCloud (built, off
                          └─────────────────┘  until the container is set up);
                                               later Google Drive
```

- **One model, two native implementations.**
  - The cellar model is written in Swift (CaveKit, tested on Linux) and in
    Kotlin (`android/core`). They read each other's files (interop tests on
    fixtures).
  - The sync protocol is in CaveKit. The Kotlin port, checked against the test
    vectors in [SYNC.md](SYNC.md), is in progress.
  - A shared Rust core was the first plan. Two small, well-tested
    implementations proved simpler than UniFFI bindings for a model of this
    size; the decision can be revisited if the model grows.
- **Merge without a CRDT library.**
  - Wines, bottles, racks and discoveries are objects keyed by UUID, each with a
    Lamport stamp: the latest change wins, deletions included.
  - A deterministic repair step fixes what a merge can leave inconsistent: two
    bottles in one place, a bottle in a removed rack.
  - Each bottle is its own object with a status, so two people drinking "the last
    bottle" never produce a wrong count.
- **Photos** are content-addressed (named after their SHA-256), encrypted
  when they leave the device.
- **Sync without servers first.**
  - Devices on the same network sync directly.
  - Apart, they exchange encrypted files through a storage that never has a key.
    [REMOTE_SYNC.md](REMOTE_SYNC.md) compares the options: CloudKit for Apple
    devices, Google Drive across platforms, and a relay anyone can host for
    mixed households.
- **Encryption.**
  - X25519 identity keys per device (Keychain, Android Keystore-protected
    storage).
  - Sessions follow the Noise KK pattern with ChaCha20-Poly1305.
  - Remote files are encrypted with a cellar key that changes when a device is
    removed.

### Pairing and sharing (QR code or 6-digit code)

**Built (iPhone, iPad, Mac; Android in progress):**

1. Device A (Settings › Devices › Add a Device) shows a QR code and a 6-digit code,
   valid two minutes, for one device.
2. Device B scans the QR code or types the code. They find each other on the
   network (Bonjour), run a commit-then-reveal key exchange, and both show the
   same 6-digit confirmation code. The users compare them, which stops a device
   in the middle.
3. B joins A's cellar (combining or replacing its own bottles); from then on,
   changes sync automatically when the devices are on the same Wi-Fi.
4. Away from home: once a relay is set on one device, the key and address reach
   the others at their next meeting, and they sync through encrypted files.
5. **Removing a device** removes its identity key from the cellar (it can no
   longer connect) and rotates the key of the remote files (it can no longer
   read anything new). It keeps what it already had.

The full protocol is in [SYNC.md](SYNC.md).

**Planned:**

- **Household sharing** ("Share cellar", *can edit* or *view only*): the same
  pairing with roles in the device list, and files signed by each device so
  view-only members cannot write.
- **Remote pairing with a 6-digit code** (a phone far away): the code becomes
  the password of a PAKE (CPace or SPAKE2, as in Magic Wormhole) through a
  relay rendezvous, so someone watching the relay cannot guess it offline and
  gets one try before the code is burned.
- **Losing a phone:** with a second paired device nothing is lost. Single-device
  users export an encrypted backup (Files, iCloud Drive, Google Drive).

## User experience

### Information architecture

| iPhone and iPad (tab bar) | Mac (sidebar) | Android |
|------------------|------------------------|---------|
| **Cellar**: all bottles, searchable | Library: Cellar, Tonight, Journal | Navigation bar on phones, |
| **Racks**: physical layout | Racks: one row per rack, and Add a Rack | navigation rail on tablets, |
| **Tonight**: ready to drink | | list-detail on foldables |
| **Journal**: history and notes | | |

On iPad, the tab bar can turn into a sidebar. Settings open as a sheet (iPhone)
or a Settings window (Mac); devices and sync are in Settings. The interface
talks about **bottles**; the underlying "wine" record is an invisible
grouping ("Château X 2015 ×3").

### Core journeys (MVP)

1. **Add a bottle by scanning the label:** live camera, automatic capture,
   on-device text recognition, optional AI refinement with your own key,
   then one confirmation sheet (quantity, suggested rack slot). Extra details
   are collapsed. Undo is always available.
2. **Find a bottle:** search shows the rack position in each row; "Show in
   rack" highlights the slot. Drag and drop on iPad and Mac.
3. **Drink a bottle:** swipe or context menu, then a short sheet (rating,
   one-line note, occasion). A full tasting note can be added later.
4. **What to drink tonight:** bottles grouped by maturity (at peak, drink
   soon, past peak, young), with a drinking-window gauge.
5. **Share a cellar** with a QR code (see above).
6. **Work offline:** everything is local. The label is read on the device;
   only the optional AI recognition and web search need the network.

Also in v1: onboarding (create a rack or scan a first bottle, or join a
shared cellar), import and export as JSON/CSV, App Intents /
Siri Shortcuts ("What should I drink tonight?"), Spotlight.

### Visual language

- System fonts (SF Pro / Roboto), system semantic colors, one burgundy
  accent (contrast-checked in light and dark), SF Symbols / Material Symbols.
- Native lists, forms and sheets instead of custom cards and modals.
- Wine color and maturity always shown with a label or symbol, never by
  color alone.
- Shared design tokens (`design/tokens.json`, W3C format), turned into the
  Apple asset catalog by `design/generate_apple_assets.py` and mirrored in
  `DesignTokens.kt` on Android, checked by a test.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 0. Public release | Clean repository, CI, TestFlight pipeline | Done |
| 1. Design in the app | The six journeys built in SwiftUI and Compose, reviewed on device through TestFlight; design tokens | Done, refined continuously |
| 2. Sync | Pairing, local network sync, relay, protocol spec and test vectors | Apple and Android done; first test between an iPhone and an Android phone next |
| 3. Apple apps MVP | iPhone, iPad, Mac with sync | Internal TestFlight; external beta next |
| 4. Android MVP | Phones, tablets, foldables | Feature parity with Apple, sync included (not yet tried on a phone); Play internal testing next |
| 5. Remote sync without servers | CloudKit (Apple), Google Drive (cross-platform) | CloudKit built (needs the iCloud container); Google Drive planned ([REMOTE_SYNC.md](REMOTE_SYNC.md)) |
| 6. Household sharing | Roles, signed files, remote pairing | Planned |
| 7. Launch | Store listings, documentation, privacy policy | Planned |

### Features beyond the core journeys

Chosen from a survey of cellar apps and collector forums (October 2026), and
kept within the privacy rules: everything is worked out on the device.

| Feature | Apple | Android |
|---------|-------|---------|
| Monthly reminder of bottles to drink, delivery-day reminder | Done | Done (WorkManager) |
| Siri, Shortcuts, Spotlight | Done | App shortcuts and links done |
| Barcode recognizes wines you already have (matched on the device) | Done | Done (CameraX + zxing-cpp) |
| Select several wines: place, take out, delete | Done | Done |
| Rack check (audit) | Done | Done |
| Drag and drop in racks | iPad and Mac | Tablets |
| Import CellarTracker and Vivino CSV exports | Done | Done |
| Year in wine, drink-down plan | Done (year shared as an image) | Done |
| Bottles on order (en primeur, deliveries) | Done | Done |
| Bottles kept for an occasion (not suggested for tonight) | Done | Done |
| Tags on wines, filter chips | Done | Done |
| "Why this window", drink sooner or keep longer | Done | Done |
| Ask your cellar (on-device language model) | Done (Apple Intelligence devices) | Later |
| Shortcuts actions that return the cellar as text or JSON, for local AI assistants ([SHORTCUTS.md](SHORTCUTS.md)) | Done | Later |
| Printable PDF inventory | Done | Done |
| Menu bar glass with tonight's bottles | Mac | — |
| Sortable table with an inspector | Mac and iPad | Tablets |
| Widgets, Control Center | Planned (needs an App Group) | Planned (Glance) |
| Saved searches | Done | Done |
| Several photos per wine | Done | Done |
| Studio look for label photos (cut out and straightened on the device, photo kept as taken) | Done (Vision) | Light evened out on a clean background (no cut-out: it would need ML Kit) |
| Undo from the keyboard (⌘Z, Edit menu, shake) | Done | — |
| Find Current Price, estimated value today (web search; only the search terms are sent) | Done | Done |
| Search Online (Brave Search, Tavily, SearXNG; only the search terms are sent) | Done | Done |
| Search Pictures of the bottle (cover picture; the label photo is kept) | Done | Done |
| Suggest Details from the search results (on-device model) | Done (Apple Intelligence devices) | Later |
| Watch app | Later | Later (Wear OS tile) |
| More places to sync through: Google Drive, Dropbox, pCloud, Proton Drive (depends on its API), a NAS over WebDAV (Synology, QNAP, Nextcloud). Each is a thin adapter of the same encrypted mailbox protocol | Later | Later |

### Ask your cellar (on the device)

Free questions about your own cellar, such as "Friends who love Beaujolais are
coming and we are having ratatouille: what should I open?". The answer names
only bottles you have, and tapping one opens it.

Done on Apple devices with Apple Intelligence (iOS, iPadOS and macOS 26), with
the Foundation Models framework: the question and a compact list of the cellar
stay on the device, so no privacy exception is needed. It is hidden where the
on-device model is unavailable. Android may follow with an on-device model once
one is available without sending usage data to a third party.

A cloud version (sending a summary of the cellar to the AI provider set up for
labels) is not planned: `CLAUDE.md` allows only the label photo to be sent to
it. For the same reason, Suggest Details reads web search results only with the
on-device model.

## Open questions

- **App store distribution and the AGPL.** Store terms are widely considered
  incompatible with the AGPL for third-party contributions. Before accepting
  outside contributions, add an AGPL section 7 additional permission for app
  store distribution, or a contributor license agreement.
- **Encryption export rules.** An end-to-end encrypted app must answer the
  export compliance questions in App Store Connect, and France requires a
  declaration for encryption products. To verify before the first store
  submission.
- **Shared core.** The model is implemented twice (Swift, Kotlin), and the
  protocol's Kotlin port is in progress, with shared test vectors. If the model grows much, revisit a shared Rust core
  (UniFFI) or a CRDT engine such as Automerge.
- **Community relay.** Whether the project runs a capped public relay, or only
  publishes the self-hosting template; see [REMOTE_SYNC.md](REMOTE_SYNC.md).

## Repository layout

```
apple/     SwiftUI app (iOS, iPadOS, macOS) and the CaveKit package (model, sync)
android/   Kotlin / Compose app and its core module (model; sync in progress)
relay/     Self-hostable relay (Node server, Cloudflare Worker)
design/    Design tokens and asset generators
docs/      Roadmap, sync protocol, remote sync analysis, development guide
```
