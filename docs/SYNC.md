# Sync protocol (version 1)

How Encave devices pair and keep one cellar in step. This document is the
specification: the Swift implementation is in
`apple/Packages/CaveKit/Sources/CaveKit/SyncState.swift` and `SyncProtocol.swift`,
and any other implementation (the Android app) must produce the same bytes. The
test vectors at the end are checked by `ProtocolVectorTests`.

- **Local network (this document, sections 1–7):** devices on the same network
  find each other with Bonjour/mDNS and talk directly over TCP. No server.
- **Remote sync (section 8):** devices that are apart exchange encrypted files
  through storage that holds only ciphertext (iCloud, a WebDAV folder, a relay).
  The choice of storage is explained in [REMOTE_SYNC.md](REMOTE_SYNC.md).

## 1. Data model and merge

The cellar file (`cellar.json`) is unchanged by sync. Backups hold the same
cellar inside a small envelope, with dates as ISO 8601 strings.
Sync metadata lives next to it in `sync.json` (`SyncState`) and the list of
devices in `devices.json` (`Membership`).

### Objects and stamps

Each object has a key: `wine:<uuid>`, `bottle:<uuid>`, `rack:<uuid>`,
`discovery:<uuid>`, `search:<uuid>` (a saved search), plus `settings` (the
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
- `wine.barcodes` and `wine.tags` are optional arrays of strings, left out
  when empty, and so is `wine.extraPhotoIDs` (more photos; they are synced like
  `photoID`). `wine.currentPrice` (a number, like `bottle.purchasePrice`),
  `wine.currentPriceCurrency` (an ISO code) and `wine.currentPriceDate` (a date)
  are optional: the price of a bottle today, accepted by the user from a web
  search or typed. `bottle.reservedFor` is an optional string (the occasion a bottle
  is kept for). `settings.savedSearches` is an optional array of
  `{id, name, query, colors, maturities, rackID?, tag?, reservation?}`, with
  `colors` and `maturities` sorted by their string value.
- Readers are strict: an unknown value of `wine.color`, `bottle.status`,
  `rack.kind` or `discovery.setting` fails decoding; readers never replace it
  with a default. A device that does not know a new value refuses the sync
  instead of rewriting the object with a wrong value and a newer stamp, which
  would spread the loss to every device. (A rack without `kind`, from version
  0.1 or 0.2, is a grid.) In the same way:
  - required fields are never filled in by the reader: every `id`, `wineID`,
    `bottle.addedAt`, `discovery.date`, `tasting.date`, `member.addedAt`,
    `settings.currency`, `cellarID`, `members` and `stamps` of a membership, the
    three fields of a stamp and the four of a `SyncState`. Fields that may be
    left out are read with their stated default
    (`bottle.status` `inCellar`, `sizeML` 750, `depth` 0 or 1, `binCapacity` 12,
    `grapes` and `discovery.tags` empty, `wantsToBuy` false);
  - a UUID is its 36-character form (either case), binary values are standard
    Base64 with padding and nothing else, prices are JSON numbers, and dates are
    numbers in the cellar file and the sync payloads, ISO 8601 strings without
    fractional seconds in backups;
  - a rack's `rows`, `columns`, `binCapacity` and each `rowColumns` value are
    brought into 1…100 (`depth` into 1…3), and `rowColumns` keeps at most 100
    values, so a damaged or hostile rack cannot make a device allocate millions
    of cells. Every device applies the same rule, so they read the same rack.
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

## 2. Devices and keys

Each device has a long-term **identity key** (X25519). The private key never
leaves the device (Keychain on Apple platforms). Its device ID is a random UUID.

`Membership = {cellarID, members: {id: Member}, stamps: {id: Stamp}}` with
`Member = {id, name, publicKey (32 bytes), platform ("ios", "ipados", "macos",
"android"), addedAt, inbox?}`. `inbox` is the device's relay inbox ID
(section 8); each device sets it in its own record, with a new stamp, when it
is missing. Adding a member stamps it; removing it leaves a deletion stamp.

Two memberships merge per member with the stamp rule, except that **a removal
is final**: a deletion stamp wins over any non-deletion stamp, whatever the
counters. A removed device cannot add itself back by sending a newer record;
to join again it pairs as a new device, with a new ID and key.

`networkTag(cellarID)` = first 6 bytes, as lowercase hex, of
`H("encave-cellar-tag-v1", [cellarID lowercase string])`.

## 3. Primitives

- `H(label, parts)` = SHA-256 of `label` (UTF-8) followed, for each part, by its
  length as a 4-byte big-endian integer and its bytes.
- `KDF(secret, salt, info, n)` = HKDF-SHA256 with input key material `secret`,
  `salt`, `info` (UTF-8), `n` bytes (32 unless stated).
- `DH(private, public)` = X25519. A result of all zero bytes (low-order point)
  is rejected.
- Random nonces are 32 bytes.
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
host's, or to **replace** it (start from an empty cellar).

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
2. `{"type":"photos-want","photos":[ids]}` each way: the photos referenced by
   the merged cellar (`photoID` and `extraPhotoIDs` of every wine) that the
   device does not have.
3. The initiator sends `{"type":"photo","photoID":…,"photoData":…}` for each
   requested photo that the merged cellar references, then
   `{"type":"photos-end"}`; then the responder does the same.

4. `{"type":"sync-keys","syncData":R}` each way, where `R` is a standard Base64
   string of the `RemoteInfo` JSON, or is left out (initiator first): the
   remote sync key and storage this device uses (section
   8), so turning remote sync on reaches the other devices. `RemoteInfo` is
   `{"key":CellarKey,"storage":string?}` and `CellarKey` is
   `{"cellarID":UUID,"epoch":number,"key":Base64 of 32 bytes}`. A key for
   another cellar is ignored; a device adopts a key that supersedes its own
   (section 8). This key is never written into a snapshot.

Photo IDs of new photos are `sha256-` + the lowercase hex SHA-256 of the JPEG,
and a received photo that was not asked for, or whose content does not match
its ID, is ignored. IDs from
before sync (UUIDs) are accepted as they are. Photo IDs are restricted to
ASCII letters, digits and dashes, at most 80 characters; a photo with any other ID is
never requested, sent, uploaded or downloaded.

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
(`relay/`, an HTTP service anyone can host) or, between Apple devices, the
user's iCloud (CloudKit private database, offered when the app is built with an
iCloud container). Google Drive, Dropbox and Proton Drive may follow (see
[REMOTE_SYNC.md](REMOTE_SYNC.md) for the analysis). Any storage that
can list, read, create (never replace) and delete files works, because every
file is written once by one device. Implementation: `RemoteSync.swift`,
`RelayMailbox.swift`, `WebDAVMailbox.swift` (and their Kotlin ports).

