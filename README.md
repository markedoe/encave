# Encave

A private wine cellar for iPhone, iPad, Mac and Android: know what you own,
where each bottle sits in your racks, and when it is ready to drink.

> **Status:** early development. The apps are built in the open and tested
> through TestFlight (Apple) and CI builds (Android). See
> [docs/ROADMAP.md](docs/ROADMAP.md).

## What it does

- **Cellar:** every bottle, searchable, with filters by style, maturity, rack and
  your own tags, as a list or (on Mac and iPad) a sortable table.
  - Each wine shows its drinking window and why, serving temperature and
    decanting advice.
  - Bottles on order (deliveries, en primeur) wait apart until they arrive.
  - Keep bottles for an occasion, so they are not suggested for tonight.
  - A printable PDF inventory for insurance or a move.
- **Adding a bottle in seconds:**
  - Scan the label: the text is read on the device, and the barcode recognizes
    wines you already have.
  - Optional AI recognition uses your own API key and sends only the label photo.
  - Optional **Search Online** completes a wine from the web with Brave Search,
    Tavily or your own SearXNG server. Only the search terms (producer, name,
    appellation, vintage) are sent, after you see and can edit them. On Apple
    Intelligence devices, **Suggest Details** reads the results on the device
    and proposes details you accept one by one.
- **Racks drawn as they look:** grids, honeycombs, wine fridges with depth, diamond
  bins, irregular rows.
  - See which bottle is where.
  - Find a wine with the key under the rack.
  - Pinch to zoom, and move bottles.
- **Tonight:** what to open now, the most urgent first, and food pairings.
  - **Ask your cellar** ("something light for a ratatouille"), answered with
    your own bottles by the on-device model of Apple Intelligence; nothing is
    sent anywhere.
- **Journal:**
  - your tastings and insights, your year in wine, and the pace to drink your
    cellar before windows close;
  - wines discovered elsewhere, with tags and a shopping list.
- **Your devices:**
  - Pair them with a QR code or a 6-digit code.
  - They sync directly on the same Wi-Fi, or away from home through a relay you
    choose (iCloud too, in builds set up with an iCloud container). Everything
    is encrypted from end to end.
- **Reminders and Siri:** a monthly reminder of the bottles to drink, a reminder
  on delivery days, "What should I open tonight?", wines in Spotlight, and on
  the Mac a glass in the menu bar. Everything is worked out on the device.
- French and English, light and dark mode, Dynamic Type, VoiceOver and TalkBack.

Android has the same features, apart from Siri and Spotlight, label reading on
the device (barcodes only), Ask your cellar and the Mac menu bar; iCloud sync is
Apple-only, relays work for both (see the [roadmap](docs/ROADMAP.md)).

## Principles

- **Local-first.** Your cellar lives on your devices and works offline,
  even in a cellar without signal.
- **Private by design.**
  - No account, no tracking, no analytics.
  - Sync is end-to-end encrypted; storage only ever holds ciphertext.
- **Native.** SwiftUI on iPhone, iPad and Mac; Jetpack Compose on Android.

## Repository

| Path | Content |
|------|---------|
| `apple/` | SwiftUI app for iPhone, iPad and Mac ([README](apple/README.md)) |
| `apple/Packages/CaveKit` | Cellar model and sync protocol, tested on Linux and macOS |
| `android/` | Kotlin and Jetpack Compose app, with its core module |
| `relay/` | Self-hostable relay for syncing away from home ([README](relay/README.md)) |
| `design/` | Design tokens and asset generators shared by all platforms |
| `docs/` | [Roadmap](docs/ROADMAP.md), [sync protocol](docs/SYNC.md), [remote sync options](docs/REMOTE_SYNC.md), [development guide](docs/development.md) |

## Getting started

See [docs/development.md](docs/development.md).

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md) (please report vulnerabilities privately)

## License

[GNU Affero General Public License v3.0](LICENSE).
