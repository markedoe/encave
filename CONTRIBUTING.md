# Contributing

Thanks for helping. Small, focused pull requests are the easiest to review.

## Before you start

- For anything larger than a bug fix, open an issue first to agree on the approach.
- Security problems: do not open an issue, see [SECURITY.md](SECURITY.md).

## Setup

See [docs/development.md](docs/development.md). Then install the git hooks once:

```bash
pip install pre-commit
pre-commit install
```

The hooks run the same checks as CI: secret scanning (gitleaks), a
public-safety check (no `.env` files, keys, certificates, test reports, home
paths or server details) and a commit message check (short, no pasted
conversation; see [SECURITY.md](SECURITY.md)).

## Guidelines

- **Native first.** Follow Apple's Human Interface Guidelines and Material 3.
  Prefer system components (lists, forms, sheets, swipe actions) over custom UI.
- **Accessible.** Support Dynamic Type and font scaling, VoiceOver and
  TalkBack, light and dark mode, and never rely on color alone.
- **Both languages.** Add every user-facing string in English and French:
  in `apple/tools/l10n.py` and `android/tools/strings.py`, then run them to
  regenerate the string files (never edit those by hand).
- **Private.** User data stays on device or is end-to-end encrypted. No
  analytics or third-party SDKs that receive user data.
- **Tests.** Add tests for model changes in `apple/Packages/CaveKit` and
  `android/core`, kept in step through the shared `fixtures/`.
- **No real data.** Never commit credentials, signing files, personal data or
  screenshots of real cellars.

## Commit messages

- Subject: imperative mood, at most 72 characters ("Fix rack drag on iPad").
- Body (optional): what changed and why, at most 12 lines.
- Do not paste conversations, prompts or issue threads into commit messages.

## Code style

- Swift 6 with strict concurrency, SwiftUI, Swift Testing.
- Keep business logic in `CaveKit`; views stay thin.
- Kotlin with Jetpack Compose and Material 3; logic in `android/core`.

Encave is under the [FSL-1.1-ALv2](LICENSE), except the relay, which is under
the AGPL-3.0 ([relay/LICENSE](relay/LICENSE)). The owner sells the app, so a
contribution is accepted only after you have signed a contributor licence
agreement; open an issue before starting work and the owner will send it.
