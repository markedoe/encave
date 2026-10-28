# Remote sync for Encave without running infrastructure

*Decision document, October 2026. Scope: how Encave devices sync when they are
not on the same Wi-Fi (phone at a restaurant, Mac at home), between iPhone and
Android, and later between members of a household, while the maintainer runs
no servers, or as few as possible.*

Facts were checked online in October 2026. Sources are linked inline and
collected at the end. Claims marked **[unverified]** could not be confirmed
from a primary source and should be tested before anything depends on them.

> **Status (October 2026).**
> - Implemented:
>   - the storage-agnostic file protocol (snapshots, photo blobs, key grants,
>     key rotation): `RemoteSync.swift` and `RemoteSync.kt`, specified in
>     [SYNC.md](SYNC.md), section 8;
>   - the self-hostable relay (Node, or Cloudflare Workers + R2 without
>     Durable Objects, `relay/`), which keeps no tokens: a mailbox ID is
>     derived from its token. It differs from the design in section 7; see
>     the note there and [relay/README.md](../relay/README.md);
>   - its client in the Apple and Android apps;
>   - CloudKit (the user's private database, one zone per mailbox) in the Apple
>     app, shown only in builds that declare an iCloud container;
>   - WebDAV (section 6.3) in the Apple and Android apps: a folder on a NAS
>     (Synology, QNAP), Nextcloud, pCloud or any WebDAV server, with a username
>     and app password kept on each device (no Login Flow v2 yet).
> - Planned: Google Drive, the community relay decision, and signatures on files.
>   Dropbox later; Proton Drive only if it publishes an API.
>
> **Corrections to the analysis below:**
> - Pairing on the local network uses a commit-then-reveal key exchange with a
>   confirmation code (as in Bluetooth numeric comparison), not a PAKE.
> - A PAKE is needed only for remote 6-digit pairing through the relay rendezvous
>   (Phase 3 below; phase 6 in the roadmap).
> - V1 sends whole snapshots instead of per-device logs; logs remain an
>   optimisation for large cellars.
> - The protocol as built ([SYNC.md](SYNC.md), section 8) differs from the
>   design in section 10:
>   - a mailbox ID is `c` and the first 32 hex characters of the SHA-256 of
>     its token (`HKDF(K, "encave/v1/relay-auth")`), not
>     `HKDF(K, "encave/v1/mailbox")` in base32; the relay stores nothing;
>   - a file is `"ENCV" ‖ 0x01 ‖ kind ‖ 12-byte nonce ‖ ciphertext ‖ tag`
>     (ChaCha20-Poly1305, with the header and path as additional data): no
>     epoch, device or sequence header, no XChaCha20, no padding, and no
>     Ed25519 signature yet (planned);
>   - there is no `meta.bin`, `roster/`, `log/` or `encave-v1/` root: a
>     mailbox holds `snap/<device>/…` and `photo/<file ID>`;
>   - photos and documents are named after the SHA-256 of their plaintext and
>     encrypted with the epoch's data key (no per-photo keys), so they are
>     uploaded again after a key rotation;
>   - a new key goes to each remaining device as a grant in that device's own
>     inbox, not as `keys/<e+1>-<dev>.bin` in the old mailbox; two keys of the
>     same epoch are settled by the smaller SHA-256 of the key, not by the
>     lowest device ID;
>   - each device deletes its own snapshots beyond the newest two; nobody
>     deletes photo files or old mailboxes (a relay deletes mailboxes not
>     written to for 365 days); there is no 30-day clean-up;
>   - the CloudKit store uses neither CKSyncEngine, `CKDatabaseSubscription`
>     (no silent push: devices poll), `CKShare` nor iCloud Keychain. Each file
>     is an `EncaveFile` record named after its path, with the bytes in a
>     `CKAsset` field `data`, listed with zone change tokens.

---

## 1. Summary and recommendation

**No single zero-infrastructure transport covers all three scenarios.** Each
scenario has a different best answer:

| Scenario | Best zero-infra answer | Fallback |
|---|---|---|
| A. One person, Apple devices only (iPhone ↔ Mac ↔ iPad) | **CloudKit private database** (free, no setup, silent push) | Google Drive / relay |
| B. One person, iPhone ↔ Android, or Android ↔ Android | **Google Drive `appDataFolder` over plain REST** (signed in with the same Google account on both) | Relay, WebDAV |
| C1. Household, everyone on Apple | **CloudKit shared database via `CKShare`** | Relay |
| C2. Household, mixed iPhone + Android, different accounts | *No zero-infra option with good UX.* **Optional self-hostable relay** (Cloudflare Worker template), or a shared Nextcloud/WebDAV folder | — |

**Ranked recommendation**

1. **CloudKit (private + shared database) on Apple platforms.** Zero cost to
   the maintainer, data counts against the user's own iCloud quota, works for
   anyone signed in to iCloud with no extra account, and is the only option
   with server-initiated wake-ups (silent push) without any server of our own.
2. **Google Drive `appDataFolder` via REST on Android and, optionally, iOS.**
   The `drive.appdata` scope is *non-sensitive*: no security assessment, only
   brand verification. It covers the iPhone ↔ Android case for one person.
   It cannot be shared with another Google account.
3. **A small, open-source, self-hostable relay** (plain HTTP API on
   Cloudflare Workers + Durable Objects + R2, also packaged as a Docker
   image). It is the only way to serve mixed-platform households and remote
   6-digit-code pairing (the PAKE rendezvous in the roadmap). Ship the code
   and a "Deploy" template. Whether the maintainer also runs a *capped*
   community instance is a separate, reversible choice. The app must work
   fully without it.
4. **WebDAV / Nextcloud**, for power users and for households that already
   share a Nextcloud. Cheap to add once the protocol exists.
5. **Dropbox app folder**: possible later; extra review gate at 50 users.
6. **A folder picked through Files / the Storage Access Framework**: use it for
   **manual encrypted backup and export only**, not live sync. The providers'
   caching, placeholder and background-access behaviour make it unreliable.
7. **Not viable as store-and-forward:** Nostr relays, the Syncthing relay
   pool, IPFS pinning and Matrix. CloudKit Web Services from Android is
   documented but impractical.

**The key design decision is protocol first, transports second.** Every
backend above reduces to five operations: *list(prefix, since)*, *get*,
*put-if-absent*, *delete* and an optional *wake-up*. If the sync protocol uses
only **immutable, single-writer files** (per-device numbered log segments,
per-device snapshots, content-addressed photo blobs, epoch key files), each
backend becomes a thin adapter of a few hundred lines, and adding a second or
third backend costs little. Section 10 specifies this layout.

**Phased plan (details in section 11)**

- **Phase 0:** the storage-agnostic protocol (v1), plus a local-folder
  backend for tests.
- **Phase 1:** CloudKit private database on iPhone, iPad and Mac.
  Optionally, the cellar key can sync through iCloud Keychain so a user's own
  Apple devices need no pairing.
- **Phase 2:** Google Drive `appDataFolder` on Android and iOS.
- **Phase 3:** household sharing, with `CKShare` for Apple-only households and
  the self-hostable relay for mixed ones, including remote PAKE pairing.
- **Phase 4:** WebDAV/Nextcloud, then Dropbox if users ask for it.

---

## 2. Requirements and constraints

- **Privacy:** only ciphertext leaves the device. The provider may see
  metadata (account identity, file counts, sizes, timing, IP addresses), and
  the design should minimise it. No analytics and no third-party SDKs that
  receive user data. A plain REST API is preferred over a vendor SDK.
- **Data volume:** encrypted state of 50 KB to 2 MB, plus label photos of
  100 to 500 KB each, up to a few hundred (≈ 30 to 150 MB per cellar).
- **Merge model:** per-object last-writer-wins with Lamport stamps.
  Shipping a full encrypted snapshot plus per-device change logs is
  acceptable. This matters: the protocol needs no server-side logic.
- **Maintainer:** one person, public repository (relay AGPL-3.0, apps FSL-1.1-ALv2), does not want to run or
  pay for servers.
- **Implied constraints the choice must address:**
  1. **Remote pairing needs a rendezvous.** The roadmap's 6-digit code + PAKE
     flow requires a service that both devices can reach. With pure
     bring-your-own-storage, pairing has to happen in person (QR code on the
     same table) or through a store both devices can already access (same
     iCloud or Google account). "Zero relay" therefore also means no remote
     code pairing between different accounts.
  2. **Wake-up and background sync.** Only CloudKit can push a change to a
     sleeping iPhone without a server of ours (`CKDatabaseSubscription`
     silent notifications). Every file or REST backend is poll-only: on iOS
     that means syncing when the app comes to the foreground plus
     opportunistic `BGAppRefreshTask`; Android `WorkManager` periodic work
     (15-minute minimum); the Mac can poll freely. The restaurant → home case
     still works ("open the app, it pulls"), but changes do not appear on a
     closed phone in real time.
  3. **Mixed-platform households** have no zero-infrastructure option with good
     UX (see sections 5, 6 and 9). This is the honest justification for an optional
     relay.

---

## 3. Comparison table

### 3.1 Capabilities

| Option | Apple ↔ Apple (same user) | iPhone ↔ Android (same user) | Household, Apple only | Household, mixed | Wake-up push | Remote PAKE pairing |
|---|---|---|---|---|---|---|
| CloudKit private + `CKShare` | ✅ best | ❌ (Web Services: impractical) | ✅ `CKShare` | ❌ | ✅ silent push | ❌ |
| Google Drive `appDataFolder` (REST) | ✅ (needs Google account) | ✅ | ❌ (not shareable) | ❌ | ❌ poll | ❌ |
| Google Drive `drive.file` shared folder | ✅ | ✅ | ⚠️ | ⚠️ complex **[unverified]** | ❌ | ❌ |
| User folder via Files / SAF | ⚠️ iCloud Drive only | ⚠️ only Nextcloud-like providers | ⚠️ | ⚠️ | ❌ | ❌ |
| WebDAV / Nextcloud (direct REST) | ✅ | ✅ | ✅ (Nextcloud share) | ✅ (Nextcloud share) | ❌ poll | ❌ |
| Dropbox app folder (REST) | ✅ | ✅ | ⚠️ **[unverified]** | ⚠️ **[unverified]** | ❌ (longpoll API exists) | ❌ |
| Self-hostable relay (CF Workers + DO + R2) | ✅ | ✅ | ✅ | ✅ | ⚠️ WebSocket when open; no push without APNs/FCM | ✅ |
| Nostr relays | ⚠️ small events only | ⚠️ | ⚠️ | ⚠️ | ⚠️ live subscription | ⚠️ |
| Syncthing relay pool | live only | live only | live only | live only | — | — |
| IPFS pinning | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Matrix (matrix.org) | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |

### 3.2 Cost, effort and risk

| Option | Provider sees | Maintainer cost | User cost | Setup UX | Swift effort | Kotlin effort | Offline / concurrency | Store / legal risk | Maintenance |
|---|---|---|---|---|---|---|---|---|---|
| **CloudKit** | Apple ID ↔ app container, record count, sizes, timing, IP | **$0** | iCloud quota (5 GB free tier) | **None** (already signed in) | Low to medium (CKSyncEngine) | n/a | Excellent; atomic records | Very low | Very low (Apple keeps the API) |
| **Drive `appDataFolder`** | Google account, file names (random), sizes, timing, IP | $0 today; quota billing planned "later in 2026" above 400 M units/day/project | Google quota (15 GB shared) | One Google consent screen | Medium (OAuth PKCE + REST) | Medium (OAuth on Android is the hard part) | Good; immutable files avoid conflicts | Low; brand verification, policy compliance | Low to medium (Cloud project, OAuth changes) |
| **Files / SAF folder** | Provider: same as its own sync | $0 | Their storage | Pick a folder; confusing | Medium + long bug tail | Medium + long bug tail | **Poor**: stale caches, placeholders, conflicted copies | Low | **High** (per-provider bugs) |
| **WebDAV / Nextcloud** | Server admin: sizes, timing, IP | $0 | Own server / provider | URL + Login Flow v2 | Medium | Medium | Good with immutable files | Low | Low |
| **Dropbox app folder** | Dropbox account, sizes, timing | $0 | 2 GB free | One OAuth consent | Medium | Medium | Good (`rev`-based updates) | Production review at 50 users | Low to medium |
| **Own relay (Cloudflare)** | Maintainer/Cloudflare: mailbox IDs, sizes, timing, IP | $0 to about $5/month at small scale; grows with photos | $0 | **None** (built-in URL) or paste a URL | Low to medium | Low to medium | Excellent (server can enforce create-only) | Maintainer becomes a service operator (privacy policy, GDPR for IPs, abuse handling) | **Medium** (abuse, quotas, account risk) |
| **Nostr** | Relay: pubkeys, sizes, timing, IP | $0 | $0 | None | High | High | Unreliable retention | Abuse-of-public-infra perception | High |
| **Syncthing relays** | Relay: IPs, traffic volume | $0 | $0 | None | High | High | Both devices must be online | Policy of the volunteer pool | Medium |
| **IPFS** | Everyone with the CID can fetch ciphertext | Pinning fees | — | — | High | High | No mutable mailbox | — | High |
| **Matrix** | Homeserver: account, rooms, sizes, timing | $0 | Account on a homeserver | Account creation (bad) | High | High | OK but heavy | ToS of matrix.org | High |

---

## 4. Option 1: Apple CloudKit

> **As built:** the Apple app stores each file as a record in one zone per
> mailbox or inbox of the private database and polls; it uses no CKSyncEngine,
> silent push, `CKShare` or iCloud Keychain (see [SYNC.md](SYNC.md), section 8,
> "Storages"). The rest of this section is the original analysis.

### 4.1 How it works

- Each user has a **private database** per app container. Encave creates one
  **custom record zone per cellar** and stores opaque records: log segments,
  snapshots and photo blobs, all already encrypted by Encave.
- **`CKSyncEngine`** (iOS/iPadOS 17+, macOS 14+) handles batching, retries,
  account changes and change tokens. "You can have multiple instances … each
  targeting a different database … one syncing a person's private database
  and another syncing their shared database."
  ([Apple: CKSyncEngine](https://developer.apple.com/documentation/cloudkit/cksyncengine-5sie5))
- **Wake-ups:** by default the engine creates a `CKDatabaseSubscription`.
  The subscription "generates push notifications when CloudKit modifies records
  in a database" and the server "excludes the device where the change
  originates". This works for private and shared databases, and silent pushes
  need only the "Remote notifications" background mode, with no APNs server
  of ours.
  ([Apple: CKDatabaseSubscription](https://developer.apple.com/documentation/cloudkit/ckdatabasesubscription))
  Notifications are coalesced and best-effort, so they are a hint and not a
  delivery guarantee.
- **Household sharing:** a **zone-wide `CKShare`**
  (`CKShare(recordZoneID:)`) shares the whole cellar zone. "CloudKit limits
  the number of participants in a share to 100, and each participant must
  have an active iCloud account." Participants accept by tapping the share
  URL (sent via `UICloudSharingController`, Messages, etc.). Participants with
  write permission can modify any record. Read-only permission exists.
  ([Apple: CKShare](https://developer.apple.com/documentation/cloudkit/ckshare))

### 4.2 Cost and quotas

- **Maintainer: $0.** Records in the private database "are counted towards
  the owner's iCloud account storage quota" and records in the shared database
  "are counted towards the originating owner's iCloud account storage quota."
  ([private](https://developer.apple.com/documentation/cloudkit/ckdatabase/scope/private),
  [shared](https://developer.apple.com/documentation/cloudkit/ckdatabase/scope/shared))
  Neither is visible to the developer in the CloudKit Console.
- **User:** about 30 to 150 MB per cellar against their iCloud quota (5 GB on
  the free tier). Handle `CKError.quotaExceeded` with a clear message.
  Recommend downscaling label photos (for example 1600 px, JPEG q≈0.7) before
  encryption.
- **Limits:** a record is capped at **1 MB excluding assets**, so snapshots
  above ~700 KB and all photos go into `CKAsset` fields.
  ([archived Web Services limits](https://developer.apple.com/library/archive/documentation/DataManagement/Conceptual/CloudKitWebServicesReference/PropertyMetrics.html),
  [Core Data note on 1 MB](https://developer.apple.com/documentation/coredata/reading-cloudkit-records-for-core-data))
  Asset size limits are inconsistent across sources (50 MB in the 2016
  Web Services reference; "up to 50 GB" in an Apple engineer's forum reply,
  [thread](https://developer.apple.com/forums/thread/827897)). This is
  irrelevant at our sizes. Batches are capped at 250 records per request
  (CKSyncEngine docs above).
- **Known issue:** a report of `quotaExceeded` when a non-primary
  Family Sharing member creates a `CKShare`, which Apple DevRel called a
  likely bug ([thread 772198](https://developer.apple.com/forums/thread/772198)).
  **[unverified whether fixed]**

### 4.3 Privacy

Apple sees the Apple ID ↔ container association, zone and record names
(use random or derived IDs, never cellar names), record counts, sizes, timing
and IP addresses. Content is Encave ciphertext. Optionally, store the
ciphertext in `encryptedValues` for a second, Apple-managed layer. This
costs nothing but is not needed. For a CKShare, Apple also knows who shares
with whom: participant Apple IDs, emails and phone numbers. This is a
metadata leak inherent to the option and should be explained in the privacy
notes.

### 4.4 Setup UX

Best of all options. If the user is signed in to iCloud there is nothing to
configure: a toggle "Sync with iCloud" (default suggested on first pairing).
For the cellar key, two choices:

- **(a) keep the QR / code pairing** (the key never touches iCloud), or
- **(b) also store the cellar key as a synchronizable iCloud Keychain item**
  (`kSecAttrSynchronizable`), so the user's own Mac and iPad get it with no
  pairing. iCloud Keychain is end-to-end encrypted by Apple. This is
  compatible with the rule "anything sent over the network is E2E encrypted",
  but it moves trust in key distribution to Apple. Offer it as the default for
  *own devices* only, with the QR flow still available. **[Decision for the
  maintainer.]**

Household (Apple only): "Share cellar" → system share sheet → the invitee taps
the link → the cellar key is still transferred by QR / code (do not put
the cellar key in the CKShare). With (b) not applicable across Apple IDs, the
invitee pairs once.

### 4.5 CloudKit from Android (CloudKit Web Services / CloudKit JS)

**Technically documented, practically not recommended.**

- Flow: an API token from CloudKit Console; a request to `users/current`
  returns `AUTHENTICATION_REQUIRED` with a `redirectURL`; the user signs in
  with an **Apple ID** on Apple's page in a browser or web view; the
  `ckWebAuthToken` comes back on a custom callback URL.
  ([Apple: Web Services authentication](https://developer.apple.com/library/archive/documentation/DataManagement/Conceptual/CloudKitWebServicesReference/SettingUpWebServices.html))
- "Each token is intended for a single round trip to the server … the previous
  token is no longer valid." Lifetime: 30 minutes, or **2 weeks** with "Keep me
  signed in". So an Android user would have to sign in again with an Apple ID
  at least every two weeks, and a single lost response breaks the token chain.
- Forum reports of the private database returning access-denied with tokens
  that worked for the public database, unresolved
  ([thread 119159](https://developer.apple.com/forums/thread/119159),
  [thread 734787](https://developer.apple.com/forums/thread/734787)).
  Sign in with Apple identities are *not* CloudKit identities.
- Most Android users have no Apple ID. Shared-database access from Web Services
  or CloudKit JS is **[unverified]**.
- Verdict: do not build on it.

### 4.6 Effort, offline, concurrency, risks, maintenance

- **Swift:** about 1 to 2 weeks for a CKSyncEngine adapter over the protocol
  in section 10, plus about 1 week for CKShare UI (share sheet, accept,
  participants, leave). **Kotlin:** n/a.
- **Offline:** CKSyncEngine queues pending changes and retries. Records are
  atomic, and server change tokens give exact incremental fetches.
  With immutable single-writer records, `serverRecordChanged` conflicts never
  happen.
- **Store / legal:** native Apple service, no review risk. Declare iCloud use
  in the privacy notes. The CloudKit schema must be deployed to Production
  in the CloudKit Console before release.
- **Maintenance:** very low. The risks are Apple API deprecations (slow and
  announced) and the Apple-only lock-in, which the storage-agnostic protocol
  neutralises.

---

## 5. Option 2: Google Drive `appDataFolder` via REST

### 5.1 How it works

- The app stores files in a hidden per-app folder: "only accessible by your
  app and its contents are hidden from the user and from other Google Drive
  apps", "can't be accessed using the Drive user interface", "deleted when a
  user uninstalls your app from their My Drive". Files in it **cannot be
  shared** (`notSupportedForAppDataFolderFiles`), trashed or moved.
  ([Google: appDataFolder](https://developers.google.com/workspace/drive/api/guides/appdata))
- Plain REST: `files.list?spaces=appDataFolder`, `files.create` (multipart or
  resumable upload), `files.get?alt=media`, `files.delete`, and
  `changes.list?spaces=appDataFolder` for incremental polling. No Google SDK
  is required for the API calls.
- **Same folder from iOS and Android:** application data is tied to the
  Google Cloud project, so Android and iOS OAuth clients created in the same
  project should see the same folder. **[unverified: test with two client IDs
  in one project before committing; it is load-bearing for iPhone ↔ Android.]**

### 5.2 Scope sensitivity and verification

- `drive.appdata` and `drive.file` are **non-sensitive**; `drive` is
  **restricted**.
  ([Google: Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth))
- Non-sensitive scopes do not require OAuth app verification and therefore
  no CASA security assessment. To show the app name and logo on the consent
  screen, the app needs "a lighter-weight verification process known as
  brand-verification" ([Google: verification](https://support.google.com/cloud/answer/13463073)).
  Brand verification needs a homepage and a privacy policy on a domain the
  maintainer has verified (Search Console). Whether a `github.io` subdomain
  qualifies is **[unverified]**: plan on a small owned domain with a static
  page.
- **Publishing status matters.** In "Testing" status, at most 100 listed
  test users, and "authorizations by a test user will expire seven days from
  the time of consent" ([Google: app audience](https://support.google.com/cloud/answer/15549945)).
  The app must be set to **In production**. The 100-user lifetime cap applies
  only to *unapproved sensitive or restricted* scopes, so it does not apply
  here.
- Compliance: the Google API Services User Data Policy (Limited Use) applies.
  Encave never reads the data server-side, so it is compliant by design.

### 5.3 OAuth on each platform (the hard part)

- **iOS:** an "iOS" OAuth client, Authorization Code + PKCE through
  `ASWebAuthenticationSession`, redirect on the reversed client-ID URL
  scheme. About 200 lines without an SDK. Store the refresh token in the
  Keychain.
- **Android:** since 2023, "new Android apps will no longer be allowed to use
  Custom URI schemes to make authorization requests" by default; Google
  recommends its Identity Services SDK, and the custom scheme can be turned
  back on under the client's Advanced Settings
  ([Google blog](https://developers.googleblog.com/en/improving-user-safety-in-oauth-flows-through-new-oauth-custom-uri-scheme-restrictions/)).
  Two choices:
  - **(a)** the Google Play services Authorization API. It is a Google
    SDK, but it only performs the authorization and is part of Play services
    already on most devices. It **requires Google Play services**, so it
    fails on de-Googled phones (for example GrapheneOS without sandboxed
    Play).
  - **(b)** enable the custom scheme and use Custom Tabs + PKCE (plain REST).
    It works on any device with a browser, Play services or not, so it is the
    more portable choice. Its risk is policy: Google could remove the opt-out.

  Recommend (b) to respect the "plain REST" preference, with (a) as a fallback
  if Google tightens the policy. **[unverified: whether Google will keep the
  opt-out.]**

### 5.4 Quotas and cost

- "All standard use of the Google Drive API is available at no additional
  cost." Per-method quota units: `files.get` 5, `files.list` 100,
  `files.download` 200, `files.update` 50. Per project: 1,000,000 units/min;
  per user: 325,000 units/min.
  ([Google: Drive limits](https://developers.google.com/workspace/drive/api/guides/limits))
- **New in 2026:** "Exceeding the quota request limits is planned to incur
  charges to your Google Cloud billing account later in 2026". Usage below
  **400,000,000 quota units per day per project** should not be billed, and
  "Cloud projects created on or after May 1, 2026 are subject to the new API
  quotas." The quota is per *project*, so costs land on the **maintainer's**
  project, not the user's.
- **Estimate:** a device polling `changes.list` 24 times a day in the
  background plus about 10 foreground syncs, downloading about 10 new
  segments, uses ≈ 34 × 100 + 10 × 200 ≈ **5,400 units/day**. That is about
  **74,000 active devices/day** before reaching 400 M. First sync of a
  300-photo cellar costs ≈ 60,000 units, a one-off.
- **Mitigation:** never attach a billing account to the project, so the
  financial ceiling is literally zero. Over-quota requests should then fail
  with 403/429 and the app backs off. **[unverified: how unbilled projects
  will be treated once billing starts.]** Also poll adaptively: on
  foreground and after local changes, not on a fixed fast timer.
- User storage counts against their Google quota (15 GB shared with Gmail and
  Photos). **[unverified for appDataFolder specifically, but assumed.]**

### 5.5 Privacy, UX, effort, risks

- **Google sees:** the Google account, file names (make them random/derived),
  counts, sizes, timing, IPs. Content is ciphertext.
- **UX:** "Sync with Google Drive" → system browser consent → done. iPhone
  users must sign in to Google, which is acceptable for the iPhone ↔ Android
  case and unnecessary for Apple-only users.
- **No household sharing:** files cannot be shared, so a household member
  with another Google account cannot read the mailbox. The `drive.file` scope
  allows a visible, shareable folder, but the invitee's app can only see files
  "that you open with an app", which on mobile implies the Google Picker
  (web-first) or Drive's own UI. **[unverified on Android/iOS; not
  recommended.]**
- **Effort:** Kotlin ~1.5 to 2 weeks (OAuth, REST, `changes.list`, resumable
  upload, WorkManager); Swift ~1 to 1.5 weeks, reusing the protocol.
- **Offline / concurrency:** good when every file is immutable and
  single-writer. Drive allows duplicate names, so use deterministic names and
  dedupe on read. `files.generateIds` (5 units) can pre-allocate IDs.
- **Store:** App Store guideline 4.8 is not triggered, because the Google
  sign-in is not used "to set up or authenticate the user's primary account
  with the app". 5.1.1(v) requires the app to work without login, which it
  does, since sync is optional.
  ([Apple: Review Guidelines](https://developer.apple.com/app-store/review/guidelines/))
- **Maintenance:** low to medium: one Cloud project, a consent-screen
  config, a privacy-policy page, watching Google policy emails and quota
  changes.

---

## 6. Option 3: a user-chosen cloud folder as an encrypted mailbox

### 6.1 iCloud Drive / Files app (iOS, macOS)

- **App's own ubiquity container** (`FileManager.url(forUbiquityContainerIdentifier:)`):
  works across the user's Apple devices. All access must go through
  `NSFileCoordinator`. Files may be placeholders (`startDownloadingUbiquitousItem`
  plus an `NSMetadataQuery` to watch), and conflicts surface as `NSFileVersion`
  ([Apple TN2336](https://developer.apple.com/library/archive/technotes/tn2336/_index.html)).
  The container can intermittently become unavailable after updates
  ([forum 765828](https://developer.apple.com/forums/thread/765828)).
  It is strictly worse than CloudKit for our case: no Android, no push, and
  more failure modes.
- **A folder in a third-party provider** picked with
  `UIDocumentPickerViewController` (Google Drive, Dropbox, OneDrive, Nextcloud
  File Provider extensions): kept via security-scoped bookmarks. Reads and
  writes depend on the provider extension downloading and uploading in its
  own time; background access is limited.
- **Real-world evidence:** Logseq staff on iCloud folder sync: "iCloud can
  sometimes have difficulty with small-file sync and conflict resolution",
  uploads "stuck … without any reason", duplicate "(1)" files and lost edits
  ([Logseq forum](https://discuss.logseq.com/t/im-using-logseq-with-icloud-but-experiencing-data-loss-or-file-conflict-errors-whats-going-on/13393)).
  KeePassium's guide says its direct Google Drive connection is more reliable
  than the Files app route, and Google Drive does not work offline even for
  files "made available offline"
  ([KeePassium](https://support.keepassium.com/kb/sync-google-drive/),
  [supported sources](https://keepassium.com/articles/supported-sync-sources)).

### 6.2 Android Storage Access Framework (SAF)

- `ACTION_OPEN_DOCUMENT_TREE` gives a folder URI with persistable permission.
  But **Google Drive's provider does not offer folder (tree) selection**
  (single files only), according to user reports
  ([Cryptomator forum](https://community.cryptomator.org/t/cryptomator-not-usable-without-webdav-or-not-listed-cloud-storages/11933/16)),
  and Dropbox's Android app historically did not implement `ACTION_OPEN_DOCUMENT`
  ([Dropbox forum](https://dropboxforum.com/discussions/101000042/why-using-intent-action-open-document-does-not-list-the-dropbox/209654)).
  **[unverified for current app versions]**
- Caching and refresh are the provider's job: a KeePassDX maintainer notes
  Google Drive via SAF "has caching and synchronization problems"; a
  commenter saw "catastrophic 0 byte writes" through SAF WebDAV mounts
  ([KeePassDX #1264](https://github.com/Kunzisoft/KeePassDX/issues/1264),
  [#342](https://github.com/Kunzisoft/KeePassDX/issues/342)).
- The common denominator between iOS Files and Android SAF that actually works
  as a *folder* on both is essentially Nextcloud, and then talking WebDAV
  directly is simpler and more reliable (6.3).

### 6.3 WebDAV / Nextcloud (direct HTTP)

**Status: done** (`WebDAVMailbox.swift`, `WebDavMailbox.kt`; mapping in
[SYNC.md](SYNC.md), section 8, "Storages"). Generic WebDAV with a username and
app password covers NAS boxes, Nextcloud and pCloud
(`webdav.pcloud.com`, or `ewebdav.pcloud.com` for accounts in the EU region
**[unverified]**)
without registering any app. Each device signs in with its own login; only the
folder URL travels in grants. `If-None-Match: *` is sent on every `PUT`, but
the protocol stays safe on a server that ignores it: snapshot and grant names
end with a random part, so they are never written twice, and a photo or
document file written again by another device has the same content. Not yet tried on real servers: Nextcloud,
Synology, QNAP and pCloud behaviour (status codes for missing folders,
`If-None-Match`, PROPFIND hrefs) is covered by tests against a fake server only.

- **Auth:** Nextcloud Login Flow v2 opens the server's login page in the
  browser and returns an **app password** the user can revoke
  ([Nextcloud Login Flow](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html)).
  Generic WebDAV uses URL + user + app password (Basic auth over TLS).
- **Operations:** `PROPFIND Depth: 1` (list), `GET`, `PUT`, `DELETE`, `MKCOL`.
  Use `If-None-Match: *` on `PUT` for create-only writes where supported
  (standard HTTP conditional requests; sabre/dav-based servers such as
  Nextcloud are expected to honour it). **[unverified per server: test
  Nextcloud, ownCloud, Synology, Hetzner Storage Box]**
- **Joplin's experience:** WebDAV works but servers differ: lock-file
  conflicts and leftover locks (HTTP 423), and providers that rate-limit
  frequent overwrites of `/locks` files
  ([Joplin lock spec](https://joplinapp.org/help/dev/spec/sync_lock),
  [forum](https://discourse.joplinapp.org/t/rate-limited-joplin-overwrites-files-in-the-locks-folder-too-often/38005)).
  Encave's design needs no locks at all (section 10).
- **Household:** one Nextcloud user shares the folder with another; each
  member's app logs in with its own account. This is the one
  zero-maintainer-infra path for mixed households, for people who already
  have Nextcloud.
- **Effort:** about 1 week per platform (URLSession / OkHttp + a small XML
  parser). **Maintenance:** low; the long tail is server quirks.

### 6.4 Dropbox API (App folder)

- Access type **App folder**: "A dedicated folder named after your app is
  created within the Apps folder … Your app gets read and write access to this
  folder only."
  ([Dropbox developer guide](https://docs.dropboxapi.com/dropbox-api/docs/developer-resources/developer-guide))
- **Review gate:** a development app can link up to 500 users; "once your app
  links 50 Dropbox users, you will have two weeks to apply for and receive
  production status approval". Applications are not reviewed before 50 users.
  The review is reportedly light (a few working days).
- OAuth Code + PKCE without client secret, short-lived access tokens + refresh
  tokens ([Dropbox OAuth guide](https://developers.dropbox.com/oauth-guide)).
  **[details not re-verified this session]** Uploads with `mode=add` and
  `autorename=false` fail on name collision. This gives create-only semantics.
  `/files/list_folder/longpoll` can wait for changes.
- Free Basic accounts have 2 GB. Sharing an app folder with another Dropbox
  user is **[unverified]**.
- **Verdict:** fine as an extra adapter, but fewer users have Dropbox than
  Google or iCloud, and it adds a review step.

### 6.5 How other apps do it

| App | Approach | Lesson |
|---|---|---|
| **Joplin** | Pluggable targets (file system, WebDAV, Nextcloud, Dropbox, OneDrive, S3, Joplin Server/Cloud). One file per item, E2EE with master keys, lock files for upgrades. ([E2EE spec](https://joplinapp.org/help/dev/spec/e2ee), [targets](https://mynixos.com/home-manager/option/programs.joplin-desktop.sync.target)) | Pluggable dumb storage works; lock files are the fragile part; many small files are slow on Dropbox. |
| **Obsidian Remotely Save** | S3/R2/B2, Dropbox, OneDrive app folder, WebDAV; optional encryption before upload; "no conflict resolution". ([repo](https://github.com/scruel/obsidian-remotely-save)) | Shows app-folder scopes are practical; whole-file sync without merge loses data. |
| **KeePassXC / KeePassium / Strongbox** | One encrypted database file; download, merge, upload. Direct provider APIs preferred over Files app. | Merge-on-sync works for a single file, but direct APIs beat OS file pickers. |
| **Logseq** | Plain files in iCloud/Dropbox; widespread conflict and data-loss reports; built its own sync service. ([forum](https://discuss.logseq.com/t/im-using-logseq-with-icloud-but-experiencing-data-loss-or-file-conflict-errors-whats-going-on/13393)) | Mutable files in consumer sync folders are dangerous. |
| **Standard Notes** | Own E2EE sync server, self-hostable. **[not re-verified]** | A dedicated relay gives the best UX, at the cost of running servers. |
| **Actual Budget** | Sync server that "is only passing around changes", optional E2EE with a separate password; CRDT messages. ([docs](https://actualbudget.org/docs/getting-started/sync/)) | A message-relay server plus client-side merge, which is close to Encave's model. |
| **Anytype** | any-sync network; free remote node with 1 GB of files; relay is a "blind orchestrator"; self-hostable. ([docs](https://doc.anytype.io/anytype-docs/advanced/data-and-security/self-hosting), [sync](https://doc.anytype.io/anytype/data/sync-and-backup)) | Even E2EE-first projects run infra and cap photos/files; self-hosting is complex. |

### 6.6 Pitfalls of file-based sync, and mitigations

| Pitfall | Mitigation in Encave's protocol |
|---|---|
| Two devices overwrite the same file → conflicted copies or lost writes | **Single-writer prefixes:** each device writes only under `log/<deviceId>/` and `snap/<deviceId>/`. |
| "Append" is not atomic on Drive, WebDAV or object stores; readers see torn files | **No appends:** the append-only log is *logical*. Physically it is numbered immutable segments (`000123.bin`), created once and never modified. |
| Partially uploaded files are visible | AEAD (Poly1305) rejects truncated files; a reader treats them as "not yet there" and retries. |
| Placeholders / stale caches | Talk to provider APIs directly (CloudKit, Drive REST, WebDAV), not OS file pickers. |
| Listing is eventually consistent | Tolerate missing segments (gaps) and fetch again later; Lamport-LWW merge is idempotent and order-independent. |
| Blob referenced before it is uploaded | Upload blobs first, then the segment; readers retry missing blobs. |
| Deletion races | Deletion of remote files is done only by GC after a grace period, never as part of normal sync. |
| Lock files | **None needed.** |

---

## 7. Option 4: a minimal serverless relay the maintainer deploys once

### 7.1 Design

A tiny HTTP service with the same five operations, scoped per mailbox:

```
PUT    /v1/m/{mailboxId}/{path}        create-only (409 if exists), body = ciphertext
GET    /v1/m/{mailboxId}/{path}
GET    /v1/m/{mailboxId}?prefix=log/&after={cursor}     list (JSON)
DELETE /v1/m/{mailboxId}/{path}        (GC, by any key holder)
GET    /v1/m/{mailboxId}/watch         WebSocket: "something changed" pings
POST   /v1/rendezvous/{slot}           PAKE message exchange for 6-digit pairing (TTL 2 min)
Authorization: Bearer <token derived from the cellar key>  (relay stores only a hash)
```

**Cloudflare mapping:** a Worker for routing, auth and rate limits; one
**Durable Object per mailbox** holding the file index in SQLite and the
WebSocket fan-out (hibernation API); **R2** for blob bodies over a few KB.

> **As built (October 2026), see [relay/README.md](../relay/README.md):**
> - an existing file gives 412, not 409 (409 means a folder on the path is a
>   file, or the path is a folder; the Node server and the Worker agree, see
>   `relay/test/conformance.mjs`);
> - no Durable Objects: the Worker lists and stores everything in R2;
> - no WebSocket watch: devices poll;
> - no rendezvous endpoint yet (remote pairing is planned);
> - mailboxes not written to by their owner for 365 days are deleted, not after
>   30 days; an inbox only others wrote to (unclaimed) is deleted 90 days after
>   the first drop, has a cap of its own and never takes a cellar's room; the
>   owner listing it claims it (the apps need no change: they list their inbox
>   every round, and drops into an inbox not seen yet still work);
> - the mailbox ID is derived from its token, so the relay stores no hash;
> - limits per file, mailbox, inbox and for the whole relay, per-client limits
>   (requests a minute, bytes uploaded and new mailboxes a day, 429), and an
>   optional access key for private relays;
> - a Cloudflare relay without totals yet answers 503 for files over 4 KB.
>
> The costs in section 7.2 assume Durable Objects and do not apply as such:
> the relay as built uses Workers requests and R2 only.

### 7.2 Cloudflare free tier (October 2026)

| Product | Free plan | Paid |
|---|---|---|
| Workers | 100,000 requests/day; 10 ms CPU/request | $5/month minimum, 10 M requests/month included |
| Durable Objects (SQLite only on Free) | 100,000 requests/day; 13,000 GB-s/day; 5 M rows read/day; 100,000 rows written/day; 5 GB total | 1 M requests/month incl. then $0.15/M; 5 GB-month incl. then $0.20/GB-month |
| WebSocket messages to DOs | billed 20:1 (100 incoming = 5 requests); outgoing free; hibernation stops duration charges | same |
| KV | 100,000 reads/day; 1,000 writes/day; 1 GB | — |
| R2 (Standard) | 10 GB-month; 1 M Class A; 10 M Class B per month; **egress free** | $0.015/GB-month; $4.50/M Class A; $0.36/M Class B |

Sources: [Workers limits](https://developers.cloudflare.com/workers/platform/limits/),
[Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/),
[DO pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/),
[DO free tier changelog](https://developers.cloudflare.com/changelog/2025-04-07-durable-objects-free-tier/),
[R2 pricing](https://developers.cloudflare.com/r2/pricing/).
R2 reportedly requires a payment method on file even within the free tier
**[unverified, from third-party guides]**. KV's 1,000 writes/day rules it out
as the main store.

**Cost if Encave becomes popular (rough):**

- **Storage is dominated by photos.** About 50 MB/cellar → the 10 GB free
  tier covers ~200 cellars.
- **10,000 cellars:** ≈ 500 GB → ≈ $7.50/month in R2. Requests at ~50/device/day
  × 20,000 devices ≈ 30 M/month → Workers Paid ($5) + a few dollars.
  **Total ≈ $15 to 30/month.**
- **100,000 cellars:** ≈ $100 to 250/month.
- Cheap, but not zero, and it scales with success.
- **Cost-control lever:** make the relay a *mailbox, not a backup*. Delete
  log segments once every known device has acknowledged them (plus a
  30-day TTL), and keep photos only until all devices have them (or a 30-day
  TTL). Storage then stays proportional to in-flight data rather than total
  cellars. The trade-off: the roadmap's "relay keeps an encrypted copy for
  recovery" would move to CloudKit/Drive or to manual backups.

### 7.3 Abuse, quotas and suspension risk

- **Abuse:** an open create-only blob store is a free file host. Mitigations:
  - per-mailbox quotas (for example 300 MB and 5,000 files);
  - per-IP and per-mailbox rate limits (Workers rate-limiting binding);
  - a max object size (for example 2 MB);
  - mailbox creation behind a small proof-of-work;
  - TTLs, so abandoned mailboxes vanish;
  - no public read URLs: every read requires the bearer token, so the relay
    cannot be used to distribute content to people without the key.

  App Attest / Play Integrity would help, but they are vendor attestation
  SDKs/APIs and clash with the "no third-party SDK" rule; avoid them.
- **Free-tier exhaustion:** daily limits reset at 00:00 UTC, and "if you
  exceed one, further operations of that type fail". A popular day would break
  sync for everyone until midnight UTC. On Paid it simply costs more. A
  monthly budget alert is essential.
- **Account suspension:** a single maintainer account is a single point of
  failure (billing problem, ToS dispute, abuse report). Mitigate by making the
  relay URL configurable and keeping the app fully functional without it.
- **Platform churn:** **Deno Deploy Classic was shut down on 20 July 2026**,
  and projects were not migrated automatically
  ([Deno docs](https://docs.deno.com/deploy/classic)). Any maintainer-run
  relay must be portable (plain HTTP + S3-compatible storage) and
  self-hostable from day one.

### 7.4 Alternatives to Cloudflare

| Platform | Free tier (2026) | Fit |
|---|---|---|
| **Deno Deploy (new)** | Free tier with ~1 M requests/month and a 1 GiB KV, figures vary by source **[unverified]** ([third-party summary](https://flaviocopes.com/hosting-free-tiers.md)) | OK for metadata; photos need external storage; Classic shutdown shows churn risk. |
| **Fly.io** | **No free tier for new customers** since 7 Oct 2024; short trial, then ~$2/month minimum for a tiny machine ([Fly docs](https://fly.io/docs/about/discontinued-plans/)) | Fine for the Docker self-host image; not "free". |
| **Supabase** | 1 GB file storage, 500 MB DB, 5 GB bandwidth; **free projects pause after one week of inactivity** **[secondary sources]** ([summary](https://automationatlas.io/answers/supabase-free-tier-limits-2026/)) | Pausing is unacceptable for a relay; vendor SDK culture. |
| **Generic S3-compatible bucket** (R2, B2, MinIO, Hetzner) | Varies | Users could point the app at their own bucket with keys. Too technical for most, but trivial to support once the protocol exists (`If-None-Match: *` conditional PUT is supported by R2 and S3 **[verify per provider]**). |

### 7.5 Privacy, UX, effort, risks

- **Relay sees:** mailbox IDs (random, rotate with key epochs), object
  counts, sizes (pad them), timing, IP addresses. No accounts, no emails.
  The same IP polling several mailboxes links them; this is unavoidable
  without Tor or a mixnet.
- **UX:** the best cross-platform UX. Nothing to sign in to; pairing by QR
  or 6-digit code works anywhere.
- **Self-hosting:** Settings → "Relay: Encave community / Custom URL". Ship a
  "Deploy to Cloudflare" template (Wrangler config) and a single-binary or
  Docker image using SQLite + filesystem for VPS, NAS or Raspberry Pi users.
  The relay URL can be embedded in the pairing QR, as in the roadmap, so a
  household on a self-hosted relay pairs normally.
- **Effort:** Worker + DO + R2 ≈ 1 to 2 weeks; Docker variant ≈ 1 week;
  client adapters ≈ 3 to 5 days per platform (simplest of all backends).
- **Legal:** running a public relay makes the maintainer a service operator:
  - a privacy policy is needed;
  - IP addresses are personal data under GDPR, so define log retention
    (none, or 24 h);
  - EU DSA hosting-service duties are light for a micro-provider but include
    a contact point and notice handling **[unverified scope]**;
  - ciphertext the operator cannot decrypt is low-risk, but this is not
    legal advice.

  Self-hosted instances move these duties to whoever runs them.
- **Maintenance:** medium if a public instance exists (abuse, quota alerts,
  dependency updates, the occasional Cloudflare change). Near zero if only the
  template is published and users self-host.

---

## 8. Option 5: public decentralised relays

| Network | How it would work | Findings | Verdict |
|---|---|---|---|
| **Nostr** | Encrypted events (NIP-44 payload encryption; NIP-78 kind 30078 "application-specific data") published to public relays; photos on Blossom servers by SHA-256. | Event size is per relay: the NIP-11 example uses `max_message_length` 16,384; strfry's packaged defaults are `maxEventSize` 65,536 and a WebSocket payload of 131,072 **[NixOS defaults; upstream unverified]** ([NIP-11](https://nips.4rs.nl/nips/11), [strfry options](https://mynixos.com/nixpkgs/option/services.strfry.settings), [NIP-78](https://scrapbox.io/nostr/NIP-78)). Retention is set by each operator and not guaranteed; Blossom retention and payment are server-defined ([Blossom](https://d-central.tech/blossom-protocol-reference/)). | A 2 MB snapshot would be split into 30+ events, and storage depends on volunteers who may prune. Using free public relays as a cellar backup is also poor citizenship. **Not viable for store-and-forward.** Possibly usable for tiny "changed" pings or a PAKE rendezvous, but not worth the complexity. |
| **Syncthing relay pool** | Volunteer relays forward bytes between two connected devices. | Relays are used only when devices can't connect directly; session mode forwards bytes; the relay sees IPs and traffic volume ([Syncthing docs](https://docs.syncthing.net/users/relaying)). No storage: both devices must be online at once **[storage absence by design; not stated explicitly]**. | Solves NAT traversal, not store-and-forward. Phones are rarely online simultaneously. **No.** |
| **IPFS pinning** | Upload ciphertext, pin it, share CIDs. | Free pinning tiers are small (Pinata ~1 GB **[third-party]** ([summary](https://agentdeals.dev/vendor/pinata-ipfs))); pinning needs an API key (the maintainer's or each user's); ciphertext is publicly fetchable by anyone with the CID; mutable pointers (IPNS) are slow; no change notification. | **No.** |
| **Matrix** | Each device or user has a Matrix account; encrypted room events and media. | matrix.org's free plan caps attachments at **10 MB** and data at **100 MB per 24 h / 500 MB per 28 days** ([matrix.org pricing](https://matrix.org/homeserver/pricing/)); account creation on a homeserver is heavy UX; the SDKs are large; ToS on non-chat use **[unverified]**. | Possible in theory (Matrix rooms are a store-and-forward log), but too heavy and leaks a social graph. **No.** |

---

## 9. Option 6: hybrid strategies

- **H1: CloudKit for Apple ↔ Apple, Google Drive for anything with Android
  (recommended core).** Covers scenarios A, B and C1 with $0 maintainer cost
  and no servers. An Apple user who adds an Android device enables "Google
  Drive sync" on all their devices; the iPhone then mirrors to both backends,
  which the protocol makes trivial (the same files go to two stores).
- **H2: H1 + an optional self-hostable relay (recommended full plan).** Adds
  C2 (mixed households) and remote 6-digit pairing. The maintainer chooses
  between:
  - (i) publishing only the template, so users or a tech-savvy household
    member deploy it on their own free Cloudflare account in ~5 minutes; or
  - (ii) also running a capped "community relay" with TTL-based mailbox
    semantics and a public budget.

  Start with (i); move to (ii) only if demand justifies it.
- **H3: "Bring your own storage" by default + relay as an option.**
  Philosophically clean but worse UX: every user must pick and authorise a
  storage before syncing, even Apple-only users who could have had zero setup
  with CloudKit. CloudKit *is* bring-your-own-storage (the user's iCloud), so
  H1 already satisfies the spirit.
- **H4: relay-first (the current roadmap).** Best UX everywhere, but it
  contradicts "no infrastructure" and makes the maintainer pay for success.
  Keep the relay *capable* of being the default, but not *required*.

**One more benefit of multi-backend support:** sync becomes redundant. A device
can push to every enabled backend, and pulls merge idempotently, so losing
one provider loses nothing.

---

## 10. Recommended protocol and storage layout

> **Superseded:** the implementation differs from this design (keys, layout,
> file format, rotation and clean-up); see [SYNC.md](SYNC.md), section 8, and
> the corrections in the status at the top of this document. This section is
> kept as the original analysis.

The same layout serves every backend, so one sync core (Rust, per the
roadmap, or Swift and Kotlin with shared test vectors until then) drives all
of them.

### 10.1 Keys

| Key | Derivation / origin | Use |
|---|---|---|
| `K_e` cellar key, epoch *e* | random 32 bytes; shared at pairing (existing X25519 + HKDF + ChaCha20-Poly1305 flow) | root of everything in epoch *e* |
| `mailboxId_e` | `HKDF(K_e, info="encave/v1/mailbox")`, 16 bytes → base32 | folder / zone / relay namespace; reveals nothing about `K_e`; changes on rotation |
| `K_data_e` | `HKDF(K_e, "encave/v1/data")` | encrypts log segments, snapshots, roster |
| `relayToken_e` | `HKDF(K_e, "encave/v1/relay-auth")` | bearer token; the relay stores `SHA-256(token)` at mailbox creation |
| `K_name_e` | `HKDF(K_e, "encave/v1/names")` | optional keyed names (avoid leaking device IDs) |
| Device identity | long-term X25519 (key wrapping) + Ed25519 (signing) per device, in Keychain / Android Keystore-protected storage; public keys in the roster | key rotation, authorship, view-only enforcement |
| Per-photo key | random 32 bytes per photo, stored inside the encrypted state record of the bottle | photos survive key rotation without re-upload |

### 10.2 Layout

```
<root>/                                   CloudKit: zone "<mailboxId>" in private DB
  encave-v1/<mailboxId_e>/                Drive: appDataFolder, names = full path
    meta.bin            plaintext JSON: {format:1, epoch:e, created:<day>}    (no secrets)
    roster/<lamport>-<dev>.bin            signed device list: device IDs, public keys,
                                          role (owner/editor/viewer), display names (encrypted)
    log/<dev>/<seq:012>.bin               immutable segment: batch of object changes
    snap/<dev>/<lamport:016>.bin          full encrypted cellar state + heads {dev: seq}
    blob/<id[0:2]>/<id>.bin               photo ciphertext; id = SHA-256(ciphertext)
    keys/<e+1>-<dev>.bin                  only after rotation: K_{e+1} wrapped per remaining device
```

**File format** (every `.bin` except `meta.bin`):

```
magic "ENCV" | version u8 | kind u8 | epoch u32 | deviceId 16B | seq/lamport u64
| nonce 24B | XChaCha20-Poly1305( K_data_e, plaintext, AAD = header ‖ path )
| Ed25519 signature by the device over (header ‖ ciphertext)
```

- Binding the **path** in the AAD stops a storage provider from swapping or
  renaming files undetected.
- The **signature** lets readers reject writes from removed devices or from
  view-only members. A shared symmetric key alone cannot enforce "view only".
- **Padding:** pad logs to 4 KB multiples and photos to 64 KB multiples to
  blur sizes. This is cheap.

### 10.3 Sync algorithm

1. **Push.** Batch local changes since the last push into one segment with
   the next `seq`. First upload any new photo blobs (blob ID = hash of
   ciphertext, so the upload is idempotent), then
   `put-if-absent(log/<me>/<seq>.bin)`. If the put fails because the file
   exists (crash after upload), read it back, verify and continue.
2. **Pull.**
   - **CloudKit:** CKSyncEngine fetches changed records.
   - **Drive:** `changes.list` since the stored page token.
   - **WebDAV / relay:** list `log/` and keep segments with `seq` above the
     known head for each device.

   Decrypt, verify the signature against the roster, and apply
   **Lamport-LWW** per object. The merge is idempotent and commutative, so
   order and duplicates don't matter.
3. **Bootstrap / long-offline device.** Read the newest valid snapshot (any
   device's), apply it, then apply segments with `seq` above the snapshot's
   `heads`.
4. **Compaction.** After N segments or M KB since its last snapshot, a device
   writes `snap/<me>/<lamport>.bin`. Each device writes only its own
   snapshots, and readers take the newest one.
5. **Garbage collection.** Only the owner device, or the lowest-ID active
   editor, deletes remote files, and only:
   - segments covered by a snapshot that is ≥ 30 days old;
   - all but the newest two snapshots per device;
   - blobs no longer referenced by the newest snapshot, after 30 days.

   Tombstones for deleted bottles stay in the state, so an old offline edit
   cannot resurrect them unless its Lamport stamp is newer, which is LWW's
   intended behaviour.
6. **Missing data.** If a segment or blob is listed but unreadable, or
   referenced but absent, retry later; never treat it as a delete.

### 10.4 Removing a device or member (key rotation)

> **Superseded:** as built, the new key goes to each remaining device as a key
> grant in its inbox; see [SYNC.md](SYNC.md), section 8, "Removing a device".

1. The owner, or any editor, generates `K_{e+1}` and a new roster without the
   removed device, signed by the owner.
2. It wraps `K_{e+1}` for each remaining device with an X25519 ECDH to that
   device's long-term key + HKDF + XChaCha20-Poly1305, and writes
   `keys/<e+1>-<dev>.bin` in the **old** mailbox:
   `{newEpoch, [ {deviceId, ephemeralPub, wrappedKey} … ]}`, signed.
   - The file is per writer, so it follows the single-writer rule. If two
     editors rotate at once, the file from the lowest device ID wins; the
     other editor re-reads it, adopts the winning epoch and re-applies its
     own removal on top.
   - The new mailbox ID is *not* stored: remaining devices derive
     `mailboxId_{e+1}` from `K_{e+1}` after unwrapping, so the removed device
     learns nothing about where the cellar moved.
3. It writes a fresh snapshot into the **new** mailbox `mailboxId_{e+1}`,
   encrypted with `K_data_{e+1}`. Photos are not re-uploaded: their per-photo
   keys travel inside the new snapshot, so only remaining devices learn keys
   for *future* photos. Optionally re-upload photos into the new mailbox
   lazily, so the old mailbox can be deleted entirely.
4. Remaining devices find `keys/<e+1>-<dev>.bin` on their next pull, unwrap, switch
   to the new mailbox, and the old mailbox is garbage-collected after all of
   them have acknowledged it (or after 30 days).
5. **What this achieves:**
   - The removed device keeps what it already had, as the roadmap states.
   - It cannot decrypt anything new.
   - On the relay it cannot even *read* the new mailbox, because it lacks
     `relayToken_{e+1}`.
   - On CloudKit or Drive under the same Apple or Google account (a user's own
     lost phone), it may still list the new ciphertext. Revoke the session at
     Apple/Google for full removal.
   - With a `CKShare`, also remove the participant from the share.

### 10.5 Mapping to each backend

> **Superseded** for the storages built (relay, WebDAV, CloudKit): see
> [SYNC.md](SYNC.md), section 8, "Storages". None of them has a wake-up: devices
> poll.

| Operation | CloudKit | Drive `appDataFolder` | WebDAV | Relay | Dropbox |
|---|---|---|---|---|---|
| Namespace | custom zone `<mailboxId>` (private DB, or the shared DB for participants) | name prefix `encave-v1/<mailboxId>/…` in `appDataFolder` | directory | `/v1/m/<mailboxId>` | `/Apps/Encave/<mailboxId>/` |
| put-if-absent | `CKRecord` (type `EncaveFile`, recordName = path, `data` bytes or `CKAsset`), save policy `.ifServerRecordUnchanged` | create with deterministic name; dedupe on read | `PUT` + `If-None-Match: *` | server-enforced | `upload mode=add, autorename=false` |
| list since | CKSyncEngine / zone change token | `changes.list` page token | `PROPFIND Depth:1` per device dir | `?after=cursor` | `list_folder/continue` cursor |
| wake-up | silent push (`CKDatabaseSubscription`) | poll | poll | WebSocket while open | `list_folder/longpoll` |
| household | zone-wide `CKShare` | ✗ | server-side share | same mailbox + token | **[unverified]** |

---

## 11. Phased plan

| Phase | Deliverable | Effort (solo, rough) | Outcome |
|---|---|---|---|
| **0. Protocol v1** | File format, key schedule, segment / snapshot / blob logic, GC rules, `StorageBackend` interface (list / get / putIfAbsent / delete / wake), local-folder backend, cross-platform test vectors; the existing Bonjour LAN sync reuses the same segments | 2 to 3 weeks | One sync core for every transport |
| **1. CloudKit private DB** | CKSyncEngine adapter; Settings → "Sync with iCloud"; quota errors; optional cellar key in iCloud Keychain for own devices; deploy CloudKit schema to Production | 1 to 2 weeks | iPhone ↔ Mac ↔ iPad anywhere, $0 |
| **2. Google Drive `appDataFolder`** | Cloud project (no billing account); brand verification; privacy-policy page on an owned domain; Android OAuth (Custom Tabs + PKCE, or the Authorization API) + REST + WorkManager; iOS OAuth (`ASWebAuthenticationSession`) + REST; verify the shared folder across iOS/Android client IDs **first** | 3 to 4 weeks | iPhone ↔ Android for one person, $0 |
| **3a. CKShare** | Zone-wide share, accept flow, participant list, view-only via roster roles + signatures | 1 to 2 weeks | Apple-only households |
| **3b. Self-hostable relay** | Worker + DO + R2 template, Docker image, rate limits, quotas, TTLs, PAKE rendezvous endpoint; "Custom relay URL" setting; relay URL in the pairing QR | 2 to 3 weeks | Mixed households; remote 6-digit pairing |
| **3c. Decision** | Run a capped community relay or not; publish a budget and a shutdown policy if yes | — | — |
| **4. WebDAV / Nextcloud** (done, without Login Flow v2) | Login Flow v2, PROPFIND / PUT; test on 3 to 4 servers | 1 to 2 weeks | Power users, Nextcloud households |
| **5. Dropbox app folder** (only if asked) | PKCE OAuth, REST adapter; production review at 50 users | 1 to 2 weeks | — |
| **Always** | Encrypted backup export/import to any Files/SAF location (one file, manual) | 3 to 5 days | Recovery for single-device users |

---

## 12. Store review and legal notes, all options

- **App Store 4.8 (Login Services):** applies only to a login that sets up
  "the user's primary account with the app". Optional Google/Dropbox
  authorisation for storage is not that, and Encave has no account.
  **5.1.1(v):** "If your app doesn't include significant account-based
  features, let people use it without a login". Keep sync optional.
  ([Apple guidelines](https://developer.apple.com/app-store/review/guidelines/))
- **Privacy labels:**
  - **Google Play Data safety** exempts data sent "using end-to-end
    encryption" that is unreadable by anyone but sender and recipient,
    including the developer ([Play help](https://support.google.com/googleplay/answer/11416267)).
    This holds for Encave because the maintainer holds no keys.
  - **Apple's privacy label:** E2EE apps commonly declare "Data Not Collected"
    ([example](https://9to5mac.com/2020/12/09/apple-app-privacy-labels/)).
    Check Apple's definitions page **[unverified]**.
  - The optional relay processes IP addresses, so document it.
- **Encryption export:** if encryption uses algorithms *not supplied by the
  OS* (a Rust crate, or libsodium instead of CryptoKit), Apple's export
  compliance asks for a **French encryption declaration** for distribution in
  France ([Apple export compliance](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption)).
  This applies regardless of transport but matters for a French maintainer;
  CryptoKit-only on Apple may avoid it **[verify]**.
- **Google:** API Services User Data Policy; brand verification; keep the
  consent-screen app info and privacy policy current.
- **Dropbox:** branding guidelines and production review at 50 users.

---

## 13. Unverified or uncertain items

1. `appDataFolder` shared between Android and iOS OAuth clients in one Cloud
   project (expected yes; test first).
2. How Google treats unbilled projects when Drive quota billing starts "later
   in 2026" (expected: 429s, not charges).
3. Whether `github.io` satisfies Google brand verification's domain
   requirement.
4. Continued availability of the custom URI scheme opt-out for Android
   OAuth clients.
5. Whether `appDataFolder` storage counts against the user's Google quota.
6. CloudKit asset size limit (50 MB vs 50 GB; irrelevant at our sizes) and
   the Family Sharing `CKShare` quota bug status.
7. CloudKit Web Services / JS access to private or shared databases from
   Android in 2026.
8. Dropbox: sharing app folders between users; current OAuth details.
9. WebDAV `If-None-Match: *` support on specific servers (the protocol no
   longer depends on it, see section 6.3).
10. R2 payment-method requirement; Deno Deploy, Supabase and Pinata
    free-tier figures (secondary sources).
11. Nostr relay size and retention defaults (strfry upstream); Syncthing
    relays storing nothing (implied by design).
12. Google Drive and Dropbox SAF tree support in current Android app versions.
13. EU DSA obligations for a micro relay operator; GDPR status of
    undecryptable ciphertext (not legal advice).
14. Apple's definition of "collected" for E2EE data in privacy labels.

---

## 14. Sources

**Apple**
- CKSyncEngine: https://developer.apple.com/documentation/cloudkit/cksyncengine-5sie5
- CKDatabaseSubscription: https://developer.apple.com/documentation/cloudkit/ckdatabasesubscription
- CKShare: https://developer.apple.com/documentation/cloudkit/ckshare
- Private DB scope: https://developer.apple.com/documentation/cloudkit/ckdatabase/scope/private
- Shared DB scope: https://developer.apple.com/documentation/cloudkit/ckdatabase/scope/shared
- CloudKit Web Services auth: https://developer.apple.com/library/archive/documentation/DataManagement/Conceptual/CloudKitWebServicesReference/SettingUpWebServices.html
- CloudKit Web Services limits (archived): https://developer.apple.com/library/archive/documentation/DataManagement/Conceptual/CloudKitWebServicesReference/PropertyMetrics.html
- Core Data + CloudKit 1 MB note: https://developer.apple.com/documentation/coredata/reading-cloudkit-records-for-core-data
- Forums: https://developer.apple.com/forums/thread/119159 · https://developer.apple.com/forums/thread/734787 · https://developer.apple.com/forums/thread/827897 · https://developer.apple.com/forums/thread/772198 · https://developer.apple.com/forums/thread/765828
- TN2336 (iCloud conflicts): https://developer.apple.com/library/archive/technotes/tn2336/_index.html
- App Review Guidelines: https://developer.apple.com/app-store/review/guidelines/
- Export compliance: https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption

**Google**
- Drive scopes: https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- appDataFolder: https://developers.google.com/workspace/drive/api/guides/appdata
- Drive limits and 2026 billing: https://developers.google.com/workspace/drive/api/guides/limits
- OAuth verification: https://support.google.com/cloud/answer/13463073
- App audience / test users: https://support.google.com/cloud/answer/15549945
- Custom URI scheme restriction: https://developers.googleblog.com/en/improving-user-safety-in-oauth-flows-through-new-oauth-custom-uri-scheme-restrictions/
- Play Data safety: https://support.google.com/googleplay/answer/11416267

**Cloudflare and other platforms**
- Workers limits: https://developers.cloudflare.com/workers/platform/limits/
- Workers pricing: https://developers.cloudflare.com/workers/platform/pricing/
- Durable Objects pricing: https://developers.cloudflare.com/durable-objects/platform/pricing/
- DO free tier: https://developers.cloudflare.com/changelog/2025-04-07-durable-objects-free-tier/
- R2 pricing: https://developers.cloudflare.com/r2/pricing/
- Deno Deploy Classic: https://docs.deno.com/deploy/classic
- Fly.io discontinued plans: https://fly.io/docs/about/discontinued-plans/
- Supabase (secondary): https://automationatlas.io/answers/supabase-free-tier-limits-2026/
- Free tiers overview (secondary): https://flaviocopes.com/hosting-free-tiers.md

**Storage providers and apps**
- Dropbox developer guide: https://docs.dropboxapi.com/dropbox-api/docs/developer-resources/developer-guide
- Dropbox OAuth guide: https://developers.dropbox.com/oauth-guide
- Nextcloud Login Flow: https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html
- Joplin sync lock: https://joplinapp.org/help/dev/spec/sync_lock
- Joplin E2EE: https://joplinapp.org/help/dev/spec/e2ee
- Joplin forum (locks rate-limited): https://discourse.joplinapp.org/t/rate-limited-joplin-overwrites-files-in-the-locks-folder-too-often/38005
- Obsidian Remotely Save: https://github.com/scruel/obsidian-remotely-save
- KeePassium: https://support.keepassium.com/kb/sync-google-drive/ · https://keepassium.com/articles/supported-sync-sources
- KeePassDX: https://github.com/Kunzisoft/KeePassDX/issues/342 · https://github.com/Kunzisoft/KeePassDX/issues/1264
- Logseq forum: https://discuss.logseq.com/t/im-using-logseq-with-icloud-but-experiencing-data-loss-or-file-conflict-errors-whats-going-on/13393
- Cryptomator forum (SAF tree): https://community.cryptomator.org/t/cryptomator-not-usable-without-webdav-or-not-listed-cloud-storages/11933/16
- Dropbox forum (SAF): https://dropboxforum.com/discussions/101000042/why-using-intent-action-open-document-does-not-list-the-dropbox/209654
- Actual Budget sync: https://actualbudget.org/docs/getting-started/sync/
- Anytype: https://doc.anytype.io/anytype-docs/advanced/data-and-security/self-hosting · https://doc.anytype.io/anytype/data/sync-and-backup

**Decentralised networks**
- NIP-11: https://nips.4rs.nl/nips/11
- NIP-78: https://scrapbox.io/nostr/NIP-78
- strfry options (NixOS): https://mynixos.com/nixpkgs/option/services.strfry.settings
- Blossom: https://d-central.tech/blossom-protocol-reference/
- Syncthing relaying: https://docs.syncthing.net/users/relaying
- Pinata (secondary): https://agentdeals.dev/vendor/pinata-ipfs
- matrix.org usage plans: https://matrix.org/homeserver/pricing/
