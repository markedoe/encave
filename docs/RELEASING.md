# Releasing

How a change reaches the testers and the stores, with the decisions behind it.
The goal: nothing to do by hand once a change is on `main`, apart from the
one-time store forms listed at the end.

Status: **done** is in the workflows today; **planned** is decided but not built
yet (see "Work in progress").

## Principles

- **`main` is always releasable.** A change is checked on a branch (fast checks,
  and the full screen tour for app changes), then `main` is fast-forwarded to it.
- **Trunk-based, with feature switches.** Unfinished features land on `main`
  behind a switch: on in beta builds, off in store builds. Turning a feature on
  for everyone is a one-line commit that rides the next release. *(planned)*
- **Fix forward.** Store releases cannot be rolled back: a bad release is
  paused (phased or staged rollout) and replaced by a fix.
- **Sync stays compatible.** Devices sync while on different versions: each
  release must read what the previous release writes, and keep fields it does
  not know (already the rule, `docs/SYNC.md`). A test syncs the new version
  with the records of the last published one and blocks the release when it
  fails. *(planned)*

## Stages

| Stage | Apple | Google | When |
|---|---|---|---|
| Internal testers | TestFlight group "Internal", gets every build (**done**) | Play internal testing (*planned*) | Every push to `main`, about 20 to 30 minutes after it (upload and processing) |
| Beta testers | TestFlight group "Friends-ext", after the screen tour and Beta App Review (**done**) | Play closed testing "Friends" (*planned*) | Every push to `main` whose tour passed |
| Everyone | App Store, phased release over 7 days (*planned*) | Play production, staged rollout 10%, 50%, 100% (*planned*) | Release train, or the urgent lane |

## Release train *(planned)*

Decided: **two trains a week, Monday and Thursday at 05:00 UTC** (07:00 in
Switzerland in summer, 06:00 in winter), with **2 days in beta**.

Each train takes the **most recent build that has been with the beta testers
for at least 2 days** (not necessarily the latest build), when:

- its checks and screen tour passed,
- no new crash was reported for it (TestFlight crash feedback, Play vitals),
- it brings user-facing changes since the last release (CHANGELOG).

The train then, with no manual step:

1. builds the store version of that same commit (`ENCAVE_CHANNEL=store` on Apple,
   `ENCAVE_BETA=false` on Android: beta notices, sample cellar and unfinished
   features off), and runs the store-mode launch checks on it;
2. sets the version (below) and the release notes in French and English, from
   the CHANGELOG;
3. uploads the store screenshots made by the screen tours and the listing kept
   in the repository (`docs/store/`);
4. submits to App Review with automatic release after approval and a phased
   release; sends the bundle to Play production with a staged rollout;
5. pauses the rollout by itself if the crash rate rises (Play vitals);
6. tags the commit `vX.Y` and notifies.

The first public release waits for the owner's explicit go: the train is
built switched off and turned on with a committed setting. As of 2026-10-23
the owner keeps the apps in TestFlight (and Play testing) for now: the train
stays off.

## Urgent lane *(planned)*

For crashes, data loss, broken sync and security fixes. The fix commit is marked
urgent (a `Release: urgent` trailer). Once its screen tour passes, it is released
at once: the 2 days in beta are skipped, a version already waiting for App Review
is withdrawn and replaced, and on Play it replaces the rollout in progress.
Apple's expedited review is a web form (no API): optional, by hand.

## Versions

- **Build number:** `1000 + run number * 10 + attempt`, the same scheme for the
  Apple build number and the Android `versionCode` (**done**).
- **Version:** shared by both apps (`MARKETING_VERSION` in `apple/project.yml`,
  read by Gradle). *(planned)* Computed at build time from the release tags:
  after the tag `v1.4`, every build is `1.5` until the train releases it.

## Speed of the checks

Measured on 2026-10-23 for one change: about 15 minutes to upload the build,
80 to 85 minutes for the screen tour in one job (iPhone and iPad side by side,
English then French), then Beta App Review.

- On `main`, a commit whose tour already passed in another run (a branch
  fast-forwarded to `main`) is not toured again: the `tour-check` job of
  `apple.yml` finds that run and the `testers` job goes ahead once the build is
  uploaded.
- The tour runs as four jobs (iPhone and iPad, English and French), one
  simulator each, and merges the screenshots into the `screens` artifact.
  "Re-run failed jobs" re-runs only the part that failed. The measured time
  is in the `apple.yml` header.

## One-time setup by hand (no API)

Apple:

- App Privacy questionnaire in App Store Connect (answers in `docs/STORE.md`).
- Be in the "Internal" TestFlight group (App Store Connect › TestFlight ›
  Internal Testing) to get every build first. Done: the owner is in it.

Google:

- A Play developer account (identity verification), the app created in the
  Play Console, and the Data safety, content rating and target audience forms
  (answers in `docs/STORE.md`).
- The first bundle uploaded by hand (Google does not allow the first upload by
  API).
- A service account with release rights in the Play Console, reached from
  GitHub Actions through Workload Identity Federation (no stored key).
- Personal accounts created after November 2023: a closed test with at least
  12 testers for 14 days before production can be requested. The owner's
  account is personal, created in 2026: this applies.

## Security

- Apple builds are signed in the cloud by App Store Connect: no certificate is
  stored. The App Store Connect API key is a repository secret.
- *(planned)* Google keeps the app signing key (Play App Signing); the upload
  key stays a repository secret; the Play API is reached without a stored key.
- *(planned)* Release secrets only for runs on `main`; build provenance
  attestations for the uploaded bundles; Dependabot for the pinned actions.

## Work in progress

1. Faster tour: four jobs, no second tour on `main` (branch `claude/faster-ci`).
2. Groundwork: version from release tags; store builds of a given commit;
   feature switches on both platforms.
3. App Store release tool and the train (Apple), then the urgent lane.
4. Google Play: internal and closed testing, production, listing and
   screenshots, an emulator smoke test. Waits for the Play account.
5. Sync compatibility test against the last release; hardening.