### Keys

A **cellar key** `{cellarID, epoch (UInt32), key (32 random bytes)}` is shared
by the devices of a cellar. A received key whose `key` is not 32 bytes fails
decoding. From it (HKDF-SHA256, empty salt):

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
| `photo/<photo ID, lowercase>` | 2 | the JPEG |

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
have written it. Grants are at most 4 KiB: a larger file is deleted unopened.
A file whose name does not have the form above is deleted, and so is a grant
that cannot be opened, except one from a device of this cellar not known yet:
up to 8 of those are kept for a later round (in name order), the others are
deleted. Grants from removed devices are deleted. The recipient also checks
that the grant's name gives the same cellar, epoch and sender as its content.
It uses a grant only if its key supersedes the current one, and it deletes the
grants of its cellar with an older epoch than the key it keeps.

### Storages

Each storage keeps the files of a mailbox or inbox under its ID:

- **Relay:** `/v1/m/<mailbox ID>/<path>`; requests carry the mailbox's token,
  which the relay checks against the ID (`relay/README.md`).
- **iCloud:** one CloudKit zone per mailbox in the user's private database.
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
  - 401 and 403 mean the login was refused, 507 that the storage is full, 413
    that the file is too large; redirects are not followed, so the login is
    never sent to another address.
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
3. Fetch the photos the merged cellar references and this device lacks (valid
   photo IDs only).
