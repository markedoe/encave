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
 iPhone / iPad / Mac (SwiftUI)        Android (Compose)
            │                                │
            └──────────┬─────────────────────┘
                       │  UniFFI bindings
              ┌────────▼────────┐
              │  Sync core (Rust)│  CRDT documents, encryption,
              │                  │  local storage, sync protocol
              └────────┬────────┘
                       │  encrypted blobs only
              ┌────────▼────────┐
              │      Relay       │  stores and forwards ciphertext;
              │ (hosted or self- │  cannot read data
              │     hosted)      │
              └─────────────────┘
```

- **Sync core in Rust**, shared by both apps through
  [UniFFI](https://github.com/mozilla/uniffi-rs) so merge logic and
  cryptography are written once. CRDT engine candidates:
  [Automerge](https://automerge.org) (Rust core) and
  [Yrs](https://github.com/y-crdt/y-crdt) (Rust port of Yjs); a spike decides.
- **One document per cellar.** Bottles, rack positions, consumption and
  tasting notes are CRDT objects keyed by UUID, so offline edits on several
  devices merge automatically. Each bottle is its own object with a status;
  two people drinking "the last bottle" at once shows up as a visible
  conflict, never a silently wrong count.
- **Photos** (labels, bottles) are encrypted, content-addressed blobs stored
  outside the CRDT document.
- **Relay.** Phones are rarely online at the same moment, so a relay holds
  encrypted changes until the other devices pick them up. It sees only
  ciphertext and document IDs. No VPS is needed: the default relay is a
  serverless function with per-cellar storage (for example Cloudflare
  Workers with Durable Objects). The relay is a small open-source
  service, so anyone can run their own and point the apps at it.
- **Encryption.** Content is encrypted with XChaCha20-Poly1305; devices
  exchange keys with X25519. Keys are kept in the Keychain or the
  Android Keystore.

### Pairing and sharing (QR code)

1. Device A shows a QR code containing an ephemeral public key, the cellar ID
   and the relay address.
2. Device B scans it; both devices derive a shared secret and display the
   same short confirmation code, which the user compares (prevents a
   man-in-the-middle).
3. A sends the cellar key to B over that channel. From then on, every change
   syncs automatically on both sides.

The same flow adds your own Mac or iPad ("Add a device") or a household
member ("Share cellar", with *can edit* or *view only*).

**Removing someone** rotates the cellar key: remaining devices receive the
new key and re-encrypt; the removed device keeps what it already had but
receives nothing new.

**Losing a phone:** with a second paired device nothing is lost. Single-device
users can export an encrypted backup (file, iCloud Drive / Google Drive),
and the relay keeps an encrypted copy that a re-paired device can restore
with the recovery key.

## User experience

### Information architecture

| iPhone (tab bar) | iPad and Mac (sidebar) | Android |
|------------------|------------------------|---------|
| **Cellar**: all bottles, searchable | Library: All, Ready, Drink soon, Recently added | Navigation bar on phones, |
| **Racks**: physical layout | Racks (one row per rack) | navigation rail on tablets, |
| **Tonight**: ready to drink | Journal, Insights | list-detail on foldables |
| **Journal**: history and notes | Shared: members and sync status | |

Settings open as a sheet (iPhone) or a Settings window (Mac). The interface
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
   soon, past peak, young), with a drinking-window gauge and a widget.
5. **Share a cellar** with a QR code (see above).
6. **Work offline:** everything is local; label recognition queues scans
   that need the network and resumes later.

Also in v1: onboarding (create a rack or scan a first bottle, or join a
shared cellar), import and export as JSON/CSV, App Intents /
Siri Shortcuts ("What should I drink tonight?"), Spotlight, one widget.

### Visual language

- System fonts (SF Pro / Roboto), system semantic colors, one burgundy
  accent (contrast-checked in light and dark), SF Symbols / Material Symbols.
- Native lists, forms and sheets instead of custom cards and modals.
- Wine color and maturity always shown with a label or symbol, never by
  color alone.
- Shared design tokens (`design/tokens.json`, W3C format) compiled to Swift
  and Compose with Style Dictionary, mirrored in a Figma library.

## Phases

| Phase | Content | Exit criteria |
|-------|---------|---------------|
| 0. Public release | Clean repository, CI, TestFlight pipeline | Repository public, first TestFlight build |
| 1. Design in the app | The six journeys built directly in SwiftUI and reviewed on device through TestFlight; design tokens | Journeys validated on iPhone, iPad and Mac |
| 2. Sync spike | Rust core with Automerge vs Yrs, UniFFI bindings, relay, QR pairing | Two phones and a Mac sync offline edits through the relay |
| 3. Apple apps MVP | iPhone, iPad, Mac with sync and sharing | External TestFlight beta |
| 4. Android MVP | Phones first, then tablets | Play internal testing |
| 5. Launch | Store listings, documentation | App Store, Mac App Store, Google Play |

Later: full tasting notes with photos, insights, wishlist, Mac menu bar
extra, notifications for closing drinking windows.

## Open questions

- **App store distribution and the AGPL.** Store terms are widely considered
  incompatible with the AGPL for third-party contributions. Before accepting
  outside contributions, add an AGPL section 7 additional permission for app
  store distribution, or a contributor license agreement.
- **Encryption export rules.** An end-to-end encrypted app must answer the
  export compliance questions in App Store Connect, and France requires a
  declaration for encryption products. To verify before the first store
  submission.
- **CRDT engine.** Automerge and Yrs both have active Rust cores (releases in
  September 2026); their Swift and Kotlin bindings are less mature, which is
  why the plan uses one Rust core with UniFFI.

## Repository layout

```
apple/     SwiftUI app (iOS, iPadOS, macOS) and the CaveKit model package
android/   Kotlin / Compose app (planned)
core/      Rust sync core + UniFFI bindings (planned)
relay/     Relay service (planned)
design/    Design tokens and asset generators
```
