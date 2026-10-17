# Sync protocol (version 1)

How Encave devices pair and keep one cellar in step. This document is the
specification: the Swift implementation is in
`apple/Packages/CaveKit/Sources/CaveKit/SyncState.swift` and `SyncProtocol.swift`,
and any other implementation (the Android app) must produce the same bytes. The
test vectors at the end are checked by `ProtocolVectorTests`.

- **Local network (this document, sections 1–7):** devices on the same network
  find each other with Bonjour/mDNS and talk directly over TCP. No server.
- **Remote sync (section 8):** devices that are apart exchange encrypted files
  through storage that holds only ciphertext (iCloud, a relay). The choice of
  storage is explained in [REMOTE_SYNC.md](REMOTE_SYNC.md).

## 1. Data model and merge

The cellar file (`cellar.json`, also the backup format) is unchanged by sync.
Sync metadata lives next to it in `sync.json` (`SyncState`) and the list of
devices in `devices.json` (`Membership`).

### Objects and stamps

Each object has a key: `wine:<uuid>`, `bottle:<uuid>`, `rack:<uuid>`,
`discovery:<uuid>`, plus `settings` (the cellar settings) and `rack-order` (the
order of the racks). UUIDs in keys are lowercase.

A **stamp** is a Lamport time: `{counter: UInt64, device: UUID, deleted: Bool}`.
Stamps are ordered by counter, then by the device UUID's uppercase string,
then a deletion after a non-deletion. `Stamp.zero` (counter 0, the nil UUID)
stands for objects that exist but were never stamped.

`SyncState = {version: 1, device, clock, stamps: {key: stamp}}`.

- **Recording.** After each local change the app compares the cellar before and
  after: every object that was added or differs gets `clock += 1` and a stamp
  `(clock, device)`; every removed object gets a stamp with `deleted: true`.
  A change to the rack sequence stamps `rack-order`; a change to settings
  stamps `settings`.
- **Starting.** When sync is set up on a cellar that already has data, every
  object gets a first stamp (`SyncState.starting`).

### Object format

Objects are the records of the cellar file, encoded as JSON as described in
section 7 (dates, UUID-keyed maps). Optional fields are left out when empty.
Two details matter for other implementations:

- `bottle.status` is one of `inCellar`, `consumed`, `gone` or `ordered`
  (bought, not delivered yet; such a bottle has no place and may carry
  `expectedAt`, a date).
- Readers are strict: an unknown enum value fails decoding. A device that does
  not know a new value refuses the sync instead of rewriting the object with a
  wrong value and a newer stamp, which would spread the loss to every device.

### Merge

`merge(local, localState, remote, remoteState)` is deterministic: both devices
compute the same result.

1. For every key in either cellar or either stamp map, take each side's stamp
   (or `Stamp.zero` if the object exists without one). The greater stamp wins:
   its object is kept, or the object is dropped if the winning stamp is a
   deletion. Equal stamps come from the same change on the same device, so
   the content is the same; as a safeguard, the Swift code then keeps the
   version whose sorted-keys JSON encoding is lexicographically greater.
2. Settings: same rule with the `settings` stamp.
3. Rack order: the order of the side with the greater `rack-order` stamp, then
   the other side's racks; racks are deduplicated, unknown ones dropped.
4. **Repair**, the same on every device:
   - bottles and discoveries whose wine is gone are removed;
   - bottles not in the cellar, or in a rack that no longer exists or no
     longer contains their place, lose their place;
   - if two bottles share a place, the one with the greater stamp keeps it and
     the others lose their place (they stay in the cellar);
   - wines with no bottles and no discoveries are removed.
5. The merged clock is the greater of both clocks; the device keeps its own ID.

A device that applies a merge result while the user edited during the session
merges again with its current state, so no local change is lost.

## 2. Devices and keys

Each device has a long-term **identity key** (X25519). The private key never
leaves the device (Keychain on Apple platforms). Its device ID is a random UUID.

