# Sync protocol (version 1)

How Encave devices pair and keep one cellar in step. This document is the
specification: the Swift implementation is in
`apple/Packages/CaveKit/Sources/CaveKit/SyncState.swift` and `SyncProtocol.swift`,
the Kotlin one in `android/core/src/main/kotlin/app/encave/core/sync/`. Every
implementation must produce the same keys, values and cryptographic bytes (keys,
transcripts, envelopes). The JSON text itself may differ in escaping (Swift
writes `/` as `\/`, Kotlin does not): readers accept both, and no JSON text is
hashed or authenticated as such. The test vectors at the end are checked by
`ProtocolVectorTests` on both platforms, and the JSON and ZIP files in
`fixtures/interop` are written by each platform and read by the other
(`InteropFixtureTests.swift`, `InteropFixtureTest.kt`). Merge conflicts (equal
stamps, a revived wine, places lost) are pinned by `fixtures/sync/merge-vectors.json`,
run both ways on both platforms (`MergeVectorTests.swift`, `MergeVectorTest.kt`).

- **Local network (this document, sections 1–7):** devices on the same network
  find each other with Bonjour/mDNS and talk directly over TCP. No server.
- **Remote sync (section 8):** devices that are apart exchange encrypted files
  through storage that holds only ciphertext (iCloud, a WebDAV folder, a relay).
  The choice of storage is explained in [REMOTE_SYNC.md](REMOTE_SYNC.md).

## 1. Data model and merge

The cellar file (`cellar.json`) is unchanged by sync. Backups hold the same
cellar inside a small envelope, with dates as ISO 8601 strings.
Sync metadata lives next to it in `sync.json` (`SyncState`) and the list of
devices in `devices.json` (`Membership`). Each device also keeps, for itself
only, `last-sync.json` (when each other device last synced on the network) and
`remote.json` (the progress of remote sync, section 8).

### Objects and stamps

Each object has a key: `wine:<uuid>`, `bottle:<uuid>`, `rack:<uuid>`,
`discovery:<uuid>` (a wine tasted elsewhere, in the Journal),
`search:<uuid>` (a saved search), plus `settings` (the
cellar settings without the saved searches) and `rack-order` (the order of the
racks). UUIDs in keys are lowercase.

A **stamp** is a Lamport time: `{counter: UInt64, device: UUID, deleted: Bool}`.
Stamps are ordered by counter, then by the device UUID's uppercase string,
then a deletion after a non-deletion. `Stamp.zero` (counter 0, the nil UUID)
stands for objects that exist but were never stamped.

`SyncState = {version: 1, device, clock, stamps: {key: stamp}}`.

- **Recording.** After each local change the app compares the cellar before and
  after: every object that was added or differs gets `clock += 1` and a stamp
  `(clock, device)`; every removed object gets a stamp with `deleted: true`.
  A change to the rack sequence stamps `rack-order`; a change to settings
  other than `savedSearches` stamps `settings`; each saved search added,
  changed or removed stamps its own `search:<uuid>`.
- **Starting.** When sync is set up on a cellar that already has data, every
  object gets a first stamp (`SyncState.starting`).

### Object format

Objects are the records of the cellar file, encoded as JSON as described in
section 7 (dates, UUID-keyed maps). Fields without a value (nil) are left out;
arrays such as `grapes` and numbers with a default (`sizeML`, `depth`,
`binCapacity`) are always written. Details that matter for other
implementations:

- `bottle.status` is one of `inCellar`, `consumed`, `gone` or `ordered`
  (bought, not delivered yet; such a bottle has no place and may carry
  `expectedAt`, a date).
- `bottle.tasting` is the optional tasting note of an opened bottle (its
  `date` is required); with discoveries, these make the Journal.
- `wine.barcodes` and `wine.tags` are optional arrays of strings, left out
  when empty, and so is `wine.extraPhotoIDs` (more photos; they are synced like
  `photoID`). `wine.coverPhotoID` is an optional file ID (section 7): the
  picture the user chose from a web search, shown instead of the label photo,
  which it never replaces; it is synced like `photoID`. `wine.currentPrice`
  (a number, like `bottle.purchasePrice`),
  `wine.currentPriceCurrency` (an ISO code) and `wine.currentPriceDate` (a date)
  are optional: the price of a bottle today, accepted by the user from a web
  search or typed. `wine.aiCompletedAt` is an optional date: when the details
  were last completed by the AI label recognition, shown by the apps.
  `wine.labelFrame` is an optional object `{photoID, x, y, w, h, angle}`, left
  out when absent: where the whole front label is in the label photo, as the
  AI label recognition found it, to frame the studio picture made on each
  device. `x`, `y`, `w` and `h` are fractions (0 to 1) of the photo's width and
  height from its top-left corner, and `angle` is in degrees, positive when the
  label's text runs uphill to the right. It is used only while `photoID` is
  the wine's `photoID`: after a new label photo it is stale and ignored.
  `bottle.reservedFor` is an optional string (the occasion a bottle
  is kept for). `settings.savedSearches` is an optional array of
  `{id, name, query, colors, maturities, rackID?, tag?, reservation?, grape?}`, with
  `colors` and `maturities` sorted by their string value.
- `wine.attachments` and `bottle.attachments` are optional arrays of documents
  (an invoice, a certificate, a technical sheet…), left out when empty:
  `{id, name, contentType, byteCount, addedAt}`. `id` is a file ID (section 7)
  and each ID appears once in an array (a reader keeps the first); `name` is
  the file name shown; `contentType` a MIME type such as `application/pdf`
  (`application/octet-stream` when the type is unknown), kept as written;
  `byteCount` the file's size in bytes (an integer); `addedAt` a date. A
  wine's documents are for all its bottles; a bottle's are for that bottle.
  They are part of their wine or bottle object, so if two devices change the
  documents of the same object before syncing, the later change wins.
