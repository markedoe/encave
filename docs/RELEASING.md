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
| Internal testers | TestFlight group "Internal", gets every build (**done**) | Play internal testing, gets every signed build (**done**, once the one-time setup below is finished) | Every push to `main`, about 20 to 30 minutes after it (upload and processing) |
| Beta testers | TestFlight group "Friends-ext", after the screen tour and Beta App Review (**done**) | Play closed testing (testers list "Friends"), after the screen tour and the emulator smoke test (built, **off until the owner decides**: internal testing only for now) | Every push to `main` whose tour passed |
| Everyone | App Store, phased release over 7 days (*planned*) | Play production, staged rollout 10%, 50%, 100% (*planned*; `play_publish.py` can set a rollout share, no job uses it) | Release train, or the urgent lane |

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

Google: see "Google Play: one-time setup" below.

## Google Play

### How it works

`android.yml`, on every push to `main` (and a manual run on `main`):

1. `release` builds the release bundle (`versionCode` = build number), signed
   with the upload key.
2. `play-internal` uploads that bundle and its R8 mapping and releases it to
   **internal testing** at once (`android/tools/play_publish.py upload`).
3. `smoke` turns the same bundle into an APK (bundletool) and runs it on an
   Android 15 emulator: sample cellar, then the four tabs in English and French
   (`android/tools/emulator_smoke.py`; screenshots in the `android-smoke`
   artifact).
4. `play-closed` releases the **same version code** to **closed testing**
   (`play_publish.py promote`) once `tour` and `smoke` of the same run passed.
   Nothing is rebuilt. **Off until the owner decides**: it runs only when the
   `PLAY_CLOSED_TRACK` variable is set. For now builds stay in internal
   testing.

Release notes, in English and French: the build number and the latest commit
subjects. Before the setup is finished (and on forks) `play-internal` ends with
a notice and sends nothing; `play-closed` is skipped. Production is not
automated: the apps stay in testing (see "Release train").

Authentication: Workload Identity Federation. GitHub gives the job a
short-lived OpenID Connect token; Google exchanges it for a one-hour token of
the service account, only for this repository and `refs/heads/main`. No key is
stored. Only `play-internal` and `play-closed` may request the token
(`id-token: write`).

Repository settings (Settings › Secrets and variables › Actions):

| Name | Kind | Value |
|---|---|---|
| `ENCAVE_KEYSTORE_BASE64`, `ENCAVE_KEYSTORE_PASSWORD`, `ENCAVE_KEY_ALIAS`, `ENCAVE_KEY_PASSWORD` | Secrets | The upload key (`docs/development.md`, "Android release") |
| `PLAY_WIF_PROVIDER` | Variable | `projects/<project number>/locations/global/workloadIdentityPools/github/providers/encave` |
| `PLAY_SERVICE_ACCOUNT` | Variable | `play-release@<project id>.iam.gserviceaccount.com` |
| `PLAY_CLOSED_TRACK` | Variable, unset for now | The closed track (`alpha`, the closed track every app has); unset, nothing goes to closed testing |

### Google Play: one-time setup

In this order. `<project id>`, `<project number>` and the email addresses are
placeholders: never commit the real ones.

1. **Upload key.** Create the keystore and set the four `ENCAVE_KEY*`
   secrets (`docs/development.md`, "Android release").
2. **Signed bundle.** Actions › Android app › Run workflow, branch `main`.
   When the run ends, download the `encave-release-aab` artifact (a ZIP with
   `app-release.aab`). `play-internal` ends with a notice: expected.
3. **The app.** In the Play Console: Create app; name Encave; default
   language English (United States); App; Free; accept the declarations.
   The package name is set by the first bundle: `app.encave`
   (`applicationId` in `android/app/build.gradle.kts`).
4. **Forms** (Dashboard › Set up your app), answers in `docs/STORE.md`: App
   access, Ads, Content rating, Target audience (18 and over), Data safety,
   Government apps, Financial features, Health, the privacy policy URL, and
   the store listing (texts in French and English, icon, feature graphic,
   phone screenshots from the `android-screens` artifact). The Console lists
   what is still missing before a release can be rolled out.
