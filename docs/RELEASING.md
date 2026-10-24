# Releasing

How a change reaches the testers and the stores, with the decisions behind it.
The goal: nothing to do by hand once a change is on `main`, apart from the
one-time store forms listed at the end.

Status: **done** is in the workflows today; **planned** is decided but not built
yet (see "Work in progress").

## Principles

- **`main` is always releasable.** A change is checked on a branch (fast checks,
  and the full screen tour for app changes), then merged into `main`. Every
  commit is checked: `scripts/check-commit-message.sh` refuses the markers that
  make GitHub skip the workflows (`[skip ci]`, `[ci skip]`, `[no ci]`,
  `[skip actions]`, `[actions skip]`).
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
| Internal testers | TestFlight group "Internal", gets every build (**done**) | Play internal testing, gets every signed build whose tests passed (**done**, once the one-time setup below is finished) | Every push to `main`, about 20 to 30 minutes after it (upload and processing) |
| Beta testers | TestFlight group "Friends-ext", after the screen tour, the Mac launch test and Beta App Review (**done**) | Play closed testing (track Alpha, testers list "Friends"), after the tests, the screen tour and the emulator smoke test, with the store listing, then Google's review (**done**, once the testers list below is set) | Every push to `main` whose tour passed |
| Everyone | App Store, phased release over 7 days (*planned*) | Play production, staged rollout 10%, 50%, 100% (*planned*; `play_publish.py` can set a rollout share, no job uses it) | Release train, or the urgent lane |

No TestFlight e-mail is sent for a build: right after each upload, the `apps`
job turns off "Automatically notify testers" for it (`testflight_testers.py
--quiet`). Testers see new builds in the TestFlight app, where they can turn on
automatic updates.

### Apple: what gates the external testers

The `testers` job of `apple.yml` gives a build of `main` to the external
TestFlight groups only when:

- the screen tour of the same run passed, or `tour-check` found an earlier run
  of the same files (same git tree) whose tour passed;
- the Mac launch test passed. When it did not run because the Mac archive
  failed, the iPhone and iPad build goes alone (and the run stays red); one
  failed upload likewise leaves the other platform's build going.

**`testflight-external.yml`** (by hand) gives an older build: it finds the
`apple.yml` run that made it (build number `1000 + run number * 10 +
attempt`) and refuses unless that run's tour passed, or was skipped by
`tour-check` after a pass. Tick **force** to give it anyway.

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
28 to 43 minutes per screen tour job, then Beta App Review.

