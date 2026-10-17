# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems.
Use GitHub's private reporting instead: *Security → Report a vulnerability*
on this repository. You should get an answer within 7 days.

## Keeping the repo clean

This repository is public. A few rules keep secrets and internal details out of it:

- **No real values in git.** `.env` files are gitignored and rejected by the hooks.
- **No signing material in git.** Certificates, `.p8` API keys, provisioning
  profiles and keystores live in GitHub secrets only.
- **No machine identities in commits.** Set a real or GitHub noreply address:
  `git config user.email "<id>+<user>@users.noreply.github.com"`.
- **No infrastructure details.** No server hostnames, home paths or private IPs
  in code or docs; use placeholders such as `/path/to/encave`.

These rules are enforced by:

| Guardrail | Where |
|-----------|-------|
| `gitleaks` with `.gitleaks.toml` | pre-commit and CI (`secrets` job) |
| `scripts/check-public-safety.sh` | pre-commit and CI (`secrets` job) |
| GitHub secret scanning + push protection | repository settings |

Set up the local hooks once:

```bash
pip install pre-commit
pre-commit install
```

If a secret is ever committed: **rotate it first**, then remove it from history.
Deleting the file in a new commit is not enough.
