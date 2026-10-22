# Encave relay

A small HTTP service that lets Encave devices sync when they are not on the same
network. It stores encrypted files and nothing else: devices encrypt everything
before sending it, and the relay never has a key (see [docs/SYNC.md](../docs/SYNC.md)
and [docs/REMOTE_SYNC.md](../docs/REMOTE_SYNC.md)).

**You do not need a relay** to sync your own devices on the same Wi-Fi. A relay
is for devices that are apart, or for households with both iPhones and Android
phones. Anyone can run one; point the apps at it in **Settings › Devices ›
Sync away from home**.

## What the relay sees

- Mailbox IDs, which look random. The cellar's mailbox ID changes when a device
  is removed; each device's inbox ID stays the same.
- Encrypted files: their number, sizes and when they are written, and their
  names: random device IDs and change counters, and hashes of photos (see
  [docs/SYNC.md](../docs/SYNC.md) §9).
- The IP addresses of the devices that connect. The Node server keeps no logs;
  on Cloudflare, check your account's logging settings.

It never sees cellar contents, device names, photos or keys.

## API

| Request | Result |
|---|---|
| `GET /v1/health` (or `HEAD`) | `{"service":"encave-relay","version":1}` |
| `GET /v1/m/{mailbox}?prefix=…&after=…` | `{"files":[…],"next":"…"}` (pages of 500) |
| `GET /v1/m/{mailbox}/{path}` | the file, or 404 |
| `PUT /v1/m/{mailbox}/{path}` | 201, or 412 if the file exists (files are never replaced), 409 if a folder on its path is a file |
| `DELETE /v1/m/{mailbox}/{path}` | 204 |

Errors: 401 wrong or missing token, 403 missing access key (private relay),
400 invalid path, 413 file too large, 507 mailbox, inbox or relay full, 429 too
many requests or too much uploaded today.

- Mailbox IDs are `c` (a cellar) or `i` (a device's inbox) followed by 32
  lowercase hex characters.
- Paths use lowercase letters, digits, `-`, `_` and `/`.
- Requests carry `Authorization: Bearer <token>`. A mailbox ID is its kind
  followed by the first 32 hex characters of the SHA-256 of its token, so the
  relay checks tokens without storing any, and nobody can claim a mailbox
  before its owner. Requests with a wrong token create nothing, except in an
  inbox:
- In an inbox, anyone may create `grant/…` files of up to 4 KB, without a token
  (that is how a device receives a key); only the owner may list, read or
  delete. So anyone who can reach the relay can store a little there: the
  limits below keep that small, and a private relay can require an access key.
- The token, the access key and the declared size are checked before the body
  is received.

**Limits:**
- 8 MB per file, 4 KB per grant;
- 400 MB and 3,000 files per mailbox, 1 MB and 200 files per inbox;
- for the whole relay, 1,000 mailboxes and 10 GB by default (9,000 MB on
  Cloudflare, under R2's free storage);
- 300 requests per minute per client (an IPv4 address or an IPv6 /64), on both
  the Node server and Cloudflare;
- on the Node server, 2 GB uploaded per client a day, and writes stop when the
  disk has less than 1 GB free.

## Private relay (access key)

Set `RELAY_ACCESS_KEY` (Node) or the Worker secret of the same name to a long
random value (for example `openssl rand -base64 32`). Every request, health
check included, must then carry it in an `X-Relay-Key` header, or gets 403.
Nobody else can store anything on the relay.

The key is off by default because the apps do not send this header yet:
with the key set, they report "not an Encave relay" when checking the address,
and refused access when syncing. The plan is an optional field next to the
relay address in **Settings › Devices › Sync away from home**, kept in the
Keychain or the Android Keystore and sent as `X-Relay-Key` with every request.
Until then, a relay the apps use runs without the key and relies on the
limits above.

Mailboxes not written to for a year are deleted, so a relay is a mailbox, not a
backup. Keep backups with **Back Up Now** in **Settings › Your data**.

## Run it on your own server (Node)

Node 20 or later, no dependencies:

```sh
PORT=8787 DATA_DIR=/var/lib/encave-relay node relay/server.mjs
```

Put it behind HTTPS, for example with Caddy (and set `TRUST_PROXY=1`):

```
relay.example.org {
    reverse_proxy localhost:8787
}
```

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8787 | Port to listen on |
| `DATA_DIR` | `./data` | Where files are kept |
| `TTL_DAYS` | 365 | Mailboxes not written to for this long are deleted |
| `RATE_PER_MINUTE` | 300 | Requests per minute per client |
| `DAILY_UPLOAD_MB` | 2048 | Bytes a client may upload a day |
| `MAX_STORAGE_MB` | 10240 | Total size of all files on the relay |
| `MAX_MAILBOXES` | 1000 | Number of mailboxes (cellars and inboxes) on the relay |
| `MIN_FREE_MB` | 1024 | Writes stop when the disk has less free space |
| `RELAY_ACCESS_KEY` | unset | When set, every request must carry it in `X-Relay-Key` |
| `TRUST_PROXY` | unset | Set to `1` behind a reverse proxy, so limits apply per client (the last `X-Forwarded-For` entry, which the proxy adds) |

### With Docker

```sh
docker build -t encave-relay relay
docker run -d --restart unless-stopped -p 8787:8787 -v encave-relay:/data encave-relay
```

## Run it on Cloudflare (free tier)

1. Install Wrangler and sign in: `npx wrangler login`.
2. Create the bucket: `npx wrangler r2 bucket create encave-relay`.
3. Deploy: `npx wrangler deploy --config relay/cloudflare/wrangler.toml`.
4. Optionally, for a private relay:
   `npx wrangler secret put RELAY_ACCESS_KEY --config relay/cloudflare/wrangler.toml`.

The rate limiter (the `[[ratelimits]]` binding in `wrangler.toml`) is on by
default. If your account refuses the binding when you deploy, delete those
lines: the relay then runs without a per-client limit, so consider the access
key, and watch R2 usage in the dashboard.

A scheduled run every 15 minutes (the cron trigger in `wrangler.toml`) deletes
mailboxes not written to for `TTL_DAYS` (365). Each run makes at most 45 R2
calls, under the Free plan's 50 per invocation, and the next run carries on
where it stopped. A full pass also counts the relay's mailboxes and bytes:
`MAX_MAILBOXES` and `MAX_STORAGE_MB` are checked against the totals of the
last full pass (none before the first), so they are approximate. Do not add an
R2 lifecycle rule: it would delete each file a year after it was written,
including photos still in use.

The free tier covers a household many times over: as of October 2026 it includes
100,000 requests a day and 10 GB of R2 storage.

## Tests

```sh
node --test relay/test/relay.test.mjs relay/test/worker.test.mjs
```

The CI also syncs two simulated devices through a running relay
(`.github/workflows/relay.yml`).
