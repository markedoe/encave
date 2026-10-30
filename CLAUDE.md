# Encave – contributor and AI assistant guidelines

## Direction

Encave is a set of **local-first native apps** with end-to-end encrypted
sync between devices and users (see `docs/ROADMAP.md`):

- `apple/`: SwiftUI app (iPhone, iPad, native macOS); model in `apple/Packages/CaveKit`
- `android/`: Kotlin + Jetpack Compose app (phones, tablets, foldables)
- `relay/`: self-hostable relay that stores ciphertext only (Node, Cloudflare Workers)
- Sync protocol: `docs/SYNC.md`, implemented in CaveKit and mirrored in Kotlin
  with shared test vectors
- Releases: `docs/RELEASING.md` (testers, release train twice a week, urgent
  lane, feature switches); keep it up to date when the pipeline changes

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
  only the label photo to the models the user sets up with their own keys:
  one at a time, the next only when the previous one fails or when the user
  asks to read the label again with another model. Through OpenRouter the
  photo reaches the chosen model's provider; requests ask OpenRouter to skip
  providers that keep or train on the data, unless the user turns that off.
  When the user asks what to expect from a wine, its identity (producer, name,
  appellation, vintage, colour, grapes) may be sent instead of the photo. When
  the user turns on web search for a connection, that provider may search the
  web with what it read, and shows its sources. Never send the cellar, notes,
  prices, places or anything else to them.
- Aroma game guesses and scores stay on the device that played: never synced,
  not even between people sharing a cellar.
- Exception: the optional web search, off by default, which sends only the
  search terms (producer, name, appellation, vintage, and for a price the
  bottle size; shown and editable before sending) to the search provider the
  user sets up. Never send the cellar, notes, prices or places. Results are
  read on the device only. For a price, the first result pages are opened
  from the device (no cookies or credentials) and read there. Picture
  thumbnails and the picture the user picks are downloaded from their hosts,
  which see the request; the picture is kept as the wine's cover and never
  replaces the label photo.
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

## AI agents and sessions

Pick the model for each agent or session by the task, not by default, and keep
requests lean:

- Opus (`claude-opus-5-5`): architecture and protocol changes (sync, crypto,
  synced model fields), adversarial and security reviews, cross-platform
  features, and failures whose cause is not yet known.
- Sonnet (`claude-sonnet-5-5`): well-specified work with files and lines named
  (UI fixes, strings and copy, docs, adding the tests a review listed), CI log
  triage, screenshot and visual reviews, routine merges.
- Haiku (`claude-haiku-5-5`): lookups, status polling, simple summaries.
- Do the work yourself by default. Start an agent or session only when the task
  needs a fresh context or a different model.
- Prefer a script or a direct command to an agent for rote work; give each
  agent only the context it needs (paths, findings, constraints); do not start
  two agents on the same files; stop or archive agents once their work is done.
