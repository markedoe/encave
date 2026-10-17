# Encave

A private wine cellar for iPhone, iPad, Mac and Android: know what you own,
where each bottle sits in your racks, and when it is ready to drink.

> **Status:** early development. The apps are built in the open and tested
> through TestFlight (Apple) and CI builds (Android). See
> [docs/ROADMAP.md](docs/ROADMAP.md).

## What it does

- **Cellar:** every bottle, searchable, with filters by style, maturity and rack.
  Each wine shows its drinking window, serving temperature and decanting advice.
- **Adding a bottle in seconds:**
  - Scan the label: the text is read on the device, and the barcode recognizes
    wines you already have.
  - Optional AI recognition uses your own API key and sends only the label photo.
- **Racks drawn as they look:** grids, honeycombs, wine fridges with depth, diamond
  bins, irregular rows.
  - See which bottle is where.
  - Find a wine with the key under the rack.
  - Pinch to zoom, and move bottles.
- **Tonight:** what to open now, the most urgent first, and food pairings.
- **Journal:**
  - your tastings and insights;
  - wines discovered elsewhere, with tags and a shopping list.
- **Your devices:**
  - Pair them with a QR code or a 6-digit code.
  - They sync directly on the same Wi-Fi, or away from home through iCloud or a
    relay you choose. Everything is encrypted from end to end.
- **Reminders and Siri:** a monthly reminder of the bottles to drink, "What should
  I open tonight?", wines in Spotlight. Everything is worked out on the device.
- French and English, light and dark mode, Dynamic Type, VoiceOver and TalkBack.

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