- **Guided tasting, scores and wine details.** These keys are optional and
  left out when empty; versions that do not know them keep them as unknown
  fields (below), so they need no new protocol version. Ids are the stable ids
  of `fixtures/tasting-vocabulary.json` (never labels, which each app gives in
  English and French); readers keep an id they do not know, unchanged, and never
  fail on one (unlike `wine.color`).
  - `tasting.aromas`: array of aroma ids (`"fruit.redCherry"`), in the order
    chosen; `tasting.sweetness`, `acidity`, `tannin`, `body`, `alcohol`,
    `length`: integers 1 to 5; `tasting.intensity` (`pale`, `medium`, `deep`) and
    `tasting.hue` (`ruby`, `gold`, `onionSkin`…): ids.
  - `tasting.score100` and `discovery.score100`: the precise score out of 100, a
    number from 50 to 100 in quarter points, when the rating was entered out of
    20 or out of 100 (a device setting, not synced). `rating` is always written
    with it, as the stars of that score, so older versions show stars. A score is
    kept only while `rating` is its stars: an older version that changed or
    removed the rating left the score behind, and it is read as left out.
  - `wine.abv` (a number, percent), `wine.classification` and `wine.lieuDit`
    (strings), `wine.dosage` (sparkling: `brutNature`, `extraBrut`, `brut`,
    `extraDry`, `sec`, `demiSec`, `doux`), `wine.sweetness` (still wines: `dry`,
    `offDry`, `mediumSweet`, `sweet`), `wine.ageing` (array of ids: `oakBarrel`,
    `newOak`, `foudre`, `stainlessSteel`, `concrete`, `amphora`, `onLees`),
    `wine.farming` (`organic`, `biodynamic`, `natural`, `sustainable`,
    `conventional`) with `wine.farmingCertified` (only `true` is written, and
    only with a farming), `wine.closure` (`naturalCork`, `technicalCork`,
    `screwCap`, `glass`, `synthetic`, `crownCap`).
  - `wine.grapeShares`: an object of percentages (integers) keyed by grape, the
    grape written as in `wine.grapes`, which stays the list older versions read.
  - `wine.criticScores`: array of `{source, score?, scale?, award?, note?}`, as
    the user typed them (nothing is looked up): `score` a number with `scale`
    100 or 20, or `award` a text (a medal) without a scale; `note` such as the
    vintage it was given for. `source` is required. Each entry keeps its unknown
    keys like any record, and another `scale` is kept and shown as
    `score/scale`.

  Read the same way on every device (vectors: `fixtures/tasting-sheet-vectors.json`,
  which also checks that an older version's edits read back right): levels are
  brought into 1…5; aroma and ageing ids are kept once, in order, empty ones
  dropped; empty strings of id fields are left out; a score is brought into
  50…100 and rounded to the nearest quarter point, then dropped unless `rating`
  is its stars; a share is kept only for a grape listed in `wine.grapes`
  (trimmed, ignoring case; a grape given twice keeps the key that sorts first by
  UTF-16 code units), brought into 1…100, and dropped at 0 or below; empty
  arrays and objects are left out.

  **Ratings and scores** (vectors: `fixtures/rating-scale-vectors.json`, also
  used by the CSV import and export). Out of 20: `score100 = 50 + 2.5 × score20`.
  Stars from a score: below 70 is 1, below 80 is 2, below 87 is 3, below 94 is
  4, else 5. A rating in stars alone is shown on the other scales as 65, 75, 83,
  90 or 96 out of 100 (marked as approximate). Shown out of 20 to the nearest
  half point, out of 100 to the nearest point:

  | /20 | /100 | stars |
  |---|---|---|
  | 10 | 75 | 2 |
  | 12 | 80 | 3 |
  | 14 | 85 | 3 |
  | 15 | 87.5 | 4 |
  | 16 | 90 | 4 |
  | 17 | 92.5 | 4 |
  | 17.5 | 93.75 | 4 |
  | 18 | 95 | 5 |
  | 19 | 97.5 | 5 |
  | 20 | 100 | 5 |

- Readers are strict: an unknown value of `wine.color`, `bottle.status`,
  `rack.kind` or `discovery.setting` fails decoding; readers never replace it
  with a default. A device that does not know a new value refuses the sync
  instead of rewriting the object with a wrong value and a newer stamp, which
  would spread the loss to every device. (A rack without `kind`, from version
  0.1 or 0.2, is a grid.) In the same way:
  - required fields are never filled in by the reader: every `id`, `wineID`,
    `wine.name`, `rack.name`, `rack.rows`, `rack.columns`, the `rackID`, `row`
    and `column` of a bottle's `slot`,
    `bottle.addedAt`, `discovery.date`, `tasting.date`, `member.addedAt`,
    `settings.currency`, `cellarID`, `members` and `stamps` of a membership, the
    three fields of a stamp, the four of a `SyncState` and the five of a
    document. Fields that may be
    left out are read with their stated default
    (`bottle.status` `inCellar`, `sizeML` 750, `depth` 0 or 1, `binCapacity` 12,
    `grapes` and `discovery.tags` empty, `wantsToBuy` false);
  - some values are normalized when read, the same way on every device:
    `wine.tags` and `discovery.tags` are trimmed and each tag is kept once
    (ignoring case), `wine.extraPhotoIDs` keeps each ID once, and
    `wine.tags`, `wine.extraPhotoIDs` and `wine.barcodes` are left out when
    nothing remains; `discovery.rating` is brought into 1…5;
  - a UUID is its 36-character form (either case), binary values are standard
    Base64 with padding and nothing else, prices are JSON numbers, and dates are
    numbers in the cellar file and the sync payloads, ISO 8601 strings without
    fractional seconds in backups;
  - a rack's `rows`, `columns`, `binCapacity` and each `rowColumns` value are
    brought into 1…100 (`depth` into 1…3, 1…2 for a `staggered` rack), and
    `rowColumns` keeps at most 100 values, so a damaged or hostile rack cannot
    make a device allocate millions of cells. Every device applies the same rule,
    so they read the same rack;
  - `rack.shelfLayers` (optional array of integers) makes a `staggered` rack a
    fridge whose shelves hold bottles in layers stacked in quincunx: one value
    per shelf, from the top shelf, each brought into 1…3. Shelves are kept while
    their total stays within 100; `rows` is then that total (the layers, lettered
    from the top, a shelf's last row being its bottom layer). It is left out (and
    dropped when read) for other kinds and when empty. Without `rowColumns`, the
    second layer of a shelf holds `columns − 1` bottles and the others `columns`;
  - `rack.zoneBreaks` (optional array of integers) marks temperature zones on such
    a fridge: each value is a shelf (0 is the top one) that starts a new zone.
    Only values from 1 to the number of kept shelves − 1 are kept, sorted, each
    once; it is left out when none remain and whenever `shelfLayers` is;
  - `rack.backReversed` (optional boolean) says that on such a fridge, two deep
    (`depth` 2), the back bottles (`slot.depth` 1) lie head-to-tail: necks facing
    you, between the bases of the front bottles. Only `true` is written; it is
    left out (and dropped when read) when false, when `shelfLayers` is left out
    and when `depth` is not 2. Apps that do not know it keep it as an unknown
    field (below);
  - `rack.rowDepths` (optional array of integers) gives each row its own depth
    when rows differ (one row single, the next two deep): one value per row, from
    the top, like `rowColumns` (on a fridge with `shelfLayers`, one per layer, every
    layer of a shelf taking the value of the shelf's bottom layer, its last row).
    An array whose length is not `rows` is stale (a version that does not know it
    added or removed rows or shelves, keeping it as an unknown field) and is read
    as left out: every row is `depth` deep. An older version's edit that keeps
    the number of rows (a shelf deleted and another duplicated, or layers moved
    between shelves with the same total) cannot be told apart: the depths may then
    land on the wrong shelves. Nothing is lost: bottles keep their places (judged
    against `depth`, below) and stay shown; editing the shelves' depths puts it
    right. Readers bring each value into
    1…`depth`, and leave it out when every row is `depth` deep, when none is (an
    older version made the rack deeper: every row is then that deep) and for
    `bins`. Writers set `depth` to the deepest row, so a version that does not
    know `rowDepths` still shows every position, and also the back positions of
    the single rows, where it can put bottles. Places stay judged against `depth`
    (or `binCapacity`) whatever the row's own depth: the merge's repair (step 5)
    and imports keep a bottle behind a single row, so every version repairs the
    same way. The newer apps never offer such a place, show a bottle there (named
    as the back, `B3, back`), count that place among the rack's positions, and
    list the bottle among those whose position an edit of the storage removes only
    when the edit removes the place itself (taken out once the person agrees,
    with Undo). A row one deep names its other positions without a side (`B3`, not `B3-1` or
    `B3, front`);
  - `rack.rowBackReversed` (optional array of booleans) says, on a fridge with
    `shelfLayers` whose shelves two deep differ, which ones lie head-to-tail: one
    value per row, from the top, read like `rowDepths` (an array whose length is
    not `rows` is stale and read as left out); a row lies head-to-tail when `backReversed` is kept, its depth is 2
    and its value is true. It is kept only while `backReversed` is: writers set
    `backReversed` to true when any shelf lies head-to-tail, so older versions
    show every shelf two deep head-to-tail. Readers write `false` for rows not two
    deep, and leave it out when every row two deep lies head-to-tail, or none does
    (`backReversed` then says it for all). Shared vectors for both fields:
    `fixtures/storage-row-vectors.json`;
  - `rack.binStyle` (optional string) says what a `bins` rack was made as:
    `"diamond"` (diamond bins, shown under Rack) or `"crates"` (shown as Crates).
    Apps write it when a storage of bins is created, and when its type is picked
    in the editor (offered only while none is saved); any other edit keeps it as
    read, left out or holding a value of a newer version. It changes with the rest
    of the rack (the rack's stamp, last writer wins). When it is left out (racks
    from older versions) or holds a value the reader does not know, the shape
    decides: bins four to a row, the last row possibly shorter, are crates, other
    bins are diamond bins. Other kinds ignore it. Apps that do not know it keep it as an unknown field (below). Shared
    vectors: `fixtures/storage-type-vectors.json`;
  - a document's `name` is normalized the same way on every device, when read
    and when set: each control character (Unicode category Cc) becomes a
    space, `/` and `\` become `-`, white space (Unicode `White_Space`) is
    trimmed at both ends, at most 200 Unicode scalar values (code points) are
    kept, and the result is trimmed again. A name can still be empty, `.` or
    `..`: apps never use it as a path, only as the name shown or given to a
    copy they make.
- Fields a reader does not know are kept and sent back unchanged. Every record
  (wine and its critic scores, bottle and its `slot` and `tasting`, document, rack, discovery,
  settings, saved search, and device in a membership) keeps each key it does
  not know, with its value as raw JSON, and writes it back unchanged whenever it
  writes the record: after a local edit, in sync payloads, in the cellar file
  and in backups (JSON and ZIP archive). Numbers are kept by value (a reader may
  write `2.50` as `2.5`). A key the record knows is never kept as unknown, so
  known keys always win. Records without unknown keys are written exactly as
  before. Unknown keys count when comparing two versions of a record, so a
  merge that brings in only a newer version's field is saved, except in a
  `slot`, which is compared as a place. A new optional field can therefore be
  added to a record without a new protocol version. Builds made before this
  rule dropped unknown fields and sent the record back without them on their
  next change to it, erasing the field on every device: during the beta, all
  devices of a cellar should be updated together. A new kind of record, or a new key at the top of the cellar, is not
  covered: older builds drop it.
- Readers key each wine, bottle and discovery by its own `id`, not by the map
  key; if two entries have the same `id`, the one whose map key sorts first
  (uppercase UUID string) is kept. Racks with a repeated `id` keep the first.

### Merge

`merge(local, localState, remote, remoteState)` is deterministic: both devices
compute the same result.

0. **Bounds.** A received state is refused if its clock is over 2^52, if any of
   its stamps has a counter over its clock, or if a membership stamp counter is
   over 2^52. A device that accepts the largest allowed value still has 2^52
   changes before its counters stop being exact in readers that hold numbers as
   doubles (2^53), and stays far from overflowing a UInt64.
1. For every key in either cellar or either stamp map, take each side's stamp
   (or `Stamp.zero` if the object exists without one). The greater stamp wins
   and becomes the merged stamp. If it is a deletion, the object is dropped.
   Otherwise the object of the winning side is kept; if that side has no
   object (repair removed it without a stamp), the other side's object is kept
   under the winning stamp.
   Equal stamps come from the same change on the same device, so the content
   is the same except where repair changed it: for a bottle, the version
   without a place wins; otherwise the local version is kept.
2. Settings: same rule with the `settings` stamp, then `savedSearches` is
   replaced by the saved searches merged one by one like objects (rule 1 with
   their `search:` stamps), sorted by lowercased name, then lowercase ID; nil
   when none are left. So searches saved on two devices are all kept.
3. **Revival.** A bottle or discovery whose wine was deleted, but whose own
   stamp is greater than the wine's deletion stamp (it was added or changed
   after the deletion, on another device), brings the wine back: its content
   from whichever side has it (the local copy if both have it), and as its stamp the greatest such stamp of
   what refers to it, made a non-deletion.
4. Rack order: the base is the list of the side with the greater `rack-order`
   stamp; on equal stamps, the list that is smaller when compared element by
   element as lowercase UUID strings. Racks of the merged cellar missing from
   the base follow, sorted by lowercase ID. Duplicate rack IDs keep the first.
   The merged `rack-order` stamp is the greater of the two (none if both are
   zero).
5. **Repair**, the same on every device:
   - bottles and discoveries whose wine is gone are removed;
   - bottles not in the cellar, or in a rack that no longer exists or no
     longer contains their place, lose their place;
   - if two bottles share a place, the one with the greater stamp keeps it
     (on equal stamps, the smaller lowercase ID) and the others lose their
     place (they stay in the cellar);
   - wines with no bottles and no discoveries are removed.
6. The merged clock is the greatest of both clocks and of every merged stamp.
   After the memberships are merged (sections 7 and 8), the clock is raised
   to at least every membership stamp counter. The device keeps its own ID.

A device that applies a merge result while the user edited during the session
merges again with its current state, so no local change is lost.

### What syncs and what stays on each device

Synced: wines, bottles (with their tasting notes), racks and their order,
discoveries, saved searches, the cellar settings (the currency), the list of
devices, and the files the cellar refers to: label photos, extra photos, cover
pictures and documents.

Stays on each device:

- the device's identity key, the remote sync key (it travels only inside the
  encrypted `sync-keys` step and in key grants, section 8) and the WebDAV login;
- the AI and web search settings and keys;
- display settings, among them the studio look of label photos (a cache each
  device makes from the label photo, never synced);
- how often to sync away from home, and the notifications of changes;
- the choice of storage away from home: a device that has none takes it up from
  another device (section 8), but turning it off or choosing another storage
  applies to that device only;
- backups and the previous versions of the cellar file;
- `last-sync.json` and `remote.json`.

After a sync, each device can tell the user what came in from other devices
(bottles opened, added or removed, wines added, other changes, and the devices
that made them, read from the stamps of the changed objects). This is a
device-only setting, off by default: a short message when the app is in front,
a notification otherwise.

## 2. Devices and keys

Each device has a long-term **identity key** (X25519). The private key never
leaves the device (Keychain on Apple platforms). Its device ID is a random UUID.

`Membership = {cellarID, members: {id: Member}, stamps: {id: Stamp}}` with
`Member = {id, name, publicKey (32 bytes), platform ("ios", "ipados", "macos",
"android"), addedAt, inbox?}`. `inbox` is the device's relay inbox ID
(section 8); each device sets it in its own record, with a new stamp, when it
is missing. Adding a member stamps it; removing it leaves a deletion stamp.

**Leaving.** A device that stops syncing removes itself from its membership
(a deletion stamp of its own), then, for about 10 seconds at most, tells the
others: a sync session with each device of the cellar on the network (the
handshake with the membership from before the removal, the exchange with the one
after it, whose result it ignores) and one remote round. It then forgets the
cellar's devices, stamps and keys, and takes a new ID and key. No key rotation
follows: the devices that learn of it simply stop syncing with it. If neither
reaches another device, the user removes it there.

Two memberships merge per member with the stamp rule, except that **a removal
is final**: a deletion stamp wins over any non-deletion stamp, whatever the
counters. A removed device cannot add itself back by sending a newer record;
to join again it pairs as a new device, with a new ID and key.

A device that leaves a cellar keeps its cellar as it is, forgets the devices,
stamps and remote sync settings, and makes a new ID and identity key. A device
whose own record is missing from its stored membership without a deletion stamp
(its key changed, for example after a backup was restored on another device)
forgets the cellar's sync data the same way and must join again.

`networkTag(cellarID)` = first 6 bytes, as lowercase hex, of
`H("encave-cellar-tag-v1", [cellarID lowercase string])`.

## 3. Primitives

- `H(label, parts)` = SHA-256 of `label` (UTF-8) followed, for each part, by its
  length as a 4-byte big-endian integer and its bytes.
- `KDF(secret, salt, info, n)` = HKDF-SHA256 with input key material `secret`,
  `salt`, `info` (UTF-8), `n` bytes (32 unless stated).
- `DH(private, public)` = X25519. A result of all zero bytes (low-order point)
  is rejected.
- Pairing and handshake nonces are 32 random bytes. ChaCha20-Poly1305 nonces
  are 12 bytes: a counter for frames (section 4), random for files (section 8).
- **Confirmation code**: the first 4 bytes of `KDF(..., "encave pair code", 4)`
  read as a big-endian UInt32, modulo 1,000,000, written with 6 digits as
  `"ddd ddd"`. Here and in every code and file name, digits are ASCII digits,
  whatever the language of the device.

## 4. Transport

TCP. Each **frame** is a 4-byte big-endian length followed by that many bytes;
frames over 16 MiB are refused. Until the other device is authenticated (the
end of pairing step 7, or the end of the sync handshake), a device refuses
frames over 64 KiB: every pairing message, `pair-welcome` included, must fit.
Before encryption starts, a frame is one JSON
object (UTF-8, keys sorted, binary values in standard Base64). After the
handshake, each frame is `ciphertext ‖ tag` of ChaCha20-Poly1305 over that JSON
object, with:

- a key per direction (see below);
- a 12-byte nonce: 4 zero bytes, then the frame counter of that direction as an
  8-byte big-endian integer, starting at 0;
- no additional data.

A frame that fails to decrypt ends the session. Messages always have a `type`.
The responder of a sync handshake may send `{"type":"error","reason":…}`
before closing, with reason `unknown-device` (not known yet, for example added
recently on another device), `removed-device` (removed from the cellar),
`other-cellar` or `unsupported-version`; `rejected` is reserved. A responder
also refuses a hello that carries its own device ID.

Error frames arrive before authentication, so anyone on the network can send
one. They are hints for the message shown to the user ("could not sync with
this device"), never a reason to stop syncing or to leave the cellar. A device
learns that it was removed only from an authenticated membership: one merged
during a sync session, or read from the cellar's mailbox.

**Timeouts.** Connecting gets 15 seconds and the sync handshake 10 seconds; a
whole sync session is closed after 300 seconds (Android also closes a
connection that sends nothing for 60 seconds). A device serves at most 4
incoming sync sessions at once and closes further connections. Pairing has its
own limits (section 5).

**Discovery (Bonjour).** Service type `_encave._tcp`.

- A device that syncs advertises the name `Encave-<first 8 characters of its
  device ID>` (any case; the name only has to be unique and is never parsed)
  with TXT records `d` = its device ID (lowercase), `c` = the cellar's
  `networkTag`, and `v` = `1`. Devices are recognized by `d` and `c` only.
- A device showing a pairing code advertises `Encave-pair-<first 6 characters
  of the rendezvous>` with TXT `p` = `rendezvous(code)` = first 8 bytes,
  lowercase hex, of `H("encave-pair-rendezvous-v1", [the 6 digits])`. The
  joiner looks for `p` only.

Device names are never advertised.

**Retries.** After a session it started fails, a device tries that device again
after 15 seconds, then 1 minute, then 5 minutes, and then waits until the device
leaves the network and comes back, a session with it succeeds, or the user asks
to sync. A success or the device going away resets the count.

## 5. Pairing

The **host** shows a 6-digit code (and a QR code), the **joiner** types or scans
it (only ASCII digits count: other characters typed are ignored). The code only finds the host on the network; security comes from the users
comparing the confirmation code (a commit-then-reveal key exchange, as in
Bluetooth numeric comparison).

The QR code is `encave://pair?code=<6 digits>&c=<commitment, Base64URL without
padding>`. A joiner that scanned it refuses a host whose commitment differs.

1. Host → joiner: `{"type":"pair-commit","version":1,"commitment":C}` with
   `C = H("encave-pair-commit-v1", [hostEphemeralPublic, hostNonce])`.
2. Joiner → host: `{"type":"pair-key","version":1,"key":joinerEphemeralPublic,
   "nonce":joinerNonce,"device":…,"name":…,"platform":…}`.
3. Host → joiner: `{"type":"pair-reveal","key":hostEphemeralPublic,
   "nonce":hostNonce,"device":…,"name":…,"platform":…}`. The joiner checks `C`.
   Because the host committed before seeing the joiner's key, an attacker in
   the middle cannot choose keys that make both codes equal (1 chance in a
   million).
4. Both compute `shared = DH(own ephemeral, other ephemeral)`, the transcript
   `T = H("encave-pair-v1", [hostKey, joinerKey, hostNonce, joinerNonce,
   hostDeviceID, joinerDeviceID])` (device IDs as lowercase strings), the
   confirmation code from `KDF(shared, T, "encave pair code", 4)`, and the keys
   `KDF(shared, T, "encave pair host to joiner")` and
   `KDF(shared, T, "encave pair joiner to host")`.
5. Both devices show the code and ask the user if the other shows the same.
   From here frames are encrypted. Each sends `{"type":"pair-confirm",
   "accepted":true|false}`; pairing stops unless both accepted.
6. Host → joiner: `{"type":"pair-welcome","cellar":cellarID,
   "identityKey":hostIdentityPublic,"membersData":M}` where `M` is a standard
   Base64 string of the Membership JSON. The host's own record in it carries
   its `inbox`; the joiner sets that record's public key to `identityKey`.
7. Joiner → host: `{"type":"pair-member","identityKey":joinerIdentityPublic,
   "name":…,"platform":…,"inbox":…}`. The host records the name and platform
   from this encrypted message, not from step 2, and the inbox only if it is
   `i` followed by 32 lowercase hex characters.
8. The host refuses a joiner whose device ID is its own, belongs to a removed
   device, or belongs to a member with another identity key. Otherwise it adds
   the joiner to its membership; the joiner adopts the cellar ID and members
   and adds itself. Then they run the exchange (section 7) on the same channel,
   the host as initiator: the pairing keys stay, and the frame counters go on.

When joining, the user chooses to **combine** the joiner's own cellar with the
host's, or to **replace** it. With replace, the joiner resets its stamps (an
empty `stamps`, its clock kept) but keeps its own objects on the device and a
local "replace pending" flag until a snapshot from another device of the cellar
has been taken in. While the flag is set, every snapshot it sends (sessions on
the network and remote rounds) holds only the objects stamped since it joined,
with the wines and racks they use and its settings (`onlyStamped` in both
cores), and merging a received snapshot starts from that same cellar. The first
merge that goes through replaces its cellar with the result and clears the
flag. Its old objects never reach the other devices, and a first sync that
fails leaves them in place: the next one completes the join.

A code works for 2 minutes. The joiner looks for the host for 30 seconds; the
key exchange (steps 1 to 3) gets 20 seconds. Once its user has answered, a
device waits up to 2 minutes for the other device's answer and the rest of the
pairing.

## 6. Sync session handshake

Devices of the same cellar authenticate each other with their identity keys, in
the style of the Noise KK pattern: fresh ephemeral keys for forward secrecy,
identity keys to prove membership. The device with the smaller device ID
(lowercase string) connects when it sees the other appear; either side may
connect after a local change.

1. Initiator → responder: `{"type":"sync-hello","version":1,"cellar":cellarID,
   "device":initiatorID,"key":ephemeralI,"nonce":nonceI}`. The responder refuses
   another cellar or a device that is not a current member.
2. Responder → initiator: `{"type":"sync-accept","device":responderID,
   "key":ephemeralR,"nonce":nonceR,"proof":HMAC-SHA256(Kauth, "responder")}`.
3. Initiator → responder: `{"type":"sync-confirm",
   "proof":HMAC-SHA256(Kauth, "initiator")}`.

With `T = H("encave-sync-v1", [cellarID, initiatorID, responderID, ephemeralI,
ephemeralR, nonceI, nonceR])` (UUIDs as lowercase strings) and
`secret = ee ‖ es ‖ se` where `ee = DH(ephemeralI, ephemeralR)`,
`es = DH(ephemeralI, identityR)`, `se = DH(identityI, ephemeralR)`:

- `Kauth = KDF(secret, T, "encave sync auth")`
- initiator → responder frames: `KDF(secret, T, "encave sync initiator to responder")`
- responder → initiator frames: `KDF(secret, T, "encave sync responder to initiator")`

Only the holder of the responder's identity key can compute `es`, and only the
holder of the initiator's can compute `se`, so a removed device (whose key is
no longer in the membership) cannot complete a handshake.

## 7. Exchange

Over the encrypted channel, the initiator always speaks first at each step, so
neither side waits on a full buffer.

1. `{"type":"sync-state","cellarData":…,"syncData":…,"membersData":…}` each
   way. Each of the three values is a standard Base64 string of UTF-8 JSON: the
   cellar, the `SyncState` and the `Membership`. A device refuses a membership
   with another cellar ID. Both check the bounds, merge (section 1) and merge
   memberships.
2. `{"type":"photos-want","photos":[ids]}` each way: the files referenced by
   the merged cellar that the device does not have, each ID once, sorted.
   The files are the photos (`photoID`, `extraPhotoIDs` and `coverPhotoID` of
   every wine) and the documents (the `id` of every entry of
   `wine.attachments` and `bottle.attachments`). The messages keep their
   "photo" names, but carry documents the same way.
3. The initiator sends `{"type":"photo","photoID":…,"photoData":…}` for each
   requested file that the merged cellar references, then
   `{"type":"photos-end"}`; then the responder does the same. A device keeps a
   received file where the cellar uses it: with its photos, with its
   documents, or in both places when the same file is both.

4. `{"type":"sync-keys","syncData":R}` each way, where `R` is a standard Base64
   string of the `RemoteInfo` JSON, or is left out (initiator first): the
   remote sync key and storage this device uses (section
   8), so turning remote sync on reaches the other devices. `RemoteInfo` is
   `{"key":CellarKey,"storage":string?}` and `CellarKey` is
   `{"cellarID":UUID,"epoch":number,"key":Base64 of 32 bytes,"removed":[UUID]?}`
   (section 8). A key for
   another cellar is ignored; a device adopts a key that supersedes its own
   (section 8). This key is never written into a snapshot.

File IDs of new photos are `sha256-` + the lowercase hex SHA-256 of the JPEG,
and those of documents the same of the file as it was added. A received file
that was not asked for, or whose content does not match its ID, is ignored.
IDs from before sync (UUIDs) are accepted as they are. File IDs are restricted
to ASCII letters, digits and dashes, at most 80 characters; a file with any
other ID is never requested, sent, uploaded or downloaded.

**Sizes.** A frame holds at most 16 MiB, so a file travels in one `photo`
message only if its Base64 fits. Photos are resized JPEGs (at most 1600
pixels, a few hundred KB). Apps accept a document of at most 8 MB
(8,000,000 bytes) when it is added: in Base64 it fits a frame, and sealed for
remote sync (34 more bytes) it stays under a relay's 8 MiB per file. A file
that would not fit is never sent; one received over a frame's size ends the
session.

JSON uses the same structure as the cellar file shared with Android, with two
details that matter for exact round trips:

- dates are seconds since 1 January 2001 as a number (not ISO 8601 strings as
  in backups), so they compare equal after a round trip. Swift holds a date as
  a double, so it is exact; another platform must keep the number it read (or
  round new dates to a value a double holds exactly) so that it writes back the
  same number;
- UUIDs are strings (Swift writes them in uppercase; readers must accept either
  case), and a dictionary keyed by UUID (`wines`, `bottles`, `discoveries`,
  `members`, `stamps` of a membership) is an array alternating key and value:
  `["<uuid>", {…}, "<uuid>", {…}]`.

## 8. Remote sync

Devices that are apart exchange encrypted files through a **storage**: a
WebDAV folder the user already has (a NAS, Nextcloud, pCloud…), a relay
(`relay/`, an HTTP service anyone can host) or, between Apple devices signed
in to the same Apple Account, the user's iCloud (CloudKit private database,
offered when the app is built with an iCloud container). Google Drive, Dropbox
and Proton Drive may follow (see [REMOTE_SYNC.md](REMOTE_SYNC.md) for the
analysis). Any storage that can list, read, create (never replace) and delete
files works, because every name is created at most once: each snapshot has a
single writer, and a photo or document file may be created by any device, always
with the same content (a second create is refused and changes nothing).
Implementation: `RemoteSync.swift`,
`RelayMailbox.swift`, `WebDAVMailbox.swift` (and their Kotlin ports), and
`CloudKitMailboxStore.swift` in the Apple app.

### Keys

A **cellar key** `{cellarID, epoch (UInt32), key (32 random bytes), removed?}`
is shared by the devices of a cellar. A received key whose `key` is not 32 bytes,
or whose epoch is not a UInt32, fails decoding. `removed` lists (uppercase UUIDs,
sorted) the devices removed by another device before the key was made: it was
given to none of them. It holds at most 64 entries, so a grant stays well under
4 KiB: the newest removals first (greatest stamp counter, then lowercase ID),
then others by ID. Two keys are the same key when their cellar, epoch and bytes
are; `removed` is what is known about it. Earlier versions ignore `removed` and
drop it when they pass a key on, and rotate only after their own removals. So a
key without it, received from another device (the grant's sender, or the peer
of a network sync), is taken to leave out only the removals made by that
device; a device's own stored key without it is settled once when the app
starts, before any sync, to leave out the removals known then. A device that
receives the key bytes it already has keeps the union of both lists. JSON:
`{"cellarID":UUID,"epoch":number,"key":Base64 of 32 bytes,"removed":[UUID]?}`.
From the key (HKDF-SHA256, empty salt):

| Name | Info | Use |
|---|---|---|
| token | `encave/v1/relay-auth` | Base64URL without padding: proves the right to use the mailbox |
| data key | `encave/v1/data` | encrypts the files |

The **mailbox ID** is `c` followed by the first 32 lowercase hex characters of
SHA-256 of the token (its UTF-8 string). A relay checks a token by recomputing
this, so it stores nothing and nobody can claim a mailbox before its owner.

Each device also has an **inbox**: token = Base64URL without padding of
`KDF(identity private key, empty salt, "encave/v1/inbox-auth")`, ID = `i`
followed by the first 32 lowercase hex characters of SHA-256 of that token. The
ID is published in the device's member record (`Member.inbox`); a member
without one gets keys only during network syncs.

A key **supersedes** another of the same cellar if its epoch is greater, or, on
the same epoch (two devices turned remote sync on, or rotated, at the same
time), if the SHA-256 of its 32 key bytes is smaller, compared byte by byte as
unsigned values.
Devices always move to a superseding key, so they settle on one.

### Files

Every file except grants is `"ENCV" ‖ 0x01 ‖ kind ‖ nonce (12) ‖ ciphertext ‖ tag`,
ChaCha20-Poly1305 with the data key, a random nonce, and `"ENCV" ‖ 0x01 ‖ kind ‖
path (UTF-8)` as additional data, so a file cannot be moved to another name.

| Path | Kind | Content |
|---|---|---|
| `snap/<device>/<clock, 16 digits>-<8 hex>` | 1 | `{"type":"snapshot","cellarData","syncData","membersData"}` as in the exchange |
| `photo/<file ID, lowercase>` | 2 | the photo's JPEG, or the document's file as it was added |

Each device writes only under its own `snap/<device>/` and keeps its newest two
snapshots. The clock in a snapshot's name is its `syncData` clock, written with
16 ASCII digits. Paths use lowercase letters, digits, `-`, `_` and `/`, at most 200
characters, with no empty, `.` or `..` parts.

### Key grants

A device gives a cellar key to another by creating, in the recipient's inbox,
`grant/<cellarID>-<epoch, 8 digits>-<sender device>-<8 random lowercase hex>`
(lowercase UUIDs; an epoch over 99,999,999 has more digits) containing
`ephemeralPublic (32) ‖ envelope(kind 3)` where the envelope's key is
`KDF(DH(ephemeral, recipient) ‖ DH(sender identity, recipient),
H("encave-grant-v1", [ephemeralPublic, recipientPublic, path]), "encave grant")`
and the plaintext is `{"cellarKey":…,"from":senderID,"storage":"https://…"}`.
Anyone may create files in an inbox, so a grant's name ends with a random
part: nobody can create a file under the name the sender will use and so keep
the grant out. A sender for whom the storage answers that the file already
exists treats it as a failure (the grant was not left) and tries again later.

The recipient accepts a grant only from a current member of its cellar and
only for its cellar; both identity keys enter the key, so only a member can
have written it. Only files under `grant/` are looked at; anything else in an
inbox is left alone. Grants are at most 4 KiB: a larger file is deleted unopened.
A file under `grant/` whose name does not have the form above is deleted, and
so is a grant that cannot be opened, except one from a device of this cellar not known yet:
up to 8 of those are kept for a later round (in name order), the others are
deleted. Grants from removed devices are deleted. The recipient also checks
that the grant's name gives the same cellar, epoch and sender as its content.
It uses a grant only if its key supersedes the current one. It deletes the
other grants it opened (the current key again, or a key that loses to the one
kept: a rotation retried after a failure may have left several) and the grants
of its cellar with an older epoch than the key it keeps.

### Storages

Each storage keeps the files of a mailbox or inbox under its ID:

- **Relay:** `/v1/m/<mailbox ID>/<path>`; requests carry the mailbox's token
  (`Authorization: Bearer`), which the relay checks against the ID
  (`relay/README.md`). One exception: anyone may create a `grant/…` file in an
  inbox without a token (that is how a device receives a key); listing,
  reading and deleting need the inbox's token. Every file created in an inbox
  is at most 4 KiB. A device leaving a grant sends no token. Redirects are not
  followed (an error), so a token is never sent to another address.
- **iCloud:** one CloudKit zone per mailbox and per inbox in the user's private
  database, named after its ID, so only devices on the same Apple Account meet
  there. Each file is a record of type `EncaveFile` whose record name is the
  path, with the bytes in a `CKAsset` field named `data`, created with the save
  policy `ifServerRecordUnchanged` (an existing record means the file exists).
  Listing reads the zone's changes since a change token kept in memory, so the
  first listing after launch reads the whole zone. Tokens are not sent.
- **WebDAV:** `<folder>/<mailbox ID>/<path>` under the folder the user chose,
  with the server's own login (HTTP Basic, ideally an app password); tokens are
  not sent.
  - List: `PROPFIND` with `Depth: 1`, folder by folder (`Depth: infinity` is
    off on most servers), from the deepest folder the prefix names. Hrefs may
    be absolute URLs, absolute paths or relative, percent-encoded; entries
    outside the mailbox folder or with an invalid path are ignored.
  - Read: `GET` (404: no such file). Delete: `DELETE` (404 is fine).
  - Create: `PUT` with `If-None-Match: *`; 412 means the file exists. A missing
    folder (409, or 404 on some servers) is made with `MKCOL` (405: it exists)
    and the `PUT` is tried once more.
  - 401 means the login was refused: the app then makes no automatic round
    until a new login or Sync Now, so the server does not lock the account.
    403 means the login works but the folder is refused. 507 means the storage
    is full, 413 that the file is too large; redirects are not followed, so the
    login is never sent to another address.
  - Only mailbox IDs and paths that pass the rules above become part of a URL.
  - A server that ignores `If-None-Match` replaces an existing file instead of
    answering 412. The protocol does not depend on it: snapshot and grant names
    carry a random part, so no name is written twice, and photos are named
    after their content.

### One round

1. Read the inbox; adopt a granted key that supersedes the current one (and
   start reading the new mailbox from scratch). If the grant names another
   storage than the one the round runs on, the round ends there, so nothing is
   read or written under the new key on the old storage; the next round runs on
   the grant's storage.
2. List `snap/`; for each other current member (a device of the membership,
   not removed; files of other device IDs are ignored), read its newest
   snapshot if newer than the last one read, check the bounds (section 1) and
   merge it. A snapshot counts only if its `syncData` names the same device and
   the same clock as its path and its `membersData` the same cellar ID. A snapshot that cannot be
   read, decrypted, decoded or checked is skipped for this round; the other
   devices still sync.
3. Fetch the files (photos and documents, section 7) the merged cellar
   references and this device lacks (valid file IDs only).
4. If anything changed since this device's last upload (its clock, or any
   stamp: a merge on the local network can change the cellar without moving
   the clock), or the key is new: upload the files it has not uploaded yet in
   this epoch (once each, even if a file is both a photo and a document; files
   fetched in step 3, and those `photo/` already lists, count as uploaded),
   then a snapshot of the merged state, then delete its older snapshots. If
   the clock is not above both the last uploaded clock and the newest of this
   device's own snapshots listed in step 2, it is first set to one more than
   the greater of them, so readers see a newer snapshot. A file the storage
   refuses as too large (413, or CloudKit's `limitExceeded`) is left out; when
   the storage is full (507, or `quotaExceeded`), the remaining files are left
   out for this round too. The snapshot is still written; the device says so
   and tries the files again with its next snapshot.

After a local change, a device waits 2 seconds without further changes, syncs
with the devices on the network, then runs a round 3 seconds later. Rounds also
run every minute while the app is open (counted from the end of the previous
round) and when it comes back to the foreground. Each device can choose every
5 or 15 minutes instead, or to sync only when the user asks (a device-only
setting, which also stops the rounds after a change and on returning to the
foreground; changes still go to devices on the same network). A new interval
counts from the last round: the next round comes one interval after it, at once
if that is past. In
the background, where the system allows it: on Android a periodic worker at
the chosen interval, at least 15 minutes apart; on iPhone and iPad an app
refresh task, no sooner than 15 minutes, when iOS decides.

### Removing a device

Any device that holds the key and runs rounds, not only the one that removed a
device (it may have no storage, and the removal may reach the others over the
local network), rotates the key before a round when its membership has a
device removed by another device that the key's `removed` does not list (with
64 entries listed, removals older than the oldest listed one count as left
out). A device that left removed itself and forgot the key (section 2): it calls for no
rotation. A device that finds itself removed never rotates.

Before rotating, the device reads its inbox and adopts a superseding key (unless
the grant names another storage: the round follows it first, and rotates
there). The new key's epoch is one more than the greatest epoch seen (the
current key, a key granted, the kept key); at epoch 4,294,967,295 the device
refuses to rotate and says so. Its `removed` is the union of the removals in the
membership and the current key's `removed`, and no device in it gets a grant.
The device keeps the new key, and the devices it has given it to, until the
rotation is done, so a retry after a failure reuses the same key and leaves no
second grant for them. A device whose grant fails (its inbox is full, its record
is damaged) does not stop the rotation: the device notes it and tries again in
each later round until the grant is left; after 5 rounds it tells the user. A
round cut short by the system is not a failed grant. Moving to another storage
forgets which devices were given the kept key.
It leaves a grant in each remaining member's inbox, writes to the **old**
mailbox a snapshot with an empty cellar, no object stamps, its clock and the
updated membership (so devices that have not switched yet learn about the
removal, while the removed device, which can still read the old mailbox, learns
nothing else), and writes the cellar to the **new** mailbox. The removed device
cannot find the new mailbox (its ID comes from the new key) and its files in
the old one are ignored, because it is no longer a member. If, before the
rotation is done, the device receives a key that supersedes the kept one, or one
of the same or a later epoch while it has given the kept key to nobody, it drops
the kept key, and rotates only if the received key does not list a removal. If
it has given the kept key to some devices and the received key does not
supersede it, or a device the kept key was given to is removed meanwhile, it
replaces the kept key with a new one above every epoch seen.

Two devices that remove different devices at the same time may both rotate to
the same epoch; the supersedes rule then picks one key, which may have been
given to the device the other one removed. The other device then sees a removal
the key does not list and rotates again, so the cellar settles on a key that
neither removed device has.

A device on an earlier version rotates only after its own removals and leaves
keys without `removed`. Taking such a key as leaving out its sender's removals
means devices updating at different times do not rotate again for removals
the sender made, while a removal the sender had not heard of still calls for a
rotation.

### Turning it on

A device that turns on remote sync first reads its inbox. If another member
already left it a key, it uses that key. Otherwise it creates the key (epoch 1)
and leaves a grant for every other member that has an inbox. The key and the
storage also travel in the `sync-keys` step of every network sync (section 7),
so devices pick up the setting when they meet.

`storage` is one of:

- `"icloud"`;
- `"webdav:"` followed by the folder URL, ending with `/`, for example
  `"webdav:https://nas.example.com/encave/"`;
- the relay's URL.

A device uses a storage told by another device only if it has none yet and its
user has not turned remote sync off on it (Android: the turned-off choice is not
kept, so a storage told by another device is taken up again). It accepts only an
`https://` relay, a WebDAV folder over `https://` (or `http://` for a server
on the local network: `localhost`, a private or link-local IP address, or a
name of the home network, `.local` and `.home.arpa` names included, which is
resolved before each request and reached only at one of its local addresses,
or refused if it has none), or iCloud on Apple devices that support it and are
signed in to iCloud. A device that receives a new key in a grant also moves to
the grant's storage.

Turning remote sync on settles the key (from a grant, or a new one with its
grants left) before anything is kept, so a failure leaves remote sync off
(Android: the storage and the new key are kept even if leaving a grant fails).

The WebDAV login never travels: not in grants, `sync-keys` or snapshots. Each
device keeps its own username and password (Keychain, Android Keystore) for one
folder URL; a device told about a folder it has no login for asks the user to
sign in to it, and syncs through it once they have.

## 9. Security notes

- **What a network observer sees:** that devices running Encave exchange
  encrypted data, their IP addresses, sizes and timing, the random device IDs
  and cellar tags in the Bonjour records.
- **Pairing:** the 6-digit pairing code is not a secret; an attacker on the
  same network who sees it can try to pair, but the users then see two
  different confirmation codes (or the joiner sees a host that does not match
  the scanned QR code). Users must compare the codes; the app says so.
- **Removing a device** removes its public key from the membership; other
  devices refuse its handshakes as soon as they have the updated membership.
  It keeps the data it already has.
- **The relay** sees mailbox and inbox IDs, file counts, sizes, timing and IP
  addresses; never contents, names or keys. It also sees file paths: device
  IDs and Lamport clocks in snapshot names (how often the cellar changes), the
  cellar ID, epoch and sender in grant names (and their random part), and the
  hashes of photos and documents, with each file's size (a document's size
  and the moment it was added say a little about it). Device
  IDs are the same before and after a key rotation, so the relay can link the
  old and new mailboxes. A mailbox opens only with the token its ID is derived
  from, so someone who learns a mailbox ID cannot read it or lock its owner out.
  The one exception is an inbox: anyone who knows its ID can create small
  `grant/…` files in it (at most 4 KiB each, within the inbox's limits), which
  the owner deletes if they cannot be opened.
- **A WebDAV server** sees the same as a relay, and its account holders can
  read the encrypted files and delete or replace them: a damaged or replaced
  file fails to open and is skipped. Anyone who can open the folder can do the
  same, since mailbox tokens are not checked there.
- **On the local network,** the Bonjour records (`d`, `c`) stay the same on
  every network, so they can track a device across networks. During pairing,
  device names cross the network in plain text in steps 2 and 3 (the names
  that are stored come from the encrypted step 7).
- **Not covered yet:**
  - A device that is lost while still a member keeps access until another
    device removes it.
  - Before a key rotation, a removed device that still holds the old key could
    write files under another device's name in the old mailbox. It cannot add
    itself back (removals are final), but it could send wrong cellar content.
    Signing files with device keys (planned) closes this.
  - A malicious member can send any cellar content; the bounds on clocks only
    keep it from breaking other devices.
  - The protocol version is not yet bound into the pairing and sync
    transcripts; it must be before a version 2 exists, so a device in the
    middle cannot hold two newer devices to version 1.
  - View-only members.
  - Remote pairing with a 6-digit code, planned with a PAKE through the relay.

## 10. Test vectors

The inputs and values are in `fixtures/sync/protocol-vectors.json`.
`ProtocolVectorTests.swift` and `ProtocolVectorTests.kt` read that file, check
the values, and check that the tables below give the same ones. Inputs (all
keys are X25519 private keys given as 32 raw bytes):

- host ephemeral private key: bytes `01 02 03 … 20`; joiner ephemeral: `41 42 … 60`
- host nonce: 32 × `a0`; joiner nonce: 32 × `b0`
- host device: `11111111-1111-4111-8111-111111111111`; joiner device:
  `22222222-2222-4222-8222-222222222222`; cellar: `33333333-3333-4333-8333-333333333333`
- sync session: initiator = host (ephemeral and nonce as above, identity
  private key `81 82 … a0`), responder = joiner (identity private key
  `c1 c2 … e0`)
- first frame: `{"type":"photos-end"}` sent by the initiator (counter 0),
  with the initiator to responder key
- frame 5 from the responder: `{"type":"photos-end"}` sent by the responder
  (counter 5), with the responder to initiator key

| Value | Expected |
|---|---|
| host ephemeral public | `07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c` |
| joiner ephemeral public | `64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d48466` |
| commitment | `0096fb81cae546de04c7851836d2c4f8e5e9b9c97a1eb42b2a4b01cb32a9b5e2` |
| confirmation code | `141 953` |
| pair host to joiner | `a79c1d1f93a7699a239e54c6a62a9321e53b3e81a8cb3710c505c3f2c61fb354` |
| pair joiner to host | `47c1553f86cbcc6389e5a2562434f866f113a6b8268138ca5a9321e0bcb21019` |
| sync auth | `b37137f432db51fbb80805e75d49ab80cd1e3bb990f3c559a8decf54c14c62de` |
| sync initiator to responder | `bfe9c2c7723bdc71a54ed30da89382476a1f8abd8b90e6447767f4428bd56dfc` |
| sync responder to initiator | `718fd5b20b9fed4805c0fa0f6efe235c7f476228f10741211a3a8acc0245ba5f` |
| first frame (ciphertext ‖ tag) | `8f7553c327cfefd5e3c77017246680dc74a070adfcdf6b09a06a2a8bf7c21dd2bbe39db85c` |
| frame 5 from the responder (ciphertext ‖ tag) | `76a6de13dc5fb6410280aa6bb34ad4dcc318628c2e465dde6ab3dc7fc32139fbcadbbda024` |
| rendezvous of `123456` | `2bc4802d60f2f128` |
| network tag of the cellar | `aedcc6a68baa` |

**Remote sync** (section 8). Inputs: cellar key `{cellar above, epoch 1, key
bytes 10 11 … 2f}`; device identity private key `81 82 … a0` (the host device
above); for the envelope, plaintext `hello`, kind 2 (photo), path `photo/x`,
nonce 12 × `07`; for the grant, path `grant/<cellar>-00000002-<host device>-0a1b2c3d`
(lowercase UUIDs), ephemeral private key `01 02 … 20`, recipient identity
private key `c1 c2 … e0`, sender identity `81 82 … a0`. A second cellar key
`{cellar above, epoch 2, key bytes 30 31 … 4f}`: the epoch does not enter the
derived values, only the key bytes do. The grant file holds that key, from the
host device, with storage `icloud`, sealed with the grant key above and
envelope nonce 12 × `09`. Its plaintext is the JSON both platforms write (keys
sorted, no spaces). Swift writes a `/` in a string (a URL, or Base64) as `\/`,
so such a grant's bytes differ between platforms; both read either form.

| Value | Expected |
|---|---|
| relay token | `zyJIksEyVCrPawgL8q0UGr5GMi1KewaCGSzwHGOLFno` |
| mailbox ID | `cace8487402ad2a081f417653a8443def` |
| data key | `d9bdb64332f3da0ef2e703d1cbdf3be079c75095ce76a6818f07fdfa2b802f54` |
| inbox token | `K9Ij7YuyprGlxKKF0rXF1bTbBNHtk2czQHE87LzJvx8` |
| inbox ID | `i2a17946aea22c15127c53bac03a59f8a` |
| envelope (whole file) | `454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf` |
| grant key | `b2ecfff6ab54c6b39ae9edbdd44abffc2dbc361e2faa6571cd4abc5fba9d517d` |
| epoch 2 relay token | `VoGlMN7nVFG_rC-Hdqw0T-IRpc515349SBrXDlKS-x0` |
| epoch 2 mailbox ID | `caf53d3e721c812253477388b93c90a43` |
| epoch 2 data key | `45577e756346781b26cf7b9adace1841dfa975ae81cc9f0e6cb3d9920debeb6b` |
| grant plaintext | `{"cellarKey":{"cellarID":"33333333-3333-4333-8333-333333333333","epoch":2,"key":"MDEyMzQ1Njc4OTo7PD0+P0BBQkNERUZHSElKS0xNTk8="},"from":"11111111-1111-4111-8111-111111111111","storage":"icloud"}` |
| grant (whole file) | `07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c454e435601030909090909090909090909095edd6e9494612397862e23735344dda3b9793decc01e1e8f615c322ca83d9cf473cd9c8a832575eaee4ed82d148c4d3af3c9a1f1f38d5ea43d407a38389d62b2d25cf3ffb2ddc73be54b9ef928b21b14b745a9c9920158e2b5f1ade54c1938dd538debb4c5bad930356440eb24070a2f4ce652aa6506ae789de5688e2d48f31106c4548d5fa0eaf9e1483fd955c61ba891e1734e14a9aa7f1200c390af3a21ced0c670cc87f94aa035594f8c54bcf7125f50260c531b8e298cb6c2a43a7cfbe5e8c0f11eb6d38fdbb4cd87177b716092e4` |

**Files that must be refused.** Each is opened with the data key of the epoch 1
key above. Only the first opens; a device must refuse the others.

| Case | Kind | Path | File | Expected |
|---|---|---|---|---|
| the envelope above | 2 | `photo/x` | `454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf` | `hello` |
| opened as a snapshot | 1 | `photo/x` | `454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf` | refused |
| kind byte changed to snapshot | 1 | `photo/x` | `454e435601010707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf` | refused |
| moved to another path | 2 | `photo/y` | `454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf` | refused |
| last tag byte flipped | 2 | `photo/x` | `454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbe` | refused |
