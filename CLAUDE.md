# Encave – contributor and AI assistant guidelines

## Direction

Encave is a set of **local-first native apps** with end-to-end encrypted
sync between devices and users (see `docs/ROADMAP.md`):

- `apple/`: SwiftUI app (iPhone, iPad, native macOS); model in `apple/Packages/CaveKit`
- `android/` (planned): Kotlin + Jetpack Compose app
- `core/` (planned): Rust sync core (CRDT, encryption, storage) exposed through UniFFI
- `relay/` (planned): relay that stores and forwards ciphertext only

## Product and design rules

- Follow Apple's Human Interface Guidelines and Material 3. Prefer native
  components (List, Form, sheets, swipe actions, context menus, searchable)
  over custom UI.
- Four sections: Cellar, Racks, Tonight, Journal. Talk about bottles; the
  "wine" record is an internal grouping.
- Adding a bottle by scanning must take under 10 seconds.
- Support light and dark mode, Dynamic Type / font scaling, VoiceOver /
  TalkBack, and French and English for every string.
- Use the shared design tokens (`design/tokens.json`); never hardcode colors.
- Never rely on color alone (wine color, maturity).

## Privacy rules

- User data stays on device; anything sent over the network is end-to-end
  encrypted. The relay must never see plaintext.
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
