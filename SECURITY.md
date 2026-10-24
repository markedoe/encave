# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems.
Use GitHub's private reporting instead: *Security → Report a vulnerability*
on this repository. You should get an answer within 7 days.

## Data that leaves the device

Your cellar stays on your devices.

- **Sync between your devices** is end-to-end encrypted.
  - On the same network, devices talk directly with no server.
  - Away from home, they exchange encrypted files through a WebDAV folder or
    a relay you choose, which never has a key (iCloud too, in builds set up
    with an iCloud container). A WebDAV username and password stay on the
    device that uses them (Keychain, Android Keystore) and are sent only to
    that server, over HTTPS (or HTTP on the local network).
- **Pairing:** each device has its own identity key (in the Keychain,
  or encrypted with the Android Keystore). Pairing is
  confirmed by comparing a 6-digit code on both screens.
- **Removing a device** shuts it out of the network handshake and rotates the key
  of the remote files. A removal is final: the device cannot add itself back.

The protocol and its threat model are in [docs/SYNC.md](docs/SYNC.md).

There are two exceptions, both off by default:

- The optional AI label recognition: when you enable it with your own API key,
  the label photo (and nothing else) is sent directly to the provider you chose.
- The optional web search: when you choose a search service (Brave Search,
  Tavily or your own SearXNG server) and tap Search, only the search terms
  are sent to it: by default the producer, name, appellation and vintage (and
  the bottle size, for a price), shown and editable first. The cellar, notes, prices and places are never sent. The
  results are read on the device; on Apple devices, Suggest Details uses only the
  on-device Apple Intelligence model. For Search Pictures, the thumbnails and
  the picture you choose are downloaded from the sites that host them, which
  see the request (your IP address); the picture is checked (https, an image,
  at most 8 MB) and saved as a re-encoded JPEG without metadata.

Keys are kept in the Keychain (Apple) or encrypted with the Android Keystore,
on the device. The algorithms and libraries, and how the apps declare their
encryption to the stores, are in [docs/ENCRYPTION.md](docs/ENCRYPTION.md).

## Keeping the repo clean

This repository is public. A few rules keep secrets and internal details out of it:

- **No real values in git.** `.env` files are gitignored and rejected by the hooks.
- **No signing material in git.** Certificates, `.p8` API keys, provisioning
  profiles and keystores live in GitHub secrets only.
- **No machine identities in commits.** Set a real or GitHub noreply address:
  `git config user.email "<id>+<user>@users.noreply.github.com"`.
- **No infrastructure details.** No server hostnames, home paths or private IPs
  in code or docs; use placeholders such as `/path/to/encave`. Home folders
  (Linux, macOS, root), private, CGNAT and Tailscale addresses and Tailscale
  names are checked automatically, in the tree and in every line the pushed
  commits add (a file added and removed again stays in the history). Other
  hostnames and public IPs are not, so check them yourself.
- **No test output or local settings.** Test reports (`.xcresult`, JUnit
  `TEST-*.xml`, `build/reports`), `local.properties`, `.envrc`, certificates
  and provisioning profiles are refused too.

These rules are enforced by:

| Guardrail | Where |
|-----------|-------|
| `gitleaks` with `.gitleaks.toml` | pre-commit, CI (`secrets` job) and weekly on the whole history |
| `scripts/check-public-safety.sh` | pre-commit, CI (`secrets` job) and weekly on the whole history |
| `scripts/check-commit-message.sh` | commit-msg hook and CI (`secrets` job) |
| `scripts/check-workflow-secrets.py`: release secrets go only to code from `main` | CI (`secrets` job) |
| GitHub secret scanning + push protection | repository settings |

The `secrets` job runs on pull requests and on pushes to `main` and `claude/**`.

Set up the local hooks once:

```bash
pip install pre-commit
pre-commit install
```

If a secret is ever committed: **rotate it first**, then remove it from history.
Deleting the file in a new commit is not enough.
