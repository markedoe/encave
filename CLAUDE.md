# Encave – contributor and AI assistant guidelines

## Direction

Encave is a set of **local-first native apps** with end-to-end encrypted
sync between devices and users (see `docs/ROADMAP.md`):

- `apple/`: SwiftUI app (iPhone, iPad, native macOS); model in `apple/Packages/CaveKit`
- `android/`: Kotlin + Jetpack Compose app (phones, tablets, foldables)
- `relay/`: self-hostable relay that stores ciphertext only (Node, Cloudflare Workers)
- Sync protocol: `docs/SYNC.md`, implemented in CaveKit and mirrored in Kotlin
  with shared test vectors

## Product and design rules

- Follow Apple's Human Interface Guidelines and Material 3. Prefer native
  components (List, Form, sheets, swipe actions, context menus, searchable)
  over custom UI.
- Four sections: Cellar, Storage (Rangements), Tonight, Journal. Talk about bottles; the
  "wine" record is an internal grouping.
- Adding a bottle by scanning must take under 10 seconds.
- Support light and dark mode, Dynamic Type / font scaling, VoiceOver /
  TalkBack, and French and English for every string.
- Use the shared design tokens (`design/tokens.json`); never hardcode colors.
- Never rely on color alone (wine color, maturity).

## Privacy rules

- User data stays on device; anything sent over the network is end-to-end
  encrypted. The relay must never see plaintext (protocol: `docs/SYNC.md`).
- Exception: the optional AI label recognition, off by default, which sends
  only the label photo directly to the provider the user sets up with their
  own key. Never send the cellar or anything else to it.
- Exception: the optional web search, off by default, which sends only the
  search terms (producer, name, appellation, vintage, and for a price the
  bottle size; shown and editable before sending) to the search provider the
  user sets up. Never send the cellar, notes, prices or places. Results are
  read on the device only. Picture thumbnails and the picture the user picks
  are downloaded from their hosts, which see the request; the picture is
  kept as the wine's cover and never replaces the label photo.
- No analytics or third-party SDKs that receive user data.
- No integrations with commercial wine platforms.

## Public repository rules

This repository is public.

- Never commit secrets, `.env` files, signing keys or certificates,
  provisioning profiles, test reports, real user data or screenshots of it,
  server hostnames, IP addresses or home directory paths.
- Commit messages: subject of at most 72 characters, body of at most
  12 lines, no pasted conversation or prompt text, no links to AI chat sessions.
- Enforced by `scripts/check-public-safety.sh`, `scripts/check-commit-message.sh`
  and gitleaks, in pre-commit hooks and CI.
