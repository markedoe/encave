# Encave

A private wine cellar for iPhone, iPad, Mac and Android: know what you own,
where each bottle sits in your storage, and when it is ready to drink.

> **Status:** early development. The apps are built in the open and tested
> through TestFlight (Apple) and Google Play internal and closed testing
> (Android). See [docs/ROADMAP.md](docs/ROADMAP.md).

## What it does

- **Cellar:** every bottle, searchable, with filters by style, maturity, storage and
  your own tags, as a list or (on Mac, iPad and Android tablets) a sortable
  table. Save the searches you use often.
  - Each wine shows its drinking window and why, serving temperature and
    decanting advice.
  - Bottles on order (deliveries, en primeur) wait apart until they arrive.
  - Keep bottles for an occasion, so they are not suggested for tonight.
  - Several photos per wine, and documents (invoice, certificate, technical
    sheet, up to 8 MB) attached to a wine or to one bottle.
  - A printable PDF inventory for insurance or a move.
- **Adding a bottle in seconds:**
  - Scan the label: the text is read on the device, and the barcode recognizes
    wines you already have.
  - Optional AI recognition uses your own API key and sends only the label photo.
  - Optional **Find Current Price** and **Search Online** look a wine up on the
    web with Brave Search, Tavily or your own SearXNG server: the prices found
    are listed with their sites, the middle one is suggested, and the price you
    save counts in the estimated value of the cellar. Only the search terms
    (producer, name, appellation, vintage, bottle size) are sent, after you see
    and can edit them; for a price, the first five result pages are then opened
    from the device and read there. On Apple Intelligence devices, **Suggest Details** reads
    the results on the device and proposes details you accept one by one.
  - **Search Pictures** finds a picture of the bottle with the same service;
    the one you choose is downloaded from its site and shown as the wine's
    picture. The label photo is always kept.
- **Storage drawn as it looks:** racks in grids or honeycombs, wine fridges with depth or with
  bottles stacked in layers on each shelf, diamond bins and crates, irregular rows.
  - See which bottle is in which position ("Shelf 2, back, bottom layer, position 3").
  - Find a wine with the key under the drawing.
  - Pinch to zoom, and move bottles.
  - Check a storage bottle by bottle.
- **Tonight:** what to open now, the most urgent first, and food pairings.
  - **Ask your cellar** ("something light for a ratatouille"), answered with
    your own bottles by the on-device model of Apple Intelligence; nothing is
    sent anywhere.
- **Journal:**
  - your tastings and insights, your year in wine, and the pace to drink your
    cellar before windows close;
  - wines tasted elsewhere, in the same diary, with tags and a shopping list.
- **Your devices:**
  - Pair them with a QR code or a 6-digit code.
  - They sync directly on the same Wi-Fi, or away from home through a WebDAV
    folder you already have (NAS, Nextcloud, pCloud) or a relay you choose
    (iCloud too, in builds set up with an iCloud container). Everything is
    encrypted from end to end.
- **Reminders and Siri:** a monthly reminder of the bottles to drink, a reminder
  on delivery days, "What should I open tonight?", wines in Spotlight, and on
  the Mac a glass in the menu bar. Everything is worked out on the device.
  - Shortcuts actions give the cellar as text or JSON to a local AI assistant
    ([docs/SHORTCUTS.md](docs/SHORTCUTS.md)).
  - On Android, home screen widgets: Tonight and Cellar.
- **Backups:** previous versions kept on the device (7 daily, 4 weekly), a
  weekly backup on the device (on by default), **Back Up Now** as a ZIP file
  readable by other programs (backups are not encrypted), and a restore that
  shows what changes first. Import from CellarTracker, Vivino
  or a spreadsheet; export to CSV.
- French and English, light and dark mode, Dynamic Type, VoiceOver and TalkBack.

Android has the same features, apart from Siri, Spotlight and the Shortcuts
actions, label reading on the device (barcodes only), the document scanner, the
cut-out studio look, Ask your cellar, Suggest Details, Undo from the keyboard
and the Mac menu bar. Home screen widgets are Android-only for now. iCloud sync
is Apple-only; WebDAV folders and relays work for both (see the
[roadmap](docs/ROADMAP.md)).

Requirements: iOS and iPadOS 18, macOS 15, Android 15 or later.

## Principles

- **Local-first.** Your cellar lives on your devices and works offline,
  even in a cellar without signal.
- **Private by design.**
  - No account, no tracking, no analytics.
  - Sync is end-to-end encrypted; storage only ever holds ciphertext.
  - What the apps keep and send: the [privacy policy](docs/PRIVACY.md).
- **Native.** SwiftUI on iPhone, iPad and Mac; Jetpack Compose on Android.

## Repository

| Path | Content |
|------|---------|
| `apple/` | SwiftUI app for iPhone, iPad and Mac ([README](apple/README.md)) |
| `apple/Packages/CaveKit` | Cellar model and sync protocol, tested on Linux and macOS |
| `android/` | Kotlin and Jetpack Compose app, with its core module |
| `relay/` | Self-hostable relay for syncing away from home ([README](relay/README.md)) |
| `design/` | Design tokens and asset generators shared by all platforms |
| `fixtures/` | Test vectors shared by the Swift and Kotlin tests |
| `scripts/` | Public-safety and commit message checks, public snapshot export |
| `docs/` | [Roadmap](docs/ROADMAP.md), [sync protocol](docs/SYNC.md), [remote sync options](docs/REMOTE_SYNC.md), [Shortcuts for other apps](docs/SHORTCUTS.md), [encryption and export compliance](docs/ENCRYPTION.md), [privacy policy](docs/PRIVACY.md), [store paperwork](docs/STORE.md), [development guide](docs/development.md) |

## Getting started

See [docs/development.md](docs/development.md).

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md) (please report vulnerabilities privately)

## License

[GNU Affero General Public License v3.0](LICENSE).