`Membership = {cellarID, members: {id: Member}, stamps: {id: Stamp}}` with
`Member = {id, name, publicKey (32 bytes), platform ("ios", "ipados", "macos",
"android"), addedAt}`. Adding a member stamps it; removing it leaves a deletion
stamp, so an older copy cannot add it back. Two memberships merge per member
with the same stamp rule.

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
  `"ddd ddd"`.

## 4. Transport

TCP. Each **frame** is a 4-byte big-endian length followed by that many bytes;
frames over 16 MiB are refused. Before encryption starts, a frame is one JSON
object (UTF-8, keys sorted, binary values in standard Base64). After the
handshake, each frame is `ciphertext ‖ tag` of ChaCha20-Poly1305 over that JSON
object, with:

- a key per direction (see below);
- a 12-byte nonce: 4 zero bytes, then the frame counter of that direction as an
  8-byte big-endian integer, starting at 0;
- no additional data.

A frame that fails to decrypt ends the session. Messages always have a `type`.
A device that refuses a session may send `{"type":"error","reason":…}` with
reason `unknown-device` (not known yet, for example added recently on another
device), `removed-device` (removed from the cellar), `other-cellar`,
`unsupported-version` or `rejected` before closing. Only `removed-device`
means the device was removed.

**Discovery (Bonjour).** Service type `_encave._tcp`.

- A device that syncs advertises the name `Encave-<first 8 characters of its
  device ID>` with TXT records `d` = its device ID (lowercase), `c` = the
  cellar's `networkTag`, and `v` = `1`.
- A device showing a pairing code advertises `Encave-pair-…` with TXT `p` =
  `rendezvous(code)` = first 8 bytes, lowercase hex, of
  `H("encave-pair-rendezvous-v1", [the 6 digits])`.

Device names are never advertised.

## 5. Pairing