- `apple.yml` runs the tour as four jobs (iPad and iPhone, English and
  French), one simulator each, three at a time, so that the `apps` job (build,
  Mac launch test, TestFlight upload) never waits for a macOS runner: the
  internal testers get the build about 20 to 30 minutes after the push. The
  screenshots are merged into the `screens` artifact; "Re-run failed jobs"
  re-runs only the part that failed. A part whose build fails stops at once.
  The Mac is covered by the launch test and the Mac screenshots of the `apps`
  job (GitHub's runners cannot run the Mac UI tests).
- On `main`, a merge commit whose tree is the same as an earlier run's head
  commit whose tour passed (a pull request merged as it was toured) is not
  toured again: `tour-check` logs the matching run and the `testers` job goes
  ahead once the build is uploaded.

## One-time setup by hand (no API)

Apple:

- App Privacy questionnaire in App Store Connect (answers in `docs/STORE.md`).
- Be in the "Internal" TestFlight group (App Store Connect › TestFlight ›
  Internal Testing) to get every build first. Done: the owner is in it.

Google: see "Google Play: one-time setup" below.

## Google Play

### How it works

`android.yml`, on every push to `main` (and a manual run on `main`):

1. `build` runs the core tests (sync test vectors and a sync through a local
   relay included), the app unit tests, lint and the licences check; nothing
   goes to Google Play unless it passed.
2. `release` builds the release bundle (`versionCode` = build number), signed
   with the upload key.
3. `play-internal` uploads that bundle and its R8 mapping and releases it to
   **internal testing** at once (`android/tools/play_publish.py upload`).
4. `smoke` turns the same bundle into an APK (bundletool) and runs it on an
   Android 15 emulator: sample cellar, then the four tabs in English and French
   (`android/tools/emulator_smoke.py`; screenshots in the `android-smoke`
   artifact).
5. `play-closed` releases the **same version code** to **closed testing**
   (the built-in track `alpha`; `play_publish.py promote`) once `tour` and
   `smoke` of the same run passed. Nothing is rebuilt. In the same edit it
   updates the **store listing** (below), so Google reviews the release and
   the listing together. On by default; the `PLAY_CLOSED_TRACK` variable
   names another closed track, or `none` turns closed testing off.

Release notes, in English and French: the build number and the latest commit
subjects. Before the setup is finished (and on forks) `play-internal` ends with
a notice and sends nothing; `play-closed` is skipped. Production is not
automated: the apps stay in testing (see "Release train").

Runs on `main` run side by side and can finish out of order: `play_publish.py`
reads the track first and, when it already has a newer version code, sends
nothing and ends with a notice (the newer run has released, or will release,
its own build). `play-release.yml`, run by hand, may release an older version
code on purpose.

Every release to closed testing goes through Google's review: the first one
takes hours to a few days, later ones are usually faster. A release waiting
for review does not stop the next one: the newer version code replaces it.

While the app itself is still a draft in Google Play (App content forms not
finished), Google takes only draft releases: `play-internal` and
`play-closed` then save the release as a draft of its track and end with a
warning; roll it out from the Play Console (Test and release › the track ›
Edit release › Review › Roll out). Once the forms are finished, releases go
out by themselves.

### Store listing

Kept in the repository and published by `play-closed`, before the release:

- **Texts**, French (France, the default language) and English (United
  States): title (at most 30 characters), short description (80) and full
  description (4000), in `docs/store/play.json`. Adapted from the App Store
  texts (`docs/store/appstore.json`) to what the Android app does. Edit them
  there; `play_publish.py` checks the lengths.
- **Images**, made in CI from the sample cellar of the same run's screen tour
  by `android/tools/play_listing_images.py` (artifact `play-listing`), nothing
  committed: the 512x512 icon (`design/generate_app_icon.py`), the 1024x500
  feature graphic (the style of the App Store header images), 7 phone
  screenshots per language (the screen in a frame under a caption, 1290x2580:
  the raw capture is taller than the 2:1 Play takes), and the same screens on
  the tablet (2560x1600) as 7-inch and 10-inch tablet screenshots. Which
  screens, and their captions, are in `docs/store/play.json`. The branch
  builds make the same images from the quick tour (artifact
  `play-listing-preview`) to check them before `main`.

Texts and images are compared with what Google Play has (the images by their
SHA-256) and only what changed is replaced (`edits.listings.update`,
`edits.images.deleteall` then upload). A video added in the Console is kept.
Contact details, the app category and the default language are left as they
are in the Console: the job warns when the contact email, which Google needs
before a review, is missing. `play_publish.py listing` publishes the listing
alone (`LISTING_JSON`, `LISTING_IMAGES`).

**`play-listing.yml`** (Actions › Google Play listing › Run workflow, branch
`main`) publishes the listing and the **Data safety** form at any time,
without a release: it runs the screen tour for the images, then
`play_publish.py listing` and `play_publish.py data-safety`. The Data safety
answers (`docs/store/play-data-safety.csv`, "no data collected or shared",
reasons in `docs/STORE.md`) are in the Play Console's CSV format: all its
questions, only the first one answered. When the form changes, export it
from the Console (App content › Data safety › Export to CSV) and commit it
there. Permissions: "Manage store presence" for the listing, "Manage policy
declarations" for Data safety.

Nothing to re-run by hand for the listing: every closed release from `main`
publishes it again, with screenshots from that run's tour, so UI changes
reach the store with the next build. Run `play-listing.yml` only for the
Data safety form, or to update the listing without a release (closed
testing turned off with `PLAY_CLOSED_TRACK` = `none`).

### App content without an API

Google offers no API for these Play Console forms (Policy and programs ›
App content); the answers are in `docs/STORE.md`. Data safety is the only
App content form the API fills (above).

| Form | Answer (`docs/STORE.md`) |
|---|---|
| Privacy policy | The URL of the published `docs/PRIVACY.md` |
| App access | All functionality is available without special access (no login) |
| Ads | No, the app contains no ads |
| Content rating (IARC questionnaire) | Category All Other App Types; alcohol references: yes; everything else no |
| Target audience and content | 18 and over only; not appealing to children |
| News apps | No |
| Advertising ID | No (the app does not use it) |
| Government apps | No |
| Financial features | None |
| Health apps | No health features |
| Store settings: app category, contact details | Food and drink; a contact email (step 6) |
| Testers, countries and feedback of closed testing | Step 6 |

The Console may add forms over time (Dashboard and App content list what is
missing); the camera, notification and local network permissions of the
app need no declaration form.

Authentication: Workload Identity Federation. GitHub gives the job a
short-lived OpenID Connect token; Google exchanges it for a one-hour token of
the service account, only for this repository (by its numeric ID and its
owner's, which survive a rename) and `refs/heads/main`. No key is
stored. Only the jobs that talk to Google Play may request the token
(`id-token: write`): `play-internal` and `play-closed` in `android.yml`, and
the hand-run `play-release.yml`, `play-status.yml` and `play-listing.yml`
(its `publish` job).

Repository settings (Settings › Secrets and variables › Actions):

| Name | Kind | Value |
|---|---|---|
| `ENCAVE_KEYSTORE_BASE64`, `ENCAVE_KEYSTORE_PASSWORD`, `ENCAVE_KEY_ALIAS`, `ENCAVE_KEY_PASSWORD` | Secrets | The upload key (`docs/development.md`, "Android release") |
| `PLAY_WIF_PROVIDER` | Variable | `projects/<project number>/locations/global/workloadIdentityPools/github/providers/encave` |
| `PLAY_SERVICE_ACCOUNT` | Variable | `play-release@<project id>.iam.gserviceaccount.com` |
| `PLAY_CLOSED_TRACK` | Variable, optional | Unset: closed testing on the track `alpha` (the closed track every app has). Another closed track's name, or `none` to send nothing to closed testing |

### Google Play: one-time setup

In this order. `<project id>`, `<project number>` and the email addresses are
placeholders: never commit the real ones.

1. **Upload key.** Create the keystore and set the four `ENCAVE_KEY*`
   secrets (`docs/development.md`, "Android release").
2. **Signed bundle.** Actions › Android app › Run workflow, branch `main`.
   When the run ends, download the `encave-release-aab` artifact (a ZIP with
   `app-release.aab`). `play-internal` ends with a notice: expected.
3. **The app.** In the Play Console: Create app; name Encave; default
   language French (France); App; Free; accept the declarations. Then add
   English (United States) as a translation of the store listing.
   The Play Console asks for the package name when the app is created; it is
   permanent: `markeddoe.com.encave` (`applicationId` in
   `android/app/build.gradle.kts`; the Kotlin code stays in `app.encave`, and
   the Apple apps keep the bundle ID `app.encave`).
4. **Forms** (Dashboard › Set up your app), answers in `docs/STORE.md`: App
   access, Ads, Content rating, Target audience (18 and over), Data safety,
   Government apps, Financial features, Health, the privacy policy URL. The
   store listing texts and images are published by the CI ("Store
   listing"); its contact details and category are not (step 6). The
   Console lists what is still missing before a release can be rolled out.
5. **First bundle, by hand** (Google does not take the first one by API). Test
   and release › Testing › Internal testing › Testers: create an email list
   with your own address, save. Then Create new release: keep "Google-generated
   key" for Play App Signing (Google keeps the app signing key; ours is only
   the upload key), upload `app-release.aab` from step 2, Next, Save, and
   **Roll out to internal testing** (a release left as a draft keeps the app a
   draft, and the API then refuses releases).
6. **Closed testing** (no API for these; once, before the first closed
   release can go out):
   - Grow users › Store presence › Store settings: **App category** Food
     and drink (App), and **Store listing contact details**: an email
     address (required; shown on the store), the website
     `https://github.com/markedoe/encave` if wanted. Save.
   - Test and release › Testing › Closed testing › **Alpha** › Manage track.
     **Countries / regions** tab: Add countries / regions, tick the testers'
     countries (for example France and Switzerland), Add.
   - **Testers** tab: Email lists › Create email list, named "Friends", add
     the testers' Google account addresses (or choose Google Groups and enter
     the group's address), Save changes, tick the list. **Feedback URL or
     email address**: an address of yours or
     `https://github.com/markedoe/encave/issues`. Save.
   - Copy the **opt-in link** (same tab, "How testers join your test") and
     send it to the testers: each one accepts on the web, then installs from
     the Play Store.
   - Publishing overview: when **Managed publishing** is on, Google holds the
     changes until you click **Send changes for review** (the job then warns
     "Google Play kept these changes"). Leave it off to send each release
     automatically.
   - Policy and programs › App content: anything still marked "Start" or
     "Action needed" (step 4) blocks the review.

   A personal account created after November 2023 (the owner's) needs at
   least 12 testers opted in for 14 days in a row before it can apply for
   production. `PLAY_CLOSED_TRACK` stays unset (track `alpha`); a wrong name
   makes the job list the track names.
7. **Google Cloud** (console.cloud.google.com › Cloud Shell), the project,
   the service account and the Workload Identity Federation:

   ```bash
   PROJECT_ID="encave-release-$RANDOM"   # new and globally unique; note the printed values
   # The repository and its owner by number: a name can be reused after a rename or deletion.
   REPO_ID=$(curl -fsS https://api.github.com/repos/markedoe/encave | jq .id)
   OWNER_ID=$(curl -fsS https://api.github.com/repos/markedoe/encave | jq .owner.id)
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
     --attribute-mapping="google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.repository_owner_id=assertion.repository_owner_id,attribute.ref=assertion.ref" \
     --attribute-condition="assertion.repository_id == '$REPO_ID' && assertion.repository_owner_id == '$OWNER_ID' && assertion.ref == 'refs/heads/main'"
   PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
   gcloud iam service-accounts add-iam-policy-binding \
     "play-release@$PROJECT_ID.iam.gserviceaccount.com" \
     --role=roles/iam.workloadIdentityUser \
     --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository_id/$REPO_ID"
   echo "PLAY_WIF_PROVIDER=projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/encave"
   echo "PLAY_SERVICE_ACCOUNT=play-release@$PROJECT_ID.iam.gserviceaccount.com"
   ```

   The service account has no key and no role in the project: it can only
   be impersonated by runs on `main` of this repository.

   **Already set up by repository name** (before 2026-10-24)? Switch the
   existing provider and binding to the IDs, in Cloud Shell, once:

   ```bash
   gcloud config set project <project id>
   PROJECT_NUMBER=$(gcloud projects describe <project id> --format='value(projectNumber)')
   REPO_ID=$(curl -fsS https://api.github.com/repos/markedoe/encave | jq .id)
   OWNER_ID=$(curl -fsS https://api.github.com/repos/markedoe/encave | jq .owner.id)
   gcloud iam workload-identity-pools providers update-oidc encave --location=global \
     --workload-identity-pool=github \
     --attribute-mapping="google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.repository_owner_id=assertion.repository_owner_id,attribute.ref=assertion.ref" \
     --attribute-condition="assertion.repository_id == '$REPO_ID' && assertion.repository_owner_id == '$OWNER_ID' && assertion.ref == 'refs/heads/main'"
   SA="play-release@<project id>.iam.gserviceaccount.com"
   POOL="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github"
   gcloud iam service-accounts add-iam-policy-binding "$SA" --role=roles/iam.workloadIdentityUser \
     --member="$POOL/attribute.repository_id/$REPO_ID"
   gcloud iam service-accounts remove-iam-policy-binding "$SA" --role=roles/iam.workloadIdentityUser \
     --member="$POOL/attribute.repository/markedoe/encave"
   ```

   Then run Actions › Google Play status on `main` to check the token still
   comes.
8. **Invite the service account** in the Play Console: Users and
   permissions › Invite new users; email: the `PLAY_SERVICE_ACCOUNT` value;
   App permissions › Add app › Encave; tick **Release apps to testing
   tracks** (releases), **Manage store presence** (the store listing) and
   **Manage policy declarations** (the Data safety form), with **View app
   information (read-only)**, which they build on (add "Release to
   production, exclude devices, and use Play App Signing" only when
   production is turned on). Invite user (or, already invited: the service
   account › App permissions › Encave, tick, Apply). No account permission
   is needed. A job that lacks one names it in its error.
9. **GitHub variables**: set `PLAY_WIF_PROVIDER` and `PLAY_SERVICE_ACCOUNT`
   to the two values printed in step 7 (Settings › Secrets and variables ›
   Actions › Variables). Leave `PLAY_CLOSED_TRACK` unset (closed testing on
   the track `alpha`).
10. **Check**: Actions › Android app › Run workflow on `main`. `play-internal`
   ends with "released to the Google Play track internal", `play-closed` with
   "Google Play store listing updated" and "released to the Google Play track
   alpha". The first closed release then waits for Google's review (usually
   hours to a few days; the Console shows it under Publishing overview).

If a job fails, its message says which step is missing (app unknown, no
permission, app still a draft, unknown track). A closed release refused
because the listing or App content is incomplete is reported by Google in
the Console (Publishing overview, Inbox).

## Security

- Apple builds are signed in the cloud by App Store Connect: no certificate is
  stored. The App Store Connect API key is a repository secret.
- **Release secrets go only to code from `main`** (**done**). A manual run
  can start any workflow on any branch, with that branch's code: every job or
  step that reads `ASC_*`, `ENCAVE_KEY*` or `APPLE_TEAM_ID` has a
  `github.ref == 'refs/heads/main'` condition, so a branch run builds
  unsigned and uploads nothing. `scripts/check-workflow-secrets.py` (CI,
  `secrets` job) fails on a use without one.
- *Recommended, by the owner:* a GitHub Environment `release` (Settings ›
  Environments › New environment; Deployment branches and tags: Selected,
  `main` only) holding the release secrets, as a second lock. The workflows
  do not depend on it yet: moving the secrets there also needs
  `environment: release` on the jobs that read them.
- CI tools are pinned: actions by commit SHA (Dependabot, one grouped pull
  request a month), XcodeGen and xcbeautify by version and SHA-256
  (`apple/tools/install-build-tools.sh`), the Gradle distribution by its
  SHA-256 (`distributionSha256Sum`). Release bundles and R8 mappings are kept
  7 days as artifacts (Google Play keeps both).
- Google keeps the app signing key (Play App Signing); the upload key is a
  repository secret, used only by the `release` job; the Play API is reached
  through Workload Identity Federation, without a stored key, only from `main`.
- Dependabot keeps the pinned actions (one grouped pull request a month), the
  Swift packages of CaveKit and the Gradle dependencies up to date (**done**,
  `.github/dependabot.yml`).
- *(planned)* Build provenance attestations for the uploaded bundles.

## Work in progress

1. Faster tour: four jobs, no second tour on `main` (**done**).
2. Groundwork: version from release tags; store builds of a given commit;
   feature switches on both platforms.
3. App Store release tool and the train (Apple), then the urgent lane.
4. Google Play: internal testing, the emulator smoke test, closed testing
   and the store listing are on (the first closed release, 3981, was sent for
   Google's review on 2026-10-23); then production.
5. Sync compatibility test against the last release; hardening.
