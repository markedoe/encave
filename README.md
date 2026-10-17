# Encave

A private wine cellar for iPhone, iPad, Mac and Android: know what you own,
where each bottle sits in your racks, and when it is ready to drink.

> **Status:** early development. The Apple app is being built in the open and
> tested through TestFlight; Android follows. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Principles

- **Local-first.** Your cellar lives on your devices and works offline,
  even in a cellar without signal.
- **Private by design.** Sync between your devices and with the people you
  share a cellar with will be end-to-end encrypted. No account, no tracking.
- **Native.** SwiftUI on iPhone, iPad and Mac; Jetpack Compose on Android.

## Repository

| Path | Content |
|------|---------|
| `apple/` | SwiftUI app for iPhone, iPad and Mac ([README](apple/README.md)) |
| `apple/Packages/CaveKit` | Platform-independent cellar model, tested on Linux and macOS |
| `design/` | Design tokens and asset generators shared by all platforms |
| `docs/` | Roadmap and development guide |

## Getting started

See [docs/development.md](docs/development.md).

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md) (please report vulnerabilities privately)

## License

[GNU Affero General Public License v3.0](LICENSE).