The **host** shows a 6-digit code (and a QR code), the **joiner** types or scans
it. The code only finds the host on the network; security comes from the users
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
   "identityKey":hostIdentityPublic,"membersData":<Membership JSON>}`.
7. Joiner → host: `{"type":"pair-member","identityKey":joinerIdentityPublic}`.
8. The host adds the joiner to its membership; the joiner adopts the cellar ID
   and members and adds itself. Then they run the exchange (section 7) on the
   same channel, the host as initiator.

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

1. `{"type":"sync-state","cellarData":<cellar JSON>,"syncData":<SyncState JSON>,
   "membersData":<Membership JSON>}` each way. Both merge (section 1) and merge
   memberships.
2. `{"type":"photos-want","photos":[ids]}` each way: the photos referenced by
   the merged cellar that the device does not have.
3. The initiator sends `{"type":"photo","photoID":…,"photoData":…}` for each
   requested photo that the merged cellar references, then
   `{"type":"photos-end"}`; then the responder does the same.

4. `{"type":"sync-keys","syncData":<RemoteInfo JSON or absent>}` each way
   (initiator first): the remote sync key and storage this device uses (section
   8), so turning remote sync on reaches the other devices. A key for another
   cellar is ignored; a device adopts a key with a higher epoch than its own.
   This key is never written into a snapshot.

Photo IDs of new photos are `sha256-` + the lowercase hex SHA-256 of the JPEG,
and a received photo whose content does not match its ID is ignored. IDs from
before sync (UUIDs) are accepted as they are. Photo IDs are restricted to
letters, digits and dashes, at most 80 characters.

JSON uses the same structure as the cellar file shared with Android, with two
details that matter for exact round trips:

- dates are seconds since 1 January 2001 as a number (not ISO 8601 strings as
  in backups), so they compare equal after a round trip;
- UUIDs are strings (Swift writes them in uppercase; readers must accept either
  case), and a dictionary keyed by UUID (`wines`, `bottles`, `discoveries`,
  `members`, `stamps` of a membership) is an array alternating key and value:
  `["<uuid>", {…}, "<uuid>", {…}]`.

## 8. Remote sync

Devices that are apart exchange encrypted files through a **storage**: today a
relay (`relay/`, an HTTP service anyone can host); CloudKit and Google Drive are
planned (see [REMOTE_SYNC.md](REMOTE_SYNC.md) for the analysis). Any storage that
can list, read, create (never replace) and delete files works, because every
file is written once by one device. Implementation: `RemoteSync.swift`,
`RelayMailbox.swift`.

### Keys

A **cellar key** `{cellarID, epoch (UInt32), key (32 random bytes)}` is shared
by the devices of a cellar. From it (HKDF-SHA256, empty salt):

| Name | Info | Use |
|---|---|---|
| mailbox ID | `encave/v1/mailbox` (16 bytes) | `c` + lowercase hex: where the cellar's files are |
| token | `encave/v1/relay-auth` | Base64URL without padding: proves the right to use the mailbox |
| data key | `encave/v1/data` | encrypts the files |

Each device also has an **inbox**: ID `i` + lowercase hex of the first 16 bytes
of `H("encave-inbox-v1", [identity public key])`, token = Base64URL of
`KDF(identity private key, empty salt, "encave/v1/inbox-auth")`.

### Files

Every file except grants is `"ENCV" ‖ 0x01 ‖ kind ‖ nonce (12) ‖ ciphertext ‖ tag`,
ChaCha20-Poly1305 with the data key, a random nonce, and `"ENCV" ‖ 0x01 ‖ kind ‖
path (UTF-8)` as additional data, so a file cannot be moved to another name.

| Path | Kind | Content |
|---|---|---|
| `snap/<device>/<clock, 16 digits>-<8 hex>` | 1 | `{"type":"snapshot","cellarData","syncData","membersData"}` as in the exchange |
| `photo/<photo ID, lowercase>` | 2 | the JPEG |

Each device writes only under its own `snap/<device>/` and keeps its newest two
snapshots. Paths use lowercase letters, digits, `-`, `_` and `/`.

### Key grants

A device gives a cellar key to another by creating, in the recipient's inbox,
`grant/<cellarID>-<epoch, 8 digits>-<sender device>` containing
`ephemeralPublic (32) ‖ envelope(kind 3)` where the envelope's key is
`KDF(DH(ephemeral, recipient) ‖ DH(sender identity, recipient),
H("encave-grant-v1", [ephemeralPublic, recipientPublic, path]), "encave grant")`
and the plaintext is `{"cellarKey":…,"from":senderID,"storage":"https://…"}`.
The recipient accepts a grant only from a current member of its cellar and
only for its cellar; both identity keys enter the key, so only a member can
have written it.

### One round

1. Read the inbox; adopt a granted key with a higher epoch.
2. List `snap/`; for each other current member, read its newest snapshot if
   newer than the last one read, and merge it (section 1).
3. Fetch the photos the merged cellar references and this device lacks.
4. If anything changed since this device's last upload: upload its missing
   photos, then a snapshot of the merged state, then delete its older snapshots.

Rounds run a few seconds after a change, every minute while the app is open,
and when it comes back to the foreground.

### Removing a device

The device that removes another creates a new cellar key (epoch + 1), leaves
a grant in each remaining member's inbox, writes a snapshot with the updated
membership to the **old** mailbox (so devices that have not switched yet learn
about the removal) and the cellar to the **new** one. The removed device cannot
find the new mailbox (its ID comes from the new key) and its files in the old
one are ignored, because it is no longer a member.

### Turning it on

The first device to turn on a relay creates the key (epoch 1) and leaves a grant
for every other member. The key and relay address also travel in the
`sync-keys` step of every network sync (section 7), so devices pick up the
setting when they meet.

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
  addresses; never contents, names or keys. The first token used with a
  mailbox is the only one it accepts afterwards, so someone who learns a
  mailbox ID cannot read it.
- **Not covered yet:**
  - A device that is lost while still a member keeps access until another
    device removes it.
  - Before a key rotation, a removed device that still holds the old key could
    write files under another device's name in the old mailbox. Signing files
    with device keys (planned) closes this.
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