5. **First bundle, by hand** (Google does not take the first one by API). Test
   and release › Testing › Internal testing › Testers: create an email list
   with your own address, save. Then Create new release: keep "Google-generated
   key" for Play App Signing (Google keeps the app signing key; ours is only
   the upload key), upload `app-release.aab` from step 2, Next, Save, and
   **Roll out to internal testing** (a release left as a draft keeps the app a
   draft, and the API then refuses releases).
6. **Closed testing (later, when the owner decides).** Test and release › Testing › Closed testing ›
   Alpha › Testers: create an email list named "Friends" (or use a Google
   Group), choose the countries, add a feedback email, save. Copy the opt-in
   link and send it to the testers. A personal account created after November
   2023 (the owner's) needs at least 12 testers opted in for 14 days in a row
   before it can apply for production. Then set the `PLAY_CLOSED_TRACK`
   variable to `alpha` (or the name of another closed track; a wrong name
   makes the job list the track names).
7. **Google Cloud** (console.cloud.google.com › Cloud Shell), the project,
   the service account and the Workload Identity Federation:

   ```bash
   PROJECT_ID="encave-release-$RANDOM"   # new and globally unique; note the printed values
   gcloud projects create "$PROJECT_ID" --name="Encave release"
   gcloud config set project "$PROJECT_ID"
   gcloud services enable androidpublisher.googleapis.com iam.googleapis.com \
     iamcredentials.googleapis.com sts.googleapis.com
   gcloud iam service-accounts create play-release --display-name="Encave Play release"
   gcloud iam workload-identity-pools create github --location=global \
     --display-name="GitHub Actions"
   gcloud iam workload-identity-pools providers create-oidc encave --location=global \
     --workload-identity-pool=github --display-name="markedoe/encave main" \
     --issuer-uri="https://token.actions.githubusercontent.com" \
     --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
     --attribute-condition="assertion.repository == 'markedoe/encave' && assertion.ref == 'refs/heads/main'"
   PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
   gcloud iam service-accounts add-iam-policy-binding \
     "play-release@$PROJECT_ID.iam.gserviceaccount.com" \
     --role=roles/iam.workloadIdentityUser \
     --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/markedoe/encave"
   echo "PLAY_WIF_PROVIDER=projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/encave"
   echo "PLAY_SERVICE_ACCOUNT=play-release@$PROJECT_ID.iam.gserviceaccount.com"
   ```

   The service account has no key and no role in the project: it can only
   be impersonated by runs on `main` of this repository.
8. **Invite the service account** in the Play Console: Users and
   permissions › Invite new users; email: the `PLAY_SERVICE_ACCOUNT` value;
   App permissions › Add app › Encave; tick only **Release apps to testing
   tracks** (add "Release to production, exclude devices, and use Play App
   Signing" only when production is turned on). Invite user. No account
   permission is needed.
9. **GitHub variables**: set `PLAY_WIF_PROVIDER` and `PLAY_SERVICE_ACCOUNT`
   to the two values printed in step 7 (Settings › Secrets and variables ›
   Actions › Variables). Leave `PLAY_CLOSED_TRACK` unset (step 6, later).
10. **Check**: Actions › Android app › Run workflow on `main`. `play-internal`
   ends with "released to the Google Play track internal"; `play-closed` is
   skipped. Once closed testing is on, its first release goes through
   Google's review (usually hours to a few days); later ones are faster.

If a job fails, its message says which step is missing (app unknown, no
permission, app still a draft, unknown track).

## Security

- Apple builds are signed in the cloud by App Store Connect: no certificate is
  stored. The App Store Connect API key is a repository secret.
- Google keeps the app signing key (Play App Signing); the upload key is a
  repository secret, used only by the `release` job; the Play API is reached
  through Workload Identity Federation, without a stored key, only from `main`.
- *(planned)* Release secrets only for runs on `main`; build provenance
  attestations for the uploaded bundles; Dependabot for the pinned actions.

## Work in progress

1. Faster tour: four jobs, no second tour on `main` (branch `claude/faster-ci`).
2. Groundwork: version from release tags; store builds of a given commit;
   feature switches on both platforms.
3. App Store release tool and the train (Apple), then the urgent lane.
4. Google Play: internal testing and the emulator smoke test are built and
   wait for the one-time setup; closed testing is built and off; then
   production, the listing and screenshots.
5. Sync compatibility test against the last release; hardening.
