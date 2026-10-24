# Store paperwork (draft)

> Answers for the App Store Connect and Google Play Console forms, with the
> reasoning, checked against the code as it is. On the App Store side, the
> "App Store listing" workflow applies `docs/store/appstore.json` (privacy
> policy URL, age rating, texts in French and English, and the build number
> it attaches to the iOS version being prepared); the App Privacy label
> is answered in App Store Connect by hand (no API). Google Play is filled in
> by hand. Recheck them whenever the apps start sending
> something new. The privacy policy is [PRIVACY.md](PRIVACY.md).

## Facts the answers rest on

- No account, no analytics, no advertising, no third-party SDK that receives
  data, no server run by the developer. Crash reports come only from Apple or
  Google, for users who allowed it in the system settings.
- The cellar, photos and documents stay in the app's storage on the device
  (protected by the system's encryption: iOS data protection, FileVault on a
  Mac when on, Android's file-based encryption).
- Backups stay on the device unless the user moves them, and are **not
  encrypted**. The weekly backup is on by default: the last two ZIP archives
  are kept in the app's Documents folder on Apple devices, and in the shared
  Download/Encave folder on Android, where apps with access to all files can
  read them and where they stay after the app is removed. **Back Up Now** saves
  or shares a ZIP file where the user chooses. Nothing is sent by the app.
- Sync is end-to-end encrypted (`docs/SYNC.md`, `docs/REMOTE_SYNC.md`): direct
  on the local network, or through a storage the user chooses (iCloud, in
  builds set up with an iCloud container; WebDAV; a relay) that holds only
  ciphertext. Nobody but the user's devices has the
  keys, the developer included.
- AI label recognition (off by default): only the label photo, sent from the
  device straight to the provider the user set up with their own key.
- Web search (off by default): only the search terms (producer, name,
  appellation, vintage, and for a price the bottle size), shown and editable
  before sending, to the provider the user set up. Picture thumbnails and the
  chosen picture are downloaded from their hosts.
- The app has no web view: links and search results open in the browser.
- The privacy manifest (`apple/Encave/Resources/PrivacyInfo.xcprivacy`)
  declares no tracking and no collected data.

## Apple: App Privacy ("nutrition label")

**Answer: Data Not Collected.**

Apple counts data as collected when it is transmitted off the device in a way
that lets the developer or its third-party partners access it beyond servicing
the request in real time.

- **Sync**: end-to-end encrypted, stored in the user's own iCloud, WebDAV
  folder or relay; neither the developer nor any partner can read it.
- **AI label photo and search terms**: sent, at the user's request, to a
  service the user picked and pays for with their own account and key, as a
  mail client or browser would. The developer has no relationship with these
  services and never receives the data. The in-app texts and the policy say
  exactly what is sent.
- **Crash reports**: collected by Apple, not by the app.

If Apple reviewers ask for the conservative reading (the AI provider and the
search provider as "third-party partners"), the label would become:
*Photos or Videos* (the label photo) and *Search History* (the search terms),
both **App Functionality**, **not linked to the user**, **not used for
tracking**. Keep the "Data Not Collected" answer unless asked: these services
are not partners of the developer.

**Tracking**: No. **Privacy policy URL**: the published PRIVACY.md.

## Google Play: Data safety

Sent by `play-listing.yml` from `docs/store/play-data-safety.csv` (the
Console's CSV format; only the first question answered: No).

**Does your app collect or share any of the required user data types?** No.

Reasoning, from Google's definitions (collection = transmitting data off the
device from the app):

- **Sync**: exempt as end-to-end encrypted data (unreadable by anyone but the
  sender and recipient, including the developer and the storage).
- **AI label photo and search terms**: sent directly from the device to the
  user's own account at a provider the user chose, under that provider's
  terms; the app and the developer never collect or access it. This is the
  case of Google's FAQ on uploads straight to the user's own Drive or Dropbox
  account, which needs no declaration. If Google asks for the conservative
  reading, declare *Photos* and *App activity › In-app search history* as
  **collected, not shared** (sharing is exempt: user-initiated, to a service
  the user chose), **processed ephemerally** for the search terms,
  **optional**, purpose **App functionality**.
- **Crash data**: Android vitals is collected by Google Play, not by the app.

Other questions:

| Question | Answer |
|---|---|
| Is all of the user data collected by your app encrypted in transit? | Yes (if the conservative reading is used: HTTPS, plain HTTP only to a server on the user's local network, including home-network names that point to a local address) |
| Do you provide a way for users to request that their data is deleted? | Nothing is held by the developer. On the device, **Erase all data** empties the cellar and, unless the user turns that off, the previous versions and the weekly backups Encave made (never files exported elsewhere); removing the app deletes its own storage, but not the weekly backups in Download/Encave, which the user deletes in the Files app |
| Independent security review | No |
| Privacy policy URL | The published PRIVACY.md |

## Age ratings

### Apple (age rating questionnaire)

| Item | Answer | Why |
|---|---|---|
| Alcohol, Tobacco, or Drug Use or References | **Frequent** | The whole app is about wine: bottles, tastings, drinking windows, "what to open tonight" |
| Unrestricted web access | No | No web view; links and search results open in the browser |
| User-generated content shared with others | No | Notes and photos are only shared between the user's own devices, encrypted |
| Messaging and chat, social media | No | |
| Advertising, contests, gambling, loot boxes | No | |
| Medical or treatment information | No | |
| Every other content item (violence, profanity, sexual content, horror, mature themes) | None | |
| Parental controls, age assurance | No | |

Result: **18+** (Apple's table: frequent alcohol references give 18+;
infrequent would give 13+, which does not describe a cellar app). This also
matches the legal drinking age in most stores' regions.

### Google Play (IARC questionnaire)

- Category: **All Other App Types** (a utility, not a game, store or
  social app).
- Violence, sexuality, language, fear, gambling: **No**.
- Controlled substances: **Yes, the app references alcohol** (it catalogues
  wine and shows when to drink it); it does not sell alcohol, and no use of
  tobacco or drugs.
- Users interact or exchange content: **No** (sync is between the user's own
  devices); shares location: **No**; digital purchases: **No**.

Expected result: an alcohol-reference rating (for example PEGI 16 or 18 and
ESRB Teen or Mature, depending on the region's rules; IARC assigns it). In the
Play Console, also set the **target audience** to **18 and over**, which keeps
the app out of the Families programme.

## Other forms

- **Export compliance**: see [ENCRYPTION.md](ENCRYPTION.md)
  (`ITSAppUsesNonExemptEncryption = NO`; Play asks no export questions).
- **Apple privacy manifest**: in the app; CI checks that the built apps bundle
  it and that it declares no tracking and no collected data (the required-reason
  APIs it lists are kept in step with the code by hand).
- **App access, and notes for App Review and Google Play**: no login, no account
  and no server are needed. The builds CI makes today are beta builds
  (`ENCAVE_CHANNEL=beta`, `ENCAVE_BETA` unset): while the cellar is empty they
  offer the sample cellar (**Load sample cellar** on the empty Cellar, or
  **Settings › Try Encave**). A store build (`ENCAVE_CHANNEL=store`,
  `ENCAVE_BETA=false`) does not offer it at first run; it is in Help only, so the
  notes for it read: "To explore with example data: open Settings (on Mac, the
  Help menu › Encave Help), then Help › Getting started, scroll to the end and
  tap Load sample cellar under Try with a sample cellar. It is offered only while
  the cellar is empty and no other device syncs with it. Remove Sample, at the
  top of the Cellar, removes it. Or start from scratch: add a bottle with +
  (scan a label or type a name), then add a storage in Storage and put the
  bottle in it; Tonight and the Journal fill as bottles are added and opened. AI label
  recognition and web search are optional and need the reviewer's own key or
  server; sync needs a second device." On Android the path is the same:
  Settings › Help › Getting started › Try with a sample cellar › Load sample
  cellar.
- **Google Play: ads**: the app contains no ads.
- **Google Play: advertising ID**: No (the app does not declare
  `com.google.android.gms.permission.AD_ID` and uses no advertising library).
- **Google Play: app category**: Food & Drink; no tags needed.
- **Google Play: health apps, financial features, government apps, news**: No.

## App Store header and search results image

`docs/store/header-fr.png` and `header-en.png`: one universal image per
language (5244 x 2950 PNG, no transparency), made by `docs/store/make_header.py`
from the screen tour's sample cellar. The "App Store header image" workflow
(run by hand) reads App Store Connect with `inspect`, and with `upload` places
each image as the product page header and the search results asset of the
version being prepared. It submits nothing: the images go to review with the
version. Shown on iOS and iPadOS 27 and later.