4. If anything changed since this device's last upload (its clock, or any
   stamp: a merge on the local network can change the cellar without moving
   the clock): upload its missing photos, then a snapshot of the merged state,
   then delete its older snapshots. If the clock did not move since the last
   upload, it is raised by one first, so readers see a newer snapshot.

Rounds run a few seconds after a change, every minute while the app is open,
and when it comes back to the foreground. Each device can choose a longer
interval, or to sync only when the user asks (a device-only setting, which
also stops the rounds after a change and on returning to the foreground). In
the background, where the system allows it: on Android a periodic worker at
the chosen interval, at least 15 minutes apart; on iPhone and iPad an app
refresh task, no sooner than 15 minutes, when iOS decides.

### Removing a device

The device that removes another creates a new cellar key (epoch + 1) and keeps
it until the rotation is done, so a retry after a failure reuses the same key.
It leaves a grant in each remaining member's inbox, writes to the **old**
mailbox a snapshot with an empty cellar, no object stamps, its clock and the
updated membership (so devices that have not switched yet learn about the
removal, while the removed device, which can still read the old mailbox, learns
nothing else), and writes the cellar to the **new** mailbox. The removed device
cannot find the new mailbox (its ID comes from the new key) and its files in
the old one are ignored, because it is no longer a member. If, before the
rotation is done, the device receives a key of the same or a later epoch, it
replaces the kept key with a new one at that epoch + 1.

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

A device uses a storage told by another device only if it has none yet, and
only an `https://` relay, a WebDAV folder over `https://` (or `http://` for a
local-network address: `localhost`, a `.local` or `.home.arpa` name, a private
or link-local IP address), or iCloud on Apple devices that support it. A device
that receives a new key in a grant also moves to the grant's storage.

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
  hashes of photos. Device
  IDs are the same before and after a key rotation, so the relay can link the
  old and new mailboxes. A mailbox opens only with the token its ID is derived
  from, so someone who learns a mailbox ID cannot read it or lock its owner out.
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

Inputs (all keys are X25519 private keys given as 32 raw bytes):

- host ephemeral private key: bytes `01 02 03 … 20`; joiner ephemeral: `41 42 … 60`
- host nonce: 32 × `a0`; joiner nonce: 32 × `b0`
- host device: `11111111-1111-4111-8111-111111111111`; joiner device:
  `22222222-2222-4222-8222-222222222222`; cellar: `33333333-3333-4333-8333-333333333333`
- sync session: initiator = host (ephemeral and nonce as above, identity
  private key `81 82 … a0`), responder = joiner (identity private key
  `c1 c2 … e0`)
- first frame: `{"type":"photos-end"}` sent by the initiator (counter 0)

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
| rendezvous of `123456` | `2bc4802d60f2f128` |
| network tag of the cellar | `aedcc6a68baa` |

**Remote sync** (section 8). Inputs: cellar key `{cellar above, epoch 1, key
bytes 10 11 … 2f}`; device identity private key `81 82 … a0` (the host device
above); for the envelope, plaintext `hello`, kind 2 (photo), path `photo/x`,
nonce 12 × `07`; for the grant, path `grant/<cellar>-00000002-<host device>-0a1b2c3d`
(lowercase UUIDs), ephemeral private key `01 02 … 20`, recipient identity
private key `c1 c2 … e0`, sender identity `81 82 … a0`.

| Value | Expected |
|---|---|
| relay token | `zyJIksEyVCrPawgL8q0UGr5GMi1KewaCGSzwHGOLFno` |
| mailbox ID | `cace8487402ad2a081f417653a8443def` |
| data key | `d9bdb64332f3da0ef2e703d1cbdf3be079c75095ce76a6818f07fdfa2b802f54` |
| inbox token | `K9Ij7YuyprGlxKKF0rXF1bTbBNHtk2czQHE87LzJvx8` |
| inbox ID | `i2a17946aea22c15127c53bac03a59f8a` |
| envelope (whole file) | `454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf` |
| grant key | `b2ecfff6ab54c6b39ae9edbdd44abffc2dbc361e2faa6571cd4abc5fba9d517d` |
